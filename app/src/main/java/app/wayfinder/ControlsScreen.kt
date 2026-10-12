package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.VSpace
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.Caption
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.EmptyState
import app.wayfinder.ui.FieldLabel
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassNotice
import app.wayfinder.ui.ModalHeader
import app.wayfinder.ui.NoticeKind
import app.wayfinder.ui.PageHeader
import app.wayfinder.ui.Radii
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.modalDim
import app.wayfinder.ui.modalSurface
import app.wayfinder.ui.scrollFade
import app.wayfinder.ui.selectedFill
import androidx.compose.material.icons.rounded.Check
import kotlinx.coroutines.delay

/**
 * Controls: every action and the button trigger bound to it. Pick an action,
 * press the combination, save. Controller-navigable; while capturing, every press
 * goes to the capture (so the combination can include B, D-pad…), and capture
 * stops as soon as one combination is complete so the controller works again.
 */
@Composable
fun ControlsScreen(myDisplayId: Int, pkg: String? = null, onBack: () -> Unit, go: ((String) -> Unit)? = null) {
    val g = LocalGlass.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + AppConfigStore.version.intValue   // recompose on change
    var editing by remember { mutableStateOf<ThorAction?>(null) }
    // round 8 — an "Open…" combo being set: its target and the trigger it had (null = new);
    // [picking] = the list to pick a target from ("app" / "pair" / "page")
    var openEdit by remember { mutableStateOf<Pair<String, Trigger?>?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val actions = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.OPEN }
    // 1.4 (GitHub #51): all apps = only the combos you have, by their first button; one app = the full list
    val shownActions = if (pkg != null) actions else actions.filter { a -> ControlsStore.triggerFor(a) != null && Features.allows(a) }
        .sortedWith(compareBy({ ControlsStore.triggerFor(it)!!.let { t -> (t.modifier ?: t.button).ordinal } },
            { ControlsStore.triggerFor(it)!!.let { t -> if (t.modifier == null) -1 else t.button.ordinal } }))
    // dev build 16 (WP7): the page opens on its list's first control (never Back), after the first layout. Build 18 (C3, B3):
    // or on the row it is sent to — a Search result for an action ("combo:<ACTION>"), a link's way back. Round 2 (B3, by
    // the Search owner): an action with no row lands on the AYN row that does it, else Add a combo opens on it ([comboLanding])
    val landing = remember {
        comboLanding(app.wayfinder.ui.ReturnFocus.take(), drawn = { it in shownActions }, offered = { it in actions && Features.allows(it) },
            aynTap = AppSettings.aynTap.takeIf { pkg == null && AppSettings.aynButtonOurs },
            aynHold = AppSettings.aynHold?.takeIf { pkg == null && AppSettings.aynButtonOurs })
    }
    // 1.4 (GitHub #51): the "Add a combo" list of actions — round 2: open from the start on [landing]'s action ([addOn])
    var addOn by remember { mutableStateOf(landing.addOn) }
    var adding by remember { mutableStateOf(addOn != null) }
    // build 18 (C3, CTL-01 = b18r0 BUG-1): the list closed without a pick (B, Cancel) → back on "+ Add a combo" (it landed
    // nowhere); a pick opens the editor, which takes the focus as before
    val addFocus = remember { FocusRequester() }
    var addWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(adding) {
        if (adding) addWasUp = true
        else if (addWasUp) { addWasUp = false; addOn = null; if (editing == null) addFocus.requestAfterLayout() }
    }
    // when the "Open…" card or picker closes, the controller lands on what opened it (it landed nowhere). Build 18 (C3,
    // CTL-05): the "+ …" button of the picker it came from, or the row it was ([openFrom]: "app" / "pair" / "page" or
    // "open:<trigger>"; a row gone or changed → "+ An app")
    val openFocus = remember { mapOf("app" to FocusRequester(), "pair" to FocusRequester(), "page" to FocusRequester()) }
    val openRowFocus = remember { HashMap<String, FocusRequester>() }
    var openFrom by remember { mutableStateOf("app") }
    var openWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(openEdit, picking) {
        if (openEdit != null || picking != null) openWasUp = true
        else if (openWasUp) {
            openWasUp = false
            val row = openRowFocus[openFrom]?.takeIf { ControlsStore.opens().any { "open:" + it.trigger == openFrom } }
            (row ?: openFocus[openFrom] ?: openFocus.getValue("app")).requestAfterLayout()
        }
    }
    // Scoped to one app: shows that app's effective combos; saves only to it.
    val appLabel = remember(pkg) {
        pkg?.let { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
    }
    // dev build 16 (WP7): the pill is B's path (an open card first)
    val back = app.wayfinder.ui.rememberBackPress(onBack)
    // dev build 16 (theme F): combos in the names of the controller in hand (WindowRoot's look)
    val look = app.wayfinder.ui.currentLook()
    var resetNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resetNote) { if (resetNote != null) { delay(2500); resetNote = null } }
    val view = androidx.compose.ui.platform.LocalView.current
    val listFocusable = editing == null && openEdit == null && picking == null && !adding   // the card / picker is modal
    // the row it is sent to ([landing]); Add a combo open from the start has the focus — the page leaves it there
    val returnKey = landing.focus
    val entry = app.wayfinder.ui.rememberEntryFocus(returnKey = returnKey, skip = { adding })

    // the list's items before the combo rows: "ayn-h", "ayn", "combos-h" (its last holds "+ Add a combo")
    val head = if (pkg == null) 2 + (if (AppSettings.aynButtonOurs) 1 else 0) else 0
    // build 18 (C3, B3): a return row far down the list is composed from the start (the entry looks for it 3 frames)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = remember {
        returnKey?.removePrefix("combo:")?.takeIf { returnKey.startsWith("combo:") }
            ?.let { n -> shownActions.indexOfFirst { it.name == n } }?.takeIf { it >= 0 }?.let { it + head } ?: 0
    })
    // When the card closes, the controller lands back on the row it came from (it used
    // to land nowhere: A did nothing and the D-pad restarted at the top). Build 18 (C3): a new combo cancelled has no row —
    // "+ Add a combo" then. b18l (b18w P3): one removed lands on the row after it (else the one before it, else "+ Add a
    // combo") — [comboAfterEdit]; it went to "+ Add a combo", far up and not composed: no ring, the D-pad's first press on
    // Back. The target is scrolled into the composed rows first, then focused by its id once laid out ([EntryFocus.request])
    val rowFocus = remember { HashMap<ThorAction, FocusRequester>() }
    var lastEdited by remember { mutableStateOf<ThorAction?>(null) }
    val editedFrom = remember { ArrayList<ThorAction>() }                 // the rows when its editor opened
    LaunchedEffect(editing) {
        val open = editing
        if (open != null) { lastEdited = open; editedFrom.clear(); editedFrom += shownActions; return@LaunchedEffect }
        val a = lastEdited ?: return@LaunchedEffect
        val id = comboAfterEdit(a, editedFrom, shownActions)
        val at = if (id == COMBO_ADD) head - 1 else head + shownActions.indexOfFirst { "combo:${it.name}" == id }
        if (at >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == at }) listState.scrollToItem(at)
        entry.request(id)
    }

    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
      Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // dev build 17 (WP8): the shared page header. Each focusable carries the modal rule itself: on the page's Column it hid
            // every row from the D-pad (down from the back button went nowhere — 2026-09-24).
            // build 18 (C3, CTL-13 / VD-10): ≤ 20 words, Home and Back in the hand's names (a game's own buttons: the caption
            // under "Your combos")
            PageHeader(if (pkg == null) NavPages.title(HubPage.CONTROLS) else "${NavPages.title(HubPage.CONTROLS)} · $appLabel", back,
                subtitle = if (pkg == null) "Your combos for Wayfinder's actions — combos on ${Say.btn(ThorButton.HOME, look)} or " +
                    "${Say.btn(ThorButton.BACK, look)} never reach the game."
                    else "Changes here only apply while $appLabel has the controller. Highlighted = specific to this app.",
                backModifier = Modifier.focusProperties { canFocus = listFocusable }) {
                resetNote?.let { Text(it, color = g.positive, style = MaterialTheme.typography.labelLarge) }      // build 18 (A23): the token
                // "Reset" wipes every custom combo: two presses (ConfirmButton), red from the first
                ConfirmButton(if (pkg == null) "Reset" else "Use my usual combos", "Press again to reset", {
                    if (pkg == null) ControlsStore.resetDefaults()
                    else AppConfigStore.update(pkg) { it.copy(buttons = emptyList(), freed = emptyList()) }
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    resetNote = if (pkg == null) "✓ Back to the default combos" else "✓ Using your usual combos"
                }, Modifier.focusProperties { canFocus = listFocusable })
            }
            androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides entry) {
            // build 18 (C3, VD-03): the rows fade under the header and at the bottom while more is beyond (they were cut by a
            // hard edge), and the last one stops clear of the screen's edge
            LazyColumn(
                Modifier.scrollFade(top = listState.canScrollBackward, bottom = listState.canScrollForward),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 28.dp),
            ) {
                // 1.3.1 — the AYN button first: its tap and hold (they were only under Quick panel), then the combos
                if (pkg == null) {
                    item(key = "ayn-h") {
                        Column(Modifier.padding(start = 4.dp)) {
                            // build 18 (C10, ps-F7): named on the controller in hand (Mute on a DualSense)
                            SectionHeader("${Say.btn(ThorButton.AYN)} button")
                            Caption(if (AppSettings.aynButtonOurs) "A tap and a hold can each do an action — e.g. hold = bottom screen off. It never reaches the game."
                                else "The AYN button opens AYN's own drawer. Turn on ${Say.path(NavCards.PANEL_AYN)} (or ${Nav.place(HubPage.FEATURES)}) to give it a tap and a hold.")
                            if (!AppSettings.aynButtonOurs && go != null)
                                NavLink(NavCards.PANEL_AYN, go, modifier = Modifier.focusProperties { canFocus = listFocusable })
                        }
                    }
                    if (AppSettings.aynButtonOurs) item(key = "ayn") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AynButtonChoices(canFocus = listFocusable) }
                    }
                    item(key = "combos-h") {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.padding(start = 4.dp)) {
                                SectionHeader("Your combos")
                                // build 18 (C3, CTL-13): a game's own buttons — out of the page's subtitle
                                val gc = Say.combo(ThorAction.GAME_CONTROLS, look)
                                Caption("Press one to change or remove it. " + (gc?.let { "A game's own buttons: $it in that game, or ${Nav.place(HubPage.APPS)}." }
                                    ?: "A game's own buttons: ${Nav.place(HubPage.APPS)}, then the game."))
                            }
                            // full width: a small button at the end of the header was skipped by the D-pad (QA2)
                            FocusableGlass(onClick = { adding = true }, radius = Radii.Card, focusRequester = addFocus,
                                focusId = COMBO_ADD,                    // b18l: a removed combo's last landing ([comboAfterEdit])
                                modifier = Modifier.fillMaxWidth().focusProperties { canFocus = listFocusable }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("+ Add a combo", color = g.accent, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    // build 18 (C3, VD-10): in the hand's names
                                    app.wayfinder.ui.GlyphText("${Say.btn(ThorButton.HOME, look)}, ${Say.btn(ThorButton.BACK, look)} or two buttons — tapped, twice, held…",
                                        color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                items(shownActions, key = { it.name }) { action ->
                    val trigger = if (pkg == null) ControlsStore.triggerFor(action) else ControlsStore.effectiveTriggerFor(pkg, action)
                    val appSpecific = pkg != null && AppConfigStore.get(pkg).let { c ->
                        c.buttons.any { it.action == action } ||
                            (ControlsStore.triggerFor(action)?.let { it in c.freed } == true)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableGlass(
                            onClick = { editing = if (editing == action) null else action },
                            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = listFocusable }, radius = Radii.Card,
                            focusRequester = rowFocus.getOrPut(action) { FocusRequester() },
                            focusId = "combo:${action.name}",                   // build 18 (C3, B3): Search's action results
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(action.title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                                    Text(action.description, color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                                }
                                TriggerChip(trigger, highlight = appSpecific || pkg == null)
                            }
                        }
                    }
                }
                // round 8 — combos that open an app, an app pair or a Wayfinder page (all apps only)
                if (pkg == null) {
                    item(key = "open-h") {
                        Column(Modifier.padding(top = 10.dp, start = 4.dp)) {
                            SectionHeader("Open with a combo")
                            Caption("An app opens on the screen you choose; a pair opens both apps, one on each screen.")
                        }
                    }
                    items(ControlsStore.opens(), key = { "open:" + it.trigger }) { b ->     // dev build 16: not its names
                        val rowKey = "open:" + b.trigger
                        FocusableGlass(
                            onClick = { openFrom = rowKey; openEdit = b.arg!! to b.trigger },
                            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = listFocusable }, radius = Radii.Card,
                            focusRequester = openRowFocus.getOrPut(rowKey) { FocusRequester() },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(OpenTargets.label(ctx, b.arg), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f))
                                TriggerChip(b.trigger)
                            }
                        }
                    }
                    item(key = "open-add") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((kind, text) in listOf("app" to "+ An app", "pair" to "+ An app pair", "page" to "+ A Wayfinder page")) {
                                FocusableGlass(onClick = { openFrom = kind; picking = kind }, radius = Radii.Row,
                                    focusRequester = openFocus.getValue(kind),
                                    modifier = Modifier.weight(1f).focusProperties { canFocus = listFocusable }) {
                                    Text(text, color = g.accent, style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        // The combo editor: a centred card over a dimmed page — always in view, and the
        // list behind can't take focus (so scrolling can't happen, or be captured).
        editing?.let { action ->
            // B / Back closes the editor, not the whole page (it left the page and dropped the
            // edit — review 2026-09-25). dev build 16 (WP7): each card a layer (what opens in it closes first)
            app.wayfinder.ui.BackLayer(onBack = { editing = null }) {
            Box(Modifier.fillMaxSize().background(g.modalDim), contentAlignment = Alignment.Center) {
                // build 18 (C3, VD-02): a popup's width (it spread over the whole top screen)
                Box(Modifier.widthIn(max = 560.dp).padding(24.dp)
                    .modalSurface(g, RoundedCornerShape(Radii.Modal))) {
                    CaptureCard(action, pkg, onDone = { editing = null })
                }
            }
            }
        }
        openEdit?.let { (arg, old) ->
            app.wayfinder.ui.BackLayer(onBack = { openEdit = null }) {
            Box(Modifier.fillMaxSize().background(g.modalDim), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 560.dp).padding(24.dp)
                    .modalSurface(g, RoundedCornerShape(Radii.Modal))) {
                    CaptureCard(ThorAction.OPEN, null, onDone = { openEdit = null }, openArg = arg, openOld = old)
                }
            }
            }
        }
        if (adding) {
            app.wayfinder.ui.BackLayer(onBack = { adding = false }) {
            Box(Modifier.fillMaxSize().background(g.modalDim), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 1100.dp).fillMaxHeight(0.9f).padding(24.dp)
                    .modalSurface(g, RoundedCornerShape(Radii.Modal))) {
                    // build 18 (C4): the one action picker (C10, VD-17: its title and line)
                    ActionPicker("Pick its action", actions, current = addOn, onCancel = { adding = false },
                        subtitle = "Then press its buttons") { a -> if (a != null) { adding = false; editing = a } }
                }
            }
            }
        }
        picking?.let { kind ->
            app.wayfinder.ui.BackLayer(onBack = { picking = null }) {
            Box(Modifier.fillMaxSize().background(g.modalDim), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = if (kind == "app") 1400.dp else 760.dp).fillMaxHeight(0.86f).padding(24.dp)
                    .modalSurface(g, RoundedCornerShape(Radii.Modal))) {
                    OpenPicker(kind, onCancel = { picking = null }) { arg -> picking = null; openEdit = arg to null }
                }
            }
            }
        }
      }
    }
}

/** b18l (b18w P3): the "+ Add a combo" button's id on the Combos page ([comboAfterEdit]). */
internal const val COMBO_ADD = "combos:add"

/** b18l (b18w P3): where the controller lands when [edited]'s combo editor closes — its row (its id, "combo:<ACTION>") while
 *  the page still draws it ([now]); removed, the row after it in the rows it was among ([before]), else the one before it,
 *  else "+ Add a combo" ([COMBO_ADD]) — also for a new combo cancelled (it never had a row). Pure (NewActionsTest). */
internal fun comboAfterEdit(edited: ThorAction, before: List<ThorAction>, now: List<ThorAction>): String {
    if (edited in now) return "combo:${edited.name}"
    val i = before.indexOf(edited)
    val near = if (i < 0) null else before.drop(i + 1).firstOrNull { it in now } ?: before.take(i).lastOrNull { it in now }
    return near?.let { "combo:${it.name}" } ?: COMBO_ADD
}

/** 1.4 (GitHub #51): the actions in groups — the ones already on a combo say which. */
private val COMBO_GROUPS: List<Pair<String, List<ThorAction>>> = listOf(
    "Screens & apps" to listOf(ThorAction.SWAP_OR_SEND, ThorAction.RECENTS, ThorAction.CLEAR_BACKGROUND, ThorAction.CLOSE_APP,
        ThorAction.CLOSE_OTHER, ThorAction.HOME_HERE, ThorAction.HOME_TOP, ThorAction.HOME_BOTTOM, ThorAction.HOME_BOTH,
        ThorAction.TOGGLE_SECOND_SCREEN, ThorAction.TOGGLE_TOP_SCREEN, ThorAction.BACK, ThorAction.NOTHING),
    "Controller" to listOf(ThorAction.FOCUS_SWITCH_UP, ThorAction.FOCUS_SWITCH_DOWN, ThorAction.FOCUS_LOCK_TOGGLE,
        ThorAction.GAME_CONTROLS, ThorAction.GYRO_TOGGLE, ThorAction.KEYBOARD, ThorAction.AYN_MOUSE),
    "Brightness & volume" to listOf(ThorAction.BRIGHTER, ThorAction.DIMMER, ThorAction.TOP_BRIGHTER, ThorAction.TOP_DIMMER,
        ThorAction.BOTTOM_BRIGHTER, ThorAction.BOTTOM_DIMMER, ThorAction.LOUDER, ThorAction.QUIETER, ThorAction.TOP_LOUDER,
        ThorAction.TOP_QUIETER, ThorAction.BOTTOM_LOUDER, ThorAction.BOTTOM_QUIETER),
    "Wayfinder" to listOf(ThorAction.QUICK_MENU, ThorAction.GUIDE, ThorAction.SCREENSHOT, ThorAction.RECORD_SCREEN, ThorAction.FPS_COUNTER,
        ThorAction.WAYFINDER_OFF),
    "Android & the Thor" to listOf(ThorAction.SLEEP, ThorAction.TOGGLE_KEEP_AWAKE, ThorAction.AYN_DRAWER),
    "Touch" to listOf(ThorAction.SWIPE_UP, ThorAction.SWIPE_DOWN, ThorAction.SWIPE_LEFT, ThorAction.SWIPE_RIGHT),
)

/**
 * Build 18 (C4, CTL-06 / VD-08): the one action picker — Add a combo's, and the AYN button's tap and hold (in a sheet).
 * Its groups ([COMBO_GROUPS], then "Other"); the focus lands on [current] (filled, with its check), else the first.
 * [extraFirst] = (title, line) of a first option that is no action — the hold's "Same as a tap": picked as null, and the
 * current one when [current] is null. [leading]: drawn before the title (the AYN chip). [focus]: the requester of that
 * first focus when the host focuses it (a GlassSheet's `first`); null = the picker focuses it after its first layout.
 */
@Composable
internal fun ActionPicker(
    title: String, actions: List<ThorAction>, current: ThorAction?, onCancel: () -> Unit,
    subtitle: String? = null, extraFirst: Pair<String, String>? = null, leading: (@Composable () -> Unit)? = null,
    focus: FocusRequester? = null, onPick: (ThorAction?) -> Unit,
) {
    val own = remember { FocusRequester() }
    val first = focus ?: own
    if (focus == null) LaunchedEffect(Unit) { first.requestAfterLayout() }
    val listed = COMBO_GROUPS.flatMap { it.second }.toSet()
    // a part turned off (Features) hides its actions — never the current one
    fun shown(a: ThorAction) = a in actions && (Features.allows(a) || a == current)
    val groups = (COMBO_GROUPS.map { (t, l) -> t to l.filter { shown(it) } } +
        listOf("Other" to actions.filter { it !in listed && shown(it) })).filter { it.second.isNotEmpty() }
    // the focused row: the current action; else the extra option; else the first action
    val target = current?.takeIf { c -> groups.any { c in it.second } }
        ?: if (extraFirst != null) null else groups.firstOrNull()?.second?.firstOrNull()
    // the list starts on it (a row not laid out can't take the focus), its group's header just above
    val start = remember {
        var i = if (extraFirst != null) 1 else 0
        var at = 0
        if (target != null) for ((_, l) in groups) { val k = l.indexOf(target); if (k >= 0) { at = i + k; break }; i += 1 + l.size }
        (at - 1).coerceAtLeast(0)
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = start)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // dev build 17 (WP8): Cancel on the left with the hand's Back button
        ModalHeader(title, onCancel, closeLabel = "Cancel", subtitle = subtitle, leading = leading)
        LazyColumn(Modifier.scrollFade(top = state.canScrollBackward, bottom = state.canScrollForward), state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (extraFirst != null) item(key = "extra") {
                PickRow(extraFirst.first, extraFirst.second, null, selected = current == null, req = if (target == null) first else null) { onPick(null) }
            }
            for ((group, list) in groups) {
                item(key = "g:$group") { SectionHeader(group) }
                items(list, key = { it.name }) { a ->
                    PickRow(a.title, a.description, ControlsStore.triggerFor(a), selected = a == current,
                        req = if (a == target) first else null) { onPick(a) }
                }
            }
        }
    }
}

/** One choice of [ActionPicker]: its title and line, the combo it has now (C10, VD-17: "now" and its chips, dimmed), the
 *  current one filled with its check (A10's selected look). */
@Composable
private fun PickRow(title: String, line: String, now: Trigger?, selected: Boolean, req: FocusRequester?, onClick: () -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Radii.Row)
    FocusableGlass(onClick = onClick, modifier = Modifier.fillMaxWidth(), radius = Radii.Row, focusRequester = req,
        role = app.wayfinder.ui.FocusRole.PICK) {
        Row(Modifier.fillMaxWidth().then(if (selected) Modifier.selectedFill(g, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (selected) g.onAccent else g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(line, color = if (selected) g.onAccent.copy(alpha = 0.85f) else g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (now != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("now", color = if (selected) g.onAccent else g.textTertiary, style = MaterialTheme.typography.labelMedium)
                app.wayfinder.ui.TriggerGlyphs(now, 22.dp, dim = true)
            }
            if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = g.onAccent,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TriggerChip(trigger: Trigger?, highlight: Boolean = true) {
    val g = LocalGlass.current
    GlassPanel(radius = Radii.Control, strong = trigger != null && highlight) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (trigger == null) Text("Not set", color = g.textTertiary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp))
            else app.wayfinder.ui.TriggerGlyphs(trigger, 26.dp, dim = !highlight)
        }
    }
}

/** Save [t] for [action] into [pkg]'s overrides (or globally when pkg is null). */
private fun saveBinding(pkg: String?, action: ThorAction, t: Trigger) {
    if (pkg == null) { ControlsStore.bind(action, t); return }
    AppConfigStore.update(pkg) { c ->
        c.copy(
            buttonsMode = ButtonsMode.CUSTOM,
            buttons = c.buttons.filter { it.action != action && it.trigger != t } + Binding(t, action),
            freed = c.freed - t,
        )
    }
}

/** No trigger for [action] (globally, or in [pkg] only). */
private fun removeBinding(pkg: String?, action: ThorAction) {
    if (pkg == null) { ControlsStore.unbind(action); return }
    val global = ControlsStore.triggerFor(action)
    AppConfigStore.update(pkg) { c ->
        c.copy(
            buttonsMode = ButtonsMode.CUSTOM,
            buttons = c.buttons.filter { it.action != action },
            freed = if (global != null && global !in c.freed) c.freed + global else c.freed,
        )
    }
}

/** Drop [pkg]'s override for [action]: back to the global combo. */
private fun useGlobal(pkg: String, action: ThorAction) {
    val global = ControlsStore.triggerFor(action)
    AppConfigStore.update(pkg) { c ->
        c.copy(buttons = c.buttons.filter { it.action != action }, freed = c.freed.filter { it != global })
    }
}

/** Inline "press the combination" editor for one action. */
@Composable
private fun CaptureCard(action: ThorAction, pkg: String?, onDone: () -> Unit, openArg: String? = null, openOld: Trigger? = null) {
    val g = LocalGlass.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 1.3.2 (GitHub #42): an app opens on the controller's screen, the top or the bottom
    var openTarget by remember(openArg) { mutableStateOf(openArg) }
    val title = if (openTarget != null) OpenTargets.label(ctx, openTarget) else action.title
    // 1.4: an existing combo opens on itself (Change / Remove); a new one starts recording at once
    val current = remember(action, openArg) {
        if (openArg != null) openOld else if (pkg == null) ControlsStore.triggerFor(action) else ControlsStore.effectiveTriggerFor(pkg, action) }
    var capturing by remember { mutableStateOf(current == null) }
    val order = remember { mutableStateListOf<ThorButton>() }    // buttons in press order this attempt
    val down = remember { mutableStateListOf<ThorButton>() }
    var result by remember { mutableStateOf<Trigger?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var press by remember { mutableStateOf(Press.TAP) }
    val saveFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    val look = app.wayfinder.ui.currentLook()      // dev build 16 (theme F): the buttons as the controller in hand calls them

    // Hook the engine's capture while this card is capturing. Presses are swallowed at
    // once (nothing leaks to the page), but only count once ARMED: ~0.4 s after the card
    // appears, and only buttons pressed after that — the A that opened the card, or a
    // D-pad scroll, can never become the combo.
    LaunchedEffect(capturing) {
        armed = false
        // build 18 (C3, CTL-20): nothing pressed for 10 s — an existing combo goes back to its editor, a new one closes
        if (capturing) { delay(400); armed = true; delay(10_000); if (capturing && order.isEmpty()) { if (current != null) capturing = false else onDone() } }
    }
    DisposableEffect(capturing) {
        if (capturing) {
            order.clear(); down.clear(); result = null; error = null
            ForegroundAppService.startCapture { b, isDown ->
                if (!armed) return@startCapture
                if (isDown) { if (b !in down) down.add(b); if (b !in order) order.add(b) }
                else if (b in down) {
                    down.remove(b)
                    if (down.isEmpty() && order.isNotEmpty()) {
                        // Combination complete → decide, stop capturing so the controller navigates again.
                        when {
                            // B alone = cancel. dev build 16 (theme F): the back button of the controller that pressed (its
                            // menu B: ○ on a DualSense — the Thor's B position there is ✕, its confirm)
                            // build 18 (C3, CTL-20): changing an existing combo, back to its editor (it closed the whole editor)
                            order.size == 1 && order[0] == ButtonNames.menuPos(ThorButton.B, ButtonNames.handLook) -> {
                                if (current != null) capturing = false else onDone(); return@startCapture }
                            // 1.3.1 (GitHub #44): the AYN button starts a combo, never ends one; alone = its tap / hold
                            order.size >= 2 && order[1] == ThorButton.AYN -> error = "The AYN button can only start a combo: hold it first, then press another button."
                            order.size == 1 && order[0] == ThorButton.AYN -> error = "The AYN button on its own: set its tap and hold at the top of the Combos page. For a combo, hold it and press another button."
                            order.size >= 2 -> result = Trigger(order[1], modifier = order[0])
                            order[0].isSystem -> result = Trigger(order[0], press)
                            else -> error = "${order[0].spoken(ButtonNames.handLook)} alone would break games — use " +
                                "${Say.btn(ThorButton.HOME, ButtonNames.handLook)} or ${Say.btn(ThorButton.BACK, ButtonNames.handLook)}, " +
                                "or hold a button and press another."
                        }
                        capturing = false
                    }
                }
            }
        }
        onDispose { ForegroundAppService.stopCapture() }
    }
    LaunchedEffect(result) { if (result != null) saveFocus.requestAfterLayout() }

    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Tile, strong = true) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // build 18 (C3, VD-02): a popup's header like every other — Cancel on the left with the hand's Back button, what
            // the action does under the title (the quiet Cancel buttons are gone)
            ModalHeader(title, onDone, closeLabel = "Cancel", subtitle = if (openTarget != null) null else action.description)
            if (capturing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningDot(armed)
                    Text(if (armed) "Listening — press its buttons" else "Get ready…",
                        color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                }
                // build 18 (C3, VD-10 + ps-F7): Home, Back and AYN in the hand's names
                app.wayfinder.ui.GlyphText(
                    if (order.isEmpty()) "${Say.btn(ThorButton.HOME, look)} or ${Say.btn(ThorButton.BACK, look)} on their own, or hold " +
                        "one button (${Say.btn(ThorButton.AYN, look)} too) and press a second. ${Say.menu(ThorButton.B, look)} on its own " +
                        (if (current != null) "goes back." else "cancels.")
                    else order.joinToString(" + ") { it.spoken(look) },
                    color = if (order.isEmpty()) g.textTertiary else g.accent,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                val r = result
                if (r != null) {
                    val t = r.copy(press = press)   // 1.4 (GitHub #59): combos too
                    app.wayfinder.ui.TriggerGlyphs(t, 34.dp)
                    // 1.4 (GitHub #59): a combo's second button can be tapped once, twice, three times or held too
                    if (r.isChord) app.wayfinder.ui.GlyphText("Keep ${r.modifier!!.spoken(look)} held, and ${r.button.spoken(look)}:", color = g.textSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                    // a stick flick is a press and a release in one: it can't be held
                    val presses = Press.values().filter { it != Press.HOLD || !r.button.isFlick }
                    if (press !in presses) press = Press.TAP
                    GlassSegmentedControl(
                        options = presses.map { it.label.replaceFirstChar { c -> c.uppercase() } },
                        selectedIndex = presses.indexOf(press), modifier = Modifier.fillMaxWidth(), collapse = false,
                    ) { press = presses[it] }
                    val note = when {
                        r.modifier == ThorButton.AYN -> if (r.button.isDpad) "The D-pad press still reaches the game." else null
                        r.isChord && r.modifier?.isSystem == false && r.button.isDpad ->
                            "Both ${r.modifier.spoken(look)} and the D-pad still reach the game."
                        r.isChord && r.modifier?.isSystem == false -> "${r.modifier.spoken(look)} still reaches the game; ${r.button.spoken(look)} is hidden from it."
                        r.isChord && r.button.isDpad -> "The D-pad press still reaches the game."
                        else -> null
                    }
                    note?.let { app.wayfinder.ui.GlyphText(it, color = g.textTertiary, style = MaterialTheme.typography.bodySmall) }
                    openTarget?.takeIf { OpenTargets.isApp(it) }?.let { tgt ->
                        val wheres = listOf(null, "top", "bottom")
                        FieldLabel("Opens on")
                        GlassSegmentedControl(
                            options = listOf("The controller's screen", "Top screen", "Bottom screen"),
                            selectedIndex = wheres.indexOf(OpenTargets.appWhere(tgt)).coerceAtLeast(0), modifier = Modifier.fillMaxWidth(),
                        ) { i -> openTarget = OpenTargets.app(OpenTargets.appPkg(tgt), wheres[i]) }
                        // b18p (GitHub #107): "The controller's screen" gives way to the app's own "Opens on" (OpenTargets.opensOnTop)
                        AppConfigStore.get(OpenTargets.appPkg(tgt)).route.takeIf { OpenTargets.appWhere(tgt) == null && it != Route.ANY }?.let { r ->
                            app.wayfinder.ui.GlyphText("This app is set to open on the ${r.label.lowercase()} screen (its “Opens on”): it opens there.",
                                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ControlsStore.effective(pkg).firstOrNull { it.trigger == t }
                        ?.takeIf { if (openArg != null) it.trigger != openOld else it.action != action }?.let {
                            GlassNotice("Currently used by “${OpenTargets.title(ctx, it)}” — saving moves it here.", NoticeKind.WARN, Modifier.fillMaxWidth())
                        }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Save", {
                            if (openTarget != null) ControlsStore.bindOpen(openTarget!!, t, openOld) else saveBinding(pkg, action, t); onDone()
                        }, kind = ButtonKind.PRIMARY, focusRequester = saveFocus)
                        GlassButton("Try again", { capturing = true }, kind = ButtonKind.QUIET)
                    }
                } else if (error == null && current != null) {
                    // 1.4: the combo as it is. Build 18 (C3, VD-02): change it or remove it, one row
                    app.wayfinder.ui.TriggerGlyphs(current, 34.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Change the buttons", { capturing = true }, kind = ButtonKind.PRIMARY, focusRequester = saveFocus)
                        // dev build 17 (WP8): it deletes what the user set — two presses
                        ConfirmButton("Remove the combo", "Press again to remove it",
                            { if (openArg != null) ControlsStore.unbindOpen(openOld!!) else removeBinding(pkg, action); onDone() })
                    }
                    LaunchedEffect(Unit) { saveFocus.requestAfterLayout() }
                } else {
                    GlassNotice(error ?: "", NoticeKind.WARN, Modifier.fillMaxWidth())
                    GlassButton("Try again", { capturing = true }, kind = ButtonKind.PRIMARY, focusRequester = saveFocus)
                    LaunchedEffect(Unit) { saveFocus.requestAfterLayout() }
                }
            }
            val overridden = openArg == null && pkg != null && AppConfigStore.get(pkg).let { c ->
                c.buttons.any { it.action == action } || (ControlsStore.triggerFor(action)?.let { it in c.freed } == true)
            }
            if (!capturing && overridden) {
                GlassButton("Use my usual combo (${ControlsStore.triggerFor(action)?.let { Say.of(it, look) } ?: "not set"})",
                    { useGlobal(pkg!!, action); onDone() }, kind = ButtonKind.QUIET)
            }
        }
    }
}

/** Round 8 — pick what an "Open…" combo opens: an app, an app pair or a Wayfinder page. */
@Composable
internal fun OpenPicker(kind: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    val g = LocalGlass.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val first = remember { FocusRequester() }
    // (label, target) — apps load off the main thread (100+ labels)
    val options by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, kind) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (kind) {
                "app" -> emptyList()      // AppGridPicker loads them, with icons
                "pair" -> Layouts.pairs.map { p -> OpenTargets.label(ctx, OpenTargets.pair(p.id)).removePrefix("Open the pair ") to OpenTargets.pair(p.id) }
                else -> OpenTargets.PAGES.map { (id, name) -> name to OpenTargets.page(id) }
            }
        }
    }
    // apps: a grid with icons and a search (a list of 100+ names wasted the screen — 2026-09-25)
    if (kind == "app") { AppGridPicker(onCancel, onPick); return }
    LaunchedEffect(options) { if (options != null) first.requestAfterLayout() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModalHeader(when (kind) { "app" -> "Which app?"; "pair" -> "Which app pair?"; else -> "Which Wayfinder page?" }, onCancel,
            closeLabel = "Cancel")
        val list = options
        when {
            list == null -> EmptyState("Loading…")
            list.isEmpty() -> EmptyState(if (kind == "pair") "No app pairs yet — save one from the quick panel's “App pairs” tile, or on the App pairs page."
                else "Nothing to pick.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.size, key = { list[it].second }) { i ->
                    FocusableGlass(onClick = { onPick(list[i].second) }, radius = Radii.Control, modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (i == 0) first else null) {
                        Text(list[i].first, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

/** The app picker: a search box and a grid of icons (the Wayfinder keyboard opens on the other screen). */
@Composable
private fun AppGridPicker(onCancel: () -> Unit, onPick: (String) -> Unit) {
    val g = LocalGlass.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val first = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    val apps by androidx.compose.runtime.produceState<List<AppEntry>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadApps(ctx).sortedBy { it.label.lowercase() } }
    }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        apps?.filter { q.isEmpty() || it.label.lowercase().contains(q) }
    }
    LaunchedEffect(apps) { if (apps != null) first.requestAfterLayout() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // dev build 17 (WP8): Cancel on the left; the search field takes the room after the title
        ModalHeader("Which app?", onCancel, closeLabel = "Cancel", fillTitle = false) {
            app.wayfinder.ui.ControllerTextField(query, { query = it.take(30) }, Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                placeholder = "Find an app…", surface = Modifier.background(g.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(Radii.Control)))
        }
        val list = shown
        when {
            list == null -> EmptyState("Loading…")
            list.isEmpty() -> EmptyState("No app matches “$query”.")
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(118.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.size, key = { list[it].pkg }) { i ->
                    val a = list[i]
                    FocusableGlass(onClick = { onPick(OpenTargets.app(a.pkg)) }, radius = Radii.Row, modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (i == 0) first else null) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.icon?.let { androidx.compose.foundation.Image(it, null, Modifier.size(42.dp)) } ?: Box(Modifier.size(42.dp))
                            Text(a.label, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Red pulsing dot while listening (grey while getting ready). */
@Composable
private fun ListeningDot(armed: Boolean) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    val g = LocalGlass.current
    Box(Modifier.size(14.dp).background(
        if (armed) g.danger.copy(alpha = a) else g.textTertiary.copy(alpha = .5f), androidx.compose.foundation.shape.CircleShape))
}
