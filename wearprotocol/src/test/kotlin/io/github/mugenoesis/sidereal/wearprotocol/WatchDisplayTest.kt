package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDisplayTest {

    private val ready = WearStatus.UNKNOWN.copy(
        phoneOnOsmo = true, cameraMode = WearCameraMode.PHOTO, batteryPercent = 82, photosLeft = 13_716, recordSecondsLeft = 12_173
    )

    @Test
    fun `no status yet means the phone app is not open`() {
        assertEquals("Open Sidereal on your phone", WatchDisplay.headline(null, ageMs = 0))
    }

    @Test
    fun `a stale status is treated like no status`() {
        assertEquals("Open Sidereal on your phone", WatchDisplay.headline(ready, ageMs = 10_000))
    }

    @Test
    fun `a fresh status within the grace period is shown`() {
        assertEquals("13.7k photos left", WatchDisplay.headline(ready, ageMs = 2_000))
    }

    @Test
    fun `phone not on the Osmo network says so`() {
        assertEquals("Phone isn't on the Osmo's WiFi", WatchDisplay.headline(ready.copy(phoneOnOsmo = false), 0))
    }

    @Test
    fun `a running sequence shows its progress`() {
        assertEquals("Panorama 3/9", WatchDisplay.headline(ready.copy(sequenceRunning = true, sequenceLabel = "Panorama 3/9"), 0))
    }

    @Test
    fun `recording shows the clock`() {
        val s = ready.copy(cameraMode = WearCameraMode.VIDEO, recording = true, recordElapsedSec = 75)
        assertEquals("REC 01:15", WatchDisplay.headline(s, 0))
    }

    @Test
    fun `video mode idle shows time left`() {
        assertEquals("3h 22m left", WatchDisplay.headline(ready.copy(cameraMode = WearCameraMode.VIDEO), 0))
    }

    @Test
    fun `small photo counts are written in full`() {
        assertEquals("987 photos left", WatchDisplay.headline(ready.copy(photosLeft = 987), 0))
    }

    @Test
    fun `unknown photo count shows no number`() {
        assertEquals("Ready", WatchDisplay.headline(ready.copy(photosLeft = -1), 0))
    }

    @Test
    fun `battery text`() {
        assertEquals("82%", WatchDisplay.battery(ready))
        assertEquals("--", WatchDisplay.battery(ready.copy(batteryPercent = -1)))
        assertEquals("--", WatchDisplay.battery(null))
    }

    @Test
    fun `the shutter is available only when the phone is on the Osmo and no sequence is running`() {
        assertTrue(WatchDisplay.canShoot(ready, ageMs = 0))
        assertFalse(WatchDisplay.canShoot(ready.copy(phoneOnOsmo = false), 0))
        assertFalse(WatchDisplay.canShoot(ready.copy(sequenceRunning = true), 0))
        assertFalse(WatchDisplay.canShoot(null, 0))
        assertFalse(WatchDisplay.canShoot(ready, ageMs = 20_000))
    }

    @Test
    fun `the shutter button reads photo, record or stop`() {
        assertEquals("Photo", WatchDisplay.shutterLabel(ready))
        assertEquals("Rec", WatchDisplay.shutterLabel(ready.copy(cameraMode = WearCameraMode.VIDEO)))
        assertEquals("Stop", WatchDisplay.shutterLabel(ready.copy(cameraMode = WearCameraMode.VIDEO, recording = true)))
    }

    @Test
    fun `the shutter sends a capture in photo mode and a record toggle in video mode`() {
        assertEquals(WearCommand.Capture, WatchDisplay.shutterCommand(ready))
        assertEquals(WearCommand.ToggleRecord, WatchDisplay.shutterCommand(ready.copy(cameraMode = WearCameraMode.VIDEO)))
    }

    @Test
    fun `drag distance becomes a gimbal rate with a dead zone and a clamp`() {
        // dragging right and up by half the radius: yaw right (+), pitch up (+, screen y is down so negative dy)
        val rate = WatchDisplay.dragToGimbal(dxPx = 50f, dyPx = -50f, radiusPx = 100f)
        assertTrue(rate.yaw > 0f && rate.pitch > 0f)
        assertEquals(WearCommand.Gimbal(0f, 0f), WatchDisplay.dragToGimbal(3f, 3f, 100f))
        assertEquals(WearCommand.Gimbal(1f, 0f), WatchDisplay.dragToGimbal(900f, 0f, 100f))
    }

    @Test
    fun `an ack is shown briefly only when it carries something to say`() {
        assertEquals(null, WatchDisplay.ackText(WearAck(WearPaths.CAPTURE, true, "")))
        assertEquals("Stop recording first", WatchDisplay.ackText(WearAck(WearPaths.CAPTURE, false, "Stop recording first")))
        assertEquals("Stop sent", WatchDisplay.ackText(WearAck(WearPaths.TOGGLE_RECORD, true, "Stop sent")))
        assertEquals("Failed", WatchDisplay.ackText(WearAck(WearPaths.CAPTURE, false, "")))
    }

    @Test
    fun `live view is available whenever the phone is on the Osmo, even mid sequence`() {
        assertTrue(WatchDisplay.canWatchLive(ready, 0))
        assertTrue(WatchDisplay.canWatchLive(ready.copy(sequenceRunning = true), 0))
        assertFalse(WatchDisplay.canWatchLive(ready.copy(phoneOnOsmo = false), 0))
        assertFalse(WatchDisplay.canWatchLive(null, 0))
        assertFalse(WatchDisplay.canWatchLive(ready, ageMs = 20_000))
    }

    @Test
    fun `the open-on-phone button is offered when the phone is not answering`() {
        assertTrue(WatchDisplay.showOpenPhone(null, ageMs = 0))
        assertTrue(WatchDisplay.showOpenPhone(ready, ageMs = 10_000))
    }

    @Test
    fun `it is hidden while the phone app is answering, on the Osmo's WiFi or not`() {
        assertFalse(WatchDisplay.showOpenPhone(ready, ageMs = 2_000))
        assertFalse(WatchDisplay.showOpenPhone(ready.copy(phoneOnOsmo = false), ageMs = 2_000))
    }

    @Test
    fun `the open-on-phone link is a stable custom-scheme address the phone app can claim`() {
        assertEquals("sidereal", WearPaths.OPEN_PHONE_SCHEME)
        assertEquals("sidereal://open", WearPaths.OPEN_PHONE_URI)
        assertTrue(WearPaths.OPEN_PHONE_URI.startsWith(WearPaths.OPEN_PHONE_SCHEME + "://"))
    }
}
