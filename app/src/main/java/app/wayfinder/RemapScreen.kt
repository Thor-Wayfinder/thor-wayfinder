package app.wayfinder

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlyphText
import app.wayfinder.ui.LocalPadLook
import app.wayfinder.ui.appendButton
import app.wayfinder.ui.currentLook
import app.wayfinder.ui.rememberButtonInline
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.BackLayer
import app.wayfinder.ui.GlassSheet
import app.wayfinder.ui.rememberBackPress
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.arrivalTo
import app.wayfinder.ui.ReturnFocus
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.BackPill
import app.wayfinder.ui.BackPillDefaults
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.Caption
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.ConfirmState
import app.wayfinder.ui.EmptyState
import app.wayfinder.ui.FieldLabel
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassButtonDefaults
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassNotice
import app.wayfinder.ui.GlassOption
import app.wayfinder.ui.GlassSwitchRow
import app.wayfinder.ui.KeyHint
import app.wayfinder.ui.ModalHeader
import app.wayfinder.ui.NoticeKind
import app.wayfinder.ui.Radii
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.Stepper
import app.wayfinder.ui.keyHintWide
import app.wayfinder.ui.modalDim
import app.wayfinder.ui.modalSurface
import app.wayfinder.ui.rememberConfirmState
import app.wayfinder.ui.recess
import app.wayfinder.ui.selectedFill
import app.wayfinder.ui.scrollFade
import app.wayfinder.ui.TIGHT_PAD_DP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Input layer, phase 2b — "Buttons for <app>". dev build 16 (WP6): Game controls — ONE
 * page for every controller: the same header, controller tabs, status chip, drawing,
 * legend and More on every tab; only whose drawing, names and controls change.
 * ONE thing to navigate: the controller of the tab, centred (dev build 16, WP5: any controller's
 * drawing — PadArt.kt / PadArtCanvas.kt). The D-pad moves between controls (or
 * touch one); A opens that control's popup: what it becomes — Controller (it listens for the
 * button you press), Keyboard, Mouse, Actions — and what it does when held (normal, turbo,
 * toggle). L1 / R1 switch the popup's tabs, Y resets the control (asked twice), B closes; on the drawing X switches
 * the face buttons and L1 / R1 the controller. Everything applies only while the app has the controller.
 * Critic round 2 applied (one focus style, tabs look like tabs, "when held" at the bottom,
 * an inspector in the drawing's empty screen, hints hidden while listening).
 */

private enum class Popup { BIND, DPAD, GYRO, PADGYRO, CHANGES, PRESETS, CHORDS, APPS, PERF, MORE }
private enum class Tab(val label: String) { CONTROLLER("Controller"), KEYBOARD("Keyboard"), MOUSE("Mouse"), ACTIONS("Actions"), MACRO("Macro") }

/** Short words for tags and chips, in the Thor's names (dev build 16, WP5: [short] with a look — FaceStyles.kt). */
internal fun RemapTarget.short(): String = short(ButtonNames.thorLook)

/** dev build 16 (WP6): how much of the header fits — measured, not a fixed width (critique §5). Each step leaves out the
 *  least needed: the app icon, then Back's words (its arrow and glyph stay), then Performance (into Game settings, with
 *  narrower face pills) — the bottom screen; build 18 round 2 (MORE-12): last, Game settings' words (its gear stays, the
 *  words its description), as the bar's "App settings" does. */
private enum class HeaderFit { FULL, NO_ICON, NO_BACK_WORDS, COMPACT, SETTINGS_ICON }

/** Build 18 round 2 (MORE-12): the header button's name — the popup's title and every path to it say the same
 *  (NavCards' section; "More" was the Hub page's name too). */
private val GAME_SETTINGS: String get() = NavCards.GC_PLAYER1.section!!

/** Game controls on a screen of its own — dev build 17 (WP11): a controller's every-game editor (its page's "Edit its own
 *  controls"); a game's or an app's page is [GamePage], Game controls its first section. */
@Composable
fun RemapScreen(myDisplayId: Int, pkg: String, onBack: () -> Unit, inGame: Boolean = false,
                onSwitchApp: (String) -> Unit = {}, onAllApps: () -> Unit = {},
                /** dev build 16 (WP6): the controller that opened the page (the intent's pad=) — its tab, kept for the session */
                openedBy: String? = null,
                /** where Back goes when it isn't the game or Games ("Back to DualSense": a controller's every-game editor) */
                backTo: String? = null) {
    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
        GameControls(myDisplayId, pkg, onBack, inGame, onSwitchApp, onAllApps, openedBy, backTo)
    }
}

/**
 * Game controls — dev build 17 (WP11): the page's body, apart from its screen: [RemapScreen] puts it on one, [GamePage] makes
 * it the first section of a game's page. [initialFocus]: the drawing takes the controller when the page opens (a game's
 * page opening on its app settings: no); [onPopupOpen]: a popup opens / closes (the page scrolls up and holds still);
 * [toApp]: a button to the app's settings below (null: none — a controller's every-game editor); [openPage]: a Hub page
 * from a popup (Performance's Stick lights; null in a game — it says where instead: K13). Build 18 (GC12, T18-02 / gd-D27):
 * [perfAsk] bumped = open Performance (the app settings' "Performance and screen" and in-game "Stick lights" rows); review
 * (GC12): [perfBack] gives the focus back to the row that asked when it closes (false: it's gone — the drawing). Device pass
 * (P1-3): [returnTo] = the row the page comes back to ([app.wayfinder.ui.ReturnFocus]) — one of the header's popup openers
 * ([GameTabRules.Opener]: Which game? › All apps › Back) has the focus, not the drawing.
 */
@Composable
internal fun GameControls(myDisplayId: Int, pkg: String, onBack: () -> Unit, inGame: Boolean = false,
                          onSwitchApp: (String) -> Unit = {}, onAllApps: () -> Unit = {}, openedBy: String? = null,
                          backTo: String? = null, initialFocus: Boolean = true, onPopupOpen: (Boolean) -> Unit = {},
                          toApp: (() -> Unit)? = null, openPage: ((String) -> Unit)? = null, perfAsk: Int = 0,
                          perfBack: (suspend () -> Boolean)? = null, returnTo: String? = null) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    PadProfiles.init(ctx); ButtonNames.init(ctx)
    val v = AppConfigStore.version.intValue + PadProfiles.version.intValue + ExtPadsCtl.version.intValue
    // pkg = an app, or a game profile (`<pkg>#<game>`, GameProfiles) — or one controller's own controls there
    // (`<that>@<controller>`): that controller's tab alone. dev build 16 (WP6): its page's "Edit its own controls" opens
    // `*@<controller>`, its every-game editor
    val fixedPad = if (PadProfiles.isPad(pkg)) PadProfiles.padOf(pkg).takeIf { GameTabRules.padKeyOk(it) } else null
    val baseKey = if (PadProfiles.isPad(pkg)) PadProfiles.baseOf(pkg) else pkg
    val everyEditor = baseKey == PadProfiles.EVERY
    if (everyEditor && fixedPad == null) { LaunchedEffect(Unit) { onBack() }; return }   // "*" is no app
    val appPkg = GameProfiles.pkgOf(baseKey)
    val game = remember(v, baseKey) { GameProfiles.get(baseKey) }
    // dev build 16 (WP6): the controllers' tabs and the one shown. The controller wanted is kept for the session — not keyed
    // on the page: a late game detection or the title switcher keeps the tab — and changed only by the user (L1 / R1, the
    // tab row). One that left shows the Thor's tab, and comes back to its own if it reconnects (unless the user acted there)
    var tabWant by rememberSaveable { mutableStateOf(fixedPad ?: openedBy?.takeIf { GameTabRules.padKeyOk(it) }) }
    val tabs = ControlsRouter(fixedPad, v)
    val shown = GameTabRules.shown(tabs.map { it.key }, tabWant)
    val tab = tabs.firstOrNull { it.key == shown } ?: tabs.first()
    val padKey = tab.key
    // callbacks (captures, popups) outlive a recomposition: what they edit is read NOW
    val padNow by rememberUpdatedState(padKey)
    val baseNow by rememberUpdatedState(baseKey)
    // the drawing and names of the shown tab's controller (dev build 16, WP5); its motion pill and own parts (WP6)
    val tabArt = rememberTabArt(padKey, padGyro = padKey != null && PadControls.hasGyro(padKey), parts = true)
    val art = tabArt.art
    val look = tabArt.look
    // what the shown tab plays and draws: the Thor's tab the page itself; a controller's tab [PadControls.tab] — the
    // engine's very answer (its own, else a copy of what it uses: the Thor's controls dimmed, or plain)
    val padTab = remember(v, baseKey, padKey) { padKey?.let { PadControls.tab(ctx, baseKey, it) } }
    val cfg = remember(v, baseKey, padKey) { padTab?.shown ?: Profiles.get(baseKey) }
    val remap = cfg.remap ?: PadRemap()
    val borrowed = padTab?.source == PadSource.Thor
    // the own entry a first change (or "Make its own") made in this session, and from what: the chip says "made from …"
    var madeHere by remember { mutableStateOf<Pair<String, PadSource>?>(null) }
    val app by produceState<AppEntry?>(null, appPkg) {
        value = withContext(Dispatchers.IO) { runCatching {
            val pm = ctx.packageManager; val ai = pm.getApplicationInfo(appPkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(),
                runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull(), GameApps.isGame(ai, appPkg))
        }.getOrNull() }
    }
    val appLabel = if (everyEditor) "every game" else app?.label ?: appPkg
    val label = game?.title ?: appLabel
    // build 18 (GC6, T18-01): Game controls in this app as the page says it — a game not set up yet in "Games you set up" is
    // PENDING (its first change starts them), never "off" in red; a controller's every-game editor has no app
    val layerState = if (everyEditor) LayerState.ON
        else GameTabRules.layer(PadLayerCtl.mode, AppConfigStore.get(appPkg).layer, PadLayerCtl.isSetUp(appPkg))
    // Share = a file with this profile's controller mapping (never its Wayfinder actions), in Download/Wayfinder — see
    // ProfileShare. A controller's tab shares its own controls (none of its own: nothing to share)
    val shareKey = if (padKey == null) baseKey else padTab?.editKey
    // dev build 16 review (WP6-3): the key chosen when the sheet opened — the tab changing under it (the controller left)
    // re-pointed it to the Thor's controls; the sheet also closes with the tab (below)
    var sharingKey by remember { mutableStateOf<String?>(null) }
    val sharing = sharingKey != null
    sharingKey?.let { sk -> ShareChoice(label, onSave = {
        sharingKey = null
        val f = ProfileShare.export(ctx, sk)
        Overlays.pill(if (f != null && ProfileShare.save(ctx, f.first, f.second)) "Saved in Download / Wayfinder: ${f.first}"
            else "The file couldn't be saved", CueOrigin.Window(myDisplayId))
    }, onSend = {
        sharingKey = null
        val f = ProfileShare.export(ctx, sk)
        if (f == null || !ProfileShare.send(ctx, f.first, f.second, "Send these controls")) Overlays.pill("Couldn't open the share menu", CueOrigin.Window(myDisplayId))
    }) { sharingKey = null } }
    // critique m4: once the user acts on the tab shown (an edit, a popup, a pill), it is the one wanted — a controller
    // reconnecting doesn't pull the page away from it
    fun claim() { if (fixedPad == null && tabWant != padNow) tabWant = padNow }
    /** The own entry in force for [pad] here (its own at the page's level, its app's or every game's), null = none. */
    fun editKey(pad: String) = (PadControls.resolve(ctx, baseNow, pad) as? PadSource.Own)?.key
    /** The stored profile NOW (capture callbacks outlive a recomposition): what the shown tab draws. */
    fun fresh(): PadRemap = (padNow?.let { PadControls.tab(ctx, baseNow, it).shown } ?: Profiles.get(baseNow)).remap ?: PadRemap()
    /** What a copy of [t]'s controls is made from — critique m3: the Thor's reach it only where the input layer runs; where
     *  it is off for the app the copy is its plain buttons, and the words say so. */
    fun copiedFrom(t: PadTab): PadSource =
        if (t.source == PadSource.Thor && t.base != PadProfiles.EVERY && PadLayerCtl.need(GameProfiles.pkgOf(t.base)) == false) PadSource.Plain else t.source
    /** dev build 16 (WP6, D3, critique B5): the entry a change on [pad]'s tab goes to — its own in force, else made now at
     *  the page's level from what the tab drew ([PadControls.ownForEdit]); one kept switched off there is kept aside
     *  (thor_padprof_prev), and the pill says where to bring it back. */
    fun ensureOwn(pad: String): String {
        val before = PadControls.tab(ctx, baseNow, pad)
        // build 18 (GC1, decision H4): a copy of the Thor's made here by itself (no own ones kept off at this level) carries
        // its mark — "Use the Thor's again" deletes it while only its face differs
        val auto = before.editKey == null && !before.switchedOff && copiedFrom(before) == PadSource.Thor
        val k = PadControls.ownForEdit(ctx, baseNow, pad, madeFrom = if (auto) MadeFrom.THOR else null)
        if (before.editKey == null) {
            val from = copiedFrom(before)
            madeHere = k to from
            val name = padNameOf(pad)
            Overlays.pill(if (before.switchedOff) GameWords.replacedKeptOff(name, label, from, appLabel)
                else GameWords.madeByEdit(name, label, from, appLabel), CueOrigin.Window(myDisplayId))
        }
        return k
    }
    // 1.4: a change is a set-up game — the layer starts for it right away ("Only games I set up"). dev build 16 (WP6, D3):
    // a controller's tab without its own draws what it uses; its first REAL change makes its own — the Thor's never change
    // from a controller's tab, and a press that changes nothing makes nothing (S1)
    fun save(r: PadRemap) {
        claim()
        val pad = padNow
        val k = if (pad == null) baseNow else {
            if (r == fresh() && editKey(pad) == null) return
            ensureOwn(pad)
        }
        Profiles.update(k) { it.copy(remap = r.takeIf { !r.isEmpty }) }
        if (!everyEditor) PadLayerCtl.nowFor(ctx, appPkg)
    }

    // dev build 16 (WP5): the selection is a part's identity — kept across drawings (tabs), resolved on this one. Build 18
    // (GC4, G17): a tab opens on its confirm button (✕ on a DualSense, A on the Thor) until the user moves the selection
    val confirmId = art.ctlFor(Vocab.menuPos(ThorButton.A, look))?.id ?: art.first
    var selId by remember { mutableStateOf(confirmId) }
    var selMoved by remember { mutableStateOf(false) }
    LaunchedEffect(confirmId) { if (!selMoved) selId = confirmId }
    val sel = art.resolve(selId)
    // 1.3: the D-pad direction being edited (its page opens the same editor as a button)
    var dirSel by remember { mutableStateOf<ThorButton?>(null) }
    var popup by remember { mutableStateOf<Popup?>(null) }
    var bindTab by remember { mutableStateOf(Tab.CONTROLLER) }
    var kbPage by remember { mutableStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var mapFocused by remember { mutableStateOf(false) }
    // dev build 16 (WP6): where the controller is — the tab row (L1 / R1 work there too), the header (no shortcut there)
    var onTabs by remember { mutableStateOf(false) }
    var onHeader by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<String?>(null) }
    // build 18 (GC10, gd-D16): a popup's "nothing changed to reset" (its Reset button is gone), in its header for 2.5 s
    var resetNote by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    // dev build 16 (WP6, S7): Y asks twice before resetting (the drawing, the popups and their Reset pill). dev build 17 (WP8):
    // one ConfirmState — Y and the popups' Reset button share it, so "the button then Y" confirms too
    val resetAsk = rememberConfirmState()
    // More opened from a controller's own part (SL / SR, its Home): its rows first
    var moreParts by remember { mutableStateOf(false) }
    // dev build 16 review (THOR-2): Performance opened from More (the compact header, the bottom screen) — B / its Back go
    // back to More, like Chords and Presets
    var perfFromMore by remember { mutableStateOf(false) }
    // phase 3: which output a long / double press button's tabs edit (0 = its press, 1 = the 2nd
    // action); macro recording; a new chord's two buttons
    var slot by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var chordListen by remember { mutableStateOf(false) }
    var chordPair by remember { mutableStateOf(listOf<ThorButton>()) }
    val recDown = remember { HashMap<ThorButton, Long>() }
    var recLastUp by remember { mutableStateOf(0L) }
    val mapFocus = remember { FocusRequester() }
    val popupFirst = remember { FocusRequester() }
    // build 18 (GC2, G03): a popup closed gives the focus back to the header control that opened it — the title (Which
    // game?), Performance, More, the changes count; null = the drawing (a part's popup: its part stays selected)
    val titleFocus = remember { FocusRequester() }
    val perfFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val changesFocus = remember { FocusRequester() }
    // device pass (P1-3): the openers by id ([GameTabRules.Opener]) — back on the page from a popup's row that left it (Which
    // game? › All apps), its opener is the first to get the focus (the hand-back below), not the drawing
    val openers = remember { mapOf(GameTabRules.Opener.TITLE to titleFocus, GameTabRules.Opener.PERF to perfFocus,
        GameTabRules.Opener.MORE to moreFocus, GameTabRules.Opener.CHANGES to changesFocus) }
    var opener by remember { mutableStateOf(if (initialFocus) GameTabRules.openerOf(returnTo)?.let { openers[it] } else null) }
    /** Device pass (P1-3): a popup's row leaves for another page (Which game? › All apps) — Back comes back to the popup's
     *  opener (the row is gone with the popup): its id is the row the Hub records. Called before the row's page opens.
     *  b18r3 (review): EVERY popup row that leaves the page calls it (Performance's "Colours and brightness ›" didn't — Back
     *  then took the Hub's guess for a page no row opened, Nav.rowTo). [asked]: the popup's own header control, for a popup
     *  no header control opened (Performance asked by the app settings' row below, [perfAsk]). */
    fun leavingFromPopup(asked: GameTabRules.Opener? = null) {
        (openers.entries.firstOrNull { it.value === opener }?.key ?: asked)?.let { ReturnFocus.clicked = it.id }
    }
    // review (GC12): the open Performance was asked by a row of the app settings below ([perfAsk]) — closed, back to it
    val perfAsked = remember { booleanArrayOf(false) }
    // build 18 (GC2, G04): the D-pad direction whose editor went back to the D-pad popup — it opens on that direction
    var lastDir by remember { mutableStateOf<ThorButton?>(null) }
    // build 18 (GC3): "Change a button" picked a part — the drawing takes the focus (bumped from the capture's thread)
    var pickFocus by remember { mutableIntStateOf(0) }
    val open = popup != null
    // dev build 16 (WP7): the drawing (or the popup's first control) once laid out — no fixed delay. dev build 17 (WP11): only
    // when the page opens on it (a game's page may open on its app settings). Device pass (P1-3): not when it comes back to a
    // popup's opener (the hand-back below gives it the focus, the drawing if it's gone)
    LaunchedEffect(Unit) { if (initialFocus && opener == null) mapFocus.requestAfterLayout() }
    // dev build 17 (WP11): a game's page — the controller is here (the page or a popup), a popup or the Share sheet was open on
    // the last run, or the page opens on the drawing: the hand-back below is ours. Else (the controller in the app settings
    // below) a controller leaving (the tab changes) never pulls the focus up from there
    val hosted = toApp != null
    val gcFocus = remember { booleanArrayOf(false) }
    val focusRuns = remember { booleanArrayOf(true, false, false) }          // first run · a popup was open · Share was open
    // after a popup, a bind tab, a keyboard page — and dev build 16 (WP6): after a tab switch, the drawing has the controller.
    // dev build 16 (WP7): not while the Share sheet is open (it has it); when it closes, the drawing again (its opener, More's
    // Share row, closed with More)
    LaunchedEffect(popup, bindTab, kbPage, padKey, sharing) {
        val ours = !hosted || (if (focusRuns[0]) initialFocus else gcFocus[0] || popup != null || focusRuns[1] || focusRuns[2])
        focusRuns[0] = false; focusRuns[1] = popup != null; focusRuns[2] = sharing
        // review (GC12): Performance asked by an app settings row, now closed — back to that row ([perfBack]); else forgotten
        val back = perfBack?.takeIf { perfAsked[0] && popup == null && !sharing }
        if (popup != Popup.PERF) perfAsked[0] = false
        if (!ours) return@LaunchedEffect
        if (popup != null) popupFirst.requestAfterLayout()
        else if (!sharing) {
            // build 18 (GC2): back to what opened it — the drawing when that's gone (the changes count after removing all).
            // Device pass (P1-3): the popup's focus left with it, so this request comes into the page from outside — the
            // page's arrival rule (the drawing) stands aside for it ([arrivalTo]) and it lands on its first try; true only
            // when the opener HOLDS the focus (A: requestAfterLayout), else the drawing
            val o = opener; opener = null
            if (back != null && back()) return@LaunchedEffect
            // b18r3 (review): Performance isn't in a compact header (it is in Game settings) — back from Stick lights (the
            // Hub's way back names Performance, Nav.rowTo) lands on Game settings, the way to it there
            if (o == null || !(o.requestAfterLayout() || (o === perfFocus && moreFocus.requestAfterLayout()))) mapFocus.requestAfterLayout()
        }
    }
    LaunchedEffect(pickFocus) { if (pickFocus > 0) mapFocus.requestAfterLayout() }
    // build 18 (GC12, T18-02 / gd-D27): the app settings below asked for Performance (review GC12: back to the row that asked
    // when it closes — [perfBack]); a count asked before this composition is never replayed
    var perfSeen by remember { mutableIntStateOf(perfAsk) }
    LaunchedEffect(perfAsk) {
        if (perfAsk > perfSeen) { perfSeen = perfAsk; opener = null; perfFromMore = false; perfAsked[0] = true; popup = Popup.PERF }
    }
    LaunchedEffect(open) { onPopupOpen(open) }
    LaunchedEffect(live) { if (live != null) { delay(2500); live = null } }
    LaunchedEffect(selId, dirSel, popup, padKey) { resetAsk.cancel(); resetNote = null }  // the second Y is for the same control
    LaunchedEffect(resetNote) { if (resetNote != null) { delay(2500); resetNote = null } }
    LaunchedEffect(popup) {
        if (popup != null) claim()
        if (popup != Popup.BIND) dirSel = null
        if (popup != Popup.MORE) moreParts = false
        // 1.4 (review): Chords / the bind popup closed (to More too) — no capture left running, no half-picked pair
        if (popup != Popup.BIND && popup != Popup.GYRO) { listening = false; recording = false }
        if (popup != Popup.CHORDS) { chordListen = false; chordPair = emptyList() }
    }
    // dev build 16 (WP6): the shown controller changed under an open popup or a capture (it left; the helper restarted) —
    // closed, so no change ever goes to another controller than the one whose tab was open
    LaunchedEffect(padKey) {
        popup = null; picking = false; listening = false; recording = false; chordListen = false
        resetAsk.cancel(); slot = 0; dirSel = null; live = null
        opener = null; perfAsked[0] = false                      // build 18 (GC2): after a tab switch, the drawing
        sharingKey = null                                        // dev build 16 review (WP6-3): the Share sheet too
    }
    if (BuildConfig.DEBUG) {
        // the S7 device tests read these (no UI dump: it kills accessibility overlays)
        // where the controller really is (behind a popup nothing may have it: the popup's own rows are "popup")
        val at = when { mapFocused -> "drawing"; onTabs -> "tabs"; onHeader -> "header"; open -> "popup"; else -> "other" }
        LaunchedEffect(padKey, at) {
            Log.d("ThorGameControls", "tab=${padKey ?: "thor"} name=${tab.name} docked=${ExtPadsCtl.seatPad()?.name ?: "-"} focus=$at")
        }
        LaunchedEffect(popup) { Log.d("ThorGameControls", "popup=${popup ?: "none"}") }
    }

    // Face buttons. dev build 15 (WP2): positions only — a stored PlayStation is the Xbox positions. The usual one: the
    // Thor's tab AYN's style now (state: the tile may change it meanwhile), picking it = following all apps; a controller's
    // tab the positions it has in this game without a face of its own here (its page's face; like the Thor's = the Thor's
    // for this game). dev build 16 (WP6, critique G1): a controller's tab offered Usual · Nintendo · Xbox — build 18 (GC8):
    // every tab offers Usual and the other style only (an explicit face equal to the usual one shows as Usual)
    val usualXbox = padKey?.let { ExtPadsCtl.usualXbox(it, baseKey) } ?: ButtonNames.aynXbox
    val usual = if (usualXbox) FaceStyle.XBOX else FaceStyle.NINTENDO
    // a borrowed tab has no face of its own (its copy of the Thor's never carries the Thor's face for this game)
    val ownFace = cfg.face
    val effective = ownFace?.let { if (it.xboxPositions) FaceStyle.XBOX else FaceStyle.NINTENDO } ?: usual
    val facePs = look.names == FaceStyle.PLAYSTATION
    // build 18 (GC8, gd-D03 / FTU-12): every tab offers Usual (this game without a face of its own) and the one style whose
    // positions differ; an own face equal to the usual one shows as Usual (nothing is written until a pick)
    val faceChoices = GameTabRules.faceOptions(usual, tab.kind)
    val faceUsual = ownFace == null || ownFace.xboxPositions == usual.xboxPositions
    fun setFace(f: FaceStyle?) {
        claim()
        val pad = padNow ?: run {
            val n = f?.let { x -> GameTabRules.thorFace(x, usual) }
            if (n != Profiles.get(baseNow).face) Profiles.update(baseNow) { it.copy(face = n) }
            return
        }
        if (f == PadControls.tab(ctx, baseNow, pad).shown.face) return               // S1: nothing changed, nothing made
        Profiles.update(ensureOwn(pad)) { it.copy(face = f) }
    }
    /** GC8: "Usual" picked — this game's face of its own goes (the Thor's tab: its profile's; a controller's: its own). */
    fun clearFace() = setFace(null)
    /** X on the drawing: Usual ↔ the other pill (as the two pills read). */
    fun cycleFace() = if (faceUsual) setFace(GameTabRules.padFace(faceChoices[1])) else clearFace()
    /** What the popup's tabs pick: the control's press, or its long / double press output. */
    fun bind(src: ThorButton, t: RemapTarget?) {
        val r = fresh()
        if (slot == 2 && r.shift != null && src != r.shift) save(r.copy(shifted = if (t == null) r.shifted - src else r.shifted + (src to t)))
        else if (slot == 1 && r.fire[src]?.hasAlt == true) save(r.copy(alt = if (t == null) r.alt - src else r.alt + (src to t)))
        else save(r.copy(buttons = if (t == null || t == RemapTarget.Button(src)) r.buttons - src else r.buttons + (src to t)))
    }
    /** What Y's reset leaves of the controls (null: the motion aims, reset elsewhere). dev build 16 review (WP6-2 /
     *  THOR-1): the D-pad on the drawing resets too — its four directions (their jobs, turbo / toggle, second and shifted
     *  jobs, a controller's own part job on them) and its swap with the left stick; Y promised it and did nothing. */
    fun resetOf(r: PadRemap): PadRemap? {
        fun clear(x: PadRemap, b: ThorButton): PadRemap {
            val y = x.copy(buttons = x.buttons - b, fire = x.fire - b, alt = x.alt - b, shifted = x.shifted - b)
            // a controller's own part job on it (its reader's) goes too
            return PadRemap.outCode(b)?.let { c -> y.withPart(c, null).withPartFire(c, null) } ?: y
        }
        return when (val s = sel.src) {
            ArtSrc.Gyro, ArtSrc.PadGyro -> null
            is ArtSrc.Part -> r.withPart(s.code, null).withPartFire(s.code, null)
            ArtSrc.Dpad -> dirSel?.let { clear(r, it) } ?: PadRemap.DIRS.fold(r) { x, b -> clear(x, b) }.copy(dpadStick = false)
            else -> (dirSel ?: sel.btn)?.let { clear(r, it) } ?: r
        }
    }
    fun resetSel() {
        when (sel.src) {
            // dev build 17 (D10): the Thor's gyro only from its own tab (a controller's controls can't hold it)
            ArtSrc.Gyro -> if (padNow == null) save(remap.copy(gyro = GyroSettings()))
            ArtSrc.PadGyro -> padNow?.let { ExtPadsCtl.setGyroFor(ctx, it, baseNow, null) }            // back to its usual aim
            else -> resetOf(remap)?.let { if (it != remap) save(it) }
        }
        slot = 0
    }
    /** dev build 16 review (WP6-2 / THOR-1): on the drawing, Y on a part with nothing to reset asks nothing (it said "Press Y
     *  again to reset" and the second Y changed nothing) — the legend says so instead. The popups keep their two presses. */
    /** Build 18 (GC10, gd-D16): the selected control (a D-pad direction being edited too) has nothing Y / Reset would undo —
     *  the drawing's legend and the popups' Reset button read it ([nothingToReset] on the drawing). */
    fun nothingHere() = when (sel.src) {
        // build 18 (GC4, G09): the motions too — the Thor's gyro as it starts (and nothing of it on a controller's tab: Y does
        // nothing there); a controller's motion aim with no aim of this game's own (every game: its page's, off)
        ArtSrc.Gyro -> padNow != null || remap.gyro == GyroSettings()
        ArtSrc.PadGyro -> padNow?.let { p ->
            if (baseNow == PadProfiles.EVERY) ExtPadsCtl.gyro(ctx, p) == ExtPadsCtl.Gyro() else ExtPadsCtl.gyroAt(ctx, p, baseNow) == null } ?: true
        else -> resetOf(remap) == remap
    }
    fun nothingToReset() = !open && nothingHere()
    // build 18 (GC10, gd-D16): a popup's Y with nothing to reset says so too (its Reset button is gone) — no "Press again"
    fun askReset() {
        if (!resetAsk.asked && nothingHere()) {
            live = "${dirSel?.spoken(look) ?: sel.title(look)}: nothing changed to reset"
            if (open) resetNote = live                                  // a popup says it where its Reset would be
            return
        }
        resetAsk.press { resetSel() }
    }
    /** Opens part [id]'s editor (critique M6: the touched one, not the selection of the frame before). */
    fun openSel(id: ArtId = selId) {
        val p = when (art.resolve(id).src) {
            ArtSrc.Dpad -> Popup.DPAD
            // dev build 17 (D10): the Thor's gyro only on its own tab: a controller's controls can't hold it (PadRules.forPad
            // drops it — saved there, it vanished)
            ArtSrc.Gyro -> Popup.GYRO.takeIf { padNow == null }
            is ArtSrc.Btn -> Popup.BIND
            ArtSrc.PadGyro -> Popup.PADGYRO.takeIf { padNow?.let { PadControls.hasGyro(it) } == true }      // D10
            is ArtSrc.Part -> { moreParts = true; Popup.MORE }                                    // Game settings › Its other buttons
        } ?: return
        // build 18 (GC2): a part's popup gives the focus back to the drawing; the D-pad's opens on Up
        opener = null; lastDir = null
        dirSel = null; popup = p; bindTab = Tab.CONTROLLER; slot = 0
    }
    /** L1 / R1, the tab row: another controller's tab (the drawing gets the controller: the effect above). */
    fun switchTo(k: String?) { if (fixedPad == null) tabWant = k }

    // "Find a control by pressing it" (map) and "Listen for a button" (popup): the next press
    // is captured (ButtonEngine swallows it, its release too). Home cancels listening.
    DisposableEffect(picking, listening, recording, chordListen) {
        if (!picking && !listening && !recording && !chordListen) return@DisposableEffect onDispose { }
        ForegroundAppService.startCapture { b, down ->
            if (recording) {                                     // a macro: presses + timing, appended
                val src = dirSel ?: art.resolve(selId).btn ?: return@startCapture
                if (b == ThorButton.HOME) { if (down) recording = false; return@startCapture }
                if (b.isFlick || b == ThorButton.BACK || b !in PadRemap.OUTPUTS) return@startCapture
                val now = android.os.SystemClock.uptimeMillis()
                val r = fresh()
                val cur = (if (slot == 2 && r.shift != null) r.shifted[src] else if (slot == 1 && r.fire[src]?.hasAlt == true) r.alt[src] else r.buttons[src]) as? RemapTarget.Macro
                val steps = cur?.steps.orEmpty()
                if (down) {
                    recDown[b] = now
                    // the pause before this press belongs to the step before it
                    if (steps.isNotEmpty() && recLastUp > 0)
                        bind(src, RemapTarget.Macro(steps.dropLast(1) + steps.last().copy(gap = (now - recLastUp).toInt().coerceIn(0, 5000)), cur!!.repeat, cur.armed))
                } else {
                    val t0 = recDown.remove(b) ?: return@startCapture
                    val n = steps + MacroStep(RemapTarget.Button(b), (now - t0).toInt().coerceIn(10, 5000), 60)
                    bind(src, RemapTarget.Macro(n.take(MacroStep.MAX), cur?.repeat ?: false, cur?.armed ?: true))
                    recLastUp = now
                    if (n.size >= MacroStep.MAX) recording = false
                }
                return@startCapture
            }
            if (!down) return@startCapture
            if (chordListen) {                                   // a new chord's two buttons
                if (b == ThorButton.HOME) { chordListen = false; chordPair = emptyList() }
                else if (b in PadRemap.BUTTONS && b !in chordPair) { chordPair = chordPair + b; if (chordPair.size == 2) chordListen = false }
                return@startCapture
            }
            if (picking) {
                // build 18 (GC3, G08): Home cancels (the capture has it: it never goes home) — the focus stays on the pill;
                // the control pressed is selected AND focused (A then opens it)
                if (b == ThorButton.HOME) { picking = false; return@startCapture }
                art.ctlFor(b)?.let { selId = it.id; selMoved = true; picking = false; if (!b.isDpad) pickFocus++ }
                // 1.3: a D-pad direction opens that direction's editor straight away
                if (b.isDpad) { opener = null; dirSel = b; bindTab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }
            } else if (listening && art.resolve(selId).src == ArtSrc.Gyro) {        // the gyro's on / off button
                // dev build 17 (D10): only on the Thor's own tab (its gyro)
                if (b == ThorButton.HOME || padNow != null) listening = false
                else if (b in GyroSettings.BUTTONS) { save(remap.copy(gyro = remap.gyro.copy(button = b))); listening = false }
            } else if (listening) {
                val src = dirSel ?: art.resolve(selId).btn
                when {
                    b == ThorButton.HOME -> listening = false
                    src == null || b.isFlick -> {}
                    b == ThorButton.BACK -> { bind(src, RemapTarget.Action(ThorAction.BACK)); listening = false }
                    else -> { bind(src, RemapTarget.Button(b)); listening = false }
                }
            }
        }
        onDispose { ForegroundAppService.stopCapture() }
    }
    LaunchedEffect(picking) { if (picking) { delay(6000); picking = false } }
    LaunchedEffect(listening) { if (listening) { delay(8000); listening = false } }
    LaunchedEffect(recording) { if (recording) { recLastUp = 0L; recDown.clear(); delay(15000); recording = false } }
    LaunchedEffect(chordListen) { if (chordListen) { chordPair = emptyList(); delay(8000); chordListen = false } }

    /** Everything behind the popup is out of the controller's reach while it's open. */
    val behind = Modifier.focusProperties { canFocus = !open }
    // a controller's motion aim here (the Nintendo kinds, D10): its pill on the drawing
    val padGyro = padKey?.takeIf { PadControls.hasGyro(it) }?.let { ExtPadsCtl.gyroFor(ctx, it, baseKey) }
    val gyroPill = padGyro?.let { GyroPill(it.stick != 0, if (it.stick != 0) "Motion → ${GameWords.motion(it)}" else "Motion · off") }
    // What the drawing's empty screen says about the selected control (dev build 16, WP5: in the tab's names, no hints;
    // WP6: borrowed → where it comes from; Y pressed once → what a second Y does)
    val inspector = inspectorText(sel, remap, look, gyroPill, tabArt).let { (a, b) ->
        a to when {
            resetAsk.asked -> "Press ${Say.menu(ThorButton.Y)} again to reset ${dirSel?.spoken(look) ?: sel.title(look)}"
            borrowed && sel.src != ArtSrc.PadGyro -> listOf(b, "From the Thor's controls · a change makes its own").filter { it.isNotEmpty() }.joinToString("   ·   ")
            else -> b
        }
    }
    val hand = ButtonNames.handLook
    // the Back pill: the same path as B — a popup open closes first (dev build 16, WP7)
    val backPress = rememberBackPress(onBack)
    // the header measured itself compact (the bottom screen): More then has Performance too (S8)
    var headerCompact by remember { mutableStateOf(false) }
    val moreCtx = MoreCtx(baseKey, appPkg, appLabel, label, everyEditor, headerCompact, tab, padTab,
        art.ctls.filter { it.src is ArtSrc.Part }, moreParts)

    CompositionLocalProvider(LocalPadLook provides look) {
      Box(Modifier.fillMaxSize().onFocusChanged { gcFocus[0] = it.hasFocus }.onPreviewKeyEvent { e ->
            // while a press is being captured (listening, recording, a chord) nothing navigates:
            // the D-pad reaches the screen as well (Android makes keys of its HAT inside the app)
            if (listening || recording || chordListen) return@onPreviewKeyEvent true
            val n = e.nativeKeyEvent
            val b = ButtonEngine.menuButton(n)
            // (B closes the popup through its BackLayer below — on B's release, like every Back — dev build 16, WP7)
            // 1.3: the D-pad's page — press a direction to change it, X to swap with the left stick
            if (popup == Popup.DPAD) {
                val d = when (n.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> ThorButton.UP; KeyEvent.KEYCODE_DPAD_DOWN -> ThorButton.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> ThorButton.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> ThorButton.RIGHT
                    else -> null
                }
                if (d != null) {
                    if (e.type == KeyEventType.KeyDown && n.repeatCount == 0) { dirSel = d; bindTab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }
                    return@onPreviewKeyEvent true
                }
                if (b == ThorButton.X) {
                    if (e.type == KeyEventType.KeyDown && n.repeatCount == 0) save(remap.copy(dpadStick = !remap.dpadStick))
                    return@onPreviewKeyEvent true
                }
            }
            if (e.type != KeyEventType.KeyDown || n.repeatCount > 0) return@onPreviewKeyEvent false
            if (open) when (b) {
                ThorButton.L1, ThorButton.R1 -> {
                    if (popup == Popup.BIND && !recording) {
                        val all = Tab.values(); val i = (bindTab.ordinal + if (b == ThorButton.R1) 1 else all.size - 1) % all.size
                        bindTab = all[i]; listening = false
                    }
                    return@onPreviewKeyEvent true
                }
                ThorButton.L2, ThorButton.R2 -> {        // the keyboard's two pages
                    if (popup == Popup.BIND && bindTab == Tab.KEYBOARD) { kbPage = if (b == ThorButton.R2) 1 else 0; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                ThorButton.Y -> { if (popup == Popup.BIND || popup == Popup.GYRO || popup == Popup.PADGYRO) askReset(); return@onPreviewKeyEvent true }
                else -> return@onPreviewKeyEvent false
            }
            // dev build 16 (WP6, D9): the shortcuts only while the drawing (or, for L1 / R1, the tab row) has the controller —
            // pressed on a header pill they did their job behind it (X switched the game's face buttons under a pill)
            val capturing = picking || listening || recording || chordListen
            if (b == null || !GameTabRules.shortcut(b, mapFocused, onTabs, open, capturing, tabs.size)) {
                // the drawing: what a press does here (L1 / R1 too while there is one tab)
                if (mapFocused && b != null && b in PadRemap.BUTTONS)
                    live = "${b.label(look)}  →  $label gets  ${(remap.buttons[b] ?: RemapTarget.Button(b)).short(look)}"
                return@onPreviewKeyEvent false
            }
            when (b) {
                ThorButton.L1, ThorButton.R1 -> switchTo(GameTabRules.step(tabs.map { it.key }, padKey, if (b == ThorButton.R1) 1 else -1))
                ThorButton.X -> cycleFace()
                ThorButton.Y -> askReset()
                else -> {}
            }
            true
        }) {
        // dev build 16 (WP6, uinav 2/8): focus coming into the page from nowhere (the first press, after a touch) lands on the
        // drawing — never on Back (an A then left the page). Device pass (P1-3): an arrival rule ([arrivalTo]) — a closed
        // popup's request for its opener isn't sent to the drawing (it was: Compose asks this `enter` for it too)
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
            .focusProperties { enter = { arrivalTo(mapFocus) } }.focusGroup()) {
            // ── header: Back, the game (the switcher, on every tab), face buttons, Performance, More ──
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tm = rememberTextMeasurer()
                val density = LocalDensity.current
                val ty = MaterialTheme.typography
                val gamey = app?.isGame != false || game != null || GameProfiles.forApp(appPkg).isNotEmpty() || GameProfiles.runningIn(appPkg) != null
                val backText = GameTabRules.backLabel(inGame, gamey, appLabel, backTo)
                // build 18 (GC9, gd-D04 / FTU-13): the title is the name alone — its ⌄ is an icon after it, never cut by the
                // ellipsis (the typed "  ▾" was); the fit measures the name + the icon
                val titleText = if (everyEditor) "Every game" else label
                val titleIcon = if (everyEditor) 0.dp else 26.dp
                val hasIcon = app?.icon != null && !everyEditor
                // dev build 17 (WP8): the hand's Back button in the pill — a word on a sideways Joy-Con (a capsule, measured)
                val backHint = Say.menu(ThorButton.B)
                // build 18 (GC8, gd-D05): the face pills are one width on every tab — what the widest words any tab here can
                // show need (both usuals, in each tab's names), so L1 / R1 never reflow the header
                val faceNames = tabs.map { t -> (t.key?.let { ExtPadsCtl.padLook(it) } ?: ButtonNames.thorLook).names == FaceStyle.PLAYSTATION }
                    .distinct().sorted()
                val xHint = Say.menu(ThorButton.X)
                // build 18 round 2 (MORE-12): the button is named as its popup and the paths ("More" was the Hub page's too)
                val settingsText = "$GAME_SETTINGS  ›"
                val compactFace = 168.dp
                val (wideFace, fit) = remember(maxWidth, backText, backHint, titleText, faceNames, xHint, hasIcon, everyEditor, density) {
                    fun w(s: String, st: TextStyle): Dp = with(density) { tm.measure(s, st, maxLines = 1).size.width.toDp() }
                    val words = w(backText, ty.labelLarge) + 6.dp
                    val hintStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val hintW = if (keyHintWide(backHint)) w(backHint, hintStyle) + 18.dp else BackPillDefaults.HINT
                    // the face block: the X hint, 6 dp, two equal segments (the widest word + the tight cell's padding), 6 dp
                    // between them — the panel's 4 dp padding a side is the + 8 below
                    val faceWord = faceNames.flatMap { GameTabRules.faceLabelsAll(it) }.maxOf { w(it, ty.labelMedium) }
                    val xW = if (keyHintWide(xHint)) w(xHint, hintStyle) + 18.dp else BackPillDefaults.HINT
                    val face = xW + 6.dp + (faceWord + (TIGHT_PAD_DP * 2).dp) * 2 + 6.dp
                    // Back (paddings, arrow, glyph + its words) · the title (readable: up to 150 dp of it) · Performance over Game
                    // settings · the face pills; 12 dp between
                    val backW = BackPillDefaults.CHROME + hintW
                    val titleW = minOf(w(titleText, ty.headlineSmall.copy(fontWeight = FontWeight.SemiBold)) + titleIcon, 150.dp) + 24.dp
                    val settingsW = w(settingsText, ty.labelLarge)
                    val chromeW = GlassButtonDefaults.chromeWidth(ButtonSize.COMPACT)
                    val need = backW + words + titleW + (maxOf(if (everyEditor) 0.dp else w("Performance  ›", ty.labelLarge), settingsW) + chromeW) +
                        (face + 8.dp) + 36.dp
                    // compact: no Back words, Game settings alone (Performance is inside it), the narrow face pills
                    val compactNeed = backW + titleW + (settingsW + chromeW) + (compactFace + 8.dp) + 36.dp
                    face to when {
                        hasIcon && need + 50.dp <= maxWidth -> HeaderFit.FULL
                        need <= maxWidth -> HeaderFit.NO_ICON
                        need - words <= maxWidth -> HeaderFit.NO_BACK_WORDS
                        compactNeed <= maxWidth -> HeaderFit.COMPACT
                        else -> HeaderFit.SETTINGS_ICON
                    }
                }
                val compact = fit >= HeaderFit.COMPACT
                Row(Modifier.fillMaxWidth().onFocusChanged { onHeader = it.hasFocus }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BackPill(backPress, behind, label = if (fit <= HeaderFit.NO_ICON) backText else null, contentDescription = backText)
                    if (fit == HeaderFit.FULL) app?.icon?.let { Image(it, null, Modifier.size(38.dp).clip(CircleShape)) }
                    // the name switches to another app or game (wrong one picked, or a game's own profile) — on every tab, the
                    // tab kept; a controller's every-game editor has nothing to switch (not focusable, H). dev build 17 (theme H):
                    // there it isn't a control at all — plain text, no press feedback on a title that does nothing
                    Box(Modifier.weight(1f)) {
                        val title: @Composable () -> Unit = {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                // the page's name small, the app / game big: "Game controls · <name>" didn't fit
                                Text(if (everyEditor) "${tab.name.uppercase()} — ITS OWN CONTROLS" else "GAME CONTROLS",
                                    color = if (everyEditor) g.accent else g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                // build 18 (GC9, gd-D04): the name, then ⌄ — the ellipsis cuts the name, never the mark
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(titleText, Modifier.weight(1f, fill = false), color = g.textPrimary, style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!everyEditor) Icon(Icons.Rounded.ExpandMore, null, tint = g.accent, modifier = Modifier.padding(start = 4.dp).size(22.dp))
                                }
                                Text(when {
                                    everyEditor -> "Only on ${tab.name}, wherever a game has none of its own for it"
                                    game != null -> "$appLabel · this game only"
                                    GameProfiles.runningIn(appPkg) != null -> "$appLabel · all its games — switch for this game only"
                                    inGame -> if (app?.isGame != false || GameProfiles.forApp(appPkg).isNotEmpty()) "Not this game? Switch" else "Not this app? Switch"
                                    else -> "Only while it has the controller"              // build 18 (GC4, G16): never cut
                                }, color = g.accent, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (everyEditor) title()
                        else FocusableGlass(onClick = { opener = titleFocus; popup = Popup.APPS }, radius = Radii.Row,
                            modifier = Modifier.focusProperties { canFocus = !open }, focusRequester = titleFocus) { title() }
                    }
                    // face buttons for this game, on this tab. Build 18 (GC8, gd-D03 / FTU-12): "Usual (<its usual>)" and the other
                    // style on every tab — the value is in the pill, so the caption under it went
                    val faceW = if (compact) compactFace else wideFace
                    val usualName = GameTabRules.faceUsualName(usual, facePs)
                    GameFacePills(faceChoices, if (faceUsual) FaceChoice.AUTO else faceChoices[1],
                        { c -> GameTabRules.faceLabel(c, usualName, facePs, compact) }, focusable = !open, width = faceW) { c ->
                        if (c == FaceChoice.AUTO) clearFace() else setFace(GameTabRules.padFace(c))
                    }
                    // Performance over Game settings (WP6: moved up here, the same on every tab); compact: Performance is in Game
                    // settings; build 18 round 2 (MORE-12): where even its words don't fit, the gear alone, its words the description
                    Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!everyEditor && !compact) GlassButton("Performance  ›", { opener = perfFocus; perfFromMore = false; popup = Popup.PERF },
                            behind.fillMaxWidth(), size = ButtonSize.COMPACT, focusRequester = perfFocus)
                        val gear = fit == HeaderFit.SETTINGS_ICON
                        GlassButton(if (gear) "" else settingsText, { opener = moreFocus; popup = Popup.MORE }, behind.fillMaxWidth(),
                            size = ButtonSize.COMPACT, icon = if (gear) Icons.Rounded.Settings else null, iconDescription = GAME_SETTINGS,
                            focusRequester = moreFocus)
                    }
                }
                SideEffect { headerCompact = compact }
            }
            Spacer(Modifier.height(8.dp))
            // ── the controllers: underlined tabs, each with its kind and whose controls it plays (build 18, GC9: "Own" ·
            // "Thor's" · "Plain"); L1 / R1 switch them (D9) from here or the drawing. Out of reach behind a popup ──
            if (tabs.size > 1) {
                val tabFocus = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
                var tabAt by remember { mutableIntStateOf(-1) }
                Row(Modifier.fillMaxWidth().onFocusChanged { onTabs = it.hasFocus }
                    // Left / Right step by index (Compose's own search skipped tabs sideways, build 14); nothing past the ends
                    .onKeyEvent { e ->
                        val dir = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onKeyEvent false }
                        if (e.type == KeyEventType.KeyDown) tabFocus.getOrNull((if (tabAt in tabFocus.indices) tabAt else 0) + dir)?.let { runCatching { it.requestFocus() } }
                        true
                    },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Controls for", color = g.textSecondary, style = MaterialTheme.typography.labelLarge)
                    KeyHint("◀ " + ThorButton.L1.label(hand), wide = true)
                    tabs.forEachIndexed { i, t ->
                        key(t.key) {
                            val tArt = rememberTabArt(t.key, parts = false)
                            val mark = t.key?.let { k -> remember(v, baseKey, k) { PadControls.tab(ctx, baseKey, k).mark } }
                            ControllerTabItem(t, tArt.art, t.key == padKey, mark, behind.weight(1f).onFocusChanged { if (it.isFocused) tabAt = i },
                                tabFocus[i]) { switchTo(t.key) }
                        }
                    }
                    KeyHint(ThorButton.R1.label(hand) + " ▶", wide = true)
                }
                Spacer(Modifier.height(6.dp))
            }
            // ── whose controls this controller plays here: PadControls.tab, the very answer the engine sends. Not on the Thor's
            // tab (docked: one line about the controller playing as the Thor's) ──
            // build 18 (GC6): the chip keeps the engine's answer (a borrowed controller really plays plain buttons until the
            // app is set up); the notices below read [layerState]
            val playsPlain = !everyEditor && PadLayerCtl.need(appPkg) == false
            val layerOff = !everyEditor && layerState == LayerState.OFF
            if (padTab != null) {
                val chip = GameTabRules.chip(padTab, everyEditor, madeHere?.takeIf { it.first == padTab.editKey }?.second, appLabel,
                    label, tab.away, playsPlain)
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlyphText(chip.text, Modifier.weight(1f), color = g.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    for (p in chip.pills) GlassButton(p.label, {
                        claim()
                        val name = tab.name
                        when (p.action) {
                            ChipAction.MAKE_OWN, ChipAction.ONLY_FOR -> {
                                val cameBack = padTab.switchedOff
                                val k = PadControls.makeOwn(ctx, baseKey, padTab.pad)
                                madeHere = if (cameBack) null else k to copiedFrom(padTab)
                                Overlays.pill(GameWords.madeOwn(name, label, cameBack), CueOrigin.Window(myDisplayId))
                            }
                            // D7: kept, switched off — what comes back is in the pill's own words. Build 18 (GC1, decision
                            // H4): a copy made here from the Thor's by itself, only its face changed, is deleted instead
                            ChipAction.USE_BELOW -> padTab.editKey?.let { k ->
                                if (PadControls.useBelow(ctx, k, baseKey, padTab.below)) {
                                    madeHere = null
                                    Overlays.pill(GameWords.backToThors(name, padTab.shown.face != null), CueOrigin.Window(myDisplayId))
                                } else Overlays.pill(GameWords.switchedOff(name, label), CueOrigin.Window(myDisplayId))
                            }
                        }
                    }, behind, size = ButtonSize.COMPACT)
                }
                Spacer(Modifier.height(6.dp))
            } else ExtPadsCtl.seatPad()?.let { d ->                   // dev build 18f: docked, or by its switch
                GlyphText(GameWords.seatLine(d.name, ExtPadsCtl.seatDocked), Modifier.padding(horizontal = 6.dp), color = g.textSecondary,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
            }
            // the input layer off for the app: the Thor's controls do nothing there — on the Thor's tab and a controller's using
            // them (its own or plain buttons run in its reader whatever the layer); not in a controller's every-game editor
            if (layerOff && (padKey == null || borrowed)) {
                // dev build 17 (WP8): a danger notice with its button (out of reach behind a popup). WP9: "Game controls" (glossary)
                GlassNotice(if (padKey == null) "Game controls are off in $appLabel, so these changes do nothing now."
                    else "Game controls are off in $appLabel: the Thor's controls don't reach ${tab.name} — it plays its plain buttons.",
                    NoticeKind.DANGER, Modifier.fillMaxWidth(), action = "Turn it on for $appLabel",
                    onAction = { PadLayerCtl.setForApp(ctx, appPkg, true) }, actionModifier = behind)
                Spacer(Modifier.height(8.dp))
            }
            // build 18 (GC6, T18-01 / FTU-02): a game not set up yet in "Games you set up" — a calm line, no red, no button: the
            // first change on the Thor's tab sets it up and starts Game controls (a controller's own change doesn't: its copy
            // runs in its reader, so its tab says where the change that starts them is made)
            if (layerState == LayerState.PENDING && (padKey == null || borrowed)) {
                GlassNotice(if (padKey == null) "Your first change turns Game controls on for $appLabel."
                    else "A change on the Thor's tab turns Game controls on for $appLabel.", NoticeKind.INFO, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            // ── the controller, centred: the only thing to navigate ──
            GlassPanel(Modifier.fillMaxSize(), radius = Radii.Panel, strong = true) {
                // a near-solid wash inside the panel: the wallpaper showing through made pills look focused
                Column(Modifier.fillMaxSize().background(g.wash, RoundedCornerShape(Radii.Panel)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // build 18 (GC11, gd-D02): this game's face positions aren't the ones printed on the controller — a note by its
                    // face buttons says which one confirms (the drawing stays the physical controller; a pad of unknown print: none)
                    val printedXbox = if (tab.kind == ControllerKind.THOR) false else tab.kind.print?.xboxPositions
                    val faceNote = if (everyEditor || printedXbox == null || effective.xboxPositions == printedXbox) null
                        else GameWords.facePositions(if (effective.xboxPositions && facePs) FaceStyle.PLAYSTATION else effective, look)
                    PadArtCanvas(art,
                        Modifier.weight(1f).fillMaxWidth()
                            .onFocusChanged { mapFocused = it.isFocused }
                            .padArtInput(art, selId, mapFocus, enabled = !open, onSelect = { selId = it; selMoved = true }, onOpen = { openSel(it) }),
                        remap = remap, sel = sel.id, focused = mapFocused && !open, inspector = inspector, gyro = gyroPill,
                        borrowed = borrowed, faint = tabArt.faint, look = look, faceNote = faceNote)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // dev build 16 (WP6): what to press — the controller in hand's buttons, drawn (S4)
                        val legendStyle = MaterialTheme.typography.bodySmall
                        // build 18 (GC3): the Home that cancels, as the controller in hand's badge (any press is captured)
                        if (live != null || picking) HandGlyphText(live ?: "Press the control you want · ${Say.key(ThorButton.HOME)} cancels",
                            Modifier.weight(1f), color = g.accent, style = legendStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else {
                            val inline = rememberButtonInline(legendStyle, look = hand)
                            val hints = buildAnnotatedString {
                                appendButton(Vocab.menuPos(ThorButton.A, hand), hand); append(" change")
                                // dev build 16 review (THOR-1): no "reset" offered where it would do nothing
                                if (!nothingToReset()) { append("   ·   "); appendButton(Vocab.menuPos(ThorButton.Y, hand), hand); append(" reset") }
                                if (tabs.size > 1) { append("   ·   "); appendButton(ThorButton.L1, hand); appendButton(ThorButton.R1, hand); append(" controller") }
                                // build 18 (GC11, gd-D20): the Thor's "back edge" sentence is a note on its drawing now
                            }
                            Text(hints, Modifier.weight(1f), color = g.textSecondary, style = legendStyle, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, inlineContent = inline)
                        }
                        // 1.4 (§10.5): change a button; the count when there is one (More is in the header since dev build 16)
                        GlassButton(if (picking) "Cancel" else "Change a button", { picking = !picking }, behind, size = ButtonSize.COMPACT)
                        if (remap.changes > 0) GlassButton("${remap.changes} change${if (remap.changes > 1) "s" else ""}  ›",
                            { opener = changesFocus; popup = Popup.CHANGES }, behind, size = ButtonSize.COMPACT, focusRequester = changesFocus)
                        // dev build 17 (WP11, D11): a game's page — its app settings, below (the D-pad down goes there too); the
                        // bottom screen's compact header: the arrow alone. Build 18 (GC4, G16): always "App settings" (it
                        // changed name with the app's)
                        if (toApp != null) {
                            val words = "App settings"
                            GlassButton(if (headerCompact) "" else words, { toApp() }, behind, size = ButtonSize.COMPACT,
                                icon = Icons.Rounded.ExpandMore, iconDescription = words)
                        }
                    }
                }
            }
        }
        // ── the popup, over the whole screen ── dev build 16 (WP7): a layer — B (on its release) closes it, deeper than the
        // page (Game controls never finishes on a popup's B), and a list open in it closes first; the D-pad direction's
        // editor goes back to the D-pad, Chords / Presets to More (1.4: opened from More)
        if (open) BackLayer(onBack = { popup = when {
            popup == Popup.BIND && dirSel != null -> { lastDir = dirSel; Popup.DPAD }            // build 18 (GC2, G04)
            popup == Popup.CHORDS || popup == Popup.PRESETS -> Popup.MORE
            popup == Popup.PERF && perfFromMore -> Popup.MORE
            else -> null } }) {
            Box(Modifier.fillMaxSize().background(g.modalDim).pointerInput(Unit) { detectTapGestures { popup = null } },
                contentAlignment = Alignment.Center) {
                // dev build 17 (WP8): the one popup surface (the sheets' too)
                Box(Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.93f)
                    .modalSurface(g, RoundedCornerShape(Radii.Modal))
                    .pointerInput(Unit) { detectTapGestures { } }       // taps inside don't close it
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    when (popup) {
                        Popup.BIND -> BindPopup(sel, look, remap, bindTab, kbPage, { kbPage = it }, listening, popupFirst,
                            slot = slot, onSlot = { slot = it }, recording = recording, onRecord = { recording = !recording },
                            onTab = { bindTab = it; listening = false; recording = false }, onListen = { listening = !listening },
                            setTarget = { t -> (dirSel ?: sel.btn)?.let { bind(it, t) } }, save = ::save, resetAsk = resetAsk, reset = ::resetSel,
                            close = { if (dirSel != null) lastDir = dirSel; popup = if (dirSel != null) Popup.DPAD else null },
                            dir = dirSel, stickPad = tab.takeIf { it.key != null }, canReset = !nothingHere(), note = resetNote)
                        Popup.APPS -> AppsPanel(baseKey, appLabel, popupFirst, onPick = { popup = null; if (it != baseKey) onSwitchApp(it) },
                            onAll = { leavingFromPopup(); popup = null; onAllApps() }) { popup = null }
                        Popup.CHORDS -> ChordsPanel(remap, popupFirst, chordListen, chordPair, onListen = { chordListen = !chordListen },
                            onDone = { chordPair = emptyList(); chordListen = false }, save = ::save) { popup = Popup.MORE }
                        Popup.DPAD -> DpadPopup(remap, popupFirst, ::save, focusDir = lastDir,
                            onEdit = { d -> dirSel = d; bindTab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }) { popup = null }
                        // build 18 (GC6): the gyro's notice reads the page's layer state — a game not set up yet isn't "off"
                        Popup.GYRO -> GyroPopup(remap.gyro, listening, popupFirst, layerState != LayerState.OFF, onListen = { listening = !listening },
                            set = { save(remap.copy(gyro = it)) }, resetAsk = resetAsk, reset = ::resetSel, pkg = baseKey,
                            canReset = !nothingHere(), note = resetNote, close = { popup = null })
                        Popup.PADGYRO -> padKey?.let { k -> PadGyroPopup(k, tab.name, baseKey, label, popupFirst, resetAsk, ::resetSel,
                            canReset = !nothingHere(), note = resetNote) { popup = null } }
                        Popup.CHANGES -> ChangesPanel(remap, popupFirst, ::save, tab.name, padKey != null) { popup = null }
                        Popup.PRESETS -> PresetsPanel(baseKey, remap, popupFirst, ::save, { setFace(FaceStyle.XBOX) }) { popup = Popup.MORE }
                        Popup.PERF -> PerfPanel(baseKey, label, appPkg, appLabel, popupFirst,
                            openPage?.let { o -> { leavingFromPopup(GameTabRules.Opener.PERF); o(HubPage.lightsFor(baseKey)) } },
                            fromMore = perfFromMore) {
                            popup = if (perfFromMore) Popup.MORE else null }
                        Popup.MORE -> MorePanel(remap, popupFirst, moreCtx, onPerf = { perfFromMore = true; popup = Popup.PERF }, onPresets = { popup = Popup.PRESETS },
                            onShare = {
                                popup = null
                                if (shareKey == null || ProfileShare.export(ctx, shareKey) == null) Overlays.pill("Nothing to share yet — map some buttons first", CueOrigin.Window(myDisplayId))
                                else sharingKey = shareKey
                            }, onReset = { save(PadRemap()); popup = null },
                            onShift = { b -> save(remap.copy(shift = b, shifted = if (b == null) emptyMap() else remap.shifted - b)) },
                            onChords = { popup = Popup.CHORDS }, save = ::save,
                            onRemove = { k ->
                                PadControls.remove(k); popup = null
                                Overlays.pill(GameWords.removed(tab.name, label), CueOrigin.Window(myDisplayId))
                                if (everyEditor) onBack()                     // its page reads "Like the Thor's" again
                            },
                            onBringBack = { k -> if (PadControls.bringBack(k)) Overlays.pill(GameWords.broughtBack(tab.name, label), CueOrigin.Window(myDisplayId)); popup = null }) { popup = null }
                        null -> {}
                    }
                }
            }
        }
      }
    }
}

// ── the popup ─────────────────────────────────────────────────────────────

@Composable
private fun BindPopup(
    sel: ArtCtl, look: PadLook, remap: PadRemap, tab: Tab, kbPage: Int, onPage: (Int) -> Unit, listening: Boolean, first: FocusRequester,
    slot: Int, onSlot: (Int) -> Unit, recording: Boolean, onRecord: () -> Unit,
    onTab: (Tab) -> Unit, onListen: () -> Unit, setTarget: (RemapTarget?) -> Unit, save: (PadRemap) -> Unit,
    resetAsk: ConfirmState, reset: () -> Unit, close: () -> Unit, dir: ThorButton? = null, stickPad: TabPad? = null,
    /** build 18 (GC10, gd-D16): something to reset (else no Reset button: [note] says Y found nothing) */
    canReset: Boolean = true, note: String? = null,
) {
    val g = LocalGlass.current
    val src = dir ?: sel.btn ?: return
    // 1.3: a D-pad direction edits like a button ("D-pad down becomes"). dev build 16 (WP6): in the tab's names
    val title = dir?.spoken(look)?.replaceFirstChar { it.uppercase() } ?: sel.title(look)
    val fire = remap.fire[src] ?: Fire.NORMAL
    val onAlt = fire.hasAlt && slot == 1
    // the Shift layer is for buttons (while it's held the D-pad goes to Wayfinder as an axis, not presses)
    val shiftBtn = remap.shift?.takeIf { it != src && !src.isDpad }
    val onShift = shiftBtn != null && slot == 2
    val cur = if (onShift) remap.shifted[src] else if (onAlt) remap.alt[src] else remap.buttons[src]
    // while listening, only the listening matters: everything else fades, the hints go
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val stickJob = when (src) { ThorButton.L3 -> remap.jobL; ThorButton.R3 -> remap.jobR; else -> null }
        // dev build 17 (WP8): Close on the left with the hand's Back button; Reset (Y — or it — asks twice, dev build 16 S7)
        // build 18 (GC4, G15): "Back" for a D-pad direction's editor (B goes back to the D-pad popup), else "Close"
        // build 18 (GC10, ps-F13): the edited button once — in the title (no ringed glyph before it, no "Now: ○ — as usual"),
        // the title further from the Close pill (its hint is a button badge too); Reset only with something to reset (gd-D16)
        ModalHeader(if (onShift) "$title with ${shiftBtn!!.spoken(look)} held" else if (onAlt) "$title — ${fire.label.lowercase()}"
            else if (stickJob != null) title else "$title becomes", close, closeLabel = if (dir != null) "Back" else "Close", closeVisible = !listening,
            status = if (stickJob != null && !onAlt && !onShift) "Now: ${stickJob.summary.lowercase()} · click → ${cur?.short(look) ?: src.label(look)}"
                else if (cur == null) (if (onAlt || onShift) "Now: nothing yet" else "Now: as usual") else "Now: ${cur.short(look)}",
            leading = { Spacer(Modifier.width(0.dp)) }) {
            ResetOrNote(listening, canReset, note, reset, resetAsk, first)
        }
        // a long / double press button has two outputs: which one the tabs edit
        if (fire.hasAlt || shiftBtn != null) {
            val main = remap.buttons[src]?.short(look) ?: src.label(look)
            // the visible slots: its press, its 2nd action, its Shift layer (hold-to-shift §6l: the same pickers)
            val slots = listOfNotNull(0, 1.takeIf { fire.hasAlt }, 2.takeIf { shiftBtn != null })
            GlassSegmentedControl(slots.map { s -> when (s) {
                0 -> "Press  →  $main"
                1 -> "${fire.label}  →  ${remap.alt[src]?.short(look) ?: "pick it"}"
                else -> "With ${shiftBtn!!.spoken(look)} held  →  ${remap.shifted[src]?.short(look) ?: "pick it"}"
            } }, slots.indexOf(slot), rest.fillMaxWidth(), collapse = false, captions = slots.map { s -> when (s) {
                0 -> "a quick press"
                1 -> if (fire == Fire.LONG) "held ${ExtEngine.LONG_MS} ms" else "two quick presses"
                else -> "the Shift layer"
            } }) { onSlot(slots[it]) }
        }
        // tabs: L1 / R1 — they look like tabs (an underline), not like choices; named as the controller in hand calls them
        Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!listening) KeyHint("◀ " + ThorButton.L1.label(ButtonNames.handLook), wide = true)
            Tab.values().forEach { t -> TabItem(t.label, t == tab, Modifier.weight(1f)) { onTab(t) } }
            if (!listening) KeyHint(ThorButton.R1.label(ButtonNames.handLook) + " ▶", wide = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                Tab.CONTROLLER -> ControllerTab(src, cur, listening, first, remap, onListen, setTarget, save, stickPad)
                Tab.KEYBOARD -> Box(rest) { KeyboardTab(cur, kbPage, first, onPage, setTarget) }
                Tab.MOUSE -> Column(rest.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("A mouse button or the wheel — for PC games (GameNative) and apps with a cursor")
                    // dev build 17 (WP8): a choice grid, 3 a row — the D-pad enters on the current one
                    GlassSegmentedControl(RemapTarget.MOUSE_NAMES.map { "🖱 $it" }, (cur as? RemapTarget.Mouse)?.b ?: -1, Modifier.fillMaxWidth(),
                        collapse = false, tight = false, columns = 3, focusRequester = first) { setTarget(RemapTarget.Mouse(it)) }
                }
                Tab.ACTIONS -> Box(rest) { ActionPicker((cur as? RemapTarget.Action)?.a, first) { setTarget(RemapTarget.Action(it)) } }
                Tab.MACRO -> MacroTab(cur, first, recording, onRecord, setTarget)
            }
        }
        // what it does when held (not for "Nothing")
        if (tab != Tab.KEYBOARD && tab != Tab.MACRO) Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(if (src == ThorButton.L3 || src == ThorButton.R3) "Its click fires" else "How it fires", Modifier.width(84.dp))
            // dev build 17 (WP8): the segmented look, the captions under the names
            val fires = listOf(Fire.NORMAL to "one press", Fire.TURBO to "rapid presses", Fire.TOGGLE to "stays on",
                Fire.LONG to "hold = 2nd action", Fire.DOUBLE to "twice = 2nd action")
            GlassSegmentedControl(fires.map { it.first.label }, fires.indexOfFirst { it.first == fire }, Modifier.weight(1f),
                collapse = false, captions = fires.map { it.second }) { i ->
                val f = fires[i].first
                save(remap.copy(fire = if (f == Fire.NORMAL) remap.fire - src else remap.fire + (src to f),
                    alt = if (f.hasAlt) remap.alt else remap.alt - src))
                onSlot(if (f.hasAlt) 1 else 0)
            }
        }
    }
}

@Composable
private fun TabItem(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = Radii.Chip, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (active) g.accent else g.textSecondary, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            Box(Modifier.padding(top = 5.dp).width(56.dp).height(4.dp)
                .background(if (active) g.accent else Color.Transparent, RoundedCornerShape(Radii.Hairline)))
        }
    }
}

@Composable
private fun ControllerTab(src: ThorButton, cur: RemapTarget?, listening: Boolean, first: FocusRequester, remap: PadRemap,
                          onListen: () -> Unit, setTarget: (RemapTarget?) -> Unit, save: (PadRemap) -> Unit, stickPad: TabPad? = null) {
    val g = LocalGlass.current
    val look = currentLook()
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    // 1.3: a stick's page is about THE STICK first (what it does, its feel); its click comes after
    val stick = src == ThorButton.L3 || src == ThorButton.R3
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stick) Column(rest, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (src == ThorButton.L3) {
                StickJobRows(remap.jobL, first) { save(remap.copy(jobL = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassSwitchRow("Invert up / down", remap.invertLeftY, { save(remap.copy(invertLeftY = it)) }, Modifier.weight(1f), compact = true)
                    GlassSwitchRow("Invert left / right", remap.invertLeftX, { save(remap.copy(invertLeftX = it)) }, Modifier.weight(1f), compact = true)
                    GlassSwitchRow("Swap with the right stick", remap.swapSticks, { save(remap.copy(swapSticks = it)) }, Modifier.weight(1f), compact = true) }
                StickShapeRows(remap.stickL, stickPad) { save(remap.copy(stickL = it)) }
            } else {
                StickJobRows(remap.jobR, first) { save(remap.copy(jobR = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassSwitchRow("Invert up / down", remap.invertRightY, { save(remap.copy(invertRightY = it)) }, Modifier.weight(1f), compact = true)
                    GlassSwitchRow("Invert left / right", remap.invertRightX, { save(remap.copy(invertRightX = it)) }, Modifier.weight(1f), compact = true)
                    GlassSwitchRow("Swap with the left stick", remap.swapSticks, { save(remap.copy(swapSticks = it)) }, Modifier.weight(1f), compact = true) }
                StickShapeRows(remap.stickR, stickPad) { save(remap.copy(stickR = it)) }
            }
            Spacer(Modifier.height(6.dp))
            FieldLabel("Its click (${src.label(look)}) becomes")
        }
        // the big one: it listens
        FocusableGlass(onClick = onListen, radius = Radii.Row, modifier = Modifier.fillMaxWidth(), focusRequester = if (stick) null else first) {
            Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(Radii.Row)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Text("🎮", fontSize = 20.sp)
                if (listening) HandGlyphText("Listening — press the button ${src.label(look)} should become  ·  ${Say.key(ThorButton.HOME)} cancels",
                    color = g.onAccent, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                else {
                    Text("Listen for a button", color = g.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    KeyHint(ThorButton.A)
                }
            }
        }
        Column(rest, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // several at once (Select + Start): the grid then toggles buttons in and out
            var multi by remember(src) { mutableStateOf(cur is RemapTarget.Buttons) }
            val list = (cur as? RemapTarget.Buttons)?.bs ?: listOf((cur as? RemapTarget.Button)?.b ?: src)
            fun pick(b: ThorButton) {
                if (!multi) { setTarget(RemapTarget.Button(b)); return }
                val n = if (b in list) list - b else (list + b).take(4)
                setTarget(when (n.size) { 0 -> RemapTarget.None; 1 -> RemapTarget.Button(n[0]); else -> RemapTarget.Buttons(n) })
            }
            fun isOn(b: ThorButton) = if (multi) b in list && cur != RemapTarget.None else (cur as? RemapTarget.Button)?.b == b || (cur == null && b == src)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(if (multi) "Pick up to 4 — pressed together" else "Or pick it")
                Spacer(Modifier.weight(1f))
                GlassSwitchRow("Several at once", multi, { on ->
                    multi = on
                    if (!on && cur is RemapTarget.Buttons) setTarget(RemapTarget.Button(cur.bs.first()))
                }, Modifier.width(210.dp), compact = true)
            }
            // dev build 17 (WP8): options (several can be on — not one segmented control): fill + a corner check
            val grid = listOf(ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y, ThorButton.L1, ThorButton.R1,
                ThorButton.L2, ThorButton.R2, ThorButton.L3, ThorButton.R3, ThorButton.SELECT, ThorButton.START)
            grid.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { b -> GlassOption(if (b == src) "${b.label(look)} (default)" else b.label(look), isOn(b), { pick(b) },
                        Modifier.weight(1f), tight = true) }
                }
            }
            // build 18 (GC4, gd-D18): six equal cells, lined up with the rows above; a direction's own cell says "(default)"
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for ((d, name) in listOf(ThorButton.UP to "↑ Up", ThorButton.DOWN to "↓ Down", ThorButton.LEFT to "← Left", ThorButton.RIGHT to "→ Right"))
                    GlassOption(if (d == src) "$name (default)" else name, isOn(d), { pick(d) }, Modifier.weight(1f), tight = true)
                GlassOption("Android Back", (cur as? RemapTarget.Action)?.a == ThorAction.BACK, { setTarget(RemapTarget.Action(ThorAction.BACK)) },
                    Modifier.weight(1f), tight = true)
                GlassOption("Nothing", cur == RemapTarget.None, { setTarget(RemapTarget.None) }, Modifier.weight(1f), tight = true)
            }
            // what belongs to this control
            when (src) {
                ThorButton.L2, ThorButton.R2 -> { FieldLabel("Triggers")
                    GlassSwitchRow("All-or-nothing (no half press)", remap.digitalTriggers, { save(remap.copy(digitalTriggers = it)) },
                        Modifier.fillMaxWidth(), compact = true)
                    if (!remap.digitalTriggers) TriggerRangeRows(remap) { save(it) } }
                else -> {}
            }
        }
    }
}

/**
 * 1.3 — the D-pad's page, laid out as the D-pad itself (a list of four rows wasn't ergonomic).
 * Each arm says what that direction does; PRESS the direction on the D-pad to change it (RemapScreen's
 * key handler), or touch it. X swaps the whole D-pad with the left stick.
 */
@Composable
private fun DpadPopup(remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, focusDir: ThorButton? = null,
                      onEdit: (ThorButton) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    // build 18 (GC2, G04): [first] on the direction whose editor just closed ([focusDir]), else Up
    fun firstOn(d: ThorButton) = first.takeIf { d == (focusDir ?: ThorButton.UP) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // dev build 17 (WP8): Close on the left; the popup opens on the Up arm ([first]). Build 18 (GC4, G15 / gd-D18): "Close"
        // (it is a top popup: B closes it), the short subtitle
        ModalHeader("D-pad", onClose, subtitle = "Press a direction to change it.")
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // the cross (build 18, gd-D18: its arms alone — the empty middle keeps its shape, no typed ✚)
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f)); DirArm(ThorButton.UP, remap, Modifier.weight(1f), firstOn(ThorButton.UP)) { onEdit(it) }; Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DirArm(ThorButton.LEFT, remap, Modifier.weight(1f), firstOn(ThorButton.LEFT)) { onEdit(it) }
                    Box(Modifier.weight(1f).height(96.dp))
                    DirArm(ThorButton.RIGHT, remap, Modifier.weight(1f), firstOn(ThorButton.RIGHT)) { onEdit(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f)); DirArm(ThorButton.DOWN, remap, Modifier.weight(1f), firstOn(ThorButton.DOWN)) { onEdit(it) }; Spacer(Modifier.weight(1f))
                }
            }
            // the whole D-pad
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("The whole D-pad")
                GlassSwitchRow("Swap with the left stick", remap.dpadStick, { save(remap.copy(dpadStick = it)) }, Modifier.fillMaxWidth(),
                    compact = true, trailing = { KeyHint(ThorButton.X) })
                Caption("For old games that only read one of them.")
                val changed = PadRemap.DIRS.filter { remap.buttons.containsKey(it) || remap.fire.containsKey(it) }
                // dev build 17 (WP8): it removes up to four changes — two presses
                if (changed.isNotEmpty()) ConfirmButton("Put all four back", "Press again", {
                    save(remap.copy(buttons = remap.buttons - changed.toSet(), fire = remap.fire - changed.toSet(), alt = remap.alt - changed.toSet()))
                }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            }
        }
        // build 18 (GC4, G14): the hand's buttons drawn as badges (A7 tokens); B closes, as its header says (G15)
        HandGlyphText("◀▶▲▼ change that direction   ·   ${Say.keyMenu(ThorButton.X)} swap   ·   ${Say.keyMenu(ThorButton.B)} close",
            color = g.textTertiary, style = MaterialTheme.typography.labelMedium)
    }
}

/** One arm of the D-pad page: its arrow, its name, and what it does now (accent = changed). */
@Composable
private fun DirArm(d: ThorButton, remap: PadRemap, modifier: Modifier, focus: FocusRequester? = null, onEdit: (ThorButton) -> Unit) {
    val g = LocalGlass.current
    val t = remap.buttons[d]; val f = remap.fire[d]
    val on = t != null || f != null
    val arrow = when (d) { ThorButton.UP -> "↑"; ThorButton.DOWN -> "↓"; ThorButton.LEFT -> "←"; else -> "→" }
    val name = when (d) { ThorButton.UP -> "Up"; ThorButton.DOWN -> "Down"; ThorButton.LEFT -> "Left"; else -> "Right" }
    // dev build 17 (WP8): the filled arm is a "changed" mark (a status, like the drawing's tags) — not the selected look
    FocusableGlass(onClick = { onEdit(d) }, radius = Radii.Row, modifier = modifier, focusRequester = focus) {
        Column(Modifier.fillMaxWidth().height(96.dp).then(if (on) Modifier.padding(2.dp).background(g.accent, RoundedCornerShape(Radii.inset(Radii.Row))) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$arrow  $name", color = if (on) g.onAccent else g.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            GlyphText((t?.short(currentLook()) ?: "as usual") + (f?.let { " · ${it.label.lowercase()}" } ?: ""),
                color = if (on) g.onAccent.copy(alpha = .9f) else g.textSecondary,
                style = MaterialTheme.typography.labelLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 1.3 — a stick's job: itself, or the mouse / wheel / 4 keys (Wayfinder plays it; the game sees it centred). */
@Composable
private fun StickJobRows(j: StickJob, first: FocusRequester? = null, set: (StickJob) -> Unit) {
    FieldLabel("What the stick does")
    val uses = StickUse.values()
    GlassSegmentedControl(uses.map { it.label }, uses.indexOf(j.use), Modifier.fillMaxWidth(), collapse = false,
        captions = uses.map { it.caption }, focusRequester = first) { i ->
        val u = uses[i]
        set(if (u == StickUse.STICK) StickJob() else j.copy(use = u))
    }
    when (j.use) {
        // b18h: Pointer speed is one name and one scale (Say.POINTER_SPEED; the trackpad's is the same curve, PointerSpeed)
        StickUse.MOUSE, StickUse.SCROLL -> Stepper(if (j.use == StickUse.MOUSE) Say.POINTER_SPEED else "Scroll speed", "${j.speed}",
            { set(j.copy(speed = (j.speed - 1).coerceAtLeast(1))) }, { set(j.copy(speed = (j.speed + 1).coerceAtMost(10))) })
        StickUse.KEYS -> GlassSegmentedControl(listOf("Arrow keys", "W A S D"), if (j.wasd) 1 else 0, Modifier.fillMaxWidth(), collapse = false,
            captions = listOf("↑ ↓ ← →", "PC games")) { set(j.copy(wasd = it == 1)) }
        StickUse.STICK -> {}
    }
    // build 18 (GC4): 20 words at most (PageWordsTest) — "the one set below if bigger" is the deadzone row's own line
    if (!j.isDefault) Caption("The game no longer sees this stick. It acts past its deadzone only (at least 10 %): drift moves nothing.")
}

@Composable
private fun ActionPicker(current: ThorAction?, first: FocusRequester, onPick: (ThorAction) -> Unit) {
    // build 18: not "Nothing" (a combo's action) — a button's own "Nothing" is on the Controller tab. Nor "Turn Wayfinder off"
    // (review): one press of a game button, no second press to confirm
    val actions = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.BACK && it != ThorAction.OPEN &&
        it != ThorAction.NOTHING && it != ThorAction.WAYFINDER_OFF }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        actions.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, a ->
                    // dev build 17 (WP8): an option with its line, text on the left
                    GlassOption(a.title, a == current, { onPick(a) }, Modifier.weight(1f), caption = a.description, align = Alignment.Start,
                        focusRequester = if (r == 0 && i == 0) first else null)
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** dev build 16 (WP6): [name] = the tab's controller ([pad]: a connected one — its own parts listed too, no Thor gyro). */
@Composable
private fun ChangesPanel(remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, name: String, pad: Boolean, onClose: () -> Unit) {
    val look = currentLook()
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // dev build 17 (WP8, critique F7): the popup opens on its first change — on Back only when it has none
        // build 18 (GC4, G15): a top popup — "Close"
        ModalHeader("Changes", onClose, closeFocus = first.takeIf { remap.isEmpty })
        if (remap.isEmpty) EmptyState(GameWords.nothingChanged(name, pad))
        else Column(Modifier.focusRequester(first), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        remap.buttons.forEach { (b, t) -> ChangeRow("${b.label(look)}  →  ${t.short(look)}") { save(remap.copy(buttons = remap.buttons - b)) } }
        remap.fire.forEach { (b, f) -> ChangeRow("${b.label(look)}  fires: ${f.label.lowercase()}") { save(remap.copy(fire = remap.fire - b)) } }
        // a controller's own parts (SL / SR, its Home going to the game) and how its reader fires them
        remap.pads.forEach { (c, d) -> ChangeRow("${partName(c, look)}  →  ${remap.partTarget(c)?.short(look) ?: PadRemap.PAD_PARTS[d] ?: "?"}") { save(remap.withPart(c, null)) } }
        remap.padFire.forEach { (c, _) -> remap.partFire(c)?.let { f -> ChangeRow("${partName(c, look)}  fires: ${f.label.lowercase()}") { save(remap.withPartFire(c, null)) } } }
        if (remap.swapSticks) ChangeRow("Sticks swapped") { save(remap.copy(swapSticks = false)) }
        if (remap.invertLeftY) ChangeRow("Left stick inverted") { save(remap.copy(invertLeftY = false)) }
        if (remap.invertRightY) ChangeRow("Right stick inverted") { save(remap.copy(invertRightY = false)) }
        if (remap.invertLeftX) ChangeRow("Left stick: left / right inverted") { save(remap.copy(invertLeftX = false)) }
        if (remap.invertRightX) ChangeRow("Right stick: left / right inverted") { save(remap.copy(invertRightX = false)) }
        if (remap.dpadStick) ChangeRow("D-pad ↔ left stick") { save(remap.copy(dpadStick = false)) }
        if (remap.digitalTriggers) ChangeRow("Triggers all-or-nothing") { save(remap.copy(digitalTriggers = false)) }
        if (!pad && remap.gyro.isOn) ChangeRow("Gyro: ${remap.gyro.summary()}") { save(remap.copy(gyro = GyroSettings())) }
        fun shape(s: StickShape) = listOfNotNull("deadzone ${s.dead} %".takeIf { s.dead != 0 }, "full at ${s.full} %".takeIf { s.full != 100 },
            listOf("", "precise centre", "fast")[s.curve.coerceIn(0, 2)].ifEmpty { null }).joinToString(" · ")
        if (!remap.jobL.isDefault) ChangeRow("Left stick  →  ${remap.jobL.summary}") { save(remap.copy(jobL = StickJob())) }
        if (!remap.jobR.isDefault) ChangeRow("Right stick  →  ${remap.jobR.summary}") { save(remap.copy(jobR = StickJob())) }
        if (!remap.stickL.isDefault) ChangeRow("Left stick: ${shape(remap.stickL)}") { save(remap.copy(stickL = StickShape())) }
        if (!remap.stickR.isDefault) ChangeRow("Right stick: ${shape(remap.stickR)}") { save(remap.copy(stickR = StickShape())) }
        if (remap.trigRanged) ChangeRow("Triggers: ${remap.trigStart}–${remap.trigFull} %") { save(remap.copy(trigStart = 0, trigFull = 100)) }
        remap.shift?.let { sh ->
            ChangeRow("Shift button: ${sh.spoken(look)}") { save(remap.copy(shift = null, shifted = emptyMap())) }
            remap.shifted.forEach { (b, t) -> ChangeRow("${sh.spoken(look)} held + ${b.spoken(look)}  →  ${t.short(look)}") { save(remap.copy(shifted = remap.shifted - b)) } }
        }
        remap.alt.forEach { (b, t) -> ChangeRow("${b.label(look)}  ${remap.fire[b]?.label?.lowercase()}  →  ${t.short(look)}") { save(remap.copy(alt = remap.alt - b)) } }
        remap.chords.forEach { c -> ChangeRow("${c.a.label(look)} + ${c.b.label(look)}  →  ${c.target.short(look)}") { save(remap.copy(chords = remap.chords - c)) } }
        }
    }
}

@Composable
private fun PresetsPanel(pkg: String, remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, xboxFace: () -> Unit, onClose: () -> Unit) {
    // dev build 16 (WP6): the buttons as the tab's controller calls them (RetroArch's "Hold Create + R1" on a DualSense)
    val words = GameWords.presets(currentLook())
    val others = remember { AppConfigStore.configured().filter { it != pkg && AppConfigStore.get(it).remap != null } }
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // dev build 17 (WP8, critique F7): Back on the left; the popup opens on its first preset
        ModalHeader("Presets", onClose, closeLabel = "Back")
        // was "Swap A ↔ B and X ↔ Y" as four remaps: the same thing as the face-button switch,
        // under a second name — now it IS that switch (naming pass 2026-09-25)
        GlassButton(words.face, { xboxFace(); onClose() }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT, focusRequester = first)
        GlassButton("Swap the sticks", { save(remap.copy(swapSticks = true)); onClose() }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
        // round 8: emulator hotkeys — only what each emulator does BY DEFAULT on
        // Android (researched with sources 2026-09-25: RetroArch's keyboard table; the others only open
        // their menu with Back). Chords: Select held + a button; those two wait 60 ms before the game.
        val app = GameProfiles.pkgOf(pkg)
        if (app.startsWith("com.retroarch")) {
            FieldLabel("RetroArch hotkeys — RetroArch's own default keys, nothing to set up in RetroArch")
            GlassButton(words.retroArch, {
                save(remap.copy(shift = ThorButton.SELECT, shifted = remap.shifted + EmuPresets.retroArch)); onClose()
            }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            Caption(words.retroArchNote)
        } else if (EmuPresets.menuByBack.any { app.startsWith(it) }) {
            FieldLabel("Emulator menu")
            GlassButton(words.emuMenu, {
                save(remap.copy(shift = ThorButton.SELECT, shifted = remap.shifted + (ThorButton.START to EmuPresets.menuTarget))); onClose()
            }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            Caption("This emulator has no default save, load or fast-forward keys: set them in its own settings, on any button.")
        }
        GlassButton("Triggers all-or-nothing", { save(remap.copy(digitalTriggers = true)); onClose() }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
        // 1.3: browsers and apps with a cursor (GitHub #9)
        FieldLabel("Web browsing and apps with a cursor")
        GlassButton(words.web, {
            // B → Android Back: Firefox keeps the pad's B for the web's gamepad API, so B did nothing there (1.3 test)
            save(remap.copy(jobL = StickJob(StickUse.MOUSE), jobR = StickJob(StickUse.SCROLL),
                buttons = remap.buttons + (ThorButton.A to RemapTarget.Mouse(0)) + (ThorButton.X to RemapTarget.Mouse(1)) +
                    (ThorButton.B to RemapTarget.Action(ThorAction.BACK)))); onClose()
        }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
        Caption("The D-pad still moves between links. While Wayfinder Keyboard is open, the controller types and these changes pause.")
        if (others.isNotEmpty()) {
            FieldLabel("Copy from another app")
            val pm = LocalContext.current.packageManager
            others.forEach { o -> key(o) {
                val name = remember(o) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(o, 0)).toString() }.getOrDefault(o) }
                val text = "$name  ·  ${(AppConfigStore.get(o).remap?.changes ?: 0).let { "$it change${if (it == 1) "" else "s"}" }}"
                val copy = { AppConfigStore.get(o).remap?.let { save(it) }; onClose() }
                // dev build 17 (WP8): over changes of its own, it replaces them — two presses
                if (remap.isEmpty) GlassButton(text, copy, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
                else ConfirmButton(text, "Press again to replace your ${remap.changes} changes", copy, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            } }
        } else EmptyState("Copy from another app: none has changes yet.")
    }
}



// ── the gyro popup ─────────────────────────────────────────────

private val SENS_STEPS = listOf(.3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 1.2f, 1.4f, 1.6f, 1.8f, 2f, 2.5f, 3f, 3.5f, 4f)
private val VSCALE_STEPS = listOf(.5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
private val DZ_STEPS = listOf(0, 5, 10, 15, 20, 25, 30, 40)

private fun <T : Comparable<T>> List<T>.step(cur: T, up: Boolean): T {
    val i = indexOfFirst { it >= cur }.let { if (it < 0) size - 1 else it }
    return this[(if (up) (if (this[i] > cur) i else i + 1) else i - 1).coerceIn(0, size - 1)]
}

@Composable
private fun GyroPopup(gyro: GyroSettings, listening: Boolean, first: FocusRequester, layerOn: Boolean,
                      onListen: () -> Unit, set: (GyroSettings) -> Unit, resetAsk: ConfirmState, reset: () -> Unit, pkg: String? = null,
                      canReset: Boolean = true, note: String? = null, close: () -> Unit) {
    val g = LocalGlass.current
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    // live preview: the engine reads the gyro for these settings while the popup is open (sends nothing)
    var liveX by remember { mutableStateOf(0f) }; var liveY by remember { mutableStateOf(0f) }; var liveOn by remember { mutableStateOf(false) }
    var calib by remember { mutableStateOf<String?>(null) }
    DisposableEffect(gyro) {
        GyroEngine.onLive = { x, y, on -> liveX = x; liveY = y; liveOn = on }
        GyroEngine.setPreview(gyro)
        onDispose { GyroEngine.setPreview(null); GyroEngine.onLive = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // dev build 17 (WP8): Close on the left; Reset shares Y's two presses
        ModalHeader(NavCards.GC_GYRO.title, close, closeVisible = !listening, status = "Now: ${gyro.summary()}", leading = {
            Box(Modifier.size(32.dp).border(2.5.dp, g.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ScreenRotation, null, tint = g.accent, modifier = Modifier.size(18.dp))
            }
        }) {
            ResetOrNote(listening, canReset, note, reset, resetAsk, first)          // build 18 (GC10, gd-D16)
        }
        // dev build 17 (WP11): its two places from the registry (one was dead: the card left More options)
        if (!layerOn) GlassNotice("Gyro needs Game controls on here: ${Say.path(NavCards.GC_APP_LAYER, from = NavPages.GAME)}, or " +
            "${Say.path(NavCards.LAYER, inWayfinder = true)}.", NoticeKind.DANGER, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // left: what it drives, and when
            Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("What it moves")
                GlassSegmentedControl(GyroMode.values().map { it.label }, gyro.mode.ordinal, rest.fillMaxWidth(), collapse = false,
                    focusRequester = first) { set(gyro.copy(mode = GyroMode.values()[it])) }
                // build 18 (GC10, gd-D17): a sentence — "Off: the game reads the gyro itself, or nothing."
                Text("${gyro.mode.label}: ${gyro.mode.caption}.", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                if (gyro.isOn) {
                    FieldLabel("When it works")
                    GlassSegmentedControl(GyroOn.values().map { it.label }, gyro.on.ordinal, rest.fillMaxWidth(), collapse = false,
                        columns = 3) { i ->
                        val o = GyroOn.values()[i]
                        val b = if (o.trigger && gyro.button != ThorButton.L2 && gyro.button != ThorButton.R2) ThorButton.L2 else gyro.button
                        set(gyro.copy(on = o, button = b))
                    }
                    if (gyro.on.usesButton) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        FieldLabel(if (gyro.on.trigger) "Trigger" else "Button", Modifier.width(62.dp))
                        if (gyro.on.trigger) {
                            val trig = listOf(ThorButton.L2, ThorButton.R2)
                            GlassSegmentedControl(trig.map { it.label }, trig.indexOf(gyro.button), Modifier.weight(2f), collapse = false) {
                                set(gyro.copy(button = trig[it])) }
                            Spacer(Modifier.weight(2f))
                        } else {
                            FocusableGlass(onClick = onListen, radius = Radii.Chip, modifier = Modifier.weight(2.2f)) {
                                Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(Radii.Chip)) else Modifier)
                                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                                    HandGlyphText(if (listening) "Press it… · ${Say.key(ThorButton.HOME)} cancels" else "${gyro.button.label}  ·  press to change",
                                        color = if (listening) g.onAccent else g.textPrimary,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                }
                            }
                            val btns = listOf(ThorButton.L2, ThorButton.R2, ThorButton.L1, ThorButton.R1)
                            GlassSegmentedControl(btns.map { it.label }, btns.indexOf(gyro.button), Modifier.weight(4f).then(rest), collapse = false) {
                                set(gyro.copy(button = btns[it])) }
                        }
                    }
                    GlyphText(when (gyro.on) {
                        GyroOn.ALWAYS -> "On as soon as the game has the controller."
                        GyroOn.HOLD -> "On while ${gyro.button.label} is held — it still does its usual job."
                        GyroOn.TOGGLE -> "Each press of ${gyro.button.label} turns it on or off (a short buzz)."
                        GyroOn.OFF_WHILE_HOLD -> "On, and off while ${gyro.button.label} is held (to re-centre your hands)."
                        GyroOn.TRIGGER_FULL -> "On while ${gyro.button.label} is pulled all the way — aim down sights, then fine-aim."
                        GyroOn.TRIGGER_HALF -> "On from a light pull of ${gyro.button.label}."
                        GyroOn.TOUCH -> "On while a finger rests on either screen — rest a thumb on the bottom screen to aim."
                    // dev build 16 (theme F): the tab's names, this game's own combos ([pkg]; the every-game editor: the usual ones)
                    } + (Say.combo(ThorAction.KEYBOARD, currentLook(), pkg?.takeIf { it != "*" })
                        ?.let { "  $it (keyboard & mouse panel) can switch it off for this game." } ?: ""),
                        color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            // right: how it feels, and a live look
            if (gyro.isOn) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).then(rest), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("How it feels")
                Stepper("Speed", "×${"%.1f".format(gyro.sens)}", { set(gyro.copy(sens = SENS_STEPS.step(gyro.sens, false))) }) {
                    set(gyro.copy(sens = SENS_STEPS.step(gyro.sens, true))) }
                if (gyro.mode != GyroMode.LEFT_STICK) {
                    Stepper("Up / down speed", "×${"%.1f".format(gyro.vScale)}", { set(gyro.copy(vScale = VSCALE_STEPS.step(gyro.vScale, false))) }) {
                        set(gyro.copy(vScale = VSCALE_STEPS.step(gyro.vScale, true))) }
                    GlassSwitchRow("Invert up / down", gyro.invertY, { set(gyro.copy(invertY = it)) }, Modifier.fillMaxWidth(), compact = true)
                }
                FieldLabel("Left / right comes from")
                GlassSegmentedControl(GyroAxis.values().map { it.label }, gyro.axis.ordinal, Modifier.fillMaxWidth(), collapse = false,
                    captions = GyroAxis.values().map { it.caption }) { set(gyro.copy(axis = GyroAxis.values()[it])) }
                FieldLabel("Steadiness (hand shake)")
                GlassSegmentedControl(listOf("Off", "Light", "Strong"), gyro.steady, Modifier.fillMaxWidth(), collapse = false) {
                    set(gyro.copy(steady = it)) }
                if (gyro.mode != GyroMode.MOUSE)
                    Stepper("Game's stick deadzone", "${gyro.gameDeadzone} %", { set(gyro.copy(gameDeadzone = DZ_STEPS.step(gyro.gameDeadzone, false))) }) {
                        set(gyro.copy(gameDeadzone = DZ_STEPS.step(gyro.gameDeadzone, true))) }
                // live: move the Thor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Canvas(Modifier.size(64.dp).background(g.recess.copy(alpha = .05f), RoundedCornerShape(Radii.Chip))) {
                        val c = Offset(size.width / 2, size.height / 2)
                        drawCircle(g.accent.copy(alpha = .25f), size.width * .42f, c, style = Stroke(2f))
                        drawCircle(if (liveOn) g.accent else g.textTertiary, 7f * density,
                            Offset(c.x + liveX * size.width * .42f, c.y + liveY * size.height * .42f))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (liveOn) "Working — move the Thor" else "Waiting for ${if (gyro.on.usesButton) gyro.button.label else "the gyro"}",
                            color = if (liveOn) g.accent else g.textSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(calib ?: "Drifting? Put the Thor down and calibrate.", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    GlassButton(if (calib == "Hold still…") "…" else "Calibrate", {
                        calib = "Hold still…"
                        GyroEngine.calibrateNow { ok -> calib = if (ok) "Calibrated." else "It moved — try again on a table." }
                    }, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}

// ── the keyboard tab ────────────────────────────────────────────────────────────────────────

/** Legacy single key + meta → the keys it stands for (modifiers first). */
private fun RemapTarget.Key.asCodes(): List<Int> = buildList {
    if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
    if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
    if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
    add(code)
}

private fun kc(name: String) = KeyEvent.keyCodeFromString("KEYCODE_$name")
private val ARROWS = setOf("↑", "↓", "←", "→")

@Composable
private fun KeyboardTab(cur: RemapTarget?, page: Int, first: FocusRequester, onPage: (Int) -> Unit, setTarget: (RemapTarget?) -> Unit) {
    val g = LocalGlass.current
    val keys: List<Int> = when (cur) { is RemapTarget.Keys -> cur.codes; is RemapTarget.Key -> cur.asCodes(); else -> emptyList() }
    fun set(list: List<Int>) = setTarget(if (list.isEmpty()) null else RemapTarget.Keys(list, (cur as? RemapTarget.Keys)?.inOrder == true))
    val toggle: (Int) -> Unit = { code -> set(if (code in keys) keys - code else (keys + code).take(6)) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // the chosen keys, in order. dev build 17 (review WP8-1): at least 30 dp, not exactly — the segmented pair's cells
        // need 36 (a fixed 30 cut the lower third of "Together" / "One after another")
        Row(Modifier.heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keys", color = g.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
            if (keys.isEmpty()) Text("Pick one or more keys — held together, pressed in this order",
                color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
            keys.forEachIndexed { i, c ->
                if (i > 0) Text("+", color = g.textSecondary, fontWeight = FontWeight.Bold)
                KeyChip(i + 1, RemapTarget.keyName(c),
                    onMove = { set(keys.toMutableList().apply { removeAt(i); add(if (i > 0) i - 1 else size, c) }) },
                    onRemove = { set(keys - c) })
            }
            Spacer(Modifier.weight(1f))
            if (keys.size > 1) Text("A on a chip: move it · × removes", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
            val inOrder = (cur as? RemapTarget.Keys)?.inOrder == true
            // dev build 17 (WP8): one segmented pair (equal widths, the corner check)
            GlassSegmentedControl(listOf("Together", "One after another"), if (inOrder) 1 else 0, Modifier.width(280.dp), collapse = false,
                tight = true) { if (keys.isNotEmpty()) setTarget(RemapTarget.Keys(keys, it == 1)) }
        }
        // the two pages: L2 / R2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            KeyHint("◀ " + ThorButton.L2.label(ButtonNames.handLook), wide = true)
            GlassSegmentedControl(listOf("Main keyboard", "Arrows, nav & numpad"), page, Modifier.width(340.dp), collapse = false,
                tight = true) { onPage(it) }
            KeyHint(ThorButton.R2.label(ButtonNames.handLook) + " ▶", wide = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(g.recess, RoundedCornerShape(Radii.Control)).padding(6.dp)) {
            if (page == 0) MainKeys(keys, first, toggle) else NavKeys(keys, first, toggle)
        }
    }
}

@Composable
private fun KeyChip(n: Int, name: String, onMove: () -> Unit, onRemove: () -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.background(g.accent, RoundedCornerShape(Radii.Control)), verticalAlignment = Alignment.CenterVertically) {
        FocusableGlass(onClick = onMove, radius = Radii.Control) {
            Row(Modifier.background(g.accent).padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(18.dp).background(g.onAccent.copy(alpha = .28f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("$n", color = g.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(name, color = g.onAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        // (×: one key, added back with one press — not a two-press delete)
        FocusableGlass(onClick = onRemove, radius = Radii.Control) {
            Text("×", color = g.onAccent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(g.accent).padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

/** One key: [w] = its width in key units; chosen keys are filled with their order number. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(label: String, code: Int?, keys: List<Int>, w: Float = 1f,
                                                                focus: FocusRequester? = null, onClick: (Int) -> Unit) {
    val g = LocalGlass.current
    if (code == null) { Spacer(Modifier.weight(w)); return }
    val i = keys.indexOf(code)
    // dev build 17 (WP8): a chosen key = the selected fill; its order number is its mark (no check)
    FocusableGlass(onClick = { onClick(code) }, radius = Radii.KeyCap, modifier = Modifier.weight(w).fillMaxHeight(), focusRequester = focus) {
        Box(Modifier.fillMaxSize().then(if (i >= 0) Modifier.selectedFill(g, RoundedCornerShape(Radii.KeyCap)) else Modifier),
            contentAlignment = Alignment.Center) {
            Text(label, color = if (i >= 0) g.onAccent else g.textPrimary, fontSize = if (label in ARROWS) 18.sp else if (label.length > 4) 10.sp else 12.sp,
                fontWeight = if (i >= 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            if (i >= 0) Text("${i + 1}", color = g.onAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp))
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)

@Composable
private fun MainKeys(keys: List<Int>, first: FocusRequester, t: (Int) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val r = Modifier.weight(1f)
        KeyRow(r) {
            KeyCap("Esc", kc("ESCAPE"), keys, focus = first, onClick = t); KeyCap("", null, keys, .5f, onClick = t)
            for (i in 1..12) {
                KeyCap("F$i", kc("F$i"), keys, onClick = t)
                if (i == 4 || i == 8) KeyCap("", null, keys, .3f, onClick = t)
            }
        }
        KeyRow(r) {
            KeyCap("`", kc("GRAVE"), keys, onClick = t)
            for (d in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")) KeyCap(d, kc(d), keys, onClick = t)
            KeyCap("-", kc("MINUS"), keys, onClick = t); KeyCap("=", kc("EQUALS"), keys, onClick = t)
            KeyCap("⌫ Bksp", kc("DEL"), keys, 2f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Tab", kc("TAB"), keys, 1.5f, onClick = t)
            for (c in "QWERTYUIOP") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap("[", kc("LEFT_BRACKET"), keys, onClick = t); KeyCap("]", kc("RIGHT_BRACKET"), keys, onClick = t)
            KeyCap("\\", kc("BACKSLASH"), keys, 1.5f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Caps", kc("CAPS_LOCK"), keys, 1.75f, onClick = t)
            for (c in "ASDFGHJKL") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap(";", kc("SEMICOLON"), keys, onClick = t); KeyCap("'", kc("APOSTROPHE"), keys, onClick = t)
            KeyCap("Enter", kc("ENTER"), keys, 2.25f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Shift", kc("SHIFT_LEFT"), keys, 2.25f, onClick = t)
            for (c in "ZXCVBNM") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap(",", kc("COMMA"), keys, onClick = t); KeyCap(".", kc("PERIOD"), keys, onClick = t); KeyCap("/", kc("SLASH"), keys, onClick = t)
            KeyCap("Shift", kc("SHIFT_RIGHT"), keys, 2.75f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Ctrl", kc("CTRL_LEFT"), keys, 1.5f, onClick = t); KeyCap("Win", kc("META_LEFT"), keys, 1.25f, onClick = t)
            KeyCap("Alt", kc("ALT_LEFT"), keys, 1.25f, onClick = t); KeyCap("Space", kc("SPACE"), keys, 6.25f, onClick = t)
            KeyCap("Alt", kc("ALT_RIGHT"), keys, 1.25f, onClick = t); KeyCap("Win", kc("META_RIGHT"), keys, 1.25f, onClick = t)
            KeyCap("Menu", kc("MENU"), keys, 1.25f, onClick = t); KeyCap("Ctrl", kc("CTRL_RIGHT"), keys, 1.5f, onClick = t)
        }
    }
}

@Composable
private fun NavKeys(keys: List<Int>, first: FocusRequester, t: (Int) -> Unit) {
    val g = LocalGlass.current
    @Composable fun Cap(text: String) = Text(text, color = g.textSecondary, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // arrows & navigation
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Arrows & navigation")
            val r = Modifier.weight(1f)
            KeyRow(r) { KeyCap("PrtSc", kc("SYSRQ"), keys, focus = first, onClick = t); KeyCap("ScrLk", kc("SCROLL_LOCK"), keys, onClick = t); KeyCap("Pause", kc("BREAK"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Ins", kc("INSERT"), keys, onClick = t); KeyCap("Home", kc("MOVE_HOME"), keys, onClick = t); KeyCap("PgUp", kc("PAGE_UP"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Del", kc("FORWARD_DEL"), keys, onClick = t); KeyCap("End", kc("MOVE_END"), keys, onClick = t); KeyCap("PgDn", kc("PAGE_DOWN"), keys, onClick = t) }
            KeyRow(r) { KeyCap("", null, keys, onClick = t); KeyCap("↑", kc("DPAD_UP"), keys, onClick = t); KeyCap("", null, keys, onClick = t) }
            KeyRow(r) { KeyCap("←", kc("DPAD_LEFT"), keys, onClick = t); KeyCap("↓", kc("DPAD_DOWN"), keys, onClick = t); KeyCap("→", kc("DPAD_RIGHT"), keys, onClick = t) }
        }
        // numpad: + and Enter two keys tall, 0 two keys wide
        Column(Modifier.weight(4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Numpad")
            Row(Modifier.weight(5f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val r = Modifier.weight(1f)
                    KeyRow(r) { KeyCap("NumLk", kc("NUM_LOCK"), keys, onClick = t); KeyCap("/", kc("NUMPAD_DIVIDE"), keys, onClick = t); KeyCap("*", kc("NUMPAD_MULTIPLY"), keys, onClick = t) }
                    KeyRow(r) { for (d in 7..9) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { for (d in 4..6) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { for (d in 1..3) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { KeyCap("0", kc("NUMPAD_0"), keys, 2f, onClick = t); KeyCap(".", kc("NUMPAD_DOT"), keys, onClick = t) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyRow(Modifier.weight(1f)) { KeyCap("−", kc("NUMPAD_SUBTRACT"), keys, onClick = t) }
                    KeyRow(Modifier.weight(2f)) { KeyCap("+", kc("NUMPAD_ADD"), keys, onClick = t) }
                    KeyRow(Modifier.weight(2f)) { KeyCap("Enter", kc("NUMPAD_ENTER"), keys, onClick = t) }
                }
            }
        }
        // media & web
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Media & web")
            val r = Modifier.weight(1f)
            KeyRow(r) { KeyCap("Vol −", kc("VOLUME_DOWN"), keys, onClick = t); KeyCap("Vol +", kc("VOLUME_UP"), keys, onClick = t); KeyCap("Mute", kc("VOLUME_MUTE"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Prev", kc("MEDIA_PREVIOUS"), keys, onClick = t); KeyCap("Play", kc("MEDIA_PLAY_PAUSE"), keys, onClick = t); KeyCap("Next", kc("MEDIA_NEXT"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Stop", kc("MEDIA_STOP"), keys, onClick = t); KeyCap("Web ◀", kc("BACK"), keys, onClick = t); KeyCap("Web ▶", kc("FORWARD"), keys, onClick = t) }
            Spacer(Modifier.weight(2f))
        }
    }
}

// ── the macro tab ─────────────────────────────────────────

private val HOLD_STEPS = listOf(10, 20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 3000, 5000)
private val GAP_STEPS = listOf(0) + HOLD_STEPS

private fun MacroStep.name(look: PadLook): String = when (val o = out) {
    is RemapTarget.Button -> o.b.spoken(look)
    is RemapTarget.Key -> "⌨ " + RemapTarget.keyName(o.code)
    is RemapTarget.Mouse -> "🖱 " + RemapTarget.MOUSE_NAMES.getOrElse(o.b) { "Mouse" }
    else -> "?"
}

@Composable
private fun MacroTab(cur: RemapTarget?, first: FocusRequester, recording: Boolean, onRecord: () -> Unit, setTarget: (RemapTarget?) -> Unit) {
    val g = LocalGlass.current
    val m = cur as? RemapTarget.Macro
    val steps = m?.steps ?: emptyList()
    val repeat = m?.repeat ?: false
    var selStep by remember { mutableStateOf(-1) }
    var addingKey by remember { mutableStateOf(false) }
    var keyPage by remember { mutableStateOf(0) }
    // editing keeps an imported macro off: only the switch below turns it on
    fun set(list: List<MacroStep>, rep: Boolean = repeat) =
        setTarget(if (list.isEmpty()) null else RemapTarget.Macro(list.take(MacroStep.MAX), rep, m?.armed ?: true))
    LaunchedEffect(addingKey, keyPage) { first.requestAfterLayout() }
    if (addingKey) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a key to the macro", color = g.textPrimary, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                GlassSegmentedControl(listOf("Main keyboard", "Arrows, nav & numpad"), keyPage, Modifier.width(340.dp), collapse = false,
                    tight = true) { keyPage = it }
                GlassButton("Cancel", { addingKey = false }, size = ButtonSize.COMPACT)
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(g.recess, RoundedCornerShape(Radii.Control)).padding(6.dp)) {
                val add: (Int) -> Unit = { code -> set(steps + MacroStep(RemapTarget.Key(code))); selStep = steps.size; addingKey = false }
                if (keyPage == 0) MainKeys(emptyList(), first, add) else NavKeys(emptyList(), first, add)
            }
        }
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // An imported macro arrives OFF: it plays nothing until the user has looked at its steps
        if (m != null && !m.armed) FocusableGlass(onClick = { setTarget(m.copy(armed = true)) }, radius = Radii.Row, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("This macro came from a shared file — it's off", color = g.textPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Check its steps below, then turn it on.", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Text("Turn it on", color = g.accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                KeyHint(ThorButton.A)
            }
        }
        // record: presses and their timing, added at the end
        FocusableGlass(onClick = onRecord, radius = Radii.Row, modifier = Modifier.fillMaxWidth(), focusRequester = first) {
            Row(Modifier.fillMaxWidth().then(if (recording) Modifier.background(g.danger, RoundedCornerShape(Radii.Row)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Text("●", color = if (recording) g.onAccent else g.danger, fontSize = 18.sp)
                if (recording) HandGlyphText("Recording — press the buttons, timing is kept  ·  ${Say.key(ThorButton.HOME)} stops",
                    color = g.onAccent, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                else {
                    Text(if (steps.isEmpty()) "Record with the controller" else "Record more (added at the end)", color = g.textPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    KeyHint(ThorButton.A)
                }
            }
        }
        val rest = if (recording) Modifier.alpha(.35f) else Modifier
        // the steps, in order
        Row(rest.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (steps.isEmpty()) EmptyState("No steps yet — record them, or add keys and clicks below.")
            steps.forEachIndexed { i, s ->
                if (i > 0) Text("›", color = g.textSecondary)
                // dev build 17 (WP8): a step is an option (the one being edited: fill + a corner check)
                GlassOption("${i + 1} · ${s.name(currentLook())}", i == selStep, { selStep = if (selStep == i) -1 else i },
                    caption = "${s.hold} ms" + if (s.gap > 0) " · wait ${s.gap}" else "", tight = true)
            }
        }
        // the selected step
        if (selStep in steps.indices) {
            val s = steps[selStep]
            fun put(n: MacroStep) = set(steps.toMutableList().also { it[selStep] = n })
            Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Stepper("Held", "${s.hold} ms", { put(s.copy(hold = HOLD_STEPS.step(s.hold, false))) }) {
                    put(s.copy(hold = HOLD_STEPS.step(s.hold, true))) } }
                Box(Modifier.weight(1f)) { Stepper("Then wait", "${s.gap} ms", { put(s.copy(gap = GAP_STEPS.step(s.gap, false))) }) {
                    put(s.copy(gap = GAP_STEPS.step(s.gap, true))) } }
            }
            Row(rest, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassButton("◀ Earlier", { if (selStep > 0) { set(steps.toMutableList().apply { add(selStep - 1, removeAt(selStep)) }); selStep-- } },
                    Modifier.weight(1f), size = ButtonSize.COMPACT)
                GlassButton("Later ▶", { if (selStep < steps.size - 1) { set(steps.toMutableList().apply { add(selStep + 1, removeAt(selStep)) }); selStep++ } },
                    Modifier.weight(1f), size = ButtonSize.COMPACT)
                // dev build 17 (WP8): deletes a recorded step — two presses. Review WP8-3: the confirmation is for THIS step
                // (another picked within the 3 s: it asks again)
                ConfirmButton("Remove step", "Press again", { set(steps.filterIndexed { i, _ -> i != selStep }); selStep = -1 },
                    Modifier.weight(1f), size = ButtonSize.COMPACT, state = rememberConfirmState(selStep))
            }
        }
        Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Add", Modifier.width(40.dp))
            GlassButton("+ Key", { addingKey = true }, Modifier.weight(1f), size = ButtonSize.COMPACT)
            GlassButton("+ Left click", { set(steps + MacroStep(RemapTarget.Mouse(0))) }, Modifier.weight(1f), size = ButtonSize.COMPACT)
            GlassButton("+ Right click", { set(steps + MacroStep(RemapTarget.Mouse(1))) }, Modifier.weight(1f), size = ButtonSize.COMPACT)
            ConfirmButton("Clear all", "Press again", { set(emptyList()); selStep = -1 }, Modifier.weight(1f), size = ButtonSize.COMPACT)
        }
        GlassSegmentedControl(listOf("Once per press", "Repeats while held"), if (repeat) 1 else 0, rest.fillMaxWidth(), collapse = false,
            captions = listOf("plays to the end", "stops when released")) { if (steps.isNotEmpty()) set(steps, it == 1) }
        val total = steps.sumOf { it.hold + it.gap }
        // build 18 (GC4, VD-10 rule): the Home and Back that stop it named on this tab's controller (badges)
        GlyphText("${steps.size} / ${MacroStep.MAX} steps · ${"%.1f".format(total / 1000f)} s   ·   " +
            "${Say.key(ThorButton.HOME)} or ${Say.key(ThorButton.BACK)} stops a macro. " +
            "Macros can break online games' rules — keep them for offline play.",
            color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

// ── which game (Game controls opened from a game) ─────────────────────

@Composable
private fun AppsPanel(current: String, appLabel: String, first: FocusRequester, onPick: (String) -> Unit, onAll: () -> Unit, onClose: () -> Unit) {
    val v = AppConfigStore.version.intValue
    val appPkg = GameProfiles.pkgOf(current)
    val onScreens = remember { ForegroundAppService.screenGames() }
    // build 18 (GC4, G11): only apps you can open (a launcher entry) — a system package with controls of its own (Google Play
    // services) is left out of the list; its controls are kept
    val pm = LocalContext.current.packageManager
    val others = remember { AppConfigStore.configured().filter { it !in onScreens && AppConfigStore.get(it).let { c -> c.remap != null || c.face != null } &&
        runCatching { pm.getLaunchIntentForPackage(it) != null }.getOrDefault(false) } }
    val games = remember(v) { GameProfiles.forApp(appPkg) }
    val running = GameProfiles.runningIn(appPkg)
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // build 18 (GC4, G15): a top popup — "Close"
        ModalHeader("Which game?", onClose)
        // dev build 17 (WP8, critique F7): the popup opens on its first content, not on Back
        Column(Modifier.focusRequester(first), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // per-game profiles of this app (Mario Kart ≠ Zelda in the same Dolphin)
        if (running != null || games.isNotEmpty()) {
            FieldLabel("Games in $appLabel — each can have its own controls")
            if (running != null && games.none { it.first == GameProfiles.key(appPkg, running.game) })
                GlassButton("+  Controls just for ${running.title}  (playing now — starts as a copy)", {
                    onPick(GameProfiles.create(appPkg, running.game, running.title))
                }, size = ButtonSize.COMPACT)
            AppRow(appPkg, current == appPkg, title = "$appLabel — all its games") { onPick(appPkg) }
            games.forEach { (k, gp) -> key(k) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppRow(appPkg, current == k, title = gp.title + if (running?.let { GameProfiles.key(appPkg, it.game) } == k) "   · playing now" else "",
                            changes = gp.remap?.changes ?: 0) { onPick(k) }
                    }
                    // dev build 17 (WP8): one ConfirmButton per game (its own two presses)
                    ConfirmButton("Delete", "Press again to delete", { if (current == k) onPick(appPkg); GameProfiles.remove(k) },
                        size = ButtonSize.COMPACT)
                }
            } }
        }
        FieldLabel("On the screens now")
        if (onScreens.isEmpty()) EmptyState("No app on the screens.")
        // this app is already listed above with its games
        onScreens.filter { it != appPkg || (running == null && games.isEmpty()) }.forEach { p -> AppRow(p, p == current) { onPick(GameProfiles.activeKey(p)) } }
        if (others.isNotEmpty()) {
            FieldLabel("Other apps with Game controls")
            others.forEach { AppRow(it, it == current) { onPick(it) } }
        }
        // build 18 (GC4, gd-D26): a row that opens Games, like the list above it — not a button of another kind
        GlassListRow("All apps", icon = Icons.Rounded.Apps, end = RowEnd.CHEVRON, onClick = onAll)
        }
    }
}

@Composable
private fun AppRow(pkg: String, current: Boolean, title: String? = null, changes: Int? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val entry by produceState<AppEntry?>(null, pkg) {
        value = withContext(Dispatchers.IO) { runCatching {
            val pm = ctx.packageManager; val ai = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai).toBitmap(72, 72).asImageBitmap() }.getOrNull())
        }.getOrNull() }
    }
    FocusableGlass(onClick = onClick, radius = Radii.Chip, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entry?.icon?.let { Image(it, null, Modifier.size(30.dp).clip(CircleShape)) }
            Text(title ?: entry?.label ?: pkg, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            val n = changes ?: AppConfigStore.get(pkg).remap?.changes
            Text(if (current) "Showing" else if (n != null && n > 0) "$n change${if (n == 1) "" else "s"}" else "",
                color = if (current) g.accent else g.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── chords: two buttons together (phase 3) ────────────────────────────────────

@Composable
private fun ChordsPanel(remap: PadRemap, first: FocusRequester, listening: Boolean, pair: List<ThorButton>,
                        onListen: () -> Unit, onDone: () -> Unit, save: (PadRemap) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val look = currentLook()
    fun add(t: RemapTarget) {
        val a = pair[0]; val b = pair[1]
        save(remap.copy(chords = remap.chords.filterNot { it.has(a) && it.has(b) } + Chord(a, b, t))); onDone()
    }
    // build 18 (GC10, gd-D11): it scrolls (six chords and a new one's buttons don't fit), and fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModalHeader("Chords — two buttons pressed together", onClose, closeLabel = "Back")
        // build 18 (GC4): 20 words at most (PageWordsTest)
        Caption("Press two buttons together for something else (${Say.btn(ThorButton.L3, look)} + ${Say.btn(ThorButton.R3, look)} → " +
            "${Say.btn(ThorButton.SELECT, look)}). Those two then reach the game ${ExtEngine.CHORD_MS} ms late.")
        // dev build 17 (WP8, critique F7): the popup opens on its first chord (or "+ New chord"), not on Back
        Column(Modifier.focusRequester(first), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        remap.chords.forEach { c -> ChangeRow("${c.a.label(look)} + ${c.b.label(look)}   →   ${c.target.short(look)}") { save(remap.copy(chords = remap.chords - c)) } }
        if (pair.size == 2) {
            // the "+ New chord" button that had focus is gone now: focus goes to the first output
            // (nothing had it — the controller had to find its way back, review 2026-09-25)
            val outFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { outFocus.requestAfterLayout() }
            FieldLabel("${pair[0].label(look)} + ${pair[1].label(look)} does…")
            // actions, not choices: buttons
            PadRemap.OUTPUTS.chunked(8).forEachIndexed { ri, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEachIndexed { bi, b -> GlassButton(b.spoken(look), { add(RemapTarget.Button(b)) }, Modifier.weight(1f),
                        size = ButtonSize.COMPACT, focusRequester = if (ri == 0 && bi == 0) outFocus else null) }
                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            FieldLabel("Or a Wayfinder action")
            Box(Modifier.fillMaxWidth().height(150.dp)) { ActionPicker(null, remember { FocusRequester() }) { add(RemapTarget.Action(it)) } }
            GlassButton("Cancel", { onDone() }, size = ButtonSize.COMPACT)
        } else if (remap.chords.size < PadRemap.MAX_CHORDS) {
            FocusableGlass(onClick = onListen, radius = Radii.Row, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(Radii.Row)) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    HandGlyphText(if (listening) "Press the two buttons…  ${pair.joinToString(" + ") { it.label(look) }}   ·   ${Say.key(ThorButton.HOME)} cancels"
                        else "+ New chord: press two buttons", color = if (listening) g.onAccent else g.textPrimary,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        } else Text("${PadRemap.MAX_CHORDS} chords at most — remove one to add another.", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── small parts ──────────────────────────────────────────────────────────

/** Build 18 (GC3, GC4): a line whose button tokens ([Say.key] / [Say.keyMenu]) are the controller in hand's — what cancels a
 *  capture (any controller's press is captured), the popups' own keys — drawn as its badges whatever tab is shown. */
@Composable
private fun HandGlyphText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, style: TextStyle,
                          maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) =
    CompositionLocalProvider(LocalPadLook provides ButtonNames.handLook) {
        GlyphText(text, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
    }

/** Build 18 (GC10, gd-D16): a popup header's Reset (two presses, shared with Y) only when there is something to reset —
 *  else, after a Y, the line saying there was nothing ([note]). Nothing while it listens. Review (GC10): Reset holding the
 *  focus when it goes (its second A, or Y, reset it all) hands it to the popup's first control ([fallback]) — the focus
 *  never goes nowhere (the next A did nothing); a reset with the focus elsewhere moves nothing. */
@Composable
private fun ResetOrNote(listening: Boolean, canReset: Boolean, note: String?, reset: () -> Unit, resetAsk: ConfirmState,
                        fallback: FocusRequester) {
    val g = LocalGlass.current
    // Reset has the focus — kept when it loses it only by leaving (nothing left to reset: [canReset] is already false then)
    var had by remember { mutableStateOf(false) }
    val can by rememberUpdatedState(canReset)
    LaunchedEffect(canReset) { if (!canReset && had) { had = false; fallback.requestAfterLayout() } }
    if (listening) return
    if (canReset) ConfirmButton("Reset", "Press again", reset, Modifier.onFocusChanged { if (it.hasFocus || can) had = it.hasFocus },
        size = ButtonSize.COMPACT, hint = ThorButton.Y, state = resetAsk)
    else if (note != null) Text(note, color = g.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun appLabelOf(ctx: android.content.Context, pkg: String): String = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Share: a file in Download/Wayfinder, or Android's share menu (Discord, mail, Drive…). */
@Composable
private fun ShareChoice(title: String, onSave: () -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    // dev build 16 (WP7): an in-window sheet (was a Dialog window). dev build 17 (WP8): Cancel in the header, on the left
    GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.55f), first = first) {
        ModalHeader("Share the controls for $title", onClose, closeLabel = "Cancel",
            subtitle = "Only these controls go in the file — never Wayfinder's combos.")
        GlassButton("Save to Download / Wayfinder", onSave, Modifier.fillMaxWidth(), focusRequester = first)
        GlassButton("Send with another app…", onSend, Modifier.fillMaxWidth())
    }
}

/** A change (Changes, Chords): the whole row removes it — dev build 17 (WP8): with two presses (it deletes what the user
 *  made), the second within 3 s. */
@Composable
private fun ChangeRow(text: String, onRemove: () -> Unit) {
    val g = LocalGlass.current
    // review WP8-3: the rows are drawn in plain loops — one removed, the next takes its place: a pending confirmation is
    // the change's (its text), never handed on
    val ask = rememberConfirmState(text)
    FocusableGlass(onClick = { ask.press(onRemove) }, radius = Radii.Chip, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphText(text, Modifier.weight(1f), color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
            Text(if (ask.asked) "Press again to remove" else "Remove", color = if (ask.asked) g.danger else g.accent,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Round 8 (2026-09-25): this app's — or this game's — own performance, fan, refresh rate and stick
 * lights, changed in game where you feel them (Home + X). "Usual" = for a game, what its app uses;
 * for an app, the user's own setting. Applied while it runs; the user's own values come back after.
 * dev build 17 (WP11, rule 7): Stick lights is the lights' full editor ([openLights]: opens it on [key]; null in a game:
 * where it is, in words). Review WP11-INGAME-LIGHTS-PATH: their mode is picked here too, as before ([appLabel]: the app's
 * name, for the way to a game's own).
 */
@Composable
private fun PerfPanel(key: String, label: String, appPkg: String, appLabel: String, first: FocusRequester, openLights: (() -> Unit)?,
                      fromMore: Boolean = false, onClose: () -> Unit) {
    val v = AppConfigStore.version.intValue
    val cfg = remember(v, key) { Profiles.get(key) }
    val isGame = GameProfiles.isGame(key)
    fun set(change: (AppConfig) -> AppConfig) {
        Profiles.update(key, change); ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyLights()
    }
    // what lights it gets without its own: a game its app's own, else the user's
    val usualLights = (if (isGame) AppConfigStore.get(appPkg).lights else null) ?: app.wayfinder.lights.LightSettings.global
    // dev build 17 (WP9): "Usual (<value>)" — what it gets without its own: a game its app's, else your own AYN settings
    // (read only: the ones kept while an app's are applied, else the current ones)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mine = remember(v) { PerfProfiles.usual(ctx) }
    val appCfg = if (isGame) AppConfigStore.get(appPkg) else null
    val usualPerf = (appCfg?.perf ?: mine.perf)?.label
    val usualFan = (appCfg?.fan ?: mine.fan)?.label
    val usualHz = (appCfg?.hz ?: mine.hz)?.let { "$it Hz" }
    val usualSecond = when (appCfg?.second) { SecondScreenPolicy.KEEP_ON -> "stays on"; SecondScreenPolicy.BLANK -> "off"; else -> "as it is" }
    val usualFps = if (appCfg?.fps ?: AppSettings.fpsCounter) "shown" else "hidden"
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // build 18 (GC4, G15): "Back" only when B goes back to More (the bottom screen's compact header opens it there)
        // build 18 (GC10, FTU-21): named as the button that opens it ("Performance ›") and NavCards.GC_LIGHTS' section
        ModalHeader("Performance · $label", onClose, closeLabel = if (fromMore) "Back" else "Close")
        // build 18 (GC4, gd-D12): an app's "Usual" is what the quick panel is set to
        Caption(if (isGame) "Only while this game runs. “Usual” = what its app uses (Games), else your own setting."
            else "While $label is on a screen. “Usual” = what the quick panel is set to.")
        // QA2: ClusterTune / Pulse installed → what they manage isn't offered here (compatibility first)
        val tuner = Tuners.installed(androidx.compose.ui.platform.LocalContext.current)
        if (tuner != null) Caption(Tuners.note(tuner))
        // dev build 17 (WP8, critique F7): the popup opens on its first choice, not on Back
        Column(Modifier.focusRequester(first), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // build 18 (GC4, G18 / gd-D12): plain labels; each row's first cell is "Usual (<value>)" — the value in one place
        if (tuner == null) {
        FieldLabel("Performance")
        PerfRow(listOf(Say.usual(usualPerf)) + PerfMode.ayn.map { it.label }, if (cfg.perf == null) 0 else PerfMode.ayn.indexOf(cfg.perf).let { if (it < 0) -1 else it + 1 }) { i ->
            set { it.copy(perf = if (i == 0) null else PerfMode.ayn[i - 1]) } }
        }
        if (tuner != "Pulse") {
        FieldLabel("Fan")
        PerfRow(listOf(Say.usual(usualFan)) + FanMode.values().map { it.label }, (cfg.fan?.ordinal ?: -1) + 1) { i ->
            set { it.copy(fan = if (i == 0) null else FanMode.values()[i - 1]) } }
        FieldLabel("Refresh rate")
        PerfRow(listOf(Say.usual(usualHz), "60 Hz", "120 Hz"), when (cfg.hz) { 60 -> 1; 120 -> 2; else -> 0 }) { i ->
            set { it.copy(hz = when (i) { 1 -> 60; 2 -> 120; else -> null }) } }
        }
        // 1.3 (GitHub #25, #22): the bottom screen and the frame-rate counter, per game too
        // build 18 (GC12, gd-D25): the title names it — the label doesn't repeat it
        FieldLabel("Bottom screen while it's on top")
        PerfRow(listOf(Say.usual(usualSecond), "Keep on", "Off"), cfg.second.ordinal) { i ->
            set { it.copy(second = SecondScreenPolicy.values()[i]) }; ForegroundAppService.reapplyPolicy() }
        FieldLabel("Frame-rate counter")
        PerfRow(listOf(Say.usual(usualFps), "Shown", "Hidden"), when (cfg.fps) { true -> 1; false -> 2; null -> 0 }) { i ->
            set { it.copy(fps = when (i) { 1 -> true; 2 -> false; else -> null }) }; ForegroundAppService.reapplyFps() }
        // dev build 17 (WP11, rule 7): Stick lights is the lights' full editor (colours, brightness) — the way there out of a
        // game. Review WP11-INGAME-LIGHTS-PATH: their mode is picked here again, as in build 16 (spec WP11H P4 / §11-3: Game
        // controls' popups keep editing what they edited — in a game no Hub page opens, and a game's own lights had no
        // other way). The same field LightsPage writes; a mode picked here keeps the colours (its own, else what it gets)
        if (Features.lights) {
            val modes = listOf(app.wayfinder.lights.LightMode.OFF, app.wayfinder.lights.LightMode.STATIC, app.wayfinder.lights.LightMode.BREATHING,
                app.wayfinder.lights.LightMode.SPECTRUM, app.wayfinder.lights.LightMode.SCREEN, app.wayfinder.lights.LightMode.AYN)
            val base = cfg.lights ?: usualLights
            // build 18 (GC12, gd-D27 / T18-05): a game's own lights ("— this game"; its page has "— all of <app>" too)
            FieldLabel(if (isGame) "${NavCards.GC_LIGHTS.title} — this game" else NavCards.GC_LIGHTS.title)
            PerfRow(listOf(Say.usual(usualLights.mode.label)) + modes.map { if (it == app.wayfinder.lights.LightMode.SCREEN) "Screen" else it.label },
                cfg.lights?.let { l -> modes.indexOf(l.mode).let { if (it < 0) -1 else it + 1 } } ?: 0) { i ->
                set { it.copy(lights = if (i == 0) null else base.copy(mode = modes[i - 1])) } }
            if (openLights != null) GlassButton("Colours and brightness  ›", openLights, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            // in a game: where they are — a game's own through its app's tile, then its title's list (no tile of its own)
            else Caption("Colours and brightness: ${Say.path(NavCards.GC_LIGHTS, name = appLabel, inWayfinder = true)}" +
                (if (isGame) ", with $label picked in “$appLabel”." else "."))
        }
        }
        Caption("Two apps on the screens: the more demanding performance, fan and refresh rate win.")
    }
}

/** Build 18 (GC4, G18 / gd-D12): one Performance row — one rhythm on the popup: smaller cells only for a row whose options
 *  can't have 120 dp each (it was by count: Fan and Stick lights small, the others not). */
@Composable
private fun PerfRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        GlassSegmentedControl(options, selected, Modifier.fillMaxWidth(), collapse = false, tight = 120.dp * options.size > maxWidth,
            onSelect = onSelect)
    }
}

/** Round 8 — a stick's shape, in steps (controller-friendly). "Every game" = the all-games value
 *  from the Thor's Test page (dev build 17: its page's "Test it"). */
@Composable
internal fun StickShapeRows(s: StickShape, pad: TabPad? = null, set: (StickShape) -> Unit) {
    val dz = (listOf(0, 4, 6, 8, 10, 12, 15, 20, 25) + s.dead).distinct().sorted()
    // review WP9-6: on a connected controller's tab, its own every-game deadzone (measured on its Test it) is what "Usual"
    // gets when it has one ([ExtRules.withPadDeadzone]) — not docked: playing as the Thor's, the Thor's apply
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val padKey = pad?.key?.takeIf { !pad.docked && ExtPadsCtl.seatPad()?.key != it }       // (dev build 18f: by its switch too)
    val own = padKey?.let { ExtPadsCtl.padSticks(ctx, it) }
    val (l, r) = own ?: (AppSettings.stickDefaults.stickL.dead to AppSettings.stickDefaults.stickR.dead)
    val thors = Say.path(NavCards.TEST_IT, name = NavPages.title(HubPage.THOR))
    val its = pad?.let { Say.path(NavCards.TEST_IT, name = it.name) }
    Caption("Deadzone — hides drift (a stick that moves on its own). " + when {
        own != null -> "Usual = its own, every game: $l % left, $r % right (set in $its)."
        padKey != null -> "Usual = every game's: $l % left, $r % right (set in $thors; its own in $its)."
        else -> "Usual = every game's: $l % left, $r % right (set in $thors)."
    })
    GlassSegmentedControl(dz.map { if (it == 0) Say.usual(null) else "$it %" }, dz.indexOf(s.dead).coerceAtLeast(0), Modifier.fillMaxWidth(),
        collapse = false) { set(s.copy(dead = dz[it])) }
    val full = (listOf(100, 95, 90, 85, 80) + s.full).distinct().sortedDescending()
    FieldLabel("Full at — for a stick that never quite reaches the edge")
    GlassSegmentedControl(full.map { "$it %" }, full.indexOf(s.full).coerceAtLeast(0), Modifier.fillMaxWidth(), collapse = false) {
        set(s.copy(full = full[it])) }
    FieldLabel("Response")
    GlassSegmentedControl(listOf("Linear", "Precise centre", "Fast"), s.curve, Modifier.fillMaxWidth(), collapse = false) { set(s.copy(curve = it)) }
}

/** Round 8 — both triggers' range: nothing below the start, a full pull from the end. */
@Composable
internal fun TriggerRangeRows(r: PadRemap, set: (PadRemap) -> Unit) {
    val start = (listOf(0, 5, 10, 20, 30) + r.trigStart).distinct().sorted()
    val end = (listOf(100, 90, 80, 70, 60) + r.trigFull).distinct().sortedDescending()
    FieldLabel("Starts at — a light touch does nothing")
    GlassSegmentedControl(start.map { if (it == 0) "0 %" else "$it %" }, start.indexOf(r.trigStart).coerceAtLeast(0), Modifier.fillMaxWidth(),
        collapse = false) { set(r.copy(trigStart = start[it])) }
    FieldLabel("Full at — a short pull is enough")
    GlassSegmentedControl(end.map { "$it %" }, end.indexOf(r.trigFull).coerceAtLeast(0), Modifier.fillMaxWidth(), collapse = false) {
        set(r.copy(trigFull = end[it])) }
}

/** Round 8 — emulator presets built only from verified DEFAULTS. */
private object EmuPresets {
    private fun keys(code: Int) = RemapTarget.Keys(listOf(code))
    /** The Shift layer (hold Select) — RetroArch's default keyboard hotkeys. */
    val retroArch = mapOf(
        ThorButton.R1 to keys(android.view.KeyEvent.KEYCODE_F2),       // save state
        ThorButton.L1 to keys(android.view.KeyEvent.KEYCODE_F4),       // load state
        ThorButton.R2 to keys(android.view.KeyEvent.KEYCODE_SPACE),    // fast-forward (toggle)
        ThorButton.START to keys(android.view.KeyEvent.KEYCODE_F1),    // menu
    )
    /** PPSSPP, Dolphin, melonDS, Azahar / Citra / Lime3DS, Lemuroid: Back opens the menu by default. */
    val menuByBack = listOf("org.ppsspp", "org.dolphinemu", "me.magnum.melon", "org.azahar", "org.citra", "io.github.lime3ds", "com.swordfish.lemuroid")
    val menuTarget: RemapTarget = RemapTarget.Action(ThorAction.BACK)
}

/** A connected controller's name from its key (connected now, else from the key itself). */
internal fun padNameOf(key: String): String =
    ExtPadsCtl.pads.values.firstOrNull { it.key == key }?.name ?: if (key == ExtPads.PAIR_KEY) "Joy-Con pair" else key.substringAfter('-').replace('_', ' ').trim()

/**
 * Game controls for every controller (2026-10-03; dev build 16, WP6): the tabs — the Thor, then each connected
 * controller by player ([ExtPadsCtl.byPlayer], one sort order); a page opened on one controller's own controls
 * ([fixedPad]: its every-game editor) has its tab alone, connected or not. Nothing else: RemapScreen draws every tab the
 * same way, from [PadControls.tab] — the engine's very answer. (PadUsesThors, the card a controller's tab showed instead
 * of the editor, is gone: its Player 1 row is in Game settings (the game's section), its "Give it its own controls" the chip's pill.)
 */
@Composable
private fun ControlsRouter(fixedPad: String?, @Suppress("UNUSED_PARAMETER") v: Int): List<TabPad> {
    @Suppress("UNUSED_VARIABLE") val pv = ExtPadsCtl.version.intValue
    val docked = ExtPadsCtl.seatPad()?.key                                 // dev build 18f: docked, or by its switch
    fun of(p: ExtPadsCtl.Pad) = TabPad(p.key, p.name, p.kind, p.number, p.node.removePrefix("hidraw").toIntOrNull() ?: 999, p.away, p.key == docked)
    if (fixedPad != null) return listOf(ExtPadsCtl.pads.values.firstOrNull { it.key == fixedPad }?.let(::of)
        ?: TabPad(fixedPad, padNameOf(fixedPad), ControllerKind.ofKey(fixedPad)))
    return listOf(TabPad(null, "The Thor", ControllerKind.THOR)) + ExtPadsCtl.byPlayer().map(::of)
}

/** dev build 16 (WP6): what More needs about the page and its tab. [compact]: the header left Performance out (here
 *  then); [parts]: the drawing's parts only a connected controller has (SL / SR, its Home going to the game);
 *  [focusParts]: More was opened from one of them. */
private class MoreCtx(val baseKey: String, val appPkg: String, val appLabel: String, val label: String, val everyEditor: Boolean,
                      val compact: Boolean, val tab: TabPad, val padTab: PadTab?, val parts: List<ArtCtl>, val focusParts: Boolean)

/** A controller's own part's name ([PadRemap.pads] codes) in [look]'s names. */
private fun partName(code: Int, look: PadLook): String = PadRemap.BY_CODE[code]?.let { if (it.isDpad) it.spoken(look) else it.label(look) }
    ?: when (code) { PadRemap.HOME_CODE -> Vocab.name(ThorButton.HOME, look); 0x132 -> "SL"; 0x135 -> "SR"; else -> "?" }

/** "Game settings ›" (build 18 round 2, MORE-12: was "More ›"; the compact header's gear): the less frequent things, off
 *  the drawing (the "after" mockup, 2026-09-26). dev build 16 (WP6):
 *  in the header, the same panel on every tab — "This game" (Player 1, the input layer, the controller when it moves;
 *  not in a controller's every-game editor) and "These controls" (chords, presets, share, the shift button, a
 *  controller's other buttons, reset, and on a controller's tab: Remove / Bring back, D7 / critique B5). */
@Composable
private fun MorePanel(remap: PadRemap, first: FocusRequester, m: MoreCtx, onPerf: () -> Unit, onPresets: () -> Unit, onShare: () -> Unit,
                      onReset: () -> Unit, onShift: (ThorButton?) -> Unit, onChords: () -> Unit, save: (PadRemap) -> Unit,
                      onRemove: (String) -> Unit, onBringBack: (String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val look = currentLook()
    @Suppress("UNUSED_VARIABLE") val v = AppConfigStore.version.intValue + PadProfiles.version.intValue + ExtPadsCtl.version.intValue
    val t = m.padTab
    // a controller's tab with nothing of its own: nothing to share or reset (it uses the Thor's, or its plain buttons)
    val nothingOwn = t != null && t.editKey == null
    val partsFirst = remember { FocusRequester() }
    // one frame after the popup's own first focus (dev build 16, WP7: frames, not 250 ms)
    LaunchedEffect(m.focusParts) { if (m.focusParts && m.parts.isNotEmpty()) { withFrameNanos { }; partsFirst.requestAfterLayout() } }
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // build 18 (GC4, G15 + MORE-12): a top popup — "Close"; titled as the paths and the header's button name it (NavCards'
        // section "Game settings")
        ModalHeader(GAME_SETTINGS, onClose)
        // dev build 17 (WP8, critique F7): the popup opens on its first control, not on Back
        Column(Modifier.focusRequester(first), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── this game (a controller's every-game editor has none: its page owns that) ──
        if (!m.everyEditor) {
            // build 18 (GC10, gd-D14): the section names say the scope — a game's own (its name), then all of its app; an app's
            // settings under its name alone ("This game" sat over app-wide settings)
            val isGame = GameProfiles.isGame(m.baseKey)
            SectionHeader(if (isGame) m.label else m.appLabel)
            // build 18 (GC10, FTU-21): named as the header's button and the popup's title
            if (m.compact) GlassButton("Performance  ›", onPerf, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            // player 1 while this game (or app) has the controller — a game profile's own, else its app's, else the usual order
            // (dev build 16, WP6: moved here from the controller tab's card)
            val own1 = if (isGame) GameProfiles.get(m.baseKey)?.extFirst else AppConfigStore.get(m.baseKey).extFirst
            if (ExtPadsCtl.pads.isNotEmpty() || own1 != null) {
                // dev build 17: the registry's name. Build 18 (GC12, gd-D25): the section above names the game
                FieldLabel(NavCards.GC_PLAYER1.title)
                val usual1 = ExtPadsCtl.orderFor(ctx, if (isGame) m.appPkg else null).let { if (it) "a connected controller" else "the Thor" }
                GlassSegmentedControl(listOf(Say.usual(usual1), "A connected controller", "The Thor"), when (own1) { null -> 0; true -> 1; false -> 2 },
                    Modifier.fillMaxWidth(), collapse = false) { i ->
                    val v1 = when (i) { 1 -> true; 2 -> false; else -> null }
                    if (isGame) GameProfiles.get(m.baseKey)?.let { GameProfiles.set(m.baseKey, it.copy(extFirst = v1)) }
                    else AppConfigStore.update(m.baseKey) { it.copy(extFirst = v1) }
                }
                // dev build 15 (C5): a running game keeps its controllers — the order changes at its next start
                Caption("Usual = ${Say.path(NavCards.ORDER)}. Applies when the game starts — a running game keeps its controllers.")
            }
            // dev build 17 (theme G, D10): a controller without motion of its own, the Thor's gyro on for this game — it stays the
            // Thor's (what the Thor's gyro follows: the game's controls, else its app's)
            if (m.padTab != null && !m.tab.kind.gyro && PadControls.thorConfigFor(m.baseKey).remap?.gyro?.isOn == true)
                Caption(GameWords.thorMotionStays(m.tab.name))
            // build 18 (GC10, gd-D14): a game's page — what follows is all of its app's
            if (isGame) SectionHeader("All of ${m.appLabel}")
            // 1.4 (GitHub #60): the input layer for this app — Off gives it the Thor's own controller. dev build 16 (WP4): keyed
            // on the app, never "*"
            // dev build 17 (WP11): its name is the registry's — the paths to it say the same (its caption names the app)
            FieldLabel(NavCards.GC_APP_LAYER.title)
            val layer = AppConfigStore.get(m.appPkg).layer
            // dev build 17 (WP9): an inherited value is "Usual (<value>)" — what the setting for every app gives this one
            val usualLayer = when (PadLayerCtl.mode) {
                PadLayerCtl.Mode.EVERYWHERE -> true; PadLayerCtl.Mode.NOWHERE -> false; PadLayerCtl.Mode.SET_UP -> PadLayerCtl.isSetUp(m.appPkg) }
            GlassSegmentedControl(listOf(Say.usual(if (usualLayer) "on" else "off"), "On", "Off"), when (layer) { null -> 0; true -> 1; false -> 2 }, Modifier.fillMaxWidth(),
                collapse = false) { i -> PadLayerCtl.setForApp(ctx, m.appPkg, when (i) { 1 -> true; 2 -> false; else -> null }) }
            Caption(when (layer) {
                false -> "Off: ${m.appLabel} gets the Thor's own controller — for games that don't react to the copy. These controls do nothing there."
                true -> "On for ${m.appLabel}, whatever the setting for other apps."
                null -> "Usual: ${PadLayerCtl.mode.label.lowercase()} (${Say.path(NavCards.LAYER)})."
            })
            // 1.4: per game — the controller goes with it when it's moved (hold Back), or stays
            FieldLabel("When it moves to the other screen")                // build 18 (GC12, gd-D25): the section names it
            val follow = AppConfigStore.get(m.appPkg).followMove
            // review WP9-2: what it inherits, as the service decides it — Always top / bottom: it stays, whatever the
            // (kept, hidden) "Follows a game you move" switch says
            val lockedPut = AppSettings.focusLockEnabled && !AppSettings.focusSticky
            val usualFollow = !lockedPut && AppSettings.focusFollowsMove
            GlassSegmentedControl(listOf(Say.usual(if (usualFollow) "goes with it" else "stays"), "Goes with it", "Stays"),
                when (follow) { null -> 0; true -> 1; false -> 2 }, Modifier.fillMaxWidth(),
                collapse = false) { i -> AppConfigStore.update(m.appPkg) { it.copy(followMove = when (i) { 1 -> true; 2 -> false; else -> null }) } }
            Caption(when {
                lockedPut -> "The controller is set to Always top / bottom (${Say.path(NavCards.WHICH_SCREEN)}): it stays."
                follow == null -> "Usual: " + (if (AppSettings.focusFollowsMove) "it goes with it" else "it stays") + " (${Say.path(NavCards.WHICH_SCREEN)})."
                follow -> "Move or swap ${m.appLabel} (hold Back): the controller goes to its new screen."
                else -> "The controller stays on its screen when ${m.appLabel} moves."
            })
        }
        // ── these controls ──
        SectionHeader(NavCards.GC_BRING_BACK.section!!.substringAfterLast(Nav.SEP))   // (the pills send here by that name: B5's Bring back)
        // build 18 (GC10, gd-D13): rows that open a popup — the name, its value, the chevron at the right edge (centred buttons
        // with "›" mid-label and the description in parentheses read as something else)
        GlassListRow("Chords", value = (if (remap.chords.isNotEmpty()) "${remap.chords.size} · " else "") + "two buttons at once",
            end = RowEnd.CHEVRON, onClick = onChords)
        GlassListRow("Presets", value = "web, emulator hotkeys, copy from an app", end = RowEnd.CHEVRON, onClick = onPresets)
        if (!m.everyEditor) {
            if (nothingOwn) Caption(GameWords.nothingOwn("share", t!!.source))
            else GlassListRow("Share these controls", end = RowEnd.CHEVRON, onClick = onShare)
        }
        // hold-to-shift (§6l): opt-in, off by default (2026-09-26)
        FieldLabel("Shift button — hold it to give every button a second job")
        val opts = listOf<ThorButton?>(null) + PadRemap.SHIFTS
        GlassSegmentedControl(opts.map { it?.spoken(look) ?: "Off" }, opts.indexOf(remap.shift).coerceAtLeast(0), Modifier.fillMaxWidth(),
            collapse = false) { onShift(opts[it]) }
        Caption(if (remap.shift == null) "Off: every button works as usual."
            else "Hold ${remap.shift.spoken(look)}: buttons do only their “With ${remap.shift.spoken(look)} held” job (each button's page). " +
                "${remap.shift.spoken(look)} alone reaches the game late.")
        // dev build 16 (WP6, critique m10): the parts only a connected controller has — what each becomes, how it fires (its
        // reader runs them; keys, actions and macros on them: build 17)
        if (m.parts.isNotEmpty()) {
            FieldLabel("Its other buttons")
            m.parts.forEachIndexed { i, c ->
                val code = (c.src as ArtSrc.Part).code
                val dests = PadRemap.OUTPUTS
                val cur = remap.part(code)
                val odd = cur != null && cur != 0xffff && PadRemap.BY_CODE[cur] == null      // SL / SR / Home: no position
                val names = listOf("Itself") + dests.map { it.spoken(look) } + "Nothing" + listOfNotNull(cur?.takeIf { odd }?.let { PadRemap.PAD_PARTS[it] ?: "?" })
                val at = when {
                    cur == null -> 0
                    cur == 0xffff -> dests.size + 1
                    odd -> dests.size + 2
                    else -> dests.indexOf(PadRemap.BY_CODE[cur]) + 1
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1.4f).then(if (i == 0) Modifier.focusRequester(partsFirst) else Modifier)) {
                        GlassSegmentedControl(names, at, Modifier.fillMaxWidth(), collapse = true, label = "${c.title(look)} becomes") { n ->
                            val d = when { n == 0 -> null; n <= dests.size -> PadRemap.outCode(dests[n - 1]); n == dests.size + 1 -> 0xffff; else -> cur }
                            save(remap.withPart(code, d))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        GlassSegmentedControl(listOf("Normally", "Turbo", "Toggle"), when (remap.partFire(code)) { Fire.TURBO -> 1; Fire.TOGGLE -> 2; else -> 0 },
                            Modifier.fillMaxWidth(), collapse = true, label = "It fires") { n -> save(remap.withPartFire(code, n.takeIf { it > 0 })) }
                    }
                }
            }
        }
        if (nothingOwn) Caption(GameWords.nothingOwn("reset", t!!.source))
        else {
            // nothing changed: a press does nothing (as before)
            ConfirmButton("Reset all", "Press ${Say.menu(ThorButton.A)} again to reset everything", onReset, Modifier.fillMaxWidth(),
                size = ButtonSize.COMPACT, enabled = !remap.isEmpty)
            if (remap.isEmpty) Caption(GameWords.nothingChanged(m.tab.name, t != null))
        }
        // a controller's own controls at this page's level: Remove (two presses; kept for Bring back) — D7's other half, the
        // chip's "Use the Thor's again" only switches them off
        if (t != null) {
            val pageKey = PadProfiles.key(m.baseKey, t.pad)
            if (PadProfiles.has(pageKey)) ConfirmButton("Remove its own controls for ${m.label}", "Press again to remove them", { onRemove(pageKey) },
                Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            // critique B5: what a change or Remove replaced here is kept — never a silent store. dev build 16 review (WP4-1):
            // several kept: each press brings the next earlier one (what was there goes last in the line)
            val kept = PadControls.keptCount(pageKey)
            if (kept > 0) GlassButton("${NavCards.GC_BRING_BACK.title} its earlier controls for ${m.label}" + (if (kept > 1) "  ($kept kept · again = one further back)" else ""),
                { onBringBack(pageKey) }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
        }
        }
    }
}

/** dev build 15 (WP2): Game controls' face buttons for this game — positions only, through the one [FaceStyleControl]; X
 *  switches them (on the drawing). Behind an open popup it's out of the controller's reach. Build 18 (GC8): every tab's
 *  "Usual (<its usual>)" | the other style ([GameTabRules.faceOptions]) — [labelOf] names them, [width] narrower in a compact
 *  header, else one width on every tab. */
@Composable
private fun GameFacePills(choices: List<FaceChoice>, selected: FaceChoice, labelOf: (FaceChoice) -> String, focusable: Boolean,
                          width: Dp = 200.dp, onPick: (FaceChoice) -> Unit) {
    // build 18 (GC8, gd-D03): [width] = the hint and the pills (+ the panel's 4 dp padding a side); the X / □ shortcut sits 6 dp
    // left of the group — it was glued to the last segment and read as one of its words
    Row(Modifier.width(width + 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyHint(ThorButton.X)
        GlassPanel(Modifier.weight(1f), radius = Radii.Row, strong = true) {
            Box(Modifier.padding(4.dp)) {
                FaceStyleControl(choices, selected, labelOf, Modifier.fillMaxWidth(), itemsFocusable = focusable, compact = true, onPick = onPick)
            }
        }
    }
}

/** dev build 16 (WP6): one controller's tab — a small drawing of its kind, its name, whose controls it plays ([mark]: build 18,
 *  GC9 — "Own" · "Thor's" · "Plain" on a hairline capsule, never ● ○ –), the underline as the popup's [TabItem]. */
@Composable
private fun ControllerTabItem(t: TabPad, art: PadArt, active: Boolean, mark: String?, modifier: Modifier, focus: FocusRequester, onClick: () -> Unit) {
    val g = LocalGlass.current
    // build 18 (GC9, gd-D07): its icon ([PadArt.iconPath]: the Thor's clamshell, a pad's body)
    val icon = remember(art.iconPath) { androidx.compose.ui.graphics.vector.PathParser().parsePathString(art.iconPath).toPath() }
    FocusableGlass(onClick = onClick, radius = Radii.Chip, modifier = modifier, focusRequester = focus) {
        Column(Modifier.fillMaxWidth().padding(top = 7.dp, start = 6.dp, end = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val ink = if (active) g.accent else g.textSecondary
                Canvas(Modifier.size(20.dp, 14.dp)) {
                    val p = icon
                    val b = p.getBounds()
                    if (b.width <= 0f || b.height <= 0f) return@Canvas
                    val k = minOf(size.width / b.width, size.height / b.height)
                    withTransform({
                        translate((size.width - b.width * k) / 2 - b.left * k, (size.height - b.height * k) / 2 - b.top * k)
                        scale(k, k, Offset.Zero)
                    }) { drawPath(p, ink, style = Stroke(1.5.dp.toPx() / k)) }
                }
                Text(t.name + if (t.away) " · away" else "", color = ink, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                mark?.let {
                    Text(it, color = g.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.border(1.dp, g.textTertiary.copy(alpha = .5f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
                }
            }
            Box(Modifier.padding(top = 5.dp).width(56.dp).height(4.dp)
                .background(if (active) g.accent else Color.Transparent, RoundedCornerShape(Radii.Hairline)))
        }
    }
}

/** dev build 16 (WP6, critique M4, D10): a controller's motion aim in this game — its page's (Usual), or its own here
 *  (ExtPadsCtl.gyroFor / setGyroFor: the controller's, like its face — never part of its controls). A controller's
 *  every-game editor edits its page's value. Y / Reset (two presses) = back to Usual. Nintendo kinds only. */
@Composable
private fun PadGyroPopup(pad: String, name: String, base: String, label: String, first: FocusRequester, resetAsk: ConfirmState,
                         reset: () -> Unit, canReset: Boolean = true, note: String? = null, close: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val every = base == PadProfiles.EVERY
    val page = ExtPadsCtl.gyro(ctx, pad)
    val here = ExtPadsCtl.gyroAt(ctx, pad, base)
    fun set(n: ExtPadsCtl.Gyro?) = ExtPadsCtl.setGyroFor(ctx, pad, base, n)
    // build 18 (GC10, gd-D11): the popup fades at an edge with more beyond
    val scroll = rememberScrollState()
    Column(Modifier.scrollFade(top = scroll.canScrollBackward, bottom = scroll.canScrollForward).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // dev build 17 (WP8): Close on the left; Reset shares Y's two presses
        ModalHeader("Motion aim · $name", close, status = "Now: ${GameWords.motion(here ?: page)}",
            subtitle = "Turn and tilt $name to aim with a stick. Put it down a second after choosing: it learns its rest.",
            leading = {
                Box(Modifier.size(32.dp).border(2.5.dp, g.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ScreenRotation, null, tint = g.accent, modifier = Modifier.size(18.dp))
                }
            }) { ResetOrNote(false, canReset, note, reset, resetAsk, first) }        // build 18 (GC10, gd-D16)
        Box(Modifier.focusRequester(first)) {
            if (every) PadMotionRows(page) { set(it) }
            else GlassSegmentedControl(listOf("Usual (its page: ${GameWords.motion(page)})", "For $label"), if (here == null) 0 else 1,
                Modifier.fillMaxWidth(), collapse = false) { i ->
                if (i == 0) set(null) else if (here == null) set(page)             // starts as its page's aim
            }
        }
        if (!every) {
            if (here != null) PadMotionRows(here) { set(it) }
            else Caption("Its page's motion aim works here (${Nav.place(NavPages.CONTROLLER_PAGE, name)}).")
        }
    }
}

/** dev build 16 (WP6): a controller's motion aim — its page's card (Controller → it) and Game controls' motion popup (one
 *  game's own). */
@Composable
internal fun PadMotionRows(gy: ExtPadsCtl.Gyro, set: (ExtPadsCtl.Gyro) -> Unit) {
    val g = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassSegmentedControl(listOf("Off", "Left stick", "Right stick"), gy.stick, Modifier.fillMaxWidth()) { i ->
            set(ExtPadsCtl.Gyro(i, gy.sens, gy.flags))
        }
        if (gy.stick != 0) {
            Text("Sensitivity ${gy.sens}", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            app.wayfinder.ui.GlassSlider((gy.sens - 1) / 49f, step = 1f / 49f) { f ->
                val n = (1 + Math.round(f * 49)).coerceIn(1, 50)
                if (n != gy.sens) set(ExtPadsCtl.Gyro(gy.stick, n, gy.flags))
            }
            GlassSegmentedControl(listOf("Normal", "Left-right reversed", "Up-down reversed", "Both"), gy.flags and 3,
                Modifier.fillMaxWidth(), label = "Direction") { i -> set(ExtPadsCtl.Gyro(gy.stick, gy.sens, (gy.flags and 4) or i)) }
            GlassSegmentedControl(listOf("Turn = sideways", "Turn = up-down"), if (gy.flags and 4 != 0) 1 else 0,
                Modifier.fillMaxWidth()) { i -> set(ExtPadsCtl.Gyro(gy.stick, gy.sens, (gy.flags and 3) or (if (i == 1) 4 else 0))) }
        }
    }
}
