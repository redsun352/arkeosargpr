package com.arkeosar.groundscan.data

/**
 * "Dewow" filtering: removes slow-varying baseline drift ("wow") from
 * survey data, leaving the higher-frequency anomaly signal. This is a
 * standard pre-processing step in GPR and other geophysical survey
 * software - low-frequency drift in raw instrument readings (thermal
 * drift, slow DC offset wander, very-low-frequency background field
 * changes) would otherwise show up as a gradual brightness gradient
 * across the scan, masking or distorting the real localized anomalies.
 *
 * The technique implemented here - subtracting a moving average from
 * each sample - is the simplest, most common dewow approach (a more
 * elaborate version would use a high-pass filter with a specific cutoff
 * frequency; a moving-average subtraction is the standard
 * approximation used across geophysical processing software and is
 * what's implemented here, written from scratch for ArkeoSAR Ground
 * Scan).
 *
 * Applied per scan line (row or column, matching [ScanAxis]) since
 * drift is a function of acquisition order/time, not spatial position -
 * exactly like a GPR trace being dewowed along its time axis.
 */
object DewowFilter {

    /**
     * Returns a new value array with the slow-varying baseline removed
     * from each line of [grid] (row-wise for ROW_MAJOR scans,
     * column-wise for COLUMN_MAJOR), using a moving average of the
     * given [windowSize] as the baseline estimate.
     *
     * Does not mutate [grid] - callers apply the result explicitly
     * (e.g. via [applyInPlace]) so dewow is an optional, reversible-by-rescanning
     * step rather than something silently baked into raw readings.
     */
    fun computeDewowedValues(grid: ScanGrid, windowSize: Int = 5): Array<FloatArray> {
        val result = Array(grid.rows) { FloatArray(grid.columns) { Float.NaN } }

        if (grid.scanAxis == ScanAxis.ROW_MAJOR) {
            for (row in 0 until grid.rows) {
                val line = FloatArray(grid.columns) { col -> grid.pointAt(row, col).value }
                val dewowed = dewowLine(line, windowSize)
                for (col in 0 until grid.columns) result[row][col] = dewowed[col]
            }
        } else {
            for (col in 0 until grid.columns) {
                val line = FloatArray(grid.rows) { row -> grid.pointAt(row, col).value }
                val dewowed = dewowLine(line, windowSize)
                for (row in 0 until grid.rows) result[row][col] = dewowed[row]
            }
        }

        return result
    }

    /** Computes dewowed values and writes them back into each [ScanGrid.GridPoint.value]. */
    fun applyInPlace(grid: ScanGrid, windowSize: Int = 5) {
        val dewowed = computeDewowedValues(grid, windowSize)
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.columns) {
                val point = grid.pointAt(row, col)
                if (point.hasValue) {
                    point.value = dewowed[row][col]
                }
            }
        }
    }

    /**
     * Subtracts a centered moving-average baseline from one 1D line of
     * samples. NaN (unmeasured) entries are skipped when computing the
     * average and left as NaN in the output.
     */
    private fun dewowLine(line: FloatArray, windowSize: Int): FloatArray {
        val n = line.size
        val output = FloatArray(n)
        val halfWindow = (windowSize / 2).coerceAtLeast(1)

        for (i in 0 until n) {
            if (line[i].isNaN()) {
                output[i] = Float.NaN
                continue
            }
            val lo = (i - halfWindow).coerceAtLeast(0)
            val hi = (i + halfWindow).coerceAtMost(n - 1)
            var sum = 0f
            var count = 0
            for (j in lo..hi) {
                if (!line[j].isNaN()) {
                    sum += line[j]
                    count++
                }
            }
            val baseline = if (count > 0) sum / count else line[i]
            output[i] = line[i] - baseline
        }
        return output
    }
}
