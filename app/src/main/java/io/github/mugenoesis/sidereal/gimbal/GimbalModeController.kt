package io.github.mugenoesis.sidereal.gimbal

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.tracking.FaceTrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

enum class GimbalMode {
    MANUAL,
    TIMED_MOVE,
    FACE_TRACK
}

/**
 * Single owner of "which control scheme currently has the wheel."
 *
 * Only one of ManualGimbalController / TimedMoveController /
 * FaceTrackingController should ever be sending rotate() commands at a
 * time - this class enforces that by stopping whichever mode was active
 * before starting a new one.
 */
class GimbalModeController(
    private val manualController: ManualGimbalController,
    private val timedMoveController: TimedMoveController,
    private val faceTrackingController: FaceTrackingController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var currentMode: GimbalMode = GimbalMode.MANUAL
        private set

    init {
        // currentMode defaults to MANUAL, but switchTo(MANUAL) short-circuits
        // when mode == currentMode (see below) - without this, that default
        // was never paired with an actual manualController.activate() call,
        // so manual gimbal control silently did nothing until the user
        // switched to some other mode and back.
        manualController.activate()
    }

    fun switchTo(mode: GimbalMode) {
        val plan = GimbalModeSwitchLogic.planSwitch(currentMode, mode) ?: return

        Log.i("GimbalModeController", "Switching gimbal mode: $currentMode -> $mode")
        applyStop(plan.stop)
        currentMode = plan.newMode
        applyStart(plan.start)
    }

    private fun applyStop(action: GimbalModeSwitchLogic.StopAction) {
        when (action) {
            GimbalModeSwitchLogic.StopAction.STOP_MANUAL -> manualController.deactivate()
            GimbalModeSwitchLogic.StopAction.STOP_TIMED_MOVE -> timedMoveController.cancel()
            GimbalModeSwitchLogic.StopAction.STOP_FACE_TRACK -> faceTrackingController.disarm()
            GimbalModeSwitchLogic.StopAction.NONE -> {}
        }
    }

    private fun applyStart(action: GimbalModeSwitchLogic.StartAction) {
        when (action) {
            GimbalModeSwitchLogic.StartAction.START_MANUAL -> manualController.activate()
            GimbalModeSwitchLogic.StartAction.START_FACE_TRACK -> faceTrackingController.arm()
            GimbalModeSwitchLogic.StartAction.NONE -> {}
        }
    }

    fun startTimedMove(durationMillis: Long) {
        if (currentMode != GimbalMode.TIMED_MOVE) {
            Log.w("GimbalModeController", "Not in TIMED_MOVE mode, ignoring start request")
            return
        }
        val gimbal = DJIConnectionManager.gimbal ?: run {
            Log.w("GimbalModeController", "No gimbal connected, ignoring timed move start")
            return
        }
        timedMoveController.start(gimbal, durationMillis, scope)
    }

    /**
     * One-shot recall move to captured point A/B - only meaningful in
     * TIMED_MOVE mode (the joystick is disarmed there, so there's nothing
     * else the A/B buttons could usefully do besides preview a point;
     * MainActivity's click handlers call captureA()/captureB() directly
     * instead while in MANUAL mode, the only mode a new point can actually
     * be positioned in). Mirrors startTimedMove()'s mode/gimbal guards.
     */
    fun previewPointA() = previewPoint(timedMoveController::previewA)
    fun previewPointB() = previewPoint(timedMoveController::previewB)

    private fun previewPoint(preview: (dji.sdk.gimbal.Gimbal) -> Unit) {
        if (currentMode != GimbalMode.TIMED_MOVE) {
            Log.w("GimbalModeController", "Not in TIMED_MOVE mode, ignoring point preview request")
            return
        }
        val gimbal = DJIConnectionManager.gimbal ?: run {
            Log.w("GimbalModeController", "No gimbal connected, ignoring point preview request")
            return
        }
        preview(gimbal)
    }
}
