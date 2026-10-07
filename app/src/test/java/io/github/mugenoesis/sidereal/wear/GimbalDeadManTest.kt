package io.github.mugenoesis.sidereal.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalDeadManTest {

    @Test
    fun `a turning gimbal that stops hearing from the watch is stopped`() {
        val d = GimbalDeadMan(timeoutMs = 600)
        d.onRate(0, 0.8f, 0f)
        assertFalse(d.shouldStop(500))
        assertTrue(d.shouldStop(700))
    }

    @Test
    fun `it says stop once, not on every check`() {
        val d = GimbalDeadMan(600)
        d.onRate(0, 0f, 1f)
        assertTrue(d.shouldStop(1_000))
        assertFalse(d.shouldStop(1_200))
    }

    @Test
    fun `a steady stream of drag updates keeps it going`() {
        val d = GimbalDeadMan(600)
        var t = 0L
        repeat(30) { d.onRate(t, 0.5f, 0.2f); t += 100; assertFalse(d.shouldStop(t)) }
    }

    @Test
    fun `an explicit stop disarms it`() {
        val d = GimbalDeadMan(600)
        d.onRate(0, 0.5f, 0f)
        d.onRate(100, 0f, 0f)
        assertFalse(d.shouldStop(5_000))
    }

    @Test
    fun `nothing ever turning means nothing to stop`() {
        assertFalse(GimbalDeadMan(600).shouldStop(10_000))
    }

    @Test
    fun `after being stopped a new drag arms it again`() {
        val d = GimbalDeadMan(600)
        d.onRate(0, 1f, 0f)
        assertTrue(d.shouldStop(1_000))
        d.onRate(2_000, 0f, -1f)
        assertTrue(d.shouldStop(3_000))
    }
}
