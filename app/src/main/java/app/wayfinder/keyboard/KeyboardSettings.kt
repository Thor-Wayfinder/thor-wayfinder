package app.wayfinder.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where the keyboard appears when the text field is on the top screen. */
enum class KeyboardPlacement(val label: String) { OTHER_SCREEN("Bottom screen"), SAME_SCREEN("Same screen") }

/**
 * Keyboard preferences. Plug and play: until the user picks, the languages come from
 * the device's own language list (plus English) and the keyboard opens on the bottom
 * screen whenever the text field is on the top one.
 */
object KeyboardSettings {
    private lateinit var prefs: SharedPreferences

    var layoutIds by mutableStateOf(listOf("en_US"))
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var placement by mutableStateOf(KeyboardPlacement.OTHER_SCREEN)
        private set
    /** 1.3 (GitHub #11): the D-pad picks the keys and the left stick moves the text cursor (default: the other way). */
    var dpadKeys by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_keyboard", Context.MODE_PRIVATE)
        val saved = prefs.getString("layouts", null)?.split(',')?.filter { layoutById(it) != null }
        layoutIds = saved?.ifEmpty { null } ?: run {
            val locales = ctx.resources.configuration.locales
            defaultLayoutIds((0 until locales.size()).map { locales[it] })
        }
        currentId = prefs.getString("current", null)
        placement = runCatching { KeyboardPlacement.valueOf(prefs.getString("placement", null) ?: "") }
            .getOrDefault(KeyboardPlacement.OTHER_SCREEN)
        dpadKeys = prefs.getBoolean("dpad_keys", false)
    }

    fun chooseDpadKeys(on: Boolean) {
        dpadKeys = on
        prefs.edit().putBoolean("dpad_keys", on).apply()
    }

    fun setLayouts(ids: List<String>) {
        layoutIds = ids.ifEmpty { listOf("en_US") }
        prefs.edit().putString("layouts", layoutIds.joinToString(",")).apply()
    }

    fun toggleLayout(id: String) =
        setLayouts(if (id in layoutIds) layoutIds - id else layoutIds + id)

    fun setCurrent(id: String) {
        currentId = id
        prefs.edit().putString("current", id).apply()
    }

    fun setPlacementMode(p: KeyboardPlacement) {
        placement = p
        prefs.edit().putString("placement", p.name).apply()
    }
}
