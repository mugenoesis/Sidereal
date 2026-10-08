package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceSettingsTest {

    private val base = SequenceSettings()

    @Test
    fun `adjusting moves one rung up or down a value ladder`() {
        val up = base.adjust("intervalSec", +1)
        val down = base.adjust("intervalSec", -1)
        assertTrue(up.intervalSec > base.intervalSec)
        assertTrue(down.intervalSec < base.intervalSec)
    }

    @Test
    fun `adjusting clamps at both ends of the ladder`() {
        var s = base
        repeat(50) { s = s.adjust("frames", +1) }
        val top = s.frames
        assertEquals(top, s.adjust("frames", +1).frames)
        repeat(50) { s = s.adjust("frames", -1) }
        assertEquals(1, s.frames)
        assertEquals(1, s.adjust("frames", -1).frames)
    }

    @Test
    fun `a value that is not on the ladder snaps to the nearest rung before stepping`() {
        val odd = base.copy(intervalSec = 11)
        val up = odd.adjust("intervalSec", +1).intervalSec
        val down = odd.adjust("intervalSec", -1).intervalSec
        assertEquals(15, up)
        assertEquals(10, down)
    }

    @Test
    fun `unknown field ids are ignored`() {
        assertEquals(base, base.adjust("nope", +1))
    }

    @Test
    fun `each mode exposes only the fields that mean something for it`() {
        fun ids(mode: SequenceMode) = base.copy(mode = mode).fields().map { it.id }
        assertEquals(listOf("frames", "intervalSec", "settleMs", "dither", "saveFrames"), ids(SequenceMode.INTERVALOMETER))
        assertEquals(listOf("durationMin", "intervalSec", "fps", "settleMs", "motion", "ramp", "saveTimelapseFrames", "makeVideo"), ids(SequenceMode.TIMELAPSE))
        assertEquals(listOf("yawSpanDeg", "pitchSpanDeg", "overlapPct", "shotsPerNode", "settleMs", "saveFrames", "stitch"), ids(SequenceMode.PANORAMA))
        assertEquals(listOf("calFrames", "saveFrames"), ids(SequenceMode.DARKS))
        assertEquals(listOf("calFrames", "saveFrames"), ids(SequenceMode.BIAS))
        assertEquals(listOf("calFrames", "saveFrames"), ids(SequenceMode.FLATS))
    }

    @Test
    fun `toggle fields flip instead of stepping`() {
        assertFalse(base.dither)
        assertTrue(base.adjust("dither", +1).dither)
        assertFalse(base.adjust("dither", +1).adjust("dither", +1).dither)
        assertTrue(base.adjust("motion", -1).motion)
    }

    @Test
    fun `fields show human readable values`() {
        val s = base.copy(frames = 20, intervalSec = 90, settleMs = 1500, dither = true)
        val byId = s.fields().associate { it.id to it.display }
        assertEquals("20", byId["frames"])
        assertEquals("1m 30s", byId["intervalSec"])
        assertEquals("1.5s", byId["settleMs"])
        assertEquals("On", byId["dither"])
    }

    @Test
    fun `timelapse shows duration in minutes and hours`() {
        val s = base.copy(mode = SequenceMode.TIMELAPSE, durationMin = 90)
        assertEquals("1h 30m", s.fields().first { it.id == "durationMin" }.display)
    }

    @Test
    fun `every mode has a label`() {
        SequenceMode.values().forEach { assertTrue(it.label.isNotBlank()) }
    }

    @Test
    fun `mode steps forward and wraps around the end`() {
        assertEquals(SequenceMode.TIMELAPSE, SequenceMode.INTERVALOMETER.step(+1))
        assertEquals(SequenceMode.INTERVALOMETER, SequenceMode.FLATS.step(+1))
    }

    @Test
    fun `mode steps backward and wraps around the start`() {
        assertEquals(SequenceMode.INTERVALOMETER, SequenceMode.TIMELAPSE.step(-1))
        assertEquals(SequenceMode.FLATS, SequenceMode.INTERVALOMETER.step(-1))
    }

    @Test
    fun `the ramp options only appear once the ramp is switched on`() {
        val on = base.copy(mode = SequenceMode.TIMELAPSE, ramp = true)
        assertEquals(listOf("durationMin", "intervalSec", "fps", "settleMs", "motion", "ramp", "keepDarkPct", "maxIso", "saveTimelapseFrames", "makeVideo"), on.fields().map { it.id })
        assertTrue(on.fields().first { it.id == "ramp" }.toggle)
    }

    @Test
    fun `ramp fields step along their ladders and show readable values`() {
        val on = base.copy(mode = SequenceMode.TIMELAPSE, ramp = true)
        assertEquals("50%", on.fields().first { it.id == "keepDarkPct" }.display)
        assertEquals(75, on.adjust("keepDarkPct", +1).keepDarkPct)
        assertEquals(25, on.adjust("keepDarkPct", -1).keepDarkPct)
        assertEquals(0, on.copy(keepDarkPct = 0).adjust("keepDarkPct", -1).keepDarkPct)
        assertEquals("ISO 3200", on.fields().first { it.id == "maxIso" }.display)
        assertEquals(6400, on.adjust("maxIso", +1).maxIso)
        assertTrue(on.adjust("ramp", +1).ramp.not())
    }

    @Test
    fun `saving frames and stitching are on by default, the timelapse video and its frames are off`() {
        assertTrue(base.saveFrames)
        assertTrue(base.stitch)
        assertFalse(base.saveTimelapseFrames)
        assertFalse(base.makeVideo)
    }

    @Test
    fun `the download options are toggles that flip`() {
        for (id in listOf("saveFrames", "stitch", "saveTimelapseFrames", "makeVideo")) {
            val before = base.adjust(id, +1)
            assertTrue("$id should be a toggle field", base.copy(mode = SequenceMode.TIMELAPSE).fields().plus(base.copy(mode = SequenceMode.PANORAMA).fields()).first { it.id == id }.toggle)
            assertEquals(base, before.adjust(id, +1))
        }
    }

    @Test
    fun `a timelapse needs its frames downloaded only if asked to keep them or make a video`() {
        val t = base.copy(mode = SequenceMode.TIMELAPSE)
        assertFalse(t.downloadsFrames())
        assertTrue(t.copy(saveTimelapseFrames = true).downloadsFrames())
        assertTrue(t.copy(makeVideo = true).downloadsFrames())
    }

    @Test
    fun `a panorama downloads when saving or stitching, and not when both are off`() {
        val p = base.copy(mode = SequenceMode.PANORAMA)
        assertTrue(p.downloadsFrames())
        assertTrue(p.copy(saveFrames = false).downloadsFrames())
        assertFalse(p.copy(saveFrames = false, stitch = false).downloadsFrames())
    }

    @Test
    fun `calibration frames and the intervalometer download only when saving`() {
        for (mode in listOf(SequenceMode.INTERVALOMETER, SequenceMode.DARKS, SequenceMode.BIAS, SequenceMode.FLATS)) {
            assertTrue(base.copy(mode = mode).downloadsFrames())
            assertFalse(base.copy(mode = mode, saveFrames = false).downloadsFrames())
        }
    }

    @Test
    fun `frames are kept on the phone per the mode's own save option`() {
        assertTrue(base.copy(mode = SequenceMode.PANORAMA).keepsFrames())
        assertFalse(base.copy(mode = SequenceMode.PANORAMA, saveFrames = false).keepsFrames())
        assertFalse(base.copy(mode = SequenceMode.TIMELAPSE).keepsFrames())
        assertTrue(base.copy(mode = SequenceMode.TIMELAPSE, saveTimelapseFrames = true).keepsFrames())
    }
}
