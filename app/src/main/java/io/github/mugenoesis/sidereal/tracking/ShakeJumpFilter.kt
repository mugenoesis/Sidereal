package io.github.mugenoesis.sidereal.tracking

/**
 * Rejects handheld-shake spikes distinct from real subject movement,
 * extracted from FaceTrackingController.isShakeJump() so it's unit-testable
 * on its own. See that method's original doc comment (preserved below) for
 * why this compares only against the immediately preceding sample rather
 * than a windowed average.
 *
 * A big jump isn't rejected outright: it's held as "pending," and if the
 * very next sample lands near it (within [shakeConfirmDistance]), that's
 * treated as confirmation of real movement - both this sample and the
 * pending one become the new accepted position, so a genuine fast move
 * only costs one frame of latency, not the multi-second lag a windowed-
 * average version of this check was found to cause on real hardware. An
 * isolated spike that doesn't repeat next frame just gets discarded when
 * the following sample lands back near the old baseline instead.
 */
class ShakeJumpFilter(
    private val shakeJumpDistance: Double = 0.12,
    private val shakeConfirmDistance: Double = 0.05
) {
    private var lastAcceptedX = 0.5
    private var lastAcceptedY = 0.5
    private var hasAcceptedPosition = false
    private var pendingJumpX: Double? = null
    private var pendingJumpY: Double? = null

    /** True if (x, y) should be treated as a shake spike and skipped rather than acted on. */
    fun isShakeJump(x: Double, y: Double): Boolean {
        if (!hasAcceptedPosition) {
            lastAcceptedX = x
            lastAcceptedY = y
            hasAcceptedPosition = true
            return false
        }

        val dx = x - lastAcceptedX
        val dy = y - lastAcceptedY
        val isBigJump = kotlin.math.sqrt(dx * dx + dy * dy) > shakeJumpDistance

        if (!isBigJump) {
            lastAcceptedX = x
            lastAcceptedY = y
            pendingJumpX = null
            pendingJumpY = null
            return false
        }

        val pX = pendingJumpX
        val pY = pendingJumpY
        if (pX != null && pY != null) {
            val pdx = x - pX
            val pdy = y - pY
            if (kotlin.math.sqrt(pdx * pdx + pdy * pdy) <= shakeConfirmDistance) {
                // Confirmed: two consecutive samples landed near the same
                // new spot, so this is real movement, not a one-off spike.
                lastAcceptedX = x
                lastAcceptedY = y
                pendingJumpX = null
                pendingJumpY = null
                return false
            }
        }

        // First big jump seen, or the previous pending one wasn't
        // confirmed by this sample - hold this as the new pending
        // candidate and reject it for now.
        pendingJumpX = x
        pendingJumpY = y
        return true
    }

    fun reset() {
        hasAcceptedPosition = false
        pendingJumpX = null
        pendingJumpY = null
    }
}
