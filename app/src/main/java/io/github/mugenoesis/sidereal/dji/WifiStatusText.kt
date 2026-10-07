package io.github.mugenoesis.sidereal.dji

object WifiStatusText {

    /**
     * Android withholds the network name unless location is allowed AND switched on, so a null name usually means "it
     * won't tell me", not "no WiFi" - saying "currently: no WiFi network" there looked like an error on a phone that
     * was happily on its home WiFi.
     */
    fun notOnOsmo(currentSsid: String?): String {
        val where = currentSsid?.takeIf { it.isNotBlank() }?.let { " (currently: $it)" }.orEmpty()
        return "Not on your Osmo's WiFi$where - tap to join it (hold to set its password)"
    }
}
