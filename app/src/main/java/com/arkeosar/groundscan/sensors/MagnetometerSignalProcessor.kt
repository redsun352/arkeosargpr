package com.arkeosar.groundscan.sensors

import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sign

/**
 * Live signal-processing chain for the Detector screen.
 *
 * This packages three independent steps reverse-engineered from Thuban
 * Lodestar's detector activity (com.lugatek.thubanlodestar, decompiled
 * v1.0.317) and reimplemented from scratch in Kotlin for ArkeoSAR Ground
 * Scan. The three steps mirror what that app's "Hesapla" (calculate
 * noise) flow and live detector loop do, but written independently here
 * - no original code or assets were copied.
 *
 * 1. [NoiseFloorEstimator] - peak-to-peak noise floor over a small
 *    sliding window. This is what feeds the auto-calibrate button: it
 *    tells you how noisy the ambient signal is right now, so you can
 *    set a sensible detection threshold instead of guessing.
 * 2. [applyGain] - a logarithmic ("dB-style") gain control: the gain
 *    slider's value is used as an *exponent* of 10, not a linear
 *    multiplier, matching the source app's `10^sliderValue` curve. This
 *    means small slider movements produce large changes in amplification
 *    - appropriate for boosting a weak magnetic anomaly signal that can
 *    span several orders of magnitude.
 * 3. [applyDeadzone] - subtracts the noise floor from the signal: values
 *    inside +-threshold collapse to zero (suppressing noise), values
 *    outside it are shifted toward zero by the threshold amount (so the
 *    visible signal starts counting from the noise floor rather than
 *    from absolute zero).
 *
 * None of this needs FFT or any external math library - it's plain
 * arithmetic over a small ring buffer, which keeps it cheap enough to
 * run on every sensor sample.
 */
object MagnetometerSignalProcessor {

    /**
     * Tracks a fixed-size sliding window of recent samples and reports
     * the peak-to-peak (max - min) spread, which is a simple, standard
     * proxy for "how noisy is the signal right now" - used to suggest
     * a detection threshold without the person needing to know the
     * sensor's real noise characteristics.
     *
     * This is deliberately simpler than a standard deviation or RMS
     * noise estimate: peak-to-peak is what the reference app's noise
     * button used, and it's cheap, intuitive ("the needle wobbles by
     * this much"), and robust to small sample counts.
     */
    class NoiseFloorEstimator(private val windowSize: Int = 16) {
        private val window = ArrayDeque<Float>(windowSize)

        /** Adds a sample, dropping the oldest one once [windowSize] is exceeded. */
        fun addSample(value: Float) {
            window.addLast(value)
            if (window.size > windowSize) window.removeFirst()
        }

        fun reset() = window.clear()

        val isFull: Boolean get() = window.size >= windowSize

        /** Peak-to-peak spread of the current window, or 0f if empty. */
        fun noiseFloor(): Float {
            if (window.isEmpty()) return 0f
            return (window.max() - window.min())
        }
    }

    /**
     * Logarithmic gain: `value * 10^gainExponent`.
     *
     * [gainExponent] is the slider's raw value (e.g. a Material Slider
     * ranging 0f..4f), not a multiplier - this is what makes the control
     * feel like an audio/RF gain knob rather than a linear zoom. At
     * gainExponent = 0, this is a no-op (10^0 = 1).
     *
     * [preserveSign], when true, applies the gain to the magnitude only
     * and re-applies the original sign afterward - useful when the raw
     * signal can be negative (e.g. a baseline-subtracted reading) and
     * you want symmetric amplification instead of only boosting positive
     * values.
     */
    fun applyGain(
        value: Float,
        gainExponent: Float,
        preserveSign: Boolean = false
    ): Float {
        val gain = 10f.pow(gainExponent)
        if (!preserveSign) return value * gain

        // value.sign is 0f when value == 0f; treat that case as positive
        // so a zero reading doesn't get stuck at zero regardless of gain.
        val sign = if (value == 0f) 1f else value.sign
        return value.absoluteValue * gain * sign
    }

    /**
     * Deadzone / noise-floor subtraction.
     *
     * Values with absolute value below [threshold] are suppressed to
     * zero. Values above it are shifted toward zero by [threshold], so
     * the output represents "how far above the noise floor" rather than
     * the raw amplified value. This is the step that turns a noisy
     * boosted signal into a clean go/no-go style anomaly indicator.
     *
     * Example with threshold = 5:
     *   3   -> 0      (inside the deadzone, treated as noise)
     *   8   -> 3      (8 - 5)
     *   -8  -> -3     (-8 + 5)
     */
    fun applyDeadzone(value: Float, threshold: Float): Float {
        if (threshold <= 0f) return value
        return when {
            abs(value) < threshold -> 0f
            value > threshold -> value - threshold
            value < -threshold -> value + threshold
            else -> value
        }
    }

    /**
     * Convenience wrapper running the full chain in the same order as
     * the reference app: gain first, then deadzone subtraction using a
     * separately-supplied threshold (typically the live or
     * last-calibrated [NoiseFloorEstimator.noiseFloor]).
     */
    fun process(
        rawValue: Float,
        gainExponent: Float,
        noiseThreshold: Float,
        preserveSign: Boolean = false
    ): Float {
        val boosted = applyGain(rawValue, gainExponent, preserveSign)
        return applyDeadzone(boosted, noiseThreshold)
    }
}
