package io.github.mugenoesis.sidereal.sequence

/**
 * One plan covers the plain intervalometer, dithered stacking runs and
 * motion timelapses: they differ only in whether the gimbal is told where
 * to point before each frame.
 *
 * @param intervalMs start-to-start time between frames
 * @param settleMs delay between the gimbal arriving and the shutter opening
 * @param hold base attitude re-commanded before every frame (required for dither)
 * @param path A to B attitudes interpolated evenly across the frames (motion timelapse); overrides [hold]
 * @param dither random per-frame offset on top of whichever attitude applies
 */
data class IntervalConfig(
    val frames: Int,
    val intervalMs: Long,
    val settleMs: Long,
    val exposureMs: Long,
    val hold: Attitude? = null,
    val path: Pair<Attitude, Attitude>? = null,
    val dither: DitherConfig? = null,
    /** Day-to-night ramping: the exposure is metered and reset before every frame. */
    val ramp: RampConfig? = null
)

object IntervalPlanner {

    /** Time the camera needs after the shutter closes to write the frame, before it will take another reliably. */
    const val WRITE_ALLOWANCE_MS = 2_000L

    fun plan(config: IntervalConfig): List<SequenceStep> {
        require(config.frames >= 1) { "need at least one frame" }
        require(config.dither == null || config.hold != null || config.path != null) { "dither needs a base attitude" }

        val offsets = config.dither?.let { DitherGenerator.offsets(it, config.frames) }
        val steps = ArrayList<SequenceStep>()
        config.ramp?.let { steps += SequenceStep.BeginRamp(it) }
        for (i in 0 until config.frames) {
            steps += SequenceStep.WaitUntil(i * config.intervalMs)
            if (config.ramp != null) steps += SequenceStep.AdaptExposure
            val base = baseAttitude(config, i)
            if (base != null) {
                val offset = offsets?.get(i) ?: Attitude(0f, 0f)
                steps += SequenceStep.MoveTo(base.pitch + offset.pitch, base.yaw + offset.yaw)
            }
            steps += SequenceStep.Settle(config.settleMs)
            steps += SequenceStep.Capture(config.exposureMs, "light")
        }
        return steps
    }

    private fun baseAttitude(config: IntervalConfig, frame: Int): Attitude? {
        val path = config.path ?: return config.hold
        val t = if (config.frames == 1) 0f else frame.toFloat() / (config.frames - 1)
        return Attitude(
            path.first.pitch + (path.second.pitch - path.first.pitch) * t,
            path.first.yaw + (path.second.yaw - path.first.yaw) * t
        )
    }

    fun minimumIntervalMs(config: IntervalConfig): Long = config.settleMs + config.exposureMs + WRITE_ALLOWANCE_MS

    fun validate(config: IntervalConfig): List<String> {
        val warnings = ArrayList<String>()
        if (config.intervalMs < minimumIntervalMs(config)) {
            warnings += "Interval is shorter than settle + exposure + write time (${minimumIntervalMs(config) / 1000.0}s) - frames will run late"
        }
        return warnings
    }
}

object TimelapseMath {

    fun framesForDuration(durationMs: Long, intervalMs: Long): Int =
        (durationMs / intervalMs).toInt().coerceAtLeast(1)

    fun clipSeconds(frames: Int, fps: Int): Double = frames.toDouble() / fps

    fun totalDurationMs(frames: Int, intervalMs: Long, settleMs: Long, exposureMs: Long): Long =
        (frames - 1) * intervalMs + settleMs + exposureMs

    fun remainingMs(frames: Int, done: Int, intervalMs: Long, settleMs: Long, exposureMs: Long): Long =
        if (done >= frames) 0L else (frames - 1 - done) * intervalMs + settleMs + exposureMs

    fun format(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%dh %02dm %02ds".format(h, m, s)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "${s}s"
        }
    }
}
