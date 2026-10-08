package io.github.mugenoesis.sidereal.series

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class PanoramaStitcherTest {

    private val hFov = 60.0
    private val vFov = 46.0

    private fun spec(yaw: Double, pitch: Double) = PanoFrameSpec(yaw, pitch)

    // --- projection conventions (independent of the renderer) ---

    @Test
    fun `the direction a frame points at lands in its centre`() {
        val p = PanoramaGeometry.project(spec(30.0, -10.0), 30.0, -10.0, hFov, vFov)!!
        assertEquals(0.0, p.nx, 1e-9)
        assertEquals(0.0, p.ny, 1e-9)
    }

    @Test
    fun `turning right of the frame's direction moves right in the picture and up moves up`() {
        val right = PanoramaGeometry.project(spec(0.0, 0.0), 10.0, 0.0, hFov, vFov)!!
        val up = PanoramaGeometry.project(spec(0.0, 0.0), 0.0, 10.0, hFov, vFov)!!
        assertTrue(right.nx > 0.0)
        assertEquals(0.0, right.ny, 1e-9)
        assertTrue(up.ny > 0.0)
        assertEquals(0.0, up.nx, 1e-9)
    }

    @Test
    fun `half the field of view away lands exactly on the edge`() {
        assertEquals(1.0, PanoramaGeometry.project(spec(0.0, 0.0), hFov / 2, 0.0, hFov, vFov)!!.nx, 1e-9)
        assertEquals(-1.0, PanoramaGeometry.project(spec(0.0, 0.0), -hFov / 2, 0.0, hFov, vFov)!!.nx, 1e-9)
        assertEquals(1.0, PanoramaGeometry.project(spec(0.0, 0.0), 0.0, vFov / 2, hFov, vFov)!!.ny, 1e-9)
    }

    @Test
    fun `a pitched frame still has its top edge half a field of view above its pointing`() {
        val p = PanoramaGeometry.project(spec(0.0, 20.0), 0.0, 20.0 + vFov / 2, hFov, vFov)!!
        assertEquals(1.0, p.ny, 1e-9)
        assertEquals(0.0, p.nx, 1e-9)
    }

    @Test
    fun `a frame turned in yaw sees what is ahead of it`() {
        val p = PanoramaGeometry.project(spec(90.0, 0.0), 90.0 + 15.0, 0.0, hFov, vFov)!!
        assertEquals(tan(Math.toRadians(15.0)) / tan(Math.toRadians(hFov / 2)), p.nx, 1e-9)
    }

    @Test
    fun `directions behind the camera are not in the picture`() {
        assertNull(PanoramaGeometry.project(spec(0.0, 0.0), 180.0, 0.0, hFov, vFov))
        assertNull(PanoramaGeometry.project(spec(0.0, 0.0), 100.0, 0.0, hFov, vFov))
    }

    @Test
    fun `yaw wraps around - a frame at 350 sees the same as one at -10`() {
        val a = PanoramaGeometry.project(spec(350.0, 0.0), -5.0, 0.0, hFov, vFov)!!
        val b = PanoramaGeometry.project(spec(-10.0, 0.0), -5.0, 0.0, hFov, vFov)!!
        assertEquals(b.nx, a.nx, 1e-9)
    }

    @Test
    fun `the gimbal's pitch number grows as the camera looks down`() {
        assertEquals(-20.0, GimbalAngles.toFrameSpec(5f, 20f).pitchDeg, 1e-9)
        assertEquals(15.0, GimbalAngles.toFrameSpec(5f, -15f).pitchDeg, 1e-9)
        assertEquals(5.0, GimbalAngles.toFrameSpec(5f, 20f).yawDeg, 1e-9)
    }

    // --- canvas layout ---

    @Test
    fun `the canvas covers every frame's reach and stays under the pixel budget`() {
        val frames = listOf(spec(-40.0, -10.0), spec(0.0, -10.0), spec(40.0, -10.0), spec(40.0, 20.0), spec(0.0, 20.0), spec(-40.0, 20.0))
        val canvas = PanoramaGeometry.canvasFor(frames, hFov, vFov, maxPixels = 4_000_000L, nativePixelsPerDeg = 70.0)
        assertEquals(-40.0 - hFov / 2, canvas.lonMin, 1e-9)
        assertEquals(40.0 + hFov / 2, canvas.lonMax, 1e-9)
        assertEquals(20.0 + vFov / 2, canvas.latMax, 1e-9)
        assertEquals(-10.0 - vFov / 2, canvas.latMin, 1e-9)
        assertTrue(canvas.width.toLong() * canvas.height <= 4_000_000L)
        assertEquals(0, canvas.width % 2)
        assertEquals(0, canvas.height % 2)
    }

    @Test
    fun `a small panorama is rendered at no more than the camera's own resolution`() {
        val canvas = PanoramaGeometry.canvasFor(listOf(spec(0.0, 0.0)), hFov, vFov, maxPixels = 100_000_000L, nativePixelsPerDeg = 50.0)
        val pxPerDeg = canvas.width / (canvas.lonMax - canvas.lonMin)
        assertTrue("$pxPerDeg", pxPerDeg <= 50.0 * 0.75 + 0.5)
    }

    // --- rendering a known scene ---

    /** A smooth, direction-dependent colour with plenty of detail: what the camera would see. */
    private fun scene(lon: Double, lat: Double): Int {
        val r = (128 + 100 * sin(Math.toRadians(lon * 3.0)) * cos(Math.toRadians(lat * 2.0))).toInt()
        val g = (128 + 100 * sin(Math.toRadians(lat * 5.0 + lon))).toInt()
        val b = (128 + 90 * cos(Math.toRadians(lon * 1.5 - lat * 3.0))).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun photograph(spec: PanoFrameSpec, w: Int = 240, h: Int = 184): Raster {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5) / w * 2 - 1
            val ny = 1 - (y + 0.5) / h * 2
            val (lon, lat) = PanoramaGeometry.directionOf(spec, nx, ny, hFov, vFov)
            px[y * w + x] = scene(lon, lat)
        }
        return Raster(w, h, px)
    }

    private val grid = listOf(
        spec(-35.0, -5.0), spec(0.0, -5.0), spec(35.0, -5.0),
        spec(35.0, 25.0), spec(0.0, 25.0), spec(-35.0, 25.0)
    )

    private fun stitcher(): PanoramaStitcher {
        val canvas = PanoramaGeometry.canvasFor(grid, hFov, vFov, maxPixels = 400_000L, nativePixelsPerDeg = 4.0)
        return PanoramaStitcher(canvas, grid, hFov, vFov)
    }

    private fun render(s: PanoramaStitcher, bandRows: Int): IntArray {
        val out = IntArray(s.canvas.width * s.canvas.height)
        runBlocking {
            var row = 0
            while (row < s.canvas.height) {
                val end = minOf(s.canvas.height, row + bandRows)
                s.renderBand(row, end, { i -> photograph(grid[i]) }, out)
                row = end
            }
        }
        return out
    }

    private fun channelError(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))

    @Test
    fun `a stitched panorama reproduces the scene across the covered area`() {
        val s = stitcher()
        val out = render(s, bandRows = s.canvas.height)
        var checked = 0
        var worst = 0
        var total = 0L
        for (row in 0 until s.canvas.height) for (col in 0 until s.canvas.width) {
            val p = out[row * s.canvas.width + col]
            if ((p ushr 24) == 0) continue
            checked++
            val err = channelError(p, scene(s.canvas.lonAt(col), s.canvas.latAt(row)))
            worst = maxOf(worst, err)
            total += err
        }
        assertTrue("covered pixels: $checked", checked > s.canvas.width * s.canvas.height / 2)
        assertTrue("mean error ${total.toDouble() / checked}", total.toDouble() / checked < 2.0)
        assertTrue("worst error $worst", worst < 24)
    }

    @Test
    fun `rendering in bands gives exactly the same picture as in one go`() {
        val s = stitcher()
        assertTrue(render(s, bandRows = s.canvas.height).contentEquals(render(s, bandRows = 17)))
    }

    @Test
    fun `the order the frames were shot in does not change the picture`() {
        val s = stitcher()
        val whole = render(s, s.canvas.height)
        val reversed = PanoramaStitcher(s.canvas, grid.reversed(), hFov, vFov)
        val out = IntArray(s.canvas.width * s.canvas.height)
        runBlocking { reversed.renderBand(0, s.canvas.height, { i -> photograph(grid.reversed()[i]) }, out) }
        var diff = 0
        for (i in whole.indices) if (channelError(whole[i], out[i]) > 1) diff++
        assertEquals(0, diff)
    }

    @Test
    fun `pixels no frame reaches are left transparent`() {
        val one = listOf(spec(0.0, 0.0))
        val canvas = PanoramaGeometry.canvasFor(listOf(spec(-60.0, 0.0), spec(60.0, 0.0)), hFov, vFov, 200_000L, 4.0)
        val s = PanoramaStitcher(canvas, one, hFov, vFov)
        val out = IntArray(canvas.width * canvas.height)
        runBlocking { s.renderBand(0, canvas.height, { photograph(one[0]) }, out) }
        assertEquals(0, out[0] ushr 24) // far corner: nothing there
        assertEquals(0xFF, out[(canvas.height / 2) * canvas.width + canvas.width / 2] ushr 24) // middle: covered
    }

    @Test
    fun `which rows a frame touches is known without rendering it`() {
        val s = stitcher()
        val top = s.rowsTouchedBy(4) // pitch 25
        val bottom = s.rowsTouchedBy(1) // pitch -5
        assertTrue(top.first < bottom.first)
        assertTrue(top.last < s.canvas.height && bottom.first >= 0)
        assertNotNull(top)
    }

    @Test
    fun `skipping columns a frame cannot reach changes nothing`() {
        val s = stitcher()
        val brute = PanoramaStitcher(s.canvas, grid, hFov, vFov, pruneColumns = false)
        val a = render(s, s.canvas.height)
        val b = IntArray(s.canvas.width * s.canvas.height)
        runBlocking { brute.renderBand(0, s.canvas.height, { i -> photograph(grid[i]) }, b) }
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `skipping columns is also safe for steeply tilted frames`() {
        val steep = listOf(spec(-30.0, 60.0), spec(30.0, 60.0), spec(0.0, 40.0), spec(0.0, -50.0), spec(45.0, -30.0))
        val canvas = PanoramaGeometry.canvasFor(steep, hFov, vFov, 300_000L, 4.0)
        val a = IntArray(canvas.width * canvas.height)
        val b = IntArray(canvas.width * canvas.height)
        runBlocking {
            PanoramaStitcher(canvas, steep, hFov, vFov).renderBand(0, canvas.height, { photograph(steep[it]) }, a)
            PanoramaStitcher(canvas, steep, hFov, vFov, pruneColumns = false).renderBand(0, canvas.height, { photograph(steep[it]) }, b)
        }
        val firstDiff = a.indices.firstOrNull { a[it] != b[it] }
        assertTrue("first difference at row ${firstDiff?.div(canvas.width)} col ${firstDiff?.rem(canvas.width)} of ${canvas.width}x${canvas.height}, lat ${firstDiff?.let { canvas.latAt(it / canvas.width) }} lon ${firstDiff?.let { canvas.lonAt(it % canvas.width) }}", firstDiff == null)
        assertTrue(a.any { (it ushr 24) != 0 })
    }

    @Test
    fun `a band can be written into a buffer that starts at its own first row`() {
        val s = stitcher()
        val whole = render(s, s.canvas.height)
        val from = 40
        val to = 90
        val band = IntArray((to - from) * s.canvas.width)
        runBlocking { s.renderBand(from, to, { i -> photograph(grid[i]) }, band, outRowOrigin = from) }
        assertTrue(band.contentEquals(whole.copyOfRange(from * s.canvas.width, to * s.canvas.width)))
    }

    @Test
    fun `a frame that fails to load is skipped rather than ruining the picture`() {
        val s = stitcher()
        val out = IntArray(s.canvas.width * s.canvas.height)
        runBlocking { s.renderBand(0, s.canvas.height, { i -> if (i == 0) null else photograph(grid[i]) }, out) }
        assertTrue(out.any { (it ushr 24) != 0 })
    }
}
