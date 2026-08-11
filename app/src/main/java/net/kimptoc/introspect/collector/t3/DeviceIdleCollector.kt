package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Raw `dumpsys deviceidle` output (spec §3 T3) — Doze state machine
 * detail, allowlists, and idling history, directly relevant to this
 * project's recurring Doze-engagement investigation (STATUS.md tracks
 * Doze engagement percentage as a key drain-rate indicator throughout).
 * Complements [net.kimptoc.introspect.collector.t0.DozeScreenCollector]'s
 * cheap `PowerManager.isDeviceIdleMode()` polling rather than replacing
 * it: that one is free and frequent, this one is a periodic deep
 * snapshot with structural detail Android doesn't expose through any
 * public API (`mState`/`mLightState`, `mNextAlarmTime`, the temp
 * allowlist, and the idling-history log of deep/light transitions).
 *
 * Only ~17,000 characters on this device, well under the 20,000 char cap
 * (checked on-device before picking it, same as the other T3 collectors)
 * — small enough that the whole dump fits, including the live state
 * fields at the tail (`mState`, `mScreenOn`, `mCharging`,
 * `mNextAlarmTime`), not just a head-truncated fragment the way
 * `batterystats`' much larger dump needs. Hourly cadence, matching
 * [SensorServiceCollector]: the structural detail here doesn't need
 * finer resolution than that, and the cheap T0 doze collector already
 * covers on/off transitions at full frequency.
 */
class DeviceIdleCollector : Collector {
    override val id = "deviceidle"
    override val tier = Tier.T3

    private val prefsName = "deviceidle_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 60 * 60 * 1000L
    private val maxChars = 20_000

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = ShizukuManager.dumpsys("deviceidle", maxChars = maxChars)

        // NotBoundYet is a one-time startup condition, not a completed
        // attempt (same reasoning as the other T3 collectors) - don't
        // spend the hourly gate waiting on a bind that normally finishes
        // in seconds.
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
