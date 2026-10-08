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
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand as W
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
            "histogram_probe" -> histogramProbe()
            "luma_vs_shutter" -> lumaVsShutter(args)
            "pad_key" -> kotlin.run {
                // Holds or releases one pad button through the real input path (unlike `input gamepad keyevent`, which is a blink).
                val keyName = args["key"] ?: "A"
                val code = android.view.KeyEvent.keyCodeFromString(if (keyName.startsWith("DPAD")) "KEYCODE_$keyName" else "KEYCODE_BUTTON_$keyName")
                val down = args["state"] != "up"
                val now = android.os.SystemClock.uptimeMillis()
                val event = android.view.KeyEvent(now, now, if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP, code, 0, 0, 0, 0, 0, android.view.InputDevice.SOURCE_GAMEPAD)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Log.i(TAG, "pad_key ${args["key"]} ${if (down) "down" else "up"} handled=${io.github.mugenoesis.sidereal.input.GamepadInput.active?.handleKey(event)}")
                }
            }
            "gamepad_probe" -> {
                val on = args["on"] != "false"
                io.github.mugenoesis.sidereal.input.GamepadInput.probeUntilMs =
                    if (on) android.os.SystemClock.elapsedRealtime() + 10 * 60_000L else 0L
                Log.i(TAG, "gamepad probe only = $on")
                appContext?.let { ctx ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            ctx,
                            if (on) "CONTROLLER TEST MODE: the controller is only being recorded (10 min)" else "Controller test mode off - controller is live",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            "luma_now" -> {
                val camera = DJIConnectionManager.camera
                var latest: ShortArray? = null
                camera?.setHistogramEnabled(true) { }
                camera?.setHistogramCallback { latest = it }
                delay(1500)
                val st = io.github.mugenoesis.sidereal.camera.HistogramModel.stats(latest)
                Log.i(TAG, "LUMA_NOW ${args["tag"] ?: ""} mean=${st?.meanLuma?.let { "%.1f".format(it) }} hi=${st?.highlightsClipped?.let { "%.3f".format(it) }} att=${attitudeText()}")
            }
            "ramp_run" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val c = io.github.mugenoesis.sidereal.sequence.SequenceFeature.latest?.controller ?: return@withContext Unit.also { Log.w(TAG, "no sequence feature") }
                c.setMode(io.github.mugenoesis.sidereal.sequence.SequenceMode.TIMELAPSE)
                fun setTo(field: String, target: Int, read: (io.github.mugenoesis.sidereal.sequence.SequenceSettings) -> Int) {
                    repeat(20) { if (read(c.settings.value) < target) c.adjust(field, +1) else if (read(c.settings.value) > target) c.adjust(field, -1) }
                }
                setTo("durationMin", (args["duration"] ?: "1").toInt()) { it.durationMin }
                setTo("intervalSec", (args["interval"] ?: "5").toInt()) { it.intervalSec }
                setTo("settleMs", 500) { it.settleMs }
                if (!c.settings.value.ramp) c.adjust("ramp", +1)
                setTo("keepDarkPct", (args["keep"] ?: "0").toInt()) { it.keepDarkPct }
                setTo("maxIso", (args["maxiso"] ?: "800").toInt()) { it.maxIso }
                Log.i(TAG, "RAMP settings ${c.settings.value}")
                c.start()
                Log.i(TAG, "RAMP started msg=${c.message.value} running=${c.isRunning.value}")
            }
            "series_run" -> {
                // args: mode=PANORAMA ints=frames:3,intervalSec:5 flips=stitch,saveFrames
                val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    io.github.mugenoesis.sidereal.sequence.SequenceFeature.latest?.controller
                } ?: run { Log.w(TAG, "no sequence feature"); return }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    c.setMode(io.github.mugenoesis.sidereal.sequence.SequenceMode.valueOf(args["mode"] ?: "INTERVALOMETER"))
                    (args["ints"] ?: "").split(',').filter { it.contains(':') }.forEach { kv ->
                        val (field, v) = kv.split(':')
                        val target = v.toInt()
                        fun current(): Int = when (field) {
                            "frames" -> c.settings.value.frames
                            "intervalSec" -> c.settings.value.intervalSec
                            "settleMs" -> c.settings.value.settleMs
                            "durationMin" -> c.settings.value.durationMin
                            "fps" -> c.settings.value.fps
                            "yawSpanDeg" -> c.settings.value.yawSpanDeg
                            "pitchSpanDeg" -> c.settings.value.pitchSpanDeg
                            "overlapPct" -> c.settings.value.overlapPct
                            "shotsPerNode" -> c.settings.value.shotsPerNode
                            "calFrames" -> c.settings.value.calFrames
                            else -> target
                        }
                        repeat(30) { if (current() < target) c.adjust(field, +1) else if (current() > target) c.adjust(field, -1) }
                    }
                    (args["flips"] ?: "").split(',').filter { it.isNotBlank() }.forEach { c.adjust(it, +1) }
                    Log.i(TAG, "SERIES settings ${c.settings.value}")
                    Log.i(TAG, "SERIES plan ${(c.preview() as? io.github.mugenoesis.sidereal.sequence.PlanResult.Ok)?.plan?.summary}")
                    c.start()
                }
                var lastAfter: Any? = null
                val deadline = System.currentTimeMillis() + 25 * 60_000
                delay(1000)
                while (c.isRunning.value && System.currentTimeMillis() < deadline) {
                    val a = c.afterRun.value
                    if (a != lastAfter) { Log.i(TAG, "SERIES after=$a progress=${c.progress.value.capturesDone}/${c.progress.value.capturesTotal}"); lastAfter = a }
                    delay(500)
                }
                Log.i(TAG, "SERIES finished state=${c.progress.value.state} message=${c.message.value}")
            }
            "restitch" -> {
                // args: folder=Panorama_x pitches=-15.3,1.3,17.9 yaws=-63.2,-33.2,-3.2 [fov=60.4,46.2]
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Sidereal/${args["folder"]}")
                val pitches = args["pitches"]!!.split(',').map { it.toFloat() }
                val yaws = args["yaws"]!!.split(',').map { it.toFloat() }
                val fov = (args["fov"] ?: "60.4,46.2").split(',').map { it.toFloat() }
                val nodes = ArrayList<io.github.mugenoesis.sidereal.sequence.Node>()
                pitches.forEachIndexed { r, p -> (if (r % 2 == 0) yaws.indices else yaws.indices.reversed()).forEach { c -> nodes += io.github.mugenoesis.sidereal.sequence.Node(r, c, p, yaws[c]) } }
                val processor = io.github.mugenoesis.sidereal.series.PanoramaProcessor(
                    appContext!!, "${args["folder"]}_restitch", io.github.mugenoesis.sidereal.series.PanoramaLayout(nodes, 1, fov[0], fov[1])
                )
                nodes.forEachIndexed { i, n ->
                    val f = dir.listFiles()!!.first { it.name.contains("_r${n.row + 1}c${n.col + 1}_") }
                    val copy = java.io.File(appContext!!.cacheDir, "re_${f.name}").also { f.copyTo(it, overwrite = true) }
                    processor.onFrame(i, "r${n.row + 1}c${n.col + 1}", copy)
                }
                Log.i(TAG, "RESTITCH " + processor.finish("x", emptyList()))
            }
            "afc_trials" -> {
                // args: starts=0,1000,2000 tag=scene  - time the software AFC from each blurred start, and what the camera's own AF would pick
                val camera = DJIConnectionManager.camera ?: error("no camera")
                suspend fun ring(): Int = callback { cb -> camera.getFocusRingValue(object : dji.common.util.CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(v: Int) = cb(v)
                    override fun onFailure(e: dji.common.error.DJIError) = cb(-1)
                }) }
                val tag = args["tag"] ?: "scene"
                for (start in (args["starts"] ?: "0,1000,2000").split(',').map { it.toInt() }) {
                    callback<String?> { RealCameraGateway.setFocusAssistantEnabled(false, false) { e -> it(e) } }
                    callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }
                    callback<String?> { RealCameraGateway.setFocusRingValue(start) { e -> it(e) } }
                    delay(2500)
                    val ctrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { io.github.mugenoesis.sidereal.camera.SoftwareAfcController.latest } ?: error("no afc controller")
                    val t0 = System.currentTimeMillis()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctrl.start() }
                    var locked = false
                    while (System.currentTimeMillis() - t0 < 45_000) { if (ctrl.isLocked.value) { locked = true; break }; delay(50) }
                    val lockMs = System.currentTimeMillis() - t0
                    val lockedRing = ring()
                    val sharp = ctrl.lastSharpness.value.toInt()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctrl.stop() }
                    Log.i(TAG, "AFTRIAL $tag start=$start locked=$locked lockMs=$lockMs ring=$lockedRing sharp=$sharp")
                    delay(1500)
                }
                // what the camera's own AF does on its own, from the same blurred starts
                for (start in (args["starts"] ?: "0,1000,2000").split(',').map { it.toInt() }) {
                    callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }
                    callback<String?> { RealCameraGateway.setFocusRingValue(start) { e -> it(e) } }
                    delay(2500)
                    callback<String?> { RealCameraGateway.setFocusMode("AUTO") { e -> it(e) } }
                    delay(350)
                    val t0 = System.currentTimeMillis()
                    callback<String?> { RealCameraGateway.setFocusTarget(0.5f, 0.5f) { e -> it(e) } }
                    var last = ring(); var stableSince = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 12_000) {
                        delay(100)
                        val r = ring()
                        if (r != last) { last = r; stableSince = System.currentTimeMillis() }
                        else if (System.currentTimeMillis() - stableSince >= 700 && last != start) break
                    }
                    Log.i(TAG, "AFTRIAL_HW $tag start=$start settledMs=${stableSince - t0} ring=$last")
                    delay(1000)
                }
                Log.i(TAG, "RESULT afc_trials $tag: DONE")
            }
            "af_curve" -> {
                // args: step=50 tag=scene - the scene's real sharpness at every ring position (ground truth for AF tests)
                val ctrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { io.github.mugenoesis.sidereal.camera.SoftwareAfcController.latest } ?: error("no afc controller")
                val step = args["step"]?.toInt() ?: 50
                val tag = args["tag"] ?: "scene"
                callback<String?> { RealCameraGateway.setFocusAssistantEnabled(false, false) { e -> it(e) } }
                callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctrl.beginSharpnessProbe() }
                val out = StringBuilder()
                var ringPos = 0
                while (ringPos <= 2035) {
                    callback<String?> { RealCameraGateway.setFocusRingValue(ringPos) { e -> it(e) } }
                    delay(700)
                    val samples = ArrayList<Double>()
                    repeat(4) { samples += ctrl.lastSharpness.value; delay(160) }
                    out.append("$ringPos:${samples.sorted()[samples.size / 2].toInt()} ")
                    ringPos += step
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctrl.endSharpnessProbe() }
                Log.i(TAG, "AFCURVE $tag $out")
            }
            "aperture_probe" -> {
                val camera = DJIConnectionManager.camera ?: error("no camera")
                Log.i(TAG, "APERTURE adjustableSupported=${camera.isAdjustableApertureSupported}")
                Log.i(TAG, "APERTURE setMode MANUAL -> ${callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }}")
                delay(1500)
                for (name in (args["names"] ?: "F_1_DOT_7,F_4,F_1_DOT_7").split(',')) {
                    Log.i(TAG, "APERTURE set $name -> ${callback<String?> { RealCameraGateway.setAperture(name) { e -> it(e) } }}")
                    delay(1500)
                }
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
            // Real controller events arrive on the main thread and touch views, so the injected ones must too.
            "wear_host_test" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { wearHostTest() }
            "afc_start" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { io.github.mugenoesis.sidereal.camera.SoftwareAfcController.latest?.start(); Log.i(TAG, "AFC started by harness") }
            "afc_stop" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { io.github.mugenoesis.sidereal.camera.SoftwareAfcController.latest?.stop(); Log.i(TAG, "AFC stopped by harness") }
            "af_probe" -> {
                val camera = DJIConnectionManager.camera ?: error("no camera")
                suspend fun ring(): Int = callback { cb -> camera.getFocusRingValue(object : dji.common.util.CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(v: Int) = cb(v)
                    override fun onFailure(e: dji.common.error.DJIError) = cb(-1)
                }) }
                callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }
                callback<String?> { RealCameraGateway.setFocusRingValue(args["start"]?.toInt() ?: 0) { e -> it(e) } }
                delay(2500)
                if (args["assist"] == "off") {
                    Log.i(TAG, "AFPROBE focus assistant off: ${callback<String?> { RealCameraGateway.setFocusAssistantEnabled(false, false) { e -> it(e) } }}")
                }
                Log.i(TAG, "AFPROBE start ring=${ring()}")
                Log.i(TAG, "AFPROBE ->AUTO ${callback<String?> { RealCameraGateway.setFocusMode("AUTO") { e -> it(e) } }}")
                Log.i(TAG, "AFPROBE target ${callback<String?> { RealCameraGateway.setFocusTarget(0.5f, 0.5f) { e -> it(e) } }}")
                for (t in listOf(500L, 1000L, 1500L, 2500L)) { delay(t - (if (t == 500L) 0 else 500)); Log.i(TAG, "AFPROBE in AUTO +${t}ms ring=${ring()}") }
                Log.i(TAG, "AFPROBE ->MANUAL ${callback<String?> { RealCameraGateway.setFocusMode("MANUAL") { e -> it(e) } }}")
                for (t in listOf(200L, 800L)) { delay(t); Log.i(TAG, "AFPROBE in MANUAL +${t}ms ring=${ring()}") }
                Log.i(TAG, "RESULT af_probe: DONE")
            }
            "gamepad_test" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { gamepadTest() }
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
            "media_times" -> {
                val media = io.github.mugenoesis.sidereal.camera.MediaLibraryController()
                media.enterAndLoad()
                for (i in 1..30) {
                    delay(1000)
                    if (media.loadState.value == io.github.mugenoesis.sidereal.camera.MediaLoadState.LOADED) break
                }
                Log.i(TAG, "MEDIATIMES phoneNow=${System.currentTimeMillis()}")
                for (f in media.files.value.take(8)) {
                    Log.i(TAG, "MEDIATIMES ${f.fileName} type=${f.mediaType.name} timeCreated=${f.timeCreated} date=${f.dateCreated} size=${f.fileSize} index=${f.index}")
                }
                Log.i(TAG, "MEDIATIMES total=${media.files.value.size} state=${media.loadState.value}")
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

    /** Logs the camera's raw pushed histogram at three exposures so the layout (length, channels, scale) can be read off. */
    private suspend fun histogramProbe() {
        val camera = DJIConnectionManager.camera ?: return
        var latest: ShortArray? = null
        camera.setHistogramEnabled(true) { Log.i(TAG, "hist enable ${it?.description}") }
        camera.setHistogramCallback { latest = it }
        callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }
        for ((label, iso, shutter) in listOf(
            Triple("dark", "ISO_100", "SHUTTER_SPEED_1_8000"),
            Triple("mid", "ISO_800", "SHUTTER_SPEED_1_60"),
            Triple("bright", "ISO_1600", "SHUTTER_SPEED_1_8")
        )) {
            callback<String?> { RealCameraGateway.setIso(iso) { e -> it(e) } }
            callback<String?> { RealCameraGateway.setShutterSpeed(shutter) { e -> it(e) } }
            delay(3000)
            val d = latest
            if (d == null) { Log.i(TAG, "HIST $label: no data"); continue }
            Log.i(TAG, "HIST $label: len=${d.size} min=${d.minOrNull()} max=${d.maxOrNull()} sum=${d.sumOf { it.toLong() }}")
            Log.i(TAG, "HIST $label: ${d.joinToString(",")}")
            delay(3500) // hold this exposure so the host can screenshot it
        }
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
    }

    /** Mean preview luma at one ISO across a ladder of shutters - shows where the live preview stops following the shutter. */
    private suspend fun lumaVsShutter(args: Map<String, String>) {
        val camera = DJIConnectionManager.camera ?: return
        var latest: ShortArray? = null
        camera.setHistogramEnabled(true) { }
        camera.setHistogramCallback { latest = it }
        callback<String?> { RealCameraGateway.setExposureMode("MANUAL") { e -> it(e) } }
        val iso = args["iso"] ?: "ISO_400"
        callback<String?> { RealCameraGateway.setIso(iso) { e -> it(e) } }
        for (sh in listOf("1_250", "1_100", "1_60", "1_30", "1_15", "1_8", "1_4", "1_2", "1", "2", "4", "8")) {
            val name = "SHUTTER_SPEED_" + (if (sh.contains("_")) sh else "${sh}")
            val err = callback<String?> { RealCameraGateway.setShutterSpeed(name) { e -> it(e) } }
            delay(2500)
            val stats = io.github.mugenoesis.sidereal.camera.HistogramModel.stats(latest)
            Log.i(TAG, "LUMA $iso $name err=$err mean=${stats?.meanLuma?.let { "%.1f".format(it) }} hi=${stats?.highlightsClipped?.let { "%.3f".format(it) }}")
        }
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
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

    private fun padMotion(vararg axes: Pair<Int, Float>): android.view.MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { axes.forEach { (axis, value) -> setAxisValue(axis, value) } })
        return android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0)
    }

    private fun padKey(code: Int, down: Boolean): android.view.KeyEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return android.view.KeyEvent(now, now, if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP, code, 0, 0, 0, 0, 0, android.view.InputDevice.SOURCE_GAMEPAD)
    }

    /** Injects synthetic controller events into the running activity and checks what the real gimbal and camera did. */
    private suspend fun gamepadTest() {
        val pad = io.github.mugenoesis.sidereal.input.GamepadInput.active ?: run {
            Log.i(TAG, "RESULT gamepad_test: FAIL no active GamepadInput - open the app first"); return
        }
        val problems = mutableListOf<String>()
        val km = dji.sdk.sdkmanager.DJISDKManager.getInstance().keyManager!!
        suspend fun read(name: String): String? = suspendCancellableCoroutine { cont ->
            km.getValue(dji.keysdk.CameraKey.create(name), object : dji.keysdk.callback.GetCallback {
                override fun onSuccess(value: Any) { if (cont.isActive) cont.resume(value.toString()) }
                override fun onFailure(e: dji.common.error.DJIError) { if (cont.isActive) cont.resume(null) }
            })
        }
        fun att() = DJIConnectionManager.gimbalState.value?.attitudeInDegrees
        suspend fun hold(ms: Long, vararg axes: Pair<Int, Float>) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { pad.handleMotion(padMotion(*axes)); delay(40) }
        }
        suspend fun press(code: Int, holdMs: Long = 120) { pad.handleKey(padKey(code, true)); delay(holdMs); pad.handleKey(padKey(code, false)) }
        val X = android.view.MotionEvent.AXIS_X
        val Y = android.view.MotionEvent.AXIS_Y

        callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
        delay(2000)

        // 1. left stick right pans right, left pans back
        val a0 = att()!!
        hold(1500, X to 1f)
        pad.handleMotion(padMotion(X to 0f)); delay(800)
        val a1 = att()!!
        Log.i(TAG, "PAD stick right: yaw ${a0.yaw} -> ${a1.yaw}")
        if (a1.yaw - a0.yaw < 15f) problems += "right stick moved yaw only ${a1.yaw - a0.yaw}"
        val settled = att()!!; delay(1000)
        if (kotlin.math.abs(att()!!.yaw - settled.yaw) > 0.3f) problems += "gimbal kept moving after the stick was released"
        hold(1500, X to -1f); pad.handleMotion(padMotion(X to 0f)); delay(800)

        // 2. up/down are inverted: stick up tilts DOWN, stick down tilts back up
        val b0 = att()!!
        hold(800, Y to -1f); pad.handleMotion(padMotion(Y to 0f)); delay(800)
        val b1 = att()!!
        Log.i(TAG, "PAD stick up (inverted): pitch ${b0.pitch} -> ${b1.pitch}")
        if (b0.pitch - b1.pitch < 5f) problems += "up stick changed pitch by ${b1.pitch - b0.pitch} (expected a clear decrease - inverted)"
        hold(800, Y to 1f); pad.handleMotion(padMotion(Y to 0f)); delay(800)
        val b2 = att()!!
        if (b2.pitch - b1.pitch < 5f) problems += "down stick did not tilt back up (${b1.pitch} -> ${b2.pitch})"

        // 3. right trigger takes a photo (photo mode only)
        var shot = false
        pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_RTRIGGER to 1f))
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end && !shot) { shot = DJIConnectionManager.cameraSystemState.value?.isShootingSinglePhoto == true; delay(50) }
        pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_RTRIGGER to 0f))
        Log.i(TAG, "PAD trigger: photo started=$shot")
        if (!shot) problems += "trigger did not start a photo"
        delay(5000)

        // 4. d-pad right then left steps the exposure mode
        val mode0 = read(dji.keysdk.CameraKey.EXPOSURE_MODE)
        pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_HAT_X to 1f)); delay(100); pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_HAT_X to 0f)); delay(1500)
        val mode1 = read(dji.keysdk.CameraKey.EXPOSURE_MODE)
        pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_HAT_X to -1f)); delay(100); pad.handleMotion(padMotion(android.view.MotionEvent.AXIS_HAT_X to 0f)); delay(1500)
        val mode2 = read(dji.keysdk.CameraKey.EXPOSURE_MODE)
        Log.i(TAG, "PAD d-pad: exposure $mode0 -> $mode1 -> $mode2")
        if (mode1 == mode0 || mode2 != mode0) problems += "d-pad exposure mode $mode0 -> $mode1 -> $mode2"

        // 5. X toggles the exposure lock
        press(android.view.KeyEvent.KEYCODE_BUTTON_X); delay(1500)
        val lockOn = read(dji.keysdk.CameraKey.AE_LOCK)
        press(android.view.KeyEvent.KEYCODE_BUTTON_X); delay(1500)
        val lockOff = read(dji.keysdk.CameraKey.AE_LOCK)
        Log.i(TAG, "PAD X: AE lock $lockOn then $lockOff")
        if (lockOn != "true" || lockOff != "false") problems += "AE lock $lockOn/$lockOff"

        // 6. L1 / L2 move the focus ring (the first press switches to manual focus)
        val camera = DJIConnectionManager.camera!!
        suspend fun ring(): Int = callback { cb -> camera.getFocusRingValue(object : dji.common.util.CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(v: Int) = cb(v)
            override fun onFailure(e: dji.common.error.DJIError) = cb(-1)
        }) }
        press(android.view.KeyEvent.KEYCODE_BUTTON_L2, 100); delay(1500)
        val r0 = ring()
        press(android.view.KeyEvent.KEYCODE_BUTTON_L2, 1500); delay(1200)
        val r1 = ring()
        press(android.view.KeyEvent.KEYCODE_BUTTON_L1, 1500); delay(1200)
        val r2 = ring()
        Log.i(TAG, "PAD focus: ring $r0 -> (L2 held) $r1 -> (L1 held) $r2")
        if (r1 <= r0) problems += "L2 hold did not move the ring farther ($r0 -> $r1)"
        if (r2 >= r1) problems += "L1 hold did not move the ring nearer ($r1 -> $r2)"

        // 7. R1 switches to video mode and back (never touches the shutter while in video)
        press(android.view.KeyEvent.KEYCODE_BUTTON_R1); delay(3000)
        val toVideo = DJIConnectionManager.cameraSystemState.value?.mode?.name
        press(android.view.KeyEvent.KEYCODE_BUTTON_R1); delay(3000)
        val toPhoto = DJIConnectionManager.cameraSystemState.value?.mode?.name
        Log.i(TAG, "PAD R1: $toVideo then $toPhoto")
        if (toVideo != "RECORD_VIDEO" || toPhoto != "SHOOT_PHOTO") problems += "R1 mode switch $toVideo/$toPhoto"

        // restore
        callback<String?> { RealCameraGateway.setFocusMode("AUTO") { e -> it(e) } }
        callback<String?> { RealCameraGateway.setExposureMode("PROGRAM") { e -> it(e) } }
        Log.i(TAG, "RESULT gamepad_test: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }

    /** Plays the part of a watch: feeds commands to the phone's bridge handler and checks what the camera and gimbal did. */
    private suspend fun wearHostTest() {
        val bridge = io.github.mugenoesis.sidereal.wear.WearBridge.active ?: run {
            Log.i(TAG, "RESULT wear_host_test: FAIL no active WearBridge - open the app first"); return
        }
        val problems = mutableListOf<String>()
        callback<String?> { RealCameraGateway.setCameraMode("SHOOT_PHOTO") { e -> it(e) } }
        delay(2500)

        val status = bridge.statusSnapshot()
        Log.i(TAG, "WEAR status: $status")
        if (!status.phoneOnOsmo) problems += "status says the phone is not on the Osmo"
        if (status.batteryPercent !in 1..100) problems += "battery ${status.batteryPercent}"
        if (status.photosLeft <= 0) problems += "photosLeft ${status.photosLeft}"
        if (status.cameraMode != io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode.PHOTO) problems += "mode ${status.cameraMode}"

        // the status survives the wire format that really goes to the watch
        val decoded = io.github.mugenoesis.sidereal.wearprotocol.WearProtocol.decodeStatus(io.github.mugenoesis.sidereal.wearprotocol.WearProtocol.encodeStatus(status))
        if (decoded != status) problems += "status changed on the wire"

        // capture
        var started = false
        val ack = bridge.handleCommand(W.Capture)
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end && !started) { started = DJIConnectionManager.cameraSystemState.value?.isShootingSinglePhoto == true; delay(50) }
        Log.i(TAG, "WEAR capture: ack=$ack photoStarted=$started")
        if (ack?.ok != true || !started) problems += "capture ack=$ack started=$started"
        delay(5000)

        // gimbal streams without acks
        val yaw0 = DJIConnectionManager.gimbalState.value!!.attitudeInDegrees.yaw
        val gimbalAck = bridge.handleCommand(W.Gimbal(1f, 0f))
        delay(1500)
        bridge.handleCommand(W.Gimbal(0f, 0f)); delay(800)
        val yaw1 = DJIConnectionManager.gimbalState.value!!.attitudeInDegrees.yaw
        Log.i(TAG, "WEAR gimbal: ack=$gimbalAck yaw $yaw0 -> $yaw1")
        if (gimbalAck != null || yaw1 - yaw0 < 15f) problems += "gimbal ack=$gimbalAck yaw delta=${yaw1 - yaw0}"
        bridge.handleCommand(W.Gimbal(-1f, 0f)); delay(1500); bridge.handleCommand(W.Gimbal(0f, 0f)); delay(800)

        // record is refused in photo mode (and never actually sent)
        val recordAck = bridge.handleCommand(W.ToggleRecord)
        Log.i(TAG, "WEAR record in photo mode: $recordAck")
        if (recordAck?.ok != false) problems += "record in photo mode was not refused: $recordAck"

        // mode toggle to video and back (no shutter involved)
        val toVideo = bridge.handleCommand(W.ToggleMode); delay(3000)
        val modeAfter = bridge.statusSnapshot().cameraMode
        val back = bridge.handleCommand(W.ToggleMode); delay(3000)
        val modeBack = bridge.statusSnapshot().cameraMode
        Log.i(TAG, "WEAR mode: ack=${toVideo?.ok} -> $modeAfter, ack=${back?.ok} -> $modeBack")
        if (modeAfter != io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode.VIDEO || modeBack != io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode.PHOTO) problems += "mode toggle $modeAfter/$modeBack"

        // live view flag turns the frame loop on and off without a channel being open
        bridge.handleCommand(W.LiveView(true)); delay(1500); bridge.handleCommand(W.LiveView(false))

        Log.i(TAG, "RESULT wear_host_test: ${if (problems.isEmpty()) "PASS" else "FAIL $problems"}")
    }
}
