package io.github.mugenoesis.sidereal.gimbal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen virtual joystick for manual gimbal control. Appears wherever the
 * user first touches down (not a fixed HUD position), tracks drag as an
 * offset from that point clamped to a max radius, and reports a normalized
 * (-1..1, -1..1) vector for as long as the finger is held - callers drive a
 * continuous rotate-at-speed loop off that, since a single command per
 * touch-move wouldn't keep the gimbal moving while the finger just sits at
 * an offset without moving further.
 *
 * Declines every touch (returns false from onTouchEvent) unless `armed` is
 * true, so events fall through to whatever sibling view is underneath -
 * e.g. face-tap-select while in FACE_TRACK mode. `armed` covers MANUAL mode
 * and also FACE_TRACK while not actively LOCKED (see MainActivity), so the
 * user can manually search when tracking has nothing to follow. Because
 * Android can't retroactively hand a claimed gesture to a sibling view once
 * armed has claimed ACTION_DOWN, a touch that never moves past touch slop -
 * a plain tap, not a drag - is reported via onTap instead of being treated
 * as stick input, so callers can forward it on to e.g. face-tap-select
 * themselves.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var armed: Boolean = false

    var onStickMoved: ((x: Float, y: Float) -> Unit)? = null
    var onStickReleased: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    /** Fired instead of stick input when a touch turns out to be a plain tap (no real drag) - screen pixel coords. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    private val maxRadiusPx = resources.displayMetrics.density * 60f
    private val baseRadiusPx = resources.displayMetrics.density * 45f
    private val knobRadiusPx = resources.displayMetrics.density * 22f

    private var centerX = 0f
    private var centerY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var stickVisible = false
    private var hasMovedPastSlop = false
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return false

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                centerX = event.x
                centerY = event.y
                knobX = event.x
                knobY = event.y
                stickVisible = true
                hasMovedPastSlop = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> updateKnob(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stickVisible = false
                invalidate()
                if (hasMovedPastSlop) {
                    onStickReleased?.invoke()
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    onTap?.invoke(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun updateKnob(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val distanceFromDown = hypot(dx, dy)
        if (distanceFromDown > touchSlopPx) hasMovedPastSlop = true
        val distance = min(distanceFromDown, maxRadiusPx)
        val angle = atan2(dy, dx)
        knobX = centerX + distance * cos(angle)
        knobY = centerY + distance * sin(angle)
        invalidate()

        // Real hardware testing: this used to fire onStickMoved on every
        // ACTION_MOVE unconditionally, including the sub-slop jitter any
        // real finger tap has - a "tap" that had even slight incidental
        // movement would start the gimbal moving at a slow rate, and since
        // hasMovedPastSlop stayed false, ACTION_UP took the onTap() branch
        // instead of onStickReleased() - nothing ever stopped it. Gating
        // on hasMovedPastSlop here means a plain tap never calls
        // onStickMoved at all, so there's nothing left running to release.
        if (!hasMovedPastSlop) return

        val normalizedX = ((knobX - centerX) / maxRadiusPx).coerceIn(-1f, 1f)
        val normalizedY = ((knobY - centerY) / maxRadiusPx).coerceIn(-1f, 1f)
        onStickMoved?.invoke(normalizedX, normalizedY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!stickVisible) return
        canvas.drawCircle(centerX, centerY, baseRadiusPx, basePaint)
        canvas.drawCircle(knobX, knobY, knobRadiusPx, knobPaint)
    }
}
