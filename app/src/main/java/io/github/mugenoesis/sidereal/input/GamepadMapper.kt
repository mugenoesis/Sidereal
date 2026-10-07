package io.github.mugenoesis.sidereal.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

enum class GamepadButton { A, B, X, Y, L1, R1, L2, R2, L3, R3, START, SELECT, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT }

/** Stick axes are -1..1 as Android reports them (down and right positive); triggers are 0..1. */
enum class GamepadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, L2, R2, HAT_X, HAT_Y }

/** What a gamepad can ask the camera app to do - the activity implements these with the same code the touch controls use. */
interface GamepadActions {
    /** Camera-relative gimbal rates, -1..1: positive yaw pans right, positive pitch tilts up (whatever the stick direction that produced it). Sent on change, with a final (0, 0). */
    fun gimbal(yaw: Float, pitch: Float)

    /** -1..1, positive zooms in; a final 0 when released. */
    fun zoom(rate: Float)
    fun shutter()
    fun togglePhotoVideo()

    /**
     * The autofocus button: [pressed] true shows the tap-to-focus crosshair in the middle of the picture (the gimbal
     * can be moved with it up), false hides it and focuses there. A quick tap is just a very short hold.
     */
    fun autofocusHold(pressed: Boolean)

    /** One manual-focus step: -1 nearer, +1 farther. Repeated while the button is held. */
    fun focusRing(direction: Int)

    /** +1 next / -1 previous of P, A, S, M. */
    fun exposureMode(direction: Int)
    fun recenter()
    fun toggleAeLock()
    fun cycleGrid()

    /** One step of exposure compensation: +1 brighter, -1 darker. Repeated while the button is held. */
    fun exposureCompensation(direction: Int)
}

/** What a button can be set to do. [NONE] first so "unassigned" is where cycling starts. */
enum class GamepadAction(val label: String) {
    NONE("Nothing"),
    SHUTTER("Shutter"),
    TOGGLE_PHOTO_VIDEO("Photo / video"),
    AUTOFOCUS("Autofocus"),
    FOCUS_NEARER("Focus nearer (hold)"),
    FOCUS_FARTHER("Focus farther (hold)"),
    EXPOSURE_MODE_PREVIOUS("Exposure mode back"),
    EXPOSURE_MODE_NEXT("Exposure mode next"),
    EXPOSURE_COMP_UP("Exposure comp + (hold)"),
    EXPOSURE_COMP_DOWN("Exposure comp − (hold)"),
    RECENTER("Recentre gimbal"),
    TOGGLE_AE_LOCK("Exposure lock"),
    CYCLE_GRID("Grid")
}

/**
 * Which action each button triggers. A button has one action ([GamepadAction.NONE] clears it), and the same action
 * may be on as many buttons as you like. Immutable: every change returns a copy.
 */
class GamepadBindings private constructor(private val map: Map<GamepadButton, GamepadAction>) {

    fun actionFor(button: GamepadButton): GamepadAction = map[button] ?: GamepadAction.NONE

    /** Every button currently set to [action] (for [GamepadAction.NONE]: every button with nothing on it). */
    fun buttonsFor(action: GamepadAction): List<GamepadButton> = REMAPPABLE.filter { actionFor(it) == action }

    fun buttonFor(action: GamepadAction): GamepadButton? = buttonsFor(action).firstOrNull()

    fun with(button: GamepadButton, action: GamepadAction): GamepadBindings {
        val next = LinkedHashMap(map)
        if (action == GamepadAction.NONE) next.remove(button) else next[button] = action
        return GamepadBindings(next)
    }

    /** "A=AUTOFOCUS;B=NONE;..." for every remappable button, so an explicitly cleared default stays cleared. */
    fun encode(): String = REMAPPABLE.joinToString(";") { "${it.name}=${actionFor(it).name}" }

    override fun equals(other: Any?) = other is GamepadBindings && REMAPPABLE.all { actionFor(it) == other.actionFor(it) }
    override fun hashCode() = REMAPPABLE.fold(1) { h, b -> 31 * h + actionFor(b).hashCode() }
    override fun toString() = encode()

    companion object {
        /** Buttons offered on the settings screen (in the order shown). */
        val REMAPPABLE = listOf(
            GamepadButton.A, GamepadButton.B, GamepadButton.X, GamepadButton.Y,
            GamepadButton.L1, GamepadButton.R1, GamepadButton.L2, GamepadButton.R2,
            GamepadButton.L3, GamepadButton.R3, GamepadButton.START, GamepadButton.SELECT,
            GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN, GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT
        )

        fun default(): GamepadBindings = GamepadBindings(
            linkedMapOf(
                GamepadButton.R2 to GamepadAction.SHUTTER,
                GamepadButton.R1 to GamepadAction.TOGGLE_PHOTO_VIDEO,
                GamepadButton.A to GamepadAction.AUTOFOCUS,
                GamepadButton.L1 to GamepadAction.FOCUS_NEARER,
                GamepadButton.L2 to GamepadAction.FOCUS_FARTHER,
                GamepadButton.L3 to GamepadAction.RECENTER,
                GamepadButton.X to GamepadAction.TOGGLE_AE_LOCK,
                GamepadButton.Y to GamepadAction.CYCLE_GRID,
                GamepadButton.DPAD_LEFT to GamepadAction.EXPOSURE_MODE_PREVIOUS,
                GamepadButton.DPAD_RIGHT to GamepadAction.EXPOSURE_MODE_NEXT,
                GamepadButton.DPAD_UP to GamepadAction.EXPOSURE_COMP_UP,
                GamepadButton.DPAD_DOWN to GamepadAction.EXPOSURE_COMP_DOWN
            )
        )

        /** Starts from the defaults and applies every valid entry of [stored]; anything unreadable is skipped. */
        fun decode(stored: String?): GamepadBindings {
            var result = default()
            if (stored.isNullOrBlank()) return result
            for (entry in stored.split(";")) {
                val parts = entry.split("=")
                if (parts.size != 2) continue
                val button = REMAPPABLE.firstOrNull { it.name == parts[0].trim() } ?: continue
                val action = GamepadAction.values().firstOrNull { it.name == parts[1].trim() } ?: continue
                result = result.with(button, action)
            }
            return result
        }
    }
}

/** The stick/response settings the user can change on the gamepad settings screen. */
enum class GamepadSetting(val label: String) {
    GIMBAL_SPEED("Gimbal speed"),
    ZOOM_SPEED("Zoom speed"),
    DEADZONE("Dead zone"),
    RESPONSE("Response"),
    INVERT_TILT("Stick up")
}

data class GamepadConfig(
    val deadzone: Float = 0.15f,
    /** Response curve exponent: 1 is linear, 2 gives fine control near the centre and full speed at the edge. */
    val expo: Float = 2f,
    val triggerPress: Float = 0.6f,
    val triggerRelease: Float = 0.4f,
    val focusRepeatDelayMs: Long = 300,
    val focusRepeatMs: Long = 120,
    /** Exposure compensation repeats slower than focus: each step is a camera round trip, and a third of a stop is a visible change. */
    val evRepeatDelayMs: Long = 400,
    val evRepeatMs: Long = 250,
    /** Stick up tilts the camera DOWN (and vice versa) - the owner's preference; set false for the usual way round. */
    val invertPitch: Boolean = true,
    /** Scales the gimbal stick's output, [MIN_SPEED]..1: lower is slower at full deflection (finer moves). */
    val gimbalSpeed: Float = 1f,
    /** Scales the zoom stick the same way. */
    val zoomSpeed: Float = 1f
) {
    /** The user-adjustable settings only (the rest are fixed tuning): "deadzone,expo,gimbalSpeed,zoomSpeed,invertPitch". */
    fun encode(): String = String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f,%b", deadzone, expo, gimbalSpeed, zoomSpeed, invertPitch)

    /** One rung up or down for [setting] (the toggle just flips); off-ladder values step to their neighbour. */
    fun adjusted(setting: GamepadSetting, direction: Int): GamepadConfig = when (setting) {
        GamepadSetting.GIMBAL_SPEED -> copy(gimbalSpeed = stepPercent(gimbalSpeed, direction, 10, 100, 10))
        GamepadSetting.ZOOM_SPEED -> copy(zoomSpeed = stepPercent(zoomSpeed, direction, 10, 100, 10))
        GamepadSetting.DEADZONE -> copy(deadzone = stepPercent(deadzone, direction, 5, 50, 5))
        GamepadSetting.RESPONSE -> copy(expo = stepPercent(expo, direction, 100, 300, 50))
        GamepadSetting.INVERT_TILT -> copy(invertPitch = !invertPitch)
    }

    fun display(setting: GamepadSetting): String = when (setting) {
        GamepadSetting.GIMBAL_SPEED -> "${Math.round(gimbalSpeed * 100)}%"
        GamepadSetting.ZOOM_SPEED -> "${Math.round(zoomSpeed * 100)}%"
        GamepadSetting.DEADZONE -> "${Math.round(deadzone * 100)}%"
        GamepadSetting.RESPONSE -> when {
            expo < 1.25f -> "Linear"
            expo < 1.75f -> "Soft"
            expo < 2.25f -> "Curved"
            expo < 2.75f -> "Steep"
            else -> "Strong"
        }
        GamepadSetting.INVERT_TILT -> if (invertPitch) "Up = tilt down" else "Up = tilt up"
    }

    /** [value] is a fraction (or, with scale 1, a plain number) moved one [step] along min..max in hundredths. */
    private fun stepPercent(value: Float, direction: Int, min: Int, max: Int, step: Int, scale: Float = 100f): Float {
        val current = Math.round(value * scale)
        val next = if (direction > 0) {
            (min..max step step).firstOrNull { it > current } ?: max
        } else {
            (min..max step step).lastOrNull { it < current } ?: min
        }
        return next / scale
    }

    companion object {
        const val MIN_SPEED = 0.1f
        const val MAX_DEADZONE = 0.5f
        private const val MIN_DEADZONE = 0.05f

        /** Out-of-range or unreadable values fall back to sane ones rather than leaving the pad unusable. */
        fun decode(stored: String?): GamepadConfig {
            val p = stored?.split(",") ?: return GamepadConfig()
            if (p.size != 5) return GamepadConfig()
            val d = p[0].toFloatOrNull() ?: return GamepadConfig()
            val e = p[1].toFloatOrNull() ?: return GamepadConfig()
            val g = p[2].toFloatOrNull() ?: return GamepadConfig()
            val z = p[3].toFloatOrNull() ?: return GamepadConfig()
            val inv = when (p[4]) { "true" -> true; "false" -> false; else -> return GamepadConfig() }
            return GamepadConfig(
                deadzone = d.coerceIn(MIN_DEADZONE, MAX_DEADZONE),
                expo = e.coerceIn(1f, 3f),
                gimbalSpeed = g.coerceIn(MIN_SPEED, 1f),
                zoomSpeed = z.coerceIn(MIN_SPEED, 1f),
                invertPitch = inv
            )
        }
    }
}

/**
 * Turns raw gamepad events into [GamepadActions]. Pure - no Android types - so the behaviour that matters
 * (dead zone, response curve, trigger hysteresis, held-button repeat, lock-out) is unit-tested. The Android
 * side only translates `MotionEvent`/`KeyEvent` codes, which differ between controller families, into
 * [GamepadAxis]/[GamepadButton].
 *
 * Default layout:
 *  - left stick: gimbal (up/down inverted by default: push up to tilt down); click it to recentre
 *  - right stick up/down: zoom
 *  - R2 / right trigger: shutter; R1: photo <-> video
 *  - A: autofocus; L1 / L2: manual focus ring nearer / farther (repeats while held)
 *  - d-pad left/right: step the exposure mode P/A/S/M
 *  - X: exposure lock; Y: composition grid
 */
class GamepadMapper(
    private val actions: GamepadActions,
    /** Live: change it and the next stick movement uses it. */
    var config: GamepadConfig = GamepadConfig(),
    /** Live: change it and the next press uses it. */
    var bindings: GamepadBindings = GamepadBindings.default()
) {

    /** While locked (e.g. a sequence is running) nothing is acted on; locking also stops any motion in progress. */
    var locked: Boolean = false
        set(value) {
            if (value && !field) stopMotion()
            field = value
        }

    /** Called when the button chord is entered. */
    var onChord: (() -> Unit)? = null
    private val chord = io.github.mugenoesis.sidereal.drill.ChordDetector()

    private var leftX = 0f
    private var leftY = 0f
    private var rightY = 0f
    private var sentYaw = 0f
    private var sentPitch = 0f
    private var sentZoom = 0f

    private val down = HashSet<GamepadButton>()

    /**
     * Many controllers (the 8BitDo Ultimate among them) report each trigger twice - as an analog axis and as a button.
     * Treating those as two presses fires the shutter twice per pull, so the two are tracked separately and the
     * trigger counts as pressed while EITHER is down; only a change of that combined state is acted on.
     */
    private val triggerAxisDown = HashSet<GamepadButton>()
    private val triggerButtonDown = HashSet<GamepadButton>()
    private val triggerActive = HashSet<GamepadButton>()
    private var hatX = 0
    private var hatY = 0

    /** The held button that keeps repeating (focus or exposure compensation) - the one pressed last wins. */
    private enum class Repeat { FOCUS, EXPOSURE }
    /** Buttons currently held as "autofocus": the crosshair is up while any is down. */
    private val aimButtons = HashSet<GamepadButton>()

    private var repeatKind: Repeat? = null
    private var repeatDirection = 0
    private var repeatSource: GamepadButton? = null
    private var nextRepeatAt = 0L

    fun onAxis(axis: GamepadAxis, value: Float, nowMs: Long = 0) {
        if (locked) return
        when (axis) {
            GamepadAxis.LEFT_X -> { leftX = value; updateGimbal() }
            GamepadAxis.LEFT_Y -> { leftY = value; updateGimbal() }
            GamepadAxis.RIGHT_Y -> { rightY = value; updateZoom() }
            GamepadAxis.RIGHT_X -> Unit
            GamepadAxis.R2 -> triggerAxis(GamepadButton.R2, value, nowMs)
            GamepadAxis.L2 -> triggerAxis(GamepadButton.L2, value, nowMs)
            // The d-pad as a hat is just four buttons: pressing a direction presses that button until the hat re-centres.
            GamepadAxis.HAT_X -> {
                val direction = direction(value)
                if (direction != hatX) {
                    hold(hatX, GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT)?.let { dispatch(it, false, nowMs) }
                    hatX = direction
                    hold(direction, GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT)?.let { dispatch(it, true, nowMs) }
                }
            }
            GamepadAxis.HAT_Y -> {
                val direction = direction(value)
                if (direction != hatY) {
                    hold(hatY, GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN)?.let { dispatch(it, false, nowMs) }
                    hatY = direction
                    hold(direction, GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN)?.let { dispatch(it, true, nowMs) }
                }
            }
        }
    }

    fun onButton(button: GamepadButton, pressed: Boolean, nowMs: Long = 0) {
        if (locked) return
        if (pressed) {
            if (!down.add(button)) return // key auto-repeat: act on the press edge only
        } else {
            down.remove(button)
        }
        if (button == GamepadButton.L2 || button == GamepadButton.R2) {
            if (pressed) triggerButtonDown += button else triggerButtonDown -= button
            updateTrigger(button, nowMs)
        } else {
            dispatch(button, pressed, nowMs)
        }
    }

    /** Drive from a steady clock (e.g. every 50 ms) so a held focus button keeps stepping. */
    fun tick(nowMs: Long) {
        val kind = repeatKind
        if (locked || kind == null) return
        if (nowMs >= nextRepeatAt) {
            when (kind) {
                Repeat.FOCUS -> { actions.focusRing(repeatDirection); nextRepeatAt = nowMs + config.focusRepeatMs }
                Repeat.EXPOSURE -> { actions.exposureCompensation(repeatDirection); nextRepeatAt = nowMs + config.evRepeatMs }
            }
        }
    }

    /** The controller went away: don't leave the gimbal or zoom running. */
    fun onDisconnected() {
        stopMotion()
        down.clear()
        triggerAxisDown.clear()
        triggerButtonDown.clear()
        triggerActive.clear()
        hatX = 0
        hatY = 0
        leftX = 0f
        leftY = 0f
        rightY = 0f
    }

    /** Does whatever [button] is currently bound to; held actions (focus) start on press and stop on release. */
    private fun dispatch(button: GamepadButton, pressed: Boolean, nowMs: Long) {
        // The code is read from the raw buttons, before bindings: it works whatever they are set to do (and they still do it).
        if (pressed) chordKey(button)?.let { if (chord.onKey(it, nowMs)) onChord?.invoke() }
        // Letting go of a button that is aiming always ends the aim - even if it was rebound while held.
        if (!pressed && aimButtons.remove(button) && aimButtons.isEmpty()) actions.autofocusHold(false)
        when (bindings.actionFor(button)) {
            GamepadAction.NONE -> Unit
            GamepadAction.SHUTTER -> if (pressed) actions.shutter()
            GamepadAction.TOGGLE_PHOTO_VIDEO -> if (pressed) actions.togglePhotoVideo()
            GamepadAction.AUTOFOCUS -> if (pressed) {
                val first = aimButtons.isEmpty()
                aimButtons += button
                if (first) actions.autofocusHold(true)
            }
            GamepadAction.FOCUS_NEARER -> if (pressed) startRepeat(Repeat.FOCUS, -1, button, nowMs) else stopRepeat(button)
            GamepadAction.FOCUS_FARTHER -> if (pressed) startRepeat(Repeat.FOCUS, +1, button, nowMs) else stopRepeat(button)
            GamepadAction.EXPOSURE_COMP_UP -> if (pressed) startRepeat(Repeat.EXPOSURE, +1, button, nowMs) else stopRepeat(button)
            GamepadAction.EXPOSURE_COMP_DOWN -> if (pressed) startRepeat(Repeat.EXPOSURE, -1, button, nowMs) else stopRepeat(button)
            GamepadAction.EXPOSURE_MODE_PREVIOUS -> if (pressed) actions.exposureMode(-1)
            GamepadAction.EXPOSURE_MODE_NEXT -> if (pressed) actions.exposureMode(+1)
            GamepadAction.RECENTER -> if (pressed) actions.recenter()
            GamepadAction.TOGGLE_AE_LOCK -> if (pressed) actions.toggleAeLock()
            GamepadAction.CYCLE_GRID -> if (pressed) actions.cycleGrid()
        }
    }

    private fun chordKey(button: GamepadButton): io.github.mugenoesis.sidereal.drill.ChordKey? = when (button) {
        GamepadButton.DPAD_UP -> io.github.mugenoesis.sidereal.drill.ChordKey.UP
        GamepadButton.DPAD_DOWN -> io.github.mugenoesis.sidereal.drill.ChordKey.DOWN
        GamepadButton.DPAD_LEFT -> io.github.mugenoesis.sidereal.drill.ChordKey.LEFT
        GamepadButton.DPAD_RIGHT -> io.github.mugenoesis.sidereal.drill.ChordKey.RIGHT
        GamepadButton.B -> io.github.mugenoesis.sidereal.drill.ChordKey.B
        GamepadButton.A -> io.github.mugenoesis.sidereal.drill.ChordKey.A
        GamepadButton.START -> io.github.mugenoesis.sidereal.drill.ChordKey.START
        else -> null
    }

    private fun direction(value: Float) = when {
        value >= 0.5f -> 1
        value <= -0.5f -> -1
        else -> 0
    }

    /** The d-pad button a hat [direction] stands for: negative = [negative], positive = [positive], 0 = none. */
    private fun hold(direction: Int, negative: GamepadButton, positive: GamepadButton): GamepadButton? = when (direction) {
        -1 -> negative
        1 -> positive
        else -> null
    }

    private fun triggerAxis(button: GamepadButton, value: Float, nowMs: Long) {
        val was = button in triggerAxisDown
        val now = when {
            !was && value >= config.triggerPress -> true
            was && value <= config.triggerRelease -> false
            else -> was
        }
        if (now) triggerAxisDown += button else triggerAxisDown -= button
        updateTrigger(button, nowMs)
    }

    private fun updateTrigger(button: GamepadButton, nowMs: Long) {
        val active = button in triggerAxisDown || button in triggerButtonDown
        if (active == (button in triggerActive)) return
        if (active) triggerActive += button else triggerActive -= button
        dispatch(button, active, nowMs)
    }

    private fun startRepeat(kind: Repeat, direction: Int, source: GamepadButton, nowMs: Long) {
        repeatKind = kind
        repeatDirection = direction
        repeatSource = source
        when (kind) {
            Repeat.FOCUS -> { actions.focusRing(direction); nextRepeatAt = nowMs + config.focusRepeatDelayMs }
            Repeat.EXPOSURE -> { actions.exposureCompensation(direction); nextRepeatAt = nowMs + config.evRepeatDelayMs }
        }
    }

    private fun stopRepeat(source: GamepadButton) {
        if (repeatSource == source) {
            repeatKind = null
            repeatDirection = 0
            repeatSource = null
        }
    }

    private fun updateGimbal() {
        val pitchSign = if (config.invertPitch) -1f else 1f
        val inside = hypot(leftX, leftY) < config.deadzone
        val yaw = if (inside) 0f else shape(leftX) * config.gimbalSpeed
        val pitch = if (inside) 0f else shape(-leftY) * pitchSign * config.gimbalSpeed
        if (yaw != sentYaw || pitch != sentPitch) {
            sentYaw = yaw
            sentPitch = pitch
            actions.gimbal(yaw, pitch)
        }
    }

    private fun updateZoom() {
        val rate = if (abs(rightY) < config.deadzone) 0f else shape(-rightY) * config.zoomSpeed
        if (rate != sentZoom) {
            sentZoom = rate
            actions.zoom(rate)
        }
    }

    /** Dead zone removed and the rest rescaled to 0..1, then curved, keeping the sign. */
    private fun shape(v: Float): Float {
        val magnitude = abs(v).coerceAtMost(1f)
        if (magnitude <= config.deadzone) return 0f
        val rescaled = (magnitude - config.deadzone) / (1f - config.deadzone)
        return sign(v) * rescaled.pow(config.expo)
    }

    private fun stopMotion() {
        if (aimButtons.isNotEmpty()) {
            aimButtons.clear()
            actions.autofocusHold(false)
        }
        if (sentYaw != 0f || sentPitch != 0f) {
            sentYaw = 0f
            sentPitch = 0f
            actions.gimbal(0f, 0f)
        }
        if (sentZoom != 0f) {
            sentZoom = 0f
            actions.zoom(0f)
        }
        repeatKind = null
        repeatDirection = 0
        repeatSource = null
    }
}
