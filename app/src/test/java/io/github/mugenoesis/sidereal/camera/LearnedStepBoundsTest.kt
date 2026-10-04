package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedStepBoundsTest {

    @Test
    fun `isOutOfRangeError is true only for Param Illegal`() {
        assertTrue(LearnedStepBounds.isOutOfRangeError("Param Illegal"))
        assertTrue(LearnedStepBounds.isOutOfRangeError("param illegal")) // case-insensitive
    }

    @Test
    fun `isOutOfRangeError is false for other real rejection reasons`() {
        // Regression test: these are real, confirmed-distinct rejection
        // reasons from real hardware testing - none of them mean "past the
        // camera's real EV range," and treating them as if they did was
        // itself a confirmed bug (a single transient/unrelated failure
        // permanently disabled stepping in that direction all session).
        assertFalse(LearnedStepBounds.isOutOfRangeError("Cannot set the parameters in this state"))
        assertFalse(LearnedStepBounds.isOutOfRangeError("Invalid key for component"))
        assertFalse(LearnedStepBounds.isOutOfRangeError("No camera connected"))
    }

    @Test
    fun `clamp is a no-op within the full range before anything is learned`() {
        val bounds = LearnedStepBounds(size = 10)
        assertEquals(0, bounds.clamp(0))
        assertEquals(9, bounds.clamp(9))
        assertEquals(5, bounds.clamp(5))
    }

    @Test
    fun `clamp still respects the array's own bounds before anything is learned`() {
        val bounds = LearnedStepBounds(size = 10)
        assertEquals(0, bounds.clamp(-3))
        assertEquals(9, bounds.clamp(20))
    }

    @Test
    fun `a rejection below the last-good index narrows the lower bound to last-good`() {
        val bounds = LearnedStepBounds(size = 10)
        bounds.recordRejection(rejectedIndex = 3, lastGoodIndex = 4)
        // Stepping any further down than 4 should now clamp back to 4.
        assertEquals(4, bounds.clamp(3))
        assertEquals(4, bounds.clamp(0))
        // Upward stepping is unaffected.
        assertEquals(7, bounds.clamp(7))
    }

    @Test
    fun `a rejection above the last-good index narrows the upper bound to last-good`() {
        val bounds = LearnedStepBounds(size = 10)
        bounds.recordRejection(rejectedIndex = 6, lastGoodIndex = 5)
        assertEquals(5, bounds.clamp(6))
        assertEquals(5, bounds.clamp(9))
        assertEquals(2, bounds.clamp(2))
    }

    @Test
    fun `repeated rejections at the same boundary do not move it further`() {
        val bounds = LearnedStepBounds(size = 10)
        bounds.recordRejection(rejectedIndex = 6, lastGoodIndex = 5)
        bounds.recordRejection(rejectedIndex = 6, lastGoodIndex = 5)
        bounds.recordRejection(rejectedIndex = 6, lastGoodIndex = 5)
        assertEquals(5, bounds.clamp(6))
    }

    @Test
    fun `both directions can be learned independently`() {
        val bounds = LearnedStepBounds(size = 20)
        bounds.recordRejection(rejectedIndex = 2, lastGoodIndex = 3) // lower bound at 3
        bounds.recordRejection(rejectedIndex = 15, lastGoodIndex = 14) // upper bound at 14
        assertEquals(3, bounds.clamp(0))
        assertEquals(14, bounds.clamp(19))
        assertEquals(10, bounds.clamp(10))
    }

    @Test
    fun `reset forgets all learned bounds`() {
        val bounds = LearnedStepBounds(size = 10)
        bounds.recordRejection(rejectedIndex = 6, lastGoodIndex = 5)
        bounds.reset()
        assertEquals(9, bounds.clamp(9))
        assertEquals(0, bounds.clamp(0))
    }
}
