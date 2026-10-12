package app.wayfinder

/**
 * b18i (D1, option C; 2026-10-10): did the game pause itself while Game controls was open? Covered by the page, some
 * games pause (Eden, GameNative: device run g1, OBS-1) and stay paused once it closes, waiting in their own menu; RetroArch and
 * PPSSPP go on. Fed one look a second of the root helper's frame count for the game's screen ([FpsSampler]: frames
 * SurfaceFlinger presented in the last second for the app's busiest layer; 0 = nothing drawn, the counter's "idle"; -1 = not
 * counted). Pure (D1RulesTest); ForegroundAppService feeds it and says the pill.
 *
 * - While the page is open ([look]): the game ran, then stopped (a look with none since). "Ran" = it was drawing steadily: a
 *   look with at least [steadyFor] frames (the first one also covers the second before the page came), or frames in two looks
 *   in a row. b18i (review): a few frames were taken for a game running — an app that draws only when something changes (a
 *   menu, a visual novel, a turn-based game) drew them for the last input, then nothing: "paused" when nothing had paused.
 * - The page closed ([close]): nothing to say unless it ran and then stopped.
 * - After ([after]): a game that draws again went on by itself (or was only hidden by the page) — nothing to say; still nothing
 *   drawn after [AFTER] looks (the second one counts a whole second after the close) — it paused itself: the pill.
 * - b18j: the looks count the game's own surface (the layer that drew the most when the watch began, [LayerPick]), not the app's
 *   busiest layer. Any frame after the close is still "draws again" ([after]).
 *
 * [hz]: the game's screen's refresh rate when the page opened.
 */
internal class GamePauseWatch(hz: Float = 60f) {
    private val steady = steadyFor(hz)
    private var ran = false
    private var stopped = false
    private var closed = false
    private var idleAfter = 0
    /** The last look while open had frames (two in a row: it draws on its own, not once for an input). */
    private var drew = false

    /** A look while the page is open: [frames] presented in the last second (-1: not counted, ignored). */
    fun look(frames: Int) {
        if (closed || frames < 0) return
        if (frames > 0) {
            if (frames >= steady || drew) ran = true
            stopped = false; drew = true
        } else {
            if (ran) stopped = true
            drew = false
        }
    }

    /** The page closed. True: the game ran, then stopped while it was open — the next looks tell whether it paused itself. */
    fun close(): Boolean { closed = true; return ran && stopped }

    /** A look after the close. True: still nothing drawn after [AFTER] looks — it paused itself (say so). False: it draws again,
     *  can't be counted any more, or there was nothing to judge — nothing to say. Null: look again.
     *  b18j (review): any frame is "draws again", as in b18i. An app that ran and then drew nothing while covered may redraw
     *  once as it resumes (1–3 frames: a View-based menu or library, whose kept layer is its own window, or a game that draws
     *  only on change) and then draw nothing because nothing changes. A few frames looked at again gave 2, 0, 0 → the pill,
     *  and nothing had paused. Eden's pill comes from the count of its own game surface (0, 0), not from this. */
    fun after(frames: Int): Boolean? = when {
        !closed || !(ran && stopped) -> false
        frames != 0 -> false
        ++idleAfter >= AFTER -> true
        else -> null
    }

    companion object {
        /** Looks with nothing drawn after the close: the first one still counts the second before it, page included. */
        const val AFTER = 2

        /** The frames in one look that say "drawing steadily": about half the screen's refresh rate (0.45: a 30 fps game shows
         *  28–31 in a second), the rate taken at most 60 Hz. At AYN's 120 Hz (its switch fixes the screen at 120) a game at 30
         *  fps — many Switch games in Eden — is still a game running; an animation needs a quarter of a second of 120 Hz
         *  frames, right before Home + X (which the app never gets), to reach it. A rate unknown (0, not a number): 60 Hz. */
        fun steadyFor(hz: Float): Int {
            val rate = if (hz.isNaN() || hz < 1f) 60f else minOf(hz, 60f)
            return kotlin.math.ceil(rate * 0.45f).toInt().coerceAtLeast(1)
        }
    }
}
