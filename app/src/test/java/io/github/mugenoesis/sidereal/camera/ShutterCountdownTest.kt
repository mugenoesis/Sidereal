package io.github.mugenoesis.sidereal.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterCountdownTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Test
    fun `counts down each second then fires once`() {
        val ticks = ArrayList<Int>()
        var fired = 0
        val c = ShutterCountdown(scope, sleep = { })
        c.onTick = { ticks += it }
        c.start(3) { fired++ }
        assertEquals(listOf(3, 2, 1), ticks)
        assertEquals(1, fired)
        assertNull(c.remaining.value)
    }

    @Test
    fun `waits a full second between ticks`() {
        val sleeps = ArrayList<Long>()
        val c = ShutterCountdown(scope, sleep = { sleeps += it })
        c.start(2) { }
        assertEquals(listOf(1000L, 1000L), sleeps)
    }

    @Test
    fun `cancelling mid countdown never fires`() {
        val gate = CompletableDeferred<Unit>()
        var fired = 0
        val c = ShutterCountdown(scope, sleep = { gate.await() })
        c.start(5) { fired++ }
        assertTrue(c.isRunning)
        c.cancel()
        gate.complete(Unit)
        assertEquals(0, fired)
        assertFalse(c.isRunning)
        assertNull(c.remaining.value)
    }

    @Test
    fun `remaining shows the seconds left while counting`() {
        val gate = CompletableDeferred<Unit>()
        val c = ShutterCountdown(scope, sleep = { gate.await() })
        c.start(4) { }
        assertEquals(4, c.remaining.value)
        c.cancel()
    }

    @Test
    fun `starting again while counting restarts rather than stacking two countdowns`() {
        val gate = CompletableDeferred<Unit>()
        var fired = 0
        val c = ShutterCountdown(scope, sleep = { gate.await() })
        c.start(5) { fired += 10 }
        c.start(2) { fired += 1 }
        gate.complete(Unit)
        assertEquals(1, fired)
    }

    @Test
    fun `timer options cycle off 2 5 10 and back`() {
        assertEquals(2, TimerOptions.next(0))
        assertEquals(5, TimerOptions.next(2))
        assertEquals(10, TimerOptions.next(5))
        assertEquals(0, TimerOptions.next(10))
        assertEquals(0, TimerOptions.next(7)) // unknown value falls back to off
    }

    @Test
    fun `timer labels`() {
        assertEquals("Off", TimerOptions.label(0))
        assertEquals("10s", TimerOptions.label(10))
    }
}
