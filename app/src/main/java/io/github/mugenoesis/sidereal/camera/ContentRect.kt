package io.github.mugenoesis.sidereal.camera

data class FloatRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where the camera's picture actually sits inside the preview view. The
 * Osmo's stream is 16:9 but a 4:3 photo setting shows a pillarboxed 4:3
 * picture inside it (confirmed on the test phone: view 1637x921, picture
 * ~1228x921), so anything drawn over the picture - the composition grid -
 * has to use the picture's rectangle, not the view's.
 */
object ContentRect {

    private const val DEFAULT_ASPECT = 16f / 9f

    /** The largest rectangle of [aspect] (width/height) that fits centred inside a [viewW] x [viewH] view. */
    fun fit(viewW: Float, viewH: Float, aspect: Float): FloatRect {
        val viewAspect = viewW / viewH
        return if (aspect >= viewAspect) {
            val h = viewW / aspect
            FloatRect(0f, (viewH - h) / 2f, viewW, h)
        } else {
            val w = viewH * aspect
            FloatRect((viewW - w) / 2f, 0f, w, viewH)
        }
    }

    fun aspectOf(photoRatioName: String?): Float = when (photoRatioName) {
        "RATIO_4_3" -> 4f / 3f
        "RATIO_3_2" -> 3f / 2f
        "RATIO_16_9" -> 16f / 9f
        else -> DEFAULT_ASPECT
    }

    /** Video frames fill the 16:9 stream; photo mode follows the photo aspect ratio setting. */
    fun aspectFor(isVideoMode: Boolean, photoRatioName: String?): Float =
        if (isVideoMode) DEFAULT_ASPECT else aspectOf(photoRatioName)
}

fun GridLine.offset(dx: Float, dy: Float) = GridLine(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
