package net.kimptoc.introspect.timeline

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
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
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
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
    private lateinit var thermalBand: TimelineBandView
    private lateinit var dozeBand: TimelineBandView
    private lateinit var sessionsBand: TimelineBandView

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
        thermalBand = findViewById(R.id.thermalBand)
        dozeBand = findViewById(R.id.dozeBand)
        sessionsBand = findViewById(R.id.sessionsBand)
        batteryChart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) = syncBandsToChart()
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) = syncBandsToChart()
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) = syncBandsToChart()
        }
        batteryChart.description.isEnabled = false

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        loadRange(TimelineRange.LAST_24H)
    }

    private fun timestampToX(ts: Long): Float = (ts - rangeStartMs) / 1000f

    private fun xToTimestamp(x: Float): Long = rangeStartMs + (x * 1000).toLong()

    private fun syncBandsToChart() {
        val startMs = xToTimestamp(batteryChart.lowestVisibleX)
        val endMs = xToTimestamp(batteryChart.highestVisibleX)
        thermalBand.setVisibleRange(startMs, endMs)
        dozeBand.setVisibleRange(startMs, endMs)
        sessionsBand.setVisibleRange(startMs, endMs)
    }

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

            val thermalColors = mapOf(
                "none" to Color.rgb(200, 230, 200),
                "light" to Color.rgb(255, 235, 150),
                "moderate" to Color.rgb(255, 180, 80),
                "severe" to Color.rgb(255, 100, 60),
                "critical" to Color.rgb(220, 40, 40),
                "emergency" to Color.rgb(150, 0, 0),
                "shutdown" to Color.rgb(80, 0, 0),
                "unknown" to Color.LTGRAY,
            )
            thermalBand.setSegments(
                repository.loadThermal(startMs, endMs).map {
                    TimelineBandView.Segment(it.startMs, it.endMs, thermalColors[it.value] ?: Color.LTGRAY, it.value)
                },
            )

            val deviceIdle = repository.loadDeviceIdle(startMs, endMs)
            val screenOn = repository.loadScreenOn(startMs, endMs)
            // One combined band: screen_on takes visual priority (drawn
            // second, so it wins where both series would otherwise
            // overlap) since an interactive screen is the more actionable
            // state to see at a glance than Doze specifically.
            dozeBand.setSegments(
                deviceIdle.map {
                    TimelineBandView.Segment(
                        it.startMs, it.endMs,
                        if (it.value) Color.rgb(150, 180, 255) else Color.LTGRAY,
                        if (it.value) "idle" else "active",
                    )
                } + screenOn.map {
                    TimelineBandView.Segment(
                        it.startMs, it.endMs,
                        if (it.value) Color.rgb(255, 220, 100) else Color.TRANSPARENT,
                        if (it.value) "screen on" else "screen off",
                    )
                },
            )

            val sessionColors = listOf(
                Color.rgb(120, 190, 230), Color.rgb(230, 160, 120), Color.rgb(160, 210, 130),
                Color.rgb(220, 150, 200), Color.rgb(210, 210, 120),
            )
            val packageColor = mutableMapOf<String, Int>()
            sessionsBand.setSegments(
                repository.loadAppSessions(startMs, endMs).map { session ->
                    val color = packageColor.getOrPut(session.packageName) {
                        sessionColors[packageColor.size % sessionColors.size]
                    }
                    TimelineBandView.Segment(session.startMs, session.endMs, color, session.packageName)
                },
            )

            syncBandsToChart()

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
