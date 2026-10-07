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

    @Test
    fun `setVideoStandardByName forwards to the gateway and reports success`() {
        val gateway = FakeCameraGateway()
        val controller = MediaFormatController(gateway)
        var completedWith: Boolean? = null
        controller.setVideoStandardByName("NTSC") { completedWith = it }
        assertEquals(listOf("setVideoStandard(NTSC)"), gateway.calls)
        assertEquals(true, completedWith)
    }

    @Test
    fun `a rejected video standard names itself in the error`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Not supported" }
        val controller = MediaFormatController(gateway)
        val message = awaitEvents(controller.errorEvents) { controller.setVideoStandardByName("PAL") }.single()
        assertTrue(message, message.contains("PAL") && message.contains("Not supported"))
    }

    @Test
    fun `setColorByName forwards to the gateway and updates the state on success`() {
        val gateway = FakeCameraGateway()
        val controller = MediaFormatController(gateway)
        controller.setColorByName("D_CINELIKE")
        assertEquals(listOf("setColor(D_CINELIKE)"), gateway.calls)
        assertEquals("D_CINELIKE", controller.cameraColor.value)
    }

    @Test
    fun `a rejected colour profile leaves the state alone and says why`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = MediaFormatController(gateway)
        val message = awaitEvents(controller.errorEvents) { controller.setColorByName("D_LOG") }.single()
        assertEquals(null, controller.cameraColor.value)
        assertTrue(message, message.contains("D_LOG") && message.contains("Param Illegal"))
    }

    @Test
    fun `a successful video standard change updates the state`() {
        val controller = MediaFormatController(FakeCameraGateway())
        controller.setVideoStandardByName("NTSC")
        assertEquals("NTSC", controller.videoStandard.value)
    }
}
