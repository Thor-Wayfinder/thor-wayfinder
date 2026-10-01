package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, DARK, LIGHT,
    /** 1.3: pure black for OLED screens (dark glass on #000, no aurora, no background blur). */
    BLACK }

/** Glass backdrop: your real background (blurred live behind the window) or our aurora. */
enum class Backdrop { WALLPAPER, AURORA }

/** On-the-fly blank gesture: 3-finger TAP toggles; 3-finger SWIPE down blanks / up wakes. */
enum class BlankGesture { OFF, TAP, SWIPE }

/**
 * Lightweight app-wide settings backed by SharedPreferences and exposed as
 * Compose snapshot state (single process, so a singleton is fine). Call [init]
 * once in MainActivity.onCreate before setContent.
 */
object AppSettings {
    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** Auto-blank the second screen after [idleBlankSeconds] with no touch on it. */
    var idleBlankEnabled by mutableStateOf(false)
        private set
    var idleBlankSeconds by mutableStateOf(30)
        private set

    var backdrop by mutableStateOf(Backdrop.WALLPAPER)
        private set

    fun setBackdropMode(b: Backdrop) {
        backdrop = b
        if (::prefs.isInitialized) prefs.edit().putString("backdrop", b.name).apply()
    }

    /** Live-blur glass (Hub + quick panel): how see-through (0 frosted … 1 clear) and how blurred (0 light … 1 heavy). */
    var glassClarity by mutableStateOf(0.5f)
        private set
    var glassBlur by mutableStateOf(0.5f)
        private set

    fun setGlassLook(clarity: Float = glassClarity, blur: Float = glassBlur) {
        glassClarity = clarity.coerceIn(0f, 1f); glassBlur = blur.coerceIn(0f, 1f)
        if (::prefs.isInitialized) prefs.edit().putFloat("glass_clarity", glassClarity).putFloat("glass_blur", glassBlur).apply()
    }

    /** Blur radius in px for [glassBlur] (the old fixed value, 90, sits in the middle). */
    fun glassBlurPx(): Int = (30 + glassBlur * 120).toInt()

    /** Glass panels bend the light behind them (Android 13+; see ui/GlassLens.kt). */
    /** Animate apps moving between the screens. */
    var animateMoves by mutableStateOf(true)
        private set
    fun setAnimateMovesOn(on: Boolean) {
        animateMoves = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("animate_moves", on).apply()
    }

    /** Round 8 — the sticks and triggers for every game (a game's own Game controls values win):
     *  drift is the stick's, not the game's. Set on the controller test page. */
    var stickDefaults by mutableStateOf(PadRemap())
        private set
    fun setStickDefaultsTo(r: PadRemap) {
        stickDefaults = PadRemap(stickL = r.stickL, stickR = r.stickR, trigStart = r.trigStart, trigFull = r.trigFull)
        if (::prefs.isInitialized) prefs.edit().putString("stick_defaults", stickDefaults.toJson().toString()).apply()
    }

    /** Round 8 — Do not disturb while a game is on a screen (opt-in, off by default). */
    var dndWhilePlaying by mutableStateOf(false)
        private set
    fun setDndWhilePlayingOn(on: Boolean) {
        dndWhilePlaying = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("dnd_while_playing", on).apply()
    }

    /** Keep the bottom screen on — remembered across restarts. */
    var keepBottomOn by mutableStateOf(false)
        private set
    fun rememberKeepBottom(on: Boolean) {
        keepBottomOn = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("keep_bottom_on", on).apply()
    }

    var refraction by mutableStateOf(true)
        private set

    fun setRefractionOn(on: Boolean) {
        refraction = on
        app.wayfinder.ui.GlassLensConfig.enabled = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("refraction", on).apply()
    }

    /** What the screenshot action captures. */
    var shotTarget by mutableStateOf(ShotTarget.TOP)
        private set

    fun setScreenshotTarget(t: ShotTarget) {
        shotTarget = t
        if (::prefs.isInitialized) prefs.edit().putString("shot_target", t.name).apply()
    }

    /** Lock — keep the controller on one screen; touching the other won't move it. */
    var focusLockEnabled by mutableStateOf(false)
        private set
    var focusLockTop by mutableStateOf(true)   // lock target: top (true) or bottom screen
        private set

    fun setFocusLock(enabled: Boolean, top: Boolean = focusLockTop) {
        focusLockEnabled = enabled; focusLockTop = top
        if (::prefs.isInitialized) prefs.edit().putBoolean("focus_lock", enabled).putBoolean("focus_lock_top", top).apply()
    }

    /**
     * Moving the controller with its combo (Home + right stick) makes it STAY there — a touch on
     * the other screen won't pull it back (it turns the lock on at that screen). Off =
     * the controller follows your touches until you lock it yourself. The chosen default: stay.
     */
    var focusSticky by mutableStateOf(true)
        private set

    fun setFocusStickyOn(on: Boolean) {
        focusSticky = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("focus_sticky", on).apply()
    }

    /** 1.4: the controller goes with the app it was on when Wayfinder moves / swaps it ("Follows touch" and
     *  "Stays where sent" — "Always top / bottom" stay put). New installs on; an update keeps 1.3's way (off). */
    var focusFollowsMove by mutableStateOf(false)
        private set

    /** The controller goes with [pkg] when it moves: its own choice, else the switch. */
    fun followsMove(pkg: String): Boolean = AppConfigStore.get(pkg).followMove ?: focusFollowsMove

    fun setFocusFollowsMoveOn(on: Boolean) {
        focusFollowsMove = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("focus_follows_move", on).apply()
    }

    /** The AYN button opens Wayfinder's panel instead of AYN's drawer. */
    var aynButtonOurs by mutableStateOf(true)
        private set

    fun setAynButtonOursOn(on: Boolean) {
        aynButtonOurs = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("ayn_button_ours", on).apply()
    }

    /** 1.3 (GitHub #13) — Recents' controller hint: 0 full · 1 compact (symbols, no title) · 2 off; and where:
     *  0 top centre · 1 top left · 2 top right · 3 bottom left · 4 bottom right. */
    var recentsHint by mutableStateOf(0)
        private set
    var recentsHintAt by mutableStateOf(0)
        private set
    fun chooseRecentsHint(mode: Int, at: Int) {
        recentsHint = mode.coerceIn(0, 2); recentsHintAt = at.coerceIn(0, 4)
        if (::prefs.isInitialized) prefs.edit().putInt("recents_hint", recentsHint).putInt("recents_hint_at", recentsHintAt).apply()
    }

    /** 1.3 — what a tap on the AYN button does (the quick panel unless changed). */
    var aynTap by mutableStateOf(ThorAction.QUICK_MENU)
        private set
    /** 1.3 — what holding it does; null = nothing of its own (it acts like a tap when let go). */
    var aynHold by mutableStateOf<ThorAction?>(null)
        private set

    fun chooseAynTap(a: ThorAction) {
        aynTap = a
        if (::prefs.isInitialized) prefs.edit().putString("ayn_tap", a.name).apply()
    }

    /** 1.3 (GitHub #31): the quick panel takes the controller (off: touch only, the game keeps it). */
    var panelTakesController by mutableStateOf(true)
        private set

    fun setPanelTakesControllerOn(on: Boolean) {
        panelTakesController = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("panel_takes_controller", on).apply()
    }

    fun chooseAynHold(a: ThorAction?) {
        aynHold = a
        if (::prefs.isInitialized) prefs.edit().putString("ayn_hold", a?.name).apply()
    }

    /** FPS counter on the top screen. */
    var fpsCounter by mutableStateOf(false)
        private set

    /** FPS counter: which screens (0 top · 1 bottom · 2 both) and which corner (0 ↖ · 1 ↗ · 2 ↙ · 3 ↘). */
    var fpsScreens by mutableStateOf(0)
        private set
    var fpsCorner by mutableStateOf(0)
        private set
    /** Round 8 — what the counter shows: 0 the frame rate · 1 + battery · 2 + temperatures. */
    var fpsLevel by mutableStateOf(0)
        private set
    fun setFpsLevelTo(level: Int) {
        fpsLevel = level.coerceIn(0, 2)
        if (::prefs.isInitialized) prefs.edit().putInt("fps_level", fpsLevel).apply()
    }
    fun setFpsPlacement(screens: Int = fpsScreens, corner: Int = fpsCorner) {
        fpsScreens = screens; fpsCorner = corner
        if (::prefs.isInitialized) prefs.edit().putInt("fps_screens", screens).putInt("fps_corner", corner).apply()
    }

    fun setFpsCounterOn(on: Boolean) {
        fpsCounter = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("fps_counter", on).apply()
    }

    /** Which screen the Hub lives on (the Control Deck takes the other one). */
    var hubOnTop by mutableStateOf(true)
        private set

    fun setHubScreen(top: Boolean) {
        hubOnTop = top
        if (::prefs.isInitialized) prefs.edit().putBoolean("hub_on_top", top).apply()
    }

    // ── 1.4 ──
    /** 1.4 (GitHub #62 #63): hide Android's navigation bar again when it comes back although it's set hidden. */
    var navGuard by mutableStateOf(true)
        private set
    fun setNavGuardOn(on: Boolean) { navGuard = on; if (::prefs.isInitialized) prefs.edit().putBoolean("nav_guard", on).apply() }

    /** 1.4 (GitHub #52): the "Your controls" sheet on the other screen while Wayfinder is open. */
    var controlsSheet by mutableStateOf(true)
        private set
    fun setControlsSheetOn(on: Boolean) { controlsSheet = on; if (::prefs.isInitialized) prefs.edit().putBoolean("controls_sheet", on).apply() }

    /** 1.4 (GitHub #52): the list of combos while Home / Back / AYN is held — 0 soon · 1 after a longer hold · 2 off. */
    var chordHint by mutableStateOf(0)
        private set
    fun chooseChordHint(mode: Int) { chordHint = mode.coerceIn(0, 2); if (::prefs.isInitialized) prefs.edit().putInt("chord_hint", chordHint).apply() }

    /** 1.4 (GitHub #54): a blanked screen wakes on a double tap (off: any touch). */
    var wakeDoubleTap by mutableStateOf(false)
        private set
    fun setWakeDoubleTapOn(on: Boolean) { wakeDoubleTap = on; if (::prefs.isInitialized) prefs.edit().putBoolean("wake_double_tap", on).apply() }

    /** 1.4: this install is an UPDATE (from 1.3.x or older) — new defaults then keep what it did before. */
    fun isUpdate(ctx: Context): Boolean = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.lastUpdateTime - pi.firstInstallTime > 60_000
    }.getOrDefault(false)

    /** 1.4 (GitHub #24): your own colours — a flat look. ARGB: background, cards, text, accent. */
    var customColors by mutableStateOf(false)
        private set
    var customBg by mutableStateOf(0xFF101318.toInt())
        private set
    var customCard by mutableStateOf(0xFF1E232C.toInt())
        private set
    var customText by mutableStateOf(0xFFFFFFFF.toInt())
        private set
    var customAccent by mutableStateOf(0xFF3D9BFF.toInt())
        private set
    fun setCustomColorsOn(on: Boolean) { customColors = on; if (::prefs.isInitialized) prefs.edit().putBoolean("custom_colors", on).apply() }
    fun setCustomColor(which: Int, argb: Int) {
        val c = argb or 0xFF000000.toInt()
        when (which) { 0 -> customBg = c; 1 -> customCard = c; 2 -> customText = c; else -> customAccent = c }
        if (::prefs.isInitialized) prefs.edit().putInt("custom_c$which", c).apply()
    }
    /** No glass: Black, or your own colours. */
    val flatLook: Boolean get() = themeMode == ThemeMode.BLACK || customColors

    /** 3-finger gesture on the bottom screen blanks/wakes it (on the fly). */
    var gestureBlankMode by mutableStateOf(BlankGesture.TAP)
        private set

    /** Idempotent — safe to call from both MainActivity.onCreate and the service. */
    fun init(ctx: Context) {
        if (initialized) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        hubOnTop = prefs.getBoolean("hub_on_top", true)
        backdrop = runCatching { Backdrop.valueOf(prefs.getString("backdrop", null) ?: "WALLPAPER") }.getOrDefault(Backdrop.WALLPAPER)
        refraction = prefs.getBoolean("refraction", true)
        animateMoves = prefs.getBoolean("animate_moves", true)
        dndWhilePlaying = prefs.getBoolean("dnd_while_playing", false)
        stickDefaults = runCatching { PadRemap.fromJson(org.json.JSONObject(prefs.getString("stick_defaults", "{}") ?: "{}")) }.getOrNull()
            ?.let { PadRemap(stickL = it.stickL, stickR = it.stickR, trigStart = it.trigStart, trigFull = it.trigFull) } ?: PadRemap()
        keepBottomOn = prefs.getBoolean("keep_bottom_on", false)
        glassClarity = prefs.getFloat("glass_clarity", 0.5f)
        glassBlur = prefs.getFloat("glass_blur", 0.5f)
        app.wayfinder.ui.GlassLensConfig.enabled = refraction
        shotTarget = runCatching { ShotTarget.valueOf(prefs.getString("shot_target", null) ?: "TOP") }.getOrDefault(ShotTarget.TOP)
        focusLockEnabled = prefs.getBoolean("focus_lock", false)
        focusLockTop = prefs.getBoolean("focus_lock_top", true)
        focusSticky = prefs.getBoolean("focus_sticky", true)
        // decided once (kept: a later update must not flip it)
        if (!prefs.contains("focus_follows_move")) prefs.edit().putBoolean("focus_follows_move", !isUpdate(ctx)).apply()
        focusFollowsMove = prefs.getBoolean("focus_follows_move", false)
        fpsCounter = prefs.getBoolean("fps_counter", false)
        fpsScreens = prefs.getInt("fps_screens", 0)
        fpsCorner = prefs.getInt("fps_corner", 0)
        recentsHint = prefs.getInt("recents_hint", 0).coerceIn(0, 2)
        recentsHintAt = prefs.getInt("recents_hint_at", 0).coerceIn(0, 4)
        fpsLevel = prefs.getInt("fps_level", 0).coerceIn(0, 2)
        aynButtonOurs = prefs.getBoolean("ayn_button_ours", true)
        aynTap = runCatching { ThorAction.valueOf(prefs.getString("ayn_tap", null) ?: "QUICK_MENU") }.getOrDefault(ThorAction.QUICK_MENU)
        aynHold = runCatching { ThorAction.valueOf(prefs.getString("ayn_hold", null)!!) }.getOrNull()
        panelTakesController = prefs.getBoolean("panel_takes_controller", true)
        idleBlankEnabled = prefs.getBoolean("idle_blank_enabled", false)
        idleBlankSeconds = prefs.getInt("idle_blank_seconds", 30)
        gestureBlankMode = runCatching { BlankGesture.valueOf(prefs.getString("gesture_blank_mode", null) ?: "TAP") }
            .getOrDefault(BlankGesture.TAP)
        navGuard = prefs.getBoolean("nav_guard", true)
        customColors = prefs.getBoolean("custom_colors", false)
        customBg = prefs.getInt("custom_c0", customBg); customCard = prefs.getInt("custom_c1", customCard)
        customText = prefs.getInt("custom_c2", customText); customAccent = prefs.getInt("custom_c3", customAccent)
        controlsSheet = prefs.getBoolean("controls_sheet", true)
        chordHint = prefs.getInt("chord_hint", 0).coerceIn(0, 2)
        wakeDoubleTap = prefs.getBoolean("wake_double_tap", false)
        Features.init(ctx)
        initialized = true
    }

    fun setMode(mode: ThemeMode) {
        themeMode = mode
        if (::prefs.isInitialized) prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setIdleBlank(enabled: Boolean) {
        idleBlankEnabled = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean("idle_blank_enabled", enabled).apply()
    }

    fun setIdleSeconds(seconds: Int) {
        idleBlankSeconds = seconds.coerceIn(5, 900)
        if (::prefs.isInitialized) prefs.edit().putInt("idle_blank_seconds", idleBlankSeconds).apply()
    }

    fun setGestureBlank(mode: BlankGesture) {
        gestureBlankMode = mode
        if (::prefs.isInitialized) prefs.edit().putString("gesture_blank_mode", mode.name).apply()
    }
}
