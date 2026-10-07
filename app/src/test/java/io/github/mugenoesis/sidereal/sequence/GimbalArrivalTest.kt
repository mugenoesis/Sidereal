package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalArrivalTest {

    @Test
    fun `arrived when both axes are within tolerance`() {
        assertTrue(GimbalArrival.hasArrived(Attitude(-10.05f, 40.1f), Attitude(-10f, 40f), toleranceDeg = 0.2f))
    }

    @Test
    fun `not arrived when either axis is off`() {
        assertFalse(GimbalArrival.hasArrived(Attitude(-9f, 40f), Attitude(-10f, 40f), 0.2f))
        assertFalse(GimbalArrival.hasArrived(Attitude(-10f, 41f), Attitude(-10f, 40f), 0.2f))
    }

    @Test
    fun `yaw wraps around plus or minus 180`() {
        assertTrue(GimbalArrival.hasArrived(Attitude(0f, 179.9f), Attitude(0f, -179.9f), 0.5f))
        assertFalse(GimbalArrival.hasArrived(Attitude(0f, 179f), Attitude(0f, -179f), 0.5f))
    }

    @Test
    fun `error is the larger of the two axis errors`() {
        assertEquals(3f, GimbalArrival.errorDeg(Attitude(1f, 0f), Attitude(-2f, 1f)), 1e-5f)
    }

    @Test
    fun `quantize rounds to the gimbal's reporting resolution`() {
        assertEquals(1.7f, GimbalArrival.quantize(1.6915741f), 1e-5f)
        assertEquals(-34.5f, GimbalArrival.quantize(-34.475357f), 1e-5f)
        assertEquals(0f, GimbalArrival.quantize(0.04f), 1e-5f)
    }

    @Test
    fun `a quantized target is always reachable within one resolution step of tolerance`() {
        val raw = Attitude(1.6915741f, -34.475357f)
        val target = Attitude(GimbalArrival.quantize(raw.pitch), GimbalArrival.quantize(raw.yaw))
        // The gimbal can only report 0.1 steps; sitting on the nearest one counts as arrived.
        assertTrue(GimbalArrival.hasArrived(Attitude(1.7f, -34.5f), target, toleranceDeg = 0.15f))
        assertFalse(GimbalArrival.hasArrived(Attitude(1.5f, -34.5f), target, toleranceDeg = 0.15f))
    }
}
