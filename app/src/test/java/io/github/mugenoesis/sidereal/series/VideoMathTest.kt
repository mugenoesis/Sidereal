package io.github.mugenoesis.sidereal.series

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMathTest {

    @Test
    fun `output keeps the picture's shape and never exceeds the limit`() {
        val (w, h) = VideoMath.outputSize(4608, 3456, maxWidth = 2880, maxHeight = 2160)
        assertEquals(2880, w)
        assertEquals(2160, h)
    }

    @Test
    fun `a small picture is not blown up`() {
        val (w, h) = VideoMath.outputSize(1280, 960, maxWidth = 2880, maxHeight = 2160)
        assertEquals(1280, w)
        assertEquals(960, h)
    }

    @Test
    fun `the limit applies to whichever side hits it first`() {
        val (w, h) = VideoMath.outputSize(4000, 1000, maxWidth = 2000, maxHeight = 2000)
        assertEquals(2000, w)
        assertEquals(496, h) // rounded down to a multiple of 16, which hardware encoders prefer
    }

    @Test
    fun `dimensions are multiples of sixteen so hardware encoders accept them`() {
        for ((sw, sh) in listOf(4608 to 3456, 4000 to 3000, 1919 to 1081, 5472 to 3648)) {
            val (w, h) = VideoMath.outputSize(sw, sh, 2880, 2160)
            assertEquals("width $w", 0, w % 16)
            assertEquals("height $h", 0, h % 16)
            assertTrue(w <= 2880 && h <= 2160)
            assertTrue(w >= 16 && h >= 16)
        }
    }

    @Test
    fun `presentation times are evenly spaced at the frame rate`() {
        assertEquals(0L, VideoMath.ptsUs(0, 25))
        assertEquals(40_000L, VideoMath.ptsUs(1, 25))
        assertEquals(1_000_000L, VideoMath.ptsUs(25, 25))
        assertEquals(33_333L, VideoMath.ptsUs(1, 30))
    }

    @Test
    fun `bitrate scales with picture size and frame rate and stays in a sane range`() {
        val small = VideoMath.bitrate(1280, 960, 24)
        val big = VideoMath.bitrate(2880, 2160, 30)
        assertTrue(big > small)
        assertTrue(small >= 2_000_000)
        assertTrue(big <= 100_000_000)
    }

    @Test
    fun `grey pixels convert to mid chroma and the right luma`() {
        val white = IntArray(4) { 0xFFFFFFFF.toInt() }
        val black = IntArray(4) { 0xFF000000.toInt() }
        val w = VideoMath.toI420(white, 2, 2)
        assertArrayEquals(byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte()), w.y)
        assertEquals(128, w.u[0].toInt() and 0xFF)
        assertEquals(128, w.v[0].toInt() and 0xFF)
        val b = VideoMath.toI420(black, 2, 2)
        assertEquals(16, b.y[0].toInt() and 0xFF)
        assertEquals(128, b.u[0].toInt() and 0xFF)
    }

    @Test
    fun `red and blue push chroma in opposite directions`() {
        val red = VideoMath.toI420(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        val blue = VideoMath.toI420(IntArray(4) { 0xFF0000FF.toInt() }, 2, 2)
        assertTrue((red.v[0].toInt() and 0xFF) > 200)
        assertTrue((red.u[0].toInt() and 0xFF) < 128)
        assertTrue((blue.u[0].toInt() and 0xFF) > 200)
        assertTrue((blue.v[0].toInt() and 0xFF) < 128)
    }

    @Test
    fun `chroma planes are a quarter the size, averaged over each two by two block`() {
        val argb = IntArray(16) { if (it % 4 < 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() } // 4 wide: 2 white, 2 black per row
        val out = VideoMath.toI420(argb, 4, 4)
        assertEquals(16, out.y.size)
        assertEquals(4, out.u.size)
        assertEquals(4, out.v.size)
        // left blocks are white, right blocks black: luma differs, chroma stays neutral
        assertEquals(235, out.y[0].toInt() and 0xFF)
        assertEquals(16, out.y[3].toInt() and 0xFF)
        assertEquals(128, out.u[0].toInt() and 0xFF)
    }
}
