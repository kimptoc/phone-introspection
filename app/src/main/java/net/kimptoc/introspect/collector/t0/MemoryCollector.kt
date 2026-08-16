package net.kimptoc.introspect.collector.t0

import android.app.ActivityManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * System-wide memory pressure (spec §3 T0, `ActivityManager.getMemoryInfo()`).
 * No permission needed. This is the coarse, always-available signal -
 * total/available RAM and the OS's own low-memory threshold and flag -
 * not per-process attribution; that needs `dumpsys meminfo` (T3, Shizuku)
 * and is out of scope here (see issue #24 and STATUS.md's 2026-08-07
 * memory/process-bloat investigation, which found that level of detail
 * useful but had to read it by hand).
 *
 * Gated to run at most every 15 minutes, matching the `SamplingWorker`
 * fallback cadence: memory pressure moves faster than storage
 * ([StorageCollector]'s 30-min gate) but a "coarse" signal doesn't need
 * this app's full 60s periodic-tick resolution either - that would be
 * 96x the row volume for a number that's still meaningful sampled far
 * less often.
 */
class MemoryCollector : Collector {
    override val id = "memory"
    override val tier = Tier.T0

    private val prefsName = "memory_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 15 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        // A negative delta means the wall clock jumped backward (reboot with a
        // stale RTC, NTP correction) - treat that as "run now", not "block
        // until the clock crawls back up to lastRun" (matches StorageCollector).
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        if (memInfo.totalMem <= 0) return emptyList()

        val availPct = 100.0 * memInfo.availMem / memInfo.totalMem

        prefs.edit().putLong(lastRunKey, now).apply()

        return listOf(
            Sample(now, id, "avail_mem_bytes", valueNum = memInfo.availMem.toDouble()),
            Sample(now, id, "total_mem_bytes", valueNum = memInfo.totalMem.toDouble()),
            Sample(now, id, "threshold_bytes", valueNum = memInfo.threshold.toDouble()),
            Sample(now, id, "avail_pct", valueNum = availPct),
            Sample(now, id, "low_memory", valueText = memInfo.lowMemory.toString()),
        )
    }
}
