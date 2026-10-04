package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusControllerTest {

    @Test
    fun `cycleFocusMode starts at MANUAL and never lands on AFC`() {
        // AFC is confirmed unsupported on this hardware - see the class
        // doc comment. The cycle must only ever visit MANUAL and AUTO.
        val gateway = FakeCameraGateway()
        val controller = FocusController(gateway)

        controller.cycleFocusMode()
        controller.cycleFocusMode()
        controller.cycleFocusMode()
        controller.cycleFocusMode()

        assertEquals(
            listOf("setFocusMode(MANUAL)", "setFocusMode(AUTO)", "setFocusMode(MANUAL)", "setFocusMode(AUTO)"),
            gateway.calls
        )
    }

    @Test
    fun `cycleFocusMode keeps advancing even when every request is rejected`() {
        // Regression test for the real "stuck cycle" bug this session: if
        // "next" were derived from the real pushed focusState instead of
        // an independent index, a rejected request (state never changes)
        // would make every subsequent press recompute the same "next"
        // forever.
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = FocusController(gateway)

        controller.cycleFocusMode()
        controller.cycleFocusMode()
        controller.cycleFocusMode()

        assertEquals(
            listOf("setFocusMode(MANUAL)", "setFocusMode(AUTO)", "setFocusMode(MANUAL)"),
            gateway.calls
        )
    }

    @Test
    fun `setFocusModeByName emits an error naming the lens rejection`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = FocusController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setFocusModeByName("AFC") }
        val message = messages.single()
        assertTrue(message.contains("AFC"))
        assertTrue(message.contains("Param Illegal"))
    }

    @Test
    fun `setFocusTarget and setFocusRingValue reach the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = FocusController(gateway)
        controller.setFocusTarget(0.5f, 0.25f)
        controller.setFocusRingValue(500)
        assertEquals(listOf("setFocusTarget(0.5, 0.25)", "setFocusRingValue(500)"), gateway.calls)
    }

    @Test
    fun `setFocusAssistantEnabled reaches the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = FocusController(gateway)
        controller.setFocusAssistantEnabled(false, false)
        assertEquals(listOf("setFocusAssistantEnabled(false, false)"), gateway.calls)
    }
}
