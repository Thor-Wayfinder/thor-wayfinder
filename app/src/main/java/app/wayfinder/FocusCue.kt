package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Home
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import app.wayfinder.ui.ButtonGlyphDrawable
import app.wayfinder.ui.ButtonLetterDrawable
import app.wayfinder.ui.FaceMark
import app.wayfinder.ui.GlassTokens
import app.wayfinder.ui.Radii
import kotlin.math.roundToInt

/**
 * The overlay host: discreet glass cues — a pill ("Controller on the top screen"), a tip, a list kept up while a button is
 * held. Never focusable or touchable, so a cue can't itself steal input.
 *
 * dev build 17 (WP10): the ONE host behind [Overlays]. [CueSlots] decides what each screen
 * shows (one slot per screen: held list > tip > pill — a cue covers another, never destroys it); this class turns its
 * effects into windows, in the theme's colours ([GlassTokens]). [ctlDisplay]: the service's one controllerDisplay();
 * [live]: the displays that exist now.
 */
class FocusCue(private val service: AccessibilityService, private val ctlDisplay: () -> Int,
               private val live: () -> Set<Int>) : Overlays.Host {

    private val main = Handler(Looper.getMainLooper())
    /** What each screen shows — main thread only. */
    private val slots = CueSlots { SystemClock.uptimeMillis() }
    private val prefs by lazy { service.getSharedPreferences("thor_settings", Context.MODE_PRIVATE) }

    /** A cue's window and the moment it must be gone at the latest; [fading] = on its way out. */
    private class Win(val cue: Cue, val wm: WindowManager, val view: View, var deadline: Long, var fading: Boolean = false)

    /** EVERY window this host has put up — an entry leaves this list only once its window is really removed. Pills stayed
     *  on screen for good twice (2026-09-24): an entry was dropped first, then the removal was skipped ("not attached
     *  yet") and nothing held a reference any more. Now removal never depends on attachment, and [sweep] removes any window
     *  past its deadline — or no longer what its screen shows — whatever happened to the normal path. */
    private val shown = mutableListOf<Win>()
    private val sweep: Runnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            shown.filter { now > it.deadline || (!it.fading && slots.visible(it.cue.display) !== it.cue) }.forEach { remove(it) }
            if (shown.isNotEmpty()) main.postDelayed(this, 1000)
        }
    }
    private val tickRunnable = Runnable { dropGone(); apply(slots.tick()) }

    @Volatile override var tipUp = false
        private set

    override fun controllerDisplay(): Int = ctlDisplay()
    override fun liveDisplays(): Set<Int> = live()
    override fun post(c: Cue) {
        main.post {
            if (BuildConfig.DEBUG) android.util.Log.d("ThorCueSlot", "d${c.display} post ${c.kind.name.lowercase()} ${c.key}")
            dropGone(); apply(slots.post(c))
        }
    }
    override fun release(key: String) { main.post { apply(slots.release(key)) } }
    override fun anyButton() { main.post { apply(slots.anyButton()) } }
    /** Review WP10-1: stashed, no screen shows anything — every window goes, the ones fading out too (a cue released just
     *  before, e.g. the Home list by Home + R1's screenshot, was out of the model but still up for 400 ms: in the image —
     *  build 16's dismissAll removed it, GitHub #67). The model is kept for [unstash]; a fading cue is done there. */
    override fun stash() { main.post { apply(slots.stash()); for (w in shown.toList()) remove(w) } }
    override fun unstash() { main.post { apply(slots.unstash()) } }

    /** Remove everything at once (the service is going away / being re-created). */
    fun dismissAll() = main.post {
        main.removeCallbacks(tickRunnable)
        slots.clear(); tipUp = false
        for (w in shown.toList()) remove(w)
    }

    /** A display that went away (undocked): its cues go — the next post / tick. */
    private fun dropGone() {
        val now = runCatching { live() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        for (d in slots.displays()) if (d !in now) apply(slots.displayGone(d))
    }

    private fun apply(effects: List<CueEffect>) {
        for (e in effects) {
            if (BuildConfig.DEBUG) log(e)
            when (e) {
                is CueEffect.Show -> show(e.cue, e.fresh)
                is CueEffect.Hide -> hide(e.cue, e.fade)
                is CueEffect.Seen -> prefs.edit().putBoolean(e.cue.key, true).apply()
                is CueEffect.Drop -> {}
            }
        }
        tipUp = slots.tipUp
        main.removeCallbacks(tickRunnable)
        slots.nextDue()?.let { main.postDelayed(tickRunnable, it.coerceAtLeast(16L)) }
    }

    /** debug builds: `ThorCueSlot d<display> <show|hide|seen|drop> <pill|tip|held> <key> <why|fresh|back>` (S13). */
    private fun log(e: CueEffect) {
        fun c(cue: Cue, what: String) = "d${cue.display} $what ${cue.kind.name.lowercase()} ${cue.key}"
        android.util.Log.d("ThorCueSlot", when (e) {
            is CueEffect.Show -> c(e.cue, "show") + if (e.fresh) " fresh" else " back"
            is CueEffect.Hide -> c(e.cue, "hide") + " ${e.why}"
            is CueEffect.Seen -> c(e.cue, "seen")
            is CueEffect.Drop -> c(e.cue, "drop") + " ${e.why}"
        })
    }

    private fun remove(w: Win) {
        // removeViewImmediate works whether or not the view got attached yet; it throws only
        // if the window is already gone — either way, it's gone.
        try { w.wm.removeViewImmediate(w.view) } catch (_: Exception) {}
        shown.remove(w)
    }

    private fun hide(c: Cue, fade: Boolean) {
        for (w in shown.filter { it.cue === c && !it.fading }) {
            if (!fade) { remove(w); continue }
            // Fade, then remove. The window stays in [shown] until really removed; a cancelled
            // fade (no end action) is covered by the timer, and the timer by [sweep].
            w.fading = true
            w.deadline = minOf(w.deadline, SystemClock.uptimeMillis() + 600)
            w.view.animate().alpha(0f).setDuration(220).withEndAction { remove(w) }.start()
            main.postDelayed({ remove(w) }, 400)
        }
    }

    private fun show(c: Cue, fresh: Boolean) {
        // dev build 16 (theme F): what a pill or a hint says, in its words (device test S8) — debug builds only,
        // once per cue (dev build 17: not again when it comes back from under another one)
        if (BuildConfig.DEBUG && fresh) android.util.Log.d("ThorCue", c.content.logLine())
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(c.display)
            ?: run { apply(slots.displayGone(c.display)); return }
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density
        // b18i (leftovers, GitHub #49's family): the screen's size from ScreenSize, never this context's metrics. A screen's
        // display contexts share one cached ResourcesImpl, which takes the PROCESS configuration's app bounds as its size at
        // each configuration change (AOSP ResourcesManager.applyConfigurationToResourcesLocked); that configuration follows
        // the screen with the focus. So a cue on the top could be laid out for the bottom screen's 1240 px, one on the bottom
        // for 1920 px (QuickPanelWindow's 1.3.2 note saw it there). The density stays the context's (its screen's own)
        val screen = ScreenSize.real(service, c.display) ?: run { apply(slots.displayGone(c.display)); return }
        val view = build(ctx, c.content, screen)
        // bottom corners sit above Recents' own row of buttons (Screenshot, Clear all); 1.3.2: a one-line hint hugs the top
        // edge (22 dp down it sat on Recents' cards). Build 18: (E6, MORE-19) on the screen "Your combos" is up on, a pill goes
        // under its header cards (it covered the TOP / BOTTOM cards); (E9, V13) a pill from Wayfinder's own window sits at the
        // bottom centre (top centre it covered the Hub's Search and Ko-fi, Game controls' title) — CueSpot decides
        val spot = CueSpot.of(c.content, c.fromWindow, underCompanion = MainActivity.companionDisplay() == c.display)
        // device pass (P1-1, P2-5): the window is as wide as its content measures against the screen. At WRAP_CONTENT Android
        // lays a window out at 320 dp first (config_prefDialogWidth) and widens it only for content that reports "too small" —
        // a TextView never does, a shrinking TableLayout only by a rounding accident (build 17's list had it, build 18's didn't).
        // The held list chose 3 columns for the screen ([build]), got 320 dp, and its 3rd column shrank under its own padding:
        // one letter per line, drawn nowhere, a first row as tall as the screen — every other combo below the bottom edge. A
        // pill measured on one line (its pill ends, its width) was wrapped onto two by the narrow window
        view.measure(View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST))
        val lp = WindowManager.LayoutParams(
            view.measuredWidth.takeIf { it > 0 } ?: WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (spot.at) {
                1 -> Gravity.TOP or Gravity.START; 2 -> Gravity.TOP or Gravity.END
                3 -> Gravity.BOTTOM or Gravity.START; 4 -> Gravity.BOTTOM or Gravity.END
                5 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            x = if (spot.at == 0 || spot.at == 5) 0 else (22 * dp).toInt()
            y = (spot.yDp * dp).toInt()
        }
        try {
            wm.addView(view, lp)
            if (fresh) {
                view.alpha = 0f; view.translationY = spot.enterDp * dp
                view.animate().alpha(1f).translationY(0f).setDuration(160).start()
            }
            shown += Win(c, wm, view, SystemClock.uptimeMillis() + (c.limitMs - c.shownMs).coerceAtLeast(0L) + 1500)
            main.removeCallbacks(sweep); main.postDelayed(sweep, 1000)
        } catch (_: Exception) {}
        // a tip's showings: after [Overlays.TIP_MAX_SHOWS] it counts as seen (Overlays.tip)
        if (fresh && c.kind == CueKind.TIP) prefs.edit().putInt("${c.key}_n", prefs.getInt("${c.key}_n", 0) + 1).apply()
    }

    /** The cue's view: a line, or a title and a table of (button, what it does) — the button in bold, words instead of
     *  arrows and emoji (the symbols made the hints hard to read, 2026-09-24). [screen]: the screen's size in px
     *  ([ScreenSize.real]). */
    private fun build(ctx: Context, content: CueContent, screen: Point): View {
        val dp = ctx.resources.displayMetrics.density
        // dev build 17 (WP10): the theme's colours (light, dark, black, your own) — one resolve, as the app's pages
        val g = GlassTokens.resolve(ctx)
        val ink = g.textPrimary.toArgb()
        val soft = g.textSecondary.toArgb()
        // build 18 (E6, MORE-21): a table (the held list, a tip, Recents' hint) sits on the opaque sheet colour — at 95 % the
        // page behind bled through its text; a pill keeps the 95 % glass. (A9): the badges read against that card
        val opaque = content is CueContent.Table
        val card = if (opaque) g.surfaceModal else g.base.copy(alpha = 0.95f)
        val under = g.panel.compositeOver(card.copy(alpha = 1f)).toArgb()
        /** [glyphsTo]: PlayStation's symbols up to there are drawn as badges (a table's "what" side stays text); [letters]:
         *  a lone A / B / X / Y there too (build 18, E6 addendum: the held list's button side). */
        fun label(t: CharSequence, sp: Float, color: Int = ink, glyphsTo: Int = t.length, letters: Boolean = false) = TextView(ctx).apply {
            // 1.4 (GitHub #66): larger font sizes up to 1.1× — bigger overflowed the screen; cells wrap below
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * ctx.resources.configuration.fontScale.coerceIn(0.85f, 1.1f))
            setLineSpacing(0f, 1.15f)
            text = glyphs(t, textSize, glyphsTo, g.dark, under, color, letters)
        }
        val compact = content is CueContent.Table && content.compact
        val view: View = when (content) {
            is CueContent.Line -> label(content.text, 16f).apply {
                // dev build 17 (WP10): a long text wraps at a readable width (and gets a panel's corners, below). Build 18 (E9):
                // balanced lines — no word left alone on the last one
                maxWidth = minOf(screen.x - (2 * 16 * dp).toInt(), (560 * dp).toInt())
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
            }
            is CueContent.Table -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                content.title.forEachIndexed { i, t ->
                    addView(label(t, if (i == 0) 17f else 14f, if (i == 0) ink else soft).apply {
                        if (i == 0) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
                if (content.cells.isNotEmpty()) addView(TableLayout(ctx).apply {
                    setPadding(0, if (compact) 0 else (8 * dp).toInt(), 0, 0)
                    isShrinkAllColumns = true   // cells wrap on a narrow screen or a large font — nothing cut (1.4, #66)
                    val cells = content.cells.map { (btn, what) ->
                        val t = android.text.SpannableStringBuilder().apply {
                            append(btn, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0)
                            if (what.isNotEmpty()) { append(if (compact) " " else "  "); append(what) }
                        }
                        label(t, if (compact) 14f else 16f, glyphsTo = btn.length, letters = true).apply {
                            setPadding(0, (3 * dp).toInt(), ((if (compact) 14 else 26) * dp).toInt(), (3 * dp).toInt()) }
                    }
                    // 1.4 (GitHub #66 + the bottom screen): as many columns (3 → 1) as the cells' real widths fit on THIS
                    // screen — a fixed 3 squeezed the last column to a word per line on the narrower bottom screen
                    val room = screen.x - (2 * 16 + 40) * dp
                    val widths = cells.map { it.measure(0, 0); it.measuredWidth }
                    fun fits(c: Int) = (0 until c).sumOf { col -> widths.filterIndexed { i, _ -> i % c == col }.maxOrNull() ?: 0 } <= room
                    val tall = cells.sumOf { it.measuredHeight } > screen.y * 0.7f
                    val cols = if (compact) cells.size else listOf(3, 2).firstOrNull { fits(it) } ?: if (tall) 2 else 1
                    for (row in cells.chunked(cols)) addView(TableRow(ctx).apply { row.forEach { addView(it) } })
                })
                // build 18 (E12): footer rows ("Also …") under the cells, wrapped at the width the titles and the table take —
                // as a cell, the long line widened its column and folded the whole list into one tall column
                if (content.footer.isNotEmpty()) {
                    val avail = screen.x - ((2 * 16 + 40) * dp).toInt()
                    measure(View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
                    val w = measuredWidth.coerceAtLeast((240 * dp).toInt()).coerceAtMost(avail)
                    for ((head, what) in content.footer) {
                        val t = android.text.SpannableStringBuilder().apply {
                            append(head, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0)
                            if (what.isNotEmpty()) { append("  "); append(what) }
                        }
                        addView(label(t, if (compact) 14f else 15f, soft, glyphsTo = head.length, letters = true).apply {
                            maxWidth = w; setPadding(0, (6 * dp).toInt(), 0, 0)
                        })
                    }
                }
            }
        }
        view.setPadding((20 * dp).toInt(), ((if (compact) 4 else 10) * dp).toInt(), (20 * dp).toInt(), ((if (compact) 6 else 12) * dp).toInt())
        // one line = a pill with round ends; a table, or a line that wraps = a panel's corners (critique K6: Radii.Panel) —
        // a pill's round ends cut into several lines. Build 18 (E6, MORE-19): a wrapped line takes a card's corners
        // (Radii.Card): the panel's 24 dp read as a stadium on two lines
        val panel = Radii.Panel.value * dp
        val radius = when (content) {
            is CueContent.Line -> {
                val tv = view as TextView
                val w = tv.maxWidth
                tv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
                if (tv.lineCount > 1) {
                    // build 18 (E9): balanced lines are shorter than the width they wrapped at — the card hugs the longest
                    // one (a margin of a pixel or two: the same lines at that width)
                    val longest = (0 until tv.lineCount).maxOf { tv.layout.getLineWidth(it) }
                    tv.width = (kotlin.math.ceil(longest).toInt() + tv.totalPaddingLeft + tv.totalPaddingRight + 2).coerceAtMost(w)
                    Radii.Card.value * dp
                } else 999f
            }
            is CueContent.Table -> if (content.compact && content.title.isEmpty()) 999f else panel
        }
        // Glass = a tint (keeps text legible over busy games; 95 %: text behind bled through, 2026-09-24 — a table: opaque,
        // build 18), a frost lit from the top and a specular rim — public APIs only
        val tint = GradientDrawable().apply { cornerRadius = radius; setColor(card.toArgb()) }
        val frost = GradientDrawable().apply {
            cornerRadius = radius
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(g.panel.toArgb(), g.panel.copy(alpha = g.panel.alpha * 0.4f).toArgb())
        }
        view.background = android.graphics.drawable.LayerDrawable(arrayOf(tint, frost,
            RimDrawable(radius, dp, g.rimTop.toArgb(), g.rimBottom.toArgb())))
        return view
    }
}

/**
 * Build 18 (E4 addendum, MORE-D19): the icons a cue's text can carry — one private-use character each, drawn by [FocusCue] as
 * the app's vector icon (the text's colour and size, centred), read aloud as [spoken]. Recents' symbol hint was typed ↗ × ⌂ ↩
 * in fallback fonts. A builder takes the character from here ([toString]), never types it.
 */
enum class CueIcons(val char: Char, val icon: androidx.compose.ui.graphics.vector.ImageVector, val spoken: String) {
    OPEN('\uE101', androidx.compose.material.icons.Icons.AutoMirrored.Rounded.OpenInNew, "open"),
    CLOSE('\uE102', androidx.compose.material.icons.Icons.Rounded.Delete, "close"),
    CLOSE_ALL('\uE103', androidx.compose.material.icons.Icons.Rounded.DeleteSweep, "close all"),
    HOME('\uE104', androidx.compose.material.icons.Icons.Rounded.Home, "home"),
    BACK('\uE105', androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Undo, "back");

    override fun toString() = char.toString()

    companion object {
        fun of(c: Char): CueIcons? = values().firstOrNull { it.char == c }
    }
}

/** A lone A / B / X / Y in a text: not inside a word or a name ("R1", "Xbox", "A-B"). */
private val LONE_LETTER = Regex("(?<![\\w\\-/'’])[ABXY](?![\\w\\-/'’])")

/**
 * dev build 14: PlayStation's ✕ ○ □ △ in [t] (before [end]) drawn as the app's vector badges, 1.3× the text
 * ([textPx]) — as characters they came from mismatched fallback fonts (the square small and low). The symbols stay
 * in the text underneath ([ButtonNames.psSymbol]) for accessibility. Only the button side of a table: Recents'
 * compact line uses ✕ on the "what" side as the close mark.
 * Build 18: (A9) the badges read against [under], the overlay's card; (E6 addendum, MORE-D32) with [letters], a lone A / B / X /
 * Y before [end] is a lettered badge too ([ButtonLetterDrawable]); (E4 addendum, MORE-D19) a [CueIcons] character anywhere is
 * its icon, in [color].
 */
private fun glyphs(t: CharSequence, textPx: Float, end: Int, dark: Boolean, under: Int, color: Int, letters: Boolean): CharSequence {
    var out: android.text.SpannableStringBuilder? = null
    fun span(d: android.graphics.drawable.Drawable, from: Int, to: Int, spoken: String? = null) {
        (out ?: android.text.SpannableStringBuilder(t).also { out = it })
            .setSpan(CentredImageSpan(d).apply { if (spoken != null && android.os.Build.VERSION.SDK_INT >= 30) contentDescription = spoken },
                from, to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val side = (textPx * 1.3f).roundToInt()
    for (i in 0 until end) {
        val mark = FaceMark.ofSymbol(t[i]) ?: continue
        span(ButtonGlyphDrawable(mark, dark, under).apply { setBounds(0, 0, side, side) }, i, i + 1)
    }
    if (letters) for (m in LONE_LETTER.findAll(t.subSequence(0, end))) {
        val b = ButtonLetterDrawable.letterOf(m.value) ?: continue
        span(ButtonLetterDrawable(b, dark, under).apply { setBounds(0, 0, side, side) }, m.range.first, m.range.last + 1)
    }
    val iconSide = (textPx * 1.15f).roundToInt()
    for (i in t.indices) {
        val ic = CueIcons.of(t[i]) ?: continue
        span(VectorIconDrawable(ic.icon, color).apply { setBounds(0, 0, iconSide, iconSide) }, i, i + 1, ic.spoken)
    }
    return out ?: t
}

/** Build 18 (E4 addendum): a Compose vector icon ([ImageVector]: Material's, one or a few filled paths) drawn in a View —
 *  its paths parsed once, scaled to the bounds, filled with [color]. */
private class VectorIconDrawable(vector: androidx.compose.ui.graphics.vector.ImageVector, color: Int) : android.graphics.drawable.Drawable() {
    private val vw = vector.viewportWidth
    private val vh = vector.viewportHeight
    private val paths: List<android.graphics.Path> = ArrayList<android.graphics.Path>().also { out ->
        fun walk(g: androidx.compose.ui.graphics.vector.VectorGroup) {
            for (n in g) when (n) {
                is androidx.compose.ui.graphics.vector.VectorPath -> {
                    val p = androidx.compose.ui.graphics.vector.PathParser().addPathNodes(n.pathData).toPath()
                    p.fillType = n.pathFillType
                    out += p.asAndroidPath()
                }
                is androidx.compose.ui.graphics.vector.VectorGroup -> walk(n)
            }
        }
        walk(vector.root)
    }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL; this.color = color
    }

    override fun draw(canvas: android.graphics.Canvas) {
        if (vw <= 0f || vh <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / vw, bounds.height() / vh)
        for (p in paths) canvas.drawPath(p, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** An ImageSpan centred on its text's middle. ALIGN_CENTER centres it on the line, which the taller badge only
 *  grows upward — it sat high; here the line grows evenly above and below. */
private class CentredImageSpan(d: android.graphics.drawable.Drawable) : android.text.style.ImageSpan(d) {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val h = drawable.bounds.height()
        if (fm != null) {
            val f = paint.fontMetricsInt
            val mid = (f.ascent + f.descent) / 2
            fm.ascent = minOf(f.ascent, mid - h / 2); fm.descent = maxOf(f.descent, mid + h - h / 2)
            fm.top = minOf(f.top, fm.ascent); fm.bottom = maxOf(f.bottom, fm.descent)
        }
        return drawable.bounds.right
    }

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val f = paint.fontMetricsInt
        canvas.save()
        canvas.translate(x, (y + (f.ascent + f.descent) / 2 - drawable.bounds.height() / 2).toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}

/** Specular rim: a hairline that's bright at the top-left ([top]) and fades toward the bottom-right ([bottom]) — dev build
 *  17 (WP10): the theme's rim colours. */
private class RimDrawable(private val radius: Float, private val dp: Float, private val top: Int, private val bottom: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.2f * dp
    }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        paint.shader = android.graphics.LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(top, top and 0x00FFFFFF, bottom),
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
