package io.github.mugenoesis.sidereal.sequence

import kotlin.math.abs

/**
 * Whether the gimbal has reached a commanded pose. `rotate()`'s completion
 * callback is a fixed ~2s client-side timeout, not a real ack (see NOTES),
 * so arrival has to be judged from the pushed attitude instead.
 */
object GimbalArrival {

    /** The gimbal reports attitude in whole 0.1 degree steps, so that's the finest pose it can be seen to reach. */
    const val RESOLUTION_DEG = 0.1f

    /** Nearest pose on the gimbal's reporting grid - targets off the grid can never be matched within a tight tolerance. */
    fun quantize(deg: Float): Float = Math.round(deg / RESOLUTION_DEG) * RESOLUTION_DEG

    fun errorDeg(current: Attitude, target: Attitude): Float =
        maxOf(abs(current.pitch - target.pitch), abs(wrap(current.yaw - target.yaw)))

    fun hasArrived(current: Attitude, target: Attitude, toleranceDeg: Float): Boolean =
        errorDeg(current, target) <= toleranceDeg

    /** Shortest signed angular difference, in (-180, 180]. */
    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }
}
