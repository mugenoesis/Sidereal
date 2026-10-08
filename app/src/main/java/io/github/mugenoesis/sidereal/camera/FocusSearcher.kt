package io.github.mugenoesis.sidereal.camera

/** What [SoftwareAfcController] needs from a focus search: start one, feed it frames, obey its commands. */
interface FocusSearcher {
    /** True once settled on a ring and just watching for the scene to change. */
    val locked: Boolean

    /** Starts (or restarts) a search near [seedRing], or from scratch if null. */
    fun begin(seedRing: Int?, nowMs: Long): FocusSearch.Command.MoveTo

    /** Feed each preview frame's sharpness with its time; a non-null result is the next thing to do. [steady] is false while the picture itself is changing. */
    fun onFrame(nowMs: Long, score: Double, steady: Boolean = true): FocusSearch.Command?
}

/**
 * Decides which focus search suits the light. The quick climb needs the preview's sharpness to be a clean, smooth
 * hill. Measured on the real X5, that holds whenever the ISO is low - even indoors at 1/10 s - and breaks down only
 * when the camera is working at very high ISO, where noise swamps the picture's detail.
 */
object FocusLight {
    private const val CLEAN_MAX_ISO = 800
    private const val SLOWEST_CLEAN_SHUTTER_SEC = 1.0 / 2
    private const val BRIGHT_SHUTTER_SEC = 1.0 / 60

    fun isBright(shutterName: String?, iso: Int?): Boolean {
        val seconds = shutterName?.let { ShutterLogic.exposureSeconds(it) }
        if (iso == null) return seconds != null && seconds <= BRIGHT_SHUTTER_SEC + 1e-9
        if (iso > CLEAN_MAX_ISO) return false
        return seconds == null || seconds <= SLOWEST_CLEAN_SHUTTER_SEC + 1e-9
    }
}
