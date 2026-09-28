package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Two apps opened together, each on its screen (null = leave that screen as is). */
data class AppPair(val id: Long, val top: String?, val bottom: String?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("top", top ?: "").put("bottom", bottom ?: "")

    companion object {
        fun fromJson(o: JSONObject) = AppPair(
            o.optLong("id"), o.optString("top").ifEmpty { null }, o.optString("bottom").ifEmpty { null },
        )
    }
}

/**
 * App pairs + restore the screens after a restart. Pairs are saved from what's
 * on the screens (plug and play: set it up once, save it). The last layout is recorded
 * continuously; at boot, the one from BEFORE the restart is kept aside ([bootLayout])
 * before anything new is recorded, then reopened if the user turned that on.
 */
object Layouts {
    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false

    val pairs = mutableStateListOf<AppPair>()
    var restoreOnBoot by mutableStateOf(false)
        private set

    /** The layout recorded before this boot (what to restore). */
    var bootLayout: Pair<String?, String?> = null to null
        private set

    fun init(ctx: Context) {
        if (initialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_layouts", Context.MODE_PRIVATE)
        restoreOnBoot = prefs.getBoolean("restore_on_boot", false)
        val arr = runCatching { JSONArray(prefs.getString("pairs", "[]")) }.getOrDefault(JSONArray())
        pairs.clear()
        for (i in 0 until arr.length()) runCatching { pairs.add(AppPair.fromJson(arr.getJSONObject(i))) }
        // The layout to restore is captured ONCE per boot and kept in prefs: at boot Android
        // may kill us for memory seconds after starting (seen 2026-09-24, restarted ~30 s
        // later) — the restarted service must still know what was there before the restart.
        val boot = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt("boot_layout_boot", -2) != boot) prefs.edit()
            .putString("boot_top", prefs.getString("last_top", null))
            .putString("boot_bottom", prefs.getString("last_bottom", null))
            .putInt("boot_layout_boot", boot).apply()
        bootLayout = prefs.getString("boot_top", null) to prefs.getString("boot_bottom", null)
        initialized = true
    }

    private fun savePairs() {
        if (!initialized) return
        prefs.edit().putString("pairs", JSONArray().apply { pairs.forEach { put(it.toJson()) } }.toString()).apply()
    }

    /** Adds the pair unless the same one exists; returns false if nothing to save. */
    fun add(top: String?, bottom: String?): Boolean {
        if (top == null && bottom == null) return false
        if (pairs.any { it.top == top && it.bottom == bottom }) return true
        pairs.add(AppPair(System.currentTimeMillis(), top, bottom))
        savePairs()
        return true
    }

    fun remove(id: Long) { pairs.removeAll { it.id == id }; savePairs() }

    fun flip(id: Long) {
        val i = pairs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        pairs[i] = pairs[i].copy(top = pairs[i].bottom, bottom = pairs[i].top)
        savePairs()
    }

    fun setRestore(on: Boolean) {
        restoreOnBoot = on
        if (initialized) prefs.edit().putBoolean("restore_on_boot", on).apply()
    }

    /** What's on the screens now (called when it changes). Screens on their home = null. */
    fun recordLast(top: String?, bottom: String?) {
        if (!initialized || (top == null && bottom == null)) return
        prefs.edit().putString("last_top", top).putString("last_bottom", bottom).apply()
    }

    fun bootRestoreDone(bootCount: Int): Boolean = initialized && prefs.getInt("restored_boot", -1) == bootCount

    /** Restore once per boot. */
    fun claimBootRestore(bootCount: Int): Boolean {
        if (!initialized || prefs.getInt("restored_boot", -1) == bootCount) return false
        prefs.edit().putInt("restored_boot", bootCount).apply()
        return true
    }
}
