package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRingStepperTest {

    @Test
    fun `a step is one percent of the ring's range`() {
        assertEquals(1707 + 20, FocusRingStepper.next(current = 1707, direction = +1, upperBound = 2035))
        assertEquals(1707 - 20, FocusRingStepper.next(current = 1707, direction = -1, upperBound = 2035))
    }

    @Test
    fun `a tiny range still moves by at least one`() {
        assertEquals(51, FocusRingStepper.next(50, +1, 60))
    }

    @Test
    fun `it never leaves the ring's range`() {
        assertEquals(2035, FocusRingStepper.next(2030, +1, 2035))
        assertEquals(0, FocusRingStepper.next(5, -1, 2035))
    }

    @Test
    fun `a current value outside the range is pulled back in`() {
        assertEquals(2035, FocusRingStepper.next(9_999, -1, 2035).coerceAtMost(2035))
    }
}
