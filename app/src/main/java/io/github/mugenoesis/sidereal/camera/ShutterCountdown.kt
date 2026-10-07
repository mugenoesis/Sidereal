package io.github.mugenoesis.sidereal.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object TimerOptions {
    private val options = listOf(0, 2, 5, 10)

    fun next(current: Int): Int {
        val i = options.indexOf(current)
        return if (i < 0) 0 else options[(i + 1) % options.size]
    }

    fun label(seconds: Int): String = if (seconds == 0) "Off" else "${seconds}s"
}

/** Self-timer: counts down [start]'s seconds, calling [onTick] with each remaining second, then fires once. */
class ShutterCountdown(
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val _remaining = MutableStateFlow<Int?>(null)
    val remaining: StateFlow<Int?> = _remaining

    var onTick: ((Int) -> Unit)? = null

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(seconds: Int, onFire: () -> Unit) {
        cancel()
        job = scope.launch {
            try {
                for (left in seconds downTo 1) {
                    _remaining.value = left
                    onTick?.invoke(left)
                    sleep(1000)
                }
                _remaining.value = null
                onFire()
            } finally {
                _remaining.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remaining.value = null
    }
}
