package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first three arrays are the camera's real pushed histograms (Zenmuse X5, 2026-10-07), captured at 1/8000 ISO 100,
 * 1/60 ISO 800 and 1/8 ISO 1600 while a screenshot of the same preview was measured: mean luma 6, 37 and 162 of 255.
 */
class HistogramModelTest {

    private fun parse(s: String) = s.split(",").map { it.trim().toShort() }.toShortArray()

    private val dark = parse("0,0,0,0,247,255,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")
    private val mid = parse("0,0,0,0,11,162,155,117,156,207,255,209,169,176,145,78,41,30,22,18,15,13,13,12,10,7,6,5,5,4,4,4,4,4,4,4,4,4,5,7,2,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0")
    private val bright = parse("0,0,0,0,0,0,10,31,47,47,50,49,39,36,37,36,30,26,23,24,24,25,25,28,31,33,35,37,40,44,46,47,50,52,53,63,73,74,73,71,78,98,101,103,106,114,131,146,129,95,56,51,48,37,38,52,54,255,1,0,0,0,0,0")

    @Test
    fun `the camera reports 64 buckets and the display has the same number`() {
        assertEquals(64, HistogramModel.display(mid)!!.size)
    }

    @Test
    fun `the tallest display bar is exactly full height`() {
        for (d in listOf(dark, mid, bright)) assertEquals(1f, HistogramModel.display(d)!!.max(), 1e-6f)
    }

    @Test
    fun `black sits at the left edge, not a few buckets in`() {
        val shown = HistogramModel.display(dark)!!
        val peak = shown.indices.maxByOrNull { shown[it] }!!
        assertTrue("black peak at $peak", peak <= 1)
    }

    @Test
    fun `the white clip spike lands at the right edge`() {
        val shown = HistogramModel.display(bright)!!
        val peak = shown.indices.maxByOrNull { shown[it] }!!
        assertTrue("white peak at $peak", peak >= 62)
    }

    @Test
    fun `mean brightness matches what the preview actually measured`() {
        assertEquals(6.0, HistogramModel.stats(dark)!!.meanLuma, 6.0)
        assertEquals(37.0, HistogramModel.stats(mid)!!.meanLuma, 6.0)
        assertEquals(162.0, HistogramModel.stats(bright)!!.meanLuma, 8.0)
    }

    @Test
    fun `clipping is reported for the blown-out frame and not for the well-exposed one`() {
        assertTrue(HistogramModel.stats(bright)!!.highlightsClipped > 0.04)
        assertTrue(HistogramModel.stats(mid)!!.highlightsClipped < 0.01)
        assertTrue(HistogramModel.stats(dark)!!.shadowsClipped > 0.9)
        assertTrue(HistogramModel.stats(bright)!!.shadowsClipped < 0.01)
    }

    @Test
    fun `no data or an all-zero frame gives nothing to draw`() {
        assertNull(HistogramModel.display(null))
        assertNull(HistogramModel.display(ShortArray(0)))
        assertNull(HistogramModel.display(ShortArray(64)))
        assertNull(HistogramModel.stats(ShortArray(64)))
    }

    @Test
    fun `a different bucket count is handled in proportion`() {
        val flat = ShortArray(256) { 10 }
        val shown = HistogramModel.display(flat)!!
        assertEquals(64, shown.size)
        assertTrue(shown.all { it > 0.9f })
    }
}
