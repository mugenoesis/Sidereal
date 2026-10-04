package io.github.mugenoesis.sidereal.gimbal

/**
 * Basic PID controller, used to turn "how far off-center is the target"
 * into "how fast should the gimbal rotate to correct it."
 *
 * Tune kP/kI/kD per axis once you're testing on real hardware - these
 * defaults are a reasonable starting point, not a promise.
 */
class PidController(
    var kP: Double,
    var kI: Double = 0.0,
    var kD: Double = 0.0,
    private val outputMin: Double = -100.0,
    private val outputMax: Double = 100.0,
    // Error-units/sec. Real hardware testing found a fast, real subject
    // movement (or a shake-jump filter accepting one in a single step
    // rather than a smooth ramp) can produce a large frame-to-frame error
    // jump, which the derivative term turns into a single-frame output
    // spike well beyond what steady-state tracking needs - felt as a
    // "bobbing"/overshoot jolt distinct from the slow-oscillation overshoot
    // kD itself was raised to fix. Clamping the raw derivative before
    // multiplying by kD keeps that damping benefit for normal-range
    // corrections while capping how much any single big jump can spike the
    // output - standard "derivative kick" limiting.
    private val maxDerivative: Double = Double.MAX_VALUE
) {
    private var integral = 0.0
    private var previousError = 0.0
    private var hasPrevious = false

    /**
     * @param error current offset from target (e.g. pixels off-center, or
     *   normalized -1..1 offset - pick one convention and stay consistent
     *   with kP tuning).
     * @param dtSeconds time since last update call, for correct I/D terms.
     */
    fun update(error: Double, dtSeconds: Double): Double {
        if (dtSeconds <= 0.0) return 0.0

        integral += error * dtSeconds
        val rawDerivative = if (hasPrevious) (error - previousError) / dtSeconds else 0.0
        val derivative = rawDerivative.coerceIn(-maxDerivative, maxDerivative)

        previousError = error
        hasPrevious = true

        val output = kP * error + kI * integral + kD * derivative
        return output.coerceIn(outputMin, outputMax)
    }

    fun reset() {
        integral = 0.0
        previousError = 0.0
        hasPrevious = false
    }
}
