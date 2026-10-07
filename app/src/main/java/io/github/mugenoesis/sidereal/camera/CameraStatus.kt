package io.github.mugenoesis.sidereal.camera

import java.util.Locale

enum class BatteryLevel { OK, LOW, CRITICAL, UNKNOWN }

/**
 * Everything the status strip shows, flattened from the camera's pushed
 * system/storage state and the battery.
 *
 * @param cardInserted null until the camera has reported storage state
 * @param photosLeft estimated remaining stills (an estimate from free space - not an exact count)
 * @param recordSecondsLeft estimated remaining video time
 * @param isVideoMode the camera is in video mode, so recording time rather than photos is what matters
 */
data class CameraStatus(
    val batteryPercent: Int? = null,
    val cardInserted: Boolean? = null,
    val cardFull: Boolean = false,
    val cardError: Boolean = false,
    val photosLeft: Long? = null,
    val recordSecondsLeft: Int? = null,
    val isRecording: Boolean = false,
    val recordElapsedSec: Int = 0,
    val isVideoMode: Boolean = false
)

object CameraStatusFormat {

    private const val LOW_PHOTOS = 50L
    private const val LOW_RECORD_SECONDS = 300

    fun photosLeft(count: Long): String = when {
        count >= 100_000 -> "${count / 1000}k"
        count >= 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
        else -> String.format(Locale.US, "%,d", count)
    }

    fun timeLeft(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    fun elapsed(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun batteryLevel(percent: Int?): BatteryLevel = when {
        percent == null -> BatteryLevel.UNKNOWN
        percent >= 50 -> BatteryLevel.OK
        percent >= 20 -> BatteryLevel.LOW
        else -> BatteryLevel.CRITICAL
    }

    fun battery(percent: Int?): String = if (percent == null) "--" else "$percent%"

    fun card(status: CameraStatus): String = when {
        status.cardInserted == false -> "No card"
        status.cardError -> "Card error"
        status.cardFull -> "Card full"
        status.isVideoMode -> {
            val left = status.recordSecondsLeft?.let { "${timeLeft(it)} left" }
            when {
                status.isRecording && left != null -> "REC ${elapsed(status.recordElapsedSec)} · $left"
                status.isRecording -> "REC ${elapsed(status.recordElapsedSec)}"
                else -> left ?: "--"
            }
        }
        else -> status.photosLeft?.let { "${photosLeft(it)} left" } ?: "--"
    }

    fun cardWarning(status: CameraStatus): Boolean = when {
        status.cardFull || status.cardError || status.cardInserted == false -> true
        status.isVideoMode -> (status.recordSecondsLeft ?: Int.MAX_VALUE) < LOW_RECORD_SECONDS
        else -> (status.photosLeft ?: Long.MAX_VALUE) < LOW_PHOTOS
    }
}
