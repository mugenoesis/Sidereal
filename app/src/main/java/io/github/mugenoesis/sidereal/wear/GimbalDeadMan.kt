package io.github.mugenoesis.sidereal.wear

/**
 * The watch drives the gimbal with a rate that stays in force until a stop arrives, and the watch link is best-effort:
 * a lost "stop" (or a watch that goes to sleep or out of range mid-drag) would leave the gimbal turning for ever. While
 * a drag is in progress the watch sends an update every ~100 ms, so a turning gimbal that hears nothing for
 * [timeoutMs] is stopped.
 */
class GimbalDeadMan(private val timeoutMs: Long = 600) {
    private var lastAt = 0L
    private var turning = false

    /** Feed every gimbal command from the watch; a zero rate is an explicit stop. */
    fun onRate(nowMs: Long, yaw: Float, pitch: Float) {
        turning = yaw != 0f || pitch != 0f
        lastAt = nowMs
    }

    /** True once, when a non-zero rate has gone [timeoutMs] without any new command. */
    fun shouldStop(nowMs: Long): Boolean {
        if (!turning || nowMs - lastAt <= timeoutMs) return false
        turning = false
        return true
    }
}
