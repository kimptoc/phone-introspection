package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Raw `dumpsys cpuinfo` output (spec §3 T3) — system-wide CPU load by
 * process, load averages, and a `TOTAL` breakdown by user/kernel/iowait/
 * irq/softirq. Complements [BatteryStatsCollector]'s cumulative
 * since-charge CPU time with a point-in-time load snapshot instead - the
 * two answer different questions ("what's used the most CPU overall" vs
 * "what's using CPU right now"), directly useful for the memory/process-
 * bloat investigation theme in STATUS.md (2026-08-07's 1,026-process
 * finding had no CPU-load data alongside it at the time).
 *
 * Checked the real dump on-device before picking [maxChars] (spec §7):
 * a consistent 16,990 characters across three consecutive samples on
 * this device, so 50,000 leaves roughly 3x headroom - matching
 * [DeviceIdleCollector]'s margin for a similarly-sized dump. The dump's
 * own closing `TOTAL` line is a meaningful summary value, not filler, so
 * headroom here isn't just about the process list growing - it's about
 * not silently losing that line too. Hourly cadence, matching
 * [SensorServiceCollector]/[DeviceIdleCollector]: a true CPU-spike
 * catcher would need much finer resolution than this, but that's not
 * this collector's job (T0 already tracks instantaneous current draw at
 * full frequency) - this is a periodic point-in-time snapshot for
 * correlating with process/memory investigations, the same role
 * `sensorservice` and `deviceidle` play for their respective domains.
 */
class CpuInfoCollector : Collector {
    override val id = "cpuinfo"
    override val tier = Tier.T3

    private val prefsName = "cpuinfo_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 60 * 60 * 1000L
    private val maxChars = 50_000

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = ShizukuManager.dumpsys("cpuinfo", maxChars = maxChars)

        // NotBoundYet is a one-time startup condition, not a completed
        // attempt (same reasoning as the other T3 collectors) - don't
        // spend the hourly gate waiting on a bind that normally finishes
        // in seconds.
        if (result is DumpsysResult.NotBoundYet) {
            return listOf(Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_bound_yet"))
        }
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (result) {
            is DumpsysResult.Success -> {
                val samples = mutableListOf(
                    Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
                )
                // result.truncated is computed by DumpsysService against the
                // real pre-truncation length (see DeviceIdleCollector's
                // three-round history on why this can't be re-derived from
                // the returned string's own length downstream).
                if (result.truncated) {
                    samples += Sample(now, id, "dump_status", valueNum = 1.0, valueText = "truncated")
                }
                samples
            }
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
