package io.github.mugenoesis.sidereal.sync

import kotlin.math.abs

/** Guesses which audio take belongs with a video, by how close their start times are. */
object TakeMatcher {

    private const val DEFAULT_TOLERANCE_MS = 60_000L

    /**
     * The camera's own recorded start is the better yardstick when a take has one; otherwise the phone's audio start.
     * Null if nothing starts within [toleranceMs] of the video. Ties go to the earlier take.
     */
    fun best(videoStartEpochMs: Long, takes: List<SyncSidecar>, toleranceMs: Long = DEFAULT_TOLERANCE_MS): SyncSidecar? =
        takes
            .map { it to abs((it.cameraStartEpochMs ?: it.audioStartEpochMs) - videoStartEpochMs) }
            .filter { (_, distance) -> distance <= toleranceMs }
            .minWithOrNull(compareBy({ it.second }, { it.first.audioStartEpochMs }))
            ?.first
}
