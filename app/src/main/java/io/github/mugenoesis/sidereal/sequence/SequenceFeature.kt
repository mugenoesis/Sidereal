package io.github.mugenoesis.sidereal.sequence

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.camera.ShutterLogic
import io.github.mugenoesis.sidereal.display.NightMode
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
    private val pointsProvider: () -> Pair<Attitude?, Attitude?>
) {
    val controller = SequenceController(
        scope = activity.lifecycleScope,
        hostFactory = { onPrompt -> RealSequenceHost(onPrompt) },
        contextProvider = ::shootContext,
        prepare = ::ensurePhotoMode,
        precondition = ::blockedReason
    )

    private var promptDialog: AlertDialog? = null

    init {
        tray.bind(controller, activity.lifecycleScope)

        combine(controller.isRunning, controller.progress, controller.settings) { running, progress, settings ->
            Triple(running, progress, settings)
        }.onEach { (running, progress, settings) ->
            shutterButton.isEnabled = !running
            shutterButton.alpha = if (running) 0.4f else 1f
            banner.visibility = if (running) View.VISIBLE else View.GONE
            banner.text = "${settings.mode.label} · ${progress.capturesDone}/${progress.capturesTotal}" +
                if (progress.state is SequenceState.AwaitingUser) " · waiting for you" else ""
        }.launchIn(activity.lifecycleScope)

        controller.prompt.onEach { showPrompt(it) }.launchIn(activity.lifecycleScope)
    }

    fun refreshPreview() = tray.refreshPreview()

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
