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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.Glass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.VSpace
import kotlinx.coroutines.delay

/**
 * Controls: every action and the button trigger bound to it. Pick an action,
 * press the combination, save. Controller-navigable; while capturing, every press
 * goes to the capture (so the combination can include B, D-pad…), and capture
 * stops as soon as one combination is complete so the controller works again.
 */
@Composable
fun ControlsScreen(myDisplayId: Int, pkg: String? = null, onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue + AppConfigStore.version.intValue   // recompose on change
    var editing by remember { mutableStateOf<ThorAction?>(null) }
    // round 8 — an "Open…" combo being set: its target and the trigger it had (null = new);
    // [picking] = the list to pick a target from ("app" / "pair" / "page")
    var openEdit by remember { mutableStateOf<Pair<String, Trigger?>?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    // when the "Open…" card or picker closes, the controller lands on "+ An app" (it landed nowhere)
    val openFocus = remember { FocusRequester() }
    var openWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(openEdit, picking) {
        if (openEdit != null || picking != null) openWasUp = true
        else if (openWasUp) { openWasUp = false; delay(80); runCatching { openFocus.requestFocus() } }
    }
    // Scoped to one app: shows that app's effective combos; saves only to it.
    val appLabel = remember(pkg) {
        pkg?.let { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
    }
    val backFocus = remember { FocusRequester() }
    var resetNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resetNote) { if (resetNote != null) { delay(2500); resetNote = null } }
    // "Reset" wipes every custom combo: a second press confirms it (like the remap screen's Reset all)
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(3000); confirmReset = false } }
    val view = androidx.compose.ui.platform.LocalView.current
    val listFocusable = editing == null && openEdit == null && picking == null   // the card / picker is modal
    LaunchedEffect(Unit) { delay(300); runCatching { backFocus.requestFocus() } }
    // When the card closes, the controller lands back on the row it came from (it used
    // to land nowhere: A did nothing and the D-pad restarted at the top).
    val rowFocus = remember { HashMap<ThorAction, FocusRequester>() }
    var lastEdited by remember { mutableStateOf<ThorAction?>(null) }
    LaunchedEffect(editing) {
        if (editing != null) lastEdited = editing
        else lastEdited?.let { a -> delay(80); runCatching { rowFocus[a]?.requestFocus() } }
    }

    val actions = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.OPEN }

    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
      Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Each focusable carries the modal rule itself: on the page's Column it hid every
                // row from the D-pad (down from the back button went nowhere — 2026-09-24).
                FocusableGlass(onClick = onBack, radius = 16.dp, focusRequester = backFocus,
                    modifier = Modifier.focusProperties { canFocus = listFocusable }) {
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
                    Text(if (pkg == null) "Combos" else "Combos · $appLabel", color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (pkg == null) "Button combos that run Wayfinder's actions anywhere. Combos that start with Home or Back " +
                            "never reach the game. (A game's own buttons: App profiles, then the game, then Game controls.)"
                        else "Changes here only apply while $appLabel has the controller. Highlighted = specific to this app.",
                        color = g.textTertiary, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                resetNote?.let { Text(it, color = Glass.Positive, style = MaterialTheme.typography.labelLarge) }
                FocusableGlass(onClick = {
                    if (!confirmReset) { confirmReset = true; return@FocusableGlass }
                    confirmReset = false
                    if (pkg == null) ControlsStore.resetDefaults()
                    else AppConfigStore.update(pkg) { it.copy(buttons = emptyList(), freed = emptyList()) }
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    resetNote = if (pkg == null) "✓ Back to the default combos" else "✓ Using your usual combos"
                }, radius = 16.dp, modifier = Modifier.focusProperties { canFocus = listFocusable }) {
                    Text(when {
                        confirmReset -> "Press again to reset"
                        pkg == null -> "Reset"
                        else -> "Use my usual combos"
                    }, color = if (confirmReset) androidx.compose.ui.graphics.Color(0xFFD64545) else g.textSecondary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            ) {
                items(actions, key = { it.name }) { action ->
                    val trigger = if (pkg == null) ControlsStore.triggerFor(action) else ControlsStore.effectiveTriggerFor(pkg, action)
                    val appSpecific = pkg != null && AppConfigStore.get(pkg).let { c ->
                        c.buttons.any { it.action == action } ||
                            (ControlsStore.triggerFor(action)?.let { it in c.freed } == true)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableGlass(
                            onClick = { editing = if (editing == action) null else action },
                            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = listFocusable }, radius = 18.dp,
                            focusRequester = rowFocus.getOrPut(action) { FocusRequester() },
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
                            Text("OPEN WITH A COMBO", color = g.textTertiary, style = MaterialTheme.typography.labelMedium)
                            Text("An app opens on the screen with the controller; a pair opens both apps, each on its screen.",
                                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(ControlsStore.opens(), key = { "open:" + it.trigger.label() }) { b ->
                        FocusableGlass(
                            onClick = { openEdit = b.arg!! to b.trigger },
                            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = listFocusable }, radius = 18.dp,
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
                                FocusableGlass(onClick = { picking = kind }, radius = 16.dp,
                                    focusRequester = if (kind == "app") openFocus else null,
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
        // The combo editor: a centred card over a dimmed page — always in view, and the
        // list behind can't take focus (so scrolling can't happen, or be captured).
        editing?.let { action ->
            // B / Back closes the editor, not the whole page (it left the page and dropped the
            // edit — review 2026-09-25)
            androidx.activity.compose.BackHandler { editing = null }
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 720.dp).padding(24.dp)
                    .background(if (g.dark) Color(0xF01C1E26) else Color(0xF5F4F5FA), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))) {
                    CaptureCard(action, pkg, onDone = { editing = null })
                }
            }
        }
        openEdit?.let { (arg, old) ->
            androidx.activity.compose.BackHandler { openEdit = null }
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 720.dp).padding(24.dp)
                    .background(if (g.dark) Color(0xF01C1E26) else Color(0xF5F4F5FA), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))) {
                    CaptureCard(ThorAction.OPEN, null, onDone = { openEdit = null }, openArg = arg, openOld = old)
                }
            }
        }
        picking?.let { kind ->
            androidx.activity.compose.BackHandler { picking = null }
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = if (kind == "app") 1400.dp else 760.dp).fillMaxHeight(0.86f).padding(24.dp)
                    .background(if (g.dark) Color(0xF01C1E26) else Color(0xF5F4F5FA), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))) {
                    OpenPicker(kind, onCancel = { picking = null }) { arg -> picking = null; openEdit = arg to null }
                }
            }
        }
      }
    }
}

/** A readable warning: amber panel, normal text colour (yellow text on glass was unreadable). */
@Composable
private fun WarnBox(text: String) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().background(Glass.Warn.copy(alpha = if (g.dark) 0.22f else 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
        Text(text, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TriggerChip(trigger: Trigger?, highlight: Boolean = true) {
    val g = LocalGlass.current
    GlassPanel(radius = 14.dp, strong = trigger != null && highlight) {
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
    val title = if (openArg != null) OpenTargets.label(ctx, openArg) else action.title
    var capturing by remember { mutableStateOf(true) }
    val order = remember { mutableStateListOf<ThorButton>() }    // buttons in press order this attempt
    val down = remember { mutableStateListOf<ThorButton>() }
    var result by remember { mutableStateOf<Trigger?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var press by remember { mutableStateOf(Press.TAP) }
    val saveFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }

    // Hook the engine's capture while this card is capturing. Presses are swallowed at
    // once (nothing leaks to the page), but only count once ARMED: ~0.4 s after the card
    // appears, and only buttons pressed after that — the A that opened the card, or a
    // D-pad scroll, can never become the combo.
    LaunchedEffect(capturing) {
        armed = false
        if (capturing) { delay(400); armed = true; delay(10_000); if (capturing && order.isEmpty()) onDone() }
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
                            order.size == 1 && order[0] == ThorButton.B -> { onDone(); return@startCapture }   // B alone = cancel
                            order.size >= 2 -> result = Trigger(order[1], modifier = order[0])
                            order[0].isSystem -> result = Trigger(order[0], press)
                            else -> error = "${order[0].spoken} alone would break games — use Home or Back, " +
                                "or hold a button and press another."
                        }
                        capturing = false
                    }
                }
            }
        }
        onDispose { ForegroundAppService.stopCapture() }
    }
    LaunchedEffect(result) { if (result != null) { delay(150); runCatching { saveFocus.requestFocus() } } }

    GlassPanel(Modifier.fillMaxWidth(), radius = 22.dp, strong = true) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (capturing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningDot(armed)
                    Text(if (armed) "Listening — press the buttons for “$title”" else "Get ready…",
                        color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (order.isEmpty()) "Home or Back on their own, or hold one button and press a second. B on its own cancels."
                    else order.joinToString(" + ") { it.spoken },
                    color = if (order.isEmpty()) g.textTertiary else g.accent,
                    style = MaterialTheme.typography.bodyLarge,
                )
                FocusableGlass(onClick = { onDone() }, radius = 14.dp) {
                    Text("Cancel", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            } else {
                val r = result
                if (r != null) {
                    val t = if (r.isChord) r else r.copy(press = press)
                    Text("For “$title”", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    app.wayfinder.ui.TriggerGlyphs(t, 34.dp)
                    if (!r.isChord) {
                        GlassSegmentedControl(
                            options = Press.values().map { it.label.replaceFirstChar { c -> c.uppercase() } },
                            selectedIndex = press.ordinal, modifier = Modifier.fillMaxWidth(),
                        ) { press = Press.values()[it] }
                    }
                    val note = when {
                        r.isChord && r.modifier?.isSystem == false && r.button.isDpad ->
                            "Both ${r.modifier.spoken} and the D-pad still reach the game."
                        r.isChord && r.modifier?.isSystem == false -> "${r.modifier.spoken} still reaches the game; ${r.button.spoken} is hidden from it."
                        r.isChord && r.button.isDpad -> "The D-pad press still reaches the game."
                        else -> null
                    }
                    note?.let { Text(it, color = g.textTertiary, style = MaterialTheme.typography.bodySmall) }
                    ControlsStore.effective(pkg).firstOrNull { it.trigger == t }
                        ?.takeIf { if (openArg != null) it.trigger != openOld else it.action != action }?.let {
                            WarnBox("Currently used by “${OpenTargets.title(ctx, it)}” — saving moves it here.")
                        }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusableGlass(onClick = {
                            if (openArg != null) ControlsStore.bindOpen(openArg, t, openOld) else saveBinding(pkg, action, t); onDone()
                        }, radius = 14.dp, focusRequester = saveFocus) {
                            Text("Save", color = g.accent, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        }
                        FocusableGlass(onClick = { capturing = true }, radius = 14.dp) {
                            Text("Try again", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                        FocusableGlass(onClick = { onDone() }, radius = 14.dp) {
                            Text("Cancel", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                } else {
                    WarnBox(error ?: "")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusableGlass(onClick = { capturing = true }, radius = 14.dp, focusRequester = saveFocus) {
                            Text("Try again", color = g.accent, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                        FocusableGlass(onClick = { onDone() }, radius = 14.dp) {
                            Text("Cancel", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    LaunchedEffect(Unit) { delay(150); runCatching { saveFocus.requestFocus() } }
                }
            }
            val overridden = openArg == null && pkg != null && AppConfigStore.get(pkg).let { c ->
                c.buttons.any { it.action == action } || (ControlsStore.triggerFor(action)?.let { it in c.freed } == true)
            }
            if (!capturing && overridden) {
                FocusableGlass(onClick = { useGlobal(pkg!!, action); onDone() }, radius = 14.dp) {
                    Text("Use my usual combo (${ControlsStore.triggerFor(action)?.label() ?: "not set"})", color = g.textSecondary,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            val hasCombo = if (openArg != null) openOld != null
                else (if (pkg == null) ControlsStore.triggerFor(action) else ControlsStore.effectiveTriggerFor(pkg, action)) != null
            if (!capturing && hasCombo) {
                FocusableGlass(onClick = { if (openArg != null) ControlsStore.unbindOpen(openOld!!) else removeBinding(pkg, action); onDone() }, radius = 14.dp) {
                    Text("Remove the combo", color = Glass.Danger, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Round 8 — pick what an "Open…" combo opens: an app, an app pair or a Wayfinder page. */
@Composable
private fun OpenPicker(kind: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
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
    LaunchedEffect(options) { if (options != null) { delay(150); runCatching { first.requestFocus() } } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (kind) { "app" -> "Which app?"; "pair" -> "Which app pair?"; else -> "Which Wayfinder page?" },
                color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FocusableGlass(onClick = onCancel, radius = 14.dp) {
                Text("Cancel · B", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        val list = options
        when {
            list == null -> Text("Loading…", color = g.textTertiary)
            list.isEmpty() -> Text(if (kind == "pair") "No app pairs yet — save one from the quick panel's “App pairs” tile, or on the App pairs page."
                else "Nothing to pick.", color = g.textSecondary)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.size, key = { list[it].second }) { i ->
                    FocusableGlass(onClick = { onPick(list[i].second) }, radius = 14.dp, modifier = Modifier.fillMaxWidth(),
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
    LaunchedEffect(apps) { if (apps != null) { delay(150); runCatching { first.requestFocus() } } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Which app?", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.text.BasicTextField(
                query, { query = it.take(30) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(g.accent),
                modifier = Modifier.weight(1f).background(g.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner -> if (query.isEmpty()) Text("Find an app…", color = g.textTertiary); inner() },
            )
            FocusableGlass(onClick = onCancel, radius = 14.dp) {
                Text("Cancel · B", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        val list = shown
        when {
            list == null -> Text("Loading…", color = g.textTertiary)
            list.isEmpty() -> Text("No app matches “$query”.", color = g.textSecondary)
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(118.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.size, key = { list[it].pkg }) { i ->
                    val a = list[i]
                    FocusableGlass(onClick = { onPick(OpenTargets.app(a.pkg)) }, radius = 16.dp, modifier = Modifier.fillMaxWidth(),
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
    Box(Modifier.size(14.dp).background(
        if (armed) Glass.Danger.copy(alpha = a) else Color(0x80888888), androidx.compose.foundation.shape.CircleShape))
}
