package app.wayfinder

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/**
 * b18g (GitHub #49): a screen's whole size in px as the DISPLAY has it, never a configuration's max bounds. Every
 * screen-size read goes through here: the Touch pointer and Direct, Recents gestures, the swipe combos, the
 * second-screen cover test, the quick panel's form, the deck beside a game, the move animation's fallback.
 *
 * Why: on Android 12+, a `Display` got through a context OF THAT DISPLAY carries the context's Resources, and its
 * `getRealSize` / `getRealMetrics` then answer that configuration's `maxBounds` (AOSP
 * `DisplayManager.getOrCreateDisplayLocked` + `Display.shouldReportMaxBounds`). This service's context is display 0's,
 * and its configuration is the PROCESS's, which follows the screen holding the focus (Wayfinder Keyboard's IME window,
 * same process, moves there). With the bottom screen focused, display 0 read 1240×1080: the pointer stopped at x 1239
 * of 1920. `currentWindowMetrics` of a display context reads the same process configuration. A `Display` asked for
 * through a context of ANOTHER display carries no Resources and answers its own logical size.
 */
object ScreenSize {
    /** A size in px (the JVM-tested parts use it: no android.graphics.Point there). */
    data class Px(val w: Int, val h: Int) {
        val ok get() = w > 0 && h > 0
        override fun toString() = "${w}x$h"
    }

    /** [displayId]'s whole logical size in its current rotation; null if there's no such display. Any thread.
     *  Never `ctx.display`: it throws on a service. */
    fun real(ctx: Context, displayId: Int): Point? {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        val own = dm.getDisplay(displayId) ?: return null
        val all = runCatching { dm.displays.toList() }.getOrDefault(emptyList())
        val other = via(all.map { it.displayId }, displayId)?.let { id -> all.firstOrNull { it.displayId == id } }
        val plain = other?.let { o ->
            runCatching { ctx.createDisplayContext(o).getSystemService(DisplayManager::class.java)?.getDisplay(displayId) }.getOrNull()
        }
        val px = pick(plain?.let { runCatching { realSize(it) }.getOrNull() }, realSize(own)) ?: return null
        return Point(px.w, px.h)
    }

    /** The self-check line, e.g. "plain 1920x1080 · getRealSize 1240x1080 · mode 1920x1080": [real]'s answer, the old
     *  read (the Display that carries [ctx]'s Resources) and the panel's mode turned to the current rotation (the
     *  cross-check, should the first answer ever look wrong). */
    fun report(ctx: Context, displayId: Int, real: Point?): String {
        val d = runCatching { ctx.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) }.getOrNull()
        val old = d?.let { runCatching { realSize(it) }.getOrNull() }
        val mode = d?.let { runCatching { oriented(it.mode.physicalWidth, it.mode.physicalHeight, it.rotation) }.getOrNull() }
        return "plain ${real?.let { Px(it.x, it.y) }} · getRealSize $old · mode $mode"
    }

    /** The display to ask through: any other one. Null when [id] is alone; its own Display is then right by
     *  construction (the configuration can only be that display's). */
    internal fun via(ids: List<Int>, id: Int): Int? = ids.firstOrNull { it != id }

    /** The first usable answer: the plain one, else the display's own read (never worse than before b18g). */
    internal fun pick(plain: Px?, own: Px?): Px? = plain?.takeIf { it.ok } ?: own?.takeIf { it.ok }

    /** A mode's panel size (the panel's natural orientation) turned to [rotation]: swapped at 90° and 270°. */
    internal fun oriented(w: Int, h: Int, rotation: Int): Px =
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) Px(h, w) else Px(w, h)

    private fun realSize(d: Display): Px = Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }.let { Px(it.x, it.y) }
}
