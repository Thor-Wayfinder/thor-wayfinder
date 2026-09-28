package app.wayfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.TriggerGlyphs
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
    fun markDone(ctx: Context) = prefs(ctx).edit().putBoolean("tour_done", true).apply()
}

/**
 * While a "try it" step is on screen (and the tour is in front), the service TICKS combos instead
 * of carrying them out: practising "hold Back" would otherwise move the tour to the other screen,
 * and "Home + right stick" would send the controller away from it (critique 2026-09-25).
 */
object TourPractice {
    @Volatile var active = false
    val done = mutableStateListOf<ThorAction>()
    var sawHomeHint by mutableStateOf(false)
    /** The last combo pressed during practice, for "that was …" when it's not the one asked. */
    var last by mutableStateOf<ThorAction?>(null)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun intercept(a: ThorAction): Boolean {
        // Back keeps working (step 4 promises it; it's the tour's "previous step" too)
        if (!active || a == ThorAction.BACK) return false
        main.post { if (a !in done) done += a; last = a }
        return true
    }
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
    val steps = remember { tourSteps() }
    var i by remember { mutableIntStateOf(0) }
    val finish = { Tour.markDone(ctx); onDone() }
    // Seen = done: it opens on its own ONCE (leaving with Home/Back mid-way used to bring
    // it back on every launch). Help & status → Take the tour replays it.
    LaunchedEffect(Unit) { Tour.markDone(ctx) }
    BackHandler(enabled = i > 0) { i-- }
    val next = remember { FocusRequester() }
    LaunchedEffect(i) { delay(250); runCatching { next.requestFocus() } }
    // Practice is on only while a "try it" step is in front (leaving with Home turns it off)
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true else if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); TourPractice.active = false }
    }
    LaunchedEffect(Unit) { TourPractice.reset() }
    LaunchedEffect(i) { TourPractice.last = null }
    // resumed is not enough on two screens (both can be resumed): the tour must have the focus, or
    // playing on the other screen would have every combo silently "ticked" (review 2026-09-25)
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    SideEffect { TourPractice.active = resumed && focused && steps[i].tasks.isNotEmpty() }
    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            // The card is centred in the space above; the buttons stay put at the bottom so
            // A-A-A never chases a moving Next button.
            Column(Modifier.widthIn(max = 980.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(i, label = "step", transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                    }) { s -> TourCard(steps[s]) }
                }
                TourNav(i, steps.size, next, back = { if (i > 0) i-- else finish() },
                    forward = { if (i < steps.lastIndex) i++ else finish() }, skip = finish)
            }
        }
    }
}

@Composable
private fun TourCard(s: TourStep) {
    val g = LocalGlass.current
    GlassPanel(Modifier.fillMaxWidth(), radius = 28.dp, strong = true) {
        Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).background(g.accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, tint = g.accent, modifier = Modifier.size(28.dp))
                }
                Text(s.title, color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
            }
            Text(s.body, color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
            s.extra?.invoke()
            s.tasks.forEach { TaskLine(it) }
            if (s.tasks.isNotEmpty()) {
                val last = TourPractice.last
                val allDone = s.tasks.all { it.done() }
                val msg = when {
                    allDone -> "That's it — Next when you're ready."
                    last != null && s.tasks.none { it.action == last } -> "That was “${last.title}” — not carried out while you practise."
                    else -> "While you practise here, Wayfinder only ticks it — nothing moves."
                }
                Text(msg, color = if (allDone) app.wayfinder.ui.Glass.Positive else g.textTertiary, style = MaterialTheme.typography.bodyMedium)
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
    Row(Modifier.fillMaxWidth().background(g.accent.copy(alpha = if (done) 0.05f else 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) app.wayfinder.ui.Glass.Positive else g.textTertiary, modifier = Modifier.size(28.dp))
        t.trigger()?.let { TriggerGlyphs(it, 30.dp) }
        Text(t.text, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ComboLine(a: ThorAction, what: String) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val t = ControlsStore.triggerFor(a)
        if (t != null) TriggerGlyphs(t, 30.dp) else Text("not set", color = g.textTertiary, style = MaterialTheme.typography.labelLarge)
        Text(what, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TourNav(i: Int, n: Int, next: FocusRequester, back: () -> Unit, forward: () -> Unit, skip: () -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FocusableGlass(onClick = back, radius = 16.dp) {
            Text(if (i == 0) "Skip the tour" else "‹ Back", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            repeat(n) { k ->
                Box(Modifier.padding(4.dp).size(if (k == i) 10.dp else 7.dp)
                    .background(if (k == i) g.accent else g.textTertiary.copy(alpha = 0.4f), CircleShape))
            }
        }
        if (i in 1 until n - 1) FocusableGlass(onClick = skip, radius = 16.dp) {
            Text("Skip", color = g.textTertiary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
        FocusableGlass(onClick = forward, radius = 16.dp, focusRequester = next) {
            Text(if (i == n - 1) "Start using Wayfinder" else "Next ›", color = g.accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        }
    }
}

/** A setup line: ✓ when fine, otherwise a button that fixes it (or opens the right screen). */
@Composable
private fun FixRow(ok: Boolean, okText: String, todo: String, button: String, fix: () -> Unit) {
    val g = LocalGlass.current
    // a button pressed here stays (as "Done ✓") once it worked: if it vanished, the controller's
    // focus fell to Next and the following presses skipped whole steps
    var pressed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
            tint = if (ok) app.wayfinder.ui.Glass.Positive else g.accent, modifier = Modifier.size(24.dp))
        Text(if (ok) okText else todo, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok || pressed) FocusableGlass(onClick = { pressed = true; if (!ok) fix() }, radius = 14.dp) {
            Text(if (ok) "Done ✓" else button, color = if (ok) app.wayfinder.ui.Glass.Positive else g.accent,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun SetupChecks() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // re-check after the user comes back
    @Suppress("UNUSED_EXPRESSION") tick
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FixRow(ForegroundAppService.isRunning, "Wayfinder's service is on", "Wayfinder's service is off — it does all the work", "Turn on") {
            Setup.enableService(ctx)
        }
        FixRow(Setup.notificationsAllowed(ctx), "Its status notification is on",
            "Its status notification is off — a silent notification that keeps Wayfinder running", "Allow") {
            (ctx as? android.app.Activity)?.let { Setup.allowNotifications(it) }
        }
        FixRow(pm.isIgnoringBatteryOptimizations(ctx.packageName), "Android won't put it to sleep", "Android may stop it in the background", "Allow") {
            Setup.allowBackground(ctx)
        }
        FixRow(!AynHomeGuard.isOn(ctx), "Home combos work", "AYN's \"press Home twice\" blocks the Home combos", "Turn it off") {
            AynHomeGuard.turnOff { tick++ }
        }
        FixRow(keyboardState(ctx) == KeyboardState.ACTIVE, "Wayfinder Keyboard is ready", "Wayfinder Keyboard isn't the active keyboard", "Set up") {
            Setup.useKeyboard(ctx)
        }
        FixRow(PServiceBridge.cachedAvailable(), "Wayfinder can use the Thor's system service",
            "Wayfinder can't reach the Thor's system service yet — if this stays, turn off “Force SELinux” in the Thor's settings and restart",
            "Check again") { Thread { PServiceBridge.isAvailable() }.apply { isDaemon = true }.start() }
        FixRow(PadLayerCtl.active, "Game controls are on (the input layer)",
            if (PadLayerCtl.wanted) (if (ForegroundAppService.isRunning) "The input layer is starting…" else "The input layer starts once the service is on") else "The input layer is off — games' own buttons, gyro and macros won't work",
            "Turn on") { PadLayerCtl.set(ctx, true) }
    }
}

private fun practice(a: ThorAction, text: String) =
    Task(text, { ControlsStore.triggerFor(a) }, a, { a in TourPractice.done })

private fun tourSteps() = listOf(
    TourStep(Icons.Rounded.AutoAwesome, "Welcome to Wayfinder",
        "Two screens, one controller. Wayfinder moves apps between the screens, sends the controller where you want it, " +
            "gives every game its own buttons and puts the Thor's settings a press away — all without leaving your game.\n\n" +
            "A = next · B = back. Everything works with the controller."),
    TourStep(Icons.Rounded.Shield, "What Wayfinder uses",
        "Wayfinder needs more access than most apps. What, and why:\n" +
            "•  Accessibility — sees which app is on each screen and reads the controller; a screenshot or a tap only when you ask.\n" +
            "•  The Thor's system service — moves apps, sets brightness and sleep, runs the input layer (each game's own buttons).\n" +
            "•  Its keyboard — types into text fields. Nothing you type is stored or sent.\n" +
            "•  Game detection — the game file an emulator has open, and Cocoon, RetroArch and GameNative's game lists.\n" +
            "Nothing is collected or sent. Only the Game guide page uses the internet: a web search for your game, when you open it.\n" +
            "The next step turns these on — pressing its buttons means you agree."),
    TourStep(Icons.Rounded.Tune, "Quick setup", "A few things make everything work. Anything marked ! needs one press:", extra = { SetupChecks() }),
    TourStep(Icons.Rounded.Info, "Hold Home: your combos",
        "Everything Wayfinder does with the controller is Home or Back plus a button. No need to learn them: hold Home and " +
            "they appear on the screen you're using. Let go to hide them. Try it now.",
        tasks = listOf(Task("Hold Home — the combos appear", { Trigger(ThorButton.HOME, Press.HOLD) }, null, { TourPractice.sawHomeHint })),
        footer = "Home pressed alone still goes home, and Back still goes back."),
    TourStep(Icons.Rounded.SwapVert, "Move apps between the screens",
        "The app you're in goes to the other screen — or, with an app on each, they swap. Apps keep their place; nothing restarts. Try it.",
        tasks = listOf(practice(ThorAction.SWAP_OR_SEND, "Move / swap apps")),
        combos = listOf(ThorAction.RECENTS to "Recent apps (browse with the D-pad or L1 / R1, A opens, Y closes)",
            ThorAction.CLEAR_BACKGROUND to "Close background apps")),
    TourStep(Icons.Rounded.SportsEsports, "The controller follows you",
        "Send the controller to the top or bottom screen. It stays there — touching the other screen won't steal it " +
            "(the Controller page has the other ways). Try both.",
        tasks = listOf(practice(ThorAction.FOCUS_SWITCH_DOWN, "Controller to the bottom screen"),
            practice(ThorAction.FOCUS_SWITCH_UP, "Controller to the top screen"))),
    TourStep(Icons.Rounded.Dashboard, "The quick panel",
        "Brightness and volume, performance, fan, 60/120 Hz, temperatures and your shortcuts — one press away, on the bottom screen.",
        extra = {
            var ours by remember { mutableStateOf(AppSettings.aynButtonOurs) }
            if (!ours) FixRow(false, "", "The AYN button opens AYN's own drawer", "Use Wayfinder's") {
                AppSettings.setAynButtonOursOn(true); ours = true
            }
        },
        tasks = listOf(Task("Press the AYN button", { ControlsStore.triggerFor(ThorAction.QUICK_MENU) }, ThorAction.QUICK_MENU,
            { ThorAction.QUICK_MENU in TourPractice.done })),
        footer = "Pick its shortcuts on the Quick panel page. The AYN button can go back to AYN's drawer on that page too."),
    TourStep(Icons.Rounded.CheckCircle, "More when you need it",
        "That's all you need to start. The rest shows up when it's useful — the first time you play a game, Wayfinder tells you how to open its buttons.",
        combos = listOf(ThorAction.GAME_CONTROLS to "Game controls: the game's own buttons, turbo, macros, gyro",
            ThorAction.KEYBOARD to "Keyboard & mouse, on the other screen",
            ThorAction.SCREENSHOT to "Screenshot"),
        footer = "In Wayfinder: App profiles (each app's screen, buttons, performance), App pairs, sleep, speaker tuning, stick lights. " +
            "If a game ever acts strangely, hold Home + Back for 5 seconds to turn the input layer off. " +
            "Replay this tour from Help & status."),
)
