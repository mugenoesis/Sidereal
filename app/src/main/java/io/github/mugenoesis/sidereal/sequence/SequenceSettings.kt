package io.github.mugenoesis.sidereal.sequence

enum class SequenceMode(val label: String) {
    INTERVALOMETER("Intervalometer"),
    TIMELAPSE("Timelapse"),
    PANORAMA("Panorama"),
    DARKS("Darks"),
    BIAS("Bias"),
    FLATS("Flats");

    /** The neighbouring mode in [direction] (+1/-1), wrapping at both ends. */
    fun step(direction: Int): SequenceMode {
        val all = values()
        return all[(ordinal + direction).mod(all.size)]
    }
}

/** One row in the sequence tray: a labelled value with -/+ (or a single toggle button when [toggle]). */
data class FieldSpec(val id: String, val label: String, val display: String, val toggle: Boolean = false)

/**
 * Everything the user can dial in for a sequence. Numeric fields step
 * along fixed value ladders rather than free entry - same reasoning as the
 * ISO/shutter steppers: a few meaningful rungs beat a keyboard on a phone
 * held next to a tripod in the dark.
 */
data class SequenceSettings(
    val mode: SequenceMode = SequenceMode.INTERVALOMETER,
    val frames: Int = 20,
    val intervalSec: Int = 10,
    val settleMs: Int = 1_000,
    val dither: Boolean = false,
    val durationMin: Int = 10,
    val fps: Int = 24,
    val motion: Boolean = false,
    val yawSpanDeg: Int = 180,
    val pitchSpanDeg: Int = 60,
    val overlapPct: Int = 30,
    val shotsPerNode: Int = 1,
    val calFrames: Int = 15,
    /** Day-to-night exposure ramp for timelapses. */
    val ramp: Boolean = false,
    /** How much of the scene's darkening is kept in the frames, percent (0 = night as bright as day, 100 = fixed exposure). */
    val keepDarkPct: Int = 50,
    val maxIso: Int = 3200
) {
    companion object {
        private val FRAMES = listOf(1, 2, 3, 5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 1000)
        private val INTERVAL_SEC = listOf(1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300)
        private val DURATION_MIN = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 600)
        private val SETTLE_MS = listOf(0, 250, 500, 1_000, 1_500, 2_000, 3_000)
        private val FPS = listOf(24, 25, 30, 60)
        private val YAW_SPAN = listOf(60, 90, 120, 140, 180, 240, 300, 360, 480, 600)
        private val PITCH_SPAN = listOf(10, 20, 30, 40, 60, 80, 100, 120)
        private val OVERLAP_PCT = listOf(10, 15, 20, 25, 30, 35, 40, 50)
        private val SHOTS = listOf(1, 2, 3, 4, 5, 8, 10)
        private val CAL_FRAMES = listOf(3, 5, 10, 15, 20, 30, 50)
        private val KEEP_DARK_PCT = listOf(0, 25, 50, 75, 100)
        private val MAX_ISO = listOf(400, 800, 1600, 3200, 6400, 12800, 25600)

        /** Next rung strictly above [value] (or the top), or strictly below it (or the bottom) - so an off-ladder value steps to its neighbour. */
        private fun step(ladder: List<Int>, value: Int, direction: Int): Int =
            if (direction > 0) ladder.firstOrNull { it > value } ?: ladder.last()
            else ladder.lastOrNull { it < value } ?: ladder.first()

        private fun formatSeconds(ms: Int): String =
            if (ms % 1_000 == 0) "${ms / 1_000}s" else String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)

        private fun formatMinutes(min: Int): String {
            val h = min / 60
            val m = min % 60
            return when {
                h == 0 -> "${m}m"
                m == 0 -> "${h}h"
                else -> "${h}h ${m}m"
            }
        }
    }

    fun fields(): List<FieldSpec> = when (mode) {
        SequenceMode.INTERVALOMETER -> listOf(framesField(), intervalField(), settleField(), toggle("dither", "Dither", dither))
        SequenceMode.TIMELAPSE -> listOf(
            FieldSpec("durationMin", "Duration", formatMinutes(durationMin)),
            intervalField(),
            FieldSpec("fps", "Clip fps", "$fps fps"),
            settleField(),
            toggle("motion", "A→B move", motion),
            toggle("ramp", "Day→night ramp", ramp)
        ) + if (ramp) listOf(
            FieldSpec("keepDarkPct", "Keep darkness", "$keepDarkPct%"),
            FieldSpec("maxIso", "Max ISO", "ISO $maxIso")
        ) else emptyList()
        SequenceMode.PANORAMA -> listOf(
            FieldSpec("yawSpanDeg", "Yaw span", "$yawSpanDeg°"),
            FieldSpec("pitchSpanDeg", "Pitch span", "$pitchSpanDeg°"),
            FieldSpec("overlapPct", "Overlap", "$overlapPct%"),
            FieldSpec("shotsPerNode", "Shots/frame", "$shotsPerNode"),
            settleField()
        )
        SequenceMode.DARKS, SequenceMode.BIAS, SequenceMode.FLATS -> listOf(FieldSpec("calFrames", "Frames", "$calFrames"))
    }

    private fun framesField() = FieldSpec("frames", "Frames", "$frames")
    private fun intervalField() = FieldSpec("intervalSec", "Interval", TimelapseMath.format(intervalSec * 1_000L))
    private fun settleField() = FieldSpec("settleMs", "Settle", formatSeconds(settleMs))
    private fun toggle(id: String, label: String, on: Boolean) = FieldSpec(id, label, if (on) "On" else "Off", toggle = true)

    /** Steps field [id] one rung in [direction] (+1/-1); toggles flip. Unknown ids leave the settings unchanged. */
    fun adjust(id: String, direction: Int): SequenceSettings = when (id) {
        "frames" -> copy(frames = step(FRAMES, frames, direction))
        "intervalSec" -> copy(intervalSec = step(INTERVAL_SEC, intervalSec, direction))
        "settleMs" -> copy(settleMs = step(SETTLE_MS, settleMs, direction))
        "dither" -> copy(dither = !dither)
        "durationMin" -> copy(durationMin = step(DURATION_MIN, durationMin, direction))
        "fps" -> copy(fps = step(FPS, fps, direction))
        "motion" -> copy(motion = !motion)
        "yawSpanDeg" -> copy(yawSpanDeg = step(YAW_SPAN, yawSpanDeg, direction))
        "pitchSpanDeg" -> copy(pitchSpanDeg = step(PITCH_SPAN, pitchSpanDeg, direction))
        "overlapPct" -> copy(overlapPct = step(OVERLAP_PCT, overlapPct, direction))
        "shotsPerNode" -> copy(shotsPerNode = step(SHOTS, shotsPerNode, direction))
        "calFrames" -> copy(calFrames = step(CAL_FRAMES, calFrames, direction))
        "ramp" -> copy(ramp = !ramp)
        "keepDarkPct" -> copy(keepDarkPct = step(KEEP_DARK_PCT, keepDarkPct, direction))
        "maxIso" -> copy(maxIso = step(MAX_ISO, maxIso, direction))
        else -> this
    }
}
