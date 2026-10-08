package io.github.mugenoesis.sidereal.series

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A decoded picture: ARGB pixels, row by row. */
class Raster(val width: Int, val height: Int, val pixels: IntArray)

/** Where the camera pointed for one frame, in the gimbal's own angles: yaw turns right, pitch up. */
data class PanoFrameSpec(val yawDeg: Double, val pitchDeg: Double)

/** A direction as seen from a frame: [nx] -1 (left edge) to 1 (right edge), [ny] -1 (bottom) to 1 (top). */
data class FramePoint(val nx: Double, val ny: Double)

/**
 * The output picture: equirectangular, so a column is a bearing and a row an elevation, spread evenly. That keeps
 * the maths simple and handles anything from a small tile to a full circle.
 */
class PanoCanvas(
    val width: Int,
    val height: Int,
    val lonMin: Double,
    val lonMax: Double,
    val latMin: Double,
    val latMax: Double
) {
    fun lonAt(col: Int): Double = lonMin + (col + 0.5) * (lonMax - lonMin) / width
    fun latAt(row: Int): Double = latMax - (row + 0.5) * (latMax - latMin) / height
}

/**
 * The Osmo's reported gimbal angles to the picture geometry's. Measured on the real rig: raising the gimbal's pitch
 * number moves the scene UP in the frame (the camera looks down), so the camera's elevation is the negative of it;
 * yaw turns right as it increases, as assumed.
 */
object GimbalAngles {
    fun toFrameSpec(yawDeg: Float, pitchDeg: Float) = PanoFrameSpec(yawDeg.toDouble(), -pitchDeg.toDouble())
}

object PanoramaGeometry {

    private fun rad(deg: Double) = deg * PI / 180.0
    private fun deg(rad: Double) = rad * 180.0 / PI

    /**
     * Where the direction ([lonDeg] bearing, [latDeg] elevation) falls in the picture a camera pointing at [spec]
     * takes - or null if it is behind the camera. A rectilinear lens is assumed. Values beyond +-1 are outside the
     * picture.
     */
    fun project(spec: PanoFrameSpec, lonDeg: Double, latDeg: Double, hFovDeg: Double, vFovDeg: Double): FramePoint? {
        val psi = rad(spec.yawDeg); val theta = rad(spec.pitchDeg)
        val lam = rad(lonDeg); val phi = rad(latDeg)
        val dx = cos(phi) * sin(lam); val dy = cos(phi) * cos(lam); val dz = sin(phi)
        val fx = cos(theta) * sin(psi); val fy = cos(theta) * cos(psi); val fz = sin(theta)
        val cx = dx * fx + dy * fy + dz * fz
        if (cx <= 1e-9) return null
        val rx = cos(psi); val ry = -sin(psi)
        val ux = -sin(theta) * sin(psi); val uy = -sin(theta) * cos(psi); val uz = cos(theta)
        val px = (dx * rx + dy * ry) / cx
        val py = (dx * ux + dy * uy + dz * uz) / cx
        return FramePoint(px / tan(rad(hFovDeg / 2)), py / tan(rad(vFovDeg / 2)))
    }

    /** The inverse of [project]: the (bearing, elevation) of picture point ([nx], [ny]). */
    fun directionOf(spec: PanoFrameSpec, nx: Double, ny: Double, hFovDeg: Double, vFovDeg: Double): Pair<Double, Double> {
        val psi = rad(spec.yawDeg); val theta = rad(spec.pitchDeg)
        val tx = nx * tan(rad(hFovDeg / 2)); val ty = ny * tan(rad(vFovDeg / 2))
        val x = cos(theta) * sin(psi) + tx * cos(psi) + ty * (-sin(theta) * sin(psi))
        val y = cos(theta) * cos(psi) + tx * (-sin(psi)) + ty * (-sin(theta) * cos(psi))
        val z = sin(theta) + ty * cos(theta)
        val n = sqrt(x * x + y * y + z * z)
        return deg(atan2(x / n, y / n)) to deg(asin(z / n))
    }

    /**
     * The canvas that holds every frame, as sharp as is useful: at most 75% of the camera's own pixels per degree
     * (the frames are resampled, so the full rate would only add blur and size) and at most [maxPixels] in total.
     */
    fun canvasFor(frames: List<PanoFrameSpec>, hFovDeg: Double, vFovDeg: Double, maxPixels: Long, nativePixelsPerDeg: Double): PanoCanvas {
        val lonMin = frames.minOf { it.yawDeg } - hFovDeg / 2
        val lonMax = frames.maxOf { it.yawDeg } + hFovDeg / 2
        val latMin = frames.minOf { it.pitchDeg } - vFovDeg / 2
        val latMax = frames.maxOf { it.pitchDeg } + vFovDeg / 2
        val lonSpan = lonMax - lonMin
        val latSpan = latMax - latMin
        val ppd = min(nativePixelsPerDeg * 0.75, sqrt(maxPixels / (lonSpan * latSpan)))
        var w = even(floor(lonSpan * ppd).toInt())
        var h = even(floor(latSpan * ppd).toInt())
        while (w.toLong() * h > maxPixels) { w = even(w - 2); h = even(h - 2) }
        return PanoCanvas(w, h, lonMin, lonMax, latMin, latMax)
    }

    private fun even(v: Int) = max(2, v - v % 2)
}

/**
 * Blends the frames onto a [PanoCanvas] using only where each one was pointing - no feature matching, so it works on
 * a night sky as well as a street. Each frame's weight falls towards its edges, so overlaps cross-fade.
 *
 * Rendering is by bands of rows so a large panorama never needs more than one band of working memory, and frames are
 * loaded one at a time by [renderBand]'s loader (and are free to be garbage collected straight after).
 */
class PanoramaStitcher(
    val canvas: PanoCanvas,
    private val frames: List<PanoFrameSpec>,
    private val hFovDeg: Double,
    private val vFovDeg: Double,
    private val pruneColumns: Boolean = true,
    /**
     * How strongly the frame whose centre is nearest wins where frames overlap. 2 cross-fades broadly (smooth, but
     * anything nearer than the scene's far background doubles up because the gimbal turns about its own axes, not the
     * lens); larger values narrow the blend to a seam and show one frame's version of each thing.
     */
    private val blendPower: Int = 2
) {
    private val tanH = tan(hFovDeg / 2 * PI / 180)
    private val tanV = tan(vFovDeg / 2 * PI / 180)

    private val sinLon = DoubleArray(canvas.width) { sin(canvas.lonAt(it) * PI / 180) }
    private val cosLon = DoubleArray(canvas.width) { cos(canvas.lonAt(it) * PI / 180) }
    private val sinLat = DoubleArray(canvas.height) { sin(canvas.latAt(it) * PI / 180) }
    private val cosLat = DoubleArray(canvas.height) { cos(canvas.latAt(it) * PI / 180) }

    /** Canvas rows frame [index] can reach (a degree of margin each side). */
    fun rowsTouchedBy(index: Int): IntRange {
        val f = frames[index]
        val top = f.pitchDeg + vFovDeg / 2 + 1.0
        val bottom = f.pitchDeg - vFovDeg / 2 - 1.0
        val degPerRow = (canvas.latMax - canvas.latMin) / canvas.height
        val first = floor((canvas.latMax - top) / degPerRow).toInt().coerceIn(0, canvas.height - 1)
        val last = floor((canvas.latMax - bottom) / degPerRow).toInt().coerceIn(0, canvas.height - 1)
        return first..last
    }

    /**
     * Draws canvas rows [rowStart, rowEnd) into [out], whose first row is canvas row [outRowOrigin]. Pixels no frame
     * reaches are left fully transparent (alpha 0); the rest are opaque.
     *
     * @param loader returns frame i's picture, or null if it could not be read (that frame is then left out)
     */
    suspend fun renderBand(rowStart: Int, rowEnd: Int, loader: suspend (Int) -> Raster?, out: IntArray, outRowOrigin: Int = 0) {
        val w = canvas.width
        val rows = rowEnd - rowStart
        val acc = FloatArray(rows * w * 4)
        for (i in frames.indices) {
            val touched = rowsTouchedBy(i)
            if (touched.last < rowStart || touched.first >= rowEnd) continue
            val raster = loader(i) ?: continue
            accumulate(i, raster, rowStart, rowEnd, acc)
        }
        for (r in 0 until rows) {
            val outBase = (rowStart + r - outRowOrigin) * w
            for (c in 0 until w) {
                val a = (r * w + c) * 4
                val weight = acc[a + 3]
                out[outBase + c] = if (weight <= 0f) 0 else {
                    (0xFF shl 24) or
                        (to8(acc[a] / weight) shl 16) or
                        (to8(acc[a + 1] / weight) shl 8) or
                        to8(acc[a + 2] / weight)
                }
            }
        }
    }

    private fun to8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    private suspend fun accumulate(index: Int, raster: Raster, rowStart: Int, rowEnd: Int, acc: FloatArray) {
        val chunks = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rows = rowEnd - rowStart
        val per = (rows + chunks - 1) / chunks
        coroutineScope {
            (0 until chunks).map { k ->
                val from = rowStart + k * per
                val to = min(rowEnd, from + per)
                async(Dispatchers.Default) { if (from < to) accumulateRows(index, raster, from, to, rowStart, acc) }
            }.awaitAll()
        }
    }

    private fun accumulateRows(index: Int, raster: Raster, from: Int, to: Int, bandStart: Int, acc: FloatArray) {
        val spec = frames[index]
        val psi = spec.yawDeg * PI / 180
        val theta = spec.pitchDeg * PI / 180
        val fx = cos(theta) * sin(psi); val fy = cos(theta) * cos(psi); val fz = sin(theta)
        val rx = cos(psi); val ry = -sin(psi)
        val ux = -sin(theta) * sin(psi); val uy = -sin(theta) * cos(psi); val uz = cos(theta)
        val sinPsi = sin(psi); val cosPsi = cos(psi)
        val w = canvas.width
        val rw = raster.width
        val rh = raster.height
        val px = raster.pixels

        for (row in from until to) {
            val cl = cosLat[row]
            val sl = sinLat[row]
            var colFrom = 0
            var colTo = w
            if (pruneColumns) {
                // How far either side of its own bearing the frame reaches on this row. It widens a lot towards the
                // poles, so it is measured rather than guessed.
                var reach = -1
                for (k in 0 until DELTA_STEPS) {
                    val sd = SIN_DELTA[k]; val cd = COS_DELTA[k]
                    val dx = cl * (sinPsi * cd + cosPsi * sd); val dy = cl * (cosPsi * cd - sinPsi * sd); val dz = sl
                    val cx = dx * fx + dy * fy + dz * fz
                    if (cx <= 1e-9) continue
                    val nx = ((dx * rx + dy * ry) / cx) / tanH
                    val ny = ((dx * ux + dy * uy + dz * uz) / cx) / tanV
                    if (abs(nx) < 1.0 && abs(ny) < 1.0) reach = k
                }
                if (reach < 0) continue
                val perCol = (canvas.lonMax - canvas.lonMin) / w
                val centre = (spec.yawDeg - canvas.lonMin) / perCol
                val span = (reach + 2) * DELTA_DEG / perCol
                colFrom = max(0, floor(centre - span).toInt())
                colTo = min(w, floor(centre + span).toInt() + 2)
            }
            val accRow = (row - bandStart) * w * 4
            for (col in colFrom until colTo) {
                val dx = cl * sinLon[col]; val dy = cl * cosLon[col]; val dz = sl
                val cx = dx * fx + dy * fy + dz * fz
                if (cx <= 1e-9) continue
                val nx = ((dx * rx + dy * ry) / cx) / tanH
                val ny = ((dx * ux + dy * uy + dz * uz) / cx) / tanV
                if (nx <= -1.0 || nx >= 1.0 || ny <= -1.0 || ny >= 1.0) continue
                val edge = min(1.0 - abs(nx), 1.0 - abs(ny))
                val weight = Math.pow(edge, blendPower.toDouble()).toFloat()
                if (weight <= 0f) continue

                val sx = (nx + 1) / 2 * rw - 0.5
                val sy = (1 - ny) / 2 * rh - 0.5
                val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
                val fxr = (sx - x0).toFloat(); val fyr = (sy - y0).toFloat()
                val xa = x0.coerceIn(0, rw - 1); val xb = (x0 + 1).coerceIn(0, rw - 1)
                val ya = y0.coerceIn(0, rh - 1); val yb = (y0 + 1).coerceIn(0, rh - 1)
                val p00 = px[ya * rw + xa]; val p10 = px[ya * rw + xb]
                val p01 = px[yb * rw + xa]; val p11 = px[yb * rw + xb]
                val w00 = (1 - fxr) * (1 - fyr); val w10 = fxr * (1 - fyr)
                val w01 = (1 - fxr) * fyr; val w11 = fxr * fyr
                val r = ch(p00, 16) * w00 + ch(p10, 16) * w10 + ch(p01, 16) * w01 + ch(p11, 16) * w11
                val g = ch(p00, 8) * w00 + ch(p10, 8) * w10 + ch(p01, 8) * w01 + ch(p11, 8) * w11
                val b = ch(p00, 0) * w00 + ch(p10, 0) * w10 + ch(p01, 0) * w01 + ch(p11, 0) * w11
                val a = accRow + col * 4
                acc[a] += r * weight
                acc[a + 1] += g * weight
                acc[a + 2] += b * weight
                acc[a + 3] += weight
            }
        }
    }

    private fun ch(p: Int, shift: Int): Float = ((p shr shift) and 0xFF).toFloat()

    private companion object {
        const val DELTA_DEG = 0.5
        const val DELTA_STEPS = 361
        val SIN_DELTA = DoubleArray(DELTA_STEPS) { sin(it * DELTA_DEG * PI / 180) }
        val COS_DELTA = DoubleArray(DELTA_STEPS) { cos(it * DELTA_DEG * PI / 180) }
    }
}
