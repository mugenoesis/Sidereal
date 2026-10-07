package io.github.mugenoesis.sidereal.camera

import kotlin.math.abs

/**
 * Decides when the camera's own autofocus has finished moving the focus ring, from periodic ring readings.
 * Measured on the X5: hardware AF hunts for 1.5-2.5 s (0 -> 648 -> 324 -> 1670 -> 1620), so a fixed wait either
 * cuts it off mid-hunt or wastes time. Settled = the ring held within [tolerance] for [stableReads] readings in a
 * row, after at least [minWaitMs] (a ring that has not started to move yet must not count as "still"), or simply
 * the latest value once [maxWaitMs] has passed.
 */
class RingSettleDetector(
    private val minWaitMs: Long = 1_200,
    private val stableReads: Int = 2,
    private val tolerance: Int = 6,
    private val maxWaitMs: Long = 5_000
) {
    private var startedAt: Long? = null
    private var last: Int? = null
    private var lastGood: Int? = null
    private var stableCount = 0

    fun reset() {
        startedAt = null
        last = null
        lastGood = null
        stableCount = 0
    }

    /** Feed each reading (null if the read failed); returns the settled ring value, or null if still waiting. */
    fun onReading(nowMs: Long, ring: Int?): Int? {
        val start = startedAt ?: nowMs.also { startedAt = it }
        val elapsed = nowMs - start
        if (ring == null) {
            // A failed read breaks the "held still" streak: the next good reading is a fresh baseline.
            stableCount = 0
            last = null
            return if (elapsed >= maxWaitMs) lastGood else null
        }
        val previous = last
        stableCount = if (previous != null && abs(ring - previous) <= tolerance) stableCount + 1 else 0
        last = ring
        lastGood = ring
        if (elapsed >= maxWaitMs) return ring
        if (elapsed >= minWaitMs && stableCount >= stableReads - 1) return ring
        return null
    }
}
