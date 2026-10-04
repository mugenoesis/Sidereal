package io.github.mugenoesis.sidereal.dji

import android.graphics.Point
import android.graphics.PointF
import android.util.Log
import dji.common.camera.ResolutionAndFrameRate
import dji.common.camera.SettingsDefinitions
import dji.common.camera.WhiteBalance

/**
 * Production CameraGateway - looks up DJIConnectionManager.camera fresh on
 * every call (never caches it), matching every controller's pre-existing
 * behavior: the camera reference can be hot-swapped mid-session (a lens
 * change, a reconnect), so a stale cached reference would silently keep
 * calling a dead object.
 *
 * Converts each String name back to its real SettingsDefinitions enum via
 * valueOf() here, and only here - see CameraGateway's doc comment for why
 * the interface itself never touches the live enum types. valueOf() on an
 * unrecognized name throws IllegalArgumentException; caught and reported
 * through onResult like any other failure rather than crashing, though in
 * practice every caller passes a name that came from that same enum's
 * .name in the first place.
 */
object RealCameraGateway : CameraGateway {

    private const val TAG = "RealCameraGateway"

    private inline fun withCamera(onResult: (String?) -> Unit, block: (dji.sdk.camera.Camera) -> Unit) {
        val camera = DJIConnectionManager.camera
        if (camera == null) {
            onResult("No camera connected")
            return
        }
        try {
            block(camera)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unrecognized SDK enum name: ${e.message}")
            onResult("Unrecognized value: ${e.message}")
        }
    }

    override fun setExposureMode(modeName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val mode = SettingsDefinitions.ExposureMode.valueOf(modeName)
            it.setExposureMode(mode) { error -> onResult(error?.description) }
        }

    override fun setIso(isoName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val iso = SettingsDefinitions.ISO.valueOf(isoName)
            it.setISO(iso) { error -> onResult(error?.description) }
        }

    override fun setShutterSpeed(speedName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val speed = SettingsDefinitions.ShutterSpeed.valueOf(speedName)
            it.setShutterSpeed(speed) { error -> onResult(error?.description) }
        }

    override fun setAperture(apertureName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val aperture = SettingsDefinitions.Aperture.valueOf(apertureName)
            it.setAperture(aperture) { error -> onResult(error?.description) }
        }

    override fun setExposureCompensation(evName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val ev = SettingsDefinitions.ExposureCompensation.valueOf(evName)
            it.setExposureCompensation(ev) { error -> onResult(error?.description) }
        }

    override fun setFocusMode(modeName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val mode = SettingsDefinitions.FocusMode.valueOf(modeName)
            it.setFocusMode(mode) { error -> onResult(error?.description) }
        }

    override fun setFocusTarget(xNorm: Float, yNorm: Float, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setFocusTarget(PointF(xNorm, yNorm)) { error -> onResult(error?.description) } }

    override fun setFocusRingValue(value: Int, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setFocusRingValue(value) { error -> onResult(error?.description) } }

    override fun setFocusAssistantEnabled(enabledAF: Boolean, enabledMF: Boolean, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            it.setFocusAssistantSettings(dji.common.camera.FocusAssistantSettings(enabledAF, enabledMF)) { error -> onResult(error?.description) }
        }

    override fun setMeteringMode(modeName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val mode = SettingsDefinitions.MeteringMode.valueOf(modeName)
            it.setMeteringMode(mode) { error -> onResult(error?.description) }
        }

    override fun setSpotMeteringTarget(col: Int, row: Int, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setSpotMeteringTarget(Point(col, row)) { error -> onResult(error?.description) } }

    override fun setWhiteBalance(presetName: String, colorTemperature: Int?, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val preset = SettingsDefinitions.WhiteBalancePreset.valueOf(presetName)
            val wb = if (colorTemperature != null) WhiteBalance(preset, colorTemperature) else WhiteBalance(preset)
            it.setWhiteBalance(wb) { error -> onResult(error?.description) }
        }

    override fun setSharpness(value: Int, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setSharpness(value) { error -> onResult(error?.description) } }

    override fun setContrast(value: Int, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setContrast(value) { error -> onResult(error?.description) } }

    override fun setSaturation(value: Int, onResult: (String?) -> Unit) =
        withCamera(onResult) { it.setSaturation(value) { error -> onResult(error?.description) } }

    override fun setAntiFlickerFrequency(freqName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val freq = SettingsDefinitions.AntiFlickerFrequency.valueOf(freqName)
            it.setAntiFlickerFrequency(freq) { error -> onResult(error?.description) }
        }

    override fun setPhotoFileFormat(formatName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val format = SettingsDefinitions.PhotoFileFormat.valueOf(formatName)
            it.setPhotoFileFormat(format) { error -> onResult(error?.description) }
        }

    override fun setPhotoAspectRatio(ratioName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val ratio = SettingsDefinitions.PhotoAspectRatio.valueOf(ratioName)
            it.setPhotoAspectRatio(ratio) { error -> onResult(error?.description) }
        }

    override fun setVideoFileFormat(formatName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val format = SettingsDefinitions.VideoFileFormat.valueOf(formatName)
            it.setVideoFileFormat(format) { error -> onResult(error?.description) }
        }

    override fun setVideoResolutionAndFrameRate(resolutionName: String, frameRateName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val resolution = SettingsDefinitions.VideoResolution.valueOf(resolutionName)
            val frameRate = SettingsDefinitions.VideoFrameRate.valueOf(frameRateName)
            it.setVideoResolutionAndFrameRate(ResolutionAndFrameRate(resolution, frameRate)) { error -> onResult(error?.description) }
        }

    override fun setCameraMode(modeName: String, onResult: (String?) -> Unit) =
        withCamera(onResult) {
            val mode = SettingsDefinitions.CameraMode.valueOf(modeName)
            it.setMode(mode) { error -> onResult(error?.description) }
        }

    override fun startShootPhoto(onResult: (String?) -> Unit) =
        withCamera(onResult) { it.startShootPhoto { error -> onResult(error?.description) } }

    override fun startRecordVideo(onResult: (String?) -> Unit) =
        withCamera(onResult) { it.startRecordVideo { error -> onResult(error?.description) } }

    override fun stopRecordVideo(onResult: (String?) -> Unit) {
        val keyManager = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager
        if (keyManager == null) {
            onResult("No key manager available")
            return
        }
        keyManager.performAction(
            dji.keysdk.CameraKey.create(dji.keysdk.CameraKey.STOP_RECORD_VIDEO),
            object : dji.keysdk.callback.ActionCallback {
                override fun onSuccess() {
                    onResult(null)
                }

                override fun onFailure(error: dji.common.error.DJIError) {
                    onResult(error.description)
                }
            }
        )
    }
}
