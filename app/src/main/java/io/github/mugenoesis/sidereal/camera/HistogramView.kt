package io.github.mugenoesis.sidereal.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Renders the raw bucket data HistogramController pushes as a simple bar
 * graph - normalized against the data's own max value, since the SDK
 * doesn't document a fixed scale to draw against. Treats the array as N
 * equal-width buckets left-to-right; HistogramController's doc comment
 * flags that the real layout (luma-only vs. RGB-interleaved) isn't
 * confirmed, so this generic normalize-and-bar-chart approach is a
 * reasonable first pass that doesn't assume a specific interpretation -
 * revisit once real pushed data has been inspected on hardware.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: ShortArray? = null

    private val barPaint = Paint().apply {
        color = Color.parseColor("#4CC2FF")
        style = Paint.Style.FILL
    }

    fun update(newData: ShortArray?) {
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buckets = data ?: return
        if (buckets.isEmpty() || width <= 0 || height <= 0) return

        val maxValue = buckets.maxOf { it.toInt() }.coerceAtLeast(1)
        val barWidth = width.toFloat() / buckets.size
        for (i in buckets.indices) {
            val normalized = buckets[i].toFloat() / maxValue
            val barHeight = normalized * height
            val left = i * barWidth
            canvas.drawRect(left, height - barHeight, left + barWidth - 1f, height.toFloat(), barPaint)
        }
    }
}
