package net.kimptoc.introspect.collector.t1

import android.app.usage.UsageStatsManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import java.time.Instant
import java.time.ZoneId

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
 * get a real per-cycle delta. A drop below the stored baseline is only
 * trusted as the midnight reset if the query window actually crosses a
 * local-midnight boundary — the same drop can also mean an app data
 * clear, an uninstall/reinstall, or the OS pruning its usage stats, none
 * of which should be reported as a burst of new foreground time. Deltas
 * are clamped to the wall-clock time elapsed since the last run, since
 * foreground time can never exceed real elapsed time.
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
        val elapsedMs = now - windowStart
        val windowCrossesMidnight = crossesLocalMidnight(windowStart, now)

        for (stat in stats.orEmpty()) {
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

            val rawDelta = if (stat.totalTimeInForeground >= baseline) {
                stat.totalTimeInForeground - baseline
            } else if (windowCrossesMidnight) {
                // Local-midnight bucket reset: the new cumulative value is
                // itself the delta since the reset, not since our baseline.
                stat.totalTimeInForeground
            } else {
                // Drop with no midnight crossing to explain it: data clear,
                // uninstall/reinstall, or an OS stats purge. The baseline
                // above has already been re-seeded to resync; don't emit a
                // value we can't trust as real new foreground time.
                continue
            }

            val delta = minOf(rawDelta, elapsedMs)
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

    private fun crossesLocalMidnight(start: Long, end: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        return startDate != endDate
    }
}
