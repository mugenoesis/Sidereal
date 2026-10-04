package io.github.mugenoesis.sidereal.zoom

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps digital zoom for lenses that support it. Not every Zenmuse/Osmo
 * lens does - the X5/X5R primes don't - so the UI should stay hidden
 * until `capability.supported` comes back true for whatever's actually
 * connected right now.
 */
class ZoomController {

    data class ZoomCapability(
        val supported: Boolean,
        val minScale: Float,
        val maxScale: Float
    )

    companion object {
        private const val TAG = "ZoomController"
    }

    private val _capability = MutableStateFlow(ZoomCapability(supported = false, minScale = 1f, maxScale = 1f))
    val capability: StateFlow<ZoomCapability> = _capability

    private val _currentScale = MutableStateFlow(1f)
    val currentScale: StateFlow<Float> = _currentScale

    /**
     * Call whenever the connected camera/lens changes - lens swaps on an
     * interchangeable-lens setup are a real mid-session event, not just a
     * one-time startup check. Wire this to
     * DJIConnectionManager.connectionState / component-change updates.
     *
     * `Camera.isDigitalZoomSupported()` is a synchronous boolean - there's
     * no SDK-exposed min/max scale range to go with it, so 1x-6x below is
     * still a placeholder to confirm against the connected lens's real
     * documented range.
     */
    fun refreshCapability() {
        val camera = DJIConnectionManager.camera
        _capability.value = if (camera != null && camera.isDigitalZoomSupported()) {
            ZoomCapability(true, 1f, 6f)
        } else {
            ZoomCapability(false, 1f, 1f)
        }
    }

    fun setZoomScale(scale: Float) {
        val camera = DJIConnectionManager.camera ?: return
        val cap = _capability.value
        if (!cap.supported) return

        val clamped = scale.coerceIn(cap.minScale, cap.maxScale)
        camera.setDigitalZoomFactor(clamped) { error ->
            if (error != null) {
                Log.w(TAG, "setDigitalZoomFactor failed: ${error.description}")
            } else {
                _currentScale.value = clamped
            }
        }
    }

    fun adjustZoomBy(delta: Float) {
        setZoomScale(_currentScale.value + delta)
    }
}
