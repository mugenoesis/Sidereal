package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.CameraGateway
import io.github.mugenoesis.sidereal.dji.RealCameraGateway
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Auto-exposure lock: freezes the metered exposure so reframing doesn't change it. */
class AeLockController(private val gateway: CameraGateway = RealCameraGateway) {

    private companion object {
        const val TAG = "AeLockController"
    }

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    fun toggle() {
        val target = !_locked.value
        gateway.setAeLock(target) { error ->
            if (error == null) {
                _locked.value = target
            } else {
                Log.w(TAG, "setAeLock($target) failed: $error")
                _errorEvents.tryEmit("Exposure lock rejected ($error)")
            }
        }
    }

    /** The camera's own report of the lock state is authoritative. */
    fun onCameraReported(locked: Boolean) {
        _locked.value = locked
    }

    /** Forget the lock locally (e.g. after a reconnect) without sending anything. */
    fun reset() {
        _locked.value = false
    }
}
