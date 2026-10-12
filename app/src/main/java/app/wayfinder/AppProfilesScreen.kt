package app.wayfinder

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import app.wayfinder.ui.glassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import app.wayfinder.ui.AppIconShape
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.Caption
import app.wayfinder.ui.EmptyState
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.ModalHeader
import app.wayfinder.ui.PageHeader
import app.wayfinder.ui.Radii
import app.wayfinder.ui.SectionHeader
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.GlassSheet
import app.wayfinder.ui.ReturnFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable app, with its icon pre-rasterised off the main thread. */
internal class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?, val isGame: Boolean = false)

internal fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                AppEntry(pkg, pm.getApplicationLabel(info).toString(), icon, GameApps.isGame(info, pkg))
            }.getOrNull()
        }
}

private fun SecondScreenPolicy.label() = when (this) {
    SecondScreenPolicy.DEFAULT -> Say.usual(null)
    SecondScreenPolicy.KEEP_ON -> "Bottom stays on"
    SecondScreenPolicy.BLANK -> "Bottom off"
}

/** Review WP11-GAMES-GRID-RETURN: the search a tile was opened from (main thread) — back from its page, it's there again. */
private var queryAtOpen = ""

/**
 * Games: every launchable app as a tile; a tile opens its Game page ([onOpen]: dev build 17, D11 — Game controls, then its
 * own settings: the page Home + X opens in the game; was an options dialog). Fully D-pad / stick navigable; B = back.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppProfilesScreen(myDisplayId: Int, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    // Re-read configs whenever the store changes.
    val storeVersion = AppConfigStore.version.intValue
    // dev build 16 (WP7): the page opens on its first tile (not Back), or comes back to the row that opened the page left —
    // dev build 17 (D11): back from a game's page, its tile ("app:<pkg>")
    val returnTo = remember { app.wayfinder.ui.ReturnFocus.take() }
    // dev build 16 review (WP7-2): the user moved to the header (Back, Find an app…, Import) while the apps loaded — the grid's
    // entry then leaves the focus where they put it (it ended the typing just started)
    var userFocused by remember { mutableStateOf(false) }
    val back = app.wayfinder.ui.rememberBackPress(onBack)
    // build 18 (GC5, G05): the search field in the page's focus order — Up from the grid's first row reaches it (it went to
    // Back: one A then left the page), Left from it is Back
    val searchGate = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    // Import: Android's file picker grants access to the ONE file the user picks (no storage
    // permission, no root reading their files); the file is then checked and cleaned
    // (ProfileShare) and shown in a preview — nothing is saved before "Use these controls".
    var incoming by remember { mutableStateOf<ProfileShare.Incoming?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        ProfileShare.PickInWayfinderFolder()   // opens in Download/Wayfinder, not on "Recent"
    ) { uri -> if (uri != null) ProfileShare.read(ctx, uri).let { (inc, err) -> incoming = inc; importError = err } }

    // With 100+ apps and a controller, alphabetical was a long walk (critique 2026-09-25): the
    // apps on the screens now, then games and emulators, then the rest — the ones already set
    // up first in each. Frozen per visit so tiles don't move under the cursor while you edit.
    // review WP11-GAMES-GRID-RETURN: back from a game's page, the search it was opened from too (a fresh visit: empty)
    var query by remember { mutableStateOf(if (returnTo?.startsWith("app:") == true) queryAtOpen else "") }
    val sections = remember(apps) {
        val all = apps ?: return@remember null
        val set = AppConfigStore.configured()
        val onScreens = ForegroundAppService.screenGames().toSet()
        val sort = compareBy<AppEntry>({ it.pkg !in set }, { it.label.lowercase() })
        listOf(
            "On the screens now" to all.filter { it.pkg in onScreens }.sortedWith(sort),
            "Games and emulators" to all.filter { it.pkg !in onScreens && it.isGame }.sortedWith(sort),
            "Other apps" to all.filter { it.pkg !in onScreens && !it.isGame }.sortedWith(sort),
        )
    }
    val shown = remember(sections, query) {
        val q = query.trim().lowercase()
        sections?.map { (t, l) -> t to if (q.isEmpty()) l else l.filter { it.label.lowercase().contains(q) } }?.filter { it.second.isNotEmpty() }
    }
    val ordered = remember(shown) { shown?.flatMap { it.second } }

    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // dev build 17 (WP8): the shared page header — 1.4: Home + X opens the running game's own controls (not the
            // emulator's). dev build 16 (theme F): in the names of the controller in hand
            // build 18 (GC5, G07): 20 words at most — a game inside an emulator having its own is Help's to say
            PageHeader(NavPages.title(HubPage.APPS), back, Modifier.onFocusChanged { if (it.hasFocus) userFocused = true },
                subtitle = "Each app's screen, performance, controller and lights." +
                    (Say.combo(ThorAction.GAME_CONTROLS, app.wayfinder.ui.currentLook())?.let { " In a game, $it opens its Game controls." } ?: ""),
                backModifier = Modifier.focusRequester(backFocus)) {
                // find an app by name (the Wayfinder keyboard opens on the other screen)
                val field = RoundedCornerShape(Radii.Row)
                app.wayfinder.ui.ControllerTextField(query, { query = it.take(30) }, Modifier.width(200.dp).focusProperties { left = backFocus },
                    textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = 16.sp), placeholder = "Find an app…",
                    surface = Modifier.glassSurface(g, field, raised = false), shape = field,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                    gateFocus = searchGate)
                // build 18 (GC12, gd-D23): a plain button — it was the loudest control on the page
                GlassButton("Import controls", { picker.launch(ProfileShare.PICK_TYPES) }, kind = ButtonKind.NORMAL)
            }
            Box(Modifier.padding(top = 12.dp)) {
                if (ordered == null) {
                    EmptyState("Loading apps…")
                } else {
                    // the tiles are the page's entry: made once the apps are there (an empty page has nothing to focus)
                    val entry = app.wayfinder.ui.rememberEntryFocus(returnKey = returnTo, skip = { userFocused })
                    // review WP11-GAMES-GRID-RETURN: back from a game's page (D11: every tile opens a page — the grid is made
                    // again), the grid starts at its tile: a tile below the first screenful was never laid out, its id never
                    // offered, and the focus fell on the first tile. Its index in the current order (the section headers
                    // counted), one item of context above; gone (uninstalled): the top, as before
                    val startAt = remember {
                        val pkg = returnTo?.takeIf { it.startsWith("app:") }?.removePrefix("app:")
                        var i = 0; var hit = -1
                        if (pkg != null) for ((_, l) in shown.orEmpty()) {
                            i++                                              // its header
                            val at = l.indexOfFirst { it.pkg == pkg }
                            if (at >= 0) { hit = i + at; break }
                            i += l.size
                        }
                        (hit - 1).coerceAtLeast(0)
                    }
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = startAt)
                    // a grid: a one-per-line list wasted most of the Thor's wide screen (2026-09-24)
                    androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalEntryFocus provides entry) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(240.dp),
                        // build 18 (GC5, G05): Up out of the grid (its first row) lands on the search field, never on Back
                        modifier = Modifier.focusProperties {
                            exit = { d -> if (d == FocusDirection.Up) searchGate else FocusRequester.Default }
                        }.focusGroup(),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        for ((title, list) in shown.orEmpty()) {
                            item(key = "h:$title", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                SectionHeader(title)
                            }
                            items(list.size, key = { list[it].pkg }) { i ->
                                val app = list[i]
                                val cfg = remember(storeVersion, app.pkg) { AppConfigStore.get(app.pkg) }
                                AppProfileRow(app, cfg) {
                                    // its page; Back comes back to this tile (ReturnFocus: the page records it)
                                    ReturnFocus.clicked = Nav.appTile(app.pkg)
                                    queryAtOpen = query
                                    if (BuildConfig.DEBUG) android.util.Log.d("ThorGames", "open app=%04x".format(app.pkg.hashCode() and 0xffff))
                                    onOpen(app.pkg)
                                }
                            }
                        }
                        if (shown.isNullOrEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            EmptyState("No app matches “$query”.")
                        }
                    }
                    }
                }
            }
        }
    }
    if (incoming != null || importError != null) ImportDialog(incoming, importError, onUse = { inc ->
        Overlays.pill(if (!ProfileShare.apply(inc)) "Those controls couldn't be saved" else "Controls imported for ${inc.title}" +
            if (inc.report.macrosOff > 0) " — macros stay off until you turn them on" else "", CueOrigin.Window(myDisplayId))
        incoming = null; importError = null
    }) { incoming = null; importError = null }
}

@Composable
private fun AppProfileRow(app: AppEntry, cfg: AppConfig, onOpen: () -> Unit) {
    val g = LocalGlass.current
    // a tile: icon + name, and what's set in two lines under it
    FocusableGlass(onClick = onOpen, modifier = Modifier.fillMaxWidth(), radius = Radii.Card, focusId = Nav.appTile(app.pkg)) {
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (app.icon != null)
                Image(app.icon, null, Modifier.size(44.dp).clip(AppIconShape))
            else Box(Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val summary = listOf(cfg.summary(), GameProfiles.forApp(app.pkg).size.let {
                    if (it == 0) "" else "$it game${if (it > 1) "s" else ""} set up" }).filter { it.isNotEmpty() }.joinToString(SUMMARY_SEP)
                // build 18 (GC12, gd-D24 / T18-11 / FTU-19): nothing of its own = "Not set up" in grey ("Usual" read like a mode);
                // set up = its summary in the secondary text after an accent dot (accent text means focus and links)
                if (summary.isEmpty()) Text("Not set up", color = g.textTertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                else Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(6.dp).background(g.accent, CircleShape))
                    Text(summary, color = g.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Build 18 (GC12, gd-D24): the summary's separator — a no-break space before the dot, so a line never starts with "·". */
private const val SUMMARY_SEP = "\u00A0· "

/** What's set for this app, in a few words (the list shows it; "" = nothing set). */
private fun AppConfig.summary(): String = listOfNotNull(
    route.takeIf { it != Route.ANY }?.let { "Opens on the ${it.label.lowercase()} screen" },
    followMove?.let { if (it) "Controller goes with it" else "Controller stays" },
    extFirst?.let { if (it) "A connected controller is player 1" else "The Thor is player 1" },
    second.takeIf { it != SecondScreenPolicy.DEFAULT }?.label(),
    buttonsMode.takeIf { it != ButtonsMode.NORMAL }?.let { "Combos ${it.label.lowercase()}" },
    face?.let { "${it.label} buttons" },
    remap?.changes?.takeIf { it > 0 }?.let { "$it button change${if (it > 1) "s" else ""}" },
    perf?.let { "Performance ${it.label.lowercase()}" },
    fan?.let { "Fan ${it.label.lowercase()}" },
    hz?.let { "$it Hz" },
    lights?.let { "Own lights" },
    "Guide".takeIf { companion },
).joinToString(SUMMARY_SEP)

/** Import controls: what the file holds — checked and cleaned (ProfileShare) — before anything is saved ("Use these
 *  controls"). */
@Composable
private fun ImportDialog(inc: ProfileShare.Incoming?, error: String?, onUse: (ProfileShare.Incoming) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    // dev build 16 (WP7): an in-window sheet (was a Dialog window). dev build 17 (WP8): Close / Cancel in the header, on the
    // left — the focus only when it is the sheet's one action (an unusable file)
    GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.7f), first = first) {
        ModalHeader("Import controls", onClose, closeLabel = if (inc == null) "Close" else "Cancel", closeFocus = if (inc == null) first else null)
        if (inc == null) {
            Text(error ?: "That file can't be used.", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        } else {
            val installed = remember(inc) { ProfileShare.installed(ctx, inc.pkg) }
            val replaces = remember(inc) { Profiles.get(inc.key).remap != null }
            Text(if (inc.game != null) "For ${inc.title} — a game in ${inc.appName}" else "For ${inc.appName}",
                color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(buildList {
                add("${inc.remap.changes} change${if (inc.remap.changes == 1) "" else "s"} to the controls")
                inc.face?.let { add("face buttons: ${it.label}") }
                if (inc.remap.gyro.isOn) add("gyro")
            }.joinToString(" · "), color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (!installed) Text("${inc.appName} isn't installed — the controls wait for it.",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (replaces) Text("This replaces the controls you have for ${inc.title}.",
                color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
            // what the safety check removed or switched off
            for (l in inc.report.lines()) Text(l, color = g.warn, style = MaterialTheme.typography.bodyMedium)
            Caption("Shared files only carry game controls: never Wayfinder's combos or system keys.")
            GlassButton("Use these controls", { onUse(inc) }, kind = ButtonKind.PRIMARY, focusRequester = first)
        }
    }
}
