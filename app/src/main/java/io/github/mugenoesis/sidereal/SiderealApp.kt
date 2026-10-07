package io.github.mugenoesis.sidereal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import com.secneo.sdk.Helper

/**
 * DJI MSDK v4 requires a couple of things wired up at the Application level
 * before anything else touches the SDK:
 *  1. Helper.install(this) in attachBaseContext - patches the classloader so
 *     DJI's internal libs resolve correctly. Must happen before super.onCreate().
 *  2. Kicking off registration once the process is up.
 */
class SiderealApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }

    // Android 14+ throws a SecurityException when an app targeting it registers a receiver without saying whether it
    // is exported - and the DJI SDK (which runs inside this process, using this Application as its context) does
    // exactly that when it registers, so the app would crash on launch on any modern phone. Every receiver
    // registered through the app context is for the system or this app only, so it is marked not-exported here.
    // (System broadcasts such as USB and WiFi changes are still delivered.)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? =
        if (receiver != null && Build.VERSION.SDK_INT >= 33) super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else super.registerReceiver(receiver, filter)

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? =
        if (receiver != null && Build.VERSION.SDK_INT >= 33) {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        DJIConnectionManager.initialize(this)
        retryRegistrationWhenOnline()
    }

    /**
     * First-time registration needs the internet (see RegistrationText). If it failed for that reason, try again by
     * itself the moment a working internet connection shows up, so the user does not have to restart the app.
     */
    private fun retryRegistrationWhenOnline() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val state = DJIConnectionManager.connectionState.value
                    if (state is DJIConnectionManager.ConnectionState.Error && io.github.mugenoesis.sidereal.dji.RegistrationText.needsInternet(state.message)) {
                        android.util.Log.i("SiderealApp", "internet is back - registering with DJI again")
                        DJIConnectionManager.initialize(this@SiderealApp)
                    }
                }
            })
        }
    }
}
