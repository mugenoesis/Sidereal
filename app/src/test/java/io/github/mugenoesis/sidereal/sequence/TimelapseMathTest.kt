package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapseMathTest {

    @Test
    fun `frames for a duration are duration divided by interval`() {
        assertEquals(720, TimelapseMath.framesForDuration(durationMs = 3_600_000, intervalMs = 5_000))
    }

    @Test
    fun `a duration shorter than one interval still gives one frame`() {
        assertEquals(1, TimelapseMath.framesForDuration(durationMs = 1_000, intervalMs = 5_000))
    }

    @Test
    fun `clip length is frames over fps`() {
        assertEquals(30.0, TimelapseMath.clipSeconds(frames = 720, fps = 24), 1e-9)
        assertEquals(24.0, TimelapseMath.clipSeconds(frames = 720, fps = 30), 1e-9)
    }

    @Test
    fun `total duration is the gaps between frame starts plus the last frame's own work`() {
        // 4 frames, 10s apart: last starts at 30s, then settle 1s + exposure 2s.
        assertEquals(33_000L, TimelapseMath.totalDurationMs(frames = 4, intervalMs = 10_000, settleMs = 1_000, exposureMs = 2_000))
    }

    @Test
    fun `remaining time shrinks as frames complete`() {
        val full = TimelapseMath.remainingMs(frames = 4, done = 0, intervalMs = 10_000, settleMs = 1_000, exposureMs = 2_000)
        val half = TimelapseMath.remainingMs(frames = 4, done = 2, intervalMs = 10_000, settleMs = 1_000, exposureMs = 2_000)
        val none = TimelapseMath.remainingMs(frames = 4, done = 4, intervalMs = 10_000, settleMs = 1_000, exposureMs = 2_000)
        assertEquals(33_000L, full)
        assertEquals(13_000L, half)
        assertEquals(0L, none)
    }

    @Test
    fun `formats a duration as h m s`() {
        assertEquals("1h 02m 03s", TimelapseMath.format(3_723_000))
        assertEquals("2m 05s", TimelapseMath.format(125_000))
        assertEquals("9s", TimelapseMath.format(9_000))
    }
}
