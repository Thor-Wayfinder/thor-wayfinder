package app.wayfinder

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.3 (GitHub #23) — Android's navigation bar coming back although it's set hidden (AYN's setting
 * `hide_nav_bar` = 1), until a reboot. Checked on an app change or the screen coming on, at most every 8 s:
 * if the setting is on and a NavigationBar window is visible anyway, the setting is written again
 * (0, then 1 — SystemUI then hides the bar, verified 2026-09-27). Nothing when the setting is off.
 */
object NavBarGuard {
    private const val EVERY_MS = 8_000L
    @Volatile private var last = 0L
    private val busy = AtomicBoolean(false)

    fun poke() {
        val now = SystemClock.uptimeMillis()
        if (now - last < EVERY_MS || !PServiceBridge.cachedAvailable() || !busy.compareAndSet(false, true)) return
        last = now
        Thread {
            try {
                if (PServiceBridge.exec("settings get global hide_nav_bar")?.trim() != "1") return@Thread
                // the NavigationBar windows' view visibility: 0x0 = shown (0x4 / 0x8 = hidden)
                val shown = PServiceBridge.exec("dumpsys window windows | awk '/Window #.*NavigationBar/{f=1} " +
                    "f&&/mViewVisibility=/{print; f=0}' | grep -c 'mViewVisibility=0x0 '")?.trim()?.toIntOrNull() ?: 0
                if (shown > 0) {
                    android.util.Log.w("ThorNavBar", "navigation bar visible although hidden in settings — hiding it again")
                    PServiceBridge.exec("settings put global hide_nav_bar 0; sleep 0.3; settings put global hide_nav_bar 1")
                }
            } finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }
}
