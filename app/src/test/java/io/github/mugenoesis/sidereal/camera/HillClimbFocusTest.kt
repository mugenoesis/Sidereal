package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HillClimbFocusTest {

    // --- laplacianVariance ---

    @Test
    fun `laplacianVariance is zero for a perfectly flat image`() {
        val size = 10
        val flatGray = 0xFF808080.toInt() // opaque mid-gray, same everywhere
        val pixels = IntArray(size * size) { flatGray }
        assertEquals(0.0, HillClimbFocus.laplacianVariance(pixels, size), 1e-9)
    }

    @Test
    fun `laplacianVariance is higher for a sharp checkerboard than a blurred gradient`() {
        val size = 20
        val checkerboard = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val gradient = IntArray(size * size) { i ->
            val x = i % size
            val v = (x * 255 / size).coerceIn(0, 255)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }

        val sharpScore = HillClimbFocus.laplacianVariance(checkerboard, size)
        val blurredScore = HillClimbFocus.laplacianVariance(gradient, size)

        assertTrue("checkerboard ($sharpScore) should score far higher than a smooth gradient ($blurredScore)", sharpScore > blurredScore * 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `laplacianVariance rejects a pixel buffer of the wrong size`() {
        HillClimbFocus.laplacianVariance(IntArray(5), size = 10)
    }

    // --- normalizedLaplacianVariance ---

    private fun detailPicture(size: Int, level: Double): IntArray {
        // fixed pattern of texture, then brightness scaled by [level]
        val rnd = java.util.Random(11)
        return IntArray(size * size) {
            val base = 90 + (rnd.nextInt(60))
            val v = (base * level).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test
    fun `normalized sharpness does not move when the whole picture flickers brighter or darker`() {
        val size = 40
        val normal = HillClimbFocus.normalizedLaplacianVariance(detailPicture(size, 1.0), size)
        val dimmer = HillClimbFocus.normalizedLaplacianVariance(detailPicture(size, 0.6), size)
        val brighter = HillClimbFocus.normalizedLaplacianVariance(detailPicture(size, 1.4), size)
        assertEquals(normal, dimmer, normal * 0.04)
        assertEquals(normal, brighter, normal * 0.04)
        // while the plain measure follows the brightness squared
        val plainRatio = HillClimbFocus.laplacianVariance(detailPicture(size, 0.6), size) / HillClimbFocus.laplacianVariance(detailPicture(size, 1.0), size)
        assertTrue("plain ratio $plainRatio", plainRatio < 0.5)
    }

    @Test
    fun `normalized sharpness still tells sharp from soft`() {
        val size = 40
        val checker = IntArray(size * size) { val v = if ((it % size + it / size) % 2 == 0) 200 else 60; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val soft = IntArray(size * size) { val v = 100 + (it % size) * 2; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(HillClimbFocus.normalizedLaplacianVariance(checker, size) > 20 * HillClimbFocus.normalizedLaplacianVariance(soft, size))
    }

    @Test
    fun `normalized sharpness of a black picture is zero, not a division by zero`() {
        val size = 20
        assertEquals(0.0, HillClimbFocus.normalizedLaplacianVariance(IntArray(size * size) { 0xFF000000.toInt() }, size), 1e-9)
    }
}
