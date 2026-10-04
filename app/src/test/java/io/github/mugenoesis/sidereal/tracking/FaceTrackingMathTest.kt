package io.github.mugenoesis.sidereal.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackingMathTest {

    @Test
    fun `isWithinDeadband is true only for errors strictly smaller than the deadband`() {
        assertTrue(FaceTrackingMath.isWithinDeadband(0.01, deadband = 0.035))
        assertTrue(FaceTrackingMath.isWithinDeadband(-0.01, deadband = 0.035))
        assertFalse(FaceTrackingMath.isWithinDeadband(0.05, deadband = 0.035))
        assertFalse(FaceTrackingMath.isWithinDeadband(0.035, deadband = 0.035))
    }

    @Test
    fun `findReacquireCandidateId picks the nearest face within range`() {
        val faces = listOf(
            FaceTrackingMath.FaceCenter(trackingId = 1, centerX = 0.9, centerY = 0.9),
            FaceTrackingMath.FaceCenter(trackingId = 2, centerX = 0.51, centerY = 0.51)
        )
        val result = FaceTrackingMath.findReacquireCandidateId(faces, lastFaceX = 0.5, lastFaceY = 0.5, maxDistance = 0.25)
        assertEquals(2, result)
    }

    @Test
    fun `findReacquireCandidateId returns null when the nearest face is outside maxDistance`() {
        val faces = listOf(FaceTrackingMath.FaceCenter(trackingId = 1, centerX = 0.9, centerY = 0.9))
        val result = FaceTrackingMath.findReacquireCandidateId(faces, lastFaceX = 0.5, lastFaceY = 0.5, maxDistance = 0.25)
        assertNull(result)
    }

    @Test
    fun `findReacquireCandidateId returns null for an empty face list`() {
        val result = FaceTrackingMath.findReacquireCandidateId(emptyList(), lastFaceX = 0.5, lastFaceY = 0.5, maxDistance = 0.25)
        assertNull(result)
    }

    @Test
    fun `coastRate decays linearly to zero over the hold window`() {
        val start = FaceTrackingMath.coastRate(lastKnownRate = 20.0, maxRate = 20.0, elapsedSinceLostMs = 0, holdMillis = 1000)
        assertEquals(20.0, start!!, 1e-9)

        val half = FaceTrackingMath.coastRate(lastKnownRate = 20.0, maxRate = 20.0, elapsedSinceLostMs = 500, holdMillis = 1000)
        assertEquals(10.0, half!!, 1e-9)
    }

    @Test
    fun `coastRate clamps the last known rate to maxRate before decaying`() {
        val result = FaceTrackingMath.coastRate(lastKnownRate = 90.0, maxRate = 20.0, elapsedSinceLostMs = 0, holdMillis = 1000)
        assertEquals(20.0, result!!, 1e-9)
    }

    @Test
    fun `coastRate returns null once the hold window has expired`() {
        val result = FaceTrackingMath.coastRate(lastKnownRate = 20.0, maxRate = 20.0, elapsedSinceLostMs = 3501, holdMillis = 3500)
        assertNull(result)
    }

    @Test
    fun `shouldResyncTarget is false for small gaps and true beyond the threshold`() {
        assertFalse(FaceTrackingMath.shouldResyncTarget(target = 10f, actual = 12f, threshold = 15f))
        assertTrue(FaceTrackingMath.shouldResyncTarget(target = 10f, actual = 30f, threshold = 15f))
    }
}
