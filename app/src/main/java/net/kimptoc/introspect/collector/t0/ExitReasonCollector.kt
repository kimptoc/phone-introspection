package net.kimptoc.introspect.collector.t0

import android.app.ActivityManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Why our own process last died (spec §3, §5): "ApplicationExitInfo
 * logged on every start so process deaths are self-documenting."
 * Requires API 30, same floor as thermal headroom.
 *
 * Only emits exit reasons newer than the last one this collector has
 * already recorded, tracked via SharedPreferences, so a repeat collection
 * cycle doesn't duplicate old history.
 */
class ExitReasonCollector : Collector {
    override val id = "exit_reason"
    override val tier = Tier.T0

    private val prefsName = "exit_reason_collector"
    private val lastSeenKey = "last_seen_timestamp"

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(lastSeenKey, 0L)

        val reasons = activityManager.getHistoricalProcessExitReasons(null, 0, 0)
        val newReasons = reasons.filter { it.timestamp > lastSeen }
        if (newReasons.isEmpty()) return emptyList()

        val samples = newReasons.map { info ->
            Sample(
                timestamp = info.timestamp,
                collectorId = id,
                key = "reason",
                valueNum = info.reason.toDouble(),
                valueText = "${info.description}|status=${info.status}|importance=${info.importance}",
            )
        }

        prefs.edit()
            .putLong(lastSeenKey, newReasons.maxOf { it.timestamp })
            .apply()

        return samples
    }
}
