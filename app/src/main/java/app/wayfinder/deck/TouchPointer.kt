package app.wayfinder.deck

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 1.3 (GitHub #36) — the deck's trackpad in Touch mode: a pointer on the game's screen, and real
 * finger touches there (accessibility gestures) instead of a mouse. For games that only react to
 * touches (a mouse click does nothing in most of them). Move = the pointer; tap = a tap at the
 * pointer; hold then move = a finger dragged live; two fingers = a swipe.
 */
object TouchPointer {
    private const val TAG = "ThorTouch"
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile var display: Int = android.view.Display.DEFAULT_DISPLAY
        private set
    private var w = 1920f; private var h = 1080f
    var x = 960f; private set
    var y = 540f; private set
    val isShown get() = view != null

    // a finger held down (hold + move): the stroke continues in small pieces
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f; private var lastY = 0f; private var lastAt = 0L
    private var pendingX = 0f; private var pendingY = 0f; private var sending = false

    /** The pointer on [displayId] (the game's screen), in its middle the first time. */
    fun show(svc: AccessibilityService, displayId: Int) {
        if (view != null && display == displayId) return
        hide()
        val d = svc.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val ctx = svc.createDisplayContext(d)
        // 1.3.2 (GitHub #49): the display's REAL size — this context's window metrics answered the deck's
        // (bottom) screen for the top one, so the pointer stopped ~60 % across the game
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
        if (real.x > 0 && real.y > 0) { w = real.x.toFloat(); h = real.y.toFloat() }
        if (display != displayId) { x = w / 2; y = h / 2 }
        service = svc; display = displayId
        val size = (40 * ctx.resources.displayMetrics.density).toInt()
        val dot = object : View(ctx) {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = size / 10f; color = 0xE6FFFFFF.toInt() }
            private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = size / 5f; color = 0x66000000 }
            private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D9BFF.toInt() }
            override fun onDraw(c: Canvas) {
                val r = size / 2f
                c.drawCircle(r, r, r * 0.62f, edge); c.drawCircle(r, r, r * 0.62f, ring)
                c.drawCircle(r, r, r * (if (stroke != null) 0.34f else 0.18f), core)
            }
        }
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; title = "WayfinderTouchPointer" }
        val manager = ctx.getSystemService(WindowManager::class.java)
        try {
            manager.addView(dot, p); view = dot; wm = manager; lp = p; place()
        } catch (e: Exception) { Log.w(TAG, "pointer on $displayId: ${e.message}") }
    }

    fun hide() {
        release()
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null; wm = null; lp = null
    }

    private fun place() {
        val v = view ?: return; val p = lp ?: return
        p.x = (x - v.width.coerceAtLeast(p.width) / 2f).toInt(); p.y = (y - p.height / 2f).toInt()
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** The pointer moves by (dx, dy) screen pixels; a held finger follows it. */
    fun move(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, w - 1); y = (y + dy).coerceIn(0f, h - 1)
        place()
        if (stroke != null) { pendingX = x; pendingY = y; pump() }
    }

    /** 1.4 (GitHub #65): the pointer jumps to a place (fractions of the game's screen); a held finger follows. */
    fun jumpTo(fx: Float, fy: Float) {
        x = (fx * w).coerceIn(0f, w - 1); y = (fy * h).coerceIn(0f, h - 1)
        place()
        if (stroke != null) { pendingX = x; pendingY = y; pump() }
    }

    /** A tap at the pointer. */
    fun tap() = dispatch(GestureDescription.StrokeDescription(pathAt(x, y), 0, 40))

    /** A finger pressed at the pointer and kept down ([move] drags it, [release] lifts it). */
    fun press() {
        if (stroke != null) return
        val s = GestureDescription.StrokeDescription(pathAt(x, y), 0, 30, true)
        stroke = s; lastX = x; lastY = y; lastAt = SystemClock.uptimeMillis()
        view?.invalidate()
        dispatch(s)
    }

    fun release() {
        val s = stroke ?: return
        stroke = null; view?.invalidate()
        runCatching {
            dispatch(s.continueStroke(Path().apply { moveTo(lastX, lastY); lineTo(x, y) }, 0, 30, false))
        }
    }

    /** A swipe from the pointer by (dx, dy) (two fingers on the trackpad). */
    fun swipe(dx: Float, dy: Float) {
        val tx = (x + dx).coerceIn(0f, w - 1); val ty = (y + dy).coerceIn(0f, h - 1)
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(tx, ty) }, 0, 180))
    }

    /** The held finger's next piece: one at a time (a new piece before the last one ended cancels it). */
    private fun pump() {
        val s = stroke ?: return
        if (sending) return
        if (pendingX == lastX && pendingY == lastY) return
        val now = SystemClock.uptimeMillis()
        val dur = (now - lastAt).coerceIn(16, 60)
        val next = s.continueStroke(Path().apply { moveTo(lastX, lastY); lineTo(pendingX, pendingY) }, 0, dur, true)
        stroke = next; lastX = pendingX; lastY = pendingY; lastAt = now
        sending = true
        dispatch(next) { sending = false; main.post { pump() } }
    }

    private fun pathAt(px: Float, py: Float) = Path().apply { moveTo(px, py) }

    private fun dispatch(s: GestureDescription.StrokeDescription, done: (() -> Unit)? = null) {
        val svc = service ?: return
        val g = GestureDescription.Builder().addStroke(s).setDisplayId(display).build()
        val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done?.invoke() }
            override fun onCancelled(d: GestureDescription?) { done?.invoke() }
        }, main)
        if (!ok) done?.invoke()
    }
}
