package io.github.mugenoesis.sidereal.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOffsetTest {

    @Test
    fun `fine medium and coarse nudges move by 10 ms 100 ms and 1 s`() {
        assertEquals(10L, SyncOffset.nudge(0, NudgeStep.FINE, +1))
        assertEquals(-100L, SyncOffset.nudge(0, NudgeStep.MEDIUM, -1))
        assertEquals(1_250L, SyncOffset.nudge(250, NudgeStep.COARSE, +1))
    }

    @Test
    fun `offset is clamped to thirty seconds either way`() {
        assertEquals(30_000L, SyncOffset.nudge(29_990, NudgeStep.COARSE, +1))
        assertEquals(-30_000L, SyncOffset.nudge(-29_500, NudgeStep.COARSE, -1))
    }

    @Test
    fun `offsets read as signed seconds`() {
        assertEquals("+0.30 s", SyncOffset.format(300))
        assertEquals("-1.25 s", SyncOffset.format(-1_250))
        assertEquals("0.00 s", SyncOffset.format(0))
    }

    @Test
    fun `a positive offset says the audio is delayed and a negative one that it is brought forward`() {
        assertEquals("audio starts 0.30 s after the video", SyncOffset.describe(300))
        assertEquals("audio starts 1.25 s before the video", SyncOffset.describe(-1_250))
        assertEquals("audio and video start together", SyncOffset.describe(0))
    }

    @Test
    fun `milliseconds convert to microseconds for the muxer`() {
        assertEquals(300_000L, SyncOffset.toUs(300))
        assertEquals(-1_250_000L, SyncOffset.toUs(-1_250))
    }
}
