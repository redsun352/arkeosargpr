package com.arkeosar.groundscan.render

/**
 * Marching Squares: extracts polygon contours from a 2D scalar field at
 * a given threshold value. This is the 2D analogue of Marching Cubes,
 * and the building block [VolumetricMesh] uses to turn each depth
 * slice of [SchematicDepthModel]'s projected volume into a closed
 * isosurface "blob" outline instead of a flat colored rectangle -
 * which is what gives the volumetric view its organic, "stalactite"-like
 * shape (matching the reference app's volumetric mode) instead of
 * looking like stacked flat cards.
 *
 * This is a standard, decades-old computer graphics algorithm
 * (Lorensen & Cline's Marching Cubes paper generalizes it; the 2D case
 * is commonly called Marching Squares) - the implementation below is
 * written from scratch for ArkeoSAR Ground Scan.
 */
object MarchingSquares {

    data class Point(val x: Float, val y: Float)
    data class Segment(val a: Point, val b: Point)

    /**
     * Returns line segments approximating the contour where [field]
     * crosses [threshold]. [field] is indexed [row][col]; NaN cells are
     * treated as below-threshold (excluded) so missing data doesn't
     * produce spurious contour fragments.
     *
     * Cell coordinates map directly to (col, row) in output point space;
     * callers can scale/offset the result afterward as needed.
     */
    fun extractContours(field: Array<FloatArray>, threshold: Float): List<Segment> {
        val rows = field.size
        if (rows == 0) return emptyList()
        val cols = field[0].size
        if (cols == 0) return emptyList()

        val segments = mutableListOf<Segment>()

        fun valueAt(row: Int, col: Int): Float {
            val v = field[row][col]
            return if (v.isNaN()) Float.NEGATIVE_INFINITY else v
        }

        for (row in 0 until rows - 1) {
            for (col in 0 until cols - 1) {
                // Four corners of this cell, in marching-squares order: TL, TR, BR, BL.
                val tl = valueAt(row, col)
                val tr = valueAt(row, col + 1)
                val br = valueAt(row + 1, col + 1)
                val bl = valueAt(row + 1, col)

                var caseIndex = 0
                if (tl > threshold) caseIndex = caseIndex or 8
                if (tr > threshold) caseIndex = caseIndex or 4
                if (br > threshold) caseIndex = caseIndex or 2
                if (bl > threshold) caseIndex = caseIndex or 1
                if (caseIndex == 0 || caseIndex == 15) continue // fully outside or fully inside: no contour edge here

                // Edge midpoints, interpolated by how far the threshold
                // sits between each pair of corners (gives a smoother
                // contour than always cutting at the cell midpoint).
                val top = interpolateEdge(col.toFloat(), row.toFloat(), col + 1f, row.toFloat(), tl, tr, threshold)
                val right = interpolateEdge(col + 1f, row.toFloat(), col + 1f, row + 1f, tr, br, threshold)
                val bottom = interpolateEdge(col.toFloat(), row + 1f, col + 1f, row + 1f, bl, br, threshold)
                val left = interpolateEdge(col.toFloat(), row.toFloat(), col.toFloat(), row + 1f, tl, bl, threshold)

                // Standard 16-case lookup (ambiguous cases 5 and 10 are
                // resolved with a fixed diagonal choice - acceptable for
                // a schematic visualization where exact saddle handling
                // doesn't materially change the displayed shape).
                when (caseIndex) {
                    1, 14 -> segments += Segment(left, bottom)
                    2, 13 -> segments += Segment(bottom, right)
                    3, 12 -> segments += Segment(left, right)
                    4, 11 -> segments += Segment(top, right)
                    5 -> { segments += Segment(left, top); segments += Segment(bottom, right) }
                    6, 9 -> segments += Segment(top, bottom)
                    7, 8 -> segments += Segment(left, top)
                    10 -> { segments += Segment(left, bottom); segments += Segment(top, right) }
                }
            }
        }

        return segments
    }

    private fun interpolateEdge(x0: Float, y0: Float, x1: Float, y1: Float, v0: Float, v1: Float, threshold: Float): Point {
        val denom = v1 - v0
        val t = if (kotlin.math.abs(denom) < 1e-6f) 0.5f else ((threshold - v0) / denom).coerceIn(0f, 1f)
        return Point(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
    }
}
