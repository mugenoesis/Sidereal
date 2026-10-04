package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeControllerTest {

    @Test
    fun `triggerShutter in SHOOT_PHOTO mode calls startShootPhoto and does not touch recording intent`() {
        val gateway = FakeCameraGateway()
        val controller = CameraModeController(gateway)
        controller.triggerShutterForModeName("SHOOT_PHOTO")
        assertEquals(listOf("startShootPhoto()"), gateway.calls)
        assertFalse(controller.isRecordingIntent.value)
    }

    @Test
    fun `first press in RECORD_VIDEO mode starts recording and flips the intent on`() {
        val gateway = FakeCameraGateway()
        val controller = CameraModeController(gateway)
        controller.triggerShutterForModeName("RECORD_VIDEO")
        assertEquals(listOf("startRecordVideo()"), gateway.calls)
        assertTrue(controller.isRecordingIntent.value)
    }

    @Test
    fun `second press in RECORD_VIDEO mode requests a stop and flips the intent back off`() {
        val gateway = FakeCameraGateway()
        val controller = CameraModeController(gateway)
        controller.triggerShutterForModeName("RECORD_VIDEO") // start
        controller.triggerShutterForModeName("RECORD_VIDEO") // stop
        assertEquals(listOf("startRecordVideo()", "stopRecordVideo()"), gateway.calls)
        assertFalse(controller.isRecordingIntent.value)
    }

    @Test
    fun `a mode other than SHOOT_PHOTO or RECORD_VIDEO sends nothing to the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = CameraModeController(gateway)
        controller.triggerShutterForModeName("PLAYBACK")
        assertEquals(emptyList<String>(), gateway.calls)
    }

    @Test
    fun `setModeByName forwards to the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = CameraModeController(gateway)
        controller.setModeByName("RECORD_VIDEO")
        assertEquals(listOf("setCameraMode(RECORD_VIDEO)"), gateway.calls)
    }
}
