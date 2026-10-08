package io.github.mugenoesis.sidereal

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.audio.AudioRecorderController
import io.github.mugenoesis.sidereal.audio.AudioSourceController
import io.github.mugenoesis.sidereal.audio.AudioSourceKind
import io.github.mugenoesis.sidereal.camera.CameraLabels
import io.github.mugenoesis.sidereal.camera.BatteryLevel
import io.github.mugenoesis.sidereal.camera.CameraModeController
import io.github.mugenoesis.sidereal.camera.CameraStatusController
import io.github.mugenoesis.sidereal.camera.CameraStatusFormat
import io.github.mugenoesis.sidereal.camera.CycleHelpers
import io.github.mugenoesis.sidereal.camera.ExposureController
import io.github.mugenoesis.sidereal.camera.FocusController
import io.github.mugenoesis.sidereal.camera.FocusRingStepper
import io.github.mugenoesis.sidereal.camera.HistogramController
import io.github.mugenoesis.sidereal.camera.HistogramView
import io.github.mugenoesis.sidereal.camera.ImageTuningController
import io.github.mugenoesis.sidereal.camera.LearnedStepBounds
import io.github.mugenoesis.sidereal.camera.MediaFormatController
import io.github.mugenoesis.sidereal.camera.MeteringController
import io.github.mugenoesis.sidereal.camera.FocusLight
import io.github.mugenoesis.sidereal.camera.SoftwareAfcController
import io.github.mugenoesis.sidereal.camera.WhiteBalanceController
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.OsmoWifiChecker
import io.github.mugenoesis.sidereal.gimbal.GimbalMode
import io.github.mugenoesis.sidereal.gimbal.GimbalModeController
import io.github.mugenoesis.sidereal.gimbal.JoystickView
import io.github.mugenoesis.sidereal.gimbal.ManualGimbalController
import io.github.mugenoesis.sidereal.gimbal.TimedMoveController
import io.github.mugenoesis.sidereal.display.NightMode
import io.github.mugenoesis.sidereal.focus.FocusAssistController
import io.github.mugenoesis.sidereal.focus.FocusAssistView
import io.github.mugenoesis.sidereal.sequence.Attitude
import io.github.mugenoesis.sidereal.sequence.SequenceFeature
import io.github.mugenoesis.sidereal.tracking.FaceTrackingController
import io.github.mugenoesis.sidereal.tracking.FollowStyle
import io.github.mugenoesis.sidereal.tracking.TrackingState
import io.github.mugenoesis.sidereal.tracking.VideoFrameProvider
import io.github.mugenoesis.sidereal.zoom.ZoomController
import dji.common.camera.SettingsDefinitions
import dji.sdk.codec.DJICodecManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var videoPreview: TextureView
    private lateinit var faceOverlay: io.github.mugenoesis.sidereal.tracking.FaceOverlayView
    private lateinit var joystickView: JoystickView
    private lateinit var connectionStatus: android.widget.TextView

    private var codecManager: DJICodecManager? = null
    // Cached so onConfigurationChanged can reapply sizing on rotation
    // without waiting for another onVideoSizeChanged callback (the stream's
    // dimensions haven't changed, just the available screen space has).
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    private val cameraModeController = CameraModeController()

    private val frameCaptureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var frameCaptureRunnable: Runnable? = null
    private val frameCaptureIntervalMs = 1000L / 12 // matches videoFrameProvider's targetFps below

    private val manualController = ManualGimbalController()
    private val timedMoveController = TimedMoveController()
    private val zoomController = ZoomController()
    private val faceTrackingController = FaceTrackingController(zoomController)
    private val videoFrameProvider = VideoFrameProvider(faceTrackingController, targetFps = 12)
    private lateinit var gimbalModeController: GimbalModeController
    private lateinit var sequenceFeature: SequenceFeature

    private companion object {
        /** Zoom scale change per 50 ms tick at full right-stick deflection (~1.2x per second). */
        const val GAMEPAD_ZOOM_PER_TICK = 0.06f
    }

    private val exposureController = ExposureController()
    private val focusController = FocusController()
    private val softwareAfcController = SoftwareAfcController(focusController, brightLight = {
        exposureController.readout.value?.let { FocusLight.isBright(it.shutterSpeed.name, it.iso) } ?: false
    })
    private val meteringController = MeteringController()
    private val whiteBalanceController = WhiteBalanceController()
    private val histogramController = HistogramController()
    private val imageTuningController = ImageTuningController()
    private val mediaFormatController = MediaFormatController()
    private val audioRecorderController = AudioRecorderController()
    private val focusAssistController = FocusAssistController()
    private lateinit var cameraStatusController: CameraStatusController
    private lateinit var shootingControls: io.github.mugenoesis.sidereal.camera.ShootingControls
    private lateinit var cameraSounds: io.github.mugenoesis.sidereal.camera.CameraSoundsFeature
    private lateinit var gamepadInput: io.github.mugenoesis.sidereal.input.GamepadInput
    private var wearBridge: io.github.mugenoesis.sidereal.wear.WearBridge? = null
    private var wearLiveViewWanted = false
    private val gamepadMapper by lazy {
        io.github.mugenoesis.sidereal.input.GamepadMapper(
            gamepadActions,
            io.github.mugenoesis.sidereal.input.GamepadConfig.decode(AppPreferences.gamepadConfig),
            io.github.mugenoesis.sidereal.input.GamepadBindings.decode(AppPreferences.gamepadBindings)
        )
    }
    private var gamepadZoomRate = 0f

    /** Which settings tray (if any) is open - only one at a time, mirrors the rail icon's selected state. UI-only, not a controller concern. */
    private enum class SettingsPanel { NONE, EXPOSURE, WHITE_BALANCE, METERING, FOCUS, SEQUENCE, MORE }
    private var activeSettingsPanel = SettingsPanel.NONE

    // ExposureSettings.getISO() pushes back a plain Int (the camera's real
    // reported ISO number), not the SettingsDefinitions.ISO enum the setter
    // takes - unlike shutter/aperture/EV, which round-trip as the same enum
    // both ways. There's no reliable int-to-enum-member mapping exposed by
    // the SDK, so the ISO stepper steps its own locally-tracked enum
    // selection instead of trying to derive one from the readout; the
    // readout strip still displays the camera's real reported int value
    // when available; UNVERIFIED which one @hardware actually reports as
    // authoritative once they'd disagree.
    private var selectedIso = SettingsDefinitions.ISO.AUTO

    // Shutter/aperture/EV round-trip as the same enum both ways (unlike ISO
    // above), so these started out deriving "current" fresh from
    // exposureController.readout.value on every press instead of tracking
    // their own local var - but real hardware testing found that gets a
    // rapid run of taps stuck: the SDK's pushed readout update lags behind
    // taps (worse under the WiFi flakiness this rig sees in practice), so a
    // second "+1" tap before the first push lands recomputes the exact same
    // target as the first, over and over, making no progress. Null until the
    // first press, which seeds from the live readout; every press after
    // that advances from this instead - same fix, same reasoning as
    // FocusController.cycleIndex/WhiteBalanceController.cycleIndex.
    private var selectedShutterSpeed: SettingsDefinitions.ShutterSpeed? = null
    private var selectedAperture: SettingsDefinitions.Aperture? = null
    private var selectedEv: SettingsDefinitions.ExposureCompensation? = null

    // Serializes the actual outgoing SDK call for ISO/shutter/aperture/EV
    // stepping - real hardware testing (diagnostic logging of every pushed
    // ExposureSettings) found that firing one real setXxx() call per tap
    // lets several overlap in flight at once, which leaves the camera's
    // pushed readout stuck never reflecting any of them - confirmed this is
    // fixable (not a hardware limit) via a side-by-side test against Litchi
    // (a third-party app driving the same camera/lens): the same rapid
    // 5-tap burst that left this app's EV readout frozen for 20+ seconds
    // converged in Litchi within under a second and held steady. Litchi
    // appears to send one request at a time and wait for it to actually
    // complete before sending the next, rather than firing on a timer - a
    // flat debounce delay (tried first) reduced the call volume but a
    // *single*, cleanly-debounced call could still get stuck, so the fix
    // isn't about delay length, it's about never overlapping two real
    // requests. Each send*InFlight/send*Queued pair below implements that:
    // if a request is already in flight, a new tap just updates the queued
    // target (coalescing a burst to the latest value, same as before) -
    // once the in-flight one's completion callback fires, the queued
    // target (if any) is sent immediately, not after a fixed wait.
    private var evSendInFlight = false
    private var evQueuedTarget: SettingsDefinitions.ExposureCompensation? = null
    private var evQueuedTargetIndex = -1
    private var isoSendInFlight = false
    private var isoQueuedTarget: SettingsDefinitions.ISO? = null
    private var shutterSendInFlight = false
    private var shutterQueuedTarget: SettingsDefinitions.ShutterSpeed? = null
    private var apertureSendInFlight = false
    private var apertureQueuedTarget: SettingsDefinitions.Aperture? = null

    // Same lag-gets-you-stuck bug, same fix, for the plain-Int sharpness/
    // contrast/saturation steppers - imageTuningController.sharpness.value
    // et al. are pushed StateFlows too.
    private var selectedSharpness: Int? = null
    private var selectedContrast: Int? = null
    private var selectedSaturation: Int? = null

    // ExposureSettings has no mode field of its own (it only bundles
    // aperture/shutter/ISO/EV) and ExposureController exposes no StateFlow
    // for the current ExposureMode - so, same as selectedIso above, the
    // P/A/S/M segment's selected state is tracked locally here, updated
    // optimistically on tap (matching CameraModeController.isRecordingIntent's
    // pattern: what we last asked for, not a slow/absent pushed signal).
    // Restored from AppPreferences on launch and saved on every change -
    // unlike selectedIso, this is a real standing preference worth
    // remembering (which shooting mode you work in), not just a cycle
    // position.
    private var selectedExposureMode = AppPreferences.exposureMode

    // Cycle positions for the video-side cyclers - tracked here, not derived from the camera's state, same lesson as
    // every other cycle button (a rejected value must not trap the button). The lists themselves come from the camera
    // (MediaFormatController.videoModeRange etc.); the old hard-coded resolution list offered 30/60 fps modes that a
    // PAL camera rejects.
    private var selectedVideoResolutionIndex = -1
    private var videoStandardCycleIndex: Int? = null
    private var colorCycleIndex: Int? = null

    // Each of these enums ends with SDK sentinel members (FIXED/UNKNOWN) that
    // aren't real settable values - they're state-reporting placeholders, not
    // options a stepper should ever be able to land on. They sit at the tail
    // of each enum's ordinal order, so dropping them here doesn't disturb the
    // ordinal-as-array-index assumption step() relies on for every real value.
    // isoStepValues / shutterSpeedStepValues are vars: replaced by the real
    // per-camera range once CameraKey.ISO_RANGE / SHUTTER_SPEED_RANGE
    // resolve (see observeExposure()).
    private var isoStepValues = SettingsDefinitions.ISO.values()
        .filter { it != SettingsDefinitions.ISO.FIXED && it != SettingsDefinitions.ISO.UNKNOWN }
        .toTypedArray()
    private var shutterSpeedStepValues = SettingsDefinitions.ShutterSpeed.values()
        .filter { it != SettingsDefinitions.ShutterSpeed.UNKNOWN }
        .toTypedArray()
    private val apertureStepValues = SettingsDefinitions.Aperture.values()
        .filter { it != SettingsDefinitions.Aperture.UNKNOWN }
        .toTypedArray()
    // var, not val: replaced outright once the real per-camera range
    // arrives from CameraKey.EXPOSURE_COMPENSATION_RANGE (see
    // observeExposure()'s exposureController.evRange subscription) -
    // starts as the full SDK enum (±5.0 EV) as a fallback for the brief
    // window before that query resolves, or if it fails.
    private var evStepValues = SettingsDefinitions.ExposureCompensation.values()
        .filter { it != SettingsDefinitions.ExposureCompensation.FIXED && it != SettingsDefinitions.ExposureCompensation.UNKNOWN }
        .toTypedArray()
    // See LearnedStepBounds' doc comment: this was originally the only
    // way to learn the camera's real (narrower-than-the-full-SDK-enum) EV
    // range, by remembering wherever a real rejection ("Param Illegal") is
    // hit rather than resending the same doomed value on every subsequent
    // press. CameraKey.EXPOSURE_COMPENSATION_RANGE (see evStepValues
    // above) now gives the real range directly, but this stays as a
    // fallback for the same brief window / query-failure case, and as a
    // defensive backstop in case the queried range is ever itself wrong -
    // var so it can be reconstructed at the new size when evStepValues
    // changes.
    private var evBounds = LearnedStepBounds(evStepValues.size)

    // Independent cycle positions for anti-flicker/photo-format/photo-
    // aspect-ratio/video-format, mirroring FocusController.cycleIndex and
    // WhiteBalanceController.cycleIndex - real hardware testing found the
    // original "next = indexOf(real current value) + 1" approach gets
    // permanently stuck the moment any single option in the list is
    // rejected by the camera: the real value never changes, so the next
    // press computes the exact same rejected "next" again, forever, unable
    // to reach anything past it (including cycling back around to the
    // start). Null until the first press, which seeds it from whatever the
    // real camera value is at that moment; every press after that just
    // advances regardless of whether the previous request actually landed.
    private var antiFlickerCycleIndex: Int? = null
    private var photoFormatCycleIndex: Int? = null
    private var photoAspectRatioCycleIndex: Int? = null
    private var videoFormatCycleIndex: Int? = null

    // A real user request: the old free-scrubbing 1s..300s slider was
    // "useless in its current state" (300 discrete steps most of which
    // don't make sense for a pan shot) - replaced with a short list of
    // sensible presets, cycled like every other stepper button in this UI.
    private val moveDurationOptionsMs = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L)

    // Snaps whatever was persisted from a session before this change (the
    // old slider could store any of 300 values) to the nearest preset,
    // rather than silently keeping an option the new cycle button can
    // never land back on.
    private var pendingMoveDurationMs = moveDurationOptionsMs.minByOrNull {
        kotlin.math.abs(it - AppPreferences.moveDurationMs)
    } ?: moveDurationOptionsMs[1]

    // MediaLibraryController reads and writes MediaStore (findExistingDownload()
    // queries it, saveToMediaStore() inserts into it) - both permissions
    // were declared in the manifest but never actually in this
    // runtime-request list, so neither was ever granted. Confirmed on real
    // hardware, on an API 28 (Android 9) test device, both gaps: reading
    // crashed with a SecurityException the first time anything called it
    // (tapping a video in the media library), and writing failed silently
    // into a toast ("Couldn't save ... to gallery") the first time an
    // actual download completed - meaning downloads to the gallery likely
    // never worked at all on this device before now, pre-existing and
    // unrelated to whatever specifically was being tested when each was
    // found. WRITE_EXTERNAL_STORAGE only matters up to API 28 (the
    // manifest's own maxSdkVersion cap - scoped storage on 29+ means an
    // app's own MediaStore inserts don't need it there) - this device
    // being exactly API 28 is what surfaced it. READ_EXTERNAL_STORAGE is
    // similarly only relevant below API 33 (targetSdk here is 33) -
    // READ_MEDIA_IMAGES/READ_MEDIA_VIDEO are what actually matter on 33+.
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Only for AudioSourceController's Bluetooth-mic name lookup - see
        // its doc comment. Harmless to request even if the user never
        // plugs in/pairs a Bluetooth mic.
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    } + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        // Matches the manifest's own maxSdkVersion="28" cap on
        // WRITE_EXTERNAL_STORAGE (P = API 28) - requesting it on a newer
        // device the manifest doesn't grant it for would be a no-op at
        // best, so this only asks for it where it actually applies.
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Matches Litchi's behavior on this same rig - a camera/gimbal
        // controller left mid-flight or mid-recording shouldn't have the
        // screen dim or lock out from under the operator.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        SystemBars.applyInsets(this, immersive = true)
        NightMode.apply(window)

        gimbalModeController = GimbalModeController(
            manualController, timedMoveController, faceTrackingController
        )

        requestPermissionsIfNeeded()
        bindViews()
        bindCameraSettingsViews()
        bindSequenceFeature()
        bindCameraStatus()
        bindGamepad()
        shootingControls = io.github.mugenoesis.sidereal.camera.ShootingControls(this, mediaFormatController)
        cameraSounds = io.github.mugenoesis.sidereal.camera.CameraSoundsFeature(
            this, softwareAfcController.isLocked, sequenceRunning = { sequenceFeature.controller.isRunning.value }
        )
        shootingControls.onTimerTick = cameraSounds::onTimerTick
        io.github.mugenoesis.sidereal.input.GamepadSettingsFeature(this, gamepadMapper)
        observeConnectionState()
        observeWifiState()
        observeComponentChanges()
        observeFaceTracking()
        observeZoomCapability()
        observeCameraState()
        observeExposure()
        observeFocus()
        observeMetering()
        observeWhiteBalance()
        observeHistogram()
        observeMoreSettings()
        observeTimedMove()
        updateGimbalModeUi()
    }

    // --- Game controller support: the pure mapper decides WHAT was asked; these do it with the same code paths
    // the touch controls use, so every guard (recording lock-out, sequence lock-out, capability checks) still applies.
    private val gamepadActions = object : io.github.mugenoesis.sidereal.input.GamepadActions {
        override fun gimbal(yaw: Float, pitch: Float) {
            if (yaw == 0f && pitch == 0f) manualController.onJoystickReleased() else manualController.onJoystickMoved(yaw, pitch)
        }

        override fun zoom(rate: Float) {
            gamepadZoomRate = rate
        }

        override fun shutter() {
            val button = findViewById<android.widget.Button>(R.id.btnShutter)
            if (button.isEnabled) button.performClick()
        }

        override fun togglePhotoVideo() {
            if (DJIConnectionManager.cameraSystemState.value?.isRecording == true) {
                showErrorToast("Stop recording before switching mode")
                return
            }
            val video = DJIConnectionManager.cameraSystemState.value?.mode == SettingsDefinitions.CameraMode.RECORD_VIDEO
            cameraModeController.setMode(if (video) SettingsDefinitions.CameraMode.SHOOT_PHOTO else SettingsDefinitions.CameraMode.RECORD_VIDEO)
        }

        // Hold = show the tap-to-focus crosshair in the middle (the stick keeps moving the gimbal under it);
        // release = hide it and focus there. A quick tap does both at once.
        override fun autofocusHold(pressed: Boolean) {
            val overlay = findViewById<io.github.mugenoesis.sidereal.tracking.FaceOverlayView>(R.id.faceOverlay)
            if (pressed) {
                overlay.showAimReticle(0.5f, 0.5f)
            } else {
                overlay.hideAimReticle()
                overlay.flashReticle(0.5f, 0.5f)
                focusController.setFocusTarget(0.5f, 0.5f)
            }
        }

        override fun focusRing(direction: Int) {
            if (findViewById<android.view.View>(R.id.focusRingRow).visibility != android.view.View.VISIBLE) {
                // The ring only does something in manual focus - switch there first, the next step moves it.
                gamepadRingValue = null
                focusController.setFocusMode(SettingsDefinitions.FocusMode.MANUAL)
                return
            }
            val bar = findViewById<SeekBar>(R.id.focusRingSeekBar)
            val known = gamepadRingValue
            if (known != null) {
                applyGamepadRing(FocusRingStepper.next(known, direction, bar.max))
                return
            }
            // First nudge since manual focus engaged: start from where the camera's ring really is, not from the
            // slider (which only moves when touched).
            DJIConnectionManager.camera?.getFocusRingValue(object : dji.common.util.CommonCallbacks.CompletionCallbackWith<Int> {
                override fun onSuccess(value: Int) {
                    runOnUiThread { applyGamepadRing(FocusRingStepper.next(value, direction, bar.max)) }
                }

                override fun onFailure(error: dji.common.error.DJIError) {
                    android.util.Log.w("MainActivity", "getFocusRingValue failed: ${error.description}")
                }
            })
        }

        override fun exposureMode(direction: Int) {
            val order = listOf(
                SettingsDefinitions.ExposureMode.PROGRAM,
                SettingsDefinitions.ExposureMode.APERTURE_PRIORITY,
                SettingsDefinitions.ExposureMode.SHUTTER_PRIORITY,
                SettingsDefinitions.ExposureMode.MANUAL
            )
            val current = order.indexOf(selectedExposureMode).coerceAtLeast(0)
            selectExposureMode(order[(current + direction).mod(order.size)])
        }

        override fun recenter() = manualController.onDoubleTap()
        override fun toggleAeLock() = shootingControls.toggleAeLock()
        override fun cycleGrid() = shootingControls.cycleGrid()

        // Same rules as the on-screen buttons: they are disabled when the camera won't take an EV change (Manual
        // mode, recording) or the step would run past the end of the range, so follow their state.
        override fun exposureCompensation(direction: Int) {
            val button = findViewById<android.widget.Button>(if (direction > 0) R.id.btnEvUp else R.id.btnEvDown)
            if (button.isEnabled) stepEv(direction)
        }
    }

    /** The manual-focus ring value the pad last set (null until its first nudge reads the camera's own). */
    private var gamepadRingValue: Int? = null

    private fun applyGamepadRing(value: Int) {
        gamepadRingValue = value
        findViewById<SeekBar>(R.id.focusRingSeekBar).progress = value
        focusController.setFocusRingValue(value)
    }

    // --- Watch remote: what a connected Wear OS watch can make this app do (see wear/WearBridge). ---
    private val wearHost = object : io.github.mugenoesis.sidereal.wear.WearHost {
        override fun status() = io.github.mugenoesis.sidereal.wear.WearStatusBuilder.build(
            camera = cameraStatusController.status.value,
            connected = DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected,
            sequence = sequenceFeature.controller.let { it.settings.value.mode.label to it.progress.value }
        )

        override fun capture(): String? {
            val button = findViewById<android.widget.Button>(R.id.btnShutter)
            if (DJIConnectionManager.cameraSystemState.value?.mode == SettingsDefinitions.CameraMode.RECORD_VIDEO) return "Switch to photo mode first"
            if (!button.isEnabled) return "The camera is busy"
            button.performClick()
            return null
        }

        override fun toggleRecord(): String? {
            val button = findViewById<android.widget.Button>(R.id.btnShutter)
            if (!button.isEnabled) return "The camera is busy"
            button.performClick()
            return null
        }

        override fun toggleMode(): String? { gamepadActions.togglePhotoVideo(); return null }
        override fun recenter() = manualController.onDoubleTap()
        override fun gimbal(yaw: Float, pitch: Float) = gamepadActions.gimbal(yaw, pitch)

        override fun setLiveView(on: Boolean) {
            wearLiveViewWanted = on
            updateFrameCaptureState()
        }

        override fun connectOsmo(): String? {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return "This phone's Android version can't join the Osmo's WiFi from the app"
            // The system shows its own confirmation on the phone the first time.
            runOnUiThread { joinOsmoWifi() }
            return null
        }
    }

    private val osmoWifiConnector by lazy { io.github.mugenoesis.sidereal.dji.OsmoWifiConnector(this) }

    /** Joins the Osmo's WiFi from inside the app; falls back to Android's WiFi settings if that isn't possible. */
    private fun joinOsmoWifi() {
        osmoWifiConnector.connect(AppPreferences.osmoWifiPassphrase) { error ->
            runOnUiThread {
                if (error != null) showErrorToast(error)
            }
        }
    }

    private fun promptForOsmoWifiPassword() {
        val input = android.widget.EditText(this).apply {
            setText(AppPreferences.osmoWifiPassphrase)
            setSingleLine()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Osmo WiFi password")
            .setMessage("The factory default is ${io.github.mugenoesis.sidereal.dji.OsmoWifiPassphrase.DEFAULT}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val problem = io.github.mugenoesis.sidereal.dji.OsmoWifiPassphrase.validate(input.text.toString())
                if (problem != null) showErrorToast(problem)
                else AppPreferences.osmoWifiPassphrase = io.github.mugenoesis.sidereal.dji.OsmoWifiPassphrase.clean(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
        NightMode.apply(dialog)
    }

    private fun bindGamepad() {
        // A button chord on the controller opens the reticle drill screen.
        gamepadMapper.onChord = {
            runOnUiThread { startActivity(android.content.Intent(this, io.github.mugenoesis.sidereal.drill.DrillActivity::class.java)) }
        }
        gamepadInput = io.github.mugenoesis.sidereal.input.GamepadInput(this, gamepadMapper) {
            val capability = zoomController.capability.value
            if (gamepadZoomRate != 0f && capability.supported) zoomController.adjustZoomBy(gamepadZoomRate * GAMEPAD_ZOOM_PER_TICK)
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        (::gamepadInput.isInitialized && gamepadInput.handleMotion(event)) || super.dispatchGenericMotionEvent(event)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (::gamepadInput.isInitialized && gamepadInput.handleKey(event)) || super.dispatchKeyEvent(event)

    private fun bindCameraStatus() {
        cameraStatusController = CameraStatusController(lifecycleScope)
        val text = findViewById<android.widget.TextView>(R.id.cameraStatusText)
        cameraStatusController.status
            .onEach { status ->
                val connected = DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected
                text.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
                text.text = "${CameraStatusFormat.battery(status.batteryPercent)}  ·  ${CameraStatusFormat.card(status)}"
                val warn = CameraStatusFormat.cardWarning(status) || CameraStatusFormat.batteryLevel(status.batteryPercent) == BatteryLevel.CRITICAL
                text.setTextColor(if (warn) 0xFFFFB74D.toInt() else android.graphics.Color.WHITE)
            }
            .launchIn(lifecycleScope)
        DJIConnectionManager.connectionState
            .onEach { cameraStatusController.status.value.let { _ -> text.visibility = if (it is DJIConnectionManager.ConnectionState.ProductConnected) android.view.View.VISIBLE else android.view.View.GONE } }
            .launchIn(lifecycleScope)
    }

    private fun bindSequenceFeature() {
        sequenceFeature = SequenceFeature(
            activity = this,
            tray = findViewById(R.id.sequenceTray),
            banner = findViewById(R.id.sequenceBanner),
            shutterButton = findViewById(R.id.btnShutter),
            shutterNameProvider = { exposureController.readout.value?.shutterSpeed?.name },
            rampIo = io.github.mugenoesis.sidereal.sequence.RampIo(
                refreshRanges = { exposureController.refreshKeyBasedEvTelemetry() },
                currentAperture = { exposureController.readout.value?.getAperture()?.name },
                apertureNames = { SettingsDefinitions.Aperture.values().map { it.name } },
                setAperture = { name, done -> exposureController.setApertureByNameForRamp(name, done) },
                shutterOptions = {
                    exposureController.shutterRange.value.orEmpty().mapNotNull { s ->
                        io.github.mugenoesis.sidereal.camera.ShutterLogic.exposureSeconds(s.name)?.let { io.github.mugenoesis.sidereal.sequence.ShutterOption(s.name, it) }
                    }
                },
                isoOptions = {
                    exposureController.isoRange.value.orEmpty().mapNotNull { i ->
                        Regex("ISO_(\\d+)").matchEntire(i.name)?.let { io.github.mugenoesis.sidereal.sequence.IsoOption(i.name, it.groupValues[1].toInt()) }
                    }
                },
                currentNames = { exposureController.readout.value?.let { it.shutterSpeed.name to "ISO_${it.iso}" } },
                meanLuma = { io.github.mugenoesis.sidereal.camera.HistogramModel.stats(histogramController.histogramData.value)?.meanLuma },
                setMetering = { on ->
                    if (on) histogramController.activate()
                    else if (findViewById<HistogramView>(R.id.histogramView).visibility != android.view.View.VISIBLE) histogramController.deactivate()
                }
            ),
            pointsProvider = {
                fun TimedMoveController.Point?.toAttitude() = this?.let { Attitude(it.pitch.toFloat(), it.yaw.toFloat()) }
                timedMoveController.capturedA.toAttitude() to timedMoveController.capturedB.toAttitude()
            }
        )
        findViewById<android.widget.ImageButton>(R.id.btnSequenceRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.SEQUENCE) }

        // Back would finish the activity and cancel the sequence with it; while one runs, back only backgrounds the app
        // (the keep-alive service carries on, and the notification brings the user back).
        val keepRunningOnBack = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, keepRunningOnBack)
        sequenceFeature.controller.isRunning
            .onEach { running -> keepRunningOnBack.isEnabled = running }
            .launchIn(lifecycleScope)

        // A running sequence owns the camera and gimbal: the pad is locked out until it ends.
        sequenceFeature.controller.isRunning
            .onEach { running -> gamepadMapper.locked = running }
            .launchIn(lifecycleScope)

        findViewById<FocusAssistView>(R.id.focusAssistView).bind(focusAssistController, lifecycleScope)
        val starAssistButton = findViewById<android.widget.Button>(R.id.btnStarAssist)
        starAssistButton.setOnClickListener { focusAssistController.setEnabled(!focusAssistController.enabled.value) }
        focusAssistController.enabled
            .onEach { on ->
                starAssistButton.text = if (on) "On" else "Off"
                updateFrameCaptureState()
            }
            .launchIn(lifecycleScope)
    }

    private fun observeTimedMove() {
        timedMoveController.state
            .onEach { updateTimedMoveUi() }
            .launchIn(lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        gamepadInput.start()
        wearBridge = io.github.mugenoesis.sidereal.wear.WearBridge(this, lifecycleScope, wearHost).also { it.start() }
        OsmoWifiChecker.start(this)
    }

    override fun onStop() {
        super.onStop()
        gamepadInput.stop()
        wearBridge?.stop()
        wearBridge = null
        wearLiveViewWanted = false
        OsmoWifiChecker.stop(this)
    }

    /**
     * AndroidManifest declares configChanges for orientation/screenSize, so
     * rotating (now sensorLandscape, both landscape orientations) doesn't
     * recreate this activity - which matters here specifically, since a
     * recreate would tear down and reconstruct the live DJI connection,
     * gimbal/tracking controllers, and video surface from scratch, losing
     * whatever mode/lock state was live at the time. All that's actually
     * needed on rotation is reapplying the video letterbox/pillarbox sizing,
     * since the available screen space changed but the stream's own
     * dimensions didn't - videoPreview.post defers until the new layout
     * pass has actually happened, so the freshly-rotated bounds are read.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            applyVideoAspectRatio(lastVideoWidth, lastVideoHeight)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun bindViews() {
        videoPreview = findViewById(R.id.videoPreview)
        faceOverlay = findViewById(R.id.faceOverlay)
        joystickView = findViewById(R.id.joystickView)
        connectionStatus = findViewById(R.id.connectionStatus)

        videoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                // Pure Surface rendering, never enabledYuvData(true) - real
                // hardware testing found that toggle causes a genuine, hard
                // (non-recoverable-without-restart) freeze on this decoder:
                // it tears down and rebuilds the whole MediaCodec instance,
                // and DJI's own internal GL render context for this Surface
                // doesn't reliably come back afterward. See
                // VideoFrameProvider's doc comment for the full writeup and
                // the logcat evidence. Face detection instead reads already-
                // rendered frames back off this same Surface via
                // TextureView.getBitmap() - see startFrameCapture below.
                codecManager = DJICodecManager(this@MainActivity, surface, width, height)
                // videoPreview otherwise just stretches to fill whatever
                // space is above controlBar, which won't generally match
                // the stream's real aspect ratio (likely 16:9, but read
                // the actual reported size rather than assuming) -
                // distorting the picture. This resizes it to fit that
                // space without stretching once the real dimensions are
                // known, letterboxing/pillarboxing as needed.
                codecManager?.setOnVideoSizeChangedListener { videoWidth, videoHeight ->
                    runOnUiThread { applyVideoAspectRatio(videoWidth, videoHeight) }
                }
            }

            // Real hardware testing found the visible video content ignoring
            // videoPreview's actual current layout bounds entirely after
            // applyVideoAspectRatio resized it for letterboxing - measured
            // on screen at a completely different position/size than what
            // Android's own layout system reported for the view. Root
            // cause: DJICodecManager's internal GL render manager scales/
            // positions the decoded frame itself (not a plain TextureView
            // auto-stretch), configured once at construction time from
            // whatever width/height it was originally given - it has no
            // way to know the view was resized afterward unless explicitly
            // told via onSurfaceSizeChanged(), which this callback existed
            // to forward and never did (a no-op). Forwarding it here is
            // what actually keeps DJI's own rendering in sync with our
            // letterbox/pillarbox sizing on every resize (including
            // rotation, via onConfigurationChanged's re-apply).
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                codecManager?.onSurfaceSizeChanged(width, height, 0)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                codecManager?.cleanSurface()
                codecManager?.destroyCodec()
                codecManager = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // MANUAL-mode gestures (drag-the-stick, double-tap-recenter) are
        // owned entirely by joystickView now - it only arms itself for
        // MANUAL (see switchGimbalMode), so in other modes these events
        // fall through to faceOverlay/videoPreview underneath.
        joystickView.onStickMoved = { x, y -> manualController.onJoystickMoved(x, y) }
        joystickView.onStickReleased = { manualController.onJoystickReleased() }
        joystickView.onDoubleTap = { manualController.onDoubleTap() }
        // Joystick claims ACTION_DOWN itself while armed, so a plain tap
        // (not a real drag) is reported back here instead of being treated
        // as stick input - forward it on to face-tap-select, which sits
        // underneath and would otherwise never see it.
        joystickView.onTap = { x, y -> faceOverlay.handleExternalTap(x, y) }

        findViewById<android.widget.Button>(R.id.btnManualMode).setOnClickListener {
            switchGimbalMode(GimbalMode.MANUAL)
        }

        findViewById<android.widget.Button>(R.id.btnTimedMoveMode).setOnClickListener {
            switchGimbalMode(GimbalMode.TIMED_MOVE)
        }

        // A real user request: in MANUAL mode (the only mode the joystick
        // is armed in, so the only mode a new point can actually be
        // positioned in) these capture the current attitude, same as
        // before. In TIMED_MOVE mode, tapping A/B instead recalls that
        // captured point - re-capturing would just silently overwrite it
        // with wherever the gimbal happens to be sitting from the last
        // preview/run, which was the actual bug being reported.
        findViewById<android.widget.Button>(R.id.btnSetA).setOnClickListener {
            if (gimbalModeController.currentMode == GimbalMode.TIMED_MOVE) {
                gimbalModeController.previewPointA()
            } else {
                timedMoveController.captureA()
                capturedA = true
                updateTimedMoveUi()
            }
        }

        findViewById<android.widget.Button>(R.id.btnSetB).setOnClickListener {
            if (gimbalModeController.currentMode == GimbalMode.TIMED_MOVE) {
                gimbalModeController.previewPointB()
            } else {
                timedMoveController.captureB()
                capturedB = true
                updateTimedMoveUi()
            }
        }

        findViewById<android.widget.Button>(R.id.btnStartMove).setOnClickListener {
            gimbalModeController.startTimedMove(pendingMoveDurationMs)
        }

        findViewById<android.widget.Button>(R.id.btnDurationCycle).setOnClickListener {
            val idx = moveDurationOptionsMs.indexOf(pendingMoveDurationMs).let { if (it < 0) 0 else it }
            pendingMoveDurationMs = moveDurationOptionsMs[(idx + 1) % moveDurationOptionsMs.size]
            AppPreferences.moveDurationMs = pendingMoveDurationMs
            updateDurationUi()
        }
        updateDurationUi()

        findViewById<android.widget.ToggleButton>(R.id.toggleFaceTrack).setOnCheckedChangeListener { _, isChecked ->
            switchGimbalMode(if (isChecked) GimbalMode.FACE_TRACK else GimbalMode.MANUAL)
            updateFrameCaptureState()
        }

        // Restores the remembered follow style and applies it to the
        // controller explicitly (rather than relying on setting isChecked
        // to trigger the listener below, which Android only does when the
        // value actually changes from the button's XML default) before
        // wiring the listener that saves future changes.
        val restoredFollowStyle = AppPreferences.followStyle
        faceTrackingController.setFollowStyle(restoredFollowStyle)
        findViewById<android.widget.ToggleButton>(R.id.toggleFollowStyle).apply {
            isChecked = restoredFollowStyle == FollowStyle.TRAIL
            setOnCheckedChangeListener { _, isChecked ->
                val style = if (isChecked) FollowStyle.TRAIL else FollowStyle.LOCKED_ON
                faceTrackingController.setFollowStyle(style)
                AppPreferences.followStyle = style
            }
        }

        // btnPhotoMode/btnVideoMode/btnShutter reflect real camera state
        // reactively via observeCameraState(), not just what was last
        // tapped - these listeners only fire the request itself.
        findViewById<android.widget.ImageButton>(R.id.btnPhotoMode).setOnClickListener {
            cameraModeController.setMode(dji.common.camera.SettingsDefinitions.CameraMode.SHOOT_PHOTO)
        }
        findViewById<android.widget.ImageButton>(R.id.btnVideoMode).setOnClickListener {
            cameraModeController.setMode(dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO)
        }

        findViewById<android.widget.Button>(R.id.btnShutter).setOnClickListener {
            if (!shootingControls.handleShutter { cameraModeController.triggerShutter() }) {
                cameraModeController.triggerShutter()
            }
        }

        // Browsing media switches the camera to CameraMode.MEDIA_DOWNLOAD,
        // which this camera can't do mid-recording - and separately,
        // CameraModeController's doc comment explains stop-record is
        // unreliable on this hardware/SDK combo, so refusing here avoids
        // compounding that with an unpredictable mode-switch-while-recording
        // interaction rather than a clean, known failure mode.
        findViewById<android.widget.ImageButton>(R.id.btnMediaLibrary).setOnClickListener {
            if (DJIConnectionManager.cameraSystemState.value?.isRecording == true) {
                android.widget.Toast.makeText(this, "Stop recording before browsing media", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                startActivity(android.content.Intent(this, io.github.mugenoesis.sidereal.media.MediaLibraryActivity::class.java))
            }
        }

        // Zoom: manual slider only does anything while auto-zoom is off -
        // the two would otherwise fight each other for control of the lens.
        findViewById<SeekBar>(R.id.zoomSeekBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val cap = zoomController.capability.value
                    if (!cap.supported) return
                    val scale = cap.minScale + (cap.maxScale - cap.minScale) * (progress / 100f)
                    zoomController.setZoomScale(scale)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        // Same explicit-restore-then-wire-listener pattern as
        // toggleFollowStyle above. NOT using .apply{} here - zoomSeekBar is
        // a sibling of toggleAutoZoom, not a descendant, so a findViewById
        // call inside .apply{} would resolve against the ToggleButton's own
        // subtree (returning null) rather than the Activity's.
        val restoredAutoZoom = AppPreferences.autoZoomEnabled
        faceTrackingController.setAutoZoomEnabled(restoredAutoZoom)
        findViewById<SeekBar>(R.id.zoomSeekBar).isEnabled = !restoredAutoZoom
        findViewById<android.widget.ToggleButton>(R.id.toggleAutoZoom).apply {
            isChecked = restoredAutoZoom
            setOnCheckedChangeListener { _, isChecked ->
                faceTrackingController.setAutoZoomEnabled(isChecked)
                AppPreferences.autoZoomEnabled = isChecked
                // Qualified receiver deliberate - see the comment above on
                // why a plain findViewById here would resolve against this
                // ToggleButton's own subtree instead of the Activity's.
                this@MainActivity.findViewById<SeekBar>(R.id.zoomSeekBar).isEnabled = !isChecked
            }
        }

        faceOverlay.onFaceTapped = { trackingId ->
            faceTrackingController.selectFace(trackingId)
        }
        faceOverlay.onDeselectRequested = {
            if (faceTrackingController.trackingState.value == TrackingState.LOCKED) {
                faceTrackingController.deselect()
            }
        }
        faceOverlay.onFrameTargetDragged = { x, y ->
            faceTrackingController.setTargetPosition(x.toDouble(), y.toDouble())
        }

        // Sync joystickView.armed to the starting mode (MANUAL).
        switchGimbalMode(gimbalModeController.currentMode)
    }

    /**
     * Wires the settings rail, its four trays, the exposure readout strip,
     * and the histogram toggle. Kept separate from bindViews() purely for
     * readability given how much this adds - same views/DJIConnectionManager
     * access patterns throughout.
     */
    private fun bindCameraSettingsViews() {
        val histogramView = findViewById<HistogramView>(R.id.histogramView)
        findViewById<android.widget.ImageButton>(R.id.btnHistogramToggle).setOnClickListener {
            val showing = histogramView.visibility == android.view.View.VISIBLE
            if (showing) {
                histogramView.visibility = android.view.View.GONE
                histogramController.deactivate()
            } else {
                histogramView.visibility = android.view.View.VISIBLE
                histogramController.activate()
            }
        }

        findViewById<android.widget.ImageButton>(R.id.btnNightMode).setOnClickListener {
            NightMode.enabled = !NightMode.enabled
            NightMode.apply(window)
        }

        findViewById<android.widget.ImageButton>(R.id.readoutFocusIcon).setOnClickListener {
            setDefaultTapAction(io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.TAP_TO_FOCUS)
        }
        findViewById<android.widget.ImageButton>(R.id.readoutMeteringIcon).setOnClickListener {
            setDefaultTapAction(io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.SPOT_METER)
        }

        findViewById<android.widget.ImageButton>(R.id.btnExposureRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.EXPOSURE) }
        findViewById<android.widget.ImageButton>(R.id.btnWhiteBalanceRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.WHITE_BALANCE) }
        findViewById<android.widget.ImageButton>(R.id.btnMeteringRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.METERING) }
        findViewById<android.widget.ImageButton>(R.id.btnFocusRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.FOCUS) }
        findViewById<android.widget.ImageButton>(R.id.btnMoreRail).setOnClickListener { setActiveSettingsPanel(SettingsPanel.MORE) }

        findViewById<android.widget.Button>(R.id.btnModeP).setOnClickListener { selectExposureMode(SettingsDefinitions.ExposureMode.PROGRAM) }
        findViewById<android.widget.Button>(R.id.btnModeA).setOnClickListener { selectExposureMode(SettingsDefinitions.ExposureMode.APERTURE_PRIORITY) }
        findViewById<android.widget.Button>(R.id.btnModeS).setOnClickListener { selectExposureMode(SettingsDefinitions.ExposureMode.SHUTTER_PRIORITY) }
        findViewById<android.widget.Button>(R.id.btnModeM).setOnClickListener { selectExposureMode(SettingsDefinitions.ExposureMode.MANUAL) }

        findViewById<android.widget.Button>(R.id.btnIsoDown).setOnClickListener { stepIso(-1) }
        findViewById<android.widget.Button>(R.id.btnIsoUp).setOnClickListener { stepIso(1) }

        findViewById<android.widget.Button>(R.id.btnShutterSpeedDown).setOnClickListener { stepShutterSpeed(-1) }
        findViewById<android.widget.Button>(R.id.btnShutterSpeedUp).setOnClickListener { stepShutterSpeed(1) }
        findViewById<android.widget.Button>(R.id.btnApertureDown).setOnClickListener { stepAperture(-1) }
        findViewById<android.widget.Button>(R.id.btnApertureUp).setOnClickListener { stepAperture(1) }

        findViewById<android.widget.Button>(R.id.btnEvDown).setOnClickListener { stepEv(-1) }
        findViewById<android.widget.Button>(R.id.btnEvUp).setOnClickListener { stepEv(1) }

        findViewById<android.widget.ImageButton>(R.id.btnWbCycle).setOnClickListener { whiteBalanceController.cyclePreset() }
        findViewById<SeekBar>(R.id.wbKelvinSeekBar).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val range = whiteBalanceController.customTemperatureRangeKelvin.value ?: return
                whiteBalanceController.setCustomColorTemperature(range.first + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<android.widget.ImageButton>(R.id.btnMeteringCycle).setOnClickListener {
            val values = SettingsDefinitions.MeteringMode.values().filter { it != SettingsDefinitions.MeteringMode.UNKNOWN }
            val idx = values.indexOf(meteringController.meteringMode.value)
            meteringController.setMeteringMode(values[(idx + 1).coerceAtLeast(0) % values.size])
        }

        findViewById<android.widget.ImageButton>(R.id.btnFocusCycle).setOnClickListener {
            cycleFocusModeWithSoftwareAfc()
        }
        findViewById<SeekBar>(R.id.focusRingSeekBar).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                focusController.setFocusRingValue(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<android.widget.Button>(R.id.btnSharpnessDown).setOnClickListener { stepSharpness(-1) }
        findViewById<android.widget.Button>(R.id.btnSharpnessUp).setOnClickListener { stepSharpness(1) }
        findViewById<android.widget.Button>(R.id.btnContrastDown).setOnClickListener { stepContrast(-1) }
        findViewById<android.widget.Button>(R.id.btnContrastUp).setOnClickListener { stepContrast(1) }
        findViewById<android.widget.Button>(R.id.btnSaturationDown).setOnClickListener { stepSaturation(-1) }
        findViewById<android.widget.Button>(R.id.btnSaturationUp).setOnClickListener { stepSaturation(1) }
        findViewById<android.widget.Button>(R.id.btnAntiFlickerCycle).setOnClickListener { cycleAntiFlicker() }
        findViewById<android.widget.Button>(R.id.btnPhotoFormatCycle).setOnClickListener { cyclePhotoFormat() }
        findViewById<android.widget.Button>(R.id.btnPhotoAspectCycle).setOnClickListener { cyclePhotoAspectRatio() }
        findViewById<android.widget.Button>(R.id.btnVideoFormatCycle).setOnClickListener { cycleVideoFormat() }
        findViewById<android.widget.Button>(R.id.btnVideoResCycle).setOnClickListener { cycleVideoResolution() }
        findViewById<android.widget.Button>(R.id.btnVideoStandardCycle).setOnClickListener { cycleVideoStandard() }
        findViewById<android.widget.Button>(R.id.btnColorCycle).setOnClickListener { cycleColor() }
        findViewById<android.widget.Button>(R.id.btnAudioSync).setOnClickListener {
            startActivity(android.content.Intent(this, io.github.mugenoesis.sidereal.sync.AudioSyncActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btnAudioSourceCycle).setOnClickListener { cycleAudioSource() }

        // Tap-to-focus/spot-meter: fires instead of face-tap-select whenever
        // faceOverlay.tapMode != FACE_SELECT (see updateTapMode()). While
        // software AFC is running, a focus tap means "recalibrate" (a
        // one-shot hardware AF-at-point to escape a bad starting position -
        // see SoftwareAfcController.recalibrateAt's doc comment) rather
        // than the normal AUTO/MANUAL tap-to-focus - the real camera focus
        // mode is MANUAL while AFC drives the ring, so a plain
        // setFocusTarget() would just be rejected outright, same as it
        // would from any other tap while genuinely in MANUAL mode.
        faceOverlay.onPreviewTapped = { x, y ->
            when (faceOverlay.tapMode) {
                io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.TAP_TO_FOCUS -> {
                    if (softwareAfcController.isRunning.value) {
                        softwareAfcController.recalibrateAt(x, y)
                    } else {
                        focusController.setFocusTarget(x, y)
                    }
                }
                io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.SPOT_METER -> meteringController.setSpotMeteringTarget(x, y)
                io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.FACE_SELECT -> {}
            }
        }
    }

    private fun selectExposureMode(mode: SettingsDefinitions.ExposureMode) {
        val previous = selectedExposureMode
        selectedExposureMode = mode
        AppPreferences.exposureMode = mode
        // The real camera's acceptable EV-compensation range is mode-
        // dependent (P/A/S each leave different headroom for compensation
        // to bias against) - confirmed via user report: a boundary learned
        // in one mode (e.g. A) was staying in effect after switching to
        // another (e.g. S), permanently clamping the EV stepper there too
        // even though that mode's real range differs. evBounds is shared
        // across all four modes (unlike per-lens state, which already gets
        // reset on componentsBoundTick in observeComponentChanges()), so it
        // needs the same treatment on every mode switch, not just a lens
        // swap.
        evBounds.reset()
        exposureController.setExposureMode(mode) { success ->
            if (!success) {
                // Don't leave the UI (and the persisted preference) claiming
                // a mode the camera never actually entered - a rejected
                // switch previously still flipped which stepper rows looked
                // enabled, based on a mode the real camera was never in.
                runOnUiThread {
                    selectedExposureMode = previous
                    AppPreferences.exposureMode = previous
                    updateExposureTrayUi()
                    updateExposureReadoutUi()
                }
            }
        }
        updateExposureTrayUi()
        updateExposureReadoutUi()
    }

    private fun <T : Enum<T>> step(current: T, delta: Int, values: Array<T>): T = CycleHelpers.stepEnum(current, delta, values)

    private fun stepIso(delta: Int) {
        selectedIso = step(selectedIso, delta, isoStepValues)
        updateExposureTrayUi()
        val target = selectedIso
        if (isoSendInFlight) {
            isoQueuedTarget = target
            return
        }
        sendIsoNow(target)
    }

    private fun sendIsoNow(target: SettingsDefinitions.ISO) {
        isoSendInFlight = true
        exposureController.setIso(target) {
            runOnUiThread {
                isoSendInFlight = false
                val queued = isoQueuedTarget
                isoQueuedTarget = null
                if (queued != null) sendIsoNow(queued)
            }
        }
    }

    private fun stepShutterSpeed(delta: Int) {
        val values = shutterSpeedStepValues
        val current = selectedShutterSpeed ?: exposureController.readout.value?.getShutterSpeed() ?: values[0]
        val next = step(current, delta, values)
        selectedShutterSpeed = next
        updateExposureTrayUi()
        if (shutterSendInFlight) {
            shutterQueuedTarget = next
            return
        }
        sendShutterNow(next)
    }

    private fun sendShutterNow(target: SettingsDefinitions.ShutterSpeed) {
        shutterSendInFlight = true
        exposureController.setShutterSpeed(target) {
            runOnUiThread {
                shutterSendInFlight = false
                val queued = shutterQueuedTarget
                shutterQueuedTarget = null
                if (queued != null) sendShutterNow(queued)
            }
        }
    }

    private fun stepEv(delta: Int) {
        val values = evStepValues
        val current = selectedEv ?: exposureController.evReadout.value ?: values[0]
        val currentIndex = values.indexOf(current).coerceAtLeast(0)
        val nextIndex = evBounds.clamp(currentIndex + delta)
        if (nextIndex == currentIndex) {
            // Already at a boundary the camera has already rejected once
            // this session (or the true end of the range) - stop here
            // instead of resending the identical value again. Confirmed on
            // real hardware this was previously spamming the same rejected
            // EV (e.g. "setExposureCompensation(N_4_0) failed: Param
            // Illegal") on every single subsequent press with no forward
            // progress and no indication the boundary had already been hit.
            return
        }
        val next = values[nextIndex]
        selectedEv = next
        updateExposureTrayUi()
        if (evSendInFlight) {
            // Coalesce to the latest target - sent the instant the in-flight
            // request completes, not after a fixed delay. See the
            // evSendInFlight doc comment above for why this replaced a flat
            // debounce.
            evQueuedTarget = next
            evQueuedTargetIndex = nextIndex
            return
        }
        sendEvNow(next, nextIndex, currentIndex)
    }

    private fun sendEvNow(target: SettingsDefinitions.ExposureCompensation, targetIndex: Int, fromIndex: Int) {
        evSendInFlight = true
        exposureController.setExposureCompensation(target) { error ->
            runOnUiThread {
                evSendInFlight = false
                if (error != null) {
                    if (LearnedStepBounds.isOutOfRangeError(error)) {
                        evBounds.recordRejection(targetIndex, fromIndex)
                    }
                    // Only revert to the pre-request value if nothing newer
                    // has superseded it - see stepEv's queueing above.
                    if (selectedEv == target) {
                        selectedEv = evStepValues[fromIndex]
                    }
                    updateExposureTrayUi()
                }
                val nextFromIndex = if (error == null) targetIndex else fromIndex
                val queued = evQueuedTarget
                val queuedIndex = evQueuedTargetIndex
                evQueuedTarget = null
                if (queued != null) sendEvNow(queued, queuedIndex, nextFromIndex)
            }
        }
    }

    private fun stepAperture(delta: Int) {
        val values = apertureStepValues
        val current = selectedAperture ?: exposureController.readout.value?.getAperture() ?: values[0]
        val next = step(current, delta, values)
        selectedAperture = next
        updateExposureTrayUi()
        if (apertureSendInFlight) {
            apertureQueuedTarget = next
            return
        }
        sendApertureNow(next)
    }

    private fun sendApertureNow(target: SettingsDefinitions.Aperture) {
        apertureSendInFlight = true
        exposureController.setAperture(target) {
            runOnUiThread {
                apertureSendInFlight = false
                val queued = apertureQueuedTarget
                apertureQueuedTarget = null
                if (queued != null) sendApertureNow(queued)
            }
        }
    }

    private fun stepSharpness(delta: Int) {
        val current = selectedSharpness ?: imageTuningController.sharpness.value ?: 0
        val next = (current + delta).coerceIn(ImageTuningController.TUNING_VALUE_MIN, ImageTuningController.TUNING_VALUE_MAX)
        selectedSharpness = next
        imageTuningController.setSharpness(next)
    }

    private fun stepContrast(delta: Int) {
        val current = selectedContrast ?: imageTuningController.contrast.value ?: 0
        val next = (current + delta).coerceIn(ImageTuningController.TUNING_VALUE_MIN, ImageTuningController.TUNING_VALUE_MAX)
        selectedContrast = next
        imageTuningController.setContrast(next)
    }

    private fun stepSaturation(delta: Int) {
        val current = selectedSaturation ?: imageTuningController.saturation.value ?: 0
        val next = (current + delta).coerceIn(ImageTuningController.TUNING_VALUE_MIN, ImageTuningController.TUNING_VALUE_MAX)
        selectedSaturation = next
        imageTuningController.setSaturation(next)
    }

    // MANUAL -> AUTO -> AFC(software) -> MANUAL... Deliberately NOT
    // FocusController.cycleFocusMode() - that one only ever calls the
    // hardware's setFocusMode(), and AFC there is excluded outright since
    // it's confirmed rejected by this lens. Here "AFC" means
    // softwareAfcController's contrast-detection emulation instead - see
    // its class doc comment. Same independent-index reasoning as every
    // other cycle in this file.
    private val focusCycleOrder = listOf(
        SettingsDefinitions.FocusMode.MANUAL,
        SettingsDefinitions.FocusMode.AUTO,
        SettingsDefinitions.FocusMode.AFC
    )
    private var focusCycleIndex: Int? = null

    private fun cycleFocusModeWithSoftwareAfc() {
        val realMode = if (softwareAfcController.isRunning.value) SettingsDefinitions.FocusMode.AFC else focusController.focusState.value?.getFocusMode()
        val (idx, next) = nextCycleValue(focusCycleIndex, realMode, focusCycleOrder)
        focusCycleIndex = idx
        if (next == SettingsDefinitions.FocusMode.AFC) {
            softwareAfcController.start()
        } else {
            softwareAfcController.stop()
            focusController.setFocusMode(next)
        }
        updateFrameCaptureState()
        updateFocusTrayUi()
    }

    /** Frame capture (TextureView.getBitmap() at ~12fps) is only worth running while something actually consumes it. */
    private fun updateFrameCaptureState() {
        val needed = findViewById<android.widget.ToggleButton>(R.id.toggleFaceTrack).isChecked || softwareAfcController.isRunning.value || focusAssistController.enabled.value || wearLiveViewWanted
        if (needed) startFrameCapture() else stopFrameCapture()
    }

    /** Generic "advance an independent cycle position" helper shared by the four cycle functions below - see the cycle-index fields' doc comment for why. */
    private fun <T> nextCycleValue(current: Int?, realValue: T?, options: List<T>): Pair<Int, T> = CycleHelpers.nextCycleValue(current, realValue, options)

    private fun cycleAntiFlicker() {
        val values = SettingsDefinitions.AntiFlickerFrequency.values().filter { it != SettingsDefinitions.AntiFlickerFrequency.UNKNOWN }
        val (nextIndex, next) = nextCycleValue(antiFlickerCycleIndex, imageTuningController.antiFlickerFrequency.value, values)
        antiFlickerCycleIndex = nextIndex
        imageTuningController.setAntiFlickerFrequency(next)
    }

    // Only RAW/JPEG/RAW_AND_JPEG are offered here - MediaFormatController's
    // doc comment notes the rest of SettingsDefinitions.PhotoFileFormat is
    // thermal-camera-only and not relevant to the X5.
    private val photoFormatCycleOptions = listOf(
        SettingsDefinitions.PhotoFileFormat.JPEG,
        SettingsDefinitions.PhotoFileFormat.RAW,
        SettingsDefinitions.PhotoFileFormat.RAW_AND_JPEG
    )

    private fun cyclePhotoFormat() {
        val (nextIndex, next) = nextCycleValue(photoFormatCycleIndex, mediaFormatController.photoFileFormat.value, photoFormatCycleOptions)
        photoFormatCycleIndex = nextIndex
        mediaFormatController.setPhotoFileFormat(next)
    }

    private fun cyclePhotoAspectRatio() {
        val values = SettingsDefinitions.PhotoAspectRatio.values().filter { it != SettingsDefinitions.PhotoAspectRatio.UNKNOWN }
        val (nextIndex, next) = nextCycleValue(photoAspectRatioCycleIndex, mediaFormatController.photoAspectRatio.value, values)
        photoAspectRatioCycleIndex = nextIndex
        mediaFormatController.setPhotoAspectRatio(next)
    }

    // Only MOV/MP4 offered - VideoFileFormat's TIFF_SEQ/SEQ variants are for
    // DJI's thermal/still-sequence cameras, not relevant to the X5.
    private val videoFormatCycleOptions = listOf(SettingsDefinitions.VideoFileFormat.MOV, SettingsDefinitions.VideoFileFormat.MP4)

    private fun cycleVideoFormat() {
        val (nextIndex, next) = nextCycleValue(videoFormatCycleIndex, mediaFormatController.videoFileFormat.value, videoFormatCycleOptions)
        videoFormatCycleIndex = nextIndex
        mediaFormatController.setVideoFileFormat(next)
    }

    private fun cycleVideoResolution() {
        val options = mediaFormatController.videoModeRange.value
        if (options.isEmpty()) {
            showErrorToast("The camera hasn't reported its video modes yet")
            return
        }
        val current = mediaFormatController.videoResolutionAndFrameRate.value
            ?.let { it.getResolution().name to it.getFrameRate().name }
        val start = if (selectedVideoResolutionIndex >= 0) selectedVideoResolutionIndex else options.indexOf(current)
        selectedVideoResolutionIndex = (start + 1).mod(options.size)
        val (resolution, frameRate) = options[selectedVideoResolutionIndex]
        mediaFormatController.setVideoResolutionAndFrameRate(
            SettingsDefinitions.VideoResolution.valueOf(resolution),
            SettingsDefinitions.VideoFrameRate.valueOf(frameRate)
        )
    }

    private fun cycleVideoStandard() {
        val options = mediaFormatController.videoStandardRange.value.ifEmpty { listOf("PAL", "NTSC") }
        val (index, next) = nextCycleValue(videoStandardCycleIndex, mediaFormatController.videoStandard.value, options)
        videoStandardCycleIndex = index
        mediaFormatController.setVideoStandard(next)
        // The frame-rate list changes with the standard, so the resolution cycler starts over from the camera's state.
        selectedVideoResolutionIndex = -1
    }

    private fun cycleColor() {
        val options = mediaFormatController.colorRange.value
        if (options.isEmpty()) {
            showErrorToast("The camera hasn't reported its colour profiles yet")
            return
        }
        val (index, next) = nextCycleValue(colorCycleIndex, mediaFormatController.cameraColor.value, options)
        colorCycleIndex = index
        mediaFormatController.setColor(next)
    }

    /**
     * Cycles through AudioSourceController.availableKinds() - re-queried
     * fresh on every press (not a fixed list) since BLUETOOTH only appears
     * once a Bluetooth mic is actually connected, matching
     * updateMoreSettingsTrayUi()'s live label. Takes effect on the NEXT
     * recording started, not retroactively - see AudioRecorderController's
     * doc comment for why this is tied to the shutter button, not to the
     * camera's own state.
     */
    private fun cycleAudioSource() {
        val kinds = AudioSourceController.availableKinds(this)
        val currentIndex = kinds.indexOf(AudioSourceController.selectedKind).coerceAtLeast(-1)
        AudioSourceController.selectedKind = kinds[(currentIndex + 1) % kinds.size]
        updateMoreSettingsTrayUi()
    }

    /**
     * What a plain tap on the video does by default, outside FACE_TRACK
     * mode - focus-on-demand is essential enough that it shouldn't require
     * opening the Focus tray first, the way it originally did. Toggled via
     * the readoutFocusIcon/readoutMeteringIcon pair in the top strip
     * (tapActionToggle), which doubles as this toggle's own UI rather than
     * being purely decorative. Defaults to focus, the more universally
     * needed of the two.
     */
    private var defaultTapAction = AppPreferences.defaultTapAction

    private fun setDefaultTapAction(action: io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode) {
        defaultTapAction = action
        AppPreferences.defaultTapAction = action
        updateTapMode()
    }

    /**
     * Decides what a bare tap on the video preview means right now - only
     * one of face-select/tap-to-focus/spot-meter can be active at a time
     * (see FaceOverlayView.TapMode). Face-tap-select stays exclusive while
     * FACE_TRACK mode is actually engaged (selecting/locking a subject is
     * that mode's core gesture and shouldn't be silently replaced);
     * otherwise a tap always does whichever of focus/spot-meter is
     * currently the default, regardless of which settings tray (if any) is
     * open. Recomputed whenever the gimbal mode, default tap action, focus
     * state, or metering mode changes.
     */
    private fun updateTapMode() {
        faceOverlay.tapMode = if (gimbalModeController.currentMode == GimbalMode.FACE_TRACK) {
            io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.FACE_SELECT
        } else {
            defaultTapAction
        }
        // A plain tap still safely reaches whatever tapMode routes it to
        // (focus/meter/face-select) even while the joystick is armed and
        // sitting on top in z-order - JoystickView only forwards a touch
        // as onTap() once it's confirmed the finger never moved past real
        // touch slop, so it doesn't need to be disarmed for this to work;
        // see JoystickView's own doc comment on hasMovedPastSlop and
        // updateJoystickAvailability()'s doc comment for why an earlier
        // version's "disarm during tap mode" approach was reverted.
        updateJoystickAvailability()
        updateTapActionToggleUi()
        updateFocusTrayUi()
        updateMeteringTrayUi()
    }

    private fun updateTapActionToggleUi() {
        val isFocus = defaultTapAction == io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.TAP_TO_FOCUS
        findViewById<android.widget.ImageButton>(R.id.readoutFocusIcon).setBackgroundResource(
            if (isFocus) R.drawable.bg_segment_selected else android.R.color.transparent
        )
        findViewById<android.widget.ImageButton>(R.id.readoutMeteringIcon).setBackgroundResource(
            if (!isFocus) R.drawable.bg_segment_selected else android.R.color.transparent
        )
    }

    private fun setActiveSettingsPanel(panel: SettingsPanel) {
        activeSettingsPanel = if (activeSettingsPanel == panel) SettingsPanel.NONE else panel

        findViewById<android.view.View>(R.id.exposureTray).visibility = if (activeSettingsPanel == SettingsPanel.EXPOSURE) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.whiteBalanceTray).visibility = if (activeSettingsPanel == SettingsPanel.WHITE_BALANCE) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.meteringTray).visibility = if (activeSettingsPanel == SettingsPanel.METERING) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.focusTray).visibility = if (activeSettingsPanel == SettingsPanel.FOCUS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.sequenceTray).visibility = if (activeSettingsPanel == SettingsPanel.SEQUENCE) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.moreSettingsScroll).visibility = if (activeSettingsPanel == SettingsPanel.MORE) android.view.View.VISIBLE else android.view.View.GONE

        findViewById<android.widget.ImageButton>(R.id.btnExposureRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.EXPOSURE) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.ImageButton>(R.id.btnWhiteBalanceRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.WHITE_BALANCE) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.ImageButton>(R.id.btnMeteringRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.METERING) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.ImageButton>(R.id.btnFocusRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.FOCUS) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.ImageButton>(R.id.btnSequenceRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.SEQUENCE) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.ImageButton>(R.id.btnMoreRail).setBackgroundResource(if (activeSettingsPanel == SettingsPanel.MORE) R.drawable.bg_segment_selected else android.R.color.transparent)

        if (activeSettingsPanel == SettingsPanel.MORE) {
            imageTuningController.refresh()
            mediaFormatController.refresh()
        }
        updateExposureTrayUi()
        updateWhiteBalanceTrayUi()
        updateMeteringTrayUi()
        updateFocusTrayUi()
        updateMoreSettingsTrayUi()
        updateTapMode()
    }

    /** Shared by every controller's errorEvents - a rejected set() surfaces here instead of only a logcat warning. */
    private fun showErrorToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun observeExposure() {
        exposureController.readout
            .onEach { updateExposureReadoutUi(); updateExposureTrayUi(); sequenceFeature.refreshPreview() }
            .launchIn(lifecycleScope)
        exposureController.apertureSupported
            .onEach { updateExposureTrayUi(); updateExposureReadoutUi() }
            .launchIn(lifecycleScope)
        // Key-based EV telemetry (see ExposureController.evReadout's doc
        // comment) - real, camera-confirmed EV, unlike the bundled
        // `readout` above which can get stuck for this specific field.
        exposureController.evReadout
            .onEach { updateExposureReadoutUi(); updateExposureTrayUi() }
            .launchIn(lifecycleScope)
        // Real per-camera EV range (see ExposureController.evRange's doc
        // comment) - once known, replaces the full-SDK-enum fallback and
        // resets the learned-rejection bounds to match the new size, so
        // the stepper clamps to the real range from the start instead of
        // discovering it by hitting rejections.
        exposureController.evRange
            .onEach { range ->
                if (range != null) {
                    evStepValues = range.toTypedArray()
                    evBounds = LearnedStepBounds(evStepValues.size)
                    updateExposureTrayUi()
                }
            }
            .launchIn(lifecycleScope)
        exposureController.isoRange
            .onEach { range ->
                if (range != null) {
                    isoStepValues = range.toTypedArray()
                    updateExposureTrayUi()
                }
            }
            .launchIn(lifecycleScope)
        exposureController.shutterRange
            .onEach { range ->
                if (range != null) {
                    shutterSpeedStepValues = range.toTypedArray()
                    updateExposureTrayUi()
                }
            }
            .launchIn(lifecycleScope)
        // Real hardware testing found the camera rejects exposure changes
        // outright while actively recording video ("Not supported") -
        // previously silent (log-only), which read as "the controls just
        // don't work" in video mode.
        exposureController.errorEvents
            .onEach { message -> showErrorToast(message) }
            .launchIn(lifecycleScope)
    }

    private fun observeFocus() {
        focusController.focusState
            .onEach { updateFocusTrayUi(); updateTapMode() }
            .launchIn(lifecycleScope)
        focusController.focusRingUpperBound
            .onEach { updateFocusTrayUi() }
            .launchIn(lifecycleScope)
        // Every focus mode stays selectable (see FocusController's doc
        // comment) even though not every lens supports all of them - a
        // rejection surfaces here as a toast rather than the option just
        // silently not working.
        focusController.errorEvents
            .onEach { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
            .launchIn(lifecycleScope)
        softwareAfcController.isRunning
            .onEach { updateFrameCaptureState(); updateFocusTrayUi() }
            .launchIn(lifecycleScope)
        softwareAfcController.lastSharpness
            .onEach { if (softwareAfcController.isRunning.value) updateFocusTrayUi() }
            .launchIn(lifecycleScope)
    }

    private fun observeMetering() {
        meteringController.meteringMode
            .onEach { mode ->
                // Spot-meter as the default tap action only makes sense
                // while metering mode is actually SPOT - if the user
                // cycles metering away from SPOT while it's the active tap
                // action, tapping the video would either be rejected or
                // meaningless. Falls back to focus automatically rather
                // than leaving the toggle pointed at a dead action.
                if (defaultTapAction == io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.SPOT_METER &&
                    mode != SettingsDefinitions.MeteringMode.SPOT
                ) {
                    setDefaultTapAction(io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.TAP_TO_FOCUS)
                }
                updateMeteringTrayUi()
                updateTapMode()
            }
            .launchIn(lifecycleScope)
        meteringController.supported
            .onEach { updateMeteringTrayUi() }
            .launchIn(lifecycleScope)
        meteringController.errorEvents
            .onEach { message -> showErrorToast(message) }
            .launchIn(lifecycleScope)
    }

    private fun observeWhiteBalance() {
        whiteBalanceController.whiteBalance
            .onEach { updateWhiteBalanceTrayUi(); updateExposureReadoutUi() }
            .launchIn(lifecycleScope)
        whiteBalanceController.customTemperatureRangeKelvin
            .onEach { updateWhiteBalanceTrayUi() }
            .launchIn(lifecycleScope)
        whiteBalanceController.errorEvents
            .onEach { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
            .launchIn(lifecycleScope)
    }

    private fun observeHistogram() {
        histogramController.histogramData
            .onEach { findViewById<HistogramView>(R.id.histogramView).update(it) }
            .launchIn(lifecycleScope)
    }

    private fun observeMoreSettings() {
        imageTuningController.sharpness.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        imageTuningController.contrast.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        imageTuningController.saturation.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        imageTuningController.antiFlickerFrequency.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.photoFileFormat.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.photoAspectRatio.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.videoFileFormat.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.videoResolutionAndFrameRate.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.videoStandard.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        mediaFormatController.cameraColor.onEach { updateMoreSettingsTrayUi() }.launchIn(lifecycleScope)
        // Media format/resolution changes are especially likely to be
        // rejected mid-recording (MediaFormatController's own doc comment
        // flags this) - surfaced here rather than silently failing.
        imageTuningController.errorEvents.onEach { message -> showErrorToast(message) }.launchIn(lifecycleScope)
        mediaFormatController.errorEvents.onEach { message -> showErrorToast(message) }.launchIn(lifecycleScope)
    }

    private fun updateExposureReadoutUi() {
        val readout = exposureController.readout.value
        findViewById<android.widget.TextView>(R.id.readoutMode).text = exposureModeLabel(selectedExposureMode)
        findViewById<android.widget.TextView>(R.id.readoutShutter).text = readout?.getShutterSpeed()?.let { shutterSpeedLabel(it) } ?: "--"

        val apertureText = findViewById<android.widget.TextView>(R.id.readoutAperture)
        val aperture = readout?.getAperture()
        if (exposureController.apertureSupported.value && aperture != null) {
            apertureText.visibility = android.view.View.VISIBLE
            apertureText.text = apertureLabel(aperture)
        } else {
            apertureText.visibility = android.view.View.GONE
        }

        findViewById<android.widget.TextView>(R.id.readoutIso).text = readout?.let { "ISO ${it.getISO()}" } ?: "ISO --"
        // exposureController.evReadout (Key-based), not readout - see its
        // doc comment: the bundled Camera-object readout's EV field can get
        // stuck not reflecting confirmed changes, unlike this one.
        findViewById<android.widget.TextView>(R.id.readoutEv).text = exposureController.evReadout.value?.let { evLabel(it) } ?: "0.0"
        findViewById<android.widget.TextView>(R.id.readoutWb).text = whiteBalanceLabel(whiteBalanceController.whiteBalance.value)
    }

    private fun updateExposureTrayUi() {
        val mode = selectedExposureMode
        val readout = exposureController.readout.value
        // Real hardware testing: the camera rejects every exposure-related
        // change outright ("Not supported" for mode, "Cannot set the
        // parameters in this state" for ISO/shutter/EV/anti-flicker,
        // "Param Illegal" for video resolution/frame rate) while actively
        // recording video - previously these all stayed enabled and just
        // silently failed (now surfaced via toast, but proactively
        // disabling is the better fix: no point letting the user tap
        // something guaranteed to be rejected). Confirmed NOT locked during
        // recording: white balance, metering, focus, sharpness/contrast/
        // saturation, photo format/aspect ratio - those trays are
        // unaffected.
        val recording = DJIConnectionManager.cameraSystemState.value?.isRecording == true

        val modeSegmentAlpha = if (recording) 0.4f else 1f
        listOf(R.id.btnModeP, R.id.btnModeA, R.id.btnModeS, R.id.btnModeM).forEach { id ->
            findViewById<android.widget.Button>(id).apply {
                isEnabled = !recording
                alpha = modeSegmentAlpha
            }
        }
        findViewById<android.widget.Button>(R.id.btnModeP).setBackgroundResource(if (mode == SettingsDefinitions.ExposureMode.PROGRAM) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.Button>(R.id.btnModeA).setBackgroundResource(if (mode == SettingsDefinitions.ExposureMode.APERTURE_PRIORITY) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.Button>(R.id.btnModeS).setBackgroundResource(if (mode == SettingsDefinitions.ExposureMode.SHUTTER_PRIORITY) R.drawable.bg_segment_selected else android.R.color.transparent)
        findViewById<android.widget.Button>(R.id.btnModeM).setBackgroundResource(if (mode == SettingsDefinitions.ExposureMode.MANUAL) R.drawable.bg_segment_selected else android.R.color.transparent)

        val isoEditable = exposureController.isIsoEditable(mode) && !recording
        findViewById<android.view.View>(R.id.isoStepperRow).alpha = if (isoEditable) 1f else 0.4f
        findViewById<android.widget.Button>(R.id.btnIsoDown).isEnabled = isoEditable
        findViewById<android.widget.Button>(R.id.btnIsoUp).isEnabled = isoEditable
        // Shows selectedIso (the locally-tracked optimistic selection, same
        // value each tap steps from - see its doc comment above) rather
        // than the readout's raw reported Int, which otherwise pre-empted
        // it here the moment any readout existed and left this label
        // lagging a full WiFi round-trip behind each tap. Same fix as
        // evValueText below; readoutIso up in the summary strip still shows
        // the camera's own reported int for ground truth.
        findViewById<android.widget.TextView>(R.id.isoValueText).text = isoLabel(selectedIso)

        val shutterEditable = exposureController.isShutterEditable(mode) && !recording
        findViewById<android.view.View>(R.id.shutterStepperRow).alpha = if (shutterEditable) 1f else 0.4f
        findViewById<android.widget.Button>(R.id.btnShutterSpeedDown).isEnabled = shutterEditable
        findViewById<android.widget.Button>(R.id.btnShutterSpeedUp).isEnabled = shutterEditable
        // Same fix as evValueText below: prefer the optimistic
        // selectedShutterSpeed (what the next tap steps from) over waiting
        // on the lagging pushed readout, so this label moves on every tap
        // instead of only once the camera's confirmation round-trips back.
        val shutterCurrent = selectedShutterSpeed ?: readout?.getShutterSpeed()
        findViewById<android.widget.TextView>(R.id.shutterValueText).text = shutterCurrent?.let { shutterSpeedLabel(it) } ?: "--"

        val apertureRow = findViewById<android.view.View>(R.id.apertureStepperRow)
        if (exposureController.apertureSupported.value) {
            apertureRow.visibility = android.view.View.VISIBLE
            val apertureEditable = exposureController.isApertureEditable(mode) && !recording
            apertureRow.alpha = if (apertureEditable) 1f else 0.4f
            findViewById<android.widget.Button>(R.id.btnApertureDown).isEnabled = apertureEditable
            findViewById<android.widget.Button>(R.id.btnApertureUp).isEnabled = apertureEditable
            // Same fix as shutter/EV: prefer the optimistic selectedAperture
            // over the lagging pushed readout.
            val apertureCurrent = selectedAperture ?: readout?.getAperture()
            findViewById<android.widget.TextView>(R.id.apertureValueText).text = apertureCurrent?.let { apertureLabel(it) } ?: "--"
        } else {
            apertureRow.visibility = android.view.View.GONE
        }

        val evEditable = exposureController.isEvEditable(mode) && !recording
        findViewById<android.view.View>(R.id.evStepperRow).alpha = if (evEditable) 1f else 0.4f
        // Also greys out whichever single direction has already hit a
        // learned rejection boundary this session (see LearnedStepBounds),
        // so pressing further that way visibly can't do anything instead of
        // silently no-opping - was previously indistinguishable from the
        // button being unresponsive/broken.
        val evCurrent = selectedEv ?: exposureController.evReadout.value ?: evStepValues[0]
        val evCurrentIndex = evStepValues.indexOf(evCurrent).coerceAtLeast(0)
        findViewById<android.widget.Button>(R.id.btnEvDown).isEnabled = evEditable && evBounds.clamp(evCurrentIndex - 1) != evCurrentIndex
        findViewById<android.widget.Button>(R.id.btnEvUp).isEnabled = evEditable && evBounds.clamp(evCurrentIndex + 1) != evCurrentIndex
        // Shows evCurrent (selectedEv optimistically, same value the next
        // tap's step is computed from) rather than waiting on readout alone
        // - same "reflect the request immediately, only correct it back on
        // a confirmed rejection" treatment selectExposureMode() already
        // gives the mode segment buttons. Real hardware testing found this
        // label previously only ever showed the camera's last PUSHED
        // readout, which lags a tap by a full WiFi round-trip (the pushed
        // ExposureSettings callback, not the set-call's own completion) -
        // read as "doesn't reflect what it's actually set at" and invited
        // repeated taps that then queued up multiple in-flight requests.
        findViewById<android.widget.TextView>(R.id.evValueText).text = evLabel(evCurrent)
    }

    private fun updateWhiteBalanceTrayUi() {
        val wb = whiteBalanceController.whiteBalance.value
        findViewById<android.widget.TextView>(R.id.wbValueText).text = whiteBalanceLabel(wb)
        val isCustom = wb?.getWhiteBalancePreset() == SettingsDefinitions.WhiteBalancePreset.CUSTOM
        findViewById<android.view.View>(R.id.wbCustomRow).visibility = if (isCustom) android.view.View.VISIBLE else android.view.View.GONE
        val range = whiteBalanceController.customTemperatureRangeKelvin.value
        if (range != null) {
            val seekBar = findViewById<SeekBar>(R.id.wbKelvinSeekBar)
            seekBar.max = (range.last - range.first).coerceAtLeast(1)
            if (isCustom) seekBar.progress = ((wb?.getColorTemperature() ?: range.first) - range.first).coerceIn(0, seekBar.max)
        }
        findViewById<android.widget.TextView>(R.id.wbKelvinValueText).text = "${wb?.getColorTemperature() ?: 5600}K"
    }

    private fun updateMeteringTrayUi() {
        val mode = meteringController.meteringMode.value
        findViewById<android.widget.TextView>(R.id.meteringValueText).text = meteringLabel(mode)
        // Reflects faceOverlay.tapMode directly (set by updateTapMode(),
        // which this itself is called from) rather than re-deriving the
        // condition, so the hint can never drift out of sync with what a
        // tap on the video actually does right now.
        findViewById<android.view.View>(R.id.meteringHint).visibility =
            if (faceOverlay.tapMode == io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.SPOT_METER) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateFocusTrayUi() {
        val mode = focusController.focusState.value?.getFocusMode()
        val softwareAfcRunning = softwareAfcController.isRunning.value
        findViewById<android.widget.TextView>(R.id.focusValueText).text = if (softwareAfcRunning) {
            val lockedSuffix = if (softwareAfcController.isLocked.value) ", locked" else ""
            "${focusModeLabel(SettingsDefinitions.FocusMode.AFC)} (SW, sharpness ${softwareAfcController.lastSharpness.value.toInt()}$lockedSuffix)"
        } else {
            focusModeLabel(mode)
        }
        val isManual = mode == SettingsDefinitions.FocusMode.MANUAL
        findViewById<android.view.View>(R.id.focusRingRow).visibility = if (isManual || softwareAfcRunning) android.view.View.VISIBLE else android.view.View.GONE
        // While the hill-climb is driving the ring itself, dragging the seek
        // bar would just fight it - same reasoning as any other
        // programmatically-driven control that also happens to accept
        // manual input.
        findViewById<SeekBar>(R.id.focusRingSeekBar).isEnabled = !softwareAfcRunning
        findViewById<android.view.View>(R.id.focusHint).visibility =
            if (faceOverlay.tapMode == io.github.mugenoesis.sidereal.tracking.FaceOverlayView.TapMode.TAP_TO_FOCUS) android.view.View.VISIBLE else android.view.View.GONE
        focusController.focusRingUpperBound.value?.let {
            findViewById<SeekBar>(R.id.focusRingSeekBar).max = it.coerceAtLeast(1)
        }
    }

    private fun updateMoreSettingsTrayUi() {
        findViewById<android.widget.TextView>(R.id.sharpnessValueText).text = (imageTuningController.sharpness.value ?: 0).toString()
        findViewById<android.widget.TextView>(R.id.contrastValueText).text = (imageTuningController.contrast.value ?: 0).toString()
        findViewById<android.widget.TextView>(R.id.saturationValueText).text = (imageTuningController.saturation.value ?: 0).toString()

        // Real hardware testing: anti-flicker and video format/resolution
        // are rejected outright while actively recording ("Cannot set the
        // parameters in this state" / "Param Illegal") - sharpness/
        // contrast/saturation/photo format/aspect ratio are NOT locked
        // during recording (confirmed working), so only these three get
        // disabled. Same reasoning as updateExposureTrayUi()'s recording
        // gate.
        val recording = DJIConnectionManager.cameraSystemState.value?.isRecording == true
        val lockedDuringRecordingAlpha = if (recording) 0.4f else 1f

        findViewById<android.widget.Button>(R.id.btnAntiFlickerCycle).apply {
            text = antiFlickerLabel(imageTuningController.antiFlickerFrequency.value)
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
        findViewById<android.widget.Button>(R.id.btnPhotoFormatCycle).text = photoFormatLabel(mediaFormatController.photoFileFormat.value)
        findViewById<android.widget.Button>(R.id.btnPhotoAspectCycle).text = photoAspectLabel(mediaFormatController.photoAspectRatio.value)
        findViewById<android.widget.Button>(R.id.btnVideoFormatCycle).apply {
            text = mediaFormatController.videoFileFormat.value?.name ?: "MOV"
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
        findViewById<android.widget.Button>(R.id.btnVideoResCycle).apply {
            text = videoResolutionLabel(mediaFormatController.videoResolutionAndFrameRate.value)
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
        findViewById<android.widget.Button>(R.id.btnVideoStandardCycle).apply {
            text = CameraLabels.videoStandardLabel(mediaFormatController.videoStandard.value)
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
        findViewById<android.widget.Button>(R.id.btnColorCycle).apply {
            text = CameraLabels.colorLabel(mediaFormatController.cameraColor.value)
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
        findViewById<android.widget.Button>(R.id.btnAudioSourceCycle).apply {
            val label = AudioSourceController.label(this@MainActivity, AudioSourceController.selectedKind)
            text = if (audioRecorderController.isRecording.value) "$label (rec)" else label
            // Changing source mid-recording wouldn't affect the recording
            // already in progress - lock it the same way format/resolution
            // are locked, so it's clear a change here waits for the next take.
            isEnabled = !recording
            alpha = lockedDuringRecordingAlpha
        }
    }

    // --- Label helpers: derive short display text from SDK enum names
    // rather than hardcoding an exhaustive when() over every ladder member
    // (ISO/ShutterSpeed/Aperture especially have many members) ---

    private fun exposureModeLabel(mode: SettingsDefinitions.ExposureMode): String = when (mode) {
        SettingsDefinitions.ExposureMode.PROGRAM -> getString(R.string.exposure_mode_program)
        SettingsDefinitions.ExposureMode.SHUTTER_PRIORITY -> getString(R.string.exposure_mode_shutter_priority)
        SettingsDefinitions.ExposureMode.APERTURE_PRIORITY -> getString(R.string.exposure_mode_aperture_priority)
        SettingsDefinitions.ExposureMode.MANUAL -> getString(R.string.exposure_mode_manual)
        SettingsDefinitions.ExposureMode.CINE -> getString(R.string.exposure_mode_cine)
        else -> "?"
    }

    private fun isoLabel(iso: SettingsDefinitions.ISO): String = CameraLabels.isoLabel(iso.name)

    private fun shutterSpeedLabel(speed: SettingsDefinitions.ShutterSpeed): String = CameraLabels.shutterSpeedLabel(speed.name)

    private fun apertureLabel(aperture: SettingsDefinitions.Aperture): String = CameraLabels.apertureLabel(aperture.name)

    private fun evLabel(ev: SettingsDefinitions.ExposureCompensation): String = CameraLabels.evLabel(ev.name)

    private fun whiteBalanceLabel(wb: dji.common.camera.WhiteBalance?): String {
        val preset = wb?.getWhiteBalancePreset() ?: return getString(R.string.wb_auto)
        return when (preset) {
            SettingsDefinitions.WhiteBalancePreset.AUTO -> getString(R.string.wb_auto)
            SettingsDefinitions.WhiteBalancePreset.SUNNY -> getString(R.string.wb_sunny)
            SettingsDefinitions.WhiteBalancePreset.CLOUDY -> getString(R.string.wb_cloudy)
            SettingsDefinitions.WhiteBalancePreset.WATER_SURFACE -> getString(R.string.wb_water_surface)
            SettingsDefinitions.WhiteBalancePreset.INDOOR_INCANDESCENT -> getString(R.string.wb_indoor_incandescent)
            SettingsDefinitions.WhiteBalancePreset.INDOOR_FLUORESCENT -> getString(R.string.wb_indoor_fluorescent)
            SettingsDefinitions.WhiteBalancePreset.CUSTOM -> "${wb.getColorTemperature()}K"
            SettingsDefinitions.WhiteBalancePreset.PRESET_NEUTRAL -> getString(R.string.wb_neutral)
            else -> preset.name
        }
    }

    private fun meteringLabel(mode: SettingsDefinitions.MeteringMode?): String = when (mode) {
        SettingsDefinitions.MeteringMode.CENTER -> getString(R.string.metering_center)
        SettingsDefinitions.MeteringMode.AVERAGE -> getString(R.string.metering_average)
        SettingsDefinitions.MeteringMode.SPOT -> getString(R.string.metering_spot)
        else -> "--"
    }

    private fun focusModeLabel(mode: SettingsDefinitions.FocusMode?): String = when (mode) {
        SettingsDefinitions.FocusMode.MANUAL -> getString(R.string.focus_manual)
        SettingsDefinitions.FocusMode.AUTO -> getString(R.string.focus_auto)
        SettingsDefinitions.FocusMode.AFC -> getString(R.string.focus_continuous)
        else -> "--"
    }

    private fun antiFlickerLabel(freq: SettingsDefinitions.AntiFlickerFrequency?): String = CameraLabels.antiFlickerLabel(freq?.name)

    private fun photoFormatLabel(format: SettingsDefinitions.PhotoFileFormat?): String = CameraLabels.photoFormatLabel(format?.name)

    private fun photoAspectLabel(ratio: SettingsDefinitions.PhotoAspectRatio?): String = CameraLabels.photoAspectLabel(ratio?.name)

    private fun videoResolutionLabel(rf: dji.common.camera.ResolutionAndFrameRate?): String =
        if (rf == null) "--" else CameraLabels.videoResolutionLabel(rf.getResolution().name, rf.getFrameRate().name)

    /** Routes a mode switch through both the controller and joystickView.armed, which must stay in sync. */
    private fun switchGimbalMode(mode: GimbalMode) {
        gimbalModeController.switchTo(mode)
        // FACE_TRACK claims tap-for-face-select exclusively (see
        // updateTapMode()) - entering/leaving it needs to re-evaluate
        // tapMode, not just joystick availability. updateTapMode() calls
        // updateJoystickAvailability() itself, so this covers both.
        updateTapMode()
        updateGimbalModeUi()
        // A real bug this surfaced: btnStartMove.isEnabled (in
        // updateTimedMoveUi()) is gated on currentMode == TIMED_MOVE, but
        // was only ever recomputed by timedMoveController.state changing
        // (captureA/B, or a move finishing) - switching INTO TIMED_MOVE
        // mode after both points were already captured earlier left Start
        // permanently stuck disabled, since nothing re-ran this check at
        // the actual moment the mode changed.
        updateTimedMoveUi()
    }

    /**
     * Highlights whichever of Manual/A->B is actually active, the same
     * bg_segment_selected treatment the photo/video pill and P/A/S/M
     * segment already use - these two buttons previously had no visual
     * selected state at all, which read as "doesn't do anything" even
     * though the mode switch itself was working (confirmed via
     * GimbalModeController's own log line).
     */
    private fun updateGimbalModeUi() {
        val mode = gimbalModeController.currentMode
        findViewById<android.widget.Button>(R.id.btnManualMode).setBackgroundResource(
            if (mode == GimbalMode.MANUAL) R.drawable.bg_segment_selected else android.R.color.transparent
        )
        findViewById<android.widget.Button>(R.id.btnTimedMoveMode).setBackgroundResource(
            if (mode == GimbalMode.TIMED_MOVE) R.drawable.bg_segment_selected else android.R.color.transparent
        )
    }

    /**
     * Set A/Set B/Start previously gave no feedback at all -
     * TimedMoveController.state existed but nothing observed it, and its
     * State enum has no "A captured, waiting for B" case to observe anyway
     * (it only becomes Ready once both points are set) - so, like
     * selectedExposureMode/selectedIso elsewhere in this file, capture
     * state is tracked locally here rather than added to the controller.
     * Start is only enabled once both points are captured (Ready/Paused),
     * instead of silently no-op'ing (TimedMoveController.start() just
     * returns early if A/B aren't set, and
     * GimbalModeController.startTimedMove() does the same if not in
     * TIMED_MOVE mode) - and its label reflects what's actually happening
     * (Running shows live progress) rather than always reading "Start".
     */
    private var capturedA = false
    private var capturedB = false

    private fun updateTimedMoveUi() {
        val state = timedMoveController.state.value
        if (state is TimedMoveController.State.Idle) {
            capturedA = false
            capturedB = false
        }
        findViewById<android.widget.Button>(R.id.btnSetA).setBackgroundResource(
            if (capturedA) R.drawable.bg_segment_selected else R.drawable.bg_stepper_button
        )
        findViewById<android.widget.Button>(R.id.btnSetB).setBackgroundResource(
            if (capturedB) R.drawable.bg_segment_selected else R.drawable.bg_stepper_button
        )

        val startButton = findViewById<android.widget.Button>(R.id.btnStartMove)
        when (state) {
            is TimedMoveController.State.MovingToStart -> {
                startButton.text = "Moving to A..."
                startButton.isEnabled = false
            }
            is TimedMoveController.State.Running -> {
                startButton.text = "Running ${(state.progress * 100).toInt()}%"
                startButton.isEnabled = false
            }
            is TimedMoveController.State.Paused -> {
                startButton.text = "Paused"
                startButton.isEnabled = true
            }
            is TimedMoveController.State.Completed -> {
                startButton.text = "Done"
                startButton.isEnabled = true
            }
            is TimedMoveController.State.Ready -> {
                startButton.text = "Start"
                startButton.isEnabled = gimbalModeController.currentMode == GimbalMode.TIMED_MOVE
            }
            else -> {
                startButton.text = "Start"
                startButton.isEnabled = false
            }
        }
        startButton.alpha = if (startButton.isEnabled) 1f else 0.4f
    }

    private fun updateDurationUi() {
        findViewById<android.widget.Button>(R.id.btnDurationCycle).text = "${pendingMoveDurationMs / 1000L}s"
    }

    /**
     * Joystick is usable in MANUAL mode, and also in FACE_TRACK mode
     * whenever tracking isn't actively LOCKED - lets the user manually
     * search for/recover the subject when there's nothing to follow (e.g.
     * they turned away, or the lock timed out) without leaving face-track
     * mode entirely. ManualGimbalController is activated/deactivated to
     * match, since it only sends commands while active; GimbalModeController
     * already owns activation for plain MANUAL mode, so this only adds the
     * FACE_TRACK-but-unlocked case on top.
     *
     * Does NOT need to disarm for tap-to-focus/spot-meter to work safely -
     * an earlier version tried gating this on faceOverlay.tapMode too, but
     * that's solving the wrong layer: since tap-to-focus is now the
     * always-on default outside FACE_TRACK (see updateTapMode()), that
     * would leave the joystick disarmed for effectively all of MANUAL
     * mode, breaking real drag-to-move-gimbal entirely. The actual fix
     * belongs in JoystickView itself (see its own doc comment on
     * hasMovedPastSlop) - touch slop already exists specifically to tell
     * real drags apart from a tap's natural jitter; once JoystickView
     * correctly gates onStickMoved on it, a plain tap never sends a
     * gimbal command regardless of what's armed, and a real drag still
     * works normally here.
     */
    private fun updateJoystickAvailability() {
        val mode = gimbalModeController.currentMode
        val locked = faceTrackingController.trackingState.value == TrackingState.LOCKED
        val shouldArm = mode == GimbalMode.MANUAL || (mode == GimbalMode.FACE_TRACK && !locked)
        if (shouldArm && !joystickView.armed) {
            manualController.activate()
        } else if (!shouldArm && joystickView.armed && mode != GimbalMode.MANUAL) {
            manualController.deactivate()
        }
        joystickView.armed = shouldArm
    }

    /**
     * Resizes videoPreview to fit (letterboxed/pillarboxed as needed)
     * within the space above controlBar at the stream's real aspect ratio,
     * instead of stretching to fill that space regardless of ratio.
     * faceOverlay and joystickView don't need their own handling - both are
     * constrained to videoPreview's edges with MATCH_CONSTRAINT width/
     * height in the layout, so they automatically track whatever bounds
     * videoPreview ends up with.
     */
    private fun applyVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        videoPreview.post {
            val parentView = videoPreview.parent as? android.view.View ?: return@post
            val controlBar = findViewById<android.view.View>(R.id.controlBar)
            val availableWidth = parentView.width
            val availableHeight = controlBar.top.takeIf { it > 0 } ?: parentView.height
            if (availableWidth <= 0 || availableHeight <= 0) return@post

            val videoAspect = videoWidth.toFloat() / videoHeight
            val containerAspect = availableWidth.toFloat() / availableHeight
            val (newWidth, newHeight) = if (videoAspect > containerAspect) {
                availableWidth to (availableWidth / videoAspect).toInt()
            } else {
                (availableHeight * videoAspect).toInt() to availableHeight
            }

            val params = videoPreview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (params.width == newWidth && params.height == newHeight) return@post
            params.width = newWidth
            params.height = newHeight
            videoPreview.layoutParams = params
        }
    }

    /** Periodically hands the already-rendered preview frame to VideoFrameProvider - see its doc comment for why. */
    private fun startFrameCapture() {
        if (frameCaptureRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                captureFrame()
                frameCaptureHandler.postDelayed(this, frameCaptureIntervalMs)
            }
        }
        frameCaptureRunnable = runnable
        frameCaptureHandler.post(runnable)
    }

    private fun stopFrameCapture() {
        frameCaptureRunnable?.let { frameCaptureHandler.removeCallbacks(it) }
        frameCaptureRunnable = null
    }

    private fun captureFrame() {
        val bitmap = videoPreview.bitmap ?: return
        videoFrameProvider.onBitmapFrame(bitmap)
        softwareAfcController.onBitmapFrame(bitmap)
        focusAssistController.onBitmapFrame(bitmap)
        wearBridge?.offerFrame(bitmap)
    }

    private fun observeConnectionState() {
        DJIConnectionManager.connectionState
            .onEach { updateConnectionStatusDisplay() }
            .launchIn(lifecycleScope)
    }

    /**
     * The DJI SDK gives no signal for "phone is on the wrong WiFi network" -
     * it just sits in Disconnected forever, same as "the Osmo is simply
     * off". Checking the SSID directly catches the common case (phone still
     * on home WiFi, a mobile hotspot, etc.) and surfaces it as an actionable
     * message instead of a generic "waiting" one.
     */
    private fun observeWifiState() {
        OsmoWifiChecker.currentSsid
            .onEach { updateConnectionStatusDisplay() }
            .launchIn(lifecycleScope)
    }

    private fun updateConnectionStatusDisplay() {
        val djiState = DJIConnectionManager.connectionState.value
        // The readout strip/settings rail have no meaningful content
        // without a live camera link - hidden together with
        // connectionStatus's inverse, same idiom as zoomControlsGroup.
        val connected = djiState is DJIConnectionManager.ConnectionState.ProductConnected
        findViewById<android.view.View>(R.id.exposureReadoutStrip).visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.settingsRail).visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        if (connected) {
            connectionStatus.visibility = android.view.View.GONE
            connectionStatus.setOnClickListener(null)
            return
        }

        val ssid = OsmoWifiChecker.currentSsid.value
        val wrongWifi = !OsmoWifiChecker.isOsmoNetwork(ssid)

        connectionStatus.text = if (wrongWifi) {
            io.github.mugenoesis.sidereal.dji.WifiStatusText.notOnOsmo(ssid)
        } else {
            when (djiState) {
                is DJIConnectionManager.ConnectionState.Disconnected -> "Waiting for Osmo..."
                is DJIConnectionManager.ConnectionState.Registering -> "Registering with DJI..."
                is DJIConnectionManager.ConnectionState.Registered -> "Registered - waiting for Osmo..."
                is DJIConnectionManager.ConnectionState.ProductConnected -> null
                is DJIConnectionManager.ConnectionState.Error -> io.github.mugenoesis.sidereal.dji.RegistrationText.describe(djiState.message)
            }
        }
        connectionStatus.visibility = android.view.View.VISIBLE
        connectionStatus.setOnClickListener {
            if (wrongWifi && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                joinOsmoWifi()
            } else {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
        }
        connectionStatus.setOnLongClickListener {
            promptForOsmoWifiPassword()
            true
        }
    }

    /**
     * bindComponents() (and so componentsBoundTick) fires on the initial
     * product connect *and* on a mid-session component swap (lens change,
     * gimbal hot-swap) - unlike connectionState, which only reflects the
     * latter as a no-op re-set of the same ProductConnected singleton, so it
     * doesn't actually re-emit for a lens change alone. Zoom support is a
     * per-lens capability (X5/X5R primes don't have it, others might), so it
     * needs to be re-checked on every bind, not just the first one.
     */
    private fun observeComponentChanges() {
        DJIConnectionManager.componentsBoundTick
            .onEach {
                zoomController.refreshCapability()
                // Re-registers pushed-state callbacks and re-queries
                // capabilities on every bind, not just the first - a fresh
                // Camera/Lens instance after a mid-session component swap
                // doesn't carry over callbacks registered on the old one,
                // same reasoning as DJIConnectionManager's own
                // bindComponents() re-registering system/gimbal/storage
                // state callbacks every time.
                shootingControls.onCameraRebound()
                exposureController.startObserving()
                exposureController.refreshCapability()
                // Litchi showed aperture as genuinely adjustable (f/1.7
                // changed to f/1.8 and back) on hardware where this app's
                // own aperture row stays hidden - originally suspected as a
                // timing race (isAdjustableApertureSupported() reading
                // before the Lens sub-component's handshake finishes) and
                // "fixed" with a delayed re-check here. Disproven by
                // further testing: CameraKey.APERTURE_RANGE via the
                // Key-based interface fails with "The feature is
                // unsupported" too, the same answer as the boolean check,
                // not a timing-dependent one - see README's "DJI SDK v3 vs
                // v4" section. Not a fix, so removed; aperture support
                // looks like it may genuinely need the v3 SDK.
                exposureController.refreshKeyBasedEvTelemetry()
                // DJISDKManager.getInstance().getKeyManager() is @Nullable
                // and was observed null on the very first
                // componentsBoundTick (before the product finishes
                // connecting) - unlike aperture above, this retry is
                // confirmed to actually matter: a second call after the
                // product finishes connecting reliably picks up EV
                // telemetry that the first call (right at bind time)
                // missed.
                lifecycleScope.launch {
                    delay(2000)
                    exposureController.refreshKeyBasedEvTelemetry()
                }
                // TEMP diagnostic retry - camera's runtime class resolved
                // to the real dji.internal.camera.fdd on the first
                // componentsBoundTick, but getLens(0) still returned null
                // at that same moment - checking whether that's just the
                // Lens sub-component connecting slightly later.
                lifecycleScope.launch {
                    delay(5000)
                    exposureController.refreshCapability()
                }
                // A component swap may mean a different camera/lens with a
                // different real EV range - see LearnedStepBounds' doc
                // comment - so last session's learned boundary shouldn't
                // carry over.
                evBounds.reset()
                // Reasserts the remembered exposure mode (AppPreferences)
                // on every bind, not just the first connect - same
                // "reapply on every rebind" treatment as the capability
                // refreshes above, so a mid-session lens swap doesn't
                // silently leave the camera in whatever mode it happened
                // to power up in versus what this app's UI is telling the
                // user is selected.
                exposureController.reassertMode(selectedExposureMode)
                focusController.startObserving()
                focusController.refreshFocusRingRange()
                meteringController.refreshCapability()
                meteringController.refreshGridSize()
                whiteBalanceController.refresh()
                whiteBalanceController.refreshCustomTemperatureRange()
                // Real hardware testing: a disconnect/reconnect while
                // already in MANUAL or FACE_TRACK mode left the gimbal
                // unresponsive to the joystick/tracking correction - the
                // fresh Gimbal connection needs its own FREE mode request,
                // but that was previously only sent from activate()/arm(),
                // neither of which fires again on a mere reconnect. See
                // each controller's resetFreeModeRequest() doc comment.
                manualController.resetFreeModeRequest()
                faceTrackingController.resetFreeModeRequest()
            }
            .launchIn(lifecycleScope)
    }

    private fun observeFaceTracking() {
        faceTrackingController.detectedFaces
            .onEach { faces ->
                val lockedId = if (faceTrackingController.trackingState.value == TrackingState.LOCKED) {
                    faces.firstOrNull()?.trackingId
                } else null
                faceOverlay.update(faces, lockedId)
            }
            .launchIn(lifecycleScope)

        // Keeps the joystick's availability in sync with LOCKED/ARMED
        // transitions that happen without a mode switch - e.g. losing lock
        // and coasting back to ARMED, or re-selecting a face back to LOCKED.
        faceTrackingController.trackingState
            .onEach { updateJoystickAvailability() }
            .launchIn(lifecycleScope)
    }

    private fun observeZoomCapability() {
        zoomController.capability
            .onEach { cap ->
                findViewById<android.view.View>(R.id.zoomControlsGroup).visibility =
                    if (cap.supported) android.view.View.VISIBLE else android.view.View.GONE
            }
            .launchIn(lifecycleScope)
    }

    /**
     * Drives btnPhotoMode/btnVideoMode's selected-segment highlight from
     * the camera's actual reported SystemState.mode, and btnShutter's
     * white/red/orange circle from comparing CameraModeController
     * .isRecordingIntent (what we last asked for) against the camera's
     * real SystemState.isRecording.
     */
    // Tracks SystemState.isRecording specifically (not just re-running on
    // every emission, which happens roughly every second purely from
    // currentVideoRecordingTimeInSeconds ticking) so
    // stopPhoneAudioIfCameraReallyStopped() below fires exactly once per
    // real stop, on the true -> false edge.
    private var lastCameraIsRecording = false

    private fun observeCameraState() {
        DJIConnectionManager.cameraSystemState
            .onEach { state ->
                updateCameraModeUi()
                // Exposure and format/anti-flicker controls need to
                // disable/re-enable the instant recording starts/stops,
                // not just when their own settings change - see
                // updateExposureTrayUi()/updateMoreSettingsTrayUi()'s
                // recording-lock comments.
                updateExposureTrayUi()
                updateMoreSettingsTrayUi()
                val nowRecording = state?.isRecording == true
                if (!lastCameraIsRecording && nowRecording) audioRecorderController.onCameraRecordingStarted()
                if (lastCameraIsRecording && !nowRecording) stopPhoneAudioIfCameraReallyStopped()
                lastCameraIsRecording = nowRecording
            }
            .launchIn(lifecycleScope)
        cameraModeController.isRecordingIntent
            .onEach { recording ->
                updateCameraModeUi()
                if (recording) startPhoneAudioForRecordingIntent()
            }
            .launchIn(lifecycleScope)
        audioRecorderController.isRecording
            .onEach { updateMoreSettingsTrayUi() }
            .launchIn(lifecycleScope)
    }

    /**
     * Starts AudioRecorderController off the app's own record-start intent
     * - CameraModeController.isRecordingIntent - since starting recording
     * is reliable on this camera/SDK combo (only STOP is documented as
     * broken - see CameraModeController's class doc comment). GIMBAL is a
     * no-op inside start() itself, so this doesn't need to check the
     * selected kind first.
     */
    private fun startPhoneAudioForRecordingIntent() {
        val kind = AudioSourceController.selectedKind
        if (kind != AudioSourceKind.GIMBAL && !audioRecorderController.start(this, kind)) {
            showErrorToast("Couldn't start phone audio recording (${AudioSourceController.label(this, kind)})")
        }
    }

    /**
     * Stopping, unlike starting, is deliberately NOT tied to the app's own
     * shutter-button press - CameraModeController's doc comment documents
     * at length that software stop-record can't be trusted on this rig,
     * confirmed on two independent phones: pressing the app's stop button
     * usually does nothing real, and the camera just keeps recording video
     * until the physical stop button on the gimbal itself is pressed. If
     * phone audio stopped on the app button press instead, it would almost
     * always end far earlier than the video it's meant to go with. Tying
     * this to SystemState.isRecording's real true -> false edge instead
     * (observeCameraState() above) means phone audio keeps rolling for
     * exactly as long as the video actually does, however that stop
     * happens - the physical button (the normal case) or, on the rare
     * occasion the software stop actually works, that.
     */
    private fun stopPhoneAudioIfCameraReallyStopped() {
        audioRecorderController.stop()?.let { file ->
            android.widget.Toast.makeText(this, "Audio saved: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCameraModeUi() {
        val state = DJIConnectionManager.cameraSystemState.value
        val photoModeButton = findViewById<android.widget.ImageButton>(R.id.btnPhotoMode)
        val videoModeButton = findViewById<android.widget.ImageButton>(R.id.btnVideoMode)
        val shutterButton = findViewById<android.widget.Button>(R.id.btnShutter)
        val isVideoMode = state?.mode == dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO

        photoModeButton.setBackgroundResource(
            if (isVideoMode) android.R.color.transparent else R.drawable.bg_segment_selected
        )
        videoModeButton.setBackgroundResource(
            if (isVideoMode) R.drawable.bg_segment_selected else android.R.color.transparent
        )
        // Red reflects our own intent instantly on press (see
        // CameraModeController's doc comment on why). Orange is a mismatch
        // warning with NO timeout - shown for as long as we've asked to
        // stop but the camera's own real isRecording still says otherwise,
        // since real hardware testing found the physical record light
        // could still be blinking well after this app had given up
        // retrying and quietly gone back to looking idle. White only once
        // reality actually agrees.
        val stopUnconfirmed = !cameraModeController.isRecordingIntent.value && state?.isRecording == true
        shutterButton.setBackgroundResource(
            when {
                stopUnconfirmed -> R.drawable.bg_shutter_stopping
                isVideoMode && cameraModeController.isRecordingIntent.value -> R.drawable.bg_shutter_recording
                else -> R.drawable.bg_shutter_photo
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFrameCapture()
        videoFrameProvider.release()
        softwareAfcController.stop()
        cameraSounds.release()
        histogramController.deactivate()
        // Safety net, not the normal path - the isRecordingIntent observer
        // above already stops this on a normal shutter-button stop press.
        // Only matters if the Activity gets torn down mid-recording.
        audioRecorderController.stop()
    }
}
