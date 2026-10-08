package io.github.mugenoesis.sidereal.dji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationTextTest {

    private val firstTime = "For first time registration, app should be connected to Internet."

    @Test
    fun `the first-time-registration error is recognised`() {
        assertTrue(RegistrationText.needsInternet(firstTime))
        assertTrue(RegistrationText.needsInternet("Server is unreachable. Check internet connection"))
        assertFalse(RegistrationText.needsInternet("Invalid app key"))
        assertFalse(RegistrationText.needsInternet(""))
    }

    @Test
    fun `it explains what to do instead of quoting the SDK`() {
        val text = RegistrationText.describe(firstTime)
        assertTrue(text, text.contains("internet", ignoreCase = true))
        assertTrue(text, text.contains("once", ignoreCase = true))
        assertTrue("says to use the normal WiFi or mobile data first: $text", text.contains("mobile data", ignoreCase = true))
        assertFalse("not the raw SDK wording: $text", text.contains("app should be connected"))
    }

    @Test
    fun `any other error is shown as the SDK reported it`() {
        assertEquals("DJI SDK error: Invalid app key", RegistrationText.describe("Invalid app key"))
    }

    @Test
    fun `the metadata-from-server error is also an internet problem`() {
        // seen on a fresh install while joined to the Osmo's WiFi (no internet): the SDK words it differently
        val msg = "The metadata received from server is invalid, please reconnect to the server and try."
        assertTrue(RegistrationText.needsInternet(msg))
        assertTrue(RegistrationText.describe(msg).contains("internet", ignoreCase = true))
    }
}
