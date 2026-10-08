package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaOrderingTest {

    private data class F(val name: String, val time: Long)

    private fun sorted(vararg files: F) =
        MediaOrdering.newestFirst(files.toList(), { it.time }, { it.name }).map { it.name }

    @Test
    fun `newest capture time comes first`() {
        assertEquals(
            listOf("C", "B", "A"),
            sorted(F("A", 100), F("B", 200), F("C", 300)),
        )
    }

    @Test
    fun `files with the same time fall back to the highest file name first`() {
        assertEquals(
            listOf("DJI_0012.JPG", "DJI_0011.JPG", "DJI_0010.JPG"),
            sorted(F("DJI_0010.JPG", 5), F("DJI_0012.JPG", 5), F("DJI_0011.JPG", 5)),
        )
    }

    @Test
    fun `a camera with an unset clock still lists the highest numbered file first`() {
        assertEquals(
            listOf("DJI_0003.MP4", "DJI_0002.JPG", "DJI_0001.JPG"),
            sorted(F("DJI_0001.JPG", 0), F("DJI_0002.JPG", 0), F("DJI_0003.MP4", 0)),
        )
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<String>(), sorted())
    }
}
