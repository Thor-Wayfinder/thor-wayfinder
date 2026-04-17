package com.thorwayfinder.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shell-level operations via Shizuku (UID 2000).
 *
 * Shizuku must be running and permission granted before calling any method.
 * All methods are blocking — call from a background thread.
 */
object ShizukuHelper {

    private const val TAG = "ThorShizuku"

    // ── availability ────────────────────────────────────────────────────

    /** True if Shizuku service is reachable. */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** True if we already have Shizuku permission. */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    // ── shell execution ─────────────────────────────────────────────────

    /** Run a shell command via Shizuku (UID 2000). Returns (exitCode, stdout). */
    private fun exec(vararg cmd: String): Pair<Int, String> {
        // Shizuku.newProcess is private in 13.1.5 — access via reflection
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf(*cmd), null, null) as Process
        val stdout = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return exit to stdout
    }

    // ── force-stop ────────────────────────────────────────────────────

    /**
     * `am force-stop <pkg>` with shell authority.
     * Unlike the app-level call, this should actually succeed (exit=0) from UID 2000.
     */
    fun forceStop(pkg: String): Boolean {
        val (exit, _) = exec("am", "force-stop", pkg)
        Log.d(TAG, "am force-stop $pkg → exit=$exit")
        return exit == 0
    }

    // ── start on display ─────────────────────────────────────────────

    /**
     * `am start --display <id> -n <component>` with shell authority.
     * Moves the existing task to the target display without creating duplicates.
     * Works for singleTask apps that ignore trampoline display hints.
     */
    fun startOnDisplay(context: Context, pkg: String, displayId: Int): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        val component = launchIntent?.component
        if (component == null) {
            Log.w(TAG, "No launch component for $pkg")
            return false
        }
        val (exit, stdout) = exec(
            "am", "start", "--display", displayId.toString(),
            "-n", "${component.packageName}/${component.className}"
        )
        Log.d(TAG, "am start --display $displayId $pkg → exit=$exit" +
            if (stdout.isNotBlank()) " ($stdout)" else "")
        return exit == 0
    }
}
