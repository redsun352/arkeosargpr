package com.arkeosar.groundscan.render

/**
 * Color palette selectable for anomaly visualization, matching the
 * "Grafik Renkleri" picker observed in ArkeoMag / Thuban Lodestar's 3D
 * view: Chlorophy(ll), Autumn, Gray, Bone, Jet, Winter, Summer, Spring,
 * Hot, Cool. These names and color recipes come from the long-standing
 * MATLAB/matplotlib colormap family (jet, hot, cool, bone, autumn,
 * winter, spring, summer, gray are all standard names dating back to
 * MATLAB's original colormap set) - generic, widely published color-stop
 * recipes, not anyone's proprietary asset. Implemented from scratch
 * here as plain RGB stop lists.
 */
enum class ColorPalette(val label: String) {
    CHLOROPHYLL("Chlorophy"),  // deep blue -> teal -> green -> yellow -> red
    AUTUMN("Autumn"),          // red -> orange -> yellow
    GRAY("Gray"),              // black -> white
    BONE("Bone"),              // black -> bluish-gray -> white (grayscale with a cool tint)
    JET("Jet"),                // classic blue -> cyan -> yellow -> red rainbow
    WINTER("Winter"),          // blue -> green
    SUMMER("Summer"),          // green -> yellow
    SPRING("Spring"),          // magenta -> yellow
    HOT("Hot"),                // black -> red -> yellow -> white
    COOL("Cool");              // cyan -> magenta

    companion object {
        fun next(current: ColorPalette): ColorPalette {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}

/**
 * Maps a magnetic anomaly reading to an RGB color using the currently
 * selected [ColorPalette]. Stop lists are defined per-palette below;
 * interpolation between stops is linear.
 */
object AnomalyColorScale {

    data class Rgb(val r: Float, val g: Float, val b: Float)

    /** The palette used by [colorFor] / [colorForRaw]. Change this to switch palettes app-wide. */
    var activePalette: ColorPalette = ColorPalette.CHLOROPHYLL

    private fun rgb(hex: Int): Rgb = Rgb(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f
    )

    private fun stopsFor(palette: ColorPalette): List<Pair<Float, Rgb>> = when (palette) {
        ColorPalette.CHLOROPHYLL -> listOf(
            0.00f to rgb(0x05214F),
            0.30f to rgb(0x0E6E6E),
            0.55f to rgb(0x2DBE6C),
            0.80f to rgb(0xD9D14A),
            1.00f to rgb(0xE0483B)
        )
        ColorPalette.AUTUMN -> listOf(
            0.00f to rgb(0xFF0000),
            1.00f to rgb(0xFFFF00)
        )
        ColorPalette.GRAY -> listOf(
            0.00f to rgb(0x000000),
            1.00f to rgb(0xFFFFFF)
        )
        ColorPalette.BONE -> listOf(
            0.00f to rgb(0x000000),
            0.50f to rgb(0x4A5A6B),
            1.00f to rgb(0xFFFFFF)
        )
        ColorPalette.JET -> listOf(
            0.00f to rgb(0x00007F),
            0.25f to rgb(0x0000FF),
            0.50f to rgb(0x00FFFF),
            0.75f to rgb(0xFFFF00),
            1.00f to rgb(0xFF0000)
        )
        ColorPalette.WINTER -> listOf(
            0.00f to rgb(0x0000FF),
            1.00f to rgb(0x00FF80)
        )
        ColorPalette.SUMMER -> listOf(
            0.00f to rgb(0x008066),
            1.00f to rgb(0xFFFF66)
        )
        ColorPalette.SPRING -> listOf(
            0.00f to rgb(0xFF00FF),
            1.00f to rgb(0xFFFF00)
        )
        ColorPalette.HOT -> listOf(
            0.00f to rgb(0x000000),
            0.40f to rgb(0xFF0000),
            0.75f to rgb(0xFFFF00),
            1.00f to rgb(0xFFFFFF)
        )
        ColorPalette.COOL -> listOf(
            0.00f to rgb(0x00FFFF),
            1.00f to rgb(0xFF00FF)
        )
    }

    /**
     * @param normalized a value in [0, 1] representing where the reading
     *   falls between the scan's observed minimum and maximum.
     */
    fun colorFor(normalized: Float, palette: ColorPalette = activePalette): Rgb {
        val stops = stopsFor(palette)
        val t = normalized.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (t in t0..t1) {
                val localT = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
                return Rgb(
                    lerp(c0.r, c1.r, localT),
                    lerp(c0.g, c1.g, localT),
                    lerp(c0.b, c1.b, localT)
                )
            }
        }
        return stops.last().second
    }

    fun colorForRaw(value: Float, rangeMin: Float, rangeMax: Float, palette: ColorPalette = activePalette): Rgb {
        val span = rangeMax - rangeMin
        val normalized = if (span <= 0f) 0.5f else (value - rangeMin) / span
        return colorFor(normalized, palette)
    }

    /** All stop colors for [palette], for drawing a colorbar preview swatch. */
    fun previewStops(palette: ColorPalette): List<Rgb> = stopsFor(palette).map { it.second }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
