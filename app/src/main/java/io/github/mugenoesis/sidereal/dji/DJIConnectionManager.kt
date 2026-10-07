package io.github.mugenoesis.sidereal.dji

import android.content.Context
import android.util.Log
import dji.common.camera.StorageState
import dji.common.camera.SystemState
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.gimbal.GimbalState
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.gimbal.Gimbal
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the DJI product connection.
 *
 * Everything else in the app (gimbal control, camera settings, video feed)
 * should go through here rather than calling DJISDKManager directly, so
 * there's one place that knows whether we're actually connected to an Osmo.
 */
object DJIConnectionManager {

    private const val TAG = "DJIConnectionManager"

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Registering : ConnectionState()
        object Registered : ConnectionState()
        object ProductConnected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var camera: Camera? = null
        private set

    var gimbal: Gimbal? = null
        private set

    // Gimbal has no synchronous state getter - GimbalState only arrives via
    // setStateCallback, so this is the latest one we've been pushed.
    private val _gimbalState = MutableStateFlow<GimbalState?>(null)
    val gimbalState: StateFlow<GimbalState?> = _gimbalState.asStateFlow()

    // Same pattern for the camera - SystemState carries current CameraMode
    // (SHOOT_PHOTO/RECORD_VIDEO) and isRecording, pushed via
    // setSystemStateCallback rather than any synchronous getter.
    private val _cameraSystemState = MutableStateFlow<SystemState?>(null)
    val cameraSystemState: StateFlow<SystemState?> = _cameraSystemState.asStateFlow()

    // Same pattern again for SD card/storage - pushed via
    // setStorageStateCallBack. Added specifically to check whether a stuck
    // "stopRecordVideo acked but isRecording stays true" run correlates
    // with the card being full/read-only/erroring, i.e. the camera is
    // genuinely still recording because it can't finish flushing/closing
    // the file, not ignoring the stop request outright.
    private val _storageState = MutableStateFlow<StorageState?>(null)
    val storageState: StateFlow<StorageState?> = _storageState.asStateFlow()

    // Battery charge in percent, pushed via Battery.setStateCallback; null until the first push or when the
    // product has no battery component.
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    // Bumped every time bindComponents() runs - i.e. on initial product
    // connect *and* on a mid-session component swap (lens change, gimbal
    // hot-swap). connectionState alone doesn't cover the latter: it's a
    // StateFlow of singleton objects, so re-setting it to the same
    // ConnectionState.ProductConnected instance from onComponentChange
    // wouldn't emit anyway. Things that need to react to "the camera/lens
    // just changed, not just connected" (e.g. ZoomController re-checking
    // digital zoom support) should observe this instead.
    private val _componentsBoundTick = MutableStateFlow(0)
    val componentsBoundTick: StateFlow<Int> = _componentsBoundTick.asStateFlow()

    fun initialize(context: Context) {
        _connectionState.value = ConnectionState.Registering

        DJISDKManager.getInstance().registerApp(
            context.applicationContext,
            object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(djiError: DJIError?) {
                    if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                        Log.i(TAG, "DJI SDK registration succeeded")
                        _connectionState.value = ConnectionState.Registered
                        // Registration success is when it's safe to start the
                        // connection-to-product process (starts listening for
                        // the Osmo over wifi/USB).
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        val msg = djiError?.description ?: "Unknown registration error"
                        Log.e(TAG, "DJI SDK registration failed: $msg")
                        _connectionState.value = ConnectionState.Error(msg)
                    }
                }

                override fun onProductDisconnect() {
                    Log.i(TAG, "Product disconnected")
                    camera = null
                    gimbal = null
                    _gimbalState.value = null
                    _cameraSystemState.value = null
                    _connectionState.value = ConnectionState.Registered
                }

                override fun onProductConnect(baseProduct: BaseProduct?) {
                    Log.i(TAG, "Product connected: ${baseProduct?.model}")
                    bindComponents(baseProduct)
                    _connectionState.value = ConnectionState.ProductConnected
                }

                override fun onProductChanged(baseProduct: BaseProduct?) {
                    bindComponents(baseProduct)
                }

                override fun onComponentChange(
                    componentKey: BaseProduct.ComponentKey?,
                    oldComponent: dji.sdk.base.BaseComponent?,
                    newComponent: dji.sdk.base.BaseComponent?
                ) {
                    // Re-bind whenever DJI swaps out a component (e.g. camera
                    // module hot-swap), so we never hold a stale reference.
                    bindComponents(DJISDKManager.getInstance().product)
                }

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    // No-op for now; hook here if you want a progress bar
                    // during SDK init.
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                    // Fly-safe/geo database download progress. Osmo (no GPS,
                    // handheld) doesn't need this, but the callback still fires.
                }
            }
        )
    }

    private fun bindComponents(product: BaseProduct?) {
        camera = product?.camera
        gimbal = product?.gimbal

        if (camera == null) Log.w(TAG, "No camera component found on product")
        if (gimbal == null) Log.w(TAG, "No gimbal component found on product")

        cachedPitchRange = null
        cachedYawRange = null

        _gimbalState.value = null
        gimbal?.setStateCallback { state -> _gimbalState.value = state }

        _cameraSystemState.value = null
        camera?.setSystemStateCallback { state ->
            if (state.isRecording != _cameraSystemState.value?.isRecording) {
                Log.d(TAG, "cameraSystemState: isRecording changed to ${state.isRecording}")
            }
            _cameraSystemState.value = state
        }

        _storageState.value = null
        camera?.setStorageStateCallBack { state -> _storageState.value = state }

        _batteryPercent.value = null
        product?.battery?.setStateCallback { state -> _batteryPercent.value = state.chargeRemainingInPercent }

        _componentsBoundTick.value += 1
    }

    fun isReadyToShoot(): Boolean =
        _connectionState.value is ConnectionState.ProductConnected && camera != null && gimbal != null

    private var cachedPitchRange: ClosedFloatingPointRange<Float>? = null
    private var cachedYawRange: ClosedFloatingPointRange<Float>? = null

    /**
     * Real min/max ABSOLUTE_ANGLE range for the connected gimbal's pitch
     * axis, from Gimbal.getCapabilities()[ADJUST_PITCH] - null if not
     * (yet) available. Queried lazily rather than right at bind time: real
     * hardware testing found a guessed conservative-looking clamp
     * (-90..30) was actually wrong for this product - once the
     * accumulating target hit that boundary it got pinned there
     * permanently, and since the pinned value itself was invalid, every
     * subsequent rotate() call was rejected with "Param Illegal" forever
     * (a full tracking lock-up, worse than the occasional rejection during
     * a big excursion the clamp was meant to fix). Querying the gimbal's
     * own reported real range instead of guessing is the actual fix.
     * Cached once found (component swaps clear the cache in
     * bindComponents), since capabilities don't change mid-session.
     */
    fun pitchRangeDegrees(): ClosedFloatingPointRange<Float>? {
        cachedPitchRange?.let { return it }
        val range = queryGimbalRange(dji.common.gimbal.CapabilityKey.ADJUST_PITCH)
        if (range != null) cachedPitchRange = range
        return range
    }

    /** Same as pitchRangeDegrees() but for yaw (CapabilityKey.ADJUST_YAW). */
    fun yawRangeDegrees(): ClosedFloatingPointRange<Float>? {
        cachedYawRange?.let { return it }
        val range = queryGimbalRange(dji.common.gimbal.CapabilityKey.ADJUST_YAW)
        if (range != null) cachedYawRange = range
        return range
    }

    private fun queryGimbalRange(key: dji.common.gimbal.CapabilityKey): ClosedFloatingPointRange<Float>? {
        val capability = gimbal?.capabilities?.get(key) as? dji.common.util.DJIParamMinMaxCapability ?: return null
        if (!capability.isSupported) return null
        val min = capability.min?.toFloat() ?: return null
        val max = capability.max?.toFloat() ?: return null
        if (min >= max) return null
        return min..max
    }
}
