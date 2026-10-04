package io.github.mugenoesis.sidereal.gimbal

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.sdk.gimbal.Gimbal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Point A -> Point B move over a user-chosen duration, with ease-in-out
 * so reveal/pan shots don't look robotic. Supports pause/resume.
 */
class TimedMoveController {

    companion object {
        private const val TAG = "TimedMoveController"
        // Time given the gimbal to physically reach a one-shot ABSOLUTE_ANGLE
        // target - matches recenter()'s own 0.5s precedent in
        // ManualGimbalController, generous rather than precisely measured.
        private const val PREVIEW_MOVE_TIME_SECONDS = 0.8
        // Held after commanding the go-to-A move in start() and before the
        // timed A->B loop begins, so the physical gimbal has time to
        // actually arrive - see start()'s doc comment.
        private const val GO_TO_START_SETTLE_MS = 900L
    }

    data class Point(val pitch: Double, val yaw: Double, val roll: Double)

    sealed class State {
        object Idle : State()
        data class Ready(val pointA: Point, val pointB: Point) : State()
        /** Snapping to point A before the timed A->B animation begins - see start()'s doc comment. */
        object MovingToStart : State()
        data class Running(val progress: Float) : State()
        object Paused : State()
        object Completed : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var pointA: Point? = null
    private var pointB: Point? = null
    private var job: Job? = null
    private var pauseRequested = false
    private var elapsedAtPause = 0L

    /** Call when the user taps "Set Point A" - captures current attitude. */
    fun captureA() {
        pointA = currentAttitudeAsPoint()
        maybeMarkReady()
    }

    fun captureB() {
        pointB = currentAttitudeAsPoint()
        maybeMarkReady()
    }

    fun clear() {
        pointA = null
        pointB = null
        _state.value = State.Idle
    }

    /**
     * One-shot recall move to a captured point while in TIMED_MOVE mode -
     * lets the user visually confirm point A/B without running the full
     * A->B animation. A real user request: tapping A/B should recall that
     * point in TIMED_MOVE mode, not re-capture it (re-capturing only makes
     * sense in MANUAL mode - the joystick is disarmed in TIMED_MOVE, so
     * there'd be nothing to reposition to anyway). No-op if that point
     * hasn't been captured yet. See GimbalModeController.previewPointA/B
     * for the mode/gimbal-connected guards around this.
     */
    fun previewA(gimbal: Gimbal) { pointA?.let { rotateTo(gimbal, it) } }
    fun previewB(gimbal: Gimbal) { pointB?.let { rotateTo(gimbal, it) } }

    private fun rotateTo(gimbal: Gimbal, point: Point) {
        // roll deliberately omitted - real hardware testing (see start()'s
        // rotate() call below) found sending roll() gets every frame
        // rejected on this gimbal, even for a trivial move.
        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(point.pitch.toFloat())
            .yaw(point.yaw.toFloat())
            .time(PREVIEW_MOVE_TIME_SECONDS)
            .build()
        gimbal.rotate(rotation) { error ->
            if (error != null) Log.w(TAG, "rotateTo failed: ${error.description}")
        }
    }

    private fun currentAttitudeAsPoint(): Point? {
        val attitude = DJIConnectionManager.gimbalState.value?.attitudeInDegrees ?: return null
        return Point(attitude.pitch.toDouble(), attitude.yaw.toDouble(), attitude.roll.toDouble())
    }

    private fun maybeMarkReady() {
        val a = pointA
        val b = pointB
        if (a != null && b != null) {
            _state.value = State.Ready(a, b)
        }
    }

    fun start(gimbal: Gimbal, durationMillis: Long, scope: CoroutineScope) {
        val a = pointA ?: return
        val b = pointB ?: return

        job?.cancel()
        pauseRequested = false
        elapsedAtPause = 0L

        // Gimbal.rotate() requires GimbalMode.FREE - it connects in
        // YAW_FOLLOW, which rejects direct attitude commands outright
        // ("Param Illegal" on every single frame, confirmed on real
        // hardware). ManualGimbalController already has to do this
        // (ensureFreeMode()); this controller never did, so a fresh
        // connection's first A->B move failed silently end-to-end. Fired
        // once per start(), not retried - see ManualGimbalController's doc
        // comment on why retrying a FREE-mode request that firmware is
        // genuinely rejecting made tracking jitter worse, not better.
        gimbal.setMode(dji.common.gimbal.GimbalMode.FREE) { error ->
            if (error != null) {
                Log.w(TAG, "setMode(FREE) failed: ${error.description}")
            }
        }

        job = scope.launch {
            // Snap to point A first, then hold briefly for the physical
            // gimbal to actually arrive, before starting the timed A->B
            // loop below - a real user request. Without this, if the
            // gimbal wasn't already sitting at A when Start was pressed
            // (a fresh session, or bumped since capturing), the eased
            // curve's slow-starting early frames would end up doing this
            // same catch-up move anyway, just silently eating into the
            // user's chosen duration instead of being a clean, separate
            // step before it starts.
            rotateTo(gimbal, a)
            _state.value = State.MovingToStart
            delay(GO_TO_START_SETTLE_MS)

            val startTime = System.currentTimeMillis()
            val frameIntervalMs = 33L // ~30Hz command rate

            while (isActive) {
                if (pauseRequested) {
                    _state.value = State.Paused
                    delay(frameIntervalMs)
                    continue
                }

                val elapsed = (System.currentTimeMillis() - startTime)
                val t = TimedMoveMath.progress(elapsed, durationMillis)
                val (pitch, yaw) = TimedMoveMath.pointAt(a, b, t)

                // Real hardware testing: sending an explicit .roll() here
                // made every single frame get rejected ("Param Illegal"),
                // even for a trivial move where A and B were captured only
                // seconds apart. ManualGimbalController's working rotate()
                // calls never set roll at all - this gimbal's roll axis
                // likely doesn't accept arbitrary ABSOLUTE_ANGLE values the
                // way pitch/yaw do (recenter() only ever sends roll(0f),
                // never a live-captured value). Dropping it here to match
                // the pattern that's actually confirmed working.
                //
                // A real user-reported bug: this loop left .time() unset,
                // which ManualGimbalController.sendAbsolute() already
                // documents the fix for - unset .time() makes each
                // ABSOLUTE_ANGLE command an instant snap rather than an
                // eased move, so the smooth per-frame waypoints this loop
                // computes weren't actually being eased between sends. The
                // reported symptom (slow through most of the move, then a
                // fast catch-up snap right at the end) fits exactly: the
                // steep middle of the ease curve advances the target
                // faster than an unset-time snap-per-frame command stream
                // reliably keeps up with, and the visible "catch-up" is
                // the gimbal finally closing that gap once t reaches 1 and
                // the target stops moving. Same fix as sendAbsolute():
                // pass the frame interval as .time() so each new target
                // eases in over exactly the span until the next one.
                val rotation = Rotation.Builder()
                    .mode(RotationMode.ABSOLUTE_ANGLE)
                    .pitch(pitch.toFloat())
                    .yaw(yaw.toFloat())
                    .time(frameIntervalMs / 1000.0)
                    .build()

                gimbal.rotate(rotation) { error ->
                    if (error != null) {
                        Log.w(TAG, "rotate() failed: ${error.description}")
                    }
                }

                _state.value = State.Running(t)

                if (t >= 1f) {
                    _state.value = State.Completed
                    break
                }

                delay(frameIntervalMs)
            }
        }
    }

    fun pause() {
        pauseRequested = true
    }

    fun resume() {
        pauseRequested = false
    }

    fun cancel() {
        job?.cancel()
        job = null
        pauseRequested = false
        // Without this, switching gimbal mode away mid-move (which calls
        // this) left state stuck at Running/Paused indefinitely - nothing
        // else in this class ever moves it back to Idle, so UI observing
        // state would keep showing stale progress after the move was
        // actually stopped.
        _state.value = State.Idle
    }
}
