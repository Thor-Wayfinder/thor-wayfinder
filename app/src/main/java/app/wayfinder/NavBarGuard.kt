package app.wayfinder

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.3 (GitHub #23) — Android's navigation bar coming back although it's set hidden (AYN's setting
 * `hide_nav_bar` = 1), until a reboot. Checked on an app change or the screen coming on, at most every 8 s:
 * if the setting is on and the TOP screen's bar is visible anyway, the setting is written again
 * (0, then 1 — SystemUI then hides the bar, verified 2026-09-27). Nothing when the setting is off.
 *
 * 1.4 (GitHub #62 #63): it flashed the bar in apps for ever — it counted the bottom screen's bar too (and a bar an
 * app hides itself can still read "visible"), and each rewrite shows the bar for a moment. Now: the top screen's bar
 * only, still there a second later, and after two rewrites that change nothing it stops until the next boot.
 * A switch too (Screens → "Keep the navigation bar hidden").
 */
object NavBarGuard {
    private const val EVERY_MS = 8_000L
    private const val MAX_USELESS = 2
    @Volatile private var last = 0L
    /** 1.4: AYN's "hide the navigation bar" (global hide_nav_bar = 1), as last read; null = not read yet. */
    @Volatile var navHidden: Boolean? = null
        private set
    /** The setting, now: straight from Android when this app may read it, else the last root read. */
    fun navHiddenNow(ctx: android.content.Context): Boolean {
        runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "hide_nav_bar") == 1 }.getOrNull()?.let { return it }
        navHidden?.let { return it }
        // unknown yet (the guard is off, or nothing ran): ask root once, in the background; meanwhile not hidden
        if (asking.compareAndSet(false, true)) Thread {
            try { if (PServiceBridge.cachedAvailable()) navHidden = PServiceBridge.exec("settings get global hide_nav_bar")?.trim() == "1" }
            finally { asking.set(false) }
        }.apply { isDaemon = true }.start()
        return false
    }
    private val asking = AtomicBoolean(false)
    @Volatile private var useless = 0
    private val busy = AtomicBoolean(false)

    /** The top screen's NavigationBar window (NavigationBar0) is drawn. */
    private fun topBarShown(): Boolean =
        (PServiceBridge.exec("dumpsys window windows | awk '/Window #.*NavigationBar0\\}/{f=1} " +
            "f&&/mViewVisibility=/{print; f=0}' | grep -c 'mViewVisibility=0x0 '")?.trim()?.toIntOrNull() ?: 0) > 0

    fun poke() {
        val now = SystemClock.uptimeMillis()
        if (!AppSettings.navGuard || useless >= MAX_USELESS) return
        if (now - last < EVERY_MS || !PServiceBridge.cachedAvailable() || !busy.compareAndSet(false, true)) return
        last = now
        Thread {
            try {
                val hidden = PServiceBridge.exec("settings get global hide_nav_bar")?.trim() == "1"
                navHidden = hidden
                if (!hidden) return@Thread
                if (!topBarShown()) return@Thread
                Thread.sleep(1000)   // a bar shown for a moment (a swipe, a window taking focus) goes by itself
                if (!topBarShown()) return@Thread
                android.util.Log.w("ThorNavBar", "navigation bar visible although hidden in settings — hiding it again")
                PServiceBridge.exec("settings put global hide_nav_bar 0; sleep 0.3; settings put global hide_nav_bar 1")
                Thread.sleep(1500)
                if (topBarShown()) {
                    useless++
                    android.util.Log.w("ThorNavBar", "still visible after hiding it again ($useless/$MAX_USELESS)")
                } else useless = 0
            } finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }
}
