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

    // --- emaUpdate ---

    @Test
    fun `emaUpdate returns the raw value as-is on the first sample`() {
        assertEquals(42.0, HillClimbFocus.emaUpdate(previous = null, raw = 42.0, weight = 0.35), 1e-9)
    }

    @Test
    fun `emaUpdate blends toward the raw value by the given weight`() {
        // weight 0.5 halfway between previous and raw
        assertEquals(75.0, HillClimbFocus.emaUpdate(previous = 50.0, raw = 100.0, weight = 0.5), 1e-9)
    }

    @Test
    fun `emaUpdate with a low weight favors the previous value`() {
        val result = HillClimbFocus.emaUpdate(previous = 100.0, raw = 0.0, weight = 0.1)
        assertTrue("expected a value close to the previous 100.0, got $result", result > 85.0)
    }

    // --- stepSize ---

    @Test
    fun `stepSize scales with bound`() {
        assertEquals(50, HillClimbFocus.stepSize(bound = 2000, fraction = 0.025, minStep = 3))
    }

    @Test
    fun `stepSize never goes below minStep`() {
        assertEquals(3, HillClimbFocus.stepSize(bound = 10, fraction = 0.025, minStep = 3))
    }

    // --- nextClimbState ---

    @Test
    fun `nextClimbState keeps direction and step when score improves`() {
        val state = HillClimbFocus.ClimbState(direction = 1, step = 40)
        val next = HillClimbFocus.nextClimbState(state, prevScore = 100.0, newScore = 150.0, minStep = 3)
        assertEquals(state, next)
    }

    @Test
    fun `nextClimbState reverses direction and halves step when score worsens`() {
        val state = HillClimbFocus.ClimbState(direction = 1, step = 40)
        val next = HillClimbFocus.nextClimbState(state, prevScore = 150.0, newScore = 100.0, minStep = 3)
        assertEquals(-1, next.direction)
        assertEquals(20, next.step)
    }

    @Test
    fun `nextClimbState never halves the step below minStep`() {
        val state = HillClimbFocus.ClimbState(direction = 1, step = 4)
        val next = HillClimbFocus.nextClimbState(state, prevScore = 150.0, newScore = 100.0, minStep = 3)
        assertEquals(3, next.step)
    }

    @Test
    fun `nextClimbState never reverses on the very first sample (no prevScore)`() {
        val state = HillClimbFocus.ClimbState(direction = 1, step = 40)
        val next = HillClimbFocus.nextClimbState(state, prevScore = null, newScore = 1.0, minStep = 3)
        assertEquals(state, next)
    }

    // --- nextPosition ---

    @Test
    fun `nextPosition applies a signed step`() {
        assertEquals(1540, HillClimbFocus.nextPosition(currentPos = 1500, direction = 1, step = 40, bound = 2035, anchor = 1500, leash = 300))
        assertEquals(1460, HillClimbFocus.nextPosition(currentPos = 1500, direction = -1, step = 40, bound = 2035, anchor = 1500, leash = 300))
    }

    @Test
    fun `nextPosition clamps to the ring's own 0 and bound limits`() {
        assertEquals(2035, HillClimbFocus.nextPosition(currentPos = 2020, direction = 1, step = 40, bound = 2035, anchor = 2020, leash = 300))
        assertEquals(0, HillClimbFocus.nextPosition(currentPos = 10, direction = -1, step = 40, bound = 2035, anchor = 10, leash = 300))
    }

    @Test
    fun `nextPosition clamps to the leash around the anchor even within the ring's own bounds`() {
        // Regression test for the "video keeps zooming in" finding: an
        // unleashed search racking far from where it started visibly
        // changed framing on real hardware. A step that would land outside
        // the leash must be clamped to the leash edge instead.
        val result = HillClimbFocus.nextPosition(currentPos = 1790, direction = 1, step = 40, bound = 2035, anchor = 1500, leash = 300)
        assertEquals(1800, result) // anchor(1500) + leash(300), not 1830
    }

    @Test
    fun `nextPosition leash never extends past the ring's own bounds`() {
        val result = HillClimbFocus.nextPosition(currentPos = 100, direction = -1, step = 500, bound = 2035, anchor = 100, leash = 300)
        assertEquals(0, result) // anchor - leash would be -200, clamped to the ring minimum
    }

    // --- shouldReanchorLeash ---

    @Test
    fun `shouldReanchorLeash is false below the threshold`() {
        assertEquals(false, HillClimbFocus.shouldReanchorLeash(consecutiveStuckSamples = 1, threshold = 2))
    }

    @Test
    fun `shouldReanchorLeash is true at and above the threshold`() {
        assertEquals(true, HillClimbFocus.shouldReanchorLeash(consecutiveStuckSamples = 2, threshold = 2))
        assertEquals(true, HillClimbFocus.shouldReanchorLeash(consecutiveStuckSamples = 5, threshold = 2))
    }

    @Test
    fun `shouldReanchorLeash is false at zero (never stuck)`() {
        assertEquals(false, HillClimbFocus.shouldReanchorLeash(consecutiveStuckSamples = 0, threshold = 2))
    }
}
