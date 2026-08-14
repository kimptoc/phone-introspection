package net.kimptoc.introspect.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples ORDER BY timestamp ASC")
    suspend fun getAll(): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun count(): Long

    @Query(
        """
        SELECT timestamp, value_num FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeNumeric(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampNum>

    @Query(
        """
        SELECT MIN(timestamp) AS timestamp, AVG(value_num) AS value_num FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY timestamp / :bucketMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeNumericBucketed(
        collectorId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): List<TimestampNum>

    @Query(
        """
        SELECT timestamp, value_text FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeText(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampText>

    @Query(
        """
        SELECT MIN(timestamp) AS timestamp, value_text FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY timestamp / :bucketMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeTextBucketed(
        collectorId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): List<TimestampText>

    @Query(
        """
        SELECT timestamp, key, value_text FROM samples
        WHERE collector_id = 'usage_events'
          AND value_text IN ('activity_resumed', 'activity_paused', 'activity_stopped')
          AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun usageEventsInRange(startMs: Long, endMs: Long): List<UsageEventRow>

    // For each package, the single most recent lifecycle event before
    // startMs - used to detect a session already in progress when a range
    // begins (bot review on PR #21: without this, loadAppSessions silently
    // drops the visible portion of any session that started before the
    // loaded window). GROUP BY key with a bare value_text column alongside
    // MAX(timestamp) is a documented SQLite behavior, not an arbitrary-row
    // pick: with exactly one MAX() aggregate present, every bare column
    // takes its value from the row that produced that MAX - verified
    // against this exact query shape with sqlite3 3.37.0 before shipping.
    @Query(
        """
        SELECT key, MAX(timestamp) AS timestamp, value_text FROM samples
        WHERE collector_id = 'usage_events'
          AND value_text IN ('activity_resumed', 'activity_paused', 'activity_stopped')
          AND timestamp < :startMs
        GROUP BY key
        """,
    )
    suspend fun lastUsageEventBeforeRange(startMs: Long): List<UsageEventRow>

    @Query("SELECT MIN(timestamp) FROM samples")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM samples")
    suspend fun lastTimestamp(): Long?
}
