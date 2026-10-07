package io.github.mugenoesis.sidereal.sync

/**
 * Saved next to each phone audio take: when the phone started recording and,
 * if the camera confirmed it, when the camera's own recording started and
 * stopped. This is all the information needed to line the audio up with the
 * video later, since the camera's clip has no audio and nothing in either file
 * says how they relate.
 *
 * Offset convention used everywhere: a positive offset means the audio is
 * DELAYED relative to the video's start (audio second 0 belongs at video
 * second `offset`); negative means the first part of the audio is cut off.
 *
 * Plain `key=value` lines rather than JSON: org.json is not available in JVM unit tests, and this is
 * a handful of scalars.
 */
data class SyncSidecar(
    val audioFileName: String,
    val audioStartEpochMs: Long,
    val cameraStartEpochMs: Long?,
    val cameraStopEpochMs: Long?,
    /** What the user dialled in by ear on top of the automatic suggestion. */
    val manualOffsetMs: Long = 0
) {
    /** The audio began this much after the camera did - delay it by that much. Zero if the camera start is unknown. */
    val suggestedOffsetMs: Long
        get() = cameraStartEpochMs?.let { audioStartEpochMs - it } ?: 0L

    val totalOffsetMs: Long
        get() = suggestedOffsetMs + manualOffsetMs

    fun serialize(): String = buildString {
        appendLine("audioFileName=$audioFileName")
        appendLine("audioStartEpochMs=$audioStartEpochMs")
        cameraStartEpochMs?.let { appendLine("cameraStartEpochMs=$it") }
        cameraStopEpochMs?.let { appendLine("cameraStopEpochMs=$it") }
        appendLine("manualOffsetMs=$manualOffsetMs")
    }

    companion object {
        const val SUFFIX = ".sync.properties"

        fun fileNameFor(audioFileName: String): String = audioFileName.substringBeforeLast('.', audioFileName) + SUFFIX

        /** Null if the text is not a complete, well-formed sidecar. */
        fun parse(text: String): SyncSidecar? {
            val values = text.lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .toMap()
            val name = values["audioFileName"]?.takeIf { it.isNotEmpty() } ?: return null
            val audioStart = values["audioStartEpochMs"]?.toLongOrNull() ?: return null

            fun optionalLong(key: String): Result<Long?> {
                val raw = values[key] ?: return Result.success(null)
                val parsed = raw.toLongOrNull() ?: return Result.failure(IllegalArgumentException(key))
                return Result.success(parsed)
            }

            val cameraStart = optionalLong("cameraStartEpochMs").getOrElse { return null }
            val cameraStop = optionalLong("cameraStopEpochMs").getOrElse { return null }
            val manual = optionalLong("manualOffsetMs").getOrElse { return null } ?: 0L
            return SyncSidecar(name, audioStart, cameraStart, cameraStop, manual)
        }
    }
}
