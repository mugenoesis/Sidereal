package io.github.mugenoesis.sidereal.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrillEngineTest {

    /** A drill with no random spawns getting in the way: tests place targets themselves. */
    private fun quiet() = DrillEngine(seed = 5, spawning = false)

    private fun run(drill: DrillEngine, seconds: Float, stickX: Float = 0f, stickY: Float = 0f): List<DrillEvent> {
        val events = ArrayList<DrillEvent>()
        var t = 0f
        while (t < seconds - 1e-4f) { events += drill.step(0.05f, stickX, stickY); t += 0.05f }
        return events
    }

    @Test
    fun `a new drill is playing, centred, with three margin and no score`() {
        val g = quiet()
        assertEquals(DrillState.PLAYING, g.state)
        assertEquals(3, g.margin)
        assertEquals(0, g.score)
        assertEquals(g.width / 2, g.crosshairX, 0.01f)
        assertEquals(g.height / 2, g.crosshairY, 0.01f)
    }

    @Test
    fun `the stick moves the crosshair in proportion`() {
        val g = quiet()
        run(g, 0.5f, stickX = 1f)
        assertEquals(g.width / 2 + DrillEngine.CROSSHAIR_SPEED * 0.5f, g.crosshairX, 5f)
        val h = quiet()
        run(h, 0.5f, stickX = 0.5f)
        assertEquals(g.width / 2 + DrillEngine.CROSSHAIR_SPEED * 0.25f, h.crosshairX, 5f)
        assertEquals(g.height / 2, h.crosshairY, 0.01f)
    }

    @Test
    fun `the crosshair stays on the screen`() {
        val g = quiet()
        run(g, 10f, stickX = 1f, stickY = 1f)
        assertEquals(g.width, g.crosshairX, 0.01f)
        assertEquals(g.height, g.crosshairY, 0.01f)
        run(g, 10f, stickX = -1f, stickY = -1f)
        assertEquals(0f, g.crosshairX, 0.01f)
        assertEquals(0f, g.crosshairY, 0.01f)
    }

    @Test
    fun `marking an target under the crosshair destroys it and scores`() {
        val g = quiet()
        g.addTargetForTest(g.crosshairX, g.crosshairY, z = 0.0f + 0.5f)
        run(g, 0.05f) // let it settle onto its position
        val a = g.targets.single()
        // aim straight at it
        g.moveCrosshairForTest(a.x, a.y)
        val events = g.mark()
        assertEquals(1, g.score)
        assertTrue(g.targets.isEmpty())
        assertTrue(events.any { it is DrillEvent.Cleared })
    }

    @Test
    fun `marking at nothing is a miss and scores nothing`() {
        val g = quiet()
        g.addTargetForTest(100f, 100f, z = 0.4f)
        run(g, 0.05f)
        val events = g.mark()
        assertEquals(0, g.score)
        assertEquals(1, g.targets.size)
        assertTrue(events.any { it is DrillEvent.Off })
    }

    @Test
    fun `you cannot fire faster than the cooldown`() {
        val g = quiet()
        g.addTargetForTest(800f, 450f, z = 0.5f)
        g.addTargetForTest(800f, 450f, z = 0.6f)
        run(g, 0.05f)
        g.moveCrosshairForTest(g.targets[0].x, g.targets[0].y)
        g.mark()
        assertEquals(1, g.score)
        assertTrue("an immediate second shot does nothing", g.mark().isEmpty())
        assertEquals(1, g.score)
        run(g, DrillEngine.MARK_COOLDOWN + 0.1f)
        g.moveCrosshairForTest(g.targets.single().x, g.targets.single().y)
        g.mark()
        assertEquals(2, g.score)
    }

    @Test
    fun `with two lined up, the nearer one is hit first`() {
        val g = quiet()
        g.addTargetForTest(800f, 450f, z = 0.8f)
        g.addTargetForTest(800f, 450f, z = 0.3f)
        run(g, 0.05f)
        val nearest = g.targets.minByOrNull { it.z }!!
        g.moveCrosshairForTest(nearest.x, nearest.y)
        g.mark()
        assertEquals(1, g.targets.size)
        assertTrue(g.targets.single().z > 0.5f)
    }

    @Test
    fun `targets grow and move out from the middle as they come at you`() {
        val g = quiet()
        g.addTargetForTest(1400f, 200f, z = 0.9f)
        run(g, 0.05f)
        val far = g.targets.single().let { Triple(it.x, it.y, it.radius) }
        run(g, 2f)
        val near = g.targets.single()
        assertTrue("grew ${far.third} -> ${near.radius}", near.radius > far.third)
        assertTrue("moved towards its target", near.x > far.first && near.y < far.second)
        assertTrue(near.z < 0.9f)
    }

    @Test
    fun `one that reaches you costs a life and is gone`() {
        val g = quiet()
        g.addTargetForTest(200f, 200f, z = 0.02f)
        val events = run(g, 1f)
        assertEquals(2, g.margin)
        assertTrue(g.targets.isEmpty())
        assertTrue(events.any { it is DrillEvent.Strike })
        assertEquals(DrillState.PLAYING, g.state)
    }

    @Test
    fun `the third one ends the drill`() {
        val g = quiet()
        repeat(3) { g.addTargetForTest(200f + it * 300f, 200f, z = 0.02f) }
        val events = run(g, 1f)
        assertEquals(DrillState.ENDED, g.state)
        assertEquals(0, g.margin)
        assertTrue(events.any { it is DrillEvent.Ended })
    }

    @Test
    fun `after drill over nothing moves or scores until restart, and restart starts clean`() {
        val g = quiet()
        repeat(3) { g.addTargetForTest(200f + it * 300f, 200f, z = 0.02f) }
        run(g, 1f)
        val x = g.crosshairX
        run(g, 1f, stickX = 1f)
        assertEquals(x, g.crosshairX, 0.01f)
        assertTrue(g.mark().isEmpty())
        g.restart()
        assertEquals(DrillState.PLAYING, g.state)
        assertEquals(3, g.margin)
        assertEquals(0, g.score)
        assertTrue(g.targets.isEmpty())
        assertEquals(0f, g.time, 0.001f)
    }

    @Test
    fun `the final crash flash still fades after the drill is over`() {
        val g = quiet()
        repeat(3) { g.addTargetForTest(200f + it * 300f, 200f, z = 0.02f) }
        run(g, 0.4f)
        assertEquals(DrillState.ENDED, g.state)
        assertTrue(g.effects.isNotEmpty())
        run(g, 2f)
        assertTrue("still ${g.effects.size} effects", g.effects.isEmpty())
    }

    @Test
    fun `it gets harder, faster and more frequent, up to a limit`() {
        assertEquals(1f, DrillEngine.speedFactor(0f), 1e-4f)
        assertTrue(DrillEngine.speedFactor(30f) > DrillEngine.speedFactor(10f))
        assertTrue(DrillEngine.speedFactor(60f) > DrillEngine.speedFactor(30f))
        assertEquals(DrillEngine.speedFactor(10_000f), DrillEngine.speedFactor(20_000f), 1e-4f)
        assertTrue(DrillEngine.spawnInterval(60f) < DrillEngine.spawnInterval(0f))
        assertEquals(DrillEngine.spawnInterval(10_000f), DrillEngine.spawnInterval(20_000f), 1e-4f)
        assertTrue("starts gently: the first target takes several seconds to arrive", DrillEngine.travelSeconds(0f) >= 5f)
    }

    @Test
    fun `targets keep arriving on their own, always inside the screen`() {
        val g = DrillEngine(seed = 11)
        var spawned = 0
        var seen = HashSet<Int>()
        repeat(400) { // 20 seconds
            g.step(0.05f, 0f, 0f)
            for (a in g.targets) {
                if (seen.add(a.id)) spawned++
                assertTrue("x ${a.x}", a.x in 0f..g.width)
                assertTrue("y ${a.y}", a.y in 0f..g.height)
            }
        }
        assertTrue("only $spawned spawned", spawned >= 5)
    }

    @Test
    fun `the same seed plays out the same way`() {
        fun trace(seed: Long): List<Int> {
            val g = DrillEngine(seed = seed)
            val ids = ArrayList<Int>()
            repeat(200) { g.step(0.05f, 0f, 0f); ids += g.targets.map { (it.x * 10).toInt() } }
            return ids
        }
        assertEquals(trace(3), trace(3))
        assertNotEquals(trace(3), trace(4))
    }

    @Test
    fun `without marking you eventually lose`() {
        val g = DrillEngine(seed = 2)
        repeat(4_000) { g.step(0.05f, 0f, 0f) } // 200 seconds
        assertEquals(DrillState.ENDED, g.state)
    }

    @Test
    fun `hit and miss flashes fade away`() {
        val g = quiet()
        g.mark() // a miss leaves a flash
        assertTrue(g.effects.isNotEmpty())
        run(g, 2f)
        assertTrue(g.effects.isEmpty())
    }

    @Test
    fun `score counts every target destroyed`() {
        val g = quiet()
        repeat(5) {
            g.addTargetForTest(300f + it * 200f, 400f, z = 0.4f)
            run(g, DrillEngine.MARK_COOLDOWN + 0.05f)
            val a = g.targets.single()
            g.moveCrosshairForTest(a.x, a.y)
            g.mark()
        }
        assertEquals(5, g.score)
    }
}

class BestRecordTest {
    @Test
    fun `a better result replaces the stored best and says so`() {
        val r = BestRecord.submit(previous = 7, score = 9)
        assertEquals(9, r.best)
        assertTrue(r.isNewRecord)
    }

    @Test
    fun `an equal or lower score leaves it alone`() {
        assertFalse(BestRecord.submit(7, 7).isNewRecord)
        assertEquals(7, BestRecord.submit(7, 3).best)
    }

    @Test
    fun `the first ever score with nothing recorded is a record only if it is above zero`() {
        assertTrue(BestRecord.submit(0, 1).isNewRecord)
        assertFalse(BestRecord.submit(0, 0).isNewRecord)
    }
}
