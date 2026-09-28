package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Steam-controller-style button configurator: data model + storage.
 *
 * A [Binding] maps a [Trigger] to a [ThorAction]. Two trigger shapes:
 *  - SYSTEM-BUTTON presses — [ThorButton.HOME] / [ThorButton.BACK] with tap, double,
 *    triple or hold. These are swallowed and, when not bound, re-performed through
 *    the accessibility global actions — so taking them over never loses Home/Back.
 *  - CHORDS — hold a modifier, press a button ("R3 + ↑", "Home + Y"). If the
 *    modifier is Home/Back it is swallowed too (games never see the chord — the
 *    Steam-button model); a gamepad modifier (R3…) still reaches the game with no
 *    added latency, but the second button is swallowed. The D-pad can't be
 *    swallowed (it's a HAT axis, not a key).
 */
enum class ThorButton(val printed: String, val keyCode: Int, val hatAxis: Int = -1, val hatValue: Int = 0) {
    A("A", KeyEvent.KEYCODE_BUTTON_A), B("B", KeyEvent.KEYCODE_BUTTON_B),
    X("X", KeyEvent.KEYCODE_BUTTON_X), Y("Y", KeyEvent.KEYCODE_BUTTON_Y),
    L1("L1", KeyEvent.KEYCODE_BUTTON_L1), R1("R1", KeyEvent.KEYCODE_BUTTON_R1),
    L2("L2", KeyEvent.KEYCODE_BUTTON_L2), R2("R2", KeyEvent.KEYCODE_BUTTON_R2),
    L3("L3", KeyEvent.KEYCODE_BUTTON_THUMBL), R3("R3", KeyEvent.KEYCODE_BUTTON_THUMBR),
    SELECT("Select", KeyEvent.KEYCODE_BUTTON_SELECT), START("Start", KeyEvent.KEYCODE_BUTTON_START),
    HOME("Home", KeyEvent.KEYCODE_HOME), BACK("Back", KeyEvent.KEYCODE_BACK),
    UP("↑", -1, ThorInput.ABS_HAT_Y, -1), DOWN("↓", -1, ThorInput.ABS_HAT_Y, 1),
    LEFT("←", -1, ThorInput.ABS_HAT_X, -1), RIGHT("→", -1, ThorInput.ABS_HAT_X, 1),
    // Stick FLICKS (input layer, phase 1): a quick push of a stick past 60 %, re-armed below
    // 40 %. Only as the 2nd button of a Home/Back combo — "Home + right stick ↑".
    RS_UP("Right stick ↑", -1), RS_DOWN("Right stick ↓", -1), RS_LEFT("Right stick ←", -1), RS_RIGHT("Right stick →", -1),
    LS_UP("Left stick ↑", -1), LS_DOWN("Left stick ↓", -1), LS_LEFT("Left stick ←", -1), LS_RIGHT("Left stick →", -1);

    /** 1.3 (GitHub #27): the name on screen — the face buttons can be named the Xbox way ([ButtonNames]). */
    val label: String get() = ButtonNames.of(this)

    /** The button in words, for hints ("D-pad up", "Right stick left"): the arrow glyphs were
     *  hard to read in the on-screen hints (2026-09-24). */
    val spoken: String get() = when (this) {
        UP -> "D-pad up"; DOWN -> "D-pad down"; LEFT -> "D-pad left"; RIGHT -> "D-pad right"
        else -> label.replace("↑", "up").replace("↓", "down").replace("←", "left").replace("→", "right")
    }

    val isSystem get() = this == HOME || this == BACK
    val isDpad get() = hatAxis >= 0
    val isFlick get() = name.startsWith("RS_") || name.startsWith("LS_")

    companion object {
        fun fromKeyCode(code: Int): ThorButton? = values().firstOrNull { it.keyCode == code && it.keyCode >= 0 }
        fun fromHat(axis: Int, value: Int): ThorButton? =
            values().firstOrNull { it.hatAxis == axis && it.hatValue == value }
    }
}

enum class Press(val label: String) { TAP("tap"), DOUBLE_TAP("double-tap"), TRIPLE_TAP("triple-tap"), HOLD("hold") }

data class Trigger(val button: ThorButton, val press: Press = Press.TAP, val modifier: ThorButton? = null) {
    val isChord get() = modifier != null
    // in words, never arrows: shown in hints, lists and the tour (2026-09-25)
    fun label(): String = if (modifier != null) "${modifier.spoken} + ${button.spoken}" else "${button.spoken} · ${press.label}"

    fun toJson(): JSONObject = JSONObject().put("button", button.name).put("press", press.name)
        .apply { if (modifier != null) put("modifier", modifier.name) }

    companion object {
        fun fromJson(o: JSONObject): Trigger? = runCatching {
            Trigger(
                ThorButton.valueOf(o.getString("button")),
                Press.valueOf(o.optString("press", "TAP")),
                o.optString("modifier", "").takeIf { it.isNotEmpty() }?.let { ThorButton.valueOf(it) },
            )
        }.getOrNull()
    }
}

/** [arg]: only for [ThorAction.OPEN] — what it opens ([OpenTargets]). */
data class Binding(val trigger: Trigger, val action: ThorAction, val arg: String? = null)

object ControlsStore {
    private const val KEY = "bindings_v1"
    private lateinit var prefs: SharedPreferences
    @Volatile private var bindings: List<Binding> = defaults()

    /** Bumps on every change so Compose screens can observe it. */
    val version = mutableIntStateOf(0)

    /** Today's behaviour, expressed as bindings — resetting restores exactly this. */
    fun defaults(): List<Binding> = listOf(
        Binding(Trigger(ThorButton.BACK, Press.TAP), ThorAction.BACK),
        Binding(Trigger(ThorButton.BACK, Press.DOUBLE_TAP), ThorAction.RECENTS),
        Binding(Trigger(ThorButton.BACK, Press.TRIPLE_TAP), ThorAction.CLEAR_BACKGROUND),
        Binding(Trigger(ThorButton.BACK, Press.HOLD), ThorAction.SWAP_OR_SEND),
        // Rev 5 — system shortcuts on Home/Back only (input layer plan §4): the controller moves
        // with Home + a flick of the right stick (was R3 + D-pad, which reached the game), and
        // Home + R3 locks it.
        Binding(Trigger(ThorButton.RS_UP, modifier = ThorButton.HOME), ThorAction.FOCUS_SWITCH_UP),
        Binding(Trigger(ThorButton.RS_DOWN, modifier = ThorButton.HOME), ThorAction.FOCUS_SWITCH_DOWN),
        Binding(Trigger(ThorButton.R3, modifier = ThorButton.HOME), ThorAction.FOCUS_LOCK_TOGGLE),
        // Rev 2 — screenshot on Home + R1 (the Steam Deck's Steam + R1).
        Binding(Trigger(ThorButton.R1, modifier = ThorButton.HOME), ThorAction.SCREENSHOT),
        // Rev 3 — the input deck (keys / trackpad / pads for the game) on Home + Y.
        Binding(Trigger(ThorButton.Y, modifier = ThorButton.HOME), ThorAction.KEYBOARD),
        // Rev 4 — brightness of the screen with the controller.
        Binding(Trigger(ThorButton.R2, modifier = ThorButton.HOME), ThorAction.BRIGHTER),
        Binding(Trigger(ThorButton.L2, modifier = ThorButton.HOME), ThorAction.DIMMER),
        // Rev 6 — the game's controls (remap, gyro, macros) while playing (plan §6h).
        Binding(Trigger(ThorButton.X, modifier = ThorButton.HOME), ThorAction.GAME_CONTROLS),
    )

    /**
     * Defaults introduced after a user's bindings were first saved: added once, only
     * if neither the trigger nor the action is already in use (never overrides a
     * user's choice). Bump [DEFAULTS_REV] when adding a default.
     */
    private const val DEFAULTS_REV = 6
    private val addedInRev = mapOf(
        2 to listOf(Binding(Trigger(ThorButton.R1, modifier = ThorButton.HOME), ThorAction.SCREENSHOT)),
        3 to listOf(Binding(Trigger(ThorButton.Y, modifier = ThorButton.HOME), ThorAction.KEYBOARD)),
        4 to listOf(
            Binding(Trigger(ThorButton.R2, modifier = ThorButton.HOME), ThorAction.BRIGHTER),
            Binding(Trigger(ThorButton.L2, modifier = ThorButton.HOME), ThorAction.DIMMER),
        ),
        5 to listOf(Binding(Trigger(ThorButton.R3, modifier = ThorButton.HOME), ThorAction.FOCUS_LOCK_TOGGLE)),
        6 to listOf(Binding(Trigger(ThorButton.X, modifier = ThorButton.HOME), ThorAction.GAME_CONTROLS)),
    )

    /**
     * Defaults that MOVED to another trigger: moved only if the user still has the old default
     * exactly (their own choice is never touched) and nothing else uses the new trigger.
     */
    private val movedInRev = mapOf(
        5 to listOf(
            Binding(Trigger(ThorButton.UP, modifier = ThorButton.R3), ThorAction.FOCUS_SWITCH_UP) to
                Trigger(ThorButton.RS_UP, modifier = ThorButton.HOME),
            Binding(Trigger(ThorButton.DOWN, modifier = ThorButton.R3), ThorAction.FOCUS_SWITCH_DOWN) to
                Trigger(ThorButton.RS_DOWN, modifier = ThorButton.HOME),
        ),
    )

    private fun migrate() {
        val rev = prefs.getInt("bindings_defaults_rev", 1)
        if (rev >= DEFAULTS_REV) return
        var list = bindings
        for (r in (rev + 1)..DEFAULTS_REV) {
            movedInRev[r].orEmpty().forEach { (old, to) ->
                if (old in list && list.none { it.trigger == to }) list = list.map { if (it == old) Binding(to, old.action) else it }
            }
            addedInRev[r].orEmpty().forEach { b ->
                if (list.none { it.trigger == b.trigger || it.action == b.action }) list = list + b
            }
        }
        prefs.edit().putInt("bindings_defaults_rev", DEFAULTS_REV).apply()
        if (list != bindings) save(list)
    }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { s ->
            runCatching {
                val arr = JSONArray(s)
                bindings = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val t = Trigger.fromJson(o.getJSONObject("trigger")) ?: return@mapNotNull null
                    val a = runCatching { ThorAction.valueOf(o.getString("action")) }.getOrNull() ?: return@mapNotNull null
                    val arg = o.optString("arg").ifEmpty { null }
                    if (a == ThorAction.OPEN && !OpenTargets.valid(arg)) return@mapNotNull null   // never a free-form string
                    Binding(t, a, arg.takeIf { a == ThorAction.OPEN })
                }
            }
            migrate()
        } ?: prefs.edit().putInt("bindings_defaults_rev", DEFAULTS_REV).apply()   // fresh install: defaults are current
    }

    fun all(): List<Binding> = bindings

    /** Only bindings whose action actually does something today. */
    fun active(): List<Binding> = bindings.filter { ActionRegistry.isImplemented(it.action) }

    fun triggerFor(action: ThorAction): Trigger? = bindings.firstOrNull { it.action == action }?.trigger

    /**
     * The bindings in force while [pkg] has the controller: global, unless the
     * app is OFF (nothing) or CUSTOM (its overrides replace global bindings with the
     * same trigger or action; its freed triggers are dropped).
     */
    fun effective(pkg: String?): List<Binding> {
        val all = effectiveAll(pkg)
        // 1.3: where Back goes to the game, no combo uses Back (its press, hold, taps, or as a modifier)
        return if (pkg != null && backToGame(pkg)) all.filter { it.trigger.button != ThorButton.BACK && it.trigger.modifier != ThorButton.BACK }
        else all
    }

    /** 1.3 — the controller's own Back goes straight to [pkg] (an app, or a game profile's key):
     *  the app's own choice, else automatic — on for RetroArch when RetroArch itself uses Back (its menu
     *  or a hotkey on Back, GitHub #1 / Reddit); else Wayfinder's Back (hold = move / swap) as usual. */
    fun backToGame(pkg: String): Boolean {
        val app = pkg.substringBefore('#')
        return AppConfigStore.get(app).backToGame ?: (app.startsWith("com.retroarch") && RetroArchBack.usesBack(app))
    }

    private fun effectiveAll(pkg: String?): List<Binding> {
        val cfg = pkg?.let { AppConfigStore.get(it) } ?: return active()
        return when (cfg.buttonsMode) {
            ButtonsMode.NORMAL -> active()
            ButtonsMode.OFF -> emptyList()
            ButtonsMode.CUSTOM -> {
                val oT = cfg.buttons.map { it.trigger }.toSet() + cfg.freed
                val oA = cfg.buttons.map { it.action }.toSet() - ThorAction.OPEN
                (active().filter { it.trigger !in oT && it.action !in oA } + cfg.buttons)
                    .filter { ActionRegistry.isImplemented(it.action) }
            }
        }
    }

    /** Trigger for [action] as [pkg] sees it (for the per-app Controls page). */
    fun effectiveTriggerFor(pkg: String?, action: ThorAction): Trigger? =
        effective(pkg).firstOrNull { it.action == action }?.trigger

    /** Bind [action] to [trigger]; whatever used that trigger before is unbound. */
    fun bind(action: ThorAction, trigger: Trigger) =
        save(bindings.filter { it.action != action && it.trigger != trigger } + Binding(trigger, action))

    fun unbind(action: ThorAction) = save(bindings.filter { it.action != action })

    /** Round 8 — "Open…" combos: several, each with its target. [old] = the trigger being changed. */
    fun opens(): List<Binding> = bindings.filter { it.action == ThorAction.OPEN }
    fun bindOpen(arg: String, trigger: Trigger, old: Trigger? = null) {
        if (!OpenTargets.valid(arg)) return
        save(bindings.filter { it.trigger != trigger && !(it.action == ThorAction.OPEN && it.trigger == old) } + Binding(trigger, ThorAction.OPEN, arg))
    }
    fun unbindOpen(trigger: Trigger) = save(bindings.filter { !(it.action == ThorAction.OPEN && it.trigger == trigger) })

    fun resetDefaults() = save(defaults())

    fun actionUsing(trigger: Trigger): ThorAction? = bindings.firstOrNull { it.trigger == trigger }?.action

    private fun save(list: List<Binding>) {
        bindings = list
        if (::prefs.isInitialized) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("trigger", it.trigger.toJson()).put("action", it.action.name).apply { it.arg?.let { a -> put("arg", a) } }) }
            prefs.edit().putString(KEY, arr.toString()).apply()
        }
        version.intValue++
    }
}
