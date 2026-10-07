package io.github.mugenoesis.sidereal.focus

import kotlin.math.max
import kotlin.math.min

object LumaConversion {
    /** ARGB_8888 pixels (as from Bitmap.getPixels) to luminance, optionally taking every [step]th pixel to save time. */
    fun fromArgb(pixels: IntArray, width: Int, height: Int): LumaImage {
        val data = FloatArray(width * height)
        for (i in data.indices) {
            val p = pixels[i]
            data[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        return LumaImage(width, height, data)
    }
}

/**
 * Cheap first pass for focus assist: locate the brightest star in a whole
 * frame on a coarse grid, so the expensive FWHM measurement only has to run
 * on a small full-resolution [crop] around it.
 */
object StarFinder {

    private const val CELL = 4
    private const val SIGNAL_SIGMAS = 6f
    private const val MIN_SIGNAL = 12f

    /** Pixel position (x, y) of the brightest compact blob, or null if nothing stands out from the background. */
    fun brightest(image: LumaImage): Pair<Int, Int>? {
        val cols = image.width / CELL
        val rows = image.height / CELL
        if (cols < 3 || rows < 3) return null
        val cells = FloatArray(cols * rows)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            var sum = 0f
            for (y in 0 until CELL) for (x in 0 until CELL) sum += image[cx * CELL + x, cy * CELL + y]
            cells[cy * cols + cx] = sum / (CELL * CELL)
        }
        val sorted = cells.sortedArray()
        val median = sorted[sorted.size / 2]
        val mad = FloatArray(sorted.size) { Math.abs(sorted[it] - median) }.sortedArray()[sorted.size / 2]
        val threshold = max(SIGNAL_SIGMAS * mad * 1.4826f, MIN_SIGNAL)

        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in cells.indices) if (cells[i] > bestValue) { bestValue = cells[i]; best = i }
        if (bestValue - median < threshold * 0.5f) return null

        // Refine inside the winning cell neighbourhood to the brightest raw pixel - and reject a single hot pixel,
        // whose cell average barely rises above the background.
        val cx = best % cols
        val cy = best / cols
        var px = cx * CELL
        var py = cy * CELL
        var peak = Float.NEGATIVE_INFINITY
        for (y in max(0, cy * CELL - CELL)..min(image.height - 1, cy * CELL + 2 * CELL - 1)) {
            for (x in max(0, cx * CELL - CELL)..min(image.width - 1, cx * CELL + 2 * CELL - 1)) {
                if (image[x, y] > peak) { peak = image[x, y]; px = x; py = y }
            }
        }

        // A hot pixel has dark neighbours; a star's core is at least two pixels wide.
        val neighbours = listOf(
            image[max(0, px - 1), py], image[min(image.width - 1, px + 1), py],
            image[px, max(0, py - 1)], image[px, min(image.height - 1, py + 1)]
        ).average().toFloat()
        if (neighbours - median < 0.25f * (peak - median)) return null
        return px to py
    }

    /** A [size]x[size] window centred on (cx, cy), shifted to stay inside the image (or the whole image if it is smaller). */
    fun crop(image: LumaImage, cx: Int, cy: Int, size: Int): LumaImage {
        val w = min(size, image.width)
        val h = min(size, image.height)
        val x0 = (cx - w / 2).coerceIn(0, image.width - w)
        val y0 = (cy - h / 2).coerceIn(0, image.height - h)
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = image[x0 + x, y0 + y]
        return LumaImage(w, h, data)
    }
}
