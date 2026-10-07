package io.github.mugenoesis.sidereal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncSidecarTest {

    private val full = SyncSidecar(
        audioFileName = "audio_20261007_101500.m4a",
        audioStartEpochMs = 1_791_000_000_300,
        cameraStartEpochMs = 1_791_000_000_000,
        cameraStopEpochMs = 1_791_000_060_000
    )

    @Test
    fun `a sidecar survives a round trip through its text form`() {
        assertEquals(full, SyncSidecar.parse(full.serialize()))
    }

    @Test
    fun `camera times are optional - the camera may never have confirmed it started`() {
        val partial = full.copy(cameraStartEpochMs = null, cameraStopEpochMs = null)
        assertEquals(partial, SyncSidecar.parse(partial.serialize()))
    }

    @Test
    fun `suggested offset is how much later the phone audio started than the camera`() {
        // Audio began 300 ms after the camera, so audio second 0 belongs at video second 0.3: delay the audio by 300 ms.
        assertEquals(300L, full.suggestedOffsetMs)
    }

    @Test
    fun `suggested offset is zero when the camera start is unknown`() {
        assertEquals(0L, full.copy(cameraStartEpochMs = null).suggestedOffsetMs)
    }

    @Test
    fun `audio that started before the camera suggests a negative offset`() {
        assertEquals(-250L, full.copy(audioStartEpochMs = full.cameraStartEpochMs!! - 250).suggestedOffsetMs)
    }

    @Test
    fun `garbage and incomplete text do not parse`() {
        assertNull(SyncSidecar.parse(""))
        assertNull(SyncSidecar.parse("hello world"))
        assertNull(SyncSidecar.parse("audioStartEpochMs=5"))
        assertNull(SyncSidecar.parse("audioFileName=a.m4a\naudioStartEpochMs=notanumber"))
    }

    @Test
    fun `the sidecar sits next to its audio file under a derived name`() {
        assertEquals("audio_20261007_101500.sync.properties", SyncSidecar.fileNameFor("audio_20261007_101500.m4a"))
        assertEquals("take.sync.properties", SyncSidecar.fileNameFor("take"))
    }

    @Test
    fun `the manual offset the user settled on is stored and restored`() {
        val tuned = full.copy(manualOffsetMs = -40)
        assertEquals(-40L, SyncSidecar.parse(tuned.serialize())!!.manualOffsetMs)
        assertEquals(260L, tuned.totalOffsetMs)
    }
}
