package io.github.mugenoesis.sidereal.sequence

import kotlin.math.ln
import kotlin.math.pow

/**
 * How bright the X5's live preview (and so its histogram) gets for a given exposure, measured on the real camera
 * (2026-10-07, ISO 100, one indoor scene): mean luma on the 0..255 display scale against exposure in stops, where
 * stops = log2(shutter seconds) + log2(ISO / 100). The preview follows the shutter all the way to 8 s, so this works
 * as a light meter across the whole day-to-night range.
 *
 * It is an S-shaped tone curve (compressing towards white), not a straight line, which is why it is a table.
 * Scenes with a different contrast shift it a little; the ramp measures every frame, so that self-corrects.
 */
object LumaCurve {

    private val points = listOf(
        -9.64 to 1.0, -6.64 to 4.4, -5.91 to 6.6, -4.91 to 11.8, -3.91 to 19.3, -3.0 to 29.5, -2.0 to 47.5,
        -1.0 to 71.0, 0.0 to 104.6, 1.0 to 146.7, 2.0 to 191.7, 3.0 to 226.5, 4.0 to 248.0, 5.0 to 254.5
    )
    private const val BELOW_SLOPE = 0.8
    private const val WHITE = 255.0

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** Mean luma the preview shows at [stops] of exposure for a reference scene. */
    fun lumaAt(stops: Double): Double {
        val first = points.first()
        val last = points.last()
        if (stops <= first.first) return first.second * 2.0.pow(BELOW_SLOPE * (stops - first.first))
        if (stops >= last.first) return (last.second + 0.5 * (stops - last.first)).coerceAtMost(WHITE)
        val i = points.indexOfLast { it.first <= stops }
        val (x0, l0) = points[i]
        val (x1, l1) = points[i + 1]
        val t = (stops - x0) / (x1 - x0)
        return 2.0.pow(log2(l0) + t * (log2(l1) - log2(l0)))
    }

    /** The exposure at which the reference scene would read [luma]; finite for any reading, including 0 and 255. */
    fun stopsFor(luma: Double): Double {
        val first = points.first()
        val last = points.last()
        val l = luma.coerceIn(0.0, WHITE)
        if (l <= first.second) return first.first + log2(l.coerceAtLeast(0.05) / first.second) / BELOW_SLOPE
        if (l >= last.second) return last.first + (l - last.second) / 0.5
        val i = points.indexOfLast { it.second <= l }
        val (x0, l0) = points[i]
        val (x1, l1) = points[i + 1]
        val t = (log2(l) - log2(l0)) / (log2(l1) - log2(l0))
        return x0 + t * (x1 - x0)
    }
}
