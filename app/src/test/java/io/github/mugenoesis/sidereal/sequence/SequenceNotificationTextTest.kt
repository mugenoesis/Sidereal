package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceNotificationTextTest {

    private fun progress(
        state: SequenceState = SequenceState.Running,
        done: Int = 0,
        total: Int = 0,
        waiting: Boolean = false,
        exposure: String = ""
    ) = SequenceProgress(state = state, capturesDone = done, capturesTotal = total, waitingForCamera = waiting, exposureSummary = exposure)

    @Test
    fun `a running sequence names its mode and shows frames done`() {
        val c = SequenceNotificationText.of("Timelapse", progress(done = 7, total = 12))
        assertEquals("Timelapse running", c.title)
        assertTrue(c.text, c.text.contains("7/12"))
        assertEquals(58, c.percent)
        assertFalse(c.indeterminate)
    }

    @Test
    fun `the live exposure of a ramp is shown`() {
        val c = SequenceNotificationText.of("Timelapse", progress(done = 3, total = 10, exposure = "1/4 · ISO 100 (+0.0 stops)"))
        assertTrue(c.text, c.text.contains("1/4 · ISO 100"))
    }

    @Test
    fun `a dropped camera link is called out`() {
        val c = SequenceNotificationText.of("Timelapse", progress(done = 3, total = 10, waiting = true))
        assertTrue(c.text, c.text.contains("Waiting for the camera"))
    }

    @Test
    fun `a prompt tells the user the sequence needs them`() {
        val c = SequenceNotificationText.of("Darks", progress(state = SequenceState.AwaitingUser("Cap the lens"), done = 0, total = 15))
        assertEquals("Darks needs you", c.title)
        assertTrue(c.text, c.text.contains("Cap the lens"))
    }

    @Test
    fun `before the first frame, or with no known total, progress is indeterminate rather than 0 or a divide by zero`() {
        val c = SequenceNotificationText.of("Intervalometer", progress(done = 0, total = 0))
        assertTrue(c.indeterminate)
        assertEquals(0, c.percent)
    }

    @Test
    fun `endings are plain about how it went`() {
        assertEquals("Timelapse finished", SequenceNotificationText.of("Timelapse", progress(SequenceState.Done, 12, 12)).title)
        val failed = SequenceNotificationText.of("Timelapse", progress(SequenceState.Failed("Lost the camera for 10 minutes - sequence stopped"), 5, 12))
        assertEquals("Timelapse stopped", failed.title)
        assertTrue(failed.text, failed.text.contains("Lost the camera"))
        assertEquals("Timelapse cancelled", SequenceNotificationText.of("Timelapse", progress(SequenceState.Cancelled, 5, 12)).title)
    }

    @Test
    fun `progress never exceeds 100`() {
        assertEquals(100, SequenceNotificationText.of("Timelapse", progress(done = 15, total = 12)).percent)
    }
}
