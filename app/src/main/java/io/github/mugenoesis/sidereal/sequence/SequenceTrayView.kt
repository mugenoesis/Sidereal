package io.github.mugenoesis.sidereal.sequence

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import io.github.mugenoesis.sidereal.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The sequence tray: mode chips, one -/+ row per field of the chosen mode,
 * a one-line plan summary, and Start/Stop with progress. Built in code
 * (not activity_main.xml) because its rows depend on the mode, and so
 * MainActivity - already well past a thousand lines - only has to host it.
 * Purely a renderer of [SequenceController]'s state; holds none of its own.
 */
class SequenceTrayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val modeLabel = text(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
    private val fieldsBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val summary = text(12f, Color.WHITE)
    private val message = text(12f, 0xFFFFB74D.toInt())
    private val progressText = text(12f, Color.WHITE)
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private val startStop = Button(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
    }

    private var controller: SequenceController? = null
    private var renderedMode: SequenceMode? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_pill_container)
        setPadding(dp(10), dp(10), dp(10), dp(10))

        // Mode selector: < Intervalometer > - one compact row instead of six chips that overflow the tray.
        val modeRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modeRow.addView(stepButton("<") { controller?.stepMode(-1) }, LayoutParams(dp(32), dp(32)))
        modeRow.addView(modeLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(stepButton(">") { controller?.stepMode(+1) }, LayoutParams(dp(32), dp(32)))
        addView(modeRow, LayoutParams(dp(260), LayoutParams.WRAP_CONTENT))
        addView(fieldsBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // Summary and Start share one row to keep the tray short enough for the video area.
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(summary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(startStop, LayoutParams(dp(96), dp(40)).apply { marginStart = dp(8) })
        addView(bottom, LayoutParams(dp(260), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(message, LayoutParams(dp(260), LayoutParams.WRAP_CONTENT))
        addView(progressBar, LayoutParams(dp(260), dp(8)).apply { topMargin = dp(6) })
        addView(progressText)
        // Swallow taps on the tray's empty space so they don't fall through to the video's tap-to-focus.
        isClickable = true
        contentDescription = "Sequence tray"
    }

    fun bind(controller: SequenceController, scope: LifecycleCoroutineScope) {
        this.controller = controller
        startStop.setOnClickListener {
            if (controller.isRunning.value) controller.stop() else controller.start()
        }
        combine(controller.settings, controller.isRunning, controller.progress, controller.message) { _, _, _, _ -> }
            .onEach { render() }
            .launchIn(scope)
    }

    /** Re-reads live camera/gimbal state into the summary (e.g. after the shutter speed changes). */
    fun refreshPreview() = render()

    private fun render() {
        val c = controller ?: return
        val settings = c.settings.value
        val running = c.isRunning.value

        modeLabel.text = settings.mode.label
        for (tag in listOf("step:<", "step:>")) findViewWithTag<View>(tag)?.isEnabled = !running
        if (renderedMode != settings.mode) {
            renderedMode = settings.mode
            fieldsBox.removeAllViews()
            settings.fields().forEach { fieldsBox.addView(fieldRow(it.id)) }
        }
        settings.fields().forEach { spec ->
            fieldsBox.findViewWithTag<TextView>("value:${spec.id}")?.text = spec.display
        }
        for (i in 0 until fieldsBox.childCount) {
            val row = fieldsBox.getChildAt(i)
            row.alpha = if (running) 0.4f else 1f
            (row as? LinearLayout)?.let { r -> for (j in 0 until r.childCount) r.getChildAt(j).isEnabled = !running }
        }

        val preview = c.preview()
        summary.text = when (preview) {
            is PlanResult.Ok -> preview.plan.summary
            is PlanResult.Error -> preview.message
        }
        val warnings = (preview as? PlanResult.Ok)?.plan?.warnings.orEmpty()
        val note = c.message.value ?: warnings.firstOrNull()
        message.text = note.orEmpty()
        message.visibility = if (note == null) GONE else VISIBLE

        val progress = c.progress.value
        val showProgress = running || progress.state is SequenceState.Done || progress.state is SequenceState.Failed || progress.state is SequenceState.Cancelled
        progressBar.visibility = if (showProgress) VISIBLE else GONE
        progressText.visibility = if (showProgress) VISIBLE else GONE
        progressBar.progress = if (progress.capturesTotal == 0) 0 else progress.capturesDone * 100 / progress.capturesTotal
        progressText.text = progressLabel(progress)

        startStop.text = if (running) "Stop" else "Start"
        startStop.setBackgroundResource(if (running) R.drawable.bg_shutter_stopping else R.drawable.bg_segment_selected)
    }

    private fun progressLabel(p: SequenceProgress): String {
        val frames = "${p.capturesDone}/${p.capturesTotal}"
        return when (val s = p.state) {
            is SequenceState.AwaitingUser -> "Waiting for you · $frames"
            is SequenceState.Running -> "Shooting $frames" + if (p.retries > 0) " · ${p.retries} retries" else ""
            is SequenceState.Done -> "Done · $frames"
            is SequenceState.Cancelled -> "Stopped · $frames"
            is SequenceState.Failed -> "Failed · $frames · ${s.reason}"
            SequenceState.Idle -> ""
        }
    }

    private fun fieldRow(id: String): View {
        val c = controller
        val spec = c?.settings?.value?.fields()?.first { it.id == id }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(12f, Color.WHITE).apply { text = spec?.label.orEmpty() }, LayoutParams(dp(84), LayoutParams.WRAP_CONTENT))
        if (spec?.toggle == true) {
            // A toggle is a tappable value pill rather than -/+.
            row.addView(text(12f, Color.WHITE).apply {
                tag = "value:$id"
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_segment_selected)
                setOnClickListener { controller?.adjust(id, +1) }
            }, LayoutParams(dp(104), dp(30)))
        } else {
            row.addView(stepButton("-") { controller?.adjust(id, -1) }, LayoutParams(dp(30), dp(30)))
            row.addView(text(12f, Color.WHITE).apply {
                tag = "value:$id"
                gravity = Gravity.CENTER
            }, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
            row.addView(stepButton("+") { controller?.adjust(id, +1) }, LayoutParams(dp(30), dp(30)))
        }
        return row.also {
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
    }

    private fun stepButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setBackgroundResource(R.drawable.bg_stepper_button)
        setOnClickListener { onClick() }
        tag = "step:$label"
    }

    private fun text(sizeSp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
