package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.SettingsDefinitions
import dji.common.camera.WhiteBalance
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.keysdk.CameraKey
import dji.keysdk.callback.GetCallback
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sets camera white balance (preset or a custom Kelvin value) and mirrors
 * the camera's actual current setting back out via [whiteBalance].
 *
 * Unlike SystemState/GimbalState/StorageState, WhiteBalance has no pushed
 * callback in this SDK version - there's no setWhiteBalanceCallback - so
 * [whiteBalance] is only ever as fresh as the last successful set or the
 * last explicit [refresh] call, not continuously live.
 */
class WhiteBalanceController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "WhiteBalanceController"

        // Fixed order for icon-cycle UI. WATER_SURFACE/PRESET_NEUTRAL/UNKNOWN
        // are real presets but left out of the quick cycle since they're
        // rarely needed day-to-day - still reachable via setPreset() directly.
        //
        // CUSTOM stays in the cycle despite real hardware testing finding it
        // rejected ("Camera received invalid parameters") at both 0K and a
        // normal 5600K daylight value - not excluded, because which presets
        // a given camera/firmware accepts isn't this app's call to make
        // permanently; rejections surface via [errorEvents] instead.
        //
        // Names, not live enum values - see CameraGateway's doc comment for
        // why: cyclePreset() calls setPresetByName() on every press, and
        // that must never pass a live SettingsDefinitions value through a
        // function call in a way a JVM unit test would ever execute.
        private val CYCLE_ORDER = listOf("AUTO", "SUNNY", "CLOUDY", "INDOOR_INCANDESCENT", "INDOOR_FLUORESCENT", "CUSTOM")
        private const val DEFAULT_CUSTOM_KELVIN = 5600
    }

    private val _whiteBalance = MutableStateFlow<WhiteBalance?>(null)
    val whiteBalance: StateFlow<WhiteBalance?> = _whiteBalance

    private val _customTemperatureRangeKelvin = MutableStateFlow<IntRange?>(null)
    val customTemperatureRangeKelvin: StateFlow<IntRange?> = _customTemperatureRangeKelvin

    // One-shot events, not persistent state - see FocusController.errorEvents
    // for why this is a SharedFlow rather than a StateFlow.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    // Tracks cyclePreset()'s position independently of real state - see
    // FocusController.cycleIndex's doc comment for why (a rejected preset
    // leaves whiteBalance unchanged, which would otherwise stall the cycle
    // on the same rejected "next" forever).
    private var cycleIndex: Int? = null

    fun setPreset(preset: SettingsDefinitions.WhiteBalancePreset) = setPresetByName(preset.name)

    internal fun setPresetByName(presetName: String) {
        val kelvin = if (presetName == "CUSTOM") {
            // WhiteBalance(CUSTOM)'s 1-arg constructor defaults
            // colorTemperature to 0, which the camera rejects outright -
            // land on a real starting Kelvin instead so a direct
            // setPreset(CUSTOM) call at least has a chance of succeeding.
            _customTemperatureRangeKelvin.value?.let { (it.first + it.last) / 2 } ?: DEFAULT_CUSTOM_KELVIN
        } else null
        sendByName(presetName, kelvin)
    }

    fun setCustomColorTemperature(kelvin: Int) {
        sendByName("CUSTOM", kelvin)
    }

    /** Advances to the next preset in [CYCLE_ORDER] - see [cycleIndex]'s doc comment for why this doesn't follow real state. */
    fun cyclePreset() {
        val startIndex = cycleIndex ?: CYCLE_ORDER.indexOf(_whiteBalance.value?.whiteBalancePreset?.name).coerceAtLeast(-1)
        val nextIndex = (startIndex + 1).mod(CYCLE_ORDER.size)
        cycleIndex = nextIndex
        setPresetByName(CYCLE_ORDER[nextIndex])
    }

    internal fun sendByName(presetName: String, colorTemperature: Int?) {
        gateway.setWhiteBalance(presetName, colorTemperature) { error ->
            if (error != null) {
                Log.w(TAG, "setWhiteBalance($presetName, $colorTemperature) failed: $error")
                _errorEvents.tryEmit("White balance $presetName not supported by this camera ($error)")
            } else {
                refresh()
            }
        }
    }

    /**
     * Re-queries the camera's current white balance and updates
     * [whiteBalance]. Called automatically after every successful set
     * above; call it manually too, once on initial connect, so the UI
     * isn't blank until the first set happens.
     */
    fun refresh() {
        val camera = DJIConnectionManager.camera ?: return
        camera.getWhiteBalance(object : CommonCallbacks.CompletionCallbackWith<WhiteBalance> {
            override fun onSuccess(value: WhiteBalance) {
                _whiteBalance.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getWhiteBalance failed: ${error.description}")
            }
        })
    }

    /**
     * Queries the valid custom-Kelvin range for the CUSTOM preset. There's
     * no getter for this on Camera itself, only the CameraKey system - call
     * once on initial connect, same as [refresh]. This is a fixed hardware
     * capability, not something that changes mid-session, so it's not
     * re-queried after every set the way [whiteBalance] is.
     */
    fun refreshCustomTemperatureRange() {
        DJISDKManager.getInstance().keyManager?.getValue(
            CameraKey.create(CameraKey.WHITE_BALANCE_CUSTOM_COLOR_TEMPERATURE_RANGE),
            object : GetCallback {
                override fun onSuccess(value: Any) {
                    val range = WhiteBalanceRangeParser.parseRange(value)
                    if (range != null) {
                        _customTemperatureRangeKelvin.value = range
                    } else {
                        Log.w(TAG, "WHITE_BALANCE_CUSTOM_COLOR_TEMPERATURE_RANGE returned unrecognized shape: ${value.javaClass}")
                    }
                }

                override fun onFailure(error: DJIError) {
                    Log.w(TAG, "WHITE_BALANCE_CUSTOM_COLOR_TEMPERATURE_RANGE query failed: ${error.description}")
                }
            }
        )
    }

}
