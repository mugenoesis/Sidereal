package io.github.mugenoesis.sidereal.dji

/**
 * The DJI SDK has to register the app with DJI's servers once, the first time it runs after install - which needs the
 * internet, and the Osmo's own WiFi has none. Installing the app while already connected to the Osmo (the usual place
 * to be) hits this, and the SDK's wording is no help, so say what to do.
 */
object RegistrationText {

    fun needsInternet(sdkMessage: String): Boolean =
        sdkMessage.contains("internet", ignoreCase = true) ||
            sdkMessage.contains("metadata received from server", ignoreCase = true)

    fun describe(sdkMessage: String): String =
        if (needsInternet(sdkMessage)) {
            "First start needs the internet once, so DJI can register this app. Connect to your normal WiFi or mobile " +
                "data - it will carry on by itself - then join the Osmo's WiFi."
        } else {
            "DJI SDK error: $sdkMessage"
        }
}
