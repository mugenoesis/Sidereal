package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveControllerTest {

    private val gateway = FakeCameraGateway()
    private val controller = DriveController(gateway)

    @Test
    fun `starts on single shot`() {
        assertEquals("Single", controller.current.value.label)
        assertEquals("SINGLE", controller.current.value.modeName)
    }

    @Test
    fun `cycling goes single then the burst sizes then AEB, and wraps`() {
        val labels = ArrayList<String>()
        repeat(DrivePresets.all.size + 1) {
            controller.cycle()
            labels += controller.current.value.label
        }
        assertEquals("Burst 3", labels[0])
        assertTrue(labels.any { it.startsWith("Burst") })
        assertTrue(labels.any { it.startsWith("AEB") })
        assertEquals("Single", labels[DrivePresets.all.size - 1])
        assertEquals("Burst 3", labels.last())
    }

    @Test
    fun `selecting a burst preset sets the mode first and then its count`() {
        val burst5 = DrivePresets.all.first { it.label == "Burst 5" }
        controller.select(burst5)
        assertEquals(listOf("setShootPhotoMode(BURST)", "setPhotoBurstCount(BURST_COUNT_5)"), gateway.calls)
    }

    @Test
    fun `selecting AEB sets its bracket count`() {
        controller.select(DrivePresets.all.first { it.label == "AEB 5" })
        assertEquals(listOf("setShootPhotoMode(AEB)", "setPhotoAebCount(AEB_COUNT_5)"), gateway.calls)
    }

    @Test
    fun `single needs no count`() {
        controller.select(DrivePresets.all.first { it.label == "Single" })
        assertEquals(listOf("setShootPhotoMode(SINGLE)"), gateway.calls)
    }

    @Test
    fun `only drive modes the Zenmuse X5 really accepted on hardware are offered`() {
        // Probed on a real Osmo Pro / X5: HDR (although listed in SHOOT_PHOTO_MODE_RANGE) and a burst of 10 are
        // rejected with "Camera received invalid parameters" / "set param failed".
        val labels = DrivePresets.all.map { it.label }
        assertEquals(listOf("Single", "Burst 3", "Burst 5", "Burst 7", "AEB 3", "AEB 5"), labels)
    }

    @Test
    fun `a rejected mode reports why and skips the count`() = runBlocking {
        gateway.errorToReturn = "Not supported"
        val events = awaitEvents(controller.errorEvents) { controller.select(DrivePresets.all.first { it.label == "Burst 3" }) }
        assertEquals(1, events.size)
        assertTrue(events[0], events[0].contains("Burst 3") && events[0].contains("Not supported"))
        assertEquals(listOf("setShootPhotoMode(BURST)"), gateway.calls)
    }

    @Test
    fun `cycling keeps moving even when a mode is rejected, so an unsupported one cannot trap the button`() {
        gateway.errorToReturn = "Not supported"
        controller.cycle()
        val first = controller.current.value.label
        controller.cycle()
        assertTrue(controller.current.value.label != first)
    }

    @Test
    fun `preset labels are unique`() {
        val labels = DrivePresets.all.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
