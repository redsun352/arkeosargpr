package com.arkeosar.groundscan.data

/**
 * "Stacking" (coherent averaging): combines multiple readings taken at
 * the same physical point into a single, lower-noise value by
 * averaging. This is a standard technique in GPR and other geophysical
 * survey instruments - random sensor noise tends to average toward
 * zero across repeated samples, while the real (consistent) signal
 * stays, improving the signal-to-noise ratio at the cost of needing
 * more time per point.
 *
 * Used by [com.arkeosar.groundscan.ui.GridScanActivity]'s manual scan
 * mode: instead of committing a single instantaneous reading per "Ölç"
 * tap, a short burst of [sampleCount] readings is collected and
 * averaged before being written to the grid.
 */
class StackingAccumulator(private val sampleCount: Int = 8) {

    private val values = mutableListOf<Float>()
    private val rawXs = mutableListOf<Float>()
    private val rawYs = mutableListOf<Float>()
    private val rawZs = mutableListOf<Float>()
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var altitude: Double? = null

    val isComplete: Boolean get() = values.size >= sampleCount
    val progress: Int get() = values.size
    val target: Int get() = sampleCount

    fun addSample(reading: ScanReading) {
        if (isComplete) return
        values += reading.value
        reading.rawX?.let { rawXs += it }
        reading.rawY?.let { rawYs += it }
        reading.rawZ?.let { rawZs += it }
        latitude = reading.latitude ?: latitude
        longitude = reading.longitude ?: longitude
        altitude = reading.altitude ?: altitude
    }

    /** Returns the averaged reading, or null if no samples were collected yet. */
    fun result(): ScanReading? {
        if (values.isEmpty()) return null
        return ScanReading(
            value = values.average().toFloat(),
            buttonPressed = false,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            rawX = if (rawXs.isNotEmpty()) rawXs.average().toFloat() else null,
            rawY = if (rawYs.isNotEmpty()) rawYs.average().toFloat() else null,
            rawZ = if (rawZs.isNotEmpty()) rawZs.average().toFloat() else null
        )
    }

    fun reset() {
        values.clear()
        rawXs.clear(); rawYs.clear(); rawZs.clear()
        latitude = null; longitude = null; altitude = null
    }
}
