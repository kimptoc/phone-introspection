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
import com.github.mikephil.charting.components.YAxis
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
import net.kimptoc.introspect.db.TimestampNum
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
    private lateinit var processChart: LineChart
    private lateinit var emptyStateText: TextView
    private lateinit var thermalBand: TimelineBandView
    private lateinit var dozeBand: TimelineBandView
    private lateinit var sessionsBand: TimelineBandView
    private lateinit var processesLabel: TextView

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
    private var loadedTemperature: List<TimestampNum> = emptyList()
    private var loadedMemory: List<TimestampNum> = emptyList()
    private var loadedProcessCount: List<TimestampNum> = emptyList()

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
        processChart = findViewById(R.id.processChart)
        emptyStateText = findViewById(R.id.timelineEmptyStateText)
        thermalBand = findViewById(R.id.thermalBand)
        dozeBand = findViewById(R.id.dozeBand)
        sessionsBand = findViewById(R.id.sessionsBand)
        processesLabel = findViewById(R.id.processesLabel)
        batteryChart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) {
                syncBandsToChart()
                syncProcessChartToBattery()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {
                syncBandsToChart()
                syncProcessChartToBattery()
            }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {
                syncBandsToChart()
                syncProcessChartToBattery()
            }
        }
        batteryChart.description.isEnabled = false

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        findViewById<TextView>(R.id.chartLabel).setOnClickListener { showHelp(R.string.timeline_chart_title, R.string.timeline_help_chart) }
        processesLabel.setOnClickListener { showHelp(R.string.timeline_processes_title, R.string.timeline_help_processes) }
        findViewById<TextView>(R.id.thermalLabel).setOnClickListener { showHelp(R.string.timeline_thermal_title, R.string.timeline_help_thermal) }
        findViewById<TextView>(R.id.dozeLabel).setOnClickListener { showHelp(R.string.timeline_doze_title, R.string.timeline_help_doze) }
        findViewById<TextView>(R.id.sessionsLabel).setOnClickListener { showHelp(R.string.timeline_sessions_title, R.string.timeline_help_sessions) }

        loadRange(TimelineRange.LAST_24H)
    }

    /**
     * [titleRes] is its own dedicated string (e.g. [R.string.timeline_thermal_title]),
     * not the label string reused with the trailing " ⓘ" stripped off -
     * that string-match strip only held while the label copy and the
     * stripped literal stayed byte-for-byte identical, so any future edit
     * to the label (different spacing, a different Unicode glyph) would
     * have silently put the ⓘ back in the dialog title with no error
     * (bot review round 2 on PR #26). Separate resources decouple the two
     * completely instead of relying on that coincidence.
     */
    private fun showHelp(titleRes: Int, messageRes: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun xToTimestamp(x: Float): Long = rangeStartMs + (x * 1000).toLong()

    /**
     * Nearest sample to [timestampMs] within [maxDistanceMs], or null if
     * the closest one is further away than that - a real gap (before this
     * signal existed, or monitoring was off), not a value worth showing
     * as "close enough". Used for [loadedTemperature]/[loadedMemory]/
     * [loadedProcessCount] in the marker lookup below: unlike the
     * segment-based signals (thermal/
     * idle/screen-on, each held from one change to the next with an
     * explicit end), these are periodic point samples with no
     * "held-until" semantics of their own to look up a timestamp against.
     *
     * [maxDistanceMs] has no default - callers must size it to the
     * range's actual data spacing. A fixed 30-minute window (this
     * function's original shape) covers normal gaps at raw cadence
     * (24h/3d), but 7d/ALL_TIME load through
     * [TimelineRepository.loadNumeric]'s SQL bucketing, where adjacent
     * points can legitimately sit far more than 30 minutes apart once the
     * dataset spans weeks - a fixed cutoff would silently hide real,
     * correctly-loaded data on exactly the range where "all time" is the
     * whole point (bot review on PR #25). [loadRange] below derives the
     * window from [TimelineRepository.bucketMsFor] for this reason.
     */
    private fun nearestNum(samples: List<TimestampNum>, timestampMs: Long, maxDistanceMs: Long): Double? {
        val nearest = samples.filter { it.valueNum != null }.minByOrNull { abs(it.timestamp - timestampMs) } ?: return null
        return nearest.valueNum.takeIf { abs(nearest.timestamp - timestampMs) <= maxDistanceMs }
    }

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
     * Keeps [processChart] showing exactly the time window the battery
     * chart shows, in value space (no pixel math, unlike the bands' inset
     * hand-off): force this chart's visible span to the battery chart's
     * span, then align its left edge to the battery chart's left edge.
     * Both charts share the same X domain (seconds since the range's
     * start), so matching visible-X ranges means the same timestamps sit
     * above each other. Called from the same gesture hooks as
     * [syncBandsToChart], plus once per [loadRange].
     *
     * The `span <= 1f` guard skips a battery chart with no data (its
     * degenerate ~1-second default axis range) - nothing to mirror then,
     * and forcing a 1-second window onto this chart would hide its own
     * data. No-op while this chart is hidden (no process_count samples in
     * the loaded range).
     */
    private fun syncProcessChartToBattery() {
        if (!processChart.isShown) return
        val span = batteryChart.highestVisibleX - batteryChart.lowestVisibleX
        if (span <= 1f) return
        processChart.setVisibleXRange(span, span)
        processChart.moveViewToX(batteryChart.lowestVisibleX)
    }

    /**
     * Loads and renders one range. Each signal (battery, memory,
     * temperature, process count, thermal, Doze/screen-on, sessions) is
     * loaded and rendered independently - there is no shared early-return
     * on any one signal being empty, because an empty battery table
     * doesn't imply an empty thermal/Doze/sessions table (and vice versa).
     * The "No data in this range" text only shows when ALL of them come
     * back empty.
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
            val batteryDataSet = LineDataSet(entries, getString(R.string.timeline_series_battery)).apply {
                color = Color.BLUE
                setDrawCircles(false)
                lineWidth = 2f
                axisDependency = YAxis.AxisDependency.LEFT
            }

            // Shares the left 0-100 axis with battery (both are percentages).
            loadedMemory = repository.loadMemoryAvailPct(startMs, endMs)
            val memoryEntries = loadedMemory.mapNotNull { row ->
                row.valueNum?.let { Entry(toX(row.timestamp), it.toFloat()) }
            }
            val memoryDataSet = LineDataSet(memoryEntries, getString(R.string.timeline_series_memory)).apply {
                color = Color.rgb(0, 150, 80)
                setDrawCircles(false)
                lineWidth = 2f
                axisDependency = YAxis.AxisDependency.LEFT
                // The marker (TimelineMarkerView) assumes the highlighted
                // Entry's y IS the battery reading - it always has, since
                // this chart only ever held one series before. Disabling
                // highlight on this dataset (and temperature's, below)
                // keeps that assumption true now that there are three,
                // rather than rewriting the marker to know which dataset
                // it landed on.
                setHighlightEnabled(false)
            }

            // Own right-axis scale (°C), separate from the two 0-100 percentages.
            loadedTemperature = repository.loadTemperature(startMs, endMs)
            val temperatureEntries = loadedTemperature.mapNotNull { row ->
                row.valueNum?.let { Entry(toX(row.timestamp), it.toFloat()) }
            }
            val temperatureDataSet = LineDataSet(temperatureEntries, getString(R.string.timeline_series_temperature)).apply {
                color = Color.rgb(220, 100, 40)
                setDrawCircles(false)
                lineWidth = 2f
                axisDependency = YAxis.AxisDependency.RIGHT
                setHighlightEnabled(false)
            }

            batteryChart.data = LineData(batteryDataSet, memoryDataSet, temperatureDataSet)
            batteryChart.axisRight.isEnabled = true
            // Three distinctly-colored series now, unlike the single
            // blank-label one this chart used to hold (which is why the
            // legend used to be disabled outright, per bot review on PR
            // #21) - a legend is the only way to tell them apart.
            batteryChart.legend.isEnabled = true
            // Shared with processChart below: both charts cover the same
            // (startMs, endMs) window on the same X domain, so their time
            // labels are identical by construction - one formatter keeps
            // them that way instead of two copies drifting apart.
            val xValueFormatter = object : ValueFormatter() {
                private val format = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                override fun getFormattedValue(value: Float): String =
                    format.format(Date(startMs + (value * 1000).toLong()))
            }
            batteryChart.xAxis.valueFormatter = xValueFormatter
            // Cap the number of X-axis labels drawn: "MMM d HH:mm" is ~13
            // chars, and MPAndroidChart's default label count crowds and
            // overlaps that many of them across the chart width. 4 slots
            // gives each label enough room to stay legible at this width.
            batteryChart.xAxis.setLabelCount(4, false)
            batteryChart.notifyDataSetChanged()
            batteryChart.invalidate()

            // Own chart rather than a fourth series on the battery chart:
            // the count (~300-1,100) would flatten temperature's ~20-45°C
            // right-axis range into a smear if they shared an axis, and
            // MPAndroidChart only has two Y-axes - both already taken
            // (0-100% left, °C right). Touch is disabled so the battery
            // chart stays the single pan/zoom driver and this chart just
            // follows it (syncProcessChartToBattery), the same one-way
            // relationship the bands below already have.
            loadedProcessCount = repository.loadProcessCount(startMs, endMs)
            val processEntries = loadedProcessCount.mapNotNull { row ->
                row.valueNum?.let { Entry(toX(row.timestamp), it.toFloat()) }
            }
            val processDataSet = LineDataSet(processEntries, getString(R.string.timeline_series_processes)).apply {
                color = Color.rgb(140, 90, 210)
                setDrawCircles(false)
                lineWidth = 2f
                axisDependency = YAxis.AxisDependency.LEFT
            }
            processChart.data = LineData(processDataSet)
            processChart.description.isEnabled = false
            // Single series - the section label above already names it, so a
            // legend would only cost vertical space (the battery chart's
            // legend exists because IT has three series to tell apart).
            processChart.legend.isEnabled = false
            processChart.axisRight.isEnabled = false
            processChart.setTouchEnabled(false)
            processChart.xAxis.valueFormatter = xValueFormatter
            processChart.xAxis.setLabelCount(4, false)
            processChart.notifyDataSetChanged()
            processChart.invalidate()
            // Hidden entirely when there's no data (e.g. Shizuku never
            // granted) rather than showing an empty chart with axes: the
            // common no-Shizuku case shouldn't carry a permanent blank
            // section on this screen.
            val processesVisible = loadedProcessCount.isNotEmpty()
            processesLabel.visibility = if (processesVisible) View.VISIBLE else View.GONE
            processChart.visibility = if (processesVisible) View.VISIBLE else View.GONE

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
                loadedScreenOn.isEmpty() && loadedSessions.isEmpty() && loadedProcessCount.isEmpty()
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
            syncProcessChartToBattery()

            // A tap is never more than half a bucket from the nearest
            // loaded point, so bucketMs is a safe, self-scaling bound -
            // 30 min stays the floor for raw-cadence ranges (24h/3d),
            // where bucketMsFor returns null (bot review on PR #25).
            val nearestWindowMs = maxOf(30 * 60 * 1000L, repository.bucketMsFor(startMs, endMs) ?: 0L)

            batteryChart.marker = TimelineMarkerView(this@TimelineActivity, startMs, range.downsample) { timestampMs ->
                buildString {
                    append(nearestNum(loadedTemperature, timestampMs, nearestWindowMs)?.let { "Temp: %.1f°C\n".format(it) } ?: "")
                    append(loadedThermal.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Thermal: $it\n" } ?: "")
                    append(loadedDeviceIdle.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Idle: $it\n" } ?: "")
                    append(loadedScreenOn.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Screen on: $it\n" } ?: "")
                    append(nearestNum(loadedMemory, timestampMs, nearestWindowMs)?.let { "Mem avail: %.0f%%\n".format(it) } ?: "")
                    append(nearestNum(loadedProcessCount, timestampMs, nearestWindowMs)?.let { "Processes: %.0f\n".format(it) } ?: "")
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
