package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailTest {
    @Test
    fun `thumbnails keep the aspect ratio and fit the longest side`() {
        assertEquals(240 to 135, Thumbnail.fit(1920, 1080, 240))
        assertEquals(135 to 240, Thumbnail.fit(1080, 1920, 240))
        assertEquals(240 to 180, Thumbnail.fit(1200, 900, 240))
    }

    @Test
    fun `a source smaller than the target is not enlarged`() {
        assertEquals(100 to 50, Thumbnail.fit(100, 50, 240))
    }

    @Test
    fun `a degenerate source gets a safe size`() {
        assertEquals(1 to 1, Thumbnail.fit(0, 0, 240))
    }
}
