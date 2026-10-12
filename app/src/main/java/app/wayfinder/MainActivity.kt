package app.wayfinder

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import rikka.shizuku.Shizuku
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DarkMode
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import app.wayfinder.ui.Glass
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.StatusPill
import app.wayfinder.ui.ThorGlassTheme
import app.wayfinder.ui.VSpace
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.d("ThorMain", "Shizuku permission result: code=$requestCode granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windows += this          // b18j (D1): the live windows ([sheetsOffHere])
        ButtonNames.init(this)   // 1.3 (GitHub #27)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // First run: asked in the tour's setup step, with the reason. After the tour: granted
        // quietly through the system service, never Android's prompt at start — it came with no
        // context and the controller can't press its buttons (release test 2026-09-24). Without
        // the system service, the tour's setup step (Help & status → tour) offers it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Tour.done(this) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) Thread {
            if (PServiceBridge.isAvailable()) PServiceBridge.exec("pm grant $packageName android.permission.POST_NOTIFICATIONS; echo OK")
        }.apply { isDaemon = true }.start()

        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(0)
        } catch (e: Exception) { Log.d("ThorMain", "Shizuku not available: ${e.message}") }

        PServiceBridge.probeAsync()
        RootHelper.probeAsync()
        // 1.4: on in Android's settings but not running (stopped by Android) → looked at again in 15 s, revived
        if (ServiceWatch.stuck(this)) ServiceWatch.schedule(applicationContext, 15_000)

        // Volume keys here = the media volume (else Android picks the ring volume when nothing plays).
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        AppSettings.init(this)
        // Every settings store the UI touches: the service may not be running yet (a fresh
        // install, or the user turned it off) — opening a page must never depend on it.
        LinkedVolume.init(this); SpeakerTune.init(this); SleepSettings.init(this); Layouts.init(this)
        app.wayfinder.lights.LightSettings.init(this)
        AppConfigStore.init(this)
        // also here: the controls page can be used while the service is off (game profiles and the
        // gyro preview would otherwise work in memory only)
        GameProfiles.init(this); GyroEngine.init(this)
        ControlsStore.init(this)
        // b18i (D1): Game controls opened beside the game, on the screen the service chose (a restored window keeps its own)
        askedScreen = if (savedInstanceState == null) screenAsked(intent) else savedInstanceState.getInt(KEY_SCREEN, -1).takeIf { it >= 0 }
        // Opened on the wrong screen (e.g. from the bottom screen's launcher)?
        // Relaunch on the chosen one before drawing anything.
        if (relocateIfNeeded()) return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // b18g (WI-5, g1 BUG-1): a new window opened on Game controls (Home + X, the tile) IS Game controls before onResume
        // tells the service "the Hub is shown" — as becomes() makes a reused window. Marked only by its page's composition
        // (after onResume), it was taken for the Hub: the service's hubOn stayed on the game's screen once it closed (its
        // stop sends no hubHidden), and the frame-rate counter never came back over the game. A restored window
        // (savedInstanceState) shows its saved page: its composition says what it is, as before.
        val first = if (savedInstanceState == null) firstPage(intent) else null
        if (first?.startsWith("play:") == true) gameControls = this

        @Suppress("DEPRECATION")
        val myDisplayId = windowManager.defaultDisplay.displayId
        setContent {
            val dark = when (AppSettings.themeMode) {
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalRealGlass provides (realGlass.value && !AppSettings.flatLook)) {
            ThorGlassTheme(dark = dark) {
            // build 18 (E6, D1): the status bar's icons follow the theme — over a light app the light theme's clock and battery
            // were white on white. The page's own colours decide (Your own colours on a light background: dark icons)
            val lightBars = !app.wayfinder.ui.LocalGlass.current.dark
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = lightBars
            }
            // dev build 16 (WP7): one Back path, the sheets, the focus memory and the hand's names (ui/WindowRoot.kt)
            app.wayfinder.ui.WindowRoot {
                // Deep link: `page` extra opens a sub-page directly (e.g. "controls", "apps"),
                // on first launch or when the Hub is already open (onNewIntent).
                // First launch → the welcome tour.
                // device pass (d3 N2): the Hub's home asked for (the panel's compass, the "Wayfinder" tile now name it) is the
                // tour while the first-run tour isn't done, as a start with no page
                // b18g (WI-5): the page onCreate read (once: a card's target also sets where the focus lands)
                var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(first ?: firstPage(intent)) }
                // device pass (final): the page on screen, for a page asked later (onNewIntent: the tour stays for the home).
                // b18i (leftovers, WI-5's gap): what the window is follows that page — a page the window goes to by itself
                // (Game controls' "All apps", B back to the game's page, a restored window) goes through becomes(), as a page
                // an intent asks for does. On a change only: a recomposition before an intent's page shows asks nothing
                androidx.compose.runtime.SideEffect { if (page != shownPage) { shownPage = page; becomes(page); cardShown(page) } }
                // dev build 16 (WP6): the controller that opened Game controls (pad=<key>) — its tab; kept with the page
                var pad by androidx.compose.runtime.saveable.rememberSaveable {
                    mutableStateOf(intent?.getStringExtra("pad")?.takeIf { GameTabRules.padKeyOk(it) })
                }
                LaunchedEffect(deepLink.value) { deepLink.value?.let { (p, k) -> pad = k; page = p; deepLink.value = null } }
                // B / Back goes up one level; the Hub home itself leaves the app as usual. dev build 16 (WP7): the window's
                // root layer — a list, a popup or a sheet open in the page is deeper, and closes first
                app.wayfinder.ui.BackLayer(enabled = page != HubPage.HOME, onBack = {
                    // Game controls (opened from a game): Back returns to the game
                    if (page.startsWith("play:")) { finish(); ForegroundAppService.gameControlsClosed() }
                    else page = hubBack(page)
                }) {
                    // build 18 (E5, pF04): Keyboard & mouse opened over Wayfinder — B closes it and the Hub stays (B left the
                    // app with the deck still "Sending to: Wayfinder"). Deeper than the page, shallower than its lists and sheets.
                    // Review (E5): never in Game controls — it belongs to the game under it, and B goes back to that game
                    app.wayfinder.ui.BackLayer(enabled = app.wayfinder.deck.InputDeck.shown.value && !page.startsWith("play:") &&
                        app.wayfinder.deck.InputDeck.targetPkg == packageName, onBack = { app.wayfinder.deck.InputDeck.hide() }) {
                        HubNavHost(myDisplayId, page, pad) { page = it }
                    }
                }
            }
            }
            }
        }
        // build 18 (E1, MORE-02): Hints › "Combos on the other screen" acts at once — off, the sheet stayed until the Hub was
        // next shown; on, it came back only then
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                androidx.compose.runtime.snapshotFlow { AppSettings.controlsSheet }.collect { on ->
                    if (isFinishing) return@collect
                    if (on) showCompanion() else { companion?.dismiss(); companion = null }
                }
            }
        }
        // b18n (device b18j B1): a window that lands on another screen lays out there — no Wayfinder sheet stays over it ([landed])
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(landed)
        // After setContent: the window's decor view must exist before blur is applied.
        applyBackdrop()
        if (Build.VERSION.SDK_INT >= 31)
            windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
    }

    // ── Real glass: blur whatever is behind the (translucent) Hub window ──────
    /** True while the system blurs the real background behind us (see LocalRealGlass). */
    val realGlass = mutableStateOf(false)
    private val blurListener = java.util.function.Consumer<Boolean> { applyBackdrop() }

    /** Apply the Appearance → Backdrop choice; falls back to the aurora when the system disables blur (e.g. battery saver). */
    fun applyBackdrop() {
        val on = AppSettings.backdrop == Backdrop.WALLPAPER &&
            Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (on) AppSettings.glassBlurPx() else 0)
        realGlass.value = on
        companion?.applyBackdrop(on)
    }

    /** A page (and the controller that asked for it: Game controls' pad=) from a later intent. */
    private val deepLink = mutableStateOf<Pair<String, String?>?>(null)

    private val padFallback = app.wayfinder.ui.PadFallbackFilter()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD)) app.wayfinder.ui.InputMode.notePadKey()   // build 18 (A0)
        if (PadTest.onKey(event)) return true               // the controller test page reads every button
        // dev build 14 (critic 2): a connected controller whose Home is the game's sends MODE — left unhandled, Android
        // turns it into Home and Wayfinder's own page went home
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return true
        if (padFallback.shouldDrop(event)) return true   // R3/L3/Start must not "click"
        return super.dispatchKeyEvent(event)
    }

    /** Build 18 (A0): a touch — the page legend hides until the next controller key (ui.InputMode). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // device pass (P2-3): at its own time — Wayfinder's focus-probe tap (InputMode.noteSyntheticTouch) is no finger
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) app.wayfinder.ui.InputMode.noteTouch(ev.eventTime)
        return super.dispatchTouchEvent(ev)
    }

    /** Device pass (final): the page this window shows (its composition writes it) — null before it draws. */
    private var shownPage: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // b18i (D1): where this window is asked to be — Game controls beside the game, else the Hub's screen (onResume relocates)
        askedScreen = screenAsked(intent)
        // b18i (review): a window opened as Game controls beside the game relaunches what it was asked last — a Hub page too (its
        // first intent is that Game controls). b18j (P3): every window does — a relocation relaunched a plain Hub window with its
        // FIRST intent, so a later page (`--es page controller`) was lost to it. One that asks nothing (the window brought forward
        // as it is: Recents' B back to the Hub) leaves what was asked before
        if (intent.getStringExtra("page") != null || intent.getStringExtra("card") != null) relaunchWith = intent
        cardPage(intent)?.let { becomes(it); deepLink.value = it to null; return }
        HubPage.safe(intent.getStringExtra("page"))?.let {
            becomes(it)
            // device pass (final): the Hub's home asked while the tour is shown (its quick panel step's compass) keeps the tour —
            // the window comes forward, as before the compass named the home (d3 N2)
            val show = HubPage.toShow(it, shownPage, Tour.done(this)) ?: return
            if (show == HubPage.TOUR && shownPage == HubPage.TOUR) return
            deepLink.value = show to intent.getStringExtra("pad")?.takeIf { k -> GameTabRules.padKeyOk(k) }
            // b18i (D1): a window already in front made Game controls may get no new onResume (Android delivers the intent to
            // a resumed window as it is): the service hears it is up all the same — its controller hand-off waits for that
            if (resumed === this && gameControls === this) announceUp()
        }
    }

    /** The service hears this window is up on its screen (onResume, or [onNewIntent] while in front): the Hub takes the
     *  controller, Game controls on another screen than the game's too (b18i, D1). b18n review: the screen the window is on
     *  ([windowDisplay]) — the activity's own could still name the one it left (B1: the Hub announced on the top while it
     *  landed on the bottom: the controller stayed on the top, AYN's top lock kept). Remembered, so [landed] says it again
     *  once the window is somewhere else. */
    private fun announceUp() {
        val d = windowDisplay()
        announcedOn = d
        ForegroundAppService.hubShown(d, System.identityHashCode(this))
    }
    /** b18n review: the screen [announceUp] last told the service; null = not told since this window was made. */
    private var announcedOn: Int? = null

    /** b18i (D1): the screen the service opened Game controls on beside the game — kept there, never sent to the Hub's screen
     *  ([relocateIfNeeded]); null = the Hub's screen. */
    private var askedScreen: Int? = null
    /** b18i (D1): the later intent that asked this window for Game controls on [askedScreen] — what a relocation relaunches.
     *  b18j (P3): any later intent (a Hub page too), for every window — but a Game controls page only while the window is
     *  Game controls ([RelaunchRules]). */
    private var relaunchWith: Intent? = null

    /** b18j (P3, device b18v v2 04:57): a `card` extra is a one-shot — WI-8's Hub screen card on the window a move opened, a card
     *  link. Once a page shows, the intents a relocation relaunches (the window's own, a later one: [relocateIfNeeded]) keep that
     *  page and no card. Kept, it outlived its use: `--es page controller` delivered to a window the Hub screen card had opened
     *  relocated it onto Appearance › Hub screen (a card wins over `page`, [firstPage]). */
    private fun cardShown(page: String) {
        for (i in listOfNotNull(intent, relaunchWith)) if (i.getStringExtra("card") != null) { i.removeExtra("card"); i.putExtra("page", page) }
    }

    /** b18i (D1): [i]'s `screen` (the service's choice for Game controls beside the game), only with a Game controls page
     *  (`play:`) and no card; null otherwise. */
    private fun screenAsked(i: Intent?): Int? = i?.takeIf { asksControls(it) }?.getIntExtra(EXTRA_SCREEN, -1)?.takeIf { it >= 0 }

    /** b18j (review): [i] asks for Game controls — a `play:` page and no card (a card wins over `page`, [firstPage]). */
    private fun asksControls(i: Intent?): Boolean {
        return i != null && i.getStringExtra("card") == null && HubPage.safe(i.getStringExtra("page"))?.startsWith("play:") == true
    }

    /** b18i (review): [i] (a relaunch, [relocateIfNeeded]) opens the Hub on the page this window shows — no Game controls page,
     *  screen or controller; the Hub's first page when it shows none yet (restored) or shows Game controls. */
    private fun asHub(i: Intent) {
        i.removeExtra(EXTRA_SCREEN); i.removeExtra("pad")
        val p = HubPage.safe(shownPage)?.takeUnless { it.startsWith("play:") }
        if (p != null) i.putExtra("page", p) else i.removeExtra("page")
    }

    /** b18i (D1): the service's late game switch ([ForegroundAppService] controlsGameFound): this Game controls window shows
     *  [page] (a `play:` page) with [pad]'s tab — what [onNewIntent] does with such an intent, without an intent (one could
     *  land in the Hub's window on its own screen). Main thread. */
    fun switchTo(page: String, pad: String?) {
        val p = HubPage.safe(page)?.takeIf { it.startsWith("play:") } ?: return
        if (gameControls !== this) return
        deepLink.value = p to pad?.takeIf { GameTabRules.padKeyOk(it) }
    }

    /** b18i (D1): Game controls opened beside the game is a window of its own (its own task): it goes with its task, or Recents
     *  kept an empty Wayfinder entry after each one. Every way it closes comes here (B, Home + X again, its stop, Back at the
     *  Hub's home). b18n (device b18v #10): keyed on how its task was made ([OwnTaskRules]), no longer on [askedScreen] — onStop
     *  clears that for such a window left as the Hub, and a relocation's relaunch (a task of its own too, NEW_TASK + MULTIPLE_TASK)
     *  reused as Game controls with the switch off left task 3486 empty in Recents beside the live Hub after B. A Hub task the
     *  launcher, a link or the panel opened keeps Android's rule (a Game controls page shown in it never removes it). */
    override fun finish() {
        if (OwnTaskRules.goesWithWindow(isTaskRoot, intent?.flags ?: 0, asksControls(intent))) super.finishAndRemoveTask()
        else super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        askedScreen?.let { outState.putInt(KEY_SCREEN, it) }   // b18i (D1): a recreated window stays on its screen
    }

    /** Device pass (d3 N2): what this window is — Game controls or the Hub — is decided when a page is asked of it, not at its next
     *  start or stop. A Hub page asked of Game controls (the quick panel's compass or a tile, an "Open Wayfinder" combo) makes it
     *  the Hub: Game controls' session ends and the Hub keeps the controller the page held ([ForegroundAppService.gameControlsToHub];
     *  shown in place, the session never ended — the lock stayed set aside, B left the app). Game controls asked of the Hub (Home +
     *  X over a game, the Hub in the background) is Game controls before it shows: its start no longer takes the controller as the
     *  Hub's, which nothing gave back. b18i (leftovers): also every page the window shows (its composition's SideEffect) — Game
     *  controls' "All apps" made the window the Hub's Games page in place with the session never ended (the lock stayed set
     *  aside, the frame-rate counter showed over the page). The one mark: Hub.kt's own mark is gone, its onDispose ran first.
     *  The service sets the session aside while the window is the Hub (B on Games comes back to the game's page) and ends it
     *  when the Hub is left. */
    private fun becomes(page: String) {
        val token = System.identityHashCode(this)
        if (page.startsWith("play:")) {
            if (gameControls !== this) {
                gameControls = this
                // b18i (review): the Hub's "Your combos" sheet goes — never with Game controls ([showCompanion]). B on Games after
                // "All apps" left it over the other screen for the rest of the session (only the window's pause closed it)
                companion?.dismiss(); companion = null
                sheetsOffHere()   // b18j (D1): another window's sheet on this screen too
                ForegroundAppService.hubToGameControls(token)
            }
        } else if (gameControls === this) {
            gameControls = null
            @Suppress("DEPRECATION") ForegroundAppService.gameControlsToHub(windowManager.defaultDisplay.displayId, token)
            showCompanion()   // the Hub's "Your combos" sheet (never with Game controls)
        }
    }

    /** dev build 17 (WP9): a `card` extra — a card of the registry ([NavCards], by its id) — opens its page on it (More options
     *  open, the card focused: [navTarget]); a controller's card also needs the `page` extra naming whose ("extpad:<key>",
     *  checked by [HubPage.safe]). Any app can send an intent: only registry ids are taken, and it only opens a page (as
     *  `page` does); an unknown id is ignored. */
    private fun cardPage(i: Intent?): String? =
        NavCards.byId(i?.getStringExtra("card"))?.let { navTarget(it, HubPage.safe(i?.getStringExtra("page"))) }

    /** The page a new window opens on: a card's, else the `page` asked, else the tour (first run) or the home. b18g (WI-5): read
     *  once, in [onCreate] — what the window is (Game controls or the Hub) is known before it shows, not at its first composition. */
    private fun firstPage(i: Intent?): String = cardPage(i)
        ?: HubPage.toShow(HubPage.safe(i?.getStringExtra("page")), shown = null, tourDone = Tour.done(this))
        ?: HubPage.HOME

    // ── Hub placement: the Hub lives on the chosen screen, the Deck on the other ──
    companion object {
        // Loop guard: if Android ever refuses the target display we must not
        // bounce between screens forever.
        @Volatile private var lastRelocate = 0L

        @Volatile private var resumed: MainActivity? = null
        /** b18j (D1): every live window (main thread; onCreate → onDestroy). With Game controls opened beside the game, two windows
         *  are up at once, one per screen: the Game controls window clears the other's sheet on its screen ([sheetsOffHere]). */
        private val windows: MutableSet<MainActivity> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        /** The Hub instance showing "Game controls" (opened from a game): Home + X again closes it. */
        @Volatile var gameControls: MainActivity? = null
        /** b18h (review, B11): the page Game controls shows, or is about to (asked, not composed yet); null = no Game controls
         *  window, or one that hasn't drawn its first page. Main thread ([ForegroundAppService]'s late game switch). */
        fun gameControlsPage(): String? = gameControls?.let { it.deepLink.value?.first ?: it.shownPage }
        /** The service (re)connected while the Hub is open: say so again — a restarted service
         *  had lost it (the FPS counter came back over the Hub, the controller hand-off was gone). */
        fun announceHub() {
            val a = resumed ?: return
            a.announceUp()                                     // b18n review: the window's screen, remembered ([landed])
        }

        /** The quick panel opened / closed: the Hub's bottom-screen panel is a presentation
         *  window, which Android draws ABOVE apps — step aside so the quick panel is on top. */
        /** The Hub's own screen on the bottom display is up (a Presentation — see [ForegroundAppService]). */
        fun companionShowing(): Boolean = resumed?.companion != null
        /** Build 18 (E6, MORE-19): the screen "Your combos" is up on (a pill there goes under its header cards); null = none. */
        fun companionDisplay(): Int? = resumed?.companion?.display?.displayId

        /** Build 18 (E3 pF07, E4 pF02): Wayfinder's Hub is the page in front on display [d] (Game controls over a game isn't:
         *  it belongs to the game under it). */
        fun hubOn(d: Int): Boolean = resumed?.let { a ->
            val here = a.windowDisplay()                       // b18n review: the screen it is on (B1: it had just landed)
            a !== gameControls && here == d
        } == true

        /** b18i (review, D1 option C): a Wayfinder window is going away — Game controls closed by B (still [gameControls] until its
         *  stop) or by Home + X again (still [resumed] until its pause). What is in front on its screen isn't settled yet. */
        fun closing(): Boolean = listOfNotNull(gameControls, resumed).any { it.isFinishing }

        fun quickPanelShown(shown: Boolean) {
            val a = resumed ?: return
            a.runOnUiThread { if (shown) { a.companion?.dismiss(); a.companion = null } else a.showCompanion() }
        }
        const val BLUR_RADIUS_PX = 90
        /** b18i (D1): the intent's screen for Game controls opened beside the game (a display id; ForegroundAppService). */
        const val EXTRA_SCREEN = "screen"
        private const val KEY_SCREEN = "wf_screen"
    }

    private fun targetDisplay(): Int? {
        val ids = getSystemService(DisplayManager::class.java).displays.map { it.displayId }
        // b18i (D1): Game controls opened beside the game stays on the screen the service chose (Android may bring an existing
        // Wayfinder task forward on the Hub's screen: it is moved there, not left over the game)
        askedScreen?.takeIf { it in ids }?.let { return it }
        return if (AppSettings.hubOnTop) android.view.Display.DEFAULT_DISPLAY
        else ids.firstOrNull { it != android.view.Display.DEFAULT_DISPLAY }
    }

    /** Relaunch the Hub on its chosen screen if it isn't there. True = this instance is going away. */
    fun relocateIfNeeded(userInitiated: Boolean = false): Boolean {
        @Suppress("DEPRECATION")
        val here = windowManager.defaultDisplay.displayId
        val want = targetDisplay() ?: return false
        if (here == want) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (!userInitiated && now - lastRelocate < 3000) { Log.w("ThorMain", "relocate $here→$want suppressed (loop guard)"); return false }
        lastRelocate = now
        Log.d("ThorMain", "Hub opened on display $here → relaunching on display $want")
        companion?.dismiss(); companion = null
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(want)
        // b18j (review): what the window is now decides what the relaunch asks ([RelaunchRules]). A window that is the Hub now
        // never relaunches a Game controls page. A later one it was asked is spent: Home + X sent `page=play:` to a Hub window
        // left in the background, then "All apps" made it the Hub in place, and that Hub, brought forward on the bottom, came
        // back as Game controls with no session. Its own first one opens the Hub on the page it shows ([asHub])
        val hubNow = RelaunchRules.hubNow(gameControls === this, shownPage, askedScreen, screenAsked(intent) != null)
        val from = relaunchWith?.takeIf { RelaunchRules.keeps(hubNow, asksControls(it)) } ?: intent
        startActivity(
            Intent(this, MainActivity::class.java)
                // keep deep links (page=…); b18i (D1): Game controls asked of this window by a later intent carries ITS page,
                // controller and screen (the window's own intent is the one it was first opened with)
                .apply { from?.extras?.let { putExtras(it) } }
                // b18i (review): a window opened as Game controls beside the game that is the Hub now (left as the Hub, restored):
                // the Hub, on the page it shows — its first intent would bring the game's page back, beside a game maybe gone.
                // b18j (review): every window that is the Hub now (one first opened as Game controls where the Hub is, too)
                .apply { if (RelaunchRules.asHub(hubNow, asksControls(from))) asHub(this) }
                // b18i (WI-8, P3): moved by the Hub screen card, the new window opens on that card (Appearance › More options,
                // as a card link does: cardPage), not on Home. The page shown here goes with this window, so a page already
                // shown doesn't stop the new one taking the card's focus (navTarget)
                .apply { if (userInitiated) { putExtra("card", NavCards.HUB_SCREEN.id); app.wayfinder.ui.ReturnFocus.shown = null } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            opts.toBundle(),
        )
        // b18i (D1): Game controls moving to its screen goes on there — its stop here doesn't end the session (the new window
        // is Game controls from its onCreate)
        if (gameControls === this) gameControls = null
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
        return true
    }

    // ── second-screen companion ──────────────────────────────────────────
    private var companion: CompanionPresentation? = null

    override fun onResume() {
        super.onResume()
        if (isFinishing) return   // this instance is being replaced on the other screen
        // An existing Hub task can be brought forward on the "wrong" screen too.
        if (relocateIfNeeded()) return
        resumed = this
        // b18j (D1): Game controls in front: no sheet over it on its screen — before it takes the controller there
        if (gameControls === this) sheetsOffHere()
        // the Hub takes the controller while it's open (the lock gives it back when it's left); b18i (D1): Game controls on
        // another screen than the game's too
        announceUp()
        applyBackdrop()   // the setting may have changed while we were away
        showCompanion()
        // Belt-and-suspenders: also start the raw-input monitor when the app opens
        // (idempotent). onServiceConnected can be flaky; opening the app guarantees it.
        InputMonitor.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Game controls left by touching the game, Home, Recents…: the controller (and AYN's top
        // lock, lifted while the page is open) go back — not only on B (review 2026-09-25)
        // The gyro popup's live preview stops with the page: the game's own gyro was dead, and the
        // sensor ran all night, after leaving by touch or Home (review 2026-09-25)
        GyroEngine.setPreview(null)
        // b18g (WI-5): Game controls too — token-guarded, a no-op unless this window set the service's Hub mark (hubOn). One
        // that set it (shown before it was known as Game controls) left the counter off over the game after it closed
        ForegroundAppService.hubHidden(System.identityHashCode(this))
        if (gameControls === this) {
            ForegroundAppService.gameControlsClosed()
            // a drawer-like page, like the quick panel: left = closed. Kept, the next Home + X only
            // closed this hidden page (two presses needed) and game detection ran for every app.
            gameControls = null
            finish()
        } else if (askedScreen != null) {
            // b18i (review): Game controls opened beside the game, left as the Hub ("All apps", then Home or a touch on the game):
            // a Hub window from now on. Back from Recents or restored, Hub on Top decides its screen, and a relocation opens the
            // Hub on its page ([relocateIfNeeded]). Kept on this screen, the Hub came back on the bottom against Hub on Top.
            // Before the save (API 28+: onSaveInstanceState comes after onStop)
            askedScreen = null; relaunchWith = null
        }
        sheetsBack()   // b18j (D1): a Game controls window gone — the window still in front shows its sheet again
    }

    override fun onPause() {
        super.onPause()
        if (resumed === this) resumed = null
        companion?.dismiss(); companion = null
        padNav.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) padNav.stop()
    }

    private fun showCompanion() {
        if (companion != null || QuickPanelWindow.isOpen || gameControls === this) return
        if (!AppSettings.controlsSheet) return   // 1.4 (GitHub #52)
        val myId = windowDisplay()   // b18n (device b18j B1): the screen the window is on, not one it just left
        val dm = getSystemService(DisplayManager::class.java)
        val other = dm.displays.firstOrNull { it.displayId != myId } ?: return
        // Only when the Hub is on top: Android forbids Presentation windows on the
        // main display, and with the Hub on the bottom the top screen should stay
        // free for whatever you're playing anyway.
        if (other.displayId == android.view.Display.DEFAULT_DISPLAY) return
        // b18j (D1): never over a Game controls window opened on that screen beside the game (the quick panel's close, a resume)
        val gc = gameControls
        if (!SheetRules.mayShow(other.displayId, gc?.displayNow(), gc?.isFinishing == true)) return
        try {
            companion = CompanionPresentation(this, other).also { it.show(); it.applyBackdrop(realGlass.value) }
        } catch (e: Exception) { Log.w("ThorMain", "companion show failed: ${e.message}") }
    }

    @Suppress("DEPRECATION") private fun displayNow(): Int = windowManager.defaultDisplay.displayId

    /** b18n (device b18j B1): the screen this window is on now — its view root's, which follows its task to another screen as it
     *  lands there; the activity's own display can still name the screen it left. Android moved the Hub's task to the bottom with
     *  an intent while the Hub was in front on top (`am start --display 4`): its resume still read the top, so it kept its place
     *  and showed "Your combos" for "the other screen" — the bottom, where the window was landing. */
    private fun windowDisplay(): Int = window?.peekDecorView()?.display?.displayId ?: displayNow()

    /** b18n (device b18j B1): the window in front lays out on a screen where a Wayfinder sheet is up — it landed there (its task
     *  moved by an outside launch, a tap in the bottom screen's launcher, any path), or the sheet was shown for a screen it was
     *  leaving: the sheet goes ([sheetsOffHere]). Under it the window had no key focus, and nothing brought it back until the
     *  next intent. Every layout, a look at at most two windows: no timer, no wait. b18n review: and the service hears the
     *  Hub's new screen ([announceUp]) when it differs from the one it was told — it lends the Hub the controller there, as for
     *  any Hub on that screen. Not Game controls: its screen is the service's own pick (beside the game). */
    private val landed = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (resumed !== this || isFinishing) return@OnGlobalLayoutListener
        val here = windowDisplay()
        if (windows.any { SheetRules.clears(it.companion?.display?.displayId, here) }) sheetsOffHere(here)
        if (gameControls !== this && announcedOn != here) announceUp()
    }

    /** b18j (D1, device b18v v2 05:02): this window is Game controls, in front: no Wayfinder sheet on its screen. "Your combos" is
     *  a Presentation — Android draws it above every activity on its screen, and it takes that screen's key focus. Game controls
     *  opened beside the game on the screen where the Hub (left open on the other one) showed its sheet stayed under it with no
     *  key focus: the panel's tile (its close had just brought the sheet back), Home + X, PS + △ alike. Its own sheet goes in
     *  [becomes]; [showCompanion] never shows one over it; [sheetsBack] brings it back once it's gone. b18n (B1): any window in
     *  front, landed on a screen with a sheet ([landed]) — its own sheet too. */
    private fun sheetsOffHere(here: Int = windowDisplay()) {
        for (w in windows.toList()) if (SheetRules.clears(w.companion?.display?.displayId, here)) { w.companion?.dismiss(); w.companion = null }
    }

    /** b18j (D1): a window went (Game controls closed): the windows still in front show their sheet again ([showCompanion]'s
     *  rules: Hub on top, the setting on, the panel closed, no Game controls on that screen). */
    private fun sheetsBack() {
        for (w in windows.toList())
            if (w !== this && !w.isFinishing && w.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) w.showCompanion()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurListener) }
        super.onDestroy()
        windows -= this   // b18j (D1)
        window?.peekDecorView()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(landed)   // b18n (B1)
        companion?.dismiss(); companion = null
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    // Left stick → D-pad so the whole UI is navigable with the stick, not just the D-pad. dev build 16 (WP7): the adapter
    // (latched per controller, 400 / 120 ms, touch mode left first) moved to ui/PadNav.kt unchanged — the quick panel and
    // the companion screen use it too
    private val padNav = app.wayfinder.ui.PadNav(
        focusView = { findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) },
        send = { dispatchKeyEvent(it) },
        paused = { PadTest.active || app.wayfinder.keyboard.ThorKeyboardService.isOpen() },
        touchMode = { window.decorView.isInTouchMode },
    )

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // the controller test page: raw sticks, no navigation — and a direction held into it stops (its "centre" ends here)
        if (PadTest.onMotion(event)) { padNav.stop(); return true }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            // Right stick → a live pointer for controls that use it (e.g. the colour wheel).
            app.wayfinder.ui.RightStick.set(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        }
        return padNav.onMotion(event) || super.onGenericMotionEvent(event)
    }
}

/** b18j (D1, device b18v v2 05:02): the Hub's "Your combos" sheet and Game controls never share a screen — the sheet is a
 *  Presentation, above every activity there and holding that screen's key focus. Pure (D1RulesTest). */
internal object SheetRules {
    /** A sheet up on display [sheet] (null: none) goes when Game controls comes to the front on display [controls]. */
    fun clears(sheet: Int?, controls: Int): Boolean = sheet != null && sheet == controls

    /** A sheet may show on display [other]: no Game controls window there ([controls]: its display, null = none), or that
     *  window is going away ([going]). */
    fun mayShow(other: Int, controls: Int?, going: Boolean): Boolean = controls == null || going || controls != other
}

/** b18n (device b18v #10): which window takes its task with it when it closes ([MainActivity.finish]). Pure (D1RulesTest). */
internal object OwnTaskRules {
    /** The window is its task's root ([root]) and that task was made for it: the intent that made it ([flags], the window's own
     *  intent) carried FLAG_ACTIVITY_MULTIPLE_TASK — Game controls beside the game (ForegroundAppService's controlsIntent), a
     *  relocation's relaunch ([MainActivity.relocateIfNeeded]) — or asked for Game controls ([controls]: a play: page and no card;
     *  with the switch off and no Wayfinder task left, Home + X makes a task for it). Such an entry is Wayfinder's own making,
     *  opened as the window's own task; left empty in Recents, it sat beside the live Hub (opening it started yet another Hub
     *  task). A Hub task the launcher, a link or the panel made — Game controls shown in it later included — is kept. */
    fun goesWithWindow(root: Boolean, flags: Int, controls: Boolean): Boolean =
        root && ((flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0 || controls)
}

/** b18j (review): what a relocation relaunches ([MainActivity.relocateIfNeeded]) follows what the window is now: a Game controls
 *  page only for Game controls. Pure (D1RulesTest). */
internal object RelaunchRules {
    /** The window is the Hub now. It isn't Game controls ([controls]), and either it shows a Hub page ([shown]; null before its
     *  first page), or it was opened beside the game ([firstBeside]) and no screen is asked any more ([asked]: cleared at the
     *  stop of such a window left as the Hub; a restored one hasn't drawn yet). A new window opened as Game controls isn't
     *  marked before its relocation in onCreate: nothing shown, no screen of its own → not the Hub. */
    fun hubNow(controls: Boolean, shown: String?, asked: Int?, firstBeside: Boolean): Boolean =
        !controls && (shown?.startsWith("play:") == false || asked == null && firstBeside)

    /** A later intent is relaunched, unless it asked Game controls ([asksControls]) of a window that is the Hub now: spent. */
    fun keeps(hubNow: Boolean, asksControls: Boolean): Boolean = !(hubNow && asksControls)

    /** The relaunch opens the Hub on the page shown ([MainActivity] asHub): the Hub now, and what it relaunches asks Game controls. */
    fun asHub(hubNow: Boolean, asksControls: Boolean): Boolean = hubNow && asksControls
}
