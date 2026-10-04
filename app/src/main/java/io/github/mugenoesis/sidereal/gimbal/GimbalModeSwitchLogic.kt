package io.github.mugenoesis.sidereal.gimbal

/**
 * Pure "what should switching to a new gimbal control mode do" decision,
 * extracted from GimbalModeController.switchTo()/stopCurrentMode() so it's
 * unit-testable without constructing real ManualGimbalController/
 * TimedMoveController/FaceTrackingController instances - mirrors
 * ShutterLogic's role for CameraModeController.
 */
object GimbalModeSwitchLogic {

    enum class StopAction { NONE, STOP_MANUAL, STOP_TIMED_MOVE, STOP_FACE_TRACK }
    enum class StartAction { NONE, START_MANUAL, START_FACE_TRACK }

    data class SwitchPlan(val stop: StopAction, val start: StartAction, val newMode: GimbalMode)

    /** Null when [requestedMode] is already the current mode - switchTo() should short-circuit and do nothing. */
    fun planSwitch(currentMode: GimbalMode, requestedMode: GimbalMode): SwitchPlan? {
        if (requestedMode == currentMode) return null

        val stop = when (currentMode) {
            GimbalMode.MANUAL -> StopAction.STOP_MANUAL
            GimbalMode.TIMED_MOVE -> StopAction.STOP_TIMED_MOVE
            GimbalMode.FACE_TRACK -> StopAction.STOP_FACE_TRACK
        }
        val start = when (requestedMode) {
            GimbalMode.MANUAL -> StartAction.START_MANUAL
            // Timed move is armed via UI (set A / set B / duration) and
            // explicitly started separately - becoming the "owner" here
            // doesn't itself start anything.
            GimbalMode.TIMED_MOVE -> StartAction.NONE
            GimbalMode.FACE_TRACK -> StartAction.START_FACE_TRACK
        }
        return SwitchPlan(stop, start, requestedMode)
    }
}
