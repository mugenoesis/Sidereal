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
 * Experimental software-emulated continuous autofocus - see
 * FocusController's class doc comment for why this exists (AFC is confirmed
 * unsupported natively on the Zenmuse X5, real hardware testing and DJI's
 * own docs agree). Classic contrast-detection "mountain climb" hill-climb:
 * on each sampled preview frame, scores sharpness (variance of Laplacian)
 * over a center crop, then nudges the manual focus ring in whichever
 * direction increased sharpness last time - reversing and halving the step
 * whenever a step makes things worse. Requires FocusMode.MANUAL underneath
 * (the ring can't be driven from AUTO), which start() switches to.
 *
 * First cut, tuned only against whatever real hardware testing this session
 * could fit in - step size/sample interval/crop size are starting points,
 * not validated constants. Certain to behave nothing like real CDAF/PDAF
 * (motor speed, ring resolution, and the sharpness metric are all much
 * cruder here) - the point was to see how close a purely software approach
 * can get, not to match it.
 *
 * Confirmed on real X5 hardware: the mechanism works end to end - mode
 * cycling into it, frame sampling, and real setFocusRingValue() commands
 * all landing (logcat shows the ring genuinely walking through its full
 * 0..bound range and converging near a local sharpness peak). Two real
 * findings so far, both from watching actual behavior rather than just
 * logcat:
 *
 * 1. The raw variance-of-Laplacian score swung wildly (5-10x) between
 * consecutive frames. Initially blamed on low light/compression noise;
 * turned out to be mostly the camera's own Focus Assistant (peaking/zoom
 * magnification for manual focus) firing on every setFocusRingValue() call
 * and feeding a completely different crop into the metric each time - see
 * FocusController.setFocusAssistantEnabled's doc comment, which start()/
 * stop() below now disable/restore around a run. SCORE_SMOOTHING (an EMA
 * feeding the hill-climb decision, separate from the raw value the UI
 * displays) is a real, independently useful smoothing fix on top of that,
 * kept for whatever residual frame-to-frame noise remains.
 *
 * 2. With Focus Assistant left enabled, the live preview visibly "zoomed
 * in" on every focus attempt - that turned out to just be (1)'s Focus
 * Assistant magnification itself, not lens breathing as first suspected.
 * MAX_EXCURSION_FRACTION/the smaller INITIAL_STEP_FRACTION below predate
 * that discovery and are a real, independent tightening (less unnecessary
 * roaming during search) but were not the actual fix for the zoom
 * complaint - disabling Focus Assistant was.
 *
 * Only exercised in PHOTO mode so far, in a normal dim room; it hunts
 * noticeably even once "settled" (small continuous ring adjustments, never
 * fully still - inherent to hill-climb CDAF, real cameras do this too).
 * That's likely fine for photo but UNTESTED and possibly not usable for
 * video (visible micro-refocusing during a recording could be far more
 * objectionable on camera than a live photo preview) - deliberately not
 * yet tested in video mode this session, since a separate known bug means
 * recording can't reliably be stopped once started. Daylight/well-lit
 * behavior is also still unconfirmed.
 *
 * Second real-hardware finding: with an unleashed search (the ring free to
 * roam its full 0..bound range) and an 8%-of-range initial step, real
 * testing showed the live preview visibly "zooming in" while it hunted -
 * this is very likely focus breathing (a normal property of this lens: the
 * field of view visibly changes as focus racks toward one end of its
 * travel, most lenses do this to some degree), made obvious by how far a
 * from-scratch search had to sweep before finding a peak. MAX_EXCURSION_FRACTION
 * leashes the search to a neighborhood of wherever it started and a smaller
 * INITIAL_STEP_FRACTION makes that initial sweep gentler - the real
 * trade-off is a true peak outside the leash is now unreachable, so this
 * only works well when the starting focus position was already roughly in
 * the right neighborhood.
 *
 * Third real-hardware finding, confirming the above's "untested against a
 * genuinely defocused start" was a real gap, not just theoretical: in a
 * low-light scene with the ring left far from correct focus (leftover from
 * earlier testing in different conditions), sharpness kept climbing right
 * up to the leash edge and then just stopped there permanently - the true
 * peak was further out than MAX_EXCURSION_FRACTION allowed, exactly the
 * flagged risk. onBitmapFrame() now re-anchors the leash (via
 * HillClimbFocus.shouldReanchorLeash()) after a few consecutive samples
 * where the climb is still trying to advance but the leash won't let it -
 * see its doc comment. This turns the original hard cap into a sliding
 * window that can still walk arbitrarily far given sustained real
 * improvement, while a single noisy sample at the edge still isn't enough
 * to move it (requires REANCHOR_AFTER_STUCK_SAMPLES in a row). Also tuned
 * for more responsiveness per a real "make it snappier" request -
 * SAMPLE_INTERVAL_MS and INITIAL_STEP_FRACTION's own comments cover what
 * changed and, just as importantly, why SCORE_SMOOTHING deliberately
 * didn't.
 *
 * Fourth real-hardware finding, confirmed AFTER the re-anchoring fix above
 * (so it's a separate problem, not that fix failing): starting from a
 * FULL physical extreme (ring dragged all the way to one end via the
 * manual slider, not just "somewhat off" like the third finding) never
 * recovers. Root cause is different from the leash - it never even gets
 * that far. This far from any real edge, the sharpness landscape is flat
 * and noisy enough that nextClimbState's reversals start almost
 * immediately, collapsing step from its initial size down to MIN_STEP
 * within about 8 samples, which traps the search oscillating in a weak
 * local optimum near the starting point - it never sustains a long enough
 * one-directional run to reach the leash edge (where re-anchoring could
 * even kick in) in the first place. Not something a bigger leash or a
 * faster step alone can fix, since the problem is the search never gets
 * a real run going at all from here.
 *
 * FIX for the fourth finding, implemented: recalibrateAt() below - while
 * AFC is running, a screen tap now triggers the camera's existing hardware
 * AF-at-point (the same "Tap video to focus" tap-to-focus already wired up
 * elsewhere for AUTO/MANUAL mode - see FaceOverlayView.onPreviewTapped and
 * FocusController.setFocusTarget) as a one-shot recalibration, snapping the
 * ring close to correct focus in a single hardware-assisted move instead of
 * asking the software hill-climb to cover that same distance one small
 * contrast-detection step at a time. The software loop takes back over from
 * wherever that lands for continuous fine tracking, same as it does after a
 * normal start(). Sidesteps the actual hard problem (making pure
 * contrast-detection hill-climbing robust from an arbitrary starting point)
 * rather than solving it - reuses hardware AF's own real distance-sensing
 * for the big jump, software AFC only ever has to do the small continuous
 * adjustments it's already reasonably good at. The two open questions from
 * when this was still just a proposal are answered in recalibrateAt()'s own
 * doc comment (both needed real code, not just documentation, to answer -
 * MainActivity's tap routing didn't reach SoftwareAfcController at all
 * before this, and the software/hardware race is avoided by explicitly
 * pausing onBitmapFrame() for the duration rather than letting them run
 * concurrently).
 *
 * Fifth real-hardware finding: even after the fourth fix above, real
 * testing found focus could still be acquired - via recalibrateAt() or
 * plain organic convergence - and then later just let go, ending up
 * completely defocused, at both close (~20cm) and normal (~2m) distances.
 * Root cause is structural, not distance-specific: nextClimbState only
 * ever halves or holds the step, never regrows it, and once floored at
 * MIN_STEP the search never stopped actively perturbing the ring even
 * once it had clearly already found the peak (visible in logcat as
 * indefinite small oscillation around a fixed position). That combination
 * means a single wrong-direction reversal at MIN_STEP - plausibly caused
 * by ordinary sensor noise, or the physical jolt of tapping the
 * touchscreen to trigger a recalibration - had no way to recover: it
 * would just keep nudging further off a peak it could no longer see well
 * enough to climb back toward, with no bigger step available to
 * re-explore with. FIX: locked/belowLockThresholdStreak below - the
 * moment a reversal happens while already at MIN_STEP (bracketing the
 * peak, the standard CDAF convergence signal), the ring is frozen
 * entirely instead of continuing to dither. Only a confirmed real drop in
 * score (LOCK_DROP_FRACTION, held for UNLOCK_CONFIRM_SAMPLES in a row so
 * one noisy frame can't false-trigger it) unlocks and restores a real
 * exploratory step size to search again, instead of being permanently
 * stuck at MIN_STEP. Bonus: this also directly addresses the class doc
 * comment's earlier "hunts noticeably even once settled... possibly not
 * usable for video" concern, since a converged lock now stops moving the
 * ring at all.
 */
class SoftwareAfcController(private val focusController: FocusController) {

    companion object {
        private const val TAG = "SoftwareAfc"
        // 350ms -> 250ms: a real-hardware "make it snappier" request. Only
        // the sampling *cadence* changed, not step sizes - reacting more
        // often speeds up convergence without making any single step
        // bigger (which would risk overshooting a narrow sharpness peak
        // before a reversal is even detected). SCORE_SMOOTHING below is
        // deliberately NOT loosened for the same "snappier" ask - it was a
        // hard-won fix for a real, confirmed noise problem (raw scores
        // swinging 5-10x between consecutive frames), and speeding up
        // reaction time there specifically risks reintroducing that.
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val MIN_STEP = 3
        // 0.025 -> 0.035: covers ground faster during the initial
        // exploratory phase (also directly helps the leash re-anchoring
        // below reach a genuinely-far peak in fewer steps) - a real
        // tradeoff against overshoot risk (a bigger step is more likely to
        // step past a narrow peak before nextClimbState's score-decrease
        // check catches it and halves), kept modest rather than doubled
        // for that reason. The existing halve-on-reversal behavior still
        // takes over for fine settling once any reversal is detected,
        // regardless of how big this starting step is.
        private const val INITIAL_STEP_FRACTION = 0.035 // of the ring's full range
        private const val MAX_EXCURSION_FRACTION = 0.15 // how far from the starting ring position a search is allowed to roam
        // How many consecutive samples the climb can be blocked by the
        // leash (still trying to advance, not a reversal) before
        // HillClimbFocus.shouldReanchorLeash() says to move the leash's
        // anchor there instead of staying permanently capped - see its doc
        // comment for the real-hardware finding that motivated this.
        private const val REANCHOR_AFTER_STUCK_SAMPLES = 2
        private const val CROP_FRACTION = 0.34
        private const val SAMPLE_SIZE = 100
        private const val SCORE_SMOOTHING = 0.35 // EMA weight on each new raw sample; lower = steadier but slower to react
        // Time given the hardware AF motor to physically settle after
        // setFocusTarget's own completion callback fires, before handing
        // ring control back to the software loop - that callback's exact
        // timing semantics are unconfirmed (may only mean "command
        // acknowledged", not "motor stopped moving", same open question
        // setFocusTarget's own doc comment already flags), so this is a
        // deliberate buffer, not a precisely measured value. Untested on
        // real hardware whether this is long enough or overly cautious.
        private const val RECALIBRATE_SETTLE_MS = 400L
        // Safety net in case setFocusMode(MANUAL)'s completion callback
        // never fires at all (this session found more than one DJI SDK
        // call that doesn't always resolve cleanly) - without this, a lost
        // callback would leave [recalibrating] stuck true forever, silently
        // freezing the whole hill-climb until the user manually stops and
        // restarts AFC. Generous on purpose since firing early would be
        // worse (resuming ring control while the camera might still
        // genuinely be mid-switch) than firing a bit late.
        private const val RECALIBRATE_TIMEOUT_MS = 3000L
        // See the class doc comment's fifth real-hardware finding. Once
        // locked, resume searching only after the smoothed score has
        // genuinely fallen - not just dipped for one noisy/shake frame -
        // to below this fraction of the locked peak's score. Originally
        // 0.75, tightened after real testing caught it false-triggering:
        // while frozen at a genuinely correct peak (smoothed ~2051), the
        // *score alone* swung down to ~900 within about 600ms with zero
        // ring movement - real frame-to-frame noise in a low-contrast
        // scene, not defocus. 0.5 requires a much more decisive drop
        // before concluding the scene actually changed.
        private const val LOCK_DROP_FRACTION = 0.5
        // Consecutive below-threshold samples required before actually
        // unlocking - at SAMPLE_INTERVAL_MS=250ms, 4 is roughly a second
        // of sustained confirmed decline. Raised from 2 alongside
        // LOCK_DROP_FRACTION above for the same reason: real testing's
        // noise swing lasted more than one sample.
        private const val UNLOCK_CONFIRM_SAMPLES = 4
        // Step size used only for the FIRST re-search sample right after
        // an unlock - deliberately much smaller than INITIAL_STEP_FRACTION
        // (a cold start() has zero information and needs a broad sweep;
        // an unlock starts near a position that was recently confirmed
        // good). Real testing showed why this matters: after an unlock
        // right next to a true peak roughly 15 ring-units wide, resuming
        // with the full ~71-unit cold-start step blew straight through it
        // and could never find its way back, converging on progressively
        // worse local points each subsequent cycle (2051 -> 157 -> 62).
        private const val UNLOCK_STEP_FRACTION = 0.008
        // A converging search is only allowed to lock in place if its
        // score is within this fraction of the best score seen anywhere
        // during the current search episode - otherwise it snaps straight
        // back to that best-known position instead. Real testing (a pan
        // to a close subject, then a recalibration tap) found the search
        // climb straight through a true peak (smoothed ~4671) on its way
        // past, then the reversal/halving cascade that's supposed to find
        // its way back only recovered as far as a much worse nearby point
        // (smoothed ~157) and locked there instead - never rediscovering
        // the peak it had already seen moments earlier. Remembering and
        // returning to the best point actually observed is cheap and
        // guaranteed at least as good as wherever the cascade stalls.
        private const val LOCK_ACCEPT_FRACTION = 0.9
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    // Exposed for the UI to show the hill-climb actually hunting - real
    // sharpness units, only meaningful relative to its own recent history.
    private val _lastSharpness = MutableStateFlow(0.0)
    val lastSharpness: StateFlow<Double> = _lastSharpness

    private var direction = 1
    private var step = 0
    private var lastScore: Double? = null
    private var smoothedScore: Double? = null
    private var position: Int? = null
    private var startPosition: Int? = null
    private var initInFlight = false
    private var lastSampleTime = 0L
    private var savedFocusAssistAF: Boolean? = null
    private var savedFocusAssistMF: Boolean? = null
    private var stuckAtLeashCount = 0
    // See the class doc comment's fifth real-hardware finding / LOCK_DROP_FRACTION.
    private var locked = false
    private var lockedScore = 0.0
    private var belowLockThresholdStreak = 0
    // Best (score, position) pair seen anywhere during the current search
    // episode - see LOCK_ACCEPT_FRACTION's doc comment.
    private var bestScoreThisSearch: Double? = null
    private var bestPositionThisSearch: Int? = null
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recalibrating = false
    private var pendingRecalibrateSettle: Runnable? = null
    private var pendingRecalibrateTimeout: Runnable? = null

    fun start() {
        if (_isRunning.value) return
        direction = 1
        step = 0
        startPosition = null
        smoothedScore = null
        lastScore = null
        position = null
        initInFlight = false
        lastSampleTime = 0L
        savedFocusAssistAF = null
        savedFocusAssistMF = null
        stuckAtLeashCount = 0
        locked = false
        lockedScore = 0.0
        belowLockThresholdStreak = 0
        bestScoreThisSearch = null
        bestPositionThisSearch = null
        _isLocked.value = false
        recalibrating = false
        pendingRecalibrateSettle?.let { mainHandler.removeCallbacks(it) }
        pendingRecalibrateSettle = null
        pendingRecalibrateTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingRecalibrateTimeout = null
        focusController.refreshFocusRingRange()
        focusController.setFocusMode(SettingsDefinitions.FocusMode.MANUAL)
        // See FocusController.getFocusAssistantEnabled's doc comment - the
        // camera's own focus-peaking zoom fires on every ring nudge unless
        // this is off. Saved so stop() can put it back the way a human
        // manually focusing would still expect it.
        focusController.getFocusAssistantEnabled { enabledAF, enabledMF ->
            savedFocusAssistAF = enabledAF
            savedFocusAssistMF = enabledMF
            focusController.setFocusAssistantEnabled(false, false)
        }
        _isRunning.value = true
    }

    fun stop() {
        _isRunning.value = false
        pendingRecalibrateSettle?.let { mainHandler.removeCallbacks(it) }
        pendingRecalibrateSettle = null
        pendingRecalibrateTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingRecalibrateTimeout = null
        recalibrating = false
        locked = false
        _isLocked.value = false
        val af = savedFocusAssistAF
        val mf = savedFocusAssistMF
        if (af != null && mf != null) {
            focusController.setFocusAssistantEnabled(af, mf)
        }
    }

    /**
     * One-shot hardware AF-at-point recalibration while AFC is running -
     * see the class doc comment's fourth real-hardware finding for why
     * this exists (a genuinely defocused start defeats the pure software
     * hill-climb). xNorm/yNorm are normalized 0..1, same convention as
     * FocusController.setFocusTarget - MainActivity's existing tap
     * handler (previously always routed a TAP_TO_FOCUS tap straight to
     * focusController.setFocusTarget, which never reached this class at
     * all) now calls this instead whenever AFC is the one actually
     * running.
     *
     * setFocusTarget's own doc comment flags that whether it's honored
     * while FocusMode == MANUAL (what start() leaves the real camera in)
     * is unconfirmed - rather than gambling on that, this switches to
     * AUTO first, confirms it landed, *then* sets the target, and only
     * switches back to MANUAL (handing ring control back to the
     * hill-climb) once that completes. onBitmapFrame() ignores every
     * frame for the duration (the [recalibrating] guard below) so the
     * software loop can't fire a setFocusRingValue() while the hardware
     * is also mid-move - letting them race was one of the two open
     * questions this feature started as a proposal with; explicitly
     * pausing is the answer, not letting them fight it out and hoping.
     */
    fun recalibrateAt(xNorm: Float, yNorm: Float) {
        if (!_isRunning.value || recalibrating) return
        recalibrating = true
        pendingRecalibrateSettle?.let { mainHandler.removeCallbacks(it) }
        focusController.setFocusMode(SettingsDefinitions.FocusMode.AUTO) { modeSetOk ->
            if (!modeSetOk) {
                Log.w(TAG, "recalibrateAt($xNorm, $yNorm): couldn't switch to AUTO, aborting recalibration")
                recalibrating = false
                return@setFocusMode
            }
            focusController.setFocusTarget(xNorm, yNorm) { targetSetOk ->
                if (!targetSetOk) {
                    Log.w(TAG, "recalibrateAt($xNorm, $yNorm): setFocusTarget rejected, returning to MANUAL anyway")
                }
                val settle = Runnable { finishRecalibration() }
                pendingRecalibrateSettle = settle
                mainHandler.postDelayed(settle, RECALIBRATE_SETTLE_MS)
            }
        }
    }

    private fun finishRecalibration() {
        pendingRecalibrateSettle = null
        // Reseed exactly like start() does - null out local hill-climb
        // state so the next onBitmapFrame() re-queries the REAL ring
        // position (now wherever hardware AF-at-point left it) instead of
        // continuing to climb from its old, now-stale position/direction.
        // UNLIKE start(), step is seeded at MIN_STEP, not 0 - 0 tells
        // onBitmapFrame() to compute the full INITIAL_STEP_FRACTION
        // exploratory step size on its very next sample, which is exactly
        // right for a cold start with zero information about where the
        // peak is, but wrong here: real hardware testing found that after
        // hardware AF-at-point just landed on a genuinely sharp position,
        // immediately shoving the ring away by a large exploratory step
        // undid it before the hill-climb could ever settle back - "tap to
        // focus never settles on the sharper image". Starting the
        // post-recalibration climb at the smallest step instead means it
        // only ever nudges gently from here, trusting hardware AF's result
        // rather than re-exploring from scratch.
        direction = 1
        step = MIN_STEP
        position = null
        startPosition = null
        lastScore = null
        smoothedScore = null
        initInFlight = false
        stuckAtLeashCount = 0
        locked = false
        lockedScore = 0.0
        belowLockThresholdStreak = 0
        bestScoreThisSearch = null
        bestPositionThisSearch = null
        _isLocked.value = false
        // recalibrating only clears once MANUAL is actually confirmed back
        // (not synchronously before this call even returns, which real
        // hardware testing's "never settles" symptom is also consistent
        // with - onBitmapFrame() could otherwise resume and fire a
        // setFocusRingValue() before the camera had actually left AUTO,
        // which would very plausibly just be silently rejected the same
        // way every other command sent to this camera in the wrong mode
        // has been all session).
        focusController.setFocusMode(SettingsDefinitions.FocusMode.MANUAL) { modeSetOk ->
            if (!modeSetOk) Log.w(TAG, "recalibrateAt: couldn't switch back to MANUAL - hill-climb may not regain ring control")
            pendingRecalibrateTimeout?.let { mainHandler.removeCallbacks(it) }
            pendingRecalibrateTimeout = null
            recalibrating = false
        }
        // See RECALIBRATE_TIMEOUT_MS's doc comment - cancelled above the
        // moment the real callback actually fires; this only ever does
        // anything if that callback gets lost entirely.
        val timeout = Runnable {
            Log.w(TAG, "recalibrateAt: setFocusMode(MANUAL) callback never fired within ${RECALIBRATE_TIMEOUT_MS}ms - forcing recalibrating clear so AFC doesn't stay frozen")
            pendingRecalibrateTimeout = null
            recalibrating = false
        }
        pendingRecalibrateTimeout = timeout
        mainHandler.postDelayed(timeout, RECALIBRATE_TIMEOUT_MS)
    }

    /** Call from the same periodic TextureView.getBitmap() loop VideoFrameProvider uses. */
    fun onBitmapFrame(bitmap: Bitmap) {
        if (!_isRunning.value || recalibrating) return
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return
        val bound = focusController.focusRingUpperBound.value
        if (bound == null || bound <= 0) return // still waiting on the range query
        lastSampleTime = now

        val rawScore = sharpnessScore(bitmap)
        _lastSharpness.value = rawScore

        // Real hardware testing found the raw per-frame score swings by
        // 5-10x between consecutive samples at a near-fixed ring position -
        // compression + low-light sensor noise in the re-encoded preview
        // feed, not real focus change - which was enough on its own to
        // make the hill-climb thrash (reverse direction on pure noise
        // every other sample instead of ever settling). Smoothing with an
        // EMA before it drives any decision was the fix that mattered;
        // _lastSharpness above still reports the raw per-frame value so
        // the on-screen number reflects what the camera is actually
        // seeing, not a lagging average.
        val score = HillClimbFocus.emaUpdate(smoothedScore, rawScore, SCORE_SMOOTHING)
        smoothedScore = score

        if (locked) {
            if (score < lockedScore * LOCK_DROP_FRACTION) {
                belowLockThresholdStreak++
            } else {
                belowLockThresholdStreak = 0
                if (score > lockedScore) lockedScore = score // track a rising peak so a slow brightness increase doesn't itself look like a future drop
            }
            if (belowLockThresholdStreak < UNLOCK_CONFIRM_SAMPLES) {
                lastScore = score
                return
            }
            Log.d(TAG, "locked score dropped to $score (was $lockedScore) - unlocking to re-search")
            locked = false
            _isLocked.value = false
            belowLockThresholdStreak = 0
            bestScoreThisSearch = null
            bestPositionThisSearch = null
            // A small local step, not the big blind INITIAL_STEP_FRACTION
            // sweep - see UNLOCK_STEP_FRACTION's doc comment.
            step = HillClimbFocus.stepSize(bound, UNLOCK_STEP_FRACTION, MIN_STEP)
        }

        if (step == 0) step = HillClimbFocus.stepSize(bound, INITIAL_STEP_FRACTION, MIN_STEP)

        val currentPos = position
        if (currentPos == null) {
            if (initInFlight) return
            initInFlight = true
            val camera = DJIConnectionManager.camera
            if (camera == null) {
                initInFlight = false
                return
            }
            camera.getFocusRingValue(object : CommonCallbacks.CompletionCallbackWith<Int> {
                override fun onSuccess(value: Int) {
                    position = value
                    startPosition = value
                    lastScore = score
                    initInFlight = false
                }
                override fun onFailure(error: DJIError) {
                    Log.w(TAG, "getFocusRingValue failed: ${error.description}")
                    position = bound / 2
                    startPosition = bound / 2
                    lastScore = score
                    initInFlight = false
                }
            })
            return
        }

        // score here corresponds to currentPos (the position the ring was
        // last commanded to, which this frame reflects) - see
        // LOCK_ACCEPT_FRACTION's doc comment for why this is tracked.
        if (bestScoreThisSearch == null || score > bestScoreThisSearch!!) {
            bestScoreThisSearch = score
            bestPositionThisSearch = currentPos
        }

        val prevScore = lastScore
        val prevDirection = direction
        val stepBeforeUpdate = step
        val (nextDirection, nextStep) = HillClimbFocus.nextClimbState(
            HillClimbFocus.ClimbState(direction, step), prevScore, score, MIN_STEP
        )
        direction = nextDirection
        step = nextStep
        val reversed = nextDirection != prevDirection

        if (reversed && stepBeforeUpdate == MIN_STEP) {
            // Bracketed the peak - reversed even though already at the
            // finest step size, the standard CDAF "found it" signal. See
            // the class doc comment's fifth finding: freezing here instead
            // of continuing to dither is what stops a single noisy/shake
            // sample from starting an unrecoverable walk off the peak.
            val best = bestScoreThisSearch
            val bestPos = bestPositionThisSearch
            if (best != null && bestPos != null && bestPos != currentPos && score < best * LOCK_ACCEPT_FRACTION) {
                // See LOCK_ACCEPT_FRACTION's doc comment: this search
                // already saw a clearly better point than where it's
                // about to settle - go back there directly instead of
                // accepting wherever the reversal cascade stalled.
                Log.d(TAG, "converged at pos=$currentPos smoothed=$score but best seen this search was $best at $bestPos - returning there instead")
                position = bestPos
                locked = true
                _isLocked.value = true
                lockedScore = best
                belowLockThresholdStreak = 0
                lastScore = best
                bestScoreThisSearch = null
                bestPositionThisSearch = null
                focusController.setFocusRingValue(bestPos)
                return
            }
            locked = true
            _isLocked.value = true
            lockedScore = score
            belowLockThresholdStreak = 0
            lastScore = score
            bestScoreThisSearch = null
            bestPositionThisSearch = null
            Log.d(TAG, "converged at pos=$currentPos smoothed=$score - locking")
            return
        }

        // Leashed to a neighborhood of where the search started, on top of
        // the ring's own 0..bound limits - see MAX_EXCURSION_FRACTION's doc
        // comment for why: an unleashed search that has to sweep far to
        // find a peak racks the ring across a big chunk of its travel,
        // which on this lens visibly changes framing (focus breathing) -
        // real hardware testing described this as the video "zooming in".
        val leash = HillClimbFocus.stepSize(bound, MAX_EXCURSION_FRACTION, MIN_STEP)
        var anchor = startPosition ?: currentPos
        val proposedPos = HillClimbFocus.nextPosition(currentPos, direction, step, bound, anchor, leash)

        // Real hardware testing (a genuinely defocused start in a low-light
        // scene) found sharpness still climbing right up to this leash and
        // then just stopping there permanently - exactly the "trade-off: a
        // true peak outside the leash will never be found" risk this leash
        // always carried, now with an escape hatch. direction not having
        // just reversed means the climb still wants to go this way (a
        // reversal means a real nearby peak was found, which is a genuine
        // stop, not the leash being in the way) - reversed itself was
        // already computed above, alongside the convergence-lock check.
        stuckAtLeashCount = if (!reversed && proposedPos == currentPos) stuckAtLeashCount + 1 else 0
        if (HillClimbFocus.shouldReanchorLeash(stuckAtLeashCount, REANCHOR_AFTER_STUCK_SAMPLES)) {
            Log.d(TAG, "leash stuck at $currentPos for $stuckAtLeashCount samples (anchor was $anchor) - re-anchoring")
            startPosition = currentPos
            anchor = currentPos
            stuckAtLeashCount = 0
        }

        val nextPos = HillClimbFocus.nextPosition(currentPos, direction, step, bound, anchor, leash)
        Log.d(TAG, "raw=$rawScore smoothed=$score prevSmoothed=$prevScore dir=$direction step=$step pos=$currentPos->$nextPos bound=$bound")
        position = nextPos
        lastScore = score
        focusController.setFocusRingValue(nextPos)
    }

    /** Crops/downsamples to a square pixel buffer, then hands off to HillClimbFocus.laplacianVariance for the actual metric. */
    private fun sharpnessScore(bitmap: Bitmap): Double {
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
        return HillClimbFocus.laplacianVariance(pixels, SAMPLE_SIZE)
    }
}
