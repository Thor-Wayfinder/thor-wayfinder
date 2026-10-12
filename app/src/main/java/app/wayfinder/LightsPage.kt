package app.wayfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.lights.LightMode
import app.wayfinder.lights.LightProfile
import app.wayfinder.lights.LightSettings
import app.wayfinder.lights.StickLights
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.Caption
import app.wayfinder.ui.GlassNotice
import app.wayfinder.ui.HexColorField
import app.wayfinder.ui.NoticeKind
import app.wayfinder.ui.Radii
import app.wayfinder.ui.inkOn
import kotlinx.coroutines.delay

// Pure, LED-friendly colours (screen-style tints wash out to pastel on the rings).
private val PALETTE = listOf(
    0xFFFF0000, 0xFFFF7000, 0xFFFFD000, 0xFF00FF00, 0xFF00FFA0,
    0xFF00E0FF, 0xFF0000FF, 0xFF8000FF, 0xFFFF0080, 0xFFFFFFFF,
).map { it.toInt() }

private val COLOURED = setOf(LightMode.STATIC, LightMode.BREATHING, LightMode.STROBE)
private val ANIMATED = setOf(LightMode.BREATHING, LightMode.STROBE, LightMode.SPECTRUM)

/** More's row. Build 18 (B11, MORE-10): lower case after the first word ("Effect: breathing"; AYN keeps its capitals).
 *  b18p (GitHub #109): without the rings' nodes, that none were found; found but locked, that (LightsWords, Words.kt). */
fun lightsSummary(): String = if (StickLights.locked) LightsWords.LOCKED_ROW
    else if (!StickLights.available) LightsWords.NOT_FOUND_ROW
    else "Effect: " + LightSettings.global.mode.label.let { if (it.startsWith("AYN")) it else it.lowercase() }

/**
 * Stick lights. [pkg] null = the global setting; otherwise that app's own lights — dev build 17 (WP11, rule 7): or a game's
 * (`<pkg>#<game>`, Game controls › Performance links here: this page is the lights' one editor) — or "use the usual ones"
 * (a game's usual = its app's own, else the global setting). Every change shows on the sticks right away; leaving the page
 * hands the lights back to whatever app has the controller.
 */
@Composable
fun LightsPage(myDisplayId: Int, pkg: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LightSettings.init(ctx)
    AppConfigStore.init(ctx)
    val gameKey = pkg?.takeIf { GameProfiles.isGame(it) }
    val game = gameKey?.let { GameProfiles.get(it) }
    val appLabel = remember(pkg) {
        pkg?.let { k -> game?.title ?: GameProfiles.pkgOf(k).let { p ->
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p } }
    }
    // what it gets without its own: a game its app's own lights, else everyone's
    val appOwn = gameKey?.let { AppConfigStore.get(GameProfiles.pkgOf(it)).lights }
    val usual = appOwn ?: LightSettings.global
    val stored = if (pkg == null) LightSettings.global else Profiles.get(pkg).lights
    var useGlobal by remember(pkg) { mutableStateOf(pkg != null && stored == null) }
    var p by remember(pkg) { mutableStateOf(stored ?: usual) }
    var sameColour by remember(pkg) { mutableStateOf(p.left == p.right) }
    var editSide by remember(pkg) { mutableStateOf(0) }

    // build 18 (GitHub #64): a change shows on the sticks at once; storing it waits until the changes stop for a moment
    // (the wheel steered by the stick changes the colour every frame). Leaving the page stores the last one first.
    var unsaved by remember(pkg) { mutableStateOf<LightProfile?>(null) }
    fun persist() {
        val np = unsaved ?: return
        unsaved = null
        if (pkg == null) LightSettings.setGlobalProfile(np)
        else Profiles.update(pkg) { it.copy(lights = if (useGlobal) null else np) }      // an app's, or a game's own
    }
    fun save(np: LightProfile) {
        p = np; unsaved = np
        StickLights.apply(ctx, if (useGlobal) usual else np)   // live preview
    }
    LaunchedEffect(unsaved) { if (unsaved != null) { delay(250); persist() } }
    DisposableEffect(pkg) { onDispose { persist(); ForegroundAppService.reapplyLights() } }
    // b18p (GitHub #109): looked for on open — without the rings' nodes no effect can run, so the page offers none. LED class:
    // found but locked (not writable by the app) says so instead — followed, so a first write refused turns the page to it
    val found = remember { StickLights.recheck() }
    val locked = StickLights.locked
    val drives = found && !locked

    SubPage(myDisplayId, if (appLabel != null) "Stick lights · $appLabel" else NavPages.title(HubPage.LIGHTS),
        if (pkg == null) "The rings around the sticks — for every app"
        else "While $appLabel has the controller", onBack) {
        // dev build 17 (WP8): a warning notice (was a plain setting card). b18p (#109): it says what that means, and it's all
        // the page shows then
        if (locked) GlassNotice(LightsWords.LOCKED, NoticeKind.WARN, Modifier.fillMaxWidth(), title = LightsWords.LOCKED_TITLE)
        else if (!found) GlassNotice(LightsWords.NOT_FOUND, NoticeKind.WARN, Modifier.fillMaxWidth(), title = LightsWords.NOT_FOUND_TITLE)

        if (drives && pkg != null) SettingCard(
            "Use my usual lights", if (useGlobal) (if (appOwn != null) "Same as the rest of the app" else "Same as everywhere else")
                else "$appLabel has its own lights",
            checked = useGlobal, onChecked = { on ->
                useGlobal = on; unsaved = null      // stored at once (with the latest change, when it's its own again)
                Profiles.update(pkg) { it.copy(lights = if (on) null else p) }
                StickLights.apply(ctx, if (on) usual else p)
            },
        )

        if (drives && !useGlobal) {
            // build 18 (B19, V10 / MORE-D16): the top screen's width is used — the effects in one row; a colour effect's two
            // halves side by side (left: one colour or two, the swatches; right: the wheel, its code, the brightness). The
            // bottom screen keeps one column (B12's order: wheel, code, brightness)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= WIDE_LIGHTS
                val perRow = if (maxWidth >= SWATCHES_ONE_ROW) PALETTE.size else PALETTE.size / 2
                val current = if (sameColour || editSide == 0) p.left else p.right
                val setColour: (Int) -> Unit = { c ->
                    save(if (sameColour) p.copy(left = c, right = c) else if (editSide == 0) p.copy(left = c) else p.copy(right = c))
                }
                val picks = ColourPicks(sameColour, { on -> sameColour = on; if (on) save(p.copy(right = p.left)) }, editSide, { editSide = it })
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Effect")
                    // dev build 17 (WP8): the segmented look — fill + check; the D-pad enters on the current effect. Build 18 (B19):
                    // one row of 7 where there's room (4 a row left a hole)
                    GlassSegmentedControl(LightMode.values().map { it.label }, LightMode.values().indexOf(p.mode), Modifier.fillMaxWidth(),
                        collapse = false, tight = wide, columns = if (wide) LightMode.values().size else 4) { save(p.copy(mode = LightMode.values()[it])) }
                    // build 18 (GitHub #82): Spectrum's two rings in step, or opposite colours (as before)
                    if (p.mode == LightMode.SPECTRUM) SettingCard("Same colour on both sticks",
                        if (p.together) "Both rings show the same colour as it cycles" else "Opposite colours on the two rings",
                        checked = p.together, onChecked = { save(p.copy(together = it)) })
                    if (p.mode in COLOURED && wide) LightsSplit(p, picks, current, perRow, setColour, ::save)
                    else LightsColumn(p, picks, current, perRow, setColour, ::save)
                }
            }
        }
        // build 18 (B12 / B19, MORE-D15): where one game's own lights are — at the end, after the cards (the page opened on it,
        // a sentence pointing elsewhere). Review B12: Game controls' Performance — an app's page from the Hub no longer has a
        // Stick lights row (GC12)
        if (drives && pkg == null) Caption("To give one game its own lights: ${Say.path(NavCards.GC_LIGHTS)}.")
    }
}

/** Build 18 (B19): Stick lights takes two columns from this width (the top screen; the bottom one keeps one). */
private val WIDE_LIGHTS = 680.dp
/** Build 18 (B19): the ten swatches in one row from this width, else two rows of five (a 48 dp swatch each). */
private val SWATCHES_ONE_ROW = 520.dp

/** Build 18 (B19): the colour's own choices — one colour or each stick's, and which stick — as one value to hand on. */
private class ColourPicks(val same: Boolean, val onSame: (Boolean) -> Unit, val side: Int, val onSide: (Int) -> Unit)

/** One colour or two, which stick, the COLOUR header, its line and the swatches. */
@Composable
private fun ColourChoice(c: ColourPicks, current: Int, perRow: Int, setColour: (Int) -> Unit) {
    // build 18 (B12, V11): what the switch does, on and off
    SettingCard("Same colour on both sticks", if (c.same) "Both sticks: one colour" else "Left and right separately",
        checked = c.same, onChecked = c.onSame)
    if (!c.same) GlassSegmentedControl(listOf("Left stick", "Right stick"), c.side, Modifier.fillMaxWidth()) { c.onSide(it) }
    SectionHeader("Colour")
    // build 18 (B19, MORE-D12 / V11): the right stick steers the wheel as soon as it's focused; the wheel's own line says A's job
    Caption("Pick a colour, or point the right stick at the wheel.")
    SwatchRows(current, perRow, setColour)
}

/** The wheel and its colour code under it — one column (B12: the D-pad goes wheel → code → brightness); the top screen's
 *  two columns put the code beside it ([WheelBesideCode]). */
@Composable
private fun WheelAndCode(current: Int, size: androidx.compose.ui.unit.Dp, setColour: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        app.wayfinder.ui.ColorWheel(current, size, setColour)
        // 1.4 (GitHub #64): or type the colour
        HexColorField(current, setColour, previewSize = HEX_SQUARE, fieldWidth = 110.dp, textSize = 16.sp)
    }
}

/** Build 18 (B19, V10): a colour effect on a wide screen — the choices and swatches left, the wheel, its code and the
 *  brightness right; Right from the swatches reaches the wheel. Device pass (P2-7): the right column fits the top screen —
 *  the code beside a [WIDE_WHEEL] wheel ([WheelBesideCode]), so Brightness and "Follow the screens' brightness" are in view
 *  without scrolling (under a 200 dp wheel with its code below, they were about 116 dp under the fold). */
@Composable
private fun LightsSplit(p: LightProfile, picks: ColourPicks, current: Int, perRow: Int, setColour: (Int) -> Unit, save: (LightProfile) -> Unit) {
    val wheel = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Column(Modifier.weight(1f)
            .focusProperties { exit = { d -> if (d == FocusDirection.Right) wheel else FocusRequester.Default } }
            .focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ColourChoice(picks, current, minOf(perRow, PALETTE.size / 2), setColour)
            if (p.mode in ANIMATED) SpeedRow(p, save)
        }
        Column(Modifier.weight(1f).focusRequester(wheel).focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WheelBesideCode(current, WIDE_WHEEL, setColour)
            LightsBrightness(p, word = p.mode in ANIMATED, save)
        }
    }
}

/**
 * Device pass (P2-7): the wheel on the top screen. Under the header and the effects, the top screen (468 dp tall) leaves the
 * columns about 272 dp (the device pass's small text, measured) or 258 dp (Android's default text size): the wheel and its
 * 2-line legend (this + 34 / 38), Brightness (35 + 10) and the Follow card (58 / 65 + 10) fit with this wheel at either
 * size — the left column (swatches) is as tall. Your own colours' four wheels are 120 dp.
 */
private val WIDE_WHEEL = 100.dp

/** Device pass (P2-7): the wheel with its colour code beside it, level with the wheel's middle (its legend runs under the
 *  wheel) — the D-pad: Right from the wheel to the code, Down from either to Brightness. The bottom screen keeps the code
 *  under a bigger wheel ([WheelAndCode]). */
@Composable
private fun WheelBesideCode(current: Int, size: androidx.compose.ui.unit.Dp, setColour: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        app.wayfinder.ui.ColorWheel(current, size, setColour)
        HexColorField(current, setColour, Modifier.padding(top = (size - HEX_SQUARE) / 2), previewSize = HEX_SQUARE, fieldWidth = 110.dp,
            textSize = 16.sp)
    }
}

/** The colour code's square on Stick lights (its row is this tall). */
private val HEX_SQUARE = 32.dp

/** One column (the bottom screen, or an effect without a colour): its choices, the wheel and code, speed, brightness, and
 *  Screen colour's cards. */
@Composable
private fun LightsColumn(p: LightProfile, picks: ColourPicks, current: Int, perRow: Int, setColour: (Int) -> Unit, save: (LightProfile) -> Unit) {
    if (p.mode in COLOURED) {
        ColourChoice(picks, current, perRow, setColour)
        WheelAndCode(current, 240.dp, setColour)
    }
    if (p.mode in ANIMATED) SpeedRow(p, save)
    if (p.mode != LightMode.AYN && p.mode != LightMode.OFF) LightsBrightness(p, word = p.mode in ANIMATED, save)
    if (p.mode == LightMode.SCREEN) {
        // QA2 (measured): the effect reads the screen several times a second — say what it costs. Build 18 (B12, pF22): short
        Caption("Screen colour costs about 0.2–0.3 W more than the other effects: it reads the screen several times a second.")
        // 1.3 (GitHub #15): like BiFrost — each ring its side of the screen
        SettingCard("Each stick its own side", if (p.split) "Left ring = the left half of the screen, right ring = the right half"
            else "Both rings show the whole screen's main colour", checked = p.split, onChecked = { save(p.copy(split = it)) })
        // 1.3 (GitHub #33): a DS game's bottom screen is menus — keep the colours on the top one
        SettingCard("Colour from", when (p.screenFrom) {
            1 -> "Always the top screen"
            2 -> "Always the bottom screen"
            else -> "The screen with the controller (a touch on the other screen moves it)"
        }) {
            app.wayfinder.ui.GlassSegmentedControl(listOf("Controller's screen", "Top screen", "Bottom screen"), p.screenFrom,
                Modifier.fillMaxWidth()) { save(p.copy(screenFrom = it)) }
        }
        Caption("The rings follow the colours of the game's screen, a few times a second.")
    }
}

/** Build 18 (B19, MORE-D17): Speed as a level row like Sound's and Appearance's — "Speed" [slider] "0.5×" and which way
 *  (the value left the header; "(left slower, right faster)" went). Log scale: 0.25× … 4×, 1× in the middle. */
@Composable
private fun SpeedRow(p: LightProfile, save: (LightProfile) -> Unit) {
    val s = p.speed
    val pos = (kotlin.math.ln(s / 0.25f) / kotlin.math.ln(16f)).coerceIn(0f, 1f)
    LevelRow(null, "Speed", compact = false, value = String.format("%.2f", s).trimEnd('0').trimEnd('.') + "×", pos = pos, step = 0.04f,
        iconSlot = false, trailing = { RowWord(if (s < 0.99f) "slower" else if (s > 1.01f) "faster" else "") }) { v ->
        save(p.copy(speed = (0.25f * Math.pow(16.0, v.toDouble()).toFloat())))
    }
}

/** Brightness as a level row ("Brightness" [slider] "30 %"), then "Follow the screens' brightness". [word]: the Speed row
 *  above has its word after the value — the same room here keeps the two sliders one length. */
@Composable
private fun LightsBrightness(p: LightProfile, word: Boolean, save: (LightProfile) -> Unit) {
    LevelRow(null, "Brightness", compact = false, value = "${(p.brightness * 100).toInt()} %", pos = p.brightness, iconSlot = false,
        trailing = if (word) ({ RowWord("") }) else null) { v -> save(p.copy(brightness = v.coerceAtLeast(0.01f))) }
    // 1.4 (Reddit). Build 18 (B3): a registry card (Search opens the page on it)
    SettingCard(NavCards.LIGHTS_FOLLOW, if (p.followScreen) "The rings dim and brighten with the screens — the slider above is how bright they are with the screens at full"
        else "Off — the rings stay at the brightness above", checked = p.followScreen, onChecked = { save(p.copy(followScreen = it)) })
}

/** The word after a level row's value ("slower" / "faster"), in a slot of one width. */
@Composable
private fun RowWord(w: String) {
    androidx.compose.material3.Text(w, color = app.wayfinder.ui.LocalGlass.current.textTertiary,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(56.dp))
}

/** The swatches, [perRow] a row, spread across the row (build 18, B19 / MORE-D16: they took half of it). */
@Composable
private fun SwatchRows(selected: Int, perRow: Int, onPick: (Int) -> Unit) {
    for (row in PALETTE.chunked(perRow.coerceAtLeast(1))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            for (c in row) Swatch(c, c == selected) { onPick(c) }
        }
    }
}

/** dev build 17 (WP8): selected = a check inside the colour (black on a light one) — a ring is focus's look. */
@Composable
private fun Swatch(c: Int, selected: Boolean, onClick: () -> Unit) {
    FocusableGlass(onClick = onClick, radius = Radii.Swatch) {
        Box(Modifier.padding(6.dp).size(36.dp).background(Color(c), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = inkOn(c))
        }
    }
}
