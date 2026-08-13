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
 */
class TimelineBandView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Segment(val startMs: Long, val endMs: Long, val color: Int, val label: String)

    private var segments: List<Segment> = emptyList()
    private var visibleStartMs: Long = 0L
    private var visibleEndMs: Long = 1L
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val span = (visibleEndMs - visibleStartMs).toFloat()
        if (span <= 0f || width == 0) return
        for (segment in segments) {
            if (segment.endMs < visibleStartMs || segment.startMs > visibleEndMs) continue
            val left = ((segment.startMs - visibleStartMs) / span * width).coerceIn(0f, width.toFloat())
            val right = ((segment.endMs - visibleStartMs) / span * width).coerceIn(0f, width.toFloat())
            if (right <= left) continue
            paint.color = segment.color
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
        }
    }
}
