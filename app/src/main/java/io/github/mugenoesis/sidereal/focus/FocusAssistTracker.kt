package io.github.mugenoesis.sidereal.focus

enum class FocusTrend { SHARPER, STEADY, SOFTER }

/**
 * @param smoothedFwhm smoothed star size in pixels, null until a star has been seen
 * @param best smallest smoothed size seen since the last reset - the focus target to get back to
 * @param atBest the current size is within 10% of [best]
 * @param lost no star has been found for several frames in a row
 */
data class FocusAssistState(
    val smoothedFwhm: Float? = null,
    val best: Float? = null,
    val trend: FocusTrend = FocusTrend.STEADY,
    val atBest: Boolean = false,
    val lost: Boolean = false
)

/**
 * Turns the stream of per-frame star measurements into something steady
 * enough to focus by: smoothing so the number doesn't flicker, a
 * better/worse arrow, and the best value seen so the user can tell when
 * they've found it (and overshot it).
 */
class FocusAssistTracker(
    private val smoothing: Float = 0.4f,
    private val trendDeadband: Float = 0.05f,
    private val lostAfterMisses: Int = 5
) {
    var state = FocusAssistState()
        private set

    private var misses = 0

    /** Feed one frame's FWHM, or null if no star was found in it. */
    fun onMeasurement(fwhm: Float?) {
        if (fwhm == null) {
            misses++
            if (misses >= lostAfterMisses) state = state.copy(lost = true)
            return
        }
        val previous = if (state.lost) null else state.smoothedFwhm
        val smoothed = if (previous == null) fwhm else previous + smoothing * (fwhm - previous)
        val trend = when {
            previous == null -> FocusTrend.STEADY
            smoothed - previous < -trendDeadband -> FocusTrend.SHARPER
            smoothed - previous > trendDeadband -> FocusTrend.SOFTER
            else -> FocusTrend.STEADY
        }
        val best = state.best?.let { minOf(it, smoothed) } ?: smoothed
        misses = 0
        state = FocusAssistState(
            smoothedFwhm = smoothed,
            best = best,
            trend = trend,
            atBest = smoothed <= best * 1.1f,
            lost = false
        )
    }

    fun reset() {
        misses = 0
        state = FocusAssistState()
    }
}
