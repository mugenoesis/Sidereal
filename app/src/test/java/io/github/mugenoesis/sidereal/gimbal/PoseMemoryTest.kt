package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseMemoryTest {

    @Test fun `nothing is known before anything is recorded`() {
        assertNull(PoseMemory().poseBefore(10_000))
    }

    @Test fun `the pose from before the sleep report is returned, not the one that was drooping at the report`() {
        val m = PoseMemory(lead = 2_000)
        for (t in 0..5_000 step 500) m.record(t.toLong(), -10f, -30f) // steady
        for (t in 5_500..6_500 step 500) m.record(t.toLong(), -40f - (t - 5_500) / 10f, -35f) // drooping once asleep
        assertEquals(PoseMemory.Pose(-10f, -30f), m.poseBefore(sleepReportedAtMs = 6_500))
    }

    @Test fun `with less history than the lead the oldest pose is used`() {
        val m = PoseMemory(lead = 2_000)
        m.record(5_000, -12f, 40f)
        m.record(5_500, -80f, 41f)
        assertEquals(PoseMemory.Pose(-12f, 40f), m.poseBefore(5_600))
    }

    @Test fun `old samples are dropped so the memory stays small`() {
        val m = PoseMemory(lead = 2_000, keepMs = 10_000)
        for (t in 0..100_000 step 100) m.record(t.toLong(), 0f, t / 1000f)
        assertEquals(true, m.size < 200)
    }

    @Test fun `forgetting clears it`() {
        val m = PoseMemory()
        m.record(0, 1f, 2f)
        m.clear()
        assertNull(m.poseBefore(100))
    }
}
