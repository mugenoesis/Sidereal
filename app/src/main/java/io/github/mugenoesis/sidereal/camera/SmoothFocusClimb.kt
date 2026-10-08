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
        /**
         * Two frames of a still picture at the same ring differing by more than this (as a fraction of their mean,
         * averaged over the first positions) means noise, not a hill: measured 1-6% in clean light, 20-30% at ISO 25600.
         */
        val maxNoise: Double = 0.15
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
    private var noiseSum = 0.0
    private var noiseCount = 0
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
        noiseSum = 0.0
        noiseCount = 0
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
        if (frames.size < config.framesPerPosition) return null
        val mean = frames.average()
        if (stage == Stage.CENTRE || stage == Stage.PROBE) {
            if (mean > 0) { noiseSum += (frames.max() - frames.min()) / mean; noiseCount++ }
        }
        frames.clear()
        measured[current] = mean
        count++
        return decide(mean, nowMs)
    }

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
            else if (noiseCount > 0 && noiseSum / noiseCount > config.maxNoise) {
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
        when {
            value > bestValue * (1 + config.improve) -> {
                gentleStreak = 0
                bestRing = current; bestValue = value
                step = min(max(config.minStep, (step * config.stepGrowth).roundToInt()), (bound * config.maxStepFraction).roundToInt())
                return climbFrom(current, nowMs)
            }
            value > bestValue * (1 + config.gentle) && ++gentleStreak < config.gentleLimit -> {
                bestRing = current; bestValue = value
                step = min(max(config.minStep, (step * 2.0).roundToInt()), (bound * config.maxStepFraction).roundToInt())
                return climbFrom(current, nowMs)
            }
            value < bestValue * (1 - config.flat) -> {
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
