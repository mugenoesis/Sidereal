package io.github.mugenoesis.sidereal.gimbal

/**
 * Pure ease/interpolation math for TimedMoveController's A -> B move,
 * extracted so it's unit-testable without a live Gimbal/scope (see
 * CameraGateway's doc comment for why touching DJI SDK types in a JVM
 * unit test is generally unsafe - this class avoids the whole question by
 * not needing any of them).
 */
object TimedMoveMath {

    // Fraction of the total duration spent ramping up at the start, and
    // again ramping down at the end - the remaining (1 - 2*RAMP_FRACTION)
    // is spent at a constant rate. Replaced a former full-duration cubic
    // ease-in-out after a real user report: on a 30s move, that curve's
    // aggressive S-shape was clearly visible as "slow, slow, sudden rush
    // through the middle, slow again" - nearly half the cubic's total
    // distance happened within the middle 20% of the time. A trapezoidal
    // profile keeps a brief smoothing ramp at each end (avoiding the
    // robotic instant start/stop a purely linear move would have) while
    // spending most of the duration moving at a steady, predictable rate
    // instead of concentrating motion into a rushed middle burst.
    private const val RAMP_FRACTION = 0.2f

    /**
     * Trapezoidal velocity profile: ramps linearly from rest up to a
     * constant cruise speed over the first RAMP_FRACTION of the duration,
     * holds that speed through the middle, then ramps back down to rest
     * over the last RAMP_FRACTION - see RAMP_FRACTION's doc comment for
     * why this replaced a full-duration cubic ease. peakSpeed is whatever
     * makes the total area under the velocity curve (i.e. total distance)
     * equal 1, so this still lands exactly on B at t=1 regardless of
     * RAMP_FRACTION's value.
     */
    fun ease(t: Float): Float {
        val r = RAMP_FRACTION
        val peakSpeed = 1f / (1f - r)
        return when {
            t <= r -> 0.5f * peakSpeed * (t * t) / r
            t >= 1f - r -> 1f - ease(1f - t)
            else -> 0.5f * peakSpeed * r + peakSpeed * (t - r)
        }
    }

    fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t

    /** Progress (0..1) for [elapsedMillis] into a move lasting [durationMillis], clamped. */
    fun progress(elapsedMillis: Long, durationMillis: Long): Float =
        (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    /**
     * The eased pitch/yaw for [pointA] -> [pointB] at raw progress [t] (0..1,
     * pre-easing). Deliberately excludes roll - see TimedMoveController.start's
     * doc comment on why an explicit roll() gets every frame rejected on real
     * hardware.
     */
    fun pointAt(pointA: TimedMoveController.Point, pointB: TimedMoveController.Point, t: Float): Pair<Double, Double> {
        val easedT = ease(t).toDouble()
        val pitch = lerp(pointA.pitch, pointB.pitch, easedT)
        val yaw = lerp(pointA.yaw, pointB.yaw, easedT)
        return pitch to yaw
    }
}
