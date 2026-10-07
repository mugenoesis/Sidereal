package io.github.mugenoesis.sidereal.camera

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.AppPreferences
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The everyday camera controls around the shutter: drive dial
 * (single/HDR/burst/AEB), self-timer, exposure lock and composition grid.
 * Owns their controllers and views so MainActivity only has to forward the
 * shutter press and the "camera re-bound" event.
 */
class ShootingControls(
    private val activity: AppCompatActivity,
    private val mediaFormatController: MediaFormatController
) {
    private val drive = DriveController()
    private val aeLock = AeLockController()
    private val countdown = ShutterCountdown(activity.lifecycleScope)

    private val driveButton: Button = activity.findViewById(R.id.btnDrive)
    private val timerButton: Button = activity.findViewById(R.id.btnTimer)
    private val aeLockButton: Button = activity.findViewById(R.id.btnAeLock)
    private val gridButton: Button = activity.findViewById(R.id.btnGrid)
    private val gridOverlay: GridOverlayView = activity.findViewById(R.id.gridOverlay)
    private val countdownText: TextView = activity.findViewById(R.id.countdownText)

    /** Called with each remaining second of the self-timer (for the beeps). */
    var onTimerTick: ((Int) -> Unit)?
        get() = countdown.onTick
        set(value) { countdown.onTick = value }

    private var timerSeconds = AppPreferences.selfTimerSeconds
    private var gridMode = AppPreferences.gridMode

    init {
        driveButton.setOnClickListener { drive.cycle() }
        timerButton.setOnClickListener {
            timerSeconds = TimerOptions.next(timerSeconds)
            AppPreferences.selfTimerSeconds = timerSeconds
            renderTimer()
        }
        aeLockButton.setOnClickListener { aeLock.toggle() }
        gridButton.setOnClickListener {
            gridMode = gridMode.next()
            AppPreferences.gridMode = gridMode
            renderGrid()
        }

        drive.current.onEach { driveButton.text = it.label }.launchIn(activity.lifecycleScope)
        aeLock.locked.onEach { renderAeLock(it) }.launchIn(activity.lifecycleScope)
        countdown.remaining.onEach {
            countdownText.visibility = if (it == null) View.GONE else View.VISIBLE
            countdownText.text = it?.toString().orEmpty()
        }.launchIn(activity.lifecycleScope)
        merge(drive.errorEvents, aeLock.errorEvents)

        combine(DJIConnectionManager.cameraSystemState, mediaFormatController.photoAspectRatio) { system, ratio ->
            val video = system?.mode?.name == "RECORD_VIDEO"
            gridOverlay.contentAspect = ContentRect.aspectFor(video, ratio?.name)
            setPhotoOnlyControlsEnabled(!video)
        }.launchIn(activity.lifecycleScope)

        renderTimer()
        renderGrid()
        renderAeLock(false)
    }

    /** Same as tapping the buttons - for other input methods (a gamepad). */
    fun toggleAeLock() = aeLock.toggle()

    fun cycleGrid() {
        gridMode = gridMode.next()
        AppPreferences.gridMode = gridMode
        renderGrid()
    }

    /**
     * Handles a shutter press for the self-timer. Returns true if the press was consumed - it cancelled a
     * running countdown, or started one that will call [fire] at zero - and false if the caller should shoot now.
     */
    fun handleShutter(fire: () -> Unit): Boolean {
        if (countdown.isRunning) {
            countdown.cancel()
            return true
        }
        val photoMode = DJIConnectionManager.cameraSystemState.value?.mode?.name == "SHOOT_PHOTO"
        if (photoMode && timerSeconds > 0) {
            countdown.start(timerSeconds) { fire() }
            return true
        }
        return false
    }

    /** The camera has (re)connected: put it back on the drive mode the UI shows, and forget any stale lock. */
    fun onCameraRebound() {
        aeLock.reset()
        drive.reassert()
        // The grid needs the photo aspect ratio to know where the picture is; it is otherwise only read when the
        // More tray opens. A second read a moment later covers the camera not answering right at bind time.
        mediaFormatController.refresh()
        activity.lifecycleScope.launch {
            delay(2500)
            mediaFormatController.refresh()
        }
    }

    private fun merge(vararg flows: kotlinx.coroutines.flow.Flow<String>) {
        for (flow in flows) {
            flow.onEach { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }.launchIn(activity.lifecycleScope)
        }
    }

    private fun setPhotoOnlyControlsEnabled(enabled: Boolean) {
        for (b in listOf(driveButton, timerButton)) {
            b.isEnabled = enabled
            b.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun renderTimer() {
        timerButton.text = if (timerSeconds == 0) "Timer off" else "Timer ${TimerOptions.label(timerSeconds)}"
        timerButton.setBackgroundResource(if (timerSeconds == 0) R.drawable.bg_pill_container else R.drawable.bg_segment_selected)
    }

    private fun renderGrid() {
        gridOverlay.mode = gridMode
        gridButton.text = if (gridMode == GridMode.OFF) "Grid off" else "Grid: ${gridMode.label}"
        gridButton.setBackgroundResource(if (gridMode == GridMode.OFF) R.drawable.bg_pill_container else R.drawable.bg_segment_selected)
    }

    private fun renderAeLock(locked: Boolean) {
        aeLockButton.text = if (locked) "AE locked" else "AE lock"
        aeLockButton.setBackgroundResource(if (locked) R.drawable.bg_segment_selected else R.drawable.bg_pill_container)
    }
}
