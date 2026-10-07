package io.github.mugenoesis.sidereal.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/** Synthetic stars with a known answer: a 2D Gaussian of sigma s has FWHM = 2.3548 * s. */
class StarMetricsTest {

    private val fwhmPerSigma = 2.3548f

    private data class Blob(val x: Float, val y: Float, val sigma: Float, val amplitude: Float)

    private fun image(
        w: Int = 64,
        h: Int = 64,
        background: Float = 20f,
        noise: Float = 0f,
        seed: Int = 1,
        vararg blobs: Blob
    ): LumaImage {
        val rng = Random(seed)
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = background
            for (b in blobs) {
                val dx = x - b.x
                val dy = y - b.y
                v += b.amplitude * exp(-(dx * dx + dy * dy) / (2f * b.sigma * b.sigma))
            }
            if (noise > 0f) v += (rng.nextFloat() * 2f - 1f) * noise * 1.7f
            data[y * w + x] = v.coerceIn(0f, 255f)
        }
        return LumaImage(w, h, data)
    }

    private fun fwhmOf(sigma: Float, background: Float = 20f, x: Float = 32f, y: Float = 32f, noise: Float = 0f): Float =
        StarMetrics.measure(image(background = background, noise = noise, blobs = arrayOf(Blob(x, y, sigma, 180f))))!!.fwhm

    @Test
    fun `measures a gaussian star's fwhm`() {
        assertEquals(2f * fwhmPerSigma, fwhmOf(2f), 2f * fwhmPerSigma * 0.12f)
        assertEquals(3f * fwhmPerSigma, fwhmOf(3f), 3f * fwhmPerSigma * 0.12f)
    }

    @Test
    fun `a bigger defocused star always reads bigger`() {
        val readings = listOf(1.2f, 1.8f, 2.5f, 3.5f, 5f).map { fwhmOf(it) }
        for (i in 1 until readings.size) assertTrue("readings=$readings", readings[i] > readings[i - 1])
    }

    @Test
    fun `background brightness does not change the answer`() {
        assertEquals(fwhmOf(2f, background = 10f), fwhmOf(2f, background = 80f), 0.5f)
    }

    @Test
    fun `a star centred between pixels reads the same as one on a pixel`() {
        assertEquals(fwhmOf(2f), fwhmOf(2f, x = 31.4f, y = 30.7f), 0.7f)
    }

    @Test
    fun `sensor noise does not wreck the measurement`() {
        assertEquals(2f * fwhmPerSigma, fwhmOf(2f, noise = 3f), 2f * fwhmPerSigma * 0.18f)
    }

    @Test
    fun `reports where the star is`() {
        val star = StarMetrics.measure(image(blobs = arrayOf(Blob(40.3f, 22.6f, 2f, 180f))))!!
        assertEquals(40.3f, star.x, 0.4f)
        assertEquals(22.6f, star.y, 0.4f)
    }

    @Test
    fun `a flat frame has no star`() {
        assertNull(StarMetrics.measure(image(blobs = emptyArray())))
    }

    @Test
    fun `noise alone is not mistaken for a star`() {
        assertNull(StarMetrics.measure(image(noise = 4f, blobs = emptyArray())))
    }

    @Test
    fun `a barely-there smudge is rejected`() {
        assertNull(StarMetrics.measure(image(noise = 3f, blobs = arrayOf(Blob(32f, 32f, 2f, 5f)))))
    }

    @Test
    fun `picks the brightest star when there are two`() {
        val img = image(blobs = arrayOf(Blob(16f, 16f, 4f, 110f), Blob(46f, 44f, 2f, 220f)))
        val star = StarMetrics.measure(img)!!
        assertEquals(2f * fwhmPerSigma, star.fwhm, 2f * fwhmPerSigma * 0.15f)
        assertEquals(46f, star.x, 1f)
    }

    @Test
    fun `a search window restricts which star is measured`() {
        val img = image(blobs = arrayOf(Blob(16f, 16f, 4f, 110f), Blob(46f, 44f, 2f, 220f)))
        val star = StarMetrics.measure(img, SearchWindow(16, 16, 10))!!
        assertEquals(4f * fwhmPerSigma, star.fwhm, 4f * fwhmPerSigma * 0.15f)
    }

    @Test
    fun `a star touching the frame edge is measured without crashing`() {
        val star = StarMetrics.measure(image(blobs = arrayOf(Blob(2f, 3f, 1.5f, 200f))))
        assertNotNull(star)
    }

    @Test
    fun `a clipped star is flagged saturated because its true fwhm is understated`() {
        val img = image(blobs = arrayOf(Blob(32f, 32f, 2.5f, 600f)))
        assertTrue(StarMetrics.measure(img)!!.saturated)
        assertFalse(StarMetrics.measure(image(blobs = arrayOf(Blob(32f, 32f, 2.5f, 120f))))!!.saturated)
    }

    @Test
    fun `a hot pixel is not a star`() {
        val img = image(blobs = emptyArray())
        img.data[20 * img.width + 20] = 255f
        assertNull(StarMetrics.measure(img))
    }
}
