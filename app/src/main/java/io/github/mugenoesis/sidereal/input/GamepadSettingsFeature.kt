package io.github.mugenoesis.sidereal.input

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.mugenoesis.sidereal.AppPreferences
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.display.NightMode

/**
 * The game-controller settings: a "Game controller" row at the bottom of the More tray opens a screen to change
 * how sensitive the sticks are and which action each button does. Everything applies to the live [GamepadMapper]
 * straight away and is saved, so there is no Apply button to forget.
 */
class GamepadSettingsFeature(private val activity: AppCompatActivity, private val mapper: GamepadMapper) {

    private var dialog: AlertDialog? = null
    private val valueViews = HashMap<GamepadSetting, TextView>()
    private val buttonViews = HashMap<GamepadButton, Button>()

    init {
        val tray = activity.findViewById<LinearLayout>(R.id.moreSettingsTray)
        tray?.addView(label("Game controller", 13f, bold = true).apply { setPadding(0, dp(14), 0, dp(2)) })
        tray?.addView(Button(activity).apply {
            text = "Buttons & sticks…"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_pill_container)
            tag = "gamepad_settings"
            setOnClickListener { show() }
        })
    }

    private fun show() {
        dialog?.dismiss()
        valueViews.clear()
        buttonViews.clear()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(label("Sticks", 14f, bold = true))
        for (setting in GamepadSetting.values()) content.addView(settingRow(setting))
        content.addView(label("Buttons", 14f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        content.addView(label("Tap a button to choose what it does. Several buttons can do the same thing.", 11f).apply { alpha = 0.7f })
        for (button in GamepadBindings.REMAPPABLE) content.addView(buttonRow(button))

        dialog = AlertDialog.Builder(activity)
            .setTitle("Game controller")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNeutralButton("Reset to defaults") { _, _ -> reset() }
            .setPositiveButton("Done", null)
            .show()
            .also { NightMode.apply(it) }
    }

    private fun settingRow(setting: GamepadSetting): View {
        val value = label(mapper.config.display(setting), 13f).apply { gravity = Gravity.CENTER }
        valueViews[setting] = value
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(setting.label, 13f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(step("-") { change(setting, -1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(value, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(step("+") { change(setting, +1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    private fun buttonRow(button: GamepadButton): View {
        val action = Button(activity).apply {
            text = mapper.bindings.actionFor(button).label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_pill_container)
            tag = "gamepad_button_${button.name}"
            setOnClickListener { chooseAction(button) }
        }
        buttonViews[button] = action
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(buttonName(button), 13f), LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(action, LinearLayout.LayoutParams(0, dp(44), 1f))
        return row
    }

    private fun change(setting: GamepadSetting, direction: Int) {
        mapper.config = mapper.config.adjusted(setting, direction)
        AppPreferences.gamepadConfig = mapper.config.encode()
        valueViews[setting]?.text = mapper.config.display(setting)
    }

    /** A pop-out list of everything this button can do, the current choice ticked. */
    private fun chooseAction(button: GamepadButton) {
        val actions = GamepadAction.values()
        val current = actions.indexOf(mapper.bindings.actionFor(button))
        AlertDialog.Builder(activity)
            .setTitle(buttonName(button))
            .setSingleChoiceItems(actions.map { it.label }.toTypedArray(), current) { picker, which ->
                mapper.bindings = mapper.bindings.with(button, actions[which])
                AppPreferences.gamepadBindings = mapper.bindings.encode()
                refreshButtons()
                picker.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { NightMode.apply(it) }
    }

    private fun reset() {
        mapper.config = GamepadConfig()
        mapper.bindings = GamepadBindings.default()
        AppPreferences.gamepadConfig = null
        AppPreferences.gamepadBindings = null
        valueViews.forEach { (s, v) -> v.text = mapper.config.display(s) }
        refreshButtons()
        // The Reset button closes the dialog; show it again with the defaults so the change is visible.
        show()
    }

    private fun refreshButtons() {
        buttonViews.forEach { (b, v) -> v.text = mapper.bindings.actionFor(b).label }
    }

    private fun buttonName(b: GamepadButton) = when (b) {
        GamepadButton.DPAD_UP -> "D-pad up"
        GamepadButton.DPAD_DOWN -> "D-pad down"
        GamepadButton.DPAD_LEFT -> "D-pad left"
        GamepadButton.DPAD_RIGHT -> "D-pad right"
        GamepadButton.L3 -> "Left stick click"
        GamepadButton.R3 -> "Right stick click"
        else -> b.name
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun step(text: String, onClick: () -> Unit) = Button(activity).apply {
        this.text = text
        minWidth = 0
        minimumWidth = 0
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.bg_pill_container)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
