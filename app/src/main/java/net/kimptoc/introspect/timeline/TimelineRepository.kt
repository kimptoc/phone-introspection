package net.kimptoc.introspect.timeline

import android.content.Context
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.db.TimestampNum
import net.kimptoc.introspect.db.TimestampText

/** One value held from [startMs] until [endMs] - a step-function segment. */
data class TimelineSegment<T>(val startMs: Long, val endMs: Long, val value: T)

/** One foreground session for [packageName], derived from `usage_events`. */
data class AppSession(val packageName: String, val startMs: Long, val endMs: Long)

/**
 * Range-scoped queries plus derived data (app sessions, categorical
 * segments) for [net.kimptoc.introspect.timeline.TimelineActivity]. Every
 * method is scoped to an explicit (startMs, endMs) window - the `samples`
 * table is 400K+ rows and growing, so nothing here ever loads it whole
 * (spec §7's storage-growth caution applies to reading it back out too,
 * not just writing it) - **except [loadAppSessions]**: `usage_events` has
 * no bucketed/downsampled query variant (unlike [loadBattery]/[loadThermal]/
 * [loadDeviceIdle]/[loadScreenOn], which bucket via [bucketMsFor] at wide
 * ranges), so at ALL_TIME it loads every usage_events row in the table.
 * Known growth risk, not fixed here - a downsampled or capped session
 * query is future work, not in scope for this pass.
 */
class TimelineRepository(private val context: Context) {

    private val dao get() = AppDatabase.get(context).sampleDao()

    /** Target point count for downsampled ranges - keeps render cost flat regardless of dataset size. */
    private val targetPoints = 500

    suspend fun resolveRange(range: TimelineRange): Pair<Long, Long> {
        val endMs = System.currentTimeMillis()
        val startMs = when (range) {
            TimelineRange.LAST_24H -> endMs - 24 * 60 * 60 * 1000L
            TimelineRange.LAST_3D -> endMs - 3 * 24 * 60 * 60 * 1000L
            TimelineRange.LAST_7D -> endMs - 7 * 24 * 60 * 60 * 1000L
            TimelineRange.ALL_TIME -> dao.earliestTimestamp() ?: endMs
        }
        return startMs to endMs
    }

    suspend fun loadBattery(startMs: Long, endMs: Long): List<TimestampNum> =
        loadNumeric("battery", "level_pct", startMs, endMs)

    suspend fun loadTemperature(startMs: Long, endMs: Long): List<TimestampNum> =
        loadNumeric("battery", "temperature_c", startMs, endMs)

    /** Coarse system-wide memory pressure (issue #24) - see [net.kimptoc.introspect.collector.t0.MemoryCollector]. */
    suspend fun loadMemoryAvailPct(startMs: Long, endMs: Long): List<TimestampNum> =
        loadNumeric("memory", "avail_pct", startMs, endMs)

    suspend fun loadThermal(startMs: Long, endMs: Long): List<TimelineSegment<String>> =
        loadText("thermal", "status", startMs, endMs).toSegments(endMs) { it ?: "unknown" }

    suspend fun loadDeviceIdle(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "device_idle", startMs, endMs).toSegments(endMs) { it == "true" }

    suspend fun loadScreenOn(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "screen_on", startMs, endMs).toSegments(endMs) { it == "true" }

    /**
     * Sessions still open at [endMs] are capped at the last real evidence
     * of *any* activity ([SampleDao.lastTimestamp]), not blindly at
     * [endMs] (bot review round 3 on PR #21) - a dangling `activity_resumed`
     * with no closing event anywhere in the dataset means monitoring
     * stopped, not that the app stayed foregrounded for however long the
     * subsequent gap happens to be. This cap is a no-op whenever monitoring
     * is actually live near [endMs] (`lastTimestamp` ≈ `endMs` then), and
     * it applies uniformly - round 2's fix only gated the *lookback* by
     * comparing `lastTimestamp` against [startMs], which caught a dangling
     * resume when it fell *before* the range (as in the original on-device
     * finding) but missed the identical case when a wider range (e.g. 7d
     * vs. 24h) pulled the same dangling resume *inside* the window, where
     * it's processed as a normal in-range event with nothing gating its
     * endMs-cap at all.
     *
     * A session already open when the range *begins* is seeded from
     * [SampleDao.lastUsageEventBeforeRange], symmetric with the endMs case
     * - both edges clip an unknown true boundary to the visible range
     * rather than pretending the session doesn't exist. Also only trusted
     * when [SampleDao.lastTimestamp] is at or past [startMs], for the same
     * reason as the endMs cap.
     *
     * A resume that arrives while one is already open for that package
     * (no intervening pause/stop - e.g. the process died) closes the prior
     * session at that point rather than merging both episodes into one
     * session spanning a gap where the app wasn't actually foregrounded.
     *
     * [lastKnownOpen] tracks, per package, whether the most recently
     * established state was open or closed - not just "is there
     * currently an entry in [openStarts]" - because
     * `UsageEventsCollector`'s own source data (verified on-device: every
     * backgrounding of this app produces both) fires **both**
     * `activity_paused` and `activity_stopped` for a single real
     * backgrounding. Without this, the first of the pair correctly closes
     * the session and the second - finding nothing left in [openStarts] -
     * was mistaken for a genuinely orphaned close and synthesized a
     * phantom session from [startMs] on *every single backgrounding in
     * the range* (bot review round 2 on PR #21). A synthesized-from-
     * [startMs] session is now created only when there's no evidence at
     * all of this package's state (`lastKnownOpen[key]` is absent, not
     * `false`) - a genuinely ambiguous case, not the routine second half
     * of a pause+stop pair.
     */
    suspend fun loadAppSessions(startMs: Long, endMs: Long): List<AppSession> {
        val events = dao.usageEventsInRange(startMs, endMs)
        val lastEvidenceMs = dao.lastTimestamp()
        val openStarts = mutableMapOf<String, Long>()
        val lastKnownOpen = mutableMapOf<String, Boolean>()

        if (lastEvidenceMs != null && lastEvidenceMs >= startMs) {
            dao.lastUsageEventBeforeRange(startMs).forEach { row ->
                val isOpen = row.valueText == "activity_resumed"
                lastKnownOpen[row.key] = isOpen
                if (isOpen) openStarts[row.key] = startMs
            }
        }

        val sessions = mutableListOf<AppSession>()
        for (event in events) {
            when (event.valueText) {
                "activity_resumed" -> {
                    val alreadyOpen = openStarts[event.key]
                    if (alreadyOpen != null) sessions += AppSession(event.key, alreadyOpen, event.timestamp)
                    openStarts[event.key] = event.timestamp
                    lastKnownOpen[event.key] = true
                }
                "activity_paused", "activity_stopped" -> {
                    val start = openStarts.remove(event.key)
                    if (start != null) {
                        sessions += AppSession(event.key, start, event.timestamp)
                    } else if (lastKnownOpen[event.key] != false) {
                        sessions += AppSession(event.key, startMs, event.timestamp)
                    }
                    lastKnownOpen[event.key] = false
                }
            }
        }

        val cappedEnd = (lastEvidenceMs ?: endMs).coerceIn(startMs, endMs)
        openStarts.forEach { (pkg, start) -> sessions += AppSession(pkg, start, cappedEnd.coerceAtLeast(start)) }
        return sessions.sortedBy { it.startMs }
    }

    private suspend fun loadNumeric(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampNum> {
        val bucketMs = bucketMsFor(startMs, endMs)
        return if (bucketMs == null) {
            dao.rangeNumeric(collectorId, key, startMs, endMs)
        } else {
            dao.rangeNumericBucketed(collectorId, key, startMs, endMs, bucketMs)
        }
    }

    private suspend fun loadText(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampText> {
        val bucketMs = bucketMsFor(startMs, endMs)
        return if (bucketMs == null) {
            dao.rangeText(collectorId, key, startMs, endMs)
        } else {
            dao.rangeTextBucketed(collectorId, key, startMs, endMs, bucketMs)
        }
    }

    /** null means "load raw, no downsampling" - only wide ranges bucket. */
    private fun bucketMsFor(startMs: Long, endMs: Long): Long? {
        val span = endMs - startMs
        // 3 days raw is already the widest un-downsampled range
        // (TimelineRange.LAST_3D); anything wider buckets.
        if (span <= 3 * 24 * 60 * 60 * 1000L) return null
        return (span / targetPoints).coerceAtLeast(1000L)
    }

    private fun <T> List<TimestampText>.toSegments(rangeEndMs: Long, map: (String?) -> T): List<TimelineSegment<T>> {
        if (isEmpty()) return emptyList()
        return mapIndexed { i, row ->
            val end = if (i + 1 < size) this[i + 1].timestamp else rangeEndMs
            TimelineSegment(row.timestamp, end, map(row.valueText))
        }
    }
}
