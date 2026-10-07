package io.github.mugenoesis.sidereal.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        val button = GamepadKeyMap.button(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> mapper.onButton(button, true, event.eventTime)
            KeyEvent.ACTION_UP -> mapper.onButton(button, false, event.eventTime)
            else -> return false
        }
        return true
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

        /** The running instance, for the debug harness to inject synthetic controller events into. */
        @Volatile var active: GamepadInput? = null
    }
}
