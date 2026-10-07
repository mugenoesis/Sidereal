package io.github.mugenoesis.sidereal.debug

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import io.github.mugenoesis.sidereal.sequence.Attitude
import io.github.mugenoesis.sidereal.sequence.CalibrationPlanner
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

    /** Set by the receiver on every command - scenarios that need files or a Context use it. */
    @Volatile var appContext: android.content.Context? = null

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
            "drive_shoot" -> {
                val problems = mutableListOf<String>()
                callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
                delay(1500)
                for ((label, expected) in listOf("Single" to 1, "Burst 3" to 3, "AEB 3" to 3)) {
                    val preset = io.github.mugenoesis.sidereal.camera.DrivePresets.all.first { it.label == label }
                    val drive = io.github.mugenoesis.sidereal.camera.DriveController()
                    drive.select(preset)
                    delay(1500)
                    val before = sdFileCount()
                    callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
                    delay(2000)
                    callback<String?> { RealCameraGateway.setShootPhotoMode(preset.modeName) { e -> it(e) } }
                    preset.burstCountName?.let { n -> callback<String?> { RealCameraGateway.setPhotoBurstCount(n) { e -> it(e) } } }
                    preset.aebCountName?.let { n -> callback<String?> { RealCameraGateway.setPhotoAebCount(n) { e -> it(e) } } }
                    delay(1000)
                    val err = callback<String?> { RealCameraGateway.startShootPhoto { e -> it(e) } }
                    delay(12_000)
                    val after = sdFileCount()
                    Log.i(TAG, "DRIVE_SHOOT $label: start=$err files $before -> $after (expected +$expected)")
                    if (before == null || after == null || after - before != expected) problems += "$label delta=${if (before != null && after != null) after - before else null} expected $expected"
                }
                callback<String?> { RealCameraGateway.setShootPhotoMode("SINGLE") { e -> it(e) } }
                Log.i(TAG, "RESULT drive_shoot: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
            }
            "video_probe" -> {
                // Read-only: never starts a recording.
                val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager
                suspend fun key(name: String): String = suspendCancellableCoroutine { cont ->
                    km?.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                        override fun onSuccess(value: Any) {
                            if (cont.isActive) cont.resume(when (value) { is Array<*> -> value.joinToString(" | "); else -> value.toString() })
                        }
                        override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume("FAIL ${e.description}") }
                    }) ?: cont.resume("no key manager")
                }
                callback<String?> { RealCameraGateway.setCameraMode("RECORD_VIDEO") { e -> it(e) } }
                delay(2500)
                for (k in listOf(
                    dji.keysdk.CameraKey.RESOLUTION_FRAME_RATE, dji.keysdk.CameraKey.VIDEO_RESOLUTION_FRAME_RATE_RANGE,
                    dji.keysdk.CameraKey.VIDEO_FILE_FORMAT, dji.keysdk.CameraKey.VIDEO_FILE_FORMAT_RANGE,
                    dji.keysdk.CameraKey.VIDEO_STANDARD, dji.keysdk.CameraKey.VIDEO_STANDARD_RANGE,
                    dji.keysdk.CameraKey.VIDEO_FILE_COMPRESSION_STANDARD, dji.keysdk.CameraKey.VIDEO_COMPRESSION_STANDARD_RANGE,
                    dji.keysdk.CameraKey.CAMERA_COLOR, dji.keysdk.CameraKey.CAMERA_COLOR_RANGE,
                    dji.keysdk.CameraKey.PICTURE_STYLE_PRESET, dji.keysdk.CameraKey.VIDEO_CAPTION_ENABLED,
                    dji.keysdk.CameraKey.SHARPNESS, dji.keysdk.CameraKey.IS_DEWARPING_SUPPORTED
                )) Log.i(TAG, "VIDEO $k = ${key(k)}")
                callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
                Log.i(TAG, "RESULT video_probe: DONE")
            }
            "video_settings" -> videoSettings()
            "mux_test" -> muxTest()
            "sync_seed" -> syncSeed()
            "sync_cleanup" -> {
                val context = appContext ?: error("no context")
                val deleted = context.contentResolver.delete(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("synthetic_video%")
                )
                val audioDir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "SiderealAudio")
                val removed = audioDir.listFiles { f -> f.name.startsWith("audio_synthetic") }?.count { it.delete() } ?: 0
                Log.i(TAG, "SYNC cleanup: removed $deleted videos and $removed audio files")
            }
            "verify_export" -> verifyExport(args)
            "tuning_range" -> {
                val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager!!
                suspend fun read(name: String): String = suspendCancellableCoroutine { cont ->
                    km.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                        override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(value.toString()) }
                        override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume("FAIL") }
                    })
                }
                for ((label, key, setter) in listOf(
                    Triple("sharpness", dji.keysdk.CameraKey.SHARPNESS, { v: Int, d: (String?) -> Unit -> RealCameraGateway.setSharpness(v, d) }),
                    Triple("contrast", dji.keysdk.CameraKey.CONTRAST, { v: Int, d: (String?) -> Unit -> RealCameraGateway.setContrast(v, d) }),
                    Triple("saturation", dji.keysdk.CameraKey.SATURATION, { v: Int, d: (String?) -> Unit -> RealCameraGateway.setSaturation(v, d) })
                )) {
                    val original = read(key)
                    val accepted = mutableListOf<Int>()
                    for (v in -8..8) {
                        val err = callback<String?> { done -> setter(v) { e -> done(e) } }
                        delay(250)
                        val back = read(key)
                        if (err == null && back == v.toString()) accepted += v
                    }
                    Log.i(TAG, "TUNING $label accepted=${accepted.min()}..${accepted.max()} (${accepted.size} values) original=$original")
                    original.toIntOrNull()?.let { o -> callback<String?> { done -> setter(o) { e -> done(e) } } }
                }
                Log.i(TAG, "RESULT tuning_range: DONE")
            }
            "media_debug" -> {
                val media = io.github.mugenoesis.sidereal.camera.MediaLibraryController()
                val manager = DJIConnectionManager.camera?.mediaManager
                Log.i(TAG, "MEDIA mediaManager=${manager != null} supported=${DJIConnectionManager.camera?.isMediaDownloadModeSupported}")
                Log.i(TAG, "MEDIA mode before=${DJIConnectionManager.cameraSystemState.value?.mode}")
                media.enterAndLoad()
                for (i in 1..16) {
                    delay(1000)
                    Log.i(TAG, "MEDIA t=$i mode=${DJIConnectionManager.cameraSystemState.value?.mode} load=${media.loadState.value} files=${media.files.value.size} snapshot=${manager?.sdCardFileListSnapshot?.size} state=${manager?.sdCardFileListState}")
                }
                media.exit()
            }
            "seq_modes" -> seqModes()
            "set_standard" -> {
                val target = args["standard"] ?: "PAL"
                Log.i(TAG, "set_standard $target -> ${setStandardAndWait(target)}")
            }
            "count_files" -> Log.i(TAG, "FILE_COUNT ${sdFileCount()}")
            "drive_probe" -> {
                val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager
                suspend fun key(name: String): String = suspendCancellableCoroutine { cont ->
                    km?.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                        override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(value.toString()) }
                        override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume("FAIL ${e.description}") }
                    })
                }
                Log.i(TAG, "DRIVE photo format=${key(dji.keysdk.CameraKey.PHOTO_FILE_FORMAT)} aspect=${key(dji.keysdk.CameraKey.PHOTO_ASPECT_RATIO)} exposureMode=${key(dji.keysdk.CameraKey.EXPOSURE_MODE)}")
                for (preset in io.github.mugenoesis.sidereal.camera.DrivePresets.all) {
                    val mode = callback<String?> { RealCameraGateway.setShootPhotoMode(preset.modeName) { e -> it(e) } }
                    val count = preset.burstCountName?.let { n -> callback<String?> { RealCameraGateway.setPhotoBurstCount(n) { e -> it(e) } } }
                        ?: preset.aebCountName?.let { n -> callback<String?> { RealCameraGateway.setPhotoAebCount(n) { e -> it(e) } } }
                    Log.i(TAG, "DRIVE ${preset.label}: mode=$mode count=$count now=${key(dji.keysdk.CameraKey.SHOOT_PHOTO_MODE)}")
                    delay(500)
                }
                callback<String?> { RealCameraGateway.setShootPhotoMode("SINGLE") { e -> it(e) } }
                Log.i(TAG, "RESULT drive_probe: DONE")
            }
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

    /** Sets and reads back every video setting the camera lists. Never records - only changes settings. */
    private suspend fun videoSettings() {
        val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager!!
        suspend fun read(name: String): Any? = suspendCancellableCoroutine { cont ->
            km.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(value) }
                override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume(null) }
            })
        }
        fun modes(v: Any?) = (v as? Array<*>).orEmpty().filterIsInstance<dji.common.camera.ResolutionAndFrameRate>().map { it.resolution.name to it.frameRate.name }
        fun names(v: Any?) = (v as? Array<*>).orEmpty().map { (it as Enum<*>).name }
        suspend fun current() = read(dji.keysdk.CameraKey.RESOLUTION_FRAME_RATE).let { it as? dji.common.camera.ResolutionAndFrameRate }?.let { it.resolution.name to it.frameRate.name }

        val problems = mutableListOf<String>()
        callback<String?> { RealCameraGateway.setCameraMode("RECORD_VIDEO") { e -> it(e) } }
        delay(3000)
        val originalMode = current()
        val originalStandard = read(dji.keysdk.CameraKey.VIDEO_STANDARD)?.let { (it as Enum<*>).name }
        val originalColor = read(dji.keysdk.CameraKey.CAMERA_COLOR)?.let { (it as Enum<*>).name }
        Log.i(TAG, "VSET start mode=$originalMode standard=$originalStandard color=$originalColor")

        // 1. every listed resolution / frame rate
        val palModes = modes(read(dji.keysdk.CameraKey.VIDEO_RESOLUTION_FRAME_RATE_RANGE))
        for ((res, fps) in palModes) {
            val err = callback<String?> { RealCameraGateway.setVideoResolutionAndFrameRate(res, fps) { e -> it(e) } }
            delay(1200)
            val now = current()
            Log.i(TAG, "VSET mode $res $fps -> err=$err readback=$now")
            if (err != null || now != (res to fps)) problems += "mode $res/$fps err=$err readback=$now"
        }
        originalMode?.let { (r, f) -> callback<String?> { RealCameraGateway.setVideoResolutionAndFrameRate(r, f) { e -> it(e) } } }

        // 2. PAL -> NTSC changes the frame-rate list (the camera needs a while to settle after the switch)
        val other = names(read(dji.keysdk.CameraKey.VIDEO_STANDARD_RANGE)).firstOrNull { it != originalStandard }
        if (other != null) {
            val result = setStandardAndWait(other)
            val otherModes = modes(read(dji.keysdk.CameraKey.VIDEO_RESOLUTION_FRAME_RATE_RANGE))
            Log.i(TAG, "VSET standard -> $other: $result; modes=${otherModes.size} fps=${otherModes.map { it.second }.distinct()}")
            if (!result.startsWith("ok") || otherModes == palModes || otherModes.isEmpty()) problems += "standard $other: $result list changed=${otherModes != palModes}"
            originalStandard?.let { st ->
                val back = setStandardAndWait(st)
                val restored = modes(read(dji.keysdk.CameraKey.VIDEO_RESOLUTION_FRAME_RATE_RANGE))
                Log.i(TAG, "VSET standard restored $st: $back; list restored=${restored == palModes}")
                if (!back.startsWith("ok") || restored != palModes) problems += "restoring $st: $back"
            }
        }
        originalMode?.let { (r, f) -> callback<String?> { RealCameraGateway.setVideoResolutionAndFrameRate(r, f) { e -> it(e) } } }

        // 3. every colour profile
        for (color in names(read(dji.keysdk.CameraKey.CAMERA_COLOR_RANGE))) {
            val err = callback<String?> { RealCameraGateway.setColor(color) { e -> it(e) } }
            delay(800)
            val now = (read(dji.keysdk.CameraKey.CAMERA_COLOR) as? Enum<*>)?.name
            Log.i(TAG, "VSET color $color -> err=$err readback=$now")
            if (err != null || now != color) problems += "color $color err=$err readback=$now"
        }
        originalColor?.let { c -> callback<String?> { done -> RealCameraGateway.setColor(c) { e -> done(e) } } }

        callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
        Log.i(TAG, "RESULT video_settings: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }

    /** Switches PAL/NTSC and waits (the camera takes several seconds and refuses queries meanwhile) until it reads back. */
    suspend fun setStandardAndWait(target: String, timeoutMs: Long = 40_000): String {
        val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager!!
        val err = callback<String?> { done -> RealCameraGateway.setVideoStandard(target) { e -> done(e) } }
        if (err != null) return "rejected: $err"
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            delay(1500)
            val now = suspendCancellableCoroutine<String?> { cont ->
                km.getValue(dji.keysdk.CameraKey.create(dji.keysdk.CameraKey.VIDEO_STANDARD), object : dji.keysdk.callback.GetCallback {
                    override fun onSuccess(value: Any) { if (cont.isActive) cont.resume((value as Enum<*>).name) }
                    override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume(null) }
                })
            }
            if (now == target) return "ok after ${timeoutMs - (end - System.currentTimeMillis())}ms"
        }
        return "timed out waiting for $target"
    }

    /** Runs a real motion timelapse, darks, bias (with shutter restore) and flats, checking real files and settings. */
    private suspend fun seqModes() {
        val problems = mutableListOf<String>()
        callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
        delay(1500)
        callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }
        callback<String?> { RealCameraGateway.setShutterSpeed("SHUTTER_SPEED_1_2") { e -> it(e) } }
        delay(1000)
        val host = RealSequenceHost(onPrompt = { Log.i(TAG, "SEQ prompt shown: $it"); delay(500) })
        val base = host.currentAttitude() ?: error("no gimbal")
        val shutterBefore = readShutter()

        suspend fun run(label: String, steps: List<SequenceStep>, expectedFiles: Int, check: suspend () -> Unit = {}) {
            val before = sdFileCount()
            callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
            delay(2500)
            val runner = SequenceRunner(host)
            runner.run(steps)
            check()
            delay(2500)
            val after = sdFileCount()
            Log.i(TAG, "SEQ $label: state=${runner.progress.value.state} files $before -> $after (expected +$expectedFiles)")
            if (runner.progress.value.state != SequenceState.Done) problems += "$label state=${runner.progress.value.state}"
            if (before == null || after == null || after - before != expectedFiles) problems += "$label files $before->$after expected +$expectedFiles"
        }

        // motion timelapse: A -> B, 3 frames
        val a = Attitude(base.pitch, base.yaw)
        val b = Attitude(base.pitch + 4f, base.yaw + 6f)
        val seen = mutableListOf<Attitude>()
        val tl = IntervalPlanner.plan(IntervalConfig(3, 7_000, 1_000, 500, path = a to b))
        run("timelapse", tl, 3) { }
        // (attitude at the end of the run should be at B)
        val end = host.currentAttitude()
        Log.i(TAG, "SEQ timelapse ended at $end, B=$b")
        if (end == null || GimbalArrival.errorDeg(end, Attitude(GimbalArrival.quantize(b.pitch), GimbalArrival.quantize(b.yaw))) > 0.5f) problems += "timelapse did not finish at B ($end)"
        host.moveTo(a.pitch, a.yaw)

        run("darks", CalibrationPlanner.darks(2, 500), 2)
        run("flats", CalibrationPlanner.flats(2, 100), 2)
        run("bias", CalibrationPlanner.bias(2, shutterBefore), 2) {
            delay(1500)
            val now = readShutter()
            Log.i(TAG, "SEQ bias shutter restored: before=$shutterBefore now=$now")
            if (now != shutterBefore) problems += "shutter not restored ($shutterBefore -> $now)"
        }
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
        Log.i(TAG, "RESULT seq_modes: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }

    private suspend fun readShutter(): String? = suspendCancellableCoroutine { cont ->
        dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager?.getValue(dji.keysdk.CameraKey.create(dji.keysdk.CameraKey.SHUTTER_SPEED), object : dji.keysdk.callback.GetCallback {
            override fun onSuccess(value: Any) { if (cont.isActive) cont.resume((value as Enum<*>).name) }
            override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume(null) }
        }) ?: cont.resume(null)
    }

    /** Merges synthetic phone audio into synthetic video at several offsets and decodes the result to find where the tone landed. */
    private suspend fun muxTest() {
        val context = appContext ?: error("no context")
        val dir = java.io.File(context.cacheDir, "muxtest").apply { deleteRecursively(); mkdirs() }
        val video = java.io.File(dir, "video.mp4")
        val audio = java.io.File(dir, "audio.m4a")
        SyntheticMedia.makeVideo(video)
        SyntheticMedia.makeAudio(audio)
        val problems = mutableListOf<String>()
        val base = SyntheticMedia.toneOnsetMs(audio)
        Log.i(TAG, "MUX source tone onset=$base ms (built at ${SyntheticMedia.TONE_START_MS})")
        if (base == null || kotlin.math.abs(base - SyntheticMedia.TONE_START_MS) > 80) problems += "source onset $base"

        for (offset in listOf(0L, 300L, 1_000L, -200L, -400L, -700L)) {
            val out = java.io.File(dir, "out_$offset.mp4")
            val result = io.github.mugenoesis.sidereal.sync.AudioMuxer.mux(video.absolutePath, audio.absolutePath, offset, out.absolutePath)
            val onset = SyntheticMedia.toneOnsetMs(out)
            val expected = (SyntheticMedia.TONE_START_MS + offset).coerceAtLeast(0)
            val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(out.absolutePath) }
            val hasVideo = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val hasAudio = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val frame = retriever.getFrameAtTime(500_000)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            Log.i(TAG, "MUX offset=$offset -> onset=$onset expected=$expected video=$hasVideo audio=$hasAudio frame=${frame != null} duration=$duration stats=$result")
            if (onset == null || kotlin.math.abs(onset - expected) > 80) problems += "offset $offset onset=$onset expected=$expected"
            if (hasVideo != "yes" || hasAudio != "yes" || frame == null) problems += "offset $offset not a playable video+audio file"
            if (result.videoSamples < 55) problems += "offset $offset video samples=${result.videoSamples}"
        }
        dir.deleteRecursively()
        Log.i(TAG, "RESULT mux_test: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }

    /** Seeds one phone audio take (with sidecar) and one video in Movies/Sidereal, then opens the sync screen on that video. */
    private suspend fun syncSeed() {
        val context = appContext ?: error("no context")
        val audioDir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "SiderealAudio").apply { mkdirs() }
        val video = java.io.File(context.cacheDir, "seed_video.mp4")
        SyntheticMedia.makeVideo(video)
        SyntheticMedia.makeAudio(java.io.File(audioDir, "audio_synthetic.m4a"))
        val now = System.currentTimeMillis()
        io.github.mugenoesis.sidereal.sync.SyncSidecarStore.save(
            audioDir,
            io.github.mugenoesis.sidereal.sync.SyncSidecar("audio_synthetic.m4a", audioStartEpochMs = now, cameraStartEpochMs = now - 350, cameraStopEpochMs = now + 2_000)
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "synthetic_video.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/Sidereal")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> video.inputStream().use { it.copyTo(out) } }
        Log.i(TAG, "SYNC seeded video=$uri audio take with suggested offset +350 ms")
        context.startActivity(
            android.content.Intent(context, io.github.mugenoesis.sidereal.sync.AudioSyncActivity::class.java)
                .setData(uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Log.i(TAG, "RESULT sync_seed: DONE")
    }

    /** Finds the newest "*_synced.mp4" in Movies/Sidereal, copies it out of MediaStore and reports where its tone begins. */
    private suspend fun verifyExport(args: Map<String, String>) {
        val context = appContext ?: error("no context")
        val expected = args["expected"]?.toLong()
        val resolver = context.contentResolver
        val cursor = resolver.query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DISPLAY_NAME),
            "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("%_synced%"),
            "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC"
        )
        val found = cursor?.use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
        if (found == null) { Log.i(TAG, "RESULT verify_export: FAIL no exported file"); return }
        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, found.first)
        val copy = java.io.File(context.cacheDir, "verify_export.mp4")
        resolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        val onset = SyntheticMedia.toneOnsetMs(copy)
        val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(copy.absolutePath) }
        val playable = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" && retriever.getFrameAtTime(500_000) != null
        retriever.release()
        // The synthetic source's own onset reads 546 for a tone built at 500 (encoder priming) - allow for that constant.
        val pass = playable && onset != null && (expected == null || kotlin.math.abs(onset - (expected + 46)) <= 80)
        Log.i(TAG, "VERIFY ${found.second}: tone onset=$onset ms expected~=${expected?.plus(46)} playable=$playable size=${copy.length()}")
        Log.i(TAG, "RESULT verify_export: ${if (pass) "PASS" else "FAIL"}")
        copy.delete()
    }
}
