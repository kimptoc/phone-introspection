package net.kimptoc.introspect.timeline

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import kotlinx.coroutines.launch
import net.kimptoc.introspect.R

/**
 * Phase 5 (spec §6): battery, thermal, Doze/screen-on, and app-session
 * data on a shared, pan/zoomable time axis. General-purpose data browser,
 * not a single-workflow view - see the design doc
 * (docs/superpowers/specs/2026-08-13-phase5-timeline-design.md) for why.
 */
class TimelineActivity : ComponentActivity() {

    private lateinit var repository: TimelineRepository
    private lateinit var batteryChart: LineChart
    private var currentRange = TimelineRange.LAST_24H

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        // Android 15+ (targetSdk 35+) enforces edge-to-edge, same as
        // MainActivity: without this the range-picker button row renders
        // (and is tappable) underneath the status bar/action bar rather
        // than below it. Deviation from the task-5 brief's verbatim
        // TimelineActivity.kt, which omits this - discovered on-device
        // when the range buttons were unreachable. Any later full-file
        // rewrite of this Activity (e.g. Task 6) must keep this call.
        applySystemBarInsetsAsPadding(findViewById(R.id.timelineRootLayout))
        repository = TimelineRepository(this)
        batteryChart = findViewById(R.id.batteryChart)

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        loadRange(currentRange)
    }

    private fun loadRange(range: TimelineRange) {
        currentRange = range
        lifecycleScope.launch {
            val (startMs, endMs) = repository.resolveRange(range)
            // Chart/band wiring lands in Task 6/7; this task only proves
            // navigation and range resolution work end to end.
            android.util.Log.d("TimelineActivity", "range=$range startMs=$startMs endMs=$endMs")
        }
    }

    /**
     * Mirrors MainActivity.applySystemBarInsetsAsPadding: without it,
     * edge-to-edge draws this screen's content behind the status bar and
     * the window's action bar, leaving the top row of range buttons
     * visually hidden and untappable (confirmed on-device).
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
