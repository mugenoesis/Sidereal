package io.github.mugenoesis.sidereal.wear

import io.github.mugenoesis.sidereal.camera.CameraStatus
import io.github.mugenoesis.sidereal.sequence.SequenceProgress
import io.github.mugenoesis.sidereal.sequence.SequenceState
import io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WearStatusBuilderTest {

    private val camera = CameraStatus(
        batteryPercent = 82, cardInserted = true, photosLeft = 13_726, recordSecondsLeft = 12_182,
        isRecording = false, recordElapsedSec = 0, isVideoMode = false
    )

    @Test
    fun `camera numbers carry across`() {
        val s = WearStatusBuilder.build(camera, connected = true, sequence = null)
        assertEquals(82, s.batteryPercent)
        assertEquals(13_726, s.photosLeft)
        assertEquals(12_182, s.recordSecondsLeft)
        assertEquals(WearCameraMode.PHOTO, s.cameraMode)
        assertEquals(true, s.phoneOnOsmo)
    }

    @Test
    fun `unknown numbers become minus one`() {
        val s = WearStatusBuilder.build(CameraStatus(), connected = false, sequence = null)
        assertEquals(-1, s.batteryPercent)
        assertEquals(-1, s.photosLeft)
        assertEquals(-1, s.recordSecondsLeft)
        assertEquals(false, s.phoneOnOsmo)
    }

    @Test
    fun `video mode and the recording clock`() {
        val s = WearStatusBuilder.build(camera.copy(isVideoMode = true, isRecording = true, recordElapsedSec = 42), true, null)
        assertEquals(WearCameraMode.VIDEO, s.cameraMode)
        assertEquals(true, s.recording)
        assertEquals(42, s.recordElapsedSec)
    }

    @Test
    fun `a huge photo count is clamped to what an int can carry`() {
        val s = WearStatusBuilder.build(camera.copy(photosLeft = 5_000_000_000L), true, null)
        assertEquals(Int.MAX_VALUE, s.photosLeft)
    }

    @Test
    fun `a running sequence shows its name and progress`() {
        val progress = SequenceProgress(state = SequenceState.Running, capturesDone = 3, capturesTotal = 9)
        val s = WearStatusBuilder.build(camera, true, "Panorama" to progress)
        assertEquals(true, s.sequenceRunning)
        assertEquals("Panorama 3/9", s.sequenceLabel)
    }

    @Test
    fun `a sequence waiting on the user still counts as running`() {
        val progress = SequenceProgress(state = SequenceState.AwaitingUser("cap the lens"), capturesDone = 0, capturesTotal = 15)
        assertEquals(true, WearStatusBuilder.build(camera, true, "Darks" to progress).sequenceRunning)
    }

    @Test
    fun `a finished sequence is not running`() {
        val progress = SequenceProgress(state = SequenceState.Done, capturesDone = 9, capturesTotal = 9)
        val s = WearStatusBuilder.build(camera, true, "Panorama" to progress)
        assertEquals(false, s.sequenceRunning)
        assertEquals("", s.sequenceLabel)
    }
}
