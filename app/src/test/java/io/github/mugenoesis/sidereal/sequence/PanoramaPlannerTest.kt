package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaPlannerTest {

    private fun config(
        yawSpan: Float = 140f,
        pitchSpan: Float = 100f,
        overlap: Float = 0.3f,
        shotsPerNode: Int = 1,
        centerPitch: Float = -20f,
        centerYaw: Float = 0f,
        pitchLimits: ClosedFloatingPointRange<Float>? = null,
        yawLimits: ClosedFloatingPointRange<Float>? = null
    ) = PanoramaConfig(
        center = Attitude(centerPitch, centerYaw),
        yawSpanDeg = yawSpan, pitchSpanDeg = pitchSpan,
        hFovDeg = 60f, vFovDeg = 46f,
        overlap = overlap, settleMs = 500, exposureMs = 10_000, shotsPerNode = shotsPerNode,
        pitchLimits = pitchLimits, yawLimits = yawLimits
    )

    @Test
    fun `fov for 15mm on a micro four thirds sensor`() {
        val (h, v) = PanoramaPlanner.fovFor(focalMm = 15f)
        assertEquals(59.9f, h, 0.2f)
        assertEquals(46.8f, v, 0.2f)
    }

    @Test
    fun `span no bigger than one fov needs a single node on that axis`() {
        val plan = PanoramaPlanner.plan(config(yawSpan = 50f, pitchSpan = 40f))
        assertEquals(1, plan.cols)
        assertEquals(1, plan.rows)
        assertEquals(listOf(Node(0, 0, -20f, 0f)), plan.nodes)
    }

    @Test
    fun `140 degrees of yaw at 60 fov and 30 percent overlap needs three columns`() {
        val plan = PanoramaPlanner.plan(config(pitchSpan = 40f))
        assertEquals(3, plan.cols)
        assertEquals(listOf(-40f, 0f, 40f), plan.nodes.map { it.yaw })
    }

    @Test
    fun `grid is centered on the requested center`() {
        val plan = PanoramaPlanner.plan(config(centerPitch = -15f, centerYaw = 90f))
        assertEquals(90f, plan.nodes.map { it.yaw }.average().toFloat(), 1e-3f)
        assertEquals(-15f, plan.nodes.map { it.pitch }.average().toFloat(), 1e-3f)
    }

    @Test
    fun `every adjacent column pair overlaps by at least the requested fraction`() {
        for (overlap in listOf(0.2f, 0.3f, 0.5f)) {
            val plan = PanoramaPlanner.plan(config(yawSpan = 300f, pitchSpan = 40f, overlap = overlap))
            val yaws = plan.nodes.map { it.yaw }.distinct().sorted()
            for (i in 1 until yaws.size) {
                val actualOverlap = 1f - (yaws[i] - yaws[i - 1]) / 60f
                assertTrue("overlap $actualOverlap < $overlap", actualOverlap >= overlap - 1e-4f)
            }
        }
    }

    @Test
    fun `rows overlap vertically too`() {
        val plan = PanoramaPlanner.plan(config(yawSpan = 50f, pitchSpan = 100f, overlap = 0.3f))
        val pitches = plan.nodes.map { it.pitch }.distinct().sorted()
        assertTrue(pitches.size >= 3)
        for (i in 1 until pitches.size) {
            assertTrue(1f - (pitches[i] - pitches[i - 1]) / 46f >= 0.3f - 1e-4f)
        }
    }

    @Test
    fun `node count is rows times columns`() {
        val plan = PanoramaPlanner.plan(config())
        assertEquals(plan.rows * plan.cols, plan.nodes.size)
    }

    @Test
    fun `visits rows in a serpentine so the gimbal never swings back across the whole width`() {
        val plan = PanoramaPlanner.plan(config())
        val byRow = plan.nodes.groupBy { it.row }
        assertEquals(byRow[0]!!.map { it.yaw }, byRow[0]!!.map { it.yaw }.sorted())
        assertEquals(byRow[1]!!.map { it.yaw }, byRow[1]!!.map { it.yaw }.sortedDescending())
        assertEquals(byRow[0]!!.last().yaw, byRow[1]!!.first().yaw, 0f)
    }

    @Test
    fun `each node gets move then settle then capture`() {
        val steps = PanoramaPlanner.plan(config(yawSpan = 50f, pitchSpan = 40f)).steps
        assertEquals(
            listOf(SequenceStep.MoveTo(-20f, 0f), SequenceStep.Settle(500), SequenceStep.Capture(10_000, "pano 1/1")),
            steps
        )
    }

    @Test
    fun `multiple shots per node share one move and one settle`() {
        val steps = PanoramaPlanner.plan(config(yawSpan = 50f, pitchSpan = 40f, shotsPerNode = 3)).steps
        assertEquals(1, steps.count { it is SequenceStep.MoveTo })
        assertEquals(1, steps.count { it is SequenceStep.Settle })
        assertEquals(3, steps.count { it is SequenceStep.Capture })
    }

    @Test
    fun `capture labels count through the whole panorama`() {
        val plan = PanoramaPlanner.plan(config(yawSpan = 140f, pitchSpan = 40f))
        val labels = plan.steps.filterIsInstance<SequenceStep.Capture>().map { it.label }
        assertEquals(listOf("pano 1/3", "pano 2/3", "pano 3/3"), labels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `grid that tips the camera past the gimbal's pitch limit is rejected`() {
        PanoramaPlanner.plan(config(centerPitch = 20f, pitchSpan = 120f, pitchLimits = -90f..30f))
    }

    @Test
    fun `the same grid is fine on a gimbal whose real pitch range is wider`() {
        PanoramaPlanner.plan(config(centerPitch = 20f, pitchSpan = 120f, pitchLimits = -120f..70f))
    }

    @Test
    fun `without limits nothing is rejected - the caller opted out`() {
        PanoramaPlanner.plan(config(centerPitch = 20f, pitchSpan = 120f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `grid that swings past the gimbal's yaw limit is rejected`() {
        PanoramaPlanner.plan(config(centerYaw = 150f, yawSpan = 140f, yawLimits = -160f..160f))
    }

    @Test
    fun `the error message names the range it needs and the range the gimbal has`() {
        val message = try {
            PanoramaPlanner.plan(config(centerPitch = 20f, pitchSpan = 120f, pitchLimits = -90f..30f)); ""
        } catch (e: IllegalArgumentException) { e.message.orEmpty() }
        assertTrue(message, message.contains("-90") && message.contains("30"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlap of 100 percent or more is rejected`() {
        PanoramaPlanner.plan(config(overlap = 1f))
    }
}
