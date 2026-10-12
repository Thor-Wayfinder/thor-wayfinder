package app.wayfinder

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * FPS counter pill: a small, untouchable overlay in the top-left corner of a screen.
 * Green ≥ 55, amber ≥ 28, red below; grey "idle" when nothing was drawn. Numbers come from [FpsSampler] via the root helper.
 * Build 18 (GitHub #86): three text sizes and a compact style; its look is in the companion, which the Hub's preview reads too.
 */
class FpsOverlay(private val service: android.accessibilityservice.AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var shownOn = -1

    private var corner = -1
    private var size = -1
    private var compact = false

    /** [corner]: 0 top-left · 1 top-right · 2 bottom-left · 3 bottom-right. [size]: 0 small · 1 normal · 2 large;
     *  [compact]: square, tight, flush in the corner. */
    fun show(displayId: Int, corner: Int = 0, size: Int = 1, compact: Boolean = false) = main.post {
        if (view != null && shownOn == displayId && this.corner == corner && this.size == size && this.compact == compact) return@post
        hideNow()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return@post
        val ctx = service.createDisplayContext(display)
        val w = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density
        val (padH, padV) = padDp(compact)
        val (mx, my) = marginDp(corner, compact)
        val tv = TextView(ctx).apply {
            text = "— FPS"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(size))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding((padH * dp).toInt(), (padV * dp).toInt(), (padH * dp).toInt(), (padV * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = radiusDp(compact)?.let { it * dp } ?: 999f; setColor(PILL) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (corner >= 2) Gravity.BOTTOM else Gravity.TOP) or (if (corner % 2 == 1) Gravity.END else Gravity.START)
            x = (mx * dp).toInt()
            y = (my * dp).toInt()
        }
        try {
            w.addView(tv, lp); view = tv; wm = w; shownOn = displayId
            this.corner = corner; this.size = size; this.compact = compact
        } catch (_: Exception) {}
    }

    fun update(fps: Int) = main.post {
        val v = view ?: return@post
        if (fps < 0) return@post
        // 0 = the app drew nothing this second (a menu at rest) — not a problem, so grey.
        val head = if (fps == 0) "idle" else "$fps FPS"
        val colour = rateColour(fps)
        // round 8 levels: + battery (%, watts) · + temperatures — the extras in white, the rate in its colour
        val extra = Extras.text(service, AppSettings.fpsLevel, compact)
        v.text = android.text.SpannableString(head + extra).apply {
            setSpan(android.text.style.ForegroundColorSpan(colour), 0, head.length, 0)
            if (extra.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(READINGS), head.length, head.length + extra.length, 0)
        }
    }

    /** One hardware reading a second, shared by both screens' counters (no root: kernel files). */
    private object Extras {
        private var stats: HwStats? = null
        private var at = 0L
        private var last = ""
        private var lastLevel = -1
        private var lastCompact = false
        fun text(ctx: android.content.Context, level: Int, compact: Boolean): String {
            if (level == 0) return ""
            val now = android.os.SystemClock.uptimeMillis()
            if (now - at < 800 && level == lastLevel && compact == lastCompact) return last
            val s = (stats ?: HwStats(ctx.applicationContext).also { stats = it }).sample()
            val parts = mutableListOf<String>()
            s.battery?.let { b -> parts += "$b %" + (s.watts?.takeIf { it > 0.05f }?.let { w -> " " + (if (s.charging) "+" else "−") + "%.1f W".format(w) } ?: "") }
            if (level >= 2) {
                s.cpuTemp?.let { parts += "CPU ${it.toInt()}°" }
                s.gpuTemp?.let { parts += "GPU ${it.toInt()}°" }
            }
            at = now; lastLevel = level; lastCompact = compact
            last = readings(parts, compact)
            return last
        }
    }

    fun hide() = main.post { hideNow() }

    private fun hideNow() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null; wm = null; shownOn = -1; corner = -1; size = -1
    }

    /** Build 18 (GitHub #86): the counter's look — the overlay and the Hub's preview (Screens) both read it here, so they
     *  never differ. Normal and not compact = the look before build 18. */
    companion object {
        /** The text size, sp: 0 small · 1 normal · 2 large. */
        fun textSp(size: Int): Float = when (size) { 0 -> 11f; 2 -> 16f; else -> 13f }
        /** The padding (sides, top and bottom), dp. */
        fun padDp(compact: Boolean): Pair<Int, Int> = if (compact) 5 to 1 else 10 to 4
        /** The corner radius, dp; null = a pill (round ends). */
        fun radiusDp(compact: Boolean): Int? = if (compact) 3 else null
        /** From the screen's side, and from its top / bottom edge, dp: at the top it stays below the status bar; compact sits
         *  flush in the corner. */
        fun marginDp(corner: Int, compact: Boolean): Pair<Int, Int> = if (compact) 2 to 2 else 10 to (if (corner >= 2) 10 else 30)
        /** What comes after the rate: "  ·  87 %  ·  CPU 52°" ("" = nothing); compact = single spaces. */
        fun readings(parts: List<String>, compact: Boolean): String {
            val sep = if (compact) " · " else "  ·  "
            return if (parts.isEmpty()) "" else sep + parts.joinToString(sep)
        }
        /** The colours (ARGB): the pill, the readings after the rate. */
        val PILL = 0x99000000.toInt()
        val READINGS = 0xFFEDEDED.toInt()
        /** The rate's colour: green ≥ 55, amber ≥ 28, red below; grey for idle (0). */
        fun rateColour(fps: Int): Int = when {
            fps == 0 -> 0xFFBBBBBB.toInt(); fps >= 55 -> 0xFF7CFFA0.toInt(); fps >= 28 -> 0xFFFFD166.toInt(); else -> 0xFFFF7B7B.toInt()
        }
    }
}
