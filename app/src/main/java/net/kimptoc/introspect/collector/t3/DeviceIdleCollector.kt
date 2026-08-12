package net.kimptoc.introspect.collector.t3

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
 * matters. [DumpsysCollector]'s truncated-flag handling makes that
 * condition detectable instead of silent if it ever happens anyway -
 * see [DumpsysService] for the three-round history of getting that
 * detection actually correct at the boundary. Hourly cadence, matching
 * [SensorServiceCollector]: the structural detail here doesn't need
 * finer resolution than that, and the cheap T0 doze collector already
 * covers on/off transitions at full frequency.
 */
class DeviceIdleCollector : DumpsysCollector() {
    override val id = "deviceidle"
    override val service = "deviceidle"
    override val intervalMs = 60 * 60 * 1000L
    override val maxChars = 60_000
}
