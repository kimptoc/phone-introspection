package net.kimptoc.introspect.collector.t3

import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * A [ShizukuUserServiceCollector] that wraps a single raw-text `dumpsys`
 * call - spec §3 T3's dumpsys row, e.g. `dumpsys cpuinfo`. Adds the
 * dumpsys-specific knobs ([service], [args], [maxChars] - the last tuned
 * per collector against the real dump's on-device size, spec §7's
 * decide-deliberately discipline) and the raw-capture success mapping:
 * the dump text stored under `dump` with its length, plus a `truncated`
 * status row when [DumpsysResult.Success.truncated] is set.
 *
 * `truncated` is computed by `DumpsysService` against the real
 * pre-truncation length and can't be re-derived downstream from the
 * returned string's own length (see [DeviceIdleCollector]'s KDoc for the
 * boundary-bug history) - [transform] just trusts the flag. Whether
 * `truncated` shows up as a rare exception or the permanent steady state
 * depends on the subclass's [maxChars] vs. the real dump size:
 * [DeviceIdleCollector]/[CpuInfoCollector] pick a cap with headroom, so it
 * should stay false; [SensorServiceCollector]/[BatteryStatsCollector]/
 * [PowerCollector] deliberately cap well below their multi-hundred-KB real
 * dumps, so `truncated=true` on every single cycle is the expected,
 * permanent state for those, not a signal something's wrong.
 *
 * Everything this class does NOT own - the watermark gate, the
 * [DumpsysResult.NotBoundYet] early-return, the NotPermitted/Error status
 * mapping, [isAvailable] - lives in [ShizukuUserServiceCollector], shared
 * with [ProcessCountCollector]'s non-dumpsys `ps -A` call (see that
 * class's KDoc for the extraction history).
 */
abstract class DumpsysCollector : ShizukuUserServiceCollector() {
    /** The `dumpsys <service>` argument, e.g. `"sensorservice"`. */
    protected abstract val service: String
    protected open val args: Array<String> = emptyArray()

    /** Character cap for the captured dump, chosen per service. */
    protected abstract val maxChars: Int

    override fun call(): DumpsysResult =
        ShizukuManager.dumpsys(service, args = args, timeoutMs = timeoutMs, maxChars = maxChars)

    override fun transform(now: Long, result: DumpsysResult.Success): List<Sample> {
        val samples = mutableListOf(
            Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
        )
        if (result.truncated) {
            samples += Sample(now, id, statusKey, valueNum = 1.0, valueText = "truncated")
        }
        return samples
    }
}
