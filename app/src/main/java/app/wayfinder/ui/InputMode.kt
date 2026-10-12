package app.wayfinder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Build 18 (A0): what is driving Wayfinder right now — a controller or a finger. The Hub feeds it (MainActivity: a gamepad
 * or D-pad key, a touch), and so do the engines (ButtonEngine.onKey, ExtEngine's key path), so the Wayfinder Keyboard knows
 * a controller opened a field ([lastPadKeyAt], KB1) and a page's legend hides after a touch ([lastWasTouch], A14).
 * No pref: it starts as "controller" every launch.
 */
object InputMode {
    /** The last input was a touch (Compose state: what reads it follows). */
    var lastWasTouch by mutableStateOf(false)
    /** SystemClock.uptimeMillis() of the last controller key press (0 = none yet). Any thread. */
    @Volatile var lastPadKeyAt = 0L

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** A controller key was pressed (any thread: the engines call it from their own). */
    fun notePadKey() {
        lastPadKeyAt = android.os.SystemClock.uptimeMillis()
        if (lastWasTouch) onMain { lastWasTouch = false }
    }

    /** The screen was touched (the Hub's window) — [at] = the touch's time (SystemClock.uptimeMillis: the event's eventTime).
     *  Device pass (P2-3): not a touch Wayfinder made itself ([noteSyntheticTouch]). */
    fun noteTouch(at: Long = android.os.SystemClock.uptimeMillis()) {
        if (at in syntheticFrom..syntheticUntil) return
        if (!lastWasTouch) onMain { lastWasTouch = true }
    }

    /** Device pass (P2-3): Wayfinder is about to inject a touch of its own — ForegroundAppService.focusDisplay's focus probe,
     *  a 20 ms tap that moves the display focus (the quick panel opening / closing over the Hub, the controller sent to the
     *  other screen and back). A touch-down from now to [SYNTHETIC_TOUCH_MS] later is that tap, not a finger: the page's
     *  legend stays (it hid with no finger on the screen). Call it right before dispatching the gesture. Any thread. */
    fun noteSyntheticTouch() {
        val now = android.os.SystemClock.uptimeMillis()
        syntheticUntil = now + SYNTHETIC_TOUCH_MS; syntheticFrom = now
    }
    /** How long after [noteSyntheticTouch] a touch-down is still Wayfinder's own: the probe's stroke (20 ms) and the
     *  gesture's dispatch, with room — its safety net gives up at 800 ms. */
    const val SYNTHETIC_TOUCH_MS = 500L
    @Volatile private var syntheticFrom = 0L
    @Volatile private var syntheticUntil = -1L

    /** Compose state is written on the main thread (an engine's thread posts it there). */
    private fun onMain(f: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) f() else main.post(f)
    }
}
