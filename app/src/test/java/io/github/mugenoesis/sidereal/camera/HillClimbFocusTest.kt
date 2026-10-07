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
}
