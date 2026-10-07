package io.github.mugenoesis.sidereal.sequence

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * @param keepDarkFraction how much of the scene's darkening stays visible in the frames: 0 = the exposure
 *   compensates completely (night comes out as bright as day), 1 = the exposure never changes. Between is the
 *   usual day-to-night look: the sky does go dark, but not as dark as it really is.
 * @param maxStepStops the most the exposure may change between two frames - bigger jumps show up as flicker
 */
data class RampConfig(
    val keepDarkFraction: Double = 0.5,
    val maxStepStops: Double = 1.0 / 3,
    val maxShutterSec: Double = 8.0,
    val maxIso: Int = 25_600,
    val deadBandStops: Double = 0.2
)

data class RampDecision(
    val setting: ExposureSetting,
    /** Estimated scene light level (stops), or null before there is a reading. */
    val sceneStops: Double?,
    val targetStops: Double,
    val changed: Boolean,
    val summary: String
)

/**
 * The day-to-night ("holy grail") exposure controller. Each frame it is given the mean brightness of the live
 * preview taken at the CURRENT settings. Brightness and the exposure that produced it say how much light is in the
 * scene ([LumaCurve]); the exposure then follows the scene by (1 - keepDark) of any change since the start, moving
 * at most one step per frame, so the sequence ramps smoothly however fast the light fades.
 *
 * Pure: the caller reads the histogram and applies the returned setting to the camera.
 */
class ExposureRamp(
    private val ladder: ExposureLadder,
    private val config: RampConfig,
    start: ExposureSetting
) {
    var current: ExposureSetting = start
        private set

    private val baselineStops = start.stops
    private val readings = ArrayDeque<Double>()
    private var baselineScene: Double? = null
    private val baselineSamples = ArrayList<Double>()

    fun next(meanLuma: Double?): RampDecision {
        if (meanLuma == null) return hold(null, current.stops)

        // Light in the scene = what the preview shows, minus the exposure it was shown at.
        val estimate = LumaCurve.stopsFor(meanLuma) - current.stops
        readings.addLast(estimate)
        while (readings.size > SMOOTHING) readings.removeFirst()
        val scene = readings.sorted()[readings.size / 2] // median: one odd frame (a passing car's lights) is ignored

        val base = baselineScene
        if (base == null) {
            baselineSamples += estimate
            if (baselineSamples.size >= BASELINE_FRAMES) baselineScene = baselineSamples.average()
            return hold(scene, current.stops)
        }

        val target = (baselineStops + (1 - config.keepDarkFraction) * (base - scene)).coerceIn(ladder.minStops, ladder.maxStops)
        val delta = target - current.stops
        val atLimit = target <= ladder.minStops + 1e-9 || target >= ladder.maxStops - 1e-9
        if (abs(delta) < 1e-9 || (!atLimit && abs(delta) < config.deadBandStops)) return hold(scene, target)

        val step = delta.coerceIn(-config.maxStepStops, config.maxStepStops)
        val next = ladder.settingFor(current.stops + step)
        val changed = next != current
        current = next
        return RampDecision(current, scene, target, changed, describe(current, target))
    }

    /** The camera did not end up on what was asked for (a rejected setting): carry on from what it really has. */
    fun resync(actual: ExposureSetting) {
        current = actual
    }

    private fun hold(scene: Double?, target: Double) = RampDecision(current, scene, target, false, describe(current, target))

    private fun describe(s: ExposureSetting, target: Double): String {
        val shutter = if (s.shutterSec >= 1.0) "${trim(s.shutterSec)}s" else "1/${(1.0 / s.shutterSec).roundToInt()}"
        val offset = s.stops - baselineStops
        return "$shutter · ISO ${s.iso} (${if (offset >= 0) "+" else ""}${"%.1f".format(java.util.Locale.US, offset)} stops)"
    }

    private fun trim(x: Double) = if (abs(x - x.roundToInt()) < 0.05) x.roundToInt().toString() else "%.1f".format(java.util.Locale.US, x)

    private companion object {
        const val SMOOTHING = 5
        const val BASELINE_FRAMES = 2
    }
}
