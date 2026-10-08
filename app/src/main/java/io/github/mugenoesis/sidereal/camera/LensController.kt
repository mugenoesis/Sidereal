package io.github.mugenoesis.sidereal.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Knows which lens is on the camera. The camera hands back its name ("DJI MFT 15mm F1.7 ASPH"); [LensInfo] reads the
 * focal length and aperture out of it. Several plans depend on the focal length (panorama overlap, dither size, the
 * stitcher's starting field of view), and the app used to assume 15 mm for all of them.
 *
 * @param read asks the camera for its lens name and calls back with it, or with null if it could not say
 */
class LensController(
    private val read: ((String?) -> Unit) -> Unit,
    /** Asks the camera for the focus ring's position and its upper limit; either may come back null. */
    private val readRing: ((Int?, Int?) -> Unit) -> Unit = { callback -> callback(null, null) }
) {

    private val _info = MutableStateFlow<LensInfo?>(null)
    val info: StateFlow<LensInfo?> = _info

    private var answered = false
    private var fromPhoto: LensInfo? = null
    private var zoomRingMax: Int? = null
    private var zoomMoved = false

    private val _zoomMm = MutableStateFlow<Float?>(null)
    private val _ringUpperBound = MutableStateFlow<Int?>(null)

    /** The focus ring's upper limit as last read. It changes with a zoom's position, so it is read again, not kept for the session. */
    val ringUpperBound: StateFlow<Int?> = _ringUpperBound

    /** Where the user says the zoom is set. The camera cannot tell (EXIF says 12 mm whatever the zoom), so it is asked. */
    val zoomMm: StateFlow<Float?> = _zoomMm
    private var ring: Int? = null
    private var ringMax: Int? = null

    private val _line = MutableStateFlow(LensDisplay.describe(LensReading(null, false, null, null)))

    /** The one-line description for the screen: checking, the lens, unknown, or "not extended". */
    val line: StateFlow<LensLine> = _line

    /** Asks again; an empty answer keeps whatever was known, since the camera is sometimes slow to answer after a bind. */
    fun refresh() {
        read { name ->
            if (!name.isNullOrBlank()) {
                val parsed = LensInfo.parse(name)
                if (parsed.name != _info.value?.name) forgetZoom()
                // The camera says "Unknown" for every third-party lens, so it must not undo a name read from a photo.
                if (parsed.isUnidentified && fromPhoto != null) _info.value = fromPhoto
                else { _info.value = parsed; fromPhoto = null }
            }
            answered = true
            publish()
        }
    }

    /** The camera cannot name this lens but a photo taken with it does (its EXIF lens model); trusted until the camera names one. */
    fun identifyFromPhoto(lensModel: String) {
        val parsed = LensInfo.parse(lensModel)
        if (parsed.name != _info.value?.name) forgetZoom()
        fromPhoto = parsed
        _info.value = parsed
        answered = true
        publish()
    }

    /** Records where the zoom is set ([mm], within the lens' range) or, with null, forgets it. */
    fun setZoomMm(mm: Float?) {
        val lens = _info.value
        if (mm == null || lens == null || !lens.isZoom || mm !in lens.focalMinMm!!..lens.focalMaxMm!!) {
            if (mm == null) forgetZoom()
            return
        }
        _zoomMm.value = mm
        zoomRingMax = ringMax
        zoomMoved = false
        publish()
    }

    /** The focal length to plan with: the lens' own if it is a prime, else where the user said the zoom is. */
    fun effectiveFocalMm(): Float? = _info.value?.primeFocalMm ?: _zoomMm.value

    private fun forgetZoom() {
        _zoomMm.value = null
        zoomRingMax = null
        zoomMoved = false
    }

    /** Re-reads the ring: its position is the only thing that tells a stowed collapsible zoom from an extended one. */
    fun refreshRing() {
        readRing { value, max ->
            ring = value
            if (max != null) { ringMax = max; _ringUpperBound.value = max }
            checkZoomMoved()
            publish()
        }
    }

    /** The ring's upper limit grows with the zoom (1570 at 12 mm, 3837 at 32 mm), so a change means the zoom was moved. */
    private fun checkZoomMoved() {
        val was = zoomRingMax ?: return
        val now = ringMax ?: return
        if (_zoomMm.value != null && kotlin.math.abs(now - was) > maxOf(RING_MOVE_MIN, (was * RING_MOVE_FRACTION).toInt())) {
            _zoomMm.value = null
            zoomRingMax = null
            zoomMoved = true
        }
    }

    private fun publish() {
        // A stowed lens is shown as soon as the ring says so, even if the camera has not been asked its name yet.
        val showAnswered = answered || LensDisplay.isStowed(ring, ringMax)
        _line.value = LensDisplay.describe(LensReading(_info.value, showAnswered, ring, ringMax, _zoomMm.value, zoomMoved))
    }

    private companion object {
        const val RING_MOVE_FRACTION = 0.05
        const val RING_MOVE_MIN = 40
    }
}
