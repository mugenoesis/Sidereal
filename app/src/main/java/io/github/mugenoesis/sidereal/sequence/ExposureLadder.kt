package io.github.mugenoesis.sidereal.sequence

import kotlin.math.abs
import kotlin.math.ln

private fun log2(x: Double) = ln(x) / ln(2.0)

data class ShutterOption(val name: String, val seconds: Double)
data class IsoOption(val name: String, val iso: Int)

/** One shutter + ISO pair the camera can be set to; [stops] is its total exposure (shutter stops plus ISO stops over 100). */
data class ExposureSetting(val shutterName: String, val shutterSec: Double, val isoName: String, val iso: Int) {
    val stops: Double get() = log2(shutterSec) + log2(iso / 100.0)
}

/**
 * Turns "this much exposure" into the shutter and ISO to set. The camera's shutter moves in thirds of a stop but its
 * ISO only in whole stops, so a gradual ramp is built the usual "holy grail" way: lengthen the shutter up to the
 * longest allowed, and when more light is needed raise the ISO one stop while shortening the shutter by the same
 * amount, so the exposure itself never jumps.
 *
 * @param maxShutterSec longest shutter allowed (the interval has to fit it)
 * @param maxIso highest ISO allowed (noise)
 */
class ExposureLadder(
    private val shutters: List<ShutterOption>,
    private val isos: List<IsoOption>,
    maxShutterSec: Double,
    maxIso: Int
) {
    private val allowedShutters = shutters.filter { it.seconds <= maxShutterSec + 1e-9 }.sortedBy { it.seconds }
    private val allowedIsos = isos.filter { it.iso <= maxIso }.sortedBy { it.iso }

    init {
        require(allowedShutters.isNotEmpty()) { "no shutter speed fits within ${maxShutterSec}s" }
        require(allowedIsos.isNotEmpty()) { "no ISO at or below $maxIso" }
    }

    private fun stopsOf(s: ShutterOption, i: IsoOption) = log2(s.seconds) + log2(i.iso / 100.0)

    val minStops: Double = stopsOf(allowedShutters.first(), allowedIsos.first())
    val maxStops: Double = stopsOf(allowedShutters.last(), allowedIsos.last())

    private val shutterMaxStops = log2(allowedShutters.last().seconds)

    /** The achievable setting closest to [stops] (clamped to the limits). */
    fun settingFor(stops: Double): ExposureSetting {
        val target = stops.coerceIn(minStops, maxStops)
        // Lowest ISO whose shutter can still reach the target: shutter first, ISO only when it has to.
        val iso = allowedIsos.firstOrNull { target - log2(it.iso / 100.0) <= shutterMaxStops + 1e-9 } ?: allowedIsos.last()
        val shutterStops = target - log2(iso.iso / 100.0)
        val shutter = allowedShutters.minByOrNull { abs(log2(it.seconds) - shutterStops) }!!
        return ExposureSetting(shutter.name, shutter.seconds, iso.name, iso.iso)
    }

    /** The setting for these camera enum names (looked up in the full lists, so a value past the caps still resolves). */
    fun nearest(shutterName: String, isoName: String): ExposureSetting {
        val s = shutters.firstOrNull { it.name == shutterName } ?: throw IllegalArgumentException("unknown shutter $shutterName")
        val i = isos.firstOrNull { it.name == isoName } ?: throw IllegalArgumentException("unknown ISO $isoName")
        return ExposureSetting(s.name, s.seconds, i.name, i.iso)
    }
}
