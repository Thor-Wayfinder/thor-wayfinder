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
import androidx.lifecycle.repeatOnLifecycle
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
        // Opened on the wrong screen (e.g. from the bottom screen's launcher)?
        // Relaunch on the chosen one before drawing anything.
        if (relocateIfNeeded()) return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

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
                // Deep link: `page` extra opens a sub-page directly (e.g. "controls", "apps"),
                // on first launch or when the Hub is already open (onNewIntent).
                // First launch → the welcome tour.
                var page by androidx.compose.runtime.saveable.rememberSaveable {
                    mutableStateOf(HubPage.safe(intent?.getStringExtra("page")) ?: if (!Tour.done(this@MainActivity)) HubPage.TOUR else "hub")
                }
                LaunchedEffect(deepLink.value) { deepLink.value?.let { page = it; deepLink.value = null } }
                // B / Back goes up one level; the Hub home itself leaves the app as usual.
                androidx.activity.compose.BackHandler(enabled = page != HubPage.HOME) {
                    // Game controls (opened from a game): Back returns to the game
                    if (page.startsWith("play:")) { finish(); ForegroundAppService.gameControlsClosed() }
                    else page = hubBack(page)
                }
                HubNavHost(myDisplayId, page) { page = it }
            }
            }
        }
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

    private val deepLink = mutableStateOf<String?>(null)

    private val padFallback = app.wayfinder.ui.PadFallbackFilter()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PadTest.onKey(event)) return true               // the controller test page reads every button
        if (padFallback.shouldDrop(event)) return true   // R3/L3/Start must not "click"
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        HubPage.safe(intent.getStringExtra("page"))?.let { deepLink.value = it }
    }

    // ── Hub placement: the Hub lives on the chosen screen, the Deck on the other ──
    companion object {
        // Loop guard: if Android ever refuses the target display we must not
        // bounce between screens forever.
        @Volatile private var lastRelocate = 0L

        @Volatile private var resumed: MainActivity? = null
        /** The Hub instance showing "Game controls" (opened from a game): Home + X again closes it. */
        @Volatile var gameControls: MainActivity? = null
        /** The service (re)connected while the Hub is open: say so again — a restarted service
         *  had lost it (the FPS counter came back over the Hub, the controller hand-off was gone). */
        fun announceHub() {
            val a = resumed ?: return
            @Suppress("DEPRECATION") ForegroundAppService.hubShown(a.windowManager.defaultDisplay.displayId, System.identityHashCode(a))
        }

        /** The quick panel opened / closed: the Hub's bottom-screen panel is a presentation
         *  window, which Android draws ABOVE apps — step aside so the quick panel is on top. */
        /** The Hub's own screen on the bottom display is up (a Presentation — see [ForegroundAppService]). */
        fun companionShowing(): Boolean = resumed?.companion != null

        fun quickPanelShown(shown: Boolean) {
            val a = resumed ?: return
            a.runOnUiThread { if (shown) { a.companion?.dismiss(); a.companion = null } else a.showCompanion() }
        }
        const val BLUR_RADIUS_PX = 90
    }

    private fun targetDisplay(): Int? {
        val ids = getSystemService(DisplayManager::class.java).displays.map { it.displayId }
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
        startActivity(
            Intent(this, MainActivity::class.java)
                .apply { intent?.extras?.let { putExtras(it) } }   // keep deep links (page=…)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            opts.toBundle(),
        )
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
        // the Hub takes the controller while it's open (the lock gives it back when it's left)
        @Suppress("DEPRECATION") ForegroundAppService.hubShown(windowManager.defaultDisplay.displayId, System.identityHashCode(this))
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
        if (gameControls !== this) ForegroundAppService.hubHidden(System.identityHashCode(this))
        if (gameControls === this) {
            ForegroundAppService.gameControlsClosed()
            // a drawer-like page, like the quick panel: left = closed. Kept, the next Home + X only
            // closed this hidden page (two presses needed) and game detection ran for every app.
            gameControls = null
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (resumed === this) resumed = null
        companion?.dismiss(); companion = null
    }

    private fun showCompanion() {
        if (companion != null || QuickPanelWindow.isOpen || gameControls === this) return
        if (!AppSettings.controlsSheet) return   // 1.4 (GitHub #52)
        @Suppress("DEPRECATION")
        val myId = windowManager.defaultDisplay.displayId
        val dm = getSystemService(DisplayManager::class.java)
        val other = dm.displays.firstOrNull { it.displayId != myId } ?: return
        // Only when the Hub is on top: Android forbids Presentation windows on the
        // main display, and with the Hub on the bottom the top screen should stay
        // free for whatever you're playing anyway.
        if (other.displayId == android.view.Display.DEFAULT_DISPLAY) return
        try {
            companion = CompanionPresentation(this, other).also { it.show(); it.applyBackdrop(realGlass.value) }
        } catch (e: Exception) { Log.w("ThorMain", "companion show failed: ${e.message}") }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurListener) }
        super.onDestroy()
        companion?.dismiss(); companion = null
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    // Left stick → D-pad so the whole UI is navigable with the stick, not just the D-pad.
    private var stickLatched = false
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (PadTest.onMotion(event)) return true           // the controller test page: raw sticks, no navigation
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            // Right stick → a live pointer for controls that use it (e.g. the colour wheel).
            app.wayfinder.ui.RightStick.set(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
            // The Thor's D-pad reports as HAT axes (MotionEvents), not KEYCODE_DPAD,
            // so Compose never sees it — convert both the D-pad (hat) and left stick.
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val dead = 0.5f
            val key = when {
                hy < -dead || y < -dead -> KeyEvent.KEYCODE_DPAD_UP
                hy > dead || y > dead -> KeyEvent.KEYCODE_DPAD_DOWN
                hx < -dead || x < -dead -> KeyEvent.KEYCODE_DPAD_LEFT
                hx > dead || x > dead -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> 0
            }
            if (key == 0) { stickLatched = false } else if (!stickLatched) {
                stickLatched = true
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
