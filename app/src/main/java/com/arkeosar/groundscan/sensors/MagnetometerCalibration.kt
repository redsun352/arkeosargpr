package com.arkeosar.groundscan.sensors

import android.content.Context

/**
 * Hard-iron magnetometer calibration.
 *
 * A phone's magnetometer reads the true ambient magnetic field *plus* a
 * constant offset ("hard-iron bias") caused by the phone's own nearby
 * ferrous/magnetic components (speaker magnets, camera module, chassis
 * metal, etc). If you rotate the phone through all orientations while
 * logging raw (x, y, z) samples, the true field traces out a sphere
 * centered at the origin - but the *measured* samples trace out a
 * sphere centered at the bias vector instead. This is a standard,
 * widely documented calibration technique for 3-axis magnetometers
 * (e.g. as used in compass/AHRS literature); the implementation below
 * is written from scratch for ArkeoSAR Ground Scan.
 *
 * This class only corrects for hard-iron bias (a constant offset), not
 * soft-iron distortion (which would also scale/skew the axes and needs
 * a full ellipsoid fit rather than a simple min/max center). A min/max
 * bias estimate is a deliberately simple, robust starting point - it
 * needs the person to rotate the phone through a reasonably full range
 * of orientations to be accurate, which is exactly what the on-screen
 * instructions (rotate around X, Y, then Z) are for.
 */
class MagnetometerCalibration(context: Context) {

    private val prefs = context.getSharedPreferences("arkeosar_groundscan_calibration", Context.MODE_PRIVATE)

    data class Bias(val x: Float, val y: Float, val z: Float)

    var bias: Bias
        get() = Bias(
            prefs.getFloat(KEY_BIAS_X, 0f),
            prefs.getFloat(KEY_BIAS_Y, 0f),
            prefs.getFloat(KEY_BIAS_Z, 0f)
        )
        private set(value) {
            prefs.edit()
                .putFloat(KEY_BIAS_X, value.x)
                .putFloat(KEY_BIAS_Y, value.y)
                .putFloat(KEY_BIAS_Z, value.z)
                .putBoolean(KEY_HAS_CALIBRATION, true)
                .apply()
        }

    val hasCalibration: Boolean get() = prefs.getBoolean(KEY_HAS_CALIBRATION, false)

    /** Applies the stored bias correction to a raw reading. */
    fun apply(rawX: Float, rawY: Float, rawZ: Float): Triple<Float, Float, Float> {
        val b = bias
        return Triple(rawX - b.x, rawY - b.y, rawZ - b.z)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Accumulates raw samples during an active calibration session and
     * computes the bias from their min/max extent per axis once enough
     * samples and orientation coverage have been collected.
     */
    class Session {
        private var minX = Float.MAX_VALUE
        private var maxX = -Float.MAX_VALUE
        private var minY = Float.MAX_VALUE
        private var maxY = -Float.MAX_VALUE
        private var minZ = Float.MAX_VALUE
        private var maxZ = -Float.MAX_VALUE

        private val samplesXY = mutableListOf<Pair<Float, Float>>()
        private val samplesYZ = mutableListOf<Pair<Float, Float>>()
        private val samplesXZ = mutableListOf<Pair<Float, Float>>()

        var sampleCount: Int = 0
            private set

        fun addSample(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
            samplesXY += x to y
            samplesYZ += y to z
            samplesXZ += x to z
            sampleCount++
        }

        fun pointsXY(): List<Pair<Float, Float>> = samplesXY
        fun pointsYZ(): List<Pair<Float, Float>> = samplesYZ
        fun pointsXZ(): List<Pair<Float, Float>> = samplesXZ

        /**
         * Rough coverage heuristic: how much of the (x, y, z) range
         * collected so far looks like a full rotation rather than a
         * thin sliver - used to drive the "Ham Veriler" / on-screen
         * progress feel. Returns 0..1.
         */
        fun coverageEstimate(): Float {
            if (sampleCount < 10) return 0f
            val rangeX = (maxX - minX).coerceAtLeast(0f)
            val rangeY = (maxY - minY).coerceAtLeast(0f)
            val rangeZ = (maxZ - minZ).coerceAtLeast(0f)
            // Compare against a generous expected full-rotation range for
            // Earth's field in microtesla; this is a rough heuristic, not
            // a precise completeness measurement.
            val expectedRange = 80f
            val coverage = listOf(rangeX, rangeY, rangeZ).map { (it / expectedRange).coerceIn(0f, 1f) }
            return coverage.average().toFloat()
        }

        fun computeBias(): Bias? {
            if (sampleCount < 10) return null
            return Bias(
                x = (minX + maxX) / 2f,
                y = (minY + maxY) / 2f,
                z = (minZ + maxZ) / 2f
            )
        }
    }

    fun finishSession(session: Session): Boolean {
        val computed = session.computeBias() ?: return false
        bias = computed
        return true
    }

    companion object {
        private const val KEY_BIAS_X = "bias_x"
        private const val KEY_BIAS_Y = "bias_y"
        private const val KEY_BIAS_Z = "bias_z"
        private const val KEY_HAS_CALIBRATION = "has_calibration"
    }
}
