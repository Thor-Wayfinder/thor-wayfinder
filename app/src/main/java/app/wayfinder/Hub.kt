@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.wayfinder

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.StatusPill
import app.wayfinder.ui.VSpace
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.Caption
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.FieldLabel
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassNotice
import app.wayfinder.ui.GlassSwitchRow
import app.wayfinder.ui.HexColorField
import app.wayfinder.ui.ModalHeader
import app.wayfinder.ui.NoticeKind
import app.wayfinder.ui.PageHeader
import app.wayfinder.ui.Radii
import app.wayfinder.ui.focusId
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.FocusRole
import app.wayfinder.ui.LocalSegmentHint
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.TokenArgb
import app.wayfinder.ui.selectedFill
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.reportingFocusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.wayfinder.ui.fillWell
import app.wayfinder.ui.glassSurface
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import rikka.shizuku.Shizuku

// Hub navigation: the routes ([HubPage], [hubParent]) are in HubRoutes.kt (dev build 16: pure, the JVM tests load them).
// B / Back always goes up one level.

/** Where Back (the page's button or B) goes from [page]: the page a link came from, else one level up. Also sets the
 *  row to focus there. */
fun hubBack(page: String): String {
    val rf = app.wayfinder.ui.ReturnFocus
    // build 18 (B12, pF21): a page no row opened (a deep link, a quick panel tile) → the row there that leads here. b18r3
    // (r1 BUG-2): its id from the registry (Nav.rowTo — the rows draw the same), not the page's name: Combos' row is "combos"
    rf.pending = rf.opener[page] ?: Nav.rowTo(page); rf.goingBack = true
    val to = rf.from[page] ?: hubParent(page) ?: HubPage.HOME
    // build 18 (B2, hF5): back to Search from a result — it opens with its query, on that result, keyboard closed
    if (to == HubPage.SEARCH) SearchState.returning = true
    return to
}

/** Build 18 (B0, for C6): the scroll of the [SubPage] being shown — a page's in-place layer can scroll the page to the top. */
val LocalPageScroll = androidx.compose.runtime.compositionLocalOf<androidx.compose.foundation.ScrollState?> { null }

/** Build 18 (B12, pF21): the Hub card [page] is under — its chain's page right below Home ("lights" → "more"). */
private fun hubTop(page: String): String {
    var p = page
    while (true) { val up = hubParent(p) ?: return p; if (up == HubPage.HOME) return p; p = up }
}

@Composable
fun HubNavHost(myDisplayId: Int, page: String, pad: String? = null, go: (String) -> Unit) {
    // 1.4: a row opened this page → Back focuses it again (forgotten if it isn't on the parent page)
    LaunchedEffect(page) {
        val rf = app.wayfinder.ui.ReturnFocus
        val prev = rf.shown; rf.shown = page
        if (!rf.goingBack) {
            // a row on a page that isn't this one's parent opened it (Help & status → Stick lights): Back goes there. Build 18
            // (B2, hF5): Search's results too — Back from the page a result opened goes back to Search (was: the Hub)
            if (rf.clicked != null && prev != null && prev != page && prev != hubParent(page)) rf.from[page] = prev
            else rf.from.remove(page)
            // build 18 (B12, pF21): entered by a deep link or a quick panel tile (no row opened it) — Back up its chain ends on
            // the Hub card of its branch (Stick lights from the panel › More › the More card); not the first-run tour
            if (rf.clicked == null && page != HubPage.HOME && page != HubPage.TOUR && !page.startsWith("play:")) lastHubCard = hubTop(page)
            // b18r3 (r1 BUG-2): and no row opened it this time — an earlier visit's row (Help's Combos row) is no longer the
            // way back: Back focuses the registry's row there (hubBack, Nav.rowTo)
            if (rf.clicked == null) rf.opener.remove(page)
        }
        rf.goingBack = false
        app.wayfinder.ui.ReturnFocus.clicked?.let { app.wayfinder.ui.ReturnFocus.opener[page] = it }
        app.wayfinder.ui.ReturnFocus.clicked = null
        // dev build 16 (WP7): the page shown has taken its row by now (ReturnFocus.take, in its composition) — never
        // left for a later page (was: forgotten after 1.5 s)
        rf.pending = null
        // dev build 17 (WP11): so has its "More options" (search opens it) — a page without one never leaves it set for a
        // later page (was: a list of the pages that have one, in SearchPage)
        MoreOptionsState.openNext = false
        NavTarget.expand = null                     // dev build 17 (WP9): a link's card, likewise
        // dev build 16 (theme E, device tests S6 / S9): the page shown — its kind only, no package or controller
        if (BuildConfig.DEBUG) android.util.Log.d("ThorHub", "page ${page.substringBefore(':')}")
    }
    val back = { go(hubBack(page)) }
    when {
        page == HubPage.SCREENS -> ScreensPage(myDisplayId, back, go)
        // dev build 17 (WP11): the list of controllers, the Thor first; each one's page is ONE template (ControllerPage.kt)
        page == HubPage.CONTROLLER -> ControllerListPage(myDisplayId, back, go)
        page == HubPage.THOR -> ControllerDetailPage(myDisplayId, CtlRef.Thor, back, go)
        page == HubPage.CONTROLS -> ControlsScreen(myDisplayId, onBack = back, go = go)
        page.startsWith("controls:") -> ControlsScreen(myDisplayId, pkg = page.removePrefix("controls:"), onBack = back)
        // dev build 17 (WP11, D11): a tile opens the game's page — the same page Home + X opens in the game
        page == HubPage.APPS -> AppProfilesScreen(myDisplayId, onOpen = { go(HubPage.gameFor(it)) }, onBack = back)
        page.startsWith("remap:") -> GamePage(myDisplayId, page.removePrefix("remap:"), inGame = false, openedBy = null, onBack = back,
            onSwitchApp = { go(HubPage.remapFor(it)) }, onAllApps = { go(HubPage.APPS) }, go = go)
        page.startsWith("play:") -> {
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            // b18i (leftovers): the window is marked Game controls by MainActivity.becomes(), from the page it shows. A mark here
            // was undone by its onDispose first when the page changed: "All apps" never reached becomes(), the session never ended
            // dev build 16 (WP6): opened on the tab of the controller that pressed Home + X ([pad]). dev build 17 (WP11): the
            // game's page, as from the Games tile — its rows that would leave it say where they are instead (K13)
            GamePage(myDisplayId, page.removePrefix("play:"), inGame = true, openedBy = pad,
                onBack = { act?.finish(); ForegroundAppService.gameControlsClosed() },
                onSwitchApp = { go(HubPage.playFor(it)) }, onAllApps = { go(HubPage.APPS) }, go = go)
        }
        page == HubPage.SHOTS -> ScreensPage(myDisplayId, back, go)
        page == HubPage.APPEARANCE -> AppearancePage(myDisplayId, back)
        page == HubPage.HELP -> HelpPage(myDisplayId, back, go)
        page == HubPage.KEYBOARD -> KeyboardPage(myDisplayId, back)
        page == HubPage.LIGHTS -> LightsPage(myDisplayId, null, back)
        page == HubPage.PAIRS -> PairsPage(myDisplayId, back)
        page == HubPage.SLEEP -> SleepPage(myDisplayId, back)
        page == HubPage.BATTERY -> BatteryPage(myDisplayId, back, go)
        // build 18 (B13, MORE-14): leaving or finishing goes back to where the tour was opened (Help, on "Take the tour"); the
        // first-run tour (nothing opened it) still ends on the Hub
        page == HubPage.TOUR -> TourPage(myDisplayId) { go(hubBack(HubPage.TOUR)) }
        page == HubPage.PANEL -> PanelShortcutsPage(myDisplayId, back, go)
        page == HubPage.PADTEST -> ControllerTestPage(myDisplayId, null, back)
        page.startsWith("padtest:") -> ControllerTestPage(myDisplayId, page.removePrefix("padtest:"), back)    // dev build 17 (WP11)
        page == HubPage.EXTPADS -> ExtPadsPage(myDisplayId, back)
        // dev build 16 (theme E): each controller's page is a route of its own (was drawn inside Other controllers)
        page.startsWith("extpad:") -> ExtPadPage(myDisplayId, page.removePrefix("extpad:"), back, go)
        page == HubPage.LICENSES -> LicensesPage(myDisplayId, back)
        page.startsWith("lights:") -> LightsPage(myDisplayId, page.removePrefix("lights:"), back)
        page == HubPage.SEARCH -> SearchPage(myDisplayId, back, go)
        page == HubPage.MORE -> MorePage(myDisplayId, back, go)
        page == HubPage.SOUND -> SoundPage(myDisplayId, back)
        page == HubPage.HINTS -> HintsPage(myDisplayId, back)
        page == HubPage.FEATURES -> FeaturesPage(myDisplayId, back, go)
        else -> HubHome(myDisplayId, go)
    }
}

private fun spanFor(displayId: Int) = if (displayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM

// ─────────────────────────────────────────────────────────────────────────────
// Live status shared by Home and Help.
// ─────────────────────────────────────────────────────────────────────────────

private class HubStatus(
    val running: Boolean = false,
    val batteryOptimized: Boolean = false,
    val pservice: Boolean = false,
    val shizuku: Boolean = false,
    val root: Boolean = false,
    val displayApps: Map<Int, String> = emptyMap(),
    val displayIds: List<Int> = emptyList(),
    /** Build 18 (GitHub #83): the user turned Wayfinder off ("Turn Wayfinder off"). */
    val offByUser: Boolean = false,
) { val backend get() = pservice || shizuku || root; val ready get() = running && backend }

@Composable
private fun rememberHubStatus(): HubStatus {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(HubStatus()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                s = HubStatus(
                    running = ForegroundAppService.isRunning,
                    batteryOptimized = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
                    pservice = PServiceBridge.cachedAvailable(),
                    shizuku = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
                    root = RootHelper.cachedAvailable(),
                    displayApps = ForegroundAppService.displayApps.toMap(),
                    displayIds = ForegroundAppService.availableDisplayIds.ifEmpty {
                        ctx.getSystemService(DisplayManager::class.java).displays.map { it.displayId }
                    },
                    offByUser = Setup.offByUser(ctx),
                )
                delay(2000)
            }
        }
    }
    return s
}

// ─────────────────────────────────────────────────────────────────────────────
// Home: header + search, quick actions, menu grid — fits one screen.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HubHome(myDisplayId: Int, go: (String) -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val st = rememberHubStatus()
    val firstFocus = remember { FocusRequester() }
    val setupFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val open: (String) -> Unit = { page -> lastHubCard = page; go(page) }
    LaunchedEffect(Unit) { UpdateCheck.check(ctx) }      // 1.3.1: at most once a day
    // the setup button when it's shown (it's what needs doing), else the card last opened (1.3: back from a page, the card
    // that opened it — by its page's id), else the first. dev build 16 (WP7): after the first layout, no 350 ms timer.
    // Build 18 (B2): the Search button is one of its ids (back from Search: on it) — the first card is named, so the header,
    // composed first, never becomes the entry
    val running = remember { ForegroundAppService.isRunning }
    val cards = app.wayfinder.ui.rememberEntryFocus(first = if (running) firstFocus else setupFocus,
        returnKey = if (running) lastHubCard else null)
    // build 18 (B5, hF6): Up from the first row of cards goes to Search (it landed on Ko-fi: one A left Wayfinder) — unless
    // something to press sits between them (the setup button, a warning, the setup question): then the D-pad's own way
    val noticesShown = remember { booleanArrayOf(false, false, false) }
    val upToSearch = Modifier.focusProperties {
        up = if (!st.running || !Features.asked || noticesShown.any { it }) FocusRequester.Default else searchFocus
    }
    remember { app.wayfinder.ui.ReturnFocus.take() }      // Home has no rows: a row to come back to is not for a later page
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + AppConfigStore.version.intValue + ExtPadsCtl.version.intValue

    GlassScreen(span = spanFor(myDisplayId)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: title · status · search
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text("Wayfinder", color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    Text("For the AYN Thor", color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
                }
                StatusPill(if (st.ready) "Ready" else if (!st.running && st.offByUser) "Off" else "Setup needed", st.ready)
                // 1.3.1 — a newer version on GitHub: a small orange-dot pill that opens the release page
                // (the same pill as "Ready" — shape, padding, dot, text — only it can be pressed)
                if (UpdateCheck.available != null) {
                    // build 18 (B5): the row's first button while it shows — left stays here (as Ko-fi's, QA2)
                    FocusableGlass(onClick = { openLink(ctx, UpdateCheck.RELEASES_URL, myDisplayId) }, radius = Radii.Control,
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // dev build 17 (WP8): the theme's amber (was an orange of its own)
                            Box(Modifier.size(8.dp).background(g.warn, CircleShape))
                            Text("Update available", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.weight(1f))
                // discreet support link (2026-09-26): small, quiet, opens Ko-fi in the browser. Build 18 (B5, hF6): the
                // same words, smaller — reached only by Left from Search
                FocusableGlass(onClick = { openKofi(ctx, myDisplayId) }, radius = Radii.Tile,
                    // the row's first button: left stays here (it dropped into the tiles below — QA2); the update pill, when
                    // it shows, is left of it
                    modifier = Modifier.focusProperties { left = if (UpdateCheck.available != null) FocusRequester.Default else FocusRequester.Cancel }) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Coffee, null, tint = g.textTertiary, modifier = Modifier.size(16.dp))
                        // 1.4 (GitHub #66): with a large font the words pushed the search box off the screen
                        if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.05f)
                            Text("Support me on Ko-fi", color = g.textTertiary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                // build 18 (B2, hF4): its id is its page's — back from Search, the focus is on it again (lastHubCard)
                androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides cards) {
                FocusableGlass(onClick = { SearchState.query = ""; SearchState.returning = false; open(HubPage.SEARCH) }, radius = Radii.Tile,
                    focusRequester = searchFocus, focusId = HubPage.SEARCH) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = g.accent, modifier = Modifier.size(20.dp))
                        Text(if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.05f) "Search settings & features" else "Search",
                            color = g.textTertiary, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, softWrap = false)
                    }
                }
                }
            }

            // Setup (only when something's missing). Build 18 (GitHub #83): turned off on purpose — the same press turns it back on
            if (!st.running) GlassActionButton(
                // build 18 (B20, FTU-18): the tour's and Help's words
                if (st.offByUser) "Turn Wayfinder back on" else "Turn on Wayfinder's service", Icons.Rounded.Accessibility, Modifier.fillMaxWidth(),
                subtitle = if (st.offByUser) "You turned it off — one press turns it back on"
                    else "Required — sees which app is on each screen and reads the controller; nothing leaves the Thor",
                focusRequester = setupFocus,
            ) { Setup.enableService(ctx) }
            // build 18 (B5): each says whether it shows (Up from the cards then stops at it, not at Search)
            SetupFlagWarning(shown = { noticesShown[0] = it }) { runCatching { firstFocus.requestFocus() } }
            AynHomeWarning(shown = { noticesShown[1] = it })
            OtherAppsWarning(shown = { noticesShown[2] = it }) { runCatching { firstFocus.requestFocus() } }   // the card is gone: focus moves on
            // Option B (2026-09-25): one live status strip instead of the intro line —
            // the tour explains Wayfinder; this says what it's doing right now.
            // 1.4: the setup question takes the status strip's place until it's answered (both rows of cards stay on screen)
            if (Features.asked) StatusStrip(myDisplayId)

            // No quick actions here on purpose: while the Hub is open it IS the top app
            // (Wayfinder never swaps itself), so swap/recents/screenshot would be
            // meaningless. Actions live on combos, gestures and the Control Deck.

            // 1.4: after an update (or a skipped tour) — Wayfinder can be made simpler, once
            if (!Features.asked) SetupChoiceCard()

            // Menus — 1.4: six doors, named by what you want to do; a part turned off
            // (Features) has no door. dev build 16 (WP7): the cards are the page's entry (the first one = Screens)
            androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides cards) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuCard(NavPages.title(HubPage.SCREENS), screensSummary(), Icons.Rounded.Tv, Modifier.weight(1f).then(upToSearch), firstFocus, HubPage.SCREENS) { open(HubPage.SCREENS) }
                MenuCard(NavPages.title(HubPage.CONTROLLER), controllerSummary(), Icons.Rounded.SportsEsports, Modifier.weight(1f).then(upToSearch), null, HubPage.CONTROLLER) { open(HubPage.CONTROLLER) }
                MenuCard(NavPages.title(HubPage.APPS), appsSummary(), Icons.Rounded.Apps, Modifier.weight(1f).then(upToSearch), null, HubPage.APPS) { open(HubPage.APPS) }
                // what it draws, charging and sleep — their own door
                MenuCard(NavPages.title(HubPage.BATTERY), batterySummary(), Icons.Rounded.BatteryChargingFull, Modifier.weight(1f).then(upToSearch), null, HubPage.BATTERY) { open(HubPage.BATTERY) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // dev build 17 (WP11): always there — off, it says so (its page turns the AYN button back to the panel)
                MenuCard(NavPages.title(HubPage.PANEL), panelSummary(ctx), Icons.Rounded.Dashboard, Modifier.weight(1f), null, HubPage.PANEL) { open(HubPage.PANEL) }
                MenuCard(NavPages.title(HubPage.KEYBOARD), keyboardSummary(LocalContext.current), Icons.Rounded.Keyboard, Modifier.weight(1f), null, HubPage.KEYBOARD) { open(HubPage.KEYBOARD) }
                // build 18 (review): turned off on purpose, it says so (the status above reads "Off")
                MenuCard(NavPages.title(HubPage.MORE), if (!st.ready) (if (!st.running && st.offByUser) "Help: Wayfinder is off" else "Help: something needs setup")
                    else moreSummary(), Icons.Rounded.MoreHoriz,
                    Modifier.weight(1f), null, HubPage.MORE) { open(HubPage.MORE) }
            }
            }
        }
    }
}

/** The Hub card last opened (its page), so going back puts the focus on it again. */
private var lastHubCard: String? = null

const val KOFI_URL = "https://ko-fi.com/thorwayfinder"

/** The Ko-fi page in the browser, on the screen the Hub is on. */
/** 1.3.1 — a web page in the browser, on [displayId]. */
fun openLink(ctx: android.content.Context, url: String, displayId: Int) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }.onFailure { Overlays.pill("No browser to open the page", CueOrigin.Window(displayId)) }
}

fun openKofi(ctx: android.content.Context, displayId: Int) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }.onFailure { Overlays.pill("No browser to open ko-fi.com/thorwayfinder", CueOrigin.Window(displayId)) }
}

@Composable
private fun MenuCard(title: String, summary: String, icon: ImageVector, modifier: Modifier, focus: FocusRequester? = null,
                     id: String, onClick: () -> Unit) {
    val g = LocalGlass.current
    // 126 dp: both rows fit the top screen with the status strip, no scrolling (Option B). dev build 16 (WP7): [id] = its
    // page (Back from it comes back here)
    FocusableGlass(onClick = onClick, modifier = modifier.height(126.dp), radius = Radii.Large, focusRequester = focus, focusId = id) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = g.accent, modifier = Modifier.size(30.dp))
            Column {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                // build 18 (B5, D10 / hF19): two lines (one cut "Brightness · bottom screen · co…")
                Text(summary, color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// Live one-line summaries for the menu cards.
private fun screensSummary(): String = "Brightness · bottom screen · pairs"

private fun batterySummary(): String = "What it draws · charging · sleep"

// dev build 17 (WP11): the page is the list of controllers (where the controller is and how it moves: the status strip).
// Build 18 (B22, FTU-15): the combos first, always — the page's main door ("12 combos · 2 controllers")
private fun controllerSummary(): String {
    val n = ControlsStore.active().size
    val pads = ExtPadsCtl.byPlayer().size
    val combos = if (n == 1) "1 combo" else "$n combos"
    return if (pads == 0) "$combos · the Thor" else "$combos · ${pads + 1} controllers"
}

/** Option B's live strip: where the controller is, the input layer, the apps on the screens,
 *  brightness. Information only (not focusable) — each part has its page among the tiles. */
@Composable
private fun StatusStrip(myDisplayId: Int) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var bright by remember { mutableStateOf<String?>(null) }
    val tickOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {       // only while the Hub is on screen (it ran in the background — review 2026-09-25)
        tickOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { while (true) { delay(1500); tick++ } }
    }
    // brightness: asked of the root helper (its "Q", milliseconds — no process started), and
    // only while this page is actually on screen: it polled through root every 6 s for as long
    // as the Hub stayed in the background, screen off included (review 2026-09-25)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                val ids = listOfNotNull(0, ForegroundAppService.availableDisplayIds.firstOrNull { it != 0 })
                ScreenLevels.readAsync(ctx, ids) { m ->
                    val p = ids.mapNotNull { d -> m[d]?.let { ScreenLevels.percent(it) } }
                    bright = when { p.isEmpty() -> null; p.distinct().size == 1 -> "${p[0]} %"; else -> "top ${p[0]} % · bottom ${p[1]} %" }
                }
                delay(6000)
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val screen = ForegroundAppService.controllerScreen()
    val where = when (screen) { null -> "—"; 0 -> "Top screen"; else -> "Bottom screen" }
    // build 18 (B5, D10): the same words as the combos sheet (Say.controllerMode — was "stays where sent" / "locked")
    val how = Say.controllerMode()
    // The input layer is an internal: said only when it ISN'T working (critique 2026-09-25) —
    // otherwise that cell shows the battery. Build 18 (B5, hF7): only when it's real — not when you put another controller
    // first (Player order) or play docked (the docked controller is player 1)
    val chosenOrder = ExtPadsCtl.orderList(ctx).indexOf("thor") > 0 || ExtPadsCtl.dockedPad() != null
    val layerOk = (PadLayerCtl.wanted && PadLayerCtl.active && (PadLayerCtl.controllerNumber <= 1 || PadLayerCtl.compatMode)) ||
        !PadLayerCtl.wanted ||   // 1.4: off on purpose (Nowhere, only the games you set up, or off for this app)
        chosenOrder
    val layerProblem = when {
        layerOk -> null
        !PadLayerCtl.wanted -> "Off — game controls paused"
        // build 18 (B5, hF7): in words, short enough for the cell (was "Controller #2 — some games want #1", cut off);
        // Help & status's "Some games ignore the Thor" says what to do
        PadLayerCtl.active -> "Thor is player ${PadLayerCtl.controllerNumber} — see Help"
        else -> "Starting…"
    }
    val battery = remember(tick / 20) {     // every ~30 s
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { val l = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1); val sc = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / sc else null }
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10 }
        // what the charger really does (QA2: "charging" at 100 % with the 80 % limit on)
        val how = Charging.now(ctx).phrase
        listOfNotNull(pct?.let { "$it %" + (how?.let { h -> " · $h" } ?: "") }, t?.let { "$it °C" }).joinToString(" · ").ifEmpty { "—" }
    }
    val second = ForegroundAppService.availableDisplayIds.firstOrNull { it != 0 }
    // Wayfinder and launchers aren't tracked as screen apps: name the Hub's own screen, and
    // "Home screen" when nothing else is known
    // build 18 (B5, D10): one screen off → the screen that's on and "top only" / "bottom only" here (was in Brightness)
    val mode = ForegroundAppService.screenMode()
    val shown = when (mode) { 1 -> listOf(0); 2 -> listOfNotNull(second); else -> listOfNotNull(0, second) }
    val apps = shown.joinToString(" · ") { d ->
        if (d == myDisplayId) "Wayfinder"
        else ForegroundAppService.displayApps[d]?.let { appLabel(ctx, it) } ?: "Home screen"
    } + when (mode) { 1 -> " · top only"; 2 -> " · bottom only"; else -> "" }
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Tile) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // build 18 (B5): the controller's longer words ("stays where you send it") get the room Brightness gave up
            StripCell("Controller", "$where · $how", Modifier.weight(1.25f))
            if (layerProblem != null) StripCell("Game controls", layerProblem, Modifier.weight(0.9f), dot = g.warn)
            else StripCell("Battery", battery, Modifier.weight(0.8f))
            StripCell("On the screens", apps, Modifier.weight(1f))
            StripCell("Brightness", bright ?: "—", Modifier.weight(0.75f))
        }
    }
}

@Composable
private fun StripCell(label: String, value: String, modifier: Modifier, dot: androidx.compose.ui.graphics.Color? = null) {
    val g = LocalGlass.current
    Column(modifier.padding(horizontal = 8.dp)) {
        Text(label.uppercase(), color = g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(dot))
            Text(value, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun appsSummary(): String = AppConfigStore.configured().size.let { if (it == 0) "Each game's screen, controls, performance" else "$it games and apps set up" }

private fun panelSummary(ctx: android.content.Context): String =
    if (AppSettings.aynButtonOurs) "AYN button · ${PanelShortcuts.chosen(ctx).size} shortcuts" else "Off — AYN's drawer"

/** More's Appearance row. Build 18 (B11, MORE-06): "Your own colours" while they're on (it named a theme that did nothing),
 *  "Black" alone (no glass backdrop there); (hF21) "what's behind", the backdrop's own words. */
private fun appearanceSummary(): String = when {
    AppSettings.customColors -> "Your own colours"
    AppSettings.themeMode == ThemeMode.BLACK -> "Black"
    else -> "${AppSettings.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (AppSettings.backdrop == Backdrop.WALLPAPER) "what's behind" else "aurora"}"
}

/** Build 18 (B11, MORE-10): the Hub's More card — More's rows by their registry titles, lower case after the first
 *  ("Sound · stick lights · appearance · features · help"); a part turned off has no word (no Stick lights: hints instead). */
private fun moreSummary(): String = listOfNotNull(HubPage.SOUND, HubPage.LIGHTS.takeIf { Features.lights }, HubPage.APPEARANCE,
    HubPage.HINTS.takeUnless { Features.lights }, HubPage.FEATURES, HubPage.HELP)
    .joinToString(" · ") { NavPages.title(it).substringBefore(" & ").lowercase() }.replaceFirstChar { it.uppercase() }

// ─────────────────────────────────────────────────────────────────────────────
// Sub-page scaffold: back pill + title, scrollable glass content.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SubPage(
    myDisplayId: Int, title: String, subtitle: String?, onBack: () -> Unit,
    entry: Boolean = true,   // false when the page focuses something else itself (search field)
    content: @Composable ColumnScope.() -> Unit,
) {
    // dev build 16 (WP7): the pill is B's path (the innermost open thing first); the page opens on its first content, or
    // comes back to the row that opened the page left — never on Back (an A then left the page; it was focused at 300 ms)
    val back = app.wayfinder.ui.rememberBackPress(onBack)
    val returnTo = remember { if (entry) app.wayfinder.ui.ReturnFocus.take() else null }
    val pageScroll = rememberScrollState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    // build 18 (A11 / MORE-17, done in B): past the rows the header is whole or gone — a scroll that stops with it cut goes on
    // to its nearer edge the way it was going (down: gone, up: whole), and a page only a little taller than the screen gets
    // the room at its end to scroll it away. [headerEnd]: the header's bottom in the page; [room]: that room (px)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPad = with(density) { PAGE_TOP.roundToPx() }
    var headerEnd by remember { mutableIntStateOf(0) }
    var room by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageScroll.maxValue, headerEnd) {
        val over = pageScroll.maxValue - room
        val want = if (headerEnd > 0 && over in 1 until headerEnd) headerEnd - over else 0
        if (want != room) room = want
    }
    // b18r3 (P2-4, r1 BUG-1): the snap never undoes a focus bring-into-view. It sent a 25 px bring back to 0 (Controller ›
    // "Where Game controls work": cut at the edge): it settled while the focus's requests still ran (its scroll cancelled
    // them), and its way came from the last stop's value, which a silent move (the page shrinking under a closing "?" line
    // clamps the scroll, no scroll runs) left stale — a bring going down read as going up. Now it settles only once no scroll
    // and no focus bring runs ([FocusInView.bringing]), its way is this move's own, and it goes only where the focused element
    // keeps the room it was brought to ([headerSnapTo]) — else the other edge, else nowhere: the focus wins
    val inView = remember { app.wayfinder.ui.FocusInView() }
    val viewport = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val marginPx = with(density) { app.wayfinder.ui.FOCUS_VIEW_MARGIN.toPx() }
    LaunchedEffect(pageScroll) {
        var from = pageScroll.value
        androidx.compose.runtime.snapshotFlow { pageScroll.isScrollInProgress || inView.bringing.intValue > 0 }.collect { busy ->
            if (busy) { from = pageScroll.value; return@collect }
            val v = pageScroll.value
            val end = headerEnd
            if (end > 0 && v in 1 until end) {
                val down = minOf(end, pageScroll.maxValue)
                val way = if (v > from) down else 0
                val f = if (app.wayfinder.ui.InputMode.lastWasTouch) null else focusSpan(viewport[0], inView.focused())
                val to = headerSnapTo(v, way, if (way == 0) down else 0, f?.top, f?.bottom, viewport[0]?.size?.height?.toFloat() ?: 0f, marginPx)
                // its own coroutine, started at once (a bring asked meanwhile takes the scroll from it, never the reverse): a
                // touch taking the scroll cancels the snap, never this watch
                if (to != null && to != v) launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pageScroll.animateScrollTo(to) }
            }
            from = v
        }
    }
    // device pass (final): the page's edge scroll, also for a control that uses the key itself (a choice's last row)
    val edgeScroll: (Boolean) -> Unit = remember(pageScroll, scrollScope) {
        { down -> inView.scrolledAway(); scrollScope.launch { pageScroll.animateScrollBy(if (down) 360f else -360f) } }
    }
    // b18r3 (P2-4): the page's focus view — its whole content reports (the header's Back pill too)
    GlassScreen(span = spanFor(myDisplayId)) { androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalFocusInView provides inView) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding().navigationBarsPadding()
                .imePadding()   // shrink above the keyboard so results stay visible
                // nothing left to focus below (above): the page scrolls — text after the last button stays readable (QA2)
                .onKeyEvent { e ->
                    val down = e.key == androidx.compose.ui.input.key.Key.DirectionDown
                    val up = e.key == androidx.compose.ui.input.key.Key.DirectionUp
                    if ((!down && !up) || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                    val moved = focus.moveFocus(if (down) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up)
                    if (!moved) edgeScroll(down)
                    true
                }
                // b18r3 (P2-4): the viewport (the snap reads the focused element in it)
                .onPlaced { viewport[0] = it }
                .verticalScroll(pageScroll)
                // b18r3 (P2-4): the content changed size — a card's "?" line opening as the focus came, the room under the last
                // row arriving a frame later — and the focused control asks to be in view again: its first request was met
                // before (or cut short at the scroll's end then), and the control ended under the edge. One in view: nothing moves
                .onSizeChanged { if (!app.wayfinder.ui.InputMode.lastWasTouch) inView.reflowed() }
                .padding(start = 20.dp, end = 20.dp, top = PAGE_TOP, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // dev build 17 (WP8): the shared page header (Back with the hand's Back button)
            PageHeader(title, back, Modifier.onSizeChanged { headerEnd = topPad + it.height }, subtitle = subtitle)
            VSpace(2)
            // left / right stay in the settings (QA2: at a card's left edge, left jumped to Back and scrolled to the top)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            Column(Modifier.fillMaxWidth()
                .focusProperties {
                    exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right)
                        FocusRequester.Cancel else FocusRequester.Default }
                }
                .focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // the header (Back) is outside: never the entry. Build 18 (B0): the page's scroll, for a layer drawn in place
                androidx.compose.runtime.CompositionLocalProvider(LocalPageScroll provides pageScroll,
                    app.wayfinder.ui.LocalPageEdgeScroll provides edgeScroll) {
                    if (entry) {
                        val e = app.wayfinder.ui.rememberEntryFocus(returnKey = returnTo)
                        androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides e) { content() }
                    } else content()
                }
            }
            // build 18 (MORE-17): and the room to scroll the header away (none on a page that fits, or scrolls far enough)
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp + with(density) { room.toDp() }))
        }
    } }
}

/** b18r3 (P2-4): [focused]'s bounds in the page's [viewport] (px; the scroll applied), null when either is gone or they
 *  aren't one hierarchy (a popup's element). */
private fun focusSpan(viewport: androidx.compose.ui.layout.LayoutCoordinates?, focused: androidx.compose.ui.layout.LayoutCoordinates?): androidx.compose.ui.geometry.Rect? =
    if (viewport == null || focused == null || !viewport.isAttached || !focused.isAttached) null
    else runCatching { viewport.localBoundingBoxOf(focused, clipBounds = false) }.getOrNull()

/**
 * b18r3 (P2-4, r1 BUG-1): where SubPage's header snap goes from [v] (px) — [way], the edge its scroll was going to (MORE-17:
 * down, the header gone; up, whole), else [other] — without undoing a focus bring-into-view: the focused element
 * ([focusTop]..[focusBottom] in the [viewport], px now; null = none, or a finger drives) asks [margin] above and below (A11),
 * as much as the viewport holds beside it, and a target may not show it worse than now. null = stay: the header stays cut,
 * the focus wins. The snap took a 25 px bring back to 0 and the focused control stayed cut at the bottom edge.
 */
internal fun headerSnapTo(v: Int, way: Int, other: Int, focusTop: Float?, focusBottom: Float?, viewport: Float, margin: Float): Int? {
    if (focusTop == null || focusBottom == null || viewport <= 0f) return way
    val m = margin.coerceAtMost(((viewport - (focusBottom - focusTop)) / 2f).coerceAtLeast(0f))
    // how much of the element and its room a scroll to [to] leaves outside the viewport
    fun cut(to: Int): Float {
        val shift = (to - v).toFloat()
        return maxOf(0f, m - (focusTop - shift)) + maxOf(0f, focusBottom - shift + m - viewport)
    }
    val now = cut(v)
    return listOf(way, other).firstOrNull { cut(it) <= now + SNAP_SLACK_PX }
}

/** b18r3: rounding room (px) when [headerSnapTo] compares a target with now — a bring stops on whole pixels. */
private const val SNAP_SLACK_PX = 1.5f

/** Build 18 (A11, done in B): a page's top padding — Combos' and Games' 16 dp, so the Back pill sits at one height on every
 *  page (SubPage had 14). */
private val PAGE_TOP = 16.dp

/** 1.4: the less-used settings of a page, behind one row. Open when search brought you here. */
object MoreOptionsState { @Volatile var openNext = false }

/** dev build 17 (WP9): the card a link opens its page on ([goTo]) — its More options opens if it holds that card. */
object NavTarget { @Volatile var expand: String? = null }

/** [cards]: the registry cards inside (NavPaths.kt) — a link to one of them opens it (dev build 17, WP9). Build 18 (B7
 *  addendum, MORE-D18): the row's value is built from their titles ([Nav.moreValue]), so it can't drift; [what] only for a
 *  part without registry cards (Keyboard's). Build 18 (B7, D13): open, it starts with its own header ([title]) and the row
 *  reads "Fewer options" — the options no longer look like the section above them. */
@Composable
fun ColumnScope.MoreOptions(what: String? = null, title: String = "More options", cards: List<NavCard> = emptyList(),
                            content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(MoreOptionsState.openNext || NavTarget.expand.let { e -> e != null && cards.any { it.id == e } }) }
    LaunchedEffect(Unit) { MoreOptionsState.openNext = false; if (cards.any { it.id == NavTarget.expand }) NavTarget.expand = null }
    if (open) SectionHeader(title)
    GlassListRow(if (open) "Fewer options" else title, value = if (open) null else if (cards.isNotEmpty()) Nav.moreValue(cards) else what,
        icon = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, id = "more:$title") { open = !open }
    if (open) content()
}

/** dev build 17 (WP9): the page [card] is on, made ready to open on it — its More options open ([NavTarget]), the card
 *  focused ([app.wayfinder.ui.ReturnFocus.pending]: the page takes it in its composition, the 1.4 return mechanism). A
 *  controller's card needs its page ([page]: "thor" or "extpad:<key>"); null = it can't be opened ([Nav.canGo]: a card in
 *  a popup, or a kind of page without which one) — the caller opens nothing. */
fun navTarget(card: NavCard, page: String? = null): String? {
    val to = when {
        card.section != null -> null
        card.page == NavPages.CONTROLLER_PAGE -> page?.takeIf { it == HubPage.THOR || it.startsWith("extpad:") }
        card.page.endsWith(":*") -> null
        else -> card.page
    } ?: return null
    // already on that page: nothing composes again to take them (they would land on a later page)
    if (app.wayfinder.ui.ReturnFocus.shown != to) {
        NavTarget.expand = card.id.takeIf { card.inMore }
        app.wayfinder.ui.ReturnFocus.pending = card.id
    }
    if (BuildConfig.DEBUG) android.util.Log.d("ThorNav", "goTo ${card.id}")     // device test S14
    return to
}

/** Open [card]'s page on it ([navTarget]); [padKey]: whose page, for a controller's card ("thor" the Thor's). Back comes
 *  back to the row that called it (ReturnFocus: its id). */
fun goTo(card: NavCard, go: (String) -> Unit, padKey: String? = null) {
    navTarget(card, padKey?.let { if (it == HubPage.THOR) it else HubPage.extpad(it) })?.let(go)
}

/** A link to [card]: its path ("Controller › Where Game controls work"); A opens its page on it. Its id is what Back comes
 *  back to. In a game (no Hub page to open) a path is said as text instead. Build 18 (B20, MORE-D25): the link icon and the
 *  row's chevron (it was an "i" and a blue "Open ›" — the "i" read as information, not a way somewhere). */
@Composable
fun NavLink(card: NavCard, go: (String) -> Unit, padKey: String? = null, name: String? = null, modifier: Modifier = Modifier) =
    GlassListRow(Say.path(card, name), modifier, icon = Icons.Rounded.Link, id = "link:${card.id}", end = RowEnd.CHEVRON) { goTo(card, go, padKey) }

/** The "?" of a card whose explanation has more. Touch: a tap shows / hides the rest. Controller: it opens (and lights
 *  up) while the controller is anywhere on the card — so it never sits there unreachable. */
@Composable
private fun MoreHelp(open: Boolean, onToggle: () -> Unit) {
    val g = LocalGlass.current
    val tap by androidx.compose.runtime.rememberUpdatedState(onToggle)
    // build 18 (B11 addendum, MORE-D36): open = an accent outline (a filled accent disc read as a button to press — the
    // controller can't press it: it opens by itself while the controller is on the card)
    Box(Modifier.padding(start = 8.dp).size(26.dp)
        .then(if (open) Modifier.border(1.5.dp, g.accent, CircleShape) else Modifier.background(g.textTertiary.copy(alpha = 0.25f), CircleShape))
        .pointerInput(Unit) { detectTapGestures { tap() } }, contentAlignment = Alignment.Center) {
        Text("?", color = if (open) g.accent else g.textSecondary, style = MaterialTheme.typography.labelLarge)
    }
}

/** [content] padded — or no room at all when it shows nothing (a switch card's settings, hidden while it's off). */
@Composable
private fun PaddedIfAny(side: androidx.compose.ui.unit.Dp, top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp,
                        content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout({ Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }) { ms, c ->
        val s = side.roundToPx(); val t = top.roundToPx(); val b = bottom.roundToPx()
        val p = ms.first().measure(androidx.compose.ui.unit.Constraints(0, (c.maxWidth - 2 * s).coerceAtLeast(0)))
        if (p.height == 0) layout(c.minWidth, 0) {} else layout(c.maxWidth, p.height + t + b) { p.place(s, t) }
    }
}

/** dev build 17 (WP9): a card of the registry ([NavCards]) — its title from there, and focusable by its id: a link
 *  ([goTo]) lands on it (NavPathsTest: every card a text points at is drawn). Build 18 (C5, CTL-11): [confirmOff] = its
 *  switch asks before turning off (GlassSwitchRow's two presses: the line says it) — a page's main switch. */
@Composable
internal fun SettingCard(
    card: NavCard, explanation: String,
    checked: Boolean? = null, onChecked: (Boolean) -> Unit = {},
    confirmOff: String? = null,
    body: (@Composable () -> Unit)? = null,
) = SettingCard(card.title, explanation, checked, onChecked, focusId = card.id, confirmOff = confirmOff, body = body)

/** Build 18 (B14): a registry card whose explanation is split by hand — [short] shown, [more] behind its "?" (and while the
 *  controller is on the card); null = nothing more. */
@Composable
internal fun SettingCard(
    card: NavCard, short: String, more: String?,
    checked: Boolean? = null, onChecked: (Boolean) -> Unit = {},
    confirmOff: String? = null,
    body: (@Composable () -> Unit)? = null,
) = SettingCard(card.title, short = short, more = more, checked = checked, onChecked = onChecked, focusId = card.id,
    confirmOff = confirmOff, body = body)

/** A glass setting card: title, explanation, optional switch, optional body below. [focusId]: its id on the page (its
 *  switch row, or the card — a group: focus enters its first control). 1.4 (§10.3): one short line, the rest with "?"
 *  ([Say.split]; build 18, B14: or split by hand — the overload with `short` and `more`). */
@Composable
internal fun SettingCard(
    title: String, explanation: String,
    checked: Boolean? = null, onChecked: (Boolean) -> Unit = {},
    focusId: String? = null,
    confirmOff: String? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val (short, more) = remember(explanation) { Say.split(explanation) }
    SettingCard(title, short = short, more = more, checked = checked, onChecked = onChecked, focusId = focusId, confirmOff = confirmOff, body = body)
}

/** Build 18 (B14, D11): the top of a card the focus brings into view (its title and first line) — never the whole card,
 *  so the page doesn't jump to re-centre a card whose extra line just opened. */
private val CARD_TOP = 72.dp

/** Device pass (P2-4): rounding room (px) around the focused control's rect — Android's focus rect is in whole pixels. */
private const val FOCUS_RECT_SLACK = 2f
/** Device pass (P2-4): how many 60 ms looks a focused card takes at its height before it stops asking again (its "?" line
 *  opens in about 250 ms; a card whose height never settles stops at this). */
private const val CARD_SETTLE_STEPS = 8

/**
 * Device pass (P2-4): what a card [w] × [h] asks to bring into view as the focus comes onto it (all in the card, px): its top
 * ([top]: the title and first line — B14, never the whole card) together with the control focused in it ([focused], null =
 * unknown) and the room that control asks above and below itself ([margin], A11). ONE rect that holds the control's own
 * request: the scroll then shows this one when it fits, else the control's (the bigger request that fits wins) — the card's
 * top alone didn't hold it and cancelled it. A rect that isn't in the card counts as unknown: the top alone, as before.
 */
internal fun cardViewRect(w: Float, h: Float, top: Float, focused: androidx.compose.ui.geometry.Rect?, margin: Float): androidx.compose.ui.geometry.Rect {
    val head = androidx.compose.ui.geometry.Rect(0f, 0f, w, minOf(h, top))
    if (focused == null || focused.width <= 0f || focused.height <= 0f || !focused.overlaps(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)))
        return head
    val m = margin + FOCUS_RECT_SLACK
    return androidx.compose.ui.geometry.Rect(minOf(0f, focused.left - FOCUS_RECT_SLACK), minOf(0f, focused.top - m),
        maxOf(w, focused.right + FOCUS_RECT_SLACK), maxOf(head.bottom, focused.bottom + m))
}

/** Device pass (P2-4): the focused control's bounds in [card] — Compose's focused node, as its window reports it
 *  (AndroidComposeView answers `getFocusedRect` with the focus owner's rect, in the root); null when [card] isn't placed. */
private fun focusedIn(view: android.view.View, card: androidx.compose.ui.layout.LayoutCoordinates?): androidx.compose.ui.geometry.Rect? {
    if (card == null || !card.isAttached) return null
    val r = android.graphics.Rect()
    view.getFocusedRect(r)
    if (r.isEmpty) return null
    val root = card.findRootCoordinates()
    return androidx.compose.ui.geometry.Rect(
        card.localPositionOf(root, androidx.compose.ui.geometry.Offset(r.left.toFloat(), r.top.toFloat())),
        card.localPositionOf(root, androidx.compose.ui.geometry.Offset(r.right.toFloat(), r.bottom.toFloat())))
}

/**
 * The card every other overload draws: [short] its line, [more] the rest (behind "?"). Build 18 (B14, D11 / MORE-24): the
 * extra line opens and closes smoothly as the controller passes (the list jumped), and the focus coming onto a card that
 * fits the screen brings only its top into view — no re-centring. (A21, B14 addendum): a choice in its body shows its
 * focused option's hint here, in place of the line ([LocalSegmentHint]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SettingCard(
    title: String, short: String, more: String?,
    checked: Boolean? = null, onChecked: (Boolean) -> Unit = {},
    focusId: String? = null,
    confirmOff: String? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val g = LocalGlass.current
    // what a tap on "?" chose (null = follow the controller: open while it's on the card). A new visit by the
    // controller starts over. (QA2: a tap on a card that kept the controller's focus changed nothing on screen.)
    var helpChoice by remember(title) { mutableStateOf<Boolean?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    // the controller came onto the card: its top in view when the whole card fits (B14: the top only — no jump), with the
    // control it focused (device pass, P2-4: [cardViewRect])
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val size = remember { intArrayOf(0, 0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val topPx = with(density) { CARD_TOP.toPx() }
    // device pass (P2-4): the card's request holds the focused control's own (A11's margin included) — a request that doesn't
    // hold an earlier one cancels it (Compose's bring-into-view queue), and the card's top alone dropped the control's: a code
    // field, a slider, an option or a Fix row focused below the edge stayed there. Asked 60 ms in as before, and again while
    // the card's height still changes (its "?" line opening as the focus arrives moves the control down; each new rect holds
    // the last, so none cancels another). [placed]: the card where it is drawn
    val view = androidx.compose.ui.platform.LocalView.current
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val marginPx = with(density) { app.wayfinder.ui.FOCUS_VIEW_MARGIN.toPx() }
    val page = app.wayfinder.ui.LocalFocusInView.current      // b18r3 (P2-4): the page's snap waits for these asks too
    androidx.compose.runtime.LaunchedEffect(hasFocus) {
        if (hasFocus) {
            helpChoice = null
            var asked = -1
            for (step in 0 until CARD_SETTLE_STEPS) {
                delay(60)
                val h = size[1]
                if (h == asked) break                                           // settled since the last ask
                asked = h
                if (size[0] > 0 && h < screenPx * 0.75f) launch {
                    runCatching { bring.bringIntoView(cardViewRect(size[0].toFloat(), h.toFloat(), topPx, focusedIn(view, placed[0]), marginPx)) }
                }.also { page?.track(it) }
            }
        }
    }
    val cardMod = Modifier.fillMaxWidth().bringIntoViewRequester(bring)
        .then(Modifier.onSizeChanged { size[0] = it.width; size[1] = it.height })
        .then(Modifier.onPlaced { placed[0] = it })
    // (A21): the focused option's words while a choice in the body has an unselected one focused
    val hint = remember { mutableStateOf<String?>(null) }
    val hinted = hint.value
    val line = hinted ?: short
    val showMore = hinted == null && more != null && (helpChoice ?: hasFocus)
    val toggleHelp = { helpChoice = !showMore }
    val extra: @Composable ColumnScope.() -> Unit = { if (more != null) MoreLine(showMore, more) }
    // A plain on/off card: the WHOLE card is the button (A toggles), with the same glowing ring
    // as every other card. A bare Material switch showed almost no focus on glass, and focusing
    // it scrolled only the switch into view — the explanation stayed cut off (release test
    // 2026-09-24).
    // dev build 17 (WP8): on / off is the shared GlassSwitchRow — a card of its own, or the inset row of a card with settings
    if (checked != null && body == null) {
        GlassSwitchRow(title, checked, onChecked, Modifier.fillMaxWidth(), explanation = line, focusId = focusId, onFocus = { hasFocus = it },
            confirmOff = confirmOff, details = extra, trailing = { if (more != null) MoreHelp(showMore, toggleHelp) })
        return
    }
    // A switch AND settings below (frame rate, the bottom screen's timer…): the top row is ONE target, like a plain
    // switch card — A toggles. (QA2: only the small Switch could take focus and the D-pad jumped past the card.)
    if (checked != null) {
        GlassPanel(cardMod.onFocusChanged { hasFocus = it.hasFocus }, radius = Radii.Card) {
            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                GlassSwitchRow(title, checked, onChecked, explanation = line, inset = true, focusId = focusId,
                    confirmOff = confirmOff, details = extra, trailing = { if (more != null) MoreHelp(showMore, toggleHelp) })
                if (body != null) PaddedIfAny(10.dp, 8.dp, 6.dp) { CardBody(hint, body) }
            }
        }
        return
    }
    // dev build 17 (WP9): a card with an id is one group — a link's focus enters its first control (its current choice)
    GlassPanel(cardMod.onFocusChanged { hasFocus = it.hasFocus }
        .then(if (focusId != null) Modifier.focusId(focusId).focusGroup() else Modifier), radius = Radii.Card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    app.wayfinder.ui.GlyphText(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    if (line.isNotBlank()) Caption(line)       // build 18 (A0.4 / B9): a blank explanation takes no line
                    extra()
                }
                if (more != null) MoreHelp(showMore, toggleHelp)
            }
            if (body != null) CardBody(hint, body)
        }
    }
}

/** Build 18 (B14 addendum, A21): a card's body, where a choice writes its focused option's hint ([LocalSegmentHint]). */
@Composable
private fun CardBody(hint: androidx.compose.runtime.MutableState<String?>, body: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalSegmentHint provides hint) { body() }

/** Build 18 (B14, D11): a card's extra line — it opens and closes smoothly (the card grows instead of jumping). Not the
 *  card's whole size animated: that clips at the card's edge, and a switch card's focus ring is drawn outside it. */
@Composable
private fun ColumnScope.MoreLine(open: Boolean, text: String) {
    AnimatedVisibility(open, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
        Caption(text)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pages
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScreensPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.SCREENS), "Brightness, the bottom screen and how they look", onBack) {
        // dev build 16 (theme F): combos in the names of the controller in hand. Build 18 (B3): every card of the registry
        // (Search opens the page on it); (B6, hF13): a switch says what it does now — off, "Off — …"
        val look = app.wayfinder.ui.currentLook()
        SettingCard(NavCards.BRIGHTNESS, "\"Both screens\" keeps the difference between them. " +
            (Say.combo(ThorAction.BRIGHTER, look)?.let { "$it / ${Say.combo(ThorAction.DIMMER, look) ?: "—"} change both screens." } ?: "")) {
            BrightnessSliders()
        }
        SectionHeader("Bottom screen")
        // build 18 (B7, D24 / V09): the choices say what they do ("Off with 3 fingers: Off" read twice)
        SettingCard(
            NavCards.GESTURE,
            when (AppSettings.gestureBlankMode) {
                BlankGesture.OFF -> "Off — the bottom screen turns off with a combo or the quick panel"
                BlankGesture.TAP -> "Tap the bottom screen with 3 fingers to turn it off; tap again to turn it back on"
                BlankGesture.SWIPE -> "Swipe down with 3 fingers to turn it off; touch it to turn it back on"
            },
        ) {
            GlassSegmentedControl(listOf("Never", "Tap", "Swipe down"), AppSettings.gestureBlankMode.ordinal, Modifier.fillMaxWidth()) {
                AppSettings.setGestureBlank(BlankGesture.values()[it])
            }
        }
        // 1.4 (GitHub #54)
        SettingCard(NavCards.WAKE2,
            if (AppSettings.wakeDoubleTap) "Tap twice to turn it back on — a thumb brushing the screen doesn't wake it"
            else "Off — any touch turns it back on",
            checked = AppSettings.wakeDoubleTap, onChecked = { AppSettings.setWakeDoubleTapOn(it) })
        SettingCard(
            NavCards.IDLE,
            if (AppSettings.idleBlankEnabled) "After this long without a touch; touch it to turn it back on"
            else "Off — it stays on until you turn it off",
            checked = AppSettings.idleBlankEnabled, onChecked = { AppSettings.setIdleBlank(it) },
        ) {
            if (AppSettings.idleBlankEnabled) {
                val opts = listOf(15, 30, 60, 120)
                GlassSegmentedControl(
                    opts.map { if (it < 60) "$it s" else "${it / 60} min" },
                    opts.indexOf(AppSettings.idleBlankSeconds).let { if (it < 0) 1 else it }, Modifier.fillMaxWidth(),
                ) { AppSettings.setIdleSeconds(opts[it]) }
            }
        }
        // dev build 17 (WP11): app pairs and the restart restore are screen switching — here, whatever the quick panel or
        // Features (with "Just switching screens" the quick panel is off: they were behind it)
        SectionHeader("Apps on the screens")
        // build 18 (B11, D21): a chevron — it opens a page
        GlassListRow(NavCards.PAIRS.title, value = "${Layouts.pairs.size} saved · two apps, one press", icon = Icons.Rounded.ViewAgenda,
            id = NavCards.PAIRS.id, end = RowEnd.CHEVRON) { go(HubPage.PAIRS) }
        SettingCard(NavCards.RESTORE,
            if (Layouts.restoreOnBoot) "After the Thor restarts, reopen the apps that were on each screen"
            else "Off — after a restart, each screen starts on its home screen",
            checked = Layouts.restoreOnBoot, onChecked = { Layouts.setRestore(it) })
        // build 18 (B7, pF24): screenshots out of More options — a section of their own (the card of GitHub #98: which screen a
        // screenshot takes, and where screen recordings go; its short line first, the rest behind "?")
        @Suppress("UNUSED_VARIABLE") val cv = ControlsStore.version.intValue
        val shot = Say.howTo(ThorAction.SCREENSHOT, look)
        SectionHeader("Screenshots and recordings")
        SettingCard(NavCards.SCREENSHOTS,
            "Saved to Pictures / Screenshots · " + (shot?.let { "combo $it" } ?: "no combo yet") +
                " — recordings where you choose below, both where your gallery apps see them. Screenshots capture everything " +
                "shown, even what apps hide from them (passwords, banking)." +
                (if (shot == null) " Set a combo in ${Nav.place(HubPage.CONTROLS)}." else "")) {
            GlassSegmentedControl(
                ShotTarget.values().map { if (it == ShotTarget.BOTH) "Both (stacked)" else "${it.label} screen" },
                AppSettings.shotTarget.ordinal, Modifier.fillMaxWidth(), label = "Screenshots",   // review: each choice named
            ) { AppSettings.setScreenshotTarget(ShotTarget.values()[it]) }
            GlassSegmentedControl(listOf(RecordFolder.MOVIES.label, "With my screenshots"), AppSettings.recordFolder.ordinal,
                Modifier.fillMaxWidth(), label = "Screen recordings") { AppSettings.chooseRecordFolder(RecordFolder.values()[it]) }
        }
        // 1.4 (2026-09-30): stop charging at 80 % was only a hidden quick-panel tile. Build 18 (B7 addendum): its value
        // is its cards' titles
        MoreOptions(cards = listOf(NavCards.COLOUR, NavCards.DND, NavCards.AWAKE, NavCards.FPS, NavCards.NAVBAR, NavCards.ANIMATE)) {
        SettingCard(NavCards.COLOUR, "How vivid both screens are — the Thor's panels are strong out of the box; 80–90 % looks more natural. 100 % = as shipped.") {
            SaturationSlider()
        }
        SettingCard(NavCards.DND,
            if (AppSettings.dndWhilePlaying) "While a game is on either screen, notifications stay quiet (Do not disturb, priority only). It goes back off when you stop — unless you had it on already."
            else "Off — notifications behave as usual while you play",
            checked = AppSettings.dndWhilePlaying, onChecked = { AppSettings.setDndWhilePlayingOn(it); ForegroundAppService.reapplyDnd() })
        // both screens (one power state) — it isn't a "Bottom screen" setting
        var awake by remember { mutableStateOf(ForegroundAppService.isSecondKeptAwake()) }
        SettingCard(
            NavCards.AWAKE,
            if (awake) "The screens don't turn off on their own (both share one power state) — for maps, videos, guides. The power button still works."
            else "Off — the screens follow Android's timeout",
            checked = awake, onChecked = { ActionRegistry.perform(ThorAction.TOGGLE_KEEP_AWAKE); awake = ForegroundAppService.isSecondKeptAwake() },
        )
        SettingCard(NavCards.FPS,
            if (AppSettings.fpsCounter) "The real frames per second each screen's app draws (\"idle\" = nothing moving)" else "Off — no counter",
            checked = AppSettings.fpsCounter, onChecked = { AppSettings.setFpsCounterOn(it); ForegroundAppService.reapplyFps() }) {
            if (AppSettings.fpsCounter) {
                // build 18 (B7, D14): every choice named — "Screen", "Corner", "Shows" (two had no label)
                GlassSegmentedControl(listOf("Top screen", "Bottom screen", "Both"), AppSettings.fpsScreens, Modifier.fillMaxWidth(), label = "Screen") {
                    AppSettings.setFpsPlacement(screens = it); ForegroundAppService.reapplyFps()
                }
                GlassSegmentedControl(listOf("Top left", "Top right", "Bottom left", "Bottom right"), AppSettings.fpsCorner, Modifier.fillMaxWidth(), label = "Corner") {
                    AppSettings.setFpsPlacement(corner = it); ForegroundAppService.reapplyFps()
                }
                // round 8: how much it shows (the data is read anyway — no root, no cost). Build 18 (B7, pF15): the levels'
                // words (glossary: Frame rate / Frame rate + battery / Everything — the quick panel's tile says the same)
                GlassSegmentedControl(listOf("Frame rate", "Frame rate + battery", "Everything"), AppSettings.fpsLevel, Modifier.fillMaxWidth(),
                    label = "Shows", captions = listOf(null, null, "and CPU / GPU temperatures")) {
                    AppSettings.setFpsLevelTo(it)
                }
                // build 18 (GitHub #86): how big, and a compact style — the preview shows it here (the real one hides over the Hub)
                FpsPreview()
                GlassSegmentedControl(listOf("Small", "Normal", "Large"), AppSettings.fpsSize, Modifier.fillMaxWidth(), label = "Size") {
                    AppSettings.setFpsSizeTo(it); ForegroundAppService.reapplyFps()
                }
                GlassSwitchRow("Compact", AppSettings.fpsCompact, { AppSettings.setFpsCompactOn(it); ForegroundAppService.reapplyFps() },
                    explanation = if (AppSettings.fpsCompact) "Square, tight, right in the corner" else "Off — a rounded pill, a little in from the corner",
                    inset = true)
            }
        }
        // 1.4 (GitHub #62 #63)
        SettingCard(NavCards.NAVBAR,
            if (AppSettings.navGuard) "When Android's navigation bar is set hidden but comes back, Wayfinder hides it again (it gives up if that doesn't help)"
            else "Off — Wayfinder never touches the navigation bar",
            checked = AppSettings.navGuard, onChecked = { AppSettings.setNavGuardOn(it) })
        var anim by remember { mutableStateOf(ForegroundAppService.animateMoves) }
        SettingCard(NavCards.ANIMATE,
            if (anim) "A glass slide when an app goes to the other screen" else "Off — apps change screens at once",
            checked = anim, onChecked = { anim = it; ForegroundAppService.animateMoves = it })
        }
    }

/** Build 18 (GitHub #86): the frame-rate counter as it will look — its size, style and corner, with a sample of what it
 *  shows. The real one hides over the Hub, so its card shows it here, drawn from the overlay's own numbers and colours
 *  ([FpsOverlay]'s companion): the two never differ. Not a control (the D-pad passes it by). */
@Composable
private fun FpsPreview() {
    val g = LocalGlass.current
    val compact = AppSettings.fpsCompact
    val corner = AppSettings.fpsCorner
    val (padH, padV) = FpsOverlay.padDp(compact)
    val (mx, my) = FpsOverlay.marginDp(corner, compact)
    val parts = (if (AppSettings.fpsLevel >= 1) listOf("87 % −6.2 W") else emptyList()) +
        (if (AppSettings.fpsLevel >= 2) listOf("CPU 52°", "GPU 48°") else emptyList())
    val text = buildAnnotatedString {
        pushStyle(SpanStyle(color = Color(FpsOverlay.rateColour(60)))); append("60 FPS"); pop()
        pushStyle(SpanStyle(color = Color(FpsOverlay.READINGS))); append(FpsOverlay.readings(parts, compact)); pop()
    }
    val pill = FpsOverlay.radiusDp(compact)?.let { RoundedCornerShape(it.dp) } ?: RoundedCornerShape(percent = 50)
    // the well is a screen's corner: the counter at its real distance from the side and from the edge (at the top, room
    // for the status bar); the well keeps one height whichever corner
    val well = 42
    val top = corner < 2
    val left = corner % 2 == 0
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.Control)).background(g.fillWell)) {
        Text(text,
            Modifier.align(if (top) (if (left) Alignment.TopStart else Alignment.TopEnd) else (if (left) Alignment.BottomStart else Alignment.BottomEnd))
                .padding(start = (if (left) mx else 0).dp, end = (if (left) 0 else mx).dp,
                    top = (if (top) my else well - my).dp, bottom = (if (top) well - my else my).dp)
                .background(Color(FpsOverlay.PILL), pill).padding(horizontal = padH.dp, vertical = padV.dp),
            fontSize = FpsOverlay.textSp(AppSettings.fpsSize).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif,
            maxLines = 1, softWrap = false, style = androidx.compose.ui.text.TextStyle.Default)   // a TextView's line, not the page's
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App pairs (dev build 17, WP11: a page of Screens; the restore after a restart is on Screens itself)
// ─────────────────────────────────────────────────────────────────────────────

private fun appLabel(ctx: android.content.Context, pkg: String?): String =
    if (pkg == null) "(as is)" else runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

@Composable
private fun PairsPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.PAIRS), "Two apps, one press — each opens on its screen", onBack) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var building by remember { mutableStateOf(false) }
        val newPair = remember { FocusRequester() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // build 18 (B8, D15 / hF17): one way in — the empty state's own button while there's no pair, else New pair; how to
        // open a pair from any app is a card of its own (the 40-word paragraph is gone)
        if (Layouts.pairs.isEmpty()) app.wayfinder.ui.EmptyState("No pairs yet. A pair opens two apps, one on each screen.",
            action = "New pair", onAction = { building = true })
        else GlassActionButton("New pair", Icons.Rounded.Add, Modifier.fillMaxWidth(), subtitle = "Pick an app for each screen",
            focusRequester = newPair) { building = true }
        // the first pair saved replaces the empty state (the button the sheet gives the focus back to): New pair then
        if (building) PairBuilderDialog(myDisplayId) { building = false; scope.launch { newPair.requestAfterLayout() } }
        for (p in Layouts.pairs.toList()) PairRow(p, appLabel(ctx, p.top), appLabel(ctx, p.bottom))
        // (D15): the quick panel's way, in the controller's own words. Nothing to press on this card, so all of it is on its line
        // (no "?" the controller couldn't open)
        SettingCard("Open a pair from anywhere",
            short = "The ${Say.btn(ThorButton.AYN)} button, then the App pairs tile — add the tile with the panel's Arrange", more = null)
    }

@Composable
private fun PairRow(p: AppPair, top: String, bottom: String) {
    val g = LocalGlass.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusableGlass(onClick = { ForegroundAppService.openPair(p) }, modifier = Modifier.weight(1f), radius = Radii.Row) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val ctx = LocalContext.current
                Text(pairTitle(ctx, p.top, p.bottom), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(pairWhere(ctx, p.top, p.bottom), color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        GlassButton("Swap", { Layouts.flip(p.id) }, kind = ButtonKind.PRIMARY)
        // dev build 17 (WP8): the shared two presses (same words). Review WP8-3: the confirmation is this pair's (a plain
        // loop: one deleted, the next takes its place — not armed)
        ConfirmButton("Delete", "Delete?", { Layouts.remove(p.id) }, state = app.wayfinder.ui.rememberConfirmState(p.id))
    }
}

/** 1.3 (GitHub #27): what the face buttons are CALLED on screen. dev build 15 (D8): the names follow the face style; only
 *  "as printed" stays a choice of its own. dev build 17 (WP11): on the Thor's page (was Controller › More options). */
@Composable
internal fun NamesAsPrintedCard() =
    // build 18 (C7, CTL-09): what the switch does now (it described On while off); with the Thor on Nintendo it changes nothing
    SettingCard(NavCards.NAMES_PRINTED,
        (if (ButtonNames.asPrinted) "On: A on the right, B at the bottom in every hint and page" else "Off: names follow the face buttons") +
            (if (ButtonNames.thorStyle == FaceStyle.NINTENDO) " — Nintendo is already as printed." else "."),
        checked = ButtonNames.asPrinted, onChecked = { ButtonNames.setAsPrinted(it) })

/** Shown while Android thinks the Thor's first setup isn't finished — it silently blocks Recents. */
@Composable
fun SetupFlagWarning(shown: ((Boolean) -> Unit)? = null, onFixed: () -> Unit = {}) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val broken = remember(tick) { SetupFlagGuard.broken(ctx) }
    androidx.compose.runtime.SideEffect { shown?.invoke(broken) }     // build 18 (B5): the Hub's Up from its cards
    // it had the controller's focus and vanished: hand the focus on (else nothing was highlighted)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(broken) { if (broken) shown = true else if (shown) { shown = false; delay(100); onFixed() } }
    if (!broken) return
    // dev build 17 (WP8): a notice you press (was an action tile with the blue Info icon)
    GlassNotice("Android thinks this Thor's first setup isn't finished (a system reset can cause it) — press to fix", NoticeKind.DANGER,
        Modifier.fillMaxWidth(), title = "Recent apps can't open", onClick = { SetupFlagGuard.fix { tick++ } })
}

/** Another app doing the same job as a part of Wayfinder, not handled automatically: said once, with what to do. */
@Composable
fun OtherAppsWarning(shown: ((Boolean) -> Unit)? = null, onDone: () -> Unit = {}) {
    // 1.4: an app that does the same as a part of Wayfinder, not handled automatically — say it once, with what to do
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val warn = remember(tick) { OtherApps.toWarn(ctx) }
    androidx.compose.runtime.SideEffect { shown?.invoke(warn.isNotEmpty()) }     // build 18 (B5)
    for (a in warn) GlassNotice(a.text + " Press here once you've read this.", NoticeKind.WARN, Modifier.fillMaxWidth(),
        title = "${a.name} can conflict", onClick = { OtherApps.acknowledge(ctx, a.pkg); tick++; onDone() })
}

/** Shown while AYN's "press Home twice" option is on — it silently breaks every Home combo. */
@Composable
fun AynHomeWarning(shown: ((Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val on = remember(tick) { AynHomeGuard.isOn(ctx) }
    androidx.compose.runtime.SideEffect { shown?.invoke(on) }     // build 18 (B5)
    if (!on) return
    GlassNotice("AYN's “press Home twice” protection is on — press to turn it off (Home combos need it off)", NoticeKind.DANGER,
        Modifier.fillMaxWidth(), title = "Home combos can't work yet", onClick = { AynHomeGuard.turnOff { tick++ } })
}


@Composable
private fun AppearancePage(myDisplayId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SubPage(myDisplayId, NavPages.title(HubPage.APPEARANCE), "Theme, colours and glass", onBack) {
        // build 18 (B16, MORE-D02): ONE look choice — the four themes and Your colours (a separate switch left a theme choice
        // that did nothing). Your colours keep the theme for later; a theme turns your colours off and keeps them. (B3): the
        // registry's cards (Search opens the page on them)
        val own = AppSettings.customColors
        SettingCard(NavCards.THEME, when {
            own -> "Your own colours: a flat look — no glass, no blur (here, the quick panel and \"Your combos\")"
            AppSettings.themeMode == ThemeMode.BLACK -> "Black: dark glass on pure black — OLED screens show true black and use less power (no aurora, no background blur)"
            else -> "Light, dark or black glass, your own colours — or follow the system"     // (B11, MORE-20a)
        }) {
            GlassSegmentedControl(
                listOf("System", "Light", "Dark", "Black", "Your colours"),
                if (own) 4 else when (AppSettings.themeMode) { ThemeMode.SYSTEM -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2; ThemeMode.BLACK -> 3 },
                Modifier.fillMaxWidth(),
            ) {
                if (it == 4) AppSettings.setCustomColorsOn(true)
                else {
                    AppSettings.setCustomColorsOn(false)
                    AppSettings.setMode(when (it) { 1 -> ThemeMode.LIGHT; 2 -> ThemeMode.DARK; 3 -> ThemeMode.BLACK; else -> ThemeMode.SYSTEM })
                }
            }
            // the four colours in the Theme card while they're chosen — Search's "Your own colours" lands on them
            if (own) CustomColorPickers(focusId = NavCards.OWN_COLOURS.id)
        }
        MoreOptions(cards = if (own) listOf(NavCards.HUB_SCREEN) else listOfNotNull(NavCards.BACKDROP, NavCards.SEE_THROUGH, NavCards.REFRACTION.takeIf { app.wayfinder.ui.GlassLensConfig.supported }, NavCards.HUB_SCREEN)) {
        // build 18 (B11, MORE-07): your colours have no glass — one line instead of three settings that change nothing
        if (own) Caption("Glass settings apply when Your own colours is off.")
        else {
        SettingCard(
            NavCards.BACKDROP,
            // build 18 (B11, hF21): "What's behind" — the glass blurs the screen under Wayfinder: the app there, or the wallpaper
            // (it promised "your background")
            if (AppSettings.backdrop == Backdrop.WALLPAPER) "The screen behind Wayfinder, blurred live — your wallpaper, or the app under it. If the system turns blur off (battery saver), it falls back to Aurora."
            else "Wayfinder's drifting colour aurora, flowing across both screens",
        ) {
            GlassSegmentedControl(listOf("What's behind", "Aurora"), AppSettings.backdrop.ordinal, Modifier.fillMaxWidth()) {
                AppSettings.setBackdropMode(Backdrop.values()[it])
                (ctx as? MainActivity)?.applyBackdrop()
            }
        }
        SettingCard(NavCards.SEE_THROUGH, "How much of what's behind shows through — here and in the quick panel (the AYN button)") {
            GlassLookSliders(onBlur = { (ctx as? MainActivity)?.applyBackdrop() })
        }
        // dev build 17 (WP8): on / off — the card's switch (was On / Off segments)
        if (app.wayfinder.ui.GlassLensConfig.supported) SettingCard(
            NavCards.REFRACTION,
            if (!AppSettings.refraction) "Plain frosted glass"
            else if (AppSettings.backdrop == Backdrop.AURORA) "The glass bends the aurora at its edges and catches the light"
            // build 18 (round 2): the backdrop option's name (hF21: "your background" promised the wallpaper)
            else "The glass edges catch the light (what's behind stays live, so it's blurred, not bent)",
            checked = AppSettings.refraction, onChecked = { AppSettings.setRefractionOn(it) },
        )
        }
        SettingCard(NavCards.HUB_SCREEN, "Where Wayfinder opens. On the top screen, \"Your combos\" fills the bottom one; on the bottom screen, the top stays free for your game.") {
            GlassSegmentedControl(listOf("Top screen", "Bottom screen"), if (AppSettings.hubOnTop) 0 else 1, Modifier.fillMaxWidth()) { i ->
                AppSettings.setHubScreen(i == 0)
                (ctx as? MainActivity)?.relocateIfNeeded(userInitiated = true)
            }
        }
        }
    }
}

/** 1.4 (GitHub #24): the four colours — a wheel and a hex field each. Build 18 (B15, MORE-D01): text hard to read on these
 *  colours says so, with a fix (the text white or near-black, whichever reads on the cards), and the four can be reset (two
 *  presses); nothing is written unless one is pressed. (B11, MORE-08): Down into the four lands on the first, Background (it
 *  landed on the third). [focusId]: the row's id (Search's "Your own colours"). */
@Composable
private fun CustomColorPickers(focusId: String? = null) {
    val names = listOf("Background", "Cards", "Text", "Accent")
    val values = listOf(AppSettings.customBg, AppSettings.customCard, AppSettings.customText, AppSettings.customAccent)
    val text = TokenArgb.of(AppSettings.customText)
    val card = TokenArgb.of(AppSettings.customCard)
    val first = remember { FocusRequester() }
    val textPick = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // fixed, the notice goes with its button: the focus moves to the Text colour it changed (not left on nothing)
    if (minOf(TokenArgb.contrast(text, card), TokenArgb.contrast(text, TokenArgb.of(AppSettings.customBg))) < 4.5)
        GlassNotice("Text is hard to read on these colours.", NoticeKind.WARN, Modifier.fillMaxWidth(), action = "Fix the text colour",
            onAction = { AppSettings.setCustomColor(2, readableText(card)); scope.launch { textPick.requestAfterLayout() } })
    Row(Modifier.fillMaxWidth().then(if (focusId != null) Modifier.focusId(focusId) else Modifier)
        // device pass (final): Background is where the focus ARRIVES (B11) — an explicit request (a code field's typing, "Fix the
        // text colour") goes where it was sent: redirected to Background, typing flashed the ring there and the fix stayed on it
        .focusProperties { enter = { app.wayfinder.ui.arrivalTo(first) } }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        names.forEachIndexed { i, name ->
            ColorPick(name, values[i], Modifier.weight(1f).then(when (i) {
                0 -> Modifier.focusRequester(first).focusGroup()
                // says whether it holds the focus: a request that went elsewhere is asked again (requestAfterLayout)
                2 -> Modifier.reportingFocusRequester(textPick).focusGroup()
                else -> Modifier
            })) {
                AppSettings.setCustomColor(i, it) }
        }
    }
    ConfirmButton("Reset colours", "Press again to reset the four colours",
        { AppSettings.CUSTOM_DEFAULTS.forEachIndexed { i, c -> AppSettings.setCustomColor(i, c) } }, size = ButtonSize.COMPACT)
}

/** B15: white or the default near-black — whichever reads better on [card]. */
private fun readableText(card: Long): Int {
    val light = AppSettings.CUSTOM_DEFAULTS[2]
    val dark = AppSettings.CUSTOM_DEFAULTS[0]
    return if (TokenArgb.contrast(TokenArgb.of(light), card) >= TokenArgb.contrast(TokenArgb.of(dark), card)) light else dark
}

@Composable
private fun ColorPick(name: String, argb: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(name)
        app.wayfinder.ui.ColorWheel(argb, 120.dp) { onPick(it) }
        // dev build 17 (WP8): the shared hex field (Stick lights has the same one)
        HexColorField(argb, onPick, previewSize = 22.dp, fieldWidth = 96.dp, textSize = 15.sp)
    }
}

/** Back up / restore (Backup.kt): a backup made on this Thor restores everything; any other
 *  file only gives its game controls, filtered like a shared file. */
@Composable
private fun BackupSection(myDisplayId: Int) {
    val ctx = LocalContext.current
    var incoming by remember { mutableStateOf<Backup.Incoming?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(ProfileShare.PickInWayfinderFolder()) { uri ->
        // off the main thread: a big file of many entries froze the app (review 2026-09-25)
        if (uri != null) scope.launch {
            val (inc, err) = withContext(Dispatchers.IO) { Backup.read(ctx, uri) }
            incoming = inc; error = err
        }
    }
    SectionHeader("Back up and restore")
    // build 18 (B20, MORE-D25): icons by job (all three were an "i")
    GlassListRow(NavCards.BACKUP.title, value = "a file in Download / Wayfinder", icon = Icons.Rounded.Save, id = NavCards.BACKUP.id) {
        val (name, bytes) = Backup.make(ctx)
        Overlays.pill(if (ProfileShare.save(ctx, name, bytes)) "Backup saved in Download / Wayfinder: $name"
            else "The backup couldn't be saved", CueOrigin.Window(myDisplayId))
    }
    GlassListRow("Send a backup…", value = "Drive, mail, another app", icon = Icons.Rounded.Share) {
        val (name, bytes) = Backup.make(ctx)
        if (!ProfileShare.send(ctx, name, bytes, "Send the backup")) Overlays.pill("Couldn't open the share menu", CueOrigin.Window(myDisplayId))
    }
    GlassListRow("Restore from a file…", value = "you'll see what it contains first", icon = Icons.Rounded.Restore, id = "restore") {
        picker.launch(ProfileShare.PICK_TYPES)
    }
    // the limit of the signing key, said BEFORE anyone relies on a backup (2026-09-25)
    Text("A backup restores everything only here, with this installation of Wayfinder. If Wayfinder is uninstalled or its " +
        "data is cleared — or on another Thor — a backup still brings back your game controls, but not your combos " +
        "and settings. Keep a fresh backup, and set those up again after a reinstall.",
        color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodySmall)
    if (incoming != null || error != null) RestoreDialog(incoming, error, onRestore = { inc ->
        incoming = null; error = null
        if (inc.trusted && inc.prefs != null) { Backup.restoreAll(ctx, inc.prefs); Backup.restartApp(ctx) }
        else {
            Backup.restoreMappings(inc.mappings)
            Overlays.pill("Game controls imported" + if (inc.report.macrosOff > 0) " — macros stay off until you turn them on" else "",
                CueOrigin.Window(myDisplayId))
        }
    }) { incoming = null; error = null }
}

@Composable
private fun RestoreDialog(inc: Backup.Incoming?, error: String?, onRestore: (Backup.Incoming) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val first = remember { FocusRequester() }
    // dev build 16 (WP7): an in-window sheet (was a Dialog window), opening on Cancel. dev build 17 (WP8): Close / Cancel in the
    // header, on the left — and still the focus when it opens (review 2026-09-25: "Restore everything" replaces it all and
    // restarts; the one sheet whose other action must never be one stray A away)
    app.wayfinder.ui.GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.7f), first = first) {
        ModalHeader("Restore", onClose, closeLabel = if (inc == null) "Close" else "Cancel", closeFocus = first)
        if (inc == null) {
            Text(error ?: "That file can't be used.", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        } else if (inc.trusted) {
            Text("A backup from ${inc.made}, made by Wayfinder on this Thor.", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text("It brings back everything: combos, app and game controls, the quick panel, keyboard, lights, sound and sleep settings. " +
                "What you have now is replaced, and Wayfinder restarts.", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("This backup can't be fully restored here.", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text("It comes from another Thor or an earlier install of Wayfinder, or it was changed. Only the Wayfinder " +
                "that made a backup can restore all of it.",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            val games = inc.mappings.count { it.game != null }
            Text("For safety, only its game controls are taken: ${inc.mappings.size - games} app${if (inc.mappings.size - games == 1) "" else "s"}" +
                " and $games game${if (games == 1) "" else "s"}. Its Wayfinder combos and settings are not imported.",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            for (l in inc.report.lines()) Text(l, color = g.warn, style = MaterialTheme.typography.bodyMedium)
        }
        if (inc != null) GlassButton(if (inc.trusted) "Restore everything" else "Import the game controls", { onRestore(inc) }, kind = ButtonKind.PRIMARY)
    }
}

/** A question of Troubleshooting, its answer, and what its "Fix" row opens: the page on the [card] its answer names (dev
 *  build 17, WP9: a link), else the [page]; or (build 18, B11 MORE-05) what it [run]s on the spot; none = no row. */
private class Fix(val q: String, val a: String, val card: NavCard? = null, val page: String? = null, val run: (() -> Unit)? = null)

/** Build 18 (B20, MORE-D26): [s]'s first sentence, and the rest (a question's card shows the first). */
private fun firstSentence(s: String): Pair<String, String?> {
    val i = Regex("""(?<!e\.g|i\.e|etc)\.\s""").find(s)?.range?.first ?: return s to null
    return s.substring(0, i + 1) to s.substring(i + 2).trim().ifEmpty { null }
}

/** The questions people actually have, each with its fix (critique 2026-09-25). */
@Composable
private fun Troubleshooting(go: (String) -> Unit) {
    SetupFlagWarning()
    val ctx = LocalContext.current
    // dev build 16 (theme F): combos in the names of the controller in hand
    val look = app.wayfinder.ui.currentLook()
    val home = Say.combo(ThorAction.FOCUS_SWITCH_UP, look)
    val gc = Say.combo(ThorAction.GAME_CONTROLS, look)
    // build 18 (B11, MORE-05): Recents blocked by the setup flag → its row fixes it (as the warning above does); else no row
    val flagBroken = remember { SetupFlagGuard.broken(ctx) }
    // dev build 17 (WP11, theme H; critique K10): every place from the registry (NavPaths.kt — a moved row moves its path, a
    // dead one fails NavPathsTest). dev build 17 (WP9): each "Fix" opens the page on the card its text names
    val items = listOf(
        Fix("A game sees two controllers, or buttons act twice",
            "Wayfinder gives games a copy of the controller and hides AYN's. Set ${Say.path(NavCards.LAYER)} to Nowhere and back — or restart the game.", NavCards.LAYER),
        // build 18 (B5, hF7): the Hub's status line points here ("Thor is player 2 — see Help")
        Fix("Some games ignore the Thor",
            "Some games only listen to player 1: the Hub's status line says when the Thor isn't — set ${NavCards.LAYER.title} to Nowhere and back, " +
                "or put the Thor first: ${Say.path(NavCards.ORDER)}. " +
                "A game that ignores Wayfinder's copy of the controller altogether: ${Say.path(NavCards.GC_APP_LAYER)}: Off (in an emulator: the whole emulator).", NavCards.LAYER),
        // critique F1 / K11: the input layer's own emergency — the Thor's Home + Back (or a docked controller's), whatever is in hand
        Fix("No controller at all",
            "${Say.emergencyOff(ctx).replaceFirstChar { it.uppercase() }}: the input layer turns off and AYN's own controller comes back. Back on: ${Say.path(NavCards.LAYER)} (it remembers where it ran).", NavCards.LAYER),
        Fix("Home combos do nothing",
            "${Say.path(NavCards.HOME_TWICE)} must be off. In one app only? Its combos may be off: ${Say.path(NavCards.APP_COMBOS)}.", NavCards.HOME_TWICE),
        Fix("The controller works the wrong screen",
            (home?.let { "$it sends it to the top screen, the same with down to the bottom one. " } ?: "") +
                "How it follows you (touch, where you send it, always top): ${Say.path(NavCards.WHICH_SCREEN)}.", NavCards.WHICH_SCREEN),
        Fix("A stick moves on its own (drift)",
            // build 18 (C7, CTL-07): the page is "Test it" now — its place says it. Device pass: its suggestion is used with a 1 s
            // hold of Start there (C8: a press only tests the button), not "in one press" — the hold in the hand's badge
            "${Say.path(NavCards.TEST_IT)} measures it and suggests a deadzone: hold ${Say.key(ThorButton.START)} there for a second, " +
                "and every game uses it. One game differently: Game controls, that stick.", page = HubPage.PADTEST),
        Fix("Recent apps doesn't open (double Back does nothing)",
            "Android refuses Recents while it thinks the Thor's first setup isn't finished — a system reset can " +
                "leave it that way. The warning at the top of this list fixes it in one press (shown only when that's the cause).",
            run = if (flagBroken) ({ SetupFlagGuard.fix { } }) else null),
        // controllers. Build 18 (B23, MORE-D27): "your controller" in the path, no "(on its page)"
        Fix("A Joy-Con's stick, a D-pad or the triggers of a Bluetooth controller do nothing",
            "AYN's system loses parts of some controllers. Turn on ${Say.path(NavCards.HANDLES)}: Wayfinder reads the controller itself. " +
                "A part still wrong: ${Say.path(NavCards.PAD_SETUP)}.", NavCards.HANDLES),
        Fix("A connected controller should be player 1 (or the Thor should)",
            "Every app: ${Say.path(NavCards.ORDER)}; one app: ${Say.path(NavCards.GC_PLAYER1)}; one game: in that game, " +
                "${gc ?: "Game controls"} › ${Say.path(NavCards.GC_PLAYER1, from = NavPages.GAME)}. A connected controller as player 1 needs Game controls on.", NavCards.ORDER),
        Fix("Two Joy-Cons should be one controller",
            "Press L on the left one and R on the right one together for a second, or turn on ${Say.path(NavCards.COMBINE)}. One Joy-Con on its own sideways: hold SL + SR.", NavCards.COMBINE),
        Fix("On a TV / monitor, the connected controller should be the Thor's",
            "${Say.path(NavCards.TV)} (on by default). It plays as the Thor's controller (same name, player 1) until you unplug the screen. A controller without a Home button: hold Select + Start for Wayfinder's combos.", NavCards.TV),
        // device pass (GC5's note): a game inside an emulator has Game controls of its own — the Games subtitle said it until GC5
        // shortened it. No row: it's done in the game (A on the card would do nothing)
        Fix("One game in an emulator needs other controls",
            "A game inside an emulator can have its own Game controls, apart from the emulator and its other games." +
                (gc?.let { " In that game, $it opens them." } ?: "")),
        // dev build 15 (C6): nothing hidden is listed any more — what's left is an emulator's saved place
        Fix("An emulator lost a controller",
            "Emulators remember a controller by its id and place: map it once in the emulator (once more after this update). Wayfinder keeps both: a controller off for less than 30 minutes, or reconnected, keeps its place. Changing the player order changes places.", NavCards.ORDER),
        // dev build 15 (WP2, D1 / critique M2): each controller's own face — in games too. Build 18 (B23): its page by the path
        Fix("A controller's face buttons are swapped in a game",
            "Each controller uses its own face buttons: on a PlayStation or Xbox one the bottom button confirms — in games too, so an emulator set up before sees them swapped. Map it again in the emulator, or choose “Like the Thor” on its page (${Nav.place(NavPages.CONTROLLER_PAGE)}).", page = HubPage.CONTROLLER),
        // build 18 (GitHub #83)
        Fix("A game won't start while Wayfinder is on",
            "Some games refuse to run beside an accessibility app. Turn Wayfinder off: “Turn off” beside Accessibility service above, " +
                "a combo or a quick panel shortcut. To turn it back on, open Wayfinder: one press."),
        // build 18 (B11, MORE-05): its Fix goes to the System access row (Status, above)
        Fix("Moving apps, brightness or sleep do nothing",
            "Check “${NavCards.HELP_ACCESS.title}” in the status above. If it isn't working: turn off “Force SELinux” in the Thor's settings, then restart the Thor.",
            NavCards.HELP_ACCESS),
    )
    // QA2: a bottom screen stuck black while touch still works — whatever did it, one press brings it back. Build 18 (B20,
    // MORE-D25 / MORE-D26): a card like the questions (its answer inside), its row with the fix's icon (was an "i")
    SettingCard("Bottom screen stays black", short = "Touch still works on it? One press turns it back on, whatever turned it off.",
        more = "Another app, AYN's own bottom-off, a brightness at 0. \"Bottom screen off / on\" does the same when the screen is dark. " +
            "If one app stays black, move it to the top and back.") {
        GlassListRow("Turn it back on", icon = Icons.Rounded.Build, id = "fix:bottom") { ForegroundAppService.repairBottomScreen() }
    }
    for (f in items) FixCard(f, go)
}

/** Build 18 (B20, MORE-D26): one question — its answer's first sentence in the card (the rest while the controller is on it,
 *  or behind "?"), and a "Fix" row when it opens the place to fix it, or fixes it at once. No row where A would do nothing
 *  (B11, MORE-05): then the whole answer is the card's line. A card on Help itself is focused in place. */
@Composable
private fun FixCard(f: Fix, go: (String) -> Unit) {
    val entry = app.wayfinder.ui.LocalEntryFocus.current
    val card = f.card; val page = f.page; val run = f.run
    val act: (() -> Unit)? = when {
        run != null -> run
        card != null && card.page == HubPage.HELP -> ({ entry?.request(card.id) })
        card != null -> ({ goTo(card, go) })
        page != null -> ({ go(page) })
        else -> null
    }
    if (act == null) { SettingCard(f.q, short = f.a, more = null); return }
    val (first, rest) = firstSentence(f.a)
    SettingCard(f.q, short = first, more = rest) {
        GlassListRow(if (run != null) "Fix it now" else "Fix", icon = Icons.AutoMirrored.Rounded.HelpOutline, id = "fix:${f.q}",
            end = if (run != null) RowEnd.NONE else RowEnd.CHEVRON) { act() }
    }
}

/** Build 18 (B20, MORE-D24): a Help & status row whose value is a state — what works in the positive colour, a problem in the
 *  warning colour ([ok] null: neither). A list row's look (GlassListRow's value is always the secondary text). */
@Composable
private fun StatusRow(label: String, value: String, ok: Boolean?, icon: ImageVector, modifier: Modifier = Modifier, id: String = label,
                      onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier.fillMaxWidth(), radius = Radii.Row, focusId = id) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp))
            Text(label, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, color = when (ok) { true -> g.positive; false -> g.warn; null -> g.textSecondary },
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HelpPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) {
    val g = LocalGlass.current
    // the footer (disclaimer + version) comes into view when the controller reaches the last row
    val footer = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val footerScope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = LocalContext.current
    val st = rememberHubStatus()
    // build 18 (B20, MORE-D24): back to a row (its page, Search) → that row; else the first status row with a problem (the
    // service's own row comes first; then a setup check's button) — read before SubPage takes the row
    val returning = remember { app.wayfinder.ui.ReturnFocus.pending != null }
    val problem = remember { FocusRequester() }
    SubPage(myDisplayId, NavPages.title(HubPage.HELP), if (st.ready) "Everything is set up" else "A couple of things need attention", onBack) {
        // after the page's own entry (composed after it, so this one wins): the first failing check
        LaunchedEffect(Unit) { if (!returning && ForegroundAppService.isRunning) problem.requestAfterLayout() }
        // ── build 18 (B20, MORE-D24): Status first — the service and the setup checks, system access, the input layer, the screens
        SectionHeader("Status")
        // build 18 (GitHub #83): "Turn off" beside it — for games that refuse to run beside an accessibility app (two presses).
        // Once it's off the button goes: the controller lands on the row (it went nowhere)
        val a11yRow = remember { FocusRequester() }
        var turnedOff by remember { mutableStateOf(false) }
        LaunchedEffect(st.running) { if (!st.running && turnedOff) { turnedOff = false; a11yRow.requestAfterLayout() } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusRow(NavCards.A11Y.title, if (st.running) "working ✓" else "off — press to turn on", st.running, Icons.Rounded.Accessibility,
                Modifier.weight(1f).focusRequester(a11yRow), id = NavCards.A11Y.id) {
                if (st.running) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else Setup.enableService(ctx)
            }
            if (st.running) ConfirmButton("Turn off", "Press again to turn off", { turnedOff = true; ActionRegistry.perform(ThorAction.WAYFINDER_OFF) })
        }
        // (FTU-18): the tour's setup checks, in its words (the Battery optimisation row was one of them)
        SetupChecks(firstProblem = problem)
        StatusRow(NavCards.HELP_ACCESS.title, if (st.backend) "working ✓" else "not reachable — press to check again", st.backend,
            Icons.Rounded.Memory, id = NavCards.HELP_ACCESS.id) { Thread { PServiceBridge.isAvailable() }.apply { isDaemon = true }.start() }
        if (!st.backend) Caption("If it stays unreachable: turn off “Force SELinux” in the Thor's settings, then restart the Thor.")
        // 1.3 (GitHub #39): the input layer's state — and why it stopped, to put in a report
        val layerWhy = PadLayerCtl.failReason
        StatusRow(NavCards.HELP_LAYER.title, when {
            PadLayerCtl.active -> if (PadLayerCtl.compatMode) "working ✓ · compatibility mode" else "working ✓"
            !PadLayerCtl.wanted && layerWhy.isEmpty() -> if (PadLayerCtl.mode == PadLayerCtl.Mode.NOWHERE) "off — press to turn it on" else "off for this app"
            layerWhy.isNotEmpty() -> "stopped: $layerWhy — press to try again"
            else -> "starting…"
        }, when { PadLayerCtl.active -> true; layerWhy.isNotEmpty() -> false; else -> null }, Icons.Rounded.SportsEsports,
            id = NavCards.HELP_LAYER.id) { if (!PadLayerCtl.active) { PadLayerCtl.set(ctx, true) } }
        SectionHeader("Screens right now")
        GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                st.displayIds.forEachIndexed { i, id ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (i == 0) "Top" else "Bottom", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(if (id == myDisplayId) "Wayfinder" else st.displayApps[id]?.let { appLabel(ctx, it) } ?: "Home screen", color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // 1.4: other apps that do what a part of Wayfinder does. Build 18 (B20, MORE-D25): a way to their page (chevron), not an "i"
        var othersTick by remember { mutableStateOf(0) }
        val othersLc = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(othersLc) { othersLc.repeatOnLifecycle(Lifecycle.State.RESUMED) { othersTick++ } }
        val others = remember(othersTick) { OtherApps.detected(ctx) }
        if (others.isNotEmpty()) {
            SectionHeader("Other apps on this Thor")
            for (o in others) GlassListRow(o.name + if (o.handled) " — handled" else " — can conflict",
                icon = Icons.Rounded.Apps, id = "other:${o.pkg}", end = RowEnd.CHEVRON) { go(o.page) }.also {
                Text(o.text, color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
            }
        }
        SectionHeader("If something's wrong")
        Troubleshooting(go)
        BackupSection(myDisplayId)
        // ── About: the tour, the combos, updates, support, the licenses, the version
        SectionHeader("About")
        GlassListRow("Take the tour", value = "2 min", icon = Icons.Rounded.Explore, end = RowEnd.CHEVRON) { go(HubPage.TOUR) }
        // build 18 (B20, FTU-22): the combos under the tour — Game controls' combo and the hold that lists them all
        GlassListRow(NavPages.title(HubPage.CONTROLS), value = Say.combo(ThorAction.GAME_CONTROLS)?.let { "$it: Game controls · ${Say.hold(Say.btn(ThorButton.HOME))}: all" } ?: "",
            icon = Icons.Rounded.SportsEsports, id = HubPage.CONTROLS, end = RowEnd.CHEVRON) { go(HubPage.CONTROLS) }
        // 1.3.1 — the daily update check (the only other internet use, besides guides and Ko-fi)
        SettingCard(NavCards.UPDATES,
            (if (UpdateCheck.enabled) "Once a day, Wayfinder asks GitHub for the latest version number — nothing about you is sent. " +
                "A new version shows as “Update available” next to Ready."
            else "Off — Wayfinder never checks. New versions are on the GitHub page.") +
                (UpdateCheck.available?.let { " Version $it is out." } ?: ""),
            checked = UpdateCheck.enabled, onChecked = { UpdateCheck.setEnabled(ctx, it) })
        GlassListRow("Support Wayfinder", value = "Support me on Ko-fi · ko-fi.com/thorwayfinder", icon = Icons.Rounded.Coffee,
            end = RowEnd.EXTERNAL) { openKofi(ctx, myDisplayId) }
        GlassListRow("Open-source licenses", Modifier.onFocusChanged { if (it.isFocused) footerScope.launch { delay(60); footer.bringIntoView() } },
            value = "The libraries inside Wayfinder", icon = Icons.Rounded.Gavel, id = Nav.rowTo(HubPage.LICENSES), end = RowEnd.CHEVRON) { go(HubPage.LICENSES) }
        Text("Wayfinder ${BuildConfig.VERSION_NAME} is an independent project, not affiliated with, endorsed or sponsored by " +
            "AYN or any other company named in it. AYN, Thor and Odin are trademarks of AYN; other names are trademarks of " +
            "their owners, used only to describe compatibility.",
            color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodySmall)
        // 1.3.1 — the version on its own line, easy to find for a bug report
        Text("Version ${BuildConfig.VERSION_NAME}", color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).bringIntoViewRequester(footer))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1.4: More, Sound, Hints & pop-ups, Features, Battery, the setup choice.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MorePage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.MORE), "Everything else, one press away", onBack) {
        // build 18: each row named as its page (B11, MORE-10 — "Features", its value lower case), its id the page's (B12, pF21:
        // Back from a page opened elsewhere — a quick panel tile — lands on its row), a chevron (B11, D21: each opens a page)
        GlassListRow(NavPages.title(HubPage.SOUND), value = soundSummary(), icon = Icons.Rounded.VolumeUp, id = HubPage.SOUND,
            end = RowEnd.CHEVRON) { go(HubPage.SOUND) }
        if (Features.lights) GlassListRow(NavPages.title(HubPage.LIGHTS), value = lightsSummary(), icon = Icons.Rounded.Lightbulb,
            id = HubPage.LIGHTS, end = RowEnd.CHEVRON) { go(HubPage.LIGHTS) }
        GlassListRow(NavPages.title(HubPage.APPEARANCE), value = appearanceSummary(), icon = Icons.Rounded.Palette, id = HubPage.APPEARANCE,
            end = RowEnd.CHEVRON) { go(HubPage.APPEARANCE) }
        GlassListRow(NavPages.title(HubPage.HINTS), value = hintsSummary(), icon = Icons.Rounded.ChatBubbleOutline, id = HubPage.HINTS,
            end = RowEnd.CHEVRON) { go(HubPage.HINTS) }
        GlassListRow(NavPages.title(HubPage.FEATURES), value = "make Wayfinder simpler · ${featuresSummary()}", icon = Icons.Rounded.ToggleOn,
            id = HubPage.FEATURES, end = RowEnd.CHEVRON) { go(HubPage.FEATURES) }
        // build 18 (review): turned off on purpose — Help's "Accessibility service" row turns it back on. (B20, FTU-22): what Help
        // holds now
        GlassListRow(NavPages.title(HubPage.HELP), value = if (ForegroundAppService.isRunning) "tour · combos · troubleshooting · backup"
            else if (Setup.offByUser(LocalContext.current)) "Wayfinder is off — turn it back on" else "something needs setup",
            icon = Icons.AutoMirrored.Rounded.HelpOutline, id = HubPage.HELP, end = RowEnd.CHEVRON) { go(HubPage.HELP) }
    }

private fun soundSummary(): String = listOfNotNull(
    if (SpeakerTune.enabled) "speaker fix" else null,
    if (SpeakerTune.boost > 0) "+${SpeakerTune.boost} dB" else null,
    if (LinkedVolume.enabled) "linked volume" else null,
    if (HeadphoneEq.Out.values().any { HeadphoneEq.preset(it) != 0 }) "headphones EQ" else null,
).joinToString(" · ").ifEmpty { "volume, boost, speaker fix" }

private fun hintsSummary(): String = listOf(
    AppSettings.controlsSheet, AppSettings.chordHint != 2, AppSettings.recentsHint != 2).count { it }.let { n ->
        if (n == 0) "all off" else "$n of 3 on" }

private fun featuresSummary(): String = listOf(Features.moreCombos, AppSettings.aynButtonOurs, Features.gameControls,
    Features.deck, Features.lights).count { !it }.let { if (it == 0) "everything on" else "$it turned off" }

@Composable
private fun SoundPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.SOUND), "Each screen's volume, the speaker fix and a headphones EQ", onBack) {
        // build 18 (B3): the registry's cards (Search opens the page on them)
        SettingCard(NavCards.VOLUME, "\"Both screens\" keeps the difference between them. Top and Bottom set how loud each screen is compared with the other.") {
            VolumeSliders()
        }
        SpeakerFixCard()
        HeadphoneEqCard()
        // 1.4 (§10.2): the rest behind one row
        MoreOptions(cards = listOf(NavCards.BOOST, NavCards.LINKED)) {
        // 1.3 (Reddit request): the volume booster
        SettingCard(NavCards.BOOST, if (SpeakerTune.boost == 0) "Louder than the maximum: extra gain with a limiter (no crackle), on the speakers, headphones and Bluetooth"
            else "${SpeakerTune.boostStatus()} — with headphones, start low: it's louder than their usual maximum") {
            GlassSegmentedControl(SpeakerTune.BOOST_STEPS.map { if (it == 0) "Off" else "+$it dB" },
                SpeakerTune.BOOST_STEPS.indexOf(SpeakerTune.boost).coerceAtLeast(0), Modifier.fillMaxWidth()) { SpeakerTune.setVolumeBoost(SpeakerTune.BOOST_STEPS[it]) }
        }
        SettingCard(
            NavCards.LINKED,
            if (LinkedVolume.enabled) "The buttons turn both screens up and down together, keeping their difference"
            else "Off — the buttons only change the top screen (the Thor's default)",
            checked = LinkedVolume.enabled, onChecked = { LinkedVolume.setLinked(it) },
        )
        }
    }

@Composable
private fun SpeakerFixCard() {
        SettingCard(
            NavCards.SPEAKER,
            "Clearer, louder, wider speakers — never applied to headphones. The community's Thor speaker tuning (EQ by Joey, Retro Handhelds), built in: its EQ, +15 dB with a limiter, and a stereo widener." +
                (if (SpeakerTune.externalDspInstalled() != null) " Turning it on pauses your equalizer app (switch that back on yourself if you go back to it)." else "") +
                (if (SpeakerTune.enabled) " " + SpeakerTune.status else ""),
            checked = SpeakerTune.enabled, onChecked = { SpeakerTune.setOn(it) },
        ) {
            if (SpeakerTune.enabled) {
                val eqRow = remember { FocusRequester() }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                // A/B: the equalizer + loudness on its own (the widener has its slider; 1× = off). Build 18 (B17, MORE-D05): a
                // switch — "Equalizer on | Equalizer off" segments read as two settings
                GlassSwitchRow("Equalizer", SpeakerTune.eqOn, { SpeakerTune.setEq(it) }, Modifier.fillMaxWidth(), inset = true,
                    explanation = if (SpeakerTune.eqOn) "Joey's EQ and loudness — turn it off to hear the difference" else "Off — only the widener and the limiter",
                    focusRequester = eqRow)
                // (B11, MORE-03): the widener couldn't be added — said, with a way to try again (its slider did nothing); the notice
                // goes with its button, so the focus moves up to the Equalizer row. Build 18 (widener review): a layer that isn't
                // Wayfinder's refused it (another tool's, often left by its uninstall) — a restart of the Thor clears that
                if (SpeakerTune.installFailed) GlassNotice(if (SpeakerTune.failedOutside) "Stereo widening isn't available right now. Restarting the Thor clears it."
                    else "Stereo widening isn't available right now.", NoticeKind.INFO, Modifier.fillMaxWidth(),
                    action = "Try again", onAction = { SpeakerTune.retryWidener(); scope.launch { eqRow.requestAfterLayout() } })
                else StereoWidthSlider()
            }
        }
}

/** 1.4 (Reddit): an EQ per output — each remembers its own; the one playing is marked. */
@Composable
private fun HeadphoneEqCard() {
    val v = HeadphoneEq.version
    @Suppress("UNUSED_EXPRESSION") v
    val playing = HeadphoneEq.playing
    var out by remember { mutableStateOf(playing ?: HeadphoneEq.Out.WIRED) }
    // build 18 (B17, MORE-D06 / FTU-20): what plays now first, the rest behind "?" (split by length, "(headsets, DACs)" was
    // lifted out of its list); (MORE-D07) the card's own spacing between its rows — no Spacers of its own
    SettingCard(NavCards.HEADEQ, short = playing?.let { "Playing now: ${it.title}." } ?: "Playing now: the speakers.",
        more = "Each output keeps its own EQ — it follows what you plug in. USB audio covers headsets and DACs.") {
        // (B11, MORE-20c): the output being edited — a picker, so labelled (it read like a setting of its own)
        GlassSegmentedControl(HeadphoneEq.Out.values().map { if (it == playing) "${it.title} ♪" else it.title }, out.ordinal, Modifier.fillMaxWidth(),
            label = "Editing") { out = HeadphoneEq.Out.values()[it] }
        // (B17, MORE-D04): "EQ" — "Sound   Off" read like a mute
        GlassSegmentedControl(HeadphoneEq.PRESETS.map { it.first }, HeadphoneEq.preset(out), Modifier.fillMaxWidth(), label = "EQ") {
            HeadphoneEq.choose(out, it) }
        // the curve to shape (a preset shows its own; shaping it makes it Custom)
        EqCurve(out, HeadphoneEq.gains(out) ?: List(HeadphoneEq.FREQS.size) { 0f })
    }
}

@Composable
private fun HintsPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.HINTS), "What Wayfinder shows on its own — each can go", onBack) {
        // 1.3 (GitHub #13): the Recents hint — smaller, off, or in a corner (it covered the middle card's icon). Build 18 (B11,
        // MORE-18 / MORE-11 / pF19): the keys drawn as the hand's badges (A7's tokens) — no font arrows, no typed Select / Start
        // (Create / Options on a DualSense); Select closes every app on its second press (E8, decision H8)
        SettingCard(NavCards.RECENTS_HINT, when (AppSettings.recentsHint) {
            // device pass: the choice is "Symbols" (was "Compact" here) — icons stand for the words
            1 -> "Symbols: one slim line, icons for the words — ${Say.keyMenu(ThorButton.A)} open · ${Say.keyMenu(ThorButton.Y)} close · ${Say.keyMenu(ThorButton.B)} back"
            2 -> "Off — the buttons still work: ${Say.keyMenu(ThorButton.A)} opens, ${Say.keyMenu(ThorButton.Y)} closes, " +
                "${Say.key(ThorButton.SELECT)} twice closes all, ${Say.key(ThorButton.START)} goes home, ${Say.keyMenu(ThorButton.B)} goes back"
            else -> "One line: what the buttons do in Recent apps, for a few seconds when it opens"
        }) {
            GlassSegmentedControl(listOf("Words", "Symbols", "Off"), AppSettings.recentsHint, Modifier.fillMaxWidth()) {
                AppSettings.chooseRecentsHint(it, AppSettings.recentsHintAt) }
            // build 18 (B17, MORE-D07): the card's own spacing (no Spacer)
            if (AppSettings.recentsHint != 2) GlassSegmentedControl(listOf("Top", "Top left", "Top right", "Bottom left", "Bottom right"),
                AppSettings.recentsHintAt, Modifier.fillMaxWidth(), label = "Where") { AppSettings.chooseRecentsHint(AppSettings.recentsHint, it) }
        }
        // 1.4 (GitHub #52): the pop-ups. Build 18 (B11 addendum, ps-F7): Home, Back and AYN in the hand's names
        SettingCard(NavCards.COMBO_LIST, when (AppSettings.chordHint) {
            1 -> "Shows after a longer hold — quick combos never flash it"
            2 -> "Off — the combos still work"
            else -> "${Say.hold(Say.btn(ThorButton.HOME), cap = true)}, ${Say.btn(ThorButton.BACK)} or ${Say.btn(ThorButton.AYN)}: the combos you can press show after a moment"
        }) {
            GlassSegmentedControl(listOf("Soon", "Later", "Off"), AppSettings.chordHint, Modifier.fillMaxWidth()) { AppSettings.chooseChordHint(it) }
        }
        // build 18 (B18, MORE-D20): with Wayfinder on the bottom screen the sheet can't show — the card says so, on or off, and where
        SettingCard(NavCards.YOUR_COMBOS,
            if (!AppSettings.hubOnTop) "Only when Wayfinder opens on the top screen — it opens on the bottom now: ${Say.path(NavCards.HUB_SCREEN)}"
            else if (AppSettings.controlsSheet) "While Wayfinder is open on the top screen, the bottom screen shows your combos"
            else "Off — the bottom screen keeps what it shows",
            checked = AppSettings.controlsSheet, onChecked = { AppSettings.setControlsSheetOn(it) })
    }

/** 1.4: AYN's own charging switches (the quick panel's tiles), readable and set here too. */
@Composable
internal fun BatterySwitches() {
    val ctx = LocalContext.current
    // live: the lines say what the charger does NOW (QA2 — "the battery stops at 80 %" while it sat at 100 %)
    var n by remember { mutableStateOf(Charging.now(ctx)) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); n = Charging.now(ctx) } }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    // build 18 (B3): the registry's cards (Search opens Battery on them). (B9, D11): "AYN's own setting" always behind "?" — a
    // split by length showed it under some lines and hid it under others
    SettingCard(NavCards.LIMIT80, short = Charging.limitLine(n), more = "AYN's own setting", checked = n.limit,
        onChecked = { on -> n = n.copy(limit = on); Charging.set(ctx, limit = on) { main.post { n = Charging.now(ctx) } } })
    SettingCard(NavCards.DIRECT, short = Charging.directLine(n), more = "AYN's own setting", checked = n.direct,
        onChecked = { on -> n = n.copy(direct = on); Charging.set(ctx, direct = on) { main.post { n = Charging.now(ctx) } } })
}

/** The two setups (the tour's first question, the Features page). Build 18 (B10, MORE-04): the setup the parts make now is
 *  drawn selected (A10's look: the fill and a corner check) and is where the focus lands — [focus], or the page's entry —
 *  "Everything" when it's a mix of your own; "Just switching screens" over parts that are on asks first ("Press again: turns
 *  off 5 parts"); each press says what it did. */
@Composable
fun SetupChoice(modifier: Modifier = Modifier, focus: FocusRequester? = null, onChosen: () -> Unit = {}) {
    val ctx = LocalContext.current
    val display = androidx.compose.ui.platform.LocalView.current.display?.displayId ?: 0
    val current = Features.preset()
    val on = Features.partsOn()
    val screens = current == Features.Preset.JUST_SCREENS
    val ask = app.wayfinder.ui.rememberConfirmState(current)
    val entry = app.wayfinder.ui.LocalEntryFocus.current
    val parts = if (on == 1) "1 part" else "$on parts"
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // the page opens on the current setup: the other card offers no entry
        androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides entry.takeIf { screens }) {
            PresetCard("Just switching screens", Icons.Rounded.SwapVert,
                if (ask.asked) "Press again: turns off $parts" else "Move and swap apps, Recents, Home per screen",
                screens, ask.asked, Modifier.weight(1f), focus.takeIf { screens }) {
                if (screens) { Features.justScreens(ctx); onChosen() }
                else ask.press {
                    Features.justScreens(ctx)
                    Overlays.pill("Just switching screens: $parts off — Everything brings them back.", CueOrigin.Window(display))
                    onChosen()
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides entry.takeIf { !screens }) {
            PresetCard("Everything", Icons.Rounded.AutoAwesome, "Plus combos, quick panel, Game controls, lights",
                current == Features.Preset.EVERYTHING, false, Modifier.weight(1f), focus.takeIf { !screens }) {
                ask.cancel()
                Features.everything(ctx)
                Overlays.pill("Everything is on.", CueOrigin.Window(display))
                onChosen()
            }
        }
    }
}

/** Build 18 (B10): one setup — a big action tile ([GlassActionButton]'s layout) that can be the selected one (the accent fill
 *  and its corner check, as every selected option); [asking]: its line is the confirmation ("Press again: …"). */
@Composable
private fun PresetCard(label: String, icon: ImageVector, subtitle: String, selected: Boolean, asking: Boolean, modifier: Modifier,
                       focus: FocusRequester?, onClick: () -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Radii.Action)
    FocusableGlass(onClick = onClick, modifier = modifier, radius = Radii.Action, focusRequester = focus, role = FocusRole.OPTION) {
        Box(Modifier.fillMaxWidth().then(if (selected) Modifier.selectedFill(g, shape) else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, null, tint = if (selected) g.onAccent else g.accent, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, color = if (selected) g.onAccent else g.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, color = when { selected -> g.onAccent.copy(alpha = 0.85f); asking -> g.accent; else -> g.textSecondary },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = g.onAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp).size(16.dp))
        }
    }
}

/** Once, on the Hub: small — the six cards must still fit the top screen. */
@Composable
private fun SetupChoiceCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Tile) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                // build 18 (B21, FTU-07): "New:" only after an update — a new install has nothing new
                val update = remember { AppSettings.isUpdate(ctx) }
                Text(if (update) "New: make Wayfinder simpler?" else "Keep Wayfinder simple?", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Just the screen switcher, or everything. Nothing is deleted — ${Nav.place(HubPage.FEATURES)} any time.",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            GlassButton("Just switching screens", { Features.justScreens(ctx) })
            GlassButton("Everything", { Features.everything(ctx) })
            GlassButton("Keep it as it is", { Features.markAsked() })
        }
    }
}

@Composable
private fun FeaturesPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.FEATURES), "Turn off what you don't use — Wayfinder gets simpler. Nothing is deleted.", onBack) {
        val ctx = LocalContext.current
        val g = LocalGlass.current
        SetupChoice()
        SectionHeader("Always on")
        // build 18 (B11, MORE-05): information, not a row (A did nothing on it)
        GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Row) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.SwapVert, null, tint = g.accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("Screen switching", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Caption("Move and swap apps · Recents · Home on each screen · the controller between screens · app pairs")
                }
            }
        }
        SectionHeader("Parts")
        // review WP9-5: the Home that opens them (the Thor's, "on the Thor", when the hand's controller gives its Home to the game)
        SettingCard("More combos", if (Features.moreCombos) "Screenshots, brightness and volume, Game controls, Keyboard & mouse… on " +
                Say.comboHome(ctx, app.wayfinder.ui.currentLook(), then = " + a button")
            else "Off — only the screen-switching combos work (your other combos are kept for later)",
            checked = Features.moreCombos, onChecked = { Features.enableMoreCombos(it) })
        SettingCard("Quick panel", if (AppSettings.aynButtonOurs) "The AYN button opens Wayfinder's panel: brightness, volume, performance, your shortcuts"
            else "Off — the AYN button opens AYN's own drawer",
            checked = AppSettings.aynButtonOurs, onChecked = { AppSettings.setAynButtonOursOn(it) })
        // dev build 17 (WP11, rule 7): one editor — where Game controls work, or off, is the Controller page's card; here its
        // value and the way there (dev build 17, WP9: it opens the Controller page on that card). Build 18 (B11 addendum,
        // MORE-D22): a card like its siblings, the way there its row (the icon and the loose caption are gone)
        SettingCard(NavCards.FEATURES_GC.title, "Each game its own buttons, gyro and macros") {
            GlassListRow("Where", value = if (Features.gameControls) PadLayerCtl.mode.label else "Off", end = RowEnd.CHEVRON,
                id = NavCards.FEATURES_GC.id) { goTo(NavCards.LAYER, go) }
        }
        // dev build 16 (theme F): its combo as the controller in hand says it
        SettingCard("Keyboard & mouse", if (Features.deck) "Keys, a trackpad and the game's guide on the other screen" +
                (Say.combo(ThorAction.KEYBOARD, app.wayfinder.ui.currentLook())?.let { " ($it)" } ?: "")
            else "Off", checked = Features.deck, onChecked = { Features.enableDeck(it) })
        SettingCard("Stick lights", if (Features.lights) "Effects and colours for the lights around the sticks" else "Off — AYN's own lights, untouched",
            checked = Features.lights, onChecked = { Features.enableLights(ctx, it) })
    }

/** The third-party notices (assets/THIRD_PARTY_NOTICES.txt — the same file as in the source repository). Build 18 (B11,
 *  MORE-22 / MORE-D28): one block per section (its heading, then its paragraphs) — about 8 stops, not one per paragraph — and
 *  the file's hard line wraps joined, so the text fills the card; a block taller than the screen scrolls with the D-pad before
 *  the focus moves on. */
@Composable
private fun LicensesPage(myDisplayId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sections = remember {
        licenseSections(runCatching { ctx.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault(""))
    }
    SubPage(myDisplayId, NavPages.title(HubPage.LICENSES), "Wayfinder is built with these libraries", onBack) {
        for ((head, paras) in sections) {
            if (head != null) SectionHeader(head)
            if (paras.isNotEmpty()) LicenseBlock(paras.joinToString("\n\n"))
        }
    }
}

/** Build 18 (B11, MORE-22): the notices as (heading, its paragraphs) — a line underlined / framed with ===== or ----- is a
 *  heading; a hard-wrapped line (60 characters or more) joins the next one, a short one (a name, a copyright) keeps its
 *  break, and so does a list item. */
internal fun licenseSections(text: String): List<Pair<String?, List<String>>> {
    val out = ArrayList<Pair<String?, MutableList<String>>>()
    for (p in text.replace("\r\n", "\n").split(Regex("\\n\\s*\\n")).map { it.trimEnd() }.filter { it.isNotBlank() }) {
        val lines = p.lines().filterNot { l -> l.isNotBlank() && l.trim().all { it == '=' || it == '-' } }
        if (lines.size == 1 && lines.size < p.lines().size) { out += lines[0].trim() to mutableListOf(); continue }
        val sb = StringBuilder()
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (i > 0) sb.append(if (lines[i - 1].trim().length >= 60 && !LICENSE_LIST_ITEM.matches(l)) " " else "\n")
            sb.append(l)
        }
        if (out.isEmpty()) out += null to mutableListOf()
        out.last().second += sb.toString()
    }
    return out
}

private val LICENSE_LIST_ITEM = Regex("^([-*•]|\\(?[0-9a-zA-Z]{1,2}[.)])\\s.*")

/** One section's text, one focus stop. Taller than the screen: Down / Up scroll through it first (the page's own scroll,
 *  [LocalPageScroll]), then the focus moves on. */
@Composable
private fun LicenseBlock(text: String) {
    val g = LocalGlass.current
    val scroll = LocalPageScroll.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val bounds = remember { floatArrayOf(0f, 0f) }       // its top and bottom in the window
    FocusableGlass(onClick = {}, modifier = Modifier.fillMaxWidth()
        .onGloballyPositioned { val r = it.boundsInWindow(); bounds[0] = r.top; bounds[1] = r.bottom }
        .onKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || scroll == null) return@onKeyEvent false
            val down = e.key == androidx.compose.ui.input.key.Key.DirectionDown
            val up = e.key == androidx.compose.ui.input.key.Key.DirectionUp
            if ((down && bounds[1] > view.height - edge) || (up && bounds[0] < edge)) {
                scope.launch { scroll.animateScrollBy(if (down) 360f else -360f) }; true
            } else false
        }, radius = Radii.Control, strong = false) {
        Text(text, color = g.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search: every setting and feature, by name or by what you'd call it.
// ─────────────────────────────────────────────────────────────────────────────

// Build 18 (B3): the entries, their places and the ranking are in SearchIndex.kt (pure: SearchIndexTest reads them).

/** Search. Build 18 (B1, hF1 / hF23): the field is the shared ControllerTextField, in the page's focus order — B closes the
 *  keyboard and leaves the focus on the field (its ring), Down goes to the first result, Up from it comes back, A types
 *  again (the text kept, the cursor at its end). (B2, hF5): the query and the result opened are kept — Back from a result's
 *  page comes back here, on that result, keyboard closed. (B3): a result opens its card ([openSearchResult]). (B4): an empty search shows
 *  Suggestions; while typing, ↵ opens the first result (it says so). */
@Composable
private fun SearchPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) {
    val g = LocalGlass.current
    var q by remember { mutableStateOf(SearchState.query) }
    // B2: back from a result (hubBack set it): no typing, that result focused — read once, then cleared
    val returning = remember { SearchState.returning }
    val returnTo = remember { app.wayfinder.ui.ReturnFocus.take() ?: SearchState.lastResult.takeIf { returning } }
    LaunchedEffect(Unit) { SearchState.returning = false }
    // opened from the Hub (an empty query): straight into typing, the keyboard up — as before
    val typing = remember { q.isEmpty() && !returning }
    val fieldGate = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val look = app.wayfinder.ui.currentLook()
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + ExtPadsCtl.version.intValue
    // T18-09: the controller-only entries only while one is connected. Round 2 (B3): an action alone only while its part is on
    val results = if (q.isBlank()) SUGGESTIONS else search(q, hasPad = ExtPadsCtl.byPlayer().isNotEmpty(), allows = Features::allows)
    val open: (SearchEntry) -> Unit = { e -> openSearchResult(e, go) }
    SubPage(myDisplayId, NavPages.title(HubPage.SEARCH), "Settings and features", onBack, entry = false) {
        // the page's entry: the result to come back to, else the field's gate (it offers itself when not typing) — typing from
        // the start, the field takes the focus and the keyboard itself (ControllerTextField), and the entry moves nothing
        val entry = app.wayfinder.ui.rememberEntryFocus(returnKey = returnTo, skip = { typing || fieldFocused })
        // device pass (P1-2, the page half): typing from the start, the page never stays with nothing focused (a B on that blank
        // page left Search) and the keyboard comes up even when the window gets the focus after the field ([SearchArrival])
        if (typing) SearchArrival(fieldGate, firstResult.takeIf { results.isNotEmpty() }) { fieldFocused }
        androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides entry) {
            SearchField(q, { q = it; SearchState.query = it }, typing, fieldGate,
                down = if (results.isNotEmpty()) firstResult else FocusRequester.Default,
                onFocus = { fieldFocused = it },
                // ↵: the first result opens (its row says "↵ opens"); with nothing typed, the focus goes to the suggestions
                onSubmit = {
                    val first = results.firstOrNull()
                    if (first != null && q.isNotBlank()) open(first) else if (first != null) runCatching { firstResult.requestFocus() }
                })
            when {
                q.isBlank() -> SectionHeader("Suggestions")
                results.isEmpty() -> app.wayfinder.ui.EmptyState("Nothing matches “$q”.")
                else -> Caption(if (results.size == 1) "1 result" else "${results.size} results")
            }
            results.forEachIndexed { i, e ->
                SearchResultRow(e, look, focus = if (i == 0) firstResult else null, upTo = if (i == 0) fieldGate else null,
                    opens = i == 0 && fieldFocused && q.isNotBlank()) { open(e) }
            }
        }
    }
}

/** B1: the search field — the shared ControllerTextField in the glass row, the icon inside it; its ring when the keyboard is
 *  closed. [down]: the first result (Down from the field). */
@Composable
private fun SearchField(q: String, onChange: (String) -> Unit, typing: Boolean, gate: FocusRequester, down: FocusRequester,
                        onFocus: (Boolean) -> Unit, onSubmit: () -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Radii.Tile)
    val tell by androidx.compose.runtime.rememberUpdatedState(onFocus)
    Box(Modifier.fillMaxWidth()) {
        app.wayfinder.ui.ControllerTextField(q, onChange,
            Modifier.fillMaxWidth().onFocusChanged { tell(it.hasFocus) }.focusProperties { this.down = down },
            textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = 17.sp),
            placeholder = "Try “screenshot”, “lock”, “dark”…",
            surface = Modifier.glassSurface(g, shape, raised = true), shape = shape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 52.dp, end = 18.dp, top = 15.dp, bottom = 15.dp),
            startEditing = typing, imeAction = androidx.compose.ui.text.input.ImeAction.Search, onSubmit = onSubmit, gateFocus = gate)
        Icon(Icons.Rounded.Search, null, tint = g.accent, modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp).size(22.dp))
    }
}

/** Device pass (P1-2): the field's own arrival — its text field asked for a few frames, then (failing) its gate for a few
 *  more (ControllerTextField, `requestAfterLayout`'s 3 each) — with a frame or two to spare. */
private const val FIELD_ARRIVAL_FRAMES = 8

/**
 * Device pass (P1-2, the page half): Search opened from the Hub types at once — the field takes the focus and the keyboard
 * on its first frames, or gives the focus to its gate (ControllerTextField). The page makes sure of the outcome:
 * - once the field's arrival is over ([FIELD_ARRIVAL_FRAMES]) and no part of it holds the focus, its [gate] gets it (the
 *   field's ring: A types), else [first] (the first row): never nothing focused — a B on that blank page left Search;
 * - the keyboard is asked again once the window and the field both have the focus: a field focused before its window was
 *   (the Hub brought from another screen) got no keyboard. On the gate nothing is typing: nothing shows.
 * [holds]: the field (its gate or its text) has the focus.
 */
@Composable
private fun SearchArrival(gate: FocusRequester, first: FocusRequester?, holds: () -> Boolean) {
    val has by androidx.compose.runtime.rememberUpdatedState(holds)
    LaunchedEffect(Unit) {
        repeat(FIELD_ARRIVAL_FRAMES) { androidx.compose.runtime.withFrameNanos { }; if (has()) return@LaunchedEffect }
        if (!gate.requestAfterLayout() && !has()) first?.requestAfterLayout()
    }
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(window, keyboard) {
        androidx.compose.runtime.snapshotFlow { window.isWindowFocused && has() }.first { it }
        keyboard?.show()
    }
}

/** B3: open [e] — its card (the page opens on it, its More options too), an action alone on its Combos row (C3 gives the rows
 *  their "combo:<ACTION>" ids; round 2: with no combo, the AYN row that does it or Add a combo on it — [comboLanding]), else
 *  its page. Back comes back to it (B2: its id, [SearchState.lastResult]). */
private fun openSearchResult(e: SearchEntry, go: (String) -> Unit) {
    val id = entryId(e)
    SearchState.lastResult = id
    app.wayfinder.ui.ReturnFocus.clicked = id
    val card = e.card
    val action = e.action
    when {
        card != null -> navTarget(card)?.let(go) ?: run { app.wayfinder.ui.ReturnFocus.clicked = null }
        action != null -> { app.wayfinder.ui.ReturnFocus.pending = "combo:${action.name}"; go(HubPage.CONTROLS) }
        else -> {
            // a page entry: that page's More options open (the entries without a card there are behind it — Keyboard & mouse)
            // (B22: Keyboard & mouse left Keyboard's More options — its entries are in sight now)
            MoreOptionsState.openNext = e.page in listOf(HubPage.SCREENS, HubPage.APPEARANCE, HubPage.SOUND, HubPage.PANEL)
            go(e.page)
        }
    }
}

/** One result: its name, its place (and how its action is done — B4, D6: "AYN hold · 3-finger tap", "no combo" only when
 *  nothing does it), a chevron (B4, D23: was a blue "Open ›" on every row). [opens]: the first result while typing — its
 *  wash and "↵ opens". */
@Composable
private fun SearchResultRow(e: SearchEntry, look: PadLook, focus: FocusRequester?, upTo: FocusRequester?, opens: Boolean, onOpen: () -> Unit) {
    val g = LocalGlass.current
    val line = e.action?.let { a -> "${e.where} — ${Say.howTo(a, look) ?: "no combo"}" } ?: e.where
    FocusableGlass(onClick = onOpen,
        modifier = Modifier.fillMaxWidth().then(if (upTo != null) Modifier.focusProperties { up = upTo } else Modifier),
        radius = Radii.Row, focusRequester = focus, focusId = e.id, role = app.wayfinder.ui.FocusRole.OPEN) {
        Row(Modifier.fillMaxWidth().then(if (opens) Modifier.background(g.accent.copy(alpha = 0.08f)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                // dev build 16 (theme F): in the names of the controller in hand
                app.wayfinder.ui.GlyphText(line, color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
            // device pass (P2-9): ↵ as the keyboard's Enter icon at the words' size — the character drew as a tiny fallback glyph
            if (opens) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null, tint = g.textSecondary, modifier = Modifier.size(16.dp))
                Text("opens", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = g.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Which screen the controller works on — one choice instead of the old two overlapping
 * settings ("stays there after R3 + ↑/↓" and "lock to one screen").
 */
@Composable
internal fun ControllerScreenCard() {
    // dev build 16 (theme F): combos in the names of the controller in hand
    val look = app.wayfinder.ui.currentLook()
    val ctx = LocalContext.current
    val up = Say.combo(ThorAction.FOCUS_SWITCH_UP, look)
    val down = Say.combo(ThorAction.FOCUS_SWITCH_DOWN, look)
    val send = if (up != null && down != null) "$up / $down" else "a combo (set one in Combos)"
    // "Stays where you send it" works by locking the controller wherever it's sent: it must still read
    // as "Stays where you send it", not "Always top" (release test 2026-09-24)
    val mode = when {
        AppSettings.focusSticky -> 1
        AppSettings.focusLockEnabled -> if (AppSettings.focusLockTop) 2 else 3
        else -> 0
    }
    SettingCard(
        NavCards.WHICH_SCREEN,
        when (mode) {
            0 -> "The screen you last touched. $send sends it to the top / bottom one."
            1 -> "The screen you send it to with $send — touching the other screen doesn't take it."
            2 -> "Always the top screen, whatever you touch. $send moves it."
            else -> "Always the bottom screen, whatever you touch. $send moves it."
        } + " ${Say.hold(Say.comboHome(ctx, look), cap = true)} to see your combos and which screen has the controller.",   // review WP9-5
    ) {
        // build 18 (C10, V16): "Stays where you send it" — the strip's and the sheet's words (Say.controllerMode)
        GlassSegmentedControl(listOf("Follows touch", "Stays where you send it", "Always top", "Always bottom"), mode, Modifier.fillMaxWidth()) {
            when (it) {
                0 -> { AppSettings.setFocusLock(false); AppSettings.setFocusStickyOn(false) }
                1 -> { AppSettings.setFocusLock(false); AppSettings.setFocusStickyOn(true) }
                2 -> { AppSettings.setFocusStickyOn(false); AppSettings.setFocusLock(true, top = true) }
                else -> { AppSettings.setFocusStickyOn(false); AppSettings.setFocusLock(true, top = false) }
            }
            ForegroundAppService.reapplyFocusLock()
        }
        // 1.4: the controller goes with a game you move — not for "Always top / bottom"
        if (mode <= 1) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            // dev build 17 (WP8): an inset switch row (was a glass card nested in the card). Build 18 (C7, CTL-15): a 5-word
            // title, what it does now (≤ 20 words; it was ~25), each game's own choice after " — "
            GlassSwitchRow("Follows a game you move", AppSettings.focusFollowsMove, { AppSettings.setFocusFollowsMoveOn(it) },
                explanation = (if (AppSettings.focusFollowsMove) "On: a game you move or swap takes the controller with it"
                    else "Off: the controller stays on its screen") +
                    " — each game can have its own: ${Nav.place(HubPage.APPS)}" + (Say.combo(ThorAction.GAME_CONTROLS, look)?.let { ", or $it" } ?: ""),
                inset = true)
        }
    }
}

/** AYN's "press Home twice" protection, explained and switchable (it breaks Home combos). dev build 17 (WP11): on the
 *  Thor's page. */
@Composable
internal fun HomeTwiceCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { AynHomeGuard.isOn(ctx) }
    SettingCard(
        NavCards.HOME_TWICE,
        if (on) "On (AYN's protection against pressing Home by accident). It holds back the first press, so Wayfinder's Home combos can't work."
        else "Off — one press goes Home, and Wayfinder's Home combos work. Turn on if you keep pressing Home by accident.",
        checked = on, onChecked = { AynHomeGuard.set(it) { ForegroundAppService.later(0) { tick++ } } },
    )
}
