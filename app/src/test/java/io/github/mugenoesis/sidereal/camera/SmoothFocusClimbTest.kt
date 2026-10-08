package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SmoothFocusClimb] against sharpness curves measured on the real X5 in daylight (ring 0..2000 in steps of
 * 50, af_curve scenario), so the tests are about the real lens, not an idealised one.
 */
class SmoothFocusClimbTest {

    private val bound = 2035

    // Far outdoor scene: climbs steadily, then a wide flat top (everything is at infinity).
    private val far = mapOf(
        0 to 623, 50 to 638, 100 to 661, 150 to 686, 200 to 742, 250 to 767, 300 to 765, 350 to 780, 400 to 811, 450 to 834,
        500 to 855, 550 to 850, 600 to 849, 650 to 881, 700 to 890, 750 to 886, 800 to 914, 850 to 914, 900 to 912, 950 to 942,
        1000 to 977, 1050 to 1040, 1100 to 1070, 1150 to 1129, 1200 to 1118, 1250 to 1118, 1300 to 1126, 1350 to 1110,
        1400 to 1151, 1450 to 1101, 1500 to 1095, 1550 to 1097, 1600 to 1088, 1650 to 1093, 1700 to 1116, 1750 to 1102,
        1800 to 1118, 1850 to 1124, 1900 to 1138, 1950 to 1112, 2000 to 1103
    )

    // Near subject about a metre away: one broad hill peaking around ring 1050.
    private val near = mapOf(
        0 to 3852, 50 to 4082, 100 to 4262, 150 to 4423, 200 to 4511, 250 to 4618, 300 to 4857, 350 to 5141, 400 to 5441,
        450 to 5686, 500 to 5937, 550 to 6164, 600 to 6346, 650 to 6575, 700 to 6772, 750 to 6920, 800 to 7000, 850 to 7201,
        900 to 7321, 950 to 7362, 1000 to 7424, 1050 to 7464, 1100 to 7406, 1150 to 7367, 1200 to 7373, 1250 to 7358,
        1300 to 7344, 1350 to 7272, 1400 to 7249, 1450 to 7043, 1500 to 6962, 1550 to 6891, 1600 to 6836, 1650 to 6824,
        1700 to 6826, 1750 to 6775, 1800 to 6680, 1850 to 6591, 1900 to 6424, 1950 to 6229, 2000 to 6027
    )

    // Dim room (1/10 s, ISO 100): a broad hill peaking near ring 1500, falling away gently on the far side.
    private val room = mapOf(
        0 to 647, 50 to 712, 100 to 782, 150 to 848, 200 to 911, 250 to 980, 300 to 1092, 350 to 1213, 400 to 1307, 450 to 1407,
        500 to 1518, 550 to 1654, 600 to 1810, 650 to 2035, 700 to 2241, 750 to 2505, 800 to 2800, 850 to 3131, 900 to 3433,
        950 to 3836, 1000 to 4246, 1050 to 4643, 1100 to 5050, 1150 to 5457, 1200 to 5751, 1250 to 5974, 1300 to 6083,
        1350 to 6117, 1400 to 6317, 1450 to 6482, 1500 to 6513, 1550 to 6317, 1600 to 6318, 1650 to 6316, 1700 to 6006,
        1750 to 5837, 1800 to 5703, 1850 to 5454, 1900 to 5025, 1950 to 4756, 2000 to 4515
    )

    // Dark scene at ISO 6400 (real, step 100): frame-to-frame noise about 5%, peak near ring 1500 over a flat noisy floor.
    private val darkIso6400 = (0..2000 step 50).associateWith { ring ->
        val pts = mapOf(0 to 21, 100 to 27, 200 to 25, 300 to 27, 400 to 31, 500 to 40, 600 to 41, 700 to 53, 800 to 69, 900 to 91,
            1000 to 137, 1100 to 237, 1200 to 473, 1300 to 1029, 1400 to 2616, 1500 to 4853, 1600 to 4344, 1700 to 2130, 1800 to 888,
            1900 to 445, 2000 to 252)
        val lo = ring / 100 * 100; val hi = minOf(lo + 100, 2000)
        val a = pts.getValue(lo).toDouble(); val b = pts.getValue(hi).toDouble()
        (if (hi == lo) a else a + (b - a) * (ring - lo) / (hi - lo)).toInt()
    }

    private fun sharpness(curve: Map<Int, Int>, ring: Int): Double {
        val lo = (ring / 50 * 50).coerceAtMost(2000)
        val hi = (lo + 50).coerceAtMost(2000)
        val a = curve.getValue(lo).toDouble()
        val b = curve.getValue(hi).toDouble()
        return if (hi == lo) a else a + (b - a) * (ring - lo) / (hi - lo)
    }

    private class Result(val ring: Int, val ms: Long, val measurements: Int)

    private fun run(curve: Map<Int, Int>, seed: Int?): Result {
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(seed, now).ring
        var moves = 1
        while (now < 30_000) {
            now += 150
            val command = search.onFrame(now, sharpness(curve, ring), steady = true)
            when (command) {
                is FocusSearch.Command.MoveTo -> { ring = command.ring; moves++ }
                is FocusSearch.Command.Locked -> return Result(command.ring, now, moves)
                null, is FocusSearch.Command.Unreliable -> {}
            }
        }
        throw AssertionError("never locked")
    }

    private fun share(curve: Map<Int, Int>, ring: Int) = sharpness(curve, ring) / curve.values.max()

    @Test
    fun `from where the camera's own autofocus lands it settles within a few percent of the best focus on a near subject`() {
        for (seed in listOf(1295, 1240, 1255, 1299)) {
            val r = run(near, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(near, r.ring)})", share(near, r.ring) >= 0.97)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 5_000)
        }
    }

    @Test
    fun `from a seed that stopped short it climbs to the top of a far scene`() {
        for (seed in listOf(783, 744, 807, 821)) {
            val r = run(far, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(far, r.ring)})", share(far, r.ring) >= 0.95)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 8_000)
        }
    }

    @Test
    fun `a seed on the flat top locks quickly where it is`() {
        val r = run(far, 1501)
        assertTrue(share(far, r.ring) >= 0.95)
        assertTrue("took ${r.ms} ms", r.ms <= 5_000)
    }

    @Test
    fun `even from a poor seed at either end it finds the hill`() {
        for (seed in listOf(0, 300, 1900, 2035)) {
            val r = run(near, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(near, r.ring)})", share(near, r.ring) >= 0.95)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 11_000)
        }
    }

    @Test
    fun `with no hint it starts mid-ring and still finds it`() {
        val r = run(near, null)
        assertTrue("${r.ring} (${share(near, r.ring)})", share(near, r.ring) >= 0.95)
    }

    @Test
    fun `it measures only a handful of positions`() {
        assertTrue(run(near, 1255).measurements <= 8)
        assertTrue(run(far, 783).measurements <= 12)
    }

    @Test
    fun `once locked a lasting collapse in sharpness starts a new search from the locked ring`() {
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(1255, now).ring
        var locked: FocusSearch.Command.Locked? = null
        while (locked == null) {
            now += 150
            when (val c = search.onFrame(now, sharpness(near, ring), true)) {
                is FocusSearch.Command.MoveTo -> ring = c.ring
                is FocusSearch.Command.Locked -> locked = c
                null, is FocusSearch.Command.Unreliable -> {}
            }
        }
        assertTrue(search.locked)
        // the scene goes soft for good
        var restarted: FocusSearch.Command? = null
        repeat(10) {
            now += 150
            restarted = restarted ?: search.onFrame(now, locked!!.score * 0.2, true)
        }
        assertNotNull(restarted)
        assertTrue(restarted is FocusSearch.Command.MoveTo)
        assertEquals(false, search.locked)
    }

    @Test
    fun `a collapse while the picture is changing is ignored`() {
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(1255, now).ring
        var locked: FocusSearch.Command.Locked? = null
        while (locked == null) {
            now += 150
            when (val c = search.onFrame(now, sharpness(near, ring), true)) {
                is FocusSearch.Command.MoveTo -> ring = c.ring
                is FocusSearch.Command.Locked -> locked = c
                null, is FocusSearch.Command.Unreliable -> {}
            }
        }
        repeat(20) {
            now += 150
            assertEquals(null, search.onFrame(now, locked!!.score * 0.1, steady = false))
        }
        assertTrue(search.locked)
    }

    @Test
    fun `rings are always inside the lens range`() {
        for (curve in listOf(near, far)) for (seed in listOf(0, 2035, null)) {
            val search = SmoothFocusClimb(bound)
            var now = 0L
            var ring = search.begin(seed, now).ring
            assertTrue(ring in 0..bound)
            repeat(200) {
                now += 150
                val c = search.onFrame(now, sharpness(curve, ring), true)
                if (c is FocusSearch.Command.MoveTo) { ring = c.ring; assertTrue("ring $ring", ring in 0..bound) }
            }
        }
    }

    @Test
    fun `a noisy picture is handed to the scanning search instead of being climbed`() {
        val rnd = java.util.Random(3)
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(1250, now).ring
        var result: FocusSearch.Command? = null
        var guard = 0
        while (result == null && guard++ < 200) {
            now += 150
            // the true value wobbled by +-35% from frame to frame, like ISO 25600
            val noisy = sharpness(near, ring) * (1 + (rnd.nextDouble() - 0.5) * 0.7)
            when (val c = search.onFrame(now, noisy, true)) {
                is FocusSearch.Command.MoveTo -> ring = c.ring
                null -> {}
                else -> result = c
            }
        }
        assertTrue("got $result", result is FocusSearch.Command.Unreliable)
        assertEquals(1250, (result as FocusSearch.Command.Unreliable).seed)
    }

    @Test
    fun `a clean picture with a little noise still climbs`() {
        val rnd = java.util.Random(5)
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(1250, now).ring
        var result: FocusSearch.Command? = null
        var guard = 0
        while (result == null && guard++ < 200) {
            now += 150
            val noisy = sharpness(near, ring) * (1 + (rnd.nextDouble() - 0.5) * 0.06)
            when (val c = search.onFrame(now, noisy, true)) {
                is FocusSearch.Command.MoveTo -> ring = c.ring
                null -> {}
                else -> result = c
            }
        }
        assertTrue("got $result", result is FocusSearch.Command.Locked)
    }

    @Test
    fun `in the dim room it ends close to the top from any seed the camera's autofocus might give`() {
        for (seed in (800..2035 step 37).toList()) {
            val r = run(room, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(room, r.ring)})", share(room, r.ring) >= 0.94)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 8_000)
        }
    }

    private fun runNoisy(curve: Map<Int, Int>, seed: Int, sigma: Double, rndSeed: Long): FocusSearch.Command? {
        val rnd = java.util.Random(rndSeed)
        val search = SmoothFocusClimb(bound)
        lastSearch = search
        var now = 0L
        var ring = search.begin(seed, now).ring
        repeat(400) {
            now += 150
            val v = sharpness(curve, ring) * (1 + sigma * rnd.nextGaussian())
            when (val c = search.onFrame(now, v.coerceAtLeast(0.0), true)) {
                is FocusSearch.Command.MoveTo -> ring = c.ring
                null -> {}
                else -> return c
            }
        }
        return null
    }

    private var lastSearch: SmoothFocusClimb? = null

    @Test
    fun `at moderate noise it averages more frames and still climbs to the hill`() {
        for (rndSeed in 1L..12L) for (seed in listOf(1450, 1600, 1300)) {
            val c = runNoisy(darkIso6400, seed, sigma = 0.05, rndSeed = rndSeed)
            assertTrue("seed $seed rnd $rndSeed -> $c", c is FocusSearch.Command.Locked)
            val ring = (c as FocusSearch.Command.Locked).ring
            assertTrue("seed $seed rnd $rndSeed locked at $ring (${share(darkIso6400, ring)})", share(darkIso6400, ring) >= 0.6)
        }
    }

    @Test
    fun `extreme noise is handed to the scanning search or, if it locks, locks on the hill`() {
        for (rndSeed in 1L..8L) {
            val c = runNoisy(darkIso6400, 1450, sigma = 0.3, rndSeed = rndSeed)
            if (c is FocusSearch.Command.Locked) assertTrue("rnd $rndSeed locked at ${c.ring}", share(darkIso6400, c.ring) >= 0.4)
            else assertTrue("rnd $rndSeed -> $c", c is FocusSearch.Command.Unreliable)
        }
    }

    @Test
    fun `noise beyond anything averaging can fix is always handed over`() {
        val handedOver = (1L..16L).count { runNoisy(darkIso6400, 1450, sigma = 0.6, rndSeed = it) is FocusSearch.Command.Unreliable }
        assertTrue("handed over $handedOver of 16", handedOver >= 14)
    }

    @Test
    fun `noise on a perfectly flat picture is handed to the scanning search, not locked`() {
        val flat = (0..2000 step 50).associateWith { 100 }
        // The noise level is itself estimated from a handful of frames, so an unlucky run can underestimate it:
        // require it to work nearly always rather than every time.
        val handedOver = (1L..16L).count { runNoisy(flat, 800, sigma = 0.06, rndSeed = it) is FocusSearch.Command.Unreliable }
        assertTrue("handed over $handedOver of 16", handedOver >= 14)
    }

    @Test
    fun `a noisy seed down on the rising floor still walks up to the hill`() {
        for (rndSeed in 1L..8L) {
            val c = runNoisy(darkIso6400, 300, sigma = 0.06, rndSeed = rndSeed)
            if (c is FocusSearch.Command.Locked) assertTrue("rnd $rndSeed locked at ${c.ring}", share(darkIso6400, c.ring) >= 0.5)
            else assertTrue("rnd $rndSeed -> $c", c is FocusSearch.Command.Unreliable)
        }
    }

    @Test
    fun `a clean seed on a flat floor far from the peak still locks - there is no way to tell it from a plateau`() {
        // documented limitation: clean light is trusted to have a sensible seed (hardware autofocus)
        val c = runNoisy(far, 1500, sigma = 0.005, rndSeed = 1)
        assertTrue(c is FocusSearch.Command.Locked)
    }

    @Test
    fun `flicker-level noise on a strong hill is climbed, not given up on`() {
        // LED light with a shutter that does not match the mains frequency: 10-15% frame-to-frame, hill still obvious
        var locked = 0
        for (rndSeed in 1L..12L) {
            val c = runNoisy(darkIso6400, 1450, sigma = 0.12, rndSeed = rndSeed)
            if (c is FocusSearch.Command.Locked) {
                locked++
                assertTrue("rnd $rndSeed locked at ${c.ring} (${share(darkIso6400, c.ring)})", share(darkIso6400, c.ring) >= 0.4)
            }
        }
        assertTrue("locked $locked of 12", locked >= 10)
    }

    @Test
    fun `a seed that landed on the low floor is not locked on - the wide check finds the hill`() {
        // seen on the real camera: hardware autofocus left the ring at about 400 where the picture was blurred
        for (seed in listOf(300, 380, 420, 500, 600)) {
            val r = run(room, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(room, r.ring)})", share(room, r.ring) >= 0.88)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 12_000)
        }
    }

    @Test
    fun `a seed on the floor of the far scene finds the flat top too`() {
        val r = run(far, 100)
        assertTrue("${r.ring} (${share(far, r.ring)})", share(far, r.ring) >= 0.9)
    }

    @Test
    fun `the wide check does not move a lock that was already on top`() {
        val r = run(near, 1050)
        assertTrue("${r.ring}", r.ring in 950..1150)
    }

    // Panasonic 12-32 at 12 mm (real, step 50, ring range 0-1570): a sharp peak near 900-950 over a long blurry slope.
    private val panasonic12 = mapOf(
        0 to 67, 50 to 73, 100 to 81, 150 to 92, 200 to 104, 250 to 119, 300 to 140, 350 to 166, 400 to 197, 450 to 249,
        500 to 322, 550 to 433, 600 to 564, 650 to 826, 700 to 1287, 750 to 1763, 800 to 2511, 850 to 3075, 900 to 3264,
        950 to 3233, 1000 to 2974, 1050 to 2325, 1100 to 2033, 1150 to 974, 1200 to 621, 1250 to 525, 1300 to 383,
        1350 to 292, 1400 to 238, 1450 to 198, 1500 to 164, 1550 to 136, 1600 to 137, 1650 to 139, 1700 to 138,
        1750 to 123, 1800 to 116, 1850 to 118, 1900 to 116, 1950 to 120, 2000 to 118
    )

    /**
     * After the camera's own autofocus the lens may still be moving when the climb starts, so for [staleMs] every frame
     * shows the picture as it was at [staleRing] (sharp), whatever ring the climb believes it is at.
     */
    private fun runWithStaleStart(curve: Map<Int, Int>, bound: Int, seed: Int, staleRing: Int, staleMs: Long): Int {
        val search = SmoothFocusClimb(bound)
        var now = 0L
        var ring = search.begin(seed, now).ring
        while (now < 30_000) {
            now += 150
            val seen = if (now < staleMs) sharpness(curve, staleRing) else sharpness(curve, ring)
            when (val command = search.onFrame(now, seen, steady = true)) {
                is FocusSearch.Command.MoveTo -> ring = command.ring
                is FocusSearch.Command.Locked -> return command.ring
                null, is FocusSearch.Command.Unreliable -> {}
            }
        }
        throw AssertionError("never locked")
    }

    @Test fun `a first reading made while the lens was still moving does not become the lock`() {
        // Seed at 1312 (blurry, 383) but the picture stays sharp (as at 930) for the first 1.6 s.
        val locked = runWithStaleStart(panasonic12, 1570, seed = 1312, staleRing = 930, staleMs = 1600)
        assertTrue("locked at $locked", share(panasonic12, locked) > 0.8)
    }

    @Test fun `the same curve with a settled start still finds the peak from anywhere`() {
        for (seed in listOf(0, 400, 930, 1312, 1541)) {
            val locked = runWithStaleStart(panasonic12, 1570, seed = seed, staleRing = seed, staleMs = 0)
            assertTrue("seed $seed locked at $locked", share(panasonic12, locked) > 0.8)
        }
    }
}
