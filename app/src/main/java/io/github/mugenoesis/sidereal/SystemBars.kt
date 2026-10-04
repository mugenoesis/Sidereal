package io.github.mugenoesis.sidereal

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge is enforced for apps targeting API 35+ (and can't be opted
 * out of from API 36): the window always draws under the status bar,
 * navigation bar and display cutout. Neither layout handled insets back
 * when the target was 33, so without this the control strip and readouts
 * end up underneath system UI.
 *
 * [applyInsets] pads the activity's content view by whatever system UI is
 * actually showing, so it works the same with the bars visible or hidden.
 * [immersive] additionally hides the bars (swipe from the edge to bring
 * them back transiently), which is what a full-screen camera view wants.
 */
object SystemBars {
    fun applyInsets(activity: Activity, immersive: Boolean) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val content = activity.findViewById<View>(android.R.id.content)
        if (immersive) content.setBackgroundColor(Color.BLACK)

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (immersive) {
            WindowInsetsControllerCompat(window, content).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
