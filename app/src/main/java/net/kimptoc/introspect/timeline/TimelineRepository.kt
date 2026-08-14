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

    suspend fun loadThermal(startMs: Long, endMs: Long): List<TimelineSegment<String>> =
        loadText("thermal", "status", startMs, endMs).toSegments(endMs) { it ?: "unknown" }

    suspend fun loadDeviceIdle(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "device_idle", startMs, endMs).toSegments(endMs) { it == "true" }

    suspend fun loadScreenOn(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "screen_on", startMs, endMs).toSegments(endMs) { it == "true" }

    /**
     * Sessions still open at [endMs] (the app was in the foreground when
     * the loaded range ends) are capped there rather than dropped - an
     * ongoing session at the visible edge is real data, not noise. The
     * same is now true at the [startMs] edge (bot review on PR #21): a
     * session already in progress when the range begins is seeded from
     * [SampleDao.lastUsageEventBeforeRange] rather than silently vanishing
     * because its `activity_resumed` falls outside the query window - both
     * edges clip an unknown true boundary to the visible range rather than
     * pretending the session doesn't exist.
     *
     * A resume that arrives while one is already open for that package
     * (no intervening pause/stop - e.g. the process died) closes the prior
     * session at that point rather than merging both episodes into one
     * session spanning a gap where the app wasn't actually foregrounded. A
     * pause/stop with no open start (missed by both the lookback and the
     * in-range resume - a genuinely orphaned event) is synthesized from
     * [startMs] rather than dropped, consistent with "missing signal is a
     * gap, never silently dropped" - a same-length phantom session in that
     * rare case is far less harmful than an invisible real one.
     *
     * The lookback is only trusted if monitoring was actually producing
     * data up to (or past) [startMs] - checked via [SampleDao.lastTimestamp].
     * Found on-device verifying this exact fix: an `activity_resumed` with
     * no pairing pause can also mean monitoring itself stopped right after
     * (Samsung killing the service, spec §7) rather than the app staying
     * genuinely foregrounded - a 39-hour-old dangling resume synthesized a
     * session spanning an entire, otherwise-empty "Last 24h" range and
     * silently suppressed the "no data" state. If the last real sample in
     * the whole table predates [startMs], there is no contemporaneous
     * evidence the app was still in the foreground, so the lookback is
     * skipped entirely for that call - matches this range's genuinely
     * empty reality instead of extrapolating across a monitoring gap.
     */
    suspend fun loadAppSessions(startMs: Long, endMs: Long): List<AppSession> {
        val events = dao.usageEventsInRange(startMs, endMs)
        val openStarts = mutableMapOf<String, Long>()
        val monitoringWasLiveAtRangeStart = (dao.lastTimestamp() ?: 0L) >= startMs
        if (monitoringWasLiveAtRangeStart) {
            dao.lastUsageEventBeforeRange(startMs).forEach { row ->
                if (row.valueText == "activity_resumed") openStarts[row.key] = startMs
            }
        }

        val sessions = mutableListOf<AppSession>()
        for (event in events) {
            when (event.valueText) {
                "activity_resumed" -> {
                    val alreadyOpen = openStarts[event.key]
                    if (alreadyOpen != null) sessions += AppSession(event.key, alreadyOpen, event.timestamp)
                    openStarts[event.key] = event.timestamp
                }
                "activity_paused", "activity_stopped" -> {
                    val start = openStarts.remove(event.key) ?: startMs
                    sessions += AppSession(event.key, start, event.timestamp)
                }
            }
        }
        openStarts.forEach { (pkg, start) -> sessions += AppSession(pkg, start, endMs) }
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
