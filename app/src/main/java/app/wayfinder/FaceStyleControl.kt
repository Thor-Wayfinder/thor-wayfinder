package app.wayfinder

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalPadLook

/**
 * dev build 15 (WP2): the ONE face-button control — the Controller page (the Thor's style, [ThorFaceCard]), each
 * controller's page (its own, [PadFaceCard]) and Game controls' header (this game's positions; dev build 16: a
 * controller's tab offers Usual · Nintendo · Xbox). The
 * quick-panel tile goes through the same setter as the Controller page ([ButtonNames.setThorStyle]). The Thor's style
 * uses [FaceChoice.NINTENDO] / [FaceChoice.XBOX] / [FaceChoice.PLAYSTATION] as plain style carriers. Choices too wide for
 * the row (build 18, A21: by width) show as one value row that opens its list ([GlassSegmentedControl]'s collapse); [itemsFocusable] false = out of the
 * controller's reach (behind an open popup); [compact] = smaller text, no check mark (a narrow header).
 */
@Composable
internal fun FaceStyleControl(
    choices: List<FaceChoice>, selected: FaceChoice, labelOf: (FaceChoice) -> String,
    modifier: Modifier = Modifier, label: String? = null, itemsFocusable: Boolean = true, compact: Boolean = false,
    onPick: (FaceChoice) -> Unit,
) = GlassSegmentedControl(choices.map(labelOf), choices.indexOf(selected).coerceAtLeast(0), modifier,
    label = label, itemsFocusable = itemsFocusable, tight = compact || choices.size >= 5) { onPick(choices[it]) }

/** A face style as the control's choice (the Thor's three, Game controls' two). */
internal fun FaceStyle.choice(): FaceChoice = when (this) {
    FaceStyle.NINTENDO -> FaceChoice.NINTENDO; FaceStyle.XBOX -> FaceChoice.XBOX; FaceStyle.PLAYSTATION -> FaceChoice.PLAYSTATION
}

/** The Thor's three styles, in the tile's order. */
private val THOR_STYLES = FaceStyle.values().map { it.choice() }

/**
 * dev build 15 (WP2, D8): the Controller page's "Face buttons — every app" — the Thor's style (AYN's controller style for
 * the positions, Wayfinder's PlayStation names on them), the same answer the quick-panel tile and every name read
 * ([ButtonNames.thorStyle]). "Names as printed" is a switch of its own on the Thor's page (dev build 17, WP11).
 */
@Composable
internal fun ThorFaceCard() {
    val ctx = LocalContext.current
    ButtonNames.init(ctx)
    val style = ButtonNames.thorStyle
    // the confirming button in the Thor's names now (as printed: the bottom one is B)
    val ok = Say.menu(ThorButton.A, ButtonNames.thorLook)
    // build 18 (C10, T18-04): other controllers are spoken of only while one is connected — and behind "?" (after " — ")
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val others = ExtPadsCtl.byPlayer().isNotEmpty()
    // dev build 16 (theme F): the Thor's card speaks the Thor's names wherever it is shown (the page's look is the hand's)
    CompositionLocalProvider(LocalPadLook provides ButtonNames.thorLook) {
    SettingCard(
        "Face buttons — every app",                                 // build 18 (C7, CTL-08): one title on both cards
        when (style) {
            FaceStyle.NINTENDO -> "Nintendo: as printed, $ok on the right confirms"
            FaceStyle.XBOX -> "Xbox: $ok at the bottom confirms"
            FaceStyle.PLAYSTATION -> "PlayStation: $ok at the bottom confirms" + if (ButtonNames.asPrinted) "" else ", PlayStation's marks everywhere"
        } + " — " + (if (others) "connected controllers: PlayStation, Xbox and Nintendo ones use their own; the rest do as the Thor. One"
            else "one") + " game differently: Game controls" + (Say.combo(ThorAction.GAME_CONTROLS, ButtonNames.thorLook)?.let { " ($it)" } ?: "") + ".",
    ) {
        // written as the one chosen (from AYN's "Ban On Use" too); the observer updates the style
        FaceStyleControl(THOR_STYLES, style.choice(), { it.style?.label ?: "" }, Modifier.fillMaxWidth()) {
            it.style?.let { s -> ButtonNames.setThorStyle(ctx, s) }
        }
    }
    }
}

/**
 * dev build 15 (WP2, D1): a controller's own face, every app — Automatic first (its maker's convention: PlayStation,
 * Xbox, Nintendo; an unknown pad like the Thor's), "Like the Thor" only where Automatic isn't already that (a stored "t"
 * there reads as Automatic), or a style of its own. Its words are its own names (its page provides [ui.LocalPadLook]).
 */
@Composable
internal fun PadFaceCard(pad: ExtPadsCtl.Pad) {
    val ctx = LocalContext.current
    var face by remember(pad.key) { mutableStateOf(ExtPadsCtl.faceChoice(ctx, pad.key)) }
    val kind = pad.kind
    val thorStyle = ButtonNames.thorStyle
    val choices = listOfNotNull(FaceChoice.AUTO, FaceChoice.THOR.takeIf { kind.auto != null },
        FaceChoice.NINTENDO, FaceChoice.XBOX, FaceChoice.PLAYSTATION)
    val shown = face.takeIf { it in choices } ?: FaceChoice.AUTO
    val style = FaceRules.style(shown, kind)
    // its names at the positions the choice gives — what the setting means, not a game's or its own controls' face now
    val look = ExtPadsCtl.padLook(pad.key).let { l -> style?.let { l.copy(names = kind.print ?: it, xbox = it.xboxPositions) } ?: l }
    fun labelOf(c: FaceChoice) = when (c) {
        FaceChoice.AUTO -> "Automatic (${kind.auto?.label ?: "like the Thor"})"
        FaceChoice.THOR -> "Like the Thor (${thorStyle.label})"
        else -> c.style?.label ?: ""
    }
    val where = if (look.xbox) "bottom" else "right"
    // review W4: a Nintendo controller on Automatic still takes a game's own face buttons (Game controls), as before
    val games = if (shown == FaceChoice.AUTO && kind.nintendo) "every game (a game with face buttons of its own, in Game " +
        "controls, changes it)" else "every game (a game's own face buttons don't change it)"
    val desc = when {
        style == null -> "Each button does what the Thor's button in the same place does (now ${thorStyle.label}), and each " +
            "game's face buttons too."
        // a Joy-Con held sideways: its buttons are named by where they are
        look.sideways -> "The $where button confirms, the ${if (look.xbox) "right" else "bottom"} one goes back — in " +
            "Wayfinder, Android and $games."
        else -> "${Say.menu(ThorButton.A, look)} at the $where confirms, ${Say.menu(ThorButton.B, look)} goes " +
            "back — in Wayfinder, Android and $games."
    }
    // build 18 (C13, ps-F1 / H6): docked, it plays with the Thor's face style — which of its buttons confirms then, in its
    // own names (the Thor's confirm position, on this controller: ○ on a DualSense when the Thor is on Nintendo)
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val dockedOk = Vocab.name(Vocab.menuPos(ThorButton.A, ButtonNames.thorLook), ExtPadsCtl.padLook(pad.key))
    val docked = ExtPadsCtl.seatPad()?.key == pad.key                  // dev build 18f: docked, or by its switch
    // the line: docked now, what confirms; else what the choice does — the rest behind "?" (split by hand: the whole text
    // has no cut Say.split takes, and the line said every word)
    val (line, rest) = Say.split(desc)
    val short = if (docked) "${if (ExtPadsCtl.seatDocked) "Docked now" else "Now"}: $dockedOk confirms (the Thor's ${thorStyle.label} buttons)." else line
    val more = listOfNotNull(if (docked) desc else rest, "Remembered for this controller.",
        "Docked, it follows the Thor's ${thorStyle.label} buttons: $dockedOk confirms.".takeIf { !docked }).joinToString(" ")
    // build 18 (C7, CTL-08): the same title as the Thor's card (two titles for one setting before)
    SettingCard("Face buttons — every app", short = short, more = more) {
        FaceStyleControl(choices, shown, ::labelOf, Modifier.fillMaxWidth()) { c ->
            face = c; ExtPadsCtl.setFaceChoice(ctx, pad.key, c)
        }
    }
}
