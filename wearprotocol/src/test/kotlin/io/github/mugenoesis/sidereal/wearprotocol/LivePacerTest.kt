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

    private fun clean(p: LivePacer, from: Int, count: Int, rtt: Long = 300, startAt: Long = 0): Long {
        var t = startAt
        repeat(count) { t = roundTrip(p, from + it, t + 5, rtt) }
        return t
    }

    // --- sending rules ---

    @Test
    fun `the first frame goes at once, and starts with one frame out at a time`() {
        val p = LivePacer()
        assertTrue(p.shouldSend(0))
        assertEquals(1, p.window)
        p.onSent(0)
        assertFalse(p.shouldSend(500))
    }

    @Test
    fun `a frame is never sent into a backlog - delivery, not writing, is what counts`() {
        val p = LivePacer()
        p.onSent(0)
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
    fun `no more frames are out at once than the window allows`() {
        val p = LivePacer(minIntervalMs = 0)
        clean(p, 1, 40) // grow the window on a steady link
        val w = p.window
        assertTrue("window $w", w >= 2)
        var now = 100_000L
        repeat(w) { assertTrue(p.shouldSend(now)); p.onSent(now); now += 5 }
        assertFalse(p.shouldSend(now))
    }

    // --- timing ---

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
        clean(p, 1, 20)
        val before = 20
        p.onSent(10_000)
        p.onSent(10_050)
        p.onAck(10_200, before + 2)
        assertEquals(150.0, p.lastRttMs!!, 1.0)
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

    // --- how many frames the link takes at once ---

    @Test
    fun `on a steady link the number of frames in flight grows`() {
        val p = LivePacer(minIntervalMs = 0)
        clean(p, 1, 40)
        assertTrue("window ${p.window}", p.window >= 3)
    }

    @Test
    fun `it never grows past the limit`() {
        val p = LivePacer(minIntervalMs = 0)
        clean(p, 1, 400)
        assertEquals(LivePacer.MAX_WINDOW, p.window)
    }

    @Test
    fun `when frames start queueing up the window shrinks again, but never below one`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = clean(p, 1, 60, rtt = 300)
        assertTrue(p.window > 1)
        repeat(40) { t = roundTrip(p, 61 + it, t + 5, rtt = 1_500) }
        assertEquals(1, p.window)
    }

    @Test
    fun `one slow frame in a steady stream does not shrink the window`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = clean(p, 1, 60, rtt = 300)
        val w = p.window
        t = roundTrip(p, 61, t + 5, rtt = 900)
        assertEquals(w, p.window)
    }

    // --- picture quality ---

    @Test
    fun `quality starts where it used to be fixed`() {
        val q = LivePacer().quality
        assertEquals(280, q.maxSide)
        assertEquals(55, q.encodeQuality)
    }

    @Test
    fun `the ladder only ever gets bigger and better`() {
        val levels = LivePacer.LEVELS
        for (i in 1 until levels.size) {
            assertTrue(levels[i].maxSide > levels[i - 1].maxSide)
            assertTrue(levels[i].encodeQuality >= levels[i - 1].encodeQuality)
        }
    }

    @Test
    fun `quality only improves once the link has kept up at the full window for a while`() {
        val p = LivePacer(minIntervalMs = 0)
        val start = p.level
        var t = clean(p, 1, 12)
        assertEquals("not yet: window is still growing", start, p.level)
        t = clean(p, 13, 120, startAt = t)
        assertTrue("climbed to ${p.level}", p.level > start)
    }

    @Test
    fun `quality never goes above the top of the ladder`() {
        val p = LivePacer(minIntervalMs = 0)
        clean(p, 1, 800)
        assertEquals(LivePacer.LEVELS.lastIndex, p.level)
    }

    @Test
    fun `a link that is overloaded even one frame at a time gets smaller frames, down to a floor`() {
        val p = LivePacer(minIntervalMs = 0)
        var t = clean(p, 1, 20, rtt = 300)
        repeat(80) { t = roundTrip(p, 21 + it, t + 5, rtt = 1_600) }
        assertEquals(0, p.level)
    }

    @Test
    fun `a middling steady link neither climbs nor falls`() {
        val p = LivePacer(minIntervalMs = 0)
        // Fixed 300 ms, but frames only ever fit one at a time (the round trip doubles with a second frame out).
        var t = 0L
        repeat(100) { t = roundTrip(p, it + 1, t + 5, rtt = 300) }
        val level = p.level
        assertTrue(level >= 2)
    }

    // --- lost confirmations ---

    @Test
    fun `a confirmation that never comes does not freeze the picture`() {
        val p = LivePacer(ackTimeoutMs = 2_000)
        p.onSent(0)
        assertFalse(p.shouldSend(1_500))
        assertTrue(p.shouldSend(2_100))
    }

    @Test
    fun `and it eases off - one frame at a time, smaller`() {
        val p = LivePacer(minIntervalMs = 0, ackTimeoutMs = 2_000)
        val t = clean(p, 1, 60)
        val before = p.level
        p.onSent(t + 10)
        p.shouldSend(t + 10 + 2_100)
        assertEquals(1, p.window)
        assertTrue(p.level < before || before == 0)
    }

    // --- whole-link simulations ---

    /** A link: frames take size/bandwidth to cross, one after another, plus a fixed latency out and back. */
    private class SimResult(val fps: Double, val maxRttMs: Double, val medianRttMs: Double, val finalWindow: Int, val finalLevel: Int)

    private fun simulate(latencyMs: Long, bytesPerSec: Double, seconds: Int = 60): SimResult {
        val p = LivePacer(minIntervalMs = 60)
        var linkFreeAt = 0.0
        var sent = 0
        var delivered = 0
        val pending = ArrayDeque<Pair<Long, Int>>() // (confirmation time, running count)
        val rtts = ArrayList<Double>()
        var now = 0L
        while (now < seconds * 1_000L) {
            while (pending.isNotEmpty() && pending.first().first <= now) {
                val (_, count) = pending.removeFirst()
                p.onAck(now, count)
                p.lastRttMs?.let { rtts += it }
                delivered = count
            }
            if (p.shouldSend(now)) {
                val q = p.quality
                val bytes = q.maxSide * q.maxSide * 0.06
                p.onSent(now)
                sent++
                val start = maxOf(now.toDouble(), linkFreeAt)
                linkFreeAt = start + bytes / bytesPerSec * 1000.0
                pending.addLast((linkFreeAt + latencyMs).toLong() to sent)
            }
            now += 5
        }
        val sorted = rtts.sorted()
        return SimResult(delivered / seconds.toDouble(), sorted.maxOrNull() ?: 0.0, sorted[sorted.size / 2], p.window, p.level)
    }

    @Test
    fun `a link that is slow to respond but wide - the picture gets more frames and better quality, not more lag`() {
        val r = simulate(latencyMs = 340, bytesPerSec = 400_000.0)
        assertTrue("fps ${r.fps}", r.fps >= 5.0)
        assertTrue("window ${r.finalWindow}", r.finalWindow >= 3)
        assertTrue("level ${r.finalLevel}", r.finalLevel >= 3)
        assertTrue("median rtt ${r.medianRttMs}", r.medianRttMs < 700)
    }

    @Test
    fun `a narrow link - quality comes down to what it can carry, and the lag stays bounded`() {
        val r = simulate(latencyMs = 60, bytesPerSec = 8_000.0)
        assertTrue("median rtt ${r.medianRttMs}", r.medianRttMs < 1_200)
        assertTrue("level ${r.finalLevel}", r.finalLevel <= 2)
        assertTrue("fps ${r.fps}", r.fps >= 1.5)
    }

    @Test
    fun `a link like the watch's - several frames in flight beat one at a time`() {
        val r = simulate(latencyMs = 250, bytesPerSec = 40_000.0)
        assertTrue("fps ${r.fps}", r.fps > 3.5)
        assertTrue("window ${r.finalWindow}", r.finalWindow >= 2)
        assertTrue("median rtt ${r.medianRttMs}", r.medianRttMs < 800)
    }
}
