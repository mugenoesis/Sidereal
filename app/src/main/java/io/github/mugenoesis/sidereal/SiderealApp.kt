package io.github.mugenoesis.sidereal

import android.app.Application
import android.content.Context
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

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        DJIConnectionManager.initialize(this)
    }
}
