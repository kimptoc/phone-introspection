package net.kimptoc.introspect.collector

import android.content.Context

/**
 * A single signal source. The registry composes whatever is available;
 * adding a higher tier later means adding collectors, not restructuring
 * (spec §5).
 */
interface Collector {
    val id: String
    val tier: Tier

    /** Re-probed at startup and after any permission change. */
    fun isAvailable(context: Context): Boolean

    fun collect(context: Context): List<Sample>
}
