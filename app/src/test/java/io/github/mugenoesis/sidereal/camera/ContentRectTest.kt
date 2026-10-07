package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRectTest {

    @Test
    fun `a 4 by 3 picture inside a 16 by 9 view is pillarboxed and centred`() {
        // The real preview view on the test phone: 1637 x 921.
        val r = ContentRect.fit(1637f, 921f, 4f / 3f)
        assertEquals(921f, r.height, 0.5f)
        assertEquals(1228f, r.width, 1f)
        assertEquals((1637f - r.width) / 2f, r.left, 0.5f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun `a 16 by 9 picture in a 16 by 9 view fills it`() {
        val r = ContentRect.fit(1600f, 900f, 16f / 9f)
        assertEquals(0f, r.left, 0.5f)
        assertEquals(1600f, r.width, 0.5f)
        assertEquals(900f, r.height, 0.5f)
    }

    @Test
    fun `a wider picture than the view is letterboxed top and bottom`() {
        val r = ContentRect.fit(1000f, 1000f, 2f)
        assertEquals(1000f, r.width, 0.5f)
        assertEquals(500f, r.height, 0.5f)
        assertEquals(250f, r.top, 0.5f)
    }

    @Test
    fun `aspect ratio names map to numbers, defaulting to 16 by 9`() {
        assertEquals(4f / 3f, ContentRect.aspectOf("RATIO_4_3"), 1e-4f)
        assertEquals(3f / 2f, ContentRect.aspectOf("RATIO_3_2"), 1e-4f)
        assertEquals(16f / 9f, ContentRect.aspectOf("RATIO_16_9"), 1e-4f)
        assertEquals(16f / 9f, ContentRect.aspectOf(null), 1e-4f)
        assertEquals(16f / 9f, ContentRect.aspectOf("UNKNOWN"), 1e-4f)
    }

    @Test
    fun `video mode always uses the full 16 by 9 frame whatever the photo setting is`() {
        assertEquals(16f / 9f, ContentRect.aspectFor(isVideoMode = true, photoRatioName = "RATIO_4_3"), 1e-4f)
        assertEquals(4f / 3f, ContentRect.aspectFor(isVideoMode = false, photoRatioName = "RATIO_4_3"), 1e-4f)
    }

    @Test
    fun `grid lines can be offset into the content rect`() {
        val r = ContentRect.fit(1600f, 900f, 4f / 3f)
        val lines = GridGeometry.lines(GridMode.CENTER, r.width, r.height).map { it.offset(r.left, r.top) }
        val vertical = lines.first { it.x1 == it.x2 }
        assertEquals(800f, vertical.x1, 0.5f)
    }
}
