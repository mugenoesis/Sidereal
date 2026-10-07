package io.github.mugenoesis.sidereal.display

import android.app.Dialog
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.Window
import android.view.WindowManager
import io.github.mugenoesis.sidereal.AppPreferences

/**
 * Red-only display for astrophotography: night vision takes 20-30 minutes
 * to develop and any green or blue light resets it. Applied as one colour
 * filter on a window's whole view hierarchy (a hardware layer on the
 * decor view) rather than by theming views one at a time, so every view -
 * trays, dialogs, the media library, even the live video preview - is
 * covered, including ones added later. Luminance becomes the red level,
 * so contrast survives and the video still reads as an image.
 *
 * Windows are separate: call [apply] for each activity and each dialog.
 */
object NightMode {

    private const val R_WEIGHT = 0.299f
    private const val G_WEIGHT = 0.587f
    private const val B_WEIGHT = 0.114f
    private const val NIGHT_BRIGHTNESS = 0.02f
    private const val SYSTEM_BRIGHTNESS = -1f // WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** What a pixel of this colour displays as in night mode: all light on the red channel. */
    fun toRed(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val level = Math.round(R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b).coerceIn(0, 255)
        return Triple(level, 0, 0)
    }

    /** 4x5 Android ColorMatrix: red = luminance, green and blue zeroed, alpha untouched. */
    fun colorMatrix(): FloatArray = floatArrayOf(
        R_WEIGHT, G_WEIGHT, B_WEIGHT, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Window brightness to request: dim at night, or the system's own setting when off. */
    fun screenBrightness(enabled: Boolean): Float = if (enabled) NIGHT_BRIGHTNESS else SYSTEM_BRIGHTNESS

    var enabled: Boolean
        get() = AppPreferences.nightMode
        set(value) { AppPreferences.nightMode = value }

    /** Applies (or removes) the red filter and dimming on [window] according to [on]. */
    fun apply(window: Window, on: Boolean = enabled) {
        val decor: View = window.decorView
        if (on) {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(colorMatrix())) }
            decor.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            decor.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        val attrs: WindowManager.LayoutParams = window.attributes
        attrs.screenBrightness = screenBrightness(on)
        window.attributes = attrs
    }

    /** Dialogs get their own window, so they miss the activity's filter - call this after show(). */
    fun apply(dialog: Dialog) = dialog.window?.let { apply(it) }
}
