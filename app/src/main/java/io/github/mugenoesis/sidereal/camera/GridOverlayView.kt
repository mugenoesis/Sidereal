package io.github.mugenoesis.sidereal.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Composition guides drawn over the picture itself (not the whole preview
 * view - see [ContentRect]). Never consumes touches, so tap-to-focus still
 * works underneath.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: GridMode = GridMode.OFF
        set(value) { field = value; invalidate() }

    /** Width / height of the picture inside this view. */
    var contentAspect: Float = 16f / 9f
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == GridMode.OFF || width == 0 || height == 0) return
        val rect = ContentRect.fit(width.toFloat(), height.toFloat(), contentAspect)
        for (line in GridGeometry.lines(mode, rect.width, rect.height).map { it.offset(rect.left, rect.top) }) {
            canvas.drawLine(line.x1, line.y1, line.x2, line.y2, paint)
        }
    }
}
