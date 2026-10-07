package io.github.mugenoesis.sidereal.wear

import io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand
import io.github.mugenoesis.sidereal.wearprotocol.WearPaths
import io.github.mugenoesis.sidereal.wearprotocol.WearStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHost : WearHost {
    var status = WearStatus.UNKNOWN.copy(phoneOnOsmo = true, cameraMode = WearCameraMode.PHOTO)
    val calls = mutableListOf<String>()
    var failWith: String? = null
    var throwOnCapture = false

    override fun status() = status
    override fun capture(): String? { calls += "capture"; if (throwOnCapture) error("boom"); return failWith }
    override fun toggleRecord(): String? { calls += "toggleRecord"; return failWith }
    override fun toggleMode(): String? { calls += "toggleMode"; return failWith }
    override fun recenter() { calls += "recenter" }
    override fun gimbal(yaw: Float, pitch: Float) { calls += "gimbal(%.1f,%.1f)".format(yaw, pitch) }
    override fun setLiveView(on: Boolean) { calls += "liveview($on)" }
    override fun connectOsmo(): String? { calls += "connectOsmo"; return failWith }
}

class WearCommandHandlerTest {

    private val host = FakeHost()
    private val handler = WearCommandHandler(host)

    @Test
    fun `a capture reaches the camera and is acknowledged`() {
        val ack = handler.handle(WearCommand.Capture)!!
        assertEquals(listOf("capture"), host.calls)
        assertTrue(ack.ok)
        assertEquals(WearPaths.CAPTURE, ack.commandPath)
    }

    @Test
    fun `a refusal from the phone is passed back to the watch with its reason`() {
        host.failWith = "Stop recording first"
        val ack = handler.handle(WearCommand.Capture)!!
        assertFalse(ack.ok)
        assertEquals("Stop recording first", ack.message)
    }

    @Test
    fun `camera commands are refused politely when the phone is not on the Osmo`() {
        host.status = host.status.copy(phoneOnOsmo = false)
        val ack = handler.handle(WearCommand.Capture)!!
        assertFalse(ack.ok)
        assertTrue(ack.message, ack.message.contains("Osmo"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `while a sequence runs the watch cannot shoot, record, switch mode or move the gimbal`() {
        host.status = host.status.copy(sequenceRunning = true, sequenceLabel = "Panorama 2/6")
        for (c in listOf(WearCommand.Capture, WearCommand.ToggleRecord, WearCommand.ToggleMode, WearCommand.Recenter)) {
            val ack = handler.handle(c)!!
            assertFalse(ack.ok)
            assertTrue(ack.message, ack.message.contains("sequence", ignoreCase = true))
        }
        handler.handle(WearCommand.Gimbal(1f, 0f))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `but a stop is always allowed through, even during a sequence`() {
        host.status = host.status.copy(sequenceRunning = true)
        handler.handle(WearCommand.Gimbal(0f, 0f))
        assertEquals(listOf("gimbal(0.0,0.0)"), host.calls)
    }

    @Test
    fun `gimbal commands are not acknowledged - they stream and would flood the link`() {
        assertNull(handler.handle(WearCommand.Gimbal(0.5f, 0.5f)))
        assertEquals(listOf("gimbal(0.5,0.5)"), host.calls)
    }

    @Test
    fun `stopping a recording warns that this camera may ignore it`() {
        host.status = host.status.copy(cameraMode = WearCameraMode.VIDEO, recording = true)
        val ack = handler.handle(WearCommand.ToggleRecord)!!
        assertTrue(ack.ok)
        assertTrue(ack.message, ack.message.contains("button", ignoreCase = true))
    }

    @Test
    fun `starting a recording has no such warning`() {
        host.status = host.status.copy(cameraMode = WearCameraMode.VIDEO, recording = false)
        assertEquals("", handler.handle(WearCommand.ToggleRecord)!!.message)
    }

    @Test
    fun `recording needs video mode`() {
        host.status = host.status.copy(cameraMode = WearCameraMode.PHOTO, recording = false)
        val ack = handler.handle(WearCommand.ToggleRecord)!!
        assertFalse(ack.ok)
        assertTrue(ack.message, ack.message.contains("video", ignoreCase = true))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `the mode cannot be switched mid recording`() {
        host.status = host.status.copy(cameraMode = WearCameraMode.VIDEO, recording = true)
        val ack = handler.handle(WearCommand.ToggleMode)!!
        assertFalse(ack.ok)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `live view and connect work even when the phone is not on the Osmo`() {
        host.status = host.status.copy(phoneOnOsmo = false)
        assertNull(handler.handle(WearCommand.LiveView(true)))
        val ack = handler.handle(WearCommand.ConnectOsmo)!!
        assertTrue(ack.ok)
        assertEquals(listOf("liveview(true)", "connectOsmo"), host.calls)
    }

    @Test
    fun `hello is answered even with nothing connected`() {
        host.status = WearStatus.UNKNOWN
        assertTrue(handler.handle(WearCommand.Hello)!!.ok)
    }

    @Test
    fun `an exception inside the phone becomes a failed ack instead of killing the listener`() {
        host.throwOnCapture = true
        val ack = handler.handle(WearCommand.Capture)!!
        assertFalse(ack.ok)
        assertTrue(ack.message.isNotBlank())
    }

    @Test
    fun `recentre goes through when idle`() {
        assertTrue(handler.handle(WearCommand.Recenter)!!.ok)
        assertEquals(listOf("recenter"), host.calls)
    }
}
