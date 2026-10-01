package app.wayfinder

import android.content.Context
import android.os.SystemClock

/**
 * 1.4 — another CPU / GPU tuner on the Thor. ClusterTune and Pulse set the clocks themselves (through AYN's system
 * service too) and have their own per-app profiles. When one is installed, compatibility comes first: Wayfinder writes
 * nothing that touches the CPU — no per-game AYN performance mode, and the quick panel's Performance tile steps aside
 * (AYN's Medium / High raise the minimum speeds above the tuner's limits: the clusters would sit pinned at them, hot).
 * Wayfinder never writes clocks or the GPU itself. With Pulse (it also sets the fan and the refresh rate), the per-game
 * fan and refresh rate stand down too. Nothing is deleted: the choices come back if the tuner is uninstalled.
 */
object Tuners {
    private val KNOWN = linkedMapOf("com.aure.clustertune" to "ClusterTune", "com.kei.pulse" to "Pulse")
    @Volatile private var cached: String? = null
    @Volatile private var at = -1_000_000L

    /** The tuner installed on this Thor ("ClusterTune" / "Pulse"), or null. Cached a few seconds. */
    fun installed(ctx: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - at > 5_000) {
            cached = KNOWN.entries.firstOrNull { (pkg, _) ->
                runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)
            }?.value
            at = now
        }
        return cached
    }

    /** Pulse also sets the fan mode and the refresh rate: then those stand down too. */
    fun ownsFanAndHz(ctx: Context) = installed(ctx) == "Pulse"

    /** One line where performance would be. */
    fun note(name: String) = if (name == "Pulse") "Pulse is installed: it manages the CPU, GPU, fan and refresh rate, so " +
        "Wayfinder's per-game performance, fan and refresh rate are off — the two never fight. Use Pulse for those."
        else "$name is installed: it manages the CPU, so Wayfinder's per-game performance is off — the two never fight. " +
        "Use $name's own profiles; fan and refresh rate here still work."

    private val POLICIES = intArrayOf(0, 3, 7)
    /** The CPU's top speeds in force now (kHz, small · middle · big), whoever set them; null when unreadable. */
    fun cpuLimitsNow(): List<Int>? = POLICIES.map {
        val f = "/sys/devices/system/cpu/cpufreq/policy$it/scaling_max_freq"
        runCatching { java.io.File(f).readText().trim().toInt() }.getOrNull()
            // after a boot policy0's node can be 0660 (unreadable for apps): ask the system service
            ?: PServiceBridge.exec("cat $f")?.trim()?.toIntOrNull() ?: return null
    }

    /** The CPU's own top speeds (kHz), to tell "usual" from "limited". */
    fun cpuMaxima(): List<Int>? = POLICIES.map {
        runCatching { java.io.File("/sys/devices/system/cpu/cpufreq/policy$it/cpuinfo_max_freq").readText().trim().toInt() }
            .getOrNull() ?: return null
    }
}
