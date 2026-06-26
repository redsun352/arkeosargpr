package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.render.AnomalyColorScale
import com.arkeosar.groundscan.render.SchematicDepthModel

/**
 * Renders a B-Scan style vertical cross-section: horizontal axis is
 * position along a chosen grid row or column, vertical axis is depth,
 * and color is anomaly strength - the same visual language used by
 * real Ground Penetrating Radar (GPR) software.
 *
 * This is **not** GPR data. A phone magnetometer measures a static
 * magnetic field at a single point; it has no transmit pulse, no
 * receive antenna, and no time-of-flight measurement, which is what a
 * real GPR uses to derive true depth. What's drawn here is the surface
 * magnetometer reading along the chosen line, projected downward by
 * [SchematicDepthModel]'s dipole-falloff assumption - i.e. the same
 * schematic/estimated depth data used in the volumetric view, just
 * displayed in the cross-section layout GPR users are used to reading,
 * rather than as a 3D shape. A target buried at a known depth does
 * **not** produce the characteristic hyperbola shape a real GPR B-scan
 * would show, because that shape comes from time-of-flight geometry
 * this app has no way to measure - simulating one would be fabricating
 * data, so this view doesn't attempt it. The persistent label in the
 * layout (see activity_bscan.xml) makes this distinction visible at
 * all times, not just on first open.
 */
class BScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var depthSlices: Array<FloatArray> = emptyArray() // [depthIndex][positionIndex]
    private var maxDepth: Double = 5.0
    private var rangeMin = 0f
    private var rangeMax = 1f

    private val cellPaint = Paint().apply { isAntiAlias = false }
    private val gridPaint = Paint().apply {
        color = 0xFF23332E.toInt()
        strokeWidth = context.resources.displayMetrics.density * 1f
    }
    private val labelPaint = Paint().apply {
        color = 0xFF8FA39D.toInt()
        textSize = context.resources.displayMetrics.density * 11f
        isAntiAlias = true
    }

    /**
     * Computes and displays the cross-section along one row or column
     * of [grid]. [assumedDepth] / [maxDepthInGridUnits] match the same
     * parameters used by the volumetric view, so switching between the
     * two views shows a consistent depth model.
     */
    fun showLine(
        grid: ScanGrid,
        lineIndex: Int,
        alongRow: Boolean,
        depthSliceCount: Int = 40,
        maxDepthInGridUnits: Double = 5.0,
        assumedDepth: Double = SchematicDepthModel.DEFAULT_ASSUMED_DEPTH
    ) {
        maxDepth = maxDepthInGridUnits
        val positionCount = if (alongRow) grid.columns else grid.rows

        val surfaceLine = FloatArray(positionCount) { i ->
            val point = if (alongRow) grid.pointAt(lineIndex, i) else grid.pointAt(i, lineIndex)
            point.value
        }

        val filled = surfaceLine.filter { !it.isNaN() }
        rangeMin = if (filled.isEmpty()) 0f else filled.min()
        rangeMax = if (filled.isEmpty()) 1f else filled.max()
        if (rangeMin == rangeMax) rangeMax = rangeMin + 1f

        // Reuse the same 1D falloff math as the volumetric view, applied
        // per position along this single line instead of the full 2D grid.
        val surface2d = Array(1) { surfaceLine }
        val volume = SchematicDepthModel.buildVolume(
            surfaceValues = surface2d,
            depthSlices = depthSliceCount,
            maxDepth = maxDepthInGridUnits,
            assumedDepth = assumedDepth
        )
        depthSlices = Array(depthSliceCount) { d -> volume[d][0] }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || depthSlices.isEmpty()) return

        val positionCount = depthSlices[0].size
        val depthCount = depthSlices.size
        if (positionCount == 0) return

        val cellWidth = width.toFloat() / positionCount
        val cellHeight = height.toFloat() / depthCount

        for (d in 0 until depthCount) {
            for (p in 0 until positionCount) {
                val value = depthSlices[d][p]
                val top = d * cellHeight
                val left = p * cellWidth
                cellPaint.color = if (value.isNaN()) {
                    0xFF141B19.toInt()
                } else {
                    val rgb = AnomalyColorScale.colorForRaw(value, rangeMin, rangeMax)
                    val r = (rgb.r * 255).toInt().coerceIn(0, 255)
                    val g = (rgb.g * 255).toInt().coerceIn(0, 255)
                    val b = (rgb.b * 255).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cellPaint)
            }
        }

        // Depth axis gridlines + labels (every quarter of maxDepth).
        for (i in 0..4) {
            val y = height * i / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            val depthLabel = String.format("%.1f m", maxDepth * i / 4.0)
            canvas.drawText(depthLabel, 4f, (y + labelPaint.textSize).coerceAtMost(height.toFloat() - 2f), labelPaint)
        }
    }
}
