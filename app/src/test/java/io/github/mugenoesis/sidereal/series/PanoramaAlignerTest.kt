package io.github.mugenoesis.sidereal.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.tan

class PanoramaAlignerTest {

    private val hFov = 60.0
    private val vFov = 46.0

    /** A textured scene: soft blobs scattered over the sphere on a gentle gradient - plenty of corners to lock onto. */
    private class Scene(seed: Long) {
        private val rnd = Random(seed)
        private val blobs = List(900) {
            doubleArrayOf(rnd.nextDouble() * 220 - 110, rnd.nextDouble() * 140 - 70, 0.6 + rnd.nextDouble() * 1.8, (rnd.nextDouble() - 0.5) * 220)
        }
        fun at(lon: Double, lat: Double): Float {
            var v = 128.0 + lat * 0.3
            for (b in blobs) {
                val dl = lon - b[0]; val dt = lat - b[1]
                if (abs(dl) > 8 || abs(dt) > 8) continue
                v += b[3] * exp(-(dl * dl + dt * dt) / (2 * b[2] * b[2]))
            }
            return v.coerceIn(0.0, 255.0).toFloat()
        }
    }

    private fun photograph(scene: Scene, spec: PanoFrameSpec, trueHFov: Double, trueVFov: Double, w: Int = 320, h: Int = 240): GrayImage {
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5) / w * 2 - 1
            val ny = 1 - (y + 0.5) / h * 2
            val (lon, lat) = PanoramaGeometry.directionOf(spec, nx, ny, trueHFov, trueVFov)
            data[y * w + x] = scene.at(lon, lat)
        }
        return GrayImage(w, h, data)
    }

    // nominal pointings of a 3 x 2 grid, and the truth the camera actually did
    private val nominal = listOf(
        PanoFrameSpec(-35.0, -12.0), PanoFrameSpec(0.0, -12.0), PanoFrameSpec(35.0, -12.0),
        PanoFrameSpec(35.0, 12.0), PanoFrameSpec(0.0, 12.0), PanoFrameSpec(-35.0, 12.0)
    )

    private fun shifted(dyaw: List<Double>, dpitch: List<Double>) =
        nominal.mapIndexed { i, s -> PanoFrameSpec(s.yawDeg + dyaw[i], s.pitchDeg + dpitch[i]) }

    @Test
    fun `recovers where the camera really pointed and how wide it really sees`() {
        val scene = Scene(7)
        val truth = shifted(listOf(0.0, 1.2, 2.4, 2.1, 1.0, -0.3), listOf(0.0, -0.8, 0.5, 0.7, -0.5, 0.4))
        val trueScale = 0.94 // the lens sees 6% less (in tangent terms) than the nominal field of view
        val trueH = 2 * Math.toDegrees(atan(tan(Math.toRadians(hFov / 2)) * trueScale))
        val trueV = 2 * Math.toDegrees(atan(tan(Math.toRadians(vFov / 2)) * trueScale))
        val frames = nominal.indices.map { AlignFrame(nominal[it], photograph(scene, truth[it], trueH, trueV)) }

        val result = PanoramaAligner.align(frames, hFov, vFov)

        assertTrue("matches ${result.matchCount}", result.matchCount > 40)
        assertEquals(trueScale, result.fovScale, 0.01)
        // frame 0 is the anchor, so everything is relative to where it really pointed; its own error cancels out
        val anchorShiftYaw = truth[0].yawDeg - nominal[0].yawDeg
        val anchorShiftPitch = truth[0].pitchDeg - nominal[0].pitchDeg
        for (i in nominal.indices) {
            // yaw and field of view trade off slightly (a wider reach looks like a bigger turn), so yaw gets a looser bound
            assertEquals("yaw $i", truth[i].yawDeg - anchorShiftYaw, result.specs[i].yawDeg, 0.5)
            assertEquals("pitch $i", truth[i].pitchDeg - anchorShiftPitch, result.specs[i].pitchDeg, 0.2)
        }
        assertTrue("rms ${result.rmsDegAfter} was ${result.rmsDegBefore}", result.rmsDegAfter < result.rmsDegBefore / 3)
        assertTrue("matches line up to ${result.rmsDegAfter} degrees", result.rmsDegAfter < 0.15)
    }

    @Test
    fun `leaves a correct set of angles alone`() {
        val scene = Scene(11)
        val frames = nominal.map { AlignFrame(it, photograph(scene, it, hFov, vFov)) }
        val result = PanoramaAligner.align(frames, hFov, vFov)
        assertEquals(1.0, result.fovScale, 0.01)
        for (i in nominal.indices) {
            assertEquals(nominal[i].yawDeg, result.specs[i].yawDeg, 0.15)
            assertEquals(nominal[i].pitchDeg, result.specs[i].pitchDeg, 0.15)
        }
    }

    @Test
    fun `frames with nothing to match keep their nominal angles`() {
        val blank = GrayImage(320, 240, FloatArray(320 * 240) { 100f })
        val frames = nominal.map { AlignFrame(it, blank) }
        val result = PanoramaAligner.align(frames, hFov, vFov)
        assertEquals(0, result.matchCount)
        assertEquals(1.0, result.fovScale, 1e-9)
        assertEquals(nominal, result.specs)
    }

    @Test
    fun `a single frame is returned as it is`() {
        val scene = Scene(3)
        val result = PanoramaAligner.align(listOf(AlignFrame(nominal[0], photograph(scene, nominal[0], hFov, vFov))), hFov, vFov)
        assertEquals(listOf(nominal[0]), result.specs)
    }

    @Test
    fun `an exposure change between frames does not stop them matching`() {
        val scene = Scene(5)
        val truth = shifted(listOf(0.0, 0.8, 1.6, 1.4, 0.7, -0.2), List(6) { 0.0 })
        val frames = nominal.indices.map { i ->
            val img = photograph(scene, truth[i], hFov, vFov)
            val gain = 0.6f + 0.15f * i
            AlignFrame(nominal[i], GrayImage(img.width, img.height, FloatArray(img.data.size) { (img.data[it] * gain).coerceAtMost(255f) }))
        }
        val result = PanoramaAligner.align(frames, hFov, vFov)
        assertTrue(result.matchCount > 40)
        assertEquals(truth[2].yawDeg - truth[0].yawDeg, result.specs[2].yawDeg - result.specs[0].yawDeg, 0.25)
    }
}
