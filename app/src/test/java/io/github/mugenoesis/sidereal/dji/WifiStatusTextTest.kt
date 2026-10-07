package io.github.mugenoesis.sidereal.dji

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStatusTextTest {

    @Test
    fun `it names the network the phone is on`() {
        val t = WifiStatusText.notOnOsmo("ThisLan_5G")
        assertTrue(t, t.contains("ThisLan_5G"))
        assertTrue(t, t.contains("tap to join", ignoreCase = true))
    }

    @Test
    fun `when Android will not say which network it is, it does not claim there is none`() {
        val t = WifiStatusText.notOnOsmo(null)
        assertFalse("must not say 'no WiFi network': $t", t.contains("no WiFi", ignoreCase = true))
        assertFalse(t, t.contains("currently"))
        assertTrue(t, t.contains("tap to join", ignoreCase = true))
    }

    @Test
    fun `an empty name is treated like an unknown one`() {
        assertFalse(WifiStatusText.notOnOsmo("").contains("currently"))
    }
}
