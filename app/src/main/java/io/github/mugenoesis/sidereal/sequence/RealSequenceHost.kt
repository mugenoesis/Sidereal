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
    private val moveDurationSec: Double = 1.0,
    private val rampIo: RampIo? = null
) : SequenceHost, AutoCloseable {

    private companion object {
        const val TAG = "RealSequenceHost"
        const val POLL_MS = 50L
        const val MOVE_ATTEMPTS = 2

        /** The enum starts at f/1 though the lens stops at f/1.7: the camera refuses the impossible ones, so walk down the list. */
        const val APERTURE_ATTEMPTS = 12
    }

    private var freeModeRequested = false

    private var ramp: ExposureRamp? = null
    private var ladder: ExposureLadder? = null
    private var rampShutterMs: Long? = null
    private var metering = false

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun isCameraReachable(): Boolean = DJIConnectionManager.isReadyToShoot()

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
        // One retry: the first move after switching to FREE mode has been seen stalling a fraction of a
        // degree short and sitting out the whole timeout; re-sending the same target finishes it.
        repeat(MOVE_ATTEMPTS) { attempt ->
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
            while (nowMs() - start < moveTimeoutMs / MOVE_ATTEMPTS) {
                delay(100)
                val current = currentAttitude() ?: continue
                if (GimbalArrival.hasArrived(current, target, arrivalToleranceDeg)) {
                    Log.i(TAG, "moveTo(${target.pitch}, ${target.yaw}) arrived in ${nowMs() - start}ms at $current (attempt ${attempt + 1})")
                    return
                }
            }
        }
        Log.w(TAG, "moveTo(${target.pitch}, ${target.yaw}) did not arrive; now at ${currentAttitude()}")
    }

    override suspend fun beginRamp(config: RampConfig) {
        val io = rampIo ?: run { Log.w(TAG, "beginRamp: no exposure access, shooting at fixed exposure"); return }
        val modeError = suspendCancellableCoroutine<String?> { cont ->
            gateway.setExposureMode("MANUAL") { if (cont.isActive) cont.resume(it) }
        }
        if (modeError != null) Log.w(TAG, "beginRamp: setExposureMode(MANUAL) failed: $modeError")
        io.setMetering(true)
        metering = true
        // The shutter range is only readable in Manual, so if the app started with the camera in Program there is
        // none yet: ask again now that it is in Manual, and wait for it.
        val rangesDeadline = nowMs() + 4_000
        io.refreshRanges()
        while (io.shutterOptions().isEmpty() && nowMs() < rangesDeadline) {
            delay(500)
            if (io.shutterOptions().isEmpty()) io.refreshRanges()
        }
        // A ramp that ends in the dark can only get brighter if the lens starts wide open. The camera leaves whatever
        // Program mode last chose (f/8 in bright light was seen), which would cost up to four and a half stops at night.
        val gainedStops = openApertureWide(io)
        var names: Pair<String, String>? = null
        val deadline = nowMs() + 5_000
        while (names == null && nowMs() < deadline) {
            delay(250)
            names = io.currentNames()
        }
        if (names == null) { Log.w(TAG, "beginRamp: camera exposure readout unavailable, shooting at fixed exposure"); return }
        val built = try {
            ExposureLadder(io.shutterOptions(), io.isoOptions(), config.maxShutterSec, config.maxIso)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "beginRamp: ${e.message}, shooting at fixed exposure"); return
        }
        var start = try { built.nearest(names.first, names.second) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "beginRamp: ${e.message}, shooting at fixed exposure"); return
        }
        if (gainedStops > 0.2) {
            // The wider lens lets in more light: shorten the exposure by the same amount so the first frame matches the scene.
            val compensated = built.settingFor(start.stops - gainedStops)
            Log.i(TAG, "beginRamp: aperture opened by ${"%.1f".format(gainedStops)} stops, ${start.shutterName} ${start.isoName} -> ${compensated.shutterName} ${compensated.isoName}")
            if (compensated.isoName != start.isoName) {
                val e = suspendCancellableCoroutine<String?> { cont -> gateway.setIso(compensated.isoName) { if (cont.isActive) cont.resume(it) } }
                if (e != null) Log.w(TAG, "beginRamp: setIso(${compensated.isoName}) failed: $e")
            }
            if (compensated.shutterName != start.shutterName) {
                val e = suspendCancellableCoroutine<String?> { cont -> gateway.setShutterSpeed(compensated.shutterName) { if (cont.isActive) cont.resume(it) } }
                if (e != null) Log.w(TAG, "beginRamp: setShutterSpeed(${compensated.shutterName}) failed: $e")
            }
            delay(400)
            start = compensated
        }
        ladder = built
        ramp = ExposureRamp(built, config, start)
        rampShutterMs = Math.round(start.shutterSec * 1000)
        Log.i(TAG, "beginRamp: baseline ${start.shutterName} ${start.isoName}, ${built.minStops}..${built.maxStops} stops")
    }

    /** Opens the lens to its widest aperture; returns the light gained in stops (0 if it was already wide or could not be set). */
    private suspend fun openApertureWide(io: RampIo): Double {
        val before = io.currentAperture() ?: return 0.0
        for (candidate in ApertureMath.widestFirst(io.apertureNames()).take(APERTURE_ATTEMPTS)) {
            val gained = ApertureMath.stopsGained(before, candidate) ?: continue
            if (gained <= 0.05) return 0.0 // already at least this wide
            val error = suspendCancellableCoroutine<String?> { cont -> io.setAperture(candidate) { if (cont.isActive) cont.resume(it) } }
            if (error == null) {
                delay(700)
                Log.i(TAG, "beginRamp: aperture $before -> $candidate")
                return gained
            }
            Log.w(TAG, "beginRamp: aperture $candidate rejected: $error")
        }
        return 0.0
    }

    override suspend fun adaptExposure(): String? {
        val r = ramp ?: return null
        val io = rampIo ?: return null
        val l = ladder ?: return null
        // A few readings a moment apart: the histogram arrives continuously, one frame can catch a flicker.
        val readings = ArrayList<Double>()
        repeat(3) { io.meanLuma()?.let(readings::add); delay(150) }
        val luma = if (readings.isEmpty()) null else readings.average()
        val before = r.current
        val decision = r.next(luma)
        Log.i(TAG, "adapt: luma=${luma?.let { "%.1f".format(it) }} scene=${decision.sceneStops?.let { "%.2f".format(it) }} target=${"%.2f".format(decision.targetStops)} -> ${decision.setting.shutterName} ${decision.setting.isoName}")
        if (decision.setting != before) {
            if (decision.setting.isoName != before.isoName) {
                val e = suspendCancellableCoroutine<String?> { cont -> gateway.setIso(decision.setting.isoName) { if (cont.isActive) cont.resume(it) } }
                if (e != null) Log.w(TAG, "adapt: setIso(${decision.setting.isoName}) failed: $e")
            }
            if (decision.setting.shutterName != before.shutterName) {
                val e = suspendCancellableCoroutine<String?> { cont -> gateway.setShutterSpeed(decision.setting.shutterName) { if (cont.isActive) cont.resume(it) } }
                if (e != null) Log.w(TAG, "adapt: setShutterSpeed(${decision.setting.shutterName}) failed: $e")
            }
            delay(400)
            // If the camera did not take it, carry on from what it really has rather than from what was asked for.
            io.currentNames()?.let { (shutter, iso) ->
                if (shutter != decision.setting.shutterName || iso != decision.setting.isoName) {
                    try { r.resync(l.nearest(shutter, iso)); Log.w(TAG, "adapt: camera is on $shutter $iso, not what was asked") } catch (_: IllegalArgumentException) {}
                }
            }
        }
        rampShutterMs = Math.round(r.current.shutterSec * 1000)
        return decision.summary
    }

    override fun close() {
        if (metering) rampIo?.setMetering(false)
        metering = false
    }

    override suspend fun capture(exposureMs: Long, label: String): Boolean {
        val exposureMs = rampShutterMs ?: exposureMs
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
                PhotoStatus.Done -> { Log.i(TAG, "capture($label) finished in ${nowMs() - sent}ms"); return true }
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
