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

    private var bestRttMs: Double? = null
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
            bestRttMs = null
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
        // The best round trip seen, drifting up slowly so it can follow a link that really has become slower.
        bestRttMs = bestRttMs?.let { min(rtt, it + BEST_CREEP_MS) } ?: rtt
        adapt()
    }

    private fun adapt() {
        if (settleAcks > 0) { settleAcks--; return }
        val smooth = smoothedRttMs ?: return
        val best = bestRttMs ?: return
        val ratio = smooth / best
        when {
            smooth > LAG_BUDGET_MS || ratio > QUEUEING_RATIO -> {
                // Frames are waiting behind each other: send fewer at once, and if it is already one, send smaller ones.
                goodStreak = 0
                if (window > 1) {
                    window--
                    settleAcks = SETTLE_ACKS
                } else {
                    stepQualityDown()
                }
            }
            ratio <= STEADY_RATIO && smooth < LAG_BUDGET_MS * HEADROOM -> {
                goodStreak++
                if (window < MAX_WINDOW) {
                    if (goodStreak >= GROW_ACKS) {
                        window++
                        goodStreak = 0
                        settleAcks = SETTLE_ACKS
                    }
                } else if (goodStreak >= UP_ACKS && level < LEVELS.lastIndex) {
                    level++
                    goodStreak = 0
                    settleAcks = SETTLE_ACKS
                    bestRttMs = null // bigger frames have a different best round trip: learn it afresh
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
            bestRttMs = null
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

        private const val STEADY_RATIO = 1.5      // round trip still near its best: room for another frame in flight
        private const val QUEUEING_RATIO = 2.0    // twice its best: frames are queueing behind each other
        const val LAG_BUDGET_MS = 600.0           // the round trip is never allowed to stay above this
        private const val HEADROOM = 0.8          // grow or sharpen only while under this share of the budget
        private const val BEST_CREEP_MS = 3.0
        private const val GROW_ACKS = 5
        private const val UP_ACKS = 10
        private const val SETTLE_ACKS = 3         // let a change show up in the round trips before judging again
    }
}
