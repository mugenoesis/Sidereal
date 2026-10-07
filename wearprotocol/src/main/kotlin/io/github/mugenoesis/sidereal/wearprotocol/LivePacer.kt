package io.github.mugenoesis.sidereal.wearprotocol

import kotlin.math.max
import kotlin.math.min

/** How big and how compressed a live-view frame is ([encodeQuality] is the lossy image encoder's 0-100 setting). */
data class FrameQuality(val maxSide: Int, val encodeQuality: Int)

/**
 * Keeps the watch's picture current. Writing a frame into the Bluetooth channel only means it was buffered - not that
 * it arrived - so pacing by the write let frames pile up in the pipe and the picture drift seconds behind. This paces
 * by DELIVERY: the watch tells the phone how many frames it has received, no frame goes out while the allowed number
 * are still unconfirmed (one on a slow link, two when the link is quick enough to keep it busy), and a frame that is
 * never confirmed is given up on rather than waited for.
 *
 * The picture quality follows the link: it steps down when confirmations come back slowly, and earns its way back up -
 * past where it used to be fixed - only after the link has stayed fast for a while, so one slow frame doesn't flap it.
 */
class LivePacer(
    private val minIntervalMs: Long = 60,
    private val ackTimeoutMs: Long = 3_000
) {
    var level: Int = DEFAULT_LEVEL
        private set

    val quality: FrameQuality get() = LEVELS[level]

    /** The most recent round trip (frame sent -> watch confirms), and a smoothed average of them. */
    var lastRttMs: Double? = null
        private set
    var smoothedRttMs: Double? = null
        private set

    private val unconfirmed = ArrayDeque<Long>()
    private var totalSent = 0
    private var totalConfirmed = 0
    private var lastSentAt: Long? = null
    private var goodStreak = 0
    private var settleAcks = 0

    fun shouldSend(nowMs: Long): Boolean {
        val oldest = unconfirmed.firstOrNull()
        if (oldest != null && nowMs - oldest > ackTimeoutMs) {
            // Never confirmed: give up on it, and ease off - the link is clearly struggling.
            unconfirmed.clear()
            totalConfirmed = totalSent
            stepDown()
        }
        val rtt = smoothedRttMs
        val allowed = if (rtt != null && rtt < FAST_RTT_MS) 2 else 1
        if (unconfirmed.size >= allowed) return false
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
        adapt()
    }

    private fun adapt() {
        if (settleAcks > 0) { settleAcks--; return }
        val rtt = smoothedRttMs ?: return
        when {
            rtt > SLOW_RTT_MS -> stepDown()
            rtt < GOOD_RTT_MS -> {
                goodStreak++
                if (goodStreak >= GOOD_ACKS_TO_STEP_UP && level < LEVELS.lastIndex) {
                    level++
                    goodStreak = 0
                    settleAcks = SETTLE_ACKS
                }
            }
            else -> goodStreak = 0
        }
    }

    private fun stepDown() {
        goodStreak = 0
        if (level > 0) {
            level = max(0, level - 1)
            settleAcks = SETTLE_ACKS
        }
    }

    companion object {
        /** Smallest/most compressed first. Level 2 is what the live view always used; the rest are earned or given up. */
        val LEVELS = listOf(
            FrameQuality(180, 40),
            FrameQuality(230, 48),
            FrameQuality(280, 55),
            FrameQuality(330, 64),
            FrameQuality(380, 72)
        )
        const val DEFAULT_LEVEL = 2

        private const val FAST_RTT_MS = 200.0   // quick enough to keep a second frame in flight
        private const val GOOD_RTT_MS = 160.0   // quick enough to spend on better quality
        private const val SLOW_RTT_MS = 450.0   // too slow: smaller frames
        private const val GOOD_ACKS_TO_STEP_UP = 10
        private const val SETTLE_ACKS = 3       // let the new size show up in the round trips before judging again
    }
}
