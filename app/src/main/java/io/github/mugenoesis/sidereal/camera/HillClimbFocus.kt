package io.github.mugenoesis.sidereal.camera

/**
 * The sharpness metric behind the software autofocus (see FocusSearch for the search itself), kept pure so it
 * is unit-testable without a Bitmap or a live camera.
 */
object HillClimbFocus {

    /**
     * Variance of Laplacian over a square ARGB pixel buffer - a standard,
     * cheap focus metric: higher means sharper. [pixels] must have exactly
     * size*size elements (as returned by Bitmap.getPixels() for a size x
     * size region).
     */
    fun laplacianVariance(pixels: IntArray, size: Int): Double {
        require(pixels.size == size * size) { "expected ${size * size} pixels, got ${pixels.size}" }
        val gray = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val idx = y * size + x
                val lap = -4 * gray[idx] + gray[idx - 1] + gray[idx + 1] + gray[idx - size] + gray[idx + size]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    /**
     * [laplacianVariance] divided by the picture's mean brightness squared (scaled so a mid-grey picture is unchanged).
     * Lamps and screens flicker, which brightens and darkens the whole preview from frame to frame and moves the plain
     * measure by the square of that; the ratio does not move. Measured on the real camera: this is the difference
     * between a usable and an unusable measurement under LED light with a slow shutter.
     */
    fun normalizedLaplacianVariance(pixels: IntArray, size: Int): Double {
        val variance = laplacianVariance(pixels, size)
        var sum = 0.0
        for (p in pixels) sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        val mean = sum / pixels.size
        if (mean < 1.0) return 0.0
        return variance * (MID_GREY * MID_GREY) / (mean * mean)
    }

    private const val MID_GREY = 128.0
}
