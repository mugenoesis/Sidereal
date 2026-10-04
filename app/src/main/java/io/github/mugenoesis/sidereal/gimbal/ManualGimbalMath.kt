package io.github.mugenoesis.sidereal.gimbal

/**
 * Pure joystick-to-target math for ManualGimbalController, extracted so
 * it's unit-testable without a live Gimbal/CoroutineScope (see
 * CameraGateway's doc comment for why touching DJI SDK types in a JVM
 * unit test is generally unsafe - this class avoids the question entirely
 * by working only in Float/ClosedFloatingPointRange).
 */
object ManualGimbalMath {

    /** Joystick deflection (-1..1) at [maxSpeedDegPerSec] full stick -> a rate in deg/sec. */
    fun rateFromStick(stickValue: Float, maxSpeedDegPerSec: Float): Float = stickValue * maxSpeedDegPerSec

    /**
     * Accumulates [rateDegPerSec] into [currentTarget] over [dtSeconds], then clamps to
     * [range] if the gimbal's real range is known yet (null = don't clamp - see
     * ManualGimbalController.startSending's doc comment on why an unknown range
     * must NOT fall back to a guessed clamp).
     */
    fun nextTarget(currentTarget: Float, rateDegPerSec: Float, dtSeconds: Float, range: ClosedFloatingPointRange<Float>?): Float {
        val accumulated = currentTarget + rateDegPerSec * dtSeconds
        return range?.let { accumulated.coerceIn(it) } ?: accumulated
    }
}
