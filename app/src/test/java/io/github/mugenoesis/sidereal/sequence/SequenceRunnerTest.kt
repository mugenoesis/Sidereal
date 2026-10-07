package io.github.mugenoesis.sidereal.sequence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host with a virtual clock - sleeping advances time instantly, so a 10-minute sequence tests in milliseconds. */
private class FakeHost : SequenceHost {
    var clock = 0L
    val events = mutableListOf<String>()
    val captureTimes = mutableListOf<Long>()
    var captureResults = ArrayDeque<Boolean>()
    var setShutterOk = true
    var blockSleepOn: CompletableDeferred<Unit>? = null

    /** The camera link is "up" while clock is outside every [downFrom, downUntil) window. */
    val outages = mutableListOf<LongRange>()
    override fun isCameraReachable() = outages.none { clock in it }

    override fun nowMs() = clock

    override suspend fun sleep(ms: Long) {
        events += "sleep($ms)"
        blockSleepOn?.let { blockSleepOn = null; it.await() }
        clock += ms
    }

    override suspend fun moveTo(pitch: Float, yaw: Float) {
        events += "move($pitch,$yaw)"
    }

    override suspend fun capture(exposureMs: Long, label: String): Boolean {
        val ok = isCameraReachable() && (captureResults.removeFirstOrNull() ?: true)
        events += "capture($label,${if (ok) "ok" else "fail"})"
        if (ok) captureTimes += clock
        clock += exposureMs
        return ok
    }

    override suspend fun setShutter(shutterName: String): Boolean {
        events += "shutter($shutterName)"
        return setShutterOk
    }

    override suspend fun awaitUserContinue(message: String) {
        events += "prompt($message)"
    }

    override suspend fun beginRamp(config: RampConfig) {
        events += "beginRamp(${config.keepDarkFraction})"
    }

    override suspend fun adaptExposure(): String? {
        events += "adapt"
        return "adapted"
    }
}

class SequenceRunnerTest {

    private fun plan(frames: Int, intervalMs: Long = 10_000, exposureMs: Long = 2_000, settleMs: Long = 1_000, hold: Attitude? = null) =
        IntervalPlanner.plan(IntervalConfig(frames, intervalMs, settleMs, exposureMs, hold))

    @Test
    fun `captures start on their interval slots, measured start to start`() = runBlocking {
        val host = FakeHost()
        SequenceRunner(host).run(plan(frames = 3))
        // slot at 0/10s/20s, plus the 1s settle before each shutter.
        assertEquals(listOf(1_000L, 11_000L, 21_000L), host.captureTimes)
    }

    @Test
    fun `a slow frame is not skipped and the next one starts immediately instead of waiting`() = runBlocking {
        val host = FakeHost()
        // exposure 15s > interval 10s: every frame overruns its slot.
        SequenceRunner(host).run(plan(frames = 3, intervalMs = 10_000, exposureMs = 15_000))
        assertEquals(3, host.captureTimes.size)
        assertEquals(listOf(1_000L, 17_000L, 33_000L), host.captureTimes)
    }

    @Test
    fun `gimbal is moved and settled before the shutter fires`() = runBlocking {
        val host = FakeHost()
        SequenceRunner(host).run(plan(frames = 1, hold = Attitude(-5f, 30f)))
        val events = host.events.filter { !it.startsWith("sleep(0") }
        val move = events.indexOf("move(-5.0,30.0)")
        val settle = events.indexOf("sleep(1000)")
        val capture = events.indexOfFirst { it.startsWith("capture") }
        assertTrue("move=$move settle=$settle capture=$capture in $events", move in 0 until settle && settle < capture)
    }

    @Test
    fun `progress ends Done with every capture counted`() = runBlocking {
        val host = FakeHost()
        val runner = SequenceRunner(host)
        assertEquals(SequenceState.Idle, runner.progress.value.state)
        runner.run(plan(frames = 4))
        assertEquals(SequenceState.Done, runner.progress.value.state)
        assertEquals(4, runner.progress.value.capturesDone)
        assertEquals(4, runner.progress.value.capturesTotal)
    }

    @Test
    fun `a failed capture is retried and the sequence carries on`() = runBlocking {
        val host = FakeHost().apply { captureResults = ArrayDeque(listOf(true, false, true, true)) }
        val runner = SequenceRunner(host)
        runner.run(plan(frames = 3))
        assertEquals(SequenceState.Done, runner.progress.value.state)
        assertEquals(3, host.captureTimes.size)
        assertEquals(1, runner.progress.value.retries)
    }

    @Test
    fun `a capture that keeps failing aborts the sequence and says why`() = runBlocking {
        val host = FakeHost().apply { captureResults = ArrayDeque(listOf(true, false, false, false, true, true)) }
        val runner = SequenceRunner(host)
        runner.run(plan(frames = 4))
        val state = runner.progress.value.state
        assertTrue("state=$state", state is SequenceState.Failed)
        assertEquals(1, runner.progress.value.capturesDone)
        assertEquals(1, host.captureTimes.size)
    }

    @Test
    fun `prompt steps hand the message to the host and wait for it before moving on`() = runBlocking {
        val host = FakeHost()
        SequenceRunner(host).run(CalibrationPlanner.darks(count = 2, exposureMs = 1_000))
        val firstPrompt = host.events.indexOfFirst { it.startsWith("prompt(") }
        val firstCapture = host.events.indexOfFirst { it.startsWith("capture(") }
        assertTrue(firstPrompt in 0 until firstCapture)
    }

    @Test
    fun `state is AwaitingUser while a prompt is open`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<SequenceState>()
        val host = object : SequenceHost by FakeHost() {
            override suspend fun awaitUserContinue(message: String) {
                gate.await()
            }
        }
        val runner = SequenceRunner(host)
        val job = launch { runner.run(CalibrationPlanner.darks(1, 100)) }
        yield()
        seen += runner.progress.value.state
        gate.complete(Unit)
        job.join()
        assertTrue("seen=$seen", seen.first() is SequenceState.AwaitingUser)
        assertEquals(SequenceState.Done, runner.progress.value.state)
    }

    @Test
    fun `cancelling mid sequence stops further captures and reports Cancelled`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val host = FakeHost().apply { blockSleepOn = gate }
        val runner = SequenceRunner(host)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runner.run(plan(frames = 5)) }
        yield()
        job.cancelAndJoin()
        assertEquals(SequenceState.Cancelled, runner.progress.value.state)
        assertTrue(host.captureTimes.isEmpty())
    }

    @Test
    fun `shutter changes go through the host and a rejection fails the sequence`() = runBlocking {
        val host = FakeHost().apply { setShutterOk = false }
        val runner = SequenceRunner(host)
        runner.run(CalibrationPlanner.bias(count = 3, restoreShutter = null))
        assertTrue(runner.progress.value.state is SequenceState.Failed)
        assertTrue(host.captureTimes.isEmpty())
    }

    @Test
    fun `capturesTotal counts only capture steps`() = runBlocking {
        val runner = SequenceRunner(FakeHost())
        runner.run(CalibrationPlanner.bias(count = 6, restoreShutter = "SHUTTER_SPEED_1_2"))
        assertEquals(6, runner.progress.value.capturesTotal)
    }

    @Test
    fun `a progress listener sees the count climb`() = runBlocking {
        val host = FakeHost()
        val runner = SequenceRunner(host)
        val counts = mutableListOf<Int>()
        runner.onProgress = { counts += it.capturesDone }
        runner.run(plan(frames = 3))
        assertTrue(counts.containsAll(listOf(1, 2, 3)))
        assertEquals(counts.sorted(), counts)
    }

    @Test
    fun `a capture failing because the camera link dropped waits for it and then carries on`() = runBlocking {
        val host = FakeHost()
        host.outages += 500L..40_000L // drops just before the first shot (1 s in), back after 40 s
        val runner = SequenceRunner(host)
        runner.run(plan(frames = 2))
        assertEquals(SequenceState.Done, runner.progress.value.state)
        assertEquals(2, runner.progress.value.capturesDone)
        assertTrue("first shot only after the link returned: ${host.captureTimes}", host.captureTimes.first() >= 40_000L)
        assertEquals("a dropped link is not a retry", 0, runner.progress.value.retries)
    }

    @Test
    fun `progress says it is waiting for the camera during an outage and clears afterwards`() = runBlocking {
        val host = FakeHost()
        host.outages += 500L..20_000L
        val runner = SequenceRunner(host)
        val seen = mutableListOf<Boolean>()
        runner.onProgress = { seen += it.waitingForCamera }
        runner.run(plan(frames = 1))
        assertTrue("never reported waiting: $seen", seen.any { it })
        assertEquals(false, runner.progress.value.waitingForCamera)
    }

    @Test
    fun `a camera that never comes back fails the sequence after the patience runs out and says so`() = runBlocking {
        val host = FakeHost()
        host.outages += 500L..Long.MAX_VALUE / 2
        val runner = SequenceRunner(host, linkPatienceMs = 5 * 60_000)
        runner.run(plan(frames = 3))
        val state = runner.progress.value.state
        assertTrue("$state", state is SequenceState.Failed && state.reason.contains("camera", ignoreCase = true))
        assertTrue("gave up at ${host.clock}", host.clock in 5 * 60_000L..8 * 60_000L)
    }

    @Test
    fun `several separate outages in one sequence are each waited out`() = runBlocking {
        val host = FakeHost()
        host.outages += 500L..30_000L
        host.outages += 100_000L..130_000L
        val runner = SequenceRunner(host)
        runner.run(plan(frames = 15))
        assertEquals(SequenceState.Done, runner.progress.value.state)
        assertEquals(15, runner.progress.value.capturesDone)
    }

    @Test
    fun `a failure with the link up still uses the retry budget`() = runBlocking {
        val host = FakeHost()
        host.captureResults.addAll(listOf(false, false, false))
        val runner = SequenceRunner(host)
        runner.run(plan(frames = 1))
        assertTrue(runner.progress.value.state is SequenceState.Failed)
    }

    @Test
    fun `ramp steps reach the host in order, and the summary shows in progress`() = runBlocking {
        val host = FakeHost()
        val runner = SequenceRunner(host)
        runner.run(listOf(
            SequenceStep.BeginRamp(RampConfig(keepDarkFraction = 0.25, maxShutterSec = 2.0, maxIso = 800)),
            SequenceStep.AdaptExposure,
            SequenceStep.Capture(500, "light")
        ))
        assertEquals(listOf("beginRamp(0.25)", "adapt", "capture(light,ok)"), host.events.filter { !it.startsWith("sleep") })
        assertEquals("adapted", runner.progress.value.exposureSummary)
    }
}
