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
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.kimptoc.introspect.R
import net.kimptoc.introspect.collector.t1.UsageAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5 (spec §6): battery, thermal, Doze/screen-on, and app-session
 * data on a shared, pan/zoomable time axis. General-purpose data browser,
 * not a single-workflow view - see the design doc
 * (docs/superpowers/specs/2026-08-13-phase5-timeline-design.md) for why.
 *
 * Chart X values are seconds-since-range-start, not raw epoch millis - see
 * [loadRange]'s doc comment for why that's a local `startMs`, not the
 * [rangeStartMs] field, within any single [loadRange] call.
 */
class TimelineActivity : ComponentActivity() {

    private lateinit var repository: TimelineRepository
    private lateinit var batteryChart: LineChart
    private lateinit var emptyStateText: TextView
    private lateinit var thermalBand: TimelineBandView
    private lateinit var dozeBand: TimelineBandView
    private lateinit var sessionsBand: TimelineBandView

    // Only read by syncBandsToChart/xToTimestamp (gesture/marker-sync code
    // that legitimately needs "what range is currently on screen") and
    // updated once, at the end of a successful loadRange call - never read
    // for that call's OWN chart-building math, which uses the startMs/endMs
    // locals instead (see loadRange's doc comment for why).
    private var rangeStartMs = 0L

    private var loadedThermal: List<TimelineSegment<String>> = emptyList()
    private var loadedDeviceIdle: List<TimelineSegment<Boolean>> = emptyList()
    private var loadedScreenOn: List<TimelineSegment<Boolean>> = emptyList()
    private var loadedSessions: List<AppSession> = emptyList()

    /** The [Job] of the in-flight [loadRange] call, if any - cancelled when a new one starts. */
    private var loadJob: Job? = null

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

    private fun xToTimestamp(x: Float): Long = rangeStartMs + (x * 1000).toLong()

    private fun syncBandsToChart() {
        val startMs = xToTimestamp(batteryChart.lowestVisibleX)
        val endMs = xToTimestamp(batteryChart.highestVisibleX)
        // The chart's plot area is inset from its own view bounds by the
        // Y-axis value labels (and the right axis) - without passing this
        // through, the bands (which draw across their own full width)
        // were visibly wider than the data they're meant to align under.
        // Recomputed on every call rather than once: cheap reads, and the
        // inset can genuinely change between ranges if Y-axis label width
        // changes (e.g. "100" vs a narrower value). Sent as fractions of
        // the chart's own width, not raw pixels - see TimelineBandView's
        // setContentInsets doc for why (bot review on PR #23).
        val chartWidth = batteryChart.width.toFloat()
        val contentLeftFraction = if (chartWidth > 0f) batteryChart.viewPortHandler.contentLeft() / chartWidth else 0f
        val contentRightFraction = if (chartWidth > 0f) batteryChart.viewPortHandler.contentRight() / chartWidth else 1f
        thermalBand.setVisibleRange(startMs, endMs)
        thermalBand.setContentInsets(contentLeftFraction, contentRightFraction)
        dozeBand.setVisibleRange(startMs, endMs)
        dozeBand.setContentInsets(contentLeftFraction, contentRightFraction)
        sessionsBand.setVisibleRange(startMs, endMs)
        sessionsBand.setContentInsets(contentLeftFraction, contentRightFraction)
    }

    /**
     * Loads and renders one range. Each signal (battery, thermal, Doze/
     * screen-on, sessions) is loaded and rendered independently - there is
     * no shared early-return on any one signal being empty, because an
     * empty battery table doesn't imply an empty thermal/Doze/sessions
     * table (and vice versa). The "No data in this range" text only shows
     * when ALL of them come back empty.
     *
     * Uses `startMs`/`endMs` locals (from [TimelineRepository.resolveRange],
     * itself `suspend`) for every X-axis conversion done as part of THIS
     * call, rather than the `rangeStartMs`/`rangeEndMs` fields - those
     * fields are written by whichever call finishes last, so if two
     * `loadRange` calls were ever in flight at once (e.g. two range-button
     * taps in quick succession) and this call's math read the fields
     * instead of locals, one call's chart could be built against the
     * OTHER call's range origin. [loadJob] cancellation (below) already
     * prevents two calls from actually overlapping, but the local-var
     * fix removes the bug class outright rather than relying solely on
     * that guard.
     */
    private fun loadRange(range: TimelineRange) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val (startMs, endMs) = repository.resolveRange(range)
            val toX = { ts: Long -> (ts - startMs) / 1000f }

            val battery = repository.loadBattery(startMs, endMs)
            val entries = battery.mapNotNull { row ->
                row.valueNum?.let { Entry(toX(row.timestamp), it.toFloat()) }
            }
            val dataSet = LineDataSet(entries, "").apply {
                color = Color.BLUE
                setDrawCircles(false)
                lineWidth = 2f
            }
            batteryChart.data = LineData(dataSet)
            // This chart only ever holds one series and its own description
            // is already hidden - a legend entry for a blank label is just
            // an empty box, not information (bot review on PR #21).
            batteryChart.legend.isEnabled = false
            batteryChart.xAxis.valueFormatter = object : ValueFormatter() {
                private val format = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                override fun getFormattedValue(value: Float): String =
                    format.format(Date(startMs + (value * 1000).toLong()))
            }
            // Cap the number of X-axis labels drawn: "MMM d HH:mm" is ~13
            // chars, and MPAndroidChart's default label count crowds and
            // overlaps that many of them across the chart width. 4 slots
            // gives each label enough room to stay legible at this width.
            batteryChart.xAxis.setLabelCount(4, false)
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
            loadedThermal = repository.loadThermal(startMs, endMs)
            thermalBand.setSegments(
                loadedThermal.map {
                    TimelineBandView.Segment(it.startMs, it.endMs, thermalColors[it.value] ?: Color.LTGRAY, it.value)
                },
            )

            loadedDeviceIdle = repository.loadDeviceIdle(startMs, endMs)
            loadedScreenOn = repository.loadScreenOn(startMs, endMs)
            val deviceIdle = loadedDeviceIdle
            val screenOn = loadedScreenOn
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
            loadedSessions = repository.loadAppSessions(startMs, endMs)
            sessionsBand.setSegments(
                loadedSessions.map { session ->
                    val color = packageColor.getOrPut(session.packageName) {
                        sessionColors[packageColor.size % sessionColors.size]
                    }
                    TimelineBandView.Segment(session.startMs, session.endMs, color, session.packageName)
                },
            )

            emptyStateText.visibility = if (
                battery.isEmpty() && loadedThermal.isEmpty() && loadedDeviceIdle.isEmpty() &&
                loadedScreenOn.isEmpty() && loadedSessions.isEmpty()
            ) {
                emptyStateText.text = getString(R.string.timeline_no_data)
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

            // Updated now, after all per-call chart-building math above is
            // done reading startMs as a local - see loadRange's doc
            // comment. syncBandsToChart/xToTimestamp below legitimately
            // need the current range's start in this field.
            rangeStartMs = startMs

            syncBandsToChart()

            batteryChart.marker = TimelineMarkerView(this@TimelineActivity, startMs, range.downsample) { timestampMs ->
                buildString {
                    append(loadedThermal.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Thermal: $it\n" } ?: "")
                    append(loadedDeviceIdle.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Idle: $it\n" } ?: "")
                    append(loadedScreenOn.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Screen on: $it\n" } ?: "")
                    append(loadedSessions.firstOrNull { timestampMs in it.startMs..it.endMs }?.packageName?.let { "App: $it" } ?: "")
                }.trimEnd()
            }

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
