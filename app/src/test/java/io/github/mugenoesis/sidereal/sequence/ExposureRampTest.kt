package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The scene is just a light level in stops (S); the "camera" returns the preview luma the real X5 would for S + the
 * exposure the ramp chose, optionally with noise. The ramp must follow S the way the keep-darkness option says.
 */
class ExposureRampTest {

    private val ladder = ExposureLadder(x5Shutters(), x5Isos(), maxShutterSec = 4.0, maxIso = 6400)

    private fun start(shutterSec: Double = 1.0 / 60, iso: Int = 100) =
        ladder.nearest(x5Shutters().minByOrNull { abs(it.seconds - shutterSec) }!!.name, "ISO_$iso")

    private class Run(val ramp: ExposureRamp, val scene: (Int) -> Double, val noise: Double = 0.0, seed: Int = 1) {
        val rng = Random(seed)
        val stops = ArrayList<Double>()
        val lumas = ArrayList<Double>()
        fun go(frames: Int) {
            for (i in 0 until frames) {
                val e = ramp.current.stops
                val clean = LumaCurve.lumaAt(scene(i) + e)
                val l = (clean * (1 + noise * (rng.nextDouble() * 2 - 1))).coerceIn(0.0, 255.0)
                lumas += l
                ramp.next(l)
                stops += ramp.current.stops
            }
        }
    }

    private fun ramp(keepDark: Double, maxStep: Double = 1.0 / 3, from: ExposureSetting = start()) =
        ExposureRamp(ladder, RampConfig(keepDarkFraction = keepDark, maxStepStops = maxStep), from)

    @Test
    fun `a steady scene leaves the exposure alone`() {
        val run = Run(ramp(0.5), { 6.0 })
        run.go(60)
        assertEquals(start().stops, ramp(0.5).current.stops, 1e-9)
        assertTrue("drifted: ${run.stops.min()}..${run.stops.max()}", run.stops.max() - run.stops.min() < 0.35)
    }

    @Test
    fun `with no darkness kept, a scene dimming six stops is fully compensated and brightness holds`() {
        val run = Run(ramp(0.0), { i -> 6.0 - 6.0 * i / 100.0 })
        run.go(110)
        assertEquals(6.0, run.stops.last() - start().stops, 0.5)
        val first = run.lumas.first()
        val last = run.lumas.takeLast(5).average()
        assertEquals(first, last, first * 0.25)
    }

    @Test
    fun `keeping half the darkness compensates half the dimming`() {
        val run = Run(ramp(0.5), { i -> 6.0 - 6.0 * i / 100.0 })
        run.go(110)
        assertEquals(3.0, run.stops.last() - start().stops, 0.5)
        assertTrue("frames should end darker than they began", run.lumas.takeLast(5).average() < run.lumas.first() * 0.8)
    }

    @Test
    fun `keeping all the darkness means the exposure never changes`() {
        val run = Run(ramp(1.0), { i -> 6.0 - 6.0 * i / 100.0 })
        run.go(110)
        assertEquals(start().stops, run.stops.last(), 1e-9)
    }

    @Test
    fun `exposure never moves faster than the step limit, even for a sudden change`() {
        val run = Run(ramp(0.0, maxStep = 1.0 / 3), { i -> if (i < 5) 6.0 else 2.0 })
        run.go(40)
        val deltas = run.stops.zipWithNext { a, b -> abs(b - a) }
        assertTrue("fastest step ${deltas.max()}", deltas.max() <= 0.35)
        assertEquals("it still gets there", 4.0, run.stops.last() - start().stops, 0.4)
    }

    @Test
    fun `a brightening scene (dawn) brings the exposure back down`() {
        val from = ladder.nearest(x5Shutters().minByOrNull { abs(it.seconds - 2.0) }!!.name, "ISO_800")
        val run = Run(ramp(0.0, from = from), { i -> 0.0 + 6.0 * i / 100.0 })
        run.go(110)
        assertEquals(-6.0, run.stops.last() - from.stops, 0.6)
    }

    @Test
    fun `it stops at the ladder's limits instead of oscillating or overshooting`() {
        val run = Run(ramp(0.0), { i -> 6.0 - 20.0 * i / 100.0 })
        run.go(150)
        assertEquals(ladder.maxStops, run.stops.last(), 1e-9)
        assertTrue(run.stops.takeLast(30).all { it == ladder.maxStops })
        assertTrue(run.stops.zipWithNext { a, b -> b >= a - 1e-9 }.all { it })
    }

    @Test
    fun `frame noise does not make the exposure jitter`() {
        val run = Run(ramp(0.0), { i -> 5.0 - 4.0 * i / 100.0 }, noise = 0.10, seed = 7)
        run.go(110)
        val deltas = run.stops.zipWithNext { a, b -> abs(b - a) }
        assertTrue("jumpiest step ${deltas.max()}", deltas.max() <= 0.35)
        assertEquals(4.0, run.stops.last() - start().stops, 0.7)
        val reversals = run.stops.zipWithNext { a, b -> b - a }.filter { abs(it) > 1e-9 }.zipWithNext().count { (a, b) -> a * b < 0 }
        assertTrue("direction changed $reversals times", reversals <= 6)
    }

    @Test
    fun `a blown-out reading pulls the exposure down rather than being trusted`() {
        val r = ramp(0.0)
        repeat(3) { r.next(LumaCurve.lumaAt(start().stops + 6.0 - 6.0 + 0.0)) }
        val before = r.current.stops
        repeat(6) { r.next(254.0) }
        assertTrue("went from $before to ${r.current.stops}", r.current.stops < before - 0.5)
    }

    @Test
    fun `a missing reading holds the current exposure`() {
        val r = ramp(0.0)
        repeat(3) { r.next(LumaCurve.lumaAt(start().stops + 3.0)) }
        val before = r.current
        r.next(null)
        assertEquals(before, r.current)
    }

    @Test
    fun `the decision explains itself for the progress display`() {
        val r = ramp(0.0)
        val d = r.next(LumaCurve.lumaAt(start().stops))
        assertFalse(d.summary.isBlank())
        assertTrue(d.summary.contains("ISO"))
    }

    @Test
    fun `the first readings only set the baseline`() {
        val r = ramp(0.0)
        val d1 = r.next(LumaCurve.lumaAt(start().stops))
        assertEquals(start(), d1.setting)
    }

    @Test
    fun `when the camera did not take the setting asked for the ramp can be told what it really has`() {
        val r = ramp(0.0)
        repeat(3) { r.next(LumaCurve.lumaAt(start().stops + 3.0)) }
        val actual = ladder.nearest(x5Shutters()[10].name, "ISO_800")
        r.resync(actual)
        assertEquals(actual, r.current)
    }
}
