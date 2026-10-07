package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The curve is the X5's measured preview response at ISO 100 (indoor scene, 2026-10-07): shutter seconds -> mean luma. */
class LumaCurveTest {

    private fun stops(seconds: Double) = Math.log(seconds) / Math.log(2.0)

    @Test
    fun `it reproduces the camera's measured points`() {
        assertEquals(104.6, LumaCurve.lumaAt(stops(1.0)), 0.5)
        assertEquals(71.0, LumaCurve.lumaAt(stops(0.5)), 0.5)
        assertEquals(29.5, LumaCurve.lumaAt(stops(1.0 / 8)), 0.5)
        assertEquals(11.8, LumaCurve.lumaAt(stops(1.0 / 30)), 0.5)
        assertEquals(191.7, LumaCurve.lumaAt(stops(4.0)), 0.5)
    }

    @Test
    fun `more exposure is always brighter and never beyond white`() {
        var previous = -1.0
        var s = -12.0
        while (s <= 8.0) {
            val l = LumaCurve.lumaAt(s)
            assertTrue("at $s: $l vs $previous", l >= previous)
            assertTrue(l in 0.0..255.0)
            previous = l
            s += 0.25
        }
    }

    @Test
    fun `stopsFor is the inverse of lumaAt across the useful range`() {
        var s = -7.0
        while (s <= 3.5) {
            assertEquals(s, LumaCurve.stopsFor(LumaCurve.lumaAt(s)), 0.02)
            s += 0.25
        }
    }

    @Test
    fun `extreme readings give finite answers instead of blowing up`() {
        assertTrue(LumaCurve.stopsFor(0.0).isFinite())
        assertTrue(LumaCurve.stopsFor(255.0).isFinite())
        assertTrue(LumaCurve.stopsFor(0.0) < LumaCurve.stopsFor(10.0))
        assertTrue(LumaCurve.stopsFor(255.0) > LumaCurve.stopsFor(200.0))
    }
}
