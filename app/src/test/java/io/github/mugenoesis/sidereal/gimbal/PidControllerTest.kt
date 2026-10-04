package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Test

class PidControllerTest {

    @Test
    fun `pure proportional gain scales error directly`() {
        val pid = PidController(kP = 2.0)
        assertEquals(6.0, pid.update(error = 3.0, dtSeconds = 1.0), 1e-9)
    }

    @Test
    fun `zero or negative dt is ignored and returns zero without touching state`() {
        val pid = PidController(kP = 0.0, kD = 1.0)
        assertEquals(0.0, pid.update(error = 5.0, dtSeconds = 0.0), 1e-9)
        assertEquals(0.0, pid.update(error = 5.0, dtSeconds = -1.0), 1e-9)
        // previousError/hasPrevious must still be untouched by the ignored calls
        // above - if error=5.0 had leaked in as "previous", this call would see
        // a derivative of 0 (no change) instead of a fresh first-call 0.
        // Either way the value is 0 here, so what this really guards against is
        // hasPrevious having flipped true early: confirmed via the next call.
        assertEquals(0.0, pid.update(error = 5.0, dtSeconds = 1.0), 1e-9)
        // Now previousError really is 5.0 (set by the call above) - a genuine
        // change is detected correctly, proving the two dt<=0 calls above never
        // touched previousError/hasPrevious themselves.
        assertEquals(3.0, pid.update(error = 8.0, dtSeconds = 1.0), 1e-9)
    }

    @Test
    fun `integral term accumulates error over time`() {
        val pid = PidController(kP = 0.0, kI = 1.0)
        pid.update(error = 2.0, dtSeconds = 1.0) // integral = 2.0
        val output = pid.update(error = 2.0, dtSeconds = 1.0) // integral = 4.0
        assertEquals(4.0, output, 1e-9)
    }

    @Test
    fun `derivative term reacts to change in error since previous call`() {
        val pid = PidController(kP = 0.0, kD = 1.0)
        pid.update(error = 0.0, dtSeconds = 1.0) // seeds previousError, no derivative yet
        val output = pid.update(error = 5.0, dtSeconds = 1.0) // (5-0)/1 = 5
        assertEquals(5.0, output, 1e-9)
    }

    @Test
    fun `first call after construction has no derivative kick since there is no previous error`() {
        val pid = PidController(kP = 0.0, kD = 1.0)
        val output = pid.update(error = 100.0, dtSeconds = 1.0)
        assertEquals(0.0, output, 1e-9)
    }

    @Test
    fun `maxDerivative clamps a large single-frame error jump before it is scaled by kD`() {
        val pid = PidController(kP = 0.0, kD = 1.0, maxDerivative = 2.0)
        pid.update(error = 0.0, dtSeconds = 1.0)
        // raw derivative would be 50, clamped to 2 before multiplying by kD
        val output = pid.update(error = 50.0, dtSeconds = 1.0)
        assertEquals(2.0, output, 1e-9)
    }

    @Test
    fun `output is clamped to outputMin and outputMax`() {
        val pid = PidController(kP = 100.0, outputMin = -10.0, outputMax = 10.0)
        assertEquals(10.0, pid.update(error = 5.0, dtSeconds = 1.0), 1e-9)
        pid.reset()
        assertEquals(-10.0, pid.update(error = -5.0, dtSeconds = 1.0), 1e-9)
    }

    @Test
    fun `reset clears integral and derivative history`() {
        val pid = PidController(kP = 0.0, kI = 1.0, kD = 1.0)
        pid.update(error = 10.0, dtSeconds = 1.0) // integral=10, seeds previousError=10
        pid.reset()
        // If integral/previousError weren't cleared, this would include the
        // leftover integral of 10 plus a derivative kick from 10 -> 1. With a
        // clean reset, only this call's own integral contribution (1) should
        // show up, and the derivative term should be 0 (no previous error yet).
        val output = pid.update(error = 1.0, dtSeconds = 1.0)
        assertEquals(1.0, output, 1e-9)
    }
}
