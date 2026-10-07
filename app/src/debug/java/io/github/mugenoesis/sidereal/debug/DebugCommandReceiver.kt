package io.github.mugenoesis.sidereal.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only adb entry point for on-device scenarios, e.g.
 *   adb shell am broadcast -a io.github.mugenoesis.sidereal.DEBUG --es cmd photo_probe
 * Results go to logcat tag [DebugScenarios.TAG]; each scenario ends with a
 * "RESULT <name>: PASS|FAIL ..." line so a run is checkable by grep.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val args = intent.extras?.keySet()?.associateWith { intent.extras?.get(it)?.toString().orEmpty() } ?: emptyMap()
        val pending = goAsync()
        scope.launch {
            try {
                DebugScenarios.run(cmd, args)
            } catch (t: Throwable) {
                Log.e(DebugScenarios.TAG, "RESULT $cmd: FAIL exception $t", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
