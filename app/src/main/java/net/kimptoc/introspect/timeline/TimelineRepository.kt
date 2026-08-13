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
 * not just writing it).
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
     * ongoing session at the visible edge is real data, not noise.
     */
    suspend fun loadAppSessions(startMs: Long, endMs: Long): List<AppSession> {
        val events = dao.usageEventsInRange(startMs, endMs)
        val openStarts = mutableMapOf<String, Long>()
        val sessions = mutableListOf<AppSession>()
        for (event in events) {
            when (event.valueText) {
                "activity_resumed" -> openStarts.putIfAbsent(event.key, event.timestamp)
                "activity_paused", "activity_stopped" -> {
                    val start = openStarts.remove(event.key)
                    if (start != null) sessions += AppSession(event.key, start, event.timestamp)
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
