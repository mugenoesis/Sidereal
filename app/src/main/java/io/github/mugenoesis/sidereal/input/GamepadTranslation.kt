package io.github.mugenoesis.sidereal.input

import android.view.KeyEvent
import android.view.MotionEvent

/** Android key codes -> [GamepadButton]; null for anything that isn't a pad button, which stays with the system. */
object GamepadKeyMap {
    fun button(keyCode: Int): GamepadButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GamepadButton.A
        KeyEvent.KEYCODE_BUTTON_B -> GamepadButton.B
        KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButton.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButton.R3
        KeyEvent.KEYCODE_BUTTON_START -> GamepadButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadButton.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> GamepadButton.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButton.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButton.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButton.DPAD_RIGHT
        else -> null
    }
}

/**
 * Which `MotionEvent` axes carry what. Left stick and hat are universal; the right stick is on Z/RZ for
 * Xbox- and PlayStation-style pads (current Android) but on RX/RY for some others, and the triggers are
 * LTRIGGER/RTRIGGER or BRAKE/GAS - so look at which axes the device actually reports.
 */
data class GamepadLayout(
    val leftX: Int = MotionEvent.AXIS_X,
    val leftY: Int = MotionEvent.AXIS_Y,
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val hatX: Int = MotionEvent.AXIS_HAT_X,
    val hatY: Int = MotionEvent.AXIS_HAT_Y
) {
    companion object {
        fun pick(present: Set<Int>): GamepadLayout {
            val zStick = MotionEvent.AXIS_Z in present && MotionEvent.AXIS_RZ in present
            val rxStick = MotionEvent.AXIS_RX in present && MotionEvent.AXIS_RY in present
            val standardTriggers = MotionEvent.AXIS_LTRIGGER in present || MotionEvent.AXIS_RTRIGGER in present
            val brakeGas = MotionEvent.AXIS_BRAKE in present || MotionEvent.AXIS_GAS in present
            return GamepadLayout(
                rightX = if (!zStick && rxStick) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z,
                rightY = if (!zStick && rxStick) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ,
                leftTrigger = if (!standardTriggers && brakeGas) MotionEvent.AXIS_BRAKE else MotionEvent.AXIS_LTRIGGER,
                rightTrigger = if (!standardTriggers && brakeGas) MotionEvent.AXIS_GAS else MotionEvent.AXIS_RTRIGGER
            )
        }
    }
}
