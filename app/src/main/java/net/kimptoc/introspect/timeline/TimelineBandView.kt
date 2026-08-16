package net.kimptoc.introspect.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
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
        // This call is driven by syncBandsToChart() on every chart pan/zoom
        // gesture, so a require() here (round 3's fix) turned a defensive
        // check into a main-thread crash on whatever gesture callback
        // happened to see MPAndroidChart's ViewPortHandler momentarily
        // report right < left - and that ordering is an assumption about
        // third-party internals, not a verified invariant (bot review
        // round 4 on PR #23). Loud but survivable instead: log a warning
        // (so the bad call still leaves a trace) and clamp to a zero-width
        // band rather than crash the app over a drawing-sync helper.
        if (rightFraction < leftFraction) {
            Log.w(
                "TimelineBandView",
                "setContentInsets: rightFraction ($rightFraction) < leftFraction ($leftFraction); clamping to zero-width"
            )
        }
        val clampedLeft = leftFraction.coerceIn(0f, 1f)
        contentLeftFraction = clampedLeft
        // rightFraction clamped against the already-0f..1f-clamped left, not
        // the raw parameter - coerceIn(min, max) requires min <= max, and an
        // out-of-range leftFraction (e.g. > 1f) would otherwise throw here
        // instead of degrading gracefully like the rest of this method.
        // clampedLeft is always <= 1f, so (clampedLeft, 1f) is always a
        // valid range.
        contentRightFraction = rightFraction.coerceIn(clampedLeft, 1f)
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
