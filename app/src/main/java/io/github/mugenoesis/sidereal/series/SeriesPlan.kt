package io.github.mugenoesis.sidereal.series

import io.github.mugenoesis.sidereal.sequence.Node
import io.github.mugenoesis.sidereal.sequence.SequenceMode

/** Where each panorama frame was pointed and how much it sees - everything the stitcher needs besides the pictures. */
data class PanoramaLayout(
    /** One per planned capture, in shooting order (stacked shots repeat their node). */
    val nodes: List<Node>,
    val shotsPerNode: Int,
    val hFovDeg: Float,
    val vFovDeg: Float
)

/**
 * What to do with a sequence's photos once the run is over, decided when the plan is built.
 *
 * @param tags one label per planned capture, in shooting order, used to name the downloaded files
 * @param keepFrames leave the individual frames in the series folder (otherwise they are temporary and removed
 *   once the video or panorama has been made)
 */
data class SeriesPlan(
    val mode: SequenceMode,
    val tags: List<String>,
    val keepFrames: Boolean,
    val stitch: Boolean = false,
    val makeVideo: Boolean = false,
    val fps: Int = 24,
    val panorama: PanoramaLayout? = null
) {
    /** Whether the photos have to come off the camera at all. */
    val needsDownload: Boolean get() = keepFrames || stitch || makeVideo
}
