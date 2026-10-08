package io.github.mugenoesis.sidereal.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The fast focus search for good light. In daylight the preview's sharpness is a smooth hill over the focus ring
 * (measured on the real X5: a broad peak for a near subject, a long flat top for a far one), unlike the flat noise of
 * low light that [FocusSearch] has to scan its way through. So instead of sweeping a whole window it just climbs:
 *
 *  1. measure at the camera's own autofocus ring (the seed) and one step either side;
 *  2. if the seed is on top, refine it with one parabola fit; otherwise walk uphill in growing steps until the
 *     sharpness stops improving (a flat top: stop there) or falls away (a peak: fit a parabola through it);
 *  3. go to the best point, and then watch for a lasting collapse in sharpness, as [FocusSearch] does.
 *
 * It usually needs four to six positions - about two seconds after the hardware autofocus - where the scanning
 * search needs fourteen seconds. It must not be used where the sharpness is noise (dim light): there is no hill to climb.
 */
class SmoothFocusClimb(private val bound: Int, private val config: Config = Config()) : FocusSearcher {

    data class Config(
        val settleMs: Long = 250,
        val framesPerPosition: Int = 2,
        /** First step either side of the seed, as a fraction of the ring. */
        val probeFraction: Double = 0.05,
        val stepGrowth: Double = 1.6,
        val maxStepFraction: Double = 0.16,
        /** A reading must beat the best by this fraction to count as clearly "still climbing". */
        val improve: Double = 0.03,
        /** Better by at least this much, but less than [improve], is a gentle slope: far from the top, so take bigger steps. */
        val gentle: Double = 0.01,
        /** This many gentle readings running means a plateau, not a slope. */
        val gentleLimit: Int = 2,
        /** Within this fraction of the best is "flat"; below it is "past the peak". */
        val flat: Double = 0.03,
        val maxMeasurements: Int = 14,
        val minStep: Int = 8,
        val dropFraction: Double = 0.5,
        val dropConfirmFrames: Int = 4,
        /** Averaging stops being worth the time once the noise of the mean is this small (fraction of the reading). */
        val targetNoise: Double = 0.03,
        /** More frames than this per position would make the climb slower than scanning: hand over instead. */
        val maxFramesPerPosition: Int = 5,
        /** Above this per-frame noise (fraction of the reading) the picture counts as noisy and a lock must be earned (see [minHillContrast]). */
        val noisySigma: Double = 0.025,
        /** Per-frame noise beyond this (measured: ~0.27 at ISO 25600, ~0.05 at ISO 6400) is too much to average away quickly. */
        val maxSigma: Double = 0.1,
        /**
         * In a noisy picture a real hill shows as the best reading standing well above the lowest one measured on the
         * way; if everything measured looks alike, the search was lost in the noise floor (or never found anything),
         * so the scanning search takes over rather than locking on nothing.
         */
        val minHillContrast: Double = 1.25,
        /** Two frames of a still picture disagreeing by more than this (fraction of the reading) make the noise worth measuring properly. */
        val suspectSpread: Double = 0.02,
        val noiseProbeFrames: Int = 6
    )

    private enum class Stage { IDLE, CENTRE, PROBE, CLIMB, VERIFY, LOCKING, LOCKED }

    private var stage = Stage.IDLE
    override val locked: Boolean get() = stage == Stage.LOCKED

    private val measured = LinkedHashMap<Int, Double>()
    private val queue = ArrayDeque<Int>()
    private val frames = ArrayList<Double>()
    private var current = 0
    private var commandedAt = 0L
    private var centre = 0
    private var step = 1
    private var climbDirection = 1
    private var bestRing = 0
    private var bestValue = 0.0
    private var count = 0
    private var gentleStreak = 0
    private var sigmaSum = 0.0
    private var sigmaCount = 0
    private var framesNeeded = 2
    private var extendedCentre = false
    private var confident = true

    private var lockedRing = 0
    private var lockedScore = 0.0
    private var belowStreak = 0

    override fun begin(seedRing: Int?, nowMs: Long): FocusSearch.Command.MoveTo {
        measured.clear()
        queue.clear()
        frames.clear()
        count = 0
        gentleStreak = 0
        sigmaSum = 0.0
        sigmaCount = 0
        framesNeeded = config.framesPerPosition
        extendedCentre = false
        confident = true
        belowStreak = 0
        stage = Stage.CENTRE
        centre = (seedRing ?: bound / 2).coerceIn(0, bound)
        step = max(config.minStep, (bound * config.probeFraction).roundToInt())
        return move(centre, nowMs)
    }

    override fun onFrame(nowMs: Long, score: Double, steady: Boolean): FocusSearch.Command? {
        if (stage == Stage.IDLE) return null
        if (nowMs - commandedAt < config.settleMs) return null
        if (stage == Stage.LOCKED) return monitor(nowMs, score, steady)

        frames += score
        // The first reading doubles as the noise measurement: if its two frames already disagree, take more of them
        // at this same ring so the noise level (which decides everything after) is estimated properly.
        if (stage == Stage.CENTRE && !extendedCentre && frames.size == framesNeeded && frames.size >= 2) {
            val mean = frames.average()
            if (mean > 0 && (frames.max() - frames.min()) / mean > config.suspectSpread) {
                extendedCentre = true
                framesNeeded = max(framesNeeded, config.noiseProbeFrames)
            }
        }
        if (frames.size < framesNeeded) return null
        val mean = frames.average()
        recordNoise(mean)
        frames.clear()
        measured[current] = mean
        count++
        return decide(mean, nowMs)
    }

    /** Per-frame noise (fraction of the reading) seen so far, and from it how many frames each position needs. */
    internal val debugState: String get() = "sigma=%.3f needed=%d measured=%s".format(sigma, framesNeeded, measured)
    private val sigma: Double get() = if (sigmaCount == 0) 0.0 else kotlin.math.sqrt(sigmaSum / sigmaCount)

    private fun recordNoise(mean: Double) {
        if (frames.size >= 2 && mean > 0) {
            val variance = frames.sumOf { (it - mean) * (it - mean) } / (frames.size - 1)
            sigmaSum += variance / (mean * mean) // pooled as variances: far steadier than averaging standard deviations
            sigmaCount++
            framesNeeded = framesFor(sigma).coerceIn(config.framesPerPosition, config.maxFramesPerPosition)
        }
    }

    /** How many frames' average brings noise [perFrame] (fraction of the reading) down to the target. */
    private fun framesFor(perFrame: Double): Int =
        max(config.framesPerPosition, kotlin.math.ceil((perFrame / config.targetNoise) * (perFrame / config.targetNoise)).toInt())

    /** Noise of a position's averaged reading - what the decisions below must stay clear of. */
    private val effectiveNoise: Double get() = sigma / kotlin.math.sqrt(framesNeeded.toDouble())


    private fun decide(value: Double, nowMs: Long): FocusSearch.Command? = when (stage) {
        Stage.CENTRE -> {
            bestRing = current; bestValue = value
            stage = Stage.PROBE
            queue.addAll(listOf(centre + step, centre - step).map { it.coerceIn(0, bound) }.filter { it != centre }.distinct())
            if (queue.isEmpty()) lock(centre, nowMs) else move(queue.removeFirst(), nowMs)
        }
        Stage.PROBE -> {
            if (value > bestValue) { bestRing = current; bestValue = value }
            if (queue.isNotEmpty()) move(queue.removeFirst(), nowMs)
            else if (sigma > config.maxSigma) {
                stage = Stage.IDLE
                FocusSearch.Command.Unreliable(centre)
            }
            else if (bestRing == centre) refineAtTop(nowMs)
            else startClimb(nowMs)
        }
        Stage.CLIMB -> climb(value, nowMs)
        Stage.VERIFY -> lock(measured.maxByOrNull { it.value }!!.key, nowMs)
        Stage.LOCKING -> {
            lockedRing = current
            lockedScore = value
            belowStreak = 0
            stage = Stage.LOCKED
            FocusSearch.Command.Locked(current, value, confident)
        }
        else -> null
    }

    /** The seed beat both neighbours: fit a parabola through the three and check where it points. */
    private fun refineAtTop(nowMs: Long): FocusSearch.Command? {
        val left = measured[centre - step]
        val right = measured[centre + step]
        val vertex = if (left != null && right != null) vertexOf(centre - step, left, centre, measured.getValue(centre), centre + step, right) else null
        if (vertex != null && abs(vertex - centre) >= config.minStep && vertex !in measured) {
            stage = Stage.VERIFY
            return move(vertex, nowMs)
        }
        return lock(measured.maxByOrNull { it.value }!!.key, nowMs)
    }

    private fun startClimb(nowMs: Long): FocusSearch.Command? {
        climbDirection = if (bestRing > centre) 1 else -1
        stage = Stage.CLIMB
        step = min(max(config.minStep, (step * config.stepGrowth).roundToInt()), (bound * config.maxStepFraction).roundToInt())
        return climbFrom(bestRing, nowMs)
    }

    private fun climbFrom(from: Int, nowMs: Long): FocusSearch.Command? {
        val next = (from + climbDirection * step).coerceIn(0, bound)
        if (next == from || next in measured || count >= config.maxMeasurements) return lock(bestRing, nowMs, confident = count < config.maxMeasurements)
        return move(next, nowMs)
    }

    private fun climb(value: Double, nowMs: Long): FocusSearch.Command? {
        // Never read a difference smaller than the noise of the averaged readings as a slope.
        val improve = max(config.improve, 3 * effectiveNoise)
        val gentle = max(config.gentle, 1.5 * effectiveNoise)
        val flat = max(config.flat, 2 * effectiveNoise)
        when {
            value > bestValue * (1 + improve) -> {
                gentleStreak = 0
                bestRing = current; bestValue = value
                step = min(max(config.minStep, (step * config.stepGrowth).roundToInt()), (bound * config.maxStepFraction).roundToInt())
                return climbFrom(current, nowMs)
            }
            value > bestValue * (1 + gentle) && ++gentleStreak < config.gentleLimit -> {
                bestRing = current; bestValue = value
                step = min(max(config.minStep, (step * 2.0).roundToInt()), (bound * config.maxStepFraction).roundToInt())
                return climbFrom(current, nowMs)
            }
            value < bestValue * (1 - flat) -> {
                // Past the peak: fit through the nearest measured point on the far side of the best, the best, and this one.
                val behind = measured.keys.filter { (it - bestRing) * climbDirection < 0 }.minByOrNull { abs(it - bestRing) }
                val vertex = behind?.let { vertexOf(it, measured.getValue(it), bestRing, bestValue, current, value) }
                if (vertex != null && abs(vertex - bestRing) >= config.minStep && vertex !in measured) {
                    stage = Stage.VERIFY
                    return move(vertex, nowMs)
                }
                return lock(bestRing, nowMs)
            }
            else -> {
                if (value > bestValue) { bestRing = current; bestValue = value }
                return lock(bestRing, nowMs) // a flat top: anywhere on it is as sharp as the lens gets
            }
        }
    }

    /** Peak of the parabola through three measured points, or null if they do not curve downwards. */
    private fun vertexOf(x1: Int, y1: Double, x2: Int, y2: Double, x3: Int, y3: Double): Int? {
        val denom = (x1 - x2).toDouble() * (x1 - x3) * (x2 - x3)
        if (denom == 0.0) return null
        val a = (x3 * (y2 - y1) + x2 * (y1 - y3) + x1 * (y3 - y2)) / denom
        val b = (x3.toDouble() * x3 * (y1 - y2) + x2.toDouble() * x2 * (y3 - y1) + x1.toDouble() * x1 * (y2 - y3)) / denom
        if (a >= 0) return null
        val lo = min(x1, min(x2, x3)); val hi = max(x1, max(x2, x3))
        return (-b / (2 * a)).roundToInt().coerceIn(lo, hi).coerceIn(0, bound)
    }

    private fun lock(ring: Int, nowMs: Long, confident: Boolean = true): FocusSearch.Command? {
        if (sigma > config.noisySigma && measured.size >= 3) {
            val contrast = measured.values.max() / measured.values.min().coerceAtLeast(1e-9)
            if (contrast < config.minHillContrast) {
                stage = Stage.IDLE
                return FocusSearch.Command.Unreliable(centre)
            }
        }
        this.confident = confident
        val known = measured[ring]
        if (ring == current && known != null) {
            lockedRing = ring; lockedScore = known; belowStreak = 0
            stage = Stage.LOCKED
            return FocusSearch.Command.Locked(ring, known, confident)
        }
        stage = Stage.LOCKING
        return move(ring, nowMs)
    }

    private fun move(ring: Int, nowMs: Long): FocusSearch.Command.MoveTo {
        current = ring.coerceIn(0, bound)
        commandedAt = nowMs
        frames.clear()
        return FocusSearch.Command.MoveTo(current)
    }

    private fun monitor(nowMs: Long, score: Double, steady: Boolean): FocusSearch.Command? {
        if (!steady) { belowStreak = 0; return null }
        if (score < lockedScore * config.dropFraction) belowStreak++ else {
            belowStreak = 0
            if (score > lockedScore) lockedScore += 0.2 * (score - lockedScore)
        }
        if (belowStreak >= config.dropConfirmFrames) return begin(lockedRing, nowMs)
        return null
    }
}
