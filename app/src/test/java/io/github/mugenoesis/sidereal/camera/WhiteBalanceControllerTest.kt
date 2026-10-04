package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceControllerTest {

    @Test
    fun `setPresetByName sends a non-CUSTOM preset with no color temperature`() {
        val gateway = FakeCameraGateway()
        val controller = WhiteBalanceController(gateway)
        controller.setPresetByName("SUNNY")
        assertEquals(listOf("setWhiteBalance(SUNNY, null)"), gateway.calls)
    }

    @Test
    fun `setPresetByName lands CUSTOM on a real starting Kelvin instead of the 0-default`() {
        // Regression context: WhiteBalance(CUSTOM)'s 1-arg constructor
        // defaults colorTemperature to 0, which the camera rejects
        // outright - setPresetByName must always supply a real value.
        val gateway = FakeCameraGateway()
        val controller = WhiteBalanceController(gateway)
        controller.setPresetByName("CUSTOM")
        assertEquals(listOf("setWhiteBalance(CUSTOM, 5600)"), gateway.calls)
    }

    @Test
    fun `setCustomColorTemperature sends the exact requested Kelvin value`() {
        val gateway = FakeCameraGateway()
        val controller = WhiteBalanceController(gateway)
        controller.setCustomColorTemperature(3200)
        assertEquals(listOf("setWhiteBalance(CUSTOM, 3200)"), gateway.calls)
    }

    @Test
    fun `cyclePreset visits every preset in order and wraps back to the start`() {
        val gateway = FakeCameraGateway()
        val controller = WhiteBalanceController(gateway)

        repeat(6) { controller.cyclePreset() }

        assertEquals(
            listOf("AUTO", "SUNNY", "CLOUDY", "INDOOR_INCANDESCENT", "INDOOR_FLUORESCENT", "CUSTOM"),
            gateway.calls.map { it.substringAfter("(").substringBefore(",") }
        )

        gateway.calls.clear()
        controller.cyclePreset()
        assertEquals(listOf("setWhiteBalance(AUTO, null)"), gateway.calls)
    }

    @Test
    fun `cyclePreset keeps advancing even when every request is rejected`() {
        // Same "independent index" pattern as FocusController.cycleIndex -
        // a rejected preset must not stall the cycle on the same "next"
        // forever.
        val gateway = FakeCameraGateway().apply { errorToReturn = "Camera received invalid parameters" }
        val controller = WhiteBalanceController(gateway)

        controller.cyclePreset()
        controller.cyclePreset()
        controller.cyclePreset()

        assertEquals(
            listOf("AUTO", "SUNNY", "CLOUDY"),
            gateway.calls.map { it.substringAfter("(").substringBefore(",") }
        )
    }

    @Test
    fun `setPresetByName emits an error naming the rejected preset`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Camera received invalid parameters" }
        val controller = WhiteBalanceController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setPresetByName("CUSTOM") }
        val message = messages.single()
        assertTrue(message.contains("CUSTOM"))
        assertTrue(message.contains("Camera received invalid parameters"))
    }
}
