package io.github.mugenoesis.sidereal.sequence

import kotlin.math.ln

/** Aperture arithmetic on the camera's enum names (`F_1_DOT_7`, `F_8`), kept free of SDK types so it can be tested. */
object ApertureMath {

    /** "F_1_DOT_7" -> 1.7, "F_8" -> 8.0; null if the name is not an aperture. */
    fun fNumber(name: String): Double? {
        val m = Regex("F_(\\d+)(?:_DOT_(\\d+))?").matchEntire(name) ?: return null
        val whole = m.groupValues[1]
        val fraction = m.groupValues[2]
        return (if (fraction.isEmpty()) whole else "$whole.$fraction").toDoubleOrNull()
    }

    /** Light gained, in stops, by going from aperture [from] to [to] (positive = opening up); null if either is unreadable. */
    fun stopsGained(from: String?, to: String?): Double? {
        val a = from?.let(::fNumber) ?: return null
        val b = to?.let(::fNumber) ?: return null
        return 2 * ln(a / b) / ln(2.0)
    }

    /** The real apertures among [names], widest (smallest f-number) first. */
    fun widestFirst(names: List<String>): List<String> =
        names.mapNotNull { n -> fNumber(n)?.let { n to it } }.sortedBy { it.second }.map { it.first }
}
