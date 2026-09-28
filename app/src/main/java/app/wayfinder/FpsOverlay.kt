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
 */
class FpsOverlay(private val service: android.accessibilityservice.AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var shownOn = -1

    private var corner = -1

    /** [corner]: 0 top-left · 1 top-right · 2 bottom-left · 3 bottom-right. */
    fun show(displayId: Int, corner: Int = 0) = main.post {
        if (view != null && shownOn == displayId && this.corner == corner) return@post
        hideNow()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return@post
        val ctx = service.createDisplayContext(display)
        val w = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            text = "— FPS"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(0x99000000.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (corner >= 2) Gravity.BOTTOM else Gravity.TOP) or (if (corner % 2 == 1) Gravity.END else Gravity.START)
            x = (10 * dp).toInt()
            y = ((if (corner >= 2) 10 else 30) * dp).toInt()   // top: below the status bar
        }
        try { w.addView(tv, lp); view = tv; wm = w; shownOn = displayId; this.corner = corner } catch (_: Exception) {}
    }

    fun update(fps: Int) = main.post {
        val v = view ?: return@post
        if (fps < 0) return@post
        // 0 = the app drew nothing this second (a menu at rest) — not a problem, so grey.
        val head = if (fps == 0) "idle" else "$fps FPS"
        val colour = when { fps == 0 -> 0xFFBBBBBB.toInt(); fps >= 55 -> 0xFF7CFFA0.toInt(); fps >= 28 -> 0xFFFFD166.toInt(); else -> 0xFFFF7B7B.toInt() }
        // round 8 levels: + battery (%, watts) · + temperatures — the extras in white, the rate in its colour
        val extra = Extras.text(service, AppSettings.fpsLevel)
        v.text = android.text.SpannableString(head + extra).apply {
            setSpan(android.text.style.ForegroundColorSpan(colour), 0, head.length, 0)
            if (extra.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(0xFFEDEDED.toInt()), head.length, head.length + extra.length, 0)
        }
    }

    /** One hardware reading a second, shared by both screens' counters (no root: kernel files). */
    private object Extras {
        private var stats: HwStats? = null
        private var at = 0L
        private var last = ""
        private var lastLevel = -1
        fun text(ctx: android.content.Context, level: Int): String {
            if (level == 0) return ""
            val now = android.os.SystemClock.uptimeMillis()
            if (now - at < 800 && level == lastLevel) return last
            val s = (stats ?: HwStats(ctx.applicationContext).also { stats = it }).sample()
            val parts = mutableListOf<String>()
            s.battery?.let { b -> parts += "$b %" + (s.watts?.takeIf { it > 0.05f }?.let { w -> " " + (if (s.charging) "+" else "−") + "%.1f W".format(w) } ?: "") }
            if (level >= 2) {
                s.cpuTemp?.let { parts += "CPU ${it.toInt()}°" }
                s.gpuTemp?.let { parts += "GPU ${it.toInt()}°" }
            }
            at = now; lastLevel = level
            last = if (parts.isEmpty()) "" else "  ·  " + parts.joinToString("  ·  ")
            return last
        }
    }

    fun hide() = main.post { hideNow() }

    private fun hideNow() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null; wm = null; shownOn = -1; corner = -1
    }
}
