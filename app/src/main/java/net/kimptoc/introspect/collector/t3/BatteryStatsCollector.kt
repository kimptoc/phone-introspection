package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Raw `dumpsys batterystats --charged` output (spec §3 T3) — historical
 * per-UID wakelocks, CPU time and kernel wake lock attribution, none of
 * which [net.kimptoc.introspect.collector.t2.BatteryAttributionCollector]'s
 * `BatteryStatsManager` reflection surface exposes (that one only gives a
 * flat mAh figure per UID, no wakelock/CPU breakdown). This is the
 * signal spec.md's T3 row names first ("Historical per-UID wakelocks,
 * CPU time, sensor time").
 *
 * `--charged` (~550KB on this device) instead of the unflagged dump
 * (~1.7MB) — scopes to the current discharge cycle, which is the
 * relevant window for "what's draining the battery right now" anyway,
 * and is already a meaningfully smaller dump to cap from.
 *
 * [maxChars] is far larger than [SensorServiceCollector]'s: checked the
 * real dump's structure on-device before picking it (spec §7 says decide
 * the cap deliberately, not after seeing the DB size) — the per-UID power
 * summary and kernel wake lock sections both live in roughly the first
 * 150,000 characters of a `--charged` dump on this device, after which it
 * moves into likely-lower-value per-UID sub-tables. Runs every 2 hours,
 * not [SensorServiceCollector]'s hourly: a bigger cap needs a slower
 * cadence to keep storage growth in check (spec §7 names this signal by
 * name as the real space consumer among dumpsys sources).
 */
class BatteryStatsCollector : Collector {
    override val id = "batterystats"
    override val tier = Tier.T3

    private val prefsName = "batterystats_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 2 * 60 * 60 * 1000L
    private val maxChars = 150_000
    private val timeoutMs = 10_000

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = ShizukuManager.dumpsys("batterystats", args = arrayOf("--charged"), timeoutMs = timeoutMs, maxChars = maxChars)

        // NotBoundYet is a one-time startup condition, not a completed
        // attempt (same reasoning as SensorServiceCollector) - don't spend
        // the 2-hour gate waiting on a bind that normally finishes in
        // seconds.
        if (result is DumpsysResult.NotBoundYet) {
            return listOf(Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_bound_yet"))
        }
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (result) {
            is DumpsysResult.Success -> listOf(
                Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
            )
            is DumpsysResult.NotPermitted -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_permitted"),
            )
            is DumpsysResult.Error -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = result.detail),
            )
            is DumpsysResult.NotBoundYet -> emptyList() // unreachable, handled above
        }
    }
}
