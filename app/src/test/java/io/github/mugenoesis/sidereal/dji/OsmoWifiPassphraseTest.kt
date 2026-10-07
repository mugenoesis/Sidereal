package io.github.mugenoesis.sidereal.dji

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmoWifiPassphraseTest {

    @Test
    fun `the Osmo's default password is acceptable`() {
        assertNull(OsmoWifiPassphrase.validate(OsmoWifiPassphrase.DEFAULT))
    }

    @Test
    fun `WPA2 passwords are 8 to 63 characters`() {
        assertNull(OsmoWifiPassphrase.validate("a".repeat(8)))
        assertNull(OsmoWifiPassphrase.validate("a".repeat(63)))
        assertNotNull(OsmoWifiPassphrase.validate("a".repeat(7)))
        assertNotNull(OsmoWifiPassphrase.validate("a".repeat(64)))
    }

    @Test
    fun `an empty password is rejected with a reason`() {
        val message = OsmoWifiPassphrase.validate("")!!
        assertTrue(message, message.contains("8"))
    }

    @Test
    fun `surrounding spaces do not count, but inner ones do`() {
        assertNotNull(OsmoWifiPassphrase.validate("   short  "))
        assertNull(OsmoWifiPassphrase.validate("  my osmo pass  "))
        assertTrue(OsmoWifiPassphrase.clean("  my osmo pass  ") == "my osmo pass")
    }

    @Test
    fun `characters outside printable ASCII are rejected`() {
        assertNotNull(OsmoWifiPassphrase.validate("pässwörd123"))
    }
}
