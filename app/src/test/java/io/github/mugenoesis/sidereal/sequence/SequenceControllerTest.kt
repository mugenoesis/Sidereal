package io.github.mugenoesis.sidereal.sequence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ControllerFakeHost(private val onPromptHook: suspend (String) -> Unit) : SequenceHost {
    var clock = 0L
    var captures = 0
    var failCaptures = false
    var gate: CompletableDeferred<Unit>? = null
    override fun nowMs() = clock
    override suspend fun sleep(ms: Long) { gate?.await(); clock += ms }
    override suspend fun moveTo(pitch: Float, yaw: Float) {}
    override suspend fun capture(exposureMs: Long, label: String): Boolean {
        if (failCaptures) return false
        captures++; clock += exposureMs; return true
    }
    override suspend fun setShutter(shutterName: String) = true
    override suspend fun awaitUserContinue(message: String) = onPromptHook(message)
}

class SequenceControllerTest {

    private val context = ShootContext(exposureMs = 100, shutterName = "SHUTTER_SPEED_1_10", attitude = Attitude(0f, 0f))
    private var host: ControllerFakeHost? = null

    private fun controller(
        provider: () -> ShootContext? = { context },
        prepare: suspend () -> Unit = {},
        precondition: () -> String? = { null }
    ): SequenceController {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        return SequenceController(scope, { prompt -> ControllerFakeHost(prompt).also { host = it } }, provider, prepare, precondition)
    }

    @Test
    fun `start runs the plan for the current settings to completion`() {
        val c = controller()
        c.setMode(SequenceMode.INTERVALOMETER)
        c.adjust("frames", -1) // 20 -> 15
        c.start()
        assertEquals(SequenceState.Done, c.progress.value.state)
        assertEquals(15, host!!.captures)
        assertFalse(c.isRunning.value)
    }

    @Test
    fun `changing the mode switches the fields`() {
        val c = controller()
        c.setMode(SequenceMode.PANORAMA)
        assertEquals(SequenceMode.PANORAMA, c.settings.value.mode)
    }

    @Test
    fun `adjust updates the settings flow`() {
        val c = controller()
        val before = c.settings.value.intervalSec
        c.adjust("intervalSec", +1)
        assertTrue(c.settings.value.intervalSec > before)
    }

    @Test
    fun `starting without a camera context reports why and does not run`() {
        val c = controller(provider = { null })
        c.start()
        assertEquals(SequenceState.Idle, c.progress.value.state)
        assertTrue(c.message.value!!.contains("connected", ignoreCase = true))
    }

    @Test
    fun `an unbuildable plan surfaces its error message`() {
        val c = controller(provider = { context.copy(attitude = null) })
        c.setMode(SequenceMode.PANORAMA)
        c.start()
        assertEquals(SequenceState.Idle, c.progress.value.state)
        assertTrue(c.message.value!!.contains("gimbal", ignoreCase = true))
    }

    @Test
    fun `a new start clears the previous message`() {
        var ctx: ShootContext? = null
        val c = controller(provider = { ctx })
        c.start()
        assertTrue(c.message.value != null)
        ctx = context
        c.adjust("frames", -1)
        c.start()
        assertNull(c.message.value)
    }

    @Test
    fun `prompt steps pause the run and publish their text until continued`() {
        val c = controller()
        c.setMode(SequenceMode.DARKS)
        c.adjust("calFrames", -1)
        // Hold the run open at the prompt by not auto-continuing: the fake host awaits our controller's own gate.
        c.start()
        // Controller-owned prompt: with Unconfined dispatch the run is parked at the prompt right now.
        assertTrue(c.progress.value.state is SequenceState.AwaitingUser)
        assertTrue(c.prompt.value!!.contains("cap", ignoreCase = true))
        assertTrue(c.isRunning.value)
        c.continueFromPrompt()
        assertNull(c.prompt.value)
        assertEquals(SequenceState.Done, c.progress.value.state)
    }

    @Test
    fun `stop cancels a running sequence`() {
        val c = controller()
        c.setMode(SequenceMode.DARKS)
        c.start()
        assertTrue(c.isRunning.value)
        c.stop()
        assertEquals(SequenceState.Cancelled, c.progress.value.state)
        assertFalse(c.isRunning.value)
        assertNull(c.prompt.value)
    }

    @Test
    fun `a second start while running is ignored`() {
        val c = controller()
        c.setMode(SequenceMode.DARKS)
        c.start()
        val firstHost = host
        c.start()
        assertTrue(firstHost === host)
    }

    @Test
    fun `a failing camera ends in Failed with the reason in the message`() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val c = SequenceController(scope, { p -> ControllerFakeHost(p).also { it.failCaptures = true } }, { context }, {})
        c.adjust("frames", -1)
        c.start()
        assertTrue(c.progress.value.state is SequenceState.Failed)
        assertTrue(c.message.value!!.isNotBlank())
        assertFalse(c.isRunning.value)
    }

    @Test
    fun `prepare runs before the first step`() {
        var prepared = false
        val c = controller(prepare = { prepared = true })
        c.start()
        assertTrue(prepared)
    }

    @Test
    fun `preview describes the plan for the current settings`() {
        val c = controller()
        c.adjust("frames", -1)
        val preview = c.preview()
        assertTrue(preview is PlanResult.Ok)
        assertEquals(15, (preview as PlanResult.Ok).plan.captures)
    }

    @Test
    fun `preview without a camera says so`() {
        val c = controller(provider = { null })
        assertTrue(c.preview() is PlanResult.Error)
    }

    @Test
    fun `a failed precondition blocks the start and shows its reason`() {
        val c = controller(precondition = { "Stop recording first" })
        c.start()
        assertEquals(SequenceState.Idle, c.progress.value.state)
        assertEquals("Stop recording first", c.message.value)
        assertFalse(c.isRunning.value)
        assertNull(host)
    }

    @Test
    fun `a passing precondition lets the run go ahead`() {
        val c = controller(precondition = { null })
        c.adjust("frames", -1)
        c.start()
        assertEquals(SequenceState.Done, c.progress.value.state)
    }
}
