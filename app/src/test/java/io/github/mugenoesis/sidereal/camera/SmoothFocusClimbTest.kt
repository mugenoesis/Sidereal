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
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 3_500)
        }
    }

    @Test
    fun `from a seed that stopped short it climbs to the top of a far scene`() {
        for (seed in listOf(783, 744, 807, 821)) {
            val r = run(far, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(far, r.ring)})", share(far, r.ring) >= 0.95)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 6_500)
        }
    }

    @Test
    fun `a seed on the flat top locks quickly where it is`() {
        val r = run(far, 1501)
        assertTrue(share(far, r.ring) >= 0.95)
        assertTrue("took ${r.ms} ms", r.ms <= 3_500)
    }

    @Test
    fun `even from a poor seed at either end it finds the hill`() {
        for (seed in listOf(0, 300, 1900, 2035)) {
            val r = run(near, seed)
            assertTrue("seed $seed -> ${r.ring} (${share(near, r.ring)})", share(near, r.ring) >= 0.95)
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 9_000)
        }
    }

    @Test
    fun `with no hint it starts mid-ring and still finds it`() {
        val r = run(near, null)
        assertTrue("${r.ring} (${share(near, r.ring)})", share(near, r.ring) >= 0.95)
    }

    @Test
    fun `it measures only a handful of positions`() {
        assertTrue(run(near, 1255).measurements <= 6)
        assertTrue(run(far, 783).measurements <= 10)
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
            assertTrue("seed $seed took ${r.ms} ms", r.ms <= 6_500)
        }
    }
}
