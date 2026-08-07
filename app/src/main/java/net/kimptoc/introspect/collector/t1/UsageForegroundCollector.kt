package net.kimptoc.introspect.collector.t1

import android.app.usage.UsageStatsManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Per-package foreground-time delta since this collector's last successful
 * run, and last-used (spec §3 T1). Gated to run at most every 30 minutes
 * (spec §4's reconciliation cadence) rather than every collection cycle —
 * this device has hundreds of installed packages, so a per-cycle snapshot
 * at 60s would be a real storage-growth problem (spec §7).
 *
 * `UsageStatsManager.queryUsageStats(INTERVAL_BEST, …)` does not return a
 * true delta for an arbitrary custom window — in practice it snaps to
 * Android's own coarser stored buckets (daily), so the raw
 * `totalTimeInForeground` is a cumulative "so far today" figure that
 * resets at local midnight, not "time in foreground during this cycle".
 * This collector diffs against a stored per-package baseline itself to
 * get a real per-cycle delta, and treats a drop below the stored baseline
 * as a fresh count (the midnight reset) rather than a negative delta.
 */
class UsageForegroundCollector : Collector {
    override val id = "usage_foreground"
    override val tier = Tier.T1

    private val prefsName = "usage_foreground_collector"
    private val lastRunKey = "last_run_timestamp"
    private val baselinePrefix = "baseline_"
    private val intervalMs = 30 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = UsageAccess.isGranted(context)

    override fun collect(context: Context): List<Sample> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun < intervalMs) return emptyList()

        val windowStart = if (lastRun > 0L) lastRun else now - intervalMs
        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, windowStart, now)
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: RuntimeException) {
            return emptyList()
        }

        val prefsEditor = prefs.edit()
        val samples = mutableListOf<Sample>()

        for (stat in stats) {
            if (stat.totalTimeInForeground <= 0) continue
            val baselineKey = baselinePrefix + stat.packageName
            val baseline = prefs.getLong(baselineKey, -1L)
            prefsEditor.putLong(baselineKey, stat.totalTimeInForeground)

            if (baseline < 0L) {
                // First time we've seen this package: no prior baseline to
                // diff against, so skip rather than emit a value that's
                // really "cumulative until now", not a per-cycle delta.
                continue
            }

            val delta = if (stat.totalTimeInForeground >= baseline) {
                stat.totalTimeInForeground - baseline
            } else {
                // Local-midnight bucket reset: the new cumulative value is
                // itself the delta since the reset, not since our baseline.
                stat.totalTimeInForeground
            }
            if (delta <= 0) continue

            samples += Sample(
                timestamp = now,
                collectorId = id,
                key = stat.packageName,
                valueNum = delta.toDouble(),
                valueText = "last_used_ms=${stat.lastTimeUsed}",
            )
        }

        prefsEditor.putLong(lastRunKey, now)
        prefsEditor.apply()
        return samples
    }
}
