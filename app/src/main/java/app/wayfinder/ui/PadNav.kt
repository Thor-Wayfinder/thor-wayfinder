package app.wayfinder.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * dev build 16 (WP7): the controller's left stick and D-pad (HAT axes) as D-pad keys for ONE window — MainActivity's
 * adapter, moved here unchanged so the quick panel and the companion screen move the same way (they had Android's own
 * one-latch adapter: a second controller's idle reports unlatched a held direction, options were skipped). Latched per
 * controller, pressed at once, again after 400 ms, then every 120 ms (dev build 14 WP1); a press leaves touch mode first
 * (dev build 14: synthesized keys don't). [focusView] = the view given the focus when leaving touch mode (the window's
 * content), [send] = the window's key dispatch, [paused] = something else takes the moves (the test page, Wayfinder's
 * keyboard), [touchMode] = whether the window is in touch mode.
 */
class PadNav(
    private val focusView: () -> View?,
    private val send: (KeyEvent) -> Boolean,
    private val paused: () -> Boolean = { false },
    private val touchMode: () -> Boolean = { focusView()?.isInTouchMode == true },
) {
    private val held = HashMap<Int, Int>()                 // device id → the direction it holds (KEYCODE_DPAD_*)
    private val repeats = HashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        /** Pure (JVM test): the D-pad key for a HAT ([hx], [hy]) and left stick ([x], [y]) — either past [dead]; up / down
         *  before left / right (a diagonal); 0 = centre. */
        fun direction(hx: Float, hy: Float, x: Float, y: Float, dead: Float = 0.5f): Int = when {
            hy < -dead || y < -dead -> KeyEvent.KEYCODE_DPAD_UP
            hy > dead || y > dead -> KeyEvent.KEYCODE_DPAD_DOWN
            hx < -dead || x < -dead -> KeyEvent.KEYCODE_DPAD_LEFT
            hx > dead || x > dead -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
    }

    /** A joystick move: its direction is pressed / held / let go — true = consumed (Android's own adapter never runs on
     *  it). Anything else: false. */
    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        // The Thor's D-pad reports as HAT axes (MotionEvents), not KEYCODE_DPAD, so Compose never sees it — both the
        // D-pad (hat) and the left stick become D-pad keys
        nav(e.deviceId, direction(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
            e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y)))
        return true
    }

    /** Controller [dev] now holds [key] (0 = none): a new direction is pressed at once, then repeated while held. */
    private fun nav(dev: Int, key: Int) {
        if ((held[dev] ?: 0) == key) return
        repeats.remove(dev)?.let { handler.removeCallbacks(it) }
        if (key == 0) { held.remove(dev); return }
        held[dev] = key
        // dev build 15 (WP2): moving with a controller's D-pad or stick only — its names on the hints too
        app.wayfinder.ButtonNames.inHand(app.wayfinder.ExtPadsCtl.srcOfDevice(InputDevice.getDevice(dev)))
        // device pass (P2-3): a controller drives — the page's legend comes back. The keys made below carry no gamepad source,
        // so the window's own key check (MainActivity.dispatchKeyEvent) never counted the Thor's D-pad (a HAT) or a stick
        InputMode.notePadKey()
        press(dev, key)
        val r = object : Runnable {
            override fun run() {
                // dev build 14 (review): only while this window gets the controller's moves — the test page and Wayfinder's
                // keyboard (it takes every stick move while it shows, and takes no focus) keep its "centre", and the Hub's
                // focus kept moving behind them
                if (paused()) { stop(); return }
                // a controller that went away while held never says "centre"
                if (InputDevice.getDevice(dev) == null) { held.remove(dev); repeats.remove(dev); return }
                if (held[dev] != key) return
                press(dev, key); handler.postDelayed(this, 120)
            }
        }
        repeats[dev] = r; handler.postDelayed(r, 400)
    }

    private fun press(dev: Int, key: Int) {
        // dev build 14: keys made here don't leave touch mode (only Android's own input path does): after a touch — the
        // screen, or a PlayStation pad's touchpad acting as a mouse — no view had focus and the D-pad moved nothing ("menus
        // stuck") until A or B was pressed. Leave it first, as a real D-pad press does.
        if (touchMode()) focusView()?.requestFocusFromTouch()
        val taken = send(KeyEvent(KeyEvent.ACTION_DOWN, key))
        send(KeyEvent(KeyEvent.ACTION_UP, key))
        // WP1: each press as it's made (a repeat too), and whether the window took it (focus moved, or a control used it)
        if (app.wayfinder.BuildConfig.DEBUG) Log.d("ThorNav", "device $dev → key $key ${if (taken) "taken" else "not taken"}")
    }

    /** Nothing repeats behind another window (its "centre" goes there). */
    fun stop() { handler.removeCallbacksAndMessages(null); held.clear(); repeats.clear() }
}
