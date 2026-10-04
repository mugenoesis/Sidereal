package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.SettingsDefinitions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Switches the camera between SHOOT_PHOTO/RECORD_VIDEO and triggers the
 * shutter appropriately for whichever mode is currently active - take a
 * photo in SHOOT_PHOTO, start/stop recording in RECORD_VIDEO. See
 * ShutterLogic for the pure start/stop/ignore decision this delegates to.
 *
 * The record toggle is driven by [isRecordingIntent] - what THIS app last
 * asked the camera to do - not by DJIConnectionManager.cameraSystemState
 * .isRecording (the camera's own pushed real state).
 *
 * Stop is sent EXACTLY ONCE per stop press, as a courtesy - it is NOT
 * reliable on this camera/SDK combination and should not be trusted to
 * actually stop the recording. Extensive real-hardware testing ruled out
 * every software explanation tried: a retry loop re-sending the command
 * (removed - made no difference either way), storage/error/overheating
 * state (all clean throughout), staleness of the phone's WiFi session to
 * the camera (bounced to a fresh connection - no difference), and the DJI
 * SDK version itself (every version old enough to predate 4.16.4 fails to
 * even launch on Android 12 due to a DJI-side app-hardening incompatibility,
 * so no older build could be tested here). Confirmed reproducible on a
 * second, independent phone/Android version (an Android 9 device),
 * ruling out anything specific to one device. isRecording only
 * ever flips false when the physical stop button on the camera itself is
 * pressed. Until/unless a real fix is found, stopping recording is a
 * physical-button-only operation - this call is left in only in case it
 * happens to work on a different unit/firmware, not because it's expected
 * to here.
 *
 * Uses the lower-level KeyManager.performAction(CameraKey.STOP_RECORD_VIDEO)
 * path rather than the Camera component's stopRecordVideo() - both were
 * tested (fired together) and behaved identically, so this is not expected
 * to change the outcome, but it's the simpler/more direct of the two. See
 * CameraGateway.stopRecordVideo's doc comment for why that call lives in
 * the gateway rather than directly in this controller.
 */
class CameraModeController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "CameraModeController"
    }

    // What this app last asked the camera to do - drives the toggle
    // decision and the button's red/white state. Starts false: a fresh
    // app launch is essentially never mid-recording from a prior session,
    // and DJI's own reference sample makes the same assumption (a UI
    // toggle button defaulting unchecked).
    private val _isRecordingIntent = MutableStateFlow(false)
    val isRecordingIntent: StateFlow<Boolean> = _isRecordingIntent

    fun setMode(mode: SettingsDefinitions.CameraMode) = setModeByName(mode.name)

    internal fun setModeByName(modeName: String) {
        gateway.setCameraMode(modeName) { error ->
            if (error != null) {
                Log.w(TAG, "setMode($modeName) failed: $error")
            }
        }
    }

    fun triggerShutter() {
        val state = DJIConnectionManager.cameraSystemState.value ?: return
        triggerShutterForModeName(state.mode.name, state.currentVideoRecordingTimeInSeconds)
    }

    internal fun triggerShutterForModeName(modeName: String, recordingTimeSec: Int = 0) {
        when (val action = ShutterLogic.decideAction(modeName, _isRecordingIntent.value)) {
            is ShutterLogic.Action.StartShootPhoto -> {
                gateway.startShootPhoto { error ->
                    if (error != null) Log.w(TAG, "startShootPhoto failed: $error")
                }
            }
            is ShutterLogic.Action.StartRecordVideo -> {
                _isRecordingIntent.value = true
                Log.d(TAG, "triggerShutter: start requested")
                gateway.startRecordVideo { error ->
                    if (error != null) Log.w(TAG, "startRecordVideo failed: $error")
                }
            }
            is ShutterLogic.Action.StopRecordVideo -> {
                _isRecordingIntent.value = false
                Log.d(TAG, "triggerShutter: stop requested, recordingTimeSec=$recordingTimeSec")
                gateway.stopRecordVideo { error ->
                    if (error != null) {
                        Log.w(TAG, "stopRecordVideo failed: $error")
                    } else {
                        Log.d(TAG, "stopRecordVideo: success")
                    }
                }
            }
            is ShutterLogic.Action.Ignored -> {
                Log.w(TAG, "triggerShutter() ignored - camera is in ${action.modeName}, not SHOOT_PHOTO/RECORD_VIDEO")
            }
        }
    }
}
