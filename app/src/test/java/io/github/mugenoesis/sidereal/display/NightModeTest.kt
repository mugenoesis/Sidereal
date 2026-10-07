package io.github.mugenoesis.sidereal.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    @Test
    fun `green and blue are always zero, for every colour`() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val out = NightMode.toRed(r, g, b)
            assertEquals("g for ($r,$g,$b)", 0, out.second)
            assertEquals("b for ($r,$g,$b)", 0, out.third)
        }
    }

    @Test
    fun `white becomes full red and black stays black`() {
        assertEquals(Triple(255, 0, 0), NightMode.toRed(255, 255, 255))
        assertEquals(Triple(0, 0, 0), NightMode.toRed(0, 0, 0))
    }

    @Test
    fun `brightness is preserved as the red level, so contrast survives`() {
        val dim = NightMode.toRed(60, 60, 60).first
        val bright = NightMode.toRed(200, 200, 200).first
        assertTrue(bright > dim)
        assertEquals(60, dim)
        assertEquals(200, bright)
    }

    @Test
    fun `green reads brighter than blue, like it does to the eye`() {
        assertTrue(NightMode.toRed(0, 255, 0).first > NightMode.toRed(0, 0, 255).first)
    }

    @Test
    fun `the colour matrix has the shape Android expects and kills the green and blue rows`() {
        val m = NightMode.colorMatrix()
        assertEquals(20, m.size)
        for (i in 5 until 15) assertEquals("row g/b cell $i", 0f, m[i], 0f)
        // alpha row is identity: opacity must not change.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.slice(15 until 20))
    }

    @Test
    fun `the matrix agrees with toRed`() {
        val m = NightMode.colorMatrix()
        val (r, g, b) = Triple(120, 200, 40)
        val viaMatrix = (m[0] * r + m[1] * g + m[2] * b).toInt().coerceIn(0, 255)
        assertEquals(NightMode.toRed(r, g, b).first.toFloat(), viaMatrix.toFloat(), 1f)
    }

    @Test
    fun `night mode dims the screen and off hands brightness back to the system`() {
        assertTrue(NightMode.screenBrightness(true) in 0.0f..0.1f)
        assertEquals(-1f, NightMode.screenBrightness(false), 0f)
    }
}
