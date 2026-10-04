package io.github.mugenoesis.sidereal.camera

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Collects every value [flow] emits while [action] runs, for testing the
 * controllers' one-shot errorEvents SharedFlows (MutableSharedFlow(replay =
 * 0, extraBufferCapacity = 1) - see FocusController.errorEvents' doc
 * comment for why they're SharedFlow, not StateFlow).
 *
 * Exists because of a real hang found while writing these tests:
 * `action(); flow.first()` (call the emitting function, THEN await the
 * next value) deadlocks forever. replay = 0 means a flow.first() collector
 * that subscribes AFTER the emission already happened never sees it - the
 * extraBufferCapacity buffers for an already-subscribed-but-slow
 * collector, not a not-yet-subscribed one. The collector has to be
 * subscribed (and actually running - a launched coroutine doesn't start
 * until something suspends, hence the yield()) before [action] runs.
 */
suspend fun <T> awaitEvents(flow: SharedFlow<T>, action: () -> Unit): List<T> = coroutineScope {
    val events = mutableListOf<T>()
    val job = launch { flow.collect { events.add(it) } }
    yield() // let the collector actually start running (subscribe) before we emit
    action()
    yield() // let the collector pick up what was just emitted
    job.cancel()
    events
}
