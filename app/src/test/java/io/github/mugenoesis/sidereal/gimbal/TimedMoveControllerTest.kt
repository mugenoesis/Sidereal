package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only the parts of TimedMoveController reachable without a live
 * Gimbal/CoroutineScope - captureA/B/clear/cancel/pause/resume. start()
 * itself isn't exercised here: it requires a real dji.sdk.gimbal.Gimbal
 * parameter, and constructing/touching one in a plain JVM unit test is
 * unsafe (see CameraGateway's doc comment on VerifyError). The eased
 * interpolation math it depends on is covered separately by
 * TimedMoveMathTest.
 *
 * DJIConnectionManager.gimbalState defaults to a StateFlow<GimbalState?>
 * of null in a JVM test (nothing ever connects it), so captureA()/captureB()
 * always see no attitude here - that's exactly the "no gimbal connected yet"
 * behavior these tests exercise.
 */
class TimedMoveControllerTest {

    @Test
    fun `captureA without a connected gimbal does not advance state past Idle`() {
        val controller = TimedMoveController()
        controller.captureA()
        assertEquals(TimedMoveController.State.Idle, controller.state.value)
    }

    @Test
    fun `capturing both points without a connected gimbal never reaches Ready`() {
        val controller = TimedMoveController()
        controller.captureA()
        controller.captureB()
        // Both captures resolved to a null point (no live attitude), so
        // maybeMarkReady's null-check should keep this at Idle rather than
        // incorrectly transitioning to Ready with garbage points.
        assertEquals(TimedMoveController.State.Idle, controller.state.value)
    }

    @Test
    fun `clear resets state to Idle`() {
        val controller = TimedMoveController()
        controller.captureA()
        controller.clear()
        assertEquals(TimedMoveController.State.Idle, controller.state.value)
    }

    @Test
    fun `cancel resets state to Idle even when nothing was running`() {
        val controller = TimedMoveController()
        controller.cancel()
        assertEquals(TimedMoveController.State.Idle, controller.state.value)
    }

    @Test
    fun `pause and resume do not throw and do not by themselves change published state`() {
        val controller = TimedMoveController()
        controller.pause()
        controller.resume()
        // Neither call has a running job to react to (start() was never
        // called), so state should be untouched - just confirming these are
        // safe no-ops rather than throwing.
        assertEquals(TimedMoveController.State.Idle, controller.state.value)
    }

    @Test
    fun `Point and State data classes carry their fields through unchanged`() {
        val a = TimedMoveController.Point(pitch = 1.0, yaw = 2.0, roll = 3.0)
        val b = TimedMoveController.Point(pitch = 4.0, yaw = 5.0, roll = 6.0)
        val ready = TimedMoveController.State.Ready(a, b)
        assertEquals(a, ready.pointA)
        assertEquals(b, ready.pointB)
        assertTrue(ready.pointA.pitch == 1.0)
    }
}
