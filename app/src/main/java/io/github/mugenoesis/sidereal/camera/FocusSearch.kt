package io.github.mugenoesis.sidereal.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the sharpest focus-ring position by measuring, not by feeling its way - the replacement for the old
 * reactive hill-climb, which could not work on this lens. Measured on the real X5 (see NOTES): across about 75%
 * of the ring the preview's sharpness is flat noise, with one narrow peak, and the picture follows a ring move
 * within ~150 ms. A climber started anywhere in the flat part has no gradient to follow (and a leash that stops
 * it ever reaching the peak), so it "missed focus completely".
 *
 * This works in stages, each measurement being "move the ring, wait for the picture to settle, average a couple of
 * frames":
 *  1. COARSE - a row of positions: a window around a starting hint (the camera's own hardware autofocus, which
 *     lands close) or, with no hint, the whole ring. A window whose best point is at its edge is extended; a
 *     window with no clear peak at all falls back to a full scan.
 *  2. REFINE - fit a parabola through the best point and its neighbours to land between the coarse points, then
 *     once more with a finer step. The final answer is always a position that was actually measured.
 *  3. LOCKING/LOCKED - go there, remember how sharp it was, and leave the ring completely alone. A collapse in
 *     sharpness that lasts (not one bad frame) means the subject or scene changed: search again from there.
 *
 * Pure logic with no Android or SDK types: drive it with [begin] and [onFrame], and obey the commands it returns.
 */
class FocusSearch(private val bound: Int, private val config: Config = Config()) {

    data class Config(
        /** The picture follows a ring move in ~50-150 ms; frames sooner than this after a command are ignored. */
        val settleMs: Long = 300,
        /** The coarse pass only has to tell a peak from flat ground (several times apart), so one frame is enough. */
        val coarseFramesPerPosition: Int = 1,
        /** Refining and locking compare close values, so those are averaged. */
        val framesPerPosition: Int = 2,
        val globalStepFraction: Double = 0.06,
        val seededStepFraction: Double = 0.04,
        /** The camera's hardware AF lands within about one step of the peak, so two either side is plenty (and means less visible racking). */
        val seededHalfWindowSteps: Int = 2,
        /** The best point must beat the worst by this factor to count as a real peak (frame noise alone stays under ~1.3). */
        val minContrast: Double = 1.5,
        /** Sharpness below this fraction of the locked value, for [dropConfirmFrames] frames running, means "changed". */
        val dropFraction: Double = 0.5,
        val dropConfirmFrames: Int = 4,
        val minRefineStep: Int = 4
    )

    sealed class Command {
        data class MoveTo(val ring: Int) : Command()

        /** Settled at [ring]; [confident] is false if no clear peak was found and the ring was parked at the hint instead. */
        data class Locked(val ring: Int, val score: Double, val confident: Boolean) : Command()
    }

    enum class Phase { IDLE, COARSE, REFINE, LOCKING, LOCKED }

    var phase: Phase = Phase.IDLE
        private set

    private val measured = LinkedHashMap<Int, Double>()
    private val queue = ArrayDeque<Int>()
    private val frames = ArrayList<Double>()
    private var current = 0
    private var commandedAt = 0L
    private var seed: Int? = null
    private var step = 1
    private var triedGlobal = false
    private var confident = true
    private var refineRound = 0
    private var parkAt: Int? = null

    private var lockedRing = 0
    private var lockedScore = 0.0
    private var belowStreak = 0

    /** Starts (or restarts) a search. [seedRing] is a position believed to be near focus; null scans the whole ring. */
    fun begin(seedRing: Int?, nowMs: Long): Command.MoveTo {
        measured.clear()
        queue.clear()
        frames.clear()
        seed = seedRing?.coerceIn(0, bound)
        triedGlobal = seedRing == null
        confident = true
        refineRound = 0
        parkAt = null
        belowStreak = 0
        phase = Phase.COARSE
        val fraction = if (seed != null) config.seededStepFraction else config.globalStepFraction
        step = max(config.minRefineStep, (bound * fraction).roundToInt())
        queue.addAll(initialPositions())
        return move(queue.removeFirst(), nowMs)
    }

    /**
     * Feed each preview frame's sharpness with its time; a non-null result is the next thing to do.
     * [steady] is false while the picture itself is changing (a pan in progress): a sharpness collapse then says
     * nothing about focus, so it is not allowed to restart a search.
     */
    fun onFrame(nowMs: Long, score: Double, steady: Boolean = true): Command? {
        if (phase == Phase.IDLE) return null
        if (nowMs - commandedAt < config.settleMs) return null
        if (phase == Phase.LOCKED) return monitor(nowMs, score, steady)

        frames += score
        val needed = if (phase == Phase.COARSE) config.coarseFramesPerPosition else config.framesPerPosition
        if (frames.size < needed) return null
        val mean = frames.average()
        measured[current] = mean
        frames.clear()

        return when (phase) {
            Phase.COARSE -> if (queue.isNotEmpty()) move(queue.removeFirst(), nowMs) else finishCoarse(nowMs)
            Phase.REFINE -> if (queue.isNotEmpty()) move(queue.removeFirst(), nowMs) else nextRefine(nowMs)
            Phase.LOCKING -> {
                lockedRing = current
                lockedScore = mean
                belowStreak = 0
                phase = Phase.LOCKED
                Command.Locked(current, mean, confident)
            }
            else -> null
        }
    }

    private fun move(ring: Int, nowMs: Long): Command.MoveTo {
        current = ring.coerceIn(0, bound)
        commandedAt = nowMs
        frames.clear()
        return Command.MoveTo(current)
    }

    private fun initialPositions(): List<Int> {
        val s = seed
        if (s == null) {
            val all = generateSequence(0) { it + step }.takeWhile { it <= bound }.toMutableList()
            if (all.last() != bound) all += bound
            return all
        }
        return (-config.seededHalfWindowSteps..config.seededHalfWindowSteps)
            .map { (s + it * step).coerceIn(0, bound) }
            .distinct()
    }

    private fun finishCoarse(nowMs: Long): Command? {
        val rings = measured.keys.sorted()
        val best = measured.maxByOrNull { it.value }!!
        // Real variation across the window means a peak is in (or beside) it. Best-over-worst rather than over the
        // median: a window that is mostly the peak's own skirt has a high median and would look flat.
        val worst = measured.values.minOrNull()!!
        val contrast = if (worst > 0) best.value / worst else Double.POSITIVE_INFINITY

        // A windowed search whose best point is at its edge may just have found the skirt of a peak further out.
        if (seed != null && contrast >= config.minContrast && (best.key == rings.first() || best.key == rings.last())) {
            val direction = if (best.key == rings.last()) 1 else -1
            val more = (1..config.seededHalfWindowSteps).map { (best.key + direction * it * step).coerceIn(0, bound) }
                .filter { it !in measured && it !in queue }.distinct()
            if (more.isNotEmpty()) {
                queue.addAll(more)
                return move(queue.removeFirst(), nowMs)
            }
        }

        if (contrast < config.minContrast) {
            // No clear peak near the hint: try the whole ring once; if that is also flat, trust the hint.
            if (!triedGlobal) {
                val hint = seed
                val first = begin(null, nowMs)
                parkAt = hint // if the whole ring is flat too, come back to the hint rather than guess
                return first
            }
            confident = false
            return park(parkAt ?: seed ?: best.key, nowMs)
        }

        phase = Phase.REFINE
        refineRound = 1
        val vertex = parabolaVertex(best.key, step)
        if (vertex != null && abs(vertex - best.key) >= config.minRefineStep && vertex !in measured) {
            queue.add(vertex)
            return move(queue.removeFirst(), nowMs)
        }
        return nextRefine(nowMs)
    }

    private fun nextRefine(nowMs: Long): Command? {
        refineRound++
        val best = measured.maxByOrNull { it.value }!!.key
        val fineStep = max(config.minRefineStep, step / 4)
        when (refineRound) {
            2 -> {
                val probes = listOf(best - fineStep, best + fineStep).map { it.coerceIn(0, bound) }
                    .filter { it !in measured }.distinct()
                if (probes.isNotEmpty()) {
                    queue.addAll(probes)
                    return move(queue.removeFirst(), nowMs)
                }
            }
            3 -> {
                val vertex = parabolaVertex(best, fineStep)
                if (vertex != null && abs(vertex - best) >= 2 && vertex !in measured) {
                    queue.add(vertex)
                    return move(queue.removeFirst(), nowMs)
                }
            }
        }
        if (refineRound < 3) return nextRefine(nowMs)
        return park(measured.maxByOrNull { it.value }!!.key, nowMs)
    }

    /** Peak of the parabola through (ring-h, ring, ring+h) if all three were measured and it is concave; else null. */
    private fun parabolaVertex(ring: Int, h: Int): Int? {
        val left = measured[ring - h] ?: return null
        val mid = measured[ring] ?: return null
        val right = measured[ring + h] ?: return null
        val denominator = left - 2 * mid + right
        if (denominator >= 0) return null
        val offset = h * (left - right) / (2 * denominator)
        return (ring + offset.coerceIn(-h.toDouble(), h.toDouble())).roundToInt().coerceIn(0, bound)
    }

    private fun park(ring: Int, nowMs: Long): Command {
        phase = Phase.LOCKING
        queue.clear()
        return move(ring, nowMs)
    }

    private fun monitor(nowMs: Long, score: Double, steady: Boolean): Command? {
        if (!steady) {
            belowStreak = 0
            return null
        }
        if (score < lockedScore * config.dropFraction) {
            belowStreak++
        } else {
            belowStreak = 0
            if (score > lockedScore) lockedScore += 0.2 * (score - lockedScore)
        }
        if (belowStreak >= config.dropConfirmFrames) return begin(lockedRing, nowMs)
        return null
    }
}
