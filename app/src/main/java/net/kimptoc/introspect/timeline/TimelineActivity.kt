package net.kimptoc.introspect.timeline

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch
import net.kimptoc.introspect.R
import net.kimptoc.introspect.collector.t1.UsageAccess

/**
 * Phase 5 (spec §6): battery, thermal, Doze/screen-on, and app-session
 * data on a shared, pan/zoomable time axis. General-purpose data browser,
 * not a single-workflow view - see the design doc
 * (docs/superpowers/specs/2026-08-13-phase5-timeline-design.md) for why.
 *
 * Chart X values are seconds-since-[rangeStartMs], not raw epoch millis -
 * see [timestampToX] for why.
 */
class TimelineActivity : ComponentActivity() {

    private lateinit var repository: TimelineRepository
    private lateinit var batteryChart: LineChart
    private lateinit var emptyStateText: TextView

    private var rangeStartMs = 0L
    private var rangeEndMs = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        // Android 15+ (targetSdk 35+) enforces edge-to-edge, same as
        // MainActivity: without this the range-picker button row renders
        // (and is tappable) underneath the status bar/action bar rather
        // than below it (found on-device during Task 5).
        applySystemBarInsetsAsPadding(findViewById(R.id.timelineRootLayout))
        repository = TimelineRepository(this)
        batteryChart = findViewById(R.id.batteryChart)
        emptyStateText = findViewById(R.id.timelineEmptyStateText)
        batteryChart.description.isEnabled = false

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        loadRange(TimelineRange.LAST_24H)
    }

    private fun timestampToX(ts: Long): Float = (ts - rangeStartMs) / 1000f

    private fun loadRange(range: TimelineRange) {
        lifecycleScope.launch {
            val (startMs, endMs) = repository.resolveRange(range)
            rangeStartMs = startMs
            rangeEndMs = endMs

            val battery = repository.loadBattery(startMs, endMs)
            if (battery.isEmpty()) {
                emptyStateText.text = getString(R.string.timeline_no_data)
                emptyStateText.visibility = android.view.View.VISIBLE
                batteryChart.clear()
                return@launch
            }
            emptyStateText.visibility = android.view.View.GONE

            val entries = battery.mapNotNull { row ->
                row.valueNum?.let { Entry(timestampToX(row.timestamp), it.toFloat()) }
            }
            val dataSet = LineDataSet(entries, getString(R.string.timeline_band_thermal).let { "" }).apply {
                color = Color.BLUE
                setDrawCircles(false)
                lineWidth = 2f
            }
            batteryChart.data = LineData(dataSet)
            batteryChart.notifyDataSetChanged()
            batteryChart.invalidate()

            if (!UsageAccess.isGranted(this@TimelineActivity)) {
                android.widget.Toast.makeText(
                    this@TimelineActivity,
                    R.string.timeline_grant_usage_access,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Mirrors MainActivity.applySystemBarInsetsAsPadding: without it,
     * edge-to-edge draws this screen's content behind the status bar and
     * the window's action bar, leaving the top row of range buttons
     * visually hidden and untappable (confirmed on-device in Task 5).
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
}
