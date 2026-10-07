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
    fun autofocus()

    /** One manual-focus step: -1 nearer, +1 farther. Repeated while the button is held. */
    fun focusRing(direction: Int)

    /** +1 next / -1 previous of P, A, S, M. */
    fun exposureMode(direction: Int)
    fun recenter()
    fun toggleAeLock()
    fun cycleGrid()
}

data class GamepadConfig(
    val deadzone: Float = 0.15f,
    /** Response curve exponent: 1 is linear, 2 gives fine control near the centre and full speed at the edge. */
    val expo: Float = 2f,
    val triggerPress: Float = 0.6f,
    val triggerRelease: Float = 0.4f,
    val focusRepeatDelayMs: Long = 300,
    val focusRepeatMs: Long = 120,
    /** Stick up tilts the camera DOWN (and vice versa) - the owner's preference; set false for the usual way round. */
    val invertPitch: Boolean = true
)

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
class GamepadMapper(private val actions: GamepadActions, private val config: GamepadConfig = GamepadConfig()) {

    /** While locked (e.g. a sequence is running) nothing is acted on; locking also stops any motion in progress. */
    var locked: Boolean = false
        set(value) {
            if (value && !field) stopMotion()
            field = value
        }

    private var leftX = 0f
    private var leftY = 0f
    private var rightY = 0f
    private var sentYaw = 0f
    private var sentPitch = 0f
    private var sentZoom = 0f

    private val down = HashSet<GamepadButton>()
    private var r2AxisDown = false
    private var l2AxisDown = false
    private var hatDirection = 0

    private var focusDirection = 0
    private var focusSource: GamepadButton? = null
    private var nextFocusRepeatAt = 0L

    fun onAxis(axis: GamepadAxis, value: Float, nowMs: Long = 0) {
        if (locked) return
        when (axis) {
            GamepadAxis.LEFT_X -> { leftX = value; updateGimbal() }
            GamepadAxis.LEFT_Y -> { leftY = value; updateGimbal() }
            GamepadAxis.RIGHT_Y -> { rightY = value; updateZoom() }
            GamepadAxis.RIGHT_X, GamepadAxis.HAT_Y -> Unit
            GamepadAxis.R2 -> r2AxisDown = trigger(value, r2AxisDown) { press -> if (press) actions.shutter() }
            GamepadAxis.L2 -> l2AxisDown = trigger(value, l2AxisDown) { press ->
                if (press) startFocus(+1, GamepadButton.L2, nowMs) else stopFocus(GamepadButton.L2)
            }
            GamepadAxis.HAT_X -> {
                val direction = when {
                    value >= 0.5f -> 1
                    value <= -0.5f -> -1
                    else -> 0
                }
                if (direction != hatDirection) {
                    hatDirection = direction
                    if (direction != 0) actions.exposureMode(direction)
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
        when (button) {
            GamepadButton.R2 -> if (pressed) actions.shutter()
            GamepadButton.R1 -> if (pressed) actions.togglePhotoVideo()
            GamepadButton.A -> if (pressed) actions.autofocus()
            GamepadButton.L1 -> if (pressed) startFocus(-1, GamepadButton.L1, nowMs) else stopFocus(GamepadButton.L1)
            GamepadButton.L2 -> if (pressed) startFocus(+1, GamepadButton.L2, nowMs) else stopFocus(GamepadButton.L2)
            GamepadButton.L3 -> if (pressed) actions.recenter()
            GamepadButton.X -> if (pressed) actions.toggleAeLock()
            GamepadButton.Y -> if (pressed) actions.cycleGrid()
            GamepadButton.DPAD_LEFT -> if (pressed) actions.exposureMode(-1)
            GamepadButton.DPAD_RIGHT -> if (pressed) actions.exposureMode(+1)
            else -> Unit
        }
    }

    /** Drive from a steady clock (e.g. every 50 ms) so a held focus button keeps stepping. */
    fun tick(nowMs: Long) {
        if (locked || focusDirection == 0) return
        if (nowMs >= nextFocusRepeatAt) {
            actions.focusRing(focusDirection)
            nextFocusRepeatAt = nowMs + config.focusRepeatMs
        }
    }

    /** The controller went away: don't leave the gimbal or zoom running. */
    fun onDisconnected() {
        stopMotion()
        down.clear()
        r2AxisDown = false
        l2AxisDown = false
        hatDirection = 0
        leftX = 0f
        leftY = 0f
        rightY = 0f
    }

    private fun trigger(value: Float, wasDown: Boolean, onEdge: (pressed: Boolean) -> Unit): Boolean = when {
        !wasDown && value >= config.triggerPress -> true.also { onEdge(true) }
        wasDown && value <= config.triggerRelease -> false.also { onEdge(false) }
        else -> wasDown
    }

    private fun startFocus(direction: Int, source: GamepadButton, nowMs: Long) {
        focusDirection = direction
        focusSource = source
        actions.focusRing(direction)
        nextFocusRepeatAt = nowMs + config.focusRepeatDelayMs
    }

    private fun stopFocus(source: GamepadButton) {
        if (focusSource == source) {
            focusDirection = 0
            focusSource = null
        }
    }

    private fun updateGimbal() {
        val pitchSign = if (config.invertPitch) -1f else 1f
        val inside = hypot(leftX, leftY) < config.deadzone
        val yaw = if (inside) 0f else shape(leftX)
        val pitch = if (inside) 0f else shape(-leftY) * pitchSign
        if (yaw != sentYaw || pitch != sentPitch) {
            sentYaw = yaw
            sentPitch = pitch
            actions.gimbal(yaw, pitch)
        }
    }

    private fun updateZoom() {
        val rate = if (abs(rightY) < config.deadzone) 0f else shape(-rightY)
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
        if (sentYaw != 0f || sentPitch != 0f) {
            sentYaw = 0f
            sentPitch = 0f
            actions.gimbal(0f, 0f)
        }
        if (sentZoom != 0f) {
            sentZoom = 0f
            actions.zoom(0f)
        }
        focusDirection = 0
        focusSource = null
    }
}
