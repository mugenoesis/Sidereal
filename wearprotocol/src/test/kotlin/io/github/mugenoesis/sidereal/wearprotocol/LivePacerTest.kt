package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePacerTest {

    /** Sends frame number [n] at [sentAt] and has the watch confirm it [rtt] ms later; returns the time of the confirmation. */
    private fun roundTrip(p: LivePacer, n: Int, sentAt: Long, rtt: Long): Long {
        p.onSent(sentAt)
        p.onAck(sentAt + rtt, n)
        return sentAt + rtt
    }

    @Test
    fun `the first frame goes at once`() {
        assertTrue(LivePacer().shouldSend(0))
    }

    @Test
    fun `until it knows how fast the link is, only one frame is ever out at a time`() {
        val p = LivePacer()
        p.onSent(0)
        assertFalse(p.shouldSend(500))
        p.onAck(100, 1)
        assertTrue(p.shouldSend(200))
    }

    @Test
    fun `a frame is never sent into a backlog - delivery, not writing, is what counts`() {
        val p = LivePacer()
        p.onSent(0)
        // the write returned instantly but the watch has not confirmed it: still nothing more goes out
        for (t in 100..2_000 step 100) assertFalse("at $t", p.shouldSend(t.toLong()))
    }

    @Test
    fun `frames are held for the minimum interval even if the last was confirmed at once`() {
        val p = LivePacer(minIntervalMs = 60)
        p.onSent(0)
        p.onAck(5, 1)
        assertFalse(p.shouldSend(30))
        assertTrue(p.shouldSend(60))
    }

    @Test
    fun `on a fast link a second frame may go while the first is still on its way`() {
        val p = LivePacer(minIntervalMs = 40)
        var t = 0L
        for (n in 1..6) t = roundTrip(p, n, t + 50, rtt = 90)
        p.onSent(t + 50)
        assertTrue("second frame allowed", p.shouldSend(t + 100))
        p.onSent(t + 100)
        assertFalse("but never a third", p.shouldSend(t + 150))
    }

    @Test
    fun `on a slow link frames go strictly one at a time`() {
        val p = LivePacer(minIntervalMs = 40)
        var t = 0L
        for (n in 1..6) t = roundTrip(p, n, t + 50, rtt = 380)
        p.onSent(t + 50)
        assertFalse(p.shouldSend(t + 400))
    }

    @Test
    fun `the round trip is timed against the frame that was confirmed`() {
        val p = LivePacer()
        p.onSent(0)
        p.onAck(120, 1)
        assertEquals(120.0, p.smoothedRttMs!!, 1.0)
    }

    @Test
    fun `a confirmation covering two frames times the later one`() {
        val p = LivePacer(minIntervalMs = 0)
        for (n in 1..6) roundTrip(p, n, n * 1000L, rtt = 100) // learn that the link is fast
        p.onSent(10_000)
        p.onSent(10_050)
        p.onAck(10_200, 8) // the watch reports both received
        assertEquals(150.0, p.lastRttMs!!, 1.0)
        assertTrue(p.shouldSend(10_300))
    }

    @Test
    fun `a repeated or stale confirmation changes nothing`() {
        val p = LivePacer()
        p.onSent(0)
        p.onAck(100, 1)
        val rtt = p.smoothedRttMs
        p.onAck(5_000, 1)
        assertEquals(rtt, p.smoothedRttMs)
    }

    @Test
    fun `a confirmation that never comes does not freeze the picture`() {
        val p = LivePacer(ackTimeoutMs = 2_000)
        p.onSent(0)
        assertFalse(p.shouldSend(1_500))
        assertTrue(p.shouldSend(2_100))
    }

    @Test
    fun `a lost confirmation steps the quality down`() {
        val p = LivePacer(ackTimeoutMs = 2_000)
        val before = p.level
        p.onSent(0)
        p.shouldSend(2_100)
        assertTrue(p.level < before)
    }

    @Test
    fun `quality starts where it used to be fixed`() {
        val q = LivePacer().quality
        assertEquals(280, q.maxSide)
        assertEquals(55, q.jpegQuality)
    }

    @Test
    fun `the ladder only ever gets bigger and better`() {
        val levels = LivePacer.LEVELS
        for (i in 1 until levels.size) {
            assertTrue(levels[i].maxSide > levels[i - 1].maxSide)
            assertTrue(levels[i].jpegQuality >= levels[i - 1].jpegQuality)
        }
    }

    @Test
    fun `a slow link steps quality down, but never below the bottom`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = 0L
        repeat(40) { t = roundTrip(p, it + 1, t + 10, rtt = 700) }
        assertEquals(0, p.level)
    }

    @Test
    fun `a fast link only earns better quality after it has stayed fast for a while`() {
        val p = LivePacer(minIntervalMs = 0)
        val start = p.level
        var t = 0L
        repeat(4) { t = roundTrip(p, it + 1, t + 10, rtt = 80) }
        assertEquals("not yet", start, p.level)
        repeat(30) { t = roundTrip(p, it + 5, t + 10, rtt = 80) }
        assertTrue("climbed to ${p.level}", p.level > start)
    }

    @Test
    fun `quality never goes above the top of the ladder`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = 0L
        repeat(500) { t = roundTrip(p, it + 1, t + 10, rtt = 60) }
        assertEquals(LivePacer.LEVELS.lastIndex, p.level)
    }

    @Test
    fun `one slow frame in a fast stream does not flap the quality`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = 0L
        repeat(30) { t = roundTrip(p, it + 1, t + 10, rtt = 80) }
        val level = p.level
        t = roundTrip(p, 31, t + 10, rtt = 600)
        assertEquals(level, p.level)
    }

    @Test
    fun `a middling link stays where it is`() {
        val p = LivePacer(minIntervalMs = 0)
        val start = p.level
        var t = 0L
        repeat(60) { t = roundTrip(p, it + 1, t + 10, rtt = 280) }
        assertEquals(start, p.level)
    }
}
