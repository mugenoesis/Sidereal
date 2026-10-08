package io.github.mugenoesis.sidereal.series

import io.github.mugenoesis.sidereal.sequence.SequenceMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class SeriesNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-08 01:15:30 UTC
    private val start = 1_791_422_130_000L

    @Test
    fun `folder names say what the series is and when it started`() {
        assertEquals("Panorama_2026-10-08_0115", SeriesNaming.folderName(SequenceMode.PANORAMA, start, utc))
        assertEquals("Timelapse_2026-10-08_0115", SeriesNaming.folderName(SequenceMode.TIMELAPSE, start, utc))
        assertEquals("Intervalometer_2026-10-08_0115", SeriesNaming.folderName(SequenceMode.INTERVALOMETER, start, utc))
        assertEquals("Darks_2026-10-08_0115", SeriesNaming.folderName(SequenceMode.DARKS, start, utc))
    }

    @Test
    fun `folder names follow the time zone`() {
        val plusOne = TimeZone.getTimeZone("GMT+01:00")
        assertEquals("Panorama_2026-10-08_0215", SeriesNaming.folderName(SequenceMode.PANORAMA, start, plusOne))
    }

    @Test
    fun `file names put the series first so a folder listing sorts by position, then the camera's own name`() {
        assertEquals(
            "Panorama_2026-10-08_0115_r1c3_DJI_0034.JPG",
            SeriesNaming.fileName("Panorama_2026-10-08_0115", "r1c3", "DJI_0034.JPG")
        )
    }

    @Test
    fun `a raw and its jpeg get the same tag and keep their own extensions`() {
        val jpg = SeriesNaming.fileName("Timelapse_x", "f0007", "DJI_0100.jpg")
        val dng = SeriesNaming.fileName("Timelapse_x", "f0007", "DJI_0100.DNG")
        assertEquals("Timelapse_x_f0007_DJI_0100.jpg", jpg)
        assertEquals("Timelapse_x_f0007_DJI_0100.DNG", dng)
    }

    @Test
    fun `frame tags are zero padded so they sort in order, wider for long runs`() {
        assertEquals("f0001", SeriesNaming.frameTag(1, 300))
        assertEquals("f0300", SeriesNaming.frameTag(300, 300))
        assertEquals("f00001", SeriesNaming.frameTag(1, 12_000))
    }

    @Test
    fun `panorama tags are one based row and column, with the stacked shot only when stacking`() {
        assertEquals("r1c3", SeriesNaming.panoTag(row = 0, col = 2, shot = 0, shotsPerNode = 1))
        assertEquals("r2c1_s2", SeriesNaming.panoTag(row = 1, col = 0, shot = 1, shotsPerNode = 3))
    }

    @Test
    fun `calibration tags count the frames`() {
        assertEquals("dark007", SeriesNaming.calibrationTag(SequenceMode.DARKS, 7, 15))
        assertEquals("bias003", SeriesNaming.calibrationTag(SequenceMode.BIAS, 3, 15))
        assertEquals("flat012", SeriesNaming.calibrationTag(SequenceMode.FLATS, 12, 15))
    }
}
