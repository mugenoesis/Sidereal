package io.github.mugenoesis.sidereal.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Android side of gamepad support: receives the activity's raw key and motion events, filters for game
 * controllers, translates them ([GamepadKeyMap], [GamepadLayout]) and feeds the pure [GamepadMapper].
 * Also keeps the mapper's clock running so held buttons repeat, and tells it when a controller disappears.
 *
 * Handled events are consumed, so a pad's B button does not also act as "back".
 */
class GamepadInput(
    context: Context,
    private val mapper: GamepadMapper,
    /** Called with every clock tick - for things that need steady driving while a stick is held, like zoom. */
    private val onTick: () -> Unit = {}
) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())
    private val layouts = HashMap<Int, GamepadLayout>()
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            mapper.tick(SystemClock.uptimeMillis())
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        inputManager.registerInputDeviceListener(this, handler)
        handler.post(ticker)
        active = this
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        inputManager.unregisterInputDeviceListener(this)
        mapper.onDisconnected()
        if (active === this) active = null
    }

    /** True if [event] came from a game controller and was handled here. */
    fun handleMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) return false
        val layout = layouts.getOrPut(event.deviceId) {
            GamepadLayout.pick(event.device?.motionRanges?.map { it.axis }?.toSet().orEmpty())
        }
        if (probeOnly) {
            logAxes(event, layout)
            return true
        }
        val now = event.eventTime
        mapper.onAxis(GamepadAxis.LEFT_X, event.getAxisValue(layout.leftX), now)
        mapper.onAxis(GamepadAxis.LEFT_Y, event.getAxisValue(layout.leftY), now)
        mapper.onAxis(GamepadAxis.RIGHT_X, event.getAxisValue(layout.rightX), now)
        mapper.onAxis(GamepadAxis.RIGHT_Y, event.getAxisValue(layout.rightY), now)
        mapper.onAxis(GamepadAxis.L2, event.getAxisValue(layout.leftTrigger), now)
        mapper.onAxis(GamepadAxis.R2, event.getAxisValue(layout.rightTrigger), now)
        mapper.onAxis(GamepadAxis.HAT_X, event.getAxisValue(layout.hatX), now)
        mapper.onAxis(GamepadAxis.HAT_Y, event.getAxisValue(layout.hatY), now)
        return true
    }

    fun handleKey(event: KeyEvent): Boolean {
        val fromPad = event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD) != 0
        if (!fromPad) return false
        val button = GamepadKeyMap.button(event.keyCode)
        if (probeOnly) {
            if (event.repeatCount == 0 && event.action != KeyEvent.ACTION_MULTIPLE) {
                Log.i(PROBE_TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} -> ${button ?: "(unmapped)"} from ${event.device?.name}")
            }
            return true
        }
        if (button == null) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> mapper.onButton(button, true, event.eventTime)
            KeyEvent.ACTION_UP -> mapper.onButton(button, false, event.eventTime)
            else -> return false
        }
        return true
    }

    private val lastLogged = HashMap<String, Float>()

    /** Probe mode: report each axis when it moves meaningfully, by the name the mapper would give it. */
    private fun logAxes(event: MotionEvent, layout: GamepadLayout) {
        val axes = listOf(
            "LEFT_X" to layout.leftX, "LEFT_Y" to layout.leftY, "RIGHT_X" to layout.rightX, "RIGHT_Y" to layout.rightY,
            "L2" to layout.leftTrigger, "R2" to layout.rightTrigger, "HAT_X" to layout.hatX, "HAT_Y" to layout.hatY
        )
        for ((name, axis) in axes) {
            val v = event.getAxisValue(axis)
            val last = lastLogged[name] ?: 0f
            if (Math.abs(v - last) >= 0.25f || (v == 0f && last != 0f)) {
                lastLogged[name] = v
                Log.i(PROBE_TAG, "axis $name = ${"%.2f".format(v)} (${MotionEvent.axisToString(axis)})")
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) {
        layouts.remove(deviceId)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (layouts.remove(deviceId) != null) mapper.onDisconnected()
    }

    companion object {
        private const val TICK_MS = 50L
        private const val PROBE_TAG = "GamepadProbe"

        /**
         * Diagnostic mode: log what the controller sends (button and axis names as the app understands them) and do
         * NOT act on it - for checking a new pad without the shutter or record button firing on a live camera.
         * It switches itself off when [probeUntilMs] passes, so it can never be left on by mistake.
         */
        @Volatile var probeUntilMs = 0L

        val probeOnly: Boolean get() = SystemClock.elapsedRealtime() < probeUntilMs

        /** The running instance, for the debug harness to inject synthetic controller events into. */
        @Volatile var active: GamepadInput? = null
    }
}
