package io.github.mugenoesis.sidereal.camera

/**
 * Pure "what should pressing the shutter button do right now" decision,
 * extracted from CameraModeController.triggerShutter() so it's
 * unit-testable without a live SystemState bundle (confirmed unsafe to
 * construct in a plain JVM unit test, same as every other concrete DJI SDK
 * class - see CameraGateway's doc comment) or DJISDKManager (confirmed to
 * hang, not throw, when touched from a JVM unit test).
 *
 * Takes the camera mode's plain .name rather than the live
 * SettingsDefinitions.CameraMode enum for the same reason every other
 * *ByName entry point in this codebase does.
 */
object ShutterLogic {

    sealed class Action {
        object StartShootPhoto : Action()
        object StartRecordVideo : Action()
        object StopRecordVideo : Action()
        data class Ignored(val modeName: String) : Action()
    }

    /**
     * [isRecordingIntent] is what the app last asked the camera to do, not
     * the camera's own pushed recording state - see
     * CameraModeController.isRecordingIntent's doc comment for why.
     */
    fun decideAction(modeName: String, isRecordingIntent: Boolean): Action = when (modeName) {
        "RECORD_VIDEO" -> if (isRecordingIntent) Action.StopRecordVideo else Action.StartRecordVideo
        "SHOOT_PHOTO" -> Action.StartShootPhoto
        else -> Action.Ignored(modeName)
    }

    /**
     * Shutter-open time in seconds for a ShutterSpeed enum name, exact (1/8000 stays 0.000125), or null for
     * AUTO/UNKNOWN (no fixed duration). Names are `SHUTTER_SPEED_<x>` for whole/decimal seconds (`3`, `3_DOT_2`) and
     * `SHUTTER_SPEED_1_<d>` for a 1/d fraction (`1_100`, `1_2_DOT_5`).
     */
    fun exposureSeconds(speedName: String): Double? {
        val raw = speedName.removePrefix("SHUTTER_SPEED_").replace("_DOT_", ".")
        val parts = raw.split("_")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val num = parts[0].toDoubleOrNull()
                val den = parts[1].toDoubleOrNull()
                if (num != null && den != null && den > 0) num / den else null
            }
            else -> null
        }
    }

    /** [exposureSeconds] in whole milliseconds, never below 1 ms - for waiting on a shot, not for exposure maths. */
    fun exposureMs(speedName: String): Long? = exposureSeconds(speedName)?.let { Math.round(it * 1000).coerceAtLeast(1L) }
}
