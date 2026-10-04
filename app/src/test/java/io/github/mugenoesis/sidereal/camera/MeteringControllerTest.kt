package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringControllerTest {

    @Test
    fun `setMeteringModeByName reaches the gateway and reports success`() {
        val controller = MeteringController(FakeCameraGateway())
        var completedWith: Boolean? = null
        controller.setMeteringModeByName("SPOT") { completedWith = it }
        assertEquals(true, completedWith)
    }

    @Test
    fun `setMeteringModeByName reports failure and emits an error on rejection`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Not supported" }
        val controller = MeteringController(gateway)
        var completedWith: Boolean? = null
        val messages = awaitEvents(controller.errorEvents) {
            controller.setMeteringModeByName("SPOT") { completedWith = it }
        }
        assertEquals(false, completedWith)
        val message = messages.single()
        assertTrue(message.contains("SPOT"))
        assertTrue(message.contains("Not supported"))
    }

    // No test here for "setSpotMeteringTarget skips sending when grid size
    // is unknown": that path calls refreshGridSize(), which calls
    // DJISDKManager.getInstance() - confirmed by direct experiment to hang
    // indefinitely in a plain JVM unit test (not throw, just never
    // return), unlike every other DJI SDK access this session has run
    // into. The grid-cell math itself is covered by MeteringGridTest.
}
