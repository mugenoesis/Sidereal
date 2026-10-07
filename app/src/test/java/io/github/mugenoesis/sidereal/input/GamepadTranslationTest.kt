package io.github.mugenoesis.sidereal.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadTranslationTest {

    @Test
    fun `face buttons and shoulders map to their pad names`() {
        assertEquals(GamepadButton.A, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadButton.B, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(GamepadButton.X, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(GamepadButton.Y, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(GamepadButton.L1, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(GamepadButton.R1, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(GamepadButton.L2, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(GamepadButton.R2, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test
    fun `stick clicks start select and the d-pad map too`() {
        assertEquals(GamepadButton.L3, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(GamepadButton.R3, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertEquals(GamepadButton.START, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(GamepadButton.SELECT, GamepadKeyMap.button(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(GamepadButton.DPAD_LEFT, GamepadKeyMap.button(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(GamepadButton.DPAD_RIGHT, GamepadKeyMap.button(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(GamepadButton.DPAD_UP, GamepadKeyMap.button(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(GamepadButton.DPAD_DOWN, GamepadKeyMap.button(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `keys that are not gamepad buttons are left to the system`() {
        assertNull(GamepadKeyMap.button(KeyEvent.KEYCODE_A))
        assertNull(GamepadKeyMap.button(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(GamepadKeyMap.button(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `an xbox or playstation style pad uses Z and RZ for the right stick and the trigger axes for L2 R2`() {
        val layout = GamepadLayout.pick(setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_HAT_X))
        assertEquals(MotionEvent.AXIS_Z, layout.rightX)
        assertEquals(MotionEvent.AXIS_RZ, layout.rightY)
        assertEquals(MotionEvent.AXIS_LTRIGGER, layout.leftTrigger)
        assertEquals(MotionEvent.AXIS_RTRIGGER, layout.rightTrigger)
    }

    @Test
    fun `a pad that reports RX and RY has its right stick there, and BRAKE and GAS are its triggers`() {
        val layout = GamepadLayout.pick(setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS))
        assertEquals(MotionEvent.AXIS_RX, layout.rightX)
        assertEquals(MotionEvent.AXIS_RY, layout.rightY)
        assertEquals(MotionEvent.AXIS_BRAKE, layout.leftTrigger)
        assertEquals(MotionEvent.AXIS_GAS, layout.rightTrigger)
    }

    @Test
    fun `the left stick and hat are the same on every pad`() {
        val layout = GamepadLayout.pick(emptySet())
        assertEquals(MotionEvent.AXIS_X, layout.leftX)
        assertEquals(MotionEvent.AXIS_Y, layout.leftY)
        assertEquals(MotionEvent.AXIS_HAT_X, layout.hatX)
        assertEquals(MotionEvent.AXIS_HAT_Y, layout.hatY)
    }

    @Test
    fun `when both are present the standard axes win`() {
        val layout = GamepadLayout.pick(setOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS))
        assertEquals(MotionEvent.AXIS_Z, layout.rightX)
        assertEquals(MotionEvent.AXIS_LTRIGGER, layout.leftTrigger)
    }
}
