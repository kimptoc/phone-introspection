package net.kimptoc.introspect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts collection after reboot (spec §5 resilience). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MonitoringService.start(context)
        SamplingWorker.enqueuePeriodic(context)
    }
}
