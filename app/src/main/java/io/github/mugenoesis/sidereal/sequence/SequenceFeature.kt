package io.github.mugenoesis.sidereal.sequence

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.camera.ShutterLogic
import io.github.mugenoesis.sidereal.display.NightMode
import io.github.mugenoesis.sidereal.series.FrameProcessor
import io.github.mugenoesis.sidereal.series.MediaStoreGallery
import io.github.mugenoesis.sidereal.series.PostRun
import io.github.mugenoesis.sidereal.series.RealCardSource
import io.github.mugenoesis.sidereal.series.RunSummary
import io.github.mugenoesis.sidereal.series.SeriesPlan
import io.github.mugenoesis.sidereal.series.SeriesPostRunner
import io.github.mugenoesis.sidereal.series.PanoramaProcessor
import io.github.mugenoesis.sidereal.series.TimelapseVideoProcessor
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wires [SequenceController] to the real activity: the tray, the progress
 * banner, the "cap the lens" prompt dialog, the shutter button lockout, and
 * the live camera/gimbal facts a plan needs. Lives outside MainActivity so
 * that file doesn't grow any further.
 *
 * @param shutterNameProvider current `ShutterSpeed` enum name from the camera's pushed readout
 * @param pointsProvider Timed Move's captured A and B, if set
 */
class SequenceFeature(
    private val activity: AppCompatActivity,
    private val tray: SequenceTrayView,
    private val banner: TextView,
    private val shutterButton: View,
    private val shutterNameProvider: () -> String?,
    private val pointsProvider: () -> Pair<Attitude?, Attitude?>,
    private val lensProvider: () -> io.github.mugenoesis.sidereal.camera.LensInfo? = { null },
    private val rampIo: RampIo? = null
) {
    companion object {
        /** The live feature, for the debug harness. */
        @Volatile var latest: SequenceFeature? = null
    }

    val controller = SequenceController(
        scope = activity.lifecycleScope,
        hostFactory = { onPrompt -> RealSequenceHost(onPrompt, rampIo = rampIo) },
        contextProvider = ::shootContext,
        prepare = ::ensurePhotoMode,
        precondition = ::blockedReason,
        detectedFocalMm = { lensProvider()?.primeFocalMm },
        postRun = PostRun { plan, run, report ->
            // A fresh source each time: it owns the camera's playback mode for the length of the download.
            SeriesPostRunner(RealCardSource(activity.applicationContext), MediaStoreGallery(activity.applicationContext), ::frameProcessorFor)
                .run(plan, run, report)
        }
    )

    private var promptDialog: AlertDialog? = null

    init {
        latest = this
        tray.bind(controller, activity.lifecycleScope)

        combine(controller.isRunning, controller.progress, controller.settings, controller.afterRun) { running, progress, settings, after ->
            arrayOf(running, progress, settings, after)
        }.onEach { values ->
            val running = values[0] as Boolean
            val progress = values[1] as SequenceProgress
            val settings = values[2] as SequenceSettings
            val after = values[3] as io.github.mugenoesis.sidereal.series.AfterRunProgress?
            keepAlive(running, progress, settings.mode.label, after)
            shutterButton.isEnabled = !running
            shutterButton.alpha = if (running) 0.4f else 1f
            banner.visibility = if (running) View.VISIBLE else View.GONE
            banner.text = if (after != null) {
                "${settings.mode.label} · ${after.stage}" + if (after.total > 0) " ${after.done}/${after.total}" else "..."
            } else "${settings.mode.label} · ${progress.capturesDone}/${progress.capturesTotal}" +
                (if (progress.state is SequenceState.AwaitingUser) " · waiting for you" else "") +
                (if (progress.waitingForCamera) " · waiting for the camera" else "") +
                (if (progress.exposureSummary.isNotEmpty()) "\n${progress.exposureSummary}" else "")
        }.launchIn(activity.lifecycleScope)

        controller.prompt.onEach { showPrompt(it) }.launchIn(activity.lifecycleScope)
    }

    fun refreshPreview() = tray.refreshPreview()

    /** The timelapse encoder or panorama stitcher for this run, if one was asked for. */
    @Suppress("UNUSED_PARAMETER")
    private fun frameProcessorFor(plan: SeriesPlan, run: RunSummary, folder: String): FrameProcessor? = when {
        plan.makeVideo -> TimelapseVideoProcessor(activity.applicationContext, folder, plan.fps)
        plan.stitch && plan.panorama != null -> PanoramaProcessor(activity.applicationContext, folder, plan.panorama)
        else -> null
    }

    private var keepAliveRunning = false
    private var lastNotification: SequenceNotificationContent? = null
    private var askedForNotifications = false
    private val notificationPermission =
        activity.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /**
     * While a sequence runs the foreground service keeps the process, CPU and WiFi awake and shows progress; when it
     * ends the notification stays behind saying how it went.
     */
    private fun keepAlive(running: Boolean, progress: SequenceProgress, modeLabel: String, after: io.github.mugenoesis.sidereal.series.AfterRunProgress?) {
        // The combined flow can deliver "stopped running" a beat before the final Done/Failed state; at the end read
        // the controller's own final progress, which is already set by then.
        val content = if (after != null) SequenceNotificationText.afterRun(modeLabel, after)
        else SequenceNotificationText.of(modeLabel, if (running) progress else controller.progress.value, if (running) null else controller.message.value)
        if (running) {
            if (!keepAliveRunning) {
                keepAliveRunning = true
                SequenceKeepAliveService.onStopRequested = { activity.runOnUiThread { controller.stop() } }
                askForNotificationPermissionOnce()
            }
            if (content != lastNotification) {
                lastNotification = content
                SequenceKeepAliveService.show(activity, content)
            }
        } else if (keepAliveRunning) {
            keepAliveRunning = false
            lastNotification = null
            SequenceKeepAliveService.end(activity, content)
        }
    }

    private fun askForNotificationPermissionOnce() {
        if (askedForNotifications || android.os.Build.VERSION.SDK_INT < 33) return
        askedForNotifications = true
        if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showPrompt(text: String?) {
        promptDialog?.dismiss()
        promptDialog = null
        if (text == null || activity.isFinishing) return
        promptDialog = AlertDialog.Builder(activity)
            .setTitle("Sequence paused")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> controller.continueFromPrompt() }
            .setNegativeButton("Cancel") { _, _ -> controller.stop() }
            .show()
            .also { NightMode.apply(it) }
    }

    private fun blockedReason(): String? =
        if (DJIConnectionManager.cameraSystemState.value?.isRecording == true) "Stop recording first" else null

    private fun shootContext(): ShootContext? {
        if (DJIConnectionManager.camera == null) return null
        val shutter = shutterNameProvider()
        val (a, b) = pointsProvider()
        val attitude = DJIConnectionManager.gimbalState.value?.attitudeInDegrees?.let { Attitude(it.pitch, it.yaw) }
        return ShootContext(
            exposureMs = shutter?.let { ShutterLogic.exposureMs(it) },
            shutterName = shutter,
            attitude = attitude,
            pitchLimits = DJIConnectionManager.pitchRangeDegrees(),
            yawLimits = DJIConnectionManager.yawRangeDegrees(),
            pointA = a,
            pointB = b,
            lensFocalMm = lensProvider()?.primeFocalMm,
            lensZoomMm = lensProvider()?.takeIf { it.isZoom }?.let { it.focalMinMm!!..it.focalMaxMm!! },
            ditherSeed = System.nanoTime()
        )
    }

    /** Sequences shoot stills - switch out of video mode first and wait for the camera to say it has. */
    private suspend fun ensurePhotoMode() {
        if (DJIConnectionManager.cameraSystemState.value?.mode?.name == "SHOOT_PHOTO") return
        suspendCancellableCoroutine<Unit> { cont ->
            RealCameraGateway.setCameraMode("SHOOT_PHOTO") { if (cont.isActive) cont.resume(Unit) }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            DJIConnectionManager.cameraSystemState.value?.mode?.name != "SHOOT_PHOTO"
        ) delay(100)
    }
}
