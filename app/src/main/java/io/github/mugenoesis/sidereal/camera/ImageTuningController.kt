package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sharpness, contrast, saturation, and anti-flicker frequency. Unlike
 * ExposureSettings/WhiteBalance these have no pushed callback in this SDK
 * version either, so each field is only ever as fresh as the last
 * successful set or the last explicit [refresh] call - fine given how
 * rarely these are touched, no need for a push-callback pattern.
 */
class ImageTuningController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "ImageTuningController"

        // DJI doesn't expose a documented range key for sharpness/contrast/
        // saturation the way aperture/ISO/shutter get *_RANGE CameraKeys.
        // -3..3 is the conventional small signed-int scale DJI cameras use
        // for these three - UNVERIFIED against real hardware, confirm
        // against the connected X5 before trusting it for UI (e.g. slider
        // bounds).
        const val TUNING_VALUE_MIN = -3
        const val TUNING_VALUE_MAX = 3
    }

    private val _sharpness = MutableStateFlow<Int?>(null)
    val sharpness: StateFlow<Int?> = _sharpness

    private val _contrast = MutableStateFlow<Int?>(null)
    val contrast: StateFlow<Int?> = _contrast

    private val _saturation = MutableStateFlow<Int?>(null)
    val saturation: StateFlow<Int?> = _saturation

    private val _antiFlickerFrequency = MutableStateFlow<SettingsDefinitions.AntiFlickerFrequency?>(null)
    val antiFlickerFrequency: StateFlow<SettingsDefinitions.AntiFlickerFrequency?> = _antiFlickerFrequency

    // One-shot events, not persistent state - see FocusController.errorEvents.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    fun setSharpness(value: Int) {
        gateway.setSharpness(value) { error ->
            if (error != null) {
                Log.w(TAG, "setSharpness($value) failed: $error")
                _errorEvents.tryEmit("Sharpness rejected ($error)")
            } else {
                _sharpness.value = value
            }
        }
    }

    fun setContrast(value: Int) {
        gateway.setContrast(value) { error ->
            if (error != null) {
                Log.w(TAG, "setContrast($value) failed: $error")
                _errorEvents.tryEmit("Contrast rejected ($error)")
            } else {
                _contrast.value = value
            }
        }
    }

    fun setSaturation(value: Int) {
        gateway.setSaturation(value) { error ->
            if (error != null) {
                Log.w(TAG, "setSaturation($value) failed: $error")
                _errorEvents.tryEmit("Saturation rejected ($error)")
            } else {
                _saturation.value = value
            }
        }
    }

    // _antiFlickerFrequency, typed with the live enum, can only ever be
    // touched from this untested wrapper - see CameraGateway's doc comment
    // for why passing a live SettingsDefinitions value through a function
    // call breaks in a plain JVM unit test. setAntiFlickerFrequencyByName
    // carries all the actual testable logic and never references a live
    // AntiFlickerFrequency value itself.
    fun setAntiFlickerFrequency(freq: SettingsDefinitions.AntiFlickerFrequency) {
        setAntiFlickerFrequencyByName(freq.name) { success -> if (success) _antiFlickerFrequency.value = freq }
    }

    internal fun setAntiFlickerFrequencyByName(freqName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setAntiFlickerFrequency(freqName) { error ->
            if (error != null) {
                Log.w(TAG, "setAntiFlickerFrequency($freqName) failed: $error")
                _errorEvents.tryEmit("Anti-flicker $freqName rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    /**
     * Re-queries all four settings and updates the StateFlows above. Call
     * once whenever the "More Settings" tray is opened - query-on-demand
     * is fine for settings touched this rarely, same reasoning as
     * WhiteBalanceController.refresh().
     */
    fun refresh() {
        val camera = DJIConnectionManager.camera ?: return

        camera.getSharpness(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int) {
                _sharpness.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getSharpness failed: ${error.description}")
            }
        })

        camera.getContrast(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int) {
                _contrast.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getContrast failed: ${error.description}")
            }
        })

        camera.getSaturation(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int) {
                _saturation.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getSaturation failed: ${error.description}")
            }
        })

        camera.getAntiFlickerFrequency(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.AntiFlickerFrequency> {
            override fun onSuccess(value: SettingsDefinitions.AntiFlickerFrequency) {
                _antiFlickerFrequency.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getAntiFlickerFrequency failed: ${error.description}")
            }
        })
    }
}
