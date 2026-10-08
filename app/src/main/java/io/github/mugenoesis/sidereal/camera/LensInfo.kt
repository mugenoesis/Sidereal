package io.github.mugenoesis.sidereal.camera

import kotlin.math.abs

/**
 * What the camera says about the lens on it ("DJI MFT 15mm F1.7 ASPH" on the rig this was built with), pulled out of the
 * name string. Interchangeable lenses on the X5 are Micro Four Thirds, so the name usually carries the focal length
 * (or range) and the widest aperture; a zoom does not say where it is currently set.
 */
data class LensInfo(
    val name: String?,
    val focalMinMm: Float?,
    val focalMaxMm: Float?,
    val maxApertureF: Float?
) {
    val isZoom: Boolean get() = focalMinMm != null && focalMaxMm != null && abs(focalMaxMm - focalMinMm) > 0.01f

    /** The focal length if the lens has just one. */
    val primeFocalMm: Float? get() = if (focalMinMm != null && focalMaxMm != null && !isZoom) focalMinMm else null

    companion object {
        private const val MIN_FOCAL = 2f
        private const val MAX_FOCAL = 1500f

        private val FOCAL = Regex("""(\d+(?:\.\d+)?)\s*(?:[-–—]\s*(\d+(?:\.\d+)?))?\s*mm""", RegexOption.IGNORE_CASE)
        private val APERTURE = Regex("""[Ff]/?\s*(\d+(?:\.\d+)?)(?:\s*[-–]\s*\d+(?:\.\d+)?)?""")

        fun parse(raw: String?): LensInfo {
            val name = raw?.trim()?.takeIf { it.isNotEmpty() }
            if (name == null) return LensInfo(null, null, null, null)
            val focal = FOCAL.findAll(name).mapNotNull { m ->
                val a = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                val b = m.groupValues[2].toFloatOrNull() ?: a
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                if (lo < MIN_FOCAL || hi > MAX_FOCAL) null else lo to hi
            }.firstOrNull()
            // The aperture comes after the focal length ("15mm F1.7"); look only there so "F" inside a name is not read as one.
            val after = focal?.let { name.substring(FOCAL.find(name)!!.range.last + 1) } ?: name
            val aperture = APERTURE.find(after)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it in 0.7f..64f }
            return LensInfo(name, focal?.first, focal?.second, aperture)
        }
    }
}

/** The focal length a picture says it was taken at (EXIF), and whether it differs from what was planned for. */
object FocalLength {
    private const val DISAGREE_FRACTION = 0.04f

    fun fromExif(raw: String?): Float? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = if ('/' in text) {
            val (n, d) = text.split('/', limit = 2).map { it.trim().toFloatOrNull() ?: return null }
            if (d == 0f) return null
            n / d
        } else text.toFloatOrNull() ?: return null
        return value.takeIf { it in 2f..1500f }
    }

    /** The focal length that gives horizontal field of view [hFovDeg] on a Micro Four Thirds sensor (17.3 mm wide). */
    fun fromHorizontalFov(hFovDeg: Float, sensorWidthMm: Float = 17.3f): Float =
        (sensorWidthMm / 2.0 / kotlin.math.tan(Math.toRadians(hFovDeg / 2.0))).toFloat()

    fun disagrees(planned: Float, actual: Float?): Boolean =
        actual != null && abs(actual - planned) / planned > DISAGREE_FRACTION
}
