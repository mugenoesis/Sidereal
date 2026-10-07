package io.github.mugenoesis.sidereal.sync

import java.util.Locale
import kotlin.math.abs

enum class NudgeStep(val ms: Long) { FINE(10), MEDIUM(100), COARSE(1_000) }

/** Arithmetic and wording for the audio offset (positive = audio delayed). */
object SyncOffset {

    const val LIMIT_MS = 30_000L

    fun nudge(currentMs: Long, step: NudgeStep, direction: Int): Long =
        (currentMs + step.ms * direction).coerceIn(-LIMIT_MS, LIMIT_MS)

    fun format(offsetMs: Long): String = when {
        offsetMs == 0L -> "0.00 s"
        else -> String.format(Locale.US, "%+.2f s", offsetMs / 1000.0)
    }

    fun describe(offsetMs: Long): String = when {
        offsetMs == 0L -> "audio and video start together"
        offsetMs > 0 -> "audio starts ${String.format(Locale.US, "%.2f", offsetMs / 1000.0)} s after the video"
        else -> "audio starts ${String.format(Locale.US, "%.2f", abs(offsetMs) / 1000.0)} s before the video"
    }

    fun toUs(offsetMs: Long): Long = offsetMs * 1000
}
