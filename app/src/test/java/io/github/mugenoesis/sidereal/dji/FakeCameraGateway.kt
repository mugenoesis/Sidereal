package io.github.mugenoesis.sidereal.dji

/**
 * Test double for CameraGateway - invokes onResult synchronously (there's
 * no real I/O to wait on) with whatever [errorToReturn] is currently set
 * to, and records every call for assertion. Set [errorToReturn] to null
 * (the default) to simulate a successful command, or to a description
 * string to simulate the camera rejecting it.
 */
class FakeCameraGateway : CameraGateway {

    var errorToReturn: String? = null

    /** Every call made, as a short human-readable string, in order - e.g. "setIso(ISO_100)". */
    val calls = mutableListOf<String>()

    override fun setExposureMode(modeName: String, onResult: (String?) -> Unit) {
        calls += "setExposureMode($modeName)"
        onResult(errorToReturn)
    }

    override fun setIso(isoName: String, onResult: (String?) -> Unit) {
        calls += "setIso($isoName)"
        onResult(errorToReturn)
    }

    override fun setShutterSpeed(speedName: String, onResult: (String?) -> Unit) {
        calls += "setShutterSpeed($speedName)"
        onResult(errorToReturn)
    }

    override fun setAperture(apertureName: String, onResult: (String?) -> Unit) {
        calls += "setAperture($apertureName)"
        onResult(errorToReturn)
    }

    override fun setExposureCompensation(evName: String, onResult: (String?) -> Unit) {
        calls += "setExposureCompensation($evName)"
        onResult(errorToReturn)
    }

    override fun setFocusMode(modeName: String, onResult: (String?) -> Unit) {
        calls += "setFocusMode($modeName)"
        onResult(errorToReturn)
    }

    override fun setFocusTarget(xNorm: Float, yNorm: Float, onResult: (String?) -> Unit) {
        calls += "setFocusTarget($xNorm, $yNorm)"
        onResult(errorToReturn)
    }

    override fun setFocusRingValue(value: Int, onResult: (String?) -> Unit) {
        calls += "setFocusRingValue($value)"
        onResult(errorToReturn)
    }

    override fun setFocusAssistantEnabled(enabledAF: Boolean, enabledMF: Boolean, onResult: (String?) -> Unit) {
        calls += "setFocusAssistantEnabled($enabledAF, $enabledMF)"
        onResult(errorToReturn)
    }

    override fun setMeteringMode(modeName: String, onResult: (String?) -> Unit) {
        calls += "setMeteringMode($modeName)"
        onResult(errorToReturn)
    }

    override fun setSpotMeteringTarget(col: Int, row: Int, onResult: (String?) -> Unit) {
        calls += "setSpotMeteringTarget($col, $row)"
        onResult(errorToReturn)
    }

    override fun setWhiteBalance(presetName: String, colorTemperature: Int?, onResult: (String?) -> Unit) {
        calls += "setWhiteBalance($presetName, $colorTemperature)"
        onResult(errorToReturn)
    }

    override fun setSharpness(value: Int, onResult: (String?) -> Unit) {
        calls += "setSharpness($value)"
        onResult(errorToReturn)
    }

    override fun setContrast(value: Int, onResult: (String?) -> Unit) {
        calls += "setContrast($value)"
        onResult(errorToReturn)
    }

    override fun setSaturation(value: Int, onResult: (String?) -> Unit) {
        calls += "setSaturation($value)"
        onResult(errorToReturn)
    }

    override fun setAntiFlickerFrequency(freqName: String, onResult: (String?) -> Unit) {
        calls += "setAntiFlickerFrequency($freqName)"
        onResult(errorToReturn)
    }

    override fun setPhotoFileFormat(formatName: String, onResult: (String?) -> Unit) {
        calls += "setPhotoFileFormat($formatName)"
        onResult(errorToReturn)
    }

    override fun setPhotoAspectRatio(ratioName: String, onResult: (String?) -> Unit) {
        calls += "setPhotoAspectRatio($ratioName)"
        onResult(errorToReturn)
    }

    override fun setVideoFileFormat(formatName: String, onResult: (String?) -> Unit) {
        calls += "setVideoFileFormat($formatName)"
        onResult(errorToReturn)
    }

    override fun setVideoResolutionAndFrameRate(resolutionName: String, frameRateName: String, onResult: (String?) -> Unit) {
        calls += "setVideoResolutionAndFrameRate($resolutionName, $frameRateName)"
        onResult(errorToReturn)
    }

    override fun setVideoStandard(standardName: String, onResult: (String?) -> Unit) {
        calls += "setVideoStandard($standardName)"
        onResult(errorToReturn)
    }

    override fun setColor(colorName: String, onResult: (String?) -> Unit) {
        calls += "setColor($colorName)"
        onResult(errorToReturn)
    }

    override fun setShootPhotoMode(modeName: String, onResult: (String?) -> Unit) {
        calls += "setShootPhotoMode($modeName)"
        onResult(errorToReturn)
    }

    override fun setPhotoBurstCount(countName: String, onResult: (String?) -> Unit) {
        calls += "setPhotoBurstCount($countName)"
        onResult(errorToReturn)
    }

    override fun setPhotoAebCount(countName: String, onResult: (String?) -> Unit) {
        calls += "setPhotoAebCount($countName)"
        onResult(errorToReturn)
    }

    override fun setAeLock(locked: Boolean, onResult: (String?) -> Unit) {
        calls += "setAeLock($locked)"
        onResult(errorToReturn)
    }

    override fun setCameraMode(modeName: String, onResult: (String?) -> Unit) {
        calls += "setCameraMode($modeName)"
        onResult(errorToReturn)
    }

    override fun startShootPhoto(onResult: (String?) -> Unit) {
        calls += "startShootPhoto()"
        onResult(errorToReturn)
    }

    override fun startRecordVideo(onResult: (String?) -> Unit) {
        calls += "startRecordVideo()"
        onResult(errorToReturn)
    }

    override fun stopRecordVideo(onResult: (String?) -> Unit) {
        calls += "stopRecordVideo()"
        onResult(errorToReturn)
    }
}
