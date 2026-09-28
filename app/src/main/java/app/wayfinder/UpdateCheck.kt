package app.wayfinder

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 1.3.1 — "Update available": at most once a day (when the Hub opens), asks GitHub for the latest
 * release's version number. Nothing is sent but the request itself; turned off in Help & status.
 * The Hub shows a small orange-dot pill that opens the release page.
 */
object UpdateCheck {
    private const val TAG = "ThorUpdate"
    const val RELEASES_URL = "https://github.com/Thor-Wayfinder/thor-wayfinder/releases/latest"
    private const val API = "https://api.github.com/repos/Thor-Wayfinder/thor-wayfinder/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A newer version on GitHub ("1.4"), or null. */
    var available by mutableStateOf<String?>(null)
        private set
    var enabled by mutableStateOf(true)
        private set

    private var loaded = false
    @Volatile private var running = false
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("update_check", Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        enabled = p.getBoolean("enabled", true)
        available = p.getString("latest", null)?.takeIf { enabled && newer(it, BuildConfig.VERSION_NAME) }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        load(ctx)
        enabled = on
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) available = null else check(ctx, force = true)
    }

    /** The Hub opened: check if the last check is a day old (or never ran). */
    fun check(ctx: Context, force: Boolean = false) {
        load(ctx)
        if (!enabled || running) return
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("checked_at", 0L) < DAY_MS) return
        running = true
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Wayfinder")
                val tag = c.inputStream.bufferedReader().use { JSONObject(it.readText()).optString("tag_name") }
                c.disconnect()
                val latest = tag.trim().removePrefix("v").removePrefix("V")
                if (latest.isNotEmpty()) {
                    p.edit().putString("latest", latest).putLong("checked_at", System.currentTimeMillis()).apply()
                    val newer = newer(latest, BuildConfig.VERSION_NAME)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { available = if (newer && enabled) latest else null }
                    Log.d(TAG, "latest $latest (this ${BuildConfig.VERSION_NAME}) → ${if (newer) "update" else "up to date"}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "check failed: ${e.message}")       // offline: try again next time the Hub opens
            } finally { running = false }
        }.apply { isDaemon = true }.start()
    }

    /** "1.10" > "1.9", "1.3.1" > "1.3"; anything after a '-' is ignored. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
