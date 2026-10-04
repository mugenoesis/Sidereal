package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualGimbalMathTest {

    @Test
    fun `rateFromStick scales full deflection to max speed`() {
        assertEquals(30f, ManualGimbalMath.rateFromStick(1f, maxSpeedDegPerSec = 30f), 1e-6f)
        assertEquals(-30f, ManualGimbalMath.rateFromStick(-1f, maxSpeedDegPerSec = 30f), 1e-6f)
        assertEquals(0f, ManualGimbalMath.rateFromStick(0f, maxSpeedDegPerSec = 30f), 1e-6f)
        assertEquals(15f, ManualGimbalMath.rateFromStick(0.5f, maxSpeedDegPerSec = 30f), 1e-6f)
    }

    @Test
    fun `nextTarget accumulates rate over dt when no range is known yet`() {
        val result = ManualGimbalMath.nextTarget(currentTarget = 10f, rateDegPerSec = 30f, dtSeconds = 0.1f, range = null)
        assertEquals(13f, result, 1e-4f)
    }

    @Test
    fun `nextTarget does not clamp at all when range is null, even far outside any sane bound`() {
        // Deliberate: an unknown capability must NOT fall back to a guessed
        // clamp - see ManualGimbalController's doc comment on why a wrong
        // guessed range permanently pins the target once hit.
        val result = ManualGimbalMath.nextTarget(currentTarget = 1000f, rateDegPerSec = 1000f, dtSeconds = 1f, range = null)
        assertEquals(2000f, result, 1e-4f)
    }

    @Test
    fun `nextTarget clamps to the given range once accumulation exceeds it`() {
        val range = -30f..30f
        val result = ManualGimbalMath.nextTarget(currentTarget = 29f, rateDegPerSec = 30f, dtSeconds = 1f, range = range)
        assertEquals(30f, result, 1e-6f)
    }

    @Test
    fun `nextTarget clamps at the low end of the range too`() {
        val range = -30f..30f
        val result = ManualGimbalMath.nextTarget(currentTarget = -29f, rateDegPerSec = -30f, dtSeconds = 1f, range = range)
        assertEquals(-30f, result, 1e-6f)
    }

    @Test
    fun `nextTarget does not get permanently pinned - moving back inside range recovers`() {
        // This is the exact failure mode the doc comment warns about with a
        // WRONG clamp: once pinned at a boundary, coerceIn would keep it there
        // forever. With the real range, moving the rate back the other way
        // must be free to leave the boundary again.
        val range = -30f..30f
        val pinned = ManualGimbalMath.nextTarget(currentTarget = 30f, rateDegPerSec = 30f, dtSeconds = 1f, range = range)
        assertEquals(30f, pinned, 1e-6f)
        val recovered = ManualGimbalMath.nextTarget(currentTarget = pinned, rateDegPerSec = -10f, dtSeconds = 1f, range = range)
        assertEquals(20f, recovered, 1e-6f)
    }
}
