package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPlannerTest {

    @Test
    fun `darks ask the user to cap the lens first then fire N identical exposures`() {
        val steps = CalibrationPlanner.darks(count = 5, exposureMs = 13_000)
        assertTrue(steps.first() is SequenceStep.Prompt)
        assertTrue((steps.first() as SequenceStep.Prompt).message.contains("cap", ignoreCase = true))
        val captures = steps.filterIsInstance<SequenceStep.Capture>()
        assertEquals(5, captures.size)
        assertTrue(captures.all { it.exposureMs == 13_000L && it.label == "dark" })
    }

    @Test
    fun `bias frames switch to the fastest shutter then restore the previous one`() {
        val steps = CalibrationPlanner.bias(count = 20, restoreShutter = "SHUTTER_SPEED_1_2")
        val setShutters = steps.filterIsInstance<SequenceStep.SetShutter>()
        assertEquals(listOf("SHUTTER_SPEED_1_8000", "SHUTTER_SPEED_1_2"), setShutters.map { it.shutterName })
        val firstSet = steps.indexOfFirst { it is SequenceStep.SetShutter }
        val firstCapture = steps.indexOfFirst { it is SequenceStep.Capture }
        val lastCapture = steps.indexOfLast { it is SequenceStep.Capture }
        val lastSet = steps.indexOfLast { it is SequenceStep.SetShutter }
        assertTrue(firstSet < firstCapture)
        assertTrue(lastSet > lastCapture)
        assertEquals(20, steps.count { it is SequenceStep.Capture })
    }

    @Test
    fun `bias without a known previous shutter just doesn't restore`() {
        val steps = CalibrationPlanner.bias(count = 3, restoreShutter = null)
        assertEquals(1, steps.count { it is SequenceStep.SetShutter })
    }

    @Test
    fun `bias exposures are labelled and are capped at the fastest shutter's duration`() {
        val captures = CalibrationPlanner.bias(count = 2, restoreShutter = null).filterIsInstance<SequenceStep.Capture>()
        assertTrue(captures.all { it.label == "bias" && it.exposureMs <= 1 })
    }

    @Test
    fun `flats prompt for even illumination and focus then capture`() {
        val steps = CalibrationPlanner.flats(count = 15, exposureMs = 200)
        val prompt = (steps.first() as SequenceStep.Prompt).message
        assertTrue(prompt.contains("focus", ignoreCase = true))
        assertTrue(prompt.contains("even", ignoreCase = true))
        assertEquals(15, steps.count { it is SequenceStep.Capture && it.label == "flat" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero frames is rejected`() {
        CalibrationPlanner.darks(count = 0, exposureMs = 1_000)
    }
}
