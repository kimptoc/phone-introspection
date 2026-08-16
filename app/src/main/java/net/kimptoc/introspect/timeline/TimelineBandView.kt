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
 * data they're meant to line up under. Insets are fractions of the
 * *chart's* own width (0f..1f), not raw pixels: [TimelineActivity]
 * currently relies on this view and the chart sharing the same
 * `match_parent` width in the same parent layout, which makes a raw
 * pixel copy exactly correct today - but a fraction scaled by *this*
 * view's own width in [onDraw] stays a close approximation even if that
 * assumption ever stops holding (a margin added to one but not the
 * other, a different container), instead of silently drifting by a
 * fixed pixel offset with nothing to catch it (bot review on PR #23).
 */
class TimelineBandView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Segment(val startMs: Long, val endMs: Long, val color: Int, val label: String)

    private var segments: List<Segment> = emptyList()
    private var visibleStartMs: Long = 0L
    private var visibleEndMs: Long = 1L
    private var contentLeftFraction: Float = 0f
    private var contentRightFraction: Float? = null // null means "unset, use full width"
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

    /** [leftFraction]/[rightFraction] are fractions (0f..1f) of the reference width, e.g. the chart's own. */
    fun setContentInsets(leftFraction: Float, rightFraction: Float) {
        // A coerce here would still let a right < left call through as a
        // silently zero-width (invisible) band - no different from the
        // silent erase this guards against, just with numbers that read
        // back as "healthy" (bot review round 2 on PR #23). require()
        // instead: the only caller derives both values from MPAndroidChart's
        // own ViewPortHandler, where right >= left is a real invariant, so
        // this should never fire outside a genuine programming error -
        // exactly the case for failing loudly rather than swallowing it.
        require(rightFraction >= leftFraction) {
            "setContentInsets: rightFraction ($rightFraction) < leftFraction ($leftFraction)"
        }
        contentLeftFraction = leftFraction.coerceIn(0f, 1f)
        contentRightFraction = rightFraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val span = (visibleEndMs - visibleStartMs).toFloat()
        if (span <= 0f || width == 0) return
        val drawLeft = contentLeftFraction * width
        val drawRight = (contentRightFraction ?: 1f) * width
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
