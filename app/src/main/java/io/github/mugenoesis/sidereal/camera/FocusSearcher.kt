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
 * Decides which focus search suits the light. The quick climb needs the preview's sharpness to be a smooth hill,
 * which it is when the camera is using a fast shutter at a low ISO; in dim light it is noise and the scanning search
 * has to be used instead.
 */
object FocusLight {
    private const val BRIGHT_SHUTTER_SEC = 1.0 / 60
    private const val BRIGHT_MAX_ISO = 800

    fun isBright(shutterName: String?, iso: Int?): Boolean {
        val seconds = shutterName?.let { ShutterLogic.exposureSeconds(it) } ?: return false
        return seconds <= BRIGHT_SHUTTER_SEC + 1e-9 && (iso == null || iso <= BRIGHT_MAX_ISO)
    }
}
