package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.keysdk.CameraKey
import dji.keysdk.DJIKey
import dji.keysdk.callback.GetCallback
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps exposure metering mode and spot-metering target.
 *
 * SPOT_METERING_TARGET is a discrete grid-cell (col, row), not a pixel or
 * normalized point - Camera has no getter for the grid dimensions, so
 * SPOT_METERING_COL_NUMS/SPOT_METERING_ROW_NUMS are read once via the
 * Key/Value system (KeyManager.getValue) and cached for the session, same
 * lazy-query-and-cache idiom as DJIConnectionManager.pitchRangeDegrees().
 */
class MeteringController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "MeteringController"
    }

    private val _meteringMode = MutableStateFlow<SettingsDefinitions.MeteringMode?>(null)
    val meteringMode: StateFlow<SettingsDefinitions.MeteringMode?> = _meteringMode

    private val _supported = MutableStateFlow(false)
    val supported: StateFlow<Boolean> = _supported

    // One-shot events, not persistent state - see FocusController.errorEvents.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    private var cachedGridCols: Int? = null
    private var cachedGridRows: Int? = null

    /** Call externally whenever DJIConnectionManager.componentsBoundTick changes, matching ZoomController.refreshCapability(). */
    fun refreshCapability() {
        val camera = DJIConnectionManager.camera
        _supported.value = camera?.isMeteringSupported() ?: false
        if (camera == null) {
            _meteringMode.value = null
            return
        }
        camera.getMeteringMode(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.MeteringMode> {
            override fun onSuccess(mode: SettingsDefinitions.MeteringMode) {
                _meteringMode.value = mode
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getMeteringMode failed: ${error.description}")
            }
        })
    }

    // MeteringMode's enum constructor is fundamentally broken in a plain
    // JVM (fails at class-init time, not just when passed as a parameter -
    // worse than ISO/ShutterSpeed/etc., see CameraGateway's doc comment) -
    // so _meteringMode, typed with the live enum, can only ever be touched
    // from this untested wrapper. setMeteringModeByName below carries all
    // the actual testable logic (gateway call, error formatting) and never
    // constructs or references a live MeteringMode value itself.
    fun setMeteringMode(mode: SettingsDefinitions.MeteringMode) {
        setMeteringModeByName(mode.name) { success -> if (success) _meteringMode.value = mode }
    }

    internal fun setMeteringModeByName(modeName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setMeteringMode(modeName) { error ->
            if (error != null) {
                Log.w(TAG, "setMeteringMode($modeName) failed: $error")
                _errorEvents.tryEmit("Metering mode $modeName rejected ($error)")
                onComplete(false)
            } else {
                onComplete(true)
            }
        }
    }

    /**
     * xNorm/yNorm are normalized 0..1 screen coords, same convention as
     * FaceOverlayView's bounding boxes - converted here to the discrete
     * grid-cell Point the SDK actually wants. Skips (and warns) rather
     * than guessing if the grid size isn't known yet; kicks off the query
     * so a later call can succeed. Whether (0,0) is top-left and whether
     * the SDK clamps vs. rejects an out-of-range cell is unconfirmed -
     * needs real-hardware testing.
     */
    fun setSpotMeteringTarget(xNorm: Float, yNorm: Float) {
        val cols = cachedGridCols
        val rows = cachedGridRows
        if (cols == null || rows == null) {
            Log.w(TAG, "setSpotMeteringTarget: grid size not yet known, skipping")
            refreshGridSize()
            return
        }

        val (col, row) = MeteringGrid.normalizedToCell(xNorm, yNorm, cols, rows)
        gateway.setSpotMeteringTarget(col, row) { error ->
            if (error != null) {
                Log.w(TAG, "setSpotMeteringTarget($col, $row) failed: $error")
                _errorEvents.tryEmit("Spot metering target rejected ($error)")
            }
        }
    }

    /**
     * Queries and caches the spot-metering grid dimensions. Exposed
     * publicly (not just triggered lazily from setSpotMeteringTarget) so
     * a caller can warm the cache ahead of the first tap-to-meter, e.g.
     * alongside refreshCapability() on a componentsBoundTick change.
     */
    fun refreshGridSize() {
        val keyManager = DJISDKManager.getInstance().keyManager ?: return
        keyManager.getValue(CameraKey.create(CameraKey.SPOT_METERING_COL_NUMS), object : GetCallback {
            override fun onSuccess(value: Any) {
                cachedGridCols = value as? Int
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getValue(SPOT_METERING_COL_NUMS) failed: ${error.description}")
            }
        })
        keyManager.getValue(CameraKey.create(CameraKey.SPOT_METERING_ROW_NUMS), object : GetCallback {
            override fun onSuccess(value: Any) {
                cachedGridRows = value as? Int
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getValue(SPOT_METERING_ROW_NUMS) failed: ${error.description}")
            }
        })
    }
}
