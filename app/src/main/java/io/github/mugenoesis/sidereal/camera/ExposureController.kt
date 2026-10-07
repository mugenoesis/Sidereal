package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.ExposureSettings
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.keysdk.CameraKey
import dji.keysdk.callback.GetCallback
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns exposure mode, ISO, shutter speed, aperture, and EV compensation as
 * one unit - the DJI SDK pushes them together as a single ExposureSettings
 * readout via setExposureSettingsCallback(), and which of them the user is
 * allowed to touch depends on the current ExposureMode (classic P/A/S/M
 * interdependency), so the mode-to-editability logic belongs next to the
 * fields it gates rather than duplicated in the UI layer.
 *
 * [gateway] defaults to the real camera in production; tests inject a
 * FakeCameraGateway instead - see CameraGateway's doc comment for why the
 * setters go through it but startObserving()/refreshCapability() (trivial
 * one-line delegations, no branching logic) still touch
 * DJIConnectionManager.camera directly.
 */
class ExposureController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "ExposureController"
    }

    // Null until the first pushed ExposureSettings arrives via
    // startObserving() below - there's no synchronous getter for the
    // bundle as a whole, only per-field CompletionCallbackWith getters,
    // which this controller doesn't use since the pushed callback already
    // covers aperture/shutter/ISO/EV together in one shot.
    private val _readout = MutableStateFlow<ExposureSettings?>(null)
    val readout: StateFlow<ExposureSettings?> = _readout

    // EV-specific telemetry via the newer Key-based interface, kept
    // separate from the bundled _readout above (which still sources
    // ISO/shutter/aperture - only EV was confirmed to need this). Real
    // hardware testing found Camera.setExposureSettingsCallback()'s EV
    // field can get permanently stuck not reflecting confirmed changes -
    // side-by-side against the same rapid 5-tap burst, KeyManager's pushed
    // updates for CameraKey.EXPOSURE_COMPENSATION tracked every single
    // step correctly (N_0_0->N_1_7->N_1_3->N_1_0->N_0_7->N_0_3, no drift,
    // no skips) while _readout sat frozen on the pre-burst value the whole
    // time. Null until refreshKeyBasedEvTelemetry() below succeeds.
    private val _evReadout = MutableStateFlow<SettingsDefinitions.ExposureCompensation?>(null)
    val evReadout: StateFlow<SettingsDefinitions.ExposureCompensation?> = _evReadout

    // The real per-camera EV range, from CameraKey.EXPOSURE_COMPENSATION_RANGE
    // - confirmed narrower than the full SDK enum this app previously
    // stepped through (this camera: -3.0..+3.0, not the SDK's -5.0..+5.0).
    // Replaces LearnedStepBounds' learn-by-rejection guessing once
    // available; null until the query succeeds.
    private val _evRange = MutableStateFlow<List<SettingsDefinitions.ExposureCompensation>?>(null)
    val evRange: StateFlow<List<SettingsDefinitions.ExposureCompensation>?> = _evRange

    // Real per-camera ISO / shutter ranges from CameraKey.ISO_RANGE /
    // SHUTTER_SPEED_RANGE - same role as _evRange above: lets the steppers
    // walk only values the camera will accept instead of the full generic
    // SDK enum. Null until the query succeeds (the steppers fall back to
    // the full enum, minus sentinels, in the meantime).
    private val _isoRange = MutableStateFlow<List<SettingsDefinitions.ISO>?>(null)
    val isoRange: StateFlow<List<SettingsDefinitions.ISO>?> = _isoRange

    private val _shutterRange = MutableStateFlow<List<SettingsDefinitions.ShutterSpeed>?>(null)
    val shutterRange: StateFlow<List<SettingsDefinitions.ShutterSpeed>?> = _shutterRange

    // False (assume fixed aperture) until refreshCapability() says
    // otherwise. The X5/X5R prime lenses this app is normally used with
    // have no adjustable aperture at all, so "unsupported" is the safe
    // default on an interchangeable-lens body before the mounted lens is
    // actually known - same reasoning as ZoomController's default.
    private val _apertureSupported = MutableStateFlow(false)
    val apertureSupported: StateFlow<Boolean> = _apertureSupported

    // One-shot events, not persistent state - see FocusController.errorEvents
    // for why this is a SharedFlow rather than a StateFlow. Real hardware
    // testing found the camera rejects exposure-mode changes outright
    // ("Not supported") while actively recording video - a real, sensible
    // restriction, but previously failed completely silently (log-only)
    // with no indication to the user why nothing happened.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    /**
     * Registers the pushed exposure-settings callback on the currently
     * bound camera. DJIConnectionManager.bindComponents() re-registers its
     * own pushed-state callbacks (system/storage/gimbal state) on every
     * product connect AND every mid-session component swap, because a
     * fresh Camera/Lens instance after a hot-swap does not carry over
     * callbacks that were registered on the old one. This controller isn't
     * part of that internal rebind step, so rather than subscribing to
     * componentsBoundTick itself it exposes registration as this function,
     * to be called at the same points - same pattern as refreshCapability()
     * below. MainActivity should call this once after initial connect and
     * again on every componentsBoundTick change.
     */
    fun startObserving() {
        DJIConnectionManager.camera?.setExposureSettingsCallback { settings ->
            _readout.value = settings
        }
    }

    /**
     * Sets up EV telemetry via the Key-based interface - see _evReadout/
     * _evRange's doc comments above for why. DJISDKManager.getInstance()
     * .getKeyManager() is @Nullable and was observed null on the very
     * first componentsBoundTick (before the product finishes connecting) -
     * calling this again resolves it, since re-querying/re-listening is
     * idempotent from the caller's perspective (harmless if it already
     * succeeded). MainActivity calls this alongside startObserving() on
     * every componentsBoundTick, plus once more after a short delay to
     * cover that race - same "retry once, a couple seconds later" pattern
     * as refreshCapability().
     *
     * Note KeyManager.getInstance() (a static singleton accessor, distinct
     * from DJISDKManager's) crashed with a NullPointerException on real
     * hardware - DJISDKManager.getInstance().getKeyManager() is the
     * accessor that actually works.
     */
    fun refreshKeyBasedEvTelemetry() {
        val keyManager = DJISDKManager.getInstance().keyManager ?: return
        keyManager.getValue(CameraKey.create(CameraKey.ISO_RANGE), object : GetCallback {
            override fun onSuccess(value: Any) {
                val range = (value as? Array<*>)
                    ?.filterIsInstance<SettingsDefinitions.ISO>()
                    ?.filter { it != SettingsDefinitions.ISO.UNKNOWN && it != SettingsDefinitions.ISO.FIXED }
                Log.i(TAG, "CameraKey.ISO_RANGE -> ${range?.map { it.name }}")
                if (!range.isNullOrEmpty()) _isoRange.value = range.sortedBy { it.ordinal }
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "CameraKey.ISO_RANGE query failed: ${error.description}")
            }
        })
        keyManager.getValue(CameraKey.create(CameraKey.SHUTTER_SPEED_RANGE), object : GetCallback {
            override fun onSuccess(value: Any) {
                val range = (value as? Array<*>)
                    ?.filterIsInstance<SettingsDefinitions.ShutterSpeed>()
                    ?.filter { it != SettingsDefinitions.ShutterSpeed.UNKNOWN }
                Log.i(TAG, "CameraKey.SHUTTER_SPEED_RANGE -> ${range?.map { it.name }}")
                if (!range.isNullOrEmpty()) _shutterRange.value = range.sortedBy { it.ordinal }
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "CameraKey.SHUTTER_SPEED_RANGE query failed: ${error.description}")
            }
        })
        keyManager.getValue(CameraKey.create(CameraKey.EXPOSURE_COMPENSATION_RANGE), object : GetCallback {
            override fun onSuccess(value: Any) {
                val range = (value as? Array<*>)
                    ?.filterIsInstance<SettingsDefinitions.ExposureCompensation>()
                    ?.filter { it != SettingsDefinitions.ExposureCompensation.UNKNOWN && it != SettingsDefinitions.ExposureCompensation.FIXED }
                if (!range.isNullOrEmpty()) {
                    _evRange.value = range
                }
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "CameraKey.EXPOSURE_COMPENSATION_RANGE query failed: ${error.description}")
            }
        })
        val evKey = CameraKey.create(CameraKey.EXPOSURE_COMPENSATION)
        // addListener() alone only fires on a future CHANGE (confirmed on
        // real hardware: the first log line it ever produced was after the
        // first tap, not at registration) - without this explicit initial
        // getValue(), _evReadout stays null (and every UI spot reading it
        // falls back to a stepper/readout-specific default that disagrees
        // with the other one) until something happens to change the real
        // EV for the first time this session.
        keyManager.getValue(evKey, object : GetCallback {
            override fun onSuccess(value: Any) {
                (value as? SettingsDefinitions.ExposureCompensation)?.let { _evReadout.value = it }
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "CameraKey.EXPOSURE_COMPENSATION query failed: ${error.description}")
            }
        })
        keyManager.addListener(evKey) { _, newValue ->
            (newValue as? SettingsDefinitions.ExposureCompensation)?.let { _evReadout.value = it }
        }
    }

    /**
     * Call whenever DJIConnectionManager.componentsBoundTick changes - lens
     * swaps are a real mid-session event on this interchangeable-lens body,
     * not just a one-time startup check (same rationale as
     * ZoomController.refreshCapability()).
     *
     * Calls Camera.isAdjustableApertureSupported() directly, NOT
     * Camera.getLens(0)?.isAdjustableApertureSupported() (what this used to
     * do, and what the doc comment here used to justify by "the X5/X5R only
     * has the one lens slot"). Real hardware testing found getLens(0)
     * returns null on this product - confirmed via reflection on the live
     * runtime object (not the compile-time stub class), consistently, even
     * several seconds after connect, so not a timing race - while
     * Camera.getLensInformation() (a separate, older async API) correctly
     * identifies the mounted lens ("DJI MFT 15mm F1.7 ASPH"), and
     * Camera.isAdjustableApertureSupported()/getAperture() called directly
     * (bypassing getLens(0) entirely) correctly return true/F_1_DOT_7. So
     * the SDK did support this the whole time - this app was just asking
     * the wrong object. RealCameraGateway.setAperture() already calls
     * camera.setAperture() directly (see its withCamera() helper), so that
     * half was never actually broken.
     */
    fun refreshCapability() {
        _apertureSupported.value = DJIConnectionManager.camera?.isAdjustableApertureSupported() ?: false
    }

    /**
     * [onComplete] reports whether the camera actually accepted the mode
     * switch - MainActivity's mode selector uses this to revert its
     * locally-selected mode (and the AppPreferences value persisted from
     * it) on failure, rather than leave the UI showing a mode the real
     * camera never actually entered. That mismatch is exactly what made an
     * earlier EV-compensation report ("can't change EV on A/S/M") hard to
     * pin down: a rejected mode switch (e.g. "No camera connected" during
     * a reconnect race) previously still left selectedExposureMode - and
     * therefore which stepper rows the UI enabled - pointing at the
     * requested mode, not whatever the camera was actually still in.
     */
    fun setExposureMode(mode: SettingsDefinitions.ExposureMode, onComplete: (Boolean) -> Unit = {}) =
        setExposureModeByName(mode.name, onComplete)

    internal fun setExposureModeByName(modeName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setExposureMode(modeName) { error ->
            if (error != null) {
                Log.w(TAG, "setExposureMode($modeName) failed: $error")
                _errorEvents.tryEmit("Exposure mode $modeName rejected ($error)")
            }
            onComplete(error == null)
        }
    }

    // onComplete on all three (matching setExposureCompensation's existing
    // one) lets MainActivity serialize its stepper sends - wait for this
    // request to actually finish before sending the next queued one,
    // instead of firing another overlapping request. Confirmed via a
    // side-by-side comparison against Litchi (a third-party app driving the
    // same camera) that overlapping/rapid-fire requests are what leaves the
    // camera's pushed readout stuck never reflecting reality, while
    // Litchi's one-at-a-time approach converges cleanly even for the same
    // rapid multi-tap burst.
    fun setIso(iso: SettingsDefinitions.ISO, onComplete: (String?) -> Unit = {}) = setIsoByName(iso.name, onComplete)

    internal fun setIsoByName(isoName: String, onComplete: (String?) -> Unit = {}) {
        gateway.setIso(isoName) { error ->
            if (error != null) {
                Log.w(TAG, "setISO($isoName) failed: $error")
                _errorEvents.tryEmit("ISO $isoName rejected ($error)")
            }
            onComplete(error)
        }
    }

    fun setShutterSpeed(speed: SettingsDefinitions.ShutterSpeed, onComplete: (String?) -> Unit = {}) =
        setShutterSpeedByName(speed.name, onComplete)

    internal fun setShutterSpeedByName(speedName: String, onComplete: (String?) -> Unit = {}) {
        gateway.setShutterSpeed(speedName) { error ->
            if (error != null) {
                Log.w(TAG, "setShutterSpeed($speedName) failed: $error")
                _errorEvents.tryEmit("Shutter speed $speedName rejected ($error)")
            }
            onComplete(error)
        }
    }

    fun setAperture(aperture: SettingsDefinitions.Aperture, onComplete: (String?) -> Unit = {}) =
        setApertureByName(aperture.name, onComplete)

    internal fun setApertureByName(apertureName: String, onComplete: (String?) -> Unit = {}) {
        gateway.setAperture(apertureName) { error ->
            if (error != null) {
                Log.w(TAG, "setAperture($apertureName) failed: $error")
                _errorEvents.tryEmit("Aperture $apertureName rejected ($error)")
            }
            onComplete(error)
        }
    }

    /**
     * [onComplete] reports the rejection error (null on success) - MainActivity's
     * EV stepper uses this to hold at the last accepted value on failure
     * rather than let repeated presses keep walking further into rejected
     * territory. This was written believing the SDK exposed no capability
     * query for the camera's real supported EV range, unlike gimbal's
     * CapabilityKey.ADJUST_PITCH/YAW - true for the plain Camera-object API
     * this function itself uses, but not for the SDK as a whole:
     * refreshKeyBasedEvTelemetry() below queries
     * CameraKey.EXPOSURE_COMPENSATION_RANGE via the separate Key-based
     * interface and gets a real answer. That's now MainActivity's primary
     * source for the real range (see evStepValues/evBounds there); this
     * rejection-reaction path stays as a fallback for the brief window
     * before that query resolves, or if it ever fails.
     *
     * The error is passed through (not collapsed to a Boolean) because real
     * hardware testing found MULTIPLE distinct rejection reasons here, only
     * ONE of which ("Param Illegal") actually means "past the real EV
     * range" - "Cannot set the parameters in this state" (wrong exposure
     * mode, e.g. MANUAL) and "Invalid key for component" (observed
     * immediately after connect, before the camera's parameter model had
     * fully synced - a timing race, not a range signal) are different
     * failures entirely and must NOT be treated as "found the boundary,"
     * or a single transient/unrelated rejection permanently disables
     * stepping in that direction for the rest of the session. See
     * MainActivity.isOutOfRangeEvError.
     */
    fun setExposureCompensation(ev: SettingsDefinitions.ExposureCompensation, onComplete: (String?) -> Unit = {}) =
        setExposureCompensationByName(ev.name, onComplete)

    internal fun setExposureCompensationByName(evName: String, onComplete: (String?) -> Unit = {}) {
        gateway.setExposureCompensation(evName) { error ->
            if (error != null) {
                Log.w(TAG, "setExposureCompensation($evName) failed: $error")
                _errorEvents.tryEmit("Exposure compensation rejected ($error)")
            }
            onComplete(error)
        }
    }

    // --- Mode-to-editability logic (P/A/S/M semantics) ---
    //
    // CINE and UNKNOWN aren't covered by the P/A/S/M spec this was built
    // against, so they fall through to "nothing editable" below - a
    // conservative default, not a confirmed behavior for CINE.
    //
    // Each takes the live enum publicly (unchanged for MainActivity's
    // callers) but immediately delegates to a String-named internal
    // twin - see CameraGateway's doc comment for why: passing a live
    // SettingsDefinitions value as a parameter into ANY app-authored
    // function throws java.lang.VerifyError the moment it's actually
    // called from a plain JVM unit test, so the ByName twins are what
    // tests call directly.

    fun isIsoEditable(mode: SettingsDefinitions.ExposureMode): Boolean = isIsoEditableByName(mode.name)

    internal fun isIsoEditableByName(modeName: String): Boolean = modeName == "MANUAL"

    fun isShutterEditable(mode: SettingsDefinitions.ExposureMode): Boolean = isShutterEditableByName(mode.name)

    internal fun isShutterEditableByName(modeName: String): Boolean =
        modeName == "SHUTTER_PRIORITY" || modeName == "MANUAL"

    fun isApertureEditable(mode: SettingsDefinitions.ExposureMode): Boolean = isApertureEditableByName(mode.name)

    internal fun isApertureEditableByName(modeName: String): Boolean =
        modeName == "APERTURE_PRIORITY" || modeName == "MANUAL"

    /**
     * MANUAL excluded - confirmed via an on-device instrumented probe
     * (ExposureCompensationModeProbeTest) against the real Zenmuse X5:
     * setExposureCompensation succeeds in PROGRAM/APERTURE_PRIORITY/
     * SHUTTER_PRIORITY but is rejected outright in MANUAL with "Cannot set
     * the parameters in this state," every time, mode-switch and readout
     * both confirmed first. Makes sense once confirmed - in full MANUAL,
     * ISO/shutter/aperture are all already independently pinned, so there's
     * no remaining automatic-exposure component left for a compensation
     * value to bias. This was previously left true for all four (P/A/S/M)
     * only because the general P/A/S/M photography spec this was built
     * against calls for it, explicitly flagged as unverified for MANUAL -
     * now verified false.
     */
    fun isEvEditable(mode: SettingsDefinitions.ExposureMode): Boolean = isEvEditableByName(mode.name)

    internal fun isEvEditableByName(modeName: String): Boolean = when (modeName) {
        "PROGRAM", "SHUTTER_PRIORITY", "APERTURE_PRIORITY" -> true
        else -> false
    }
}
