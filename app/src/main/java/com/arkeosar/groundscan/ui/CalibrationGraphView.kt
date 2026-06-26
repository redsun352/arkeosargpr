package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws one of the three calibration scatter plots (XY, YZ, or XZ),
 * matching the reference app's calibration screen: concentric reference
 * circles centered on the view, with the accumulated raw sample points
 * plotted in a given color. A well-calibrated, fully-rotated session
 * traces out a circle/ellipse on this plot; an off-center cluster shows
 * the hard-iron bias that [com.arkeosar.groundscan.sensors.MagnetometerCalibration]
 * corrects for.
 */
class CalibrationGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<Pair<Float, Float>> = emptyList()
    private var pointColor: Int = 0xFFE0483B.toInt()
    private var axisMax: Float = 80f // expected rough magnitude range (µT) for the reference circles

    private val circlePaint = Paint().apply {
        color = 0xFF3A4A45.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = 0xFF3A4A45.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1f
        isAntiAlias = true
    }
    private val pointPaint = Paint().apply { isAntiAlias = true }
    private val labelPaint = Paint().apply {
        color = 0xFF8FA39D.toInt()
        textSize = context.resources.displayMetrics.density * 10f
        isAntiAlias = true
    }

    fun setPointColor(color: Int) {
        pointColor = color
        invalidate()
    }

    fun updatePoints(newPoints: List<Pair<Float, Float>>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.9f

        // Reference circles + crosshair axes, matching the reference app's grid look.
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, radius * i / 3f, circlePaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, axisPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axisPaint)
        canvas.drawText(String.format("%.1f", axisMax), 4f, cy - radius + labelPaint.textSize, labelPaint)

        // Sample points, scaled so axisMax maps to the outer ring.
        pointPaint.color = pointColor
        val scale = radius / axisMax
        for ((a, b) in points) {
            val px = cx + a * scale
            val py = cy - b * scale // screen Y is inverted relative to a math-style plot
            canvas.drawCircle(px, py, 3.5f, pointPaint)
        }
    }
}
