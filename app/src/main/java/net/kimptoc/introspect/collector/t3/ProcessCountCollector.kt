package net.kimptoc.introspect.collector.t3

import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * System-wide running-process count (issue #27, spec §3 T3's `ps -A`
 * "Real process list" row) - the continuous signal behind STATUS.md's
 * 2026-08-07 1,026-process finding, which was a one-off hand-run reading
 * with nothing tracking the number over time, and the third of the three
 * signals issue #24 asked for on the Timeline (temperature and memory
 * shipped in PR #25; the count was deferred as the hard part).
 *
 * Shares [ShizukuUserServiceCollector]'s gate/status plumbing with the
 * dumpsys collectors but is NOT a [DumpsysCollector]: that class exists
 * for raw-text `dumpsys` captures (service/args/maxChars knobs + the
 * `truncated` flag), whereas this signal wants a parsed number out of
 * `ps -A`, executed by the same UserService via a dedicated
 * [net.kimptoc.introspect.shizuku.IDumpsysService] method. The raw listing
 * (~100KB per call) is deliberately not stored - only the count has
 * Timeline value, and raw text at this cadence would grow ~10MB/day
 * (spec §7's storage caution), unlike the capped dumpsys captures which
 * stay bounded by design.
 *
 * Gated to 15 minutes, matching [net.kimptoc.introspect.collector.t0.MemoryCollector],
 * its investigation partner - process count is read alongside memory
 * pressure for the bloat investigation theme, and `ps -A` is cheap
 * (~100ms). 96 rows/day of one number is negligible against the retention
 * window.
 */
class ProcessCountCollector : ShizukuUserServiceCollector() {
    override val id = "process_count"

    override val intervalMs = 15 * 60 * 1000L

    override val statusKey = "count_status"

    override fun call(): DumpsysResult = ShizukuManager.processCount()

    override fun transform(now: Long, result: DumpsysResult.Success): List<Sample> {
        val count = result.text.trim().toDoubleOrNull()
        return if (count != null) {
            listOf(Sample(now, id, "process_count", valueNum = count))
        } else {
            // Unreachable while DumpsysService owns this call's output
            // format, but a parse failure must surface as a visible status
            // row, not a null-valued sample (spec §3: treat a parse failure
            // as a missing sample).
            listOf(Sample(now, id, statusKey, valueNum = 0.0, valueText = "unparseable: ${result.text.take(80)}"))
        }
    }
}
