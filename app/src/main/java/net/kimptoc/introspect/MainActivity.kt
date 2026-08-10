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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.kimptoc.introspect.collector.CollectorRegistry
import net.kimptoc.introspect.collector.t1.UsageAccess
import net.kimptoc.introspect.export.CsvExporter
import net.kimptoc.introspect.service.MonitoringService
import net.kimptoc.introspect.service.SamplingWorker
import net.kimptoc.introspect.shizuku.ShizukuManager
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private var serviceRunning = false

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
        val statusText = findViewById<TextView>(R.id.serviceStatusText)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        toggleButton.setOnClickListener {
            if (serviceRunning) {
                MonitoringService.stop(this)
            } else {
                requestNotificationPermissionIfNeeded()
                MonitoringService.start(this)
                SamplingWorker.enqueuePeriodic(this)
            }
            serviceRunning = !serviceRunning
            toggleButton.setText(if (serviceRunning) R.string.stop_service else R.string.start_service)
            statusText.text = if (serviceRunning) getString(R.string.notification_title) else "Stopped"
        }

        exemptionButton.setOnClickListener { requestBatteryOptimizationExemption() }

        exportButton.setOnClickListener {
            exportLauncher.launch("introspect-export-${System.currentTimeMillis()}.csv")
        }

        usageAccessButton.setOnClickListener { requestUsageAccessIfNeeded() }

        shizukuAccessButton.setOnClickListener { requestShizukuAccessIfNeeded() }

        refreshTierStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshTierStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun refreshTierStatus() {
        val tierStatusText = findViewById<TextView>(R.id.tierStatusText)
        val status = CollectorRegistry.tierStatus(this)
        tierStatusText.text = status.entries.joinToString("\n") { (tier, live) ->
            "$tier: ${if (live) "live" else "dark"}"
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
    }
}
