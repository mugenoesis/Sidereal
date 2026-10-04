package io.github.mugenoesis.sidereal.camera

/**
 * Tracks which end of an ordinal-ordered stepper range the camera has
 * actually rejected this session, so once a real boundary is discovered,
 * further presses in that same direction stop short of it instead of
 * resending the identical doomed command over and over.
 *
 * Exists specifically because of a real, confirmed bug: MainActivity's EV
 * stepper walks the full SettingsDefinitions.ExposureCompensation range
 * (+-5.0 EV), but the real camera's supported range is narrower and isn't
 * queryable ahead of time (see ExposureController.setExposureCompensation's
 * doc comment - no DJIParamMinMaxCapability/CapabilityKey exists for EV,
 * unlike gimbal pitch/yaw). Without this, every press past the real ceiling
 * recomputed and resent the SAME rejected value (or an even further one)
 * on every subsequent tap, forever - confirmed on real hardware via
 * repeated "setExposureCompensation(N_4_0) failed: Param Illegal" log
 * spam from a single boundary, with the UI showing no forward progress and
 * no indication anything had actually been learned from the rejection.
 */
class LearnedStepBounds(private val size: Int) {

    companion object {
        /**
         * Only "Param Illegal" actually means "past the camera's real
         * range" - confirmed on real hardware that setExposureCompensation
         * can also fail with "Cannot set the parameters in this state"
         * (wrong exposure mode, e.g. MANUAL) and "Invalid key for
         * component" (seen immediately after connect, before the camera's
         * parameter model had fully synced - a one-off timing race, not a
         * range signal). Treating every rejection as a discovered boundary
         * was itself a real, confirmed bug: a single transient/unrelated
         * failure permanently disabled stepping in that direction for the
         * rest of the session, nowhere near the real range limit.
         */
        fun isOutOfRangeError(error: String): Boolean = error.contains("Param Illegal", ignoreCase = true)
    }

    private var minValidIndex = 0
    private var maxValidIndex = size - 1

    /** Clamp a proposed index to whatever range has been confirmed valid so far. */
    fun clamp(index: Int): Int = index.coerceIn(minValidIndex, maxValidIndex)

    /**
     * Record that [rejectedIndex] was rejected by the camera, with
     * [lastGoodIndex] being the index stepping was attempted from. Narrows
     * the learned bound on whichever side [rejectedIndex] fell past.
     */
    fun recordRejection(rejectedIndex: Int, lastGoodIndex: Int) {
        when {
            rejectedIndex < lastGoodIndex -> minValidIndex = maxOf(minValidIndex, lastGoodIndex)
            rejectedIndex > lastGoodIndex -> maxValidIndex = minOf(maxValidIndex, lastGoodIndex)
        }
    }

    /** Forget any learned bounds - call when the camera/lens changes, since a different body may support a different range. */
    fun reset() {
        minValidIndex = 0
        maxValidIndex = size - 1
    }
}
