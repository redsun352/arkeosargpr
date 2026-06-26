package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.R

/**
 * Scrolling line graph for the Detector screen's live (boosted) signal.
 *
 * Functionally similar to a strip-chart / oscilloscope trace: each call
 * to [updateValues] redraws the full visible history as a single
 * polyline, auto-scaled so the current min/max of the buffer always
 * fills the view's height. Rounded path joints (via [CornerPathEffect])
 * keep the trace readable instead of looking jagged at typical sensor
 * sample rates.
 *
 * Written from scratch for ArkeoSAR Ground Scan; the auto-scaling
 * polyline approach is the standard way to draw this kind of live
 * strip-chart and isn't tied to any particular reference implementation.
 */
class LiveLineGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Float> = emptyList()

    private val linePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.signal_cyan)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
        isAntiAlias = true
        pathEffect = CornerPathEffect(context.resources.displayMetrics.density * 8f)
    }
    private val zeroLinePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.grid_line)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1f
        isAntiAlias = true
    }
    private val path = Path()

    /** Replaces the visible history and triggers a redraw. */
    fun updateValues(newValues: List<Float>) {
        values = newValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || values.size < 2) return

        val min = values.min()
        val max = values.max()
        // Guard against a flat-zero buffer (e.g. right after reset) so
        // the trace doesn't collapse to a single pixel row.
        val range = (max - min).let { if (it < 0.001f) 1f else it }

        val widthScale = width.toFloat() / (values.size - 1)
        val heightScale = height.toFloat() / range

        fun yFor(value: Float) = height.toFloat() - (value - min) * heightScale

        // Zero-reference line, useful once a deadzone is applied so
        // values below threshold visibly sit on this baseline.
        if (min < 0f && max > 0f) {
            val zeroY = yFor(0f)
            canvas.drawLine(0f, zeroY, width.toFloat(), zeroY, zeroLinePaint)
        }

        path.reset()
        values.forEachIndexed { index, value ->
            val x = index * widthScale
            val y = yFor(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
