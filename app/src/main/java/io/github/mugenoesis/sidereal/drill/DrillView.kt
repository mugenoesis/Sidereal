package io.github.mugenoesis.sidereal.drill

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws [DrillEngine]. */
class DrillView(
    context: Context,
    private val drill: DrillEngine,
    private val best: () -> Int
) : View(context) {

    /** A touch in world units: where, and whether it is the first contact (a tap = mark). */
    var onTouch: ((x: Float, y: Float, isDown: Boolean) -> Unit)? = null
    var newRecord = false

    /** True: transparent, so the live camera view shows through behind the drill. False: solid black space. */
    var overlay = true

    private class Speck(var angle: Float, var dist: Float)

    private val stars = Random(99).let { r -> List(150) { Speck(r.nextFloat() * 6.2832f, 0.02f + r.nextFloat() * 1.1f) } }
    private val shapes = HashMap<Int, FloatArray>()
    private var clock = 0f
    private var shakeUntil = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    // The shadow keeps the text readable over a bright picture.
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
    private val path = Path()

    fun advance(dt: Float) {
        clock += dt
        val speed = DrillEngine.speedFactor(drill.time)
        for (s in stars) {
            s.dist *= 1f + dt * 0.35f * speed
            if (s.dist > 1.15f) s.dist = 0.02f + (s.angle * 7f % 1f) * 0.05f
        }
    }

    fun shake() {
        shakeUntil = clock + 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        if (!overlay) canvas.drawColor(Color.parseColor("#05060D"))
        // Over the camera view a light veil quiets the app's own controls underneath so the drill reads clearly.
        else canvas.drawColor(Color.argb(95, 0, 0, 12))
        val s = min(width / drill.width, height / drill.height)
        val ox = (width - drill.width * s) / 2
        val oy = (height - drill.height * s) / 2

        drawStars(canvas)

        canvas.save()
        if (clock < shakeUntil) canvas.translate((Math.random().toFloat() - 0.5f) * 22f, (Math.random().toFloat() - 0.5f) * 22f)
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        for (a in drill.targets.sortedByDescending { it.z }) drawTarget(canvas, a)
        for (e in drill.effects) drawEffect(canvas, e)
        if (drill.state == DrillState.PLAYING) drawCrosshair(canvas, drill.crosshairX, drill.crosshairY)
        canvas.restore()

        for (e in drill.effects) if (e.kind == EffectKind.CONTACT) {
            fill.color = Color.argb((120 * (1f - e.age / DrillEngine.EFFECT_LIFE_S)).toInt().coerceIn(0, 255), 255, 40, 40)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        drawHud(canvas)
        if (drill.time < 4f && drill.state == DrillState.PLAYING) drawTitle(canvas)
        if (drill.state == DrillState.ENDED) drawEnded(canvas)
    }

    private fun drawStars(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val reach = max(cx, cy) * 1.5f
        for (s in stars) {
            val d = s.dist * reach
            val brightness = (s.dist * 255).toInt().coerceIn(40, 255)
            fill.color = Color.argb(brightness, 220, 230, 255)
            canvas.drawCircle(cx + cos(s.angle) * d, cy + sin(s.angle) * d, 1f + s.dist * 3f, fill)
        }
    }

    private fun outline(a: Target): FloatArray = shapes.getOrPut(a.id) {
        val r = Random(a.shapeSeed.toLong())
        FloatArray(VERTICES) { 0.72f + r.nextFloat() * 0.45f }
    }

    private fun drawTarget(canvas: Canvas, a: Target) {
        val shape = outline(a)
        val spin = (if (a.shapeSeed % 2 == 0) 1f else -1f) * (0.4f + (a.shapeSeed and 7) / 8f) * drill.time
        path.reset()
        for (i in 0 until VERTICES) {
            val ang = spin + i * (6.2832f / VERTICES)
            val x = a.x + cos(ang) * a.radius * shape[i]
            val y = a.y + sin(ang) * a.radius * shape[i]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // Mostly opaque so it reads against the camera picture, but you can still see through the edges a little.
        fill.color = Color.argb(if (overlay) 215 else 255, 0x2E, 0x2F, 0x3A)
        canvas.drawPath(path, fill)
        val danger = (1f - a.z / 0.35f).coerceIn(0f, 1f) // turns red in its last third of the way
        stroke.color = Color.rgb((150 + 105 * danger).toInt(), (160 - 110 * danger).toInt(), (190 - 140 * danger).toInt())
        stroke.strokeWidth = 4f + a.radius * 0.04f
        canvas.drawPath(path, stroke)
    }

    private fun drawCrosshair(canvas: Canvas, x: Float, y: Float) {
        stroke.color = Color.parseColor("#FFD54F") // the same yellow as the tap-to-focus crosshair
        stroke.strokeWidth = 5f
        canvas.drawCircle(x, y, 34f, stroke)
        canvas.drawLine(x - 50f, y, x - 18f, y, stroke)
        canvas.drawLine(x + 18f, y, x + 50f, y, stroke)
        canvas.drawLine(x, y - 50f, x, y - 18f, stroke)
        canvas.drawLine(x, y + 18f, x, y + 50f, stroke)
    }

    private fun drawEffect(canvas: Canvas, e: Effect) {
        val t = e.age / DrillEngine.EFFECT_LIFE_S
        val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
        when (e.kind) {
            EffectKind.HIT -> {
                stroke.color = Color.argb(alpha, 255, 170, 60)
                stroke.strokeWidth = 8f * (1f - t) + 2f
                canvas.drawCircle(e.x, e.y, 30f + t * 190f, stroke)
                stroke.color = Color.argb(alpha, 255, 240, 200)
                canvas.drawCircle(e.x, e.y, 14f + t * 100f, stroke)
                text.textSize = 56f
                text.color = Color.argb(alpha, 255, 240, 120)
                canvas.drawText("+1", e.x - 30f, e.y - 40f - t * 80f, text)
                text.color = Color.WHITE
            }
            EffectKind.MISS -> {
                stroke.color = Color.argb(alpha / 2, 255, 255, 255)
                stroke.strokeWidth = 3f
                canvas.drawCircle(e.x, e.y, 10f + t * 50f, stroke)
            }
            EffectKind.CONTACT -> Unit // drawn as a screen flash
        }
    }

    private fun drawHud(canvas: Canvas) {
        val size = height * 0.05f
        text.textSize = size
        val top = size * 0.55f
        val bottom = size * 2.0f
        val baseline = size * 1.62f

        // dark pills behind each readout, so they stay clean over whatever the camera shows
        fun pill(left: Float, right: Float) {
            fill.color = Color.argb(if (overlay) 200 else 0, 0, 0, 0)
            canvas.drawRoundRect(left, top, right, bottom, size * 0.5f, size * 0.5f, fill)
        }
        val scoreText = DrillText.score + drill.score
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        pill(size * 0.6f, size * 0.6f + text.measureText(scoreText) + size * 0.8f)
        canvas.drawText(scoreText, size, baseline, text)

        val bestText = DrillText.best + max(best(), drill.score)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.parseColor("#FFD54F")
        val half = text.measureText(bestText) / 2 + size * 0.4f
        pill(width / 2f - half, width / 2f + half)
        canvas.drawText(bestText, width / 2f, baseline, text)

        val shieldsRight = width - size * 0.6f
        val shieldsLeft = width - size * (1.2f + (DrillEngine.START_MARGIN - 1) * 1.4f) - size * 0.75f
        pill(shieldsLeft, shieldsRight)
        for (i in 0 until DrillEngine.START_MARGIN) {
            fill.color = if (i < drill.margin) Color.parseColor("#4CC2FF") else Color.parseColor("#3A4254")
            canvas.drawCircle(width - size * (1.2f + i * 1.4f), size * 1.28f, size * 0.42f, fill)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
    }

    private fun drawTitle(canvas: Canvas) {
        val fade = (1f - max(0f, drill.time - 2.5f) / 1.5f).coerceIn(0f, 1f)
        val alpha = (255 * fade).toInt()
        text.textAlign = Paint.Align.CENTER
        text.color = Color.argb(alpha, 255, 255, 255)
        text.setShadowLayer(8f, 0f, 0f, Color.argb(alpha, 0, 0, 0)) // the shadow must fade with the text or it is left behind
        text.textSize = height * 0.12f
        canvas.drawText(DrillText.title, width / 2f, height * 0.38f, text)
        text.textSize = height * 0.034f
        canvas.drawText(DrillText.hint, width / 2f, height * 0.48f, text)
        text.setShadowLayer(8f, 0f, 0f, Color.BLACK)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawEnded(canvas: Canvas) {
        fill.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.parseColor("#FF5252")
        text.textSize = height * 0.14f
        canvas.drawText(DrillText.over, width / 2f, height * 0.38f, text)
        text.color = Color.WHITE
        text.textSize = height * 0.06f
        canvas.drawText(DrillText.score + drill.score + DrillText.gap + DrillText.best + best(), width / 2f, height * 0.5f, text)
        if (newRecord) {
            text.color = Color.parseColor("#FFD54F")
            canvas.drawText(DrillText.record, width / 2f, height * 0.6f, text)
        }
        text.color = Color.WHITE
        text.textSize = height * 0.04f
        canvas.drawText(DrillText.again, width / 2f, height * 0.74f, text)
        text.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = min(width / drill.width, height / drill.height)
        val ox = (width - drill.width * s) / 2
        val oy = (height - drill.height * s) / 2
        val x = (event.x - ox) / s
        val y = (event.y - oy) / s
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouch?.invoke(x, y, true)
            MotionEvent.ACTION_MOVE -> onTouch?.invoke(x, y, false)
        }
        return true
    }

    private companion object {
        const val VERTICES = 11
    }
}
