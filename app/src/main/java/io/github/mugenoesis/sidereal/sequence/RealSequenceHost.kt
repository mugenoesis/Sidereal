package io.github.mugenoesis.sidereal.sequence

import android.os.SystemClock
import android.util.Log
import dji.common.gimbal.GimbalMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Production [SequenceHost] over the real gimbal and camera.
 *
 * - moveTo() commands an ABSOLUTE_ANGLE rotate (the only mode that doesn't
 *   drift - see NOTES) and then waits for the PUSHED attitude to arrive,
 *   because rotate()'s own callback is a fixed ~2s timeout, not an ack.
 *   A move that never arrives is logged and carried on from, not fatal: the
 *   sequence is more useful with one slightly-off frame than abandoned.
 * - capture() waits for the shot to be fully finished via
 *   [PhotoCompletionTracker], not just for the command to be accepted.
 *
 * @param onPrompt shows [SequenceStep.Prompt] text and suspends until the user continues
 */
class RealSequenceHost(
    private val onPrompt: suspend (String) -> Unit = {},
    private val gateway: CameraGateway = RealCameraGateway,
    private val arrivalToleranceDeg: Float = 0.15f,
    private val moveTimeoutMs: Long = 8_000,
    private val moveDurationSec: Double = 1.0
) : SequenceHost {

    private companion object {
        const val TAG = "RealSequenceHost"
        const val POLL_MS = 50L
    }

    private var freeModeRequested = false

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override suspend fun sleep(ms: Long) = delay(ms)

    private suspend fun ensureFreeMode() {
        if (freeModeRequested) return
        freeModeRequested = true
        DJIConnectionManager.gimbal?.setMode(GimbalMode.FREE) { error ->
            if (error != null) Log.w(TAG, "setMode(FREE) failed: ${error.description}")
        }
        delay(500)
    }

    fun currentAttitude(): Attitude? =
        DJIConnectionManager.gimbalState.value?.attitudeInDegrees?.let { Attitude(it.pitch, it.yaw) }

    override suspend fun moveTo(pitch: Float, yaw: Float) {
        val gimbal = DJIConnectionManager.gimbal ?: run { Log.w(TAG, "moveTo: no gimbal"); return }
        ensureFreeMode()
        val pitchRange = DJIConnectionManager.pitchRangeDegrees()
        val yawRange = DJIConnectionManager.yawRangeDegrees()
        val target = Attitude(
            GimbalArrival.quantize(pitchRange?.let { pitch.coerceIn(it.start, it.endInclusive) } ?: pitch),
            GimbalArrival.quantize(yawRange?.let { yaw.coerceIn(it.start, it.endInclusive) } ?: yaw)
        )
        val rotation = Rotation.Builder()
            .mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(target.pitch)
            .yaw(target.yaw)
            .time(moveDurationSec)
            .build()
        gimbal.rotate(rotation) { error ->
            if (error != null) Log.w(TAG, "rotate failed: ${error.description}")
        }
        val start = nowMs()
        while (nowMs() - start < moveTimeoutMs) {
            delay(100)
            val current = currentAttitude() ?: continue
            if (GimbalArrival.hasArrived(current, target, arrivalToleranceDeg)) return
        }
        Log.w(TAG, "moveTo(${target.pitch}, ${target.yaw}) did not arrive within ${moveTimeoutMs}ms; now at ${currentAttitude()}")
    }

    override suspend fun capture(exposureMs: Long, label: String): Boolean {
        val sent = nowMs()
        val error = suspendCancellableCoroutine<String?> { cont ->
            gateway.startShootPhoto { if (cont.isActive) cont.resume(it) }
        }
        if (error != null) {
            Log.w(TAG, "startShootPhoto failed ($label): $error")
            return false
        }
        val tracker = PhotoCompletionTracker(exposureMs)
        while (true) {
            val state = DJIConnectionManager.cameraSystemState.value
            when (val status = tracker.onSample(nowMs() - sent, state?.isShootingSinglePhoto == true, state?.isStoringPhoto == true)) {
                PhotoStatus.Done -> return true
                is PhotoStatus.TimedOut -> { Log.w(TAG, "capture($label): ${status.reason}"); return false }
                else -> delay(POLL_MS)
            }
        }
    }

    override suspend fun setShutter(shutterName: String): Boolean {
        val error = suspendCancellableCoroutine<String?> { cont ->
            gateway.setShutterSpeed(shutterName) { if (cont.isActive) cont.resume(it) }
        }
        if (error != null) Log.w(TAG, "setShutterSpeed($shutterName) failed: $error")
        return error == null
    }

    override suspend fun awaitUserContinue(message: String) = onPrompt(message)
}
