package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import dji.common.camera.FocusState
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps focus mode, tap-to-focus target, and manual focus ring control.
 *
 * There's no dedicated focus capability key on this SDK surface, so
 * [focusState] doubles as the capability signal for the UI: null until the
 * first push, and FocusState.isLensMounted() == false once pushed means no
 * (or an unsupported) lens is attached. This controller doesn't gate
 * anything on that itself - it's on the UI layer to hide focus controls
 * when focusState is null or isLensMounted is false.
 *
 * AFC (continuous AF) is confirmed NOT implementable via the SDK on the
 * Zenmuse X5 - real hardware testing ("Param Illegal" on every attempt)
 * and DJI's own X5 documentation agree this is a genuine hardware/firmware
 * limitation, not a parameter bug. Recorded here rather than silently
 * dropped: if this ever needs revisiting, a SOFTWARE-emulated continuous
 * AF is plausible without any new SDK capability - setFocusRingValue()
 * plus getFocusRingValueUpperBound() already give ring-position control,
 * and the app already has a live-frame pipeline (VideoFrameProvider, built
 * for face detection) that could feed a classic contrast-detection loop
 * instead: periodically sample a frame, compute a sharpness metric (e.g.
 * Laplacian variance) over the center or the locked face's bounding box,
 * and hill-climb the ring value toward the sharpest reading. Untried and
 * unscoped - would need real tuning (step size, hunt-vs-settle behavior)
 * and is certain to behave nothing like real hardware CDAF/PDAF, but the
 * building blocks already exist in this codebase.
 */
class FocusController(private val gateway: CameraGateway = RealCameraGateway) {

    companion object {
        private const val TAG = "FocusController"

        // AFC deliberately excluded - see the class doc comment. It was
        // briefly kept in this cycle with rejections surfaced via
        // errorEvents instead, back when it was only suspected
        // camera-specific; now that it's confirmed never supported on this
        // hardware, leaving it selectable is pure friction with no path to
        // ever succeeding. setFocusMode(AFC) is still directly callable
        // (e.g. by a future software-AFC feature), just not cycled to.
        //
        // Names, not live enum values - see CameraGateway's doc comment
        // for why: cycleFocusMode() below calls setFocusModeByName() on
        // every press, and that must never pass a live SettingsDefinitions
        // value through a function call in a way a JVM unit test would
        // ever execute.
        private val CYCLE_ORDER = listOf("MANUAL", "AUTO")
    }

    private val _focusState = MutableStateFlow<FocusState?>(null)
    val focusState: StateFlow<FocusState?> = _focusState

    // One-shot events, not persistent state (a SharedFlow, not StateFlow -
    // StateFlow's distinct-until-changed would swallow the same rejection
    // happening twice in a row). UI should surface these as a toast/snackbar.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    // Tracks where cycleFocusMode() is in CYCLE_ORDER independently of the
    // real pushed focusMode. If it followed real state instead, a rejected
    // request (state unchanged) would make the next press compute the
    // exact same "next" again - permanently stuck unable to reach modes
    // past a rejected one. This always advances on every press regardless
    // of whether the previous request actually landed; what's actually
    // DISPLAYED elsewhere still comes from the real focusState, never this.
    private var cycleIndex: Int? = null

    // Range is always 0..this value - queried lazily and cached rather than
    // fetched up front, same idiom as DJIConnectionManager.pitchRangeDegrees(),
    // but as a StateFlow since UI (e.g. a focus ring slider) needs to react
    // once the real bound is known rather than polling a plain field.
    private val _focusRingUpperBound = MutableStateFlow<Int?>(null)
    val focusRingUpperBound: StateFlow<Int?> = _focusRingUpperBound

    /**
     * Registers the pushed-state callback with the currently bound camera.
     * Call this at the same connection-lifecycle points where
     * DJIConnectionManager re-registers its own callbacks inside
     * bindComponents() - initial product connect and any later component
     * swap - so focusState never keeps pointing at a stale/disconnected
     * camera.
     */
    fun startObserving() {
        DJIConnectionManager.camera?.setFocusStateCallback { state -> _focusState.value = state }
    }

    // onComplete (added for SoftwareAfcController.recalibrateAt()) reports
    // whether the mode switch actually landed - it needs to know AUTO
    // really took before calling setFocusTarget, since that call is
    // rejected outright while the real mode is still MANUAL (see
    // setFocusTarget's own doc comment), and needs to know MANUAL landed
    // again afterward before handing ring control back to the hill-climb.
    fun setFocusMode(mode: SettingsDefinitions.FocusMode, onComplete: (Boolean) -> Unit = {}) =
        setFocusModeByName(mode.name, onComplete)

    internal fun setFocusModeByName(modeName: String, onComplete: (Boolean) -> Unit = {}) {
        gateway.setFocusMode(modeName) { error ->
            if (error != null) {
                Log.w(TAG, "setFocusMode($modeName) failed: $error")
                _errorEvents.tryEmit("Focus mode $modeName not supported by this lens ($error)")
            }
            onComplete(error == null)
        }
    }

    /** Advances to the next mode in [CYCLE_ORDER] - see [cycleIndex]'s doc comment for why this doesn't follow real state. */
    fun cycleFocusMode() {
        val startIndex = cycleIndex ?: CYCLE_ORDER.indexOf(_focusState.value?.getFocusMode()?.name).coerceAtLeast(-1)
        val nextIndex = (startIndex + 1).mod(CYCLE_ORDER.size)
        cycleIndex = nextIndex
        setFocusModeByName(CYCLE_ORDER[nextIndex])
    }

    /**
     * xNorm/yNorm are normalized 0..1, the tap-to-focus entry point.
     * Whether this is honored while FocusMode == MANUAL, or requires
     * AUTO/AFC first, is unconfirmed - needs real-hardware testing; the
     * call is passed through as-is either way. SoftwareAfcController
     * .recalibrateAt() switches to AUTO first regardless, as the safe
     * assumption pending that confirmation.
     */
    fun setFocusTarget(xNorm: Float, yNorm: Float, onComplete: (Boolean) -> Unit = {}) {
        gateway.setFocusTarget(xNorm, yNorm) { error ->
            if (error != null) {
                Log.w(TAG, "setFocusTarget($xNorm, $yNorm) failed: $error")
            }
            onComplete(error == null)
        }
    }

    fun setFocusRingValue(value: Int) {
        gateway.setFocusRingValue(value) { error ->
            if (error != null) {
                Log.w(TAG, "setFocusRingValue($value) failed: $error")
            }
        }
    }

    /**
     * No-op once the bound is already known - mirrors
     * DJIConnectionManager.pitchRangeDegrees()'s query-once-and-cache
     * idiom. Note this cache lives on the controller instance itself and,
     * unlike DJIConnectionManager's gimbal-range caches, isn't cleared on a
     * mid-session lens swap - if that turns out to change the ring range,
     * a fresh FocusController (or an explicit reset) would be needed.
     */
    fun refreshFocusRingRange() {
        if (_focusRingUpperBound.value != null) return
        val camera = DJIConnectionManager.camera ?: return
        camera.getFocusRingValueUpperBound(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int) {
                _focusRingUpperBound.value = value
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getFocusRingValueUpperBound failed: ${error.description}")
            }
        })
    }

    /**
     * Focus Assistant is the camera's own built-in peaking/magnification
     * aid for manual focus - real hardware testing traced SoftwareAfcController's
     * "video keeps zooming in" symptom to this: it's a normal camera
     * feature (common across professional camera systems) that magnifies
     * the live view while the focus ring is being adjusted, which fires on
     * every single setFocusRingValue() call when driven continuously by a
     * software hill-climb rather than an occasional human nudge.
     */
    fun getFocusAssistantEnabled(callback: (enabledAF: Boolean, enabledMF: Boolean) -> Unit) {
        val camera = DJIConnectionManager.camera ?: return
        camera.getFocusAssistantSettings(object : CommonCallbacks.CompletionCallbackWithTwoParam<Boolean, Boolean> {
            override fun onSuccess(enabledAF: Boolean, enabledMF: Boolean) {
                callback(enabledAF, enabledMF)
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getFocusAssistantSettings failed: ${error.description}")
            }
        })
    }

    fun setFocusAssistantEnabled(enabledAF: Boolean, enabledMF: Boolean) {
        gateway.setFocusAssistantEnabled(enabledAF, enabledMF) { error ->
            if (error != null) {
                Log.w(TAG, "setFocusAssistantSettings failed: $error")
            }
        }
    }
}
