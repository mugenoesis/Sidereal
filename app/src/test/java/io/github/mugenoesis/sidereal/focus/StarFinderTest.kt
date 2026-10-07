package io.github.mugenoesis.sidereal.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class StarFinderTest {

    private fun frame(w: Int, h: Int, background: Float = 25f, noise: Float = 0f, vararg stars: Triple<Int, Int, Float>): LumaImage {
        val rng = Random(7)
        val data = FloatArray(w * h) { background + if (noise > 0f) (rng.nextFloat() * 2f - 1f) * noise else 0f }
        for ((sx, sy, amp) in stars) {
            for (y in 0 until h) for (x in 0 until w) {
                val d2 = ((x - sx) * (x - sx) + (y - sy) * (y - sy)).toFloat()
                data[y * w + x] = (data[y * w + x] + amp * exp(-d2 / 8f)).coerceIn(0f, 255f)
            }
        }
        return LumaImage(w, h, data)
    }

    @Test
    fun `converts ARGB pixels to luminance`() {
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val luma = LumaConversion.fromArgb(pixels, 2, 2)
        assertEquals(255f, luma[0, 0], 0.5f)
        assertEquals(0f, luma[1, 0], 0.5f)
        assertEquals(76f, luma[0, 1], 1f)
        assertEquals(150f, luma[1, 1], 1f)
    }

    @Test
    fun `finds the brightest star in a large frame`() {
        val img = frame(400, 300, noise = 3f, stars = arrayOf(Triple(120, 80, 150f), Triple(300, 220, 220f)))
        val spot = StarFinder.brightest(img)!!
        assertEquals(300f, spot.first.toFloat(), 6f)
        assertEquals(220f, spot.second.toFloat(), 6f)
    }

    @Test
    fun `finds nothing in an empty sky`() {
        assertNull(StarFinder.brightest(frame(400, 300, noise = 3f)))
    }

    @Test
    fun `ignores a lone hot pixel`() {
        val img = frame(400, 300, noise = 2f)
        img.data[100 * 400 + 100] = 255f
        assertNull(StarFinder.brightest(img))
    }

    @Test
    fun `crop is centred on the point and the requested size`() {
        val img = frame(200, 200, stars = arrayOf(Triple(100, 100, 200f)))
        val crop = StarFinder.crop(img, 100, 100, 64)
        assertEquals(64, crop.width)
        assertEquals(64, crop.height)
        assertEquals(img[100, 100], crop[32, 32], 0.01f)
    }

    @Test
    fun `crop near an edge is shifted inwards rather than shrunk`() {
        val img = frame(200, 200)
        val crop = StarFinder.crop(img, 3, 5, 64)
        assertEquals(64, crop.width)
        assertEquals(64, crop.height)
    }

    @Test
    fun `crop is limited by the image when the image is smaller than the request`() {
        val img = frame(40, 30)
        val crop = StarFinder.crop(img, 20, 15, 64)
        assertEquals(40, crop.width)
        assertEquals(30, crop.height)
    }

    @Test
    fun `finder plus metrics measure a star in a big frame end to end`() {
        val img = frame(400, 300, noise = 2f, stars = arrayOf(Triple(250, 150, 200f)))
        val spot = StarFinder.brightest(img)!!
        val star = StarMetrics.measure(StarFinder.crop(img, spot.first, spot.second, 64))
        assertNotNull(star)
        // the test star is exp(-d2/8) -> sigma 2 -> fwhm 4.7
        assertTrue("fwhm=${star!!.fwhm}", star.fwhm in 3.8f..5.6f)
    }
}
