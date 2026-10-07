package io.github.mugenoesis.sidereal.sync

/** One audio sample's fate when the audio track is shifted: its new timestamp, and whether it survives. */
data class ShiftedSample(val originalTimeUs: Long, val newTimeUs: Long, val keep: Boolean)

/**
 * The timestamp arithmetic of merging phone audio into the camera's video at an offset. A muxed file can't
 * have negative timestamps, so when the audio has to come forward the part that would land before zero is
 * dropped; when it is delayed everything is kept and simply starts later.
 */
object AudioShift {

    fun apply(sampleTimesUs: List<Long>, offsetUs: Long): List<ShiftedSample> =
        sampleTimesUs.map { t ->
            val shifted = t + offsetUs
            ShiftedSample(t, shifted, keep = shifted >= 0)
        }

    /** Where the shifted audio ends (the last sample's start), or zero for no audio. */
    fun endTimeUs(sampleTimesUs: List<Long>, offsetUs: Long): Long =
        if (sampleTimesUs.isEmpty()) 0L else sampleTimesUs.last() + offsetUs
}
