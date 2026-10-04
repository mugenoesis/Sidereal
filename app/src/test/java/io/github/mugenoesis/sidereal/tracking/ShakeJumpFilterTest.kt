package io.github.mugenoesis.sidereal.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeJumpFilterTest {

    @Test
    fun `first sample is always accepted, seeding the baseline`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        assertFalse(filter.isShakeJump(0.5, 0.5))
    }

    @Test
    fun `small movement under the jump distance is accepted immediately`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        assertFalse(filter.isShakeJump(0.55, 0.5)) // dx=0.05, well under 0.12
    }

    @Test
    fun `a single isolated big jump is rejected as a spike`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        assertTrue(filter.isShakeJump(0.8, 0.5)) // dx=0.3, well over 0.12
    }

    @Test
    fun `an isolated spike that is not confirmed next frame is discarded and baseline holds`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        filter.isShakeJump(0.8, 0.5) // rejected spike, held as pending
        // Sample lands back near the ORIGINAL baseline, not near the pending spike -
        // should be accepted as a small move from 0.5, not confirm the spike.
        assertFalse(filter.isShakeJump(0.52, 0.5))
    }

    @Test
    fun `two consecutive samples landing near the same new spot confirm real movement`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        assertTrue(filter.isShakeJump(0.8, 0.5)) // big jump, held pending
        // Next sample lands within shakeConfirmDistance (0.05) of the pending jump (0.8) - confirmed real.
        assertFalse(filter.isShakeJump(0.82, 0.5))
    }

    @Test
    fun `after confirmation the new position becomes the baseline for future comparisons`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        filter.isShakeJump(0.8, 0.5)
        filter.isShakeJump(0.82, 0.5) // confirmed, baseline now ~0.82
        // A further small move near 0.82 should be accepted as ordinary movement, not another big jump.
        assertFalse(filter.isShakeJump(0.85, 0.5))
    }

    @Test
    fun `reset forgets the baseline so the next sample is treated as the first`() {
        val filter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)
        filter.isShakeJump(0.5, 0.5)
        filter.reset()
        // Without a baseline, even a position far from 0.5 must be accepted outright.
        assertFalse(filter.isShakeJump(0.9, 0.9))
    }
}
