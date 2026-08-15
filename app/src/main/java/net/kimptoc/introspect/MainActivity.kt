package net.kimptoc.introspect

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kimptoc.introspect.collector.CollectorRegistry
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.collector.t1.UsageAccess
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.export.CsvExporter
import net.kimptoc.introspect.service.MonitoringService
import net.kimptoc.introspect.service.SamplingWorker
import net.kimptoc.introspect.shizuku.ShizukuManager
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refreshTierStatus() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsetsAsPadding(findViewById(R.id.rootLayout))

        val toggleButton = findViewById<Button>(R.id.toggleServiceButton)
        val exemptionButton = findViewById<Button>(R.id.batteryExemptionButton)
        val exportButton = findViewById<Button>(R.id.exportButton)
        val usageAccessButton = findViewById<Button>(R.id.usageAccessButton)
        val shizukuAccessButton = findViewById<Button>(R.id.shizukuAccessButton)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // If the user's persisted intent (MonitoringService.isEnabledByUser,
        // default true - see issue #1) is "monitoring should be running"
        // but it isn't actually running, start it here rather than making
        // the user notice and re-tap Start. Previously this intent was
        // only acted on by BootReceiver, i.e. only at device boot - a
        // reinstall (which force-stops the service, no reboot involved)
        // left monitoring off until manually restarted, which is exactly
        // what happened and confused an actual user of this app. Defaults
        // to on for a fresh install too: this app's whole purpose is
        // continuous self-monitoring, opted into by installing it at all.
        if (MonitoringService.isEnabledByUser(this) && !MonitoringService.isRunning) {
            // No notification-permission prompt here, unlike the manual
            // Start button below: a silent auto-restart on cold open is
            // exactly the moment an unexpected system dialog is most
            // jarring, and re-firing it on every restart until the user
            // either grants or hits Android's own auto-deny threshold is
            // its own annoyance (bot review on PR #22). Missing that
            // permission only means the foreground-service notification
            // itself won't show - monitoring still starts and runs either
            // way. The button's explicit tap remains the place a
            // permission prompt is an expected response to user action.
            //
            // Also guarded against a foreground-service start being refused
            // outright: Android 12+ can throw
            // ForegroundServiceStartNotAllowedException (a subclass of
            // IllegalStateException, caught here by the parent type - the
            // subclass itself requires API 31 and this app's minSdk is 30,
            // so catching it by name would reference a class the verifier
            // can't resolve on this app's own floor). This now runs
            // unconditionally on every cold open, not just in response to
            // a button tap - a thrown exception here would crash the whole
            // app on launch instead of degrading to the WorkManager
            // fallback alone (bot review on PR #22).
            //
            // Only MonitoringService.start() is inside the try, not
            // enqueuePeriodic() too: sharing the catch would silently drop
            // the 15-min fallback worker on any enqueue failure even
            // though the foreground service started fine - a different
            // failure entirely from the one this catch exists for (bot
            // review round 2 on PR #22).
            try {
                MonitoringService.start(this)
            } catch (e: IllegalStateException) {
                // Foreground start refused; the fallback worker below
                // still samples (bot review round 3 on PR #22 - the
                // previous comment here claimed monitoring stays off,
                // which stopped being true the moment enqueuePeriodic()
                // moved outside this catch: the foreground service is
                // off, but MonitoringService.isRunning tracks only that,
                // not the independent WorkManager fallback, which keeps
                // inserting samples every 15 minutes regardless).
            }
            SamplingWorker.enqueuePeriodic(this)
        }

        toggleButton.setOnClickListener {
            // The intent, decided *before* start()/stop() are called, not
            // read back from MonitoringService.isRunning afterwards -
            // onCreate()/onDestroy() haven't necessarily run yet the
            // instant startForegroundService()/stopService() return, so
            // reading isRunning right here could still show the pre-tap
            // value and put the UI right back to lying about state, just
            // on this tap instead of a stale local boolean (bot review on
            // PR #19). MonitoringService.isRunning is still the source of
            // truth generally (e.g. after the service is started/stopped
            // by something other than this button, like the ~14-minute
            // post-reboot START_STICKY restart in STATUS.md) - the 5s
            // poll loop reconciles with it shortly after.
            val targetRunning = !MonitoringService.isRunning
            toggleButton.setText(if (targetRunning) R.string.stop_service else R.string.start_service)
            if (targetRunning) {
                requestNotificationPermissionIfNeeded()
                MonitoringService.start(this)
                SamplingWorker.enqueuePeriodic(this)
            } else {
                MonitoringService.stop(this)
                // A user tapping "Stop" expects monitoring to actually
                // stop, not silently keep sampling every 15 minutes via the
                // WorkManager fallback - that fallback exists for Samsung
                // killing the service *without* user action, a different
                // case from an explicit Stop.
                SamplingWorker.cancel(this)
            }
            // Passing targetRunning explicitly, not letting this re-derive
            // MonitoringService.isRunning itself: lifecycleScope runs on
            // Dispatchers.Main.immediate, so this launch executes inline
            // in the same call stack as the click handler, before
            // onCreate()/onDestroy() have been dispatched - re-deriving
            // here would immediately overwrite the fresh label above with
            // the same stale value it was trying to fix (bot review on
            // PR #19, round 2). The unforced call from the poll loop below
            // is what re-derives real state and self-corrects if start()/
            // stop() didn't actually succeed.
            lifecycleScope.launch { refreshServiceStatus(forceRunning = targetRunning) }
        }

        exemptionButton.setOnClickListener { requestBatteryOptimizationExemption() }

        exportButton.setOnClickListener {
            exportLauncher.launch("introspect-export-${System.currentTimeMillis()}.csv")
        }

        usageAccessButton.setOnClickListener { requestUsageAccessIfNeeded() }

        shizukuAccessButton.setOnClickListener { requestShizukuAccessIfNeeded() }

        // repeatOnLifecycle rather than a one-shot refresh in onResume:
        // "is this actually collecting right now" (issue #1) needs the
        // last-sample time and tier status to stay current while the
        // screen is open, not just reflect whatever was true at the moment
        // it was opened. Automatically starts/stops with RESUMED, so this
        // doesn't poll the DB while backgrounded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refreshServiceStatus()
                    refreshTierStatus()
                    delay(STATUS_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    // suspend, not a fresh lifecycleScope.launch{} per call: the poll loop
    // already runs inside one coroutine and previously spawned a brand new
    // unawaited one on every 5s tick (and every tap) that outlived
    // repeatOnLifecycle's own cancellation - a real leak risk if a DB read
    // ever outlasted the tick interval (bot review on PR #19). Now there's
    // one coroutine per call site, awaited, cancellable with the rest of
    // the loop.
    //
    // forceRunning lets the click handler's own launch (which runs inline,
    // same call stack, via Main.immediate) show the just-decided target
    // state instead of re-deriving MonitoringService.isRunning - which
    // hasn't updated yet at that point and would immediately overwrite the
    // fresh label with the stale one (bot review round 2). The poll loop
    // below always calls this with forceRunning=null, so it's still the
    // one place real state gets re-derived and self-corrects.
    private suspend fun refreshServiceStatus(forceRunning: Boolean? = null) {
        val toggleButton = findViewById<Button>(R.id.toggleServiceButton)
        val statusText = findViewById<TextView>(R.id.serviceStatusText)
        val running = forceRunning ?: MonitoringService.isRunning
        toggleButton.setText(if (running) R.string.stop_service else R.string.start_service)

        val dao = AppDatabase.get(this@MainActivity).sampleDao()
        val lastTimestamp = dao.lastTimestamp()
        val count = dao.count()
        val lastSampleText = if (lastTimestamp == null) {
            getString(R.string.no_samples_yet)
        } else {
            val ageSec = (System.currentTimeMillis() - lastTimestamp) / 1000
            getString(R.string.last_sample_ago, ageSec)
        }
        statusText.text = buildString {
            append(if (running) getString(R.string.notification_title) else getString(R.string.stopped))
            if (running) {
                // The fallback keeps sampling on its own schedule even
                // if this activity's own service connection is gone
                // (issue #1's point 2) - worth saying so, not just
                // "running".
                append(" ").append(getString(R.string.fallback_also_active))
            }
            append("\n").append(lastSampleText).append(", ").append(getString(R.string.total_samples, count))
        }
    }

    private fun refreshTierStatus() {
        val tierStatusText = findViewById<TextView>(R.id.tierStatusText)
        val status = CollectorRegistry.tierStatus(this)
        tierStatusText.text = status.entries.joinToString("\n") { (tier, live) ->
            "$tier (${TIER_LABELS[tier]}): ${if (live) "live" else "dark"}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // Skip the prompt when already granted - previously fired
        // unconditionally on every Start tap, re-showing (or re-queuing)
        // a system dialog for a permission already held (bot review on
        // PR #22).
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestUsageAccessIfNeeded() {
        if (UsageAccess.isGranted(this)) return
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestShizukuAccessIfNeeded() {
        if (ShizukuManager.isPermissionGranted()) return
        val requested = ShizukuManager.requestPermission(SHIZUKU_REQUEST_CODE)
        if (!requested) {
            Toast.makeText(this, "Shizuku isn't running - install/start it first", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            CsvExporter.export(this@MainActivity, uri)
        }
    }

    /**
     * Android 15+ (targetSdk 35+) enforces edge-to-edge: content draws behind
     * the status/nav bars by default. Without this, the top of the layout
     * renders under the status bar.
     */
    private fun applySystemBarInsetsAsPadding(root: View) {
        val basePadding = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 1001
        const val STATUS_REFRESH_INTERVAL_MS = 5000L

        // Short, one-line-per-tier context for the bare T0-T4 codes
        // (issue #1, point 3) - what each tier needs to go live, not why a
        // specific one is currently dark (that varies: no permission
        // granted vs. genuinely unavailable vs. Shizuku not running, and
        // isn't something tierStatus() distinguishes today).
        val TIER_LABELS = mapOf(
            Tier.T0 to "no permissions",
            Tier.T1 to "usage access",
            Tier.T2 to "adb-provisioned",
            Tier.T3 to "Shizuku",
            Tier.T4 to "root, unsupported",
        )
    }
}
