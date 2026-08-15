package net.kimptoc.introspect.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws a horizontal strip of colored intervals (thermal state, Doze/
 * screen-on state, or app sessions - all the same shape: a value held
 * from one timestamp to the next). Not an MPAndroidChart `BarData` grid:
 * these intervals are variable-width and don't sit on a regular tick
 * grid, which `BarData` assumes. [setVisibleRange] is driven externally
 * by [TimelineActivity] from the battery [com.github.mikephil.charting.charts.LineChart]'s
 * own pan/zoom state, keeping all bands in sync with it without this
 * view needing its own gesture handling.
 *
 * [setContentInsets] does the same for horizontal alignment: this view
 * spans its full measured width, but the chart above it doesn't - its
 * plot area is inset on the left by the Y-axis value labels (and
 * potentially the right, if a right axis is enabled), so drawing bands
 * across this view's *entire* width made them visibly wider than the
 * data they're meant to line up under. [TimelineActivity] reads the
 * chart's real plot-area bounds via its `ViewPortHandler` and passes
 * them straight through, since both this view and the chart share the
 * same `match_parent` width within the same parent layout - no
 * coordinate transform needed, just reusing the same pixel offsets.
 */
class TimelineBandView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Segment(val startMs: Long, val endMs: Long, val color: Int, val label: String)

    private var segments: List<Segment> = emptyList()
    private var visibleStartMs: Long = 0L
    private var visibleEndMs: Long = 1L
    private var contentLeft: Float = 0f
    private var contentRight: Float = -1f // -1 means "unset, use full width"
    private val paint = Paint()

    fun setSegments(newSegments: List<Segment>) {
        segments = newSegments
        invalidate()
    }

    fun setVisibleRange(startMs: Long, endMs: Long) {
        visibleStartMs = startMs
        visibleEndMs = endMs.coerceAtLeast(startMs + 1)
        invalidate()
    }

    fun setContentInsets(left: Float, right: Float) {
        contentLeft = left
        contentRight = right
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val span = (visibleEndMs - visibleStartMs).toFloat()
        if (span <= 0f || width == 0) return
        val drawLeft = contentLeft
        val drawRight = if (contentRight < 0f) width.toFloat() else contentRight
        val drawWidth = drawRight - drawLeft
        if (drawWidth <= 0f) return
        for (segment in segments) {
            if (segment.endMs < visibleStartMs || segment.startMs > visibleEndMs) continue
            val left = (drawLeft + (segment.startMs - visibleStartMs) / span * drawWidth).coerceIn(drawLeft, drawRight)
            val right = (drawLeft + (segment.endMs - visibleStartMs) / span * drawWidth).coerceIn(drawLeft, drawRight)
            if (right <= left) continue
            paint.color = segment.color
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
        }
    }
}
