package io.github.mugenoesis.sidereal.sequence

/**
 * What the real host needs from the rest of the app to ramp the exposure: the camera's own shutter/ISO ranges, its
 * current readout, the preview's brightness, and a switch for the histogram stream that provides it.
 */
class RampIo(
    val shutterOptions: () -> List<ShutterOption>,
    val isoOptions: () -> List<IsoOption>,
    /** The camera's current (shutter enum name, ISO enum name), or null if not read yet. */
    val currentNames: () -> Pair<String, String>?,
    /** Mean luma 0..255 of the live preview, or null if the histogram is not flowing. */
    val meanLuma: () -> Double?,
    val setMetering: (Boolean) -> Unit
)
