package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensInfoTest {

    @Test
    fun `the DJI prime the camera reported on the real rig`() {
        val lens = LensInfo.parse("DJI MFT 15mm F1.7 ASPH")
        assertEquals(15f, lens.focalMinMm!!, 1e-6f)
        assertEquals(15f, lens.focalMaxMm!!, 1e-6f)
        assertEquals(1.7f, lens.maxApertureF!!, 1e-6f)
        assertFalse(lens.isZoom)
        assertEquals(15f, lens.primeFocalMm!!, 1e-6f)
    }

    @Test
    fun `other primes with decimals and different wording`() {
        assertEquals(7.5f, LensInfo.parse("Olympus M.Zuiko 7.5mm F1.8 Fisheye").primeFocalMm!!, 1e-6f)
        assertEquals(25f, LensInfo.parse("PANASONIC LEICA DG SUMMILUX 25mm F1.4").primeFocalMm!!, 1e-6f)
        assertEquals(12f, LensInfo.parse("OLYMPUS M.12mm F2.0").primeFocalMm!!, 1e-6f)
    }

    @Test
    fun `a zoom reports its range and has no single focal length`() {
        val lens = LensInfo.parse("OLYMPUS M.12-40mm F2.8 PRO")
        assertEquals(12f, lens.focalMinMm!!, 1e-6f)
        assertEquals(40f, lens.focalMaxMm!!, 1e-6f)
        assertTrue(lens.isZoom)
        assertNull(lens.primeFocalMm)
        assertEquals(2.8f, lens.maxApertureF!!, 1e-6f)
    }

    @Test
    fun `a variable aperture zoom takes the widest aperture`() {
        val lens = LensInfo.parse("LUMIX G VARIO 14-42mm F3.5-5.6")
        assertEquals(14f, lens.focalMinMm!!, 1e-6f)
        assertEquals(42f, lens.focalMaxMm!!, 1e-6f)
        assertEquals(3.5f, lens.maxApertureF!!, 1e-6f)
    }

    @Test
    fun `an en dash or spaces in a range are tolerated`() {
        val lens = LensInfo.parse("Lens 14 – 42 mm f/3.5-5.6")
        assertEquals(14f, lens.focalMinMm!!, 1e-6f)
        assertEquals(42f, lens.focalMaxMm!!, 1e-6f)
    }

    @Test
    fun `a name with no focal length gives only the name`() {
        val lens = LensInfo.parse("Unknown adapter")
        assertEquals("Unknown adapter", lens.name)
        assertNull(lens.focalMinMm)
        assertNull(lens.primeFocalMm)
        assertFalse(lens.isZoom)
    }

    @Test
    fun `nothing reported`() {
        assertNull(LensInfo.parse(null).name)
        assertNull(LensInfo.parse("").focalMinMm)
        assertNull(LensInfo.parse("   ").name)
    }

    @Test
    fun `sensible focal lengths only - a stray number is not a lens`() {
        assertNull(LensInfo.parse("Serial 99999mm").focalMinMm)
        assertNull(LensInfo.parse("Adapter 0mm").focalMinMm)
    }
}
