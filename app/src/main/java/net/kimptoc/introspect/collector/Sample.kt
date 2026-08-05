package net.kimptoc.introspect.collector

/** One (timestamp, key, value) reading emitted by a [Collector]. */
data class Sample(
    val timestamp: Long,
    val collectorId: String,
    val key: String,
    val valueNum: Double? = null,
    val valueText: String? = null,
)
