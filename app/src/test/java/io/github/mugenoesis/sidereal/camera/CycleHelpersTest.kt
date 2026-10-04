package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CycleHelpersTest {

    private enum class Fruit { APPLE, BANANA, CHERRY }

    @Test
    fun `stepEnum advances by delta`() {
        assertEquals(Fruit.BANANA, CycleHelpers.stepEnum(Fruit.APPLE, 1, Fruit.values()))
        assertEquals(Fruit.CHERRY, CycleHelpers.stepEnum(Fruit.BANANA, 1, Fruit.values()))
    }

    @Test
    fun `stepEnum steps backward too`() {
        assertEquals(Fruit.APPLE, CycleHelpers.stepEnum(Fruit.BANANA, -1, Fruit.values()))
    }

    @Test
    fun `stepEnum clamps at the top instead of wrapping`() {
        // This is the behavior a "+" stepper button relies on: pressing it
        // past the last real value must NOT wrap around to a sentinel or
        // back to the start - it should just stop at the last entry.
        assertEquals(Fruit.CHERRY, CycleHelpers.stepEnum(Fruit.CHERRY, 1, Fruit.values()))
    }

    @Test
    fun `stepEnum clamps at the bottom instead of wrapping`() {
        assertEquals(Fruit.APPLE, CycleHelpers.stepEnum(Fruit.APPLE, -1, Fruit.values()))
    }

    @Test
    fun `nextCycleValue seeds from the real value on first call`() {
        val (index, value) = CycleHelpers.nextCycleValue(current = null, realValue = "b", options = listOf("a", "b", "c"))
        assertEquals(2, index)
        assertEquals("c", value)
    }

    @Test
    fun `nextCycleValue seeds from -1 when the real value is not in the option list`() {
        // Mirrors a fresh camera connection reporting a value this cycle
        // doesn't know about yet - should start from the very first option.
        val (index, value) = CycleHelpers.nextCycleValue(current = null, realValue = "unknown", options = listOf("a", "b", "c"))
        assertEquals(0, index)
        assertEquals("a", value)
    }

    @Test
    fun `nextCycleValue wraps around to the start`() {
        val (index, value) = CycleHelpers.nextCycleValue(current = 2, realValue = "c", options = listOf("a", "b", "c"))
        assertEquals(0, index)
        assertEquals("a", value)
    }

    @Test
    fun `nextCycleValue keeps advancing from the independent index regardless of the real value`() {
        // This is the exact bug this pattern fixes: once a press has been
        // made, later presses must ignore a real value that never changed
        // (e.g. because the camera rejected the previous request) and keep
        // moving from the last requested index instead of recomputing the
        // same "next" forever.
        val (index1, value1) = CycleHelpers.nextCycleValue(current = null, realValue = "a", options = listOf("a", "b", "c"))
        assertEquals(1, index1)
        assertEquals("b", value1)

        // Real value is still "a" (rejected), but current=1 must be honored.
        val (index2, value2) = CycleHelpers.nextCycleValue(current = index1, realValue = "a", options = listOf("a", "b", "c"))
        assertEquals(2, index2)
        assertEquals("c", value2)
    }
}
