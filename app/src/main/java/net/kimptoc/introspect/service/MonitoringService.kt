package net.kimptoc.introspect.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kimptoc.introspect.MainActivity
import net.kimptoc.introspect.R
import net.kimptoc.introspect.collector.CollectorRegistry
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.db.SampleEntity
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Foreground service running the event-driven and periodic-sample
 * cadences from spec §4. Batch reconciliation (T1) is not part of the
 * Phase-1 skeleton.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // The periodic loop and each event listener (battery/screen/thermal)
    // all launch independent coroutines that call collectAndPersist() on
    // Dispatchers.Default, which has multiple threads - near-simultaneous
    // triggers (typically right at startup, when a sticky battery
    // broadcast, the periodic loop's first tick, and a thermal callback can
    // all fire within milliseconds of each other) can genuinely run
    // concurrently. Each collector's own watermark gate assumes it only
    // ever sees one collect() call at a time; without this lock, two
    // concurrent calls can both read the same stale watermark before
    // either writes it back, producing duplicate rows - observed directly
    // as 2-3x duplicate T3 dumpsys rows on startup (expensive at ~150KB
    // each for batterystats, not just a minor inefficiency).
    private val collectMutex = Mutex()

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                BatteryThresholdNotifier.onBatteryChanged(context, intent)
            }
            collectAndPersistAsync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerEventListeners()
        startSamplingLoop()
        // Kick off the T3 UserService bind now, not lazily on the first
        // gated collect() call - binding is async, and starting it here
        // means it's likely already connected by the time SensorServiceCollector's
        // hourly gate first lets it run.
        ShizukuManager.prewarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(eventReceiver)
        thermalListener?.let {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(it)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerEventListeners() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(eventReceiver, filter)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { collectAndPersistAsync() }
        powerManager.addThermalStatusListener(listener)
        thermalListener = listener
    }

    private fun startSamplingLoop() {
        scope.launch {
            while (true) {
                collectAndPersist()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun collectAndPersistAsync() {
        scope.launch { collectAndPersist() }
    }

    private suspend fun collectAndPersist() = collectMutex.withLock {
        val samples = CollectorRegistry.collectAll(this)
        if (samples.isEmpty()) return@withLock
        AppDatabase.get(this).sampleDao().insertAll(samples.map { it.toEntity() })
    }

    private fun buildNotification(): android.app.Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}

private fun Sample.toEntity() = SampleEntity(
    timestamp = timestamp,
    collectorId = collectorId,
    key = key,
    valueNum = valueNum,
    valueText = valueText,
)
