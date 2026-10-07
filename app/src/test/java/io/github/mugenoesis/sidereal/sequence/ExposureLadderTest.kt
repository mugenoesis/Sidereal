package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** A ladder shaped like the X5's: shutters in thirds of a stop from 1/8000 to 8 s, ISO in whole stops 100..25600. */
internal fun x5Shutters(): List<ShutterOption> =
    (-39..9).map { k -> ShutterOption("S$k", 2.0.pow(k / 3.0)) }

internal fun x5Isos(): List<IsoOption> = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600).map { IsoOption("ISO_$it", it) }

class ExposureLadderTest {

    private fun ladder(maxShutterSec: Double = 8.0, maxIso: Int = 25600) =
        ExposureLadder(x5Shutters(), x5Isos(), maxShutterSec, maxIso)

    private fun log2(x: Double) = Math.log(x) / Math.log(2.0)

    @Test
    fun `a setting's stops are shutter stops plus ISO stops over 100`() {
        val s = ExposureSetting("S0", 1.0, "ISO_400", 400)
        assertEquals(2.0, s.stops, 1e-9)
        assertEquals(-3.0, ExposureSetting("x", 0.125, "ISO_100", 100).stops, 1e-9)
    }

    @Test
    fun `ordinary exposures map straight to a shutter at base ISO`() {
        val l = ladder()
        val s = l.settingFor(log2(1.0 / 60))
        assertEquals(100, s.iso)
        assertEquals(1.0 / 60, s.shutterSec, 0.003)
    }

    @Test
    fun `past the longest allowed shutter the ISO rises and the shutter backs off by the same amount`() {
        val l = ladder(maxShutterSec = 2.0)
        val s = l.settingFor(log2(4.0)) // 4 s at ISO 100 == 2 s at ISO 200
        assertEquals(200, s.iso)
        assertEquals(2.0, s.shutterSec, 0.01)
        assertEquals(log2(4.0), s.stops, 0.01)
    }

    @Test
    fun `a gradual climb moves one third of a stop at a time, never jumping`() {
        val l = ladder(maxShutterSec = 2.0)
        var previous = l.settingFor(-2.0).stops
        var e = -2.0
        while (e < 4.0) {
            e += 1.0 / 3
            val s = l.settingFor(e)
            assertTrue("step at $e: ${s.stops - previous}", abs(s.stops - previous) <= 0.35)
            previous = s.stops
        }
    }

    @Test
    fun `the exposure achieved is within a sixth of a stop of what was asked`() {
        val l = ladder(maxShutterSec = 4.0)
        var e = l.minStops
        while (e <= l.maxStops) {
            assertEquals("asked $e", e, l.settingFor(e).stops, 0.17)
            e += 0.1
        }
    }

    @Test
    fun `limits clamp instead of overshooting`() {
        val l = ladder(maxShutterSec = 2.0, maxIso = 1600)
        val top = l.settingFor(100.0)
        assertEquals(1600, top.iso)
        assertEquals(2.0, top.shutterSec, 0.01)
        assertEquals(l.maxStops, top.stops, 1e-9)
        val bottom = l.settingFor(-100.0)
        assertEquals(100, bottom.iso)
        assertEquals(l.minStops, bottom.stops, 1e-9)
        assertEquals(1.0 / 8000, bottom.shutterSec, 1.0 / 8000 * 0.2)
    }

    @Test
    fun `the brightest reachable exposure respects both caps`() {
        assertEquals(log2(2.0) + log2(16.0), ladder(2.0, 1600).maxStops, 0.01)
    }

    @Test
    fun `the current camera settings can be located on the ladder`() {
        val l = ladder()
        val s = l.nearest("S0", "ISO_800")
        assertEquals(3.0, s.stops, 0.01)
    }
}
