package io.github.mugenoesis.sidereal.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusAssistTrackerTest {

    @Test
    fun `no reading until the first star is seen`() {
        val t = FocusAssistTracker()
        assertNull(t.state.smoothedFwhm)
        assertNull(t.state.best)
    }

    @Test
    fun `first reading is taken as is`() {
        val t = FocusAssistTracker()
        t.onMeasurement(6f)
        assertEquals(6f, t.state.smoothedFwhm!!, 1e-4f)
    }

    @Test
    fun `readings are smoothed so a jittery value does not flicker`() {
        val t = FocusAssistTracker(smoothing = 0.5f)
        t.onMeasurement(6f)
        t.onMeasurement(8f)
        assertEquals(7f, t.state.smoothedFwhm!!, 1e-4f)
    }

    @Test
    fun `best remembers the sharpest smoothed value seen`() {
        val t = FocusAssistTracker(smoothing = 1f)
        listOf(8f, 5f, 3f, 4f, 6f).forEach { t.onMeasurement(it) }
        assertEquals(3f, t.state.best!!, 1e-4f)
    }

    @Test
    fun `trend says whether focus is getting better or worse`() {
        val t = FocusAssistTracker(smoothing = 1f, trendDeadband = 0.1f)
        t.onMeasurement(6f)
        t.onMeasurement(5f)
        assertEquals(FocusTrend.SHARPER, t.state.trend)
        t.onMeasurement(5.02f)
        assertEquals(FocusTrend.STEADY, t.state.trend)
        t.onMeasurement(7f)
        assertEquals(FocusTrend.SOFTER, t.state.trend)
    }

    @Test
    fun `is at best when the current value is within tolerance of the best`() {
        val t = FocusAssistTracker(smoothing = 1f)
        listOf(8f, 3f, 3.1f).forEach { t.onMeasurement(it) }
        assertTrue(t.state.atBest)
        t.onMeasurement(5f)
        assertEquals(false, t.state.atBest)
    }

    @Test
    fun `losing the star keeps the last value but marks it lost after a few frames`() {
        val t = FocusAssistTracker(lostAfterMisses = 3)
        t.onMeasurement(4f)
        t.onMeasurement(null)
        t.onMeasurement(null)
        assertEquals(false, t.state.lost)
        t.onMeasurement(null)
        assertEquals(true, t.state.lost)
        assertEquals(4f, t.state.smoothedFwhm!!, 1e-4f)
    }

    @Test
    fun `finding the star again clears lost`() {
        val t = FocusAssistTracker(lostAfterMisses = 1)
        t.onMeasurement(4f)
        t.onMeasurement(null)
        assertEquals(true, t.state.lost)
        t.onMeasurement(4.2f)
        assertEquals(false, t.state.lost)
    }

    @Test
    fun `reset forgets the best and the history`() {
        val t = FocusAssistTracker(smoothing = 1f)
        t.onMeasurement(3f)
        t.reset()
        assertNull(t.state.best)
        assertNull(t.state.smoothedFwhm)
    }

    @Test
    fun `a star that jumps wildly after being lost restarts smoothing instead of dragging the old value`() {
        val t = FocusAssistTracker(smoothing = 0.2f, lostAfterMisses = 2)
        t.onMeasurement(3f)
        t.onMeasurement(null)
        t.onMeasurement(null)
        t.onMeasurement(12f)
        assertEquals(12f, t.state.smoothedFwhm!!, 1e-4f)
    }
}
