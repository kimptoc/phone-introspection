package net.kimptoc.introspect.db

import androidx.room.ColumnInfo

/** One (timestamp, numeric value) row from a Timeline range query. */
data class TimestampNum(
    val timestamp: Long,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
)

/** One (timestamp, text value) row from a Timeline range query. */
data class TimestampText(
    val timestamp: Long,
    @ColumnInfo(name = "value_text") val valueText: String?,
)

/** One `usage_events` row: [key] is the package name, [valueText] the event type. */
data class UsageEventRow(
    val timestamp: Long,
    val key: String,
    @ColumnInfo(name = "value_text") val valueText: String?,
)
