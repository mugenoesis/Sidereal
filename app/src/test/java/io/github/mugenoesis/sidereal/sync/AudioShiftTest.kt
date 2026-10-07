package io.github.mugenoesis.sidereal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioShiftTest {

    // AAC at 44.1 kHz: one frame every ~23,220 us.
    private val frames = List(10) { it * 23_220L }

    @Test
    fun `a positive offset delays every sample and keeps them all`() {
        val out = AudioShift.apply(frames, offsetUs = 300_000)
        assertEquals(10, out.size)
        assertEquals(300_000L, out.first().newTimeUs)
        assertEquals(frames.last() + 300_000, out.last().newTimeUs)
        assertTrue(out.all { it.keep })
    }

    @Test
    fun `a zero offset changes nothing`() {
        val out = AudioShift.apply(frames, 0)
        assertEquals(frames, out.map { it.newTimeUs })
        assertTrue(out.all { it.keep })
    }

    @Test
    fun `a negative offset drops the samples that would land before the start of the file`() {
        val out = AudioShift.apply(frames, offsetUs = -70_000)
        // frames at 0, 23220, 46440, 69660 would be at -70000..-340 - all before zero.
        assertEquals(listOf(false, false, false, false), out.take(4).map { it.keep })
        assertTrue(out.drop(4).all { it.keep })
        assertTrue(out.filter { it.keep }.all { it.newTimeUs >= 0 })
    }

    @Test
    fun `a sample landing exactly on zero is kept`() {
        val out = AudioShift.apply(listOf(0L, 23_220L, 46_440L), offsetUs = -23_220)
        assertEquals(listOf(false, true, true), out.map { it.keep })
        assertEquals(0L, out[1].newTimeUs)
    }

    @Test
    fun `order is preserved`() {
        val out = AudioShift.apply(frames, -50_000).filter { it.keep }.map { it.newTimeUs }
        assertEquals(out.sorted(), out)
    }

    @Test
    fun `an offset longer than the whole recording keeps nothing`() {
        assertTrue(AudioShift.apply(frames, -10_000_000).none { it.keep })
    }

    @Test
    fun `the output audio length follows the offset`() {
        // 10 frames spanning ~209 ms, delayed 300 ms -> ends ~509 ms.
        assertEquals(frames.last() + 300_000, AudioShift.endTimeUs(frames, 300_000))
        assertEquals(0L, AudioShift.endTimeUs(emptyList(), 300_000))
    }
}
