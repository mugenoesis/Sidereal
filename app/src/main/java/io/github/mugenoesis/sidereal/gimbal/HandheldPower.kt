package io.github.mugenoesis.sidereal.gimbal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HandheldPowerState {
    ON, SLEEPING, OFF, UNKNOWN;

    companion object {
        fun of(name: String?): HandheldPowerState = values().firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * The Osmo handle's power mode. In SLEEPING the gimbal's motors are off (the camera hangs limp and drops under its own
 * weight) and the camera stops taking pictures, though the WiFi link stays up; the handle goes there on its own and
 * when its button is pressed. Setting ON from the phone wakes it: the motors take hold within about two seconds and the
 * gimbal comes up centred, not where it was, so whoever relies on a pose has to re-aim afterwards.
 *
 * @param send asks the handle for a power mode and calls back with an error text, or null when accepted
 * @param settleAfterWakeMs how long the gimbal gets to take hold and centre before the caller carries on
 */
class HandheldPower(
    private val send: (HandheldPowerState, (String?) -> Unit) -> Unit,
    private val pollMs: Long = 100,
    private val settleAfterWakeMs: Long = 2_500,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var wokenAtMs: Long? = null

    private val _state = MutableStateFlow(HandheldPowerState.UNKNOWN)
    val state: StateFlow<HandheldPowerState> = _state

    /** Called whenever the handle goes from not-awake to awake, whoever woke it. */
    var onWoken: (() -> Unit)? = null

    /** The handle's own report of its power mode ("ON", "SLEEPING"...). */
    fun onPush(name: String?) {
        val next = HandheldPowerState.of(name)
        val before = _state.value
        _state.value = next
        if (next == HandheldPowerState.ON && (before == HandheldPowerState.SLEEPING || before == HandheldPowerState.OFF)) {
            wokenAtMs = clock()
            onWoken?.invoke()
        }
    }

    /**
     * True within [windowMs] of the handle waking. The camera wakes with it but takes longer: measured on the real rig it
     * refuses shots ("Invalid key for component") until about 7 s after the wake command.
     */
    fun recentlyWoken(windowMs: Long): Boolean = wokenAtMs?.let { clock() - it < windowMs } ?: false

    /** True when the handle is awake or never said otherwise. */
    val isAwake: Boolean get() = _state.value == HandheldPowerState.ON || _state.value == HandheldPowerState.UNKNOWN

    /**
     * Wakes the handle if it is not awake and waits up to [timeoutMs] for it to say so, asking a second time half way if
     * it has not. Returns true when it is awake (after the gimbal has had [settleAfterWakeMs] to take hold).
     */
    suspend fun ensureAwake(timeoutMs: Long = 8_000): Boolean {
        if (isAwake) return true
        var waited = 0L
        var asked = 0
        while (waited <= timeoutMs) {
            if (asked == 0 || (asked == 1 && waited >= timeoutMs / 2)) {
                asked++
                var error: String? = null
                send(HandheldPowerState.ON) { error = it }
                if (error != null && asked >= 2) return false
                if (error != null) { delay(pollMs); waited += pollMs; continue }
            }
            if (_state.value == HandheldPowerState.ON) {
                if (settleAfterWakeMs > 0) delay(settleAfterWakeMs)
                return true
            }
            delay(pollMs)
            waited += pollMs
        }
        return false
    }
}
