package app.wayfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.TriggerGlyphs
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassNotice
import app.wayfinder.ui.NoticeKind
import app.wayfinder.ui.Radii
import kotlinx.coroutines.delay

/**
 * The welcome tour: first launch (and Help → "Take the tour" any time). Setup first, then
 * four steps the user DOES (hold Home, hold Back, Home + right stick, the AYN button), each ticked
 * when done — with the user's REAL combos, so it stays right after rebinding. The rest is told
 * when it's useful (first game, first keyboard: [ForegroundAppService], ThorKeyboardService).
 * Controller: A = the focused button (Next by default), B = previous step, ← / → move.
 */
object Tour {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
    fun done(ctx: Context) = prefs(ctx).getBoolean("tour_done", false)
    fun markDone(ctx: Context) = prefs(ctx).edit().putBoolean("tour_done", true).putInt("tour_step", 0).apply()
    /** Left mid-way (Home, Back, the app closed): the next launch goes on from that step — not done until finished or
     *  skipped (it was done at once: Home mid-tour lost it for good, 2026-10-03). Opened 3 times = done anyway,
     *  so a tour you keep leaving doesn't come back on every launch. */
    fun step(ctx: Context) = prefs(ctx).getInt("tour_step", 0)
    fun saveStep(ctx: Context, i: Int) { if (!done(ctx)) prefs(ctx).edit().putInt("tour_step", i).apply() }
    fun opened(ctx: Context) {
        if (done(ctx)) return
        val n = prefs(ctx).getInt("tour_opens", 0) + 1
        prefs(ctx).edit().putInt("tour_opens", n).apply()
        if (n >= 3) markDone(ctx)
    }
}

/**
 * While a "try it" step is on screen (and the tour is in front), the service TICKS combos instead
 * of carrying them out: practising "hold Back" would otherwise move the tour to the other screen,
 * and "Home + right stick" would send the controller away from it (critique 2026-09-25).
 */
object TourPractice {
    @Volatile var active = false
    /** The tour is the window in front (any step): a Home TAP doesn't leave it — the tour asks you to HOLD Home, and
     *  a press a bit short went home and lost the tour (2026-10-03). */
    @Volatile var inFront = false
    /** Build 18 (round 2): the step in front shows "Leave the tour" — a Home tap's pill names it only then (the first step
     *  may say "Skip to setup", the last has no way out but its end). */
    @Volatile var leaveShown = false
    /** The screen the tour is on (its hints go there). */
    @Volatile var display = 0
    val done = mutableStateListOf<ThorAction>()
    var sawHomeHint by mutableStateOf(false)
    /** The last combo pressed during practice, for "that was …" when it's not the one asked. */
    var last by mutableStateOf<ThorAction?>(null)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun intercept(a: ThorAction): Boolean {
        // Back keeps working (step 4 promises it; it's the tour's "previous step" too)
        if (!active || a == ThorAction.BACK) return false
        main.post { if (a !in done) done += a; last = a }
        // the quick panel opens for real: it's on the other screen (the tour stays), and a button that did nothing
        // was confusing (2026-10-03)
        return a !in CARRIED_OUT
    }
    /** Practised AND carried out (they don't move the tour or take the controller away for good). */
    val CARRIED_OUT = setOf(ThorAction.QUICK_MENU)
    fun sawHint() { if (active) main.post { sawHomeHint = true } }
    fun reset() { done.clear(); sawHomeHint = false; last = null }
}

/** Something to try: [text], shown with [trigger]'s buttons, ticked when [done]. */
private class Task(val text: String, val trigger: () -> Trigger?, val action: ThorAction?, val done: () -> Boolean)

private class TourStep(
    val icon: ImageVector, val title: String, val body: String,
    val combos: List<Pair<ThorAction, String>> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val extra: (@Composable () -> Unit)? = null,
    val footer: String? = null,
)

@Composable
fun TourPage(myDisplayId: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    // build 18 (review B21): the "another controller" step is in this tour or not from its start — following the controllers
    // live, a controller turned off on the last step ran the tour past its end (a crash), and one connected mid-tour
    // changed the step on screen
    val withPad = remember { ExtPadsCtl.byPlayer().isNotEmpty() }
    // 1.4: not remembered — the setup question removes the steps of parts turned off
    val steps = tourSteps(ctx, withPad)
    // a tour left mid-way goes on where it was; a replay (Help & status) starts at the beginning
    var i by remember { mutableIntStateOf(if (Tour.done(ctx)) 0 else Tour.step(ctx).coerceIn(0, steps.lastIndex)) }
    // build 18 (review B21): the step shown — [i] kept inside the list, whose length can still change under it
    val at = i.coerceIn(0, steps.lastIndex)
    val finish = { Tour.markDone(ctx); onDone() }
    LaunchedEffect(Unit) { Tour.opened(ctx) }
    LaunchedEffect(at) { Tour.saveStep(ctx, at) }
    app.wayfinder.ui.BackLayer(enabled = at > 0, onBack = { i = at - 1 })     // dev build 16 (WP7): a step back first
    val next = remember { FocusRequester() }
    // each step's scroll — a step taller than the screen (the bottom screen, big text): Next shows the rest first,
    // as the controller can't scroll plain text (critique 2026-10-03: the last setup line and combos were cut off)
    val scrolls = remember { HashMap<Int, ScrollState>() }
    fun scrollOf(k: Int) = scrolls.getOrPut(k) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 220.dp.toPx() }
    LaunchedEffect(at) { next.requestAfterLayout() }       // dev build 16 (WP7): after the step's layout, no 250 ms
    DisposableEffect(Unit) { TourFocus.toNext = { runCatching { next.requestFocus() } }; onDispose { TourFocus.toNext = null } }
    // Practice is on only while a "try it" step is in front (leaving with Home turns it off)
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true else if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); TourPractice.active = false; TourPractice.inFront = false; TourPractice.leaveShown = false }
    }
    // build 18 (B21, FTU-07): on the first step, with a setup check failing, the left button goes to the setup (by the step
    // that lists what it turns on — its agreement comes first) instead of ending the tour
    val toSetup = remember(at) { at == 0 && !setupAllOk(ctx) }
    LaunchedEffect(Unit) { TourPractice.reset() }
    LaunchedEffect(at) { TourPractice.last = null }
    // resumed is not enough on two screens (both can be resumed): the tour must have the focus, or
    // playing on the other screen would have every combo silently "ticked" (review 2026-09-25)
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    SideEffect {
        TourPractice.active = resumed && focused && steps[at].tasks.isNotEmpty()
        TourPractice.inFront = resumed && focused
        TourPractice.leaveShown = at in 1 until steps.lastIndex || (at == 0 && !toSetup)    // round 2: [TourNav]'s rule
        TourPractice.display = myDisplayId
    }
    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            // The card is centred in the space above; the buttons stay put at the bottom so
            // A-A-A never chases a moving Next button.
            Column(Modifier.widthIn(max = 980.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(at, label = "step", transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                    }) { s -> steps.getOrNull(s)?.let { TourCard(it, scrollOf(s)) } }    // a leaving step the list no longer has: nothing
                }
                val more = scrollOf(at).canScrollForward
                TourNav(at, steps.size, next, more, toSetup, back = { if (at > 0) i = at - 1 else finish() },
                    forward = {
                        if (more) scope.launch { scrollOf(at).animateScrollBy(stepPx) }
                        else if (at < steps.lastIndex) i = at + 1 else finish()
                    }, skip = finish, setup = { steps.indexOfFirst { it.title == ACCESS_STEP }.takeIf { it >= 0 }?.let { i = it } })
            }
        }
    }
}

@Composable
private fun TourCard(s: TourStep, scroll: ScrollState) {
    val g = LocalGlass.current
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Large, strong = true) {
        // compact: both of the Thor's screens are about 470 dp tall
        Column(Modifier.padding(horizontal = 26.dp, vertical = 20.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).background(g.accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, tint = g.accent, modifier = Modifier.size(24.dp))
                }
                Text(s.title, color = g.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            // dev build 15 (WP2): its button names are the hand's — PlayStation's marks drawn as shapes
            app.wayfinder.ui.GlyphText(s.body, color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
            s.extra?.invoke()
            s.tasks.forEach { TaskLine(it) }
            if (s.tasks.isNotEmpty()) {
                val last = TourPractice.last
                val allDone = s.tasks.all { it.done() }
                val off = !ForegroundAppService.isRunning
                val msg = when {
                    off -> "Wayfinder's service is off, so nothing can be ticked — go back to Quick setup and turn it on."
                    s.tasks.all { it.action in TourPractice.CARRIED_OUT } ->
                        // dev build 16 (theme F): the back button of the controller in hand
                        if (allDone) "That's it — the panel is on the bottom screen. The AYN button again (or ${Say.keyMenu(ThorButton.B)}) closes it; then Next."
                        else "The panel opens on the bottom screen; the tour stays here."
                    allDone -> "That's it — Next when you're ready."
                    last != null && last !in TourPractice.CARRIED_OUT && s.tasks.none { it.action == last } -> "That was “${last.title}” — not carried out while you practise."
                    else -> "While you practise here, Wayfinder only ticks it — nothing moves."
                }
                app.wayfinder.ui.GlyphText(msg, color = if (off) g.accent else if (allDone) g.positive else g.textTertiary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            s.combos.forEach { (a, what) -> ComboLine(a, what) }
            s.footer?.let { Text(it, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TaskLine(t: Task) {
    val g = LocalGlass.current
    val done = t.done()
    val trigger = t.trigger()
    Row(Modifier.fillMaxWidth().background(g.accent.copy(alpha = if (done) 0.05f else 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(Radii.Row))
        .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) g.positive else g.textTertiary, modifier = Modifier.size(28.dp))
        trigger?.let { TriggerGlyphs(it, 30.dp) }
        // build 18 (B21, FTU-05): a combo to try says itself in words after its badges ("Back · hold — Move / swap apps")
        app.wayfinder.ui.GlyphText(if (trigger != null && t.action != null) "${Say.of(trigger, app.wayfinder.ui.currentLook())} — ${t.text}" else t.text,
            color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ComboLine(a: ThorAction, what: String) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val t = ControlsStore.triggerFor(a)
        if (t != null) TriggerGlyphs(t, 30.dp) else Text("not set", color = g.textTertiary, style = MaterialTheme.typography.labelLarge)
        // build 18 (B21, FTU-05): and in words
        app.wayfinder.ui.GlyphText(if (t != null) "${Say.of(t, app.wayfinder.ui.currentLook())} — $what" else what,
            color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The tour's buttons. Build 18 (B21, MORE-D30): Next and Back show the hand's buttons for them (the first step's typed
 *  key line is gone); (MORE-D29) the dots centred on the buttons; (B13, MORE-14) "Leave the tour" (was a bare "Skip");
 *  (FTU-07) [toSetup]: the first step's left button goes to the setup instead of ending the tour. Round 2: one name for
 *  one way out — the first step's is "Leave the tour" too (was "Skip the tour"); [TourPractice.leaveShown] follows this. */
@Composable
private fun TourNav(i: Int, n: Int, next: FocusRequester, more: Boolean, toSetup: Boolean, back: () -> Unit, forward: () -> Unit,
                    skip: () -> Unit, setup: () -> Unit) {
    val g = LocalGlass.current
    // dev build 17 (WP8): the shared buttons
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (i == 0) GlassButton(if (toSetup) "Skip to setup" else "Leave the tour", if (toSetup) setup else back, kind = ButtonKind.QUIET)
        else GlassButton("‹ Back", back, kind = ButtonKind.QUIET, hint = ThorButton.B)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(n) { k ->
                Box(Modifier.padding(4.dp).size(if (k == i) 10.dp else 7.dp)
                    .background(if (k == i) g.accent else g.textTertiary.copy(alpha = 0.4f), CircleShape))
            }
        }
        if (i in 1 until n - 1) GlassButton("Leave the tour", skip, kind = ButtonKind.QUIET)
        GlassButton(if (more) "More ↓" else if (i == n - 1) "Start using Wayfinder" else "Next ›", forward, kind = ButtonKind.PRIMARY,
            hint = ThorButton.A, focusRequester = next)
    }
}

/** A setup line: ✓ when fine, otherwise a button that fixes it (or opens the right screen). Build 18 (B21, FTU-09):
 *  [optional] = not a problem when off (no warning mark); [afterTry] = what it says once its button was pressed and it still
 *  isn't fine (the system service's "Force SELinux" — not before anyone tried); (B20) [focus]: the first problem's button
 *  (Help & status opens on it). */
@Composable
private fun FixRow(ok: Boolean, okText: String, todo: String, button: String, optional: Boolean = false, afterTry: String? = null,
                   focus: FocusRequester? = null, fix: () -> Unit) {
    val g = LocalGlass.current
    // a button pressed here stays (as "Done ✓") once it worked: if it vanished, the controller's
    // focus fell to Next and the following presses skipped whole steps
    var pressed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else if (optional) Icons.Rounded.AddCircleOutline else Icons.Rounded.ErrorOutline, null,
            tint = if (ok) g.positive else if (optional) g.textTertiary else g.accent, modifier = Modifier.size(24.dp))
        Text(if (ok) okText else if (pressed && afterTry != null) afterTry else todo, color = g.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // QA2: A on a "Done ✓" goes on to Next (it did nothing — the controller looked stuck)
        if (!ok || pressed) FocusableGlass(onClick = { if (ok) TourFocus.toNext?.invoke() else { pressed = true; fix() } }, radius = Radii.Control,
            focusRequester = focus.takeIf { !ok }) {
            Text(if (ok) "Done ✓" else button, color = if (ok) g.positive else g.accent,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** Build 18 (B21, FTU-07 / FTU-09): every setup check that must pass — Wayfinder Keyboard is optional (the tour's first step
 *  offers "Skip to setup" while one fails; the setup step moves on by itself once they all pass). */
internal fun setupAllOk(ctx: Context): Boolean =
    ForegroundAppService.isRunning && Setup.notificationsAllowed(ctx) &&
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) &&
        !AynHomeGuard.isOn(ctx) && PServiceBridge.cachedAvailable() && layerOk()

/** The setup checks — the tour's Quick setup and Help & status's Status (build 18, B20 / FTU-18: one list, the tour's words).
 *  [onAllOk]: when the last one turns fine (the tour moves on to Next; Help passes nothing); [firstProblem]: the button of the
 *  first check that fails (Help opens on it). */
@Composable
internal fun SetupChecks(onAllOk: (() -> Unit)? = null, firstProblem: FocusRequester? = null) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // re-check after the user comes back
    @Suppress("UNUSED_EXPRESSION") tick
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    val g = LocalGlass.current
    // QA2: everything green → the controller goes to Next (it stayed on the last "Done ✓"). Build 18 (B21, FTU-09): the
    // keyboard isn't one of them (optional: your own keyboard stays)
    val allOk = setupAllOk(ctx)
    val onOk by androidx.compose.runtime.rememberUpdatedState(onAllOk)
    var wasOk by remember { mutableStateOf(allOk) }
    LaunchedEffect(allOk) { if (allOk && !wasOk) { delay(600); onOk?.invoke() }; wasOk = allOk }
    if (allOk) Text("All set — nothing to do here.", color = g.positive, style = MaterialTheme.typography.titleMedium)
    // the first must-pass check that fails, in the rows' order (the keyboard's place: never)
    val bad = listOf(ForegroundAppService.isRunning, Setup.notificationsAllowed(ctx), pm.isIgnoringBatteryOptimizations(ctx.packageName),
        !AynHomeGuard.isOn(ctx), true, PServiceBridge.cachedAvailable(), layerOk()).indexOfFirst { !it }
    fun focusOf(k: Int) = firstProblem.takeIf { bad == k }
    val rows: List<@Composable () -> Unit> = listOf({
        FixRow(ForegroundAppService.isRunning, "Wayfinder's service is on", "Wayfinder's service is off — it does all the work", "Turn on",
            focus = focusOf(0)) {
            Setup.enableService(ctx)
        } }, {
        FixRow(Setup.notificationsAllowed(ctx), "Its status notification is on",
            "Its status notification is off — a silent notification that keeps Wayfinder running", "Allow", focus = focusOf(1)) {
            (ctx as? android.app.Activity)?.let { Setup.allowNotifications(it) }
        } }, {
        FixRow(pm.isIgnoringBatteryOptimizations(ctx.packageName), "Android won't put it to sleep", "Android may stop it in the background", "Allow",
            focus = focusOf(2)) {
            Setup.allowBackground(ctx)
        } }, {
        FixRow(!AynHomeGuard.isOn(ctx), "Home combos work", "AYN's \"press Home twice\" blocks the Home combos", "Turn it off", focus = focusOf(3)) {
            AynHomeGuard.turnOff { tick++ }
        } }, {
        // build 18 (B21, FTU-09): optional — your own keyboard stays
        FixRow(keyboardState(ctx) == KeyboardState.ACTIVE, "Wayfinder Keyboard is ready",
            "Optional: Wayfinder Keyboard — type with the controller; your keyboard stays", "Use it", optional = true) {
            Setup.useKeyboard(ctx)
        } }, {
        // build 18 (B21, FTU-09): "Force SELinux" only once "Check again" didn't help (it was said before anything was tried)
        FixRow(PServiceBridge.cachedAvailable(), "Wayfinder can use the Thor's system service",
            "Wayfinder can't reach the Thor's system service yet", "Check again",
            afterTry = "Still not reachable — turn off “Force SELinux” in the Thor's settings, then restart the Thor", focus = focusOf(5)) {
            Thread { PServiceBridge.isAvailable() }.apply { isDaemon = true }.start() } }, {
        // 1.4: with "Only games I set up" it starts in those games — nothing to fix here. Build 18 (B21, FTU-02): and it says
        // so ("Game controls are on" read false on a game not set up yet)
        FixRow(layerOk(), when (PadLayerCtl.mode) {
                PadLayerCtl.Mode.NOWHERE -> "Game controls are off — your choice (${Nav.place(HubPage.FEATURES)})"
                PadLayerCtl.Mode.SET_UP -> "Game controls: in the games you set up"
                else -> "Game controls are on"
            },
            // dev build 17 (WP9): "Game controls" — the input layer is Help's word
            if (PadLayerCtl.wanted) (if (ForegroundAppService.isRunning) "Game controls are starting…" else "Game controls start once the service is on") else "Game controls are off — games' own buttons, gyro and macros won't work",
            "Turn on", focus = focusOf(6)) { PadLayerCtl.set(ctx, true) } })
    // two columns where there's room (the top screen): seven lines didn't fit one
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 680.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val half = (rows.size + 1) / 2
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { rows.take(half).forEach { it() } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { rows.drop(half).forEach { it() } }
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { rows.forEach { it() } }
    }
}

/** An agreement line: always near the top of its step, never where a scroll could hide it. */
@Composable
private fun Consent(text: String) {
    // dev build 17 (WP8): an info notice
    GlassNotice(text, NoticeKind.INFO, Modifier.fillMaxWidth(), icon = Icons.Rounded.VerifiedUser)   // build 18 (B21): was a gavel
}

/** What Wayfinder uses, one line each (it was one block of text). */
@Composable
private fun AccessRows() {
    val g = LocalGlass.current
    val rows = listOf(
        // one line each on the top screen: the agreement below must be seen without scrolling (2026-10-03)
        Triple(Icons.Rounded.AccessibilityNew, "Accessibility", "the app on each screen, the controller; a screenshot or tap only when you ask"),
        Triple(Icons.Rounded.Memory, "The Thor's system service", "moves apps, brightness, sleep, Game controls"),
        Triple(Icons.Rounded.Keyboard, "Its keyboard", "types into text fields; nothing you type is kept or sent"),
        Triple(Icons.Rounded.SportsEsports, "Game detection", "the game an emulator has open; Cocoon, RetroArch, GameNative lists"),
        Triple(Icons.Rounded.Public, "The internet", "only the Game guide (when you open it) and a daily update check (can be off)"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (icon, what, why) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp).padding(top = 2.dp))
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = g.textPrimary)); append(what); pop()
                    append(" — $why")
                }, color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The controllers connected now (other than the Thor's), for the "another controller" step. dev build 17 (theme G): under
 *  them, each one's Back and AYN button, in its own names. */
@Composable
private fun ConnectedPads() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    val pads = ExtPadsCtl.byPlayer()                         // dev build 16 (WP7): the player order, as everywhere
    val names = pads.map { it.name }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (names.isEmpty()) Icons.Rounded.BluetoothSearching else Icons.Rounded.CheckCircle, null,
            tint = if (names.isEmpty()) g.textTertiary else g.positive, modifier = Modifier.size(24.dp))
        Text(if (names.isEmpty()) "None connected right now — pair one in Android's Bluetooth settings whenever you like."
            else "Connected: ${names.joinToString(", ")} — ready.", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
    }
    for (p in pads) {
        val s = ExtPadsCtl.spares(ctx, p)
        val none = s.back == Say.Spare.None && s.ayn == Say.Spare.None
        app.wayfinder.ui.GlyphText("${p.name} — " + if (none) "no Back or AYN button yet: its page sets them up" else Say.spares(s.back, s.ayn, ExtPadsCtl.padLook(p.key)),
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 36.dp))
    }
}

/** The input layer is fine: on, waiting for a game you set up, or off because you chose that ("Just switching screens"). */
private fun layerOk() = PadLayerCtl.active || PadLayerCtl.mode == PadLayerCtl.Mode.SET_UP || PadLayerCtl.mode == PadLayerCtl.Mode.NOWHERE

private fun practice(a: ThorAction, text: String) =
    Task(text, { ControlsStore.triggerFor(a) }, a, { a in TourPractice.done })

/** QA2: a step's own content can hand the focus to the tour's Next button. */
private object TourFocus { var toNext: (() -> Unit)? = null }

/** Build 18 (B21, FTU-07): the step that lists what Wayfinder uses — the first step's "Skip to setup" goes there (its
 *  agreement comes before the setup's buttons). */
private const val ACCESS_STEP = "What Wayfinder uses"

/** The tour's steps. [withPad]: a controller was connected when the tour opened — the "another controller" step, else the last
 *  step's "pair it" line (one decision for both, kept for the whole tour: [TourPage]). */
private fun tourSteps(ctx: Context, withPad: Boolean) = listOfNotNull(
    TourStep(Icons.Rounded.AutoAwesome, "Welcome to Wayfinder",
        "Two screens, one controller. Wayfinder moves apps between the screens, sends the controller where you want it, " +
            "gives every game its own buttons and puts the Thor's settings a press away — all without leaving your game.\n\n" +
            // build 18 (B21, MORE-D30): the buttons show their keys now (Next, ‹ Back) — no typed key line
            "Everything works with the controller."),
    // 1.4: the first question
    TourStep(Icons.Rounded.Tune, "What do you want Wayfinder for?",
        "Wayfinder does a lot — you can keep it to the screen switcher. Nothing is lost: change it any time in ${Nav.place(HubPage.FEATURES)}.",
        extra = {
            var picked by remember { mutableStateOf(Features.asked) }
            // QA2: the controller starts on the choices (A on "Next" skipped the question); after a pick, on Next. dev build 16
            // (WP7): one frame after Next's own (the step's) — the 400 ms kept the A that showed this step from pressing
            // Next; a release whose press wasn't seen there no longer does
            val choice = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (!picked) { withFrameNanos { }; choice.requestAfterLayout() } }
            SetupChoice(focus = choice) { picked = true; TourFocus.toNext?.invoke() }
            // build 18 (B10): the same reading of the parts as the selected card's
            if (picked) Text(when (Features.preset()) {
                    Features.Preset.EVERYTHING -> "Everything — every part is on."
                    Features.Preset.JUST_SCREENS -> "Just switching screens — the rest stays off."
                    else -> "Your own mix — see ${Nav.place(HubPage.FEATURES)}."
                }, color = LocalGlass.current.positive, style = MaterialTheme.typography.bodyMedium)
        }),
    TourStep(Icons.Rounded.Shield, ACCESS_STEP,
        "Wayfinder needs more access than most apps. Nothing is collected or sent. What, and why:",
        // the agreement FIRST, where it's always on screen (at the bottom it was cut off and needed "More ↓")
        extra = { Consent("The next step turns these on — pressing its buttons means you agree."); AccessRows() }),
    TourStep(Icons.Rounded.Tune, "Quick setup", "A few things make everything work. Anything marked ! needs one press:",
        extra = { Consent("Pressing a button here means you agree to what the last step listed."); SetupChecks(onAllOk = { TourFocus.toNext?.invoke() }) }),
    // build 18 (B21, ps-F7): Home and Back in the names of the controller in hand ("Hold PS: your combos")
    TourStep(Icons.Rounded.Info, "${Say.hold(Say.btn(ThorButton.HOME), cap = true)}: your combos",
        "Everything Wayfinder does with the controller is ${Say.btn(ThorButton.HOME)} or ${Say.btn(ThorButton.BACK)} plus a button. " +
            "No need to learn them: ${Say.hold(Say.btn(ThorButton.HOME))} and they appear on the screen you're using. Let go to hide them. Try it now.",
        tasks = listOf(Task("${Say.hold(Say.btn(ThorButton.HOME), cap = true)} — the combos appear", { Trigger(ThorButton.HOME, Press.HOLD) }, null, { TourPractice.sawHomeHint })),
        footer = "After the tour, ${Say.btn(ThorButton.HOME)} pressed alone goes home as usual (here it stays on the tour), and " +
            "${Say.btn(ThorButton.BACK)} goes back."),
    TourStep(Icons.Rounded.SwapVert, "Move apps between the screens",
        "The app you're in goes to the other screen — or, with an app on each, they swap. Apps keep their place; nothing restarts. Try it.",
        tasks = listOf(practice(ThorAction.SWAP_OR_SEND, "Move / swap apps")),
        // round 2: the shoulders drawn too, in the hand's names (LB / RB on an Xbox pad — they were typed "L1 / R1")
        combos = listOf(ThorAction.RECENTS to "Recent apps (browse with the D-pad or ${Say.key(ThorButton.L1)} / ${Say.key(ThorButton.R1)}, " +
            "${Say.keyMenu(ThorButton.A)} opens, ${Say.keyMenu(ThorButton.Y)} closes)",
            ThorAction.CLEAR_BACKGROUND to "Close background apps")),
    // build 18 (B21, FTU-04): a title that's true whatever the controller mode. Round 2: the body too — in Follows touch
    // (the Controller page's first choice) a touch on the other screen takes them there
    TourStep(Icons.Rounded.SportsEsports, "Send the controller to a screen",
        "Your buttons work on one screen at a time. Send them to the top or bottom screen — " +
            (if (!AppSettings.focusLockEnabled && !AppSettings.focusSticky) "until you touch the other one"
                else "they stay there, even if you touch the other one") + ". Try both.",
        tasks = listOf(practice(ThorAction.FOCUS_SWITCH_DOWN, "Controller to the bottom screen"),
            practice(ThorAction.FOCUS_SWITCH_UP, "Controller to the top screen"))),
    // dev build 10: external controllers — always on, nothing to set up. Build 18 (B21, T18-07 /
    // FTU-06): only while one is connected (the Thor alone had a step about nothing — the last step says how to pair one);
    // (B13, MORE-13): who is player 1 now, when you changed the order; its page by its name
    controllerStep(ctx, withPad),
    // 1.4: no quick panel step when the AYN button stays AYN's ("Just switching screens")
    if (!AppSettings.aynButtonOurs) null else TourStep(Icons.Rounded.Dashboard, "The quick panel",
        "Brightness and volume, performance, fan, 60/120 Hz, temperatures and your shortcuts — one press away, on the bottom screen.",
        extra = {
            var ours by remember { mutableStateOf(AppSettings.aynButtonOurs) }
            if (!ours) FixRow(false, "", "The AYN button opens AYN's own drawer", "Use Wayfinder's") {
                AppSettings.setAynButtonOursOn(true); ours = true
            }
        },
        tasks = listOf(Task("Open the quick panel", { ControlsStore.triggerFor(ThorAction.QUICK_MENU) ?: Trigger(ThorButton.AYN, Press.TAP) }, ThorAction.QUICK_MENU,
            { ThorAction.QUICK_MENU in TourPractice.done })),
        footer = "Pick its shortcuts on the Quick panel page. The AYN button can go back to AYN's drawer on that page too."),
    TourStep(Icons.Rounded.CheckCircle, "More when you need it",
        "That's all you need to start. The rest shows up when it's useful — the first time you play a game, Wayfinder tells you how to open its buttons.",
        combos = listOf(ThorAction.GAME_CONTROLS to "Game controls: the game's own buttons, turbo, macros, gyro",
            ThorAction.KEYBOARD to "Keyboard & mouse, on the other screen",
            ThorAction.SCREENSHOT to "Screenshot").filter { Features.allows(it.first) },
        footer = "In Wayfinder: Games (each app's screen, buttons, performance), ${Nav.place(HubPage.PAIRS)}, sleep, speaker tuning, stick lights. " +
            "If a game ever acts strangely, ${Say.emergencyOff(ctx)} to turn Game controls off. " +
            // build 18 (B21): the controller step left out (none connected) — where one goes once paired
            (if (!withPad) "Another controller: pair it in Bluetooth settings — it shows on ${Nav.place(HubPage.CONTROLLER)}. " else "") +
            "Replay this tour from ${Nav.place(HubPage.HELP)}."),
)

/** Build 18 (B21 / B13): the "another controller" step — null in a tour opened with no controller connected ([withPad]). Player 1
 *  is said only when the player order puts another controller before the Thor. Review B21: the step stays when its controller
 *  goes away mid-tour (turned off, unplugged) — its lines say none is connected, its page is "your controller". */
private fun controllerStep(ctx: Context, withPad: Boolean): TourStep? {
    if (!withPad) return null
    val pads = ExtPadsCtl.byPlayer()
    val order = ExtPadsCtl.orderList(ctx)
    val first = order.firstOrNull()?.takeIf { order.indexOf("thor") > 0 }?.let { k -> pads.firstOrNull { it.key == k }?.name }
    return TourStep(Icons.Rounded.VideogameAsset, "Got another controller?",
        "Pair any Bluetooth or USB controller the usual way — Wayfinder sets it up: the same combos, its own face buttons. " +
            "By default the Thor is player 1 and a friend's controller is player 2" + (first?.let { " (now: $it is player 1)" } ?: "") + ".",
        extra = { ConnectedPads() },
        footer = "Its page: ${Nav.place(NavPages.CONTROLLER_PAGE, pads.firstOrNull()?.name)} (face buttons, set up its buttons, test it). " +
            "Player order: ${Say.path(NavCards.ORDER)}.")
}
