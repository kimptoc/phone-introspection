package net.kimptoc.introspect.collector.t3

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
class CpuInfoCollector : DumpsysCollector() {
    override val id = "cpuinfo"
    override val service = "cpuinfo"
    override val intervalMs = 60 * 60 * 1000L
    override val maxChars = 50_000
}
