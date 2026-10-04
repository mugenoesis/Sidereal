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
