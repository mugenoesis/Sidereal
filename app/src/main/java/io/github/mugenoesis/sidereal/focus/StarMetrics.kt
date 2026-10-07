package io.github.mugenoesis.sidereal.focus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A grayscale frame, one 0..255 luminance value per pixel, row-major. */
class LumaImage(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(data.size == width * height) { "data size must be width * height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]
}

/** Only look for a star inside this circle - e.g. around the one the user tapped. */
data class SearchWindow(val cx: Int, val cy: Int, val radius: Int)

/**
 * @param x / [y] sub-pixel centre of the star in image coordinates
 * @param fwhm full width at half maximum in pixels - the number to minimise when focusing
 * @param saturated the core is clipped, so the true FWHM is larger than reported - back the exposure off
 */
data class Star(val x: Float, val y: Float, val peak: Float, val fwhm: Float, val saturated: Boolean)

/**
 * Measures how tight a star is, for critical focus on stars: out-of-focus
 * stars are fat discs, in-focus ones collapse to a point, so the smallest
 * FWHM is the best focus. FWHM comes from the azimuthally averaged radial
 * profile around the star's centroid - robust to noise and to the star
 * sitting between pixels - rather than a Gaussian fit, which would be
 * wasted on a star that is anything but Gaussian when defocused.
 */
object StarMetrics {

    private const val CENTROID_RADIUS = 6
    private const val PROFILE_RADIUS = 20
    private const val BIN_WIDTH = 0.5f
    private const val SATURATED_LEVEL = 250f
    private const val MIN_SIGNAL = 12f
    private const val SIGNAL_SIGMAS = 6f

    fun measure(image: LumaImage, window: SearchWindow? = null): Star? {
        val (background, noise) = backgroundAndNoise(image)
        val threshold = max(SIGNAL_SIGMAS * noise, MIN_SIGNAL)

        // Brightest 3x3-smoothed pixel: a single hot pixel smooths away, a real star's core doesn't.
        var bestX = -1
        var bestY = -1
        var bestSmoothed = Float.NEGATIVE_INFINITY
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (window != null && sq(x - window.cx) + sq(y - window.cy) > sq(window.radius)) continue
            val s = boxMean(image, x, y)
            if (s > bestSmoothed) { bestSmoothed = s; bestX = x; bestY = y }
        }
        if (bestX < 0 || bestSmoothed - background < threshold) return null

        // Smoothing makes every pixel around a bright one tie - settle on the brightest raw pixel among them.
        var refinedX = bestX
        var refinedY = bestY
        for (y in max(0, bestY - 1)..min(image.height - 1, bestY + 1)) for (x in max(0, bestX - 1)..min(image.width - 1, bestX + 1)) {
            if (image[x, y] > image[refinedX, refinedY]) { refinedX = x; refinedY = y }
        }
        bestX = refinedX
        bestY = refinedY

        // A hot pixel has dark neighbours; a star's core is at least two pixels wide.
        val rawPeak = image[bestX, bestY]
        val neighbours = listOf(
            sample(image, bestX - 1, bestY), sample(image, bestX + 1, bestY),
            sample(image, bestX, bestY - 1), sample(image, bestX, bestY + 1)
        ).average().toFloat()
        if (neighbours - background < 0.25f * (rawPeak - background)) return null

        val centroid = centroid(image, bestX, bestY, background, rawPeak) ?: return null
        val amplitude = peakAround(image, centroid.first, centroid.second) - background
        if (amplitude <= 0f) return null

        val fwhm = fwhmFromProfile(image, centroid.first, centroid.second, background, amplitude) ?: return null
        return Star(centroid.first, centroid.second, amplitude + background, fwhm, rawPeak >= SATURATED_LEVEL)
    }

    private fun backgroundAndNoise(image: LumaImage): Pair<Float, Float> {
        val sorted = image.data.sortedArray()
        val median = sorted[sorted.size / 2]
        val deviations = FloatArray(sorted.size) { abs(sorted[it] - median) }.sortedArray()
        val mad = deviations[deviations.size / 2]
        return median to mad * 1.4826f
    }

    private fun boxMean(image: LumaImage, cx: Int, cy: Int): Float {
        var sum = 0f
        var n = 0
        for (y in max(0, cy - 1)..min(image.height - 1, cy + 1)) for (x in max(0, cx - 1)..min(image.width - 1, cx + 1)) {
            sum += image[x, y]; n++
        }
        return sum / n
    }

    private fun sample(image: LumaImage, x: Int, y: Int): Float =
        image[x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1)]

    /** Intensity-weighted centre of the star's bright core. */
    private fun centroid(image: LumaImage, px: Int, py: Int, background: Float, rawPeak: Float): Pair<Float, Float>? {
        val floor = 0.2f * (rawPeak - background)
        var sx = 0f
        var sy = 0f
        var sw = 0f
        for (y in max(0, py - CENTROID_RADIUS)..min(image.height - 1, py + CENTROID_RADIUS)) {
            for (x in max(0, px - CENTROID_RADIUS)..min(image.width - 1, px + CENTROID_RADIUS)) {
                if (sq(x - px) + sq(y - py) > sq(CENTROID_RADIUS)) continue
                val w = image[x, y] - background
                if (w <= floor) continue
                sx += w * x; sy += w * y; sw += w
            }
        }
        return if (sw > 0f) (sx / sw) to (sy / sw) else null
    }

    private fun peakAround(image: LumaImage, cx: Float, cy: Float): Float {
        var peak = Float.NEGATIVE_INFINITY
        val x0 = Math.round(cx)
        val y0 = Math.round(cy)
        for (y in max(0, y0 - 1)..min(image.height - 1, y0 + 1)) for (x in max(0, x0 - 1)..min(image.width - 1, x0 + 1)) {
            peak = max(peak, image[x, y])
        }
        return peak
    }

    /** Radius where the radial profile first falls to half the amplitude, doubled. Null if it never does. */
    private fun fwhmFromProfile(image: LumaImage, cx: Float, cy: Float, background: Float, amplitude: Float): Float? {
        val bins = (PROFILE_RADIUS / BIN_WIDTH).toInt()
        val sums = FloatArray(bins)
        val counts = IntArray(bins)
        val x0 = max(0, (cx - PROFILE_RADIUS).toInt())
        val x1 = min(image.width - 1, (cx + PROFILE_RADIUS).toInt())
        val y0 = max(0, (cy - PROFILE_RADIUS).toInt())
        val y1 = min(image.height - 1, (cy + PROFILE_RADIUS).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val r = sqrt(sq(x - cx) + sq(y - cy))
            val bin = (r / BIN_WIDTH).toInt()
            if (bin >= bins) continue
            sums[bin] += image[x, y] - background
            counts[bin]++
        }
        val half = amplitude / 2f
        var prevR = 0f
        var prevV = amplitude
        for (i in 0 until bins) {
            if (counts[i] == 0) continue
            val r = (i + 0.5f) * BIN_WIDTH
            val v = sums[i] / counts[i]
            if (v <= half) {
                val t = (prevV - half) / (prevV - v)
                return 2f * (prevR + t * (r - prevR))
            }
            prevR = r
            prevV = v
        }
        return null
    }

    private fun sq(v: Int) = v * v
    private fun sq(v: Float) = v * v
}
