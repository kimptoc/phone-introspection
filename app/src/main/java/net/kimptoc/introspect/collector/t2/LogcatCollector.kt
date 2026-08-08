package net.kimptoc.introspect.collector.t2

import android.content.Context
import android.content.pm.PackageManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Logcat as a side channel on system behaviour (spec §3 T2) — thermal
 * daemon, low-memory-killer, JobScheduler and Doze/ANR decisions, none of
 * which are otherwise queryable from an app. `READ_LOGS` (adb-granted,
 * scripts/provision.sh) is a plain permission check, not a hidden-API
 * problem like [BatteryAttributionCollector] — without it, `logcat` only
 * returns this app's own output, not other processes'.
 *
 * `logcat -T '<MM-DD HH:MM:SS.mmm>'` *is* a durable offset — it returns
 * only lines at or after that timestamp, so the watermark stored in
 * [lastLogTimeKey] survives across cycles the same way a query watermark
 * would elsewhere in this app. It's not exact: `-T` can repeat the exact
 * boundary line, so the previous cycle's last raw line is also tracked
 * ([lastLineKey]) and dropped if it reappears first. Runs every
 * [intervalMs] = 5 minutes rather than this app's usual 30, since the
 * main buffer on a busy phone can wrap well inside 30 minutes.
 *
 * Matches are joined into one length-capped row per cycle rather than one
 * row per line, per spec §7's storage-growth caution about logcat/dumpsys
 * raw text specifically. None of this app's own logging currently uses
 * any of [keywords] as of writing — if that changes, this collector would
 * start capturing its own output.
 */
class LogcatCollector : Collector {
    override val id = "logcat"
    override val tier = Tier.T2

    private val prefsName = "logcat_collector"
    private val lastRunKey = "last_run_timestamp"
    private val lastLogTimeKey = "last_log_time"
    private val lastLineKey = "last_line_seen"
    private val intervalMs = 5 * 60 * 1000L
    private val initialFetchLines = 500
    private val maxJoinedChars = 4000
    private val permission = "android.permission.READ_LOGS"
    private val timePrefixLength = 18 // "MM-DD HH:MM:SS.mmm"

    private val keywords = listOf(
        "thermal", "lowmemorykiller", "lmkd", "out of memory", "oom",
        "jobscheduler", "doze", "idle_maintenance", "powermanagerservice",
        "killing ", "anr in", "not responding",
    )

    override fun isAvailable(context: Context): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()
        // Advance the 5-minute gate unconditionally, success or failure -
        // a persistent failure (SELinux denial, exec unavailable) must not
        // retry every sampling tick forever.
        prefs.edit().putLong(lastRunKey, now).apply()

        val lastLogTime = prefs.getString(lastLogTimeKey, null)
        val result = try {
            readLogcat(lastLogTime)
        } catch (e: IOException) {
            return listOf(Sample(now, id, "read_status", valueNum = 0.0, valueText = e.javaClass.simpleName))
        }

        if (result.exitCode != 0) {
            val detail = result.stderr.take(200).ifBlank { "exit=${result.exitCode}" }
            return listOf(Sample(now, id, "read_status", valueNum = 0.0, valueText = detail))
        }

        val lines = result.lines
        if (lines.isEmpty()) {
            return listOf(Sample(now, id, "read_status", valueNum = 0.0, valueText = "ok"))
        }

        val lastSeenLine = prefs.getString(lastLineKey, null)
        val freshLines = if (lastLogTime != null && lines.first() == lastSeenLine) {
            lines.drop(1)
        } else {
            lines
        }

        val editor = prefs.edit()
        editor.putString(lastLineKey, lines.last())
        val newWatermark = lines.last().take(timePrefixLength)
        if (newWatermark.length == timePrefixLength) {
            editor.putString(lastLogTimeKey, newWatermark)
        }
        editor.apply()

        val matches = freshLines.filter { line ->
            val lower = line.lowercase()
            keywords.any { lower.contains(it) }
        }

        if (matches.isEmpty()) {
            return listOf(Sample(now, id, "read_status", valueNum = 0.0, valueText = "ok"))
        }

        val joined = matches.joinToString("\n").takeLast(maxJoinedChars)
        return listOf(Sample(now, id, "matches", valueNum = matches.size.toDouble(), valueText = joined))
    }

    private data class LogcatResult(val lines: List<String>, val exitCode: Int, val stderr: String)

    private fun readLogcat(sinceTime: String?): LogcatResult {
        val command = if (sinceTime != null) {
            listOf("logcat", "-d", "-v", "time", "-T", sinceTime)
        } else {
            listOf("logcat", "-d", "-v", "time", "-t", initialFetchLines.toString())
        }
        val process = ProcessBuilder(command).start()
        try {
            val lines = BufferedReader(InputStreamReader(process.inputStream)).readLines()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            return LogcatResult(lines, exitCode, stderr)
        } finally {
            process.destroyForcibly()
        }
    }
}
