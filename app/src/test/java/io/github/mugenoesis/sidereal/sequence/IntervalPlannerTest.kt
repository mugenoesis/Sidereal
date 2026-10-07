package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalPlannerTest {

    private fun config(
        frames: Int = 3,
        intervalMs: Long = 10_000,
        settleMs: Long = 1_000,
        exposureMs: Long = 2_000,
        hold: Attitude? = null,
        path: Pair<Attitude, Attitude>? = null,
        dither: DitherConfig? = null
    ) = IntervalConfig(frames, intervalMs, settleMs, exposureMs, hold, path, dither)

    @Test
    fun `plain intervalometer waits for its slot then settles then captures, per frame`() {
        val steps = IntervalPlanner.plan(config())
        assertEquals(
            listOf(
                SequenceStep.WaitUntil(0), SequenceStep.Settle(1_000), SequenceStep.Capture(2_000, "light"),
                SequenceStep.WaitUntil(10_000), SequenceStep.Settle(1_000), SequenceStep.Capture(2_000, "light"),
                SequenceStep.WaitUntil(20_000), SequenceStep.Settle(1_000), SequenceStep.Capture(2_000, "light")
            ),
            steps
        )
    }

    @Test
    fun `no gimbal moves are planned when nothing asks for gimbal motion`() {
        assertTrue(IntervalPlanner.plan(config()).none { it is SequenceStep.MoveTo })
    }

    @Test
    fun `hold attitude is re-commanded before every frame`() {
        val hold = Attitude(-10f, 45f)
        val moves = IntervalPlanner.plan(config(hold = hold)).filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(3, moves.size)
        assertTrue(moves.all { it.pitch == -10f && it.yaw == 45f })
    }

    @Test
    fun `move comes before settle comes before capture inside a frame`() {
        val steps = IntervalPlanner.plan(config(frames = 1, hold = Attitude(0f, 0f)))
        assertEquals(
            listOf(SequenceStep.WaitUntil(0), SequenceStep.MoveTo(0f, 0f), SequenceStep.Settle(1_000), SequenceStep.Capture(2_000, "light")),
            steps
        )
    }

    @Test
    fun `dither moves each frame to base plus its offset, first frame exactly on base`() {
        val dither = DitherConfig(0.1f, 0.3f, 5L)
        val hold = Attitude(-5f, 100f)
        val moves = IntervalPlanner.plan(config(frames = 6, hold = hold, dither = dither)).filterIsInstance<SequenceStep.MoveTo>()
        val offsets = DitherGenerator.offsets(dither, 6)
        assertEquals(6, moves.size)
        assertEquals(hold.pitch, moves[0].pitch, 1e-6f)
        assertEquals(hold.yaw, moves[0].yaw, 1e-6f)
        for (i in moves.indices) {
            assertEquals(hold.pitch + offsets[i].pitch, moves[i].pitch, 1e-5f)
            assertEquals(hold.yaw + offsets[i].yaw, moves[i].yaw, 1e-5f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dither without a base attitude is rejected`() {
        IntervalPlanner.plan(config(dither = DitherConfig(0.1f, 0.3f, 1L)))
    }

    @Test
    fun `motion timelapse path starts exactly at A and finishes exactly at B`() {
        val a = Attitude(-20f, -30f)
        val b = Attitude(10f, 60f)
        val moves = IntervalPlanner.plan(config(frames = 5, path = a to b)).filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(a.pitch, moves.first().pitch, 1e-5f)
        assertEquals(a.yaw, moves.first().yaw, 1e-5f)
        assertEquals(b.pitch, moves.last().pitch, 1e-5f)
        assertEquals(b.yaw, moves.last().yaw, 1e-5f)
    }

    @Test
    fun `motion timelapse advances by equal increments between frames`() {
        val moves = IntervalPlanner.plan(config(frames = 5, path = Attitude(0f, 0f) to Attitude(8f, 40f))).filterIsInstance<SequenceStep.MoveTo>()
        for (i in 1 until moves.size) {
            assertEquals(2f, moves[i].pitch - moves[i - 1].pitch, 1e-5f)
            assertEquals(10f, moves[i].yaw - moves[i - 1].yaw, 1e-5f)
        }
    }

    @Test
    fun `a single-frame path just goes to A`() {
        val moves = IntervalPlanner.plan(config(frames = 1, path = Attitude(1f, 2f) to Attitude(9f, 9f))).filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(listOf(SequenceStep.MoveTo(1f, 2f)), moves)
    }

    @Test
    fun `path plus dither offsets are added on top of the path position`() {
        val dither = DitherConfig(0.1f, 0.2f, 3L)
        val a = Attitude(0f, 0f)
        val b = Attitude(10f, 10f)
        val moves = IntervalPlanner.plan(config(frames = 3, path = a to b, dither = dither)).filterIsInstance<SequenceStep.MoveTo>()
        val offsets = DitherGenerator.offsets(dither, 3)
        assertEquals(5f + offsets[1].pitch, moves[1].pitch, 1e-5f)
        assertEquals(10f + offsets[2].yaw, moves[2].yaw, 1e-5f)
    }

    @Test
    fun `capture count equals frames`() {
        assertEquals(7, IntervalPlanner.plan(config(frames = 7)).count { it is SequenceStep.Capture })
    }

    @Test
    fun `warns when the interval is shorter than settle plus exposure`() {
        val warnings = IntervalPlanner.validate(config(intervalMs = 2_000, settleMs = 1_000, exposureMs = 2_000))
        assertEquals(1, warnings.size)
        assertEquals(3_000L + IntervalPlanner.WRITE_ALLOWANCE_MS, IntervalPlanner.minimumIntervalMs(config(settleMs = 1_000, exposureMs = 2_000)))
    }

    @Test
    fun `no warning when the interval comfortably fits`() {
        assertTrue(IntervalPlanner.validate(config(intervalMs = 60_000)).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero frames is rejected`() {
        IntervalPlanner.plan(config(frames = 0))
    }
}
