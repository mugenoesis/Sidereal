package io.github.mugenoesis.sidereal.wearprotocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/** What the watch shows and sends for each phone state - pure, so every wording and threshold is unit-tested. */
object WatchDisplay {

    /** The phone pushes status every ~1.5 s; this long without one means its app isn't open or the link is down. */
    const val STALE_MS = 6_000L

    private const val DRAG_DEADZONE = 0.15f

    private fun usable(status: WearStatus?, ageMs: Long): WearStatus? = status?.takeIf { ageMs <= STALE_MS }

    fun headline(status: WearStatus?, ageMs: Long): String {
        val s = usable(status, ageMs) ?: return "Open Sidereal on your phone"
        return when {
            !s.phoneOnOsmo -> "Phone isn't on the Osmo's WiFi"
            s.sequenceRunning -> s.sequenceLabel
            s.recording -> "REC " + clock(s.recordElapsedSec)
            s.cameraMode == WearCameraMode.VIDEO ->
                if (s.recordSecondsLeft >= 0) timeLeft(s.recordSecondsLeft) + " left" else "Ready"
            s.photosLeft >= 0 -> photoCount(s.photosLeft) + " photos left"
            else -> "Ready"
        }
    }

    fun battery(status: WearStatus?): String =
        if (status == null || status.batteryPercent < 0) "--" else "${status.batteryPercent}%"

    fun canShoot(status: WearStatus?, ageMs: Long): Boolean =
        usable(status, ageMs)?.let { it.phoneOnOsmo && !it.sequenceRunning } ?: false

    /** Watching the live view is harmless while a sequence runs - it is how you keep an eye on one. */
    fun canWatchLive(status: WearStatus?, ageMs: Long): Boolean = usable(status, ageMs)?.phoneOnOsmo ?: false

    fun shutterLabel(status: WearStatus?): String = when {
        status?.cameraMode == WearCameraMode.VIDEO -> if (status.recording) "Stop" else "Rec"
        else -> "Photo"
    }

    fun shutterCommand(status: WearStatus?): WearCommand =
        if (status?.cameraMode == WearCameraMode.VIDEO) WearCommand.ToggleRecord else WearCommand.Capture

    /**
     * A finger drag from where it landed ([dxPx], [dyPx]; screen y grows downward) to a gimbal rate: dragging up
     * tilts up, dragging right pans right, a full [radiusPx] is full speed, and a small wobble does nothing.
     */
    fun dragToGimbal(dxPx: Float, dyPx: Float, radiusPx: Float): WearCommand.Gimbal {
        val nx = (dxPx / radiusPx).coerceIn(-1f, 1f)
        val ny = (-dyPx / radiusPx).coerceIn(-1f, 1f)
        if (hypot(nx, ny) < DRAG_DEADZONE) return WearCommand.Gimbal(0f, 0f)
        return WearCommand.Gimbal(shape(nx) + 0f, shape(ny) + 0f)
    }

    /** Text to flash for an ack, or null when a plain success needs no comment. */
    fun ackText(ack: WearAck): String? = when {
        ack.message.isNotBlank() -> ack.message
        ack.ok -> null
        else -> "Failed"
    }

    private fun shape(v: Float): Float {
        val m = abs(v)
        if (m <= DRAG_DEADZONE) return 0f
        return sign(v) * ((m - DRAG_DEADZONE) / (1f - DRAG_DEADZONE)).pow(2)
    }

    private fun clock(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun timeLeft(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    private fun photoCount(count: Int): String = when {
        count >= 100_000 -> "${count / 1000}k"
        count >= 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
        else -> String.format(Locale.US, "%,d", count)
    }
}
