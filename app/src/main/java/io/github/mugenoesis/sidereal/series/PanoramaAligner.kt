package io.github.mugenoesis.sidereal.series

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A grey picture, 0..255 per pixel, row by row. */
class GrayImage(val width: Int, val height: Int, val data: FloatArray) {
    /** Half the size, each pixel the mean of a 2x2 block. */
    fun halved(): GrayImage {
        val w = width / 2
        val h = height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (2 * y) * width + 2 * x
            out[y * w + x] = (data[i] + data[i + 1] + data[i + width] + data[i + width + 1]) / 4f
        }
        return GrayImage(w, h, out)
    }
}

/** A frame at the angles the gimbal reported, as a small grey picture. */
class AlignFrame(val spec: PanoFrameSpec, val image: GrayImage)

/**
 * @param specs corrected pointing, same order as the input (the first frame is the reference and does not move)
 * @param fovScale how much of the nominal field of view (in tangent terms) the lens really sees: 0.94 = 6% narrower
 * @param matchCount feature matches the correction is based on; 0 means nothing could be matched and the input is returned untouched
 */
data class Alignment(
    val specs: List<PanoFrameSpec>,
    val fovScale: Double,
    val matchCount: Int,
    val rmsDegBefore: Double,
    val rmsDegAfter: Double
)

/**
 * The gimbal's angles are good to a degree or two and the lens' true field of view is only known from its
 * specification, so stitching purely by angles leaves visible double images. This finds small distinctive spots
 * (corners) in each frame, finds the same spots in the neighbouring frames, and then solves for the field-of-view
 * scale and each frame's small yaw/pitch correction that make the matches line up - the core of what panorama
 * programs call bundle adjustment, kept small enough to run on a phone.
 *
 * It needs texture: a clear sky has nothing to lock onto (stars do), and then the angles are used as they are.
 */
object PanoramaAligner {

    private const val MIN_MATCHES = 12
    private const val MAX_OFFSET_RAD = 8.0 * PI / 180
    private const val KEYPOINT_COLS = 10
    private const val KEYPOINT_ROWS = 8
    private const val COARSE_PATCH = 4 // half size: 9x9
    private const val FINE_PATCH = 7 // half size: 15x15
    private const val COARSE_NCC = 0.70
    private const val FINE_NCC = 0.80

    private class Match(val i: Int, val j: Int, val ax: Double, val ay: Double, val bx: Double, val by: Double)

    fun align(frames: List<AlignFrame>, hFovDeg: Double, vFovDeg: Double): Alignment {
        val nominal = frames.map { it.spec }
        if (frames.size < 2) return Alignment(nominal, 1.0, 0, 0.0, 0.0)

        var specs = nominal
        var scale = 1.0
        var firstRms = Double.NaN
        var lastRms = 0.0
        var lastCount = 0
        for (searchDeg in listOf(8.0, 2.0)) {
            val matches = matchAll(frames, specs, scale, hFovDeg, vFovDeg, searchDeg)
            if (matches.size < MIN_MATCHES) { if (firstRms.isNaN()) return Alignment(nominal, 1.0, matches.size, 0.0, 0.0) else break }
            val fit = optimise(matches, specs, scale, hFovDeg, vFovDeg)
            if (firstRms.isNaN()) firstRms = fit.rmsBefore
            specs = fit.specs
            scale = fit.scale
            lastRms = fit.rmsAfter
            lastCount = fit.inliers
        }
        return Alignment(specs, scale, lastCount, firstRms, lastRms)
    }

    // ---------------------------------------------------------------- matching

    /** A field of view scaled in tangent terms: [scale] 0.94 sees 6% less across the picture. */
    fun effectiveFov(fovDeg: Double, scale: Double) = 2 * atan(tan(fovDeg / 2 * PI / 180) * scale) * 180 / PI

    private fun matchAll(
        frames: List<AlignFrame>, specs: List<PanoFrameSpec>, scale: Double, hFov: Double, vFov: Double, searchDeg: Double
    ): List<Match> {
        val hEff = effectiveFov(hFov, scale)
        val vEff = effectiveFov(vFov, scale)
        val pyramids = frames.map { it.image to it.image.halved() }
        val out = ArrayList<Match>()
        for (i in frames.indices) {
            val keypoints = keypointsOf(frames[i].image)
            for (j in frames.indices) {
                if (i == j) continue
                // each pair once, from the lower index's side, so no spot is counted twice
                if (j < i) continue
                matchPair(i, j, pyramids[i], pyramids[j], keypoints, specs[i], specs[j], hEff, vEff, searchDeg, out)
            }
        }
        return out
    }

    /** Harris corners, the strongest in each cell of a grid so they spread over the picture. */
    private fun keypointsOf(img: GrayImage): List<IntArray> {
        val w = img.width; val h = img.height
        val response = FloatArray(w * h)
        val gx = FloatArray(w * h); val gy = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            gx[y * w + x] = (img.data[y * w + x + 1] - img.data[y * w + x - 1]) / 2f
            gy[y * w + x] = (img.data[(y + 1) * w + x] - img.data[(y - 1) * w + x]) / 2f
        }
        val r = 2
        var maxR = 0f
        for (y in r + 1 until h - r - 1) for (x in r + 1 until w - r - 1) {
            var sxx = 0f; var syy = 0f; var sxy = 0f
            for (dy in -r..r) for (dx in -r..r) {
                val a = gx[(y + dy) * w + x + dx]; val b = gy[(y + dy) * w + x + dx]
                sxx += a * a; syy += b * b; sxy += a * b
            }
            val det = sxx * syy - sxy * sxy
            val tr = sxx + syy
            val v = det - 0.05f * tr * tr
            response[y * w + x] = v
            if (v > maxR) maxR = v
        }
        if (maxR <= 0f) return emptyList()
        val threshold = maxR * 0.002f
        val best = HashMap<Int, IntArray>()
        val margin = FINE_PATCH + 2
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val v = response[y * w + x]
            if (v < threshold) continue
            // local maximum in 5x5
            var isMax = true
            loop@ for (dy in -2..2) for (dx in -2..2) if ((dx != 0 || dy != 0) && response[(y + dy) * w + x + dx] > v) { isMax = false; break@loop }
            if (!isMax) continue
            val cell = (y * KEYPOINT_ROWS / h) * KEYPOINT_COLS + (x * KEYPOINT_COLS / w)
            val cur = best[cell]
            if (cur == null || response[cur[1] * w + cur[0]] < v) best[cell] = intArrayOf(x, y)
        }
        return best.values.toList()
    }

    private fun matchPair(
        i: Int, j: Int, a: Pair<GrayImage, GrayImage>, b: Pair<GrayImage, GrayImage>, keypoints: List<IntArray>,
        specA: PanoFrameSpec, specB: PanoFrameSpec, hEff: Double, vEff: Double, searchDeg: Double, out: MutableList<Match>
    ) {
        val (a0, a1) = a
        val (b0, b1) = b
        val ppd = a0.width / hEff
        val radius0 = ceil(searchDeg * ppd).toInt()
        val radius1 = (radius0 + 1) / 2
        for (kp in keypoints) {
            val nxA = (kp[0] + 0.5) / a0.width * 2 - 1
            val nyA = 1 - (kp[1] + 0.5) / a0.height * 2
            val (lon, lat) = PanoramaGeometry.directionOf(specA, nxA, nyA, hEff, vEff)
            val p = PanoramaGeometry.project(specB, lon, lat, hEff, vEff) ?: continue
            if (abs(p.nx) > 0.9 || abs(p.ny) > 0.9) continue
            val px = (p.nx + 1) / 2 * b0.width - 0.5
            val py = (1 - p.ny) / 2 * b0.height - 0.5

            // coarse: half-size pictures, wide search
            val tx = kp[0] / 2; val ty = kp[1] / 2
            val template = patch(a1, tx, ty, COARSE_PATCH) ?: continue
            val cx = (px / 2).toInt(); val cy = (py / 2).toInt()
            var best = -2.0; var bx = 0; var by = 0; var second = -2.0
            for (dy in -radius1..radius1) for (dx in -radius1..radius1) {
                val x = cx + dx; val y = cy + dy
                val score = ncc(template, b1, x, y, COARSE_PATCH) ?: continue
                if (score > best) {
                    if (abs(x - bx) > 3 || abs(y - by) > 3) second = best
                    best = score; bx = x; by = y
                } else if (score > second && (abs(x - bx) > 3 || abs(y - by) > 3)) second = score
            }
            if (best < COARSE_NCC || best - second < 0.03) continue

            // fine: full-size pictures, a few pixels either side of the coarse answer
            val fineTemplate = patch(a0, kp[0], kp[1], FINE_PATCH) ?: continue
            var fBest = -2.0; var fx = 0; var fy = 0
            for (dy in -3..3) for (dx in -3..3) {
                val x = bx * 2 + dx; val y = by * 2 + dy
                val score = ncc(fineTemplate, b0, x, y, FINE_PATCH) ?: continue
                if (score > fBest) { fBest = score; fx = x; fy = y }
            }
            if (fBest < FINE_NCC) continue
            val subX = fx + subPixel(fineTemplate, b0, fx, fy, 1, 0)
            val subY = fy + subPixel(fineTemplate, b0, fx, fy, 0, 1)
            out += Match(
                i, j, nxA, nyA,
                (subX + 0.5) / b0.width * 2 - 1,
                1 - (subY + 0.5) / b0.height * 2
            )
        }
    }

    private class Patch(val values: FloatArray, val norm: Double)

    private fun patch(img: GrayImage, cx: Int, cy: Int, half: Int): Patch? {
        if (cx - half < 0 || cy - half < 0 || cx + half >= img.width || cy + half >= img.height) return null
        val size = 2 * half + 1
        val v = FloatArray(size * size)
        var sum = 0.0
        var k = 0
        for (y in cy - half..cy + half) for (x in cx - half..cx + half) { val p = img.data[y * img.width + x]; v[k++] = p; sum += p }
        val mean = (sum / v.size).toFloat()
        var sq = 0.0
        for (i in v.indices) { v[i] -= mean; sq += v[i] * v[i].toDouble() }
        val norm = sqrt(sq)
        if (norm < 1e-3 * v.size) return null // flat: nothing to match
        return Patch(v, norm)
    }

    /** Normalised cross-correlation of [template] with the patch of [img] centred on (cx, cy); null if it does not fit or is flat. */
    private fun ncc(template: Patch, img: GrayImage, cx: Int, cy: Int, half: Int): Double? {
        if (cx - half < 0 || cy - half < 0 || cx + half >= img.width || cy + half >= img.height) return null
        val n = (2 * half + 1) * (2 * half + 1)
        var sum = 0.0
        for (y in cy - half..cy + half) { val row = y * img.width; for (x in cx - half..cx + half) sum += img.data[row + x] }
        val mean = sum / n
        var dot = 0.0; var sq = 0.0
        var k = 0
        for (y in cy - half..cy + half) {
            val row = y * img.width
            for (x in cx - half..cx + half) {
                val d = img.data[row + x] - mean
                dot += d * template.values[k++]
                sq += d * d
            }
        }
        if (sq < 1e-3 * n) return null
        return dot / (template.norm * sqrt(sq))
    }

    /** Parabola through the correlation at -1, 0, +1 along (dx, dy): the sub-pixel peak offset. */
    private fun subPixel(template: Patch, img: GrayImage, x: Int, y: Int, dx: Int, dy: Int): Double {
        val c0 = ncc(template, img, x, y, FINE_PATCH) ?: return 0.0
        val cm = ncc(template, img, x - dx, y - dy, FINE_PATCH) ?: return 0.0
        val cp = ncc(template, img, x + dx, y + dy, FINE_PATCH) ?: return 0.0
        val denom = cm - 2 * c0 + cp
        if (abs(denom) < 1e-9) return 0.0
        return (0.5 * (cm - cp) / denom).coerceIn(-0.5, 0.5)
    }

    // ---------------------------------------------------------------- solving

    private class Fit(val specs: List<PanoFrameSpec>, val scale: Double, val rmsBefore: Double, val rmsAfter: Double, val inliers: Int)

    /** Unit ray (world) through picture point (nx, ny) of a frame pointing at (yaw, pitch), with the field of view scaled by [scale]. */
    private fun ray(yawRad: Double, pitchRad: Double, nx: Double, ny: Double, tanH: Double, tanV: Double, scale: Double, out: DoubleArray) {
        val fx = cos(pitchRad) * sin(yawRad); val fy = cos(pitchRad) * cos(yawRad); val fz = sin(pitchRad)
        val rx = cos(yawRad); val ry = -sin(yawRad)
        val ux = -sin(pitchRad) * sin(yawRad); val uy = -sin(pitchRad) * cos(yawRad); val uz = cos(pitchRad)
        val a = nx * tanH * scale
        val b = ny * tanV * scale
        val x = fx + a * rx + b * ux
        val y = fy + a * ry + b * uy
        val z = fz + b * uz
        val n = sqrt(x * x + y * y + z * z)
        out[0] = x / n; out[1] = y / n; out[2] = z / n
    }

    private fun optimise(matches: List<Match>, start: List<PanoFrameSpec>, startScale: Double, hFov: Double, vFov: Double): Fit {
        val n = start.size
        val tanH = tan(hFov / 2 * PI / 180)
        val tanV = tan(vFov / 2 * PI / 180)
        // unknowns: ln(scale), then (yaw, pitch) offsets of frames 1..n-1 from the starting specs, in radians
        val dim = 1 + 2 * (n - 1)
        val x = DoubleArray(dim)
        x[0] = ln(startScale)
        val baseYaw = DoubleArray(n) { start[it].yawDeg * PI / 180 }
        val basePitch = DoubleArray(n) { start[it].pitchDeg * PI / 180 }

        fun yawOf(f: Int, p: DoubleArray) = baseYaw[f] + if (f == 0) 0.0 else p[1 + 2 * (f - 1)]
        fun pitchOf(f: Int, p: DoubleArray) = basePitch[f] + if (f == 0) 0.0 else p[2 + 2 * (f - 1)]

        val ra = DoubleArray(3); val rb = DoubleArray(3)
        fun residual(m: Match, p: DoubleArray, out: DoubleArray) {
            val s = kotlin.math.exp(p[0])
            ray(yawOf(m.i, p), pitchOf(m.i, p), m.ax, m.ay, tanH, tanV, s, ra)
            ray(yawOf(m.j, p), pitchOf(m.j, p), m.bx, m.by, tanH, tanV, s, rb)
            out[0] = ra[0] - rb[0]; out[1] = ra[1] - rb[1]; out[2] = ra[2] - rb[2]
        }

        fun rmsDeg(p: DoubleArray, weights: DoubleArray?): Double {
            val r = DoubleArray(3)
            var sum = 0.0; var cnt = 0.0
            for ((k, m) in matches.withIndex()) {
                val w = weights?.get(k) ?: 1.0
                if (w <= 0.0) continue
                residual(m, p, r)
                sum += (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]); cnt += 1.0
            }
            return if (cnt == 0.0) 0.0 else sqrt(sum / cnt) * 180 / PI
        }

        val before = rmsDeg(x, null)
        val weights = DoubleArray(matches.size) { 1.0 }
        val eps = 1e-6
        val r0 = DoubleArray(3); val r1 = DoubleArray(3); val r2 = DoubleArray(3)
        var lambda = 1e-3

        for (outer in 0 until 8) {
            // robust weights from the current residuals
            val norms = DoubleArray(matches.size)
            for ((k, m) in matches.withIndex()) { residual(m, x, r0); norms[k] = sqrt(r0[0] * r0[0] + r0[1] * r0[1] + r0[2] * r0[2]) }
            val sorted = norms.sortedArray()
            val sigma = max(sorted[sorted.size / 2] * 1.4826, 0.05 * PI / 180)
            val delta = 2.5 * sigma
            for (k in matches.indices) weights[k] = if (norms[k] <= delta) 1.0 else if (norms[k] > 4 * delta) 0.0 else delta / norms[k]

            val jtj = Array(dim) { DoubleArray(dim) }
            val jtr = DoubleArray(dim)
            val cols = IntArray(5)
            val jac = Array(3) { DoubleArray(5) }
            for ((k, m) in matches.withIndex()) {
                val w = weights[k]
                if (w <= 0.0) continue
                residual(m, x, r0)
                cols[0] = 0
                cols[1] = if (m.i == 0) -1 else 1 + 2 * (m.i - 1)
                cols[2] = if (m.i == 0) -1 else 2 + 2 * (m.i - 1)
                cols[3] = if (m.j == 0) -1 else 1 + 2 * (m.j - 1)
                cols[4] = if (m.j == 0) -1 else 2 + 2 * (m.j - 1)
                for (c in 0 until 5) {
                    val col = cols[c]
                    if (col < 0) { for (r in 0 until 3) jac[r][c] = 0.0; continue }
                    val saved = x[col]
                    x[col] = saved + eps; residual(m, x, r1)
                    x[col] = saved - eps; residual(m, x, r2)
                    x[col] = saved
                    for (r in 0 until 3) jac[r][c] = (r1[r] - r2[r]) / (2 * eps)
                }
                for (a in 0 until 5) {
                    val ca = cols[a]; if (ca < 0) continue
                    for (r in 0 until 3) jtr[ca] += w * jac[r][a] * r0[r]
                    for (b in 0 until 5) {
                        val cb = cols[b]; if (cb < 0) continue
                        var s = 0.0
                        for (r in 0 until 3) s += jac[r][a] * jac[r][b]
                        jtj[ca][cb] += w * s
                    }
                }
            }
            // a gentle pull towards "no correction" keeps frames with few matches from wandering
            val prior = 1e-4
            val currentCost = weightedCost(matches, weights, x, ::residual) + priorCost(x, prior)
            var improved = false
            for (attempt in 0 until 6) {
                val a = Array(dim) { r -> DoubleArray(dim) { c -> jtj[r][c] } }
                val rhs = DoubleArray(dim) { -jtr[it] - prior * (x[it] - if (it == 0) ln(startScale) else 0.0) }
                for (d in 0 until dim) a[d][d] += lambda * (a[d][d] + 1e-9) + prior
                val step = solve(a, rhs) ?: break
                val trial = DoubleArray(dim) { x[it] + step[it] }
                trial[0] = trial[0].coerceIn(ln(0.7), ln(1.3))
                for (d in 1 until dim) trial[d] = trial[d].coerceIn(-MAX_OFFSET_RAD, MAX_OFFSET_RAD)
                val trialCost = weightedCost(matches, weights, trial, ::residual) + priorCost(trial, prior)
                if (trialCost < currentCost) {
                    System.arraycopy(trial, 0, x, 0, dim)
                    lambda = max(lambda / 4, 1e-9)
                    improved = true
                    break
                }
                lambda *= 8
            }
            if (!improved) break
        }

        val inliers = weights.count { it > 0.5 }
        val after = rmsDeg(x, weights)
        val specs = start.indices.map { f ->
            PanoFrameSpec(yawOf(f, x) * 180 / PI, pitchOf(f, x) * 180 / PI)
        }
        return Fit(specs, kotlin.math.exp(x[0]), rmsDeg(DoubleArray(dim).also { it[0] = ln(startScale) }, weights).coerceAtLeast(before.coerceAtMost(before)), after, inliers)
    }

    private fun weightedCost(matches: List<Match>, w: DoubleArray, p: DoubleArray, residual: (Match, DoubleArray, DoubleArray) -> Unit): Double {
        val r = DoubleArray(3)
        var c = 0.0
        for ((k, m) in matches.withIndex()) {
            if (w[k] <= 0.0) continue
            residual(m, p, r)
            c += w[k] * (r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        }
        return c
    }

    private fun priorCost(p: DoubleArray, prior: Double): Double {
        var c = 0.0
        for (d in 1 until p.size) c += prior * p[d] * p[d]
        return c
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-18) return null
            if (pivot != col) { val t = a[pivot]; a[pivot] = a[col]; a[col] = t; val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}
