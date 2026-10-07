package io.github.mugenoesis.sidereal.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private class Recorder : GamepadActions {
    val events = mutableListOf<String>()
    var lastGimbal = 0f to 0f
    var lastZoom = 0f
    override fun gimbal(yaw: Float, pitch: Float) { lastGimbal = yaw to pitch; events += "gimbal(%.3f,%.3f)".format(yaw, pitch) }
    override fun zoom(rate: Float) { lastZoom = rate; events += "zoom(%.3f)".format(rate) }
    override fun shutter() { events += "shutter" }
    override fun togglePhotoVideo() { events += "mode" }
    override fun autofocus() { events += "af" }
    override fun focusRing(direction: Int) { events += "focus($direction)" }
    override fun exposureMode(direction: Int) { events += "exposure($direction)" }
    override fun recenter() { events += "recenter" }
    override fun toggleAeLock() { events += "ae" }
    override fun cycleGrid() { events += "grid" }
}

class GamepadMapperTest {

    private val actions = Recorder()
    private val mapper = GamepadMapper(actions)

    @Test
    fun `a stick inside the dead zone does nothing`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 0.1f)
        mapper.onAxis(GamepadAxis.LEFT_Y, 0.05f)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `the dead zone is round, not square`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 0.1f)
        mapper.onAxis(GamepadAxis.LEFT_Y, 0.1f)  // magnitude 0.141 < 0.15
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `full right is full yaw`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        assertEquals(1f, actions.lastGimbal.first, 1e-4f)
        assertEquals(0f, actions.lastGimbal.second, 1e-4f)
    }

    @Test
    fun `pushing the stick up tilts the camera down by default - up and down are inverted`() {
        // Android reports stick-up as negative Y; the owner prefers it inverted.
        mapper.onAxis(GamepadAxis.LEFT_Y, -1f)
        assertEquals(-1f, actions.lastGimbal.second, 1e-4f)
        mapper.onAxis(GamepadAxis.LEFT_Y, 1f)
        assertEquals(1f, actions.lastGimbal.second, 1e-4f)
    }

    @Test
    fun `turning the inversion off gives the usual stick-up-tilts-up`() {
        val normal = GamepadMapper(actions, GamepadConfig(invertPitch = false))
        normal.onAxis(GamepadAxis.LEFT_Y, -1f)
        assertEquals(1f, actions.lastGimbal.second, 1e-4f)
    }

    @Test
    fun `only the gimbal is inverted - the zoom stick still zooms in when pushed up`() {
        mapper.onAxis(GamepadAxis.RIGHT_Y, -1f)
        assertEquals(1f, actions.lastZoom, 1e-4f)
    }

    @Test
    fun `half deflection is gentle - rescaled past the dead zone then squared for fine control`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 0.5f)
        val rescaled = (0.5f - 0.15f) / 0.85f
        assertEquals(rescaled * rescaled, actions.lastGimbal.first, 1e-3f)
    }

    @Test
    fun `both axes combine`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        mapper.onAxis(GamepadAxis.LEFT_Y, 1f)
        assertEquals(1f, actions.lastGimbal.first, 1e-4f)
        assertEquals(1f, actions.lastGimbal.second, 1e-3f)
    }

    @Test
    fun `releasing the stick sends one stop and then goes quiet`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        actions.events.clear()
        mapper.onAxis(GamepadAxis.LEFT_X, 0f)
        mapper.onAxis(GamepadAxis.LEFT_X, 0.02f)
        assertEquals(listOf("gimbal(0.000,0.000)"), actions.events)
    }

    @Test
    fun `an unchanged stick position is not re-sent`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 0.8f)
        actions.events.clear()
        mapper.onAxis(GamepadAxis.LEFT_X, 0.8f)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `the right stick zooms - up zooms in - and stops when released`() {
        mapper.onAxis(GamepadAxis.RIGHT_Y, -1f)
        assertEquals(1f, actions.lastZoom, 1e-4f)
        mapper.onAxis(GamepadAxis.RIGHT_Y, 0f)
        assertEquals(0f, actions.lastZoom, 1e-4f)
        assertEquals(2, actions.events.count { it.startsWith("zoom") })
    }

    @Test
    fun `the right trigger fires the shutter once per pull`() {
        mapper.onAxis(GamepadAxis.R2, 0.3f)
        assertTrue(actions.events.isEmpty())
        mapper.onAxis(GamepadAxis.R2, 0.7f)
        mapper.onAxis(GamepadAxis.R2, 1f)
        assertEquals(listOf("shutter"), actions.events)
    }

    @Test
    fun `the trigger must be released below the lower threshold before it can fire again`() {
        mapper.onAxis(GamepadAxis.R2, 1f)
        mapper.onAxis(GamepadAxis.R2, 0.5f)   // between thresholds: still held
        mapper.onAxis(GamepadAxis.R2, 0.7f)
        assertEquals(1, actions.events.count { it == "shutter" })
        mapper.onAxis(GamepadAxis.R2, 0.2f)
        mapper.onAxis(GamepadAxis.R2, 0.9f)
        assertEquals(2, actions.events.count { it == "shutter" })
    }

    @Test
    fun `controllers that report the trigger as a button also work`() {
        mapper.onButton(GamepadButton.R2, true)
        mapper.onButton(GamepadButton.R2, false)
        assertEquals(listOf("shutter"), actions.events)
    }

    @Test
    fun `R1 switches photo and video on press only`() {
        mapper.onButton(GamepadButton.R1, true)
        mapper.onButton(GamepadButton.R1, false)
        assertEquals(listOf("mode"), actions.events)
    }

    @Test
    fun `d-pad left and right step the exposure mode through the hat axis or the buttons`() {
        mapper.onAxis(GamepadAxis.HAT_X, 1f)
        mapper.onAxis(GamepadAxis.HAT_X, 0f)
        mapper.onAxis(GamepadAxis.HAT_X, -1f)
        mapper.onAxis(GamepadAxis.HAT_X, 0f)
        mapper.onButton(GamepadButton.DPAD_RIGHT, true)
        mapper.onButton(GamepadButton.DPAD_RIGHT, false)
        assertEquals(listOf("exposure(1)", "exposure(-1)", "exposure(1)"), actions.events)
    }

    @Test
    fun `A triggers autofocus`() {
        mapper.onButton(GamepadButton.A, true)
        mapper.onButton(GamepadButton.A, false)
        assertEquals(listOf("af"), actions.events)
    }

    @Test
    fun `L1 nudges focus nearer straight away and keeps going while held`() {
        mapper.onButton(GamepadButton.L1, true, nowMs = 0)
        assertEquals(listOf("focus(-1)"), actions.events)
        mapper.tick(299)
        assertEquals(1, actions.events.size)
        mapper.tick(300)
        assertEquals(2, actions.events.size)
        mapper.tick(419)
        assertEquals(2, actions.events.size)
        mapper.tick(420)
        assertEquals(3, actions.events.size)
    }

    @Test
    fun `releasing the focus button stops the repeat`() {
        mapper.onButton(GamepadButton.L2, true, nowMs = 0)
        mapper.onButton(GamepadButton.L2, false, nowMs = 100)
        actions.events.clear()
        mapper.tick(1_000)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `L2 as a trigger axis nudges focus farther`() {
        mapper.onAxis(GamepadAxis.L2, 1f, nowMs = 0)
        assertEquals(listOf("focus(1)"), actions.events)
    }

    @Test
    fun `stick click recentres and X and Y toggle the exposure lock and the grid`() {
        mapper.onButton(GamepadButton.L3, true)
        mapper.onButton(GamepadButton.X, true)
        mapper.onButton(GamepadButton.Y, true)
        assertEquals(listOf("recenter", "ae", "grid"), actions.events)
    }

    @Test
    fun `while locked nothing fires, and locking stops a moving gimbal`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        actions.events.clear()
        mapper.locked = true
        assertEquals(listOf("gimbal(0.000,0.000)"), actions.events)
        actions.events.clear()
        mapper.onButton(GamepadButton.R2, true)
        mapper.onButton(GamepadButton.A, true)
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `unlocking brings the controller back`() {
        mapper.locked = true
        mapper.locked = false
        mapper.onButton(GamepadButton.A, true)
        assertEquals(listOf("af"), actions.events)
    }

    @Test
    fun `losing the controller stops the gimbal and the zoom and any focus repeat`() {
        mapper.onAxis(GamepadAxis.LEFT_X, 1f)
        mapper.onAxis(GamepadAxis.RIGHT_Y, -1f)
        mapper.onButton(GamepadButton.L1, true, nowMs = 0)
        actions.events.clear()
        mapper.onDisconnected()
        assertTrue(actions.events.contains("gimbal(0.000,0.000)"))
        assertTrue(actions.events.contains("zoom(0.000)"))
        actions.events.clear()
        mapper.tick(5_000)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `losing a controller that was idle does not send spurious stops`() {
        mapper.onDisconnected()
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `curve output never exceeds one`() {
        for (v in listOf(0.2f, 0.5f, 0.9f, 1f, 1.5f)) {
            mapper.onAxis(GamepadAxis.LEFT_X, v)
            assertTrue(abs(actions.lastGimbal.first) <= 1f + 1e-6f)
        }
    }

    // --- remapping ---

    private fun remapped(b: GamepadBindings) = GamepadMapper(actions, GamepadConfig(), b)

    @Test
    fun `a remapped button does the new thing and the old one does nothing`() {
        val m = remapped(GamepadBindings.default().with(GamepadButton.A, GamepadAction.SHUTTER))
        m.onButton(GamepadButton.A, true)
        m.onButton(GamepadButton.R2, true)
        assertEquals(listOf("shutter"), actions.events)
    }

    @Test
    fun `the trigger axis follows its binding too`() {
        val m = remapped(GamepadBindings.default().with(GamepadButton.R2, GamepadAction.CYCLE_GRID))
        m.onAxis(GamepadAxis.R2, 1f)
        assertEquals(listOf("grid"), actions.events)
    }

    @Test
    fun `a button bound to focus repeats while held, and stops on release`() {
        val m = remapped(GamepadBindings.default().with(GamepadButton.B, GamepadAction.FOCUS_FARTHER))
        m.onButton(GamepadButton.B, true, nowMs = 0)
        m.tick(300)
        assertEquals(listOf("focus(1)", "focus(1)"), actions.events)
        m.onButton(GamepadButton.B, false, nowMs = 310)
        actions.events.clear()
        m.tick(2_000)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `d-pad up and down can be bound, from the hat or the buttons`() {
        val m = remapped(GamepadBindings.default().with(GamepadButton.DPAD_UP, GamepadAction.AUTOFOCUS).with(GamepadButton.DPAD_DOWN, GamepadAction.TOGGLE_PHOTO_VIDEO))
        m.onAxis(GamepadAxis.HAT_Y, -1f)
        m.onAxis(GamepadAxis.HAT_Y, 0f)
        m.onAxis(GamepadAxis.HAT_Y, 1f)
        m.onAxis(GamepadAxis.HAT_Y, 0f)
        m.onButton(GamepadButton.DPAD_UP, true)
        assertEquals(listOf("af", "mode", "af"), actions.events)
    }

    @Test
    fun `an unbound button does nothing`() {
        mapper.onButton(GamepadButton.B, true)
        mapper.onButton(GamepadButton.START, true)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `bindings can be changed while the pad is in use`() {
        mapper.onButton(GamepadButton.A, true)
        mapper.onButton(GamepadButton.A, false)
        mapper.bindings = GamepadBindings.default().with(GamepadButton.A, GamepadAction.CYCLE_GRID)
        mapper.onButton(GamepadButton.A, true)
        assertEquals(listOf("af", "grid"), actions.events)
    }

    @Test
    fun `exposure mode previous and next can be put on any buttons`() {
        val m = remapped(GamepadBindings.default().with(GamepadButton.START, GamepadAction.EXPOSURE_MODE_NEXT).with(GamepadButton.SELECT, GamepadAction.EXPOSURE_MODE_PREVIOUS))
        m.onButton(GamepadButton.START, true)
        m.onButton(GamepadButton.SELECT, true)
        assertEquals(listOf("exposure(1)", "exposure(-1)"), actions.events)
    }
}
