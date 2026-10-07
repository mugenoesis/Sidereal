package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadStallDetectorTest {

    private val detector = { LoadStallDetector(timeoutMs = 30_000) }

    @Test
    fun `not stalled while idle or loaded`() {
        val d = detector()
        assertFalse(d.isStalled(MediaLoadState.IDLE, 0))
        assertFalse(d.isStalled(MediaLoadState.LOADED, 100_000))
    }

    @Test
    fun `not stalled within the timeout while loading`() {
        val d = detector()
        assertFalse(d.isStalled(MediaLoadState.ENTERING_MODE, 0))
        assertFalse(d.isStalled(MediaLoadState.LOADING, 29_000))
    }

    @Test
    fun `stalled once loading has gone on past the timeout`() {
        val d = detector()
        d.isStalled(MediaLoadState.ENTERING_MODE, 0)
        assertTrue(d.isStalled(MediaLoadState.LOADING, 30_001))
    }

    @Test
    fun `the clock runs across the switch from entering mode to loading, not restarting`() {
        val d = detector()
        d.isStalled(MediaLoadState.ENTERING_MODE, 0)
        d.isStalled(MediaLoadState.LOADING, 20_000)
        assertTrue(d.isStalled(MediaLoadState.LOADING, 31_000))
    }

    @Test
    fun `finishing loading resets the clock for next time`() {
        val d = detector()
        d.isStalled(MediaLoadState.LOADING, 0)
        d.isStalled(MediaLoadState.LOADED, 10_000)
        assertFalse(d.isStalled(MediaLoadState.LOADING, 50_000))
        assertFalse(d.isStalled(MediaLoadState.LOADING, 60_000))
        assertTrue(d.isStalled(MediaLoadState.LOADING, 81_000))
    }

    @Test
    fun `an error state is not reported as a stall - it already is a failure`() {
        val d = detector()
        d.isStalled(MediaLoadState.LOADING, 0)
        assertFalse(d.isStalled(MediaLoadState.ERROR, 99_000))
    }
}
