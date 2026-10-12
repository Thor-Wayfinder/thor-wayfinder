package app.wayfinder

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The Thor docked on a TV / monitor (an external display).
 *
 * - Controllers: a connected controller takes the Thor's place ([ExtPads] "E dock": its name, player 1, the Thor's
 *   own controller off) — an emulator set up for "Odin Controller" plays with it as it is. Undocked: all back.
 * - Screens: AYN's "turn off the screens when a monitor is plugged" (Settings.System `close_screen_when_output_video`)
 *   covers each built-in screen with a black window ("primaryScreenTopLayout") — the top one was seen staying lit. A
 *   few seconds after docking, a built-in screen that isn't covered has its PANEL powered off (SurfaceControl, as root:
 *   [DisplayPowerTool]) — not covered: a TV mirroring that screen would show a cover too. Re-checked every 20 s (Android
 *   may power it on again); undocking powers it back on. What was found is logged.
 */
object Dock {
    private const val TAG = "ThorDock"
    @Volatile var docked = false
        private set
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    /** Built-in screens whose panel Wayfinder powered off because docking left them lit: display id → physical id. */
    private val ourOff = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** [ourOff] kept across an app restart (an update while docked): a panel it turned off is turned on again. */
    private fun saveOff(a: Context) = runCatching {
        a.getSharedPreferences("dock", Context.MODE_PRIVATE).edit()
            .putStringSet("off", ourOff.map { "${it.key}:${it.value}" }.toSet()).apply()
    }
    private fun leftover(a: Context) {
        val kept = runCatching { a.getSharedPreferences("dock", Context.MODE_PRIVATE).getStringSet("off", null) }.getOrNull() ?: return
        for (e in kept) { val id = e.substringBefore(':').toIntOrNull() ?: continue; ourOff[id] = e.substringAfter(':') }
        if (ourOff.isEmpty()) return
        Log.w(TAG, "screens: ${ourOff.keys} were turned off by Wayfinder before it restarted")
        if (!docked) wakeOurs(a)                                        // undocked meanwhile: on again now
    }
    /** …or blanked (the second screen). */
    private val ourBlanks: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** An external monitor / dock: a public display that isn't one of the Thor's own two panels ("Built-in Screen",
     *  "Screen-2" on AYN's ROM) nor an overlay — the same rule as [Sleep]. */
    fun isExternal(d: android.view.Display): Boolean {
        val n = d.name.orEmpty()
        // testing without a monitor (debug builds only): `setprop debug.wayfinder.fakedock 1` + Android's simulated
        // display (`settings put global overlay_display_devices 1280x720/240`) counts as one
        if (n.contains("Overlay", true) && fakeDock()) return d.isValid
        return d.displayId != 0 && d.isValid && (d.flags and android.view.Display.FLAG_PRIVATE) == 0 &&
            !n.startsWith("Built-in", true) && !n.equals("Screen-2", true) && !n.contains("Overlay", true)
    }
    private fun fakeDock(): Boolean = BuildConfig.DEBUG && runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.wayfinder.fakedock") == "1"
    }.getOrDefault(false)

    fun start(ctx: Context) {
        if (app != null) { update(); return }
        val a = ctx.applicationContext
        app = a
        a.getSystemService(DisplayManager::class.java).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { main.postDelayed({ update() }, 500) }
            override fun onDisplayRemoved(id: Int) { update() }
            override fun onDisplayChanged(id: Int) {}
        }, main)
        update()
        leftover(a)
    }

    private fun update() {
        val a = app ?: return
        val now = a.getSystemService(DisplayManager::class.java).displays.any { isExternal(it) }
        if (now == docked) return
        docked = now
        Log.w(TAG, if (now) "docked" else "undocked")
        ExtPadsCtl.setDocked(a, now)
        // one check loop at a time: every check is posted with the RECHECK token, so a dock flap cancels the earlier one
        main.removeCallbacksAndMessages(RECHECK)
        if (now) main.postAtTime({ checkScreens(a) }, RECHECK, android.os.SystemClock.uptimeMillis() + 4000) else wakeOurs(a)
    }

    /** Docked with AYN's screens-off option on: every built-in screen covered by AYN's black window, or ours. */
    private fun checkScreens(a: Context): Unit = screensCheck(a)
    private fun screensCheck(a: Context) { Thread {
        if (!docked) return@Thread
        val opt = PServiceBridge.exec("settings get system close_screen_when_output_video")?.trim()
        if (opt == "0") { Log.w(TAG, "screens: AYN's screens-off option is off — left as they are"); return@Thread }
        val wins = PServiceBridge.exec("dumpsys window windows").orEmpty()
        // the displays AYN covered: a "primaryScreenTopLayout" window, its display on the next lines
        val covered = HashSet<Int>()
        val lines = wins.lines()
        for ((i, l) in lines.withIndex()) {
            if (!l.contains("Window{") || !l.contains("primaryScreenTopLayout")) continue
            lines.drop(i + 1).take(3).firstNotNullOfOrNull { Regex("mDisplayId=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?.let { covered.add(it) }
        }
        val dm = a.getSystemService(DisplayManager::class.java)
        val builtIn = dm.displays.filter { !isExternal(it) && it.isValid && (it.flags and android.view.Display.FLAG_PRIVATE) == 0 &&
            !it.name.orEmpty().contains("Overlay", true) }.map { it.displayId }
        val bare = builtIn.filter { it !in covered }
        // the panel's power as the kernel has it: the top screen is DSI-1, the second DSI-2
        fun panelOff(id: Int) = PServiceBridge.exec("cat /sys/class/drm/card0-DSI-${if (id == 0) 1 else 2}/dpms")?.trim() == "Off"
        val lit = bare.filter { !panelOff(it) && it !in ourBlanks }
        Log.w(TAG, "screens: AYN covered $covered of $builtIn" + if (lit.isEmpty()) "" else " — Wayfinder turns off $lit")
        if (!docked) return@Thread
        for (d in lit) {
            if (d != 0) {
                // the second screen ignores a panel power-off on this ROM (verified 2026-10-03): Wayfinder's black blank —
                // safe there (a TV mirrors the TOP screen, the default display)
                main.post { if (docked && ForegroundAppService.blankForDock(d)) ourBlanks.add(d) }   // still docked (main = update())
                continue
            }
            val phys = dm.getDisplay(d)?.let { ScreenCapture.physicalId(it) } ?: continue
            val r = PServiceBridge.runEntryPoint(a, "DisplayPowerTool", phys, "0")
            if (r?.startsWith("OK") == true) { ourOff[d] = phys; saveOff(a) } else Log.w(TAG, "screens: power off $d failed")
        }
        // undocked while this ran (wakeOurs already went by): what was just turned off comes back now
        if (!docked) { main.post { if (!docked) wakeOurs(a) }; return@Thread }   // (not if docked again meanwhile)
        // Android may power a panel on again (a wake-up, a screen-state change): check again while docked
        main.post {
            if (!docked) return@post
            main.removeCallbacksAndMessages(RECHECK)
            main.postAtTime({ checkScreens(a) }, RECHECK, android.os.SystemClock.uptimeMillis() + 20_000)
        }
    }.apply { isDaemon = true }.start() }

    private val RECHECK = Any()

    /** Undocked: every panel Wayfinder powered off is on again. */
    private fun wakeOurs(a: Context) {
        val blanks = ourBlanks.toList(); ourBlanks.clear()
        if (blanks.isNotEmpty()) main.post { blanks.forEach { ForegroundAppService.wakeFromDock(it) } }
        val off = HashMap(ourOff); ourOff.clear(); saveOff(a)
        if (off.isEmpty()) return
        Thread {
            for ((d, phys) in off) {
                val r = PServiceBridge.runEntryPoint(a, "DisplayPowerTool", phys, "2")
                Log.w(TAG, "screens: display $d back on (${r?.take(2)})")
            }
        }.apply { isDaemon = true }.start()
    }
}
