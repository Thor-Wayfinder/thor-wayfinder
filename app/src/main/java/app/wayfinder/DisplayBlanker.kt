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
                        text = "Tap to wake"; setTextColor(0x66FFFFFF); textSize = 14f
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER })
                    // Wake on the first touch-down anywhere (more reliable than a click).
                    setOnTouchListener { _, ev ->
                        if (ev.action == android.view.MotionEvent.ACTION_DOWN) { wake(displayId); true } else false
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
            } catch (e: Exception) {
                Log.w(TAG, "blank $displayId failed: ${e.message}")
            }
        }
    }

    fun wake(displayId: Int) {
        val b = blanks.remove(displayId) ?: return
        runOnMain { try { b.wm.removeView(b.root) } catch (_: Exception) {} }
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
