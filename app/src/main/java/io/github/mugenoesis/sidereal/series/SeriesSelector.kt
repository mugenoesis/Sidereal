package io.github.mugenoesis.sidereal.series

/** What the series code needs to know about one file on the camera's card. [type] is `MediaFile.MediaType.name`. */
data class CameraFile(val name: String, val type: String, val timeMs: Long)

/** One shot: the JPEG and, if the camera also saved one, the RAW, which share a base name. */
data class SeriesCapture(val baseName: String, val files: List<CameraFile>) {
    val timeMs: Long get() = files.maxOf { it.timeMs }
}

/**
 * @param exact true when the number of captures found equals the number expected, so the n-th file really is the
 *   n-th planned shot and can carry that shot's tag. Otherwise (a frame the camera never stored, or a stray shot
 *   caught in the same window) the files are still saved, but numbered rather than tagged.
 */
data class SeriesSelection(val captures: List<SeriesCapture>, val exact: Boolean)

/**
 * Works out which files on the card belong to the sequence that just ran. The card has no idea what a sequence is,
 * so this goes by what we do know: the run took [runSpanMs] and its last shot is the newest photo (the download starts
 * straight after the run). Anchoring on the newest file rather than the phone's clock means it still works when the
 * camera's date is off by hours.
 */
object SeriesSelector {

    private val PHOTO_TYPES = setOf("JPEG", "RAW_DNG", "TIFF")

    fun select(files: List<CameraFile>, expectedCaptures: Int, runSpanMs: Long, slackMs: Long = 20_000L): SeriesSelection {
        val captures = files.filter { it.type in PHOTO_TYPES }
            .groupBy { baseName(it.name) }
            .map { (base, group) -> SeriesCapture(base, group.sortedBy { it.name }) }
            .sortedWith(compareBy<SeriesCapture> { it.timeMs }.thenBy { it.baseName })
        if (captures.isEmpty() || expectedCaptures <= 0) return SeriesSelection(emptyList(), false)

        val newest = captures.last().timeMs
        val clockUsable = captures.any { it.timeMs != newest }
        val inWindow = if (clockUsable) captures.filter { it.timeMs >= newest - runSpanMs - slackMs } else captures

        val chosen = if (inWindow.size >= expectedCaptures) inWindow.takeLast(expectedCaptures) else inWindow
        return SeriesSelection(chosen, exact = chosen.size == expectedCaptures && inWindow.size == chosen.size ||
            (!clockUsable && chosen.size == expectedCaptures))
    }

    fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)
}
