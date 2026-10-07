package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeLockControllerTest {

    private val gateway = FakeCameraGateway()
    private val controller = AeLockController(gateway)

    @Test
    fun `starts unlocked`() {
        assertFalse(controller.locked.value)
    }

    @Test
    fun `toggle locks then unlocks`() {
        controller.toggle()
        assertTrue(controller.locked.value)
        controller.toggle()
        assertFalse(controller.locked.value)
        assertEquals(listOf("setAeLock(true)", "setAeLock(false)"), gateway.calls)
    }

    @Test
    fun `a rejected lock leaves the state unchanged and says why`() = runBlocking {
        gateway.errorToReturn = "Not supported in this mode"
        val events = awaitEvents(controller.errorEvents) { controller.toggle() }
        assertFalse(controller.locked.value)
        assertEquals(1, events.size)
        assertTrue(events[0], events[0].contains("Not supported in this mode"))
    }

    @Test
    fun `the camera's pushed lock state wins over the local guess`() {
        controller.onCameraReported(true)
        assertTrue(controller.locked.value)
        controller.onCameraReported(false)
        assertFalse(controller.locked.value)
    }

    @Test
    fun `reset clears the lock locally without a camera call`() {
        controller.toggle()
        gateway.calls.clear()
        controller.reset()
        assertFalse(controller.locked.value)
        assertTrue(gateway.calls.isEmpty())
    }
}
