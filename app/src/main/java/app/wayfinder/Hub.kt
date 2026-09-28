package app.wayfinder

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Bedtime
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.glassSurface
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.StatusPill
import app.wayfinder.ui.VSpace
import kotlinx.coroutines.delay
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import rikka.shizuku.Shizuku

// ─────────────────────────────────────────────────────────────────────────────
// Hub navigation. Pages are strings so deep links work ("controls", "controls:<pkg>").
// B / Back always goes up one level (see [hubParent]).
// ─────────────────────────────────────────────────────────────────────────────

object HubPage {
    const val HOME = "hub"
    const val SCREENS = "screens"
    const val CONTROLLER = "controller"
    const val CONTROLS = "controls"
    const val APPS = "apps"
    const val SHOTS = "screenshots"
    const val APPEARANCE = "appearance"
    const val HELP = "help"
    const val SEARCH = "search"
    const val KEYBOARD = "keyboard"
    const val LIGHTS = "lights"
    const val PAIRS = "pairs"
    const val SLEEP = "sleep"
    const val TOUR = "tour"
    const val PANEL = "panel"
    const val PADTEST = "padtest"
    const val LICENSES = "licenses"
    fun lightsFor(pkg: String) = "lights:$pkg"
    fun controlsFor(pkg: String) = "controls:$pkg"
    fun remapFor(pkg: String) = "remap:$pkg"
    /** "Buttons for <app>" opened from a game (Game controls): Back returns to the game. */
    fun playFor(pkg: String) = "play:$pkg"

    private val ALL = setOf(HOME, SCREENS, CONTROLLER, CONTROLS, APPS, SHOTS, APPEARANCE, HELP, SEARCH, KEYBOARD, LIGHTS, PAIRS, SLEEP, TOUR, PANEL, PADTEST, LICENSES)
    /** A `page` extra from an intent (any app can send one): only known pages, and per-app
     *  pages only for a real package name. Anything else → null (the Hub opens normally). */
    fun safe(p: String?): String? = when {
        p == null -> null
        p in ALL -> p
        p.startsWith("controls:") && Shell.isPkg(p.removePrefix("controls:")) -> p
        p.startsWith("lights:") && Shell.isPkg(p.removePrefix("lights:")) -> p
        p.startsWith("remap:") && profileKeyOk(p.removePrefix("remap:")) -> p
        p.startsWith("play:") && profileKeyOk(p.removePrefix("play:")) -> p
        else -> null
    }
}

/** An app's package, or a game profile's key `<pkg>#<game-id>`. */
private fun profileKeyOk(k: String) = Shell.isPkg(GameProfiles.pkgOf(k)) &&
    (!GameProfiles.isGame(k) || Regex("^[a-z0-9-]{1,64}$").matches(k.substringAfter('#')))

fun hubParent(page: String): String? = when {
    page == HubPage.HOME -> null
    page.startsWith("controls:") -> HubPage.APPS
    page.startsWith("lights:") -> HubPage.APPS
    page.startsWith("remap:") -> HubPage.APPS
    page.startsWith("play:") -> HubPage.APPS
    page == HubPage.CONTROLS || page == HubPage.PADTEST -> HubPage.CONTROLLER
    page == HubPage.SLEEP -> HubPage.SCREENS
    page == HubPage.PAIRS -> HubPage.PANEL
    page == HubPage.LICENSES -> HubPage.HELP
    else -> HubPage.HOME
}

@Composable
fun HubNavHost(myDisplayId: Int, page: String, go: (String) -> Unit) {
    val back = { go(hubParent(page) ?: HubPage.HOME) }
    when {
        page == HubPage.SCREENS -> ScreensPage(myDisplayId, back, go)
        page == HubPage.CONTROLLER -> ControllerPage(myDisplayId, back, go)
        page == HubPage.CONTROLS -> ControlsScreen(myDisplayId, onBack = back)
        page.startsWith("controls:") -> ControlsScreen(myDisplayId, pkg = page.removePrefix("controls:"), onBack = back)
        page == HubPage.APPS -> AppProfilesScreen(myDisplayId, onEditButtons = { go(HubPage.controlsFor(it)) },
            onEditLights = { go(HubPage.lightsFor(it)) }, onEditRemap = { go(HubPage.remapFor(it)) }, onBack = back)
        page.startsWith("remap:") -> RemapScreen(myDisplayId, page.removePrefix("remap:"), onBack = back,
            onSwitchApp = { go(HubPage.remapFor(it)) }, onAllApps = { go(HubPage.APPS) })
        page.startsWith("play:") -> {
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            androidx.compose.runtime.DisposableEffect(act) {
                MainActivity.gameControls = act as? MainActivity
                onDispose { if (MainActivity.gameControls === act) MainActivity.gameControls = null }
            }
            RemapScreen(myDisplayId, page.removePrefix("play:"), inGame = true,
                onBack = { act?.finish(); ForegroundAppService.gameControlsClosed() },
                onSwitchApp = { go(HubPage.playFor(it)) }, onAllApps = { go(HubPage.APPS) })
        }
        page == HubPage.SHOTS -> ScreensPage(myDisplayId, back, go)
        page == HubPage.APPEARANCE -> AppearancePage(myDisplayId, back)
        page == HubPage.HELP -> HelpPage(myDisplayId, back, go)
        page == HubPage.KEYBOARD -> KeyboardPage(myDisplayId, back)
        page == HubPage.LIGHTS -> LightsPage(myDisplayId, null, back)
        page == HubPage.PAIRS -> PairsPage(myDisplayId, back)
        page == HubPage.SLEEP -> SleepPage(myDisplayId, back)
        page == HubPage.TOUR -> TourPage(myDisplayId) { go(HubPage.HOME) }
        page == HubPage.PANEL -> PanelShortcutsPage(myDisplayId, back, go)
        page == HubPage.PADTEST -> ControllerTestPage(myDisplayId, back)
        page == HubPage.LICENSES -> LicensesPage(myDisplayId, back)
        page.startsWith("lights:") -> LightsPage(myDisplayId, page.removePrefix("lights:"), back)
        page == HubPage.SEARCH -> SearchPage(myDisplayId, back, go)
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
    // 1.3: back from a page, the card that opened it has the focus again (not the first one)
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    fun cardFocus(page: String) = cardFocus.getOrPut(page) { FocusRequester() }
    val open: (String) -> Unit = { page -> lastHubCard = page; go(page) }
    // the setup button when it's shown (it's what needs doing), else the card last opened, else the first
    LaunchedEffect(Unit) { UpdateCheck.check(ctx) }      // 1.3.1: at most once a day
    LaunchedEffect(Unit) {
        delay(350)
        val back = lastHubCard?.let { cardFocus[it] }
        runCatching { (if (!ForegroundAppService.isRunning) setupFocus else back ?: firstFocus).requestFocus() }
            .onFailure { runCatching { firstFocus.requestFocus() } }
    }
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + AppConfigStore.version.intValue

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
                StatusPill(if (st.ready) "Ready" else "Setup needed", st.ready)
                // 1.3.1 — a newer version on GitHub: a small orange-dot pill that opens the release page
                // (the same pill as "Ready" — shape, padding, dot, text — only it can be pressed)
                if (UpdateCheck.available != null) {
                    FocusableGlass(onClick = { openLink(ctx, UpdateCheck.RELEASES_URL, myDisplayId) }, radius = 14.dp) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(androidx.compose.ui.graphics.Color(0xFFFF9500), RoundedCornerShape(4.dp)))
                            Text("Update available", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.weight(1f))
                // discreet support link (2026-09-26): small, quiet, opens Ko-fi in the browser
                FocusableGlass(onClick = { openKofi(ctx, myDisplayId) }, radius = 22.dp) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Coffee, null, tint = g.textTertiary, modifier = Modifier.size(18.dp))
                        Text("Support me on Ko-fi", color = g.textTertiary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                FocusableGlass(onClick = { go(HubPage.SEARCH) }, radius = 22.dp) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = g.accent, modifier = Modifier.size(20.dp))
                        Text("Search settings & features", color = g.textTertiary, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, softWrap = false)
                    }
                }
            }

            // Setup (only when something's missing)
            if (!st.running) GlassActionButton(
                "Enable the accessibility service", Icons.Rounded.Accessibility, Modifier.fillMaxWidth(),
                subtitle = "Required — sees which app is on each screen and reads the controller; nothing leaves the Thor",
                focusRequester = setupFocus,
            ) { Setup.enableService(ctx) }
            SetupFlagWarning { runCatching { firstFocus.requestFocus() } }
            AynHomeWarning()
            // Option B (2026-09-25): one live status strip instead of the intro line —
            // the tour explains Wayfinder; this says what it's doing right now.
            StatusStrip(myDisplayId)

            // No quick actions here on purpose: while the Hub is open it IS the top app
            // (Wayfinder never swaps itself), so swap/recents/screenshot would be
            // meaningless. Actions live on combos, gestures and the Control Deck.

            // Menus
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuCard("Screens & power", screensSummary(), Icons.Rounded.Tv, Modifier.weight(1f), firstFocus, cardFocus(HubPage.SCREENS)) { open(HubPage.SCREENS) }
                MenuCard("Controller", controllerSummary(), Icons.Rounded.SportsEsports, Modifier.weight(1f), null, cardFocus(HubPage.CONTROLLER)) { open(HubPage.CONTROLLER) }
                MenuCard("Keyboard", keyboardSummary(LocalContext.current), Icons.Rounded.Keyboard, Modifier.weight(1f), null, cardFocus(HubPage.KEYBOARD)) { open(HubPage.KEYBOARD) }
                MenuCard("App profiles", appsSummary(), Icons.Rounded.Apps, Modifier.weight(1f), null, cardFocus(HubPage.APPS)) { open(HubPage.APPS) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuCard("Quick panel", panelSummary(ctx), Icons.Rounded.Dashboard, Modifier.weight(1f), null, cardFocus(HubPage.PANEL)) { open(HubPage.PANEL) }
                MenuCard("Stick lights", lightsSummary(), Icons.Rounded.Lightbulb, Modifier.weight(1f), null, cardFocus(HubPage.LIGHTS)) { open(HubPage.LIGHTS) }
                MenuCard("Appearance", appearanceSummary(), Icons.Rounded.Palette, Modifier.weight(1f), null, cardFocus(HubPage.APPEARANCE)) { open(HubPage.APPEARANCE) }
                MenuCard("Help & status", if (st.ready) "All set · how to use" else "Something needs setup", Icons.Rounded.Info, Modifier.weight(1f), null, cardFocus(HubPage.HELP)) { open(HubPage.HELP) }
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
    }.onFailure { ForegroundAppService.pill("No browser to open the page", displayId, 3000) }
}

fun openKofi(ctx: android.content.Context, displayId: Int) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }.onFailure { ForegroundAppService.pill("No browser to open ko-fi.com/thorwayfinder", displayId, 3000) }
}

@Composable
private fun MenuCard(title: String, summary: String, icon: ImageVector, modifier: Modifier, focus: FocusRequester? = null,
                     back: FocusRequester? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    // 126 dp: both rows fit the top screen with the status strip, no scrolling (Option B)
    FocusableGlass(onClick = onClick, modifier = modifier.height(126.dp).then(if (back != null) Modifier.focusRequester(back) else Modifier),
        radius = 26.dp, focusRequester = focus) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = g.accent, modifier = Modifier.size(30.dp))
            Column {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text(summary, color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// Live one-line summaries for the menu cards.
private fun screensSummary(): String = "Brightness · sound · sleep"

// "Stays where sent" is implemented WITH the lock: checked first, or it read "on the top screen"
private fun controllerSummary(): String =
    if (!PadLayerCtl.wanted) "Input layer off"
    else "${ControlsStore.active().size} combos"   // where it is and how it moves: the status strip

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
    val how = when {
        !AppSettings.focusLockEnabled -> if (AppSettings.focusSticky) "follows touch until sent" else "follows touch"
        AppSettings.focusSticky -> "stays where sent"
        else -> "locked"
    }
    // The input layer is an internal: said only when it ISN'T working (critique 2026-09-25) —
    // otherwise that cell shows the battery
    val layerOk = PadLayerCtl.wanted && PadLayerCtl.active && PadLayerCtl.controllerNumber <= 1
    val layerProblem = when {
        layerOk -> null
        !PadLayerCtl.wanted -> "Off — game controls paused"
        PadLayerCtl.active -> "Controller #${PadLayerCtl.controllerNumber} — some games want #1"
        else -> "Starting…"
    }
    val battery = remember(tick / 20) {     // every ~30 s
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { val l = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1); val sc = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / sc else null }
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10 }
        val charging = (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        listOfNotNull(pct?.let { "$it %" + if (charging) " · charging" else "" }, t?.let { "$it °C" }).joinToString(" · ").ifEmpty { "—" }
    }
    val second = ForegroundAppService.availableDisplayIds.firstOrNull { it != 0 }
    // Wayfinder and launchers aren't tracked as screen apps: name the Hub's own screen, and
    // "Home screen" when nothing else is known
    val apps = listOfNotNull(0, second).joinToString(" · ") { d ->
        if (d == myDisplayId) "Wayfinder"
        else ForegroundAppService.displayApps[d]?.let { appLabel(ctx, it) } ?: "Home screen"
    }
    val mode = when (ForegroundAppService.screenMode()) { 1 -> "top only"; 2 -> "bottom only"; else -> "both on" }
    GlassPanel(Modifier.fillMaxWidth(), radius = 22.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StripCell("Controller", "$where · $how", Modifier.weight(1f))
            if (layerProblem != null) StripCell("Game controls", layerProblem, Modifier.weight(0.9f), dot = androidx.compose.ui.graphics.Color(0xFFE0A63A))
            else StripCell("Battery", battery, Modifier.weight(0.8f))
            StripCell("On the screens", apps, Modifier.weight(1f))
            StripCell("Brightness", listOfNotNull(bright, mode).joinToString(" · "), Modifier.weight(0.9f))
        }
    }
}

@Composable
private fun StripCell(label: String, value: String, modifier: Modifier, dot: androidx.compose.ui.graphics.Color? = null) {
    val g = LocalGlass.current
    Column(modifier.padding(horizontal = 8.dp)) {
        Text(label.uppercase(), color = g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
            Text(value, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun appsSummary(): String = AppConfigStore.configured().size.let { if (it == 0) "Settings for each app" else "$it apps set up" }

private fun panelSummary(ctx: android.content.Context): String =
    if (AppSettings.aynButtonOurs) "AYN button · ${PanelShortcuts.chosen(ctx).size} shortcuts" else "Off — AYN button opens AYN's drawer"

private fun appearanceSummary(): String =
    "${AppSettings.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (AppSettings.backdrop == Backdrop.WALLPAPER) "your background" else "aurora"}"

// ─────────────────────────────────────────────────────────────────────────────
// Sub-page scaffold: back pill + title, scrollable glass content.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SubPage(
    myDisplayId: Int, title: String, subtitle: String?, onBack: () -> Unit,
    focusBack: Boolean = true,   // false when the page focuses something else itself (search field)
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    val backFocus = remember { FocusRequester() }
    if (focusBack) LaunchedEffect(Unit) { delay(300); runCatching { backFocus.requestFocus() } }
    GlassScreen(span = spanFor(myDisplayId)) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding().navigationBarsPadding()
                .imePadding()   // shrink above the keyboard so results stay visible
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FocusableGlass(onClick = onBack, radius = 16.dp, focusRequester = backFocus) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = g.accent, modifier = Modifier.size(20.dp))
                        Text("Back", color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Column {
                    Text(title, color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    if (subtitle != null) Text(subtitle, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            VSpace(2)
            content()
            VSpace(16)
        }
    }
}

/** A glass setting card: title, explanation, optional switch, optional body below. */
@Composable
internal fun SettingCard(
    title: String, explanation: String,
    checked: Boolean? = null, onChecked: (Boolean) -> Unit = {},
    body: (@Composable () -> Unit)? = null,
) {
    val g = LocalGlass.current
    // A plain on/off card: the WHOLE card is the button (A toggles), with the same glowing ring
    // as every other card. A bare Material switch showed almost no focus on glass, and focusing
    // it scrolled only the switch into view — the explanation stayed cut off (release test
    // 2026-09-24).
    if (checked != null && body == null) {
        FocusableGlass(onClick = { onChecked(!checked) }, modifier = Modifier.fillMaxWidth(), radius = 18.dp) { _ ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(explanation, color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = checked, onCheckedChange = null,
                    modifier = Modifier.focusProperties { canFocus = false })
            }
        }
        return
    }
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(explanation, color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
                if (checked != null) {
                    var f by remember { mutableStateOf(false) }
                    Switch(checked = checked, onCheckedChange = onChecked,
                        modifier = Modifier.onFocusChanged { f = it.isFocused }
                            .border(if (f) 3.dp else 0.dp, if (f) g.accent else androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.foundation.shape.CircleShape)
                            .padding(3.dp))
                }
            }
            body?.invoke()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pages
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScreensPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, "Screens & power", "Brightness, sound, and the bottom screen", onBack) {
        SettingCard("Brightness", "\"Both screens\" keeps the difference between them. " +
            (ControlsStore.triggerFor(ThorAction.BRIGHTER)?.let { "${it.label()} / ${ControlsStore.triggerFor(ThorAction.DIMMER)?.label() ?: "—"} change both screens." } ?: "")) {
            BrightnessSliders()
        }
        SettingCard("Colour", "How vivid both screens are — the Thor's panels are strong out of the box; 80–90 % looks more natural. 100 % = as shipped.") {
            SaturationSlider()
        }
        SettingCard("Volume", "Top and Bottom set how loud each screen is compared with the other; \"Both screens\" keeps that difference") {
            VolumeSliders()
        }
        // 1.3 (Reddit request): the volume booster
        SettingCard("Volume boost", if (SpeakerTune.boost == 0) "Louder than the maximum: extra gain with a limiter (no crackle), on the speakers, headphones and Bluetooth"
            else "${SpeakerTune.boostStatus()} — with headphones, start low: it's louder than their usual maximum") {
            GlassSegmentedControl(SpeakerTune.BOOST_STEPS.map { if (it == 0) "Off" else "+$it dB" },
                SpeakerTune.BOOST_STEPS.indexOf(SpeakerTune.boost).coerceAtLeast(0), Modifier.fillMaxWidth()) { SpeakerTune.setVolumeBoost(SpeakerTune.BOOST_STEPS[it]) }
        }
        SettingCard(
            "Volume buttons change both screens",
            if (LinkedVolume.enabled) "The buttons turn both screens up and down together, keeping their difference"
            else "Off — the buttons only change the top screen (the Thor's default)",
            checked = LinkedVolume.enabled, onChecked = { LinkedVolume.setLinked(it) },
        )
        SettingCard(
            "Speaker sound fix",
            "The community's Thor speaker tuning (EQ by Joey, Retro Handhelds), built in: its EQ, +15 dB with a limiter, and a stereo widener — clearer, louder, wider. Never applied to headphones." +
                (if (SpeakerTune.externalDspInstalled() != null) " Turning it on pauses your equalizer app (switch that back on yourself if you go back to it)." else "") +
                (if (SpeakerTune.enabled) " " + SpeakerTune.status else ""),
            checked = SpeakerTune.enabled, onChecked = { SpeakerTune.setOn(it) },
        ) {
            if (SpeakerTune.enabled) {
                // A/B: the equalizer + loudness on its own (the widener has its slider; 1× = off).
                GlassSegmentedControl(listOf("Equalizer on", "Equalizer off"), if (SpeakerTune.eqOn) 0 else 1, Modifier.fillMaxWidth()) {
                    SpeakerTune.setEq(it == 0)
                }
                StereoWidthSlider()
            }
        }
        SectionHeader("Bottom screen")
        SettingCard(
            "Turn it off with 3 fingers",
            when (AppSettings.gestureBlankMode) {
                BlankGesture.OFF -> "Off"
                BlankGesture.TAP -> "Tap the bottom screen with 3 fingers to turn it off; tap again to turn it back on"
                BlankGesture.SWIPE -> "Swipe down with 3 fingers to turn it off; touch it to turn it back on"
            },
        ) {
            GlassSegmentedControl(listOf("Off", "3-finger tap", "3-finger swipe down"), AppSettings.gestureBlankMode.ordinal, Modifier.fillMaxWidth()) {
                AppSettings.setGestureBlank(BlankGesture.values()[it])
            }
        }
        SettingCard(
            "Turn it off when unused", "After this long without a touch; touch it to turn it back on",
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
        SectionHeader("More")
        SettingCard("Do not disturb while playing",
            "While a game is on either screen, notifications stay quiet (Do not disturb, priority only). It goes back off when you stop — unless you had it on already.",
            checked = AppSettings.dndWhilePlaying, onChecked = { AppSettings.setDndWhilePlayingOn(it); ForegroundAppService.reapplyDnd() })
        // both screens (one power state) — it isn't a "Bottom screen" setting
        var awake by remember { mutableStateOf(ForegroundAppService.isSecondKeptAwake()) }
        SettingCard(
            "Stay awake", "The screens don't turn off on their own (both share one power state) — for maps, videos, guides. The power button still works.",
            checked = awake, onChecked = { ActionRegistry.perform(ThorAction.TOGGLE_KEEP_AWAKE); awake = ForegroundAppService.isSecondKeptAwake() },
        )
        SettingCard("Show the frame rate", "The real frames per second each screen's app draws (\"idle\" = nothing moving)",
            checked = AppSettings.fpsCounter, onChecked = { AppSettings.setFpsCounterOn(it); ForegroundAppService.reapplyFps() }) {
            if (AppSettings.fpsCounter) {
                GlassSegmentedControl(listOf("Top screen", "Bottom screen", "Both"), AppSettings.fpsScreens, Modifier.fillMaxWidth()) {
                    AppSettings.setFpsPlacement(screens = it); ForegroundAppService.reapplyFps()
                }
                GlassSegmentedControl(listOf("Top left", "Top right", "Bottom left", "Bottom right"), AppSettings.fpsCorner, Modifier.fillMaxWidth()) {
                    AppSettings.setFpsPlacement(corner = it); ForegroundAppService.reapplyFps()
                }
                // round 8: how much it shows (the data is read anyway — no root, no cost)
                GlassSegmentedControl(listOf("Frame rate", "+ battery", "+ battery and temperatures"), AppSettings.fpsLevel, Modifier.fillMaxWidth()) {
                    AppSettings.setFpsLevelTo(it)
                }
            }
        }
        var anim by remember { mutableStateOf(ForegroundAppService.animateMoves) }
        SettingCard("Animate apps moving between screens", "A glass slide when an app goes to the other screen",
            checked = anim, onChecked = { anim = it; ForegroundAppService.animateMoves = it })
        GlassListRow("Sleep & standby", value = when { SleepSettings.enabled -> "On"; SleepSettings.lidProtection -> "Lid guard only"; else -> "Off" }, icon = Icons.Rounded.Bedtime) { go(HubPage.SLEEP) }
        // one setting: here, not on a page of its own (critique 2026-09-25)
        @Suppress("UNUSED_VARIABLE") val cv = ControlsStore.version.intValue
        SettingCard("Screenshots",
            "Saved in Pictures / Screenshots, where your gallery apps see them. They capture everything shown, " +
                "even what apps hide from screenshots (passwords, banking) · " +
                "combo: ${ControlsStore.triggerFor(ThorAction.SCREENSHOT)?.label() ?: "none — set one in Combos, on the Controller page"}") {
            GlassSegmentedControl(
                ShotTarget.values().map { if (it == ShotTarget.BOTH) "Both (stacked)" else "${it.label} screen" },
                AppSettings.shotTarget.ordinal, Modifier.fillMaxWidth(),
            ) { AppSettings.setScreenshotTarget(ShotTarget.values()[it]) }
        }
        GlassListRow("Settings for each app", value = "App profiles", icon = Icons.Rounded.Apps) { go(HubPage.APPS) }
    }

// ─────────────────────────────────────────────────────────────────────────────
// App pairs + restore after a restart
// ─────────────────────────────────────────────────────────────────────────────

private fun appLabel(ctx: android.content.Context, pkg: String?): String =
    if (pkg == null) "(as is)" else runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

@Composable
private fun PairsPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, "App pairs", "Two apps, one press — each opens on its screen", onBack) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var building by remember { mutableStateOf(false) }
        GlassActionButton(
            "New pair", Icons.Rounded.Add, Modifier.fillMaxWidth(),
            subtitle = "Pick the app for each screen — or, with both apps already open: the AYN button, then App pairs, then Save",
        ) { building = true }
        if (building) PairBuilderDialog { building = false }
        // How to OPEN a pair was nowhere (2026-09-24): say it where pairs live.
        Text(
            "To open a pair: press it below — or, from any app, the AYN button, then the “App pairs” tile (add the tile in " +
                "the quick panel's edit mode if it isn't there). Both apps open, each on its screen. “Swap” changes which one goes on top.",
            color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodyMedium,
        )
        if (Layouts.pairs.isEmpty()) Text(
            "No pairs yet.", color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodyMedium,
        )
        for (p in Layouts.pairs.toList()) PairRow(p, appLabel(ctx, p.top), appLabel(ctx, p.bottom))
        SettingCard(
            "Put my screens back after a restart",
            "After the Thor restarts, reopen the apps that were on each screen",
            checked = Layouts.restoreOnBoot, onChecked = { Layouts.setRestore(it) },
        )
    }

@Composable
private fun PairRow(p: AppPair, top: String, bottom: String) {
    val g = LocalGlass.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(3000); confirm = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusableGlass(onClick = { ForegroundAppService.openPair(p) }, modifier = Modifier.weight(1f), radius = 16.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val ctx = LocalContext.current
                Text(pairTitle(ctx, p.top, p.bottom), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(pairWhere(ctx, p.top, p.bottom), color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        FocusableGlass(onClick = { Layouts.flip(p.id) }, radius = 16.dp) {
            Text("Swap", color = g.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        }
        FocusableGlass(onClick = { if (confirm) Layouts.remove(p.id) else confirm = true }, radius = 16.dp) {
            Text(if (confirm) "Delete?" else "Delete", color = app.wayfinder.ui.Glass.Danger, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun ControllerPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, "Controller", "Buttons, combos and which screen gets the controller", onBack) {
        @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue
        GlassListRow("Combos — Home, Back and the AYN button", value = "${ControlsStore.active().size} set", icon = Icons.Rounded.SportsEsports) { go(HubPage.CONTROLS) }
        AynHomeWarning()
        // round 8: drift, triggers, buttons — and the all-games deadzone
        GlassListRow("Test the controller", value = AppSettings.stickDefaults.let { d ->
            if (d.stickL.isDefault && d.stickR.isDefault) "sticks, triggers, buttons" else "deadzone ${d.stickL.dead} / ${d.stickR.dead} %" },
            icon = Icons.Rounded.SportsEsports) { go(HubPage.PADTEST) }
        GlassListRow("Quick panel (AYN button)", value = if (AppSettings.aynButtonOurs) "on" else "off",
            icon = Icons.Rounded.Dashboard) { go(HubPage.PANEL) }
        ControllerScreenCard()
        HomeTwiceCard()
        // 1.3 (GitHub #13): the Recents hint — smaller, off, or in a corner (it covered the middle card's icon)
        SettingCard("Recent apps — the controller hint", when (AppSettings.recentsHint) {
            1 -> "Compact: one slim line of symbols (↗ open · ✕ close · ⌂ home · ↩ back)"
            2 -> "Off — the buttons still work: A opens, Y closes, Select closes all, Start goes home, B goes back"
            else -> "What the buttons do in Recent apps, for a few seconds when it opens"
        }) {
            GlassSegmentedControl(listOf("Full", "Compact", "Off"), AppSettings.recentsHint, Modifier.fillMaxWidth()) {
                AppSettings.chooseRecentsHint(it, AppSettings.recentsHintAt) }
            if (AppSettings.recentsHint != 2) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                GlassSegmentedControl(listOf("Top", "Top left", "Top right", "Bottom left", "Bottom right"), AppSettings.recentsHintAt,
                    Modifier.fillMaxWidth()) { AppSettings.chooseRecentsHint(AppSettings.recentsHint, it) }
            }
        }
        GlassListRow("Game controls — each game its own buttons", value = "App profiles", icon = Icons.Rounded.Apps) { go(HubPage.APPS) }
        // Face buttons for every app = AYN's own controller style (one setting, the same as
        // AYN's drawer and our quick-panel tile). Per-app choices live in App profiles.
        val ctx = LocalContext.current
        var style by remember {
            mutableStateOf(runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "temp_abxy_layout_mode", 1) }.getOrDefault(1))
        }
        SettingCard(
            "Face buttons — all apps",
            if (style == 0) "Xbox: A at the bottom, B on the right (buttons by position). One game differently: Game controls (Home + ${ThorButton.X.label})."
            else "Nintendo: as printed on the Thor, A on the right. One game differently: Game controls (Home + ${ThorButton.X.label}).",
        ) {
            GlassSegmentedControl(listOf("Nintendo", "Xbox"), if (style == 0) 1 else 0, Modifier.fillMaxWidth()) { i ->
                val xbox = i == 1
                if (xbox != (style == 0)) { QuickSettings.toggleControllerStyle(style); style = if (xbox) 0 else 1 }
            }
        }
        // 1.3 (GitHub #27): what the face buttons are CALLED on screen
        SettingCard("Button names on screen", when (ButtonNames.mode) {
            1 -> "As printed on the Thor: A on the right, B at the bottom — in every hint, combo and page"
            2 -> "The Xbox way: A at the bottom, B on the right, X on the left, Y on top — combos stay on the same buttons"
            else -> "Automatic: they follow the face-button style above (Xbox style → A is the bottom button)"
        }) {
            GlassSegmentedControl(listOf("Automatic", "As printed", "Xbox"), ButtonNames.mode, Modifier.fillMaxWidth()) { ButtonNames.choose(it) }
        }
        // The input layer: on by default, a master switch here.
        var layer by remember { mutableStateOf(PadLayerCtl.wanted) }
        SettingCard(
            "Input layer",
            "Gives each game its own buttons, gyro and macros, and keeps Home and Back combos out of games (games " +
                "get an identical copy of the controller). Off: game controls, gyro and macros stop working. " +
                "Emergency off from anywhere: hold Home + Back for 5 seconds.",
            checked = layer, onChecked = { layer = it; PadLayerCtl.set(ctx, it) },
        )
    }

/** Shown while Android thinks the Thor's first setup isn't finished — it silently blocks Recents. */
@Composable
fun SetupFlagWarning(onFixed: () -> Unit = {}) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val broken = remember(tick) { SetupFlagGuard.broken(ctx) }
    // it had the controller's focus and vanished: hand the focus on (else nothing was highlighted)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(broken) { if (broken) shown = true else if (shown) { shown = false; delay(100); onFixed() } }
    if (!broken) return
    GlassActionButton(
        "Recent apps can't open", Icons.Rounded.Info, Modifier.fillMaxWidth(),
        subtitle = "Android thinks this Thor's first setup isn't finished (a system reset can cause it) — press to fix",
    ) { SetupFlagGuard.fix { tick++ } }
}

/** Shown while AYN's "press Home twice" option is on — it silently breaks every Home combo. */
@Composable
fun AynHomeWarning() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val on = remember(tick) { AynHomeGuard.isOn(ctx) }
    if (!on) return
    GlassActionButton(
        "Home combos can't work yet", Icons.Rounded.Info, Modifier.fillMaxWidth(),
        subtitle = "AYN's “press Home twice” protection is on — press to turn it off (Home combos need it off)",
    ) { AynHomeGuard.turnOff { tick++ } }
}


@Composable
private fun AppearancePage(myDisplayId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SubPage(myDisplayId, "Appearance", "Theme, and which screen Wayfinder opens on", onBack) {
        SettingCard("Theme", if (AppSettings.themeMode == ThemeMode.BLACK) "Black: dark glass on pure black — OLED screens show true black and use less power (no aurora, no background blur)"
            else "Glass in light or dark — or follow the system") {
            GlassSegmentedControl(
                listOf("System", "Light", "Dark", "Black"),
                when (AppSettings.themeMode) { ThemeMode.SYSTEM -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2; ThemeMode.BLACK -> 3 },
                Modifier.fillMaxWidth(),
            ) { AppSettings.setMode(when (it) { 1 -> ThemeMode.LIGHT; 2 -> ThemeMode.DARK; 3 -> ThemeMode.BLACK; else -> ThemeMode.SYSTEM }) }
        }
        SettingCard(
            "Glass backdrop",
            if (AppSettings.backdrop == Backdrop.WALLPAPER) "Your own background, blurred live behind the glass (falls back to Aurora if the system turns blur off, e.g. battery saver)"
            else "Wayfinder's drifting colour aurora, flowing across both screens",
        ) {
            GlassSegmentedControl(listOf("Your background", "Aurora"), AppSettings.backdrop.ordinal, Modifier.fillMaxWidth()) {
                AppSettings.setBackdropMode(Backdrop.values()[it])
                (ctx as? MainActivity)?.applyBackdrop()
            }
        }
        SettingCard("See-through glass", "How much of what's behind shows through — here and in the quick panel (the AYN button)") {
            GlassLookSliders(onBlur = { (ctx as? MainActivity)?.applyBackdrop() })
        }
        if (app.wayfinder.ui.GlassLensConfig.supported) SettingCard(
            "Light refraction",
            if (!AppSettings.refraction) "Plain frosted glass"
            else if (AppSettings.backdrop == Backdrop.AURORA) "The glass bends the aurora at its edges and catches the light"
            else "The glass edges catch the light (your background stays live, so it's blurred, not bent)",
        ) {
            GlassSegmentedControl(listOf("On", "Off"), if (AppSettings.refraction) 0 else 1, Modifier.fillMaxWidth()) {
                AppSettings.setRefractionOn(it == 0)

            }
        }
        SettingCard("Hub screen", "On the top screen, \"Your controls\" fills the bottom one; on the bottom screen, the top stays free for your game") {
            GlassSegmentedControl(listOf("Top screen", "Bottom screen"), if (AppSettings.hubOnTop) 0 else 1, Modifier.fillMaxWidth()) { i ->
                AppSettings.setHubScreen(i == 0)
                (ctx as? MainActivity)?.relocateIfNeeded(userInitiated = true)
            }
        }
    }
}

/** Back up / restore (Backup.kt): a backup made on this Thor restores everything; any other
 *  file only gives its controller mappings, filtered like a shared file. */
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
    GlassListRow("Back up now", value = "a file in Download / Wayfinder", icon = Icons.Rounded.Info) {
        val (name, bytes) = Backup.make(ctx)
        ForegroundAppService.pill(if (ProfileShare.save(ctx, name, bytes)) "Backup saved in Download / Wayfinder: $name"
            else "The backup couldn't be saved", myDisplayId, 3500)
    }
    GlassListRow("Send a backup…", value = "Drive, mail, another app", icon = Icons.Rounded.Info) {
        val (name, bytes) = Backup.make(ctx)
        if (!ProfileShare.send(ctx, name, bytes, "Send the backup")) ForegroundAppService.pill("Couldn't open the share menu", myDisplayId, 3000)
    }
    GlassListRow("Restore from a file…", value = "you'll see what it contains first", icon = Icons.Rounded.Info) {
        picker.launch(ProfileShare.PICK_TYPES)
    }
    // the limit of the signing key, said BEFORE anyone relies on a backup (2026-09-25)
    Text("A backup restores everything only here, with this installation of Wayfinder. If Wayfinder is uninstalled or its " +
        "data is cleared — or on another Thor — a backup still brings back your controller mappings, but not your combos " +
        "and settings. Keep a fresh backup, and set those up again after a reinstall.",
        color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodySmall)
    if (incoming != null || error != null) RestoreDialog(incoming, error, onRestore = { inc ->
        incoming = null; error = null
        if (inc.trusted && inc.prefs != null) { Backup.restoreAll(ctx, inc.prefs); Backup.restartApp(ctx) }
        else {
            Backup.restoreMappings(inc.mappings)
            ForegroundAppService.pill("Controller mappings imported" + if (inc.report.macrosOff > 0) " — macros stay off until you turn them on" else "", myDisplayId, 3500)
        }
    }) { incoming = null; error = null }
}

@Composable
private fun RestoreDialog(inc: Backup.Incoming?, error: String?, onRestore: (Backup.Incoming) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(26.dp)
        Box(Modifier.fillMaxWidth(0.7f).background(g.base.copy(alpha = 0.97f), shape).glassSurface(g, shape, raised = true)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Restore", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
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
                    Text("For safety, only its controller mappings are taken: ${inc.mappings.size - games} app${if (inc.mappings.size - games == 1) "" else "s"}" +
                        " and $games game${if (games == 1) "" else "s"}. Its Wayfinder combos and settings are not imported.",
                        color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    for (l in inc.report.lines()) Text(l, color = androidx.compose.ui.graphics.Color(0xFFE0A63A), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inc != null) FocusableGlass(onClick = { onRestore(inc) }, radius = 14.dp) {
                        Text(if (inc.trusted) "Restore everything" else "Import the mappings", color = g.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    // focus starts here: "Restore everything" replaces it all and restarts (review)
                    FocusableGlass(onClick = onClose, radius = 14.dp, focusRequester = first) {
                        Text(if (inc == null) "Close" else "Cancel  ·  ${ButtonNames.m("B")}", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

/** The questions people actually have, each with its fix (critique 2026-09-25). */
@Composable
private fun Troubleshooting(go: (String) -> Unit) {
    SetupFlagWarning()
    val home = ControlsStore.triggerFor(ThorAction.FOCUS_SWITCH_UP)?.label()
    val items = listOf(
        Triple("A game sees two controllers, or buttons act twice",
            "Wayfinder gives games a copy of the controller and hides AYN's. Turn “Input layer” off and on again on the Controller page — or restart the game.", HubPage.CONTROLLER),
        Triple("A game ignores the controller",
            "Some games only listen to controller #1. The Home page's status line warns when the copy isn't #1; turning the input layer off and on fixes it.", HubPage.CONTROLLER),
        Triple("No controller at all",
            "Hold Home + Back for 5 seconds: the input layer turns off and AYN's own controller comes back. Turn it on again from the Controller page.", HubPage.CONTROLLER),
        Triple("Home combos do nothing",
            "AYN's “Home needs two presses” must be off (Controller page). In one app only? Its combos may be off: App profiles, that app, “Wayfinder combos”.", HubPage.CONTROLLER),
        Triple("The controller works the wrong screen",
            (home?.let { "$it sends it to the top screen, the same with down to the bottom one. " } ?: "") +
                "How it follows you (touch, where you send it, always top) is on the Controller page.", HubPage.CONTROLLER),
        Triple("A stick moves on its own (drift)",
            "Test the controller measures it and sets a deadzone for every game in one press. One game differently: Game controls, that stick.", HubPage.PADTEST),
        Triple("Recent apps doesn't open (double Back does nothing)",
            "Android refuses Recents while it thinks the Thor's first setup isn't finished — a system reset can " +
                "leave it that way. The warning at the top of this list fixes it in one press (shown only when that's the cause).", null),
        Triple("Moving apps, brightness or sleep do nothing",
            "Check “System access” below. If it isn't working: turn off “Force SELinux” in the Thor's settings, then restart the Thor.", null),
    )
    for ((q, a, page) in items) {
        if (page != null) GlassListRow(q, value = "Fix ›", icon = Icons.Rounded.Info) { go(page) }
        else GlassListRow(q, value = "", icon = Icons.Rounded.Info) {}
        Text(a, color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
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
    SubPage(myDisplayId, "Help & status", if (st.ready) "Everything is set up" else "A couple of things need attention", onBack) {
        GlassListRow("Take the tour", value = "2 min", icon = Icons.Rounded.Info) { go(HubPage.TOUR) }
        GlassListRow("Support Wayfinder", value = "Support me on Ko-fi · ko-fi.com/thorwayfinder", icon = Icons.Rounded.Coffee) { openKofi(ctx, myDisplayId) }
        SectionHeader("Setup")
        GlassListRow("Accessibility service", value = if (st.running) "on" else "turn on", icon = Icons.Rounded.Accessibility) {
            if (st.running) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else Setup.enableService(ctx)
        }
        GlassListRow("Battery optimisation", value = if (st.batteryOptimized) "disable (recommended)" else "disabled ✓", icon = Icons.Rounded.BatteryAlert) {
            if (st.batteryOptimized) Setup.allowBackground(ctx)
            else ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${ctx.packageName}") })
        }
        BackupSection(myDisplayId)
        SectionHeader("Screens right now")
        GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                st.displayIds.forEachIndexed { i, id ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (i == 0) "Top" else "Bottom", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(if (id == myDisplayId) "Wayfinder" else st.displayApps[id]?.let { appLabel(ctx, it) } ?: "Home screen", color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        SectionHeader("If something's wrong")
        Troubleshooting(go)
        SectionHeader("System access")
        GlassListRow("Moving apps, brightness, audio, sleep",
            value = if (st.backend) "working ✓" else "not reachable — press to check again", icon = Icons.Rounded.Info) {
            Thread { PServiceBridge.isAvailable() }.apply { isDaemon = true }.start()
        }
        if (!st.backend) Text("If it stays unreachable: turn off “Force SELinux” in the Thor's settings, then restart the Thor.",
            color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodySmall)
        // 1.3 (GitHub #39): the input layer's state — and why it stopped, to put in a report
        val layerWhy = PadLayerCtl.failReason
        GlassListRow("Input layer (game controls, gyro, macros)", value = when {
            PadLayerCtl.active -> "working ✓"
            !PadLayerCtl.wanted && layerWhy.isEmpty() -> "off"
            layerWhy.isNotEmpty() -> "stopped: $layerWhy — press to try again"
            else -> "starting…"
        }, icon = Icons.Rounded.Info) { if (!PadLayerCtl.active) { PadLayerCtl.set(ctx, true) } }
        // 1.3.1 — the daily update check (the only other internet use, besides guides and Ko-fi)
        SettingCard("Check for updates",
            (if (UpdateCheck.enabled) "Once a day, Wayfinder asks GitHub for the latest version number — nothing about you is sent. " +
                "A new version shows as “Update available” next to Ready."
            else "Off — Wayfinder never checks. New versions are on the GitHub page.") +
                (UpdateCheck.available?.let { " Version $it is out." } ?: ""),
            checked = UpdateCheck.enabled, onChecked = { UpdateCheck.setEnabled(ctx, it) })
        GlassListRow("Open-source licenses", Modifier.onFocusChanged { if (it.isFocused) footerScope.launch { delay(60); footer.bringIntoView() } },
            value = "The libraries inside Wayfinder", icon = Icons.Rounded.Gavel) { go(HubPage.LICENSES) }
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

/** The third-party notices (assets/THIRD_PARTY_NOTICES.txt — the same file as in the source
 *  repository): one focusable block per paragraph, so the D-pad scrolls through it. */
@Composable
private fun LicensesPage(myDisplayId: Int, onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val paragraphs = remember {
        runCatching { ctx.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
            .split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
    }
    SubPage(myDisplayId, "Open-source licenses", "Wayfinder is built with these libraries", onBack) {
        paragraphs.forEach { p ->
            // a line underlined / framed with ===== or ----- in the text file = a heading here
            val lines = p.lines().filterNot { l -> l.isNotBlank() && l.all { it == '=' || it == '-' } }
            if (lines.size == 1 && lines.size < p.lines().size) SectionHeader(lines[0])
            else FocusableGlass(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = 14.dp, strong = false) {
                Text(lines.joinToString("\n"), color = g.textSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search: every setting and feature, by name or by what you'd call it.
// ─────────────────────────────────────────────────────────────────────────────

private class SearchEntry(
    val title: String, val where: String, val keywords: String,
    val page: String, val action: ThorAction? = null,
)

private val SEARCH_INDEX = listOf(
    SearchEntry("Move / swap apps between the screens", "Action", "move switch apps screens top bottom send swap", HubPage.HOME, ThorAction.SWAP_OR_SEND),
    SearchEntry("Do not disturb while playing", "Screens & power", "dnd do not disturb notifications quiet silent game playing focus", HubPage.SCREENS),
    SearchEntry("Test the controller · stick drift · deadzone", "Controller", "test controller drift deadzone stick joystick calibrate trigger buttons check", HubPage.PADTEST),
    SearchEntry("Take a screenshot", "Action", "screenshot capture picture photo print screen", HubPage.SCREENS, ThorAction.SCREENSHOT),
    SearchEntry("Turn the bottom screen off / on", "Action", "blank off black wake screen bottom oled battery turn off", HubPage.SCREENS, ThorAction.TOGGLE_SECOND_SCREEN),
    SearchEntry("Stay awake (no screen timeout)", "Action", "keep awake sleep screen on stay bottom timeout never", HubPage.SCREENS, ThorAction.TOGGLE_KEEP_AWAKE),
    SearchEntry("The quick panel takes the controller", "Quick panel", "quick panel controller touch only keep playing game focus", HubPage.PANEL),
    SearchEntry("Volume boost (louder than the maximum)", "Screens & power", "volume boost booster louder amplify gain loud", HubPage.SCREENS),
    SearchEntry("Button names: Xbox or as printed", "Controller", "button names xbox nintendo printed a b x y labels hints", HubPage.CONTROLLER),
    SearchEntry("Recent apps — the controller hint (smaller, off, corner)", "Controller", "recents hint compact small corner hide recent apps", HubPage.CONTROLLER),
    SearchEntry("Gyro on / off (an action for a combo or a long press)", "Action", "gyro motion aim tilt toggle pause long press hold", HubPage.HOME, ThorAction.GYRO_TOGGLE),
    SearchEntry("Guide & notes (the game's guide and your notes)", "Action", "guide notes walkthrough map single screen overlay companion", HubPage.HOME, ThorAction.GUIDE),
    SearchEntry("Mouse mode (AYN's virtual mouse)", "Action", "mouse cursor pointer virtual stick ayn gamenative web", HubPage.HOME, ThorAction.AYN_MOUSE),
    SearchEntry("Sleep (both screens off)", "Action", "sleep power off screens standby suspend lock button", HubPage.HOME, ThorAction.SLEEP),
    SearchEntry("Close background apps", "Action", "clear close kill recent memory ram background", HubPage.HOME, ThorAction.CLEAR_BACKGROUND),
    SearchEntry("Recent apps", "Action", "recents multitask overview switcher", HubPage.HOME, ThorAction.RECENTS),
    SearchEntry("Turn the bottom screen off with 3 fingers", "Screens & power", "gesture three 3 finger tap swipe blank wake off", HubPage.SCREENS),
    SearchEntry("Turn the bottom screen off when unused", "Screens & power", "auto off idle timer timeout sleep blank unused", HubPage.SCREENS),
    SearchEntry("Animate apps moving between screens", "Screens & power", "animation slide glass transition move", HubPage.SCREENS),
    SearchEntry("Combos — Home, Back and the AYN button", "Controller", "buttons combo combos system shortcuts bind chord shortcut hotkey ayn tap hold long press", HubPage.CONTROLS),
    SearchEntry("Game controls: each game's buttons, gyro and macros", "App profiles", "remap mapping gyro motion aim macro turbo toggle chord game controls per game emulator buttons", HubPage.APPS),
    SearchEntry("Input layer (for game controls)", "Controller", "input layer copy controller emergency home back game controls", HubPage.CONTROLLER),
    SearchEntry("Face buttons: Nintendo or Xbox layout", "Controller", "face buttons nintendo xbox layout abxy swap a b x y", HubPage.CONTROLLER),
    SearchEntry("Share or import game controls", "App profiles", "share import export send file controls profile mapping", HubPage.APPS),
    SearchEntry("Back up and restore your settings", "Help & status", "backup back up restore export import save settings file new thor reinstall", HubPage.HELP),
    SearchEntry("Which screen the controller works on (follow touch / lock)", "Controller", "lock focus controller gamepad screen stay follow touch sticky", HubPage.CONTROLLER),
    SearchEntry("Move the controller between screens", "Controller", "focus switch controller right stick gamepad screen send", HubPage.CONTROLLER),
    SearchEntry("App profiles", "Apps", "per app profile game application", HubPage.APPS),
    SearchEntry("Wayfinder combos for one app (usual / custom / off)", "App profiles", "per app buttons combos controls custom off game", HubPage.APPS),
    SearchEntry("Bottom screen while an app plays (per app)", "Apps", "per app second screen blank keep on game bottom", HubPage.APPS),
    SearchEntry("Screenshot target (top/bottom/both)", "Screens & power", "screenshot top bottom both stacked target", HubPage.SCREENS),
    SearchEntry("Theme (light / dark)", "Appearance", "theme dark light mode colours colors night", HubPage.APPEARANCE),
    SearchEntry("Glass backdrop (your background / aurora)", "Appearance", "background wallpaper blur glass aurora transparent backdrop", HubPage.APPEARANCE),
    SearchEntry("Wayfinder Keyboard (turn on, languages)", "Keyboard", "keyboard ime type typing languages layout azerty qwertz french accents input", HubPage.KEYBOARD),
    SearchEntry("Keyboard & mouse for games", "Keyboard", "deck trackpad mouse cursor pc keys f1 esc numpad emulator retroarch hotkeys media video volume brightness game keys send keys", HubPage.KEYBOARD),
    SearchEntry("My pad: your own buttons (snippets, key combos)", "Keyboard", "my pad custom buttons snippet text shortcut combo macro ctrl", HubPage.KEYBOARD),
    SearchEntry("Keyboard on the bottom screen", "Keyboard", "keyboard placement bottom screen other screen dual type", HubPage.KEYBOARD),
    SearchEntry("Open an app on a chosen screen (per app)", "App profiles", "route open launch always top bottom screen per app routing start", HubPage.APPS),
    SearchEntry("Sleep & standby: Wi-Fi / Bluetooth off, lid, battery drain", "Screens & power", "sleep standby battery drain wifi bluetooth lid case closed hall deep sleep vpn sync dock", HubPage.SLEEP),
    SearchEntry("App pairs · put the screens back after a restart", "Quick panel", "pair pairs two apps both screens layout combo restore reboot restart boot", HubPage.PAIRS),
    SearchEntry("Quick panel shortcuts (choose and arrange)", "Quick panel", "quick panel drawer shortcuts tiles customize customise choose order arrange grid ayn wifi bluetooth airplane dnd rotate location battery saver dark night dim invert record hotspot cast controller style xbox trigger landscape vibration charge limit direct power", HubPage.PANEL),
    SearchEntry("AYN button opens the quick panel", "Quick panel", "ayn button drawer quick panel menu settings performance fan refresh 120hz stats", HubPage.PANEL),
    SearchEntry("See-through glass (transparency, blur)", "Appearance", "transparent transparency blur glass see through opaque background frost", HubPage.APPEARANCE),
    SearchEntry("Show the frame rate (FPS)", "Screens & power", "fps frame rate framerate counter performance overlay hz", HubPage.SCREENS),
    SearchEntry("Brightness (both, top, bottom)", "Screens & power", "brightness bright dim dimmer brighter light screen top bottom backlight both", HubPage.SCREENS),
    SearchEntry("Colour saturation", "Screens & power", "colour color saturation vivid oversaturated natural washed oled", HubPage.SCREENS),
    SearchEntry("Pause music when the lid closes", "Sleep & standby", "lid close pause music video audio case", HubPage.SLEEP),
    SearchEntry("Speaker sound fix (EQ)", "Screens & power", "speaker audio sound fix eq equalizer dsp loud bass treble tuning", HubPage.SCREENS),
    SearchEntry("Take the tour (how to use Wayfinder)", "Help", "tour tutorial help guide how to start welcome intro", HubPage.TOUR),
    SearchEntry("Volume (both, top, bottom)", "Screens & power", "volume sound audio both screens bottom speaker balance louder quieter linked", HubPage.SCREENS),
    SearchEntry("Stick lights (RGB): colour, breathing, strobe, spectrum, screen colour", "Stick lights", "rgb led joystick stick lights ring colour color breathing strobe spectrum rainbow screen", HubPage.LIGHTS),
    SearchEntry("Light refraction", "Appearance", "glass refraction distortion lens light liquid highlight performance", HubPage.APPEARANCE),
    SearchEntry("Which screen Wayfinder opens on", "Appearance", "hub screen top bottom placement deck", HubPage.APPEARANCE),
    SearchEntry("Accessibility service", "Help & status", "accessibility service permission setup enable", HubPage.HELP),
    SearchEntry("Battery optimisation", "Help & status", "battery optimization optimisation background killed", HubPage.HELP),
    SearchEntry("System access", "Help & status", "root shizuku backend pservice status system access selinux", HubPage.HELP),
)

private fun search(q: String): List<SearchEntry> {
    val words = q.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return SEARCH_INDEX
    return SEARCH_INDEX
        .map { e -> e to words.count { w -> e.title.lowercase().contains(w) || e.keywords.contains(w) || e.where.lowercase().contains(w) } }
        .filter { it.second == words.size }
        .sortedByDescending { (e, _) -> words.count { e.title.lowercase().contains(it) } }
        .map { it.first }
}

@Composable
private fun SearchPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) {
    val g = LocalGlass.current
    var q by remember { mutableStateOf("") }
    val firstResult = remember { FocusRequester() }
    SubPage(myDisplayId, "Search", "Settings and features", onBack, focusBack = false) {
        GlassPanel(Modifier.fillMaxWidth(), radius = 22.dp, strong = true) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Search, null, tint = g.accent, modifier = Modifier.size(22.dp))
                // A plain EditText (not BasicTextField) so we can forbid the landscape
                // "fullscreen extract" keyboard, which would hide the live results.
                val hint = "Try “screenshot”, “lock”, “dark”…"
                val textColor = g.textPrimary.toArgb(); val hintColor = g.textTertiary.toArgb()
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c ->
                        android.widget.EditText(c).apply {
                            background = null; setPadding(0, 0, 0, 0); isSingleLine = true
                            this.hint = hint; setHintTextColor(hintColor); setTextColor(textColor); textSize = 17f
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { q = s?.toString() ?: "" }
                                override fun afterTextChanged(s: android.text.Editable?) {}
                            })
                            // D-pad ↓ leaves the field for the results (an EditText inside
                            // Compose doesn't hand focus on by itself).
                            setOnKeyListener { v, code, ev ->
                                if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && ev.action == android.view.KeyEvent.ACTION_DOWN) {
                                    c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                                    runCatching { firstResult.requestFocus() }.isSuccess
                                } else false
                            }
                            setOnEditorActionListener { v, _, _ ->   // Search key → hide keyboard, show all results
                                c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                                v.clearFocus(); true
                            }
                            post {
                                requestFocus()
                                c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    },
                    update = { it.setTextColor(textColor); it.setHintTextColor(hintColor) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val results = search(q)
        if (results.isEmpty()) Text("Nothing matches “$q”.", color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
        results.forEachIndexed { i, e ->
            FocusableGlass(
                onClick = { go(if (e.action != null) HubPage.CONTROLS else e.page) },
                modifier = Modifier.fillMaxWidth(), radius = 16.dp,
                focusRequester = if (i == 0) firstResult else null,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (e.action != null) ControlsStore.triggerFor(e.action)?.let { "Combo: ${it.label()}" } ?: "No combo yet — set one in Combos"
                            else e.where,
                            color = g.textTertiary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Open ›", color = g.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Which screen the controller works on — one choice instead of the old two overlapping
 * settings ("stays there after R3 + ↑/↓" and "lock to one screen").
 */
@Composable
private fun ControllerScreenCard() {
    val up = ControlsStore.triggerFor(ThorAction.FOCUS_SWITCH_UP)?.label()
    val down = ControlsStore.triggerFor(ThorAction.FOCUS_SWITCH_DOWN)?.label()
    val send = if (up != null && down != null) "$up / $down" else "a combo (set one in Combos)"
    // "Stays where sent" works by locking the controller wherever it's sent: it must still read
    // as "Stays where sent", not "Always top" (release test 2026-09-24)
    val mode = when {
        AppSettings.focusSticky -> 1
        AppSettings.focusLockEnabled -> if (AppSettings.focusLockTop) 2 else 3
        else -> 0
    }
    SettingCard(
        "Which screen the controller works on",
        when (mode) {
            0 -> "The screen you last touched. $send sends it to the top / bottom one."
            1 -> "The screen you send it to with $send — touching the other screen doesn't take it."
            2 -> "Always the top screen, whatever you touch. $send moves it."
            else -> "Always the bottom screen, whatever you touch. $send moves it."
        } + " Hold Home to see your combos and which screen has the controller.",
    ) {
        GlassSegmentedControl(listOf("Follows touch", "Stays where sent", "Always top", "Always bottom"), mode, Modifier.fillMaxWidth()) {
            when (it) {
                0 -> { AppSettings.setFocusLock(false); AppSettings.setFocusStickyOn(false) }
                1 -> { AppSettings.setFocusLock(false); AppSettings.setFocusStickyOn(true) }
                2 -> { AppSettings.setFocusStickyOn(false); AppSettings.setFocusLock(true, top = true) }
                else -> { AppSettings.setFocusStickyOn(false); AppSettings.setFocusLock(true, top = false) }
            }
            ForegroundAppService.reapplyFocusLock()
        }
    }
}

/** AYN's "press Home twice" protection, explained and switchable (it breaks Home combos). */
@Composable
private fun HomeTwiceCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { AynHomeGuard.isOn(ctx) }
    SettingCard(
        "Home needs two presses",
        if (on) "On (AYN's protection against pressing Home by accident). It holds back the first press, so Wayfinder's Home combos can't work."
        else "Off — one press goes Home, and Wayfinder's Home combos work. Turn on if you keep pressing Home by accident.",
        checked = on, onChecked = { AynHomeGuard.set(it) { ForegroundAppService.later(0) { tick++ } } },
    )
}
