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

    @Query("SELECT MIN(timestamp) FROM samples")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM samples")
    suspend fun lastTimestamp(): Long?
}
