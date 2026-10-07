package io.github.mugenoesis.sidereal.sequence

/** Gimbal pose in degrees, in the same pitch/yaw convention `Gimbal.rotate()` uses. */
data class Attitude(val pitch: Float, val yaw: Float)

/**
 * One atomic thing a shooting sequence does. Every feature built on the
 * intervalometer (timelapse, dithering, panorama, calibration frames) is
 * just a different planner producing a different list of these, which a
 * single [SequenceRunner] then executes - so the planners are pure
 * functions testable without a camera or a gimbal.
 */
sealed class SequenceStep {
    /** Command the gimbal to an absolute attitude. Does not wait for arrival - follow with [Settle]. */
    data class MoveTo(val pitch: Float, val yaw: Float) : SequenceStep()

    /** Hold still for [ms] so motor vibration damps out before the shutter opens. */
    data class Settle(val ms: Long) : SequenceStep()

    /** Take one photo and wait until the camera has finished with it. [exposureMs] is how long the shutter is open. */
    data class Capture(val exposureMs: Long, val label: String = "") : SequenceStep()

    /** Sleep until [offsetMs] after the sequence started; a no-op if that moment has already passed. */
    data class WaitUntil(val offsetMs: Long) : SequenceStep()

    /** Stop and show [message] until the user taps continue (e.g. "cap the lens"). */
    data class Prompt(val message: String) : SequenceStep()

    /** Change the camera's shutter speed; [shutterName] is a `SettingsDefinitions.ShutterSpeed` enum name. */
    data class SetShutter(val shutterName: String) : SequenceStep()

    /** Start day-to-night exposure ramping for the rest of the sequence (once, before the first frame). */
    data class BeginRamp(val config: RampConfig) : SequenceStep()

    /** Meter the scene now and set the camera's shutter/ISO for the next frame, per the ramp begun by [BeginRamp]. */
    object AdaptExposure : SequenceStep()
}
