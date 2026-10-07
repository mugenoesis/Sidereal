package io.github.mugenoesis.sidereal.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Draws bounding boxes over detected faces and owns all touch interaction
 * with them - tap a box to select it, double-tap anywhere to deselect while
 * locked, and drag the locked (solid) box to a new screen position to set
 * where in frame tracking should hold the subject.
 *
 * This view sits on top of videoPreview in the layout and, since its
 * onTouchEvent used to unconditionally return true, it was silently
 * swallowing the entire gesture before videoPreview's own touch listener
 * (previously home to double-tap-deselect and a "manually nudge the
 * gimbal, then release repositions" feature) ever saw it - confirmed as
 * dead/unreachable code, doubly so since that reposition feature also
 * routed through the on-screen joystick, which declines touches outside
 * MANUAL mode. Consolidating everything here, where touches actually land,
 * replaces both with a single working implementation.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** What a plain (non-drag) tap on the video means right now - only one at a time, same one-owner-at-a-time spirit as GimbalModeController's exclusivity, applied to tap semantics instead of motor ownership. */
    enum class TapMode { FACE_SELECT, TAP_TO_FOCUS, SPOT_METER }

    private var faces: List<DetectedFace> = emptyList()
    private var lockedTrackingId: Int? = null

    /** Defaults to FACE_SELECT. Setting this to TAP_TO_FOCUS/SPOT_METER intentionally suspends face-tap-select for as long as it's active - a bare tap can only mean one thing at a time. */
    var tapMode: TapMode = TapMode.FACE_SELECT

    var onFaceTapped: ((trackingId: Int) -> Unit)? = null
    var onDeselectRequested: (() -> Unit)? = null
    /** x,y normalized 0..1 - where the user dropped the locked box, i.e. the new desired hold point in frame. */
    var onFrameTargetDragged: ((x: Float, y: Float) -> Unit)? = null
    /** x,y normalized 0..1 - fired instead of face-tap-select whenever tapMode != FACE_SELECT. */
    var onPreviewTapped: ((x: Float, y: Float) -> Unit)? = null

    private val lockedPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val candidatePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private val dragIndicatorPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Reticle shown briefly at the last tap-to-focus/spot-meter point, in a
    // color distinct from the green/yellow/cyan already used for face boxes
    // so it reads as "I set this here" rather than "this is a tracked face".
    // Cleared on a plain timer rather than a real fade animation - simplest
    // thing that still gives feedback without new animation machinery.
    private val reticlePaint = Paint().apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private var reticleX: Float? = null
    private var reticleY: Float? = null
    private val reticleClearRunnable = Runnable {
        reticleX = null
        reticleY = null
        invalidate()
    }
    private val reticleVisibleMs = 1200L

    // The crosshair held up while the controller's autofocus button is down (see showAimReticle): same icon as the
    // tap-to-focus reticle, but it stays until released rather than clearing on a timer.
    private var aimX: Float? = null
    private var aimY: Float? = null

    /** Puts the tap-to-focus crosshair at the normalized point [x],[y] (0..1 of this view) and keeps it there. */
    fun showAimReticle(x: Float, y: Float) {
        aimX = x
        aimY = y
        invalidate()
    }

    fun hideAimReticle() {
        aimX = null
        aimY = null
        invalidate()
    }

    /** The brief confirmation crosshair a tap shows, at a normalized point. */
    fun flashReticle(x: Float, y: Float) {
        reticleX = x * width
        reticleY = y * height
        removeCallbacks(reticleClearRunnable)
        postDelayed(reticleClearRunnable, reticleVisibleMs)
        invalidate()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDeselectRequested?.invoke()
            return true
        }
    })

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var isDraggingLockedBox = false
    private var downX = 0f
    private var downY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f

    fun update(faces: List<DetectedFace>, lockedTrackingId: Int?) {
        this.faces = faces
        this.lockedTrackingId = lockedTrackingId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (face in faces) {
            val paint = if (face.trackingId == lockedTrackingId) lockedPaint else candidatePaint
            val box = face.boundingBox
            canvas.drawRect(
                box.left * width,
                box.top * height,
                box.right * width,
                box.bottom * height,
                paint
            )
        }
        if (isDraggingLockedBox) {
            canvas.drawLine(dragCurrentX - 24f, dragCurrentY, dragCurrentX + 24f, dragCurrentY, dragIndicatorPaint)
            canvas.drawLine(dragCurrentX, dragCurrentY - 24f, dragCurrentX, dragCurrentY + 24f, dragIndicatorPaint)
        }
        val ax = aimX
        val ay = aimY
        if (ax != null && ay != null) drawReticle(canvas, ax * width, ay * height)
        val rx = reticleX
        val ry = reticleY
        if (rx != null && ry != null) drawReticle(canvas, rx, ry)
    }

    private fun drawReticle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, 28f, reticlePaint)
        canvas.drawLine(x - 36f, y, x - 14f, y, reticlePaint)
        canvas.drawLine(x + 14f, y, x + 36f, y, reticlePaint)
        canvas.drawLine(x, y - 36f, x, y - 14f, reticlePaint)
        canvas.drawLine(x, y + 14f, x, y + 36f, reticlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // Drag-the-locked-box-to-reposition only applies to
                // FACE_SELECT - in TAP_TO_FOCUS/SPOT_METER a touch is always
                // a plain tap, never a drag-to-reposition gesture.
                if (tapMode == TapMode.FACE_SELECT) {
                    val touchXNorm = event.x / width
                    val touchYNorm = event.y / height
                    // Only the currently locked box is draggable - candidate
                    // boxes are tap-to-select only, same as before.
                    val lockedFace = faces.find { it.trackingId == lockedTrackingId }
                    if (lockedFace != null && lockedFace.boundingBox.contains(touchXNorm, touchYNorm)) {
                        isDraggingLockedBox = true
                        dragCurrentX = event.x
                        dragCurrentY = event.y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingLockedBox) {
                    dragCurrentX = event.x
                    dragCurrentY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = isDraggingLockedBox
                isDraggingLockedBox = false
                val movedPastSlop = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlopPx
                if (wasDragging && movedPastSlop) {
                    invalidate()
                    onFrameTargetDragged?.invoke(
                        (event.x / width).coerceIn(0f, 1f),
                        (event.y / height).coerceIn(0f, 1f)
                    )
                    return true
                }
                invalidate()
                // Not a drag (either not on the locked box, or moved less
                // than touch slop) - fall back to tap-to-select on
                // whichever box, if any, is under the touch point.
                hitTestTap(event.x, event.y)
            }
        }
        return true
    }

    /**
     * Lets a sibling view forward a tap here in screen pixel coords - used
     * by JoystickView, which sits on top of this view and must claim
     * ACTION_DOWN itself while armed (Android can't retroactively hand a
     * claimed gesture to a sibling), but reports plain taps (no real drag)
     * back out via its own onTap so they can still reach face-tap-select.
     */
    fun handleExternalTap(xPx: Float, yPx: Float) {
        hitTestTap(xPx, yPx)
    }

    private fun hitTestTap(xPx: Float, yPx: Float) {
        val tapX = xPx / width
        val tapY = yPx / height
        if (tapMode != TapMode.FACE_SELECT) {
            // A miss can't be a no-op here, unlike face-select - any tap on
            // the video means "focus/meter here".
            reticleX = xPx
            reticleY = yPx
            removeCallbacks(reticleClearRunnable)
            postDelayed(reticleClearRunnable, reticleVisibleMs)
            invalidate()
            onPreviewTapped?.invoke(tapX.coerceIn(0f, 1f), tapY.coerceIn(0f, 1f))
            return
        }
        val tapped = faces.find { it.boundingBox.contains(tapX, tapY) }
        if (tapped != null) {
            onFaceTapped?.invoke(tapped.trackingId)
        }
    }
}
