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

    /** System-wide running-process count (issue #27) - see [net.kimptoc.introspect.collector.t3.ProcessCountCollector]. */
    suspend fun loadProcessCount(startMs: Long, endMs: Long): List<TimestampNum> =
        loadNumeric("process_count", "process_count", startMs, endMs)

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
     * A seed only represents "was open at some point *before* the range" -
     * it is no evidence that the app stayed foregrounded through any of the
     * range itself. So a package seeded as open and then observed with no
     * in-range events at all (tracked in [seededOpen]) is dropped from the
     * dangling-open tail rather than drawn as a full-width block covering
     * the whole window (issue #30: an app uninstalled days ago is seeded
     * open, gets no in-range events, and was previously rendered as a fake
     * 24h-long session). Any in-range event for that package clears the
     * seed and restores normal endMs-cap behaviour - but *which* event
     * arrives first decides whether the seeded [startMs] span is drawn:
     *
     *  - An in-range **resume** supersedes the seed: it says the app came
     *    to the foreground at that moment, and says nothing about the
     *    interval before it. Drawing [startMs] to that resume would be the
     *    same phantom the tail guard drops (an app killed while
     *    foregrounded days ago, then relaunched mid-range), so the seeded
     *    span is discarded and the new session starts at the resume.
     *  - An in-range **pause/stop** corroborates it: those only fire for an
     *    activity that really was resumed, and any earlier in-range resume
     *    would already have cleared the seed - so the app was genuinely
     *    foregrounded across [startMs] and the span is real. Verified
     *    on-device: `com.android.dreams.basic` (the screensaver) does this
     *    nightly, resuming before midnight and pausing hours later.
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
        val seededOpen = mutableSetOf<String>()

        if (lastEvidenceMs != null && lastEvidenceMs >= startMs) {
            dao.lastUsageEventBeforeRange(startMs).forEach { row ->
                val isOpen = row.valueText == "activity_resumed"
                lastKnownOpen[row.key] = isOpen
                if (isOpen) {
                    openStarts[row.key] = startMs
                    seededOpen += row.key
                }
            }
        }

        val sessions = mutableListOf<AppSession>()
        for (event in events) {
            // Consumed, not just cleared: the resume branch has to know
            // whether the openStarts entry it's closing is a real in-range
            // resume or the startMs seed. Only that branch drops the seeded
            // span - a close corroborates the seed rather than superseding
            // it (see both branches below).
            val wasSeeded = seededOpen.remove(event.key)
            when (event.valueText) {
                "activity_resumed" -> {
                    val alreadyOpen = openStarts[event.key]
                    if (alreadyOpen != null && !wasSeeded) {
                        sessions += AppSession(event.key, alreadyOpen, event.timestamp)
                    }
                    openStarts[event.key] = event.timestamp
                    lastKnownOpen[event.key] = true
                }
                "activity_paused", "activity_stopped" -> {
                    val start = openStarts.remove(event.key)
                    if (start != null) {
                        // A seeded start closed here is corroborated, not
                        // phantom, so it IS drawn: pause/stop only fires for an
                        // activity that was really resumed, and any in-range
                        // resume would already have cleared the seed - so this
                        // package was genuinely foregrounded across startMs and
                        // up to this event. Verified on-device: the nightly
                        // com.android.dreams.basic screensaver is exactly this
                        // shape (resumed ~23:50, paused ~06:50, nothing in
                        // between), and dropping it erased a real 7h session.
                        sessions += AppSession(event.key, start, event.timestamp)
                    } else if (lastKnownOpen[event.key] != false) {
                        sessions += AppSession(event.key, startMs, event.timestamp)
                    }
                    lastKnownOpen[event.key] = false
                }
            }
        }

        val cappedEnd = (lastEvidenceMs ?: endMs).coerceIn(startMs, endMs)
        openStarts.forEach { (pkg, start) ->
            if (pkg in seededOpen) return@forEach
            sessions += AppSession(pkg, start, cappedEnd.coerceAtLeast(start))
        }
        return sessions.sortedBy { it.startMs }
    }

    /**
     * Package name -> friendly app label (issue #28), for annotating
     * [AppSession]s in the marker/band UI. Looked up from `installed_packages`
     * history rather than a live `PackageManager` query, so a package still
     * resolves after the app itself has been uninstalled. A package with no
     * `installed_packages` row (never captured, e.g. a very old session) is
     * simply absent from the result - callers fall back to the raw package
     * name themselves.
     */
    suspend fun loadPackageLabels(packageNames: Collection<String>): Map<String, String> {
        if (packageNames.isEmpty()) return emptyMap()
        return dao.latestPackageLabels(packageNames.distinct())
            .mapNotNull { row -> row.valueText?.let { row.key to it } }
            .toMap()
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

    /**
     * null means "load raw, no downsampling" - only wide ranges bucket.
     * Not private: [TimelineActivity]'s marker needs this to size its
     * nearest-sample lookup window to the range's actual data spacing
     * (bot review on PR #25 - a fixed window was too narrow once ALL_TIME
     * buckets grow wider than it).
     */
    fun bucketMsFor(startMs: Long, endMs: Long): Long? {
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
