package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.focus.focusProperties
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.Caption
import app.wayfinder.ui.GlyphText
import app.wayfinder.ui.LocalPadLook
import app.wayfinder.ui.appendButton
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.LocalEntryFocus
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.currentLook
import app.wayfinder.ui.Radii
import kotlinx.coroutines.awaitCancellation

/**
 * Controller › Settings for every controller: reading them, the player order, the TV, the
 * Joy-Cons. dev build 17 (WP11): the controllers themselves are listed on the Controller page (one place per question), and
 * each controller's page is the template in ControllerPage.kt ([ExtPadPage], "extpad:<key>"; the Thor's "thor").
 */
@Composable
internal fun ExtPadsPage(myDisplayId: Int, onBack: () -> Unit) {
    SubPage(myDisplayId, NavPages.title(HubPage.EXTPADS), "Reading them, player order, TV, Joy-Cons", onBack) {
        val ctx = LocalContext.current
        @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
        var on by remember { mutableStateOf(ExtPadsCtl.isOn(ctx)) }
        // build 18 (C5, CTL-11 / VD-14): the page's main switch, first — what it does now, and two presses to turn it off (the
        // page opens on it: one A turned off every controller)
        SettingCard(NavCards.HANDLES,
            short = if (on) "On: Wayfinder reads each controller — games get every button and stick."
                else "Off: AYN's system reads them (some buttons get lost).",
            more = "AYN's system loses parts of some controllers: a Joy-Con's stick, a D-pad, the triggers, Start / Select…",
            checked = on, onChecked = { on = it; ExtPadsCtl.setOn(ctx, it) },
            confirmOff = "Press again: Wayfinder stops reading every controller")
        // who is player 1, 2, 3…: the whole order, by hand (each app can still put the Thor first or last)
        PlayerOrderCard()
        // b18m (2026-10-11): a controller connected for the first time starts with "Plays as the Thor's controller" on
        // — games keep seeing only the Thor's, so an emulator bound to it once (Dolphin's "Android/<number>/…") needs nothing
        // again. Off by default; a known controller keeps its own switch (its page)
        var seatNew by remember { mutableStateOf(ExtPadsCtl.seatNew(ctx)) }
        SettingCard(NavCards.SEAT_NEW, CtlWords.seatNew(seatNew),
            checked = seatNew, onChecked = { seatNew = it; ExtPadsCtl.setSeatNew(ctx, it) })
        var dockSwap by remember { mutableStateOf(ExtPadsCtl.dockSwap(ctx)) }
        // dev build 16 (theme F): Game controls' combo as the controller in hand says it. Build 18 (C10, ps-F7): its Home
        // button, said as a button
        val dockHome = Say.combo(ThorAction.GAME_CONTROLS, currentLook())?.let { "Its Home button works as the Thor's Home ($it: Game controls). " }
            ?: "Its Home button works as the Thor's Home. "
        // build 18 (C13, ps-F1 / H6): docked, its face buttons are the Thor's style — the confirm button can change
        val thorFace = ButtonNames.thorStyle.label
        // dev build 17 (WP9): what happens now first (≤ 20 words), the rest behind "?"
        SettingCard(NavCards.TV,
            (if (dockSwap) "On: docked to a screen, the controller you connect plays as the Thor's controller, as player 1. " +
                "Its face buttons then follow the Thor's ($thorFace). " +
                "It takes the Thor's name, and the Thor's controller is off. An emulator set up for the Thor plays as it " +
                "is. ${dockHome}Unplugging the screen, or the controller going away, " +
                "gives the Thor its controller back."
            else "Off: on a screen as everywhere — the Thor stays player 1.") +
                if (ExtPadsCtl.order == "dock") " Now: docked, a connected controller plays as the Thor's." else "",
            checked = dockSwap, onChecked = { dockSwap = it; ExtPadsCtl.setDockSwap(ctx, it) })
        // keyed on the helper's news: the L + R gesture turns it on too
        var combine by remember(ExtPadsCtl.version.intValue) { mutableStateOf(ExtPadsCtl.combine(ctx)) }
        // build 18 (C5, VD-14): what it does now; the rest after " — " (behind "?")
        SettingCard(NavCards.COMBINE,
            if (combine) "On: a left and a right Joy-Con make one controller — as on a Switch, when they're connected together."
            else "Off: each Joy-Con is its own controller — a single one can be held sideways: hold SL + SR for a moment, or on its page.",
            checked = combine, onChecked = { combine = it; ExtPadsCtl.setCombine(ctx, it) })
        // build 18 (C11, T18-10): the Thor's name in games moved to the Thor's page (ControllerPage.kt ThorNameCard)
    }
}

/** Player order, by hand: the Thor and each connected controller, moved up or down (player 1 first). dev build 15 (C5):
 *  a change is made at once only while no game runs (it keeps its controllers): else at the next game start, or now
 *  with "Apply now"; our readers keep running (each pad is made again in place). */
@Composable
private fun PlayerOrderCard() {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val ver = ExtPadsCtl.version.intValue
    // dev build 18f: the one playing as the Thor's by its switch is the Thor's row (its number is the copy's) — left out
    // (review: the same list as the Thor's row and page, ExtPadsCtl.orderListShown)
    val seat = ExtPadsCtl.seatPad()?.takeIf { !ExtPadsCtl.seatDocked }
    val list = remember(ver) { ExtPadsCtl.orderListShown(ctx) }
    val making = ExtPadsCtl.making
    val waits = ExtPadsCtl.orderWaits
    // each row keeps its identity (key) and the focus follows the moved row: its same button, in its new place
    val requesters = remember { mutableMapOf<String, Pair<FocusRequester, FocusRequester>>() }
    var refocus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // dev build 18f2 (device test t1 BUG-2): the Up / Down just pressed (its row's key, Up) keeps the focus — at the top
    // (bottom) too, dimmed: the focus went to the row's other button and one more A moved it back (rewriting the saved
    // order). Let go as soon as another of the card's controls takes the focus ([ControllerRules.orderButtonFocusable])
    var held by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // b18n (N3-ui 3): "plays as it too" only while it is connected (as the Controller list's Thor row)
    fun nameOf(t: String) = if (t == "thor") "The Thor" + (seat?.takeIf { !it.away }?.let { " · ${it.name} plays as it too" } ?: "")
        else ExtPadsCtl.pads.values.firstOrNull { it.key == t }?.name ?: t.substringAfter('-').replace('_', ' ')
    fun move(i: Int, d: Int) {
        if (ExtPadsCtl.making) return                                   // the last change is being made
        val j = i + d; if (j !in list.indices) return
        val x = list[i]
        refocus = x to (d < 0); held = x to (d < 0)
        // dev build 18f (review): the whole order is saved — the hidden seat keeps its place (left out, setOrder would put
        // it back as "not connected": before the Thor, or last)
        ExtPadsCtl.setOrder(ctx, ControllerRules.moveShown(ExtPadsCtl.orderList(ctx), list, i, j))
    }
    LaunchedEffect(list) {
        // build 18 (C5, CTL-12): the moved row's same button, in its new place — dev build 18f2: also at the top (bottom),
        // where it is [held] (it was handed to the other button there)
        refocus?.let { (t, up) -> requesters[t]?.let { (u, d) -> runCatching { (if (up) u else d).requestFocus() } } }
        refocus = null
    }
    // review C-APP-4: "Apply now" goes as soon as it works ("Z - W 0") — with the focus on it, the focus went with it and
    // the next D-pad press started from the top of the screen. dev build 18f2 (device test t1 BUG-2): it was then handed to
    // the last row's Up, and one more A moved a player. Now nothing is handed on: pressed, the row stays where it is while it
    // has the focus — inert, saying what happens — and goes once the focus has moved on ([ControllerRules.applyRow])
    var applyFocused by remember { mutableStateOf(false) }
    var applyPressed by remember { mutableStateOf(false) }
    val applyRow = ControllerRules.applyRow(waits = waits != null, making = making, focused = applyFocused)
    val applyNow by rememberUpdatedState(applyRow == ControllerRules.ApplyRow.APPLY)
    LaunchedEffect(applyRow) { if (applyRow == ControllerRules.ApplyRow.NONE) applyPressed = false }
    val waitsLabel = remember(waits) { ExtPadsCtl.orderWaitsLabel(ctx) }
    SettingCard(NavCards.ORDER,
        "Player 1 first. Emulators remember a controller by its id and place: map it once in the emulator. A change is " +
            "made now if no game is running, else when the next game starts. A controller you connect later goes " +
            "${if (list.indexOf("thor") > 0) "before the Thor" else "last"}; each keeps its place when it comes back. " +
            "An app or game can put the Thor first or last (from its next start). Moving the Thor after a controller " +
            "needs Game controls on." + when (ExtPadsCtl.order) {
                "nolayer" -> " Now: the Thor stays player 1 — Game controls are off for this app."
                else -> ""
            } + if (making) " Making the controllers again…" else "",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (list.size < 2) Text(CtlWords.orderNone(seated = seat != null), color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
            list.forEachIndexed { i, t ->
                key(t) {
                    val (upF, downF) = requesters.getOrPut(t) { FocusRequester() to FocusRequester() }
                    // dev build 18f2 (device test t2 BUG-1): while a new order waits for a game, the row's number now too — the
                    // list and the pages read the same ([ExtPadsCtl.playerNo]); the rows are the order asked
                    val now = if (waits == null || ExtPadsCtl.dockedPad() != null) null else (if (t == "thor") ExtPadsCtl.playerNo(ctx, null)
                        else ExtPadsCtl.pads.values.firstOrNull { it.key == t }?.let { ExtPadsCtl.playerNo(ctx, it) })?.let { CtlWords.nowPlayer(it.now, i + 1) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Player ${i + 1}", color = g.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
                        // build 18 (C5 addendum, VD-21): every name in one weight
                        Text(nameOf(t) + (now?.let { " · $it" } ?: ""), color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (list.size > 1) {
                            // build 18 (C5, CTL-12): the shared buttons; the first's Up and the last's Down take no focus (they did
                            // nothing) — dev build 18f2: unless just pressed ([held]). While the order is being made both are
                            // dimmed and do nothing — still focusable, so the focus isn't lost under the controller's press (C-APP-4)
                            val upHeld = held == (t to true); val downHeld = held == (t to false)
                            GlassButton("Up", { move(i, -1) }, Modifier
                                .focusProperties { canFocus = ControllerRules.orderButtonFocusable(i, list.size, up = true, held = upHeld) }
                                .onFocusChanged { if (it.isFocused && held != null && !upHeld) held = null }, size = ButtonSize.COMPACT,
                                icon = Icons.Rounded.ArrowUpward, focusRequester = upF, enabled = i > 0 && !making)
                            GlassButton("Down", { move(i, 1) }, Modifier
                                .focusProperties { canFocus = ControllerRules.orderButtonFocusable(i, list.size, up = false, held = downHeld) }
                                .onFocusChanged { if (it.isFocused && held != null && !downHeld) held = null }, size = ButtonSize.COMPACT,
                                icon = Icons.Rounded.ArrowDownward, focusRequester = downF, enabled = i < list.lastIndex && !making)
                        }
                    }
                }
            }
            // a controller above the Thor: what emulators walk changes (their places)
            if (list.indexOf("thor") > 0) Text("A controller before the Thor changes how emulators see your controllers: map them again in the emulator.",
                color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
            // the order asked waits for a running game (it keeps its controllers until its next start). dev build 18f2: ONE
            // row, the same node in every state, so the focus stays on it when it stops being "Apply now"
            if (applyRow != ControllerRules.ApplyRow.NONE) {
                if (applyRow == ControllerRules.ApplyRow.APPLY && waitsLabel != null)
                    Text("$waitsLabel is still running: the new order applies when the next game starts.", color = g.textSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                // b18g (WI-6d, g2 f2_5): the check only once it's done ("Applied" / "No longer waiting") — on "Apply now" it read
                // as "already applied"; until then the arrows of "made again"
                GlassActionButton(when (applyRow) {
                        ControllerRules.ApplyRow.APPLY -> "Apply now"
                        ControllerRules.ApplyRow.MAKING -> "Making the controllers again…"
                        else -> if (applyPressed) "Applied" else "No longer waiting"
                    }, if (applyRow == ControllerRules.ApplyRow.APPLIED) Icons.Rounded.Done else Icons.Rounded.Refresh, Modifier.fillMaxWidth()
                    .onFocusChanged { applyFocused = it.isFocused; if (it.isFocused) held = null },
                    subtitle = waitsLabel?.takeIf { applyRow == ControllerRules.ApplyRow.APPLY }?.let { "$it may lose its controllers" }) {
                    if (applyNow) { applyPressed = true; ExtPadsCtl.applyOrderNow() }        // (pressed: it does nothing more)
                }
            }
        }
    }
}

/** One line about a controller: its player, its face buttons, its battery, then what it does in games. Build 18 (C7,
 *  VD-07): the one template every controller row uses ([ControllerRules.ctlSummary]: the Thor's too) — its face always
 *  named (Automatic said nothing). */
internal fun padSummary(ctx: android.content.Context, pad: ExtPadsCtl.Pad): String = ExtPadsCtl.playerNo(ctx, pad).let { no -> ControllerRules.ctlSummary(
    no.now,                                                             // dev build 18f2: the one source (the Thor's row too)
    (FaceRules.style(ExtPadsCtl.faceChoice(ctx, pad.key), pad.kind) ?: ButtonNames.thorStyle).label,
    pad.battery.takeIf { it >= 0 }?.let { listOf("empty", "low", "half", "good", "full")[it.coerceIn(0, 4)] + if (pad.charging) " (charging)" else "" },
    listOfNotNull(
        if (pad.away) "away" else if (pad.ours == null) "starting…" else null,      // dev build 15 (C4): its pad kept
        if (ExtPadsCtl.dockedPad()?.key == pad.key) "docked: plays as the Thor's" else null,     // dev build 17 (WP11)
        // dev build 18f: by its switch
        if (ExtPadsCtl.seatPad()?.key == pad.key && !ExtPadsCtl.seatDocked) "plays as the Thor's controller" else null,
        if (pad.sideways) "held sideways" else null,
        if (ExtPadsCtl.savedMap(ctx, pad.key, pad.sideways) != null) "buttons set up by you" else null,
        when (PadControls.mode(ctx, pad.key)) { PadControls.Mode.OWN -> "its own buttons"; PadControls.Mode.PLAIN -> "plain buttons"; else -> null },
        if (pad.profileRefused) "this game's controls didn't fit it" else null,
    ), after = CtlWords.afterClose(ExtPadsCtl.orderWaitsLabel(ctx), no.after)) }

/** dev build 15 (C6): what games see of a controller — its pad's name and player — and why they keep it (its page's last
 *  section, dev build 17). dev build 18f2: its number from the one source ([ExtPadsCtl.playerNo]) — while a new order waits
 *  for a game, what it becomes then too. */
internal fun identityLine(ctx: android.content.Context, pad: ExtPadsCtl.Pad): String {
    val docked = ExtPadsCtl.order == "dock" && ExtPadsCtl.dockSlot == pad.node
    val no = ExtPadsCtl.playerNo(ctx, pad)
    val player = no.now.takeIf { it > 0 && !docked }?.let { n ->
        ", player $n" + (CtlWords.afterClose(ExtPadsCtl.orderWaitsLabel(ctx), no.after)?.let { " — $it" } ?: "") } ?: ""
    // dev build 18f: by its switch — games see only the Thor's copy (its number is the copy's)
    if (!docked && ExtPadsCtl.seatSlot == pad.node) return "It plays as the Thor's controller: games see only “${ExtPadsCtl.copyName()}”" +
        player + ". With that off, games see it as “${ExtPadsCtl.shownName(pad)}”. " +
        "Emulators remember a controller by its id and place: map it once in the emulator — Wayfinder keeps both, also " +
        "while it's off for a while (30 minutes)."
    return (if (docked) "Docked: it plays as the Thor's controller, player 1. Undocked, games" else "Games") +
        " see it as “${ExtPadsCtl.shownName(pad)}”" + player +
        ". Emulators remember a controller by its id and place: map it once in the emulator — Wayfinder keeps both, also " +
        "while it's off for a while (30 minutes)."
}

/**
 * dev build 16 (theme E): one controller's page — its own Hub route "extpad:<key>": its parent is the Controller page, and
 * Back focuses its row on the page that opened it ([ReturnFocus]). Found by its key, the same page whatever slot it
 * reconnects on (two identical controllers without a serial share a key, and their settings: the lower player's). Its own
 * controls for every game open over it — a layer: B and their Back close them, onto their row. dev build 17 (WP11): the
 * page is the one template ([ControllerDetailPage], the Thor's too).
 */
@Composable
internal fun ExtPadPage(myDisplayId: Int, key: String, onBack: () -> Unit, go: (String) -> Unit) {
    var editor by remember(key) { mutableStateOf(false) }
    if (editor) {
        // the page is composed again as the editor closes: its row is the one it comes back to (SubPage takes it)
        val close: () -> Unit = { app.wayfinder.ui.ReturnFocus.pending = "pad:edit"; editor = false }
        app.wayfinder.ui.BackLayer(onBack = close) {
            // dev build 16 (WP6): the same Game controls page, that controller's tab alone; Back says where it goes
            RemapScreen(myDisplayId, PadProfiles.key(PadProfiles.EVERY, key), onBack = close, backTo = "Back to ${padNameOf(key)}")
        }
        return
    }
    ControllerDetailPage(myDisplayId, CtlRef.Pad(key), onBack, go) { editor = true }
}

/**
 * dev build 17 (theme G, D4): the Thor's Back and AYN button on controller [pad] — which buttons they are (its own, Home +
 * Select / Home + Start standing in, or none), set up with the wizard's two steps ([setUp]: [WizardSteps.SPARE]); after its
 * Home + Back held 5 s, its controls are off until turned back on here. Its page's "Home and combos" section.
 */
@Composable
internal fun PadSparesRows(pad: ExtPadsCtl.Pad, setUp: () -> Unit) {
    val ctx = LocalContext.current
    val look = currentLook()
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val s = ExtPadsCtl.spares(ctx, pad)
    val entry = LocalEntryFocus.current
    // its Home + Back held 5 s (wfhid "M 1"): plain buttons until this row — the focus goes on to the next row, which stays
    if (pad.muted) GlassListRow("Turn its controls back on",
        value = "plain buttons since you held ${Say.homeAndBack(look, (s.back as? Say.Spare.Own)?.name)}",
        icon = Icons.Rounded.Refresh, id = "pad:unmute") { ExtPadsCtl.unmute(pad.key); entry?.request(NavCards.PAD_SPARES.id) }
    // dev build 17 (WP9): a card of the registry (a link or a search can land on it)
    // build 18 (C7, VD-07 / D21): a set-up icon, and a chevron — it opens the wizard's two steps over the page
    GlassListRow(NavCards.PAD_SPARES.title, value = Say.spares(s.back, s.ayn, look) + if (pad.away) " · set up when it's back" else "",
        icon = Icons.Rounded.Build, id = NavCards.PAD_SPARES.id, end = if (pad.away) RowEnd.NONE else RowEnd.CHEVRON) { if (!pad.away) setUp() }
}

/** One step of the wizard: the control, what to ask, and how it's read (0 button · 1 D-pad · 2 stick, + = right / down).
 *  dev build 16 (theme E): [btn] = the position asked, shown in that controller's own words when its kind is known (a
 *  sideways Joy-Con's shoulder step is its SL); [any] = the usual names for a pad whose print isn't known. Build 18 (C6,
 *  VD-11): [ask] in sentence case, the position word marked `*…*` — drawn bold ([headline]), left out of [text]; what its
 *  Back / AYN is now goes in the caption under it (it was "(now: …)" and " — tap: back · hold: move apps" in the question). */
private class Step(val target: String, val ask: String, val kind: Int, val btn: ThorButton? = null, val any: String = "") {
    private fun known(look: PadLook) = look.kind != ControllerKind.GENERIC && look.kind != ControllerKind.THOR
    /** Its position on [look]'s controller, shown with the question — a sideways Joy-Con's face buttons are named by where
     *  they are (the question says it already), a Home called "Home" says nothing more. */
    private fun shown(look: PadLook): ThorButton? =
        btn?.takeIf { known(look) && !(look.sideways && it in FACE) && Vocab.name(it, look) != "Home" }
    /** The question in plain words: "Press the bottom face button (✕)". */
    fun text(look: PadLook): String {
        val q = WizardSteps.plain(ask)
        val name = shown(look)?.let { Vocab.name(it, look) }
        return when { name != null -> "$q ($name)"; !known(look) && any.isNotEmpty() -> "$q ($any)"; else -> q }
    }
    /** The question as drawn: the position word bold, its button as the controller's own badge ([appendButton]: draw it
     *  with [app.wayfinder.ui.rememberButtonInline] in [look]). */
    fun headline(look: PadLook): AnnotatedString = buildAnnotatedString {
        ask.split('*').forEachIndexed { k, part -> if (k % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part) }
        val b = shown(look)
        when {
            b != null -> { append(" ("); appendButton(b, look); append(")") }
            !known(look) && any.isNotEmpty() -> append(" ($any)")
        }
    }
}
private val FACE = setOf(ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y)

private val STEPS = listOf(
    Step("S", "Press the *bottom* face button", 0, ThorButton.B),
    Step("E", "Press the *right* face button", 0, ThorButton.A),
    Step("W", "Press the *left* face button", 0, ThorButton.Y),
    Step("N", "Press the *top* face button", 0, ThorButton.X),
    Step("L1", "Press the *left shoulder* button", 0, ThorButton.L1, "L / LB / L1"),
    Step("R1", "Press the *right shoulder* button", 0, ThorButton.R1, "R / RB / R1"),
    Step("L2", "Press the *left trigger*", 0, ThorButton.L2, "ZL / LT / L2"),
    Step("R2", "Press the *right trigger*", 0, ThorButton.R2, "ZR / RT / R2"),
    Step("ST", "Press *Start*", 0, ThorButton.START, "+ / Menu / Options"),
    Step("SE", "Press *Select*", 0, ThorButton.SELECT, "− / View / Share"),
    Step("HO", "Press *Home*", 0, ThorButton.HOME),
    // dev build 17 (theme G, D4): the Thor's Back and AYN button on this controller — a button of its own, any one ("Not on
    // it": none — then Home + Select / Home + Start stand in, when it has a Home)
    Step("BK", "Press the button for its *Back*", 0),
    Step("AY", "Press the button for its *AYN button*", 0),
    Step("SL", "Press *SL* (a Joy-Con's small rail button) — or “Not on it”", 0),
    Step("SR", "Press *SR* (the other rail button) — or “Not on it”", 0),
    Step("L3", "Click the *left stick*", 0),
    Step("R3", "Click the *right stick*", 0),
    Step("DU", "Press the D-pad *up*", 1),
    Step("DD", "Press the D-pad *down*", 1),
    Step("DL", "Press the D-pad *left*", 1),
    Step("DR", "Press the D-pad *right*", 1),
    Step("LX", "Push the *left* stick to the *right*", 2),
    Step("LY", "Push the *left* stick *down*", 2),
    Step("RX", "Push the *right* stick to the *right*", 2),
    Step("RY", "Push the *right* stick *down*", 2),
)

/**
 * dev build 16 (theme E): what a hold on the controller being set up means — wfhid's "L h <field>" (its Home held 1.5 s)
 * and "L k <field>" (a button heard before in this session held 1 s, [ExtPadsCtl.learnHold]). Pure (WizardHoldsTest).
 */
internal object WizardHolds {
    enum class Act { NONE, CANCEL, NOT_ON_IT }
    /** [learned]: what this run learnt (target → "<field>[i]", "-1" = not on it); [acceptedDown]: presses taken for a step
     *  and still held (field → target); [done]: every step answered. Its Home cancels — unless that press was just taken
     *  as "Press Home"; a button already set skips the step ("Not on it") — unless that press was just taken for a step. */
    fun act(home: Boolean, field: Int, learned: Map<String, String>, acceptedDown: Map<Int, String>, done: Boolean): Act {
        if (done) return Act.NONE
        // dev build 16 review (E-WIZ-HOME-HINT): wfhid knows only its map's Home ("L h"). Once this run learnt Home on a
        // field, THAT field is its Home (its "L k" cancels) and the map's one is a button like any other
        val ho = homeLearned(learned)
        val isHome = if (ho != null) field == ho else home
        return when {
            isHome -> if (acceptedDown[field] == "HO") Act.NONE else Act.CANCEL
            field in acceptedDown -> Act.NONE
            learned.values.any { it != "-1" && it.trimEnd('i') == field.toString() } -> Act.NOT_ON_IT
            else -> Act.NONE
        }
    }
    /** The field this run learnt as Home (null: not yet, or "not on it"). */
    fun homeLearned(learned: Map<String, String>): Int? = learned["HO"]?.takeIf { it != "-1" }?.trimEnd('i')?.toIntOrNull()
    /** dev build 16 review (E-WIZ-HOME-HINT): its Home held cancels only where wfhid says so — a Home in the map in use (its
     *  D line's "HO=", what "L h" is timed on) or one learnt in this run; not a single Joy-Con upright, nor a map without
     *  Home, nor once its Home step was answered "not on it". */
    fun homeCancels(mapping: String, learned: Map<String, String>): Boolean =
        learned["HO"] != "-1" && (homeLearned(learned) != null ||
            mapping.split(' ').any { it.startsWith("HO=") && (it.substringAfter('=').trimEnd('i').toIntOrNull() ?: -1) >= 0 })
    /** build 18 (C1, CTL-03): the field that is this controller's Home — the one learnt in this run, else the mapping's
     *  "HO=" (null: none known, or its Home step answered "not on it"). A press of it never answers another step: holding
     *  PS to cancel first sent a tap, taken as "Press the bottom face button". */
    fun homeField(mapping: String, learned: Map<String, String>): Int? {
        if (learned["HO"] == "-1") return null
        return homeLearned(learned) ?: mapping.split(' ').firstOrNull { it.startsWith("HO=") }
            ?.substringAfter('=')?.trimEnd('i')?.toIntOrNull()?.takeIf { it >= 0 }
    }
    /** build 18 (C1): a press of [field] on step [target] that is its Home, on any step but "Press Home": ignored. */
    fun homeIgnored(mapping: String, learned: Map<String, String>, target: String, field: Int): Boolean =
        target != "HO" && field == homeField(mapping, learned)
    /** dev build 16 review (E-WIZ-DOUBLE-SKIP): ONE skip per hold — two buttons already set held together (Select + Start,
     *  a pad's Home) said "L k" twice and skipped two steps, the second unseen (a working button saved as not there).
     *  [down] = the fields of skip holds still held (their release takes them out). True = this one skips. */
    fun firstSkip(down: MutableSet<Int>, field: Int): Boolean { val first = down.isEmpty(); down += field; return first }
    /** build 18 review (C): whose Back the screens name ([ButtonNames.handLook]: the wizard's Cancel, the header's Back pill,
     *  the legend) — the controller in hand's, unless that is the one being set up ([padKey], [ExtPadsCtl.learningKey]; null:
     *  none): learning, its presses never reach the page (its ○ answered the step shown), so the Thor's, as the hint line
     *  says. [handKey]: the hand's key (null: the Thor, or the controller docked as the Thor's — [dockedKey]). True = the
     *  Thor's. */
    fun backOnThor(handKey: String?, dockedKey: String?, padKey: String?): Boolean =
        padKey != null && (handKey ?: dockedKey) == padKey
}

/** dev build 17 (theme G, D4): which targets the setup wizard asks — those the controller's drawing shows, and its Back and
 *  AYN on every controller (any button can be one: no part of the drawing is theirs); [only] = just those (its page's
 *  "Back and AYN buttons" row). Pure (WizardStepsTest). */
internal object WizardSteps {
    val SPARE = setOf("BK", "AY")
    /** Every step's target, in the wizard's order. */
    val targets: List<String> get() = STEPS.map { it.target }
    /** Build 18 (C6, VD-11): every step's question as written (its position word marked `*…*`). */
    val asks: List<String> get() = STEPS.map { it.ask }
    /** A question without its marks (any plain use: a log, TalkBack). */
    fun plain(ask: String): String = ask.replace("*", "")
    fun asked(all: List<String>, shown: Set<String>, only: Set<String>?): List<String> =
        all.filter { (it in shown || it in SPARE) && (only == null || it in only) }
    /** Two-step mode ([only]): a button the mapping in use ([mapping], wfhid's D line) gives to a target that isn't asked —
     *  taken for its Back, it would leave that target (a face button lost to Back): refused ("Not on it", or the whole
     *  wizard, are the ways). The full wizard never refuses one: it asks every button in turn. */
    fun takenElsewhere(mapping: String, field: Int, asked: Collection<String>, only: Set<String>?): Boolean =
        only != null && mapping.split(' ').any { t ->
            val k = t.substringBefore('=', ""); val v = t.substringAfter('=', "").trimEnd('i')
            k.isNotEmpty() && k !in asked && v != "-1" && v == field.toString()
        }
}

/** A controller's buttons, set up by pressing each one when asked — a layer of its page (dev build 17, WP11: hosted by the
 *  template in ControllerPage.kt). dev build 17 (theme G): its Back and AYN button too; [only] = just those targets
 *  ([WizardSteps.SPARE]: the page's "Back and AYN buttons" row). */
@Composable
internal fun SetupWizard(node: String, done: () -> Unit, only: Set<String>? = null) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val pad = ExtPadsCtl.pads[node]
    // build 18 (C6 addendum, ps-F4): Back is an arrow — a blue × reads as PlayStation's ✕ (its confirm)
    if (pad == null) { Text("That controller is gone.", color = g.textSecondary); GlassActionButton("Back", Icons.AutoMirrored.Rounded.ArrowBack, Modifier.fillMaxWidth()) { done() }; return }
    // dev build 16 (WP5, critique M10): its kind's drawing (the page provides its look: a sideways Joy-Con its own), and
    // ONE rule — the wizard asks for the parts that drawing shows (SL / SR: a Joy-Con held upright; sideways they're L1 / R1)
    val look = currentLook()
    val art = remember(look.kind, look.sideways) { PadArts.full(look) }
    val steps = remember(art, only) { WizardSteps.asked(WizardSteps.targets, art.targets, only).map { t -> STEPS.first { it.target == t } } }
    // dev build 17 (theme G): its Back / AYN now, said with their steps
    @Suppress("UNUSED_VARIABLE") val ver = ExtPadsCtl.version.intValue
    val spares = ExtPadsCtl.spares(ctx, pad)
    val step = remember { mutableIntStateOf(0) }
    val learned = remember { mutableStateMapOf<String, String>() }     // target → "<field>[i]" ("-1" = not on it)
    // dev build 16 (theme E): the presses taken for a step and still held (field → target) — a hold of one is no skip
    val acceptedDown = remember { mutableMapOf<Int, String>() }
    // dev build 16 review (E-WIZ-DOUBLE-SKIP): the skip holds still held — one skip per hold
    val skipDown = remember { mutableSetOf<Int>() }
    var heard by remember { mutableStateOf("") }
    // the controller ended learn mode itself (a minute without a press, dev build 14): it drives games again until Continue
    var paused by remember { mutableStateOf(false) }
    val lastAt = remember { longArrayOf(0L) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // build 18 (C6, CTL-04): the layer opens at the top of its page (the header in view, not where its row was) with Skip
    // focused — it opened with nothing focused, the header scrolled away
    val pageScroll = LocalPageScroll.current
    val skipF = remember { FocusRequester() }
    LaunchedEffect(Unit) { pageScroll?.animateScrollTo(0); skipF.requestAfterLayout() }

    fun finish(save: Boolean) {
        if (save) {
            // start from the mapping in use; what was learned replaces it, a skipped step keeps it
            val m = LinkedHashMap<String, String>()
            pad.mapping.split(' ').filter { '=' in it }.forEach { m[it.substringBefore('=')] = it.substringAfter('=') }
            learned.forEach { (t, f) -> m[t] = f }
            // a BUTTON field learned for one target leaves the targets it had before (kept from the mapping in use) -
            // else one press gave both. A hat is shared by the four directions on purpose.
            val learntButtons = learned.filter { (_, f) -> pad.fields.firstOrNull { it.idx.toString() == f.trimEnd('i') }?.kind == 'b' }
            for ((t, f) in m.entries.toList()) if (t !in learned && learntButtons.values.any { it.trimEnd('i') == f.trimEnd('i') }) m[t] = "-1"
            // dev build 16 (WP5): "Not on it" is kept as "T=-1" (wfhid: the same as not mapped) — the drawings leave those
            // parts out; one said before stays until its step is answered again. Only beside a real mapping (a line of
            // nothing but -1 would be a mapping with no button at all)
            val kept = m.entries.filter { it.value != "-1" }.map { "${it.key}=${it.value}" }
            val notOn = PadArts.notOn(ExtPadsCtl.savedMap(ctx, pad.key, pad.sideways)).filter { it !in learned } +
                learned.filterValues { it == "-1" }.keys
            val marks = if (kept.isEmpty()) emptyList() else notOn.filter { t -> kept.none { it.startsWith("$t=") } }.map { "$it=-1" }
            ExtPadsCtl.saveMap(ctx, pad.key, (kept + marks).joinToString(" "), pad.sideways)
        }
        done()
    }
    /** "Not on it" — its button, or (dev build 16) a button already set held 1 s on the controller. */
    fun notOnIt() {
        if (step.intValue < steps.size) learned[steps[step.intValue].target] = "-1"
        heard = ""; step.intValue = (step.intValue + 1).coerceAtMost(steps.size); if (step.intValue >= steps.size) finish(true)
    }

    fun hear(f: Int, v: Int, rest: Int) {
        val fld = pad.fields.firstOrNull { it.idx == f } ?: return
        if (fld.kind == 'b' && v == 0) { acceptedDown.remove(f); skipDown.remove(f); return }   // let go (dev build 16: before the 400 ms below)
        val i = step.intValue; if (i >= steps.size) return
        val s = steps[i]
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt[0] < 400) return                                // still letting go of the last one
        fun usedElsewhere() = learned.any { (t, x) -> t != s.target && x.trimEnd('i') == f.toString() }
        when (fld.kind) {
            'b' -> {
                // build 18 (C1): its Home answers only "Press Home" (its hold still cancels, through onHold)
                if (WizardHolds.homeIgnored(pad.mapping, learned, s.target, f)) return
                if (s.kind == 2 || usedElsewhere()) return
                // dev build 17 (theme G): the two steps alone never take a button from another one (a face button lost to Back)
                if (WizardSteps.takenElsewhere(pad.mapping, f, steps.map { it.target }, only)) { heard = "That button already does something else — press another, or Not on it."; return }
                learned[s.target] = "$f"; acceptedDown[f] = s.target
            }
            'h' -> { if (s.kind != 1) return; learned[s.target] = "$f" }    // a hat: the direction is decoded by it
            'a' -> {
                val dir = if (v > rest) 1 else -1
                when (s.kind) {
                    2 -> learned[s.target] = if (dir > 0) "$f" else "${f}i"
                    1 -> { val want = if (s.target == "DU" || s.target == "DL") -1 else 1; learned[s.target] = if (dir == want) "$f" else "${f}i" }
                    else -> {
                        if (usedElsewhere()) return
                        if (WizardSteps.takenElsewhere(pad.mapping, f, steps.map { it.target }, only)) { heard = "That button already does something else — press another, or Not on it."; return }
                        learned[s.target] = "$f"
                        if (s.target == "L2") learned["LT"] = "$f"           // an analog trigger: the axis too
                        if (s.target == "R2") learned["RT"] = "$f"
                    }
                }
            }
            else -> return
        }
        lastAt[0] = now
        heard = "Got it."
        step.intValue = i + 1
        if (step.intValue >= steps.size) main.postDelayed({ finish(true) }, 600)
    }
    // dev build 16 (theme E): left or skipped from the controller itself — its Home held = Cancel (nothing saved), a
    // button already set held = "Not on it"
    fun onHold(home: Boolean, f: Int) {
        val target = steps.getOrNull(step.intValue)?.target
        when (WizardHolds.act(home, f, learned, acceptedDown, target == null)) {
            WizardHolds.Act.CANCEL -> { if (BuildConfig.DEBUG) android.util.Log.d("ThorWizard", "cancel"); finish(false) }
            WizardHolds.Act.NOT_ON_IT -> if (WizardHolds.firstSkip(skipDown, f)) {
                if (BuildConfig.DEBUG) android.util.Log.d("ThorWizard", "skip $target"); notOnIt(); heard = "Not on it — next." }
            WizardHolds.Act.NONE -> {}
        }
    }

    DisposableEffect(node) {
        ExtPadsCtl.learnListener = { n, f, v, rest -> if (n == node) main.post { hear(f, v, rest) } }
        ExtPadsCtl.learnEnded = { n -> if (n == node) main.post { paused = true; skipDown.clear() } }
        ExtPadsCtl.learnHold = { n, home, f -> if (n == node) main.post { onHold(home, f) } }
        onDispose { ExtPadsCtl.learnListener = null; ExtPadsCtl.learnEnded = null; ExtPadsCtl.learnHold = null }
    }
    // learn mode only while the page is on screen: left with Home, the Hub isn't closed and the controller stayed
    // silent in games (dev build 14). Back on screen: learning again. Leaving the wizard ends it (the effect goes).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(node, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ExtPadsCtl.learn(pad.key, true)
            try { awaitCancellation() } finally { ExtPadsCtl.learn(pad.key, false) }
        }
    }

    val i = step.intValue.coerceAtMost(steps.size - 1)
    // dev build 16 (theme E): how to leave or skip from that controller, in its names (a pad without Home: the Thor's back)
    // dev build 16 review (E-WIZ-HOME-HINT): "Hold Home" only where holding it cancels (not just "a kind with a Home")
    val holds = if (!WizardHolds.homeCancels(pad.mapping, learned)) "Hold a button you already set to skip a step · cancel below (or the Thor's " +
        "${Say.menu(ThorButton.B, ButtonNames.thorLook)})"
        else "${Say.hold(Vocab.name(ThorButton.HOME, look), cap = true)} to cancel · hold a button you already set to skip a step"
    // build 18 (C6, VD-11): what its Back / AYN is now and does — a caption under the question (from the actions' titles)
    fun nowOf(target: String): String? = when (target) {
        "BK" -> listOfNotNull("Now: ${Say.spare(spares.back, look) ?: "none"}",
            ControlsStore.active().firstOrNull { it.trigger == Trigger(ThorButton.BACK, Press.TAP) }?.action?.title?.let { "tap: $it" },
            ControlsStore.active().firstOrNull { it.trigger == Trigger(ThorButton.BACK, Press.HOLD) }?.action?.title?.let { "hold: $it" })
            .joinToString(" · ")
        "AY" -> listOfNotNull("Now: ${Say.spare(spares.ayn, look) ?: "none"}",
            "tap: " + if (AppSettings.aynButtonOurs) AppSettings.aynTap.title else ThorAction.AYN_DRAWER.title,
            if (AppSettings.aynButtonOurs) "hold: " + (AppSettings.aynHold?.title ?: "same as a tap") else null).joinToString(" · ")
        else -> null
    }
    val askStyle = MaterialTheme.typography.headlineSmall
    val askInline = app.wayfinder.ui.rememberButtonInline(askStyle, look = look)
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pad.name} · ${(step.intValue + 1).coerceAtMost(steps.size)} / ${steps.size}", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                // build 18 (C6, VD-11): sentence case, the position word bold, the button as that controller's badge
                if (step.intValue >= steps.size) Text("Done — saved.", color = g.textPrimary, style = askStyle.copy(fontWeight = FontWeight.SemiBold))
                else Text(steps[i].headline(look), color = g.textPrimary, style = askStyle, inlineContent = askInline)
                if (step.intValue < steps.size) nowOf(steps[i].target)?.let { Caption(it) }
                Text(if (paused && pad.away) "Paused — the controller went away. Continue once it's back."
                    else if (paused) "Paused — no press for a minute, so the controller works in games again. Continue to go on."
                    else heard.ifEmpty { "On that controller. (It drives nothing while you set it up.)" }, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
                if (!paused && step.intValue < steps.size) GlyphText(holds, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            // where that part is on this controller (dev build 16, WP5: its own drawing, the part to press lit). Build 18 (C6,
            // CTL-04): room above it — the L2 / R2 tags were cut at the card's top; a little smaller, so the header, the
            // question and Skip fit the top screen together
            PadArtCanvas(art, Modifier.padding(top = 18.dp).width(300.dp).height(200.dp), highlight = steps[i].target.takeIf { step.intValue < steps.size },
                done = learned.filterValues { it != "-1" }.keys, look = look)
        }
    }
    if (paused) GlassActionButton("Continue", Icons.Rounded.Done, Modifier.fillMaxWidth(), subtitle = "listen to that controller again") {
        paused = false; ExtPadsCtl.learn(pad.key, true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassActionButton("Skip", Icons.Rounded.SkipNext, Modifier.weight(1f), subtitle = "keep it as it is", focusRequester = skipF) {
            heard = ""; step.intValue = (step.intValue + 1).coerceAtMost(steps.size); if (step.intValue >= steps.size) finish(true)
        }
        // build 18 (C6, VD-11 / ps-F4): a "none" sign — a blue × reads as PlayStation's ✕
        GlassActionButton("Not on it", Icons.Rounded.Block, Modifier.weight(1f), subtitle = "this controller has none") { notOnIt() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassActionButton("Save now", Icons.Rounded.Done, Modifier.weight(1f), subtitle = "the rest stays as it is") { finish(true) }
        // build 18 (C6, VD-11): Cancel is a way back — an arrow, and the Back button that does it: the controller in hand's,
        // but never the one being set up (review C: it drives nothing — usually it IS the hand, having opened this page).
        // The hand's look says so itself while it learns (ExtPadsCtl.learn → WizardHolds.backOnThor): the header's Back pill
        // and the legend agree; paused ("L 0"), its own B goes back again
        val cancelLook = ButtonNames.handLook
        CompositionLocalProvider(LocalPadLook provides cancelLook) {
            GlassActionButton("Cancel", Icons.AutoMirrored.Rounded.ArrowBack, Modifier.weight(1f),
                subtitle = "nothing changes · ${Say.keyMenu(ThorButton.B, cancelLook)}") { finish(false) }
        }
    }
}
