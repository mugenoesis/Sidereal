package io.github.mugenoesis.sidereal.wearprotocol

import kotlin.math.max
import kotlin.math.min

object Thumbnail {
    /** The size to shrink a [srcWidth] x [srcHeight] frame to so its longest side is [maxSide]; never enlarges. */
    fun fit(srcWidth: Int, srcHeight: Int, maxSide: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val scale = min(1.0, maxSide.toDouble() / max(srcWidth, srcHeight))
        return max(1, Math.round(srcWidth * scale).toInt()) to max(1, Math.round(srcHeight * scale).toInt())
    }
}
