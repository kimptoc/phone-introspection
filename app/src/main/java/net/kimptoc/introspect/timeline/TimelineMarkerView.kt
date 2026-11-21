package net.kimptoc.introspect.timeline

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import net.kimptoc.introspect.R
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shows the exact timestamp/battery%/thermal/Doze/app values for a
 * tapped point, looked up from [TimelineActivity]'s already-loaded range
 * data - no per-tap query, the range is small enough to hold in memory
 * (spec-consistent with every other in-memory-after-one-query pattern in
 * this app; see [TimelineRepository]'s downsampling for why the range
 * stays bounded even at "all time").
 *
 * [downsampled] mirrors [TimelineRange.downsample] for the range this
 * marker was built for: the thermal/Doze/session values it shows come
 * from [lookup] against already-downsampled (first-sample-per-bucket)
 * segments at wide ranges, so the categorical state shown can be stale by
 * up to a bucket width and a state transition inside a bucket is silently
 * dropped. Rather than presenting that as exact, we say so.
 */
class TimelineMarkerView(
    context: Context,
    private val rangeStartMs: Long,
    private val downsampled: Boolean,
    private val lookup: (timestampMs: Long) -> String,
) : MarkerView(context, R.layout.timeline_marker) {

    private val textView: TextView = findViewById(R.id.markerText)
    private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    override fun refreshContent(e: Entry, highlight: Highlight) {
        val timestampMs = rangeStartMs + (e.x * 1000).toLong()
        // Deviates from the plan's verbatim refreshContent body: the plan
        // text and this class's own doc comment both promise "battery%"
        // in the marker, but the plan's literal code never renders e.y
        // (the battery level the Entry was built from). Added rather than
        // silently dropped - the tapped point IS the battery reading, so
        // omitting it here would be the one value the marker exists to
        // show and doesn't.
        var text = "${timeFormat.format(timestampMs)}\nBattery: ${e.y.roundToInt()}%\n${lookup(timestampMs)}"
        if (downsampled) {
            text += "\n(downsampled — times/states approximate)"
        }
        textView.text = text
        super.refreshContent(e, highlight)
    }

    // Gap between the finger's highlight line and the tooltip's near edge.
    private val sideGapPx = 16f * context.resources.displayMetrics.density
    private val drawOffset = MPPointF()

    /**
     * Places the tooltip beside the tapped point rather than centred on it
     * (issue #33): centred-above put it right under the finger, hiding
     * both the tooltip and the graph. It goes on whichever side of the
     * point has more room - right of a point in the left half of the
     * chart, left of one in the right half - so the point itself and the
     * vertical highlight line marking it stay visible. Vertically it's
     * centred on the point, then clamped inside the chart.
     */
    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        val chartWidth = chartView?.width?.toFloat() ?: 0f
        val chartHeight = chartView?.height?.toFloat() ?: 0f

        val placeRight = posX < chartWidth / 2f
        var dx = if (placeRight) sideGapPx else -(width + sideGapPx)
        // Very wide tooltip on a narrow chart: keep it on-screen even if
        // that means overlapping the point.
        if (chartWidth > 0f) {
            dx = dx.coerceIn(-posX, (chartWidth - width - posX).coerceAtLeast(-posX))
        }

        var dy = -height / 2f
        if (chartHeight > 0f) {
            dy = dy.coerceIn(-posY, (chartHeight - height - posY).coerceAtLeast(-posY))
        }

        drawOffset.x = dx
        drawOffset.y = dy
        return drawOffset
    }
}
