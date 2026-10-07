package io.github.mugenoesis.sidereal.wear

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.wearprotocol.WatchDisplay
import io.github.mugenoesis.sidereal.wearprotocol.WearAck
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand
import io.github.mugenoesis.sidereal.wearprotocol.WearStatus

/**
 * The wrist remote: live view (when switched on), a status line, a shutter, photo/video and live-view buttons,
 * and drag-to-aim on the picture. All decisions about wording and thresholds are in [WatchDisplay]; this only
 * lays them out. The phone must have Sidereal open - the watch says so when it hears nothing.
 */
class WatchActivity : ComponentActivity() {

    private lateinit var link: WatchLink
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var live: ImageView
    private lateinit var headline: TextView
    private lateinit var battery: TextView
    private lateinit var toast: TextView
    private lateinit var shutter: Button
    private lateinit var mode: Button
    private lateinit var liveToggle: Button
    private lateinit var join: Button
    private lateinit var openPhone: Button

    private var status: WearStatus? = null
    private var liveOn = false
    private var lastGimbalSentAt = 0L

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        live = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        headline = label(15f, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        battery = label(11f, Typeface.NORMAL).apply { gravity = Gravity.CENTER; setTextColor(0xFFB0B0B0.toInt()) }
        toast = label(12f, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB74D.toInt())
            visibility = View.GONE
        }
        shutter = roundButton("Photo", 64).apply { setOnClickListener { onShutter() } }
        mode = roundButton("Mode", 44).apply { setOnClickListener { link.send(WearCommand.ToggleMode) } }
        liveToggle = roundButton("Live", 44).apply { setOnClickListener { setLive(!liveOn) } }
        join = Button(this).apply {
            text = "Join Osmo WiFi"
            textSize = 12f
            visibility = View.GONE
            setOnClickListener { link.send(WearCommand.ConnectOsmo) }
        }

        openPhone = Button(this).apply {
            text = "Open on phone"
            textSize = 12f
            visibility = View.GONE
            setOnClickListener {
                // The answer goes on the button itself - a toast would sit underneath it.
                say("Opening…")
                link.openOnPhone { ok -> say(if (ok) "Sent - check phone" else "Can't reach phone") }
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(mode, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            addView(shutter, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(liveToggle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(headline)
            addView(battery)
            addView(toast)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(live, FrameLayout.LayoutParams(-1, -1))
            // Round screens clip the corners, so the controls sit inside a generous inset.
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(26); marginStart = dp(30); marginEnd = dp(30) })
            // Nudged up from dead centre so they clear the shutter button below.
            addView(join, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(16) })
            addView(openPhone, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(16) })
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
        }
        setContentView(root)

        live.setOnTouchListener(dragToAim())

        link = WatchLink(
            context = this,
            scope = lifecycleScope,
            onStatus = { status = it; link.markStatusReceived(); render() },
            onAck = { ack -> onAck(ack) },
            onFrame = { bitmap -> live.setImageBitmap(bitmap) }
        )
    }

    override fun onStart() {
        super.onStart()
        active = this
        link.start()
        handler.post(refresh)
    }

    override fun onStop() {
        super.onStop()
        if (active === this) active = null
        if (liveOn) setLive(false)
        link.send(WearCommand.Gimbal(0f, 0f))
        link.stop()
        handler.removeCallbacksAndMessages(null)
    }

    private fun onShutter() {
        link.send(WatchDisplay.shutterCommand(status))
    }

    private fun setLive(on: Boolean) {
        liveOn = on
        link.send(WearCommand.LiveView(on))
        if (!on) live.setImageDrawable(null)
        render()
    }

    private fun onAck(ack: WearAck) {
        vibrate(if (ack.ok) longArrayOf(0, 30) else longArrayOf(0, 40, 60, 40, 60, 40))
        WatchDisplay.ackText(ack)?.let { showToast(it) }
    }

    private fun say(text: String) {
        openPhone.text = text
        handler.removeCallbacks(restoreOpenPhone)
        handler.postDelayed(restoreOpenPhone, 2500)
    }

    private val restoreOpenPhone = Runnable { openPhone.text = "Open on phone" }

    private fun showToast(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2500)
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    private fun render() {
        val age = if (link.lastStatusAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - link.lastStatusAt
        val s = status
        headline.text = WatchDisplay.headline(s, age)
        battery.text = if (age <= WatchDisplay.STALE_MS) "Osmo battery ${WatchDisplay.battery(s)}" else ""
        val canShoot = WatchDisplay.canShoot(s, age)
        shutter.text = WatchDisplay.shutterLabel(s)
        shutter.isEnabled = canShoot
        mode.isEnabled = canShoot && s?.recording != true
        liveToggle.text = if (liveOn) "Live ●" else "Live"
        openPhone.visibility = if (WatchDisplay.showOpenPhone(s, age)) View.VISIBLE else View.GONE
        join.visibility = if (s != null && age <= WatchDisplay.STALE_MS && !s.phoneOnOsmo) View.VISIBLE else View.GONE
        val canWatch = WatchDisplay.canWatchLive(s, age)
        liveToggle.isEnabled = canWatch || liveOn
        liveToggle.alpha = if (liveToggle.isEnabled) 1f else 0.4f
        shutter.alpha = if (canShoot) 1f else 0.4f
        mode.alpha = if (mode.isEnabled) 1f else 0.4f
    }

    /** Drag on the picture to aim the gimbal; lift to stop; double tap to recentre. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragToAim(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var lastTap = 0L
        return View.OnTouchListener { v, event ->
            val radius = v.width / 3f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true) // this drag is ours, not a system gesture
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGimbalSentAt >= 100) {
                        lastGimbalSentAt = now
                        link.send(WatchDisplay.dragToGimbal(event.x - downX, event.y - downY, radius))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    link.send(WearCommand.Gimbal(0f, 0f))
                    val moved = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                    if (event.actionMasked == MotionEvent.ACTION_UP && moved < dp(8)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < 350) link.send(WearCommand.Recenter)
                        lastTap = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Hooks for the debug harness (wear/src/debug) to put the screen into a state without a phone. */
    internal fun debugShow(status: WearStatus?, liveOn: Boolean?, ack: WearAck?, frame: android.graphics.Bitmap?) {
        this.status = status
        link.markStatusReceived()
        liveOn?.let { this.liveOn = it }
        ack?.let { onAck(it) }
        frame?.let { live.setImageBitmap(it) }
        render()
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun label(sp: Float, style: Int) = TextView(this).apply {
        textSize = sp
        setTextColor(Color.WHITE)
        setTypeface(typeface, style)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun roundButton(text: String, sizeDp: Int) = Button(this).apply {
        this.text = text
        textSize = if (sizeDp > 50) 12f else 10f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minHeight = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC333333.toInt())
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile internal var active: WatchActivity? = null
    }
}
