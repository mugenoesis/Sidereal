package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.ResolutionAndFrameRate
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Photo file format/aspect ratio and video file format/resolution+frame
 * rate. No pushed callback for any of these in this SDK version, so each
 * field is only ever as fresh as the last successful set or the last
 * explicit [refresh] call - same reasoning as WhiteBalanceController and
 * ImageTuningController.
 *
 * SettingsDefinitions.PhotoFileFormat includes RAW/JPEG/RAW_AND_JPEG plus
 * several TIFF/RADIOMETRIC variants that only apply to DJI's thermal
 * cameras. This controller stays generic and passes through whatever
 * format is requested - the UI layer should only offer RAW/JPEG/
 * RAW_AND_JPEG for the X5.
 *
 * There's no *_RANGE-style key exposing which resolution/frame-rate PAIRS
 * are actually valid on the connected camera, unlike aperture/ISO/shutter.
 * Real cameras only support specific combinations, not the full cross
 * product of SettingsDefinitions.VideoResolution x VideoFrameRate - the UI
 * layer needs its own hardcoded reasonable subset for the X5 and/or must
 * handle a rejected combo via the completion callback's error; this
 * controller has no way to validate combos itself.
 */
class MediaFormatController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "MediaFormatController"
    }

    private val _photoFileFormat = MutableStateFlow<SettingsDefinitions.PhotoFileFormat?>(null)
    val photoFileFormat: StateFlow<SettingsDefinitions.PhotoFileFormat?> = _photoFileFormat

    private val _photoAspectRatio = MutableStateFlow<SettingsDefinitions.PhotoAspectRatio?>(null)
    val photoAspectRatio: StateFlow<SettingsDefinitions.PhotoAspectRatio?> = _photoAspectRatio

    private val _videoFileFormat = MutableStateFlow<SettingsDefinitions.VideoFileFormat?>(null)
    val videoFileFormat: StateFlow<SettingsDefinitions.VideoFileFormat?> = _videoFileFormat

    private val _videoResolutionAndFrameRate = MutableStateFlow<ResolutionAndFrameRate?>(null)
    val videoResolutionAndFrameRate: StateFlow<ResolutionAndFrameRate?> = _videoResolutionAndFrameRate

    // Plain-string names for the newer settings (video standard, picture profile) and for the camera's own
    // lists of what it accepts - strings keep them testable and keep live SDK enums out of app logic.
    private val _videoStandard = MutableStateFlow<String?>(null)
    val videoStandard: StateFlow<String?> = _videoStandard

    private val _cameraColor = MutableStateFlow<String?>(null)
    val cameraColor: StateFlow<String?> = _cameraColor

    /** Resolution/frame-rate pairs (enum names) the camera really accepts right now - depends on PAL/NTSC. Empty until queried. */
    private val _videoModeRange = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val videoModeRange: StateFlow<List<Pair<String, String>>> = _videoModeRange

    private val _videoStandardRange = MutableStateFlow<List<String>>(emptyList())
    val videoStandardRange: StateFlow<List<String>> = _videoStandardRange

    private val _colorRange = MutableStateFlow<List<String>>(emptyList())
    val colorRange: StateFlow<List<String>> = _colorRange

    // One-shot events, not persistent state - see FocusController.errorEvents.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    // Every _xxx StateFlow below is typed with a live SDK enum (or, for
    // video resolution, a ResolutionAndFrameRate bundle), so - same
    // reasoning as ImageTuningController.setAntiFlickerFrequency - it can
    // only ever be touched from these untested wrappers. Each ByName twin
    // carries all the actual testable logic and never references a live
    // enum/bundle value itself; see CameraGateway's doc comment.

    fun setPhotoFileFormat(format: SettingsDefinitions.PhotoFileFormat) {
        setPhotoFileFormatByName(format.name) { success -> if (success) _photoFileFormat.value = format }
    }

    internal fun setPhotoFileFormatByName(formatName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setPhotoFileFormat(formatName) { error ->
            if (error != null) {
                Log.w(TAG, "setPhotoFileFormat($formatName) failed: $error")
                _errorEvents.tryEmit("Photo format $formatName rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    fun setPhotoAspectRatio(ratio: SettingsDefinitions.PhotoAspectRatio) {
        setPhotoAspectRatioByName(ratio.name) { success -> if (success) _photoAspectRatio.value = ratio }
    }

    internal fun setPhotoAspectRatioByName(ratioName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setPhotoAspectRatio(ratioName) { error ->
            if (error != null) {
                Log.w(TAG, "setPhotoAspectRatio($ratioName) failed: $error")
                _errorEvents.tryEmit("Photo aspect ratio $ratioName rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    fun setVideoFileFormat(format: SettingsDefinitions.VideoFileFormat) {
        setVideoFileFormatByName(format.name) { success -> if (success) _videoFileFormat.value = format }
    }

    internal fun setVideoFileFormatByName(formatName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setVideoFileFormat(formatName) { error ->
            if (error != null) {
                Log.w(TAG, "setVideoFileFormat($formatName) failed: $error")
                _errorEvents.tryEmit("Video format $formatName rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    // Changing video format/resolution mid-recording is a UI-layer concern to guard, not this
    // controller's job (kept a dumb pass-through, same as every other setter here) - likely
    // rejected by the camera, or should be prevented outright, whenever
    // DJIConnectionManager.cameraSystemState.value?.isRecording == true. MainActivity's shutter
    // button already reacts to isRecording elsewhere, same signal applies here.
    fun setVideoResolutionAndFrameRate(
        resolution: SettingsDefinitions.VideoResolution,
        frameRate: SettingsDefinitions.VideoFrameRate
    ) {
        setVideoResolutionAndFrameRateByName(resolution.name, frameRate.name) { success ->
            if (success) _videoResolutionAndFrameRate.value = ResolutionAndFrameRate(resolution, frameRate)
        }
    }

    internal fun setVideoResolutionAndFrameRateByName(resolutionName: String, frameRateName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setVideoResolutionAndFrameRate(resolutionName, frameRateName) { error ->
            if (error != null) {
                Log.w(TAG, "setVideoResolutionAndFrameRate($resolutionName, $frameRateName) failed: $error")
                _errorEvents.tryEmit("Video resolution/frame rate rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    /**
     * PAL (25/50 fps) vs NTSC (24/30/60 fps): switching it changes which frame rates the camera offers, so the
     * resolution/frame-rate list is re-read afterwards ([onRangeChanged] gives the caller a chance to do that).
     */
    internal fun setVideoStandardByName(standardName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setVideoStandard(standardName) { error ->
            if (error != null) {
                Log.w(TAG, "setVideoStandard($standardName) failed: $error")
                _errorEvents.tryEmit("Video standard $standardName rejected ($error)")
                onComplete(false)
            } else {
                _videoStandard.value = standardName
                onComplete(true)
            }
        }
    }

    fun setVideoStandard(standardName: String) {
        setVideoStandardByName(standardName) { success ->
            if (!success) return@setVideoStandardByName
            // Measured on the X5: the camera accepts the switch at once but then refuses every query for several
            // seconds while it reconfigures, so re-read its lists after it has settled, not immediately.
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            for (delayMs in listOf(3_000L, 6_000L, 10_000L)) {
                handler.postDelayed({ refreshVideoRanges() }, delayMs)
            }
        }
    }

    /** The camera's picture profile (D-Log, D-Cinelike, B&W, ...) - applies to both stills and video. */
    internal fun setColorByName(colorName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setColor(colorName) { error ->
            if (error != null) {
                Log.w(TAG, "setColor($colorName) failed: $error")
                _errorEvents.tryEmit("Colour profile $colorName rejected ($error)")
                onComplete(false)
            } else {
                _cameraColor.value = colorName
                onComplete(true)
            }
        }
    }

    fun setColor(colorName: String) = setColorByName(colorName)

    /** Reads what the camera accepts - lists of resolution/frame-rate pairs, video standards and colour profiles. */
    fun refreshVideoRanges() {
        val keyManager = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager ?: return
        fun <T> read(key: String, onValue: (Any) -> Unit) {
            keyManager.getValue(dji.keysdk.CameraKey.create(key), object : dji.keysdk.callback.GetCallback {
                override fun onSuccess(value: Any) = onValue(value)
                override fun onFailure(error: DJIError) {
                    Log.w(TAG, "$key query failed: ${error.description}")
                }
            })
        }
        read<Unit>(dji.keysdk.CameraKey.VIDEO_RESOLUTION_FRAME_RATE_RANGE) { value ->
            _videoModeRange.value = (value as? Array<*>).orEmpty()
                .filterIsInstance<ResolutionAndFrameRate>()
                .map { it.resolution.name to it.frameRate.name }
        }
        read<Unit>(dji.keysdk.CameraKey.VIDEO_STANDARD_RANGE) { value ->
            _videoStandardRange.value = (value as? Array<*>).orEmpty().map { (it as Enum<*>).name }
        }
        read<Unit>(dji.keysdk.CameraKey.CAMERA_COLOR_RANGE) { value ->
            _colorRange.value = (value as? Array<*>).orEmpty().map { (it as Enum<*>).name }
        }
        read<Unit>(dji.keysdk.CameraKey.VIDEO_STANDARD) { value -> _videoStandard.value = (value as Enum<*>).name }
        read<Unit>(dji.keysdk.CameraKey.CAMERA_COLOR) { value -> _cameraColor.value = (value as Enum<*>).name }
    }

    /**
     * Re-queries all four settings and updates the StateFlows above. Call
     * once whenever the "More Settings" tray is opened - same
     * query-on-demand pattern as ImageTuningController.refresh().
     */
    fun refresh() {
        val camera = DJIConnectionManager.camera ?: return

        camera.getPhotoFileFormat(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.PhotoFileFormat> {
            override fun onSuccess(value: SettingsDefinitions.PhotoFileFormat) {
                _photoFileFormat.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getPhotoFileFormat failed: ${error.description}")
            }
        })

        camera.getPhotoAspectRatio(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.PhotoAspectRatio> {
            override fun onSuccess(value: SettingsDefinitions.PhotoAspectRatio) {
                _photoAspectRatio.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getPhotoAspectRatio failed: ${error.description}")
            }
        })

        camera.getVideoFileFormat(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.VideoFileFormat> {
            override fun onSuccess(value: SettingsDefinitions.VideoFileFormat) {
                _videoFileFormat.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getVideoFileFormat failed: ${error.description}")
            }
        })

        refreshVideoRanges()

        camera.getVideoResolutionAndFrameRate(object : CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate> {
            override fun onSuccess(value: ResolutionAndFrameRate) {
                _videoResolutionAndFrameRate.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getVideoResolutionAndFrameRate failed: ${error.description}")
            }
        })
    }
}
