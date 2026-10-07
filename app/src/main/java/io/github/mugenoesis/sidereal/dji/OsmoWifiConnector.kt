package io.github.mugenoesis.sidereal.dji

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log

/**
 * Joins the phone to the Osmo's own WiFi from inside the app, without a trip to Android's settings.
 *
 * Android 10+ forbids apps from silently switching networks, so this uses the supported route: a
 * [WifiNetworkSpecifier] request for any SSID starting `OSMO_` with the camera's WPA2 password. The system shows
 * its own "connect to ...?" prompt the first time, and once the network is up the whole process is bound to it
 * (the camera network has no internet, so without binding Android could route the DJI SDK's sockets elsewhere).
 * Other apps are unaffected. Below Android 10 it reports that the network must be joined by hand.
 */
class OsmoWifiConnector(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** @param onResult null on success, or a message saying why not */
    fun connect(passphrase: String, onResult: (String?) -> Unit) {
        Log.i(TAG, "join requested")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult("Join the Osmo's WiFi in Android settings (this Android version can't do it from the app)")
            return
        }
        OsmoWifiPassphrase.validate(passphrase)?.let { onResult(it); return }
        release()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(OSMO_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(OsmoWifiPassphrase.clean(passphrase))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "joined the Osmo network, binding the process to it")
                connectivity.bindProcessToNetwork(network)
                onResult(null)
            }

            override fun onUnavailable() {
                Log.w(TAG, "the Osmo network was not available (declined or wrong password)")
                onResult("Couldn't join the Osmo's WiFi - is the camera on, and is the password right? (long-press to change it)")
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "lost the Osmo network")
                connectivity.bindProcessToNetwork(null)
            }
        }
        callback = cb
        try {
            connectivity.requestNetwork(request, cb, CONNECT_TIMEOUT_MS)
        } catch (e: SecurityException) {
            callback = null
            onResult("Android refused the WiFi request (${e.message})")
        }
    }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        connectivity.bindProcessToNetwork(null)
    }

    private companion object {
        const val TAG = "OsmoWifiConnector"
        const val OSMO_SSID_PREFIX = "OSMO_"
        // Includes the time spent choosing the network in Android's own dialog - 30 s was seen to expire at the very
        // moment the connection completed.
        const val CONNECT_TIMEOUT_MS = 90_000
    }
}
