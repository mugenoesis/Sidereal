package io.github.mugenoesis.sidereal.dji

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Checks whether the phone's current WiFi network looks like an Osmo's own
 * access point (SSID starting with "OSMO_", matching the naming DJI uses
 * for the handheld's direct-connect hotspot). The DJI SDK gives no signal
 * for "wrong WiFi network" specifically - it just sits in Disconnected
 * forever, indistinguishable from "the Osmo is simply off" - so this exists
 * to catch the common case of the phone being on some other network
 * (home WiFi, mobile hotspot, etc.) and surface it directly instead.
 */
object OsmoWifiChecker {

    private const val OSMO_SSID_PREFIX = "OSMO_"

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun isOsmoNetwork(ssid: String?): Boolean =
        ssid != null && ssid.startsWith(OSMO_SSID_PREFIX, ignoreCase = true)

    /** Call from onStart() - pair with stop() in onStop(). */
    fun start(context: Context) {
        if (receiver != null) return
        refresh(context)
        val appContext = context.applicationContext
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refresh(appContext)
            }
        }
        receiver = newReceiver
        @Suppress("DEPRECATION") // NETWORK_STATE_CHANGED_ACTION still fires for foreground apps on minSdk 23+
        appContext.registerReceiver(newReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    }

    fun stop(context: Context) {
        receiver?.let {
            context.applicationContext.unregisterReceiver(it)
            receiver = null
        }
    }

    private fun refresh(context: Context) {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION") // simplest cross-version way to read the current SSID (minSdk 23)
        val rawSsid = wifiManager?.connectionInfo?.ssid
        _currentSsid.value = rawSsid
            ?.trim('"')
            ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" }
    }
}
