package io.github.mugenoesis.sidereal.sequence

import kotlin.math.hypot
import kotlin.random.Random

/**
 * @param minDeg smallest move between consecutive frames - must be big enough that stars land on different sensor pixels
 * @param maxDeg furthest any frame may sit from the base attitude on either axis
 */
data class DitherConfig(val minDeg: Float, val maxDeg: Float, val seed: Long)

/**
 * Pseudorandom per-frame pointing offsets, so that stacking software
 * (Siril/DeepSkyStacker kappa-sigma clipping) sees hot pixels, amp glow and
 * walking noise at a different place in every frame and can reject them.
 * Offsets are relative to a base attitude - never accumulating - so the
 * framing never drifts away over a long run, and the first frame sits on
 * the base exactly. Seeded so a run can be reproduced.
 */
object DitherGenerator {

    fun offsets(config: DitherConfig, count: Int): List<Attitude> {
        require(config.minDeg > 0f && config.maxDeg >= config.minDeg) { "dither needs 0 < minDeg <= maxDeg" }
        if (count <= 0) return emptyList()
        val rng = Random(config.seed)
        val result = ArrayList<Attitude>(count)
        result += Attitude(0f, 0f)
        repeat(count - 1) {
            val prev = result.last()
            var next: Attitude? = null
            repeat(200) {
                if (next == null) {
                    val candidate = Attitude(
                        (rng.nextFloat() * 2f - 1f) * config.maxDeg,
                        (rng.nextFloat() * 2f - 1f) * config.maxDeg
                    )
                    if (hypot(candidate.pitch - prev.pitch, candidate.yaw - prev.yaw) >= config.minDeg) next = candidate
                }
            }
            // Vanishingly unlikely with sane settings; the far corner always satisfies the minimum.
            result += next ?: Attitude(
                if (prev.pitch > 0) -config.maxDeg else config.maxDeg,
                if (prev.yaw > 0) -config.maxDeg else config.maxDeg
            )
        }
        return result
    }
}
