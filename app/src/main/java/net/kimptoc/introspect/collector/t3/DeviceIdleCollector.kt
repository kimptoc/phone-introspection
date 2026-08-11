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
 * Only ~17,000 characters on this device (checked on-device before
 * picking a cap, same as the other T3 collectors), so [maxChars] is set
 * with real headroom above that, not just past it - the whole point of
 * this collector is the live state fields at the *tail* of the dump
 * (`mState`, `mScreenOn`, `mCharging`, `mNextAlarmTime`), and
 * [DumpsysService]'s cap keeps the *head*. A cap sized close to the
 * measured length would silently amputate exactly the content this
 * collector exists to capture the moment the dump grows (more idling-
 * history entries, more allowlisted apps) past it - "dump" would still
 * look like a normal success, just missing the one section that
 * matters. [truncated] makes that condition detectable instead of
 * silent if it ever happens anyway. Hourly cadence, matching
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
    private val maxChars = 60_000

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
            is DumpsysResult.Success -> {
                val samples = mutableListOf(
                    Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
                )
                // DumpsysService returns exactly maxChars when the cap was
                // hit (it substrings to that length), or fewer if the dump
                // was genuinely smaller - so equality is a reliable signal
                // the tail state fields this collector exists for were cut
                // off, not just "a big dump happened to land on this size."
                if (result.text.length >= maxChars) {
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
