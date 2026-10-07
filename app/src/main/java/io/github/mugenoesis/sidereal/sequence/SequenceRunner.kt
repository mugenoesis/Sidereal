package io.github.mugenoesis.sidereal.sequence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the runner needs from the outside world - the real one talks to the DJI SDK, tests use a virtual-clock fake. */
interface SequenceHost {
    fun nowMs(): Long
    suspend fun sleep(ms: Long)
    suspend fun moveTo(pitch: Float, yaw: Float)

    /** Returns true once the camera has accepted the shot and finished it, false if it failed. */
    suspend fun capture(exposureMs: Long, label: String): Boolean
    suspend fun setShutter(shutterName: String): Boolean
    suspend fun awaitUserContinue(message: String)

    /** False while the link to the camera is down (WiFi dropped, camera asleep), so a failed shot can wait instead of aborting. */
    fun isCameraReachable(): Boolean = true
}

sealed class SequenceState {
    object Idle : SequenceState()
    object Running : SequenceState()
    data class AwaitingUser(val message: String) : SequenceState()
    object Done : SequenceState()
    data class Failed(val reason: String) : SequenceState()
    object Cancelled : SequenceState()
}

data class SequenceProgress(
    val state: SequenceState = SequenceState.Idle,
    val capturesDone: Int = 0,
    val capturesTotal: Int = 0,
    val retries: Int = 0,
    /** True while a shot is waiting for the camera link to come back. */
    val waitingForCamera: Boolean = false,
    val currentLabel: String = ""
)

/**
 * Executes a list of [SequenceStep]s against a [SequenceHost]. A capture
 * that fails is retried [maxRetries] times (after [retryDelayMs]) before
 * the whole sequence aborts - a single dropped frame in a 500-frame
 * timelapse shouldn't end the night, but a camera that has stopped
 * responding shouldn't be hammered forever either.
 *
 * A failure while the camera link is down is different: that is not the
 * camera refusing, it is the WiFi dropping, so the runner waits (up to
 * [linkPatienceMs] per outage) for the link and then retries the same frame
 * without spending a retry.
 */
class SequenceRunner(
    private val host: SequenceHost,
    private val maxRetries: Int = 2,
    private val retryDelayMs: Long = 1_000,
    private val linkPatienceMs: Long = 10 * 60_000,
    private val linkPollMs: Long = 2_000
) {
    private val _progress = MutableStateFlow(SequenceProgress())
    val progress: StateFlow<SequenceProgress> = _progress

    /** Called on every progress change, from the coroutine running the sequence. */
    var onProgress: ((SequenceProgress) -> Unit)? = null

    private fun update(transform: (SequenceProgress) -> SequenceProgress) {
        val next = transform(_progress.value)
        _progress.value = next
        onProgress?.invoke(next)
    }

    suspend fun run(steps: List<SequenceStep>) {
        val startMs = host.nowMs()
        update { SequenceProgress(SequenceState.Running, capturesTotal = steps.count { it is SequenceStep.Capture }) }
        try {
            for (step in steps) {
                val failure = execute(step, startMs)
                if (failure != null) {
                    update { it.copy(state = SequenceState.Failed(failure)) }
                    return
                }
            }
            update { it.copy(state = SequenceState.Done) }
        } catch (e: CancellationException) {
            update { it.copy(state = SequenceState.Cancelled) }
            throw e
        }
    }

    /** Returns null on success or a human-readable reason the sequence must abort. */
    private suspend fun execute(step: SequenceStep, startMs: Long): String? {
        when (step) {
            is SequenceStep.MoveTo -> host.moveTo(step.pitch, step.yaw)
            is SequenceStep.Settle -> if (step.ms > 0) host.sleep(step.ms)
            is SequenceStep.WaitUntil -> {
                val wait = step.offsetMs - (host.nowMs() - startMs)
                if (wait > 0) host.sleep(wait)
            }
            is SequenceStep.Prompt -> {
                update { it.copy(state = SequenceState.AwaitingUser(step.message)) }
                host.awaitUserContinue(step.message)
                update { it.copy(state = SequenceState.Running) }
            }
            is SequenceStep.SetShutter -> {
                if (!host.setShutter(step.shutterName)) return "Camera rejected shutter speed ${step.shutterName}"
            }
            is SequenceStep.Capture -> return capture(step)
        }
        return null
    }

    private suspend fun capture(step: SequenceStep.Capture): String? {
        update { it.copy(currentLabel = step.label) }
        var attempt = 0
        while (true) {
            if (host.capture(step.exposureMs, step.label)) {
                update { it.copy(capturesDone = it.capturesDone + 1) }
                return null
            }
            if (!host.isCameraReachable()) {
                if (!waitForCamera()) return "Lost the camera for ${linkPatienceMs / 60_000} minutes - sequence stopped"
                continue
            }
            if (attempt >= maxRetries) return "Capture failed ${attempt + 1} times in a row"
            attempt++
            update { it.copy(retries = it.retries + 1) }
            host.sleep(retryDelayMs)
        }
    }

    /** Polls until the link is back (true) or the patience runs out (false). */
    private suspend fun waitForCamera(): Boolean {
        update { it.copy(waitingForCamera = true) }
        val started = host.nowMs()
        try {
            while (!host.isCameraReachable()) {
                if (host.nowMs() - started >= linkPatienceMs) return false
                host.sleep(linkPollMs)
            }
            return true
        } finally {
            update { it.copy(waitingForCamera = false) }
        }
    }
}
