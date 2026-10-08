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
class LensController(private val read: ((String?) -> Unit) -> Unit) {

    private val _info = MutableStateFlow<LensInfo?>(null)
    val info: StateFlow<LensInfo?> = _info

    /** Asks again; an empty answer keeps whatever was known, since the camera is sometimes slow to answer after a bind. */
    fun refresh() {
        read { name ->
            if (!name.isNullOrBlank()) _info.value = LensInfo.parse(name)
        }
    }
}
