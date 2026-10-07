package io.github.mugenoesis.sidereal.camera

/** One manual-focus nudge: 1% of the ring's range (at least 1), clamped to 0..[upperBound]. */
object FocusRingStepper {
    fun next(current: Int, direction: Int, upperBound: Int): Int {
        val step = (upperBound / 100).coerceAtLeast(1)
        return (current + direction * step).coerceIn(0, upperBound)
    }
}
