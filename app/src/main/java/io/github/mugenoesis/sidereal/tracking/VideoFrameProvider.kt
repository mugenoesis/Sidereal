package io.github.mugenoesis.sidereal.tracking

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds periodic snapshots of the live preview into ML Kit face detection.
 *
 * Frames come from MainActivity calling TextureView.getBitmap() on a timer
 * - a plain readback of whatever DJICodecManager is already rendering to
 * the preview Surface, not a second decode path. Two earlier approaches
 * were tried and abandoned first:
 *  1. A from-scratch raw decoder (pulling H264 via VideoFeeder, decoding
 *     independently). Wrong layer entirely - DJI's own community/GitHub
 *     docs confirm VideoFeeder.VideoDataCallback only delivers
 *     MediaCodec-decodable data when a real DJICodecManager instance
 *     exists, configured with a true output surface - without one, it's
 *     documented to deliver garbage. Real hardware testing matched this
 *     exactly.
 *  2. DJICodecManager's own enabledYuvData(true) dual-output mode (Surface
 *     render + byte callback from one decoder). Real hardware testing
 *     showed this causes a genuine hard freeze on the Qualcomm/LG decoders
 *     tested, confirmed via logcat: toggling it tears down and rebuilds
 *     the entire MediaCodec instance, and DJI's own internal renderer logs
 *     `GLContextMgr: OpenGL destoryed` / `GLYUVSurface: OpenGL destoryed`
 *     at that exact moment, followed by `libEGL: call to OpenGL ES API
 *     with no current context` - DJI's own GL render context for the
 *     Surface gets torn down on the mode switch and never reliably comes
 *     back on this hardware. Two screenshots taken 3 seconds apart during
 *     the freeze were byte-identical, confirming it's a real stuck state,
 *     not just slow. This is a defect inside DJI's own closed
 *     implementation (not reachable from app code - it isn't even present
 *     as readable strings in the SDK's jars or .so libraries, so it's
 *     dynamically loaded/protected beyond what's worth reversing further),
 *     not something fixable by tuning our own code.
 * TextureView.getBitmap() sidesteps both: it never touches the decoder at
 * all, just reads back pixels already rendered to the Surface everyone
 * agrees is rock solid.
 *
 * Two deliberate throttling choices here, both because tracking quality
 * doesn't need full framerate analysis - the PID loop and trail smoothing
 * in FaceTrackingController already produce smooth gimbal motion even
 * with a lower-rate signal feeding them:
 *  1. Time-based throttle to targetFps.
 *  2. Drop any frame that arrives while a previous detection is still
 *     in flight, rather than queuing - always processing the freshest
 *     frame instead of catching up on stale ones.
 */
class VideoFrameProvider(
    private val faceTrackingController: FaceTrackingController,
    private val targetFps: Int = 12
) {
    companion object {
        private const val TAG = "VideoFrameProvider"
    }

    private val minFrameIntervalMs = 1000L / targetFps
    private var lastProcessedTime = 0L
    private val detectionInFlight = AtomicBoolean(false)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking() // gives stable trackingId across frames - required for "follow this specific face"
            .build()
    )

    /** Call this from MainActivity's periodic TextureView.getBitmap() capture loop. */
    fun onBitmapFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < minFrameIntervalMs) return
        if (!detectionInFlight.compareAndSet(false, true)) return // still processing a previous frame, drop this one

        lastProcessedTime = now

        try {
            val image = InputImage.fromBitmap(
                bitmap,
                0 // rotation degrees - activity is landscape-locked and the
                  // Osmo feed should come in upright; if the preview looks
                  // rotated on real hardware, this is the value to change
                  // (must be 0/90/180/270).
            )

            detector.process(image)
                .addOnSuccessListener { faces ->
                    faceTrackingController.onFacesDetected(faces, bitmap.width, bitmap.height)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Face detection failed: ${e.message}")
                }
                .addOnCompleteListener {
                    detectionInFlight.set(false)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing failed: ${e.message}")
            detectionInFlight.set(false)
        }
    }

    fun release() {
        detector.close()
    }
}
