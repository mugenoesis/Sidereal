package io.github.mugenoesis.sidereal.wearprotocol

import kotlin.math.max
import kotlin.math.min

/**
 * Decides when the next live-view frame may go out. Never queues: while one frame is still being sent the next
 * is simply skipped (the freshest frame beats a backlog on a slow Bluetooth link), and if sends are slow the
 * interval stretches so the link isn't flooded, then recovers once they are fast again.
 */
class FramePacer(private val minIntervalMs: Long, private val maxIntervalMs: Long = 1_000) {

    var currentIntervalMs: Long = minIntervalMs
        private set

    private var lastSentAt: Long? = null
    private var inFlight = false

    fun shouldSend(nowMs: Long): Boolean {
        if (inFlight) return false
        val last = lastSentAt ?: return true
        return nowMs - last >= currentIntervalMs
    }

    fun onSendStarted() {
        inFlight = true
    }

    /** A frame finished going out at [nowMs], having taken [durationMs]. */
    fun onSent(nowMs: Long, durationMs: Long) {
        inFlight = false
        lastSentAt = nowMs
        currentIntervalMs = if (durationMs > currentIntervalMs / 2) {
            min(maxIntervalMs, currentIntervalMs * 2)
        } else {
            max(minIntervalMs, (currentIntervalMs * 0.8).toLong())
        }
    }
}

object Thumbnail {
    /** The size to shrink a [srcWidth] x [srcHeight] frame to so its longest side is [maxSide]; never enlarges. */
    fun fit(srcWidth: Int, srcHeight: Int, maxSide: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val scale = min(1.0, maxSide.toDouble() / max(srcWidth, srcHeight))
        return max(1, Math.round(srcWidth * scale).toInt()) to max(1, Math.round(srcHeight * scale).toInt())
    }
}
