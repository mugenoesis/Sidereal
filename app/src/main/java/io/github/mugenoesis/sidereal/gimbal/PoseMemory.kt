package io.github.mugenoesis.sidereal.gimbal

/**
 * Remembers where the gimbal was pointing a little while ago. A handle that goes to sleep lets the camera sag at once, but
 * says so about a second later, so by the time the app knows, the newest readings are already the droop: the pose to put
 * back is the one from [lead] ms before the report.
 */
class PoseMemory(private val lead: Long = 2_000, private val keepMs: Long = 30_000) {

    data class Pose(val pitch: Float, val yaw: Float)

    private class Sample(val timeMs: Long, val pose: Pose)

    private val samples = ArrayDeque<Sample>()

    val size: Int get() = samples.size

    fun record(timeMs: Long, pitch: Float, yaw: Float) {
        samples.addLast(Sample(timeMs, Pose(pitch, yaw)))
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > keepMs) samples.removeFirst()
    }

    /** The newest pose recorded at least [lead] ms before [sleepReportedAtMs], else the oldest one held; null if none. */
    fun poseBefore(sleepReportedAtMs: Long): Pose? =
        samples.lastOrNull { it.timeMs <= sleepReportedAtMs - lead }?.pose ?: samples.firstOrNull()?.pose

    fun clear() = samples.clear()
}
