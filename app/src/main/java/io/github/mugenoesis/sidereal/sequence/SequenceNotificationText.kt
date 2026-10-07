package io.github.mugenoesis.sidereal.sequence

/** What the ongoing-sequence notification says - kept pure so the wording for every state is tested. */
data class SequenceNotificationContent(val title: String, val text: String, val percent: Int, val indeterminate: Boolean)

object SequenceNotificationText {

    fun of(modeLabel: String, progress: SequenceProgress): SequenceNotificationContent {
        val total = progress.capturesTotal
        val done = progress.capturesDone
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        val frames = if (total > 0) "$done/$total frames" else "$done frames"
        val indeterminate = total <= 0

        return when (val state = progress.state) {
            is SequenceState.AwaitingUser ->
                SequenceNotificationContent("$modeLabel needs you", "${state.message} · $frames", percent, indeterminate)
            is SequenceState.Done ->
                SequenceNotificationContent("$modeLabel finished", frames, 100, false)
            is SequenceState.Failed ->
                SequenceNotificationContent("$modeLabel stopped", "${state.reason} · $frames", percent, false)
            is SequenceState.Cancelled ->
                SequenceNotificationContent("$modeLabel cancelled", frames, percent, false)
            else -> {
                val parts = ArrayList<String>()
                parts += frames
                if (progress.waitingForCamera) parts += "Waiting for the camera"
                if (progress.exposureSummary.isNotEmpty()) parts += progress.exposureSummary
                SequenceNotificationContent("$modeLabel running", parts.joinToString(" · "), percent, indeterminate)
            }
        }
    }
}
