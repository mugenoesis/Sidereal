package io.github.mugenoesis.sidereal.gimbal

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldPowerTest {

    private fun power(
        onSend: (HandheldPowerState, HandheldPower) -> Unit = { _, _ -> },
        sendError: String? = null
    ): Pair<HandheldPower, MutableList<HandheldPowerState>> {
        val sent = mutableListOf<HandheldPowerState>()
        lateinit var p: HandheldPower
        p = HandheldPower(send = { state, done -> sent += state; onSend(state, p); done(sendError) }, pollMs = 5, settleAfterWakeMs = 0)
        return p to sent
    }

    @Test fun `the state follows what the handle reports`() {
        val (p, _) = power()
        assertEquals(HandheldPowerState.UNKNOWN, p.state.value)
        p.onPush("ON")
        assertEquals(HandheldPowerState.ON, p.state.value)
        p.onPush("SLEEPING")
        assertEquals(HandheldPowerState.SLEEPING, p.state.value)
        p.onPush("nonsense")
        assertEquals(HandheldPowerState.UNKNOWN, p.state.value)
    }

    @Test fun `awake or never heard from needs no wake`() = runBlocking {
        val (p, sent) = power()
        assertTrue(p.ensureAwake(timeoutMs = 100))
        p.onPush("ON")
        assertTrue(p.ensureAwake(timeoutMs = 100))
        assertTrue(sent.isEmpty())
    }

    @Test fun `a sleeping handle is woken and the call returns once it reports ON`() = runBlocking {
        val (p, sent) = power(onSend = { state, handle -> if (state == HandheldPowerState.ON) handle.onPush("ON") })
        p.onPush("SLEEPING")
        assertTrue(p.ensureAwake(timeoutMs = 500))
        assertEquals(listOf(HandheldPowerState.ON), sent)
        assertEquals(HandheldPowerState.ON, p.state.value)
    }

    @Test fun `a handle that never wakes is retried once and then reported`() = runBlocking {
        val (p, sent) = power()
        p.onPush("SLEEPING")
        assertFalse(p.ensureAwake(timeoutMs = 80))
        assertEquals(2, sent.size)
    }

    @Test fun `a refused wake command is not waited out`() = runBlocking {
        val (p, sent) = power(sendError = "busy")
        p.onPush("SLEEPING")
        assertFalse(p.ensureAwake(timeoutMs = 2_000))
        assertTrue(sent.size in 1..2)
    }

    @Test fun `the wake is reported to the listener so the pose can be restored`() = runBlocking {
        val (p, _) = power(onSend = { state, handle -> if (state == HandheldPowerState.ON) handle.onPush("ON") })
        var woken = 0
        p.onWoken = { woken++ }
        p.onPush("SLEEPING")
        p.ensureAwake(timeoutMs = 500)
        assertEquals(1, woken)
    }

    @Test fun `a wake the user did by the handle is also reported`() {
        val (p, _) = power()
        var woken = 0
        p.onWoken = { woken++ }
        p.onPush("ON")
        p.onPush("SLEEPING")
        p.onPush("ON")
        assertEquals(1, woken)
    }

    @Test fun `the camera counts as still waking for a while after the handle wakes`() {
        var now = 1_000L
        val p = HandheldPower(send = { _, done -> done(null) }, pollMs = 5, settleAfterWakeMs = 0, clock = { now })
        assertFalse(p.recentlyWoken(8_000))
        p.onPush("ON")
        assertFalse(p.recentlyWoken(8_000)) // was never asleep
        p.onPush("SLEEPING")
        now = 5_000
        p.onPush("ON")
        now = 9_000
        assertTrue(p.recentlyWoken(8_000))
        now = 13_001
        assertFalse(p.recentlyWoken(8_000))
    }
}
