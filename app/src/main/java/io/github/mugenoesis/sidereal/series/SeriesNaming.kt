package io.github.mugenoesis.sidereal.series

import io.github.mugenoesis.sidereal.sequence.SequenceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Names for the files a finished sequence leaves on the phone, chosen so that a folder listing tells the story on its
 * own: the folder says what was shot and when, each file says where in the series it belongs, and the camera's own
 * name stays on the end so a RAW and its JPEG (same tag, same camera number) can always be matched back to the card.
 *
 *   Pictures/Sidereal/Panorama_2026-10-08_0115/Panorama_2026-10-08_0115_r2c3_DJI_0034.JPG
 */
object SeriesNaming {

    fun folderName(mode: SequenceMode, startMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).apply { this.timeZone = timeZone }
        return "${mode.label}_${format.format(Date(startMs))}"
    }

    fun fileName(folder: String, tag: String, cameraFileName: String): String = "${folder}_${tag}_$cameraFileName"

    /** "f0042": zero padded to at least four digits (more for very long runs) so a listing sorts in shooting order. */
    fun frameTag(frame: Int, total: Int): String {
        val width = maxOf(4, total.toString().length)
        return "f" + frame.toString().padStart(width, '0')
    }

    /** "r2c3" (row 2, column 3, counting from 1), plus "_s2" for the second of several stacked shots at that spot. */
    fun panoTag(row: Int, col: Int, shot: Int, shotsPerNode: Int): String =
        "r${row + 1}c${col + 1}" + if (shotsPerNode > 1) "_s${shot + 1}" else ""

    fun calibrationTag(mode: SequenceMode, frame: Int, total: Int): String {
        val prefix = when (mode) {
            SequenceMode.DARKS -> "dark"
            SequenceMode.BIAS -> "bias"
            SequenceMode.FLATS -> "flat"
            else -> "f"
        }
        return prefix + frame.toString().padStart(maxOf(3, total.toString().length), '0')
    }
}
