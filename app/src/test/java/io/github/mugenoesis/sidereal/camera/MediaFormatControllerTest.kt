package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatControllerTest {

    @Test
    fun `setPhotoFileFormatByName forwards to the gateway and reports success`() {
        val gateway = FakeCameraGateway()
        val controller = MediaFormatController(gateway)
        var completedWith: Boolean? = null
        controller.setPhotoFileFormatByName("RAW_AND_JPEG") { completedWith = it }
        assertEquals(listOf("setPhotoFileFormat(RAW_AND_JPEG)"), gateway.calls)
        assertEquals(true, completedWith)
    }

    @Test
    fun `setPhotoAspectRatioByName emits an error naming the rejected ratio`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = MediaFormatController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setPhotoAspectRatioByName("RATIO_4_3") }
        val message = messages.single()
        assertTrue(message.contains("RATIO_4_3"))
        assertTrue(message.contains("Param Illegal"))
    }

    @Test
    fun `setVideoFileFormatByName forwards to the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = MediaFormatController(gateway)
        controller.setVideoFileFormatByName("MOV")
        assertEquals(listOf("setVideoFileFormat(MOV)"), gateway.calls)
    }

    @Test
    fun `setVideoResolutionAndFrameRateByName forwards both names together`() {
        val gateway = FakeCameraGateway()
        val controller = MediaFormatController(gateway)
        var completedWith: Boolean? = null
        controller.setVideoResolutionAndFrameRateByName("RESOLUTION_4096x2160", "FRAME_RATE_24_FPS") { completedWith = it }
        assertEquals(listOf("setVideoResolutionAndFrameRate(RESOLUTION_4096x2160, FRAME_RATE_24_FPS)"), gateway.calls)
        assertEquals(true, completedWith)
    }

    @Test
    fun `setVideoResolutionAndFrameRateByName reports failure on rejection`() {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = MediaFormatController(gateway)
        var completedWith: Boolean? = null
        controller.setVideoResolutionAndFrameRateByName("RESOLUTION_4096x2160", "FRAME_RATE_24_FPS") { completedWith = it }
        assertEquals(false, completedWith)
    }
}
