package app.wayfinder

/*
 * dev build 15 (WP2): what a controller IS and how its face buttons are placed and named — one model for the root helper
 * ([ExtPads], inside InputMonitorTool: what each reader is told) and the app ([ButtonNames], [ExtPadsCtl], the glyphs:
 * what presses mean and what screens say). Pure Kotlin, no Android or Compose: the JVM unit tests load it (app/src/test).
 * [ThorButton] A / B / X / Y are POSITIONS here — the Thor's print: A right, B bottom, X top, Y left (HX_PRINTED, wfhid).
 */

/** dev build 15 (WP2): face buttons — where A sits, and what the four are called. PlayStation = the Xbox positions (✕ at
 *  the bottom) named with PlayStation's marks. The names are AppConfig / GameProfile JSON's ("NINTENDO", "XBOX"): stored
 *  faces keep their meaning. Per-app, per-game and per-pad-game faces are positions only — a stored PLAYSTATION acts as
 *  XBOX ([wire] 'x'); the three ways are for the Thor's style and each controller's page. */
enum class FaceStyle(val wire: Char, val label: String) {
    NINTENDO('n', "Nintendo"), XBOX('x', "Xbox"), PLAYSTATION('x', "PlayStation");
    /** A sends at the bottom (wfhid / wfmap "x"). */
    val xboxPositions get() = wire == 'x'
    /** The quick-panel tile: Nintendo → Xbox → PlayStation → Nintendo. */
    fun next() = values()[(ordinal + 1) % 3]
    companion object { fun parse(s: String?) = values().firstOrNull { it.name == s } }
}

/** dev build 15 (WP2, D1): a controller's own face setting. Stored as pface_<key>: absent = AUTO (an existing "" = never
 *  chose = Automatic, D1) · "t" · "n" · "x" · "p" (the values builds 10-14 wrote keep their meaning). [wire] = to the
 *  root helper ("E pface"): PlayStation goes as "x" (the Xbox positions). */
enum class FaceChoice(val store: String, val wire: String, val style: FaceStyle?) {
    AUTO("", "a", null), THOR("t", "t", null),
    NINTENDO("n", "n", FaceStyle.NINTENDO), XBOX("x", "x", FaceStyle.XBOX), PLAYSTATION("p", "x", FaceStyle.PLAYSTATION);
    companion object {
        fun ofStore(s: String?) = values().firstOrNull { it != AUTO && it.store == s } ?: AUTO
        /** The helper's view (it never needs the names): "n" / "x" / "t", anything else Automatic. */
        fun ofWire(s: String?) = when (s) { "n" -> NINTENDO; "x" -> XBOX; "t" -> THOR; else -> AUTO }
    }
}

/** dev build 15 (WP2): what a controller is — from its ids (wfhid's "I" line, or its key "<vid><pid>-…"), never its
 *  name. [auto] = its face when Automatic (null = like the Thor's, D1; critique R13: the Nintendo kinds their own maker's
 *  too — only unknown pads follow the Thor, an MFi pad can't be told by its vendor); [print] = what its face buttons are
 *  called whatever it sends (null = they follow its face style: the Thor, unknown pads). */
enum class ControllerKind(val auto: FaceStyle?, val print: FaceStyle?) {
    THOR(null, null),
    PLAYSTATION(FaceStyle.PLAYSTATION, FaceStyle.PLAYSTATION),
    XBOX(FaceStyle.XBOX, FaceStyle.XBOX),
    JOYCON_L(FaceStyle.NINTENDO, FaceStyle.NINTENDO), JOYCON_R(FaceStyle.NINTENDO, FaceStyle.NINTENDO),
    JOYCON_PAIR(FaceStyle.NINTENDO, FaceStyle.NINTENDO),
    NINTENDO_PRO(FaceStyle.NINTENDO, FaceStyle.NINTENDO),          // the Pro, 8BitDo in Switch mode, any other 057e pad
    GENERIC(null, null);
    val nintendo get() = print == FaceStyle.NINTENDO
    /** Can be held sideways (SL / SR). */
    val single get() = this == JOYCON_L || this == JOYCON_R
    // capabilities (the drawings, PadArts; WP6). critique R19: motion — the Nintendo kinds (PlayStation: D10)
    val touchpad get() = this == PLAYSTATION
    val gyro get() = nintendo
    /** dev build 16 (WP5, critique M10): SL / SR on a rail — the Joy-Cons (the setup wizard asks for them only where the
     *  drawing shows them live: one Joy-Con held upright). */
    val rails get() = single || this == JOYCON_PAIR
    /** A Capture button: the Pro, a left Joy-Con, the pair. dev build 17 (D4): no longer left free by wfhid — the Pro's and
     *  the pair's are its Back (wfhid target BK), a lone left Joy-Con's its Home (upright or sideways); drawn as decor where
     *  the drawing has no part for it. */
    val capture get() = this == NINTENDO_PRO || this == JOYCON_L || this == JOYCON_PAIR
    companion object {
        fun of(vid: Int, pid: Int, pair: Boolean): ControllerKind = when {
            pair -> JOYCON_PAIR
            vid == 0x054c -> PLAYSTATION
            vid == 0x045e -> XBOX
            vid == 0x057e -> when (pid) { 0x2006 -> JOYCON_L; 0x2007 -> JOYCON_R; else -> NINTENDO_PRO }
            else -> GENERIC
        }
        /** From a controller's key ("<vid><pid>-<name>[+u…]", ExtPads.scan; the pair: ExtPads.PAIR_KEY). */
        fun ofKey(key: String): ControllerKind = of(vidOfKey(key), pidOfKey(key), key == ExtPads.PAIR_KEY)
        fun vidOfKey(key: String) = key.take(4).toIntOrNull(16) ?: -1
        fun pidOfKey(key: String) = key.drop(4).take(4).toIntOrNull(16) ?: -1
    }
}

/** dev build 15 (WP2): how one controller's buttons are named and which one does a menu job — what pages, hints and
 *  glyphs draw for it. [names] = how its face buttons are called; [xbox] = it sends Xbox positions now (A = the bottom
 *  button confirms). Build 18 (A15): [back] / [ayn] = the name of its own button doing the Thor's Back / AYN job
 *  ("touchpad click", "Mute" — ExtPadsCtl.padLook, from its spares); null = none of its own (the Thor, a stand-in). */
data class PadLook(
    val names: FaceStyle, val xbox: Boolean,
    val kind: ControllerKind = ControllerKind.THOR, val pid: Int = 0,
    val sideways: Boolean = false,      // a single Joy-Con held sideways: positions in words
    val noHome: Boolean = false,        // no Home: Select + Start held is its Home (wfhid's Select + Start Home)
    val back: String? = null,           // build 18 (A15): its own Back button's name ("touchpad click")
    val ayn: String? = null,            // build 18 (A15): its own AYN button's name ("Mute")
)

/** dev build 15 (WP2): one controller's vocabulary — the names of its buttons ([PadLook]); [ButtonNames] delegates here. */
object Vocab {
    private val A = ThorButton.A; private val B = ThorButton.B; private val X = ThorButton.X; private val Y = ThorButton.Y
    /** Nintendo ↔ Xbox positions: A ↔ B, X ↔ Y (anything else unchanged). */
    fun faceSwap(b: ThorButton) = when (b) { A -> B; B -> A; X -> Y; Y -> X; else -> b }
    /** The Xbox-position letter as PlayStation's mark: A (bottom) ✕ · B (right) ○ · X (left) □ · Y (top) △. The plain-text
     *  fallback (accessibility, logs, text drawn later): badges and overlays draw the marks as shapes (ui.FaceMark). */
    fun psSymbol(xboxLetter: ThorButton) = when (xboxLetter) { A -> "✕"; B -> "○"; X -> "□"; Y -> "△"; else -> null }
    /** The Xbox-way letter that names position [b] (its glyph colour); Nintendo names: [b] itself. */
    fun shown(b: ThorButton, look: PadLook) = if (look.names == FaceStyle.NINTENDO) b else faceSwap(b)
    /** The position that does menu job [job] (A confirm · B back · X · Y) on [look]. */
    fun menuPos(job: ThorButton, look: PadLook) = if (look.xbox) faceSwap(job) else job

    /** The name of position [b] on [look]. The Thor (and an unknown pad) keeps today's names for everything but the face. */
    fun name(b: ThorButton, look: PadLook): String {
        val k = look.kind
        return when (b) {
            A, B, X, Y -> if (look.sideways) when (b) { A -> "right button"; B -> "bottom button"; X -> "top button"; else -> "left button" }
                else when (look.names) {
                    FaceStyle.NINTENDO -> b.printed
                    FaceStyle.XBOX -> faceSwap(b).printed
                    FaceStyle.PLAYSTATION -> psSymbol(faceSwap(b)) ?: b.printed
                }
            // a left Joy-Con: its Capture button is its Home (sideways, its − its Start: wfhid's sideways map). dev build 17
            // (D4): held upright too — a lone left Joy-Con has no Home button
            ThorButton.HOME -> when {
                k == ControllerKind.JOYCON_L -> "Capture"
                look.noHome -> "Select + Start" + Say.HELD       // a sentence that holds it drops the mark (Say.heldName)
                k == ControllerKind.PLAYSTATION -> "PS"
                k == ControllerKind.XBOX -> "Xbox button"
                else -> b.printed
            }
            ThorButton.L1, ThorButton.R1 -> when {
                look.sideways -> if (b == ThorButton.L1) "SL" else "SR"
                k == ControllerKind.XBOX -> if (b == ThorButton.L1) "LB" else "RB"
                k.nintendo -> if (b == ThorButton.L1) "L" else "R"
                else -> b.printed
            }
            ThorButton.L2, ThorButton.R2 -> when {
                k == ControllerKind.XBOX -> if (b == ThorButton.L2) "LT" else "RT"
                k.nintendo -> if (b == ThorButton.L2) "ZL" else "ZR"
                else -> b.printed
            }
            ThorButton.SELECT, ThorButton.START -> when {
                k == ControllerKind.PLAYSTATION -> if (b == ThorButton.START) "Options" else if (look.pid == 0x0ce6 || look.pid == 0x0df2) "Create" else "Share"
                k == ControllerKind.XBOX -> if (b == ThorButton.SELECT) "View" else "Menu"
                k.nintendo -> if (b == ThorButton.SELECT || (k == ControllerKind.JOYCON_L && look.sideways)) "−" else "+"
                else -> b.printed
            }
            ThorButton.L3, ThorButton.R3 -> if (k == ControllerKind.XBOX) (if (b == ThorButton.L3) "LS" else "RS") else b.printed
            // build 18 (A15): its own button doing the Back / AYN job, by its name ("touchpad click", "Mute") — the Thor and a
            // stand-in (Home + Select / Home + Start) keep "Back" / "AYN"
            ThorButton.BACK -> look.back ?: b.printed
            ThorButton.AYN -> look.ayn ?: b.printed
            else -> b.printed
        }
    }

    /** The name in words, for hints ("D-pad up", "Right stick left") — today's [ThorButton.spoken] rule on [look]'s names. */
    fun spoken(b: ThorButton, look: PadLook): String = when (b) {
        ThorButton.UP -> "D-pad up"; ThorButton.DOWN -> "D-pad down"; ThorButton.LEFT -> "D-pad left"; ThorButton.RIGHT -> "D-pad right"
        else -> name(b, look).replace("↑", "up").replace("↓", "down").replace("←", "left").replace("→", "right")
    }

    /** dev build 17 (theme G, D4): a spare button's name — the field wfhid reads it from ([page] / [usage], its "F" line: our
     *  page 0xFF01 = a PlayStation pad's built-in layout, 0xFF00 = a Nintendo one's; 0x0C the Consumer page; 9 Buttons).
     *  null = no name known (the caller says "its own button"). */
    fun spareName(page: Int, usage: Int): String? = when {
        page == 0xFF01 && usage == 14 -> "touchpad click"
        page == 0xFF01 && usage == 15 -> "Mute"
        page == 0xFF00 && usage == 0x16 -> "Capture"
        page == 0x0C && usage == 0xB2 -> "Share"
        page == 9 -> "button $usage"
        else -> null
    }

    /** D8: "Button names on screen" (0 Automatic · 1 as printed · 2 Xbox · 3 PlayStation) → (PlayStation style, as printed). */
    fun migrateNames(old: Int): Pair<Boolean, Boolean> = (old == 3) to (old == 1)
    /** The Thor's face style: AYN's positions ([aynXbox]), PlayStation's names on them when chosen ([ps]). */
    fun thorStyle(aynXbox: Boolean, ps: Boolean) = if (!aynXbox) FaceStyle.NINTENDO else if (ps) FaceStyle.PLAYSTATION else FaceStyle.XBOX
    /** The Thor's vocabulary ([ButtonNames.thorLook]): its style's names — as printed when chosen — at AYN's positions. */
    fun thorLook(aynXbox: Boolean, ps: Boolean, asPrinted: Boolean) =
        PadLook(if (asPrinted) FaceStyle.NINTENDO else thorStyle(aynXbox, ps), aynXbox)
}

/** dev build 16 (WP5, critique M13): what a target is called on [look]'s controller — [RemapTarget.label] in its names. */
fun RemapTarget.label(look: PadLook): String = when (this) {
    is RemapTarget.Button -> Vocab.spoken(b, look)
    is RemapTarget.Buttons -> bs.joinToString(" + ") { Vocab.spoken(it, look) }
    else -> label()
}

/** Short words for tags and chips in [look]'s names: ⌨ = a keyboard key, 🖱 = the mouse, ★ = a Wayfinder action.
 *  dev build 16 (WP5): moved from RemapScreen (its no-look short() = the Thor's names). */
fun RemapTarget.short(look: PadLook): String = when (this) {
    is RemapTarget.Button -> Vocab.spoken(b, look)
    RemapTarget.None -> "Nothing"
    is RemapTarget.Key -> "⌨ " + label()
    is RemapTarget.Keys -> "⌨ " + label()
    is RemapTarget.Buttons -> label(look)
    is RemapTarget.Macro -> "Macro (${steps.size})" + if (armed) "" else " · off"
    is RemapTarget.Mouse -> "🖱 " + label()
    is RemapTarget.Action -> if (a == ThorAction.BACK) "Android Back" else "★ " + when (a) {
        ThorAction.SWAP_OR_SEND -> "Move/swap"; ThorAction.CLEAR_BACKGROUND -> "Close apps"; ThorAction.CLOSE_APP -> "Close this app"; ThorAction.RECENTS -> "Recents"
        ThorAction.SCREENSHOT -> "Screenshot"; ThorAction.TOGGLE_SECOND_SCREEN -> "Bottom screen"; ThorAction.TOGGLE_KEEP_AWAKE -> "Stay awake"
        // build 18 (GitHub #91, #83, #81)
        ThorAction.TOGGLE_TOP_SCREEN -> "Top screen"; ThorAction.WAYFINDER_OFF -> "Wayfinder off"; ThorAction.NOTHING -> "Nothing"
        ThorAction.FOCUS_SWITCH_UP -> "Controller ↑"; ThorAction.FOCUS_SWITCH_DOWN -> "Controller ↓"; ThorAction.FOCUS_LOCK_TOGGLE -> "Lock controller"
        ThorAction.KEYBOARD -> "Kbd & mouse"; ThorAction.QUICK_MENU -> "Quick panel"; ThorAction.BRIGHTER -> "Brighter"
        ThorAction.DIMMER -> "Dimmer"; ThorAction.FPS_COUNTER -> "FPS"
        // 1.3.1 (GitHub #44): short tags for the drawing
        ThorAction.TOP_BRIGHTER -> "Top brighter"; ThorAction.TOP_DIMMER -> "Top dimmer"
        ThorAction.BOTTOM_BRIGHTER -> "Bottom brighter"; ThorAction.BOTTOM_DIMMER -> "Bottom dimmer"
        ThorAction.TOP_LOUDER -> "Top louder"; ThorAction.TOP_QUIETER -> "Top quieter"
        ThorAction.BOTTOM_LOUDER -> "Bottom louder"; ThorAction.BOTTOM_QUIETER -> "Bottom quieter"
        else -> a.title
    }
}

/** dev build 15 (WP2, theme E): which face a connected controller's pad sends. ONE set of functions for the root helper
 *  (what it tells the reader) and the app (what it reads presses through) — they can't disagree. */
object FaceRules {
    /** The style [choice] stands for on [kind]: null = like the Thor's (its style, and each game's). */
    fun style(choice: FaceChoice, kind: ControllerKind): FaceStyle? = when (choice) {
        FaceChoice.AUTO -> kind.auto; FaceChoice.THOR -> null; else -> choice.style }
    /** Its reader's own layout (wfhid "x n|x"). Docked it plays as the Thor's controller: the Thor's. */
    fun reader(choice: FaceChoice, kind: ControllerKind, thor: Char, asThor: Boolean): Char =
        if (asThor) thor else style(choice, kind)?.wire ?: thor
    /** The Thor's per-game face (the game's "L=") reaches it only when it is like the Thor's. Review W4: a Nintendo
     *  controller on Automatic too — its base face is Nintendo's (critique R13), but a game's own face buttons (e.g. a
     *  Minecraft Xbox) still reach it, as they did before build 15. */
    fun takesGameFace(choice: FaceChoice, kind: ControllerKind, asThor: Boolean) =
        asThor || style(choice, kind) == null || (choice == FaceChoice.AUTO && kind.nintendo)
    /** What its reader is told ("P"): its own controls, else plain (""), else the game's — without "L=" when it has a face
     *  of its own. Review W1 / W2 / W3: its own controls without a face ("Give it its own controls", "Start from the
     *  Thor's", the M1 drop) on a pad that takes the game's face get the game's "L=" — like the Thor's means the Thor's face
     *  for that game (spec §9.3), as the copied face gave before; the pad tab's usual face is then what it sends. */
    fun profileFor(own: String?, plain: Boolean, game: String, choice: FaceChoice, kind: ControllerKind, asThor: Boolean): String =
        withGameBack(if (own != null) {
            val g = if (!plain && takesGameFace(choice, kind, asThor) && !LTOK.containsMatchIn(own)) layoutOf(game) else null
            if (g != null) "L=$g $own".trim() else own
        } else if (plain) "" else if (takesGameFace(choice, kind, asThor)) game else withoutLayout(game), game)
    /** Any face token in a spec ("L=n" / "L=x" / "L=0"): its own controls then say it themselves. */
    private val LTOK = Regex("(^| )L=")
    /** dev build 17 (critique F2): the game's "Back goes to the game" ("bg=1" — RetroArch's Back hotkeys) reaches plain buttons
     *  and its own controls too, unless they say it themselves: with a Back on every controller (theme G), held it gates
     *  — Back + R1 (save a state) was withheld from the game. */
    fun withGameBack(spec: String, game: String): String =
        if (!GAME_BACK.containsMatchIn(game) || BGTOK.containsMatchIn(spec)) spec else "$spec bg=1".trim()
    private val GAME_BACK = Regex("(^| )bg=1( |$)")
    private val BGTOK = Regex("(^| )bg=")
    /** The face it sends in the end ('x' A at the bottom · 'n'). */
    fun out(own: String?, plain: Boolean, game: String, choice: FaceChoice, kind: ControllerKind, thor: Char, asThor: Boolean): Char =
        layoutOf(profileFor(own, plain, game, choice, kind, asThor)) ?: reader(choice, kind, thor, asThor)
    private val L = Regex("(^| )L=([nx])")
    /** A profile's face ("L=n" / "L=x"), null = none ("L=0", follow the source, too). The one parser (PadLayerCtl too). */
    fun layoutOf(spec: String?): Char? = spec?.let { L.find(it)?.groupValues?.get(2)?.single() }
    fun withoutLayout(spec: String) = spec.split(' ').filter { it.isNotEmpty() && !it.startsWith("L=") }.joinToString(" ")

    /** critique M1: a controller's own face that is only the copy of the Thor's for that game ("Give it its own controls",
     *  "Start from the Thor's" copied it) — dropped once, so its page's face applies (theme E). Same positions = a copy. */
    fun copiedThorFace(pad: FaceStyle?, thor: FaceStyle?) = pad != null && thor != null && pad.wire == thor.wire
    /** The same for an own-controls spec (ExtControls tokens): the spec without its "L=", or null = nothing to change. */
    fun withoutCopiedLayout(spec: String, thor: FaceStyle?): String? =
        layoutOf(spec)?.takeIf { thor != null && it == thor.wire }?.let { withoutLayout(spec) }
}
