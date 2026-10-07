package io.github.mugenoesis.sidereal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeMatcherTest {

    private fun take(name: String, audioStart: Long, cameraStart: Long? = null) =
        SyncSidecar(name, audioStart, cameraStart, null)

    @Test
    fun `picks the audio take that started closest to the video`() {
        val takes = listOf(take("a.m4a", 1_000_000), take("b.m4a", 5_000_000), take("c.m4a", 9_000_000))
        assertEquals("b.m4a", TakeMatcher.best(videoStartEpochMs = 5_040_000, takes = takes)?.audioFileName)
    }

    @Test
    fun `prefers the camera's own recorded start over the audio start when it has one`() {
        val takes = listOf(take("a.m4a", audioStart = 2_000_300, cameraStart = 2_000_000), take("b.m4a", audioStart = 2_050_000))
        assertEquals("a.m4a", TakeMatcher.best(videoStartEpochMs = 2_000_100, takes = takes)?.audioFileName)
    }

    @Test
    fun `nothing is suggested when every take is too far from the video`() {
        val takes = listOf(take("a.m4a", 0))
        assertNull(TakeMatcher.best(videoStartEpochMs = 10 * 60_000, takes = takes, toleranceMs = 60_000))
    }

    @Test
    fun `no takes means no match`() {
        assertNull(TakeMatcher.best(1_000, emptyList()))
    }

    @Test
    fun `on a tie the earlier take wins`() {
        val takes = listOf(take("late.m4a", 1_100), take("early.m4a", 900))
        assertEquals("early.m4a", TakeMatcher.best(1_000, takes)?.audioFileName)
    }
}
