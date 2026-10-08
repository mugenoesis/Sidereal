package io.github.mugenoesis.sidereal.tracking

/**
 * Pure math extracted from FaceTrackingController so it's unit-testable
 * without a live Gimbal/DJIConnectionManager (see CameraGateway's doc
 * comment for why touching DJI SDK types in a JVM unit test is generally
 * unsafe) - and, separately, without android.graphics.RectF: confirmed by
 * direct probing that under this project's `returnDefaultValues = true`
 * unit-test stub android.jar, RectF is fully stubbed - even its
 * constructor doesn't set fields, so centerX()/centerY() always return 0.0
 * regardless of the real box passed in. That makes RectF just as unsafe to
 * touch meaningfully from a JVM test as any DJI SDK bundle type, so
 * [findReacquireCandidateId] takes plain center coordinates rather than
 * DetectedFace/RectF directly.
 */
object FaceTrackingMath {

    /**
     * The tracker's gains turn "how far off-centre, as a fraction of the frame" into degrees per second, and were tuned
     * on a 60 degree wide lens. The same fraction is fewer degrees on a narrower lens, so the same gain would overshoot;
     * scale the output by how wide the view is compared with [referenceFovDeg]. Unknown field of view: unchanged.
     */
    fun fovGainScale(fovDeg: Double?, referenceFovDeg: Double): Double =
        if (fovDeg == null || fovDeg <= 0.0) 1.0 else (fovDeg / referenceFovDeg).coerceIn(0.25, 1.25)

    /** Errors smaller than [deadband] are treated as zero rather than fed to a PID. */
    fun isWithinDeadband(error: Double, deadband: Double): Boolean = kotlin.math.abs(error) < deadband

    /** A face candidate reduced to only what reacquire-matching needs - see this file's class doc comment on why not RectF. */
    data class FaceCenter(val trackingId: Int, val centerX: Double, val centerY: Double)

    /**
     * Picks up tracking again under a new trackingId after ML Kit's internal
     * tracker drops the old one. Adopts the closest visible face to
     * ([lastFaceX], [lastFaceY]) within [maxDistance] - null if none qualify.
     * Returns the trackingId rather than a whole DetectedFace, since the
     * caller already has the full list to look it back up in.
     */
    fun findReacquireCandidateId(faces: List<FaceCenter>, lastFaceX: Double, lastFaceY: Double, maxDistance: Double): Int? {
        return faces.minByOrNull { face ->
            val dx = face.centerX - lastFaceX
            val dy = face.centerY - lastFaceY
            dx * dx + dy * dy
        }?.takeIf { face ->
            val dx = face.centerX - lastFaceX
            val dy = face.centerY - lastFaceY
            kotlin.math.sqrt(dx * dx + dy * dy) <= maxDistance
        }?.trackingId
    }

    /**
     * Best-effort coasting rate while the face is lost, linearly decayed to
     * zero over [holdMillis] - null once the hold window has expired (caller
     * should fall back to genuinely holding/giving up at that point). See
     * FaceTrackingController.processLockedFrame's doc comment for why this
     * coasts instead of freezing or continuing at full tracking speed.
     */
    fun coastRate(lastKnownRate: Double, maxRate: Double, elapsedSinceLostMs: Long, holdMillis: Long): Double? {
        if (elapsedSinceLostMs > holdMillis) return null
        val decay = 1.0 - elapsedSinceLostMs.toDouble() / holdMillis
        return lastKnownRate.coerceIn(-maxRate, maxRate) * decay
    }

    /**
     * True when the accumulated [target] has diverged from the gimbal's
     * [actual] reported attitude by more than [threshold] - signals an
     * external move (e.g. the physical stick) the accumulator should resync
     * to, rather than resyncing unconditionally every frame. See
     * FaceTrackingController.applyCorrection's doc comment for why an
     * unconditional resync was tried first and rejected (caps tracking
     * speed to real-time hardware response).
     */
    fun shouldResyncTarget(target: Float, actual: Float, threshold: Float): Boolean =
        kotlin.math.abs(actual - target) > threshold
}
