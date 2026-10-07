package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStatusFormatTest {

    private fun status(
        battery: Int? = 80,
        inserted: Boolean? = true,
        full: Boolean = false,
        error: Boolean = false,
        photos: Long? = 13_743,
        recLeft: Int? = 12_196,
        recording: Boolean = false,
        elapsed: Int = 0,
        video: Boolean = false
    ) = CameraStatus(battery, inserted, full, error, photos, recLeft, recording, elapsed, video)

    @Test
    fun `small photo counts show in full with a thousands separator`() {
        assertEquals("987", CameraStatusFormat.photosLeft(987))
        assertEquals("9,876", CameraStatusFormat.photosLeft(9_876))
    }

    @Test
    fun `big photo counts are abbreviated`() {
        assertEquals("13.7k", CameraStatusFormat.photosLeft(13_743))
        assertEquals("120k", CameraStatusFormat.photosLeft(120_400))
    }

    @Test
    fun `record time remaining reads in the largest sensible units`() {
        assertEquals("3h 23m", CameraStatusFormat.timeLeft(12_196))
        assertEquals("2m 05s", CameraStatusFormat.timeLeft(125))
        assertEquals("45s", CameraStatusFormat.timeLeft(45))
        assertEquals("0s", CameraStatusFormat.timeLeft(0))
    }

    @Test
    fun `elapsed recording time is a clock`() {
        assertEquals("00:05", CameraStatusFormat.elapsed(5))
        assertEquals("12:34", CameraStatusFormat.elapsed(754))
        assertEquals("1:02:05", CameraStatusFormat.elapsed(3_725))
    }

    @Test
    fun `battery levels are bucketed`() {
        assertEquals(BatteryLevel.OK, CameraStatusFormat.batteryLevel(80))
        assertEquals(BatteryLevel.OK, CameraStatusFormat.batteryLevel(50))
        assertEquals(BatteryLevel.LOW, CameraStatusFormat.batteryLevel(49))
        assertEquals(BatteryLevel.LOW, CameraStatusFormat.batteryLevel(20))
        assertEquals(BatteryLevel.CRITICAL, CameraStatusFormat.batteryLevel(19))
        assertEquals(BatteryLevel.UNKNOWN, CameraStatusFormat.batteryLevel(null))
    }

    @Test
    fun `battery text`() {
        assertEquals("82%", CameraStatusFormat.battery(82))
        assertEquals("--", CameraStatusFormat.battery(null))
    }

    @Test
    fun `photo mode shows photos left`() {
        assertEquals("13.7k left", CameraStatusFormat.card(status(video = false)))
    }

    @Test
    fun `video mode shows record time left`() {
        assertEquals("3h 23m left", CameraStatusFormat.card(status(video = true)))
    }

    @Test
    fun `while recording the card text is the elapsed clock and what is left`() {
        assertEquals("REC 00:12 · 3h 23m left", CameraStatusFormat.card(status(video = true, recording = true, elapsed = 12)))
    }

    @Test
    fun `card problems take priority over counts`() {
        assertEquals("No card", CameraStatusFormat.card(status(inserted = false)))
        assertEquals("Card full", CameraStatusFormat.card(status(full = true)))
        assertEquals("Card error", CameraStatusFormat.card(status(error = true)))
    }

    @Test
    fun `unknown card state before the camera has reported`() {
        assertEquals("--", CameraStatusFormat.card(status(inserted = null, photos = null, recLeft = null)))
    }

    @Test
    fun `a nearly full card is flagged as a warning`() {
        assertTrue(CameraStatusFormat.cardWarning(status(photos = 20)))
        assertTrue(CameraStatusFormat.cardWarning(status(video = true, recLeft = 120)))
        assertTrue(!CameraStatusFormat.cardWarning(status()))
        assertTrue(CameraStatusFormat.cardWarning(status(full = true)))
    }
}
