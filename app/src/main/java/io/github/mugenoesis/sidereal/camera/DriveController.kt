package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One position on the drive dial: how a single press of the shutter shoots.
 * [modeName] is a `ShootPhotoMode` enum name; [burstCountName] / [aebCountName]
 * are the matching count enum names for the modes that need one.
 */
data class DrivePreset(
    val label: String,
    val modeName: String,
    val burstCountName: String? = null,
    val aebCountName: String? = null
)

/**
 * Only what the Zenmuse X5 actually accepted when probed on a real Osmo Pro: HDR (although the camera lists it in
 * SHOOT_PHOTO_MODE_RANGE) and a burst of 10 are rejected, so they are not offered. Interval shooting is covered by
 * the sequence tray's intervalometer, which also drives the gimbal.
 */
object DrivePresets {
    val all: List<DrivePreset> = listOf(
        DrivePreset("Single", "SINGLE"),
        DrivePreset("Burst 3", "BURST", burstCountName = "BURST_COUNT_3"),
        DrivePreset("Burst 5", "BURST", burstCountName = "BURST_COUNT_5"),
        DrivePreset("Burst 7", "BURST", burstCountName = "BURST_COUNT_7"),
        DrivePreset("AEB 3", "AEB", aebCountName = "AEB_COUNT_3"),
        DrivePreset("AEB 5", "AEB", aebCountName = "AEB_COUNT_5")
    )
}

/**
 * The drive dial: single / HDR / burst / auto-exposure-bracketing. Cycling
 * tracks its own position rather than deriving "next" from the camera's
 * state - the same lesson as every other cycle button in this app: one
 * rejected value must not trap the button on it forever.
 */
class DriveController(private val gateway: CameraGateway = RealCameraGateway) {

    private companion object {
        const val TAG = "DriveController"
    }

    private var index = 0

    private val _current = MutableStateFlow(DrivePresets.all[0])
    val current: StateFlow<DrivePreset> = _current

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    fun cycle() {
        index = (index + 1) % DrivePresets.all.size
        select(DrivePresets.all[index])
    }

    fun select(preset: DrivePreset) {
        index = DrivePresets.all.indexOf(preset).coerceAtLeast(0)
        _current.value = preset
        gateway.setShootPhotoMode(preset.modeName) { error ->
            if (error != null) {
                Log.w(TAG, "setShootPhotoMode(${preset.modeName}) failed: $error")
                _errorEvents.tryEmit("${preset.label} rejected ($error)")
                return@setShootPhotoMode
            }
            preset.burstCountName?.let { name ->
                gateway.setPhotoBurstCount(name) { e -> report(preset, e) }
            }
            preset.aebCountName?.let { name ->
                gateway.setPhotoAebCount(name) { e -> report(preset, e) }
            }
        }
    }

    private fun report(preset: DrivePreset, error: String?) {
        if (error != null) {
            Log.w(TAG, "${preset.label} count failed: $error")
            _errorEvents.tryEmit("${preset.label} count rejected ($error)")
        }
    }
}
