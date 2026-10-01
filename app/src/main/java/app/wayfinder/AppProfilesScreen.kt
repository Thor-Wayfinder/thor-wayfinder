package app.wayfinder

import app.wayfinder.lights.LightSettings
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import app.wayfinder.ui.glassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import app.wayfinder.ui.GlassListRow
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.wayfinder.ui.Glass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    SecondScreenPolicy.DEFAULT -> "Default"
    SecondScreenPolicy.KEEP_ON -> "Bottom stays on"
    SecondScreenPolicy.BLANK -> "Bottom off"
}

/**
 * Per-app profiles. One row per launchable
 * app; A / tap opens its options inline. Fully D-pad / stick navigable; B = back.
 */
@Composable
fun AppProfilesScreen(myDisplayId: Int, onEditButtons: (String) -> Unit = {}, onEditLights: (String) -> Unit = {},
                      onEditRemap: (String) -> Unit = {}, onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    // Re-read configs whenever the store changes.
    val storeVersion = AppConfigStore.version.intValue
    var expanded by remember { mutableStateOf(reopenAfterEdit.also { reopenAfterEdit = null }) }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { backFocus.requestFocus() } }
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
    var query by remember { mutableStateOf("") }
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
                Column(Modifier.weight(1f)) {
                    Text("Games", color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    // 1.4: Home + X opens the running game's own controls (not the emulator's)
                    Text(
                        "Each game and app: its screen, performance, controller and lights. Nothing changes unless you set it. " +
                            "In a game, " + (ControlsStore.triggerFor(ThorAction.GAME_CONTROLS)?.label() ?: "Game controls") +
                            " opens its own buttons — a game inside an emulator gets its own too.",
                        color = g.textTertiary, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // find an app by name (the Wayfinder keyboard opens on the other screen)
                app.wayfinder.ui.ControllerTextField(query, { query = it.take(30) }, Modifier.width(200.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = 16.sp), placeholder = "Find an app…",
                    surface = Modifier.glassSurface(g, RoundedCornerShape(16.dp), raised = false), shape = RoundedCornerShape(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 11.dp))
                FocusableGlass(onClick = { picker.launch(ProfileShare.PICK_TYPES) }, radius = 16.dp) {
                    Text("Import controls", color = g.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Box(Modifier.padding(top = 12.dp)) {
                if (ordered == null) {
                    Text("Loading apps…", color = g.textSecondary, modifier = Modifier.padding(8.dp))
                } else {
                    // a grid: a one-per-line list wasted most of the Thor's wide screen (2026-09-24)
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(240.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        for ((title, list) in shown.orEmpty()) {
                            item(key = "h:$title", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Text(title.uppercase(), color = g.textTertiary, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                            }
                            items(list.size, key = { list[it].pkg }) { i ->
                                val app = list[i]
                                val cfg = remember(storeVersion, app.pkg) { AppConfigStore.get(app.pkg) }
                                AppProfileRow(app, cfg) { expanded = app.pkg }
                            }
                        }
                        if (shown.isNullOrEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text("No app matches “$query”.", color = g.textSecondary, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
    if (incoming != null || importError != null) ImportDialog(incoming, importError, onUse = { inc ->
        ForegroundAppService.pill(if (!ProfileShare.apply(inc)) "Those controls couldn't be saved" else "Controls imported for ${inc.title}" +
            if (inc.report.macrosOff > 0) " — macros stay off until you turn them on" else "", myDisplayId, 3500)
        incoming = null; importError = null
    }) { incoming = null; importError = null }
    val open = expanded?.let { p -> ordered?.firstOrNull { it.pkg == p } }
    if (open != null) {
        val cfg = remember(storeVersion, open.pkg) { AppConfigStore.get(open.pkg) }
        AppOptionsDialog(open, cfg, onEditButtons, onEditLights, onEditRemap) { expanded = null }
    }
}

/** The app whose options were open when the user went to edit its buttons / lights:
 *  its popup comes back when they return, instead of dropping them at the list. */
private var reopenAfterEdit: String? = null

/** Open [pkg]'s options when the Games page shows next (the Battery page's "its own level" rows). */
internal fun openAppNext(pkg: String) { reopenAfterEdit = pkg }

@Composable
private fun AppProfileRow(app: AppEntry, cfg: AppConfig, onOpen: () -> Unit) {
    val g = LocalGlass.current
    // a tile: icon + name, and what's set in two lines under it
    FocusableGlass(onClick = onOpen, modifier = Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (app.icon != null)
                Image(app.icon, null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)))
            else Box(Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val summary = cfg.summary() + GameProfiles.forApp(app.pkg).size.let {
                    if (it == 0) "" else (if (cfg.summary().isEmpty()) "" else " · ") + "$it game profile${if (it > 1) "s" else ""}"
                }
                Text(
                    summary.ifEmpty { "Default" },
                    color = if (summary.isEmpty()) g.textTertiary else g.accent,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What's set for this app, in a few words (the list shows it; "" = nothing set). */
private fun AppConfig.summary(): String = listOfNotNull(
    route.takeIf { it != Route.ANY }?.let { "Opens on the ${it.label.lowercase()} screen" },
    followMove?.let { if (it) "Controller goes with it" else "Controller stays" },
    second.takeIf { it != SecondScreenPolicy.DEFAULT }?.label(),
    buttonsMode.takeIf { it != ButtonsMode.NORMAL }?.let { "Combos ${it.label.lowercase()}" },
    face?.let { "${it.label} buttons" },
    remap?.changes?.takeIf { it > 0 }?.let { "$it remap${if (it > 1) "s" else ""}" },
    perf?.let { "Performance ${it.label.lowercase()}" },
    fan?.let { "Fan ${it.label.lowercase()}" },
    hz?.let { "$it Hz" },
    lights?.let { "Own lights" },
    "Guide".takeIf { companion },
).joinToString(" · ")

/**
 * Every option for one app in a single popup — no scrolling, no drawer pushing the list
 * around. Two columns: the screen side on the left, the controller side on the right.
 * Closes with Done, B / Back, or a tap outside.
 */
@Composable
private fun ImportDialog(inc: ProfileShare.Incoming?, error: String?, onUse: (ProfileShare.Incoming) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(26.dp)
        Box(Modifier.fillMaxWidth(0.7f).background(g.base.copy(alpha = 0.97f), shape).glassSurface(g, shape, raised = true)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Import controls", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                if (inc == null) {
                    Text(error ?: "That file can't be used.", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
                    FocusableGlass(onClick = onClose, radius = 14.dp, focusRequester = first) {
                        Text("Close", color = g.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
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
                    for (l in inc.report.lines()) Text(l, color = androidx.compose.ui.graphics.Color(0xFFE0A63A), style = MaterialTheme.typography.bodyMedium)
                    Text("Shared files only carry controller mappings: never Wayfinder's combos or system keys.",
                        color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusableGlass(onClick = { onUse(inc) }, radius = 14.dp, focusRequester = first) {
                            Text("Use these controls", color = g.accent, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                        FocusableGlass(onClick = onClose, radius = 14.dp) {
                            Text("Cancel  ·  ${ButtonNames.m("B")}", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppOptionsDialog(
    app: AppEntry, cfg: AppConfig,
    onEditButtons: (String) -> Unit, onEditLights: (String) -> Unit, onEditRemap: (String) -> Unit, onClose: () -> Unit,
) {
    val g = LocalGlass.current
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { doneFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(26.dp)
        Box(
            Modifier.fillMaxWidth(0.94f)
                .background(g.base.copy(alpha = 0.97f), shape)
                .glassSurface(g, shape, raised = true),
        ) {
            // 1.3: scrolls — taller than the screen since the frame-rate row (its bottom was unreachable)
            val scroll = androidx.compose.foundation.rememberScrollState()
            val firstOption = remember { FocusRequester() }
            Column(Modifier.verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (app.icon != null) Image(app.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = g.textPrimary, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Only what you change here applies to ${app.label}; the rest stays as usual.",
                            color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                    FocusableGlass(onClick = onClose, radius = 14.dp, focusRequester = doneFocus) {
                        Text("Done  ·  ${ButtonNames.m("B")}", color = g.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
                // 1.4: only what's different for this app — the rest behind "Change something"
                var all by remember(app.pkg) { mutableStateOf(false) }
                val sRoute = all || cfg.route != Route.ANY; val sReopen = all || cfg.reopenOnMove != null
                val sFollow = all || cfg.followMove != null
                val sSecond = all || cfg.second != SecondScreenPolicy.DEFAULT
                val sPerf = all || cfg.perf != null; val sFan = all || cfg.fan != null; val sHz = all || cfg.hz != null; val sFps = all || cfg.fps != null
                val sCombos = all || cfg.buttonsMode != ButtonsMode.NORMAL; val sBack = all || cfg.backToGame != null
                val sLayer = all || cfg.layer != null
                val sLights = Features.lights && (all || cfg.lights != null); val sGuide = all || cfg.companion
                // every setting shown: the controller goes to the first one (before: it stayed on the row that had just
                // moved below the dialog's edge — nothing looked selected)
                var shownAll by remember(app.pkg) { mutableStateOf(false) }
                LaunchedEffect(all) {
                    if (all && !shownAll) { shownAll = true; delay(120); scroll.animateScrollTo(0); runCatching { firstOption.requestFocus() } }
                    if (!all) shownAll = false
                }
                Row(Modifier.focusRequester(firstOption), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Left: the screens and the hardware (1.4: only when something there is shown)
                    if (sRoute || sReopen || sFollow || sSecond || sPerf || sFan || sHz || sFps) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sRoute || sReopen || sFollow || sSecond) GroupHead("Screen")
                        if (sRoute) {
                        OptionLabel("Opens on")
                        GlassSegmentedControl(Route.values().map { it.label }, cfg.route.ordinal, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(route = Route.values()[i]) }
                        }
                        }
                        if (sReopen) {
                        // 1.3.2: live move, or reopen it there (Firefox crashed after a live move)
                        OptionLabel("When it moves to the other screen: " + (if (reopensOnMove(app.pkg)) "it reopens there (tabs and state reload)"
                            else "it keeps running") + if (cfg.reopenOnMove == null && reopensOnMoveByDefault(app.pkg)) " (automatic: Firefox-based browsers reopen)" else "")
                        GlassSegmentedControl(listOf("Automatic", "Keep running", "Reopen"),
                            when (cfg.reopenOnMove) { null -> 0; false -> 1; true -> 2 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(reopenOnMove = when (i) { 1 -> false; 2 -> true; else -> null }) }
                        }
                        }
                        if (sFollow) {
                        // 1.4: per game — the controller goes with it when it's moved / swapped, or stays
                        val locked = AppSettings.focusLockEnabled && !AppSettings.focusSticky
                        OptionLabel("The controller when ${app.label} moves: " + when {
                            locked -> "stays (the controller is set to Always top / bottom)"
                            AppSettings.followsMove(app.pkg) -> "goes with it"
                            else -> "stays on its screen"
                        } + if (cfg.followMove == null && !locked) " (usual)" else "")
                        GlassSegmentedControl(listOf("Usual", "Goes with it", "Stays"),
                            when (cfg.followMove) { null -> 0; true -> 1; false -> 2 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(followMove = when (i) { 1 -> true; 2 -> false; else -> null }) }
                        }
                        }
                        if (sSecond) {
                        OptionLabel("Bottom screen while ${app.label} is on top")
                        GlassSegmentedControl(listOf("Usual", "Keep on", "Off"), cfg.second.ordinal, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(second = SecondScreenPolicy.values()[i]) }
                            ForegroundAppService.reapplyPolicy()
                        }
                        }
                        if (sPerf || sFan || sHz || sFps) GroupHead("Performance")
                        val tuner = Tuners.installed(LocalContext.current)
                        if ((sPerf || (tuner == "Pulse" && (sFan || sHz))) && tuner != null) Text(Tuners.note(tuner), color = LocalGlass.current.textTertiary,
                            style = MaterialTheme.typography.bodySmall)
                        if (sPerf && tuner == null) {
                        OptionLabel("Performance" + if (cfg.perf == null) " — Usual = your AYN setting" else "")
                        GlassSegmentedControl(listOf("Usual") + PerfMode.ayn.map { it.label },
                            if (cfg.perf == null) 0 else PerfMode.ayn.indexOf(cfg.perf).let { if (it < 0) -1 else it + 1 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(perf = if (i == 0) null else PerfMode.ayn[i - 1]) }
                            ForegroundAppService.reapplyPerf()
                        }
                        }
                        if (sFan && tuner != "Pulse") {
                        OptionLabel("Fan")
                        GlassSegmentedControl(listOf("Usual") + FanMode.values().map { it.label }, (cfg.fan?.ordinal ?: -1) + 1, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(fan = if (i == 0) null else FanMode.values()[i - 1]) }
                            ForegroundAppService.reapplyPerf()
                        }
                        }
                        if (sHz && tuner != "Pulse") {
                        OptionLabel("Refresh rate")
                        GlassSegmentedControl(listOf("Usual", "60 Hz", "120 Hz"), when (cfg.hz) { 60 -> 1; 120 -> 2; else -> 0 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(hz = when (i) { 1 -> 60; 2 -> 120; else -> null }) }
                            ForegroundAppService.reapplyPerf()
                        }
                        }
                        // 1.3 (GitHub #22)
                        if (sFps) {
                        OptionLabel("Frame-rate counter")
                        GlassSegmentedControl(listOf("Usual", "Shown", "Hidden"), when (cfg.fps) { true -> 1; false -> 2; null -> 0 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(fps = when (i) { 1 -> true; 2 -> false; else -> null }) }
                            ForegroundAppService.reapplyFps()
                        }
                        }
                    }
                    // Right: the controller, and the bottom-screen guide
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupHead("Controller")
                        if (sCombos) {
                        OptionLabel(when (cfg.buttonsMode) {
                            ButtonsMode.NORMAL -> "Wayfinder combos: your usual ones"
                            ButtonsMode.CUSTOM -> "Wayfinder combos: custom for ${app.label}"
                            ButtonsMode.OFF -> "Wayfinder combos: off (all buttons go to ${app.label})"
                        })
                        OptionWithEdit(
                            ButtonsMode.values().map { it.label }, cfg.buttonsMode.ordinal,
                            editLabel = "Edit ›".takeIf { cfg.buttonsMode == ButtonsMode.CUSTOM },
                            onEdit = { reopenAfterEdit = app.pkg; onEditButtons(app.pkg) },
                        ) { i ->
                            val mode = ButtonsMode.values()[i]
                            AppConfigStore.update(app.pkg) { it.copy(buttonsMode = mode) }
                            if (mode == ButtonsMode.CUSTOM) { reopenAfterEdit = app.pkg; onEditButtons(app.pkg) }
                        }
                        }
                        if (sBack) {
                        // 1.3: Back as the game's own button (RetroArch's menu / hotkeys) — automatic for RetroArch
                        val backFree = ControlsStore.backToGame(app.pkg)
                        OptionLabel(if (backFree) "Back button: goes to ${app.label} (held too) — no Back combos here"
                            else "Back button: Wayfinder's (its taps, hold and combos)" +
                                if (cfg.backToGame == null) " — a short Back, or Back with another button held, still reaches ${app.label}" else "")
                        GlassSegmentedControl(listOf("Automatic", "Wayfinder", "The game"),
                            when (cfg.backToGame) { null -> 0; false -> 1; true -> 2 }, Modifier.fillMaxWidth()) { i ->
                            AppConfigStore.update(app.pkg) { it.copy(backToGame = when (i) { 1 -> false; 2 -> true; else -> null }) }
                        }
                        }
                        if (sLayer) {
                        // 1.4 (GitHub #60): the input layer for this app
                        val ctxL = androidx.compose.ui.platform.LocalContext.current
                        OptionLabel("Input layer: " + when (cfg.layer) {
                            true -> "on for ${app.label}"
                            false -> "off — ${app.label} gets the Thor's own controller (for games that don't like the copy)"
                            null -> if (PadLayerCtl.need(app.pkg) == true) "on (automatic)" else "automatic — ${PadLayerCtl.mode.label.lowercase()}"
                        })
                        GlassSegmentedControl(listOf("Automatic", "On", "Off"), when (cfg.layer) { null -> 0; true -> 1; false -> 2 }, Modifier.fillMaxWidth()) { i ->
                            PadLayerCtl.setForApp(ctxL, app.pkg, when (i) { 1 -> true; 2 -> false; else -> null })
                        }
                        }
                        // the layer off = these do nothing: said FIRST (the line is cut when long)
                        OptionLabel((if ((cfg.face != null || cfg.remap != null) && PadLayerCtl.need(app.pkg) == false) "Input layer off — these do nothing · " else "") +
                            "Game controls: " + when (cfg.face) {
                            null -> "face buttons like all apps"
                            FaceLayout.NINTENDO -> "Nintendo (A right)"
                            FaceLayout.XBOX -> "Xbox (A bottom)"
                        } + (cfg.remap?.changes?.takeIf { it > 0 }?.let { " · $it change${if (it > 1) "s" else ""}" } ?: "") +
                            GameProfiles.forApp(app.pkg).size.let { if (it == 0) "" else " · $it game profile${if (it > 1) "s" else ""}" })
                        // face buttons, remaps, gyro: all set in Game controls (their one home)
                        // full width: a small button here was skipped by the D-pad going down
                        FocusableGlass(onClick = { reopenAfterEdit = app.pkg; onEditRemap(app.pkg) }, radius = 14.dp, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Game controls ›", color = LocalGlass.current.accent, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                        if (sLights || sGuide) GroupHead(if (Features.lights) "Lights & guide" else "Guide")
                        if (sLights) {
                        if (Features.lights) OptionLabel(if (cfg.lights == null) "Stick lights: your usual lights" else "Stick lights: ${cfg.lights.mode.label} for ${app.label}")
                        if (Features.lights) OptionWithEdit(
                            listOf("Usual", "Own"), if (cfg.lights == null) 0 else 1,
                            editLabel = "Edit ›".takeIf { cfg.lights != null },
                            onEdit = { reopenAfterEdit = app.pkg; onEditLights(app.pkg) },
                        ) { i ->
                            if (i == 0) AppConfigStore.update(app.pkg) { it.copy(lights = null) }
                            else if (cfg.lights == null) {
                                AppConfigStore.update(app.pkg) { it.copy(lights = LightSettings.global) }
                                reopenAfterEdit = app.pkg; onEditLights(app.pkg)
                            }
                            ForegroundAppService.reapplyLights()
                        }
                        }
                        if (sGuide) {
                        OptionLabel(if (cfg.companion) "Guide & notes on the bottom screen: open with ${app.label}"
                            else "Guide & notes on the bottom screen: off")
                        OptionWithEdit(
                            listOf("Off", "Guide & notes"), if (cfg.companion) 1 else 0,
                            editLabel = "Open now ›", onEdit = { ForegroundAppService.openCompanionNow(app.pkg) },
                        ) { i -> AppConfigStore.update(app.pkg) { it.copy(companion = i == 1) } }
                        }
                    }
                }
                GlassListRow(if (all) "Show only what's changed" else "Change something for ${app.label}",
                    value = if (all) null else "screen, performance, combos, Back, lights, guide",
                    icon = if (all) Icons.Rounded.ExpandLess else Icons.Rounded.Tune) { all = !all }
                // 1.4: the games inside this app that have their own controls — each one press away
                val games = GameProfiles.forApp(app.pkg)
                if (games.isNotEmpty()) {
                    GroupHead("Games in ${app.label} with their own controls")
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        games.forEach { (key, p) ->
                            FocusableGlass(onClick = { reopenAfterEdit = app.pkg; onEditRemap(key) }, radius = 14.dp) {
                                Text("${p.title}  ›", color = g.accent, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
                // Two apps can be open at once, each with its own profile — say who wins.
                Text(
                    "Two apps open, each with a profile? Performance and fan: the more demanding one wins. " +
                        "Buttons and stick lights: the app that has the controller. Bottom screen: the app on top decides.",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 1.4: a group's heading inside one app's options. */
@Composable
private fun GroupHead(text: String) =
    Text(text.uppercase(), color = LocalGlass.current.accent, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp))

@Composable
private fun OptionLabel(text: String) =
    Text(text, color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)

/** A choice plus (when it applies) its "Edit ›" button — same shape for every option. */
@Composable
private fun OptionWithEdit(options: List<String>, selected: Int, editLabel: String?, onEdit: () -> Unit, onSelect: (Int) -> Unit) {
    val g = LocalGlass.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassSegmentedControl(options, selected, Modifier.weight(1f), onSelect = onSelect)
        if (editLabel != null) FocusableGlass(onClick = onEdit, radius = 14.dp) {
            Text(editLabel, color = g.accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
