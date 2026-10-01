package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.4 (2026-09-30) — Wayfinder's parts, each on or off: the Features page, the tour's
 * first question ("What do you want Wayfinder for?") and the one-time "choose your setup" card after an update.
 *
 * Screen switching is always on (moving and swapping apps, Recents, Home on each screen, the controller between the
 * screens, app pairs). Turning a part off hides it in Wayfinder and stops it — its combos are kept, not deleted, and
 * come back with it. Some parts ARE an existing setting (the quick panel = the AYN button's own switch; Game controls
 * = the input layer's "where it runs"); the others are stored here.
 *
 * An update never changes anything: every part stays on until the user chooses.
 */
object Features {
    private var prefs: SharedPreferences? = null

    /** Combos beyond screen switching (screenshots, brightness, Game controls, Keyboard & mouse…). */
    var moreCombos by mutableStateOf(true)
        private set
    /** Keyboard & mouse (Home + Y): keys, a trackpad and the game's guide on the other screen. */
    var deck by mutableStateOf(true)
        private set
    /** Stick lights: Wayfinder's effects (off = AYN's own lights, untouched). */
    var lights by mutableStateOf(true)
        private set
    /** The setup question was answered (the tour, or the card after an update). */
    var asked by mutableStateOf(true)
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        prefs = p
        moreCombos = p.getBoolean("feat_combos", true)
        deck = p.getBoolean("feat_deck", true)
        lights = p.getBoolean("feat_lights", true)
        asked = p.getBoolean("setup_asked", false)
    }

    fun enableMoreCombos(on: Boolean) { moreCombos = on; save("feat_combos", on); ControlsStore.version.intValue++ }
    fun enableDeck(on: Boolean) { deck = on; save("feat_deck", on); ControlsStore.version.intValue++ }
    fun enableLights(ctx: Context, on: Boolean) {
        lights = on; save("feat_lights", on)
        ForegroundAppService.reapplyLights()
    }
    fun markAsked() { asked = true; save("setup_asked", true) }
    private fun save(k: String, v: Boolean) { prefs?.edit()?.putBoolean(k, v)?.apply() }

    /** The actions of screen switching — the combos that stay when "more combos" is off. */
    val SCREEN_ACTIONS = setOf(
        ThorAction.BACK, ThorAction.RECENTS, ThorAction.CLEAR_BACKGROUND, ThorAction.SWAP_OR_SEND,
        ThorAction.FOCUS_SWITCH_UP, ThorAction.FOCUS_SWITCH_DOWN, ThorAction.FOCUS_LOCK_TOGGLE,
        ThorAction.HOME_TOP, ThorAction.HOME_BOTTOM, ThorAction.HOME_BOTH, ThorAction.HOME_HERE,
        ThorAction.CLOSE_APP, ThorAction.CLOSE_OTHER, ThorAction.OPEN, ThorAction.TOGGLE_SECOND_SCREEN,
    )

    /** A combo's action is in use with the parts on now. */
    fun allows(a: ThorAction): Boolean = when {
        a == ThorAction.KEYBOARD -> deck && moreCombos
        a == ThorAction.QUICK_MENU -> AppSettings.aynButtonOurs || moreCombos
        a == ThorAction.GAME_CONTROLS || a == ThorAction.GYRO_TOGGLE -> PadLayerCtl.mode != PadLayerCtl.Mode.NOWHERE && moreCombos
        a in SCREEN_ACTIONS -> true
        else -> moreCombos
    }

    /** Game controls (the input layer) are on somewhere. */
    val gameControls: Boolean get() = PadLayerCtl.mode != PadLayerCtl.Mode.NOWHERE

    /** "Just switching screens": the core only — the AYN button stays AYN's, no input layer, no Keyboard & mouse,
     *  no Wayfinder lights, no "Your controls" sheet. */
    fun justScreens(ctx: Context) {
        // what "Everything" puts back later
        if (PadLayerCtl.mode != PadLayerCtl.Mode.NOWHERE)
            prefs?.edit()?.putString("lite_layer_before", PadLayerCtl.mode.name)?.putBoolean("lite_sheet_before", AppSettings.controlsSheet)?.apply()
        enableMoreCombos(false); enableDeck(false); enableLights(ctx, false)
        AppSettings.setAynButtonOursOn(false)
        PadLayerCtl.setMode(ctx, PadLayerCtl.Mode.NOWHERE)
        AppSettings.setControlsSheetOn(false)
        markAsked()
    }

    /** "Everything": every part on; the input layer in the games you set up (or as it was, if it was on). */
    fun everything(ctx: Context) {
        enableMoreCombos(true); enableDeck(true); enableLights(ctx, true)
        AppSettings.setAynButtonOursOn(true)
        AppSettings.setControlsSheetOn(prefs?.getBoolean("lite_sheet_before", true) ?: true)
        if (PadLayerCtl.mode == PadLayerCtl.Mode.NOWHERE) {
            val before = runCatching { PadLayerCtl.Mode.valueOf(prefs?.getString("lite_layer_before", null)!!) }.getOrNull()
            PadLayerCtl.setMode(ctx, before ?: PadLayerCtl.Mode.SET_UP)
        }
        markAsked()
    }
}
