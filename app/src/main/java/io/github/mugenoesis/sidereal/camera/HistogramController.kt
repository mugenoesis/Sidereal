package io.github.mugenoesis.sidereal.camera

import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streams the camera's live histogram data while a histogram UI panel is
 * actually on screen. [activate]/[deactivate] are meant to be driven by
 * that panel's visibility, not connection state - streaming this data has
 * a real cost, so it shouldn't run unwatched in the background.
 *
 * The raw bucket data in [histogramData] is exposed as-is; this controller
 * doesn't interpret it. Its length and layout (luma-only vs. RGB-interleaved
 * buckets) aren't documented in the verified SDK surface, so the rendering
 * code will need to inspect real pushed data on hardware to work out the
 * bucket-to-pixel mapping.
 */
class HistogramController {

    companion object {
        private const val TAG = "HistogramController"
    }

    private val _histogramData = MutableStateFlow<ShortArray?>(null)
    val histogramData: StateFlow<ShortArray?> = _histogramData

    fun activate() {
        val camera = DJIConnectionManager.camera ?: return
        camera.setHistogramEnabled(true) { error ->
            if (error != null) Log.w(TAG, "setHistogramEnabled(true) failed: ${error.description}")
        }
        camera.setHistogramCallback { data -> _histogramData.value = data }
    }

    fun deactivate() {
        val camera = DJIConnectionManager.camera ?: return
        camera.setHistogramEnabled(false) { error ->
            if (error != null) Log.w(TAG, "setHistogramEnabled(false) failed: ${error.description}")
        }
        // There's no confirmed setHistogramCallback(null) to unregister
        // with, so the callback is just left in place - harmless, since it
        // won't fire again once the camera side is disabled. Re-activating
        // later re-registers it (overwriting, not stacking), so nothing
        // leaks across an activate/deactivate/activate cycle either.
        _histogramData.value = null
    }
}
