package app.wayfinder

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import android.view.KeyEvent
import android.util.Log
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.GlassSlider
import app.wayfinder.ui.GlassSwitchRow
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.requestAfterLayout
import app.wayfinder.ui.ThorGlassTheme
import app.wayfinder.ui.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*

/**
 * Wayfinder's quick panel — our replacement for AYN's drawer. Opens on the bottom screen from
 * the AYN button (App settings → Controller) or the "Quick menu" action — over a dual-screen game's
 * second screen too (1.3); a sheet on the right of the top screen only if the bottom one can't show it.
 *
 * 1.3 (GitHub #30, #32): an ACCESSIBILITY OVERLAY window, like AYN's own drawer — not an activity.
 * An activity paused the game under it on the same screen (WatermelonDS paused, its audio drifted),
 * and closing it resumed the app below, which some frontends (iiSU) answer by jumping to their home.
 * The window takes the controller (focusable) unless "The panel takes the controller" is off (#31):
 * then it's touch-only and the game keeps the controller. B / Back or the AYN button closes it;
 * the service then puts the controller (and its lock) back.
 */
object QuickPanelWindow {
    private const val TAG = "ThorPanel"
    @Volatile var displayId: Int? = null
        private set
    val isOpen get() = view != null
    var startPerf = 0; var startFan = 4; var startMin = 60f; var startPeak = 60f
    private var view: android.view.View? = null
    private var wm: android.view.WindowManager? = null
    private var owner: PanelOwner? = null
    private var closing = false
    private var shownAt = 0L

    /** Lifecycle + saved state for Compose, and a Back dispatcher for the panel's BackLayers (dev build 16: ui/BackLayer.kt). */
    private class PanelOwner : androidx.activity.OnBackPressedDispatcherOwner {
        val base = app.wayfinder.keyboard.ComposeOwner()
        override val lifecycle get() = base.lifecycle
        override val onBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher()
    }

    /** The window's root: B / Back go to the panel's Back handlers (edit mode…), else close it;
     *  a touch outside the side sheet closes it (as the activity's "finish on touch outside" did). */
    private class Root(ctx: Context, val back: () -> Unit, val outside: () -> Unit) : android.widget.FrameLayout(ctx) {
        // dev build 16 (WP7): the Hub's stick / D-pad adapter (latched per controller, touch mode left first) — Android's own
        // one-latch adapter skipped tiles with two controllers; and R3 / Start must not "click" a tile here either
        private val nav = app.wayfinder.ui.PadNav({ getChildAt(0) }, { dispatchKeyEvent(it) })
        private val padFallback = app.wayfinder.ui.PadFallbackFilter()
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
                return true
            }
            // dev build 14 (critic 2): a connected controller's MODE (its Home is the game's) — unhandled, Android makes it Home
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return true
            if (padFallback.shouldDrop(event)) return true   // R3/L3/Start must not "click"
            return super.dispatchKeyEvent(event)
        }
        override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean = nav.onMotion(ev) || super.dispatchGenericMotionEvent(ev)
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) nav.stop() }
        override fun onDetachedFromWindow() { nav.stop(); super.onDetachedFromWindow() }
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            // build 18 (E2); device pass (P2-3): at the touch's own time — the service's focus-probe tap is no finger
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) app.wayfinder.ui.InputMode.noteTouch(ev.eventTime)
            val out = ev.x < 0 || ev.y < 0 || ev.x > width || ev.y > height
            if (out) { if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) outside(); return true }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** Open the panel on [display] (main thread). False if the window couldn't be added. */
    fun show(service: android.accessibilityservice.AccessibilityService, display: Int): Boolean {
        if (view != null) return true
        val d = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(display) ?: return false
        val ctx = android.view.ContextThemeWrapper(service.createDisplayContext(d), R.style.Theme_Wayfinder_Glass)
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        AppSettings.init(ctx)
        // Every settings store the UI touches (the Hub may never have run since the app started).
        LinkedVolume.init(ctx); SpeakerTune.init(ctx); SleepSettings.init(ctx); Layouts.init(ctx)
        app.wayfinder.lights.LightSettings.init(ctx)
        AppConfigStore.init(ctx); ControlsStore.init(ctx)
        // what performance / fan / Hz were before any change here: the user's usual ones for
        // "Keep for <game>" (unless an override already recorded them)
        PanelShortcuts.startPanel(ForegroundAppService.panelApp())
        PanelCards.load(ctx)
        val cr = ctx.contentResolver
        startPerf = runCatching { Settings.System.getInt(cr, "performance_mode") }.getOrDefault(0)
        startFan = runCatching { Settings.System.getInt(cr, "fan_mode") }.getOrDefault(4)
        startMin = runCatching { Settings.System.getFloat(cr, "min_refresh_rate") }.getOrDefault(60f)
        startPeak = runCatching { Settings.System.getFloat(cr, "peak_refresh_rate") }.getOrDefault(60f)
        // Wide screen (the top one): a 540 dp sheet on the right, the game dimmed beside it.
        // Narrow screen (the bottom one): full screen, the app under it frosted (live blur).
        // 1.3.2: the display's REAL size — this context's cached metrics sometimes answered the TOP screen's
        // 1920 px for the bottom one: the panel then took its side-sheet form there (no blur, its own backdrop)
        // b18g (GitHub #49): the width from ScreenSize. On display 0, d carries the service's Resources, so its real
        // width was the process configuration's (the bottom screen's 537 dp with the bottom screen focused: the
        // full-screen form on the top). The density stays d's own (the display's, either way).
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
        val sheet = (ScreenSize.real(service, display)?.x ?: m.widthPixels) / m.density > 700
        val takes = AppSettings.panelTakesController
        val blur = !sheet && android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
        val owner = PanelOwner()
        val root = Root(ctx, back = {
            if (owner.onBackPressedDispatcher.hasEnabledCallbacks()) owner.onBackPressedDispatcher.onBackPressed() else close()
        }, outside = { if (takes && android.os.SystemClock.uptimeMillis() - shownAt > 800) close() })
        owner.base.attach(root)
        root.setViewTreeOnBackPressedDispatcherOwner(owner)
        val compose = androidx.compose.ui.platform.ComposeView(ctx).apply {
            setContent {
                val dark = when (AppSettings.themeMode) {
                    ThemeMode.DARK, ThemeMode.BLACK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    app.wayfinder.ui.LocalRealGlass provides (blur && !AppSettings.flatLook),
                    androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides owner,
                ) {
                    // dev build 16 (WP7): one Back path on the panel's dispatcher, its sheets, the hand's names (dev build 15, WP2)
                    ThorGlassTheme(dark = dark) { app.wayfinder.ui.CappedFontScale { app.wayfinder.ui.WindowRoot { PanelContent(close = { close() }) } } }
                }
            }
        }
        root.addView(compose)
        // removed by the system (the service unbound): forget it, or it would count as open forever
        root.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) { if (view === root && !closing) close() }
        })
        val W = android.view.WindowManager.LayoutParams.MATCH_PARENT
        val lp = android.view.WindowManager.LayoutParams(
            if (sheet) (540 * m.density).toInt() else W, W,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (takes) 0 else android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                // touch-only (#31): touches beside the sheet go to the game; else the sheet takes them (a tap
                // beside it closes it — and isn't also a tap in the game)
                (if (sheet && !takes) android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            if (sheet) {
                gravity = android.view.Gravity.END or android.view.Gravity.TOP
                // touch-only (#31): the game stays in view, undimmed — you keep playing it
                if (takes) { flags = flags or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND; dimAmount = 0.45f }
            }
            if (blur) {
                flags = flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = AppSettings.glassBlurPx()
            }
            title = "WayfinderQuickPanel"
        }
        return try {
            wm.addView(root, lp)
            view = root; this.wm = wm; this.owner = owner; displayId = display; shownAt = android.os.SystemClock.uptimeMillis()
            MainActivity.quickPanelShown(true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "panel on display $display failed: ${e.message}"); owner.base.destroy(); false
        }
    }

    /** Close it (any thread): the service gives the controller back. */
    fun close() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { close() }; return
        }
        val v = view ?: return
        if (closing) return
        closing = true
        try {
            runCatching { wm?.removeViewImmediate(v) }
            owner?.base?.destroy()
            view = null; wm = null; owner = null; displayId = null
            MainActivity.quickPanelShown(false)
            ForegroundAppService.quickPanelClosed()
        } finally { closing = false }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private enum class ScreenMode(val label: String) { BOTH("Both screens on"), TOP("Top screen only"), BOTTOM("Bottom screen only") }

@Composable
private fun PanelContent(close: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestAfterLayout() }       // dev build 16 (WP7): after the first layout, no 150 ms
    PanelBody(first, close)
}

@Composable
private fun PanelBody(first: FocusRequester, close: () -> Unit) {
    // build 18 (E2, pF12): where the header row ends (window px) — Arrange's floating description never covers it
    val headerBottom = remember { intArrayOf(-1) }
    val headerEnd = remember { { headerBottom[0].takeIf { it >= 0 } } }
    GlassScreen(span = app.wayfinder.ui.AuroraSpan.BOTTOM) { Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Edit mode arranges the shortcut tiles in place (like a home screen); B / Done leaves it.
            var editing by remember { mutableStateOf(false) }
            // build 18 (E2, pF09): leaving edit mode (B, Done) puts the focus back on Arrange — it was lost off screen
            val arrangeFocus = remember { FocusRequester() }
            val wasEditing = remember { booleanArrayOf(false) }
            LaunchedEffect(editing) {
                if (!editing && wasEditing[0]) arrangeFocus.requestAfterLayout()
                wasEditing[0] = editing
            }
            Box(Modifier.onGloballyPositioned { headerBottom[0] = (it.positionInWindow().y + it.size.height).toInt().coerceAtLeast(0) }) {
                PanelHeader(close, editing, arrangeFocus) { editing = !editing }
            }
            // dev build 16 (WP7): a layer — what opens inside it (the "Open…" picker, a lifted tile) closes first
            if (editing) app.wayfinder.ui.BackLayer(onBack = { editing = false }) {
                androidx.compose.runtime.CompositionLocalProvider(LocalHeaderBottom provides headerEnd) {
                    CardsEditor()
                    ShortcutGridEditor(columns = 4, cellHeight = 64.dp)
                }
            } else {
                // round 8: the cards the user picked, in their order (pencil → arrange)
                for ((id, shown) in PanelCards.cards) if (shown) when (id) {
                    "now" -> NowPlaying()
                    "screens" -> ScreenModeCard(first, close)
                    "levels" -> LevelsCard()
                    "details" -> DetailsCard()
                    "media" -> MediaCard()
                    "tiles" -> TilesGrid(close)
                }
            }
        }
        if (PanelShortcuts.pairsOpen) PanelPairsDialog(close) { PanelShortcuts.pairsOpen = false }
        // build 18 (GitHub #71): the Screenshot tile asks which screen
        if (PanelShortcuts.shotAskOpen) PanelShotSheet(close) { PanelShortcuts.shotAskOpen = false }
    } }
}

/**
 * Round 8 (2026-09-25): the app / game the panel is over and whether it has its own performance,
 * fan and refresh rate — and, once the user changed one of them here, "Keep for <game>": profiles
 * build themselves from normal use. A game gets a game profile (created as a copy of its app's).
 */
@Composable
private fun NowPlaying() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val v = AppConfigStore.version.intValue
    val pkg = remember { ForegroundAppService.panelApp() } ?: return
    val label = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
    val game = remember(v) { GameProfiles.runningIn(pkg) }
    val key = game?.let { GameProfiles.key(pkg, it.game) }?.takeIf { GameProfiles.get(it) != null } ?: pkg
    val own = remember(v, key) { Profiles.get(key) }
    val eff = remember(v, pkg) { GameProfiles.effective(pkg) }       // what's applied: the game's, else the app's
    val title = game?.title ?: label
    // what a tuner (ClusterTune / Pulse) owns isn't applied: not listed
    val tuner = remember { Tuners.installed(ctx) }
    val pulse = tuner == "Pulse"
    val ownText = listOfNotNull(own.perf?.takeIf { tuner == null }?.label, own.fan?.takeIf { !pulse }?.let { "fan ${it.label.lowercase()}" },
        own.hz?.takeIf { !pulse }?.let { "$it Hz" },
        "bottom off".takeIf { own.second == SecondScreenPolicy.BLANK }).joinToString(" · ")
    val effText = listOfNotNull(eff.perf?.takeIf { tuner == null }?.label, eff.fan?.takeIf { !pulse }?.let { "fan ${it.label.lowercase()}" },
        eff.hz?.takeIf { !pulse }?.let { "$it Hz" }).joinToString(" · ")
    var keptAt by remember { mutableStateOf(-1) }
    val kept = keptAt == PanelShortcuts.touchCount
    // build 18 (E3, pF07): "Keep for …" only for a change that differs from what the app had when the panel opened —
    // 60 → 120 → 60 Hz offered to keep 60 Hz. Root writes land a moment later: read again after each change
    var now by remember { mutableStateOf(Triple(QuickPanelWindow.startPerf, QuickPanelWindow.startFan, QuickPanelWindow.startPeak)) }
    LaunchedEffect(PanelShortcuts.touchCount) {
        if (PanelShortcuts.touched.isEmpty()) return@LaunchedEffect
        for (wait in listOf(450L, 700L, 1200L)) {
            delay(wait)
            now = withContext(Dispatchers.IO) {
                val cr = ctx.contentResolver
                Triple(runCatching { Settings.System.getInt(cr, "performance_mode") }.getOrDefault(QuickPanelWindow.startPerf),
                    runCatching { Settings.System.getInt(cr, "fan_mode") }.getOrDefault(QuickPanelWindow.startFan),
                    runCatching { Settings.System.getFloat(cr, "peak_refresh_rate") }.getOrDefault(QuickPanelWindow.startPeak))
            }
        }
    }
    val keep = PanelRules.keepable(PanelShortcuts.touched.toList(), now.first, now.second, now.third,
        QuickPanelWindow.startPerf, QuickPanelWindow.startFan, QuickPanelWindow.startPeak)
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                // build 18 (E10, V04 / FTU-10): the app's name alone — "Now playing: RetroArch" sat under the media card's
                // "Nothing playing" (the card is "This app's settings")
                Text(title + if (game != null) " · $label" else "", color = g.textPrimary,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(when {
                    ownText.isNotEmpty() -> "Its own: $ownText"
                    effText.isNotEmpty() -> "From $label: $effText"
                    else -> "Your usual performance, fan and refresh rate"
                }, color = g.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (keep.isNotEmpty() && !kept) FocusableGlass(onClick = {
                val cr = ctx.contentResolver
                val perf = runCatching { Settings.System.getInt(cr, "performance_mode") }.getOrDefault(0)
                val fan = runCatching { Settings.System.getInt(cr, "fan_mode") }.getOrDefault(4)
                val peak = runCatching { Settings.System.getFloat(cr, "peak_refresh_rate") }.getOrDefault(60f)
                val k = game?.let { GameProfiles.create(pkg, it.game, it.title) } ?: pkg
                PerfProfiles.setBaselineIfMissing(ctx, QuickPanelWindow.startPerf, QuickPanelWindow.startFan,
                    QuickPanelWindow.startMin, QuickPanelWindow.startPeak)
                val t = keep
                val bottomOff = ForegroundAppService.screenMode() == 1
                Profiles.update(k) { c -> c.copy(
                    perf = if ("perf" in t && Tuners.installed(ctx) == null) PerfMode.ofAyn(perf) else c.perf,
                    fan = if ("fan" in t) FanMode.values().firstOrNull { it.value == fan } else c.fan,
                    hz = if ("hz" in t) (if (peak > 90f) 120 else 60) else c.hz,
                    // 1.3 (GitHub #25): the bottom screen turned off here → off whenever this game is on top
                    second = if ("bottomoff" in t) (if (bottomOff) SecondScreenPolicy.BLANK else SecondScreenPolicy.DEFAULT) else c.second) }
                ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyPolicy()
                keptAt = PanelShortcuts.touchCount; PanelShortcuts.bottomKept()
            }, radius = Radii.Control) {
                Text("Keep for $title", color = g.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            else if (ownText.isNotEmpty()) FocusableGlass(onClick = {
                Profiles.update(key) { it.copy(perf = null, fan = null, hz = null, second = SecondScreenPolicy.DEFAULT) }
                if (GameProfiles.isGame(key)) GameProfiles.removeIfPlain(key)
                ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyPolicy(); PanelShortcuts.touched.clear(); keptAt = -1
                PanelShortcuts.bottomKept()
            }, radius = Radii.Control) {
                Text("Use my usual", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** Round 8 — edit mode: which cards show and in which order (words, not arrows). */
@Composable
private fun CardsEditor() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cards — what the panel shows, top to bottom", color = g.textPrimary, style = MaterialTheme.typography.titleSmall)
            val cards = PanelCards.cards.toList()
            for ((i, card) in cards.withIndex()) androidx.compose.runtime.key(card.first) {
                val (id, shown) = card
                CardRow(id, shown, first = i == 0, last = i == cards.lastIndex)
            }
            // build 18 (E2, pF10): two presses, and after the list — it was the first focus after Done, one press from a reset
            app.wayfinder.ui.ConfirmButton("Back to the default", "Press again to reset the cards", { PanelCards.reset(ctx) },
                size = ButtonSize.COMPACT)
        }
    }
}

/**
 * Build 18 (E10, V15): one card of the Cards editor — its switch (a "Shown / Hidden" button read as a state word), then Up and
 * Down. At an end the one that can't move it is dimmed and the D-pad skips it (it did nothing); a move onto an end first puts
 * the controller on the other button (the pressed one turns unfocusable under the press: the focus was lost).
 */
@Composable
private fun CardRow(id: String, shown: Boolean, first: Boolean, last: Boolean) {
    val ctx = LocalContext.current
    val upFocus = remember { FocusRequester() }
    val downFocus = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassSwitchRow(PanelCards.NAMES[id] ?: id, shown, { PanelCards.toggle(ctx, id) }, Modifier.weight(1f), compact = true)
        // dev build 17 (WP8): the shared compact buttons (was SmallButton)
        GlassButton("Up", {
            if (PanelCards.cards.indexOfFirst { it.first == id } == 1) runCatching { downFocus.requestFocus() }
            PanelCards.move(ctx, id, -1)
        }, Modifier.focusProperties { canFocus = !first }, kind = ButtonKind.QUIET, size = ButtonSize.COMPACT,
            focusRequester = upFocus, enabled = !first)
        GlassButton("Down", {
            if (PanelCards.cards.indexOfFirst { it.first == id } == PanelCards.cards.lastIndex - 1) runCatching { upFocus.requestFocus() }
            PanelCards.move(ctx, id, +1)
        }, Modifier.focusProperties { canFocus = !last }, kind = ButtonKind.QUIET, size = ButtonSize.COMPACT,
            focusRequester = downFocus, enabled = !last)
    }
}

/** Round 8 widget — the details the header has no room for: clocks, temperatures, battery time left. Build 18 (GitHub #89,
 *  #87): two rows of three — the power drawn, the fan and the memory too (the header drops its watts, then RAM, when
 *  short of room: #55). */
@Composable
private fun DetailsCard() {
    val ctx = LocalContext.current
    val stats = remember { HwStats(ctx) }
    var s by remember { mutableStateOf<HwSnapshot?>(null) }
    var plug by remember { mutableStateOf<Charging.Now?>(null) }
    var watts by remember { mutableStateOf<Float?>(null) }
    var hoursLeft by remember { mutableStateOf<Float?>(null) }
    var fanMode by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        // the Battery page's way (BatteryPage NowCard): what the Thor draws, averaged, and the energy left ÷ that draw
        val samples = ArrayDeque<Float>()
        while (true) {
            s = withContext(Dispatchers.IO) { stats.sample() }
            val now = withContext(Dispatchers.IO) { Charging.now(ctx) }
            if (now.plugged != plug?.plugged) samples.clear()     // plugged in / out: a new average, not the old one
            plug = now
            val w = withContext(Dispatchers.IO) { Charging.drawWatts(ctx) }
            if (w == null) samples.clear() else { samples.addLast(w); if (samples.size > 5) samples.removeFirst() }
            watts = samples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            val e = if (!now.plugged) withContext(Dispatchers.IO) { Charging.energyWh() } else null
            hoursLeft = watts?.let { ww -> e?.let { if (ww > 0.3f) it / ww else null } }
            fanMode = withContext(Dispatchers.IO) { runCatching { Settings.System.getInt(ctx.contentResolver, "fan_mode") }.getOrNull() }
            delay(2000)
        }
    }
    val v = s
    // the fan: its mode (as its tile names it) and how hard it's driven now — the mode alone if its PWM can't be read
    val fan = listOfNotNull(fanMode?.let { m -> FanMode.values().firstOrNull { it.value == m }?.label ?: "Custom" },
        v?.fanPct?.let { "$it %" }).joinToString(" · ")
    val battery = listOfNotNull(v?.batteryTemp?.let { "%.0f°".format(it) },
        plug?.takeIf { it.plugged }?.let { it.phrase ?: "charging" }
            ?: hoursLeft?.let { h -> "${h.toInt()} h ${((h % 1) * 60).toInt()} min left" }).joinToString(" · ")
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        ReadoutRows(listOf(
            listOf("CPU" to listOfNotNull(v?.cpuGhz?.let { "%.1f GHz".format(it) }, v?.cpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "),
                "GPU" to listOfNotNull(v?.gpuMhz?.let { "$it MHz" }, v?.gpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "),
                "Fan" to fan),
            listOf("Battery" to battery, "Power" to (watts?.let { "%.1f W".format(it) } ?: ""),
                "RAM" to (v?.ramUsedGb?.let { u -> "%.1f / %.0f GB".format(u, v.ramTotalGb ?: 0f) } ?: "")),
        ), widest = listOf(listOf("3.2 GHz · 99°", "680 MHz · 99°", "Custom · 100 %"), listOf("45° · 23 h 59 min left", "19.9 W", "15.9 / 16 GB")))
    }
}

/**
 * Build 18 (GitHub #89): rows of [Readout]s at one size — the size at which the widest row of [widest] (each readout's widest
 * value, so nothing jumps when a value changes) fits; with large text it shrinks, no smaller than the header's readable
 * floor (then a value clips).
 */
@Composable
private fun ReadoutRows(rows: List<List<Pair<String, String>>>, widest: List<List<String>>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium      // build 18 (E3, pF25): was labelSmall
    val valueStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        val gap = 10f   // breathing room between readouts, dp
        fun widthOf(label: String, value: String): Float = with(density) {
            maxOf(measurer.measure(label, labelStyle).size.width, measurer.measure(value, valueStyle).size.width).toDp().value
        }
        val fit = rows.indices.minOf { r ->
            val total = rows[r].indices.sumOf { i -> widthOf(rows[r][i].first, widest[r].getOrElse(i) { rows[r][i].second }).toDouble() }.toFloat()
            (maxWidth.value - gap * rows[r].size) / total
        }
        val scale = fit.coerceIn((0.72f / density.fontScale).coerceAtMost(0.72f), 1f)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (k, value) -> Readout(k, value, scale) }
            }
        }
    }
}

/** Round 8 widget — what's playing, with previous / play-pause / next (media keys: no permission). */
@Composable
private fun MediaCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var now by remember { mutableStateOf<MediaNow.Now?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        while (true) { now = withContext(Dispatchers.IO) { MediaNow.read() }; delay(2000) }
    }
    val n = now
    val appLabel = remember(n?.app) {
        n?.app?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
    }
    fun press(code: Int) { MediaNow.key(ctx, code); tick++ }
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(n?.title ?: "No music or video", color = if (n == null) g.textTertiary else g.textPrimary,   // build 18 (E10, V04)
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (n != null) Text(listOfNotNull(n.artist, appLabel).joinToString(" · "), color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // its own controls always work (media keys don't reach playback on another device)
            if (n != null) FocusableGlass(onClick = {
                ForegroundAppService.open(OpenTargets.app(n.app)); QuickPanelWindow.close()
            }, radius = Radii.Control) {
                Text("Open", color = g.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            // build 18 (E10, V17): nothing playing — only Play (it resumes the last player); Previous / Next did nothing and sat in
            // the D-pad's path
            val keys = if (n == null) listOf(Triple(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Icons.Rounded.PlayArrow, "Resume the last player"))
            else listOf(
                Triple(KeyEvent.KEYCODE_MEDIA_PREVIOUS, Icons.Rounded.SkipPrevious, "Previous"),
                Triple(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause"),
                Triple(KeyEvent.KEYCODE_MEDIA_NEXT, Icons.Rounded.SkipNext, "Next"),
            )
            for ((code, icon, what) in keys) FocusableGlass(onClick = { press(code) }, radius = Radii.Control) {
                Icon(icon, what, tint = g.textPrimary, modifier = Modifier.padding(8.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun PanelHeader(close: () -> Unit, editing: Boolean, arrangeFocus: FocusRequester, toggleEdit: () -> Unit) {
    // One row: the time, the live readouts, edit shortcuts, Wayfinder, close.
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val stats = remember { HwStats(ctx) }
    var v by remember { mutableStateOf<HwSnapshot?>(null) }
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            v = withContext(Dispatchers.IO) { stats.sample() }
            delay(1000)
        }
    }
    val s = v
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
        GlassPanel(Modifier.weight(1f), radius = Radii.Control) {
            // With the default text size (or bigger) and a 12-hour clock the four readouts overflowed —
            // "Battery" wrapped letter by letter or was cut off (1.1). Their real text is measured and
            // shrunk just enough to fit; only below a readable minimum does the last one (RAM) step aside.
            // 1.4 (GitHub #55): RAM showed for a second then left — the CPU's load only comes with the 2nd sample and
            // pushed it out. Widths are now measured on each readout's widest text from the first frame, and the
            // values get shorter (RAM used only, no battery watts) before RAM steps aside.
            fun readouts(short: Boolean) = listOf(
                "CPU" to listOfNotNull(s?.cpuLoad?.let { "$it %" }, s?.cpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "),
                "GPU" to listOfNotNull(s?.gpuLoad?.let { "$it %" }, s?.gpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "),
                "RAM" to (s?.ramUsedGb?.let { u -> if (short) "%.1f GB".format(u) else "%.1f/%.0f GB".format(u, s.ramTotalGb ?: 0f) } ?: ""),
                "Battery" to listOfNotNull(s?.battery?.let { "$it %" },
                    s?.watts?.takeIf { it > 0.05f && !short }?.let { (if (s.charging) "+" else "−") + "%.1f W".format(it) }).joinToString(" · "))
            // the widest each can get, so nothing jumps when a value arrives or grows
            fun widest(short: Boolean) = mapOf("CPU" to "100 % · 99°", "GPU" to "100 % · 99°",
                "RAM" to if (short) "15.9 GB" else "15.9/16 GB", "Battery" to if (short) "100 %" else "100 % · −19.9 W")
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val labelStyle = MaterialTheme.typography.labelMedium      // build 18 (E3, pF25): was labelSmall
            val valueStyle = MaterialTheme.typography.labelLarge
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                val gap = 10f   // breathing room between readouts, dp
                fun widthOf(label: String, value: String): Float = with(density) {
                    maxOf(measurer.measure(label, labelStyle).size.width, measurer.measure(value.ifEmpty { "—" }, valueStyle).size.width).toDp().value
                }
                // readable floor = 72 % of the DEFAULT size: users with large text can shrink further
                val minScale = (0.72f / density.fontScale).coerceAtMost(0.72f)
                var short = false
                var shown = readouts(false)
                var scale = 1f
                while (true) {
                    val w = widest(short)
                    val total = shown.sumOf { (k, _) -> widthOf(k, w.getValue(k)).toDouble() }.toFloat()
                    val fit = (maxWidth.value - gap * shown.size) / total
                    if (fit >= minScale || shown.size <= 2) { scale = fit.coerceIn(0.5f, 1f); break }
                    if (!short) { short = true; shown = readouts(true); continue }
                    shown = shown.filterNot { it.first == "RAM" }.takeIf { it.size < shown.size } ?: shown.dropLast(1)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    shown.forEach { (k, v) -> Readout(k, v, scale) }
                }
            }
        }
        FocusableGlass(onClick = toggleEdit, radius = Radii.Control, focusRequester = arrangeFocus) {
            if (editing) Text("Done", color = g.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            // a word, not a bare pencil: nobody knew what it did (2026-09-26)
            else Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Edit, null, tint = g.textSecondary, modifier = Modifier.size(20.dp))
                Text("Arrange", color = g.textSecondary, style = MaterialTheme.typography.labelLarge)
            }
        }
        // build 18 (E10, V20): it leaves the panel for Wayfinder — a compass in the text colour (a blue gear read as the panel's
        // own settings, which are Arrange's, and drew the eye)
        // device pass (d3 N2): the Hub's home, as the "Wayfinder" tile — it brought back whatever was open (Game controls)
        FocusableGlass(onClick = { PanelShortcuts.openWayfinder(ctx); close() }, radius = Radii.Control) {
            Icon(Icons.Rounded.Explore, "Open Wayfinder", tint = g.textSecondary, modifier = Modifier.padding(8.dp).size(22.dp))
        }
        FocusableGlass(onClick = close, radius = Radii.Control) {
            Icon(Icons.Rounded.Close, "Close", tint = g.textSecondary, modifier = Modifier.padding(8.dp).size(22.dp))
        }
    }
}

@Composable
private fun Readout(label: String, value: String, scale: Float = 1f) {
    val g = LocalGlass.current
    // build 18 (E3, pF25): the captions were tiny and faint (labelSmall, tertiary)
    val small = MaterialTheme.typography.labelMedium
    val large = MaterialTheme.typography.labelLarge
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = g.textSecondary, style = small.copy(fontSize = small.fontSize * scale), maxLines = 1, softWrap = false)
        Text(value.ifEmpty { "—" }, color = g.textPrimary, style = large.copy(fontSize = large.fontSize * scale), maxLines = 1,
            softWrap = false, overflow = TextOverflow.Clip)
    }
}

// ── screens ──────────────────────────────────────────────────────────────

@Composable
private fun ScreenModeCard(first: FocusRequester, close: () -> Unit) {
    var mode by remember { mutableStateOf(ForegroundAppService.screenMode()) }
    // 1.3: follows the real mode (a profile or "Use my usual" can change it while the panel is open)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(500); mode = ForegroundAppService.screenMode() }
    }
    GlassSegmentedControl(
        options = ScreenMode.values().map { it.label },
        selectedIndex = mode,
        modifier = Modifier.fillMaxWidth().focusRequesterSafe(first),
        collapse = false,
    ) { i ->
        mode = i
        PanelShortcuts.touchBottom()   // 1.3 (GitHub #25): "Keep for <game>" can remember it
        // "Top only" blanks THIS screen: close first, or the panel would sit invisible
        // under the black cover still holding the controller.
        if (i == 1) { close(); ForegroundAppService.later(300) { ForegroundAppService.setScreenMode(1) } }
        else ForegroundAppService.setScreenMode(i)
    }
}

private fun Modifier.focusRequesterSafe(r: FocusRequester) = this.focusRequester(r)

@Composable
private fun LevelsCard() {
    // Brightness | Volume side by side, three sliders each (Both / Top / Bottom). Build 18 (E10, V21): one height — one card
    // opened made them lopsided
    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPanel(Modifier.weight(1f).fillMaxHeight(), radius = Radii.Card) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { PanelLevelTitle(Icons.Rounded.LightMode, "Brightness"); BrightnessSliders(compact = true, sideExit = true) }
        }
        GlassPanel(Modifier.weight(1f).fillMaxHeight(), radius = Radii.Card) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { PanelLevelTitle(Icons.Rounded.VolumeUp, "Volume"); VolumeSliders(compact = true, sideExit = true) }
        }
    }
}

@Composable
private fun PanelLevelTitle(icon: ImageVector, title: String) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, null, tint = g.accent, modifier = Modifier.size(18.dp))
        Text(title, color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ── quick tiles (the user picks which, and their order: Controller → Quick panel) ──

@Composable
private fun TilesGrid(close: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }   // re-read values after a tap
    @Suppress("UNUSED_EXPRESSION") tick
    val after: (Long, () -> Unit) -> Unit = { ms, block -> close(); ForegroundAppService.later(ms, block) }
    val refresh: (Long) -> Unit = { ms -> ForegroundAppService.later(ms) { tick++ } }
    val all = PanelShortcuts.tiles(ctx, close, after, refresh)
    val chosen = PanelShortcuts.chosen(ctx).filter { PanelShortcuts.allowed(it) }.mapNotNull { all[it] }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chosen.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> TileView(t, Modifier.weight(1f)) }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (chosen.isEmpty()) Text("No shortcuts — press Arrange above to add some.", color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TileView(t: PanelTile, modifier: Modifier) {
    val g = LocalGlass.current
    // build 18 (E10, V05): an "on" tile is washed with the accent under its glass (its value was the only accent: blue words
    // barely read on the glass)
    FocusableGlass(onClick = t.action, modifier = modifier.height(64.dp), radius = Radii.Row,
        well = if (t.on) g.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Unspecified) {
        Column(Modifier.fillMaxSize().alpha(if (t.disabled) 0.5f else 1f).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            TileIcon(t.icon, t.pkg, if (t.on) g.accent else g.textSecondary, 20.dp)
            // QA2: a label too wide for the tile (large font: "Keyboard &…") shrinks to fit instead of being cut. Build 18 (E2,
            // pF11): the catalog's names are longer ("Close background apps") — a tile without a value line wraps them on two
            // lines first; one with a value keeps one line (the 64 dp tile has no room for three)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val m = androidx.compose.ui.text.rememberTextMeasurer()
                val base = MaterialTheme.typography.labelMedium
                val w = m.measure(t.label, base).size.width.toFloat()
                val room = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val lines = if (t.value == null && w > room) 2 else 1
                val scale = if (w > room * lines && w > 0f) (room * lines / w).coerceAtLeast(0.7f) else 1f
                Text(t.label, color = g.textPrimary, style = base.copy(fontSize = base.fontSize * scale, letterSpacing = base.letterSpacing * scale),
                    maxLines = lines, softWrap = lines > 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            // the value in the secondary text colour, semi-bold when on (the icon keeps the accent)
            if (t.value != null) Text(t.value, color = g.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                fontWeight = if (t.on) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
        }
    }
}

/** Global (not per-app) versions of AYN's quick settings, written the way AYN applies them. */
