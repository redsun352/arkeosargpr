package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.R
import kotlin.math.abs

/**
 * Single vertical bar showing the current (boosted, deadzone-filtered)
 * signal magnitude as a quick-glance intensity indicator, alongside the
 * scrolling line graph. Fill height is proportional to |value| against
 * a self-adjusting ceiling so the bar stays informative whether the
 * gain control is set low or very high.
 *
 * Written from scratch for ArkeoSAR Ground Scan.
 */
class LiveColumnGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var currentValue: Float = 0f
    private var ceiling: Float = 1f // adapts upward as stronger readings come in

    private val fillPaint = Paint().apply { isAntiAlias = true }
    private val trackPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.surface_panel_raised)
        style = Paint.Style.FILL
    }

    /** Updates the bar with a new (already gain/deadzone-processed) reading. */
    fun setValue(value: Float) {
        currentValue = value
        val magnitude = abs(value)
        if (magnitude > ceiling) ceiling = magnitude
        // Slowly relax the ceiling back down so the bar doesn't stay
        // permanently squashed after one strong transient reading.
        else ceiling = (ceiling * 0.98f).coerceAtLeast(magnitude).coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), trackPaint)

        val fraction = (abs(currentValue) / ceiling).coerceIn(0f, 1f)
        val fillTop = height.toFloat() * (1f - fraction)

        fillPaint.shader = LinearGradient(
            0f, height.toFloat(), 0f, 0f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.anomaly_mid),
                ContextCompat.getColor(context, R.color.anomaly_high),
                ContextCompat.getColor(context, R.color.anomaly_critical)
            ),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, fillTop, width.toFloat(), height.toFloat(), fillPaint)
    }
}
