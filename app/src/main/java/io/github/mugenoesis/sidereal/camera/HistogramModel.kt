package io.github.mugenoesis.sidereal.camera

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What the camera's pushed histogram actually is (read off the real X5, 2026-10-07, and checked against screenshots of
 * the preview): a single LUMA histogram of 64 buckets over 0..255, scaled so the tallest bucket is always 255 (so the
 * values are relative, never counts), and in VIDEO range - black is level 16 (bucket 4) and white 235 (bucket 58).
 * Drawn as-is, black floats ~6% in from the left edge and white stops ~8% short of the right.
 *
 * Pure so it is testable against recorded camera data.
 */
object HistogramModel {

    const val DISPLAY_BUCKETS = 64
    private const val VIDEO_BLACK = 16.0
    private const val VIDEO_WHITE = 235.0

    data class Stats(
        /** Mean brightness on the full 0..255 scale (what a screenshot of the preview measures). */
        val meanLuma: Double,
        /** Share of pixels at or below video black. */
        val shadowsClipped: Double,
        /** Share of pixels at or above video white. */
        val highlightsClipped: Double
    )

    /** Bar heights 0..1 for [DISPLAY_BUCKETS] bars spanning true black to true white, or null if there is nothing to draw. */
    fun display(raw: ShortArray?): FloatArray? {
        if (raw == null || raw.isEmpty() || raw.all { it <= 0 }) return null
        val width = 256.0 / raw.size
        val out = FloatArray(DISPLAY_BUCKETS)
        for (j in 0 until DISPLAY_BUCKETS) {
            // Centre of display bar j, as a luma level, then where that level sits in the camera's video range.
            val level = (j + 0.5) / DISPLAY_BUCKETS * 255.0
            val source = VIDEO_BLACK + level / 255.0 * (VIDEO_WHITE - VIDEO_BLACK)
            out[j] = sample(raw, source / width - 0.5)
        }
        val peak = out.max()
        if (peak <= 0f) return null
        return FloatArray(DISPLAY_BUCKETS) { out[it] / peak }
    }

    /** Linear interpolation between neighbouring buckets; position is in bucket units, bucket i being centred at i. */
    private fun sample(raw: ShortArray, position: Double): Float {
        val p = position.coerceIn(0.0, (raw.size - 1).toDouble())
        val lo = floor(p).toInt()
        val hi = min(raw.size - 1, lo + 1)
        val f = (p - lo).toFloat()
        return raw[lo].coerceAtLeast(0) * (1 - f) + raw[hi].coerceAtLeast(0) * f
    }

    fun stats(raw: ShortArray?): Stats? {
        if (raw == null || raw.isEmpty()) return null
        val total = raw.sumOf { max(0, it.toInt()).toLong() }.toDouble()
        if (total <= 0) return null
        val width = 256.0 / raw.size
        var weighted = 0.0
        var shadows = 0.0
        var highlights = 0.0
        for (i in raw.indices) {
            val count = max(0, raw[i].toInt()).toDouble()
            val centre = (i + 0.5) * width
            weighted += count * ((centre - VIDEO_BLACK) / (VIDEO_WHITE - VIDEO_BLACK) * 255.0).coerceIn(0.0, 255.0)
            if (centre <= VIDEO_BLACK + 2 * width) shadows += count
            if (centre >= VIDEO_WHITE - 1.5 * width) highlights += count
        }
        return Stats(weighted / total, shadows / total, highlights / total)
    }
}
