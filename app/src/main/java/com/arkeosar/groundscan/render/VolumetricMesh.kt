package com.arkeosar.groundscan.render

import com.arkeosar.groundscan.data.ScanGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Builds the geometry for [ViewMode.VOLUMETRIC_3D]: an organic,
 * "stalactite"-like isosurface showing where [SchematicDepthModel]'s
 * projected pseudo-depth volume exceeds a threshold, modeled on the
 * reference app's volumetric mode (anomalies hang down from the
 * surface as tapering blobs rather than appearing as flat stacked
 * slices).
 *
 * Approach: for each depth slice, run [MarchingSquares] on that
 * slice's schematic-strength field to get the threshold contour, then
 * emit a thin filled disk at that contour's location and depth. Disks
 * shrink/grow slice-to-slice following the contour, and the gaps
 * between same-column disks at adjacent depths are bridged with side
 * quads, so the stack reads as one continuous tapering shape rather
 * than disconnected flat cards. This is a simplified, slice-based
 * isosurface - not full 3D Marching Cubes - chosen as a pragmatic
 * middle ground between visual fidelity and mobile GLES 2.0
 * performance/complexity.
 *
 * Drawn with the alpha-blended, unlit "volumetric" shader program
 * (see [HeightmapRenderer]).
 */
class VolumetricMesh(
    grid: ScanGrid,
    private val depthSliceCount: Int = 14,
    private val assumedDepth: Double = SchematicDepthModel.DEFAULT_ASSUMED_DEPTH,
    private val maxDepthInGridUnits: Double = 5.0,
    private val thresholdFraction: Float = 0.45f
) {

    var vertexBuffer: FloatBuffer
    var colorBuffer: FloatBuffer
    var triangleCount: Int = 0

    var valueRangeMin: Float = 0f
    var valueRangeMax: Float = 1f

    init {
        val cols = grid.columns
        val rows = grid.rows

        val surfaceValues = Array(rows) { row ->
            FloatArray(cols) { col -> grid.pointAt(row, col).value }
        }

        val filledValues = surfaceValues.flatMap { it.toList() }.filter { !it.isNaN() }
        valueRangeMin = if (filledValues.isEmpty()) 0f else filledValues.min()
        valueRangeMax = if (filledValues.isEmpty()) 1f else filledValues.max()
        if (valueRangeMin == valueRangeMax) valueRangeMax = valueRangeMin + 1f

        val volume = SchematicDepthModel.buildVolume(
            surfaceValues = surfaceValues,
            depthSlices = depthSliceCount,
            maxDepth = maxDepthInGridUnits,
            assumedDepth = assumedDepth
        )

        // Threshold is computed *per slice* as a fraction of that
        // slice's own value range, not the surface's - since the
        // inverse-cube falloff shrinks magnitudes with depth, a single
        // surface-derived absolute threshold would either include
        // "everything" near the surface or "nothing" at depth. A
        // per-slice relative threshold keeps "the top thresholdFraction
        // of this slice's anomaly" meaningful at every depth.
        fun thresholdForSlice(sliceField: Array<FloatArray>): Float {
            val values = sliceField.flatMap { it.toList() }.filter { !it.isNaN() }
            if (values.isEmpty()) return 0f
            val lo = values.min()
            val hi = values.max()
            return lo + thresholdFraction * (hi - lo)
        }

        val triangles = mutableListOf<FloatArray>() // each entry: 9 floats (3 verts x xyz)
        val triangleColors = mutableListOf<FloatArray>() // each entry: 12 floats (3 verts x rgba)

        var previousContours: List<MarchingSquares.Segment>? = null
        var previousDepth = 0.0

        for (slice in 0 until depthSliceCount) {
            val sliceDepth = (slice.toDouble() / (depthSliceCount - 1).coerceAtLeast(1)) * maxDepthInGridUnits
            val sliceField = volume[slice]
            val sliceThreshold = thresholdForSlice(sliceField)

            val contours = MarchingSquares.extractContours(sliceField, sliceThreshold)

            val sliceColor = run {
                // Color the slice by the *average* schematic strength of
                // cells currently above threshold at this depth, so the
                // tapering shape's color follows its own strength rather
                // than a fixed hue - matching the reference app's
                // colored (not monochrome) volumetric blobs.
                val aboveThreshold = sliceField.flatMap { it.toList() }.filter { !it.isNaN() && it > sliceThreshold }
                val avg = if (aboveThreshold.isEmpty()) sliceThreshold else aboveThreshold.average().toFloat()
                AnomalyColorScale.colorForRaw(avg, valueRangeMin, valueRangeMax)
            }

            // Fill the contour interior with a thin disk at this depth
            // (a flat fan of triangles per closed loop would be more
            // correct, but for a schematic visualization, short connecting
            // wall quads along each contour segment - emitted below -
            // already convey the shape; we skip an interior cap to keep
            // triangle count low and avoid needing full polygon assembly
            // from unordered marching-squares segments).

            // Side walls: connect this slice's contour to the previous
            // slice's contour wherever segments roughly align, so the
            // stack reads as a continuous tapering wall rather than
            // disconnected rings. Since contours can change topology
            // between slices (a blob splitting or merging), we connect
            // by nearest-segment matching rather than assuming identical
            // segment counts.
            if (previousContours != null && previousContours!!.isNotEmpty() && contours.isNotEmpty()) {
                val prevY = -previousDepth.toFloat()
                val currY = -sliceDepth.toFloat()
                for (seg in contours) {
                    val nearest = findNearestSegment(seg, previousContours!!)
                    if (nearest != null) {
                        addWallQuad(
                            triangles, triangleColors,
                            seg.a, seg.b, nearest.a, nearest.b,
                            currY, prevY,
                            grid.columns, grid.rows,
                            sliceColor
                        )
                    }
                }
            }

            previousContours = contours
            previousDepth = sliceDepth
        }

        val vertexFloats = FloatArray(triangles.size * 9)
        val colorFloats = FloatArray(triangleColors.size * 12)
        triangles.forEachIndexed { i, tri -> System.arraycopy(tri, 0, vertexFloats, i * 9, 9) }
        triangleColors.forEachIndexed { i, col -> System.arraycopy(col, 0, colorFloats, i * 12, 12) }

        triangleCount = triangles.size

        vertexBuffer = allocateFloatBuffer(vertexFloats)
        colorBuffer = allocateFloatBuffer(colorFloats)
    }

    private fun findNearestSegment(
        target: MarchingSquares.Segment,
        candidates: List<MarchingSquares.Segment>
    ): MarchingSquares.Segment? {
        val targetMidX = (target.a.x + target.b.x) / 2f
        val targetMidY = (target.a.y + target.b.y) / 2f
        var best: MarchingSquares.Segment? = null
        var bestDist = Float.MAX_VALUE
        for (c in candidates) {
            val midX = (c.a.x + c.b.x) / 2f
            val midY = (c.a.y + c.b.y) / 2f
            val dx = midX - targetMidX
            val dy = midY - targetMidY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = c
            }
        }
        // Reject matches that are implausibly far apart (likely a
        // different blob/topology change) so we don't draw a wall
        // bridging two unrelated anomalies.
        return if (bestDist < 9f) best else null
    }

    /** Emits two triangles forming a quad wall between two slice contour segments. */
    private fun addWallQuad(
        triangles: MutableList<FloatArray>,
        colors: MutableList<FloatArray>,
        currA: MarchingSquares.Point,
        currB: MarchingSquares.Point,
        prevA: MarchingSquares.Point,
        prevB: MarchingSquares.Point,
        currY: Float,
        prevY: Float,
        cols: Int,
        rows: Int,
        rgb: AnomalyColorScale.Rgb
    ) {
        fun toWorld(p: MarchingSquares.Point, y: Float): FloatArray {
            val x = p.x - (cols - 1) / 2f
            val z = p.y - (rows - 1) / 2f
            return floatArrayOf(x, y, z)
        }

        val p0 = toWorld(currA, currY)
        val p1 = toWorld(currB, currY)
        val p2 = toWorld(prevB, prevY)
        val p3 = toWorld(prevA, prevY)

        val alpha = 0.78f
        val rgba = floatArrayOf(rgb.r, rgb.g, rgb.b, alpha)

        triangles += (p0 + p1 + p2)
        colors += (rgba + rgba + rgba)
        triangles += (p0 + p2 + p3)
        colors += (rgba + rgba + rgba)
    }

    private fun allocateFloatBuffer(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        return buf
    }
}
