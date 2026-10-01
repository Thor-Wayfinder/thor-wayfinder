package app.wayfinder

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.GlassSlider
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.ThorGlassTheme
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

    /** Lifecycle + saved state for Compose, and a Back dispatcher for the panel's BackHandlers. */
    private class PanelOwner : androidx.activity.OnBackPressedDispatcherOwner {
        val base = app.wayfinder.keyboard.ComposeOwner()
        override val lifecycle get() = base.lifecycle
        override val onBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher()
    }

    /** The window's root: B / Back go to the panel's Back handlers (edit mode…), else close it;
     *  a touch outside the side sheet closes it (as the activity's "finish on touch outside" did). */
    private class Root(ctx: Context, val back: () -> Unit, val outside: () -> Unit) : android.widget.FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
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
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
        val sheet = m.widthPixels / m.density > 700
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
                    ThorGlassTheme(dark = dark) { app.wayfinder.ui.CappedFontScale { PanelContent(close = { close() }) } }
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
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    PanelBody(first, close)
}

@Composable
private fun PanelBody(first: FocusRequester, close: () -> Unit) {
    GlassScreen(span = app.wayfinder.ui.AuroraSpan.BOTTOM) { Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Edit mode arranges the shortcut tiles in place (like a home screen); B / Done leaves it.
            var editing by remember { mutableStateOf(false) }
            androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
            PanelHeader(close, editing) { editing = !editing }
            if (editing) {
                CardsEditor()
                ShortcutGridEditor(columns = 4, cellHeight = 64.dp)
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
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Now playing: $title" + if (game != null) " · $label" else "", color = g.textPrimary,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(when {
                    ownText.isNotEmpty() -> "Its own: $ownText"
                    effText.isNotEmpty() -> "From $label: $effText"
                    else -> "Your usual performance, fan and refresh rate"
                }, color = g.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (PanelShortcuts.touched.isNotEmpty() && !kept) FocusableGlass(onClick = {
                val cr = ctx.contentResolver
                val perf = runCatching { Settings.System.getInt(cr, "performance_mode") }.getOrDefault(0)
                val fan = runCatching { Settings.System.getInt(cr, "fan_mode") }.getOrDefault(4)
                val peak = runCatching { Settings.System.getFloat(cr, "peak_refresh_rate") }.getOrDefault(60f)
                val k = game?.let { GameProfiles.create(pkg, it.game, it.title) } ?: pkg
                PerfProfiles.setBaselineIfMissing(ctx, QuickPanelWindow.startPerf, QuickPanelWindow.startFan,
                    QuickPanelWindow.startMin, QuickPanelWindow.startPeak)
                val t = PanelShortcuts.touched
                val bottomOff = ForegroundAppService.screenMode() == 1
                Profiles.update(k) { c -> c.copy(
                    perf = if ("perf" in t && Tuners.installed(ctx) == null) PerfMode.ofAyn(perf) else c.perf,
                    fan = if ("fan" in t) FanMode.values().firstOrNull { it.value == fan } else c.fan,
                    hz = if ("hz" in t) (if (peak > 90f) 120 else 60) else c.hz,
                    // 1.3 (GitHub #25): the bottom screen turned off here → off whenever this game is on top
                    second = if ("bottomoff" in t) (if (bottomOff) SecondScreenPolicy.BLANK else SecondScreenPolicy.DEFAULT) else c.second) }
                ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyPolicy()
                keptAt = PanelShortcuts.touchCount; PanelShortcuts.bottomKept()
            }, radius = 14.dp) {
                Text("Keep for $title", color = g.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            else if (ownText.isNotEmpty()) FocusableGlass(onClick = {
                Profiles.update(key) { it.copy(perf = null, fan = null, hz = null, second = SecondScreenPolicy.DEFAULT) }
                if (GameProfiles.isGame(key)) GameProfiles.removeIfPlain(key)
                ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyPolicy(); PanelShortcuts.touched.clear(); keptAt = -1
                PanelShortcuts.bottomKept()
            }, radius = 14.dp) {
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
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cards — what the panel shows, top to bottom", color = g.textPrimary, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                SmallButton("Back to the default") { PanelCards.reset(ctx) }
            }
            for ((id, shown) in PanelCards.cards.toList()) androidx.compose.runtime.key(id) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(PanelCards.NAMES[id] ?: id, color = if (shown) g.textPrimary else g.textTertiary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    SmallButton("Up") { PanelCards.move(ctx, id, -1) }
                    SmallButton("Down") { PanelCards.move(ctx, id, +1) }
                    SmallButton(if (shown) "Shown" else "Hidden", on = shown) { PanelCards.toggle(ctx, id) }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, on: Boolean = false, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = 12.dp) {
        Text(text, color = if (on) g.accent else g.textSecondary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Round 8 widget — the details the header has no room for: clocks, temperatures, battery time left. */
@Composable
private fun DetailsCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val stats = remember { HwStats(ctx) }
    var s by remember { mutableStateOf<HwSnapshot?>(null) }
    var hoursLeft by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            s = withContext(Dispatchers.IO) { stats.sample() }
            hoursLeft = withContext(Dispatchers.IO) {
                // energy left (Wh) = full charge (µAh) × voltage × level ÷ the power drawn now
                val snap = s ?: return@withContext null
                val full = runCatching { java.io.File("/sys/class/power_supply/battery/charge_full").readText().trim().toLong() }.getOrNull()
                val uv = runCatching { java.io.File("/sys/class/power_supply/battery/voltage_now").readText().trim().toLong() }.getOrNull()
                val w = snap.watts?.takeIf { it > 0.3f && !snap.charging }
                if (full == null || uv == null || w == null || snap.battery == null) null
                else (full / 1e6f) * (uv / 1e6f) * snap.battery / 100f / w
            }
            delay(2000)
        }
    }
    val v = s
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Readout("CPU", listOfNotNull(v?.cpuGhz?.let { "%.1f GHz".format(it) }, v?.cpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "))
            Readout("GPU", listOfNotNull(v?.gpuMhz?.let { "$it MHz" }, v?.gpuTemp?.let { "%.0f°".format(it) }).joinToString(" · "))
            Readout("Battery", listOfNotNull(v?.batteryTemp?.let { "%.0f°".format(it) },
                if (v?.charging == true) Charging.now(ctx).phrase ?: "charging" else hoursLeft?.let { h -> "about ${h.toInt()} h ${((h % 1) * 60).toInt()} min left" }).joinToString(" · "))
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
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(n?.title ?: "Nothing playing", color = if (n == null) g.textTertiary else g.textPrimary,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (n != null) Text(listOfNotNull(n.artist, appLabel).joinToString(" · "), color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // its own controls always work (media keys don't reach playback on another device)
            if (n != null) FocusableGlass(onClick = {
                ForegroundAppService.open(OpenTargets.app(n.app)); QuickPanelWindow.close()
            }, radius = 14.dp) {
                Text("Open", color = g.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            for ((code, icon, what) in listOf(
                Triple(KeyEvent.KEYCODE_MEDIA_PREVIOUS, Icons.Rounded.SkipPrevious, "Previous"),
                Triple(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, if (n?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause"),
                Triple(KeyEvent.KEYCODE_MEDIA_NEXT, Icons.Rounded.SkipNext, "Next"),
            )) FocusableGlass(onClick = { press(code) }, radius = 14.dp) {
                Icon(icon, what, tint = g.textPrimary, modifier = Modifier.padding(8.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun PanelHeader(close: () -> Unit, editing: Boolean, toggleEdit: () -> Unit) {
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
        GlassPanel(Modifier.weight(1f), radius = 14.dp) {
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
            val labelStyle = MaterialTheme.typography.labelSmall
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
        FocusableGlass(onClick = toggleEdit, radius = 14.dp) {
            if (editing) Text("Done", color = g.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            // a word, not a bare pencil: nobody knew what it did (2026-09-26)
            else Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Edit, null, tint = g.textSecondary, modifier = Modifier.size(20.dp))
                Text("Arrange", color = g.textSecondary, style = MaterialTheme.typography.labelLarge)
            }
        }
        FocusableGlass(onClick = {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close()
        }, radius = 14.dp) {
            Icon(Icons.Rounded.Settings, "Open Wayfinder", tint = g.accent, modifier = Modifier.padding(8.dp).size(22.dp))
        }
        FocusableGlass(onClick = close, radius = 14.dp) {
            Icon(Icons.Rounded.Close, "Close", tint = g.textSecondary, modifier = Modifier.padding(8.dp).size(22.dp))
        }
    }
}

@Composable
private fun Readout(label: String, value: String, scale: Float = 1f) {
    val g = LocalGlass.current
    val small = MaterialTheme.typography.labelSmall
    val large = MaterialTheme.typography.labelLarge
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = g.textTertiary, style = small.copy(fontSize = small.fontSize * scale), maxLines = 1, softWrap = false)
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
    // Brightness | Volume side by side, three sliders each (Both / Top / Bottom).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPanel(Modifier.weight(1f), radius = 18.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { PanelLevelTitle(Icons.Rounded.LightMode, "Brightness"); BrightnessSliders(compact = true) }
        }
        GlassPanel(Modifier.weight(1f), radius = 18.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { PanelLevelTitle(Icons.Rounded.VolumeUp, "Volume"); VolumeSliders(compact = true) }
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
    FocusableGlass(onClick = t.action, modifier = modifier.height(64.dp), radius = 16.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            TileIcon(t.icon, t.pkg, if (t.on) g.accent else g.textSecondary, 20.dp)
            // 1.4: a label too wide for the tile (large font: "Keyboard &…") shrinks to fit instead of being cut
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val m = androidx.compose.ui.text.rememberTextMeasurer()
                val base = MaterialTheme.typography.labelMedium
                val w = m.measure(t.label, base).size.width.toFloat()
                val room = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val scale = if (w > room && w > 0f) (room / w).coerceAtLeast(0.7f) else 1f
                Text(t.label, color = g.textPrimary, style = base.copy(fontSize = base.fontSize * scale, letterSpacing = base.letterSpacing * scale),
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (t.value != null) Text(t.value, color = if (t.on) g.accent else g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Global (not per-app) versions of AYN's quick settings, written the way AYN applies them. */
