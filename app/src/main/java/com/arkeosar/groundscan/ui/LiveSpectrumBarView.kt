package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.arkeosar.groundscan.render.AnomalyColorScale
import kotlin.math.max

/**
 * Multi-bar spectrum display: one vertical bar per frequency bin,
 * colored by [AnomalyColorScale] according to that bin's relative
 * magnitude. Visually similar to a classic audio spectrum analyzer -
 * this is a standard, generic way to show a magnitude-per-bin array
 * and isn't tied to any specific reference UI's internals.
 *
 * Intended to sit above the line graph and be fed the same
 * [com.arkeosar.groundscan.sensors.SimpleFft.lowFrequencyMagnitudes]
 * output already computed for the frequency line graph, so the two
 * views show the same data in two complementary forms (bars for a
 * quick magnitude-by-bin glance, line for the shape over the window).
 *
 * Written from scratch for ArkeoSAR Ground Scan.
 */
class LiveSpectrumBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var magnitudes: List<Float> = emptyList()
    private var ceiling: Float = 1f

    private val barPaint = Paint().apply { isAntiAlias = true }

    /** Replaces the bin magnitudes shown and triggers a redraw. */
    fun updateMagnitudes(newMagnitudes: List<Float>) {
        magnitudes = newMagnitudes
        val localMax = newMagnitudes.maxOrNull() ?: 0f
        ceiling = max(ceiling * 0.97f, max(localMax, 0.01f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || magnitudes.isEmpty()) return

        val barCount = magnitudes.size
        val gap = context.resources.displayMetrics.density * 1.5f
        val barWidth = (width.toFloat() - gap * (barCount - 1)) / barCount

        magnitudes.forEachIndexed { index, magnitude ->
            val fraction = (magnitude / ceiling).coerceIn(0f, 1f)
            val barHeight = height.toFloat() * fraction
            val left = index * (barWidth + gap)
            val top = height.toFloat() - barHeight

            val color = AnomalyColorScale.colorFor(fraction)
            barPaint.color = (0xFF shl 24) or
                ((color.r * 255).toInt() shl 16) or
                ((color.g * 255).toInt() shl 8) or
                (color.b * 255).toInt()

            canvas.drawRect(left, top, left + barWidth, height.toFloat(), barPaint)
        }
    }
}
