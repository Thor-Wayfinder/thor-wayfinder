package app.wayfinder.lights

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * The global stick-light profile (apps can override it in App profiles).
 * Plug and play: the default is AYN's own lighting, untouched, until the user picks.
 */
object LightSettings {
    private lateinit var prefs: SharedPreferences

    var global by mutableStateOf(LightProfile())
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_lights", Context.MODE_PRIVATE)
        global = runCatching { LightProfile.fromJson(JSONObject(prefs.getString("global", "{}") ?: "{}")) }.getOrNull()
            ?: LightProfile()
    }

    fun setGlobalProfile(p: LightProfile) {
        global = p
        prefs.edit().putString("global", p.toJson().toString()).apply()
    }
}
