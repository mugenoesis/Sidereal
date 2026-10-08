package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * A simulated lens modelled on the real measurements from the Zenmuse X5: the preview's sharpness is flat noise
 * almost everywhere with one narrow peak (about 25% of the ring wide at its skirts, ring 0..2035), the picture
 * follows a ring move within ~150 ms, and every frame carries some noise.
 */
private class SimLens(
    var peak: Int,
    private val width: Double = 110.0,
    private val baseline: Double = 80.0,
    private val amplitude: Double = 520.0,
    private val noise: Double = 0.0,
    private val latencyMs: Long = 150,
    startRing: Int = 0,
    seed: Long = 7
) {
    var ring = startRing
        private set
    private var previousRing = startRing
    private var commandedAt = Long.MIN_VALUE / 2
    private val rng = Random(seed)
    var moves = 0
        private set

    fun command(target: Int, now: Long) {
        previousRing = if (now - commandedAt < latencyMs) previousRing else ring
        ring = target
        commandedAt = now
        moves++
    }

    fun score(now: Long): Double {
        val shown = if (now - commandedAt < latencyMs) previousRing else ring
        val z = (shown - peak) / width
        val clean = baseline + amplitude * exp(-z * z / 2)
        val jitter = if (noise > 0) 1.0 + noise * (rng.nextDouble() * 2 - 1) else 1.0
        return clean * jitter
    }
}

private data class Outcome(val locked: FocusSearch.Command.Locked?, val moves: Int, val elapsedMs: Long, val rings: List<Int>)

private const val FRAME_MS = 250L

private fun run(
    search: FocusSearch,
    lens: SimLens,
    seed: Int?,
    limitMs: Long = 90_000,
    frameMs: Long = FRAME_MS,
    onTick: ((now: Long) -> Unit)? = null
): Outcome {
    var now = 0L
    val first = search.begin(seed, now)
    lens.command(first.ring, now)
    val rings = mutableListOf(first.ring)
    while (now < limitMs) {
        now += frameMs
        onTick?.invoke(now)
        when (val c = search.onFrame(now, lens.score(now))) {
            is FocusSearch.Command.MoveTo -> { lens.command(c.ring, now); rings += c.ring }
            is FocusSearch.Command.Locked -> return Outcome(c, lens.moves, now, rings)
            null, is FocusSearch.Command.Unreliable -> Unit
        }
    }
    return Outcome(null, lens.moves, now, rings)
}

class FocusSearchTest {

    private val bound = 2035

    @Test
    fun `finds the peak with no starting hint`() {
        val lens = SimLens(peak = 1600)
        val out = run(FocusSearch(bound), lens, seed = null)
        assertNotNull(out.locked)
        assertEquals(1600f, out.locked!!.ring.toFloat(), 40f)
        assertTrue(out.locked!!.confident)
    }

    @Test
    fun `finds peaks wherever they are on the ring`() {
        for (peak in listOf(120, 400, 800, 1234, 1700, 1950)) {
            val out = run(FocusSearch(bound), SimLens(peak = peak, startRing = 900), seed = null)
            assertNotNull("peak $peak not locked", out.locked)
            assertEquals("peak $peak", peak.toFloat(), out.locked!!.ring.toFloat(), 45f)
        }
    }

    @Test
    fun `finds a peak sitting right at either end of the ring`() {
        for (peak in listOf(0, bound)) {
            val out = run(FocusSearch(bound), SimLens(peak = peak, startRing = 1000), seed = null)
            assertEquals("peak $peak", peak.toFloat(), out.locked!!.ring.toFloat(), 60f)
        }
    }

    @Test
    fun `a good starting hint makes it much quicker than a blind scan`() {
        val blind = run(FocusSearch(bound), SimLens(peak = 1600), seed = null)
        val hinted = run(FocusSearch(bound), SimLens(peak = 1600), seed = 1700)
        assertEquals(1600f, hinted.locked!!.ring.toFloat(), 40f)
        assertTrue("hinted ${hinted.elapsedMs} vs blind ${blind.elapsedMs}", hinted.elapsedMs < blind.elapsedMs * 0.7)
        assertTrue("hinted took ${hinted.elapsedMs} ms", hinted.elapsedMs < 14_000)
    }

    @Test
    fun `with a good hint and a fast preview it locks in about five seconds`() {
        // The sampling rate the controller uses (~150 ms frames) against the lens's measured ~150 ms latency.
        for (peak in listOf(1500, 1560, 1640)) {
            val out = run(FocusSearch(bound), SimLens(peak = peak), seed = 1610, frameMs = 150)
            assertEquals("peak $peak", peak.toFloat(), out.locked!!.ring.toFloat(), 40f)
            assertTrue("peak $peak took ${out.elapsedMs} ms", out.elapsedMs <= 5_500)
        }
    }

    @Test
    fun `speed does not cost accuracy with noisy frames`() {
        for (s in 1L..8L) {
            val out = run(FocusSearch(bound), SimLens(peak = 1560, noise = 0.15, seed = s), seed = 1610, frameMs = 150)
            assertEquals("seed $s", 1560f, out.locked!!.ring.toFloat(), 80f)
        }
    }

    @Test
    fun `a hint that is too far off is walked towards the peak instead of trusted`() {
        val out = run(FocusSearch(bound), SimLens(peak = 1300), seed = 1000)
        assertEquals(1300f, out.locked!!.ring.toFloat(), 45f)
    }

    @Test
    fun `a hint in completely the wrong place falls back to a full scan`() {
        val out = run(FocusSearch(bound), SimLens(peak = 1700), seed = 100)
        assertEquals(1700f, out.locked!!.ring.toFloat(), 45f)
    }

    @Test
    fun `parabolic refinement lands between the scan points`() {
        val out = run(FocusSearch(bound), SimLens(peak = 1234), seed = null)
        assertEquals(1234f, out.locked!!.ring.toFloat(), 22f)
    }

    @Test
    fun `ordinary frame noise does not throw it off`() {
        for (s in 1L..6L) {
            val out = run(FocusSearch(bound), SimLens(peak = 1500, noise = 0.15, seed = s), seed = 1450)
            assertEquals("seed $s", 1500f, out.locked!!.ring.toFloat(), 80f)
        }
    }

    @Test
    fun `frames taken before the lens has settled are not counted`() {
        // A spike shown only in the first 300 ms after a move must not be mistaken for that position's sharpness.
        val lens = SimLens(peak = 1600, latencyMs = 450)
        val out = run(FocusSearch(bound), lens, seed = 1650)
        assertEquals(1600f, out.locked!!.ring.toFloat(), 45f)
    }

    @Test
    fun `no decision is made on the first frames after a move`() {
        val s = FocusSearch(bound)
        s.begin(1000, 0)
        assertNull(s.onFrame(100, 500.0))
        assertNull(s.onFrame(250, 500.0))
    }

    @Test
    fun `a scene with no peak is reported as not confident and parks at the hint`() {
        val out = run(FocusSearch(bound), SimLens(peak = 1600, amplitude = 0.0, noise = 0.12), seed = 1500)
        assertNotNull(out.locked)
        assertFalse(out.locked!!.confident)
        assertEquals(1500f, out.locked!!.ring.toFloat(), 1f)
    }

    @Test
    fun `every command stays inside the ring`() {
        for (peak in listOf(0, 700, bound)) {
            val out = run(FocusSearch(bound), SimLens(peak = peak), seed = if (peak == 0) 50 else null)
            assertTrue(out.rings.all { it in 0..bound })
        }
    }

    @Test
    fun `a blind scan finishes in a sensible time and number of moves`() {
        val out = run(FocusSearch(bound), SimLens(peak = 1000), seed = null)
        assertTrue("took ${out.elapsedMs} ms", out.elapsedMs < 30_000)
        assertTrue("${out.moves} moves", out.moves <= 30)
    }

    @Test
    fun `once locked the ring is left completely alone while the picture stays sharp`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val out = run(s, lens, seed = 1650)
        val movesAtLock = lens.moves
        var now = out.elapsedMs
        repeat(80) {
            now += FRAME_MS
            assertNull(s.onFrame(now, lens.score(now)))
        }
        assertEquals(movesAtLock, lens.moves)
    }

    @Test
    fun `a single bad frame after locking does not restart the search`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val out = run(s, lens, seed = 1650)
        var now = out.elapsedMs + 2_000
        assertNull(s.onFrame(now, lens.score(now) * 0.1))
        now += FRAME_MS
        assertNull(s.onFrame(now, lens.score(now)))
    }

    @Test
    fun `when the scene changes and sharpness collapses it searches again and finds the new focus`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val first = run(s, lens, seed = 1650)
        assertEquals(1600f, first.locked!!.ring.toFloat(), 45f)

        lens.peak = 1350 // the subject moved closer: new focus position
        var now = first.elapsedMs
        var relocked: FocusSearch.Command.Locked? = null
        var guard = 0
        while (relocked == null && guard++ < 400) {
            now += FRAME_MS
            when (val c = s.onFrame(now, lens.score(now))) {
                is FocusSearch.Command.MoveTo -> lens.command(c.ring, now)
                is FocusSearch.Command.Locked -> relocked = c
                null, is FocusSearch.Command.Unreliable -> Unit
            }
        }
        assertNotNull("never relocked", relocked)
        assertEquals(1350f, relocked!!.ring.toFloat(), 50f)
    }

    @Test
    fun `a collapse far from any previous focus triggers a full scan`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 400)
        val first = run(s, lens, seed = 420)
        lens.peak = 1800
        var now = first.elapsedMs
        var relocked: FocusSearch.Command.Locked? = null
        var guard = 0
        while (relocked == null && guard++ < 600) {
            now += FRAME_MS
            when (val c = s.onFrame(now, lens.score(now))) {
                is FocusSearch.Command.MoveTo -> lens.command(c.ring, now)
                is FocusSearch.Command.Locked -> relocked = c
                null, is FocusSearch.Command.Unreliable -> Unit
            }
        }
        assertEquals(1800f, relocked!!.ring.toFloat(), 55f)
    }

    @Test
    fun `reports its phase`() {
        val s = FocusSearch(bound)
        assertEquals(FocusSearch.Phase.IDLE, s.phase)
        s.begin(1000, 0)
        assertEquals(FocusSearch.Phase.COARSE, s.phase)
        run(s, SimLens(peak = 1000), seed = 1000)
        assertEquals(FocusSearch.Phase.LOCKED, s.phase)
    }

    @Test
    fun `stopping and starting again begins cleanly`() {
        val s = FocusSearch(bound)
        run(s, SimLens(peak = 1600), seed = 1600)
        val out = run(s, SimLens(peak = 600), seed = null)
        assertEquals(600f, out.locked!!.ring.toFloat(), 45f)
    }

    @Test
    fun `the search never needs more than the ring's own resolution`() {
        // A tiny ring range must not break the step arithmetic.
        val out = run(FocusSearch(40), SimLens(peak = 25, width = 6.0), seed = null)
        assertTrue(out.locked!!.ring in 0..40)
    }

    @Test
    fun `scores are compared as measured so a dim scene works as well as a bright one`() {
        val dim = run(FocusSearch(bound), SimLens(peak = 1500, baseline = 4.0, amplitude = 26.0), seed = null)
        assertEquals(1500f, dim.locked!!.ring.toFloat(), 45f)
        assertTrue(abs(dim.locked!!.score) > 0)
    }

    @Test
    fun `a sharpness collapse while the picture is still moving does not restart the search`() {
        // A pan in progress changes the picture every frame - measuring focus then is meaningless.
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val out = run(s, lens, seed = 1650)
        var now = out.elapsedMs + 2_000
        repeat(30) {
            now += FRAME_MS
            assertNull(s.onFrame(now, lens.score(now) * 0.1, steady = false))
        }
        assertEquals(FocusSearch.Phase.LOCKED, s.phase)
    }

    @Test
    fun `once the picture is steady again a lasting collapse does restart it`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val out = run(s, lens, seed = 1650)
        var now = out.elapsedMs + 2_000
        repeat(10) { now += FRAME_MS; s.onFrame(now, lens.score(now) * 0.1, steady = false) }
        var restarted = false
        repeat(8) { now += FRAME_MS; if (s.onFrame(now, lens.score(now) * 0.1, steady = true) is FocusSearch.Command.MoveTo) restarted = true }
        assertTrue(restarted)
    }

    @Test
    fun `a search can be restarted around the ring it was last locked at`() {
        val s = FocusSearch(bound)
        val lens = SimLens(peak = 1600)
        val first = run(s, lens, seed = 1650)
        lens.peak = 1700
        val restart = s.begin(first.locked!!.ring, 0)
        assertEquals(FocusSearch.Phase.COARSE, s.phase)
        assertTrue(restart.ring in 0..bound)
    }
}
