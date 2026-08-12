package net.kimptoc.introspect.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.kimptoc.introspect.collector.CollectorRegistry
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.db.SampleEntity
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback for when the foreground service has been killed
 * (spec §4, §5). 15 minutes is WorkManager's minimum periodic interval,
 * which happens to match the doze-time sampling cadence in spec §4.
 */
class SamplingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Locked: this runs on an independent WorkManager schedule that can
        // overlap MonitoringService's own periodic/event-driven cycles,
        // hitting the exact same collectors and watermarks - see
        // CollectorRegistry.collectAllLocked() for why this can't be a
        // lock scoped to just one caller.
        val samples = CollectorRegistry.collectAllLocked(applicationContext)
        if (samples.isNotEmpty()) {
            val entities = samples.map {
                SampleEntity(
                    timestamp = it.timestamp,
                    collectorId = it.collectorId,
                    key = it.key,
                    valueNum = it.valueNum,
                    valueText = it.valueText,
                )
            }
            AppDatabase.get(applicationContext).sampleDao().insertAll(entities)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sampling_watchdog"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SamplingWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        // A user tapping "Stop monitoring" should mean monitoring actually
        // stops, not "the foreground service stops but this 15-min fallback
        // keeps sampling silently" (issue #1) - the resilience case this
        // fallback exists for is Samsung killing the service *without* user
        // action, which enqueuePeriodic() from the next Start covers same
        // as before; an explicit Stop is a different, deliberate signal.
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
