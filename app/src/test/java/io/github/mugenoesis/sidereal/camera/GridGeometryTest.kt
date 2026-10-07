package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeometryTest {

    private val w = 1200f
    private val h = 900f

    @Test
    fun `off draws nothing`() {
        assertTrue(GridGeometry.lines(GridMode.OFF, w, h).isEmpty())
    }

    @Test
    fun `thirds is two vertical and two horizontal lines at one and two thirds`() {
        val lines = GridGeometry.lines(GridMode.THIRDS, w, h)
        assertEquals(4, lines.size)
        val vertical = lines.filter { it.x1 == it.x2 }.map { it.x1 }.sorted()
        val horizontal = lines.filter { it.y1 == it.y2 }.map { it.y1 }.sorted()
        assertEquals(listOf(400f, 800f), vertical)
        assertEquals(listOf(300f, 600f), horizontal)
    }

    @Test
    fun `vertical lines span the whole height and horizontal ones the whole width`() {
        for (line in GridGeometry.lines(GridMode.THIRDS, w, h)) {
            if (line.x1 == line.x2) { assertEquals(0f, line.y1, 0f); assertEquals(h, line.y2, 0f) }
            else { assertEquals(0f, line.x1, 0f); assertEquals(w, line.x2, 0f) }
        }
    }

    @Test
    fun `golden ratio lines sit at 38 and 62 percent`() {
        val lines = GridGeometry.lines(GridMode.GOLDEN, w, h)
        assertEquals(4, lines.size)
        val vertical = lines.filter { it.x1 == it.x2 }.map { it.x1 }.sorted()
        assertEquals(0.382f * w, vertical[0], 1f)
        assertEquals(0.618f * w, vertical[1], 1f)
    }

    @Test
    fun `center is a single cross through the middle`() {
        val lines = GridGeometry.lines(GridMode.CENTER, w, h)
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.x1 == 600f && it.x2 == 600f })
        assertTrue(lines.any { it.y1 == 450f && it.y2 == 450f })
    }

    @Test
    fun `modes cycle off thirds golden center and back`() {
        assertEquals(GridMode.THIRDS, GridMode.OFF.next())
        assertEquals(GridMode.GOLDEN, GridMode.THIRDS.next())
        assertEquals(GridMode.CENTER, GridMode.GOLDEN.next())
        assertEquals(GridMode.OFF, GridMode.CENTER.next())
    }

    @Test
    fun `every mode has a label`() {
        GridMode.values().forEach { assertTrue(it.label.isNotBlank()) }
    }
}
