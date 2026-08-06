package net.kimptoc.introspect.collector.t1

import android.app.usage.UsageStatsManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Per-package foreground time and last-used, snapshotted (spec §3 T1).
 * Gated to run at most every 30 minutes (spec §4's reconciliation cadence)
 * rather than every collection cycle — this device has hundreds of
 * installed packages, so a per-cycle snapshot at 60s would be a real
 * storage-growth problem (spec §7).
 */
class UsageForegroundCollector : Collector {
    override val id = "usage_foreground"
    override val tier = Tier.T1

    private val prefsName = "usage_foreground_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 30 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = UsageAccess.isGranted(context)

    override fun collect(context: Context): List<Sample> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun < intervalMs) return emptyList()

        val windowStart = now - intervalMs
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, windowStart, now)

        val samples = stats
            .filter { it.totalTimeInForeground > 0 }
            .map { stat ->
                Sample(
                    timestamp = now,
                    collectorId = id,
                    key = stat.packageName,
                    valueNum = stat.totalTimeInForeground.toDouble(),
                    valueText = "last_used_ms=${stat.lastTimeUsed}",
                )
            }

        prefs.edit().putLong(lastRunKey, now).apply()
        return samples
    }
}
