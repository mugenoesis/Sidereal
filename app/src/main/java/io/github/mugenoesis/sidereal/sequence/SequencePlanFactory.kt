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
    /** Focal length of the lens the camera reports (a prime), or null if it did not say or it is a zoom. */
    val lensFocalMm: Float? = null,
    /** A zoom lens' range, if the camera reported one: where it is set is not reported, so the user has to say. */
    val lensZoomMm: ClosedFloatingPointRange<Float>? = null,
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
    /** 0.3 and 0.8 degrees on the 60 degree wide 15 mm lens this was tuned on, as fractions of the field of view. */
    private const val DITHER_MIN_FRACTION = 0.3f / 60f
    private const val DITHER_MAX_FRACTION = 0.8f / 60f
    private const val DEFAULT_FOCAL_MM = 15f

    fun build(settings: SequenceSettings, context: ShootContext): PlanResult {
        val warnings = ArrayList<String>()
        val (focalMm, zoomWarning) = resolveFocal(settings, context)
        zoomWarning?.let { warnings += it }
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
                        dither = if (settings.dither) {
                            // Dither is meant to move the picture by a few dozen pixels, whatever the lens: scale it with the field of view.
                            val hFov = PanoramaPlanner.fovFor(focalMm).first
                            DitherConfig(hFov * DITHER_MIN_FRACTION, hFov * DITHER_MAX_FRACTION, context.ditherSeed)
                        } else null
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
                    val (hFov, vFov) = PanoramaPlanner.fovFor(focalMm)
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
                    clipNote = " · ${plan.rows}×${plan.cols} grid · ${formatMm(focalMm)}"
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
            downloadWarning(series)?.let { warnings += it }
            PlanResult.Ok(
                BuiltPlan(
                    steps = steps,
                    captures = captures,
                    estimatedMs = estimated,
                    summary = "$captures frames · ${TimelapseMath.format(estimated)}$clipNote${afterNote(series)}",
                    warnings = warnings,
                    series = series
                )
            )
        } catch (e: IllegalArgumentException) {
            PlanResult.Error(e.message ?: "Invalid settings")
        }
    }

    /** Roughly how long one photo takes to come across the camera's WiFi, from real downloads of the X5's ~7 MB JPEGs. */
    private const val DOWNLOAD_MS_PER_PHOTO = 3_500L

    /** " · then save, stitch (+4m download)": what happens to the photos after the run, and what it costs in time. */
    private fun afterNote(series: SeriesPlan): String {
        if (!series.needsDownload) return ""
        val steps = ArrayList<String>()
        if (series.keepFrames) steps += "save"
        if (series.stitch) steps += "stitch"
        if (series.makeVideo) steps += "video"
        val download = TimelapseMath.format(series.tags.size * DOWNLOAD_MS_PER_PHOTO)
        return " · then ${steps.joinToString(", ")} (+$download download)"
    }

    private const val LONG_DOWNLOAD_MS = 30 * 60_000L

    /** A heads-up when bringing the photos in will take a long time, with the options that cause it. */
    private fun downloadWarning(series: SeriesPlan): String? {
        if (!series.needsDownload) return null
        val ms = series.tags.size * DOWNLOAD_MS_PER_PHOTO
        if (ms < LONG_DOWNLOAD_MS) return null
        val options = if (series.mode == SequenceMode.TIMELAPSE) "Make video and Save frames" else "Save photos and Stitch"
        return "Downloading the photos afterwards will take about ${TimelapseMath.format(ms)} - turn off $options to leave them on the camera's card"
    }

    private fun defaultTags(mode: SequenceMode, captures: Int): List<String> = (1..captures).map {
        when (mode) {
            SequenceMode.DARKS, SequenceMode.BIAS, SequenceMode.FLATS -> SeriesNaming.calibrationTag(mode, it, captures)
            else -> SeriesNaming.frameTag(it, captures)
        }
    }

    /** Your setting, else what the camera reported, else 15 mm; plus a warning when a zoom has to be told where it is. */
    private fun resolveFocal(settings: SequenceSettings, context: ShootContext): Pair<Float, String?> {
        settings.focalMm?.let { return it to null }
        context.lensFocalMm?.let { return it to null }
        context.lensZoomMm?.let { range ->
            return range.start to "Zoom lens (${formatMm(range.start)}-${formatMm(range.endInclusive)}): set the focal length it is at, " +
                "or the panorama may have gaps. Planned for the wide end."
        }
        return DEFAULT_FOCAL_MM to null
    }

    private fun formatMm(mm: Float): String = if (mm % 1f == 0f) "${mm.toInt()} mm" else "$mm mm"

    private fun gimbalMissing() = PlanResult.Error("Gimbal attitude unavailable - is the Osmo connected?")
}
