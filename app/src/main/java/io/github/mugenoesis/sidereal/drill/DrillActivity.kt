package io.github.mugenoesis.sidereal.drill

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import io.github.mugenoesis.sidereal.AppPreferences
import io.github.mugenoesis.sidereal.input.GamepadConfig
import io.github.mugenoesis.sidereal.input.GamepadLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** Reticle drill screen: a translucent window over the live view. Left stick, d-pad or touch aim; A marks; B, Start, Select or Back close it; Y switches the background. Rules are in [DrillEngine]. */
class DrillActivity : ComponentActivity() {

    private lateinit var drill: DrillEngine
    private lateinit var view: DrillView
    private var tones: ToneGenerator? = null

    private var stickX = 0f
    private var stickY = 0f
    private var dpadX = 0
    private var dpadY = 0
    private val dpadHeld = HashSet<Int>()
    private var layout: GamepadLayout? = null
    private var config = GamepadConfig()
    private var openedAt = 0L

    private var lastFrameNs = 0L
    private var recordSaved = false
    private var bestAtStart = 0
    private var newRecord = false

    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNs == 0L) 0f else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameNs = frameTimeNanos
            tick(dt)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        config = GamepadConfig.decode(AppPreferences.gamepadConfig)
        bestAtStart = AppPreferences.drillBest
        // The play area is the real screen's shape, so the drill fills it - over the camera view there is no letterbox to hide.
        val dm = resources.displayMetrics
        val aspect = max(dm.widthPixels, dm.heightPixels).toFloat() / min(dm.widthPixels, dm.heightPixels)
        drill = DrillEngine(width = 900f * aspect, height = 900f)
        tones = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: RuntimeException) { null }
        view = DrillView(this, drill) { AppPreferences.drillBest }
        setContentView(view)
        view.onTouch = ::onTouch
        view.overlay = AppPreferences.drillOverlay
        openedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        lastFrameNs = 0L
        android.view.Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        // Quitting mid-run (B, Back, a phone call) must not lose a good score: it only used to be saved at drill over.
        val result = BestRecord.submit(AppPreferences.drillBest, drill.score)
        if (result.isNewRecord) AppPreferences.drillBest = result.best
        android.view.Choreographer.getInstance().removeFrameCallback(frame)
        tones?.release()
        tones = null
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun tick(dt: Float) {
        val x = (stickX + dpadX).coerceIn(-1f, 1f)
        val y = (stickY + dpadY).coerceIn(-1f, 1f)
        play(drill.step(dt, x, y))
        view.advance(dt)
        view.invalidate()
    }

    private fun play(events: List<DrillEvent>) {
        for (e in events) {
            when (e) {
                is DrillEvent.Cleared -> beep(ToneGenerator.TONE_PROP_BEEP, 70)
                is DrillEvent.Strike -> { beep(ToneGenerator.TONE_SUP_ERROR, 250); view.shake() }
                is DrillEvent.Ended -> onEnded(e.finalPoints)
                DrillEvent.Off -> Unit
            }
        }
    }

    private fun onEnded(score: Int) {
        val result = BestRecord.submit(AppPreferences.drillBest, score)
        newRecord = result.isNewRecord
        if (result.isNewRecord) AppPreferences.drillBest = result.best
        view.newRecord = newRecord
    }

    private fun beep(tone: Int, ms: Int) {
        try { tones?.startTone(tone, ms) } catch (_: RuntimeException) { }
    }

    private fun markOrRestart() {
        if (SystemClock.elapsedRealtime() - openedAt < OPENING_QUIET_MS) return // the A that finished the code is not a shot
        if (drill.state == DrillState.ENDED) {
            drill.restart()
            newRecord = false
            view.newRecord = false
        } else {
            play(drill.mark())
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val l = layout ?: GamepadLayout.pick(event.device?.motionRanges?.map { it.axis }?.toSet().orEmpty()).also { layout = it }
        stickX = shape(event.getAxisValue(l.leftX))
        stickY = shape(event.getAxisValue(l.leftY))
        val hx = event.getAxisValue(l.hatX)
        val hy = event.getAxisValue(l.hatY)
        if (hx != 0f || hy != 0f) { dpadX = sign(hx).toInt(); dpadY = sign(hy).toInt() } else if (dpadHeld.isEmpty()) { dpadX = 0; dpadY = 0 }
        return true
    }

    /** Dead zone then the same response curve as the gimbal stick, so the crosshair feels like the rest of the app. */
    private fun shape(v: Float): Float {
        val m = abs(v).coerceAtMost(1f)
        if (m <= config.deadzone) return 0f
        return sign(v) * ((m - config.deadzone) / (1f - config.deadzone)).pow(config.expo)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> { if (down && event.repeatCount == 0) markOrRestart(); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (down && event.repeatCount == 0) {
                    view.overlay = !view.overlay
                    AppPreferences.drillOverlay = view.overlay
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> {
                if (down && event.repeatCount == 0) finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) dpadHeld += code else dpadHeld -= code
                dpadX = (if (KeyEvent.KEYCODE_DPAD_RIGHT in dpadHeld) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_LEFT in dpadHeld) 1 else 0)
                dpadY = (if (KeyEvent.KEYCODE_DPAD_DOWN in dpadHeld) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_UP in dpadHeld) 1 else 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A finger: the crosshair goes where it touches, and a tap marks there (or restarts after drill over). */
    private fun onTouch(worldX: Float, worldY: Float, isDown: Boolean) {
        drill.aimAt(worldX, worldY)
        if (isDown) markOrRestart()
    }

    private companion object {
        const val OPENING_QUIET_MS = 600L
    }
}
