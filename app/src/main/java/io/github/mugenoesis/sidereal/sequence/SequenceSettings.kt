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
    val maxIso: Int = 3200,
    /** Download the run's photos into a labelled folder on the phone (every mode but timelapse, which is off by default - it is a lot of data). */
    val saveFrames: Boolean = true,
    /** Timelapse's own "download the frames": off unless asked, because hundreds of full-size photos take a long time. */
    val saveTimelapseFrames: Boolean = false,
    /** Join the panorama's frames into one picture on the phone. */
    val stitch: Boolean = true,
    /** Encode the timelapse's frames into a video on the phone. */
    val makeVideo: Boolean = false,
    /** The lens' focal length, mm, for planning panoramas and sizing dither; null = use what the camera reports (else 15). */
    val focalMm: Float? = null
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

        /** Focal lengths a Micro Four Thirds lens commonly has; stepping below the first goes back to auto. */
        private val FOCAL_MM = listOf(7.5f, 8f, 9f, 10f, 12f, 14f, 15f, 16f, 17f, 18f, 20f, 24f, 25f, 30f, 35f, 40f, 45f, 50f, 60f, 75f, 100f)

        private fun stepFocal(current: Float?, base: Float, direction: Int): Float? {
            val from = current ?: base
            return if (direction > 0) FOCAL_MM.firstOrNull { it > from + 1e-3f } ?: FOCAL_MM.last()
            else FOCAL_MM.lastOrNull { it < from - 1e-3f }.let { lower -> if (current != null && lower == null) null else lower ?: FOCAL_MM.first() }
        }

        private fun formatMm(mm: Float): String = if (mm % 1f == 0f) "${mm.toInt()} mm" else "$mm mm"

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
        SequenceMode.INTERVALOMETER -> listOf(framesField(), intervalField(), settleField(), toggle("dither", "Dither", dither), toggle("saveFrames", "Save photos", saveFrames))
        // The "what happens to the photos afterwards" options sit near the top: the tray scrolls, and these are the
        // ones people should not have to hunt for.
        SequenceMode.TIMELAPSE -> listOf(
            FieldSpec("durationMin", "Duration", formatMinutes(durationMin)),
            intervalField(),
            FieldSpec("fps", "Clip fps", "$fps fps"),
            toggle("makeVideo", "Make video", makeVideo),
            toggle("saveTimelapseFrames", "Save frames", saveTimelapseFrames),
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
            FieldSpec("focalMm", "Focal length", focalMm?.let(::formatMm) ?: "Auto"),
            toggle("stitch", "Stitch", stitch),
            toggle("saveFrames", "Save frames", saveFrames),
            FieldSpec("shotsPerNode", "Shots/frame", "$shotsPerNode"),
            settleField()
        )
        SequenceMode.DARKS, SequenceMode.BIAS, SequenceMode.FLATS ->
            listOf(FieldSpec("calFrames", "Frames", "$calFrames"), toggle("saveFrames", "Save photos", saveFrames))
    }

    private fun framesField() = FieldSpec("frames", "Frames", "$frames")
    private fun intervalField() = FieldSpec("intervalSec", "Interval", TimelapseMath.format(intervalSec * 1_000L))
    private fun settleField() = FieldSpec("settleMs", "Settle", formatSeconds(settleMs))
    private fun toggle(id: String, label: String, on: Boolean) = FieldSpec(id, label, if (on) "On" else "Off", toggle = true)

    /** Steps field [id] one rung in [direction] (+1/-1); toggles flip. Unknown ids leave the settings unchanged. */
    fun adjust(id: String, direction: Int, baseFocalMm: Float = 15f): SequenceSettings = when (id) {
        "focalMm" -> copy(focalMm = stepFocal(focalMm, baseFocalMm, direction))
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
        "saveFrames" -> copy(saveFrames = !saveFrames)
        "saveTimelapseFrames" -> copy(saveTimelapseFrames = !saveTimelapseFrames)
        "stitch" -> copy(stitch = !stitch)
        "makeVideo" -> copy(makeVideo = !makeVideo)
        else -> this
    }

    /** Whether the run's frames stay on the phone as individual photos, per this mode's own option. */
    fun keepsFrames(): Boolean = if (mode == SequenceMode.TIMELAPSE) saveTimelapseFrames else saveFrames

    /** Whether the photos have to come off the camera after this run: to keep them, or to build a video / panorama from them. */
    fun downloadsFrames(): Boolean = keepsFrames() ||
        (mode == SequenceMode.PANORAMA && stitch) ||
        (mode == SequenceMode.TIMELAPSE && makeVideo)
}
