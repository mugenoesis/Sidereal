package io.github.mugenoesis.sidereal.wearprotocol

import kotlin.math.max
import kotlin.math.min

/** How big and how compressed a live-view frame is ([encodeQuality] is the lossy image encoder's 0-100 setting). */
data class FrameQuality(val maxSide: Int, val encodeQuality: Int)

/**
 * Keeps the watch's picture current without wasting the link. Writing a frame into the Bluetooth channel only means
 * it was buffered - not that it arrived - so pacing by the write let frames pile up in the pipe and the picture drift
 * seconds behind. This paces by DELIVERY: the watch tells the phone how many frames it has received.
 *
 * Measured on a real watch, the link is slow to respond (about a third of a second per round trip) far more than it
 * is narrow, so sending one frame at a time wastes it. The pacer therefore learns how many frames can be out at once
 * ([window], found the way TCP finds it): it adds one while the round trip stays at its best, and takes one away when
 * frames start queueing up behind each other - which is exactly when the picture would start to lag.
 *
 * Lag matters more than frame rate, so there is a hard budget on the round trip ([LAG_BUDGET_MS]): over it, the pacer
 * sends fewer at once, and if that is already one, smaller frames. It only adds a frame in flight, or sharpens the
 * picture, while there is clear headroom under the budget.
 *
 * Picture quality follows: it steps down only when even one frame at a time is too slow, and earns its way up (past
 * where it used to be fixed) only once the link has kept up at the full window for a while.
 */
class LivePacer(
    private val minIntervalMs: Long = 60,
    private val ackTimeoutMs: Long = 3_000
) {
    var level: Int = DEFAULT_LEVEL
        private set

    val quality: FrameQuality get() = LEVELS[level]

    /** How many frames may be sent and not yet confirmed. */
    var window: Int = 1
        private set

    /** The most recent round trip (frame sent -> watch confirms), and a smoothed average of them. */
    var lastRttMs: Double? = null
        private set
    var smoothedRttMs: Double? = null
        private set

    /** The usual round trip with ONE frame out at a time - what pipelining more frames is judged against. */
    private var singleFrameRttMs: Double? = null
    private var growBlockAcks = 0
    private val unconfirmed = ArrayDeque<Long>()
    private var totalSent = 0
    private var totalConfirmed = 0
    private var lastSentAt: Long? = null
    private var goodStreak = 0
    private var settleAcks = 0

    fun shouldSend(nowMs: Long): Boolean {
        val oldest = unconfirmed.firstOrNull()
        if (oldest != null && nowMs - oldest > ackTimeoutMs) {
            // Never confirmed: give up on it, and ease right off - the link is clearly struggling.
            unconfirmed.clear()
            totalConfirmed = totalSent
            window = 1
            goodStreak = 0
            singleFrameRttMs = null
            stepQualityDown()
        }
        if (unconfirmed.size >= window) return false
        val last = lastSentAt ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun onSent(nowMs: Long) {
        unconfirmed.addLast(nowMs)
        totalSent++
        lastSentAt = nowMs
    }

    /** The watch reports it has now received [receivedCount] frames in total. */
    fun onAck(nowMs: Long, receivedCount: Int) {
        val count = min(receivedCount, totalSent)
        val fresh = count - totalConfirmed
        if (fresh <= 0 || unconfirmed.isEmpty()) return
        val covered = min(fresh, unconfirmed.size)
        repeat(covered - 1) { unconfirmed.removeFirst() }
        val sentAt = unconfirmed.removeFirst()
        totalConfirmed = count
        val rtt = (nowMs - sentAt).toDouble()
        lastRttMs = rtt
        smoothedRttMs = smoothedRttMs?.let { it * 0.7 + rtt * 0.3 } ?: rtt
        if (window == 1) singleFrameRttMs = singleFrameRttMs?.let { it * 0.85 + rtt * 0.15 } ?: rtt
        if (growBlockAcks > 0) growBlockAcks--
        adapt()
    }

    private fun adapt() {
        if (settleAcks > 0) { settleAcks--; return }
        val smooth = smoothedRttMs ?: return
        val single = singleFrameRttMs ?: return
        // What the round trip may be with this many frames out: a little more than one frame alone, since each extra
        // frame in flight can add a little. Latency-bound links stay inside this however many frames are out;
        // bandwidth-bound ones go over it, because the frames then queue behind each other.
        val allowed = single * (1.3 + 0.2 * (window - 1))
        when {
            smooth > LAG_BUDGET_MS -> {
                // Over the budget: send fewer at once, and if that is already one, send smaller pictures.
                goodStreak = 0
                if (window > 1) {
                    window--
                    settleAcks = SETTLE_ACKS
                    growBlockAcks = GROW_BLOCK_ACKS
                } else {
                    stepQualityDown()
                }
            }
            window > 1 && smooth > allowed * QUEUEING_MARGIN -> {
                // Frames are waiting behind each other: one fewer at once. Only a hint on a jumpy link, so it never
                // costs picture quality - just frame rate.
                goodStreak = 0
                window--
                settleAcks = SETTLE_ACKS
                growBlockAcks = GROW_BLOCK_ACKS
            }
            smooth <= allowed && smooth < LAG_BUDGET_MS * HEADROOM -> {
                goodStreak++
                if (window < MAX_WINDOW) {
                    if (goodStreak >= GROW_ACKS && growBlockAcks == 0) {
                        window++
                        goodStreak = 0
                        settleAcks = SETTLE_ACKS
                    }
                } else if (goodStreak >= UP_ACKS && level < LEVELS.lastIndex) {
                    // Kept up at the full window with room to spare: a sharper picture - and learn the new size afresh.
                    level++
                    window = 1
                    goodStreak = 0
                    settleAcks = SETTLE_ACKS
                    singleFrameRttMs = null
                }
            }
            else -> goodStreak = 0
        }
    }

    private fun stepQualityDown() {
        goodStreak = 0
        if (level > 0) {
            level = max(0, level - 1)
            settleAcks = SETTLE_ACKS
            window = 1
            singleFrameRttMs = null
        }
    }

    companion object {
        /** Smallest/most compressed first. Level 2 is what the live view started with; the rest are earned or given up. */
        val LEVELS = listOf(
            FrameQuality(180, 40),
            FrameQuality(230, 50),
            FrameQuality(280, 55),
            FrameQuality(330, 65),
            FrameQuality(380, 75)
        )
        const val DEFAULT_LEVEL = 2
        const val MAX_WINDOW = 4

        private const val QUEUEING_MARGIN = 1.15  // this far over what is allowed: frames are queueing behind each other
        const val LAG_BUDGET_MS = 600.0           // the round trip is never allowed to stay above this
        private const val HEADROOM = 0.8          // grow or sharpen only while under this share of the budget
        private const val GROW_BLOCK_ACKS = 30    // after backing off, do not probe a bigger window again straight away
        private const val GROW_ACKS = 5
        private const val UP_ACKS = 10
        private const val SETTLE_ACKS = 3         // let a change show up in the round trips before judging again
    }
}
