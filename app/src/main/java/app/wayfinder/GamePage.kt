@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.wayfinder

import android.util.Log
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.Caption
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalEntryFocus
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.arrivalTo
import app.wayfinder.ui.rememberEntryFocus
import app.wayfinder.ui.reportingFocusRequester
import app.wayfinder.ui.requestAfterLayout
import kotlinx.coroutines.launch

/**
 * dev build 17 (WP11, D11): a game's or an app's page — ONE page from the Games tile ("remap:<key>") and from Home + the
 * top button in a game ("play:<key>"). Game controls first, exactly one screen tall (the page as it was, unchanged), then the
 * app's own settings Game controls doesn't have: the screen it opens on, when it moves, its combos, Back, its lights, the
 * guide. It replaces the Games tile's options dialog (what Game controls edits — performance, the bottom screen, Player 1,
 * Game controls in it, the controller when it moves, face buttons — is edited there only: one setting, one editor).
 * Opens on Game controls, or on the app's settings for an app that isn't a game, with Game controls switched off, or back
 * from one of its rows ([GamePageRules.start]). In a game the rows that would leave the page say where they are instead
 * (critique K13: the in-game window stays one page — Home + the top button again, a touch on the game or Home close it).
 */
@Composable
fun GamePage(myDisplayId: Int, key: String, inGame: Boolean, openedBy: String?, onBack: () -> Unit,
             onSwitchApp: (String) -> Unit, onAllApps: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val appPkg = GameProfiles.pkgOf(key)
    // the row of its app settings the page comes back to (Back from its combos or lights), or none — taken once
    val returnTo = remember { app.wayfinder.ui.ReturnFocus.take() }
    val isGameApp = remember(appPkg) {
        runCatching { GameApps.isGame(ctx.packageManager.getApplicationInfo(appPkg, 0), appPkg) }.getOrDefault(true) }
    val start = remember {
        GamePageRules.start(inGame, GamePageRules.gamey(isGameApp, GameProfiles.isGame(key) || GameProfiles.forApp(appPkg).isNotEmpty(),
            GameProfiles.runningIn(appPkg) != null, Profiles.get(key).let { it.remap != null || it.face != null }),
            Features.gameControls, returnTo)
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var popupOpen by remember { mutableStateOf(false) }
    val firstApp = remember { FocusRequester() }
    // build 18 (GC12, T18-02 / gd-D27): a row of the app settings asks Game controls for its Performance popup — review
    // (GC12): and gets the focus back when it closes (B closes one level: the row, not the drawing a screen above)
    var perfAsk by remember { mutableIntStateOf(0) }
    val perfFrom = remember { arrayOfNulls<FocusRequester>(1) }
    // device test S15 (debug log): how it opened, which app (a hash, never its name)
    if (BuildConfig.DEBUG) LaunchedEffect(key) {
        Log.d("ThorGamePage", "in=${if (inGame) 1 else 0} app=%04x start=%s".format(appPkg.hashCode() and 0xffff, start.name.lowercase()))
    }
    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageH = maxHeight
            val sectionsTop = with(LocalDensity.current) { pageH.roundToPx() }      // the app settings start one screen down
            // the D-pad coming in from nowhere (after a touch) lands on the part on screen: Game controls' drawing, or the
            // app settings; a popup open keeps the page still at its top. Device pass (P2-1): an arrival rule ([arrivalTo]) —
            // Performance closed, the row that asked for it ([perfBack]) is asked from outside the page: it went to firstApp
            Column(Modifier.fillMaxSize()
                .focusProperties { enter = { if (scroll.value >= sectionsTop / 2) arrivalTo(firstApp) else FocusRequester.Default } }
                .focusGroup()
                .verticalScroll(scroll, enabled = !popupOpen)) {
                // ── Game controls: the page as it was, one screen tall — back in it, it is whole on the screen ──
                // build 18 (GC7, gd-D01): a snap after the focused part's own bring-into-view, not a one-shot animation that lost
                // to it (the page stayed ~75 px up: the clock over "Back to game", the battery over "Performance")
                Box(Modifier.fillMaxWidth().height(pageH).onFocusChanged {
                    if (it.hasFocus && scroll.value != 0) scope.launch {
                        repeat(2) { withFrameNanos { }; if (scroll.value != 0) scroll.scrollTo(0) }
                    }
                }) {
                    GameControls(myDisplayId, key, onBack, inGame, onSwitchApp, onAllApps, openedBy,
                        initialFocus = start == GamePageRules.Start.CONTROLS,
                        onPopupOpen = { o -> popupOpen = o; if (o) scope.launch { scroll.animateScrollTo(0) } },
                        toApp = { scope.launch { scroll.animateScrollTo(sectionsTop); firstApp.requestAfterLayout() } },
                        openPage = if (inGame) null else go, perfAsk = perfAsk,
                        // as toApp: the app settings on screen, then the row that asked (false: gone — Game controls' drawing)
                        perfBack = { scroll.animateScrollTo(sectionsTop); perfFrom[0]?.requestAfterLayout() == true },
                        // device pass (P1-3): back from a page a popup's row opened (Which game? › All apps) — its opener
                        returnTo = returnTo)
                }
                // ── the app's own settings ──
                GameAppSections(appPkg, key, inGame, go, firstApp, returnTo?.takeIf { it.startsWith(GamePageRules.ROW_PREFIX) },
                    entry = start == GamePageRules.Start.APP, scrollBy = { d -> scope.launch { scroll.animateScrollBy(d) } },
                    openPerf = { row -> perfFrom[0] = row; scope.launch { scroll.animateScrollTo(0) }; perfAsk++ },
                    // out of reach behind a popup; left / right stay in the settings (as on every Hub page: SubPage)
                    modifier = Modifier.focusProperties {
                        enter = { if (popupOpen) FocusRequester.Cancel else FocusRequester.Default }
                        exit = { d -> if (d == FocusDirection.Left || d == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                    }.focusGroup())
            }
            if (start == GamePageRules.Start.APP) LaunchedEffect(Unit) { withFrameNanos { }; scroll.scrollTo(sectionsTop) }
        }
    }
}

/** The app's own settings on its page (app-level: [AppConfigStore], whatever game of it runs). [entry]: the page opens here
 *  (its first setting, or the row it comes back to — [returnTo]); else the first layout leaves the focus to Game controls. */
@Composable
private fun GameAppSections(appPkg: String, key: String, inGame: Boolean, go: (String) -> Unit, first: FocusRequester,
                            returnTo: String?, entry: Boolean, scrollBy: (Float) -> Unit, openPerf: (FocusRequester) -> Unit,
                            modifier: Modifier) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    @Suppress("UNUSED_VARIABLE") val v = AppConfigStore.version.intValue
    val cfg = AppConfigStore.get(appPkg)
    val appLabel = remember(appPkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(appPkg, 0)).toString() }.getOrDefault(appPkg) }
    fun set(change: (AppConfig) -> AppConfig) = AppConfigStore.update(appPkg, change)
    // build 18 (GC12, gd-D25): the app's short name inside sentences (titles keep the whole one)
    val appShort = GameWords.appShort(appLabel)
    val isGamePage = GameProfiles.isGame(key)
    val e = if (entry) rememberEntryFocus(first = first, returnKey = returnTo) else null
    // device pass (final): the edge scroll, also for a control that uses the key itself (the "Guide & notes" choice, last)
    val scrollNow = androidx.compose.runtime.rememberUpdatedState(scrollBy)
    val edgeScroll: (Boolean) -> Unit = remember { { down -> scrollNow.value(if (down) 360f else -360f) } }
    CompositionLocalProvider(LocalEntryFocus provides e, app.wayfinder.ui.LocalPageEdgeScroll provides edgeScroll) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)
        // nothing left to focus below (above): the page scrolls — the note after the last setting stays readable (as SubPage)
        .onKeyEvent { k ->
            val down = k.key == Key.DirectionDown
            if ((!down && k.key != Key.DirectionUp) || k.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (!focus.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) edgeScroll(down)
            true
        },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(if (isGamePage) "All of $appLabel" else appLabel)
        // build 18 (GC5, G07; GC12, T18-04): 20 words at most — Player 1 named only where Game controls shows its row
        val own1 = if (isGamePage) GameProfiles.get(key)?.extFirst else cfg.extFirst
        Caption("Changes here apply only to $appShort. Performance and lights are in Game controls" +
            (if (ExtPadsCtl.pads.isNotEmpty() || own1 != null) ", Player 1 too" else "") + ".")
        // build 18 (GC12, T18-02): an app's performance, refresh rate, bottom screen and frame rate are Game controls'
        // Performance (one editor) — said and reached from its own settings. A game's page: that popup edits the game's own
        // (not this section's app-wide ones), so no row. Review (GC12): a row that opens it gets the focus back ([openPerf]).
        // Device pass (P2-1): the rows say whether they hold the focus — a request that went elsewhere is asked again
        val perfRow = remember { FocusRequester() }
        val lightsRow = remember { FocusRequester() }
        if (!isGamePage) GlassListRow("Performance and screen", Modifier.reportingFocusRequester(perfRow), value = perfSummary(cfg),
            end = RowEnd.CHEVRON, onClick = { openPerf(perfRow) })
        SettingCard("Opens on", when (cfg.route) {
            Route.ANY -> "Android picks the screen"                                // build 18 (GC12, gd-D28)
            Route.TOP -> "Always the top screen, when it starts"
            Route.BOTTOM -> "Always the bottom screen, when it starts"
        }) {
            GlassSegmentedControl(Route.values().map { it.label }, cfg.route.ordinal, Modifier.fillMaxWidth(), focusRequester = first) { i ->
                set { it.copy(route = Route.values()[i]) }
            }
        }
        // 1.3.2: live move, or reopen it there (Firefox crashed after a live move)
        // dev build 17 (WP9): titles of 5 words or fewer; a detected value says what it detected ("Automatic (reopens)")
        SettingCard("When it changes screen", (if (reopensOnMove(appPkg)) "It reopens there (tabs and state reload)"
            else "It keeps running") + if (cfg.reopenOnMove == null && reopensOnMoveByDefault(appPkg)) " (automatic: Firefox-based browsers reopen)" else "") {
            GlassSegmentedControl(listOf("Automatic (${if (reopensOnMoveByDefault(appPkg)) "reopens" else "keeps running"})", "Keep running", "Reopen"),
                when (cfg.reopenOnMove) { null -> 0; false -> 1; true -> 2 },
                Modifier.fillMaxWidth()) { i -> set { it.copy(reopenOnMove = when (i) { 1 -> false; 2 -> true; else -> null }) } }
        }
        // its own combos: Custom opens their editor (from the Hub; in a game, where it is)
        SettingCard(NavCards.APP_COMBOS.title, when (cfg.buttonsMode) {
            ButtonsMode.NORMAL -> "Your usual combos"                              // build 18 (GC12, gd-D28)
            ButtonsMode.CUSTOM -> "Custom for $appLabel"
            ButtonsMode.OFF -> "Off — all buttons go to $appLabel"
        }) {
            GlassSegmentedControl(ButtonsMode.values().map { it.label }, cfg.buttonsMode.ordinal, Modifier.fillMaxWidth()) { i ->
                val mode = ButtonsMode.values()[i]
                set { it.copy(buttonsMode = mode) }
                if (mode == ButtonsMode.CUSTOM && !inGame) { app.wayfinder.ui.ReturnFocus.clicked = NavCards.APP_COMBOS.id; go(HubPage.controlsFor(appPkg)) }
            }
            if (cfg.buttonsMode == ButtonsMode.CUSTOM) {
                if (!inGame) GlassListRow("Edit its combos", icon = Icons.Rounded.Tune, id = NavCards.APP_COMBOS.id) { go(HubPage.controlsFor(appPkg)) }
                else Caption("Edit them in ${Say.path(NavCards.APP_COMBOS, name = appLabel, inWayfinder = true)}.")
            }
        }
        // 1.3: Back as the game's own button (RetroArch's menu / hotkeys) — automatic for RetroArch
        val backFree = ControlsStore.backToGame(appPkg)
        SettingCard("Back button", if (backFree) "Goes to $appLabel (held too) — no Back combos here"
            else "Wayfinder's (its taps, hold and combos)" +
                if (cfg.backToGame == null) " — a short Back, or Back with another button held, still reaches $appLabel" else "") {
            GlassSegmentedControl(listOf(if (cfg.backToGame == null) "Automatic (${if (backFree) "the game" else "Wayfinder"})" else "Automatic",
                    "Wayfinder", "The game"), when (cfg.backToGame) { null -> 0; false -> 1; true -> 2 },
                Modifier.fillMaxWidth()) { i -> set { it.copy(backToGame = when (i) { 1 -> false; 2 -> true; else -> null }) } }
        }
        // rule 7: Stick lights is their one editor — its value here, and the way there. Build 18 (GC12, gd-D27 / T18-05): an
        // app's page from the Hub has no row — Game controls' Performance picks the same field and opens the editor ("Colours
        // and brightness"); a game's page says whose ("— all of <app>"); in a game the row opens Performance (no Hub page there).
        // Review (GC12): on a game's page in a game that Performance is the GAME's ("— this game") — the row says so and shows
        // the game's own lights (else "Usual (<its app's>)", as Performance does), never the app-wide ones it doesn't edit
        if (Features.lights && (inGame || isGamePage)) {
            val gameHere = inGame && isGamePage
            val appLights = cfg.lights?.let { "${it.mode.label} for $appShort" } ?: Say.usual(app.wayfinder.lights.LightSettings.global.mode.label)
            val lights = if (!gameHere) appLights
                else Profiles.get(key).lights?.let { "${it.mode.label} for this game" }
                    ?: Say.usual((cfg.lights ?: app.wayfinder.lights.LightSettings.global).mode.label)
            val title = if (gameHere) "${NavCards.APP_LIGHTS.title} — this game"
                else if (isGamePage) "${NavCards.APP_LIGHTS.title} — all of $appShort" else NavCards.APP_LIGHTS.title
            if (inGame) GlassListRow(title, Modifier.reportingFocusRequester(lightsRow), value = lights, icon = Icons.Rounded.Lightbulb,
                id = NavCards.APP_LIGHTS.id, end = RowEnd.CHEVRON, onClick = { openPerf(lightsRow) })
            else GlassListRow(title, value = lights, icon = Icons.Rounded.Lightbulb, id = NavCards.APP_LIGHTS.id, end = RowEnd.CHEVRON) {
                go(HubPage.lightsFor(appPkg)) }
        }
        SettingCard("Guide & notes", if (cfg.companion) "On the bottom screen when $appShort opens" else "Off — no guide beside it") {
            GlassSegmentedControl(listOf("Off", "Guide & notes"), if (cfg.companion) 1 else 0, Modifier.fillMaxWidth()) { i ->
                set { it.copy(companion = i == 1) } }
            // (in a game it would open over this page's screen: from the Hub only)
            if (!inGame) GlassButton("Open it now", { ForegroundAppService.openCompanionNow(appPkg) }, size = ButtonSize.COMPACT)
        }
        // two apps can be open at once, each with its own settings — say who wins
        Caption("Two apps open? Performance and fan: the more demanding. Buttons and lights: the controller's. " +
            "Bottom screen: the top app.")
    }
    }
}

/** Build 18 (GC12, T18-02): what an app's Performance holds, in a few words — "Usual" when nothing of its own, else
 *  "High · 120 Hz · bottom off". */
private fun perfSummary(c: AppConfig): String = listOfNotNull(
    c.perf?.label,
    c.fan?.let { "fan ${it.label.lowercase()}" },
    c.hz?.let { "$it Hz" },
    when (c.second) { SecondScreenPolicy.KEEP_ON -> "bottom stays on"; SecondScreenPolicy.BLANK -> "bottom off"; else -> null },
    c.fps?.let { if (it) "frame rate shown" else "frame rate hidden" },
).joinToString(" · ").ifEmpty { Say.usual(null) }
