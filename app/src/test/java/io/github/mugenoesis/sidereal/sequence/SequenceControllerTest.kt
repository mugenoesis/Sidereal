package io.github.mugenoesis.sidereal.sequence

import io.github.mugenoesis.sidereal.series.AfterRunProgress
import io.github.mugenoesis.sidereal.series.PostRun
import io.github.mugenoesis.sidereal.series.RunSummary
import io.github.mugenoesis.sidereal.series.SeriesPlan
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

private class ClosableHost : SequenceHost, AutoCloseable {
    var closed = 0
    var gate: CompletableDeferred<Unit>? = null
    override fun nowMs() = 0L
    override suspend fun sleep(ms: Long) { gate?.await() }
    override suspend fun moveTo(pitch: Float, yaw: Float) {}
    override suspend fun capture(exposureMs: Long, label: String) = true
    override suspend fun setShutter(shutterName: String) = true
    override suspend fun awaitUserContinue(message: String) {}
    override fun close() { closed++ }
}

class SequenceControllerTest {

    @Test
    fun `a host that needs cleaning up is closed once the run ends`() {
        val closable = ClosableHost()
        val c = SequenceController(CoroutineScope(Job() + Dispatchers.Unconfined), { closable }, { context }, {}, { null })
        c.setMode(SequenceMode.INTERVALOMETER)
        c.start()
        assertEquals(1, closable.closed)
    }

    @Test
    fun `and also when the run is stopped part way`() {
        val closable = ClosableHost().also { it.gate = CompletableDeferred() }
        val c = SequenceController(CoroutineScope(Job() + Dispatchers.Unconfined), { closable }, { context }, {}, { null })
        c.setMode(SequenceMode.INTERVALOMETER)
        c.start()
        assertTrue(c.isRunning.value)
        c.stop()
        assertEquals(1, closable.closed)
        assertFalse(c.isRunning.value)
    }

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

    // --- after the run: downloading / stitching / video ---

    private class RecordingPostRun(val result: String? = "Saved", val gate: CompletableDeferred<Unit>? = null) : PostRun {
        var calls = 0
        var plan: SeriesPlan? = null
        var run: RunSummary? = null
        override suspend fun run(plan: SeriesPlan, run: RunSummary, report: (AfterRunProgress) -> Unit): String? {
            calls++
            this.plan = plan
            this.run = run
            report(AfterRunProgress("Downloading", 1, 4))
            gate?.await()
            return result
        }
    }

    private fun controllerWithPost(post: PostRun?, clock: () -> Long = { 1_000L }): SequenceController {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        return SequenceController(
            scope, { prompt -> ControllerFakeHost(prompt).also { host = it } }, { context }, {}, { null },
            postRun = post, wallClock = clock
        )
    }

    @Test
    fun `a finished run with something to download hands its photos to the post run`() {
        val post = RecordingPostRun()
        val c = controllerWithPost(post)
        c.setMode(SequenceMode.INTERVALOMETER)
        c.adjust("frames", -1)
        c.start()
        assertEquals(1, post.calls)
        assertEquals(SequenceMode.INTERVALOMETER, post.plan!!.mode)
        assertEquals(15, post.run!!.capturesDone)
        assertEquals(15, post.plan!!.tags.size)
        assertEquals("Saved", c.message.value)
        assertFalse(c.isRunning.value)
        assertNull(c.afterRun.value)
    }

    @Test
    fun `the run stays locked while the post run works and shows its progress`() {
        val gate = CompletableDeferred<Unit>()
        val c = controllerWithPost(RecordingPostRun(gate = gate))
        c.setMode(SequenceMode.INTERVALOMETER)
        c.start()
        assertTrue(c.isRunning.value)
        assertEquals(AfterRunProgress("Downloading", 1, 4), c.afterRun.value)
        gate.complete(Unit)
        assertFalse(c.isRunning.value)
        assertNull(c.afterRun.value)
    }

    @Test
    fun `nothing is downloaded when the mode's options say not to`() {
        val post = RecordingPostRun()
        val c = controllerWithPost(post)
        c.setMode(SequenceMode.INTERVALOMETER)
        c.adjust("saveFrames", +1)
        c.start()
        assertEquals(0, post.calls)
    }

    @Test
    fun `a timelapse downloads nothing by default`() {
        val post = RecordingPostRun()
        val c = controllerWithPost(post)
        c.setMode(SequenceMode.TIMELAPSE)
        c.adjust("durationMin", -1)
        c.start()
        assertEquals(0, post.calls)
    }

    @Test
    fun `a stopped run is not downloaded`() {
        val post = RecordingPostRun()
        val c = controllerWithPost(post)
        c.setMode(SequenceMode.INTERVALOMETER)
        host = null
        c.start()
        // finish normally first, then check a cancelled one separately
        val closable = ClosableHost().also { it.gate = CompletableDeferred() }
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val post2 = RecordingPostRun()
        val c2 = SequenceController(scope, { closable }, { context }, {}, { null }, postRun = post2)
        c2.start()
        c2.stop()
        assertEquals(0, post2.calls)
    }

    @Test
    fun `the run span handed over is the wall clock time from start to the last photo`() {
        var now = 10_000L
        val post = RecordingPostRun()
        val c = controllerWithPost(post, clock = { now.also { now += 5_000L } })
        c.setMode(SequenceMode.INTERVALOMETER)
        c.start()
        assertEquals(10_000L, post.run!!.startedAtMs)
        assertEquals(5_000L, post.run!!.spanMs)
    }

    @Test
    fun `a post run that reports nothing leaves the message empty`() {
        val c = controllerWithPost(RecordingPostRun(result = null))
        c.setMode(SequenceMode.INTERVALOMETER)
        c.start()
        assertNull(c.message.value)
    }
}
