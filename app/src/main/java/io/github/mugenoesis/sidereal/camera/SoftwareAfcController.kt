package io.github.mugenoesis.sidereal.camera

import android.graphics.Bitmap
import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Software-emulated continuous autofocus for lenses the camera cannot AF-C itself (the Zenmuse X5 rejects AFC
 * outright). Contrast-detect on the phone's own preview frames: each is scored for sharpness (variance of the
 * Laplacian over a centre crop), and [FocusSearch] decides where to put the manual focus ring.
 *
 * Why it is built this way, from measuring the real lens rather than guessing (details in NOTES): the picture's
 * sharpness is flat noise across most of the ring with one narrow peak, and the preview follows a ring move
 * within ~150 ms. The earlier hill-climb (nudge, compare, halve) had no gradient to follow in the flat part and a
 * leash that stopped it reaching the peak, so from most starting points it missed focus entirely - however it was
 * tuned. Now:
 *  1. The camera's own hardware autofocus is pointed at the subject (centre, or where the user tapped) and given
 *     time to land; the ring position it chose is the starting hint. Hardware AF is good at "roughly right".
 *  2. [FocusSearch] samples a window of ring positions around that hint (the whole ring if there is no clear
 *     peak there), fits a parabola for the exact peak, parks the ring on it and then leaves it completely alone -
 *     no hunting, which also makes it usable while filming.
 *  3. If sharpness collapses and stays down, the scene changed: it searches again from where it was.
 *
 * Needs FocusMode.MANUAL underneath, which [start] arranges. The camera's Focus Assistant (peaking/zoom on every
 * ring move) is switched off for the run and restored after, since it would feed a different picture into the
 * metric on every nudge.
 */
class SoftwareAfcController(private val focusController: FocusController) {

    companion object {
        private const val TAG = "SoftwareAfc"
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val CROP_FRACTION = 0.34
        private const val SAMPLE_SIZE = 100

        // Consecutive frames differing by less than this (fraction of full scale) count as a steady picture.
        private const val STEADY_PICTURE = 0.03f

        // Hardware AF hunts for 1.5-2.5 s on this camera: poll the ring until it holds still (RingSettleDetector).
        private const val RING_POLL_MS = 400L

        // The camera ignores a focus target sent the instant it switches to AUTO (seen intermittently), so wait a moment
        // after the mode switch, and send the target again once if the ring has not moved by the time it "settles".
        private const val AFTER_AUTO_MS = 350L
        private const val MAX_TARGET_ATTEMPTS = 2

        // The camera drops commands sent on top of each other, so the Focus Assistant switch-off is given this long to
        // land before the AF commands start.
        private const val ASSIST_SETTLE_MS = 800L

        // Never leave the controller waiting forever on an SDK callback that does not come (this SDK has several).
        private const val SEEDING_TIMEOUT_MS = 12_000L

        /** The running controller, for the debug harness. */
        @Volatile var active: SoftwareAfcController? = null

        /** The most recently created controller (running or not), so the debug harness can start it. */
        @Volatile var latest: SoftwareAfcController? = null
    }

    init {
        latest = this
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    // Exposed for the UI: the latest raw sharpness, meaningful only relative to its own recent history.
    private val _lastSharpness = MutableStateFlow(0.0)
    val lastSharpness: StateFlow<Double> = _lastSharpness

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var search: FocusSearch? = null
    private var sceneWatcher: SceneChangeDetector? = null
    private var previousSignature: FloatArray? = null
    private var lastLockedRing: Int? = null
    private var seeding = false
    private var generation = 0
    private var lastSampleTime = 0L
    private var savedFocusAssistAF: Boolean? = null
    private var savedFocusAssistMF: Boolean? = null
    private val pending = ArrayList<Runnable>()
    private var seedingTimeout: Runnable? = null

    fun start() {
        if (_isRunning.value) return
        active = this
        savedFocusAssistAF = null
        savedFocusAssistMF = null
        focusController.refreshFocusRingRange()
        // See FocusController.getFocusAssistantEnabled's doc comment - the camera's own focus-peaking zoom fires on
        // every ring nudge unless this is off. Saved so stop() can put it back for a human focusing by hand.
        focusController.getFocusAssistantEnabled { enabledAF, enabledMF ->
            savedFocusAssistAF = enabledAF
            savedFocusAssistMF = enabledMF
            focusController.setFocusAssistantEnabled(false, false)
        }
        _isRunning.value = true
        generation++
        val token = generation
        seeding = true // hold off sampling until the first search starts
        // The camera drops commands that arrive on top of each other: let the assistant switch-off land first.
        later(ASSIST_SETTLE_MS, token) { seedFromHardwareAf(0.5f, 0.5f) }
    }

    fun stop() {
        _isRunning.value = false
        generation++
        cancelPending()
        seedingTimeout?.let { mainHandler.removeCallbacks(it) }
        seedingTimeout = null
        search = null
        sceneWatcher = null
        seeding = false
        _isLocked.value = false
        if (active === this) active = null
        val af = savedFocusAssistAF
        val mf = savedFocusAssistMF
        if (af != null && mf != null) focusController.setFocusAssistantEnabled(af, mf)
    }

    /** The user tapped the picture: aim the camera's AF there and search again from wherever it lands. */
    fun recalibrateAt(xNorm: Float, yNorm: Float) {
        if (!_isRunning.value) return
        seedFromHardwareAf(xNorm, yNorm)
    }

    private fun cancelPending() {
        pending.forEach { mainHandler.removeCallbacks(it) }
        pending.clear()
    }

    private fun later(delayMs: Long, token: Int, block: () -> Unit) {
        val r = Runnable { if (token == generation) block() }
        pending += r
        mainHandler.postDelayed(r, delayMs)
    }

    /**
     * Points the camera's hardware AF at ([x], [y], normalized 0..1), watches the ring until it stops moving, and
     * starts the search from where it landed. Frames are ignored while this runs ([seeding]) so the software can't
     * fight the hardware for the ring. If any step fails the search still starts - just with a poorer hint.
     */
    private fun seedFromHardwareAf(x: Float, y: Float) {
        generation++
        val token = generation
        cancelPending()
        seeding = true
        search = null
        sceneWatcher = null
        _isLocked.value = false
        Log.i(TAG, "AFC seeding from hardware AF at ($x, $y)")

        val timeout = Runnable {
            if (token != generation) return@Runnable
            Log.w(TAG, "seeding timed out - searching without a hint")
            beginSearch(null, token)
        }
        seedingTimeout = timeout
        mainHandler.postDelayed(timeout, SEEDING_TIMEOUT_MS)

        val ringBefore = intArrayOf(-1)
        readRing { before ->
            ringBefore[0] = before ?: -1
            if (token != generation) return@readRing
            focusController.setFocusMode(SettingsDefinitions.FocusMode.AUTO) { modeOk ->
                if (token != generation) return@setFocusMode
                if (!modeOk) Log.w(TAG, "couldn't switch to AUTO for hardware AF")
                mainHandler.post { later(AFTER_AUTO_MS, token) { aimAndWatch(x, y, token, ringBefore[0], attempt = 1) } }
            }
        }
    }

    /** Sends the AF target, then watches the ring; if it ends where it began, tries the target once more before accepting that. */
    private fun aimAndWatch(x: Float, y: Float, token: Int, ringBefore: Int, attempt: Int) {
        if (token != generation) return
        focusController.setFocusTarget(x, y) { targetOk ->
            if (!targetOk) Log.w(TAG, "setFocusTarget rejected (attempt $attempt)")
            mainHandler.post { pollRing(token, RingSettleDetector(), System.currentTimeMillis(), x, y, ringBefore, attempt) }
        }
    }

    private fun readRing(onResult: (Int?) -> Unit) {
        val camera = DJIConnectionManager.camera
        if (camera == null) { onResult(null); return }
        camera.getFocusRingValue(object : CommonCallbacks.CompletionCallbackWith<Int> {
            override fun onSuccess(value: Int) = onResult(value)
            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getFocusRingValue failed: ${error.description}")
                onResult(null)
            }
        })
    }

    /** Reads the ring every [RING_POLL_MS] until the camera's AF has stopped moving it, then starts the search there. */
    private fun pollRing(token: Int, detector: RingSettleDetector, startedAt: Long, x: Float, y: Float, ringBefore: Int, attempt: Int) {
        if (token != generation) return
        readRing { ring ->
            mainHandler.post {
                if (token != generation) return@post
                val elapsed = System.currentTimeMillis() - startedAt
                val settled = detector.onReading(elapsed, ring)
                when {
                    settled == null -> later(RING_POLL_MS, token) { pollRing(token, detector, startedAt, x, y, ringBefore, attempt) }
                    settled == ringBefore && attempt < MAX_TARGET_ATTEMPTS -> {
                        Log.w(TAG, "ring did not move after the AF target (still $settled) - sending the target again")
                        aimAndWatch(x, y, token, ringBefore, attempt + 1)
                    }
                    else -> {
                        Log.i(TAG, "hardware AF settled at ring=$settled after $elapsed ms (attempt $attempt)")
                        beginSearch(settled, token)
                    }
                }
            }
        }
    }

    private fun beginSearch(seed: Int?, token: Int) {
        if (token != generation) return
        seedingTimeout?.let { mainHandler.removeCallbacks(it) }
        seedingTimeout = null
        focusController.setFocusMode(SettingsDefinitions.FocusMode.MANUAL) { modeOk ->
            mainHandler.post {
                if (token != generation) return@post
                if (!modeOk) Log.w(TAG, "couldn't switch back to MANUAL - the search may not control the ring")
                val bound = focusController.focusRingUpperBound.value
                if (bound == null || bound <= 0) {
                    Log.w(TAG, "focus ring range unknown - cannot search")
                    seeding = false
                    return@post
                }
                val fresh = FocusSearch(bound)
                search = fresh
                val first = fresh.begin(seed, System.currentTimeMillis())
                Log.i(TAG, "AFC search begins: seed=$seed bound=$bound first move=${first.ring}")
                focusController.setFocusRingValue(first.ring)
                seeding = false
            }
        }
    }

    /** Call from the same periodic TextureView.getBitmap() loop VideoFrameProvider uses. */
    fun onBitmapFrame(bitmap: Bitmap) {
        if (!_isRunning.value || seeding) return
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return
        val current = search ?: return
        lastSampleTime = now

        val (score, signature) = analyse(bitmap)
        _lastSharpness.value = score

        // Is the picture itself still changing (a pan in progress)? Then sharpness says nothing about focus.
        val before = previousSignature
        previousSignature = signature
        val steady = before == null || SceneSignature.difference(before, signature) < STEADY_PICTURE

        // Locked: is it still the same scene? A different, settled picture means look again - first in a window
        // around where focus was (a new subject is usually at a similar distance); the search itself falls back to
        // the whole ring if there is nothing there. Not the camera's own AF again: it picked a badly blurred ring
        // for a perfectly ordinary scene in testing, so it is trusted only for the first hint.
        if (current.phase == FocusSearch.Phase.LOCKED) {
            val watcher = sceneWatcher
            if (watcher != null && watcher.onFrame(signature)) {
                val from = lastLockedRing ?: bound()
                Log.i(TAG, "t=$now scene changed and settled - searching again around ring $from")
                sceneWatcher = null
                _isLocked.value = false
                focusController.setFocusRingValue(current.begin(from, now).ring)
                return
            }
        }

        when (val command = current.onFrame(now, score, steady)) {
            is FocusSearch.Command.MoveTo -> {
                _isLocked.value = false
                sceneWatcher = null
                Log.d(TAG, "t=$now score=${score.toInt()} phase=${current.phase} -> ring ${command.ring}")
                focusController.setFocusRingValue(command.ring)
            }
            is FocusSearch.Command.Locked -> {
                _isLocked.value = true
                lastLockedRing = command.ring
                sceneWatcher = SceneChangeDetector(signature)
                Log.i(TAG, "t=$now LOCKED ring=${command.ring} score=${command.score.toInt()} confident=${command.confident}")
            }
            null -> Log.v(TAG, "t=$now score=${score.toInt()} phase=${current.phase}")
        }
    }

    private fun bound(): Int = focusController.focusRingUpperBound.value ?: 0

    /** One centre crop, downsampled to a square: its sharpness (variance of the Laplacian) and a fingerprint of the scene. */
    private fun analyse(bitmap: Bitmap): Pair<Double, FloatArray> {
        val w = bitmap.width
        val h = bitmap.height
        val cropW = (w * CROP_FRACTION).toInt().coerceAtLeast(2)
        val cropH = (h * CROP_FRACTION).toInt().coerceAtLeast(2)
        val left = ((w - cropW) / 2).coerceAtLeast(0)
        val top = ((h - cropH) / 2).coerceAtLeast(0)
        val crop = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        val small = Bitmap.createScaledBitmap(crop, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        return HillClimbFocus.laplacianVariance(pixels, SAMPLE_SIZE) to SceneSignature.of(pixels, SAMPLE_SIZE)
    }
}
