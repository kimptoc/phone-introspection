package net.kimptoc.introspect.collector.t1

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Usage event stream (ACTIVITY_RESUMED/PAUSED, screen interactive, device
 * shutdown, etc. — spec §3 T1). This is the primary source of truth for
 * session boundaries: it survives our process dying and backfills on next
 * start, which is what makes the dataset gap-free across process death
 * (spec §4).
 */
class UsageEventsCollector : Collector {
    override val id = "usage_events"
    override val tier = Tier.T1

    private val prefsName = "usage_events_collector"
    private val lastSeenKey = "last_seen_timestamp"
    private val bootstrapWindowMs = 24 * 60 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = UsageAccess.isGranted(context)

    override fun collect(context: Context): List<Sample> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastSeen = prefs.getLong(lastSeenKey, -1L)
        val startTime = if (lastSeen < 0L) now - bootstrapWindowMs else lastSeen

        val events = usageStatsManager.queryEvents(startTime, now)
        val samples = mutableListOf<Sample>()
        var maxTimestamp = lastSeen
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            samples += Sample(
                timestamp = event.timeStamp,
                collectorId = id,
                key = event.packageName ?: "unknown",
                valueNum = event.eventType.toDouble(),
                valueText = eventTypeName(event.eventType),
            )
            if (event.timeStamp > maxTimestamp) maxTimestamp = event.timeStamp
        }

        val nextLastSeen = if (samples.isNotEmpty()) maxTimestamp + 1 else now
        prefs.edit().putLong(lastSeenKey, nextLastSeen).apply()

        return samples
    }

    private fun eventTypeName(type: Int) = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "activity_resumed"
        UsageEvents.Event.ACTIVITY_PAUSED -> "activity_paused"
        UsageEvents.Event.ACTIVITY_STOPPED -> "activity_stopped"
        UsageEvents.Event.CONFIGURATION_CHANGE -> "configuration_change"
        UsageEvents.Event.USER_INTERACTION -> "user_interaction"
        UsageEvents.Event.SHORTCUT_INVOCATION -> "shortcut_invocation"
        UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "standby_bucket_changed"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "screen_interactive"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "screen_non_interactive"
        UsageEvents.Event.KEYGUARD_SHOWN -> "keyguard_shown"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "keyguard_hidden"
        UsageEvents.Event.FOREGROUND_SERVICE_START -> "foreground_service_start"
        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "foreground_service_stop"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "device_shutdown"
        UsageEvents.Event.DEVICE_STARTUP -> "device_startup"
        else -> "type_$type"
    }
}
