package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterLogicTest {

    @Test
    fun `RECORD_VIDEO mode starts recording when no recording is in progress`() {
        assertEquals(ShutterLogic.Action.StartRecordVideo, ShutterLogic.decideAction("RECORD_VIDEO", isRecordingIntent = false))
    }

    @Test
    fun `RECORD_VIDEO mode stops recording when one is already in progress`() {
        assertEquals(ShutterLogic.Action.StopRecordVideo, ShutterLogic.decideAction("RECORD_VIDEO", isRecordingIntent = true))
    }

    @Test
    fun `SHOOT_PHOTO mode always shoots a photo regardless of recording intent`() {
        assertEquals(ShutterLogic.Action.StartShootPhoto, ShutterLogic.decideAction("SHOOT_PHOTO", isRecordingIntent = false))
        assertEquals(ShutterLogic.Action.StartShootPhoto, ShutterLogic.decideAction("SHOOT_PHOTO", isRecordingIntent = true))
    }

    @Test
    fun `every other mode is ignored, naming the mode it saw`() {
        assertEquals(ShutterLogic.Action.Ignored("PLAYBACK"), ShutterLogic.decideAction("PLAYBACK", isRecordingIntent = false))
        assertEquals(ShutterLogic.Action.Ignored("MEDIA_DOWNLOAD"), ShutterLogic.decideAction("MEDIA_DOWNLOAD", isRecordingIntent = true))
    }
}
