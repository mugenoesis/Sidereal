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
        // maxDeg 0.4 on a 0.1 degree grid has 9 positions per axis; a long run should visit most of them.
        assertTrue(offsets.map { Math.round(it.pitch * 10) }.distinct().size >= 7)
        assertTrue(offsets.maxOf { it.yaw } > 0.1f)
        assertTrue(offsets.minOf { it.yaw } < -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a minDeg that cannot fit inside maxDeg`() {
        DitherGenerator.offsets(DitherConfig(minDeg = 1f, maxDeg = 0.2f, seed = 1L), 3)
    }

    @Test
    fun `offsets land on the gimbal's 0_1 degree grid so the commanded pose is one it can report and reach`() {
        for (o in DitherGenerator.offsets(config, 200)) {
            assertEquals(0f, Math.abs(o.pitch / 0.1f - Math.round(o.pitch / 0.1f)), 1e-3f)
            assertEquals(0f, Math.abs(o.yaw / 0.1f - Math.round(o.yaw / 0.1f)), 1e-3f)
        }
    }

    @Test
    fun `quantizing never pushes an offset past maxDeg or under minDeg`() {
        val odd = DitherConfig(minDeg = 0.25f, maxDeg = 0.45f, seed = 9L)
        val offsets = DitherGenerator.offsets(odd, 300)
        for (o in offsets) {
            assertTrue(abs(o.pitch) <= 0.45f + 1e-5f && abs(o.yaw) <= 0.45f + 1e-5f)
        }
        for (i in 1 until offsets.size) {
            assertTrue(hypot(offsets[i].pitch - offsets[i - 1].pitch, offsets[i].yaw - offsets[i - 1].yaw) >= 0.25f - 1e-5f)
        }
    }
}
