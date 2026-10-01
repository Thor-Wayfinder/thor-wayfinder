package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * The discreet on-screen cue for controller focus: a small glass pill at the
 * top of a screen ("🎮 Controller → Top screen"). Never focusable or touchable, so
 * it can't itself steal input. [show] flashes it; [showHint]/[hideHint] keep it up
 * while R3 is held (the "which screen has the controller right now" cue).
 */
class FocusCue(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())

    /** A pill window and the moment it must be gone at the latest. */
    private class Pill(val wm: WindowManager, val view: View, var deadline: Long, val line: Boolean = false)

    /** EVERY pill window this cue has put up — an entry leaves this list only once its window
     *  is really removed. Pills stayed on screen for good twice (2026-09-24): an entry was
     *  dropped first, then the removal was skipped ("not attached yet") and nothing held a
     *  reference any more. Now removal never depends on attachment, and [sweep] removes any
     *  pill past its deadline whatever happened to the normal path. */
    private val shown = mutableListOf<Pill>()
    private val hideRunnable = Runnable { dismiss() }
    private val sweep: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            shown.filter { now > it.deadline }.forEach { remove(it) }
            if (shown.isNotEmpty()) main.postDelayed(this, 1000)
        }
    }

    fun show(displayId: Int, text: String, holdMs: Long = 1300) {
        main.post {
            put(displayId, Content.Line(text), holdMs)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, holdMs)
        }
    }

    /** A held-button hint: [title] lines, then a small table of (button, what it does) —
     *  the button in bold, three per row, words instead of arrows and emoji (the
     *  symbols made the hints hard to read, 2026-09-24). Kept up while the button is held. */
    fun showHint(displayId: Int, title: List<String>, cells: List<Pair<String, String>>) = main.post {
        // Normally hidden when the button is let go — but never left up forever if that
        // release is missed (service reconnect, a key eaten elsewhere).
        put(displayId, Content.Table(title, cells), 8000)
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 8000)
    }

    /** The same table, shown for [holdMs] (e.g. the Recents help). [at]: 0 top centre · 1 top left ·
     *  2 top right · 3 bottom left · 4 bottom right. [compact]: one slim row. */
    fun showTable(displayId: Int, title: List<String>, cells: List<Pair<String, String>>, holdMs: Long,
                  at: Int = 0, compact: Boolean = false) = main.post {
        put(displayId, Content.Table(title, cells, at, compact), holdMs)
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, holdMs)
    }

    private sealed class Content {
        class Line(val text: String) : Content()
        class Table(val title: List<String>, val cells: List<Pair<String, String>>, val at: Int = 0, val compact: Boolean = false) : Content()
    }

    fun hideHint() = main.post { dismiss(tablesOnly = true) }

    /** Remove everything at once (the service is going away / being re-created). */
    fun dismissAll() = main.post { main.removeCallbacks(hideRunnable); dismissNow() }

    private fun remove(p: Pill) {
        // removeViewImmediate works whether or not the view got attached yet; it throws only
        // if the window is already gone — either way, it's gone.
        try { p.wm.removeViewImmediate(p.view) } catch (_: Exception) {}
        shown.remove(p)
    }

    private fun put(displayId: Int, content: Content, holdMs: Long) {
        dismissNow()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density
        val dark = when (AppSettings.themeMode) {
            ThemeMode.DARK, ThemeMode.BLACK -> true; ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val ink = if (dark) 0xFFFFFFFF.toInt() else 0xFF0A0C14.toInt()
        val soft = if (dark) 0xCCFFFFFF.toInt() else 0xB30A0C14.toInt()
        fun label(t: CharSequence, sp: Float, color: Int = ink) = TextView(ctx).apply {
            // 1.4 (GitHub #66): larger font sizes up to 1.1× — bigger overflowed the screen; cells wrap below
            text = t; setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * ctx.resources.configuration.fontScale.coerceIn(0.85f, 1.1f))
            setLineSpacing(0f, 1.15f)
        }
        // one line = a pill; a table = a rounded card (a pill's round ends cut into several lines)
        val radius = if (content is Content.Line || (content is Content.Table && content.compact && content.title.isEmpty())) 999f else 22 * dp
        val pill: View = when (content) {
            is Content.Line -> label(content.text, 16f)
            is Content.Table -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                content.title.forEachIndexed { i, t ->
                    addView(label(t, if (i == 0) 17f else 14f, if (i == 0) ink else soft).apply {
                        if (i == 0) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
                if (content.cells.isNotEmpty()) addView(TableLayout(ctx).apply {
                    setPadding(0, if (content.compact) 0 else (8 * dp).toInt(), 0, 0)
                    isShrinkAllColumns = true   // cells wrap on a narrow screen or a large font — nothing cut (1.4, #66)
                    val cells = content.cells.map { (btn, what) ->
                        val t = android.text.SpannableStringBuilder().apply {
                            append(btn, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0)
                            if (what.isNotEmpty()) { append(if (content.compact) " " else "  "); append(what) }
                        }
                        label(t, if (content.compact) 14f else 16f).apply {
                            setPadding(0, (3 * dp).toInt(), ((if (content.compact) 14 else 26) * dp).toInt(), (3 * dp).toInt()) }
                    }
                    // 1.4 (GitHub #66 + the bottom screen): as many columns (3 → 1) as the cells' real widths fit on THIS
                    // screen — a fixed 3 squeezed the last column to a word per line on the narrower bottom screen
                    val room = ctx.resources.displayMetrics.widthPixels - (2 * 16 + 40) * dp
                    val widths = cells.map { it.measure(0, 0); it.measuredWidth }
                    fun fits(c: Int) = (0 until c).sumOf { col -> widths.filterIndexed { i, _ -> i % c == col }.maxOrNull() ?: 0 } <= room
                    val tall = cells.sumOf { it.measuredHeight } > ctx.resources.displayMetrics.heightPixels * 0.7f
                    val cols = if (content.compact) cells.size else listOf(3, 2).firstOrNull { fits(it) } ?: if (tall) 2 else 1
                    for (row in cells.chunked(cols)) addView(TableRow(ctx).apply { row.forEach { addView(it) } })
                })
            }
        }
        pill.apply {
            val slimPad = content is Content.Table && content.compact
            setPadding((20 * dp).toInt(), ((if (slimPad) 4 else 10) * dp).toInt(), (20 * dp).toInt(), ((if (slimPad) 6 else 12) * dp).toInt())
            // Glass = a frosted tint + a specular rim (public APIs only).
            val frost = GradientDrawable().apply {
                cornerRadius = radius
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = if (dark) intArrayOf(0x2EFFFFFF, 0x0FFFFFFF) else intArrayOf(0xBFFFFFFF.toInt(), 0x8CFFFFFF.toInt())
            }
            val tint = GradientDrawable().apply {   // keeps text legible over busy games
                cornerRadius = radius
                setColor(if (dark) 0xF20B0D18.toInt() else 0xF2F3F4FA.toInt())   // 95 %: text behind bled through (2026-09-24)
            }
            background = android.graphics.drawable.LayerDrawable(arrayOf(tint, frost, RimDrawable(radius, dp, dark)))
            alpha = 0f; translationY = -8 * dp
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            val at = (content as? Content.Table)?.at ?: 0
            gravity = when (at) {
                1 -> Gravity.TOP or Gravity.START; 2 -> Gravity.TOP or Gravity.END
                3 -> Gravity.BOTTOM or Gravity.START; 4 -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            // bottom corners sit above Recents' own row of buttons (Screenshot, Clear all)
            // 1.3.2: a one-line hint hugs the top edge (22 dp down it sat on Recents' cards)
            val slim = (content as? Content.Table)?.compact == true
            x = if (at == 0) 0 else (22 * dp).toInt(); y = ((if (at >= 3) 96 else if (slim) 6 else 22) * dp).toInt()
        }
        try {
            wm.addView(pill, lp)
            pill.animate().alpha(1f).translationY(0f).setDuration(160).start()
            shown += Pill(wm, pill, android.os.SystemClock.uptimeMillis() + holdMs + 1500, line = content is Content.Line)
            main.removeCallbacks(sweep); main.postDelayed(sweep, 1000)
        } catch (_: Exception) {}
    }

    private fun dismiss(tablesOnly: Boolean = false) {
        for (p in shown.toList()) {
            if (tablesOnly && p.line) continue   // 1.4: a combo's own message outlives the list it came from
            // Fade, then remove. The pill stays in [shown] until really removed; a cancelled
            // fade (no end action) is covered by the timer, and the timer by [sweep].
            p.deadline = minOf(p.deadline, android.os.SystemClock.uptimeMillis() + 600)
            p.view.animate().alpha(0f).setDuration(220).withEndAction { remove(p) }.start()
            main.postDelayed({ remove(p) }, 400)
        }
    }

    private fun dismissNow() {
        for (p in shown.toList()) remove(p)
    }
}

/** Specular rim: a hairline that's bright at the top-left and fades toward the bottom-right. */
private class RimDrawable(private val radius: Float, private val dp: Float, private val dark: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.2f * dp
    }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        paint.shader = android.graphics.LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(if (dark) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt(), 0x00FFFFFF, if (dark) 0x40FFFFFF else 0x80FFFFFF.toInt()),
            floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        val h = paint.strokeWidth / 2
        val r = minOf(radius, b.height() / 2f)
        c.drawRoundRect(b.left + h, b.top + h, b.right - h, b.bottom - h, r, r, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
