package io.github.mugenoesis.sidereal.sequence

/** Dark, bias and flat calibration frames for stacking - each opens with a prompt for whatever the user has to do by hand. */
object CalibrationPlanner {

    /** Fastest shutter the X5 offers - bias frames use it to capture read noise with no light signal. */
    const val FASTEST_SHUTTER = "SHUTTER_SPEED_1_8000"
    private const val FASTEST_SHUTTER_MS = 1L

    fun darks(count: Int, exposureMs: Long): List<SequenceStep> {
        require(count >= 1) { "need at least one frame" }
        return listOf<SequenceStep>(SequenceStep.Prompt("Cap the lens (no light), then tap Continue. Darks use your current ISO and shutter.")) +
            List(count) { SequenceStep.Capture(exposureMs, "dark") }
    }

    fun bias(count: Int, restoreShutter: String?): List<SequenceStep> {
        require(count >= 1) { "need at least one frame" }
        val steps = ArrayList<SequenceStep>()
        steps += SequenceStep.Prompt("Cap the lens (no light), then tap Continue. Bias uses your current ISO at the fastest shutter.")
        steps += SequenceStep.SetShutter(FASTEST_SHUTTER)
        repeat(count) { steps += SequenceStep.Capture(FASTEST_SHUTTER_MS, "bias") }
        if (restoreShutter != null) steps += SequenceStep.SetShutter(restoreShutter)
        return steps
    }

    fun flats(count: Int, exposureMs: Long): List<SequenceStep> {
        require(count >= 1) { "need at least one frame" }
        return listOf<SequenceStep>(SequenceStep.Prompt("Keep focus locked at your shooting distance and point at an evenly lit surface, then tap Continue.")) +
            List(count) { SequenceStep.Capture(exposureMs, "flat") }
    }
}
