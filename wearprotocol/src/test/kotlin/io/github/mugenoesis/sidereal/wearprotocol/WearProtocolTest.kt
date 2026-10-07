package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {

    private fun roundTrip(command: WearCommand): WearCommand? {
        val (path, payload) = WearProtocol.encode(command)
        return WearProtocol.decode(path, payload)
    }

    @Test
    fun `simple commands survive a round trip`() {
        for (c in listOf(WearCommand.Capture, WearCommand.ToggleRecord, WearCommand.ToggleMode, WearCommand.ConnectOsmo, WearCommand.Hello, WearCommand.Recenter)) {
            assertEquals(c, roundTrip(c))
        }
    }

    @Test
    fun `gimbal commands carry their two rates`() {
        assertEquals(WearCommand.Gimbal(0.5f, -0.25f), roundTrip(WearCommand.Gimbal(0.5f, -0.25f)))
        assertEquals(WearCommand.Gimbal(0f, 0f), roundTrip(WearCommand.Gimbal(0f, 0f)))
    }

    @Test
    fun `gimbal rates are clamped into minus one to one on the way out`() {
        assertEquals(WearCommand.Gimbal(1f, -1f), roundTrip(WearCommand.Gimbal(7f, -3f)))
    }

    @Test
    fun `live view start and stop`() {
        assertEquals(WearCommand.LiveView(true), roundTrip(WearCommand.LiveView(true)))
        assertEquals(WearCommand.LiveView(false), roundTrip(WearCommand.LiveView(false)))
    }

    @Test
    fun `each command has its own path under the cmd prefix`() {
        val paths = listOf(
            WearCommand.Capture, WearCommand.ToggleRecord, WearCommand.ToggleMode, WearCommand.ConnectOsmo, WearCommand.Hello,
            WearCommand.Recenter, WearCommand.Gimbal(0f, 0f), WearCommand.LiveView(true)
        ).map { WearProtocol.encode(it).first }
        assertEquals(paths.size, paths.toSet().size)
        assert(paths.all { it.startsWith("/cmd/") })
    }

    @Test
    fun `unknown paths and malformed payloads decode to null instead of crashing`() {
        assertNull(WearProtocol.decode("/cmd/nonsense", ByteArray(0)))
        assertNull(WearProtocol.decode("/other", ByteArray(0)))
        assertNull(WearProtocol.decode(WearPaths.GIMBAL, ByteArray(3)))
        assertNull(WearProtocol.decode(WearPaths.LIVE_VIEW, ByteArray(0)))
    }

    @Test
    fun `status survives a round trip`() {
        val status = WearStatus(
            phoneOnOsmo = true, cameraMode = WearCameraMode.VIDEO, recording = true, recordElapsedSec = 75,
            batteryPercent = 82, photosLeft = 13_726, recordSecondsLeft = 12_182,
            sequenceRunning = true, sequenceLabel = "Panorama 3/9"
        )
        assertEquals(status, WearProtocol.decodeStatus(WearProtocol.encodeStatus(status)))
    }

    @Test
    fun `status with unknown values keeps them unknown`() {
        val status = WearStatus.UNKNOWN
        assertEquals(status, WearProtocol.decodeStatus(WearProtocol.encodeStatus(status)))
        assertEquals(-1, WearProtocol.decodeStatus(WearProtocol.encodeStatus(status))!!.batteryPercent)
    }

    @Test
    fun `non ascii sequence labels survive`() {
        val status = WearStatus.UNKNOWN.copy(sequenceLabel = "Panorama 2×3 · 6 frames")
        assertEquals(status, WearProtocol.decodeStatus(WearProtocol.encodeStatus(status)))
    }

    @Test
    fun `garbage status decodes to null`() {
        assertNull(WearProtocol.decodeStatus(ByteArray(0)))
        assertNull(WearProtocol.decodeStatus(byteArrayOf(99, 1, 2)))
    }

    @Test
    fun `acks carry which command they answer and whether it worked`() {
        val ack = WearAck(WearPaths.CAPTURE, ok = false, message = "Stop recording first")
        assertEquals(ack, WearProtocol.decodeAck(WearProtocol.encodeAck(ack)))
        val good = WearAck(WearPaths.CAPTURE, ok = true, message = "")
        assertEquals(good, WearProtocol.decodeAck(WearProtocol.encodeAck(good)))
    }

    @Test
    fun `garbage acks decode to null`() {
        assertNull(WearProtocol.decodeAck(ByteArray(0)))
    }
}
