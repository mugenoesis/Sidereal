package io.github.mugenoesis.sidereal.gimbal

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Driven by the on-screen virtual joystick (JoystickView): the stick's
 * offset from its center (normalized -1..1 per axis) acts as a rate that
 * accumulates into a target pitch/yaw angle, sent via ABSOLUTE_ANGLE on a
 * fixed-rate loop for as long as the stick is held.
 *
 * This replaced an earlier RotationMode.SPEED-based version. SPEED means
 * "keep moving at this rate until told otherwise", so a late/stale command
 * arriving after release could keep the gimbal moving with no natural
 * bound - real hardware testing showed a brief but real drift after
 * release that persisted across several tuning attempts. ABSOLUTE_ANGLE
 * means "move to this position" instead: even a late command just moves
 * toward a specific angle we genuinely intended a moment earlier, not an
 * open-ended continuation.
 *
 * Only active while GimbalModeController.currentMode == MANUAL.
 */
class ManualGimbalController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sendJob: Job? = null

    private var freeModeRequested = false

    private var active = false
    private var yawRateDegPerSec = 0f
    private var pitchRateDegPerSec = 0f

    private var targetPitch = 0f
    private var targetYaw = 0f
    private var hasTarget = false

    // Degrees/sec at full stick deflection, and the send rate - both
    // first-guess values needing real-hardware tuning.
    private val maxSpeedDegPerSec = 30f
    private val commandIntervalMs = 100L

    fun activate() {
        active = true
        freeModeRequested = false
        hasTarget = false
    }

    /**
     * Call whenever DJIConnectionManager.componentsBoundTick changes (i.e.
     * on every reconnect, not just the initial connect). freeModeRequested
     * previously only reset inside activate() - fine for the normal case
     * of switching gimbal modes, but a disconnect/reconnect while already
     * sitting in MANUAL mode (the common case, since it's the default)
     * never calls activate() again, so the flag stayed true from before
     * the drop and ensureFreeMode() never re-fired FREE on the fresh
     * Gimbal connection object - real hardware testing found this left
     * the gimbal unresponsive to the joystick after a WiFi hiccup, with no
     * way to recover short of restarting the app. Safe to call regardless
     * of whether a reconnect actually changed anything - the next
     * ensureFreeMode() call is a harmless single request either way.
     */
    fun resetFreeModeRequest() {
        freeModeRequested = false
    }

    fun deactivate() {
        active = false
        stopSending()
    }

    /**
     * x,y normalized -1..1 from JoystickView. Real hardware testing found
     * screen-down needs to map to pitch-up (not pitch-down) to feel right -
     * i.e. drag down to tilt the camera up, matching how a real camera
     * gimbal joystick typically works.
     */
    fun onJoystickMoved(x: Float, y: Float) {
        if (!active) return
        yawRateDegPerSec = ManualGimbalMath.rateFromStick(x, maxSpeedDegPerSec)
        pitchRateDegPerSec = ManualGimbalMath.rateFromStick(y, maxSpeedDegPerSec)
        ensureTargetInitialized()
        if (sendJob?.isActive != true) {
            startSending()
        }
    }

    /** Seeds the running target from the gimbal's actual current attitude, so the first sent angle isn't a jump from 0/0. */
    private fun ensureTargetInitialized() {
        if (hasTarget) return
        val attitude = DJIConnectionManager.gimbalState.value?.attitudeInDegrees
        targetPitch = attitude?.pitch ?: 0f
        targetYaw = attitude?.yaw ?: 0f
        hasTarget = true
    }

    /**
     * Requests FREE mode once per activate() session - not retried.
     * A retrying version was tried (every 2s until GimbalState confirmed
     * FREE), reasoning that a single call routinely doesn't stick. That
     * held up, but retrying turned out to be the wrong response: real
     * hardware testing (decompiling the SDK) found the "Execution of this
     * process has timed out" callback error is a REAL SDK-internal
     * completion-poll timeout, not a cosmetic fake one - meaning the
     * firmware genuinely processes and rejects each attempt. On the face-
     * tracking side, jitter was confirmed to stop completely the instant
     * tracking was turned off, ruling out passive hand-shake passing
     * through an unstabilized axis and pointing at something the app's own
     * loop was doing - repeatedly hammering a call the firmware keeps
     * rejecting was the prime suspect there, so this mirrors that fix.
     * Not awaited before sending either way - its completion callback is
     * as unreliable as rotate()'s (see README).
     */
    private fun ensureFreeMode() {
        if (freeModeRequested) return
        freeModeRequested = true
        DJIConnectionManager.gimbal?.setMode(dji.common.gimbal.GimbalMode.FREE) { error ->
            if (error != null) {
                Log.w("ManualGimbalController", "setMode(FREE) failed: ${error.description}")
            }
        }
    }

    fun onJoystickReleased() {
        stopSending()
    }

    fun onDoubleTap() {
        if (!active) return
        recenter()
    }

    private fun startSending() {
        sendJob = scope.launch {
            while (isActive) {
                ensureFreeMode()
                val dtSeconds = commandIntervalMs / 1000f
                // Clamps to the gimbal's own reported real range rather
                // than a guess - a guessed conservative-looking range
                // (-90..30 pitch) turned out to be wrong for this product
                // and caused a worse failure than no clamp at all: once
                // the target hit that boundary it got pinned there
                // permanently, so every following command was rejected
                // forever instead of just during a transient excursion.
                // See FaceTrackingController.applyCorrection for the fuller
                // writeup (same fix there, confirmed on real hardware). No
                // clamp at all if the capability isn't available yet.
                targetPitch = ManualGimbalMath.nextTarget(targetPitch, pitchRateDegPerSec, dtSeconds, DJIConnectionManager.pitchRangeDegrees())
                targetYaw = ManualGimbalMath.nextTarget(targetYaw, yawRateDegPerSec, dtSeconds, DJIConnectionManager.yawRangeDegrees())
                sendAbsolute(targetPitch, targetYaw)
                delay(commandIntervalMs)
            }
        }
    }

    private fun stopSending() {
        sendJob?.cancel()
        sendJob = null
    }

    /**
     * Rotation.Builder's .time() (seconds) was previously left unset,
     * defaulting to an instant snap for ABSOLUTE_ANGLE moves - DJI's own
     * docs describe it as "move to an angle over a duration," easing the
     * gimbal there instead of slewing at whatever rate it can manage.
     * Passed as commandIntervalMs/1000 so each new target eases in over
     * the same span between sends, instead of snapping every 100ms.
     */
    private fun sendAbsolute(pitch: Float, yaw: Float) {
        val gimbal = DJIConnectionManager.gimbal ?: return

        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(pitch)
            .yaw(yaw)
            .time(commandIntervalMs / 1000.0)
            .build()

        gimbal.rotate(rotation) { error ->
            if (error != null) {
                Log.w("ManualGimbalController", "rotate() failed: ${error.description}")
            }
        }
    }

    private fun recenter() {
        val gimbal = DJIConnectionManager.gimbal ?: return
        targetPitch = 0f
        targetYaw = 0f
        hasTarget = true

        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(0f)
            .yaw(0f)
            .roll(0f)
            .time(0.5)
            .build()

        gimbal.rotate(rotation) { error ->
            if (error != null) {
                Log.w("ManualGimbalController", "recenter rotate() failed: ${error.description}")
            }
        }
    }
}
