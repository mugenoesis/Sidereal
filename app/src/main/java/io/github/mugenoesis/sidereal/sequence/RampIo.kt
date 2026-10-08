package io.github.mugenoesis.sidereal.sequence

/**
 * What the real host needs from the rest of the app to ramp the exposure: the camera's own shutter/ISO ranges, its
 * current readout, the preview's brightness, and a switch for the histogram stream that provides it.
 */
class RampIo(
    val shutterOptions: () -> List<ShutterOption>,
    /**
     * Asks the camera for its shutter and ISO ranges again. The shutter range can only be read in Manual mode (in
     * Program it answers "unsupported"), so an app launched with the camera in Program has no range until the
     * ramp has switched the camera to Manual.
     */
    val refreshRanges: () -> Unit = {},
    /** The lens' current aperture enum name, if the camera reports one. */
    val currentAperture: () -> String? = { null },
    /** The aperture enum names the app knows, in any order. */
    val apertureNames: () -> List<String> = { emptyList() },
    /** Sets the lens aperture; calls back with null on success or the reason it failed. */
    val setAperture: (String, (String?) -> Unit) -> Unit = { _, done -> done("no aperture control") },
    val isoOptions: () -> List<IsoOption>,
    /** The camera's current (shutter enum name, ISO enum name), or null if not read yet. */
    val currentNames: () -> Pair<String, String>?,
    /** Mean luma 0..255 of the live preview, or null if the histogram is not flowing. */
    val meanLuma: () -> Double?,
    val setMetering: (Boolean) -> Unit
)
