package net.kimptoc.introspect.collector.t0

import android.content.Context
import android.os.StatFs
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Free internal storage. Not in spec.md's original signal tables — added
 * after Samsung support flagged <10% free storage as a potential
 * contributor to instability/overheating. No permission needed.
 * Gated to run at most every 30 minutes, matching the cadence already
 * used by the T1 aggregate collectors: storage moves slowly, and it'd be
 * a little ironic to add unnecessary rows while investigating a
 * storage-pressure issue.
 */
class StorageCollector : Collector {
    override val id = "storage"
    override val tier = Tier.T0

    private val prefsName = "storage_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 30 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun < intervalMs) return emptyList()

        val statFs = StatFs(context.filesDir.path)
        val totalBytes = statFs.totalBytes
        val availableBytes = statFs.availableBytes
        val availablePct = if (totalBytes > 0) 100.0 * availableBytes / totalBytes else 0.0

        prefs.edit().putLong(lastRunKey, now).apply()

        return listOf(
            Sample(now, id, "total_bytes", valueNum = totalBytes.toDouble()),
            Sample(now, id, "available_bytes", valueNum = availableBytes.toDouble()),
            Sample(now, id, "available_pct", valueNum = availablePct),
        )
    }
}
