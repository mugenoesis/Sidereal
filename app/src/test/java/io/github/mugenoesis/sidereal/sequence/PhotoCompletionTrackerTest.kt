package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample sequences below are taken from a real on-device probe of the X5 (1/100s and 3s exposures). */
class PhotoCompletionTrackerTest {

    private fun tracker(exposureMs: Long = 10, startTimeoutMs: Long = 3_000, graceMs: Long = 30_000, quietMs: Long = 300) =
        PhotoCompletionTracker(exposureMs, startTimeoutMs, graceMs, quietMs)

    @Test
    fun `waiting until the camera reports the shot has started`() {
        val t = tracker()
        assertEquals(PhotoStatus.Waiting, t.onSample(0, shooting = false, storing = false))
        assertEquals(PhotoStatus.Waiting, t.onSample(500, shooting = false, storing = false))
    }

    @Test
    fun `in progress while shooting or storing`() {
        val t = tracker()
        assertEquals(PhotoStatus.InProgress, t.onSample(250, shooting = true, storing = true))
        assertEquals(PhotoStatus.InProgress, t.onSample(1_700, shooting = false, storing = true))
    }

    @Test
    fun `done once both flags have stayed false for the quiet period`() {
        val t = tracker()
        t.onSample(250, true, true)
        t.onSample(1_700, false, true)
        assertEquals(PhotoStatus.InProgress, t.onSample(2_700, false, false))
        assertEquals(PhotoStatus.InProgress, t.onSample(2_900, false, false))
        assertEquals(PhotoStatus.Done, t.onSample(3_050, false, false))
    }

    @Test
    fun `a brief both-false blip between shooting and storing does not end the shot early`() {
        val t = tracker()
        t.onSample(100, true, false)
        assertEquals(PhotoStatus.InProgress, t.onSample(200, false, false))
        assertEquals(PhotoStatus.InProgress, t.onSample(250, false, true))
        assertEquals(PhotoStatus.InProgress, t.onSample(900, false, false))
        assertEquals(PhotoStatus.Done, t.onSample(1_300, false, false))
    }

    @Test
    fun `times out if the camera never starts`() {
        val t = tracker(startTimeoutMs = 3_000)
        assertEquals(PhotoStatus.Waiting, t.onSample(2_900, false, false))
        val status = t.onSample(3_100, false, false)
        assertTrue("status=$status", status is PhotoStatus.TimedOut)
        assertTrue((status as PhotoStatus.TimedOut).reason.contains("start", ignoreCase = true))
    }

    @Test
    fun `times out if the camera never finishes, allowing for the exposure plus grace`() {
        val t = tracker(exposureMs = 30_000, graceMs = 20_000)
        t.onSample(100, true, true)
        assertEquals(PhotoStatus.InProgress, t.onSample(49_000, true, true))
        val status = t.onSample(50_200, false, true)
        assertTrue("status=$status", status is PhotoStatus.TimedOut)
        assertTrue((status as PhotoStatus.TimedOut).reason.contains("finish", ignoreCase = true))
    }

    @Test
    fun `a long exposure is not mistaken for a stuck camera`() {
        val t = tracker(exposureMs = 30_000)
        t.onSample(100, true, true)
        assertEquals(PhotoStatus.InProgress, t.onSample(31_000, true, true))
        assertEquals(PhotoStatus.InProgress, t.onSample(31_500, false, true))
    }

    @Test
    fun `the already-finished state sticks`() {
        val t = tracker()
        t.onSample(100, true, true)
        t.onSample(200, false, false)
        assertEquals(PhotoStatus.Done, t.onSample(600, false, false))
        assertEquals(PhotoStatus.Done, t.onSample(700, true, true))
    }
}
