package io.github.mugenoesis.sidereal.series

/** How the finished run went, as the post run needs it. [spanMs] is from the start to the last photo being taken. */
data class RunSummary(val startedAtMs: Long, val spanMs: Long, val capturesDone: Int, val capturesPlanned: Int)

/** What the tray shows while the photos are being brought in and processed, e.g. "Downloading" 7 of 24. */
data class AfterRunProgress(val stage: String, val done: Int, val total: Int)

/**
 * Work that follows a successful sequence: bring the photos off the camera into a labelled folder, then optionally
 * stitch or encode them. Returns a one-line outcome for the user ("Saved 24 photos to ..."), or null when there is
 * nothing to say.
 */
fun interface PostRun {
    suspend fun run(plan: SeriesPlan, run: RunSummary, report: (AfterRunProgress) -> Unit): String?
}
