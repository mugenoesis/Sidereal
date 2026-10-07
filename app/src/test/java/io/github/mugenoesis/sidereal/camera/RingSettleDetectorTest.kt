package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingSettleDetectorTest {

    private fun detector() = RingSettleDetector(minWaitMs = 1_200, stableReads = 2, tolerance = 6, maxWaitMs = 5_000)

    @Test
    fun `a ring that is still moving is not settled`() {
        val d = detector()
        assertNull(d.onReading(0, 0))
        assertNull(d.onReading(500, 648))
        assertNull(d.onReading(1000, 324))
        assertNull(d.onReading(1500, 1670))
    }

    @Test
    fun `settles once the ring has held still for the required reads after the minimum wait`() {
        val d = detector()
        d.onReading(0, 0); d.onReading(500, 648); d.onReading(1000, 324); d.onReading(1500, 1670)
        assertNull(d.onReading(1900, 1620))      // first read at the new value
        assertEquals(1621, d.onReading(2300, 1621)) // second, within tolerance -> settled, reporting the latest value
    }

    @Test
    fun `never settles before the minimum wait, even if the ring has not moved yet`() {
        val d = detector()
        assertNull(d.onReading(0, 27))
        assertNull(d.onReading(400, 27))
        assertNull(d.onReading(800, 27))
    }

    @Test
    fun `a ring that never moved is accepted after the minimum wait - autofocus may have nothing to do`() {
        val d = detector()
        d.onReading(0, 27); d.onReading(400, 27); d.onReading(800, 27)
        assertEquals(27, d.onReading(1200, 27))
    }

    @Test
    fun `tolerance absorbs tiny jitter`() {
        val d = detector()
        d.onReading(0, 1000); d.onReading(1300, 1000)
        assertEquals(1004, d.onReading(1700, 1004))
    }

    @Test
    fun `gives up and takes the latest value at the maximum wait`() {
        val d = detector()
        var t = 0L
        var result: Int? = null
        var ring = 100
        while (t <= 6_000 && result == null) { result = d.onReading(t, ring); ring += 200; t += 400 }
        assertEquals(true, result != null)
        assertEquals(true, t - 400 <= 5_400)
    }

    @Test
    fun `a failed read does not count as stable`() {
        val d = detector()
        d.onReading(0, 500)
        assertNull(d.onReading(1300, null))
        assertNull(d.onReading(1700, 500))
        assertEquals(501, d.onReading(2100, 501))
    }

    @Test
    fun `reset starts over`() {
        val d = detector()
        d.onReading(0, 1); d.onReading(1300, 1); d.reset()
        assertNull(d.onReading(100, 1))
    }
}
