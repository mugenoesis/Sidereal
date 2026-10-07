package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SceneChangeDetectorTest {

    private fun scene(base: Float, seed: Int = 1): FloatArray {
        val r = Random(seed)
        return FloatArray(64) { (base + r.nextFloat() * 90f).coerceIn(0f, 255f) }
    }

    private fun shifted(s: FloatArray, by: Float) = FloatArray(64) { (s[it] + by).coerceIn(0f, 255f) }

    private fun noisy(s: FloatArray, amount: Float, seed: Int) = Random(seed).let { r -> FloatArray(64) { (s[it] + (r.nextFloat() - 0.5f) * amount).coerceIn(0f, 255f) } }

    @Test
    fun `a steady scene never fires`() {
        val ref = scene(60f)
        val d = SceneChangeDetector(ref)
        repeat(200) { assertFalse(d.onFrame(noisy(ref, 6f, it))) }
    }

    @Test
    fun `slow exposure drift is not a scene change`() {
        val ref = scene(60f)
        val d = SceneChangeDetector(ref)
        for (i in 0 until 120) assertFalse("frame $i", d.onFrame(shifted(ref, i * 0.15f)))
    }

    @Test
    fun `a different scene that holds still fires after a few steady frames`() {
        val d = SceneChangeDetector(scene(60f, seed = 1))
        val other = scene(120f, seed = 9)
        val fired = (0 until 8).map { d.onFrame(noisy(other, 4f, it)) }
        assertTrue("never fired: $fired", fired.any { it })
        assertFalse("fired immediately", fired[0])
    }

    @Test
    fun `while the camera is still panning it waits - every frame differs from the last`() {
        val d = SceneChangeDetector(scene(60f, seed = 1))
        for (i in 0 until 40) assertFalse("frame $i", d.onFrame(scene(40f + i, seed = 100 + i)))
    }

    @Test
    fun `it fires once the panning stops`() {
        val d = SceneChangeDetector(scene(60f, seed = 1))
        for (i in 0 until 10) d.onFrame(scene(40f + i * 7, seed = 100 + i))
        val landed = scene(100f, seed = 77)
        assertTrue((0 until 8).any { d.onFrame(noisy(landed, 3f, it)) })
    }

    @Test
    fun `it fires only once per change`() {
        val d = SceneChangeDetector(scene(60f, seed = 1))
        val other = scene(150f, seed = 5)
        val count = (0 until 60).count { d.onFrame(noisy(other, 3f, it)) }
        assertEquals(1, count)
    }

    @Test
    fun `after a change is handled the new scene becomes the reference`() {
        val d = SceneChangeDetector(scene(60f, seed = 1))
        val other = scene(150f, seed = 5)
        (0 until 10).forEach { d.onFrame(noisy(other, 3f, it)) }
        d.rebase(other)
        repeat(40) { assertFalse(d.onFrame(noisy(other, 3f, 50 + it))) }
    }

    @Test
    fun `a sudden flash that comes straight back is not a scene change`() {
        val ref = scene(60f)
        val d = SceneChangeDetector(ref)
        assertFalse(d.onFrame(scene(200f, seed = 3)))
        repeat(20) { assertFalse(d.onFrame(noisy(ref, 4f, it))) }
    }

    @Test
    fun `signatures are 8 by 8 block averages of luminance`() {
        // a 16x16 image, left half black, right half white
        val pixels = IntArray(16 * 16) { i -> if (i % 16 < 8) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val sig = SceneSignature.of(pixels, 16)
        assertEquals(64, sig.size)
        assertEquals(0f, sig[0], 1f)       // top-left block: black
        assertEquals(255f, sig[7], 1f)     // top-right block: white
        assertEquals(0f, sig[56], 1f)      // bottom-left
        assertEquals(255f, sig[63], 1f)    // bottom-right
    }

    @Test
    fun `difference is the mean absolute difference as a fraction of full scale`() {
        val a = FloatArray(64) { 0f }
        val b = FloatArray(64) { 51f }
        assertEquals(0.2f, SceneSignature.difference(a, b), 1e-4f)
        assertEquals(0f, SceneSignature.difference(a, a), 1e-6f)
    }
}
