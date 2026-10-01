package app.wayfinder.deck

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One button of the user's own pad: types [text], or presses [code] with [meta]. */
data class CustomKey(val label: String, val text: String? = null, val code: Int = 0, val meta: Int = 0) {
    fun toJson() = JSONObject().put("label", label).put("text", text).put("code", code).put("meta", meta)
    fun toDeckKey() = DeckKey(label, code = code, text = text, meta = meta,
        caption = text?.let { if (it.length > 14) it.take(14) + "…" else it } ?: app.wayfinder.comboLabel(code, meta))

    companion object {
        fun fromJson(o: JSONObject) = CustomKey(
            o.getString("label"), o.optString("text").takeIf { o.has("text") && !o.isNull("text") && it.isNotEmpty() },
            o.optInt("code"), o.optInt("meta"),
        )
    }
}

/**
 * Input-deck preferences. Plug and play: by default the deck opens on the pad that
 * fits the app in front ([recommendedPad]); switching pads in an app remembers that
 * choice for that app. [autoPick] off = always open the last pad used.
 */
object DeckSettings {
    private lateinit var prefs: SharedPreferences

    var autoPick by mutableStateOf(true)
        private set
    var lastPad by mutableStateOf(PAD_PC)
        private set
    var custom by mutableStateOf(listOf<CustomKey>())
        private set
    /** 1.3 (GitHub #26): the PC keys in their Simple layout ([PC_SIMPLE]). */
    var simpleKeys by mutableStateOf(false)
        private set
    /** 1.3 (GitHub #36): the trackpad sends finger touches (a pointer on the game's screen), not a mouse. */
    var touchMode by mutableStateOf(false)
        private set
    /** 1.4 (GitHub #65): Touch mode as Direct — the pad maps onto the game's screen, one finger touches / swipes there. */
    var directTouch by mutableStateOf(false)
        private set
    fun chooseDirect(on: Boolean) { directTouch = on; prefs.edit().putBoolean("direct_touch", on).apply() }
    private val perApp = HashMap<String, String>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_deck", Context.MODE_PRIVATE)
        autoPick = prefs.getBoolean("auto_pick", true)
        simpleKeys = prefs.getBoolean("simple_keys", false)
        touchMode = prefs.getBoolean("touch_mode", false)
        directTouch = prefs.getBoolean("direct_touch", false)
        lastPad = prefs.getString("last_pad", PAD_PC) ?: PAD_PC
        runCatching {
            val o = JSONObject(prefs.getString("per_app", "{}") ?: "{}")
            o.keys().forEach { perApp[it] = o.getString(it) }
        }
        custom = runCatching {
            val a = JSONArray(prefs.getString("custom", "[]") ?: "[]")
            (0 until a.length()).map { CustomKey.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** The pad to open for [pkg]. */
    fun padFor(ctx: Context, pkg: String?): String {
        if (!autoPick) return lastPad
        pkg?.let { perApp[it] }?.let { return it }
        val category = pkg?.let { runCatching { ctx.packageManager.getApplicationInfo(it, 0).category }.getOrNull() }
        return recommendedPad(pkg, category)
    }

    /** The user picked [padId] while [pkg] was in front. */
    fun choose(pkg: String?, padId: String) {
        lastPad = padId
        if (pkg != null) perApp[pkg] = padId
        prefs.edit().putString("last_pad", padId)
            .putString("per_app", JSONObject(perApp as Map<*, *>).toString()).apply()
    }

    fun setAutoPickOn(on: Boolean) { autoPick = on; prefs.edit().putBoolean("auto_pick", on).apply() }

    fun chooseSimpleKeys(on: Boolean) { simpleKeys = on; prefs.edit().putBoolean("simple_keys", on).apply() }
    fun chooseTouchMode(on: Boolean) { touchMode = on; prefs.edit().putBoolean("touch_mode", on).apply() }

    fun setCustomKeys(keys: List<CustomKey>) {
        custom = keys
        prefs.edit().putString("custom", JSONArray(keys.map { it.toJson() }).toString()).apply()
    }

    /** A fixed grid (4 wide, at least 3 rows) so a few buttons stay button-sized; blanks stay empty. */
    fun customPad(): DeckPad {
        if (custom.isEmpty()) return DeckPad(PAD_CUSTOM, "My pad", emptyList())
        val keys = custom.map { it.toDeckKey() }
        val cells = maxOf(12, (keys.size + 3) / 4 * 4)
        val grid = (keys + List(cells - keys.size) { DeckKey("") }).chunked(4)
        return DeckPad(PAD_CUSTOM, "My pad", grid)
    }
}
