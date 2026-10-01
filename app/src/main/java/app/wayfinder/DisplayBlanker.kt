package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * Blanks a screen with a fullscreen opaque BLACK overlay instead of a true panel
 * power-off (SurfaceControl.setDisplayPowerMode is re-asserted by the display
 * controller). On the Thor's OLED screens black pixels are effectively off, so
 * this saves battery like a real blank — while the app underneath keeps running.
 * Tapping the blanked screen wakes it. Used by the blank toggle, the idle auto-off and the per-app bottom-screen rule.
 */
class DisplayBlanker(private val service: AccessibilityService) {

    companion object { private const val TAG = "ThorBlank" }

    private class Blank(val wm: WindowManager, val root: View)

    private val blanks = ConcurrentHashMap<Int, Blank>()
    private val awake = ConcurrentHashMap<Int, Blank>()   // keep-awake holder overlays
    private val main = Handler(Looper.getMainLooper())

    fun isBlanked(displayId: Int): Boolean = blanks.containsKey(displayId)

    // ── keep-awake: a tiny transparent overlay with FLAG_KEEP_SCREEN_ON ──
    fun isKeptAwake(displayId: Int): Boolean = awake.containsKey(displayId)

    fun toggleKeepAwake(displayId: Int) {
        if (isKeptAwake(displayId)) {
            val a = awake.remove(displayId) ?: return
            runOnMain { try { a.wm.removeView(a.root) } catch (_: Exception) {} }
            Log.d(TAG, "keep-awake OFF display $displayId")
        } else {
            runOnMain {
                if (awake.containsKey(displayId)) return@runOnMain
                val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return@runOnMain
                try {
                    val dctx = service.createDisplayContext(display)
                    val wm = dctx.getSystemService(WindowManager::class.java)
                    val v = View(dctx)
                    val lp = WindowManager.LayoutParams(
                        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.START }
                    wm.addView(v, lp)
                    awake[displayId] = Blank(wm, v)
                    Log.d(TAG, "keep-awake ON display $displayId")
                } catch (e: Exception) { Log.w(TAG, "keep-awake $displayId failed: ${e.message}") }
            }
        }
    }

    fun toggle(displayId: Int) { if (isBlanked(displayId)) wake(displayId) else blank(displayId) }

    /** 1.4: when each screen last woke (the auto-off timer counts from it too). */
    private val wokeAt = ConcurrentHashMap<Int, Long>()
    fun wokeAt(displayId: Int): Long = wokeAt[displayId] ?: 0L
    /** 1.4: a screen just went dark (the service moves the controller off it). */
    @Volatile var onBlanked: ((Int) -> Unit)? = null
    /** 1.4: a screen woke. */
    @Volatile var onWoken: ((Int) -> Unit)? = null

    fun blank(displayId: Int) {
        if (blanks.containsKey(displayId)) return
        runOnMain {
            if (blanks.containsKey(displayId)) return@runOnMain
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
                ?: return@runOnMain
            try {
                val dctx = service.createDisplayContext(display)
                val wm = dctx.getSystemService(WindowManager::class.java)
                val root = FrameLayout(dctx).apply {
                    setBackgroundColor(Color.BLACK)
                    // A faint hint that fades out; the whole surface wakes on tap.
                    addView(TextView(dctx).apply {
                        text = if (AppSettings.wakeDoubleTap) "Tap twice to wake" else "Tap to wake"; setTextColor(0x66FFFFFF); textSize = 14f
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER })
                    // Wake on the first touch-down anywhere (more reliable than a click) — or, with "double tap to
                    // wake" (1.4, GitHub #54), on a second tap close to the first. Every touch is kept from the app below.
                    var lastDown = 0L; var lastX = 0f; var lastY = 0f
                    val slop = 60 * dctx.resources.displayMetrics.density
                    setOnTouchListener { v, ev ->
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            if (!AppSettings.wakeDoubleTap) wake(displayId)
                            else {
                                val t = ev.eventTime
                                if (t - lastDown <= 400 && kotlin.math.hypot(ev.x - lastX, ev.y - lastY) <= slop) wake(displayId)
                                else {
                                    lastDown = t; lastX = ev.x; lastY = ev.y
                                    // the hint comes back for a moment: "tap twice"
                                    (v as? FrameLayout)?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(120)
                                        ?.withEndAction { v.postDelayed({ v.getChildAt(0)?.animate()?.alpha(0f)?.setDuration(600)?.start() }, 1200) }?.start()
                                }
                            }
                        }
                        true
                    }
                    postDelayed({ getChildAt(0)?.animate()?.alpha(0f)?.setDuration(600)?.start() }, 1800)
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // Focusable-off but TOUCHABLE so a tap wakes it.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE
                ).apply { gravity = Gravity.TOP or Gravity.START }
                wm.addView(root, lp)
                blanks[displayId] = Blank(wm, root)
                Log.d(TAG, "blanked display $displayId")
                onBlanked?.invoke(displayId)
            } catch (e: Exception) {
                Log.w(TAG, "blank $displayId failed: ${e.message}")
            }
        }
    }

    fun wake(displayId: Int) {
        val b = blanks.remove(displayId) ?: return
        wokeAt[displayId] = android.os.SystemClock.uptimeMillis()
        runOnMain { try { b.wm.removeView(b.root) } catch (_: Exception) {} }
        onWoken?.invoke(displayId)
        Log.d(TAG, "woke display $displayId")
    }

    fun wakeAll() {
        blanks.keys.toList().forEach { wake(it) }
        awake.keys.toList().forEach { toggleKeepAwake(it) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
