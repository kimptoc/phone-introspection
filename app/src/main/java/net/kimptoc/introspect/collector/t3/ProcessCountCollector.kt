package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
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
 * Not a [DumpsysCollector] subclass despite living in this package: that
 * base class is welded to [ShizukuManager.dumpsys] (raw-text capture of one
 * `dumpsys` service per collector). This signal wants a parsed number out of
 * `ps -A`, which gets its own dedicated `IDumpsysService.processCount()`
 * call instead - and the raw listing (~100KB per call) is deliberately NOT
 * stored: only the count has Timeline value, and raw text at this cadence
 * would grow ~10MB/day (spec §7's storage caution), unlike the capped
 * dumpsys captures which stay bounded by design.
 *
 * Gated to 15 minutes, matching [net.kimptoc.introspect.collector.t0.MemoryCollector],
 * its investigation partner - process count is read alongside memory
 * pressure for the bloat investigation theme, and `ps -A` is cheap
 * (~100ms). 96 rows/day of one number is negligible against the retention
 * window.
 *
 * The [DumpsysResult.NotBoundYet] handling mirrors [DumpsysCollector]
 * exactly: don't burn the gate on a transient bind-in-progress state, let
 * the next tick retry, and emit a status row meanwhile so the wait is
 * visible in the data rather than silent (spec §2).
 */
class ProcessCountCollector : Collector {
    override val id = "process_count"
    override val tier = Tier.T3

    private val prefsName = "process_count_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 15 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = ShizukuManager.processCount()

        if (result is DumpsysResult.NotBoundYet) {
            return listOf(Sample(now, id, "count_status", valueNum = 0.0, valueText = "not_bound_yet"))
        }
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (result) {
            is DumpsysResult.Success -> {
                val count = result.text.trim().toDoubleOrNull()
                if (count != null) {
                    listOf(Sample(now, id, "process_count", valueNum = count))
                } else {
                    // Unreachable while DumpsysService owns this call's
                    // output format, but a parse failure must surface as a
                    // visible status row, not a null-valued sample (spec §3:
                    // treat a parse failure as a missing sample).
                    listOf(Sample(now, id, "count_status", valueNum = 0.0, valueText = "unparseable: ${result.text.take(80)}"))
                }
            }
            is DumpsysResult.NotPermitted -> listOf(
                Sample(now, id, "count_status", valueNum = 0.0, valueText = "not_permitted"),
            )
            is DumpsysResult.Error -> listOf(
                Sample(now, id, "count_status", valueNum = 0.0, valueText = result.detail),
            )
            is DumpsysResult.NotBoundYet -> emptyList() // unreachable, handled above
        }
    }
}
