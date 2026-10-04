package io.github.mugenoesis.sidereal.camera

/**
 * Generic stepping/cycling helpers extracted from MainActivity so they're
 * unit-testable in isolation. Both patterns exist because of the same
 * lesson learned the hard way on real hardware this session: deriving
 * "next" from the camera's live/pushed state gets permanently stuck the
 * moment a request is rejected or lags behind rapid taps, since the real
 * state never changes and the next press just recomputes the same target.
 * Tracking an independent position locally (only seeded from real state
 * once, on the first press) is the fix in both cases - see
 * FocusController.cycleIndex's doc comment for the fuller story.
 */
object CycleHelpers {

    /** Clamped (not wrapped) step through an ordinal-ordered enum array - used by the ISO/shutter/aperture/EV +/- steppers. */
    fun <T : Enum<T>> stepEnum(current: T, delta: Int, values: Array<T>): T {
        val next = (current.ordinal + delta).coerceIn(0, values.size - 1)
        return values[next]
    }

    /** Wrapped advance to the next entry in [options] - used by the anti-flicker/photo-format/photo-aspect-ratio/video-format cycle buttons. */
    fun <T> nextCycleValue(current: Int?, realValue: T?, options: List<T>): Pair<Int, T> {
        val startIndex = current ?: options.indexOf(realValue).coerceAtLeast(-1)
        val nextIndex = (startIndex + 1).mod(options.size)
        return nextIndex to options[nextIndex]
    }
}
