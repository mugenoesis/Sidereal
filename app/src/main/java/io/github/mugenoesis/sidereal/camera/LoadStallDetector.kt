package io.github.mugenoesis.sidereal.camera

/**
 * Notices when the camera's media browser has been "loading" for too long.
 * The camera's media index can wedge in a syncing state (seen on the X5
 * after many settings changes) and the SDK then neither succeeds nor fails
 * promptly, which left the screen on "Loading files..." forever.
 */
class LoadStallDetector(private val timeoutMs: Long) {

    private var loadingSince: Long? = null

    /** Feed the current state with a monotonic clock reading; true once loading has outlasted the timeout. */
    fun isStalled(state: MediaLoadState, nowMs: Long): Boolean {
        val loading = state == MediaLoadState.ENTERING_MODE || state == MediaLoadState.LOADING
        if (!loading) {
            loadingSince = null
            return false
        }
        val since = loadingSince ?: nowMs.also { loadingSince = it }
        return nowMs - since > timeoutMs
    }
}
