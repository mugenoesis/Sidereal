package io.github.mugenoesis.sidereal.camera

/** What the app knows about the lens at this moment, in the form [LensDisplay] turns into the line on screen. */
data class LensReading(
    /** The parsed lens name, or null if the camera has not been asked yet / did not answer. */
    val info: LensInfo?,
    /** False until the camera has answered at least once (the "Checking lens..." state). */
    val answered: Boolean,
    /** The focus ring's current position and its upper limit, if read. */
    val ring: Int?,
    val ringMax: Int?
)

data class LensLine(val text: String, val kind: Kind) {
    enum class Kind { CHECKING, KNOWN, UNKNOWN, NOT_EXTENDED }
}

/**
 * The one-line lens description shown on screen: the lens' focal length (or range, for a zoom) and aperture, or why it
 * cannot say. The camera tells a stowed collapsible zoom from an extended one in only one way: the focus ring's position
 * reads as nonsense (-26270 against a range of 0-1570 on the real Panasonic 12-32 when stowed), with no error flag set.
 */
object LensDisplay {
    private const val RING_SLACK_FRACTION = 0.10
    private const val RING_SLACK_MIN = 100

    fun describe(r: LensReading): LensLine {
        if (!r.answered) return LensLine("Checking lens...", LensLine.Kind.CHECKING)
        if (isStowed(r.ring, r.ringMax)) return LensLine("Lens not extended - rotate the zoom ring", LensLine.Kind.NOT_EXTENDED)
        val info = r.info
        if (info == null || info.isUnidentified) return LensLine("Lens unknown - tap to identify", LensLine.Kind.UNKNOWN)
        val focal = focalText(info) ?: return LensLine(info.name!!, LensLine.Kind.KNOWN)
        val aperture = apertureText(info)
        return LensLine(if (aperture == null) focal else "$focal $aperture", LensLine.Kind.KNOWN)
    }

    /** The ring reads far outside its own range. A reading a step beyond the limit (it lags a zoom move) is normal. */
    fun isStowed(ring: Int?, ringMax: Int?): Boolean {
        if (ring == null || ringMax == null || ringMax <= 0) return false
        val slack = maxOf(RING_SLACK_MIN, (ringMax * RING_SLACK_FRACTION).toInt())
        return ring < -slack || ring > ringMax * 2 + slack
    }

    private fun focalText(i: LensInfo): String? {
        val lo = i.focalMinMm ?: return null
        val hi = i.focalMaxMm ?: lo
        return if (i.isZoom) "${mm(lo)}-${mm(hi)} mm" else "${mm(lo)} mm"
    }

    private fun apertureText(i: LensInfo): String? {
        val a = i.maxApertureF ?: return null
        val b = i.apertureAtLongEndF
        return if (b != null) "f/${f(a)}-${f(b)}" else "f/${f(a)}"
    }

    private fun mm(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    private fun f(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}
