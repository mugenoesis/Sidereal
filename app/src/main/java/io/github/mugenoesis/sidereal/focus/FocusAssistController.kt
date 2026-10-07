package io.github.mugenoesis.sidereal.focus

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Star focus assist: each preview frame is searched for its brightest star
 * ([StarFinder]), a small full-resolution crop around it is measured
 * ([StarMetrics]) and the FWHM smoothed ([FocusAssistTracker]) into a number
 * to minimise while turning the focus ring. Also publishes a magnified crop
 * of the star for the overlay.
 *
 * Measures the preview the phone already has (TextureView.getBitmap), so the
 * absolute FWHM is in preview pixels - only meaningful relative to itself
 * while focusing, which is all focusing needs.
 */
class FocusAssistController(private val targetFps: Int = 8) {

    companion object {
        private const val TAG = "FocusAssist"
        private const val MEASURE_CROP = 64
        private const val ZOOM_CROP = 40
        private const val ZOOM_SCALE = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = FocusAssistTracker()
    private val inFlight = AtomicBoolean(false)
    private var lastFrameAt = 0L

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _state = MutableStateFlow(FocusAssistState())
    val state: StateFlow<FocusAssistState> = _state

    /** Magnified crop of the star being measured, or null when none is found. */
    private val _zoom = MutableStateFlow<Bitmap?>(null)
    val zoom: StateFlow<Bitmap?> = _zoom

    /** The star's core is clipped - the FWHM understates the truth, so the exposure should come down. */
    private val _saturated = MutableStateFlow(false)
    val saturated: StateFlow<Boolean> = _saturated

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (!on) reset()
    }

    fun reset() {
        tracker.reset()
        _state.value = tracker.state
        _zoom.value = null
        _saturated.value = false
    }

    /** Call from the preview frame loop; cheap when disabled or busy. */
    fun onBitmapFrame(bitmap: Bitmap) {
        if (!_enabled.value) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 1000L / targetFps) return
        if (!inFlight.compareAndSet(false, true)) return
        lastFrameAt = now
        scope.launch {
            try {
                process(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "frame failed: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun process(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = LumaConversion.fromArgb(pixels, bitmap.width, bitmap.height)

        val spot = StarFinder.brightest(luma)
        val star = spot?.let { StarMetrics.measure(StarFinder.crop(luma, it.first, it.second, MEASURE_CROP)) }
        tracker.onMeasurement(star?.fwhm)
        _state.value = tracker.state
        _saturated.value = star?.saturated == true

        _zoom.value = if (spot != null && star != null) {
            val half = ZOOM_CROP / 2
            val x = (spot.first - half).coerceIn(0, bitmap.width - ZOOM_CROP)
            val y = (spot.second - half).coerceIn(0, bitmap.height - ZOOM_CROP)
            val sub = Bitmap.createBitmap(bitmap, x, y, ZOOM_CROP, ZOOM_CROP)
            Bitmap.createScaledBitmap(sub, ZOOM_CROP * ZOOM_SCALE, ZOOM_CROP * ZOOM_SCALE, false)
        } else null

        if (star != null) Log.i(TAG, "star at ${spot?.first},${spot?.second} fwhm=%.2f smoothed=%.2f saturated=${star.saturated}".format(star.fwhm, tracker.state.smoothedFwhm))
    }
}
