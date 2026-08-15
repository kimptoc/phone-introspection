package net.kimptoc.introspect.timeline

import net.kimptoc.introspect.R

/**
 * The four ranges offered by the range picker. LAST_24H/LAST_3D load raw
 * samples directly - even at this app's finest cadence (60s, spec §4)
 * that's at most a few thousand rows per signal, well within what
 * MPAndroidChart renders smoothly. LAST_7D/ALL_TIME downsample in SQL
 * (see [TimelineRepository]) since the table is 400K+ rows and growing.
 */
enum class TimelineRange(val labelRes: Int, val downsample: Boolean) {
    LAST_24H(R.string.timeline_range_24h, downsample = false),
    LAST_3D(R.string.timeline_range_3d, downsample = false),
    LAST_7D(R.string.timeline_range_7d, downsample = true),
    ALL_TIME(R.string.timeline_range_all, downsample = true),
}
