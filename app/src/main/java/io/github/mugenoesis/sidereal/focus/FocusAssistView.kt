package io.github.mugenoesis.sidereal.focus

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import io.github.mugenoesis.sidereal.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Magnified star plus "FWHM 3.4 px  ▼ sharper  best 3.1" - purely renders [FocusAssistController]'s state. */
class FocusAssistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val zoom = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val reading = label(16f)
    private val detail = label(11f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundResource(R.drawable.bg_pill_container)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(zoom, LayoutParams(dp(110), dp(110)))
        addView(reading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(detail)
        isClickable = true
    }

    fun bind(controller: FocusAssistController, scope: LifecycleCoroutineScope) {
        combine(controller.enabled, controller.state, controller.zoom, controller.saturated) { on, state, bitmap, saturated ->
            visibility = if (on) VISIBLE else GONE
            zoom.setImageBitmap(bitmap)
            render(state, saturated)
        }.launchIn(scope)
        controller.enabled.onEach { if (it) render(controller.state.value, controller.saturated.value) }.launchIn(scope)
    }

    private fun render(state: FocusAssistState, saturated: Boolean) {
        val fwhm = state.smoothedFwhm
        if (fwhm == null || state.lost) {
            reading.text = if (fwhm == null) "No star" else "Star lost"
            detail.text = "Aim at a bright star"
            return
        }
        val arrow = when (state.trend) {
            FocusTrend.SHARPER -> "▼ sharper"
            FocusTrend.SOFTER -> "▲ softer"
            FocusTrend.STEADY -> "● steady"
        }
        reading.text = String.format(java.util.Locale.US, "FWHM %.1f px", fwhm)
        detail.text = String.format(java.util.Locale.US, "%s  ·  best %.1f", arrow, state.best ?: fwhm) +
            when {
                saturated -> "\nStar clipped - lower exposure"
                state.atBest -> "\nAt best focus"
                else -> ""
            }
    }

    private fun label(sp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
