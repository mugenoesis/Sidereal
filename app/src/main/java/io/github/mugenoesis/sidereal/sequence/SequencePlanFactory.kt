package io.github.mugenoesis.sidereal.sequence

import io.github.mugenoesis.sidereal.series.PanoramaLayout
import io.github.mugenoesis.sidereal.series.SeriesNaming
import io.github.mugenoesis.sidereal.series.SeriesPlan

/** Live facts about the camera and gimbal that a plan depends on, gathered by the caller at the moment Start is pressed. */
data class ShootContext(
    /** Shutter-open time right now, or null if unknown (AUTO). */
    val exposureMs: Long?,
    /** Current `ShutterSpeed` enum name - restored after bias frames. */
    val shutterName: String?,
    val attitude: Attitude?,
    val pitchLimits: ClosedFloatingPointRange<Float>? = null,
    val yawLimits: ClosedFloatingPointRange<Float>? = null,
    /** Timed Move's captured points, used by a motion timelapse. */
    val pointA: Attitude? = null,
    val pointB: Attitude? = null,
    val focalMm: Float = 15f,
    val ditherSeed: Long = 42L
)

data class BuiltPlan(
    val steps: List<SequenceStep>,
    val captures: Int,
    val estimatedMs: Long,
    val summary: String,
    val warnings: List<String>,
    /** What happens to the photos afterwards (download, stitch, video) and how to label them. */
    val series: SeriesPlan
)

sealed class PlanResult {
    data class Ok(val plan: BuiltPlan) : PlanResult()
    data class Error(val message: String) : PlanResult()
}

/** Rough wall-clock length of a plan - good enough for "about 12 minutes", not for scheduling. */
object SequenceEstimate {
    private const val MOVE_MS = 1_500L
    private const val SET_SHUTTER_MS = 500L
    private const val ADAPT_MS = 1_000L

    fun durationMs(steps: List<SequenceStep>): Long {
        var t = 0L
        for (step in steps) {
            t = when (step) {
                is SequenceStep.MoveTo -> t + MOVE_MS
                is SequenceStep.Settle -> t + step.ms
                is SequenceStep.Capture -> t + step.exposureMs + IntervalPlanner.WRITE_ALLOWANCE_MS
                is SequenceStep.WaitUntil -> maxOf(t, step.offsetMs)
                is SequenceStep.SetShutter -> t + SET_SHUTTER_MS
                is SequenceStep.Prompt -> t
                is SequenceStep.BeginRamp -> t
                is SequenceStep.AdaptExposure -> t + ADAPT_MS
            }
        }
        return t
    }
}

/** Turns what the user dialled in plus the live camera/gimbal state into a runnable plan, or a plain-English reason it can't. */
object SequencePlanFactory {

    private const val FALLBACK_EXPOSURE_MS = 1_000L
    private const val RAMP_SLACK_MS = 500L
    private const val MIN_RAMP_SHUTTER_SEC = 0.25
    private const val DITHER_MIN_DEG = 0.3f
    private const val DITHER_MAX_DEG = 0.8f

    fun build(settings: SequenceSettings, context: ShootContext): PlanResult {
        val warnings = ArrayList<String>()
        val exposureMs = context.exposureMs ?: FALLBACK_EXPOSURE_MS.also {
            warnings += "Couldn't read the current exposure - assuming 1s (use a fixed shutter speed)"
        }
        return try {
            val steps: List<SequenceStep>
            var clipNote = ""
            var tags: List<String>? = null
            var panorama: PanoramaLayout? = null
            when (settings.mode) {
                SequenceMode.INTERVALOMETER -> {
                    val config = IntervalConfig(
                        frames = settings.frames,
                        intervalMs = settings.intervalSec * 1_000L,
                        settleMs = settings.settleMs.toLong(),
                        exposureMs = exposureMs,
                        hold = if (settings.dither) context.attitude ?: return gimbalMissing() else null,
                        dither = if (settings.dither) DitherConfig(DITHER_MIN_DEG, DITHER_MAX_DEG, context.ditherSeed) else null
                    )
                    warnings += IntervalPlanner.validate(config)
                    steps = IntervalPlanner.plan(config)
                }
                SequenceMode.TIMELAPSE -> {
                    val frames = TimelapseMath.framesForDuration(settings.durationMin * 60_000L, settings.intervalSec * 1_000L)
                    val path = if (settings.motion) {
                        val a = context.pointA
                        val b = context.pointB
                        if (a == null || b == null) return PlanResult.Error("Set points A and B in Timed Move first, or turn the A→B move off")
                        a to b
                    } else null
                    val ramp = if (settings.ramp) {
                        // The longest shutter that still fits the interval after the settle, the camera's write time and some slack.
                        val maxShutterSec = (settings.intervalSec * 1_000L - settings.settleMs - IntervalPlanner.WRITE_ALLOWANCE_MS - RAMP_SLACK_MS) / 1_000.0
                        if (maxShutterSec < MIN_RAMP_SHUTTER_SEC) {
                            return PlanResult.Error("The interval is too short to ramp the exposure - lengthen it (needs room for the shutter, settle and write time)")
                        }
                        RampConfig(keepDarkFraction = settings.keepDarkPct / 100.0, maxShutterSec = maxShutterSec, maxIso = settings.maxIso)
                    } else null
                    val config = IntervalConfig(
                        frames = frames,
                        intervalMs = settings.intervalSec * 1_000L,
                        settleMs = settings.settleMs.toLong(),
                        exposureMs = exposureMs,
                        path = path,
                        ramp = ramp
                    )
                    warnings += IntervalPlanner.validate(config)
                    steps = IntervalPlanner.plan(config)
                    clipNote = String.format(java.util.Locale.US, " · %.1fs clip @ %d fps", TimelapseMath.clipSeconds(frames, settings.fps), settings.fps)
                }
                SequenceMode.PANORAMA -> {
                    val center = context.attitude ?: return gimbalMissing()
                    val (hFov, vFov) = PanoramaPlanner.fovFor(context.focalMm)
                    val plan = PanoramaPlanner.plan(
                        PanoramaConfig(
                            center = center,
                            yawSpanDeg = settings.yawSpanDeg.toFloat(),
                            pitchSpanDeg = settings.pitchSpanDeg.toFloat(),
                            hFovDeg = hFov,
                            vFovDeg = vFov,
                            overlap = settings.overlapPct / 100f,
                            settleMs = settings.settleMs.toLong(),
                            exposureMs = exposureMs,
                            shotsPerNode = settings.shotsPerNode,
                            pitchLimits = context.pitchLimits,
                            yawLimits = context.yawLimits
                        )
                    )
                    steps = plan.steps
                    clipNote = " · ${plan.rows}×${plan.cols} grid"
                    val shots = settings.shotsPerNode
                    tags = plan.nodes.flatMap { node -> (0 until shots).map { SeriesNaming.panoTag(node.row, node.col, it, shots) } }
                    panorama = PanoramaLayout(plan.nodes.flatMap { node -> List(shots) { node } }, shots, hFov, vFov)
                }
                SequenceMode.DARKS -> steps = CalibrationPlanner.darks(settings.calFrames, exposureMs)
                SequenceMode.BIAS -> steps = CalibrationPlanner.bias(settings.calFrames, context.shutterName)
                SequenceMode.FLATS -> steps = CalibrationPlanner.flats(settings.calFrames, exposureMs)
            }
            val captures = steps.count { it is SequenceStep.Capture }
            val series = SeriesPlan(
                mode = settings.mode,
                tags = tags ?: defaultTags(settings.mode, captures),
                keepFrames = settings.keepsFrames(),
                stitch = settings.mode == SequenceMode.PANORAMA && settings.stitch,
                makeVideo = settings.mode == SequenceMode.TIMELAPSE && settings.makeVideo,
                fps = settings.fps,
                panorama = panorama
            )
            val estimated = SequenceEstimate.durationMs(steps)
            PlanResult.Ok(
                BuiltPlan(
                    steps = steps,
                    captures = captures,
                    estimatedMs = estimated,
                    summary = "$captures frames · ${TimelapseMath.format(estimated)}$clipNote",
                    warnings = warnings,
                    series = series
                )
            )
        } catch (e: IllegalArgumentException) {
            PlanResult.Error(e.message ?: "Invalid settings")
        }
    }

    private fun defaultTags(mode: SequenceMode, captures: Int): List<String> = (1..captures).map {
        when (mode) {
            SequenceMode.DARKS, SequenceMode.BIAS, SequenceMode.FLATS -> SeriesNaming.calibrationTag(mode, it, captures)
            else -> SeriesNaming.frameTag(it, captures)
        }
    }

    private fun gimbalMissing() = PlanResult.Error("Gimbal attitude unavailable - is the Osmo connected?")
}
