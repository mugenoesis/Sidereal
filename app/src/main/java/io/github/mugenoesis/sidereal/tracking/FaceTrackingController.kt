package io.github.mugenoesis.sidereal.tracking

import android.graphics.RectF
import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.gimbal.PidController
import io.github.mugenoesis.sidereal.zoom.ZoomController
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class FollowStyle {
    LOCKED_ON,
    TRAIL
}

/**
 * Three states this manages, matching the UI toggle behavior discussed:
 *  - DISARMED: detection not running at all (default, saves CPU/battery)
 *  - ARMED: detection running, boxes drawn, nothing selected yet
 *  - LOCKED: one face selected by trackingId, gimbal actively correcting
 */
enum class TrackingState {
    DISARMED,
    ARMED,
    LOCKED
}

data class DetectedFace(
    val trackingId: Int,
    val boundingBox: RectF // normalized 0..1 coordinates within the preview
)


/**
 * NOTE on identity persistence: ML Kit's trackingId is maintained by its
 * internal frame-to-frame tracker, not true face recognition. It survives
 * brief occlusion/motion blur well, but if the subject fully leaves frame
 * and comes back, there's a real chance they get a new ID and you'll need
 * to re-tap to select them again. That's an accepted tradeoff for v1 -
 * a face-embedding re-identification fallback can be layered in later if
 * it turns out to matter in practice.
 */
class FaceTrackingController(
    private val zoomController: ZoomController
) {

    // Detection itself (the ML Kit client + the tracking-enabled options
    // that give us stable trackingIds) lives in VideoFrameProvider, since
    // that's where raw frames actually arrive. This class only consumes
    // results and owns the follow-behavior state machine.

    // Field of view of the lens on the camera, to keep the tracking gains right on lenses other than the 15 mm they were tuned on.
    private companion object {
        const val REFERENCE_H_FOV_DEG = 60.0
        const val REFERENCE_V_FOV_DEG = 46.2
    }

    @Volatile private var yawGainScale = 1.0
    @Volatile private var pitchGainScale = 1.0

    /** Call with the lens' focal length (mm) when it is known, or null if not; scales the tracking gains to its field of view. */
    fun setLensFocalMm(focalMm: Float?) {
        if (focalMm == null) { yawGainScale = 1.0; pitchGainScale = 1.0; return }
        val (h, v) = io.github.mugenoesis.sidereal.sequence.PanoramaPlanner.fovFor(focalMm)
        yawGainScale = FaceTrackingMath.fovGainScale(h.toDouble(), REFERENCE_H_FOV_DEG)
        pitchGainScale = FaceTrackingMath.fovGainScale(v.toDouble(), REFERENCE_V_FOV_DEG)
    }

    private val _trackingState = MutableStateFlow(TrackingState.DISARMED)
    val trackingState: StateFlow<TrackingState> = _trackingState

    private val _detectedFaces = MutableStateFlow<List<DetectedFace>>(emptyList())
    val detectedFaces: StateFlow<List<DetectedFace>> = _detectedFaces

    private var selectedTrackingId: Int? = null
    private var followStyle: FollowStyle = FollowStyle.LOCKED_ON

    // Target point in normalized frame coords (0.5, 0.5 = dead center).
    // Updated by manual gimbal nudge while a face is locked, per the
    // "wherever you drag it to becomes the new hold point" behavior.
    private var targetX = 0.5
    private var targetY = 0.5

    // Output is a rate (deg/sec) integrated into an accumulating gimbal
    // target angle and sent via ABSOLUTE_ANGLE, not RotationMode.SPEED -
    // same reasoning as ManualGimbalController: SPEED means "keep moving
    // until told otherwise", so if a frame is lost or delayed (ML Kit
    // busy, WiFi hiccup) there's no bounded stopping point. With
    // ABSOLUTE_ANGLE, simply not sending a new command when the face is
    // lost means the gimbal just holds at its last position instead of
    // coasting - confirmed as the real fix for the equivalent manual-
    // control drift bug.
    //
    // Separate gains per follow style, not one shared pair: LOCKED_ON is
    // meant to snap the subject back to center aggressively, while TRAIL's
    // "slow drift" character mostly comes from smoothing the input position
    // (trailAlpha below) rather than the correction rate itself, so it
    // keeps the gentler, manual-joystick-safe 30 deg/sec cap.
    // Output clamp of 180 deg/sec matches DJI's own published "Max
    // Controllable Speed" spec for the Osmo Pocket 3's gimbal (pan/tilt/
    // roll, per dji.com/osmo-pocket-3/specs) - used here as a reference
    // target for how fast aggressive subject tracking should feel, even
    // though this app runs on different (Zenmuse X5 / Osmo Pro) hardware.
    // Safe to request regardless: the DJI SDK/firmware caps actual motion
    // at whatever the connected gimbal's own real max speed is, so this
    // clamp just stops our own PID output from asking for something even
    // higher, not from the real hardware ceiling.
    //
    // kD raised from 4 to 18 after real hardware logging (with the camera
    // actually in RECORD_VIDEO mode - an earlier round of testing turned
    // out to have been against SHOOT_PHOTO's slower feedback loop the whole
    // time) showed genuine underdamped overshoot on fast subject movement:
    // errorY crossed zero and overshot to the opposite sign while pitchRate
    // swung from -18 to +19 correcting back the other way - the "blows
    // right passed the subject before coming back to settle" behavior
    // described directly. kD was originally kept low specifically because
    // the derivative term amplifies ML Kit's ordinary per-frame bounding-
    // box jitter into visible micro-motion - but that concern is largely
    // moot now: errorDeadband-range noise never reaches pid.update() at all
    // any more (skipped and PID reset entirely inside the deadband, see
    // below), so a higher kD here only acts on genuinely large, real
    // corrections, which is exactly where more damping is needed.
    //
    // maxDerivative=1.5 (both axes) added after further real hardware
    // testing: pitch specifically still "bobbed" after the kD increase
    // above, and logged pitchRate was spiking to 60+ on single frames -
    // traced to the derivative term reacting to a large one-frame error
    // jump (fast real movement, or the shake-jump filter accepting one in
    // a single step rather than a smooth ramp) rather than the slow
    // oscillation kD was meant to damp. See PidController's doc comment
    // for the fuller reasoning. That fixed the single-frame spikes (logged
    // pitchRate stayed in the single/low-double digits afterward), but a
    // real, sustained errorY oscillation (crossing zero repeatedly,
    // amplitude up to +-0.15) remained and was reported as worse, while
    // yaw at the same gains was fine. Pitch gets its own, gentler gains
    // rather than sharing yaw's - people naturally move their head up/down
    // (nodding, glancing away) far more than side-to-side while just
    // sitting there, so pitch gets excited by ordinary movement much more
    // often, and kP=100 chases that hard enough to visibly overreact to
    // it. Yaw is untouched since it was confirmed working well as-is.
    private val lockedPitchPid = PidController(kP = 55.0, kD = 10.0, outputMin = -180.0, outputMax = 180.0, maxDerivative = 1.5)
    private val lockedYawPid = PidController(kP = 100.0, kD = 18.0, outputMin = -180.0, outputMax = 180.0, maxDerivative = 1.5)
    private val trailPitchPid = PidController(kP = 40.0, kD = 6.0, outputMin = -30.0, outputMax = 30.0)
    private val trailYawPid = PidController(kP = 40.0, kD = 6.0, outputMin = -30.0, outputMax = 30.0)

    // Errors smaller than this (normalized frame units) are treated as
    // zero rather than fed to the PID - without it, ordinary detector
    // jitter on a stationary subject produces a small but constant nonzero
    // error every frame, which reads as the gimbal visibly "breathing".
    // Real hardware logging of a genuinely still subject showed the
    // detector's own natural jitter peaks around 0.015-0.021 - a deadband
    // set at 0.015 sat right on top of that noise floor instead of above
    // it, so error kept flickering in and out of the zone and firing small
    // corrective pulses each crossing. This needs real margin over the
    // observed noise, not just to match it.
    private val errorDeadband = 0.035

    // Rejects handheld-shake spikes distinct from real subject movement.
    // errorDeadband only ignores small errors near the hold target - it
    // does nothing about a *large* single-frame jump, which is exactly
    // what camera shake looks like (the subject didn't move, the whole
    // frame jerked). Confirmed on real hardware that gimbal.setMode(FREE)
    // never actually succeeds on this product (GimbalState kept reporting
    // YAW_FOLLOW through dozens of retries), so there's no IMU-level
    // stabilization backing this up - our software loop is the only thing
    // reacting to position changes, and without this check it can't tell
    // "subject moved" from "camera jerked," so it ends up fighting shake
    // instead of ignoring it.
    //
    // First attempt compared each new sample against the average of the
    // last 1.5s of history - wrong shape of filter. Confirmed on real
    // hardware: during genuine sustained fast movement, that average lags
    // the whole way through the window, so every frame reads as an
    // "outlier" and gets rejected for up to the full 1.5s, then once the
    // average finally catches up everything fires at once as one huge
    // corrective snap - a "freeze then jerk" pattern, worse than no filter.
    // This instead compares only against the immediately preceding sample
    // and requires just ONE confirming frame in a consistent direction
    // before accepting a big jump as real movement - at most one frame of
    // added latency (~80-100ms) instead of up to 1.5s, and an isolated
    // spike that doesn't repeat gets discarded outright.
    private val shakeJumpFilter = ShakeJumpFilter(shakeJumpDistance = 0.12, shakeConfirmDistance = 0.05)

    private var freeModeRequested = false
    private var targetPitch = 0f
    private var targetYaw = 0f
    // Degrees - well above ordinary one-frame catch-up lag between a
    // commanded target and the gimbal's real-time position, but well
    // below a deliberate manual repositioning distance (e.g. via the
    // Osmo's own physical stick). Also covers seeding the very first
    // target after a lock, since it defaults to 0/0 - target starting far
    // from wherever the gimbal actually is triggers this the same way.
    private val externalMoveThreshold = 15f

    // Exponential smoothing factor for TRAIL mode - lower = more lag/drift,
    // higher = closer to locked-on. Applied to the face position before PID.
    private var trailAlpha = 0.15
    private var smoothedX = 0.5
    private var smoothedY = 0.5
    private var hasSmoothedValue = false

    // Last raw (pre-smoothing) position of the locked face, used to
    // re-acquire tracking after ML Kit's internal tracker drops the ID and
    // assigns a new one - real hardware testing found this happens easily
    // under fast head/camera movement, well before the subject actually
    // leaves frame. Re-adopting the nearest newly-seen face is a pragmatic
    // stand-in for the face-embedding re-identification flagged as future
    // work in this class's doc comment - good enough for the single-
    // subject case this app targets.
    private var lastFaceX = 0.5
    private var lastFaceY = 0.5
    private val reacquireMaxDistance = 0.25
    private var pendingReacquireId: Int? = null

    private var lastSeenTimestamp = 0L
    // How long to keep coasting in the last known direction after losing
    // the face before giving up and genuinely holding position - see
    // applyCorrection's use in processLockedFrame's target==null branch.
    private val faceLostHoldMillis = 3500L
    private var lastKnownPitchRate = 0.0
    private var lastKnownYawRate = 0.0
    // Deg/sec - deliberately much gentler than LOCKED_ON's tracking clamp
    // (up to 90). Coasting is a best-effort search sweep, not a correction,
    // so a slow, decaying sweep gives detection more chances to catch the
    // subject than a fast blind dash that's easy to overshoot past them.
    private val coastMaxRate = 20.0

    private var lastUpdateTime = System.currentTimeMillis()

    // --- Auto-zoom (keep face size constant) ---
    // Independent of followStyle - this controls distance (via zoom),
    // while followStyle controls framing position. The two combine freely:
    // e.g. TRAIL position style + auto-zoom both active at once.
    private var autoZoomEnabled = false
    private var targetFaceHeight: Double? = null // normalized 0..1, captured at selection time
    private val zoomPid = PidController(kP = 8.0, kD = 1.0, outputMin = -0.5, outputMax = 0.5)

    fun arm() {
        _trackingState.value = TrackingState.ARMED
        _detectedFaces.value = emptyList()
        freeModeRequested = false
    }

    /**
     * Call whenever DJIConnectionManager.componentsBoundTick changes - same
     * reasoning and fix as ManualGimbalController.resetFreeModeRequest():
     * a disconnect/reconnect while already armed never calls arm() again,
     * so freeModeRequested stayed true from before the drop and
     * ensureFreeMode() never re-fired FREE on the fresh Gimbal connection.
     */
    fun resetFreeModeRequest() {
        freeModeRequested = false
    }

    fun disarm() {
        _trackingState.value = TrackingState.DISARMED
        selectedTrackingId = null
        _detectedFaces.value = emptyList()
        resetPids()
        zoomPid.reset()
        hasSmoothedValue = false
        shakeJumpFilter.reset()
        pendingReacquireId = null
        lastKnownPitchRate = 0.0
        lastKnownYawRate = 0.0
    }

    private fun resetPids() {
        lockedPitchPid.reset()
        lockedYawPid.reset()
        trailPitchPid.reset()
        trailYawPid.reset()
    }

    fun setFollowStyle(style: FollowStyle) {
        followStyle = style
        resetPids()
        hasSmoothedValue = false
    }

    /**
     * Only meaningful when the connected lens supports digital zoom -
     * check zoomController.capability.value.supported before exposing
     * this toggle in the UI. When enabled, the target face size is
     * captured fresh from whatever face is selected right now (or the
     * next one selected), same pattern as position's targetX/targetY.
     */
    fun setAutoZoomEnabled(enabled: Boolean) {
        autoZoomEnabled = enabled
        zoomPid.reset()
        if (enabled) {
            captureTargetFaceHeightFromCurrent()
        } else {
            targetFaceHeight = null
        }
    }

    private fun captureTargetFaceHeightFromCurrent() {
        val id = selectedTrackingId ?: return
        val face = _detectedFaces.value.find { it.trackingId == id } ?: return
        targetFaceHeight = face.boundingBox.height().toDouble()
    }

    /** User tapped a bounding box in ARMED state. */
    fun selectFace(trackingId: Int) {
        selectedTrackingId = trackingId
        targetX = 0.5
        targetY = 0.5
        resetPids()
        hasSmoothedValue = false
        shakeJumpFilter.reset()
        pendingReacquireId = null
        lastKnownPitchRate = 0.0
        lastKnownYawRate = 0.0
        lastUpdateTime = System.currentTimeMillis()
        _trackingState.value = TrackingState.LOCKED
        if (autoZoomEnabled) {
            captureTargetFaceHeightFromCurrent()
        }
    }

    /** Double-tap while LOCKED - drop selection, go back to ARMED for reselection. */
    fun deselect() {
        selectedTrackingId = null
        _trackingState.value = TrackingState.ARMED
    }

    /**
     * User dragged the locked box to a new screen position - that position
     * directly becomes the new hold point going forward (not derived from
     * wherever the face happens to be sitting right now).
     */
    fun setTargetPosition(x: Double, y: Double) {
        if (_trackingState.value != TrackingState.LOCKED) return
        targetX = x.coerceIn(0.0, 1.0)
        targetY = y.coerceIn(0.0, 1.0)
    }

    /**
     * Feed each analyzed camera frame's detected faces here. Call this from
     * your ImageAnalysis / CameraX or DJI video-frame callback, after
     * running the ML Kit detector on the frame's InputImage.
     *
     * @param faces raw ML Kit results for this frame
     * @param frameWidth, frameHeight pixel dimensions of the analyzed frame,
     *   used to normalize bounding boxes to 0..1
     */
    fun onFacesDetected(faces: List<Face>, frameWidth: Int, frameHeight: Int) {
        if (_trackingState.value == TrackingState.DISARMED) return

        val normalized = faces.mapNotNull { face ->
            val id = face.trackingId ?: return@mapNotNull null
            val box = face.boundingBox
            DetectedFace(
                trackingId = id,
                boundingBox = RectF(
                    box.left.toFloat() / frameWidth,
                    box.top.toFloat() / frameHeight,
                    box.right.toFloat() / frameWidth,
                    box.bottom.toFloat() / frameHeight
                )
            )
        }
        _detectedFaces.value = normalized

        if (_trackingState.value == TrackingState.LOCKED) {
            processLockedFrame(normalized)
        }
    }

    private fun processLockedFrame(faces: List<DetectedFace>) {
        val now = System.currentTimeMillis()
        // Clamped, not just floored: lastUpdateTime resets on selectFace(),
        // but any other idle gap (ML Kit stalls, app briefly backgrounded)
        // would otherwise produce a huge dt here - and since dt is used
        // below to integrate a PID rate into an accumulating target angle
        // (targetPitch/targetYaw += rate * dt), an inflated dt turns a
        // small, clamped rate into a single huge angle jump, which is
        // exactly what caused the gimbal to slew to an invalid angle and
        // get stuck rejecting every subsequent command with "Param Illegal"
        // on real hardware - confirmed via logging actual gimbal mode and
        // attitude alongside each command.
        val dt = ((now - lastUpdateTime).coerceIn(1, 500)) / 1000.0
        lastUpdateTime = now

        var target = faces.find { it.trackingId == selectedTrackingId }

        if (target == null) {
            // Requires the SAME candidate to be the nearest match on two
            // consecutive lost frames before trusting it - a single frame
            // isn't enough, since that risks latching onto a background
            // face or a one-off false-positive detection and chasing it
            // (confirmed on real hardware as "erratic movement" right after
            // losing the real subject). Costs at most one extra frame
            // (~80-100ms) versus reacquiring immediately, same tradeoff as
            // the shake-jump confirmation check below.
            val candidateId = FaceTrackingMath.findReacquireCandidateId(
                faces.map { FaceTrackingMath.FaceCenter(it.trackingId, it.boundingBox.centerX().toDouble(), it.boundingBox.centerY().toDouble()) },
                lastFaceX, lastFaceY, reacquireMaxDistance
            )
            if (candidateId != null && candidateId == pendingReacquireId) {
                Log.d("FaceTrackingController", "reacquired: selectedTrackingId=$selectedTrackingId -> $candidateId")
                selectedTrackingId = candidateId
                target = faces.find { it.trackingId == candidateId }
                pendingReacquireId = null
            } else {
                pendingReacquireId = candidateId
            }
        }

        if (target == null) {
            // Best-effort continue in the direction the face was last
            // heading, rather than freezing the instant it's gone - if it
            // left the frame moving right, the gimbal has a much better
            // chance of catching back up (and reacquiring) by continuing
            // right than by stopping dead where it lost it. Only for
            // faceLostHoldMillis though - past that, coasting on a
            // direction that's probably stale does more harm than good, so
            // it falls back to genuinely holding position.
            //
            // lastKnownPitchRate/YawRate reflect whatever LOCKED_ON's
            // aggressive gains were commanding right as the face hit frame
            // edge - up to +-90 deg/sec. Coasting at that full rate for the
            // whole hold window overshot past the subject on real hardware
            // ("went right passed me") before it could be reacquired.
            // Coasting is a best-effort search, not a tracking correction,
            // so it's capped to a much gentler speed and linearly decayed
            // toward zero over the window - a slowing sweep gives detection
            // more chances to catch the subject per degree of travel,
            // instead of a fast blind dash that's easy to overshoot.
            val elapsedSinceLostMs = now - lastSeenTimestamp
            val coastPitchRate = FaceTrackingMath.coastRate(lastKnownPitchRate, coastMaxRate, elapsedSinceLostMs, faceLostHoldMillis)
            val coastYawRate = FaceTrackingMath.coastRate(lastKnownYawRate, coastMaxRate, elapsedSinceLostMs, faceLostHoldMillis)
            if (coastPitchRate != null && coastYawRate != null) {
                applyCorrection(coastPitchRate, coastYawRate, dt)
            } else if (_trackingState.value == TrackingState.LOCKED) {
                // Coast window expired without reacquiring (e.g. the
                // subject turned away and there's genuinely no face to
                // detect) - previously this just sat "LOCKED" forever with
                // nothing to track, doing nothing and leaving the user with
                // no way to intervene (the on-screen joystick only arms
                // outside FACE_TRACK's LOCKED state - see MainActivity).
                // Falling back to ARMED lets them use it to manually search,
                // and still shows candidate boxes to re-tap once the
                // subject's face is visible again.
                deselect()
            }
            return
        }
        lastSeenTimestamp = now

        var faceX = target.boundingBox.centerX().toDouble()
        var faceY = target.boundingBox.centerY().toDouble()
        lastFaceX = faceX
        lastFaceY = faceY

        if (shakeJumpFilter.isShakeJump(faceX, faceY)) return

        if (followStyle == FollowStyle.TRAIL) {
            if (!hasSmoothedValue) {
                smoothedX = faceX
                smoothedY = faceY
                hasSmoothedValue = true
            } else {
                smoothedX += (faceX - smoothedX) * trailAlpha
                smoothedY += (faceY - smoothedY) * trailAlpha
            }
            faceX = smoothedX
            faceY = smoothedY
        }

        val errorX = faceX - targetX // positive = face is right of target -> yaw right
        val errorY = faceY - targetY // positive = face is below target -> needs pitch-down correction
        val withinDeadbandX = FaceTrackingMath.isWithinDeadband(errorX, errorDeadband)
        val withinDeadbandY = FaceTrackingMath.isWithinDeadband(errorY, errorDeadband)

        val (activePitchPid, activeYawPid) = if (followStyle == FollowStyle.TRAIL) {
            trailPitchPid to trailYawPid
        } else {
            lockedPitchPid to lockedYawPid
        }

        // Previously fed a deadband-clamped error (real value forced to
        // exactly 0.0) into the PID every frame. That corrupts the
        // derivative term: it sees the artificial jump from a real error to
        // a clamped 0.0 as if the subject had actually moved, producing a
        // nonzero output even while genuinely still - a real, confirmed
        // self-inflicted jitter source (jitter was reported to stop
        // completely the instant face tracking was turned off, which rules
        // out passive hand-shake and points at something in this loop
        // itself). Skipping pid.update() entirely inside the deadband, and
        // resetting so the next real update starts clean, avoids feeding it
        // a fabricated derivative.
        val yawRate = if (withinDeadbandX) {
            activeYawPid.reset()
            0.0
        } else {
            activeYawPid.update(errorX, dt) * yawGainScale
        }
        // NOT negated, despite ManualGimbalController needing a pitch-axis
        // flip for its human-intent (joystick-drag-direction) mapping - that
        // doesn't carry over here. Real hardware logging (actual attitude
        // alongside commanded target) showed the opposite assumption was
        // wrong: with this sign, errorY reliably converges as pitch is
        // corrected; negating it made errorY grow instead. Don't re-flip
        // this without re-verifying against logged real attitude data.
        val pitchRate = if (withinDeadbandY) {
            activePitchPid.reset()
            0.0
        } else {
            activePitchPid.update(errorY, dt) * pitchGainScale
        }

        ensureFreeMode()

        Log.d("FaceTrackingController", "faceX=$faceX faceY=$faceY errorX=$errorX errorY=$errorY yawRate=$yawRate pitchRate=$pitchRate dt=$dt")

        lastKnownPitchRate = pitchRate
        lastKnownYawRate = yawRate
        applyCorrection(pitchRate, yawRate, dt)

        if (autoZoomEnabled) {
            processAutoZoom(target, dt)
        }
    }

    /**
     * Also don't resend an unchanged target every single frame just because
     * a frame arrived - repeatedly reissuing an identical ABSOLUTE_ANGLE
     * move (now with a .time() duration) could itself make the gimbal's
     * motion planner stutter. Only send when there's an actual rate to
     * apply.
     */
    private fun applyCorrection(pitchRate: Double, yawRate: Double, dt: Double) {
        if (yawRate == 0.0 && pitchRate == 0.0) return

        // Only resyncs the accumulator to the gimbal's actual current
        // attitude when they've diverged by more than externalMoveThreshold
        // - not unconditionally every frame. Unconditional resync (tried
        // first) fixed the staleness bug below but capped tracking speed at
        // whatever the gimbal's real-time physical response could keep up
        // with each single frame, since the accumulator could no longer
        // lead ahead of its own still-in-flight .time()-eased moves -
        // confirmed on real hardware as tracking becoming too slow to keep
        // up with normal movement. Gating the resync on a large gap keeps
        // that lead-ahead speed during normal operation, while still
        // catching genuine external interference: after losing the face
        // and using the Osmo's own physical stick to manually recenter, the
        // app's internal targetPitch/targetYaw stayed wherever it was left
        // before losing lock - that stick input moves the gimbal without
        // this app's knowledge, so once tracking resumed, the next
        // correction was computed from a stale accumulated target far from
        // where the gimbal actually now was, sending it snapping back
        // toward the old, wrong reference ("camera turns the wrong way").
        // A gap that large is well beyond normal one-frame catch-up lag, so
        // it's a reliable signal something else moved the gimbal.
        val attitude = DJIConnectionManager.gimbalState.value?.attitudeInDegrees
        if (attitude != null) {
            if (FaceTrackingMath.shouldResyncTarget(targetPitch, attitude.pitch, externalMoveThreshold)) {
                targetPitch = attitude.pitch
            }
            if (FaceTrackingMath.shouldResyncTarget(targetYaw, attitude.yaw, externalMoveThreshold)) {
                targetYaw = attitude.yaw
            }
        }
        // Real hardware logging showed rotate() getting rejected outright
        // with "Param Illegal" during large, fast tracking excursions.
        // First fix attempt clamped to a guessed conservative range
        // (-90..30 pitch) - wrong for this product, and worse than no
        // clamp: once the accumulating target hit that boundary it got
        // pinned there permanently (coerceIn caps every subsequent frame
        // at the same invalid value), so every following command was
        // rejected forever instead of just during the transient excursion
        // - a full tracking lock-up. Clamping to the gimbal's own reported
        // real range (DJIConnectionManager.pitchRangeDegrees/
        // yawRangeDegrees, from Gimbal.getCapabilities()) instead of a
        // guess avoids that failure mode entirely. If the capability isn't
        // available yet, this deliberately does NOT clamp at all - an
        // occasional transient rejection during a big excursion (which
        // self-recovers once the subject comes back toward center) is a
        // much smaller problem than repeating the permanent-pin bug with
        // another guess. Reuses ManualGimbalMath.nextTarget - same
        // accumulate-then-conditionally-clamp shape as the manual joystick
        // path.
        targetPitch = io.github.mugenoesis.sidereal.gimbal.ManualGimbalMath.nextTarget(targetPitch, pitchRate.toFloat(), dt.toFloat(), DJIConnectionManager.pitchRangeDegrees())
        targetYaw = io.github.mugenoesis.sidereal.gimbal.ManualGimbalMath.nextTarget(targetYaw, yawRate.toFloat(), dt.toFloat(), DJIConnectionManager.yawRangeDegrees())
        sendGimbalTarget(targetPitch, targetYaw, dt)
    }

    /**
     * Requests FREE mode once per lock session - back to one-shot, not
     * retried. An earlier version retried every 2s until GimbalState
     * confirmed FREE, reasoning that a single call routinely doesn't stick.
     * That's true, but retrying turned out to be the wrong response: real
     * hardware testing (decompiling the SDK) found the "Execution of this
     * process has timed out" callback error is a REAL SDK-internal
     * completion-poll timeout, not the cosmetic fake one rotate() reports -
     * meaning the firmware genuinely processes and rejects each attempt,
     * every 2s, for as long as tracking stays locked. Separately, jitter
     * was confirmed to stop completely the instant face tracking was
     * turned off, which rules out passive hand-shake passing through an
     * unstabilized axis and points at something this app's own loop was
     * doing - repeatedly hammering a call the firmware keeps rejecting is
     * the prime remaining suspect. One attempt per session is enough to
     * take the win if conditions ever allow it, without repeatedly poking
     * a control the gimbal has already said no to.
     */
    private fun ensureFreeMode() {
        if (freeModeRequested) return
        freeModeRequested = true
        DJIConnectionManager.gimbal?.setMode(dji.common.gimbal.GimbalMode.FREE) { error ->
            if (error != null) {
                Log.w("FaceTrackingController", "setMode(FREE) failed: ${error.description}")
            }
        }
    }

    /**
     * Keeps the tracked face's size in frame roughly constant by nudging
     * digital zoom in/out as the subject moves closer/further. Uses raw
     * (unsmoothed) face box height as the error signal even in TRAIL mode -
     * trail smoothing is about framing lag, not distance, so zoom should
     * react to actual size changes rather than the smoothed position.
     */
    private fun processAutoZoom(target: DetectedFace, dt: Double) {
        val targetHeight = targetFaceHeight
        if (targetHeight == null) {
            targetFaceHeight = target.boundingBox.height().toDouble()
            return
        }

        val currentHeight = target.boundingBox.height().toDouble()
        // Positive error = face is bigger than target (too close) -> zoom out (negative delta).
        // Negative error = face is smaller than target (too far) -> zoom in (positive delta).
        val sizeError = currentHeight - targetHeight
        val zoomDelta = -zoomPid.update(sizeError, dt)

        if (kotlin.math.abs(zoomDelta) > 0.01) {
            zoomController.adjustZoomBy(zoomDelta.toFloat())
        }
    }

    /**
     * timeSeconds is Rotation.Builder's actual move duration - previously
     * unset (defaulting to an instant snap), which is a direct, documented
     * cause of jerky motion: DJI's own docs describe ABSOLUTE_ANGLE with a
     * time value as "move to an angle over a duration," easing the gimbal
     * there instead of slewing at whatever rate it can manage. Passing the
     * real interval between calls (dt) means each new target is eased in
     * over the same span it took to compute it, instead of snapping.
     */
    private fun sendGimbalTarget(pitch: Float, yaw: Float, timeSeconds: Double) {
        val gimbal = DJIConnectionManager.gimbal ?: return
        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(pitch)
            .yaw(yaw)
            .time(timeSeconds)
            .build()

        gimbal.rotate(rotation) { error ->
            if (error != null) {
                Log.w("FaceTrackingController", "rotate() failed: ${error.description}")
            }
        }
    }

    // No release() needed here anymore - the detector lifecycle is owned
    // by VideoFrameProvider, closed when MainActivity tears down.
}
