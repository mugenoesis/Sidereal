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

    @Test
    fun `whole second shutter names convert to milliseconds`() {
        assertEquals(3_000L, ShutterLogic.exposureMs("SHUTTER_SPEED_3"))
        assertEquals(30_000L, ShutterLogic.exposureMs("SHUTTER_SPEED_30"))
    }

    @Test
    fun `fractions convert to milliseconds, rounding sub-millisecond up to 1`() {
        assertEquals(10L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_100"))
        assertEquals(500L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_2"))
        assertEquals(1L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_8000"))
    }

    @Test
    fun `decimal point names use DOT`() {
        assertEquals(3_200L, ShutterLogic.exposureMs("SHUTTER_SPEED_3_DOT_2"))
        assertEquals(1_300L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_DOT_3"))
        assertEquals(400L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_2_DOT_5"))
        assertEquals(599L, ShutterLogic.exposureMs("SHUTTER_SPEED_1_1_DOT_67"))
    }

    @Test
    fun `AUTO and UNKNOWN have no fixed duration`() {
        assertEquals(null, ShutterLogic.exposureMs("SHUTTER_SPEED_AUTO"))
        assertEquals(null, ShutterLogic.exposureMs("SHUTTER_SPEED_UNKNOWN"))
        assertEquals(null, ShutterLogic.exposureMs("AUTO"))
    }
}
