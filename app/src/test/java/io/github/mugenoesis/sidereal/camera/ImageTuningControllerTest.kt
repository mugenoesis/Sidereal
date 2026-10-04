package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTuningControllerTest {

    @Test
    fun `setSharpness updates state on success`() {
        val controller = ImageTuningController(FakeCameraGateway())
        controller.setSharpness(2)
        assertEquals(2, controller.sharpness.value)
    }

    @Test
    fun `setSharpness leaves state unchanged and emits an error on rejection`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Not supported" }
        val controller = ImageTuningController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setSharpness(2) }
        assertEquals(null, controller.sharpness.value)
        val message = messages.single()
        assertTrue(message.contains("Sharpness"))
        assertTrue(message.contains("Not supported"))
    }

    @Test
    fun `setContrast and setSaturation update their own state independently`() {
        val controller = ImageTuningController(FakeCameraGateway())
        controller.setContrast(-1)
        controller.setSaturation(3)
        assertEquals(-1, controller.contrast.value)
        assertEquals(3, controller.saturation.value)
        assertEquals(null, controller.sharpness.value)
    }

    @Test
    fun `setAntiFlickerFrequencyByName forwards to the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = ImageTuningController(gateway)
        var completedWith: Boolean? = null
        controller.setAntiFlickerFrequencyByName("MANUAL_60HZ") { completedWith = it }
        assertEquals(listOf("setAntiFlickerFrequency(MANUAL_60HZ)"), gateway.calls)
        assertEquals(true, completedWith)
    }
}
