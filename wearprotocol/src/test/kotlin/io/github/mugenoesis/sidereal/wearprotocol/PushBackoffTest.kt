package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushBackoffTest {

    @Test
    fun `tries straight away at first`() {
        assertTrue(PushBackoff().shouldTry(0))
    }

    @Test
    fun `when the watch service is not available on this phone it stays quiet for a long while`() {
        val b = PushBackoff(unavailableRetryMs = 60_000)
        b.onUnavailable(1_000)
        assertFalse(b.shouldTry(30_000))
        assertTrue(b.shouldTry(61_000))
    }

    @Test
    fun `an ordinary failure retries sooner`() {
        val b = PushBackoff(failureRetryMs = 5_000, unavailableRetryMs = 60_000)
        b.onFailure(0)
        assertFalse(b.shouldTry(4_000))
        assertTrue(b.shouldTry(5_000))
    }

    @Test
    fun `success clears any backoff`() {
        val b = PushBackoff()
        b.onUnavailable(0)
        b.onSuccess()
        assertTrue(b.shouldTry(1))
    }

    @Test
    fun `it reports whether the unavailable state is new, so it is only logged once`() {
        val b = PushBackoff()
        assertTrue(b.onUnavailable(0))
        assertFalse(b.onUnavailable(70_000))
        b.onSuccess()
        assertTrue(b.onUnavailable(80_000))
    }
}
