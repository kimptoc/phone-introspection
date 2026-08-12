package net.kimptoc.introspect.collector.t3

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
class BatteryStatsCollector : DumpsysCollector() {
    override val id = "batterystats"
    override val service = "batterystats"
    override val args = arrayOf("--charged")
    override val intervalMs = 2 * 60 * 60 * 1000L
    override val maxChars = 150_000
    override val timeoutMs = 10_000
}
