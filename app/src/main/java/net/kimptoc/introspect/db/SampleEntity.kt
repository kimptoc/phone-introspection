package net.kimptoc.introspect.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Generic (timestamp, collector_id, key, value) row (spec §5). Keeps
 * schema churn low while the signal set is still moving; purpose-built
 * tables (e.g. usage sessions) arrive in later phases.
 */
@Entity(
    tableName = "samples",
    indices = [Index(value = ["timestamp"]), Index(value = ["collector_id"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    @androidx.room.ColumnInfo(name = "collector_id")
    val collectorId: String,
    val key: String,
    @androidx.room.ColumnInfo(name = "value_num")
    val valueNum: Double?,
    @androidx.room.ColumnInfo(name = "value_text")
    val valueText: String?,
)
