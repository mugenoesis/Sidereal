package io.github.mugenoesis.sidereal.sequence

sealed class PhotoStatus {
    object Waiting : PhotoStatus()
    object InProgress : PhotoStatus()
    object Done : PhotoStatus()
    data class TimedOut(val reason: String) : PhotoStatus()
}

/**
 * Decides when a photo is truly finished from the camera's pushed
 * `isShootingSinglePhoto` / `isStoringPhoto` flags. Measured on the X5:
 * `startShootPhoto`'s own callback returns after ~250ms (command accepted,
 * nothing more), both flags go true together, shooting drops about 1.4s
 * after the exposure ends, and storing drops ~1s after that - so the shot
 * is only done once both have stayed false for [quietMs]. Firing the next
 * shutter before that is how sequences drop frames.
 *
 * @param exposureMs how long the shutter is open - sets the "stuck" deadline
 * @param startTimeoutMs how long to wait for the camera to report starting
 * @param graceMs extra time past the exposure before declaring the camera stuck
 * @param quietMs how long both flags must stay false (a blip between shooting and storing is not "done")
 */
class PhotoCompletionTracker(
    private val exposureMs: Long,
    private val startTimeoutMs: Long = 3_000,
    private val graceMs: Long = 30_000,
    private val quietMs: Long = 300
) {
    private var started = false
    private var quietSince: Long? = null
    private var finished: PhotoStatus? = null

    /** [elapsedMs] is time since the shoot command was sent. */
    fun onSample(elapsedMs: Long, shooting: Boolean, storing: Boolean): PhotoStatus {
        finished?.let { return it }
        val busy = shooting || storing

        if (!started) {
            if (!busy) {
                return if (elapsedMs > startTimeoutMs) {
                    TimedOutResult("Camera never reported the shot starting").also { finished = it }
                } else PhotoStatus.Waiting
            }
            started = true
        }

        if (busy) {
            quietSince = null
        } else {
            val since = quietSince ?: elapsedMs.also { quietSince = it }
            if (elapsedMs - since >= quietMs) return PhotoStatus.Done.also { finished = it }
        }

        if (elapsedMs > exposureMs + graceMs) {
            return TimedOutResult("Camera did not finish the shot within ${(exposureMs + graceMs) / 1000}s").also { finished = it }
        }
        return PhotoStatus.InProgress
    }

    private fun TimedOutResult(reason: String) = PhotoStatus.TimedOut(reason)
}
