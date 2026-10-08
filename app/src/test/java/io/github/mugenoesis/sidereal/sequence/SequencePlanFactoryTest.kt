package io.github.mugenoesis.sidereal.sequence

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a still timelapse re-aims at the starting pose before every frame so a gimbal that went to sleep is put back`() {
        val moves = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 1, intervalSec = 10)).steps.filterIsInstance<SequenceStep.MoveTo>()
        assertEquals(6, moves.size)
        assertTrue(moves.all { it.pitch == -10f && it.yaw == 20f })
    }

    @Test
    fun `a still timelapse without a gimbal reading just runs`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 1, intervalSec = 10), ctx.copy(attitude = null))
        assertTrue(plan.steps.none { it is SequenceStep.MoveTo })
        assertEquals(6, plan.captures)
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

    private val rampSettings = SequenceSettings(
        mode = SequenceMode.TIMELAPSE, durationMin = 2, intervalSec = 10, settleMs = 1000,
        ramp = true, keepDarkPct = 25, maxIso = 1600
    )

    @Test
    fun `a timelapse without the ramp has no ramp steps`() {
        val plan = ok(rampSettings.copy(ramp = false))
        assertTrue(plan.steps.none { it is SequenceStep.BeginRamp || it is SequenceStep.AdaptExposure })
    }

    @Test
    fun `a ramped timelapse begins the ramp once, with limits taken from the settings and the interval`() {
        val steps = ok(rampSettings).steps
        val begin = steps.filterIsInstance<SequenceStep.BeginRamp>()
        assertEquals(1, begin.size)
        assertTrue(steps.first() is SequenceStep.BeginRamp)
        val c = begin.single().config
        assertEquals(0.25, c.keepDarkFraction, 1e-9)
        assertEquals(1600, c.maxIso)
        // 10 s interval minus 1 s settle, the 2 s write allowance and a half second of slack
        assertEquals(6.5, c.maxShutterSec, 1e-9)
    }

    @Test
    fun `every frame of a ramped timelapse adapts the exposure right after its slot starts and before the shutter`() {
        val steps = ok(rampSettings).steps
        val captures = steps.count { it is SequenceStep.Capture }
        assertEquals(captures, steps.count { it is SequenceStep.AdaptExposure })
        for ((i, step) in steps.withIndex()) {
            if (step is SequenceStep.Capture) {
                val adaptAt = steps.subList(0, i).indexOfLast { it is SequenceStep.AdaptExposure }
                val waitAt = steps.subList(0, i).indexOfLast { it is SequenceStep.WaitUntil }
                assertTrue("adapt $adaptAt after wait $waitAt", adaptAt > waitAt)
            }
        }
    }

    @Test
    fun `an interval too short to leave any shutter time refuses to ramp and says why`() {
        val msg = error(rampSettings.copy(intervalSec = 3))
        assertTrue(msg, msg.contains("interval", ignoreCase = true))
    }

    @Test
    fun `a panorama plan tags every capture with its row and column in shooting order`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.PANORAMA, yawSpanDeg = 120, pitchSpanDeg = 80, overlapPct = 30))
        val series = plan.series
        assertEquals(plan.captures, series.tags.size)
        assertEquals("r1c1", series.tags.first())
        assertEquals(series.tags.size, series.tags.toSet().size)
        val layout = series.panorama!!
        assertEquals(layout.nodes.size, plan.captures)
        // snake order: the second row is visited right to left, so its first shot is the last column
        val cols = layout.nodes.maxOf { it.col } + 1
        assertEquals("r2c$cols", series.tags[cols])
    }

    @Test
    fun `stacked panorama shots are tagged per shot at each node`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.PANORAMA, shotsPerNode = 3))
        assertEquals("r1c1_s1", plan.series.tags[0])
        assertEquals("r1c1_s3", plan.series.tags[2])
        assertEquals("r1c2_s1", plan.series.tags[3])
    }

    @Test
    fun `timelapse and intervalometer frames are numbered from one`() {
        val t = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 1, intervalSec = 10, makeVideo = true, fps = 30))
        assertEquals(listOf("f0001", "f0002", "f0003", "f0004", "f0005", "f0006"), t.series.tags)
        assertTrue(t.series.makeVideo)
        assertEquals(30, t.series.fps)
        val i = ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, frames = 3))
        assertEquals(listOf("f0001", "f0002", "f0003"), i.series.tags)
    }

    @Test
    fun `calibration frames are tagged by kind`() {
        assertEquals("dark001", ok(SequenceSettings(mode = SequenceMode.DARKS, calFrames = 3)).series.tags.first())
        assertEquals("bias003", ok(SequenceSettings(mode = SequenceMode.BIAS, calFrames = 3)).series.tags.last())
        assertEquals("flat002", ok(SequenceSettings(mode = SequenceMode.FLATS, calFrames = 3)).series.tags[1])
    }

    @Test
    fun `the series plan carries what to do with the files afterwards`() {
        val pano = ok(SequenceSettings(mode = SequenceMode.PANORAMA, saveFrames = false, stitch = true)).series
        assertTrue(pano.stitch)
        assertFalse(pano.keepFrames)
        assertTrue(pano.needsDownload)
        val off = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE)).series
        assertFalse(off.needsDownload)
        assertFalse(off.makeVideo)
    }

    @Test
    fun `only a timelapse can make a video and only a panorama can stitch`() {
        assertFalse(ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, makeVideo = true, stitch = true)).series.makeVideo)
        assertFalse(ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, makeVideo = true, stitch = true)).series.stitch)
        assertFalse(ok(SequenceSettings(mode = SequenceMode.PANORAMA, makeVideo = true)).series.makeVideo)
    }

    @Test
    fun `the summary says what will happen to the photos afterwards`() {
        val pano = ok(SequenceSettings(mode = SequenceMode.PANORAMA, yawSpanDeg = 120, pitchSpanDeg = 80))
        assertTrue(pano.summary, pano.summary.contains("save"))
        assertTrue(pano.summary, pano.summary.contains("stitch"))
        val lapse = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 5, intervalSec = 10, makeVideo = true))
        assertTrue(lapse.summary, lapse.summary.contains("video"))
        assertFalse(lapse.summary, lapse.summary.contains("save"))
        val plain = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 5, intervalSec = 10))
        assertFalse(plain.summary, plain.summary.contains("download"))
        assertFalse(plain.summary, plain.summary.contains("save"))
    }

    @Test
    fun `the summary warns how long bringing the photos in will take`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, frames = 100))
        assertTrue(plan.summary, plan.summary.contains("download"))
        assertTrue(plan.summary, plan.summary.contains("5m 50s download")) // 100 photos at ~3.5s each
    }

    @Test
    fun `a download that would take over half an hour is warned about`() {
        // 600 frames at ~3.5s each is 35 minutes
        val plan = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 600, intervalSec = 60, makeVideo = true))
        val warning = plan.warnings.firstOrNull { it.contains("download", ignoreCase = true) }
        assertTrue(plan.warnings.toString(), warning != null)
        assertTrue(warning!!, warning.contains("35m"))
        assertTrue(warning, warning.contains("Make video"))
    }

    @Test
    fun `a short download carries no warning`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, frames = 100))
        assertTrue(plan.warnings.toString(), plan.warnings.none { it.contains("download", ignoreCase = true) })
    }

    @Test
    fun `no download means no download warning however long the run`() {
        val plan = ok(SequenceSettings(mode = SequenceMode.TIMELAPSE, durationMin = 600, intervalSec = 60))
        assertTrue(plan.warnings.toString(), plan.warnings.none { it.contains("download", ignoreCase = true) })
    }

    // --- lens awareness ---

    private fun pano(settings: SequenceSettings = SequenceSettings(), context: ShootContext = ctx) =
        ok(settings.copy(mode = SequenceMode.PANORAMA, yawSpanDeg = 180, pitchSpanDeg = 60), context)

    @Test
    fun `a longer lens needs more frames to cover the same panorama`() {
        val wide = pano(SequenceSettings(focalMm = 15f))
        val tele = pano(SequenceSettings(focalMm = 50f))
        assertTrue("${wide.captures} vs ${tele.captures}", tele.captures > wide.captures * 3)
        assertTrue(tele.series.panorama!!.hFovDeg < wide.series.panorama!!.hFovDeg / 2)
    }

    @Test
    fun `with the focal length on auto the plan uses the lens the camera reported`() {
        val detected = pano(context = ctx.copy(lensFocalMm = 25f))
        val explicit = pano(SequenceSettings(focalMm = 25f))
        assertEquals(explicit.captures, detected.captures)
        assertEquals(explicit.series.panorama!!.hFovDeg, detected.series.panorama!!.hFovDeg, 1e-4f)
    }

    @Test
    fun `your own focal length wins over what the camera reported`() {
        val plan = pano(SequenceSettings(focalMm = 50f), ctx.copy(lensFocalMm = 15f))
        assertEquals(pano(SequenceSettings(focalMm = 50f)).captures, plan.captures)
    }

    @Test
    fun `with nothing known it falls back to 15 mm`() {
        assertEquals(pano(SequenceSettings(focalMm = 15f)).captures, pano().captures)
    }

    @Test
    fun `a zoom lens on auto asks for the focal length to be set`() {
        val plan = pano(context = ctx.copy(lensFocalMm = null, lensZoomMm = 12f..40f))
        val warning = plan.warnings.firstOrNull { it.contains("zoom", ignoreCase = true) }
        assertTrue(plan.warnings.toString(), warning != null)
        assertTrue(warning!!, warning.contains("12") && warning.contains("40"))
        // planned for the wide end, so the overlap errs on the safe side
        assertEquals(pano(SequenceSettings(focalMm = 12f)).captures, plan.captures)
    }

    @Test
    fun `a zoom lens with the focal length set needs no warning`() {
        val plan = pano(SequenceSettings(focalMm = 20f), ctx.copy(lensZoomMm = 12f..40f))
        assertTrue(plan.warnings.toString(), plan.warnings.none { it.contains("zoom", ignoreCase = true) })
    }

    @Test
    fun `the summary says which focal length it planned for`() {
        assertTrue(pano(SequenceSettings(focalMm = 25f)).summary.contains("25 mm"))
    }

    @Test
    fun `dither is smaller on a longer lens so it stays a few pixels, not a few hundred`() {
        fun biggest(focal: Float): Float {
            val plan = ok(SequenceSettings(mode = SequenceMode.INTERVALOMETER, frames = 60, dither = true, focalMm = focal))
            val hold = ctx.attitude!!
            return plan.steps.filterIsInstance<SequenceStep.MoveTo>().maxOf { maxOf(abs(it.pitch - hold.pitch), abs(it.yaw - hold.yaw)) }
        }
        val wide = biggest(15f)
        val tele = biggest(60f)
        assertTrue("wide $wide tele $tele", wide in 0.3f..0.85f)
        assertTrue("wide $wide tele $tele", tele < wide / 2.5f)
    }
}
