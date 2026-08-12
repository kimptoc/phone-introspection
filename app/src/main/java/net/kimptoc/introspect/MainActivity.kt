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

        toggleButton.setOnClickListener {
            // Reads MonitoringService.isRunning fresh on every click rather
            // than trusting a flag this activity flipped itself (issue #1):
            // that's what makes this correct even when the service was
            // started/stopped by something other than this button, e.g.
            // the ~14-minute post-reboot START_STICKY restart documented in
            // STATUS.md.
            if (MonitoringService.isRunning) {
                MonitoringService.stop(this)
                // A user tapping "Stop" expects monitoring to actually
                // stop, not silently keep sampling every 15 minutes via the
                // WorkManager fallback - that fallback exists for Samsung
                // killing the service *without* user action, a different
                // case from an explicit Stop.
                SamplingWorker.cancel(this)
            } else {
                requestNotificationPermissionIfNeeded()
                MonitoringService.start(this)
                SamplingWorker.enqueuePeriodic(this)
            }
            refreshServiceStatus()
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

    private fun refreshServiceStatus() {
        val toggleButton = findViewById<Button>(R.id.toggleServiceButton)
        val statusText = findViewById<TextView>(R.id.serviceStatusText)
        val running = MonitoringService.isRunning
        toggleButton.setText(if (running) R.string.stop_service else R.string.start_service)

        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).sampleDao()
            val lastTimestamp = dao.lastTimestamp()
            val count = dao.count()
            val lastSampleText = if (lastTimestamp == null) {
                "no samples yet"
            } else {
                val ageSec = (System.currentTimeMillis() - lastTimestamp) / 1000
                "last sample ${ageSec}s ago"
            }
            statusText.text = buildString {
                append(if (running) getString(R.string.notification_title) else "Stopped")
                if (running) {
                    // The fallback keeps sampling on its own schedule even
                    // if this activity's own service connection is gone
                    // (issue #1's point 2) - worth saying so, not just
                    // "running".
                    append(" (15-min fallback also active)")
                }
                append("\n$lastSampleText, $count total")
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
