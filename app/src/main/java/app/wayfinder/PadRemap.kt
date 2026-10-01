package app.wayfinder

import android.view.KeyEvent
import org.json.JSONObject

/**
 * Input layer, phase 2 — what one app's controls become.
 * Buttons are PRINTED buttons (as on the Thor), whatever AYN's layout. Home and Back are never
 * remapped. Turned into the mapping engine's tokens by [engineTokens]
 * (fx/wfmap.h `wf_parse`); keyboard keys and Wayfinder actions are done by the app ("X" lines).
 */
sealed class RemapTarget {
    /** Another button of the pad (or a D-pad direction: [ThorButton.UP]…[ThorButton.RIGHT]). */
    data class Button(val b: ThorButton) : RemapTarget()
    object None : RemapTarget()
    /** A keyboard key (Android keycode + meta state: Ctrl / Alt / Shift). */
    data class Key(val code: Int, val meta: Int = 0) : RemapTarget()
    data class Action(val a: ThorAction) : RemapTarget()
    /** Several keyboard keys, in order (Ctrl + Shift + Esc): pressed in that order, held
     *  together, released in reverse — or, [inOrder], typed one after another. Modifiers are
     *  real keys here (Ctrl, Shift, Alt, Win). */
    data class Keys(val codes: List<Int>, val inOrder: Boolean = false) : RemapTarget()
    /** Several pad buttons at once (Select + Start) — Wayfinder's virtual presses (wfpad "p"). */
    data class Buttons(val bs: List<ThorButton>) : RemapTarget()
    /** A macro: steps played in order, once per press or again and again while held.
     *  [armed] false = kept but not played: an IMPORTED macro stays off until the user has
     *  looked at its steps and turned it on (2026-09-25). */
    data class Macro(val steps: List<MacroStep>, val repeat: Boolean = false, val armed: Boolean = true) : RemapTarget()
    /** A mouse button (0 left, 1 right, 2 middle) or the wheel (3 up, 4 down) — the input
     *  deck's virtual mouse. */
    data class Mouse(val b: Int) : RemapTarget()

    fun label(): String = when (this) {
        is Button -> b.spoken
        None -> "Nothing"
        is Key -> keyLabel(code, meta)
        is Keys -> codes.joinToString(if (inOrder) " › " else " + ") { keyName(it) }
        is Buttons -> bs.joinToString(" + ") { it.spoken }
        is Macro -> "Macro · ${steps.size} step${if (steps.size == 1) "" else "s"}${if (repeat) " · repeats" else ""}${if (!armed) " · off" else ""}"
        is Action -> a.title
        is Mouse -> MOUSE_NAMES.getOrElse(b) { "Mouse" }
    }

    fun token(): String = when (this) {
        is Button -> "btn:${b.name}"
        None -> "none"
        is Key -> "key:$code:$meta"
        is Keys -> (if (inOrder) "keyseq:" else "keys:") + codes.joinToString(",")
        is Buttons -> "btns:" + bs.joinToString(",") { it.name }
        is Macro -> "macro:${if (repeat) 1 else 0}:" + steps.joinToString(";") { it.token() } + (if (armed) "" else ":off")
        is Action -> "act:${a.name}"
        is Mouse -> "mouse:$b"
    }

    companion object {
        fun fromToken(t: String): RemapTarget? = runCatching {
            val p = t.split(':')
            when (p[0]) {
                "btn" -> Button(ThorButton.valueOf(p[1]))
                "none" -> None
                "key" -> Key(p[1].toInt(), p.getOrNull(2)?.toInt() ?: 0)
                "keys", "keyseq" -> p[1].split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..400 }.take(6)
                    .takeIf { it.isNotEmpty() }?.let { Keys(it, inOrder = p[0] == "keyseq") }
                "btns" -> p[1].split(',').mapNotNull { n -> runCatching { ThorButton.valueOf(n) }.getOrNull() }
                    .filter { it in PadRemap.OUTPUTS }.distinct().take(4).takeIf { it.isNotEmpty() }?.let { Buttons(it) }
                "macro" -> p.getOrElse(2) { "" }.split(';').mapNotNull { MacroStep.fromToken(it) }.take(MacroStep.MAX)
                    .takeIf { it.isNotEmpty() }?.let { Macro(it, p[1] == "1", armed = p.getOrNull(3) != "off") }
                "act" -> Action(ThorAction.valueOf(p[1]))
                "mouse" -> p[1].toInt().takeIf { it in 0..4 }?.let { Mouse(it) }
                else -> null
            }
        }.getOrNull()

        fun keyLabel(code: Int, meta: Int): String {
            val mods = buildList {
                if (meta and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
                if (meta and KeyEvent.META_ALT_ON != 0) add("Alt")
                if (meta and KeyEvent.META_SHIFT_ON != 0) add("Shift")
            }
            val name = KEY_NAMES[code] ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase()
                .replaceFirstChar { it.uppercase() }
            return (mods + name).joinToString("+")
        }

        /** A key's short name (the keyboard tab's labels). */
        fun keyName(code: Int): String = KEY_NAMES[code] ?: EXTRA_NAMES[code]
            ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }

        private val EXTRA_NAMES = mapOf(
            KeyEvent.KEYCODE_CTRL_LEFT to "Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT to "Ctrl", KeyEvent.KEYCODE_SHIFT_LEFT to "Shift",
            KeyEvent.KEYCODE_SHIFT_RIGHT to "Shift", KeyEvent.KEYCODE_ALT_LEFT to "Alt", KeyEvent.KEYCODE_ALT_RIGHT to "Alt",
            KeyEvent.KEYCODE_META_LEFT to "Win", KeyEvent.KEYCODE_META_RIGHT to "Win", KeyEvent.KEYCODE_MENU to "Menu",
            KeyEvent.KEYCODE_CAPS_LOCK to "Caps", KeyEvent.KEYCODE_GRAVE to "`", KeyEvent.KEYCODE_MINUS to "-",
            KeyEvent.KEYCODE_EQUALS to "=", KeyEvent.KEYCODE_LEFT_BRACKET to "[", KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
            KeyEvent.KEYCODE_BACKSLASH to "\\", KeyEvent.KEYCODE_SEMICOLON to ";", KeyEvent.KEYCODE_APOSTROPHE to "'",
            KeyEvent.KEYCODE_COMMA to ",", KeyEvent.KEYCODE_PERIOD to ".", KeyEvent.KEYCODE_SLASH to "/",
            KeyEvent.KEYCODE_SYSRQ to "PrtSc", KeyEvent.KEYCODE_SCROLL_LOCK to "ScrLk", KeyEvent.KEYCODE_BREAK to "Pause",
            KeyEvent.KEYCODE_INSERT to "Ins", KeyEvent.KEYCODE_NUM_LOCK to "NumLk",
            KeyEvent.KEYCODE_NUMPAD_DIVIDE to "Num /", KeyEvent.KEYCODE_NUMPAD_MULTIPLY to "Num *", KeyEvent.KEYCODE_NUMPAD_SUBTRACT to "Num −",
            KeyEvent.KEYCODE_NUMPAD_ADD to "Num +", KeyEvent.KEYCODE_NUMPAD_ENTER to "Num Enter", KeyEvent.KEYCODE_NUMPAD_DOT to "Num .",
            KeyEvent.KEYCODE_VOLUME_DOWN to "Vol −", KeyEvent.KEYCODE_VOLUME_UP to "Vol +", KeyEvent.KEYCODE_VOLUME_MUTE to "Mute",
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Prev", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Play", KeyEvent.KEYCODE_MEDIA_NEXT to "Next",
            KeyEvent.KEYCODE_MEDIA_STOP to "Stop", KeyEvent.KEYCODE_BACK to "Web ◀", KeyEvent.KEYCODE_FORWARD to "Web ▶",
        ) + (0..9).associate { (KeyEvent.KEYCODE_NUMPAD_0 + it) to "Num $it" }

        /** The meta state a modifier key adds while it's held. */
        fun metaOf(code: Int): Int = when (code) {
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
            else -> 0
        }

        val MOUSE_NAMES = listOf("Left click", "Right click", "Middle click", "Wheel up", "Wheel down")

        /** Short names for the keys offered in the picker. */
        val KEY_NAMES = mapOf(
            KeyEvent.KEYCODE_ESCAPE to "Esc", KeyEvent.KEYCODE_ENTER to "Enter", KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_TAB to "Tab", KeyEvent.KEYCODE_DEL to "Backspace", KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
            KeyEvent.KEYCODE_DPAD_UP to "↑", KeyEvent.KEYCODE_DPAD_DOWN to "↓", KeyEvent.KEYCODE_DPAD_LEFT to "←",
            KeyEvent.KEYCODE_DPAD_RIGHT to "→", KeyEvent.KEYCODE_PAGE_UP to "PgUp", KeyEvent.KEYCODE_PAGE_DOWN to "PgDn",
            KeyEvent.KEYCODE_MOVE_HOME to "Home", KeyEvent.KEYCODE_MOVE_END to "End",
        ) + (1..12).associate { (KeyEvent.KEYCODE_F1 + it - 1) to "F$it" } +
            ('A'..'Z').associate { (KeyEvent.KEYCODE_A + (it - 'A')) to it.toString() } +
            (0..9).associate { (KeyEvent.KEYCODE_0 + it) to it.toString() }
    }
}

/** How a button fires (ordinal = the engine's value, fx/wfmap.h `fire`, for the first three).
 *  Long / double press give the button a second output ([PadRemap.alt]) — done by the app. */
enum class Fire(val label: String) {
    NORMAL("Normal"), TURBO("Turbo"), TOGGLE("Toggle"), LONG("Long press"), DOUBLE("Double press");
    val hasAlt get() = this == LONG || this == DOUBLE
}

/** One step of a macro: an output held for [hold] ms, then a [gap] ms pause. */
data class MacroStep(val out: RemapTarget, val hold: Int = 60, val gap: Int = 60) {
    fun token(): String = when (out) {
        is RemapTarget.Button -> "p.${out.b.name}"
        is RemapTarget.Key -> "k.${out.code}"
        is RemapTarget.Mouse -> "m.${out.b}"
        else -> "p.A"
    } + ".$hold.$gap"
    companion object {
        const val MAX = 20
        /** A step's output: a pad button (D-pad too), one keyboard key or a mouse button. */
        fun fromToken(t: String): MacroStep? = runCatching {
            val p = t.split('.')
            val out = when (p[0]) {
                "p" -> RemapTarget.Button(ThorButton.valueOf(p[1]).takeIf { it in PadRemap.OUTPUTS }!!)
                "k" -> RemapTarget.Key(p[1].toInt().takeIf { it in 1..400 }!!)
                "m" -> RemapTarget.Mouse(p[1].toInt().takeIf { it in 0..2 }!!)
                else -> null
            } ?: return null
            MacroStep(out, p[2].toInt().coerceIn(10, 5000), p[3].toInt().coerceIn(0, 5000))
        }.getOrNull()
    }
}

/** Two buttons pressed together → [target] (neither reaches the game while it's held). */
data class Chord(val a: ThorButton, val b: ThorButton, val target: RemapTarget) {
    fun has(x: ThorButton) = x == a || x == b
}

/** 1.3 — what a stick does for this app: itself, or it's Wayfinder's (fx/wfmap.h `apl` / `apr`, the
 *  game then sees it centred): the mouse pointer, the mouse wheel, or 4 keys. */
enum class StickUse(val label: String, val caption: String) {
    STICK("Stick", "as usual"), MOUSE("Mouse", "moves the pointer"), SCROLL("Scroll", "the mouse wheel"), KEYS("4 keys", "arrows or W A S D")
}

/** 1.3 — a stick's job. [speed] 1..10 (mouse, scroll); [wasd] = W A S D instead of the arrow keys. The
 *  stick's own shape (deadzone, full at, response) applies, with a deadzone of at least 10 %. */
data class StickJob(val use: StickUse = StickUse.STICK, val speed: Int = 5, val wasd: Boolean = false) {
    val isDefault get() = use == StickUse.STICK
    val summary: String get() = when (use) {
        StickUse.STICK -> "Stick"; StickUse.MOUSE -> "🖱 Mouse"; StickUse.SCROLL -> "🖱 Scroll"
        StickUse.KEYS -> if (wasd) "⌨ W A S D" else "⌨ Arrow keys"
    }
    fun toJson(): JSONObject = JSONObject().put("u", use.name).put("s", speed).put("k", wasd)
    companion object {
        fun fromJson(o: JSONObject?): StickJob = o?.let {
            StickJob(runCatching { StickUse.valueOf(it.optString("u")) }.getOrDefault(StickUse.STICK), it.optInt("s", 5).coerceIn(1, 10), it.optBoolean("k"))
        }?.takeIf { !it.isDefault } ?: StickJob()
    }
}

/** Round 8: a stick's shape. [dead] % inner deadzone (0..30, hides drift), [full] % where full
 *  deflection is reached (70..100), [curve] 0 linear · 1 precise centre · 2 fast. Values clamped on
 *  read: they also come from shared files and backups. */
data class StickShape(val dead: Int = 0, val full: Int = 100, val curve: Int = 0) {
    val isDefault get() = this == StickShape()
    fun toJson(): JSONObject = JSONObject().put("d", dead).put("o", full).put("c", curve)
    fun tokens(side: String): List<String> = buildList {
        if (dead != 0) add("dz$side=${dead.coerceIn(0, 30)}")
        if (full != 100) add("oz$side=${full.coerceIn(70, 100)}")
        if (curve != 0) add("cv$side=${curve.coerceIn(0, 2)}")
    }
    companion object {
        fun fromJson(o: JSONObject?): StickShape = o?.let {
            StickShape(it.optInt("d", 0).coerceIn(0, 30), it.optInt("o", 100).coerceIn(70, 100), it.optInt("c", 0).coerceIn(0, 2))
        } ?: StickShape()
    }
}

data class PadRemap(
    val buttons: Map<ThorButton, RemapTarget> = emptyMap(),
    val swapSticks: Boolean = false,
    val invertLeftY: Boolean = false,
    val invertRightY: Boolean = false,
    /** D-pad drives the left stick and the left stick the D-pad. */
    val dpadStick: Boolean = false,
    /** L2 / R2 are all-or-nothing (full press as soon as they click). */
    val digitalTriggers: Boolean = false,
    /** How a button fires (what it becomes stays in [buttons]); missing = normal. */
    val fire: Map<ThorButton, Fire> = emptyMap(),
    /** Turbo presses per second. */
    val turboHz: Int = 12,
    /** Gyro as mouse / stick (the app's [GyroEngine]; the stick through wfpad "g"). */
    val gyro: GyroSettings = GyroSettings(),
    /** The second output of a long / double press button ([fire]). */
    val alt: Map<ThorButton, RemapTarget> = emptyMap(),
    /** Button pairs pressed together (max [MAX_CHORDS]). */
    val chords: List<Chord> = emptyList(),
    /** Round 8 — each PHYSICAL stick's shape (drift deadzone, full at, response) and the triggers'
     *  range, done in wfpad (fx/wfmap.h `wf_shape`). Default = untouched (or the all-games default). */
    val stickL: StickShape = StickShape(),
    val stickR: StickShape = StickShape(),
    val trigStart: Int = 0,
    val trigFull: Int = 100,
    /** Hold-to-shift (§6l): this button, held, gives the others their [shifted] job; null = none (default). */
    val shift: ThorButton? = null,
    val shifted: Map<ThorButton, RemapTarget> = emptyMap(),
    /** 1.3 — each PHYSICAL stick's job: itself (default), or the mouse / wheel / 4 keys (Wayfinder plays it). */
    val jobL: StickJob = StickJob(),
    val jobR: StickJob = StickJob(),
    /** 1.3 (GitHub #19): left / right inverted (old camera controls). */
    val invertLeftX: Boolean = false,
    val invertRightX: Boolean = false,
) {
    val isEmpty get() = this == PadRemap()
    val trigRanged get() = trigStart != 0 || trigFull != 100
    val changes: Int get() = (buttons.keys + fire.keys).size + chords.size +
        listOf(swapSticks, invertLeftY, invertRightY, dpadStick, digitalTriggers, gyro.isOn,
            !stickL.isDefault, !stickR.isDefault, trigRanged, shift != null, !jobL.isDefault, !jobR.isDefault,
            invertLeftX, invertRightX).count { it } +
            (if (shift != null) shifted.size else 0)

    /** This button is played by the app (ExtEngine): a target the engine can't do alone, a
     *  long / double press, or a chord member. The engine sends its presses as "X" lines. */
    fun byApp(src: ThorButton): Boolean {
        val t = buttons[src]
        return (t != null && t !is RemapTarget.Button && t != RemapTarget.None) ||
            fire[src]?.hasAlt == true || chords.any { it.has(src) } ||
            (src.isDpad && fire[src] != null)          // 1.3: a D-pad direction's turbo / toggle: the app does it
    }

    fun toJson(): JSONObject = JSONObject()
        .put("b", JSONObject().apply { buttons.forEach { (k, v) -> put(k.name, v.token()) } })
        .put("sw", swapSticks).put("il", invertLeftY).put("ir", invertRightY)
        .put("dl", dpadStick).put("td", digitalTriggers).put("xl", invertLeftX).put("xr", invertRightX)
        .put("f", JSONObject().apply { fire.forEach { (k, v) -> put(k.name, v.name) } })
        .put("tr", turboHz)
        .apply { if (gyro != GyroSettings()) put("g", gyro.toJson()) }
        .apply { if (alt.isNotEmpty()) put("a", JSONObject().apply { alt.forEach { (k, v) -> put(k.name, v.token()) } }) }
        .apply { if (chords.isNotEmpty()) put("c", org.json.JSONArray().apply {
            chords.forEach { put(JSONObject().put("a", it.a.name).put("b", it.b.name).put("t", it.target.token())) } }) }
        .apply { if (!stickL.isDefault) put("sl", stickL.toJson()); if (!stickR.isDefault) put("sr", stickR.toJson()) }
        .apply { if (trigRanged) put("ts", trigStart).put("tf", trigFull) }
        .apply { shift?.let { put("sh", it.name) }; if (shifted.isNotEmpty()) put("shl", JSONObject().apply { shifted.forEach { (k, v) -> put(k.name, v.token()) } }) }
        .apply { if (!jobL.isDefault) put("jl", jobL.toJson()); if (!jobR.isDefault) put("jr", jobR.toJson()) }

    /** Tokens for the mapping engine (fx/wfmap.h `wf_parse`). */
    fun engineTokens(): String = buildList {
        for (src in SOURCES) {
            val s = outCode(src) ?: continue
            val t = buttons[src]
            val d = when {
                byApp(src) -> 0xfffe
                t is RemapTarget.Button -> if (t.b.isDpad) DPAD[t.b] else CODE[t.b]
                t == RemapTarget.None -> 0xffff
                else -> null
            } ?: continue
            add("k0x${s.toString(16)}=$d")
        }
        // turbo / toggle on what the game gets (the app's buttons: the app does it — "X" lines)
        for ((src, f) in fire) {
            if (byApp(src) || f.hasAlt || src.isDpad) continue
            CODE[src]?.let { add("f0x${it.toString(16)}=${f.ordinal}") }
        }
        if (fire.isNotEmpty() && turboHz != 12) add("tr=${turboHz.coerceIn(2, 30)}")
        if (swapSticks) add("sw=1")
        if (dpadStick) add("dl=1")
        if (invertLeftY) add("il=1")
        if (invertRightY) add("ir=1")
        if (invertLeftX) add("xl=1")
        if (invertRightX) add("xr=1")
        if (digitalTriggers) add("td=1")
        addAll(stickL.tokens("l")); addAll(stickR.tokens("r"))
        if (trigRanged) { add("tlo=${trigStart.coerceIn(0, 50)}"); add("thi=${trigFull.coerceIn(50, 100)}") }
        shift?.let { b -> CODE[b]?.let { add("sh=0x${it.toString(16)}") } }
        if (!jobL.isDefault) add("apl=1")
        if (!jobR.isDefault) add("apr=1")
    }.joinToString(" ")

    companion object {
        /** The pad's buttons that can be remapped (not Home / Back). */
        val BUTTONS = listOf(
            ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y, ThorButton.L1, ThorButton.R1,
            ThorButton.L2, ThorButton.R2, ThorButton.L3, ThorButton.R3, ThorButton.SELECT, ThorButton.START,
        )
        /** 1.3: the D-pad's directions, each remappable on its own (GitHub #9: browsing). */
        val DIRS = listOf(ThorButton.UP, ThorButton.DOWN, ThorButton.LEFT, ThorButton.RIGHT)
        /** Everything that can be remapped: the buttons and the D-pad's directions. */
        val SOURCES = BUTTONS + DIRS
        /** Printed button → the pad's evdev code in Nintendo ("Odin") numbering (fx/wfmap.h). */
        val CODE = mapOf(
            ThorButton.A to 0x130, ThorButton.B to 0x131, ThorButton.X to 0x133, ThorButton.Y to 0x134,
            ThorButton.L1 to 0x136, ThorButton.R1 to 0x137, ThorButton.L2 to 0x138, ThorButton.R2 to 0x139,
            ThorButton.SELECT to 0x13a, ThorButton.START to 0x13b, ThorButton.L3 to 0x13d, ThorButton.R3 to 0x13e,
        )
        val DPAD = mapOf(ThorButton.UP to 0x220, ThorButton.DOWN to 0x221, ThorButton.LEFT to 0x222, ThorButton.RIGHT to 0x223)
        /** What Wayfinder can press on the game's pad (combos, macros): the buttons + D-pad. */
        val OUTPUTS = BUTTONS + DIRS
        const val MAX_CHORDS = 4
        /** The buttons that can be the shift button (ones games rarely need held). */
        val SHIFTS = listOf(ThorButton.SELECT, ThorButton.START, ThorButton.L3, ThorButton.R3)
        /** A button's or D-pad direction's (0x220..0x223) code in the engine: what wfpad's virtual presses
         *  take (`p <code> <0|1>`), and a source's id. */
        fun outCode(b: ThorButton): Int? = CODE[b] ?: DPAD[b]
        val BY_CODE = (CODE + DPAD).entries.associate { (k, v) -> v to k }

        fun fromJson(o: JSONObject?): PadRemap? {
            o ?: return null
            val b = o.optJSONObject("b")
            val buttons = buildMap {
                b?.keys()?.forEach { k ->
                    val src = runCatching { ThorButton.valueOf(k) }.getOrNull() ?: return@forEach
                    if (src !in SOURCES) return@forEach
                    RemapTarget.fromToken(b.getString(k))?.let { put(src, it) }
                }
            }
            val f = o.optJSONObject("f")
            val fire = buildMap {
                f?.keys()?.forEach { k ->
                    val src = runCatching { ThorButton.valueOf(k) }.getOrNull() ?: return@forEach
                    val v = runCatching { Fire.valueOf(f.getString(k)) }.getOrNull() ?: return@forEach
                    if (src in SOURCES && v != Fire.NORMAL) put(src, v)
                }
            }
            val a = o.optJSONObject("a")
            val alt = buildMap {
                a?.keys()?.forEach { k ->
                    val src = runCatching { ThorButton.valueOf(k) }.getOrNull() ?: return@forEach
                    if (src in SOURCES && fire[src]?.hasAlt == true) RemapTarget.fromToken(a.getString(k))?.let { put(src, it) }
                }
            }
            val c = o.optJSONArray("c")
            val chords = (0 until (c?.length() ?: 0)).mapNotNull { i ->
                val j = c!!.optJSONObject(i) ?: return@mapNotNull null
                val x = runCatching { ThorButton.valueOf(j.getString("a")) }.getOrNull()
                val y = runCatching { ThorButton.valueOf(j.getString("b")) }.getOrNull()
                val t = RemapTarget.fromToken(j.optString("t"))
                if (x == null || y == null || t == null || x == y || x !in BUTTONS || y !in BUTTONS) null else Chord(x, y, t)
            }.take(MAX_CHORDS)
            return PadRemap(buttons, o.optBoolean("sw"), o.optBoolean("il"), o.optBoolean("ir"),
                o.optBoolean("dl"), o.optBoolean("td"), fire, o.optInt("tr", 12).coerceIn(2, 30),
                GyroSettings.fromJson(o.optJSONObject("g")) ?: GyroSettings(), alt, chords,
                StickShape.fromJson(o.optJSONObject("sl")), StickShape.fromJson(o.optJSONObject("sr")),
                o.optInt("ts", 0).coerceIn(0, 50), o.optInt("tf", 100).coerceIn(50, 100),
                shift = runCatching { ThorButton.valueOf(o.optString("sh")) }.getOrNull()?.takeIf { it in SHIFTS },
                shifted = o.optJSONObject("shl")?.let { m -> buildMap {
                    m.keys().forEach { k ->
                        val src = runCatching { ThorButton.valueOf(k) }.getOrNull() ?: return@forEach
                        if (src in BUTTONS) RemapTarget.fromToken(m.getString(k))?.let { put(src, it) }
                    } } } ?: emptyMap(),
                jobL = StickJob.fromJson(o.optJSONObject("jl")), jobR = StickJob.fromJson(o.optJSONObject("jr")),
                invertLeftX = o.optBoolean("xl"), invertRightX = o.optBoolean("xr")).takeIf { !it.isEmpty }
        }
    }
}
