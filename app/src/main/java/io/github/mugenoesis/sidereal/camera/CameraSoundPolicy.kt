package io.github.mugenoesis.sidereal.camera

/**
 * The sounds a camera makes, played by the phone: the X5/Osmo exposes no shutter-sound or beep setting through the
 * DJI SDK (checked 2026-10-07 - no key on the camera, only a flight-controller buzzer), so the app supplies its own,
 * driven by what the camera actually does.
 */
enum class CameraTone { SHUTTER, TIMER_TICK, TIMER_LAST, RECORD_START, RECORD_STOP, FOCUS }

sealed class CameraSoundEvent {
    object PhotoTaken : CameraSoundEvent()
    object RecordingStarted : CameraSoundEvent()
    object RecordingStopped : CameraSoundEvent()
    object FocusLocked : CameraSoundEvent()

    /** [remaining] seconds left on the self-timer, counting down to 1. */
    data class TimerTick(val remaining: Int) : CameraSoundEvent()
}

enum class CameraSoundOption(val label: String) {
    MASTER("Sounds"),
    SHUTTER("Shutter click"),
    TIMER("Self-timer beeps"),
    RECORDING("Record start / stop"),
    FOCUS("Focus-lock beep")
}

data class CameraSoundSettings(
    val enabled: Boolean = true,
    val shutter: Boolean = true,
    val timer: Boolean = true,
    val recording: Boolean = true,
    val focus: Boolean = true
) {
    fun isOn(option: CameraSoundOption): Boolean = when (option) {
        CameraSoundOption.MASTER -> enabled
        CameraSoundOption.SHUTTER -> shutter
        CameraSoundOption.TIMER -> timer
        CameraSoundOption.RECORDING -> recording
        CameraSoundOption.FOCUS -> focus
    }

    fun toggled(option: CameraSoundOption): CameraSoundSettings = when (option) {
        CameraSoundOption.MASTER -> copy(enabled = !enabled)
        CameraSoundOption.SHUTTER -> copy(shutter = !shutter)
        CameraSoundOption.TIMER -> copy(timer = !timer)
        CameraSoundOption.RECORDING -> copy(recording = !recording)
        CameraSoundOption.FOCUS -> copy(focus = !focus)
    }

    /** "11011" style: one digit per option in declaration order. */
    fun encode(): String = CameraSoundOption.values().joinToString("") { if (isOn(it)) "1" else "0" }

    companion object {
        /** Anything missing or malformed means "the defaults" - every sound on. */
        fun decode(stored: String?): CameraSoundSettings {
            val options = CameraSoundOption.values()
            if (stored == null || stored.length != options.size || stored.any { it != '0' && it != '1' }) return CameraSoundSettings()
            val on = stored.map { it == '1' }
            return CameraSoundSettings(enabled = on[0], shutter = on[1], timer = on[2], recording = on[3], focus = on[4])
        }
    }
}

object CameraSoundPolicy {

    /**
     * The tone for [event], or null for silence. [sequenceRunning]: a timelapse or panorama takes hundreds of
     * frames, and a click on each would be unbearable, so a sequence's shots are silent.
     */
    fun toneFor(event: CameraSoundEvent, settings: CameraSoundSettings, sequenceRunning: Boolean = false): CameraTone? {
        if (!settings.enabled) return null
        return when (event) {
            CameraSoundEvent.PhotoTaken -> CameraTone.SHUTTER.takeIf { settings.shutter && !sequenceRunning }
            CameraSoundEvent.RecordingStarted -> CameraTone.RECORD_START.takeIf { settings.recording }
            CameraSoundEvent.RecordingStopped -> CameraTone.RECORD_STOP.takeIf { settings.recording }
            CameraSoundEvent.FocusLocked -> CameraTone.FOCUS.takeIf { settings.focus }
            is CameraSoundEvent.TimerTick ->
                (if (event.remaining <= 1) CameraTone.TIMER_LAST else CameraTone.TIMER_TICK).takeIf { settings.timer }
        }
    }
}

/**
 * Turns the camera's polled state into one-off events: a shot is the moment the camera starts shooting (not every
 * reading while it is busy), recording changes are announced once, and the first reading after (re)connecting is
 * just taken as the starting point so reconnecting to a camera that is already recording stays quiet.
 */
class CameraSoundEdges {
    private var known = false
    private var shooting = false
    private var recording = false
    private var focusLocked = false

    fun onState(shooting: Boolean, recording: Boolean): List<CameraSoundEvent> {
        val events = ArrayList<CameraSoundEvent>(2)
        if (known) {
            if (shooting && !this.shooting) events += CameraSoundEvent.PhotoTaken
            if (recording && !this.recording) events += CameraSoundEvent.RecordingStarted
            if (!recording && this.recording) events += CameraSoundEvent.RecordingStopped
        }
        known = true
        this.shooting = shooting
        this.recording = recording
        return events
    }

    /** The camera went away: the next reading is a fresh starting point. */
    fun onLost() {
        known = false
    }

    fun onFocusLocked(locked: Boolean): List<CameraSoundEvent> {
        val rising = locked && !focusLocked
        focusLocked = locked
        return if (rising) listOf(CameraSoundEvent.FocusLocked) else emptyList()
    }
}
