package app.wayfinder.deck

import android.content.Context
import android.media.AudioManager
import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.wayfinder.InputMonitor
import app.wayfinder.PServiceBridge

/**
 * App side of "send keys to the game": every call becomes one command line for the
 * root input helper ([app.wayfinder.RootInjector]), which injects it. Keys go
 * to [targetDisplay] — the screen the game is on, i.e. the one the deck is NOT on.
 */
object VirtualInput {
    /** The game's screen (the other one from the deck). */
    @Volatile var targetDisplay: Int = android.view.Display.DEFAULT_DISPLAY

    fun key(code: Int, down: Boolean, meta: Int = 0) =
        InputMonitor.send("K $code ${if (down) 1 else 0} $meta $targetDisplay")

    fun tap(code: Int, meta: Int = 0) { key(code, true, meta); key(code, false, meta) }

    /** Type [text] as key presses (whatever the virtual keyboard map can express). */
    fun type(text: String) {
        // char by char: one character the map can't express (é, an emoji) made the WHOLE text
        // type nothing (review 2026-09-25) — now only that character is skipped
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (c in text) {
            val events = map.getEvents(charArrayOf(c)) ?: continue
            for (e in events) key(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.metaState)
        }
    }

    fun mouseMove(dx: Int, dy: Int) { if (dx != 0 || dy != 0) InputMonitor.send("M $dx $dy") }
    fun mouseButton(button: Int, down: Boolean) = InputMonitor.send("B $button ${if (down) 1 else 0}")
    fun scroll(vertical: Int, horizontal: Int = 0) = InputMonitor.send("W $vertical $horizontal")
    /** The virtual mouse goes away (and with it the cursor). */
    fun releaseMouse() = InputMonitor.send("R")

    // ── volume / brightness (no key injection needed) ─────────────────────
    fun volume(ctx: Context, direction: Int) {
        ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(
            AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /**
     * Per-screen brightness, one eye-scale step — the same per-display control as the
     * Hub sliders and Home + R2 / L2. (It used to write AYN's
     * `dual_screen_brightness_level`, which is AYN's "both screens" slider.)
     */
    fun brightness(ctx: Context, top: Boolean, dir: Int) = Thread {
        val d = if (top) 0 else app.wayfinder.ForegroundAppService.availableDisplayIds.firstOrNull { it != 0 } ?: return@Thread
        val cur = app.wayfinder.ScreenLevels.get(ctx, d) ?: return@Thread
        app.wayfinder.ScreenLevels.set(ctx, d, app.wayfinder.ScreenLevels.step(cur, dir))
    }.apply { isDaemon = true }.start()
}
