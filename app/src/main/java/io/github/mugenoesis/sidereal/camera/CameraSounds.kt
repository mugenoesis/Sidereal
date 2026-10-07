package io.github.mugenoesis.sidereal.camera

import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.AppPreferences
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Plays [CameraTone]s on the phone: the system's own camera sounds where there is one, short beeps for the rest. */
class CameraSounds {
    private val actionSounds = MediaActionSound().apply {
        listOf(
            MediaActionSound.SHUTTER_CLICK, MediaActionSound.FOCUS_COMPLETE,
            MediaActionSound.START_VIDEO_RECORDING, MediaActionSound.STOP_VIDEO_RECORDING
        ).forEach { load(it) }
    }
    private val beeps: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME)
    } catch (e: RuntimeException) {
        Log.w(TAG, "no tone generator: ${e.message}")
        null
    }

    var lastPlayed: CameraTone? = null
        private set

    fun play(tone: CameraTone) {
        lastPlayed = tone
        Log.i(TAG, "play $tone")
        try {
            when (tone) {
                CameraTone.SHUTTER -> actionSounds.play(MediaActionSound.SHUTTER_CLICK)
                CameraTone.FOCUS -> actionSounds.play(MediaActionSound.FOCUS_COMPLETE)
                CameraTone.RECORD_START -> actionSounds.play(MediaActionSound.START_VIDEO_RECORDING)
                CameraTone.RECORD_STOP -> actionSounds.play(MediaActionSound.STOP_VIDEO_RECORDING)
                CameraTone.TIMER_TICK -> beeps?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                CameraTone.TIMER_LAST -> beeps?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not play $tone: ${e.message}")
        }
    }

    fun release() {
        actionSounds.release()
        beeps?.release()
    }

    private companion object {
        const val TAG = "CameraSounds"
        const val BEEP_VOLUME = 80
    }
}

/**
 * The camera sounds as a feature: watches what the camera does (a shot, recording starting or stopping), the
 * software autofocus locking and the self-timer, plays the matching tone if the user's options allow it, and adds
 * the option switches to the More tray.
 *
 * @param focusLocked the software autofocus's locked state
 * @param sequenceRunning whether a timelapse/panorama sequence is active (its shots are silent)
 */
class CameraSoundsFeature(
    private val activity: AppCompatActivity,
    focusLocked: StateFlow<Boolean>,
    private val sequenceRunning: () -> Boolean
) {
    private val sounds = CameraSounds()
    private val edges = CameraSoundEdges()
    var settings: CameraSoundSettings = CameraSoundSettings.decode(AppPreferences.cameraSounds)
        private set

    init {
        DJIConnectionManager.cameraSystemState.onEach { state ->
            if (state == null) {
                edges.onLost()
            } else {
                val shooting = state.isShootingSinglePhoto || state.isShootingBurstPhoto || state.isShootingRAWBurstPhoto
                edges.onState(shooting, state.isRecording).forEach(::handle)
            }
        }.launchIn(activity.lifecycleScope)
        focusLocked.onEach { edges.onFocusLocked(it).forEach(::handle) }.launchIn(activity.lifecycleScope)
        buildSwitches()
    }

    /** Wire to the self-timer's per-second tick. */
    fun onTimerTick(remaining: Int) = handle(CameraSoundEvent.TimerTick(remaining))

    fun release() = sounds.release()

    private fun handle(event: CameraSoundEvent) {
        CameraSoundPolicy.toneFor(event, settings, sequenceRunning())?.let { sounds.play(it) }
    }

    private fun buildSwitches() {
        val tray = activity.findViewById<LinearLayout>(io.github.mugenoesis.sidereal.R.id.moreSettingsTray) ?: return
        tray.addView(TextView(activity).apply {
            text = "Camera sounds"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(2))
        })
        val boxes = HashMap<CameraSoundOption, CheckBox>()
        for (option in CameraSoundOption.values()) {
            val box = CheckBox(activity).apply {
                text = option.label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                isChecked = settings.isOn(option)
                tag = "sound_${option.name}"
                setOnCheckedChangeListener { _, _ ->
                    settings = settings.toggled(option)
                    AppPreferences.cameraSounds = settings.encode()
                    // Individual options are moot while the master is off.
                    boxes.forEach { (o, b) -> if (o != CameraSoundOption.MASTER) b.alpha = if (settings.enabled) 1f else 0.4f }
                    // Let the user hear what they just switched on.
                    if (isChecked) previewTone(option)
                }
            }
            boxes[option] = box
            tray.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        boxes.forEach { (o, b) -> if (o != CameraSoundOption.MASTER) b.alpha = if (settings.enabled) 1f else 0.4f }
    }

    private fun previewTone(option: CameraSoundOption) {
        when (option) {
            CameraSoundOption.SHUTTER -> sounds.play(CameraTone.SHUTTER)
            CameraSoundOption.TIMER -> sounds.play(CameraTone.TIMER_TICK)
            CameraSoundOption.RECORDING -> sounds.play(CameraTone.RECORD_START)
            CameraSoundOption.FOCUS -> sounds.play(CameraTone.FOCUS)
            CameraSoundOption.MASTER -> sounds.play(CameraTone.SHUTTER)
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
