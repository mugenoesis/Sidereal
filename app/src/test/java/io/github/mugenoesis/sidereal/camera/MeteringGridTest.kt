package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteringGridTest {

    @Test
    fun `maps the top-left corner to cell 0,0`() {
        assertEquals(0 to 0, MeteringGrid.normalizedToCell(0f, 0f, cols = 5, rows = 3))
    }

    @Test
    fun `maps the center to the middle cell`() {
        assertEquals(2 to 1, MeteringGrid.normalizedToCell(0.5f, 0.5f, cols = 5, rows = 3))
    }

    @Test
    fun `maps the bottom-right corner to the last cell, not one past it`() {
        // 1.0f * 5 == 5, which is out of bounds for a 0-indexed 5-column
        // grid - must clamp to column 4, not overflow.
        assertEquals(4 to 2, MeteringGrid.normalizedToCell(1f, 1f, cols = 5, rows = 3))
    }

    @Test
    fun `clamps out-of-range input instead of producing an invalid cell`() {
        assertEquals(0 to 0, MeteringGrid.normalizedToCell(-5f, -5f, cols = 5, rows = 3))
        assertEquals(4 to 2, MeteringGrid.normalizedToCell(5f, 5f, cols = 5, rows = 3))
    }
}
