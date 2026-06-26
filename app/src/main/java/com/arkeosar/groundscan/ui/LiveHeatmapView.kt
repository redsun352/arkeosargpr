package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.arkeosar.groundscan.render.AnomalyColorScale

/**
 * Single-point "heatmap" view for the Detector screen, inspired by
 * Thuban Lodestar's mGLView panel: instead of a full spatial grid (which
 * needs the person to walk a survey area, like [ScanActivity]'s
 * [com.arkeosar.groundscan.render.HeightmapRenderer]), this shows the
 * *live* reading at the phone's current position as a centered color
 * blob - useful for quickly judging "is the signal here strong or weak"
 * without needing a full grid scan.
 *
 * The color and the blob's spread both come from [AnomalyColorScale],
 * the same palette used by the real grid renderer, so the Detector
 * screen's color language matches [ScanActivity]'s. The intensity fed
 * in is whatever the currently selected [com.arkeosar.groundscan.render.DisplayFunction]
 * computed from the live X/Y/Z reading - selecting a different function
 * changes both the number shown and the blob's color/size here.
 *
 * Written from scratch for ArkeoSAR Ground Scan; a centered radial blob
 * whose color encodes value is a standard, generic way to visualize a
 * single live scalar and isn't tied to any specific reference UI.
 */
class LiveHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var normalized: Float = 0f // 0..1, where the value sits between observed min/max
    private var ceiling: Float = 1f
    private var minObserved: Float = 0f

    private val paint = Paint().apply { isAntiAlias = true }

    /**
     * Feeds a new (already function-selected) intensity value. Min/max
     * tracking auto-expands so the blob stays visually meaningful as
     * the gain control changes the value's scale, the same adaptive
     * approach [LiveColumnGraphView] uses.
     */
    fun setValue(value: Float) {
        if (value > ceiling) ceiling = value
        if (value < minObserved) minObserved = value
        // Slowly relax both bounds back toward the latest value so a
        // single strong transient doesn't permanently flatten the scale.
        ceiling = (ceiling * 0.995f).coerceAtLeast(value)
        minObserved = (minObserved * 0.995f).coerceAtMost(value)

        val span = (ceiling - minObserved).let { if (it < 0.001f) 1f else it }
        normalized = ((value - minObserved) / span).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = (maxOf(width, height) / 2f) * (0.5f + normalized * 0.9f)

        val centerColor = AnomalyColorScale.colorFor(normalized)
        val edgeColor = AnomalyColorScale.colorFor((normalized * 0.4f))

        fun toArgb(c: AnomalyColorScale.Rgb) =
            (0xFF shl 24) or
                ((c.r * 255).toInt() shl 16) or
                ((c.g * 255).toInt() shl 8) or
                (c.b * 255).toInt()

        paint.shader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            toArgb(centerColor), toArgb(edgeColor),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
