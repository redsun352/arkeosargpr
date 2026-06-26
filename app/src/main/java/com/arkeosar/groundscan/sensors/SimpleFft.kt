package com.arkeosar.groundscan.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal radix-2 Cooley-Tukey FFT, written from scratch so the
 * Detector screen's frequency view doesn't need an external math
 * dependency (e.g. Apache Commons Math3) just for a 16-sample window.
 *
 * This mirrors what Thuban Lodestar's detector screen does with its
 * sliding window of raw samples: take a small power-of-two window of
 * recent magnetometer readings, transform it, and look at the
 * magnitude of the low-frequency bins (the first quarter of the
 * spectrum) as a smoothed, frequency-domain view of how the signal is
 * varying - distinct from the plain time-domain trace the line graph
 * already shows.
 *
 * Implementation is iterative (in-place, bit-reversal + butterfly),
 * which is the standard textbook approach for power-of-two FFTs and
 * isn't tied to any particular reference implementation's internals.
 */
object SimpleFft {

    /** A single complex sample: [re] real part, [im] imaginary part. */
    data class Complex(val re: Double, val im: Double) {
        fun magnitude(): Double = kotlin.math.sqrt(re * re + im * im)
    }

    /**
     * Forward FFT of [input] (treated as real-valued samples, imaginary
     * part zero). [input].size must be a power of two - the reference
     * app's window size of 16 satisfies this by construction; callers
     * with a different window size should pad with zeros to the next
     * power of two rather than calling this directly on an odd length.
     */
    fun forward(input: DoubleArray): Array<Complex> {
        val n = input.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, was $n" }

        val re = input.copyOf()
        val im = DoubleArray(n)

        bitReversePermute(re, im)

        var size = 2
        while (size <= n) {
            val half = size / 2
            val angleStep = -2.0 * PI / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val angle = angleStep * k
                    val wRe = cos(angle)
                    val wIm = sin(angle)

                    val evenIndex = start + k
                    val oddIndex = start + k + half

                    val oddRe = re[oddIndex] * wRe - im[oddIndex] * wIm
                    val oddIm = re[oddIndex] * wIm + im[oddIndex] * wRe

                    val evenReOriginal = re[evenIndex]
                    val evenImOriginal = im[evenIndex]

                    re[evenIndex] = evenReOriginal + oddRe
                    im[evenIndex] = evenImOriginal + oddIm
                    re[oddIndex] = evenReOriginal - oddRe
                    im[oddIndex] = evenImOriginal - oddIm
                }
                start += size
            }
            size *= 2
        }

        return Array(n) { i -> Complex(re[i], im[i]) }
    }

    /**
     * Convenience for the Detector screen: runs [forward] on [samples]
     * (zero-padded up to the next power of two if needed) and returns
     * just the magnitude of the first [bins] frequency components
     * (typically size/4, matching the reference app's behavior of
     * showing roughly the lower quarter of the spectrum, where a slow
     * walking-speed sweep over a buried anomaly shows up).
     */
    fun lowFrequencyMagnitudes(samples: List<Float>, bins: Int = samples.size / 4): List<Float> {
        if (samples.isEmpty()) return emptyList()
        val paddedSize = nextPowerOfTwo(samples.size)
        val input = DoubleArray(paddedSize)
        for (i in samples.indices) input[i] = samples[i].toDouble()

        val spectrum = forward(input)
        val limit = bins.coerceIn(0, spectrum.size)
        return (0 until limit).map { spectrum[it].magnitude().toFloat() }
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p.coerceAtLeast(1)
    }

    /** In-place bit-reversal permutation, the standard first step of an iterative FFT. */
    private fun bitReversePermute(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempRe = re[i]; re[i] = re[j]; re[j] = tempRe
                val tempIm = im[i]; im[i] = im[j]; im[j] = tempIm
            }
            var bit = n shr 1
            while (bit in 1 until (j + 1) && (j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
        }
    }
}
