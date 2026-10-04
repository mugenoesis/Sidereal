package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedMoveMathTest {

    @Test
    fun `ease starts at 0 and ends at 1`() {
        assertEquals(0f, TimedMoveMath.ease(0f), 1e-6f)
        assertEquals(1f, TimedMoveMath.ease(1f), 1e-6f)
    }

    @Test
    fun `ease is symmetric around the midpoint at 0_5`() {
        assertEquals(0.5f, TimedMoveMath.ease(0.5f), 1e-6f)
    }

    @Test
    fun `ease is slower than linear near the start and end (ease-in-out shape)`() {
        // At t=0.25 a linear ramp would be 0.25 - ease-in-out should be slower (smaller).
        assert(TimedMoveMath.ease(0.25f) < 0.25f)
        // At t=0.75 a linear ramp would be 0.75 - ease-in-out should already be further along.
        assert(TimedMoveMath.ease(0.75f) > 0.75f)
    }

    @Test
    fun `ease moves at a constant rate through the middle cruise region, not a rushed S-curve`() {
        // Equal time steps well inside the cruise window (RAMP_FRACTION=0.2,
        // so 0.3..0.7 is comfortably clear of both ramps) should produce
        // equal position steps - this is the actual real-world complaint
        // the trapezoidal profile replaced a cubic ease-in-out for: on a
        // 30s move the cubic crammed a large, uneven share of the total
        // motion into this same window instead of a steady rate.
        val step1 = TimedMoveMath.ease(0.5f) - TimedMoveMath.ease(0.4f)
        val step2 = TimedMoveMath.ease(0.6f) - TimedMoveMath.ease(0.5f)
        assertEquals(step1, step2, 1e-5f)
    }

    @Test
    fun `ease ramps smoothly (no jump) at the boundary between ramp-up and cruise`() {
        // Continuity check at t = RAMP_FRACTION (0.2) - approaching from
        // just inside the ramp and just inside the cruise should agree,
        // confirming the two formulas actually meet rather than stepping.
        assertEquals(TimedMoveMath.ease(0.2f), TimedMoveMath.ease(0.2001f), 1e-3f)
    }

    @Test
    fun `lerp interpolates linearly between start and end`() {
        assertEquals(0.0, TimedMoveMath.lerp(0.0, 10.0, 0.0), 1e-9)
        assertEquals(10.0, TimedMoveMath.lerp(0.0, 10.0, 1.0), 1e-9)
        assertEquals(5.0, TimedMoveMath.lerp(0.0, 10.0, 0.5), 1e-9)
        assertEquals(2.5, TimedMoveMath.lerp(5.0, 0.0, 0.5), 1e-9)
    }

    @Test
    fun `progress is clamped to 0 to 1 and computed as a fraction of duration`() {
        assertEquals(0f, TimedMoveMath.progress(elapsedMillis = -100, durationMillis = 1000), 1e-6f)
        assertEquals(0.5f, TimedMoveMath.progress(elapsedMillis = 500, durationMillis = 1000), 1e-6f)
        assertEquals(1f, TimedMoveMath.progress(elapsedMillis = 5000, durationMillis = 1000), 1e-6f)
    }

    @Test
    fun `pointAt interpolates pitch and yaw between A and B, excluding roll`() {
        val a = TimedMoveController.Point(pitch = 0.0, yaw = -10.0, roll = 3.0)
        val b = TimedMoveController.Point(pitch = 20.0, yaw = 10.0, roll = 7.0)

        val (startPitch, startYaw) = TimedMoveMath.pointAt(a, b, t = 0f)
        assertEquals(0.0, startPitch, 1e-6)
        assertEquals(-10.0, startYaw, 1e-6)

        val (endPitch, endYaw) = TimedMoveMath.pointAt(a, b, t = 1f)
        assertEquals(20.0, endPitch, 1e-6)
        assertEquals(10.0, endYaw, 1e-6)

        val (midPitch, midYaw) = TimedMoveMath.pointAt(a, b, t = 0.5f)
        assertEquals(10.0, midPitch, 1e-6) // ease(0.5) == 0.5, so this is also the linear midpoint
        assertEquals(0.0, midYaw, 1e-6)
    }
}
