package io.github.mugenoesis.sidereal.sequence

import io.github.mugenoesis.sidereal.series.AfterRunProgress
import io.github.mugenoesis.sidereal.series.PostRun
import io.github.mugenoesis.sidereal.series.RunSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns one shooting sequence's lifecycle for the UI: the dialled-in
 * [settings], starting/stopping a run, the "cap the lens" style prompts
 * that pause it, and the progress/message state the tray renders.
 *
 * @param hostFactory builds the [SequenceHost]; it is handed the prompt hook the host must call from
 *   `awaitUserContinue` so the controller can surface the text and wait for [continueFromPrompt]
 * @param contextProvider live camera/gimbal facts, or null when the Osmo isn't ready
 * @param prepare runs once before the first step (e.g. switch the camera to photo mode)
 * @param precondition returns a reason the sequence must not start right now (e.g. "stop recording first"), or null
 * @param postRun brings the photos in and stitches / encodes them after a run that finished; the sequence stays
 *   "running" (shutter locked) until it is done, because the camera is in playback mode meanwhile
 */
class SequenceController(
    private val scope: CoroutineScope,
    private val hostFactory: (onPrompt: suspend (String) -> Unit) -> SequenceHost,
    private val contextProvider: () -> ShootContext?,
    private val prepare: suspend () -> Unit = {},
    private val precondition: () -> String? = { null },
    private val postRun: PostRun? = null,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private val _settings = MutableStateFlow(SequenceSettings())
    val settings: StateFlow<SequenceSettings> = _settings

    private val _progress = MutableStateFlow(SequenceProgress())
    val progress: StateFlow<SequenceProgress> = _progress

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** Text of the prompt currently waiting on the user, or null. */
    private val _prompt = MutableStateFlow<String?>(null)
    val prompt: StateFlow<String?> = _prompt

    /** Why the last start didn't happen or the last run failed; cleared by the next start. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Set while the photos are being downloaded / stitched / encoded after a run. */
    private val _afterRun = MutableStateFlow<AfterRunProgress?>(null)
    val afterRun: StateFlow<AfterRunProgress?> = _afterRun

    private var job: Job? = null
    private var promptGate: CompletableDeferred<Unit>? = null

    fun setMode(mode: SequenceMode) {
        if (_isRunning.value) return
        _settings.value = _settings.value.copy(mode = mode)
    }

    fun stepMode(direction: Int) = setMode(_settings.value.mode.step(direction))

    fun adjust(fieldId: String, direction: Int) {
        if (_isRunning.value) return
        _settings.value = _settings.value.adjust(fieldId, direction)
    }

    /** What Start would do right now - used for the summary line under the fields. */
    fun preview(): PlanResult {
        val context = contextProvider() ?: return PlanResult.Error(NOT_CONNECTED)
        return SequencePlanFactory.build(_settings.value, context)
    }

    fun start() {
        if (_isRunning.value) return
        _message.value = null
        precondition()?.let { _message.value = it; return }
        val context = contextProvider() ?: run { _message.value = NOT_CONNECTED; return }
        val plan = when (val result = SequencePlanFactory.build(_settings.value, context)) {
            is PlanResult.Error -> { _message.value = result.message; return }
            is PlanResult.Ok -> result.plan
        }

        val series = plan.series
        val startedAtMs = wallClock()
        val host = hostFactory { text -> awaitPrompt(text) }
        val runner = SequenceRunner(host)
        runner.onProgress = { _progress.value = it }
        _isRunning.value = true
        _progress.value = SequenceProgress(capturesTotal = plan.captures)
        job = scope.launch {
            try {
                prepare()
                runner.run(plan.steps)
                val finished = runner.progress.value
                (finished.state as? SequenceState.Failed)?.let { _message.value = it.reason }
                val post = postRun
                if (post != null && series.needsDownload && finished.state is SequenceState.Done && finished.capturesDone > 0) {
                    val summary = RunSummary(startedAtMs, wallClock() - startedAtMs, finished.capturesDone, plan.captures)
                    try {
                        _afterRun.value = AfterRunProgress("Preparing", 0, 0)
                        _message.value = post.run(series, summary) { _afterRun.value = it }
                    } finally {
                        _afterRun.value = null
                    }
                }
            } finally {
                (host as? AutoCloseable)?.close()
                _prompt.value = null
                promptGate = null
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun continueFromPrompt() {
        promptGate?.complete(Unit)
    }

    private suspend fun awaitPrompt(text: String) {
        val gate = CompletableDeferred<Unit>()
        promptGate = gate
        _prompt.value = text
        try {
            gate.await()
        } finally {
            _prompt.value = null
        }
    }

    private companion object {
        const val NOT_CONNECTED = "Osmo not connected"
    }
}
