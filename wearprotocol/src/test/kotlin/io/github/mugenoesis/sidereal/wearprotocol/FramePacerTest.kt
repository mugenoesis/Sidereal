package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    @Test
    fun `the first frame goes straight away`() {
        assertTrue(FramePacer(minIntervalMs = 100).shouldSend(nowMs = 0))
    }

    @Test
    fun `frames are held back until the interval has passed`() {
        val p = FramePacer(minIntervalMs = 100)
        p.onSent(0, durationMs = 5)
        assertFalse(p.shouldSend(50))
        assertTrue(p.shouldSend(100))
    }

    @Test
    fun `nothing is sent while a previous frame is still going out - the freshest frame beats a queue`() {
        val p = FramePacer(minIntervalMs = 100)
        p.onSendStarted()
        assertFalse(p.shouldSend(10_000))
        p.onSent(10_000, durationMs = 20)
        assertTrue(p.shouldSend(10_100))
    }

    @Test
    fun `slow sends back the rate off`() {
        val p = FramePacer(minIntervalMs = 100, maxIntervalMs = 800)
        p.onSent(0, durationMs = 300)
        assertTrue("interval=${p.currentIntervalMs}", p.currentIntervalMs > 100)
    }

    @Test
    fun `the interval never exceeds the maximum`() {
        val p = FramePacer(minIntervalMs = 100, maxIntervalMs = 800)
        repeat(20) { p.onSent(it * 1000L, durationMs = 2_000) }
        assertEquals(800L, p.currentIntervalMs)
    }

    @Test
    fun `fast sends bring the rate back up to the minimum interval`() {
        val p = FramePacer(minIntervalMs = 100, maxIntervalMs = 800)
        repeat(5) { p.onSent(it * 1000L, durationMs = 2_000) }
        repeat(30) { p.onSent(10_000 + it * 100L, durationMs = 5) }
        assertEquals(100L, p.currentIntervalMs)
    }

    @Test
    fun `thumbnails keep the aspect ratio and fit the longest side`() {
        assertEquals(240 to 135, Thumbnail.fit(1920, 1080, 240))
        assertEquals(135 to 240, Thumbnail.fit(1080, 1920, 240))
        assertEquals(240 to 180, Thumbnail.fit(1200, 900, 240))
    }

    @Test
    fun `a source smaller than the target is not enlarged`() {
        assertEquals(100 to 50, Thumbnail.fit(100, 50, 240))
    }

    @Test
    fun `a degenerate source gets a safe size`() {
        assertEquals(1 to 1, Thumbnail.fit(0, 0, 240))
    }
}
