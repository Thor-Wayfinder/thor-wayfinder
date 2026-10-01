package app.wayfinder

import java.util.concurrent.ConcurrentHashMap

/**
 * 1.3 — does RetroArch use the controller's Back itself? Its config (`retroarch.cfg`, in its Android/data
 * folder: only readable through the root bridge) binds pad buttons by Android keycode; Back = 4. A
 * hotkey-enable, menu toggle or any hotkey on "4" → RetroArch needs Back (held too), so Wayfinder leaves
 * it alone there ([ControlsStore.backToGame]). Cached a minute; unknown = no (Wayfinder's Back as usual).
 */
object RetroArchBack {
    private class Entry(val uses: Boolean, val at: Long, val held: Boolean = false)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val asking = ConcurrentHashMap.newKeySet<String>()

    fun usesBack(pkg: String): Boolean {
        val e = cache[pkg]
        if (e == null || android.os.SystemClock.uptimeMillis() - e.at > 60_000) refresh(pkg)
        return e?.uses ?: false
    }

    /** 1.4 (review): RetroArch HOLDS Back — hotkey enable or a "hold" function on it (Back + a button, or a long
     *  hold): Back must be RetroArch's whole, even with the input layer. */
    fun holdsBack(pkg: String): Boolean {
        val e = cache[pkg]
        if (e == null || android.os.SystemClock.uptimeMillis() - e.at > 60_000) refresh(pkg)
        return e?.held ?: false
    }

    private fun refresh(pkg: String) {
        if (!Shell.isPkg(pkg) || !asking.add(pkg)) return
        Thread {
            try {
                // the root daemon sees shared storage as /data/media/0 (/storage/emulated/0 is a per-app mount)
                val cfgs = listOf("/data/media/0", "/storage/emulated/0").joinToString(" ") { Shell.q("$it/Android/data/$pkg/files/retroarch.cfg") }
                // every input on Back ("4"), by name — on ONE line: the system service returns only the first line
                val names = PServiceBridge.exec("cat $cfgs 2>/dev/null | grep -oE '^input_[a-z0-9_]+_btn = \"4\"' | cut -d' ' -f1 | tr '\\n' ' '")
                    ?.split(Regex("\\s+"))?.filter { it.startsWith("input_") }.orEmpty()
                val uses = names.isNotEmpty()
                val held = names.any { it == "input_enable_hotkey_btn" || it.startsWith("input_hold_") }
                val was = cache[pkg]
                cache[pkg] = Entry(uses, android.os.SystemClock.uptimeMillis(), held)
                if (was?.uses != uses || was.held != held) {
                    ForegroundAppService.reapplyPadProfile()
                    // a page showing the setting redraws with the answer
                    android.os.Handler(android.os.Looper.getMainLooper()).post { AppConfigStore.version.intValue++ }
                }
            } finally { asking.remove(pkg) }
        }.apply { isDaemon = true }.start()
    }
}
