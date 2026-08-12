package net.kimptoc.introspect.collector.t3

/**
 * Raw `dumpsys power` output (spec §3 T3) — the last named T3 signal in
 * spec.md's dumpsys table ("Wakelock holders"). Leads with live
 * `PowerManagerService` state (`mWakefulness`, doze/idle flags, the doze
 * allowlists) followed by the `Wake Locks:` and `Suspend Blockers:`
 * sections — currently-held wake locks by owner, uid, and how long each
 * has been held, which is the classic signature of a runaway
 * wakelock-driven drain that [BatteryStatsCollector]'s cumulative
 * since-charge view can name a culprit for only after the fact.
 *
 * Checked the real dump on-device before picking [maxChars] (spec §7):
 * ~449,776 characters total, but the `Wake Locks`/`Suspend Blockers`
 * sections that matter here are both fully contained in the first
 * ~13,100 characters - the rest is display/policy/history state this
 * collector isn't after. [maxChars] leaves >2x headroom over that,
 * matching [BatteryStatsCollector]'s reasoning for capping well below a
 * multi-hundred-KB dump rather than trying to capture all of it: this
 * dump is permanently truncated by design, same as `sensorservice`/
 * `batterystats` (see [DumpsysCollector]'s note on `truncated` being the
 * expected steady state for head-heavy, far-over-cap dumps like this
 * one). `timeoutMs` matches [BatteryStatsCollector]'s, since fully
 * draining a ~450KB dump - even though only the head is kept - needs the
 * same generous allowance. Hourly cadence, matching the other
 * point-in-time T3 snapshots ([CpuInfoCollector], [DeviceIdleCollector]):
 * this is "what's holding a wake lock right now", not a cumulative
 * history that needs its own accumulation window.
 */
class PowerCollector : DumpsysCollector() {
    override val id = "power"
    override val service = "power"
    override val intervalMs = 60 * 60 * 1000L
    override val maxChars = 30_000
    override val timeoutMs = 10_000
}
