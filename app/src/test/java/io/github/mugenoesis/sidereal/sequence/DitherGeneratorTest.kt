package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DitherGeneratorTest {

    private val config = DitherConfig(minDeg = 0.1f, maxDeg = 0.4f, seed = 42L)

    @Test
    fun `first offset is zero so frame one sits exactly on the base attitude`() {
        val offsets = DitherGenerator.offsets(config, 5)
        assertEquals(0f, offsets[0].pitch, 0f)
        assertEquals(0f, offsets[0].yaw, 0f)
    }

    @Test
    fun `returns one offset per frame`() {
        assertEquals(12, DitherGenerator.offsets(config, 12).size)
        assertEquals(0, DitherGenerator.offsets(config, 0).size)
    }

    @Test
    fun `same seed gives the same sequence and a different seed gives a different one`() {
        val a = DitherGenerator.offsets(config, 20)
        val b = DitherGenerator.offsets(config, 20)
        val c = DitherGenerator.offsets(config.copy(seed = 7L), 20)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `offsets never stray further than maxDeg from the base on either axis`() {
        for (o in DitherGenerator.offsets(config, 500)) {
            assertTrue("pitch ${o.pitch}", abs(o.pitch) <= config.maxDeg + 1e-6f)
            assertTrue("yaw ${o.yaw}", abs(o.yaw) <= config.maxDeg + 1e-6f)
        }
    }

    @Test
    fun `consecutive frames always move by at least minDeg so stars land on different pixels`() {
        val offsets = DitherGenerator.offsets(config, 500)
        for (i in 1 until offsets.size) {
            val d = hypot(offsets[i].pitch - offsets[i - 1].pitch, offsets[i].yaw - offsets[i - 1].yaw)
            assertTrue("step $i moved only $d", d >= config.minDeg - 1e-6f)
        }
    }

    @Test
    fun `offsets spread out over the allowed area instead of collapsing to one spot`() {
        val offsets = DitherGenerator.offsets(config, 200)
        assertTrue(offsets.map { it.pitch }.distinct().size > 50)
        assertTrue(offsets.maxOf { it.yaw } > 0.1f)
        assertTrue(offsets.minOf { it.yaw } < -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a minDeg that cannot fit inside maxDeg`() {
        DitherGenerator.offsets(DitherConfig(minDeg = 1f, maxDeg = 0.2f, seed = 1L), 3)
    }
}
