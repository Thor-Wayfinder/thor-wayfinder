package app.wayfinder.deck

import android.view.KeyEvent as K

/**
 * Input deck — the built-in pads. A pad is rows of [DeckKey]s; a key sends a key
 * code to the game (held while the finger is down, so movement keys work), types a
 * snippet, or runs a deck action. Pads and ideas inspired by what dual-screen
 * players use; RetroArch's pad uses its DEFAULT hotkeys, so it works with zero setup.
 */
enum class DeckAction { NONE, VOLUME_DOWN, VOLUME_UP, MUTE, BRIGHT_TOP_DOWN, BRIGHT_TOP_UP, BRIGHT_BOTTOM_DOWN, BRIGHT_BOTTOM_UP }

enum class Modifier(val metaOn: Int, val keyCode: Int, val label: String) {
    SHIFT(K.META_SHIFT_ON or K.META_SHIFT_LEFT_ON, K.KEYCODE_SHIFT_LEFT, "Shift"),
    CTRL(K.META_CTRL_ON or K.META_CTRL_LEFT_ON, K.KEYCODE_CTRL_LEFT, "Ctrl"),
    ALT(K.META_ALT_ON or K.META_ALT_LEFT_ON, K.KEYCODE_ALT_LEFT, "Alt"),
    META(K.META_META_ON or K.META_META_LEFT_ON, K.KEYCODE_META_LEFT, "Win"),
}

data class DeckKey(
    val label: String,
    val code: Int = 0,
    val weight: Float = 1f,
    /** Small caption under the label (what it does in the target app). */
    val caption: String? = null,
    val modifier: Modifier? = null,
    val text: String? = null,
    val action: DeckAction = DeckAction.NONE,
    /** Shifted label shown on the PC pad while Shift is latched. */
    val shifted: String? = null,
    /** Modifiers baked into this key (custom combos like Ctrl+S). */
    val meta: Int = 0,
)

data class DeckPad(val id: String, val name: String, val rows: List<List<DeckKey>>) {
    val isTrackpad get() = id == PAD_TRACKPAD
}

const val PAD_PC = "pc"
const val PAD_TRACKPAD = "trackpad"
const val PAD_NUMPAD = "numpad"
const val PAD_EMU = "retroarch"
const val PAD_MEDIA = "media"
const val PAD_VIDEO = "video"
const val PAD_CUSTOM = "custom"

private fun k(label: String, code: Int, weight: Float = 1f, caption: String? = null, shifted: String? = null) =
    DeckKey(label, code, weight, caption, shifted = shifted)
private fun mod(m: Modifier, weight: Float = 1.5f) = DeckKey(m.label, m.keyCode, weight, modifier = m)
private fun act(label: String, a: DeckAction, caption: String? = null) = DeckKey(label, weight = 1f, caption = caption, action = a)

private fun letters(s: String) = s.map { c -> k(c.toString(), K.keyCodeFromString("KEYCODE_${c.uppercaseChar()}")) }

/** A full PC keyboard (US layout — what DOS and PC games expect). */
val PC_PAD = DeckPad(
    PAD_PC, "PC keys",
    listOf(
        listOf(k("Esc", K.KEYCODE_ESCAPE, 1.2f)) + (1..12).map { k("F$it", K.KEYCODE_F1 + it - 1) },
        listOf(
            k("`", K.KEYCODE_GRAVE, shifted = "~"), k("1", K.KEYCODE_1, shifted = "!"), k("2", K.KEYCODE_2, shifted = "@"),
            k("3", K.KEYCODE_3, shifted = "#"), k("4", K.KEYCODE_4, shifted = "$"), k("5", K.KEYCODE_5, shifted = "%"),
            k("6", K.KEYCODE_6, shifted = "^"), k("7", K.KEYCODE_7, shifted = "&"), k("8", K.KEYCODE_8, shifted = "*"),
            k("9", K.KEYCODE_9, shifted = "("), k("0", K.KEYCODE_0, shifted = ")"), k("-", K.KEYCODE_MINUS, shifted = "_"),
            k("=", K.KEYCODE_EQUALS, shifted = "+"), k("⌫", K.KEYCODE_DEL, 1.6f),
        ),
        listOf(k("Tab", K.KEYCODE_TAB, 1.4f)) + letters("qwertyuiop") + listOf(
            k("[", K.KEYCODE_LEFT_BRACKET, shifted = "{"), k("]", K.KEYCODE_RIGHT_BRACKET, shifted = "}"),
            k("\\", K.KEYCODE_BACKSLASH, 1.2f, shifted = "|"),
        ),
        listOf(k("Caps", K.KEYCODE_CAPS_LOCK, 1.7f)) + letters("asdfghjkl") + listOf(
            k(";", K.KEYCODE_SEMICOLON, shifted = ":"), k("'", K.KEYCODE_APOSTROPHE, shifted = "\""), k("Enter", K.KEYCODE_ENTER, 1.9f),
        ),
        listOf(mod(Modifier.SHIFT, 2.2f)) + letters("zxcvbnm") + listOf(
            k(",", K.KEYCODE_COMMA, shifted = "<"), k(".", K.KEYCODE_PERIOD, shifted = ">"), k("/", K.KEYCODE_SLASH, shifted = "?"),
            k("↑", K.KEYCODE_DPAD_UP), k("Del", K.KEYCODE_FORWARD_DEL),
        ),
        listOf(
            mod(Modifier.CTRL), mod(Modifier.META, 1.2f), mod(Modifier.ALT, 1.2f), k("Space", K.KEYCODE_SPACE, 5.5f),
            k("PgUp", K.KEYCODE_PAGE_UP), k("PgDn", K.KEYCODE_PAGE_DOWN),
            k("←", K.KEYCODE_DPAD_LEFT), k("↓", K.KEYCODE_DPAD_DOWN), k("→", K.KEYCODE_DPAD_RIGHT),
        ),
    ),
)

val NUMPAD = DeckPad(
    PAD_NUMPAD, "Numpad",
    listOf(
        listOf(k("Num", K.KEYCODE_NUM_LOCK), k("/", K.KEYCODE_NUMPAD_DIVIDE), k("*", K.KEYCODE_NUMPAD_MULTIPLY), k("−", K.KEYCODE_NUMPAD_SUBTRACT)),
        listOf(k("7", K.KEYCODE_NUMPAD_7, caption = "Home"), k("8", K.KEYCODE_NUMPAD_8, caption = "↑"), k("9", K.KEYCODE_NUMPAD_9, caption = "PgUp"), k("+", K.KEYCODE_NUMPAD_ADD)),
        listOf(k("4", K.KEYCODE_NUMPAD_4, caption = "←"), k("5", K.KEYCODE_NUMPAD_5), k("6", K.KEYCODE_NUMPAD_6, caption = "→"), k("⌫", K.KEYCODE_DEL)),
        listOf(k("1", K.KEYCODE_NUMPAD_1, caption = "End"), k("2", K.KEYCODE_NUMPAD_2, caption = "↓"), k("3", K.KEYCODE_NUMPAD_3, caption = "PgDn"), k("Enter", K.KEYCODE_NUMPAD_ENTER)),
        listOf(k("0", K.KEYCODE_NUMPAD_0, 2f, caption = "Ins"), k(".", K.KEYCODE_NUMPAD_DOT, caption = "Del"), k("Tab", K.KEYCODE_TAB)),
    ),
)

/** RetroArch's DEFAULT keyboard hotkeys — nothing to configure. */
val EMU_PAD = DeckPad(
    PAD_EMU, "Emulator",
    listOf(
        listOf(k("Menu", K.KEYCODE_F1, caption = "F1"), k("Save", K.KEYCODE_F2, caption = "F2 · state"), k("Load", K.KEYCODE_F4, caption = "F4 · state"),
            k("Slot −", K.KEYCODE_F6, caption = "F6"), k("Slot +", K.KEYCODE_F7, caption = "F7")),
        listOf(k("Fast ⏩", K.KEYCODE_SPACE, caption = "toggle"), k("Hold ⏩", K.KEYCODE_L, caption = "hold L"), k("Rewind ⏪", K.KEYCODE_R, caption = "hold R"),
            k("Slow", K.KEYCODE_E, caption = "hold E"), k("Pause", K.KEYCODE_P, caption = "P")),
        listOf(k("Frame ▸", K.KEYCODE_K, caption = "advance"), k("Reset", K.KEYCODE_H, caption = "H"), k("Screenshot", K.KEYCODE_F8, caption = "F8"),
            k("Shader ◂", K.KEYCODE_N, caption = "N"), k("Shader ▸", K.KEYCODE_M, caption = "M")),
        listOf(k("Mute", K.KEYCODE_F9, caption = "F9"), k("Fullscreen", K.KEYCODE_F, caption = "F"),
            k("Game focus", K.KEYCODE_SCROLL_LOCK, caption = "Scroll Lock"), k("Quit", K.KEYCODE_ESCAPE, caption = "Esc ×2")),
    ),
)

val MEDIA_PAD = DeckPad(
    PAD_MEDIA, "Media",
    listOf(
        listOf(k("⏮", K.KEYCODE_MEDIA_PREVIOUS, caption = "Previous"), k("⏯", K.KEYCODE_MEDIA_PLAY_PAUSE, 1.4f, caption = "Play / pause"),
            k("⏭", K.KEYCODE_MEDIA_NEXT, caption = "Next"), k("⏹", K.KEYCODE_MEDIA_STOP, caption = "Stop")),
        listOf(act("🔉", DeckAction.VOLUME_DOWN, "Volume −"), act("🔇", DeckAction.MUTE, "Mute"), act("🔊", DeckAction.VOLUME_UP, "Volume +")),
        listOf(act("☀ −", DeckAction.BRIGHT_TOP_DOWN, "Top screen"), act("☀ +", DeckAction.BRIGHT_TOP_UP, "Top screen"),
            act("☀ −", DeckAction.BRIGHT_BOTTOM_DOWN, "Bottom screen"), act("☀ +", DeckAction.BRIGHT_BOTTOM_UP, "Bottom screen")),
    ),
)

val VIDEO_PAD = DeckPad(
    PAD_VIDEO, "Video",
    listOf(
        listOf(k("⏪", K.KEYCODE_DPAD_LEFT, caption = "Back"), k("⏯", K.KEYCODE_MEDIA_PLAY_PAUSE, 1.4f, caption = "Play / pause"), k("⏩", K.KEYCODE_DPAD_RIGHT, caption = "Forward")),
        listOf(k("⏮ 10s", K.KEYCODE_J, caption = "J"), k("Space", K.KEYCODE_SPACE, caption = "Pause"), k("10s ⏭", K.KEYCODE_L, caption = "L")),
        listOf(k("Fullscreen", K.KEYCODE_F, caption = "F"), k("Subtitles", K.KEYCODE_C, caption = "C"), k("Speed −", K.KEYCODE_COMMA, caption = "<"), k("Speed +", K.KEYCODE_PERIOD, caption = ">")),
        listOf(act("🔉", DeckAction.VOLUME_DOWN, "Volume −"), act("🔇", DeckAction.MUTE, "Mute"), act("🔊", DeckAction.VOLUME_UP, "Volume +")),
    ),
)

/** 1.3 (GitHub #26): the PC keys, simple — bigger keys, only what typing needs (like the Wayfinder keyboard).
 *  Same pad id: the Full / Simple chip on the deck switches between them ([DeckSettings.simpleKeys]). */
val PC_SIMPLE = DeckPad(
    PAD_PC, "PC keys",
    listOf(
        (1..9).map { k("$it", K.KEYCODE_0 + it) } + listOf(k("0", K.KEYCODE_0), k("⌫", K.KEYCODE_DEL, 1.5f)),
        letters("qwertyuiop") + listOf(k("Esc", K.KEYCODE_ESCAPE, 1.2f)),
        letters("asdfghjkl") + listOf(k("Enter", K.KEYCODE_ENTER, 1.8f)),
        listOf(mod(Modifier.SHIFT, 1.6f)) + letters("zxcvbnm") + listOf(k(",", K.KEYCODE_COMMA), k(".", K.KEYCODE_PERIOD), k("↑", K.KEYCODE_DPAD_UP)),
        listOf(k("Tab", K.KEYCODE_TAB, 1.3f), k("Space", K.KEYCODE_SPACE, 5.2f), k("←", K.KEYCODE_DPAD_LEFT), k("↓", K.KEYCODE_DPAD_DOWN), k("→", K.KEYCODE_DPAD_RIGHT)),
    ),
)

val TRACKPAD = DeckPad(PAD_TRACKPAD, "Trackpad", emptyList())

val BUILT_IN_PADS = listOf(PC_PAD, TRACKPAD, NUMPAD, EMU_PAD, MEDIA_PAD, VIDEO_PAD)

/**
 * Plug and play: the pad that fits the app in front, by package (known apps) or by
 * the category the app declares. The user can pick any pad anywhere; that choice is
 * remembered per app and wins over this guess.
 */
fun recommendedPad(pkg: String?, category: Int?): String {
    val p = pkg?.lowercase() ?: return PAD_PC
    return when {
        "retroarch" in p || "lemuroid" in p -> PAD_EMU
        "limelight" in p || "moonlight" in p || "steamlink" in p || "chiaki" in p || "parsec" in p -> PAD_TRACKPAD
        "dosbox" in p || "magicbox" in p || "winlator" in p || "gamenative" in p || "gamehub" in p ||
            "exagear" in p || "mobox" in p || "termux" in p -> PAD_PC
        "vlc" in p || "mxtech" in p || "youtube" in p || "netflix" in p || "kodi" in p || "plex" in p || "jellyfin" in p -> PAD_VIDEO
        "spotify" in p || "music" in p || "musicolet" in p || "podcast" in p || "poweramp" in p -> PAD_MEDIA
        category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> PAD_VIDEO
        category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> PAD_MEDIA
        else -> PAD_PC
    }
}
