package io.github.mugenoesis.sidereal.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws the camera's pushed luma histogram (see [HistogramModel] for what that data is): 64 bars from true black on
 * the left to true white on the right, with faint quarter lines for reference and a red marker on the right edge
 * while the highlights are clipping.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bars: FloatArray? = null
    private var highlightsClipped = false

    private val barPaint = Paint().apply {
        color = Color.parseColor("#4CC2FF")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f
    }
    private val clipPaint = Paint().apply {
        color = Color.parseColor("#FF4D4D")
        style = Paint.Style.FILL
    }

    fun update(newData: ShortArray?) {
        bars = HistogramModel.display(newData)
        highlightsClipped = (HistogramModel.stats(newData)?.highlightsClipped ?: 0.0) > CLIP_WARNING
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shown = bars ?: return
        if (width <= 0 || height <= 0) return

        val barWidth = width.toFloat() / shown.size
        for (i in shown.indices) {
            val left = i * barWidth
            canvas.drawRect(left, height - shown[i] * height, left + barWidth - 1f, height.toFloat(), barPaint)
        }
        for (q in 1..3) canvas.drawLine(width * q / 4f, 0f, width * q / 4f, height.toFloat(), gridPaint)
        if (highlightsClipped) canvas.drawRect(width - 6f, 0f, width.toFloat(), height.toFloat(), clipPaint)
    }

    private companion object {
        /** Share of the frame at white that warns of blown highlights. */
        const val CLIP_WARNING = 0.02
    }
}
