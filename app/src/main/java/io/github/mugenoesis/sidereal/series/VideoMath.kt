package io.github.mugenoesis.sidereal.series

import kotlin.math.min
import kotlin.math.roundToInt

/** Planar YUV 4:2:0 ("I420"): full-size luma, quarter-size chroma planes. */
class I420(val y: ByteArray, val u: ByteArray, val v: ByteArray)

/** The pure parts of turning pictures into a video, kept apart from MediaCodec so they can be tested. */
object VideoMath {

    private const val ALIGN = 16

    /** Largest size with the source's shape that fits [maxWidth] x [maxHeight], never bigger than the source, in multiples of 16. */
    fun outputSize(srcW: Int, srcH: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1.0, min(maxWidth.toDouble() / srcW, maxHeight.toDouble() / srcH))
        return align(srcW * scale) to align(srcH * scale)
    }

    private fun align(value: Double): Int = maxOf(ALIGN, (value.toInt() / ALIGN) * ALIGN)

    /** When frame [index] is shown, in microseconds: even spacing at [fps]. */
    fun ptsUs(index: Int, fps: Int): Long = index * 1_000_000L / fps

    /** A generous H.264 rate: timelapse frames are all detail (stars, clouds), so skimping shows as mush. */
    fun bitrate(width: Int, height: Int, fps: Int): Int =
        (width.toLong() * height * fps * 0.18).roundToInt().coerceIn(4_000_000, 80_000_000)

    /** ARGB pixels to I420, BT.601 limited range (what H.264 players assume for untagged video). */
    fun toI420(argb: IntArray, width: Int, height: Int): I420 {
        val y = ByteArray(width * height)
        val cw = width / 2
        val ch = height / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        val uSum = IntArray(cw * ch)
        val vSum = IntArray(cw * ch)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[row * width + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                val ci = (row shr 1) * cw + (col shr 1)
                if (ci < uSum.size) {
                    uSum[ci] += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    vSum[ci] += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                }
            }
        }
        for (i in u.indices) {
            u[i] = (uSum[i] / 4).coerceIn(16, 240).toByte()
            v[i] = (vSum[i] / 4).coerceIn(16, 240).toByte()
        }
        return I420(y, u, v)
    }
}
