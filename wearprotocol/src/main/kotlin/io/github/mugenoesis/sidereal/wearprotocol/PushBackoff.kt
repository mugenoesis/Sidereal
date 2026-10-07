package io.github.mugenoesis.sidereal.wearprotocol

/**
 * Stops the phone from hammering a status push that cannot work. A phone with no Wear OS service at all
 * ("Wearable API unavailable") would otherwise fail every 1.5 s forever, spamming the log and wasting work;
 * it now waits a minute between tries. A transient failure retries after a few seconds. Success resets it.
 */
class PushBackoff(
    private val failureRetryMs: Long = 5_000,
    private val unavailableRetryMs: Long = 60_000
) {
    private var notBefore = 0L
    private var unavailable = false

    fun shouldTry(nowMs: Long): Boolean = nowMs >= notBefore

    /** Returns true the first time unavailability is seen (so the caller can log it once). */
    fun onUnavailable(nowMs: Long): Boolean {
        notBefore = nowMs + unavailableRetryMs
        val isNew = !unavailable
        unavailable = true
        return isNew
    }

    fun onFailure(nowMs: Long) {
        notBefore = nowMs + failureRetryMs
    }

    fun onSuccess() {
        notBefore = 0L
        unavailable = false
    }
}
