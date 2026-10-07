package io.github.mugenoesis.sidereal.dji

/** The Osmo's WiFi password: WPA2, so 8-63 printable ASCII characters. The factory default is `12341234`. */
object OsmoWifiPassphrase {
    const val DEFAULT = "12341234"

    fun clean(raw: String): String = raw.trim()

    /** A reason the password can't be right, or null if it is plausible. */
    fun validate(raw: String): String? {
        val p = clean(raw)
        if (p.length < 8 || p.length > 63) return "A WiFi password is 8 to 63 characters"
        if (p.any { it.code < 0x20 || it.code > 0x7E }) return "Use plain letters, digits and symbols only"
        return null
    }
}
