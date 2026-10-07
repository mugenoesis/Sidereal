package io.github.mugenoesis.sidereal.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordDetectorTest {

    private val code = listOf(
        ChordKey.UP, ChordKey.UP, ChordKey.DOWN, ChordKey.DOWN,
        ChordKey.LEFT, ChordKey.RIGHT, ChordKey.LEFT, ChordKey.RIGHT, ChordKey.B, ChordKey.A, ChordKey.START
    )

    private fun feed(d: ChordDetector, keys: List<ChordKey>, startMs: Long = 0, gapMs: Long = 200): List<Boolean> =
        keys.mapIndexed { i, k -> d.onKey(k, startMs + i * gapMs) }

    @Test
    fun `the sequence fires on its last key and not before`() {
        val results = feed(ChordDetector(), code)
        assertEquals(List(10) { false } + true, results)
    }

    @Test
    fun `it fires once - the code has to be entered again for the next time`() {
        val d = ChordDetector()
        feed(d, code)
        assertFalse(d.onKey(ChordKey.A, 5_000))
        assertTrue(feed(d, code, startMs = 6_000).last())
    }

    @Test
    fun `a wrong key part way through starts over`() {
        val d = ChordDetector()
        val broken = code.take(5) + ChordKey.A + code.drop(5)
        assertFalse(feed(d, broken).any { it })
    }

    @Test
    fun `noise before the code does not matter`() {
        val d = ChordDetector()
        val results = feed(d, listOf(ChordKey.A, ChordKey.B, ChordKey.LEFT) + code)
        assertTrue(results.last())
        assertEquals(1, results.count { it })
    }

    @Test
    fun `an extra first key still works - the sequence is just started a key late`() {
        val d = ChordDetector()
        val results = feed(d, listOf(ChordKey.UP) + code)
        assertTrue(results.last())
    }

    @Test
    fun `a long pause between keys starts over`() {
        val d = ChordDetector()
        code.take(5).forEachIndexed { i, k -> d.onKey(k, i * 200L) }
        val rest = code.drop(5).mapIndexed { i, k -> d.onKey(k, 10_000L + i * 200L) }
        assertFalse(rest.any { it })
    }

    @Test
    fun `progress shows how far through the code the player is`() {
        val d = ChordDetector()
        assertEquals(0, d.progress)
        d.onKey(ChordKey.UP, 0)
        d.onKey(ChordKey.UP, 100)
        assertEquals(2, d.progress)
        d.onKey(ChordKey.A, 200)
        assertEquals(0, d.progress)
    }

    @Test
    fun `the code is not complete without Start on the end`() {
        val d = ChordDetector()
        assertFalse(feed(d, code.dropLast(1)).any { it })
        assertTrue(d.onKey(ChordKey.START, 2_000))
    }

    @Test
    fun `keys outside the code do nothing special`() {
        val d = ChordDetector()
        assertFalse(d.onKey(ChordKey.B, 0))
        assertFalse(d.onKey(ChordKey.A, 100))
    }
}
