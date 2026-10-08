package io.github.mugenoesis.sidereal.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSelectorTest {

    private fun jpg(n: Int, t: Long) = CameraFile("DJI_%04d.jpg".format(n), "JPEG", t)
    private fun dng(n: Int, t: Long) = CameraFile("DJI_%04d.dng".format(n), "RAW_DNG", t)

    @Test
    fun `picks exactly this run's frames out of a card full of older shots, oldest first`() {
        val older = (1..50).map { jpg(it, 1_000_000L + it * 1_000L) }
        val run = (51..60).map { jpg(it, 10_000_000L + (it - 51) * 10_000L) }
        val pick = SeriesSelector.select((older + run).shuffled(), expectedCaptures = 10, runSpanMs = 90_000L)
        assertEquals((51..60).map { "DJI_%04d".format(it) }, pick.captures.map { it.baseName })
        assertTrue(pick.exact)
    }

    @Test
    fun `a raw and jpeg pair is one capture holding both files`() {
        val files = (1..4).flatMap { listOf(jpg(it, it * 10_000L), dng(it, it * 10_000L)) }
        val pick = SeriesSelector.select(files, expectedCaptures = 4, runSpanMs = 30_000L)
        assertEquals(4, pick.captures.size)
        assertTrue(pick.exact)
        assertEquals(setOf("DJI_0001.jpg", "DJI_0001.dng"), pick.captures[0].files.map { it.name }.toSet())
    }

    @Test
    fun `videos are never part of a photo series`() {
        val files = listOf(
            CameraFile("DJI_0001.mp4", "MP4", 5_000L),
            jpg(2, 10_000L), jpg(3, 20_000L)
        )
        val pick = SeriesSelector.select(files, expectedCaptures = 2, runSpanMs = 20_000L)
        assertEquals(listOf("DJI_0002", "DJI_0003"), pick.captures.map { it.baseName })
    }

    @Test
    fun `a test shot just before the run is left out when the count lets us tell`() {
        val test = jpg(1, 100_000L)
        val run = (2..4).map { jpg(it, 110_000L + it * 5_000L) }
        val pick = SeriesSelector.select(listOf(test) + run, expectedCaptures = 3, runSpanMs = 10_000L)
        assertEquals(listOf("DJI_0002", "DJI_0003", "DJI_0004"), pick.captures.map { it.baseName })
        assertFalse(pick.exact)
    }

    @Test
    fun `fewer files than captures means some did not store and the match is not exact`() {
        val run = (1..3).map { jpg(it, it * 10_000L) }
        val pick = SeriesSelector.select(run, expectedCaptures = 5, runSpanMs = 40_000L)
        assertEquals(3, pick.captures.size)
        assertFalse(pick.exact)
    }

    @Test
    fun `a camera with an unset clock falls back to the newest files by number`() {
        val files = (1..20).map { jpg(it, 0L) }
        val pick = SeriesSelector.select(files, expectedCaptures = 5, runSpanMs = 60_000L)
        assertEquals((16..20).map { "DJI_%04d".format(it) }, pick.captures.map { it.baseName })
        assertTrue(pick.exact)
    }

    @Test
    fun `nothing on the card gives an empty, inexact selection`() {
        val pick = SeriesSelector.select(emptyList(), expectedCaptures = 3, runSpanMs = 1_000L)
        assertTrue(pick.captures.isEmpty())
        assertFalse(pick.exact)
    }

    @Test
    fun `numbers beyond the digits of an older name still sort in order`() {
        val files = listOf(jpg(9, 10L), jpg(10, 20L), jpg(11, 30L))
        val pick = SeriesSelector.select(files, expectedCaptures = 3, runSpanMs = 100L)
        assertEquals(listOf("DJI_0009", "DJI_0010", "DJI_0011"), pick.captures.map { it.baseName })
    }
}
