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
 *   adb shell am broadcast -n io.github.mugenoesis.sidereal/.debug.DebugCommandReceiver \
 *       -a io.github.mugenoesis.sidereal.DEBUG --es cmd photo_probe
 * Results go to logcat tag [DebugScenarios.TAG]; each scenario ends with a
 * "RESULT <name>: PASS|FAIL ..." line so a run is checkable by grep.
 *
 * Scenarios run on a process-wide scope and the broadcast returns at once.
 * An earlier version held it open with goAsync() until the scenario ended;
 * scenarios last minutes, so Android raised "Sidereal isn't responding"
 * after ~10s even though nothing was blocked. The app must already be
 * running (start MainActivity first) so the process outlives the broadcast.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val args = intent.extras?.keySet()?.associateWith { intent.extras?.get(it)?.toString().orEmpty() } ?: emptyMap()
        scope.launch {
            try {
                DebugScenarios.run(cmd, args)
            } catch (t: Throwable) {
                Log.e(DebugScenarios.TAG, "RESULT $cmd: FAIL exception $t", t)
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
