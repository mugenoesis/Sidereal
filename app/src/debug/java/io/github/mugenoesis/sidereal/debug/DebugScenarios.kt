package io.github.mugenoesis.sidereal.debug

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import io.github.mugenoesis.sidereal.sequence.Attitude
import io.github.mugenoesis.sidereal.sequence.DitherConfig
import io.github.mugenoesis.sidereal.sequence.GimbalArrival
import io.github.mugenoesis.sidereal.sequence.IntervalConfig
import io.github.mugenoesis.sidereal.sequence.IntervalPlanner
import io.github.mugenoesis.sidereal.sequence.RealSequenceHost
import io.github.mugenoesis.sidereal.sequence.SequenceRunner
import io.github.mugenoesis.sidereal.sequence.SequenceState
import io.github.mugenoesis.sidereal.sequence.SequenceStep
import io.github.mugenoesis.sidereal.camera.ShutterLogic
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** On-device hardware scenarios. NEVER starts a video recording - the camera can only be stopped from its physical button. */
object DebugScenarios {
    const val TAG = "SiderealDebug"

    suspend fun run(cmd: String, args: Map<String, String>) {
        awaitConnected()
        when (cmd) {
            "photo_probe" -> photoProbe()
            "seq_interval" -> seqInterval(args)
            "focus_ring" -> {
                val v = args["value"]?.toInt() ?: 0
                Log.i(TAG, "focus_ring $v -> ${callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { _ -> }; RealCameraGateway.setFocusRingValue(v) { e -> it(e) } }}")
            }
            "gimbal_to" -> {
                val host = io.github.mugenoesis.sidereal.sequence.RealSequenceHost()
                host.moveTo(args["pitch"]?.toFloat() ?: 0f, args["yaw"]?.toFloat() ?: 0f)
                Log.i(TAG, "gimbal_to done, now ${attitudeText()}")
            }
            "exposure" -> {
                callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }
                Log.i(TAG, "iso ${callback<String?> { RealCameraGateway.setIso(args["iso"] ?: "ISO_100") { e -> it(e) } }}")
                Log.i(TAG, "shutter ${callback<String?> { RealCameraGateway.setShutterSpeed(args["shutter"] ?: "SHUTTER_SPEED_1_250") { e -> it(e) } }}")
            }
            "focus_sweep" -> focusSweep(args)
            "probe_camera" -> probeCamera()
            "reset_camera" -> {
                callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
                callback<String?> { RealCameraGateway.setFocusMode("AUTO") { e -> it(e) } }
                Log.i(TAG, "reset_camera done (PROGRAM, AF)")
            }
            "attitude" -> Log.i(TAG, "attitude ${attitudeText()}")
            else -> Log.w(TAG, "unknown command $cmd")
        }
    }

    /** Real number of files on the SD card via the media browser - exact, unlike StorageState's estimated capture count. */
    suspend fun sdFileCount(): Int? {
        val media = io.github.mugenoesis.sidereal.camera.MediaLibraryController()
        media.enterAndLoad()
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            val state = media.loadState.value
            if (state == io.github.mugenoesis.sidereal.camera.MediaLoadState.LOADED ||
                state == io.github.mugenoesis.sidereal.camera.MediaLoadState.ERROR
            ) break
            delay(200)
        }
        val count = if (media.loadState.value == io.github.mugenoesis.sidereal.camera.MediaLoadState.LOADED) media.files.value.size else null
        media.exit()
        delay(2500)
        return count
    }

    fun attitudeText(): String {
        val a = DJIConnectionManager.gimbalState.value?.attitudeInDegrees
        return "pitch=${a?.pitch} yaw=${a?.yaw}"
    }

    suspend fun awaitConnected(timeoutMs: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected &&
                DJIConnectionManager.camera != null && DJIConnectionManager.cameraSystemState.value != null
            ) return
            delay(250)
        }
        error("Osmo not connected after ${timeoutMs}ms")
    }

    suspend fun <T> callback(block: ((T) -> Unit) -> Unit): T = suspendCancellableCoroutine { cont ->
        block { if (cont.isActive) cont.resume(it) }
    }

    private suspend fun photoProbe() {
        Log.i(TAG, "setMode ${callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }}")
        delay(1500)
        Log.i(TAG, "setExposureMode M ${callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }}")
        for (shutter in listOf("SHUTTER_SPEED_1_100", "SHUTTER_SPEED_3")) {
            Log.i(TAG, "setShutter $shutter -> ${callback<String?> { RealCameraGateway.setShutterSpeed(shutter) { e -> it(e) } }}")
            delay(1000)
            val t0 = System.currentTimeMillis()
            val err = callback<String?> { RealCameraGateway.startShootPhoto { e -> it(e) } }
            Log.i(TAG, "[$shutter] startShootPhoto callback after ${System.currentTimeMillis() - t0}ms error=$err")
            var last = ""
            while (System.currentTimeMillis() - t0 < 12_000) {
                val s = DJIConnectionManager.cameraSystemState.value
                val now = "shooting=${s?.isShootingSinglePhoto} storing=${s?.isStoringPhoto}"
                if (now != last) { Log.i(TAG, "[$shutter] t+${System.currentTimeMillis() - t0}ms $now"); last = now }
                delay(20)
            }
        }
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
        Log.i(TAG, "RESULT photo_probe: DONE")
    }

    /** Real 3-frame dithered interval: frames land on the card, gimbal visits distinct dithered poses, timing holds. */
    private suspend fun seqInterval(args: Map<String, String>) {
        val frames = args["frames"]?.toInt() ?: 3
        val dither = DitherConfig(minDeg = 0.3f, maxDeg = 0.8f, seed = 11L)
        val filesBefore = sdFileCount()
        Log.i(TAG, "SD files before: $filesBefore")
        callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
        delay(1500)
        callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }
        callback<String?> { RealCameraGateway.setShutterSpeed("SHUTTER_SPEED_1_2") { e -> it(e) } }
        delay(1000)
        val host = RealSequenceHost()
        val base = host.currentAttitude() ?: error("no gimbal attitude")
        val plan = IntervalPlanner.plan(IntervalConfig(frames, intervalMs = 9_000, settleMs = 1_000, exposureMs = 500, hold = base, dither = dither))
        val moves = plan.filterIsInstance<SequenceStep.MoveTo>()
        val seenAttitudes = mutableListOf<Attitude>()
        val captureStarts = mutableListOf<Long>()
        val runner = SequenceRunner(host)
        var lastDone = 0
        runner.onProgress = { p ->
            if (p.capturesDone != lastDone) {
                lastDone = p.capturesDone
                host.currentAttitude()?.let { seenAttitudes += it }
                captureStarts += System.currentTimeMillis()
                Log.i(TAG, "frame ${p.capturesDone}/${p.capturesTotal} done at ${host.currentAttitude()}")
            }
        }
        val t0 = System.currentTimeMillis()
        runner.run(plan)
        delay(3000)
        host.moveTo(base.pitch, base.yaw)
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
        val filesAfter = sdFileCount()
        Log.i(TAG, "SD files after: $filesAfter")
        val problems = mutableListOf<String>()
        if (runner.progress.value.state != SequenceState.Done) problems += "state=${runner.progress.value.state}"
        if (runner.progress.value.capturesDone != frames) problems += "captures=${runner.progress.value.capturesDone}"
        if (filesBefore == null || filesAfter == null) problems += "could not count SD files ($filesBefore -> $filesAfter)"
        else if (filesAfter - filesBefore != frames) problems += "SD file delta=${filesAfter - filesBefore} expected $frames"
        moves.forEachIndexed { i, m ->
            val seen = seenAttitudes.getOrNull(i) ?: return@forEachIndexed
            val err = GimbalArrival.errorDeg(seen, Attitude(GimbalArrival.quantize(m.pitch), GimbalArrival.quantize(m.yaw)))
            Log.i(TAG, "frame ${i + 1}: target=(${m.pitch},${m.yaw}) seen=$seen err=$err")
            if (err > 0.5f) problems += "frame ${i + 1} pointing error $err"
        }
        val gaps = captureStarts.zipWithNext { a, b -> b - a }
        Log.i(TAG, "frame gaps ms=$gaps total=${System.currentTimeMillis() - t0}ms")
        gaps.forEach { if (kotlin.math.abs(it - 9_000) > 1_500) problems += "gap $it not ~9000" }
        Log.i(TAG, "RESULT seq_interval: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }

    /** Steps the manual focus ring across its range, pausing at each so FocusAssist's log lines can be matched to ring values. */
    private suspend fun focusSweep(args: Map<String, String>) {
        val camera = DJIConnectionManager.camera ?: error("no camera")
        callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }
        val upper = callback<Int> { cb -> camera.getFocusRingValueUpperBound(object : dji.common.util.CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(v: Int) = cb(v)
            override fun onFailure(e: dji.common.error.DJIError) = cb(-1)
        }) }
        val steps = args["steps"]?.toInt() ?: 16
        val dwellMs = args["dwell"]?.toLong() ?: 2500L
        Log.i(TAG, "focus ring upper bound=$upper")
        for (i in 0..steps) {
            val v = (upper.toLong() * i / steps).toInt()
            callback<String?> { RealCameraGateway.setFocusRingValue(v) { e -> it(e) } }
            Log.i(TAG, "RING $v")
            delay(dwellMs)
        }
        Log.i(TAG, "RESULT focus_sweep: DONE")
    }

    /** Logs what this camera/product actually supports, so features are built against facts rather than the SDK docs. */
    private suspend fun probeCamera() {
        val camera = DJIConnectionManager.camera ?: error("no camera")
        val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager
        suspend fun key(name: String) {
            val v = suspendCancellableCoroutine<String> { cont ->
                km?.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                    override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(if (value is Array<*>) value.joinToString() else value.toString()) }
                    override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume("FAIL ${e.description}") }
                }) ?: cont.resume("no key manager")
            }
            Log.i(TAG, "PROBE $name = $v")
        }
        key(dji.keysdk.CameraKey.SHOOT_PHOTO_MODE)
        key(dji.keysdk.CameraKey.SHOOT_PHOTO_MODE_RANGE)
        key(dji.keysdk.CameraKey.AE_LOCK)
        key(dji.keysdk.CameraKey.PHOTO_BURST_COUNT)
        key(dji.keysdk.CameraKey.PHOTO_AEB_COUNT)
        key(dji.keysdk.CameraKey.PHOTO_TIME_INTERVAL_SETTINGS)
        val st = DJIConnectionManager.storageState.value
        Log.i(TAG, "PROBE storage: inserted=${st?.isInserted} total=${st?.totalSpaceInMB}MB remaining=${st?.remainingSpaceInMB}MB photos=${st?.availableCaptureCount} recSec=${st?.availableRecordingTimeInSeconds}")
        val product = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
        val battery = product?.battery
        Log.i(TAG, "PROBE product=${product?.model} battery=${battery != null} connected=${battery?.isConnected}")
        val pct = suspendCancellableCoroutine<String> { cont ->
            battery?.setStateCallback { s -> if (cont.isActive) cont.resume("charge=${s.chargeRemainingInPercent}% voltage=${s.voltage} temp=${s.temperature}") }
                ?: cont.resume("no battery component")
            kotlinx.coroutines.GlobalScope.launch { delay(4000); if (cont.isActive) cont.resume("battery callback timed out") }
        }
        Log.i(TAG, "PROBE battery state: $pct")
        Log.i(TAG, "RESULT probe_camera: DONE")
    }
}
