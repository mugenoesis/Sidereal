package io.github.mugenoesis.sidereal.dji

/**
 * Abstracts the camera "command" surface (settings setters) that
 * ExposureController/FocusController/MeteringController/
 * WhiteBalanceController/ImageTuningController/MediaFormatController call,
 * so their logic (mode-editability rules, error-message formatting, spot-
 * metering cell math, custom-Kelvin defaults) is unit-testable against a
 * fake instead of a live DJI Camera - see FakeCameraGateway in the test
 * source set.
 *
 * Every settings parameter here is the DJI enum's .name (a plain String),
 * never the live SettingsDefinitions enum type - confirmed by direct,
 * repeated experiment that this SDK's classes are unsafe to touch from a
 * plain JVM unit test process in a way far stricter than "avoid the
 * concrete bundle classes" (SoftwareAfcController's earlier lesson): it's
 * not particular enums that are broken, it's that PASSING ANY live
 * SettingsDefinitions enum value as a parameter into ANY app-authored
 * method - even a trivial equality check with no SDK call at all -
 * throws java.lang.VerifyError the moment that method is actually invoked.
 * Bare static access (SettingsDefinitions.ISO.AUTO.name) is fine; calling
 * a function with that value as an argument is not. Every controller's
 * public setters therefore stay enum-typed (unchanged for MainActivity's
 * callers) but immediately convert to .name and delegate to an internal,
 * String-only method that both this gateway and tests can safely call -
 * see e.g. ExposureController.setIsoByName.
 *
 * Every method's callback receives null on success or a human-readable
 * error description on failure - deliberately a plain String, never the
 * SDK's own DJIError, which has the exact same problem as the enums.
 * RealCameraGateway below is the only place error.description ever gets
 * read off a real DJIError, and the only place any live enum gets
 * reconstructed via valueOf() or any bundle object (WhiteBalance, Point,
 * PointF, ResolutionAndFrameRate) ever gets built - none of that ever
 * crosses this interface boundary.
 *
 * Deliberately does NOT cover the getter/refresh/pushed-callback side
 * (getMeteringMode, setExposureSettingsCallback, etc.) - those are trivial
 * one-line delegations with no branching logic worth a fake for, unlike
 * the setters, which all share the same "log + format a user-facing
 * message on failure, optimistically update local state on success" shape
 * that's actually worth locking down with tests.
 */
interface CameraGateway {
    /**
     * False in the moment between the product connecting and the SDK handing over its camera. Settings the app
     * re-sends on every (re)bind wait for this instead of being rejected with "No camera connected".
     */
    val hasCamera: Boolean get() = true

    fun setExposureMode(modeName: String, onResult: (error: String?) -> Unit)
    fun setIso(isoName: String, onResult: (error: String?) -> Unit)
    fun setShutterSpeed(speedName: String, onResult: (error: String?) -> Unit)
    fun setAperture(apertureName: String, onResult: (error: String?) -> Unit)
    fun setExposureCompensation(evName: String, onResult: (error: String?) -> Unit)

    fun setFocusMode(modeName: String, onResult: (error: String?) -> Unit)
    fun setFocusTarget(xNorm: Float, yNorm: Float, onResult: (error: String?) -> Unit)
    fun setFocusRingValue(value: Int, onResult: (error: String?) -> Unit)
    fun setFocusAssistantEnabled(enabledAF: Boolean, enabledMF: Boolean, onResult: (error: String?) -> Unit)

    fun setMeteringMode(modeName: String, onResult: (error: String?) -> Unit)
    fun setSpotMeteringTarget(col: Int, row: Int, onResult: (error: String?) -> Unit)

    fun setWhiteBalance(presetName: String, colorTemperature: Int?, onResult: (error: String?) -> Unit)

    fun setSharpness(value: Int, onResult: (error: String?) -> Unit)
    fun setContrast(value: Int, onResult: (error: String?) -> Unit)
    fun setSaturation(value: Int, onResult: (error: String?) -> Unit)
    fun setAntiFlickerFrequency(freqName: String, onResult: (error: String?) -> Unit)

    fun setPhotoFileFormat(formatName: String, onResult: (error: String?) -> Unit)
    fun setPhotoAspectRatio(ratioName: String, onResult: (error: String?) -> Unit)
    fun setVideoFileFormat(formatName: String, onResult: (error: String?) -> Unit)
    fun setVideoResolutionAndFrameRate(resolutionName: String, frameRateName: String, onResult: (error: String?) -> Unit)

    // --- Video standard / picture profile (MediaFormatController) ---
    fun setVideoStandard(standardName: String, onResult: (error: String?) -> Unit)
    fun setColor(colorName: String, onResult: (error: String?) -> Unit)

    // --- Drive modes / AE lock (DriveController, AeLockController) ---
    fun setShootPhotoMode(modeName: String, onResult: (error: String?) -> Unit)
    fun setPhotoBurstCount(countName: String, onResult: (error: String?) -> Unit)
    fun setPhotoAebCount(countName: String, onResult: (error: String?) -> Unit)
    fun setAeLock(locked: Boolean, onResult: (error: String?) -> Unit)

    // --- Camera mode / shutter (CameraModeController) ---
    fun setCameraMode(modeName: String, onResult: (error: String?) -> Unit)
    fun startShootPhoto(onResult: (error: String?) -> Unit)
    fun startRecordVideo(onResult: (error: String?) -> Unit)

    /**
     * Uses the lower-level KeyManager.performAction(STOP_RECORD_VIDEO) path,
     * not Camera.stopRecordVideo() - see CameraModeController's class doc
     * comment: both were tested on real hardware and behave identically
     * (stop is confirmed unreliable on this camera/SDK combination
     * regardless of which path is used), this is just the simpler one.
     * Also the reason this needs to be a real gateway method rather than a
     * direct DJISDKManager call inside the controller: DJISDKManager
     * .getInstance() hangs indefinitely (not throws) when touched from a
     * plain JVM unit test, confirmed by direct experiment - RealCameraGateway
     * is the only place that call is allowed to happen.
     */
    fun stopRecordVideo(onResult: (error: String?) -> Unit)
}
