package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequencePlanFactoryTest {

    private val ctx = ShootContext(
        exposureMs = 2_000,
        shutterName = "SHUTTER_SPEED_2",
        attitude = Attitude(-10f, 20f),
        pitchLimits = -90f..30f,
        yawLimits = -320f..320f
    )

    private fun ok(settings: SequenceSettings, context: ShootContext = ctx): BuiltPlan =
        (SequencePlanFactory.build(settings, context) as PlanResult.Ok).plan

    private fun error(settings: SequenceSettings, context: ShootContext = ctx): String =
        (SequencePlanFactory.build(settings, context) as PlanResult.Error).message

    @Test
    fun `intervalometer plans the requested frames at the interval using the current exposure`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, frames = 4, intervalSec = 10, settleMs = 1000))
        assertEquals(4, plan.captures)
        val waits = plan.steps.filterIsInstance<SequenceStep.WaitUntil>().map { it.offsetMs }
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), waits)
        assertTrue(plan.steps.filterIsInstance<SequenceStep.Capture>().all { it.exposureMs == 2_000L })
    }

    @Test
    fun `intervalometer without dither never touches the gimbal`() {
        assertTrue(ok(SequenceSettings(frames = 3)).steps.none { it is SequenceStep.MoveTo })
    }

    @Test
    fun `dither holds the current attitude and nudges around it`() {
        val moves = ok(SequenceSettings(frames = 5, dither = true)).steps.filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(5, moves.size)
        assertEquals(-10f, moves[0].pitch, 1e-4f)
        assertEquals(20f, moves[0].yaw, 1e-4f)
        assertTrue(moves.any { it.pitch != -10f || it.yaw != 20f })
    }

    @Test
    fun `dither needs a gimbal reading`() {
        assertTrue(error(SequenceSettings(dither = true), ctx.copy(attitude = null)).contains("gimbal", ignoreCase = true))
    }

    @Test
    fun `timelapse derives frames from duration over interval`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 10, intervalSec = 5))
        assertEquals(120, plan.captures)
    }

    @Test
    fun `timelapse summary reports the resulting clip length`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 10, intervalSec = 5, fps = 24))
        assertTrue(plan.summary, plan.summary.contains("120 frames"))
        assertTrue(plan.summary, plan.summary.contains("5.0s clip"))
    }

    @Test
    fun `motion timelapse sweeps from A to B`() {
        val c = ctx.copy(pointA = Attitude(-20f, -30f), pointB = Attitude(0f, 30f))
        val moves = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 1, intervalSec = 10, motion = true), c)
            .steps.filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(-30f, moves.first().yaw, 1e-4f)
        assertEquals(30f, moves.last().yaw, 1e-4f)
    }

    @Test
    fun `motion timelapse without points A and B explains what to do`() {
        val message = error(SequenceSettings(mode = SequenceMode.TIMELAPSE, motion = true))
        assertTrue(message, message.contains("A") && message.contains("B"))
    }

    @Test
    fun `panorama centres on the current attitude and respects the gimbal's real limits`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.PANORAMA, yawSpanDeg = 140, pitchSpanDeg = 40, overlapPct = 30))
        assertEquals(3, plan.captures)
        val yaws = plan.steps.filterIsInstance<SequenceStep.MoveTo>().map { it.yaw }
        assertEquals(20f, yaws.average().toFloat(), 1e-3f)
    }

    @Test
    fun `panorama that would tip past the pitch limit says so instead of crashing`() {
        val message = error(SequenceSettings(mode = SequenceMode.PANORAMA, pitchSpanDeg = 180), ctx.copy(attitude = Attitude(20f, 0f)))
        assertTrue(message, message.contains("pitch"))
    }

    @Test
    fun `panorama needs a gimbal reading`() {
        assertTrue(error(SequenceSettings(mode = SequenceMode.PANORAMA), ctx.copy(attitude = null)).contains("gimbal", ignoreCase = true))
    }

    @Test
    fun `darks bias and flats build calibration plans`() {
        assertEquals(7, ok(SequenceSettings(mode = SequenceMode.DARKS, calFrames = 7)).captures)
        val bias = ok(SequenceSettings(mode = SequenceMode.BIAS, calFrames = 5))
        assertEquals(5, bias.captures)
        assertEquals("SHUTTER_SPEED_2", bias.steps.filterIsInstance<SequenceStep.SetShutter>().last().shutterName)
        assertEquals(9, ok(SequenceSettings(mode = SequenceMode.FLATS, calFrames = 9)).captures)
    }

    @Test
    fun `unknown exposure falls back to one second and says so`() {
        val plan = ok(SequenceSettings(frames = 2), ctx.copy(exposureMs = null))
        assertTrue(plan.steps.filterIsInstance<SequenceStep.Capture>().all { it.exposureMs == 1_000L })
        assertTrue(plan.warnings.any { it.contains("exposure", ignoreCase = true) })
    }

    @Test
    fun `short interval produces a warning`() {
        val plan = ok(SequenceSettings(frames = 3, intervalSec = 1), ctx)
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun `estimated duration follows the plan, not just the interval`() {
        // 3 frames, 10s apart, 1s settle, 2s exposure + 2s write: last frame starts at 20s.
        val steps = IntervalPlanner.plan(IntervalConfig(3, 10_000, 1_000, 2_000))
        assertEquals(25_000L, SequenceEstimate.durationMs(steps))
    }

    @Test
    fun `estimate counts gimbal moves and waits as time`() {
        val noMove = SequenceEstimate.durationMs(listOf(SequenceStep.Capture(1_000)))
        val withMove = SequenceEstimate.durationMs(listOf(SequenceStep.MoveTo(0f, 0f), SequenceStep.Capture(1_000)))
        assertTrue(withMove > noMove)
    }
}
