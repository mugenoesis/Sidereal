package io.github.mugenoesis.sidereal.series

import java.io.File
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

/** The camera's SD card, as the series code sees it. The real one switches the camera into playback mode and back. */
interface CardSource {
    /** Photos on the card, or null if it could not be read. */
    suspend fun listPhotos(): List<CameraFile>?

    /** Downloads [file] into a temporary file, or null on failure. The caller deletes it. */
    suspend fun fetch(file: CameraFile): File?

    /** Leaves playback mode - always called, whatever happened. */
    fun close()
}

/** Where kept photos end up (the phone's gallery, under Sidereal/[folder]). */
interface Gallery {
    fun save(file: File, displayName: String, mediaType: String, folder: String): Boolean
}

/** Turns the downloaded JPEGs into something else - a panorama or a video. */
interface FrameProcessor {
    /** True if it needs every frame file to still exist at [finish] (a stitch); false if it has used [onFrame]'s file by the time that returns (a video). */
    val retainsFiles: Boolean

    suspend fun onFrame(index: Int, tag: String, jpeg: File)

    /** Produces the result into [folder]; returns a short description for the user, or null. [frameFiles] are the retained files, in shooting order. */
    suspend fun finish(folder: String, frameFiles: List<File>): String?
}

/**
 * Everything that happens after a sequence ends: find this run's photos on the card, bring them across one at a time
 * into a folder named for the series, and hand the JPEGs on to a [FrameProcessor] if a panorama or video was asked for.
 * One bad file never stops the rest - it is retried once, then counted in the summary line.
 */
class SeriesPostRunner(
    private val card: CardSource,
    private val gallery: Gallery,
    private val processorFor: (SeriesPlan, RunSummary, String) -> FrameProcessor?,
    private val timeZone: TimeZone = TimeZone.getDefault()
) : PostRun {

    override suspend fun run(plan: SeriesPlan, run: RunSummary, report: (AfterRunProgress) -> Unit): String? {
        if (!plan.needsDownload) return null
        val retained = ArrayList<File>()
        try {
            report(AfterRunProgress("Reading the card", 0, 0))
            val onCard = card.listPhotos()
                ?: return "Couldn't read the camera's card, so the photos are still on it - open the media browser to get them"
            val selection = SeriesSelector.select(onCard, run.capturesDone, run.spanMs)
            if (selection.captures.isEmpty()) return "Couldn't find this run's photos on the card - open the media browser to get them"

            val folder = SeriesNaming.folderName(plan.mode, run.startedAtMs, timeZone)
            val processor = if (plan.stitch || plan.makeVideo) processorFor(plan, run, folder) else null
            val total = selection.captures.sumOf { it.files.size }
            var done = 0
            var saved = 0
            var failed = 0
            var jpegs = 0

            selection.captures.forEachIndexed { index, capture ->
                val tag = if (selection.exact) plan.tags.getOrElse(index) { numbered(index) } else numbered(index)
                for (file in capture.files) {
                    report(AfterRunProgress("Downloading", done, total))
                    val temp = fetchWithRetry(file)
                    done++
                    if (temp == null) { failed++; continue }
                    var keepTemp = false
                    try {
                        if (plan.keepFrames) {
                            if (gallery.save(temp, SeriesNaming.fileName(folder, tag, file.name), file.type, folder)) saved++ else failed++
                        }
                        if (processor != null && file.type == "JPEG") {
                            jpegs++
                            processor.onFrame(index, tag, temp)
                            if (processor.retainsFiles) { retained += temp; keepTemp = true }
                        }
                    } finally {
                        if (!keepTemp) temp.delete()
                    }
                }
            }
            report(AfterRunProgress("Downloading", total, total))

            val parts = ArrayList<String>()
            if (plan.keepFrames && saved > 0) parts += "Saved $saved photo${if (saved == 1) "" else "s"} to Pictures/Sidereal/$folder"
            if (processor != null) {
                if (jpegs == 0) {
                    parts += "No JPEG frames to make it from (only RAW was saved) - turn JPEG on in the camera's photo format"
                } else {
                    report(AfterRunProgress("Finishing", 0, 0))
                    processor.finish(folder, retained)?.let { parts += it }
                }
            }
            if (failed > 0) parts += "$failed failed"
            if (!selection.exact && plan.keepFrames) parts += "files numbered, not tagged (the card didn't match the plan)"
            return parts.joinToString(" · ").ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } finally {
            retained.forEach { it.delete() }
            card.close()
        }
    }

    private suspend fun fetchWithRetry(file: CameraFile): File? = card.fetch(file) ?: card.fetch(file)

    private fun numbered(index: Int) = "n" + (index + 1).toString().padStart(4, '0')
}
