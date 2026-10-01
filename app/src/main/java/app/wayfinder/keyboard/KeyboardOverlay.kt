package app.wayfinder.keyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import app.wayfinder.MainActivity
import app.wayfinder.ui.ThorGlassTheme

/**
 * The keyboard drawn on the OTHER screen (the text field's screen keeps its full
 * size). An accessibility overlay: it can live on any display, and being
 * NOT_FOCUSABLE its touches never move input focus away from the field — keystrokes
 * go through the IME's InputConnection as usual. The real background shows through,
 * blurred (same system blur as the Hub's glass).
 */
class KeyboardOverlay(private val service: AccessibilityService) {

    private var view: ComposeView? = null
    private var wm: WindowManager? = null
    private var owner: ComposeOwner? = null
    var displayId: Int? = null
        private set

    private var lp: WindowManager.LayoutParams? = null

    /** A password / PIN field: the overlay stays out of screenshots and recordings (which key
     *  lights up gives the password away even with the preview masked). Only then: for any
     *  other field it blocked the user's own screenshots of that screen, and Wayfinder's move
     *  animation / light sampling of it (release test 2026-09-24). */
    fun setSecure(on: Boolean) {
        if (view == null) return
        val p = lp ?: return
        if (((p.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) == on) return
        // Re-created, not updateViewLayout(): an in-place update re-laid the window out at the
        // TOP screen's width — keys cut off at the right (release test 2026-09-24)
        val d = displayId ?: return; val i = ime ?: return
        hide(); show(d, i, on)
    }

    private var ime: ThorKeyboardService? = null

    /** Show on [displayId] (replacing any other placement). True if it's up. */
    fun show(displayId: Int, ime: ThorKeyboardService, secure: Boolean = false): Boolean {
        if (view != null && this.displayId == displayId) { setSecure(secure); return true }
        hide()
        this.ime = ime
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val owner = ComposeOwner()
        val v = ComposeView(ctx).apply {
            owner.attach(this)
            setContent {
                ThorGlassTheme(dark = ime.isDark()) { app.wayfinder.ui.CappedFontScale {
                    ThorKeyboardPanel(
                        ime.kb, overlay = true, preview = ime.preview,
                        modifier = Modifier.fillMaxSize(),
                        onHide = { ime.close() },
                        moveLabel = ime.overlayMoveLabel(),
                        onMove = { ime.moveToSameScreen() },
                    )
                }}
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = MainActivity.BLUR_RADIUS_PX
            }
            dimAmount = 0f
            title = "ThorKeyboard"
        }
        return try {
            wm.addView(v, lp)
            view = v; this.wm = wm; this.owner = owner; this.displayId = displayId; this.lp = lp
            true
        } catch (e: Exception) {
            Log.w("ThorKeyboard", "overlay on display $displayId failed: ${e.message}")
            owner.destroy(); false
        }
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm?.removeViewImmediate(v) }
        owner?.destroy()
        view = null; wm = null; owner = null; displayId = null; lp = null
    }
}
