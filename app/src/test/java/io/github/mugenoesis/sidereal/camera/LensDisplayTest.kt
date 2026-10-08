package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDisplayTest {

    private fun show(info: LensInfo? = null, answered: Boolean = true, ring: Int? = null, ringMax: Int? = null) =
        LensDisplay.describe(LensReading(info, answered, ring, ringMax))

    @Test fun `before the camera has answered it says it is checking`() {
        assertEquals(LensLine("Checking lens...", LensLine.Kind.CHECKING), show(answered = false))
    }

    @Test fun `a prime shows its one focal length and aperture`() {
        val line = show(LensInfo.parse("DJI MFT 15mm F1.7 ASPH"), ring = 1100, ringMax = 2035)
        assertEquals("15 mm f/1.7", line.text)
        assertEquals(LensLine.Kind.KNOWN, line.kind)
    }

    @Test fun `a zoom shows its range, and the aperture at both ends when it varies`() {
        val line = show(LensInfo.parse("LUMIX G VARIO 12-32/F3.5-5.6"), ring = 1000, ringMax = 1570)
        assertEquals("12-32 mm f/3.5-5.6", line.text)
    }

    @Test fun `a zoom with one aperture shows it once`() {
        assertEquals("12-40 mm f/2.8", show(LensInfo.parse("OLYMPUS M.12-40mm F2.8 PRO")).text)
    }

    @Test fun `half millimetres are kept`() {
        assertEquals("7.5 mm f/1.8", show(LensInfo.parse("Olympus M.Zuiko 7.5mm F1.8 Fisheye")).text)
    }

    @Test fun `a lens the camera cannot name says so and offers to find out`() {
        val line = show(LensInfo.parse("Unknown"), ring = 900, ringMax = 1570)
        assertEquals(LensLine.Kind.UNKNOWN, line.kind)
        assertTrue(line.text, line.text.contains("unknown", ignoreCase = true))
    }

    @Test fun `a ring reading far outside its own range means the lens is not extended`() {
        // seen on the real Panasonic 12-32 stowed: the ring read -26270 against a range of 0 to 1570
        val line = show(LensInfo.parse("Unknown"), ring = -26270, ringMax = 1570)
        assertEquals(LensLine.Kind.NOT_EXTENDED, line.kind)
        assertTrue(line.text, line.text.contains("zoom ring", ignoreCase = true))
    }

    @Test fun `not extended wins over whatever name is known`() {
        assertEquals(LensLine.Kind.NOT_EXTENDED, show(LensInfo.parse("LUMIX G VARIO 12-32/F3.5-5.6"), ring = -26270, ringMax = 1570).kind)
    }

    @Test fun `a ring value just at the end of its range is normal`() {
        assertEquals(LensLine.Kind.UNKNOWN, show(LensInfo.parse("Unknown"), ring = 1570, ringMax = 1570).kind)
        assertEquals(LensLine.Kind.UNKNOWN, show(LensInfo.parse("Unknown"), ring = 0, ringMax = 1570).kind)
        assertEquals(LensLine.Kind.UNKNOWN, show(LensInfo.parse("Unknown"), ring = 1600, ringMax = 1570).kind) // reading lags a step
    }

    @Test fun `without a ring reading the lens is assumed fine`() {
        assertEquals(LensLine.Kind.KNOWN, show(LensInfo.parse("DJI MFT 15mm F1.7 ASPH"), ring = null, ringMax = null).kind)
    }

    @Test fun `a name with no focal length is shown as the name`() {
        val line = show(LensInfo.parse("Some adapter"))
        assertEquals("Some adapter", line.text)
        assertEquals(LensLine.Kind.KNOWN, line.kind)
    }
}
