package io.github.mugenoesis.sidereal.camera

/**
 * Pure math behind SoftwareAfcController's contrast-detection hill-climb,
 * extracted so the decision logic and sharpness metric are unit-testable
 * without a Bitmap/live camera - see SoftwareAfcController's class doc
 * comment for the real-hardware findings that shaped this (Focus Assistant
 * zoom, the leash/excursion limit, EMA smoothing).
 */
object HillClimbFocus {

    /**
     * Variance of Laplacian over a square ARGB pixel buffer - a standard,
     * cheap focus metric: higher means sharper. [pixels] must have exactly
     * size*size elements (as returned by Bitmap.getPixels() for a size x
     * size region).
     */
    fun laplacianVariance(pixels: IntArray, size: Int): Double {
        require(pixels.size == size * size) { "expected ${size * size} pixels, got ${pixels.size}" }
        val gray = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val idx = y * size + x
                val lap = -4 * gray[idx] + gray[idx - 1] + gray[idx + 1] + gray[idx - size] + gray[idx + size]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    /** Exponential moving average update - `null` previous means "first sample, use it as-is". */
    fun emaUpdate(previous: Double?, raw: Double, weight: Double): Double =
        if (previous == null) raw else previous * (1 - weight) + raw * weight

    /** `(bound * fraction)` clamped to at least [minStep] - the hill-climb's per-sample ring move size. */
    fun stepSize(bound: Int, fraction: Double, minStep: Int): Int =
        (bound * fraction).toInt().coerceAtLeast(minStep)

    data class ClimbState(val direction: Int, val step: Int)

    /**
     * One hill-climb iteration's direction/step decision: reverse and halve
     * the step whenever the new score is worse than the previous one,
     * otherwise keep going the same way at the same step size. A null
     * [prevScore] (first real sample) never reverses - there's nothing to
     * compare against yet.
     */
    fun nextClimbState(current: ClimbState, prevScore: Double?, newScore: Double, minStep: Int): ClimbState =
        if (prevScore != null && newScore < prevScore) {
            ClimbState(direction = -current.direction, step = (current.step / 2).coerceAtLeast(minStep))
        } else {
            current
        }

    /**
     * Next ring position: [currentPos] plus a signed step, clamped both to
     * the ring's own 0..[bound] limits AND to a leash of [leash] around
     * [anchor] (wherever the search started) - see
     * SoftwareAfcController's MAX_EXCURSION_FRACTION doc comment for why
     * the leash exists (an unleashed search sweeping far enough to find a
     * peak visibly changed framing on real hardware, at least at the time
     * this was believed to be lens breathing rather than Focus Assistant
     * zoom).
     */
    fun nextPosition(currentPos: Int, direction: Int, step: Int, bound: Int, anchor: Int, leash: Int): Int {
        val proposed = currentPos + direction * step
        return proposed.coerceIn(
            (anchor - leash).coerceAtLeast(0),
            (anchor + leash).coerceAtMost(bound)
        )
    }

    /**
     * Whether the leash should be re-anchored at the search's current
     * position - real hardware testing (a genuinely defocused start in a
     * low-light scene) found sharpness could still be climbing right up to
     * the leash edge and then just stop there permanently, exactly the
     * "untested against a genuinely defocused start" risk this leash's own
     * doc comment already flagged. [consecutiveStuckSamples] counts
     * samples where the climb wanted to keep advancing the same direction
     * (not a reversal - a reversal means a real nearby peak was found, not
     * that the leash is in the way) but nextPosition() came back unchanged
     * because the leash clamped it - see SoftwareAfcController.onBitmapFrame
     * for where that's tracked. Requiring more than one such sample before
     * re-anchoring is what keeps this from just being "no leash at all":
     * a single sample hitting the edge could still be noise, but sustained
     * improvement blocked for multiple samples in a row is real signal
     * that the true peak is further out.
     */
    fun shouldReanchorLeash(consecutiveStuckSamples: Int, threshold: Int): Boolean =
        consecutiveStuckSamples >= threshold
}
