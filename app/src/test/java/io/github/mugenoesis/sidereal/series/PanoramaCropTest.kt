package io.github.mugenoesis.sidereal.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaCropTest {

    private fun mask(w: Int, h: Int, covered: (Int, Int) -> Boolean) = BooleanArray(w * h) { covered(it % w, it / w) }

    @Test
    fun `a fully covered picture is kept whole`() {
        val r = PanoramaCrop.largestCovered(mask(20, 10) { _, _ -> true }, 20, 10)
        assertEquals(CropRect(0, 0, 20, 10), r)
    }

    @Test
    fun `empty borders are trimmed away`() {
        val r = PanoramaCrop.largestCovered(mask(20, 10) { x, y -> x in 3..16 && y in 2..7 }, 20, 10)
        assertEquals(CropRect(3, 2, 17, 8), r)
    }

    @Test
    fun `bowed top and bottom edges are cut back to the straight part`() {
        // covered rows shrink towards the middle columns' extremes: a lens shape clipped top and bottom
        val w = 40
        val h = 20
        val m = mask(w, h) { x, y ->
            val bow = ((x - w / 2) * (x - w / 2)) / 100 // 0..4 rows cut at the sides
            y >= 2 + bow && y < h - 2 - bow
        }
        val r = PanoramaCrop.largestCovered(m, w, h)
        for (y in r.top until r.bottom) for (x in r.left until r.right) assertTrue("($x,$y) uncovered", m[y * w + x])
        assertTrue("kept something sizeable: $r", (r.right - r.left) * (r.bottom - r.top) > w * h / 3)
    }

    @Test
    fun `a stray uncovered speck does not shrink the crop`() {
        val w = 100
        val h = 60
        val m = mask(w, h) { x, y -> !(x == 50 && y == 30) }
        val r = PanoramaCrop.largestCovered(m, w, h, tolerance = 0.005)
        assertEquals(CropRect(0, 0, w, h), r)
    }

    @Test
    fun `nothing covered gives an empty rectangle`() {
        val r = PanoramaCrop.largestCovered(BooleanArray(12), 4, 3)
        assertEquals(0, (r.right - r.left) * (r.bottom - r.top))
    }
}
