package net.kimptoc.introspect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts collection after reboot (spec §5 resilience) - but only if the
 * user hadn't explicitly stopped it. Without the
 * [MonitoringService.isEnabledByUser] check, an explicit Stop tap would
 * silently un-stop itself on the next reboot (issue #1): the same "UI
 * says one thing, reality does another" failure the rest of that issue's
 * fixes exist to close, just triggered by a reboot instead of a stale
 * local boolean.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MonitoringService.isEnabledByUser(context)) return
        MonitoringService.start(context)
        SamplingWorker.enqueuePeriodic(context)
    }
}
