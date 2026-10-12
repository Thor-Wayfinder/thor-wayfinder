package app.wayfinder

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.BackLayer
import app.wayfinder.ui.Caption
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.EmptyState
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.GlyphText
import app.wayfinder.ui.LocalEntryFocus
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.LocalPadLook
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.currentLook
import app.wayfinder.ui.rememberConfirmState

/*
 * dev build 17 (WP11, theme H): the Controller page is a list of controllers, the Thor first; each opens ONE page template
 * ([ControllerDetailPage]: the Thor's "thor", each connected one's "extpad:<key>") — its sections from what it can do
 * ([ControllerRules.sections]), a status header in its own symbols ([CtlWords.status]). The rules and words are in
 * ControllerRules.kt (pure); the paths to these pages in NavPaths.kt.
 */

/** Debug log for device test S6: which kind of row has the controller (its kind only — never a name). */
private fun rowLog(kind: String): Modifier =
    if (BuildConfig.DEBUG) Modifier.onFocusChanged { if (it.hasFocus) Log.d("ThorHub", "row $kind") } else Modifier

/** The Controller page: the controllers (the Thor first, then each connected one by player), their combos and where
 *  Game controls work, and which screen gets the controller. (Was the Thor's section, the other controllers and More
 *  options: their cards moved — the Thor's to its page, the input layer's up here.) */
@Composable
internal fun ControllerListPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val pv = ExtPadsCtl.version.intValue
    val thorAlone = ExtPadsCtl.byPlayer().isEmpty() && ExtPadsCtl.isOn(LocalContext.current)
    // build 18 (C12, T18-03 / H5): the Thor alone — the list stays (WP11), its words say what is on it
    SubPage(myDisplayId, NavPages.title(HubPage.CONTROLLER),
        if (thorAlone) "The Thor's controller and which screen gets it" else "Every controller, the Thor first — and which screen gets the controller",
        onBack) {
        @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + ExtPadsCtl.version.intValue + AppConfigStore.version.intValue
        val ctx = LocalContext.current
        val on = ExtPadsCtl.isOn(ctx)
        SectionHeader("Controllers")
        // dev build 16 (WP7): one player order wherever controllers are listed; each row's id stays when its name changes
        val pads = ExtPadsCtl.byPlayer()
        // the Thor's row in the Thor's names (the window speaks the hand's, dev build 16 WP7). Build 18 (C7, VD-07 / D21): one
        // summary template for every row (its player only beside another controller), the Thor's icon, a chevron
        // dev build 18f2 (device test t2 BUG-1): every number from ExtPadsCtl.playerNo — what games see now, then, while a new
        // order waits for a game, what it becomes (the Thor's row read the order asked, the controllers' rows what games see)
        val thorNo = if (pads.isEmpty()) ControllerRules.PlayerNo(0) else ExtPadsCtl.playerNo(ctx, null)
        // b18n (N3-ui 3, b18m check obs. 1): a controller's name on one line ("…" past it), its status wrapping beside it
        CompositionLocalProvider(LocalPadLook provides ButtonNames.thorLook) {
            GlassListRow(NavPages.title(HubPage.THOR), rowLog("thor"), value = thorSummary(thorNo.now,
                    CtlWords.afterClose(ExtPadsCtl.orderWaitsLabel(ctx), thorNo.after)),
                icon = Icons.Rounded.SportsEsports, id = Nav.rowTo(HubPage.THOR), end = RowEnd.CHEVRON, labelLines = 1) { go(HubPage.THOR) }
        }
        // b18n (N3-ui 3, b18w w1): an away one's row says its kept place, as its page does
        for (pad in pads) GlassListRow(pad.name, rowLog("pad"), value = if (pad.away) ExtPadsCtl.playerNo(ctx, pad).let { no ->
                CtlWords.awayRow(no.now, CtlWords.afterClose(ExtPadsCtl.orderWaitsLabel(ctx), no.after)) } else padSummary(ctx, pad),
            icon = Icons.Rounded.Gamepad, id = Nav.rowTo(HubPage.extpad(pad.key)), end = RowEnd.CHEVRON, labelLines = 1) { go(HubPage.extpad(pad.key)) }
        // build 18 (C12, T18-03): the Thor alone — no empty-state paragraph: the row under it says how to add one
        if (pads.isEmpty() && !on) {
            EmptyState("Wayfinder doesn't read other controllers now: ${Say.path(NavCards.HANDLES)}.")
            NavLink(NavCards.HANDLES, go)                                   // dev build 17 (WP9): the way there
        }
        GlassListRow(NavCards.EVERY.title, rowLog("every"), value = when {
                !on -> "off — Wayfinder doesn't read them"
                pads.isEmpty() -> "pair a controller in Bluetooth settings"
                else -> "player order, TV, Joy-Cons"
            },
            icon = Icons.Rounded.Tune, id = NavCards.EVERY.id, end = RowEnd.CHEVRON) { go(HubPage.EXTPADS) }
        if (ExtPadsCtl.holdBroken) Caption("AYN's system took over a controller: it now uses AYN's id, so an emulator may lose it after a reconnect.")

        SectionHeader("Combos and Game controls")
        AynHomeWarning()
        // build 18 (C7, VD-07): the Combos icon; (C3's rule, C10 / ps-F7) its buttons in the hand's names
        GlassListRow(NavCards.COMBOS.title, rowLog("combos"), value = combosValue(ButtonNames.handLook),
            icon = Icons.Rounded.Keyboard, id = NavCards.COMBOS.id, end = RowEnd.CHEVRON) { go(HubPage.CONTROLS) }
        // out of More options (dev build 17, WP11): where Game controls work is a first question, not a hidden one
        GameControlsWhereCard()
        // b18i (D1, 2026-10-10): Game controls beside the game, which keeps running
        GameControlsScreenCard()

        SectionHeader("Screens")
        ControllerScreenCard()
    }
}

/** The Thor's row: its player ([player], 0 = not said), its face buttons, then what it is docked and its deadzone for every
 *  game. Build 18 (C7, VD-07): the one template ([ControllerRules.ctlSummary]); its combos are on the Combos row. */
internal fun thorSummary(player: Int = 0, after: String? = null): String = ControllerRules.ctlSummary(player, ButtonNames.thorStyle.label, null, listOfNotNull(
    // dev build 18f: or one playing as it by its switch (the Thor's own buttons too) — b18n (N3-ui 3): while it is connected
    ExtPadsCtl.dockedPad()?.let { "docked: ${it.name} plays as it" } ?: ExtPadsCtl.seatPadHere()?.let { "${it.name} plays as it too" },
    AppSettings.stickDefaults.let { d -> if (d.stickL.isDefault && d.stickR.isDefault) null else "deadzone ${d.stickL.dead} / ${d.stickR.dead} %" },
), after = after)                                                       // (dev build 18f2: [ControllerRules.playerNo])

/** Build 18 (C11, T18-10): the name games list for the Thor's controller — the Thor's page, what games see (it was filed
 *  under "Settings for every controller"). The same setting as before ([ExtPadsCtl.setThorNamed]). */
@Composable
private fun ThorNameCard() {
    val ctx = LocalContext.current
    var named by remember { mutableStateOf(ExtPadsCtl.thorNamed(ctx)) }
    SettingCard(NavCards.THOR_NAME,
        (if (named) "Games list it as “Odin Controller (Wayfinder)”" else "Off — games list it as “Odin Controller”") +
            " — it tells the Thor's real controller apart: games list every controller, hidden ones too (Eden shows two " +
            "“Odin Controller”). Emulators that saved your buttons under the old name may ask you to map them again. Works " +
            "while Game controls are on.",
        checked = named, onChecked = { named = it; ExtPadsCtl.setThorNamed(ctx, it) })
}

/** The Combos row's value: how many, and the buttons they start on, in [look]'s names (build 18, C3's rule: never typed). */
private fun combosValue(look: PadLook): String =
    "${ControlsStore.active().size} set · ${Say.btn(ThorButton.HOME, look)}, ${Say.btn(ThorButton.BACK, look)}, ${Say.btn(ThorButton.AYN, look)}"

/** Where Game controls work (the input layer) — 1.4: everywhere, only the games set up, nowhere.
 *  dev build 17 (WP11): moved as it was from Controller › More options (rule 7: its one editor — Features links here). */
@Composable
internal fun GameControlsWhereCard() {
    val ctx = LocalContext.current
    var layerMode by remember { mutableStateOf(PadLayerCtl.mode) }
    // dev build 17 (WP9): the line says what happens now (≤ 20 words, PageWordsTest); the rest behind "?" — the emergency off
    // in the Thor's names, or the docked controller's (critique F1)
    SettingCard(
        NavCards.LAYER,
        when (layerMode) {
            PadLayerCtl.Mode.EVERYWHERE -> "Every app gets Game controls: its own buttons, gyro and macros."
            PadLayerCtl.Mode.SET_UP -> "Only the games you set up get Game controls; elsewhere the Thor's controller as it is." +
                // dev build 16 (theme F): the combo as the controller in hand says it
                (Say.combo(ThorAction.GAME_CONTROLS, currentLook())?.let { " Set one up in Game controls ($it)." } ?: "")
            PadLayerCtl.Mode.NOWHERE -> "Off: Game controls, gyro and macros do nothing."
        } + " Games get an identical copy of the controller. One app differently: Games. Emergency off from anywhere: " +
            "${Say.emergencyOff(ctx)}.",
    ) {
        // build 18 (A21 adoption): what each choice does, while it is focused
        GlassSegmentedControl(PadLayerCtl.Mode.values().map { it.label }, layerMode.ordinal, Modifier.fillMaxWidth(),
            hints = listOf("every app gets Game controls", "only the games you changed", "Game controls do nothing")) {
            layerMode = PadLayerCtl.Mode.values()[it]; PadLayerCtl.setMode(ctx, layerMode)
        }
    }
}

/** b18i (D1, 2026-10-10): where Game controls opens over a game. On: on the screen the game isn't on, so the game keeps
 *  running there (ForegroundAppService.openGameControls; one screen, docked, or that screen dark or covered: where the Hub
 *  is). Off (the default): where the Hub is, as before — covered by the page, some games pause (Eden, GameNative). */
@Composable
internal fun GameControlsScreenCard() {
    SettingCard(
        NavCards.GC_SCREEN,
        if (AppSettings.controlsOtherScreen) "On — Game controls opens on the screen the game isn't on, so the game keeps running. " +
            "One screen or docked: where the Hub is."
        else "Off — Game controls opens where the Hub is. Over the game, some games pause until you resume them in the game.",
        checked = AppSettings.controlsOtherScreen, onChecked = { AppSettings.setControlsOtherScreenOn(it) },
    )
}

/** Theme H's status header facts for the Thor's page ([pad] null) or a controller's, in [look]'s names. */
private fun statusOf(ctx: Context, pad: ExtPadsCtl.Pad?, look: PadLook, docked: ExtPadsCtl.Pad?): CtlStatus {
    // build 18 (C9, T18-04): the Thor alone has no player to speak of (no "player 1" beside nobody)
    // dev build 18f: one playing as the Thor's by its switch (undocked) — the Thor's page names it, its own says it. b18n
    // (N3-ui 3): while it is connected (away, its own page says so — AWAY reads no seat)
    val seat = if (docked == null) ExtPadsCtl.seatPadHere() else null
    // dev build 18f2 (device test t2 BUG-1): the one source of every number ([ExtPadsCtl.playerNo]: the list's too)
    val no = if (pad == null && ExtPadsCtl.byPlayer().isEmpty()) ControllerRules.PlayerNo(0) else ExtPadsCtl.playerNo(ctx, pad)
    val after = CtlWords.afterClose(ExtPadsCtl.orderWaitsLabel(ctx), no.after)
    if (pad == null) return CtlStatus(CtlState.THOR,
        // dev build 18f (review): without the one playing as it (its number is the copy's, as on its own page)
        player = no.now,
        home = Vocab.name(ThorButton.HOME, look), gameControls = Say.combo(ThorAction.GAME_CONTROLS, look),
        docked = docked != null, dockedName = docked?.name ?: seat?.name, seated = seat != null, after = after)
    val homeOurs = ExtPadsCtl.homeIsThors(ctx, pad.key)
    return CtlStatus(
        state = when { pad.away -> CtlState.AWAY; pad.ours == null -> CtlState.STARTING; else -> CtlState.HANDLED },
        player = no.now, after = after, home = Vocab.name(ThorButton.HOME, look), homeOurs = homeOurs,
        // dev build 17 (theme G): its Back in its words — its own button ("touchpad click") or Home + Select standing in
        back = Say.spare(ExtPadsCtl.spares(ctx, pad).back, look),
        // its Home goes to the game: Game controls is the Thor's combo, on the Thor
        gameControls = Say.combo(ThorAction.GAME_CONTROLS, if (homeOurs) look else ButtonNames.thorLook), gcOnThor = !homeOurs,
        docked = docked?.key == pad.key, seated = seat?.key == pad.key)
}

/**
 * One controller's page — the Thor's ([CtlRef.Thor], "thor") or a connected one's ([CtlRef.Pad], "extpad:<key>": found by
 * its key, the same page whatever slot it reconnects on). ONE template (dev build 17, WP11): the sections its caps give, in
 * order; its own names everywhere (the Thor's in the Thor's); the status header under its name. A connected one's every-game
 * own controls open over it ([editOwn]: ExtPadsPage.kt's layer); its button setup is a layer of this page.
 */
@Composable
internal fun ControllerDetailPage(myDisplayId: Int, ref: CtlRef, onBack: () -> Unit, go: (String) -> Unit, editOwn: () -> Unit = {}) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue + ControlsStore.version.intValue + PadProfiles.version.intValue
    val pad = (ref as? CtlRef.Pad)?.let { r -> ExtPadsCtl.byPlayer().firstOrNull { it.key == r.key } }
    // b18g (WI-6, g1 O2): "Forget it" confirmed on this page — the page that stays says so (it said "It disconnected")
    var forgot by remember(ref.key) { mutableStateOf(false) }
    if (ref is CtlRef.Pad && pad == null) {
        SubPage(myDisplayId, if (forgot) "Controller forgotten" else "Controller gone",
            if (forgot) "The next time it connects, it's made again" else "It disconnected (turned off, out of battery or out of range)", onBack) {
            // build 18 (C7 addendum, ps-F4): a way back is an arrow (a blue × reads as PlayStation's ✕)
            GlassActionButton("Back to the controllers", Icons.AutoMirrored.Rounded.ArrowBack, Modifier.fillMaxWidth()) { onBack() }
        }
        return
    }
    // debug builds: a test hook opens it on its wizard (device test S9) — once
    var wizard by remember(ref.key) { mutableStateOf(BuildConfig.DEBUG && ExtPadsCtl.wizardNext == ref.key) }
    // dev build 17 (theme G): the wizard's two steps alone — its Back and AYN button ("Back and AYN buttons")
    var wizardOnly by remember(ref.key) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(ref.key) { if (ExtPadsCtl.wizardNext == ref.key) ExtPadsCtl.wizardNext = null }
    // dev build 15 (WP2): its page speaks its names (its kind's marks, its face) — the away rows and the wizard too; the
    // Thor's page the Thor's (the window speaks the hand's, dev build 16 WP7)
    val look = if (pad != null) ExtPadsCtl.padLook(pad.key) else ButtonNames.thorLook
    val docked = ExtPadsCtl.dockedPad()
    val caps = ControllerRules.caps(thor = pad == null, kind = pad?.kind ?: ControllerKind.THOR, away = pad?.away == true,
        touchpad = pad?.proto == "sony")                                // dev build 18f: wfhid reads its touchpad
    CompositionLocalProvider(LocalPadLook provides look) {
    SubPage(myDisplayId, pad?.name ?: NavPages.title(HubPage.THOR), CtlWords.status(statusOf(ctx, pad, look, docked)), onBack) {
        val g = LocalGlass.current
        // dev build 16 (WP7, theme E): the wizard is a layer of this page (B and its Cancel close it) — the focus comes back
        // to its row
        val entry = LocalEntryFocus.current
        if (wizard && pad != null) {
            val close: () -> Unit = { wizard = false; entry?.request(NavCards.PAD_SETUP.id) }
            BackLayer(onBack = close) { SetupWizard(pad.node, close) }
            return@SubPage
        }
        val only = wizardOnly
        if (only != null && pad != null) {
            val close: () -> Unit = { wizardOnly = null; entry?.request(NavCards.PAD_SPARES.id) }
            BackLayer(onBack = close) { SetupWizard(pad.node, close, only = only) }
            return@SubPage
        }

        // review C-APP-4: the rows that come and go with the controller (away "S 0" / back "S 1") — one of them focused as
        // it went: the focus to the row that came ("Forget it" / "Set up its buttons"), not lost (the next D-pad press
        // started from the top of the screen)
        val forgetF = remember { FocusRequester() }
        val wizardF = remember { FocusRequester() }
        val awayNow by rememberUpdatedState(pad?.away == true)
        var changingFocused by remember { mutableStateOf(false) }
        fun handOn(shownAway: Boolean) = Modifier.onFocusChanged {
            if (it.isFocused) changingFocused = true else if (awayNow == shownAway) changingFocused = false   // moved on
        }
        LaunchedEffect(pad?.away) {
            if (!changingFocused) return@LaunchedEffect
            withFrameNanos { }                                          // after the rows changed
            runCatching { (if (pad?.away == true) forgetF else wizardF).requestFocus() }
        }
        // b18g (WI-6, g1 O2 / t1 C4): two presses, as every row that drops something ("Press again to …", 3 s) — one press
        // forgot it at once
        val forgetAsk = rememberConfirmState(pad?.key, pad?.away)
        if (pad?.away == true) GlassListRow("Forget it", Modifier.focusRequester(forgetF).then(handOn(true)),
            value = if (forgetAsk.asked) "Press again to forget it" else "the next time it connects, it's made again",
            icon = Icons.Rounded.DeleteOutline) {
            forgetAsk.press { forgot = true; ExtPadsCtl.forget(pad.key) }
        }
        // dev build 16 (theme F): Game controls' combo in its names — the Thor's, "on the Thor", while its Home goes to the game
        var homeThor by remember(ref.key) { mutableStateOf(pad == null || ExtPadsCtl.homeIsThors(ctx, pad.key)) }
        val gc = if (homeThor) Say.combo(ThorAction.GAME_CONTROLS, look)
            else Say.combo(ThorAction.GAME_CONTROLS, ButtonNames.thorLook)?.let { "$it on the Thor" }

        // build 18 (C7, VD-12): the rows that come and go — a section's header only over 2 or more
        val every = pad?.let { PadProfiles.key(PadProfiles.EVERY, it.key) }
        val facts = CtlRowFacts(ownEdit = pad != null && PadControls.mode(ctx, pad.key) == PadControls.Mode.OWN,
            removable = every != null && PadProfiles.has(every), previous = every != null && PadControls.hasPrevious(every),
            muted = pad?.muted == true, joyCon = pad?.joyCon == true,
            custom = pad != null && ExtPadsCtl.savedMap(ctx, pad.key, pad.sideways) != null, rumbles = pad?.rumbles == true)
        // each section keeps its own state when another comes or goes (the test while it's away)
        for (s in ControllerRules.sections(caps)) key(s) {
            if (ControllerRules.headed(s, caps, facts)) SectionHeader(CtlWords.TITLES.getValue(s))
            when (s) {
                // ── its face buttons: remembered for this controller, in every app ──
                CtlSection.FACE -> if (pad == null) {
                    // dev build 15 (WP2): ONE style and ONE setter for this page, the tile and the names (FaceStyleControl.kt)
                    ThorFaceCard()
                    NamesAsPrintedCard()
                } else PadFaceCard(pad)            // dev build 15 (WP2, D1): Automatic first (its maker's convention)
                // ── its drift, and a deadzone of its own (every game) ──
                CtlSection.TEST -> GlassListRow(NavCards.TEST_IT.title, if (pad != null) handOn(false) else Modifier,
                    value = (if (pad != null) ExtPadsCtl.padSticks(ctx, pad.key)
                        else AppSettings.stickDefaults.let { d -> if (d.stickL.isDefault && d.stickR.isDefault) null else d.stickL.dead to d.stickR.dead })
                        ?.let { (l, r) -> "deadzone $l / $r %" } ?: "sticks, triggers, buttons",
                    icon = Icons.Rounded.Speed, id = NavCards.TEST_IT.id, end = RowEnd.CHEVRON) { go(HubPage.padtestFor(ref.key)) }
                // ── what it does in games ──
                CtlSection.IN_GAMES -> if (pad == null) {
                    // the Thor has no every-game controls: each game's are on its page (Games), or Home + the top button in it.
                    // Build 18 (C7, CTL-07 / VD-15): the row says the page it opens; (VD-13) no loose caption under it
                    GlassListRow(NavPages.title(HubPage.APPS), value = "each game's own controls" + (gc?.let { " · or $it in a game" } ?: ""),
                        icon = Icons.Rounded.Tune, id = "thor:games", end = RowEnd.CHEVRON) { go(HubPage.APPS) }
                } else {
                    // dev build 16 (WP4, theme H): a pointer — its own controls are kept when you switch away ([PadControls.setMode])
                    var mode by remember(pad.key, PadProfiles.version.intValue) { mutableStateOf(PadControls.mode(ctx, pad.key).ordinal) }
                    // dev build 18f: playing as the Thor's by its switch, the Thor's controls apply — its choice stays editable
                    // (it applies when the switch is off), as docked
                    val seatedNow = ExtPadsCtl.seatPad()?.key == pad.key && !ExtPadsCtl.seatDocked
                    SettingCard(NavCards.PAD_GAMES,
                        (if (seatedNow) CtlWords.seatGames + " " else "") + when (mode) {
                            // dev build 17 (WP9): the effect first, the rest behind "?"
                            1 -> "Its own controls: the same in every game, set on the Game controls page. Buttons, keys, macros, " +
                                "turbo, sticks. A game can still have its own for it: ${gc ?: "Game controls"} in that game, on its tab. " +
                                "They're kept when you switch away."
                            2 -> "Plain buttons: each button is itself in every game — nothing from Game controls."
                            // build 18 (C7, CTL-17): the combo stays on the line (in parentheses Say.split moved it behind "?")
                            else -> "Like the Thor's: each game's Game controls work here too" + (gc?.let { ", opened with $it" } ?: "") +
                                " — button changes, keys, macros, turbo, sticks as a mouse, shapes. Nothing to set here."
                        },
                    ) {
                        // build 18 (A21 adoption): what each choice does, while it is focused
                        GlassSegmentedControl(listOf("Like the Thor's", "Its own", "Plain"), mode, Modifier.fillMaxWidth(),
                            hints = listOf("what Game controls sets for a game works here too", "the same in every game, set in Game controls",
                                "each button is itself, nothing from Game controls")) { i ->
                            mode = i
                            // its own, every game: the same Game controls page (switched back on as it was, or new: the Thor's usual
                            // controls — none); Like the Thor's / Plain switch it off, never delete it
                            PadControls.setMode(ctx, pad.key, PadControls.Mode.values()[i])
                        }
                        // build 18 (C7, VD-13): the card's last line (it was a loose note at its own left edge)
                        Caption(when {
                            gc == null -> "One game differently for this controller: Game controls in that game, on its tab."
                            homeThor -> "One game differently for this controller: in that game, $gc on it — Game controls opens on its tab."
                            else -> "One game differently for this controller: in that game, $gc, then its tab."
                        })
                    }
                    if (mode == 1) GlassListRow("Edit its own controls", value = "the same in every game", icon = Icons.Rounded.Tune, id = "pad:edit",
                        end = RowEnd.CHEVRON) { editOwn() }
                    // dev build 17 (theme H): deleting is its own action, two presses (D7: switching never deletes) — and what it
                    // removed is kept for Bring back (as in Game controls › More › These controls)
                    val every = PadProfiles.key(PadProfiles.EVERY, pad.key)
                    if (PadProfiles.has(every)) ConfirmButton("Remove its own controls", "Press again to remove them", {
                        PadControls.remove(every)
                        // review H-REMOVE-FOCUS-LOST: this button goes (and "Edit its own controls") with the focus — to its
                        // undo, which Remove always makes (the next D-pad press started from the top of the screen)
                        entry?.request("pad:bringback")
                        // dev build 17 (WP9): its Bring back is right here, under it (Game controls' path would be wrong)
                        Overlays.pill(GameWords.removed(pad.name, "every game", undo = "“Bring back its earlier controls” below"),
                            CueOrigin.Window(myDisplayId))
                    }, Modifier.fillMaxWidth())
                    if (PadControls.hasPrevious(every)) GlassListRow("Bring back its earlier controls", value = "what a change or Remove replaced",
                        icon = Icons.Rounded.Restore, id = "pad:bringback") {
                        if (PadControls.bringBack(every)) {
                            Overlays.pill(GameWords.broughtBack(pad.name, "every game"), CueOrigin.Window(myDisplayId))
                            // review H-REMOVE-FOCUS-LOST: nothing more to bring back — this row goes with the focus: to the
                            // controls it brought back ("Edit its own controls"), else the mode's card
                            if (!PadControls.hasPrevious(every))
                                entry?.request(if (PadControls.mode(ctx, pad.key) == PadControls.Mode.OWN) "pad:edit" else NavCards.PAD_GAMES.id)
                        }
                    }
                }
                // ── its Home, and the combos (the Thor's, on every controller whose Home is the Thor's) ──
                CtlSection.HOME -> if (pad == null) {
                    GlassListRow(NavCards.COMBOS.title, value = combosValue(look), icon = Icons.Rounded.Keyboard,
                        id = NavCards.COMBOS.id, end = RowEnd.CHEVRON) { go(HubPage.CONTROLS) }
                    HomeTwiceCard()
                } else {
                    // dev build 16 (theme F): its combos in its names, whatever the switch
                    val combos = listOfNotNull(Say.combo(ThorAction.GAME_CONTROLS, look)?.let { "$it: Game controls" },
                        Say.combo(ThorAction.KEYBOARD, look)?.let { "$it…" }).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
                    // build 18 (C9, VD-27 / FTU-14 / ps-F8): the switch says what its Home does, in its own name ("PS works as
                    // Home": "Its Home is the Thor's" was hard to parse) — a press and a hold now, the rest behind "?". Round 2:
                    // CtlWords' words, so a Home named Home (Switch Pro…) or a Home-less one's Select + Start reads right
                    val home = Vocab.name(ThorButton.HOME, look)
                    SettingCard(CtlWords.homeTitle(home),
                        short = CtlWords.homeLine(home, on = homeThor, combos = combos),
                        more = "Wayfinder's combos need it on; the Thor's own Home always works.",
                        checked = homeThor, onChecked = { homeThor = it; ExtPadsCtl.setHomeIsThors(ctx, pad.key, it) })
                    // dev build 17 (theme G, D4): its Back and its AYN button (and its controls back after Home + Back held 5 s)
                    PadSparesRows(pad) { wizardOnly = WizardSteps.SPARE }
                    // dev build 18f: its touchpad moves Android's pointer — on by default, PlayStation controllers
                    if (caps.touchpad) PadTouchpadCard(pad, look)
                    // (C9): its combos are the Thor's — said once, with the one it presses for Game controls
                    GlassListRow(NavCards.COMBOS.title, value = "same as the Thor's" + (gc?.let { " · $it: Game controls" } ?: ""),
                        icon = Icons.Rounded.Keyboard, id = NavCards.COMBOS.id, end = RowEnd.CHEVRON) { go(HubPage.CONTROLS) }
                }
                // ── its motion aim (dev build 16, WP4, D10: the kinds with motion) ──
                CtlSection.MOTION -> if (pad != null) {
                    var gy by remember(pad.key) { mutableStateOf(ExtPadsCtl.gyro(ctx, pad.key)) }
                    fun setG(n: ExtPadsCtl.Gyro) { gy = n; ExtPadsCtl.setGyro(ctx, pad.key, n) }
                    SettingCard("Aim with motion",
                        "Turn and tilt the controller to move a stick, for aiming in shooters. Turning left-right moves it " +
                            "sideways, tilting up-down moves it up and down. Put the controller down for a second after " +
                            "choosing, so it learns its rest.",
                        // dev build 16 (WP6): the same rows as Game controls' motion popup (one game's own aim)
                        body = { PadMotionRows(gy) { setG(it) } })
                }
                // ── its buttons and sticks: when they're read wrong ──
                CtlSection.SETUP -> if (pad != null) {
                    if (pad.joyCon) {
                        var side by remember(pad.node, pad.sideways) { mutableStateOf(pad.sideways || ExtPadsCtl.sideways(ctx, pad.key)) }
                        // build 18 (C7): what the switch does now (it described On while off)
                        SettingCard("Held sideways",
                            if (side) "On: a small controller of its own — its stick is the left stick, its four buttons the face buttons, " +
                                "SL / SR the shoulders. Holding SL + SR for a moment turns it sideways too."
                            else "Off: held upright — holding SL + SR for a moment turns it sideways, a small controller of its own.",
                            checked = side, onChecked = { side = it; ExtPadsCtl.setSideways(ctx, pad.key, it) })
                    }
                    val custom = ExtPadsCtl.savedMap(ctx, pad.key, pad.sideways) != null
                    // away: nothing to hear (the wizard, re-centring, rumbling wait for it to be back)
                    // build 18 (C7, VD-07 / addendum ps-F4, ps-F16): an icon by job; the wizard opens over the page (a chevron)
                    if (!pad.away) GlassListRow(NavCards.PAD_SETUP.title, Modifier.focusRequester(wizardF).then(handOn(false)),
                        value = if (custom) "set up by you — set up again" else "a button or stick is read wrong",
                        icon = Icons.Rounded.Build, id = NavCards.PAD_SETUP.id, end = RowEnd.CHEVRON) { wizard = true }
                    if (custom) GlassListRow("Back to automatic buttons", value = if (pad.sideways) "held sideways" else "", icon = Icons.Rounded.RestartAlt) {
                        ExtPadsCtl.saveMap(ctx, pad.key, null, pad.sideways)
                    }
                    if (!pad.away) GlassListRow("Re-centre its sticks", handOn(false), value = "leave them at rest, then press", icon = Icons.Rounded.CenterFocusStrong) {
                        ExtPadsCtl.center(pad.key)
                        Overlays.pill("${pad.name}: sticks re-centred", CueOrigin.Window(myDisplayId))
                    }
                }
                // ── what games see: one id and one place, kept while it's off for a while (dev build 15, C4 / C6) ──
                CtlSection.ABOUT -> if (pad == null) ThorNameCard()
                else {
                    PadSeatCard(pad, look)                              // dev build 18f: the Thor's controller for games
                    Text(identityLine(ctx, pad), color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    // build 18 (C7, CTL-18): it says so on screen too (nothing showed it happened)
                    if (pad.rumbles && !pad.away) GlassListRow("Find it", handOn(false), value = "it rumbles for a moment", icon = Icons.Rounded.Vibration) {
                        ExtPadsCtl.buzz(pad.key)
                        Overlays.pill("${pad.name} rumbles", CueOrigin.Window(myDisplayId))
                    }
                }
            }
        }
    }
    }
}

/** dev build 18f (b18f): "Plays as the Thor's controller" — off by default (its own player). On, and connected, it
 *  drives the Thor's copy undocked too, the Thor's own buttons at the same time: games see only the Thor's controller, so an
 *  emulator set up for the Thor plays with it unchanged. The first connected one with it on; its page says who has it. */
@Composable
private fun PadSeatCard(pad: ExtPadsCtl.Pad, look: PadLook) {
    val ctx = LocalContext.current
    var on by remember(pad.key) { mutableStateOf(ExtPadsCtl.seatOn(ctx, pad.key)) }
    val back = (ExtPadsCtl.spares(ctx, pad).back as? Say.Spare.Own)?.name
    SettingCard(NavCards.PAD_SEAT,
        short = if (on) "On: games see only the Thor's controller — this one and the Thor's own buttons both drive it."
            else "Off: it's its own player — games see it as “${ExtPadsCtl.shownName(pad)}”.",
        more = if (on) "It uses the Thor's controls and face buttons, and games don't see it on its own, so an emulator set up " +
            "for the Thor needs no change. With two on, the first connected plays as the Thor's. It needs Game controls on in " +
            "that app. " +
            // its own Home + Back reach the Thor's copy (wfpad's emergency); a Back standing in (Home + Select) is Wayfinder's,
            // never a Back the copy sees — then only the Thor's own
            (if (back != null) "${Say.homeAndBack(look, back)} held 5 seconds then turns Game controls off for every controller, " +
                "like the Thor's own."
            else "Holding ${Say.emergencyCombo(ButtonNames.thorLook)} for 5 seconds on the Thor turns Game controls off for " +
                "every controller.")
            else "On, an emulator set up for the Thor plays with it without setting it up again.",
        checked = on, onChecked = { on = it; ExtPadsCtl.setSeatOn(ctx, pad.key, it) })
    // who has it now, or why not this one
    val seat = ExtPadsCtl.seatPad()?.takeIf { !ExtPadsCtl.seatDocked }
    when {
        on && pad.aynId -> Caption("It uses AYN's id now, so it stays its own player for now.")
        on && seat != null && seat.key != pad.key -> Caption("${seat.name} plays as the Thor's now — this one is its own player.")
    }
}

/** dev build 18f (b18f): a PlayStation controller's touchpad moves Android's pointer — on by default. A finger moves
 *  it, a light tap clicks, two fingers scroll; pressing the touchpad stays its Back (its own button, through the mapping). */
@Composable
private fun PadTouchpadCard(pad: ExtPadsCtl.Pad, look: PadLook) {
    val ctx = LocalContext.current
    var on by remember(pad.key) { mutableStateOf(ExtPadsCtl.touchPointer(ctx, pad.key)) }
    // the touchpad's press is its own field, mapped as before: "stays its Back" only when its Back IS that press (review:
    // asked of the mapping, both lines); else what its setup gives it, and where its Back is (its words: "Home + Select" …)
    val (short, more) = Say.touchpadLines(on, ExtPadsCtl.backIsTouchClick(pad), Say.spare(ExtPadsCtl.spares(ctx, pad).back, look))
    SettingCard(NavCards.PAD_TOUCHPAD, short = short, more = more,
        checked = on, onChecked = { on = it; ExtPadsCtl.setTouchPointer(ctx, pad.key, it) })
}
