package io.github.mugenoesis.sidereal.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadBindingsTest {

    private val defaults = GamepadBindings.default()

    @Test
    fun `the defaults are the layout the pad has always had`() {
        assertEquals(GamepadAction.SHUTTER, defaults.actionFor(GamepadButton.R2))
        assertEquals(GamepadAction.TOGGLE_PHOTO_VIDEO, defaults.actionFor(GamepadButton.R1))
        assertEquals(GamepadAction.AUTOFOCUS, defaults.actionFor(GamepadButton.A))
        assertEquals(GamepadAction.FOCUS_NEARER, defaults.actionFor(GamepadButton.L1))
        assertEquals(GamepadAction.FOCUS_FARTHER, defaults.actionFor(GamepadButton.L2))
        assertEquals(GamepadAction.RECENTER, defaults.actionFor(GamepadButton.L3))
        assertEquals(GamepadAction.TOGGLE_AE_LOCK, defaults.actionFor(GamepadButton.X))
        assertEquals(GamepadAction.CYCLE_GRID, defaults.actionFor(GamepadButton.Y))
        assertEquals(GamepadAction.EXPOSURE_MODE_PREVIOUS, defaults.actionFor(GamepadButton.DPAD_LEFT))
        assertEquals(GamepadAction.EXPOSURE_MODE_NEXT, defaults.actionFor(GamepadButton.DPAD_RIGHT))
    }

    @Test
    fun `buttons nobody uses do nothing`() {
        for (b in listOf(GamepadButton.B, GamepadButton.START, GamepadButton.SELECT, GamepadButton.R3, GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN)) {
            assertEquals("$b", GamepadAction.NONE, defaults.actionFor(b))
        }
    }

    @Test
    fun `binding an action to a button gives it to that button`() {
        val b = defaults.with(GamepadButton.B, GamepadAction.SHUTTER)
        assertEquals(GamepadAction.SHUTTER, b.actionFor(GamepadButton.B))
    }

    @Test
    fun `an action lives on one button only, so moving it frees its old button`() {
        val b = defaults.with(GamepadButton.B, GamepadAction.SHUTTER)
        assertEquals(GamepadAction.NONE, b.actionFor(GamepadButton.R2))
        assertEquals(1, GamepadButton.values().count { b.actionFor(it) == GamepadAction.SHUTTER })
    }

    @Test
    fun `giving a button a new action replaces its old one`() {
        val b = defaults.with(GamepadButton.A, GamepadAction.CYCLE_GRID)
        assertEquals(GamepadAction.CYCLE_GRID, b.actionFor(GamepadButton.A))
        assertEquals(GamepadAction.NONE, b.actionFor(GamepadButton.Y))
        assertEquals(GamepadAction.NONE, b.buttonFor(GamepadAction.AUTOFOCUS)?.let { b.actionFor(it) } ?: GamepadAction.NONE)
    }

    @Test
    fun `NONE clears a button and can be given to many`() {
        val b = defaults.with(GamepadButton.A, GamepadAction.NONE).with(GamepadButton.X, GamepadAction.NONE)
        assertEquals(GamepadAction.NONE, b.actionFor(GamepadButton.A))
        assertEquals(GamepadAction.NONE, b.actionFor(GamepadButton.X))
    }

    @Test
    fun `cycling steps through the actions in order and wraps both ways`() {
        val all = GamepadAction.values().toList()
        val b = defaults.with(GamepadButton.B, GamepadAction.NONE)
        val next = b.cycled(GamepadButton.B, +1)
        assertEquals(all[1], next.actionFor(GamepadButton.B))
        val back = b.cycled(GamepadButton.B, -1)
        assertEquals(all.last(), back.actionFor(GamepadButton.B))
    }

    @Test
    fun `cycling onto an action another button has moves it, never duplicates`() {
        var b = defaults.with(GamepadButton.B, GamepadAction.NONE)
        repeat(GamepadAction.values().size) { b = b.cycled(GamepadButton.B, +1) }
        for (a in GamepadAction.values().filter { it != GamepadAction.NONE }) {
            assertTrue("$a", GamepadButton.values().count { b.actionFor(it) == a } <= 1)
        }
    }

    @Test
    fun `bindings survive being stored and read back`() {
        val b = defaults.with(GamepadButton.B, GamepadAction.SHUTTER).with(GamepadButton.START, GamepadAction.TOGGLE_PHOTO_VIDEO)
        assertEquals(b, GamepadBindings.decode(b.encode()))
    }

    @Test
    fun `nothing stored, or garbage, gives the defaults`() {
        assertEquals(defaults, GamepadBindings.decode(null))
        assertEquals(defaults, GamepadBindings.decode("not a layout"))
        assertEquals(defaults, GamepadBindings.decode(""))
    }

    @Test
    fun `unknown names in stored data are skipped, the rest still apply`() {
        val stored = "A=FLY_TO_THE_MOON;B=SHUTTER"
        val b = GamepadBindings.decode(stored)
        assertEquals(GamepadAction.SHUTTER, b.actionFor(GamepadButton.B))
        assertEquals(GamepadAction.AUTOFOCUS, b.actionFor(GamepadButton.A)) // that entry was skipped: default stays
    }

    @Test
    fun `every action has a readable label`() {
        assertTrue(GamepadAction.values().all { it.label.isNotBlank() })
        assertNotEquals(GamepadAction.SHUTTER.label, GamepadAction.AUTOFOCUS.label)
    }

    @Test
    fun `the buttons offered for remapping are the ones a pad really has`() {
        assertTrue(GamepadBindings.REMAPPABLE.containsAll(listOf(GamepadButton.A, GamepadButton.R2, GamepadButton.DPAD_UP)))
        assertFalse(GamepadBindings.REMAPPABLE.isEmpty())
    }
}

class GamepadSensitivityTest {

    private val actions = RecorderForSensitivity()
    private fun mapper(config: GamepadConfig) = GamepadMapper(actions, config)

    private class RecorderForSensitivity : GamepadActions {
        var gimbal = 0f to 0f
        var zoom = 0f
        override fun gimbal(yaw: Float, pitch: Float) { gimbal = yaw to pitch }
        override fun zoom(rate: Float) { zoom = rate }
        override fun shutter() {}
        override fun togglePhotoVideo() {}
        override fun autofocus() {}
        override fun focusRing(direction: Int) {}
        override fun exposureMode(direction: Int) {}
        override fun recenter() {}
        override fun toggleAeLock() {}
        override fun cycleGrid() {}
    }

    @Test
    fun `gimbal speed scales the whole stick range`() {
        mapper(GamepadConfig(gimbalSpeed = 0.5f)).onAxis(GamepadAxis.LEFT_X, 1f)
        assertEquals(0.5f, actions.gimbal.first, 1e-4f)
    }

    @Test
    fun `zoom speed scales the zoom stick`() {
        mapper(GamepadConfig(zoomSpeed = 0.25f)).onAxis(GamepadAxis.RIGHT_Y, -1f)
        assertEquals(0.25f, actions.zoom, 1e-4f)
    }

    @Test
    fun `the default speeds are full`() {
        mapper(GamepadConfig()).onAxis(GamepadAxis.LEFT_X, 1f)
        assertEquals(1f, actions.gimbal.first, 1e-4f)
    }

    @Test
    fun `a linear response curve gives more at half deflection than the default curve`() {
        mapper(GamepadConfig(expo = 1f)).onAxis(GamepadAxis.LEFT_X, 0.575f) // half way past the 0.15 dead zone
        assertEquals(0.5f, actions.gimbal.first, 1e-3f)
        val curved = RecorderForSensitivity()
        GamepadMapper(curved, GamepadConfig(expo = 2f)).onAxis(GamepadAxis.LEFT_X, 0.575f)
        assertEquals(0.25f, curved.gimbal.first, 1e-3f)
    }

    @Test
    fun `the settings can be changed while the pad is in use`() {
        val m = mapper(GamepadConfig())
        m.onAxis(GamepadAxis.LEFT_X, 1f)
        assertEquals(1f, actions.gimbal.first, 1e-4f)
        m.config = GamepadConfig(gimbalSpeed = 0.4f)
        m.onAxis(GamepadAxis.LEFT_X, 0.99f)
        m.onAxis(GamepadAxis.LEFT_X, 1f)
        assertEquals(0.4f, actions.gimbal.first, 1e-4f)
    }

    @Test
    fun `settings survive being stored and read back`() {
        val c = GamepadConfig(deadzone = 0.2f, expo = 1.5f, gimbalSpeed = 0.6f, zoomSpeed = 0.3f, invertPitch = false)
        assertEquals(c, GamepadConfig.decode(c.encode()))
    }

    @Test
    fun `nothing stored, garbage or out of range values give sane settings`() {
        assertEquals(GamepadConfig(), GamepadConfig.decode(null))
        assertEquals(GamepadConfig(), GamepadConfig.decode("zzz"))
        val wild = GamepadConfig.decode("5.0,9.0,7.0,-3.0,true")
        assertTrue(wild.deadzone <= GamepadConfig.MAX_DEADZONE)
        assertTrue(wild.gimbalSpeed in GamepadConfig.MIN_SPEED..1f)
        assertTrue(wild.zoomSpeed in GamepadConfig.MIN_SPEED..1f)
        assertTrue(wild.expo in 1f..3f)
    }
}

class GamepadSettingStepTest {

    private val base = GamepadConfig()

    @Test
    fun `speeds step in tens of percent and stop at the ends`() {
        assertEquals(0.9f, base.adjusted(GamepadSetting.GIMBAL_SPEED, -1).gimbalSpeed, 1e-4f)
        assertEquals(1f, base.adjusted(GamepadSetting.GIMBAL_SPEED, +1).gimbalSpeed, 1e-4f)
        var c = base
        repeat(30) { c = c.adjusted(GamepadSetting.ZOOM_SPEED, -1) }
        assertEquals(GamepadConfig.MIN_SPEED, c.zoomSpeed, 1e-4f)
    }

    @Test
    fun `the dead zone steps in five percent from 5 to 50`() {
        assertEquals(0.20f, base.adjusted(GamepadSetting.DEADZONE, +1).deadzone, 1e-4f)
        assertEquals(0.10f, base.adjusted(GamepadSetting.DEADZONE, -1).deadzone, 1e-4f)
        var c = base
        repeat(20) { c = c.adjusted(GamepadSetting.DEADZONE, +1) }
        assertEquals(GamepadConfig.MAX_DEADZONE, c.deadzone, 1e-4f)
    }

    @Test
    fun `the response curve steps from linear to strongly curved in halves`() {
        assertEquals(2.5f, base.adjusted(GamepadSetting.RESPONSE, +1).expo, 1e-4f)
        assertEquals(1.5f, base.adjusted(GamepadSetting.RESPONSE, -1).expo, 1e-4f)
        assertEquals(1f, base.copy(expo = 1f).adjusted(GamepadSetting.RESPONSE, -1).expo, 1e-4f)
        assertEquals(3f, base.copy(expo = 3f).adjusted(GamepadSetting.RESPONSE, +1).expo, 1e-4f)
    }

    @Test
    fun `invert tilt flips whichever way it is pressed`() {
        assertEquals(false, base.adjusted(GamepadSetting.INVERT_TILT, +1).invertPitch)
        assertEquals(true, base.adjusted(GamepadSetting.INVERT_TILT, -1).copy(invertPitch = false).adjusted(GamepadSetting.INVERT_TILT, +1).invertPitch)
    }

    @Test
    fun `values read out the way a person would say them`() {
        assertEquals("100%", base.display(GamepadSetting.GIMBAL_SPEED))
        assertEquals("60%", base.copy(zoomSpeed = 0.6f).display(GamepadSetting.ZOOM_SPEED))
        assertEquals("15%", base.display(GamepadSetting.DEADZONE))
        assertEquals("Curved", base.display(GamepadSetting.RESPONSE))
        assertEquals("Linear", base.copy(expo = 1f).display(GamepadSetting.RESPONSE))
        assertEquals("Soft", base.copy(expo = 1.5f).display(GamepadSetting.RESPONSE))
        assertEquals("Strong", base.copy(expo = 3f).display(GamepadSetting.RESPONSE))
        assertEquals("Up = tilt down", base.display(GamepadSetting.INVERT_TILT))
        assertEquals("Up = tilt up", base.copy(invertPitch = false).display(GamepadSetting.INVERT_TILT))
    }

    @Test
    fun `an off-ladder value steps to its neighbour`() {
        assertEquals(0.5f, base.copy(gimbalSpeed = 0.43f).adjusted(GamepadSetting.GIMBAL_SPEED, +1).gimbalSpeed, 1e-4f)
        assertEquals(0.4f, base.copy(gimbalSpeed = 0.43f).adjusted(GamepadSetting.GIMBAL_SPEED, -1).gimbalSpeed, 1e-4f)
    }
}
