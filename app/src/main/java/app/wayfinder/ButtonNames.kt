package app.wayfinder

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.3 (GitHub #27) — what the four face buttons are CALLED on screen. The Thor prints them the Nintendo
 * way (A on the right); in AYN's Xbox style players think "A = bottom". Automatic (default) = follow
 * AYN's controller style; or always as printed; or always the Xbox way. Only names change: a combo stays
 * on the same physical button ([ThorButton.label] is the name, [ThorButton.printed] the letter on the Thor).
 */
object ButtonNames {
    /** 0 Automatic · 1 as printed · 2 Xbox. */
    var mode by mutableIntStateOf(0)
        private set
    /** AYN's controller style is Xbox (Settings.System temp_abxy_layout_mode = 0), kept up to date. */
    var aynXbox by mutableStateOf(false)
        private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        mode = prefs!!.getInt("button_names", 0).coerceIn(0, 2)
        fun read() = runCatching { Settings.System.getInt(app.contentResolver, "temp_abxy_layout_mode", 1) == 0 }.getOrDefault(false)
        aynXbox = read()
        runCatching {
            app.contentResolver.registerContentObserver(Settings.System.getUriFor("temp_abxy_layout_mode"), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) { aynXbox = read() } })
        }
    }

    fun choose(m: Int) { mode = m.coerceIn(0, 2); prefs?.edit()?.putInt("button_names", mode)?.apply() }

    /** Names the Xbox way right now. */
    val xbox: Boolean get() = when (mode) { 1 -> false; 2 -> true; else -> aynXbox }

    /** The name of a PRINTED button. */
    fun of(b: ThorButton): String = shown(b).printed

    /** The button whose printed letter is the name shown for [b] (for its glyph colour). */
    fun shown(b: ThorButton): ThorButton = if (!xbox) b else faceSwap(b)

    /** Nintendo ↔ Xbox positions: A ↔ B, X ↔ Y (anything else unchanged). */
    fun faceSwap(b: ThorButton): ThorButton = when (b) {
        ThorButton.A -> ThorButton.B; ThorButton.B -> ThorButton.A
        ThorButton.X -> ThorButton.Y; ThorButton.Y -> ThorButton.X
        else -> b
    }

    /** The name of the button that does a menu job ("A" = confirm, "B" = back, "X", "Y") right now. */
    fun m(job: String): String = runCatching { ButtonEngine.menuSwap(ThorButton.valueOf(job)).label }.getOrDefault(job)
}
