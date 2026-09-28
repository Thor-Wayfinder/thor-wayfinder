package app.wayfinder

import app.wayfinder.keyboard.ThorKeyboardService
import app.wayfinder.lights.StickLights
import app.wayfinder.lights.LightSettings
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

class ForegroundAppService : AccessibilityService() {

    companion object {
        private const val TAG = "ThorFgSvc"
        private const val PRIMARY_DISPLAY = 0   // built-in main (top) screen

        // Per-display app tracking: displayId → packageName
        // ConcurrentHashMap: accessed from main thread (events) and background (swap logic)
        val displayApps = ConcurrentHashMap<Int, String>()

        @Volatile var isRunning = false
            private set

        @Volatile var availableDisplayIds = emptyList<Int>()
            private set

        @Volatile private var instance: ForegroundAppService? = null

        /** Play a wipe transition when moving apps between screens. */
        var animateMoves: Boolean
            get() = AppSettings.animateMoves
            set(v) = AppSettings.setAnimateMovesOn(v)

        /**
         * Trigger the same swap/send path as the long-press gesture (Shizuku,
         * verification, retries included). Returns false if the service isn't
         * connected or a swap is already running.
         */
        fun requestSwap(): Boolean = instance?.startSwapInBackground() ?: false

        /** A one-time tip: a title and a (button, what it does) table, for [holdMs]. */
        fun tip(displayId: Int, title: List<String>, cells: List<Pair<String, String>>, holdMs: Long = 7000): Boolean {
            val s = instance ?: return false
            if (s.focusCue == null) return false
            s.handler.post { s.focusCue?.showTable(displayId, title, cells, holdMs) }
            return true
        }

        /** Round 8 — an "Open…" combo: an app on the controller's screen, an app pair, or a Wayfinder page. */
        fun open(arg: String?): Boolean {
            val svc = instance ?: return false
            if (!OpenTargets.valid(arg)) return false
            if (TourPractice.intercept(ThorAction.OPEN)) return true
            val a = arg!!
            return when {
                a.startsWith("app:") -> {
                    val pkg = a.removePrefix("app:")
                    if (svc.packageManager.getLaunchIntentForPackage(pkg) == null) { pill("That app isn't installed any more"); return false }
                    val top = svc.controllerDisplay() == PRIMARY_DISPLAY || svc.secondDisplayId() == null
                    svc.openLayout(if (top) pkg else null, if (top) null else pkg, "open")
                }
                a.startsWith("pair:") -> Layouts.pairs.firstOrNull { it.id == a.removePrefix("pair:").toLongOrNull() }
                    ?.let { openPair(it) } ?: run { pill("That app pair was deleted"); false }
                else -> { svc.handler.post { svc.openHubPage(a.removePrefix("page:")) }; true }   // takes the controller along
            }
        }

        /** A short message pill on [displayId] (default: the top screen). */
        fun pill(text: String, displayId: Int = PRIMARY_DISPLAY, holdMs: Long = 1800) {
            val s = instance ?: return
            s.handler.post { s.focusCue?.show(displayId, text, holdMs) }
        }

        /** "Stay awake" on? For the switches. */
        fun isSecondKeptAwake(): Boolean = instance?.stayAwake?.isHeld == true

        /** Route every button press to [cb] (and swallow it) until [stopCapture]. */
        fun startCapture(cb: (ThorButton, Boolean) -> Unit): Boolean {
            val e = instance?.buttonEngine ?: return false
            e.capture = cb; return true
        }
        fun stopCapture() { instance?.buttonEngine?.capture = null }

        /** Apply the controller-lock settings now (with the on-screen cue). */
        fun reapplyFocusLock() { instance?.let { s -> s.handler.post { s.applyFocusLock(announce = true) } } }

        /** Re-apply the top app's 2nd-screen policy (after the user edits it). */
        fun quickPanelClosed() { instance?.let { it.handler.post { it.onQuickPanelClosed() } } }
        /** "Game controls" closed (Back to game): the controller goes back to the game. */
        fun gameControlsClosed() { instance?.let { it.handler.post { it.onGameControlsClosed() } } }
        /** The apps on the two screens now (not Wayfinder, not launchers) — the switch chip. */
        fun screenGames(): List<String> = instance?.screenGamesNow().orEmpty()
        fun screenMode(): Int = instance?.currentScreenMode() ?: 0
        /** The screen the controller works right now (null: service not running). */
        fun controllerScreen(): Int? = instance?.controllerDisplay()
        fun setScreenMode(mode: Int) { instance?.let { it.handler.post { it.applyScreenMode(mode) } } }
        fun later(ms: Long, block: () -> Unit) { instance?.handler?.postDelayed(block, ms) ?: block() }

        fun reapplyFps() { instance?.let { it.handler.post { it.applyFps() } } }
        /** The pad profile of the app with the controller, again (a setting it depends on changed). */
        fun reapplyPadProfile() { PadLayerCtl.forgetProfile() }

        fun reapplyPolicy() { instance?.let { it.reapplyTopAppPolicy(); it.handler.post { it.applyPerf() } } }

        /** The screen with [pkg]'s focused editable field (for the keyboard), or null. */
        fun editorDisplay(pkg: String): Int? {
            val s = instance ?: return null
            return runCatching {
                val all = s.windowsOnAllDisplays
                (0 until all.size()).firstNotNullOfOrNull { i ->
                    all.keyAt(i).takeIf {
                        all.valueAt(i).any { w ->
                            val root = w.root
                            root != null && root.packageName == pkg &&
                                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
                        }
                    }
                }
            }.getOrNull()
        }

        /** Open a game's companion on the bottom screen now. */
        fun openCompanionNow(pkg: String): Boolean {
            val s = instance ?: return false
            val d = s.secondDisplayId() ?: return false
            // under a dual-screen game's second screen it would be invisible → the top screen (1.1)
            s.openCompanion(pkg, if (s.coveredByPresentation(d)) PRIMARY_DISPLAY else d); return true
        }

        /** Open a saved pair. */
        fun openPair(p: AppPair): Boolean = instance?.openLayout(p.top, p.bottom, "pair") ?: false

        /** What's on the top / bottom screens right now (null = home or nothing). */
        fun currentLayout(): Pair<String?, String?> {
            val s = instance ?: return null to null
            return displayApps[PRIMARY_DISPLAY] to s.secondDisplayId()?.let { displayApps[it] }
        }

        /** Test hook (debug builds only): drive the Recents controls without a physical pad. */
        fun testRecents(cmd: String) {
            if (!BuildConfig.DEBUG) return
            val s = instance ?: return
            s.handler.post {
                when (cmd) {
                    "left" -> s.recentsBrowse(-1); "right" -> s.recentsBrowse(1)
                    "open" -> s.recentsGesture(0.50f, 0.48f, 0.50f, 0.48f, 40)
                    "close" -> s.recentsGesture(0.50f, 0.50f, 0.50f, 0.04f, 160)
                }
            }
        }

        /** Hold-to-shift: the layer's cheat sheet while the shift button is held (like Home's). */
        fun showShiftHint(button: String, cells: List<Pair<String, String>>) {
            val s = instance ?: return
            s.handler.post { s.focusCue?.showHint(s.controllerDisplay(), listOf("Keep $button held and press:"), cells) }
        }
        fun hideShiftHint() { instance?.let { s -> s.handler.post { s.focusCue?.hideHint() } } }
        /** The Hub is on screen [displayId] / left: it takes the controller, then gives it back. */
        fun hubShown(displayId: Int, token: Int) { instance?.let { s -> s.handler.post { s.onHubShown(displayId, token) } } }
        /** Only the Hub that took it gives it back ([token]): a relocating Hub's old copy stops AFTER the new one took it. */
        fun hubHidden(token: Int) { instance?.let { s -> s.handler.post { s.onHubHidden(token) } } }
        /** Keyboard & mouse (the deck) is on screen — it uses the virtual mouse too. */
        fun keyboardDeckShowing(): Boolean? = instance?.inputDeck?.isShowing
        /** Performance / fan / refresh rate changed: apply them — and ONLY them (re-running the
         *  bottom-screen policy blanked the screen the quick panel was open on — review 2026-09-25). */
        fun reapplyPerf() { instance?.let { s -> s.handler.post { s.applyPerf() } } }
        /** Re-apply the stick lights for the app that has the controller (after an edit). */
        fun reapplyLights() { instance?.let { s -> s.handler.post { s.applyLights(force = true) } } }
        /** The running game changed: its own performance / fan / Hz / lights (or back to the app's). */
        /** The "Do not disturb while playing" switch changed: apply it now. */
        fun reapplyDnd() { instance?.let { s -> s.handler.post { s.applyDnd() } } }
        fun gameChanged() { instance?.let { s -> s.handler.post { s.applyPerf(); s.applyLights(force = true) } } }
        /** The app the quick panel was opened over (for "Now playing" and "Keep for this game"). */
        fun panelApp(): String? = instance?.let { s ->
            fun ok(p: String?) = p?.takeIf { it !in s.launcherPackages && it != s.packageName }
            val d = s.panelReturnTo ?: PRIMARY_DISPLAY
            // 1.3: that screen is covered by another app's second screen (a dual-screen game): the game,
            // not the frontend underneath ("Now playing: iiSU" over WatermelonDS)
            val covered = d != PRIMARY_DISPLAY && runCatching { s.coveredByPresentation(d) }.getOrDefault(false)
            (if (covered) null else ok(displayApps[d])) ?: ok(displayApps[PRIMARY_DISPLAY]).takeIf { covered }
        }

        /** The bottom-screen keyboard host (needs this service's overlay rights). */
        fun keyboardOverlay(): app.wayfinder.keyboard.KeyboardOverlay? = instance?.keyboardOverlay

        /** The display that has key/controller focus, as far as we know (null = service off). */
        fun focusedDisplay(): Int? = instance?.focusedDisplayId()

        /** Clear background app tasks (keeps launcher, this app, and on-screen apps). */
        fun requestClearAll(): Boolean = instance?.startClearInBackground() ?: false

        /**
         * Single execution path for every action, whether triggered by the UI,
         * a gesture, or a button chord. Returns false if the service isn't
         * connected or the action isn't wired yet.
         */
        fun perform(action: ThorAction): Boolean {
            val svc = instance ?: return false
            // The tour's "try it" steps: ticked, not carried out — practising "move the app"
            // must not move the tour away (or send the controller off it)
            if (TourPractice.intercept(action)) return true
            return when (action) {
                ThorAction.SWAP_OR_SEND -> svc.startSwapInBackground()
                ThorAction.CLEAR_BACKGROUND -> svc.startClearInBackground()
                ThorAction.CLOSE_APP -> { svc.handler.post { svc.closeCurrentApp() }; true }
                ThorAction.RECENTS -> { svc.handler.post { svc.openRecents() }; true }
                ThorAction.BACK -> { svc.handler.post { svc.performGlobalAction(GLOBAL_ACTION_BACK) }; true }
                ThorAction.TOGGLE_SECOND_SCREEN -> svc.toggleSecondScreen()
                ThorAction.TOGGLE_KEEP_AWAKE -> svc.toggleKeepAwakeSecond()
                ThorAction.SLEEP -> { svc.sleepNow(); true }
                ThorAction.AYN_MOUSE -> { svc.toggleAynMouse(); true }
                ThorAction.GYRO_TOGGLE -> { svc.handler.post { svc.toggleGyro() }; true }
                ThorAction.GUIDE -> { svc.handler.post { svc.openGuide() }; true }
                ThorAction.FOCUS_LOCK_TOGGLE -> { svc.handler.post { svc.toggleFocusLock() }; true }
                ThorAction.SCREENSHOT -> { svc.handler.post { svc.takeScreenshot() }; true }
                ThorAction.KEYBOARD -> { svc.handler.post { svc.toggleInputDeck() }; true }
                ThorAction.BRIGHTER -> { svc.stepBrightness(+1); true }
                ThorAction.DIMMER -> { svc.stepBrightness(-1); true }
                ThorAction.QUICK_MENU -> { svc.handler.post { svc.toggleQuickPanel() }; true }
                ThorAction.FPS_COUNTER -> { svc.handler.post { AppSettings.setFpsCounterOn(!AppSettings.fpsCounter); svc.applyFps() }; true }
                ThorAction.GAME_CONTROLS -> { svc.handler.post { svc.openGameControls() }; true }
                ThorAction.FOCUS_SWITCH_UP -> { svc.handler.post { svc.focusAction(top = true) }; true }
                ThorAction.FOCUS_SWITCH_DOWN -> {
                    if (svc.secondDisplayId() == null) return false
                    svc.handler.post { svc.focusAction(top = false) }; true
                }
                else -> { Log.d(TAG, "perform($action): not yet implemented"); false }
            }
        }
    }

    // All button handling (Back taps/hold, chords…) runs through the user's bindings.
    private var buttonEngine: ButtonEngine? = null
    /** One move / swap / layout / route at a time — claimed atomically by whoever starts one. */
    private val swapBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val swapInProgress get() = swapBusy.get()
    @Volatile private var clearInProgress = false
    private val handler = Handler(Looper.getMainLooper())
    private var overlayAnimator: OverlayAnimator? = null
    private var blanker: DisplayBlanker? = null

    // ── Auto-off idle timer ───────────────────────────────────────────
    private val idleCheckMs = 2000L
    // True only while the SECOND screen is blanked BY the idle timer (so we never
    // auto-wake a screen the user blanked manually, and know when to auto-wake).
    @Volatile private var idleBlanked = false
    private val idleWatcher = object : Runnable {
        override fun run() {
            try { checkIdle() } catch (e: Exception) { Log.w(TAG, "idle check: ${e.message}") }
            finally { handler.postDelayed(this, idleCheckMs) }
        }
    }

    private fun secondDisplayId(): Int? =
        availableDisplayIds.firstOrNull { it != PRIMARY_DISPLAY }

    // ── On-the-fly blank: 3-finger TAP or SWIPE on the bottom screen ─────
    // Raw evdev, multi-touch protocol B: ABS_MT_SLOT (47) selects a slot,
    // ABS_MT_TRACKING_ID (57) = -1 lifts that slot's finger / ≥0 starts one,
    // ABS_MT_POSITION_X/Y (53/54) move it, SYN_REPORT closes a consistent frame.
    // A "session" runs from the first finger down to the last finger up; we judge
    // it when it ENDS, from the peak finger count, duration, and the fingers'
    // average displacement.
    // Kernel subtlety: evdev DROPS an ABS event whose value is unchanged for that
    // slot. So "no position event" means "didn't move" — a new contact may report
    // no coordinates at all (same spot as that slot's last touch). We therefore
    // mirror the per-slot state, capture each finger's start at the SYN that ends
    // its landing frame, and treat an axis that never reports as stationary.
    // Bottom panel is mounted ROTATION_90 (dumpsys input: orientation=1, raw X
    // 0..1080 / Y 0..1240 on a 1240×1080 screen): screen-X = raw Y and
    // screen-Y = 1080 − raw X, so a swipe DOWN on screen is raw X decreasing.
    private class Touch {
        var x = -1; var y = -1       // latest raw position (-1 = unknown)
        var sx = -1; var sy = -1     // raw position at contact start (-1 = unknown)
        var landing = true           // start not captured yet (captured at next SYN)
        fun dx() = if (sx >= 0 && x >= 0) x - sx else 0
        fun dy() = if (sy >= 0 && y >= 0) y - sy else 0
    }
    private val mtActive = HashMap<Int, Touch>()   // slot → finger (bottom panel)
    private var mtCurSlot = 0
    private val slotX = IntArray(16) { -1 }        // mirror of the kernel's per-slot values
    private val slotY = IntArray(16) { -1 }
    private var mtLastEvent = 0L
    private var sessStart = 0L
    private var sessBlankedAtStart = false
    private var sessMaxFingers = 0
    private var sessMoveX = 0L; private var sessMoveY = 0L; private var sessLifted = 0

    private fun installGestureListener() {
        InputMonitor.listener = listener@{ idx, type, code, value ->
            if (idx == ThorInput.DEV_CONTROLLER) { onControllerEvent(type, code, value); return@listener }
            if (idx == ThorInput.DEV_HALL) { if (type == 5 && code == 0) handler.post { SleepEngine.onLid(value == 1) }; return@listener }
            // A new finger on a screen moves input focus there (Android's own rule).
            if (type == ThorInput.EV_ABS && code == ThorInput.ABS_MT_TRACKING_ID && value >= 0) {
                if (idx == ThorInput.DEV_TOP_TOUCH) focusModel = PRIMARY_DISPLAY
                else if (idx == ThorInput.DEV_BOTTOM_TOUCH) secondDisplayId()?.let { focusModel = it }
            }
            if (idx == ThorInput.DEV_TOP_TOUCH && type == ThorInput.EV_ABS) { onTopTouch(code, value); return@listener }
            if (idx != InputMonitor.DEV_BOTTOM_TOUCH) return@listener
            if (type == 0) { if (code == 0) onTouchFrame(); return@listener }  // SYN_REPORT
            if (type != 3) return@listener                                     // only EV_ABS below
            val now = android.os.SystemClock.uptimeMillis()
            // A lost lift (e.g. helper restarted mid-touch) would leave stale fingers
            // forever — after 3s of silence, start clean.
            if (now - mtLastEvent > 3000) mtActive.clear()
            mtLastEvent = now
            val s = mtCurSlot.coerceIn(0, 15)
            when (code) {
                47 -> mtCurSlot = value
                57 -> if (value == -1) {
                    mtActive.remove(mtCurSlot)?.let { t -> sessMoveX += t.dx(); sessMoveY += t.dy(); sessLifted++ }
                    if (mtActive.isEmpty()) endGestureSession(now)
                } else {
                    if (mtActive.isEmpty()) {
                        sessStart = now; sessMaxFingers = 0
                        sessMoveX = 0; sessMoveY = 0; sessLifted = 0
                        sessBlankedAtStart = secondDisplayId()?.let { blanker?.isBlanked(it) } == true
                    }
                    mtActive[mtCurSlot] = Touch().apply { x = slotX[s]; y = slotY[s] }
                }
                53 -> { slotX[s] = value; mtActive[mtCurSlot]?.let { it.x = value; if (!it.landing && it.sx < 0) it.sx = value } }
                54 -> { slotY[s] = value; mtActive[mtCurSlot]?.let { it.y = value; if (!it.landing && it.sy < 0) it.sy = value } }
            }
        }
    }

    // ── Controller focus between screens ─────────────────────────────
    // Chord: hold R3, then D-pad Up = top screen, Down = bottom screen (haptic +
    // glass pill on the screen that now has the controller). Holding R3 alone for
    // a moment shows which screen has it right now.
    //
    // How focus moves: Android raises a display (and routes keys/gamepad to it)
    // when a touch lands on a window there that can take keys. We add a tiny,
    // invisible, FOCUSABLE overlay in the target screen's corner, tap IT with an
    // accessibility gesture, then remove it — focus falls to that screen's app,
    // which is never touched. (Off-screen `input tap` tricks only work by
    // accident; AYN's setFocusedMode(int) changes nothing — both verified.)
    // The chord itself (default R3 + ↑/↓) is a binding run by [ButtonEngine].
    private var focusCue: FocusCue? = null
    private var keyboardOverlay: app.wayfinder.keyboard.KeyboardOverlay? = null
    private var inputDeck: app.wayfinder.deck.InputDeckOverlay? = null

    /**
     * The input deck: keys, trackpad and pads for the game, drawn on the OTHER
     * screen from the one that has the controller (the game's). Home + Y by default.
     */
    fun toggleInputDeck() {
        val deck = inputDeck ?: return
        if (deck.isShowing) { deck.hide(); return }
        // The game is where the CONTROLLER is: the lock target when locked (a touch on the
        // other screen doesn't move a locked controller, so it mustn't move the deck either).
        val game = if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()
        val deckDisplay = (if (game == PRIMARY_DISPLAY) secondDisplayId() else PRIMARY_DISPLAY) ?: run {
            focusCue?.show(game, "Keyboard & mouse needs the second screen"); return
        }
        val (pkg, label, dark) = deckContext(game)
        InputMonitor.start(this)   // the root helper carries the keys; make sure it's up
        deck.show(deckDisplay, game, pkg, label, dark,
            onOpenHub = {
                deck.hide()
                startActivity(Intent(this, MainActivity::class.java).putExtra("page", HubPage.KEYBOARD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            },
            onFocusGame = { focusAction(top = game == PRIMARY_DISPLAY) },
        )
    }

    /** 1.3 (GitHub #12): the game's Guide & notes — on the other screen (over a dual-screen game's second
     *  screen too: an overlay draws above it), else a panel beside the game on its own screen (single-screen
     *  devices). The same action closes it. */
    fun openGuide() {
        val deck = inputDeck ?: return
        if (deck.isShowing) { deck.hide(); return }
        val game = if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()
        val other = if (game == PRIMARY_DISPLAY) secondDisplayId() else PRIMARY_DISPLAY
        val (pkg, label, dark) = deckContext(game)
        deck.show(other ?: game, game, pkg, label, dark, onOpenHub = { deck.hide() },
            onFocusGame = { focusAction(top = game == PRIMARY_DISPLAY) }, guideOnly = true, beside = other == null)
    }

    /** For the deck: the app on [display], its name, and whether the deck is dark (the Hub's theme). */
    private fun deckContext(display: Int): Triple<String?, String?, Boolean> {
        // Read the screen's real top app now: the event-tracked map can lag (e.g. an app
        // opened from a launcher alias arrives with no display id).
        val pkg = appOnDisplay(display) ?: displayApps[display]
        val label = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        val dark = when (AppSettings.themeMode) {
            ThemeMode.DARK, ThemeMode.BLACK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        return Triple(pkg, label, dark)
    }

    private fun screenName(displayId: Int) = if (displayId == PRIMARY_DISPLAY) "top screen" else "bottom screen"

    // ── Lock: the controller stays on one screen; touches elsewhere don't move it ──
    // TOP target → AYN's native lock (Settings.System screen_focus_lock=1): it pins the
    //   controller to the top screen at the input-routing level while touch + keyboard
    //   keep working normally on the bottom (verified: buttons reach the top screen
    //   even with input focus on the bottom). It only ever targets the top screen.
    // BOTTOM target → our own lock: when a touch session on the top screen ends,
    //   hand focus back to the bottom (skipped while a keyboard is up on top).
    private fun lockTarget(): Int =
        if (AppSettings.focusLockTop) PRIMARY_DISPLAY else (secondDisplayId() ?: PRIMARY_DISPLAY)

    fun applyFocusLock(announce: Boolean = false) {
        val on = AppSettings.focusLockEnabled
        val top = AppSettings.focusLockTop
        val target = lockTarget()
        fun done() {
            if (announce) {
                vibrateShort()
                if (on) focusCue?.show(target, "Controller locked to the ${screenName(target)}")
                else focusCue?.show(focusedDisplayId(), "Controller unlocked — it follows your touch")
            }
            Log.d(TAG, "focus lock ${if (on) "ON → ${screenName(target)}" else "OFF"}")
        }
        if (on && top) {
            // AYN's native lock misbehaves if armed while input focus is on the BOTTOM
            // screen (verified: the top screen then stops taking controller AND touch
            // focus for several seconds). AYN's own UI always arms it from the top — so
            // do the same: move focus to the top first, then arm it.
            // Verified against the real input state, with retries: arming it from the
            // bottom is exactly the broken case.
            // 1.3: said as soon as the controller is on the top screen — the check and the arming
            // below cost ~140 ms more than a move to the bottom, felt as lag (Reddit)
            var announced = false
            fun armWhenOnTop(attempt: Int) {
                focusDisplay(PRIMARY_DISPLAY) { ok ->
                    if (ok && !announced) { announced = true; focusModel = PRIMARY_DISPLAY; done() }
                    Thread {
                        val real = rootFocusedDisplay()
                        if (real != null && real != PRIMARY_DISPLAY && attempt < 4) {
                            Log.v(TAG, "top lock: focus still on $real (attempt $attempt) — retrying before arming")
                            handler.postDelayed({ armWhenOnTop(attempt + 1) }, 250)
                            return@Thread
                        }
                        if (!(AppSettings.focusLockEnabled && AppSettings.focusLockTop) ||
                            QuickPanelWindow.isOpen || MainActivity.gameControls != null) {
                            Log.v(TAG, "top lock: no longer wanted — not arming"); handler.post { if (!announced) { announced = true; done() } }; return@Thread
                        }
                        PServiceBridge.exec("settings put system screen_focus_lock 1")
                        Log.v(TAG, "top lock armed (input focus was $real, attempt $attempt)")
                        handler.post { focusModel = PRIMARY_DISPLAY; if (!announced) { announced = true; done() } }
                    }.apply { isDaemon = true }.start()
                }
            }
            armWhenOnTop(1)
        } else {
            Thread {
                PServiceBridge.exec("settings put system screen_focus_lock 0")
                handler.post {
                    if (on) focusDisplay(target) { syncFocusModel() }
                    done()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    fun toggleFocusLock() {
        if (AppSettings.focusLockEnabled) AppSettings.setFocusLock(false)
        else AppSettings.setFocusLock(true, top = focusedDisplayId() == PRIMARY_DISPLAY)  // lock where it is now
        applyFocusLock(announce = true)
    }

    // Top-screen touch tracking for the bottom lock.
    private val topSlots: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()   // socket + main threads
    private var topCurSlot = 0
    private val reassertBottomLock = Runnable {
        if (!AppSettings.focusLockEnabled || AppSettings.focusLockTop || topSlots.isNotEmpty() || lockSuspended()) {
            Log.v(TAG, "lock re-assert skipped (enabled=${AppSettings.focusLockEnabled} top=${AppSettings.focusLockTop} fingers=${topSlots.size})")
            return@Runnable
        }
        if (imeVisibleOn(PRIMARY_DISPLAY)) { Log.v(TAG, "lock re-assert skipped: keyboard up on top"); return@Runnable }
        reassertLock(attempt = 1)
    }

    /**
     * Hand focus back to the bottom lock target, verified against the real input
     * state. Android applies the top touch's own focus change asynchronously, so
     * our hand-back can occasionally land BEFORE it and get overridden (seen in
     * testing) — so check, and retry a couple of times.
     */
    private fun reassertLock(attempt: Int) {
        val target = lockTarget()
        focusDisplay(target) { _ ->
            Thread {
                val real = rootFocusedDisplay()
                if (real != null) focusModel = real
                Log.v(TAG, "lock re-assert #$attempt → display $target: input focus now $real")
                if (real != null && real != target && attempt < 3 &&
                    AppSettings.focusLockEnabled && !AppSettings.focusLockTop && topSlots.isEmpty()
                ) handler.postDelayed({ reassertLock(attempt + 1) }, 250)
            }.apply { isDaemon = true }.start()
        }
    }

    private fun imeVisibleOn(displayId: Int): Boolean = try {
        windowsOnAllDisplays.get(displayId)?.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
    } catch (_: Exception) { false }

    private fun onTopTouch(code: Int, value: Int) {
        when (code) {
            ThorInput.ABS_MT_SLOT -> topCurSlot = value
            ThorInput.ABS_MT_TRACKING_ID -> {
                if (value >= 0) { topSlots.add(topCurSlot); handler.removeCallbacks(reassertBottomLock) }
                else {
                    topSlots.remove(topCurSlot)
                    Log.v(TAG, "top touch lift: fingers=${topSlots.size} lock=${AppSettings.focusLockEnabled} top=${AppSettings.focusLockTop}")
                    if (topSlots.isEmpty() && AppSettings.focusLockEnabled && !AppSettings.focusLockTop && !lockSuspended())
                        handler.postDelayed(reassertBottomLock, 300)
                }
            }
        }
    }

    // ── Screenshot ────────────────────────────────────────────────────
    private var screenshotter: Screenshotter? = null

    fun takeScreenshot() {
        val s = screenshotter ?: return
        val ours = currentScreenMode()                          // Wayfinder's own blank (1 bottom off, 2 top off)
        Thread {
            // 1.3 (GitHub #18): "both screens" leaves out a screen that's off — Wayfinder's blank, AYN's own
            // (holding the AYN button: dual_screen_display_mode), or a display that's really off
            var target = AppSettings.shotTarget
            if (target == ShotTarget.BOTH) {
                val second = secondDisplayId()
                val mode = if (ours != 0) ours else if (PServiceBridge.cachedAvailable()) aynScreenMode() else 0
                fun off(id: Int) = getSystemService(DisplayManager::class.java).getDisplay(id)?.state == android.view.Display.STATE_OFF
                val bottomOff = second == null || mode == 1 || off(second)
                val topOff = mode == 2 || off(PRIMARY_DISPLAY)
                target = when { bottomOff && !topOff -> ShotTarget.TOP; topOff && !bottomOff -> ShotTarget.BOTTOM; else -> ShotTarget.BOTH }
            }
            handler.post { shoot(s, target) }
        }.apply { isDaemon = true }.start()
    }

    private fun shoot(s: Screenshotter, target: ShotTarget) {
        s.capture(PRIMARY_DISPLAY, secondDisplayId(), target) { name ->
            val where = if (target == ShotTarget.BOTTOM) (secondDisplayId() ?: PRIMARY_DISPLAY) else PRIMARY_DISPLAY
            if (name != null) { vibrateShort(); focusCue?.show(where, "Screenshot saved in Pictures / Screenshots") }
            else focusCue?.show(where, "The screenshot didn't work")
        }
    }

    /** Stick flicks for Home/Back combos: from the raw pad when the input layer is off (the
     *  stick then still reaches the game — [AnalogShield]), from the events the layer withheld
     *  from the game when it's on. Two detectors: the streams never mix. */
    private val rawFlicks = FlickDetector { b -> buttonEngine?.onFlick(b) }
    private val gatedFlicks = FlickDetector { b -> buttonEngine?.onFlick(b) }

    /** Input layer: a pad event the game did NOT get because Home/Back was held (printed keys). */
    private fun onGatedEvent(type: Int, code: Int, value: Int) {
        when (type) {
            ThorInput.EV_KEY -> if (value != 2) {
                ExtEngine.onGated(code, value)                  // the shift layer (§6l)
                GATED_KEYS[code]?.let { buttonEngine?.onGated(it, value != 0) }
            }
            ThorInput.EV_ABS -> when (code) {
                ThorInput.ABS_HAT_X, ThorInput.ABS_HAT_Y -> buttonEngine?.onHat(code, value)
                ThorInput.ABS_LX, ThorInput.ABS_LY, ThorInput.ABS_RX, ThorInput.ABS_RY -> gatedFlicks.onAbs(code, value)
            }
        }
    }
    private val GATED_KEYS = mapOf(
        ThorInput.BTN_A to ThorButton.A, ThorInput.BTN_B to ThorButton.B, ThorInput.BTN_X to ThorButton.X,
        ThorInput.BTN_Y to ThorButton.Y, ThorInput.BTN_L1 to ThorButton.L1, ThorInput.BTN_R1 to ThorButton.R1,
        ThorInput.BTN_L2 to ThorButton.L2, ThorInput.BTN_R2 to ThorButton.R2, ThorInput.BTN_SELECT to ThorButton.SELECT,
        ThorInput.BTN_START to ThorButton.START, ThorInput.BTN_L3 to ThorButton.L3, ThorInput.BTN_R3 to ThorButton.R3,
    )

    /** The input layer's profile for the app that has the controller (fx/wfmap.h tokens). */
    /** [pkg] = an app, or a game profile's key (`<pkg>#<game>`, see [GameProfiles]). */
    private fun padProfileFor(pkg: String?): String {
        val cfg = pkg?.let { Profiles.get(it) } ?: return ""     // Wayfinder itself: raw (the test page)
        val face = when (cfg.face) { FaceLayout.NINTENDO -> "L=n"; FaceLayout.XBOX -> "L=x"; null -> "" }
        // round 8: the all-games stick / trigger shape where this profile has none of its own
        val d = AppSettings.stickDefaults
        fun merge(own: StickShape, all: StickShape) = StickShape(
            dead = if (own.dead == 0) all.dead else own.dead, full = if (own.full == 100) all.full else own.full,
            curve = if (own.curve == 0) all.curve else own.curve)
        val r = (cfg.remap ?: PadRemap()).let { r ->
            r.copy(stickL = merge(r.stickL, d.stickL), stickR = merge(r.stickR, d.stickR),
                trigStart = if (r.trigRanged) r.trigStart else d.trigStart, trigFull = if (r.trigRanged) r.trigFull else d.trigFull)
        }
        // 1.3: Back goes to the game here → holding Back doesn't withhold the other buttons
        val bg = if (ControlsStore.backToGame(pkg)) "bg=1" else ""
        return listOf(face, r.engineTokens(), bg).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The app that really has the controller (its top window), null for Wayfinder itself. */
    private fun controllerApp(): String? {
        val top = appOnDisplay(controllerDisplay())
        return if (top == packageName) null else top ?: buttonHost.currentApp()
    }

    private fun controllerDisplay(): Int = if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()
    /** Keeps the layer on the current app's profile. A cheap check twice a second is simpler and
     *  sturdier than hooking every way the controller's app can change (focus, swaps, locks). */
    private var gameAskedFor: String? = null
    private var gameTicks = 0
    private var lastProfileKey: String? = null
    private val padProfileTick = object : Runnable {
        override fun run() {
            // The REAL top app of the controller's screen: the tracked one ignores Wayfinder's own
            // windows, so with the Hub open over a game the game's remap applied to the Hub — A
            // became B (= Back) in the remap editor itself (2026-09-24). Wayfinder never gets one.
            val app = controllerApp()
            // which game it runs (per-game profiles): only for an app that HAS game profiles, or while
            // the controls page is open (to offer one) — at once on a switch, then every 4 s. Never
            // for launchers: the root helper scans /proc for this.
            val wantGame = app != null && app !in launcherPackages && app !in ignoredPackages &&
                (GameProfiles.forApp(app).isNotEmpty() || MainActivity.gameControls != null)
            if (!wantGame) { if (gameAskedFor != null) { gameAskedFor = null; GameProfiles.ask(null) } }
            else if (app != gameAskedFor || ++gameTicks >= 8) { gameAskedFor = app; gameTicks = 0; GameProfiles.ask(app) }
            val key = app?.let { GameProfiles.activeKey(it) }
            // the app's own buttons (keys, macros, long press…): nothing held survives a switch — of
            // the profile itself, not only of the engine's tokens (two profiles can share tokens)
            // 1.3: the Wayfinder keyboard open = the controller is the keyboard's (A types, the D-pad moves
            // the cursor): the app's Game controls pause until it closes (web preset: A = click)
            val kbOpen = ThorKeyboardService.isOpen()
            val switched = PadLayerCtl.wanted && PadLayerCtl.setProfile(if (kbOpen) "" else padProfileFor(key))
            val profKey = if (kbOpen) "keyboard" else key
            if (switched || profKey != lastProfileKey) { lastProfileKey = profKey; ExtEngine.releaseAll() }
            ExtEngine.remap = if (kbOpen) null else key?.let { Profiles.get(it).remap }
            // gyro: needs the layer (its stick goes through our copy of the pad, its on/off button too)
            GyroEngine.follow(key, key?.takeIf { PadLayerCtl.wanted }?.let { Profiles.get(it).remap?.gyro })
            handler.postDelayed(this, 500)
        }
    }

    /** Raw controller events (evdev): only the D-pad matters here — keys come via the a11y filter. */
    private fun onControllerEvent(type: Int, code: Int, value: Int) {
        if (type == ThorInput.EV_ABS && !PadLayerCtl.active &&
            (code == ThorInput.ABS_LX || code == ThorInput.ABS_LY || code == ThorInput.ABS_RX || code == ThorInput.ABS_RY)) {
            rawFlicks.onAbs(code, value)
            return
        }
        if (type == ThorInput.EV_ABS && (code == ThorInput.ABS_HAT_X || code == ThorInput.ABS_HAT_Y)) {
            // Only a fresh press browses (the HAT can report the same direction again).
            val freshX = code == ThorInput.ABS_HAT_X && value != 0 && lastHatX == 0
            if (code == ThorInput.ABS_HAT_X) lastHatX = value
            if (recentsDisplay != null && freshX) handler.post {
                val d = recentsDisplay
                if (d != null && recentsStillOpen(d)) recentsBrowse(value) else exitRecents()
            }
            buttonEngine?.onHat(code, value)
        }
    }

    // ── Recents with the controller ─────────────────────────────────────
    // Android's overview ignores the pad, so while it's open we drive it with small
    // touch gestures: ←/→ (or L1/R1) browse, A opens, Y closes the app, Select = Clear
    // all, B leaves. A pill shows the controls.
    @Volatile private var recentsDisplay: Int? = null

    private fun enterRecents(displayId: Int, retry: Boolean = false) {
        // The window list can lag the event a little: check now, and once more shortly after.
        if (!recentsStillOpen(displayId)) {
            if (!retry) handler.postDelayed({ enterRecents(displayId, retry = true) }, 350)
            else Log.d(TAG, "recents: event on $displayId but not on top — ignored")
            return
        }
        Log.d(TAG, "recents: enter on $displayId")
        if (recentsDisplay == displayId) return   // Recents sends its event twice
        // Remember the app we came from (the card Recents opens centred on) for B.
        // Where B goes back to: what was on screen when WE opened Recents (the home screen
        // counts — Recents centres on the last app, so its card was wrong from home);
        // opened some other way → the card Recents centres on.
        val origin = recentsOrigin?.takeIf { android.os.SystemClock.uptimeMillis() - recentsOriginAt < 3000 }
        recentsOrigin = null
        recentsFrom = when {
            origin == HOME_ORIGIN -> HOME_ORIGIN
            origin != null -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(origin, 0)).toString() }.getOrNull()
            else -> null
        }
        if (recentsFrom == null) handler.postDelayed({ if (recentsFrom == null) recentsFrom = centredCard(displayId)?.contentDescription?.toString() }, 300)
        browseBusy = false; browseQueued = 0
        recentsDisplay = displayId
        // A few seconds is enough (it covers the card's app icon).
        fun m(b: ThorButton) = ButtonEngine.menuSwap(b).label   // the printed button for that meaning
        // 1.3 (GitHub #13): full, compact (one slim line of symbols, no title) or off; top centre or a corner
        when (AppSettings.recentsHint) {
            2 -> {}
            1 -> focusCue?.showTable(displayId, emptyList(), listOf(
                "◀ ▶" to "", m(ThorButton.A) to "↗", m(ThorButton.Y) to "✕", "Select" to "✕✕", "Start" to "⌂", m(ThorButton.B) to "↩"),
                5000, at = AppSettings.recentsHintAt, compact = true)
            else -> focusCue?.showTable(displayId, listOf("Recent apps"), listOf(
                "D-pad left / right" to "browse", m(ThorButton.A) to "open", m(ThorButton.Y) to "close the app",
                "Select" to "close all", "Start" to "home", m(ThorButton.B) to "back"), 5000, at = AppSettings.recentsHintAt)
        }
    }

    private fun exitRecents() {
        if (recentsDisplay == null) return
        Log.d(TAG, "recents: exit")
        recentsDisplay = null
        focusCue?.hideHint()
    }

    private fun recentsSize(d: Int): Pair<Float, Float>? {
        val disp = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(d) ?: return null
        val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") disp.getRealMetrics(m)
        return m.widthPixels.toFloat() to m.heightPixels.toFloat()
    }

    private fun recentsGesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val d = recentsDisplay ?: return
        val (w, h) = recentsSize(d) ?: return
        val path = android.graphics.Path().apply { moveTo(x1 * w, y1 * h); lineTo(x2 * w, y2 * h) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, ms))
            .setDisplayId(d).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { Log.w(TAG, "recents gesture cancelled") }
        }, null)
        Log.d(TAG, "recents gesture d=$d ($x1,$y1)→($x2,$y2) sent=$sent")
    }

    /**
     * Press one of Recents' own buttons by its view id (then its label), not by position:
     * the button row changes with the apps open — a fixed spot once hit "Screenshot"
     * instead of "Clear all" (2026-09-23).
     */
    private fun clickRecentsButton(viewId: String, label: String): Boolean {
        val d = recentsDisplay ?: return false
        val wins = runCatching { windowsOnAllDisplays.get(d) }.getOrNull().orEmpty()
        for (w in wins) {
            val root = w.root ?: continue
            val node = root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
                ?: root.findAccessibilityNodeInfosByText(label).firstOrNull { it.text?.toString().equals(label, ignoreCase = true) }
                ?: continue
            var n: android.view.accessibility.AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        Log.w(TAG, "recents: no \"$label\" button on display $d")
        return false
    }

    /**
     * Recents is the TOP app window of display [d]. (Our Hub is see-through, so Recents
     * can still be "visible" underneath it — that must not count.)
     */
    private fun recentsStillOpen(d: Int): Boolean {
        val top = runCatching { windowsOnAllDisplays.get(d) }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer } ?: return false
        return top.root?.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/overview_panel")?.isNotEmpty() == true
    }

    /** The app cards in Recents (each card is a clickable view named after its app). */
    private fun recentsCards(d: Int): List<android.view.accessibility.AccessibilityNodeInfo> {
        val panel = runCatching { windowsOnAllDisplays.get(d) }.getOrNull().orEmpty().firstNotNullOfOrNull { w ->
            w.root?.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/overview_panel")?.firstOrNull()
        } ?: return emptyList()
        return (0 until panel.childCount).mapNotNull { panel.getChild(it) }
            .filter { it.isClickable && !it.contentDescription.isNullOrEmpty() }
    }

    private fun centredCard(d: Int): android.view.accessibility.AccessibilityNodeInfo? {
        val cx = (recentsSize(d)?.first ?: return null) / 2
        val r = android.graphics.Rect()
        return recentsCards(d).firstOrNull { it.getBoundsInScreen(r); cx >= r.left && cx <= r.right }
    }

    /** B: back to the app we came from (Recents' own Back goes to the home screen). */
    @Volatile private var recentsFrom: String? = null
    @Volatile private var recentsOrigin: String? = null
    @Volatile private var recentsOriginAt = 0L
    private val HOME_ORIGIN = "<home screen>"   // never a package or app name

    /** Our RECENTS action: note what's on the screen being left. */
    private fun openRecents() {
        val d = if (AppSettings.focusLockEnabled || AppSettings.focusSticky) lockTarget() else focusedDisplayId()
        recentsOrigin = displayApps[d] ?: HOME_ORIGIN
        recentsOriginAt = android.os.SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    @Volatile private var lastHatX = 0

    private fun recentsGoBack() {
        val d = recentsDisplay
        val from = recentsFrom
        val card = if (d != null && from != null) recentsCards(d).firstOrNull { it.contentDescription?.toString() == from } else null
        exitRecents()
        if (card?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != true)
            performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** dir -1 = older (cards to the left), +1 = newer. One quick fling = one card. */
    // Browsing = a drag of exactly one card that stops before lifting, so Recents snaps to
    // the neighbour. (Flings carried momentum: one could fall short — "stuck" — and one sent
    // mid-animation stacked up and skipped several. Recents refuses a11y scroll actions.)
    // Presses that arrive while a drag runs are queued, never overlapped.
    private var browseBusy = false
    private var browseQueued = 0

    private fun recentsBrowse(dir: Int) {
        if (browseBusy) { browseQueued = (browseQueued + dir).coerceIn(-3, 3); return }
        val d = recentsDisplay ?: return
        val (w, h) = recentsSize(d) ?: return
        val panel = runCatching { windowsOnAllDisplays.get(d) }.getOrNull().orEmpty().firstNotNullOfOrNull { win ->
            win.root?.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/overview_panel")?.firstOrNull()
        }
        // Already at that end? (the list only offers the scroll directions it can go)
        val ids = panel?.actionList?.map { it.id }.orEmpty()
        val can = if (dir < 0) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (ids.isNotEmpty() && can !in ids) { browseQueued = 0; return }
        // One card = the centred card's width + the gap (≈3.3 %).
        val r = android.graphics.Rect()
        val step = centredCard(d)?.let { it.getBoundsInScreen(r); r.width() * 1.033f }?.takeIf { it > 50 } ?: (w * 0.60f)
        val y = h * 0.48f
        // Older cards are on the left: to go left the finger drags right, and vice versa.
        val x1 = w / 2 + dir * step / 2
        val end = w / 2 - dir * step / 2
        val move = android.accessibilityservice.GestureDescription.StrokeDescription(
            android.graphics.Path().apply { moveTo(x1, y); lineTo(end, y) }, 0, 220, true)
        browseBusy = true
        fun finish() {
            browseBusy = false
            val q = browseQueued
            if (q != 0) { browseQueued = q - Integer.signum(q); handler.postDelayed({ recentsBrowse(Integer.signum(q)) }, 60) }
        }
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                // Hold still, then lift: no release velocity → it snaps to the nearest card.
                val hold = move.continueStroke(android.graphics.Path().apply { moveTo(end, y); lineTo(end + 1, y) }, 0, 140, false)
                val sent = dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(hold).setDisplayId(d).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { handler.postDelayed({ finish() }, 180) }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { finish() }
                    }, handler)
                if (!sent) finish()
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { finish() }
        }
        if (!dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(move).setDisplayId(d).build(), cb, handler)) finish()
        Log.d(TAG, "recents browse $dir: drag ${step.toInt()} px")
    }

    private val recentsDowns = HashSet<ThorButton>()

    /** Buttons while Recents is open (swallowed so nothing else reacts). A release is swallowed
     *  only if its press was: a Back released just after Recents opened used to be eaten, and
     *  the button engine then thought Back was still held (a phantom swap 1 s later, Home dead
     *  — review 2026-09-25). */
    private fun recentsKey(event: KeyEvent): Boolean {
        val b0 = ButtonEngine.menuButton(event)   // Xbox style: the bottom button opens (GitHub #8)
        if (event.action == KeyEvent.ACTION_UP && b0 != null && recentsDowns.remove(b0)) return true
        val d = recentsDisplay ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !recentsStillOpen(d)) { exitRecents(); return false }
        val b = b0 ?: return false
        if (b == ThorButton.HOME) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return b in recentsDowns
        recentsDowns.add(b)
        when (b) {
            ThorButton.A -> {                                                          // open the middle card
                val d = recentsDisplay
                if (d == null || centredCard(d)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != true)
                    recentsGesture(0.50f, 0.48f, 0.50f, 0.48f, 40)
            }
            ThorButton.Y, ThorButton.X -> recentsGesture(0.50f, 0.50f, 0.50f, 0.04f, 160)   // swipe it away
            ThorButton.L1 -> recentsBrowse(-1)
            ThorButton.R1 -> recentsBrowse(1)
            ThorButton.SELECT -> clickRecentsButton("com.android.launcher3:id/clear_all_button", "Clear all")
            ThorButton.START -> { exitRecents(); performGlobalAction(GLOBAL_ACTION_HOME) }
            ThorButton.B, ThorButton.BACK -> recentsGoBack()
            else -> {}
        }
        return true
    }

    /** Focus actions: move the controller — or, while locked, move the lock. */
    private fun focusAction(top: Boolean) {
        // Locked — or "stays where you send it": the move sets (or moves) the lock, so a
        // touch on the other screen can't pull the controller back.
        if (AppSettings.focusLockEnabled || AppSettings.focusSticky) { AppSettings.setFocusLock(true, top = top); applyFocusLock(announce = true) }
        else switchControllerFocus(if (top) PRIMARY_DISPLAY else (secondDisplayId() ?: return))
    }

    // Which display has input focus. The a11y window list can't tell (it flags a
    // focused window on EVERY display), so we model it the way Android decides
    // it: a touch on a screen moves focus there; so does our switch. The model is
    // seeded and verified from `dumpsys input` through root, off the main thread.
    @Volatile private var focusModel = PRIMARY_DISPLAY

    private fun focusedDisplayId(): Int = focusModel

    private fun rootFocusedDisplay(): Int? =
        PServiceBridge.exec("dumpsys input | grep -m1 FocusedDisplayId")
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    private fun syncFocusModel() = Thread {
        rootFocusedDisplay()?.let { focusModel = it }
    }.apply { isDaemon = true }.start()

    fun switchControllerFocus(target: Int) {
        if (focusedDisplayId() == target) {
            // The model can be stale (e.g. an app launched on the other screen took focus
            // without a touch) — confirm with the real input state before skipping.
            Thread {
                val real = rootFocusedDisplay()
                handler.post {
                    if (real == null || real == target) {
                        if (real != null) focusModel = real
                        vibrateShort()
                        focusCue?.show(target, "The controller is already on the ${screenName(target)}")
                    } else { focusModel = real; switchControllerFocus(target) }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        focusDisplay(target) { _ ->
            // Verify against the real input state (root), then update the model.
            Thread {
                val real = rootFocusedDisplay()
                if (real != null) focusModel = real
                val ok = (real ?: target) == target
                Log.d(TAG, "controller focus → display $target: ${if (ok) "OK" else "FAILED"} (input focus now $real)")
                handler.post {
                    if (ok) { vibrateShort(); focusCue?.show(target, "Controller on the ${screenName(target)}") }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** Raise [displayId] to input focus without touching its app (see block comment). */
    fun focusDisplay(displayId: Int, onDone: (Boolean) -> Unit = {}) {
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return onDone(false)
        val wm = createDisplayContext(display).getSystemService(android.view.WindowManager::class.java)
        val probe = android.view.View(this)
        val lp = android.view.WindowManager.LayoutParams(
            6, 6, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FOCUSABLE on purpose (no FLAG_NOT_FOCUSABLE): only a window that can
            // take keys makes a touch move display focus.
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = 0; y = 0 }
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.postDelayed({
                try { wm.removeViewImmediate(probe) } catch (_: Exception) {}
                handler.postDelayed({ onDone(ok) }, 60)
            }, 40)
        }
        try { wm.addView(probe, lp) } catch (e: Exception) { Log.w(TAG, "focus probe: ${e.message}"); return onDone(false) }
        // safety net OUTSIDE the probe's own queue: if the window never attaches (screens off, no
        // frame) the probe would otherwise stay on screen and onDone never come
        handler.postDelayed({ finish(false) }, 1500)
        probe.post {
            val path = android.graphics.Path().apply { moveTo(2f, 2f) }
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 20))
                .setDisplayId(displayId)
                .build()
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(true)
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(false)
            }, handler)
            if (!sent) finish(false)
            handler.postDelayed({ finish(false) }, 800)   // safety net
        }
    }

    /**
     * If AYN's own long-press blank has the bottom screen off, wake it (mode 0);
     * otherwise run [orElse]. Off the input thread — it's a root round-trip.
     */
    private fun wakeAynOr(orElse: () -> Unit) {
        Thread {
            if (PServiceBridge.cachedAvailable() && aynScreenMode() == 1) {
                Log.d(TAG, "gesture: AYN bottom-off active → AYN mode 0")
                aynBothScreensOn()
            } else orElse()
        }.apply { isDaemon = true }.start()
    }

    /** End of a consistent input frame: fix landing fingers' start positions. */
    private fun onTouchFrame() {
        for (t in mtActive.values) if (t.landing) { t.sx = t.x; t.sy = t.y; t.landing = false }
        if (mtActive.size > sessMaxFingers) sessMaxFingers = mtActive.size
    }

    private fun endGestureSession(now: Long) {
        if (sessMaxFingers < 3) { Log.v(TAG, "gesture end: $sessMaxFingers finger(s)"); return }
        val second = secondDisplayId() ?: return
        val b = blanker ?: return
        // If the screen was blanked when the gesture began and the overlay's own
        // tap-to-wake already woke it, the gesture is spent — don't re-blank.
        val stillBlanked = b.isBlanked(second)
        if (sessBlankedAtStart && !stillBlanked) return
        val dur = now - sessStart
        val n = sessLifted.coerceAtLeast(1)
        val screenDx = sessMoveY.toFloat() / n        // screen-X = raw Y
        val screenDy = -sessMoveX.toFloat() / n       // screen-Y = 1080 − raw X
        val dist = kotlin.math.hypot(screenDx, screenDy)
        Log.v(TAG, "gesture end: mode=${AppSettings.gestureBlankMode} dur=$dur dx=${screenDx.toInt()} dy=${screenDy.toInt()} blankedAtStart=$sessBlankedAtStart still=$stillBlanked")
        when (AppSettings.gestureBlankMode) {
            BlankGesture.OFF -> {}
            BlankGesture.TAP -> if (dur < 600 && dist < 60f) {
                Log.d(TAG, "3-finger TAP (${dur}ms, ${dist.toInt()}px) → ${if (stillBlanked) "wake" else "blank/AYN-check"} 2nd screen")
                if (stillBlanked) handler.post { b.wake(second) }
                else wakeAynOr { handler.post { vibrateShort(); b.blank(second) } }
            }
            BlankGesture.SWIPE -> if (kotlin.math.abs(screenDy) > 150f &&
                kotlin.math.abs(screenDy) > 1.5f * kotlin.math.abs(screenDx)
            ) {
                if (screenDy > 0 && !stillBlanked) {
                    Log.d(TAG, "3-finger SWIPE DOWN (${screenDy.toInt()}px) → blank 2nd screen")
                    handler.post { vibrateShort(); b.blank(second) }
                } else if (screenDy < 0 && stillBlanked) {
                    Log.d(TAG, "3-finger SWIPE UP (${screenDy.toInt()}px) → wake 2nd screen")
                    handler.post { b.wake(second) }
                } else if (screenDy < 0) {
                    wakeAynOr { }   // not blanked by us — maybe by AYN's long-press
                }
            }
        }
    }

    // ── Per-app 2nd-screen policy ────────────────────────────────────
    // Applied when the app on the TOP screen changes (an app on the bottom screen
    // never blanks itself). Manual toggles and the blank gesture still override at
    // any time; the policy only re-applies on the next top-app change.
    @Volatile private var policyTopApp: String? = null
    @Volatile private var policyBlanked = false   // 2nd screen blanked BY a policy

    private fun topAppPolicy(): SecondScreenPolicy =
        displayApps[PRIMARY_DISPLAY]?.let { GameProfiles.effective(it).second } ?: SecondScreenPolicy.DEFAULT   // 1.3: per game too

    // ── Stick lights: follow the app that has the controller ─────────────
    private var lightsApp: String? = "(unset)"  // never a package name: forces the first apply

    fun applyLights(force: Boolean = false) {
        val app = displayApps[if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()]
            ?: displayApps[PRIMARY_DISPLAY]
        if (!force && app == lightsApp) return
        lightsApp = app
        val p = app?.let { GameProfiles.effective(it).lights } ?: LightSettings.global
        StickLights.apply(this, p)
    }

    @Volatile private var screenOn = true

    /** No stick-light animation while the Thor sleeps. */
    private val screenPowerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    InputMonitor.send("S 0")   // the root helper rests too (GitHub #40)
                    QuickPanelWindow.close()
                    app.wayfinder.lights.StickLights.pause(); SleepEngine.onScreenOff(); GyroEngine.setScreenOn(false)
                    // the root helper's samplers (screen colour ~12/s, frame rate) sleep too
                    if (ambientPhys != null) { InputMonitor.send("A -"); ambientPhys = null }
                    if (fpsWatching != null) { InputMonitor.send("F - -"); fpsWatching = null }
                    handler.removeCallbacks(padProfileTick)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    InputMonitor.send("S 1")
                    NavBarGuard.poke()   // 1.3 (GitHub #23)
                    app.wayfinder.lights.StickLights.resume(this@ForegroundAppService); SleepEngine.onScreenOn(); GyroEngine.setScreenOn(true)
                    applyFps()     // the lights ask for the screen colour again themselves
                    handler.removeCallbacks(padProfileTick); handler.post(padProfileTick)
                }
            }
        }
    }

    /** (whole screen, left half, right half) — 1.3: each stick its side (GitHub #15). */
    @Volatile private var screenColor: IntArray? = null
    @Volatile private var sampling = false

    /**
     * Average colour of the controller's screen. From the root helper's [AmbientSampler]
     * (~12/s, raw screencap) when it runs; else the accessibility screenshot (~3/s, a
     * frame late — the rings lagged).
     */
    @Volatile private var ambientPhys: String? = null
    @Volatile private var ambientColor: IntArray? = null
    @Volatile private var ambientAt = 0L
    /** The screen the stick lights take their colour from (1.3, GitHub #33: can be fixed to one screen). */
    private fun screenColorDisplay(): Int = when (app.wayfinder.lights.StickLights.screenFrom) {
        1 -> PRIMARY_DISPLAY
        2 -> secondDisplayId() ?: focusedDisplayId()
        else -> focusedDisplayId()
    }

    private fun requestScreenColor(): IntArray? {
        val phys = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(screenColorDisplay())?.let { ScreenCapture.physicalId(it) }
        if (Shell.isPhysId(phys) && phys != ambientPhys && InputMonitor.send("A $phys")) {
            ambientPhys = phys
            InputMonitor.ambientListener = { c, l, r -> ambientColor = intArrayOf(c, l, r); ambientAt = android.os.SystemClock.uptimeMillis() }
        }
        if (android.os.SystemClock.uptimeMillis() - ambientAt < 1000) return ambientColor
        if (!sampling) { sampling = true; handler.post { sampleScreenColor() } }
        return screenColor
    }

    private fun sampleScreenColor() {
        try {
            takeScreenshot(screenColorDisplay(), mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        // A tiny copy is plenty for an average (and cheap to read back).
                        val small = hw?.let { android.graphics.Bitmap.createScaledBitmap(it, 32, 18, true) }
                            ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        if (small != null) {
                            fun avg(x0: Int, x1: Int): Int {
                                var rr = 0L; var gg = 0L; var bb = 0L
                                for (y in 0 until small.height) for (x in x0 until x1) {
                                    val c = small.getPixel(x, y)
                                    rr += android.graphics.Color.red(c); gg += android.graphics.Color.green(c); bb += android.graphics.Color.blue(c)
                                }
                                val n = (x1 - x0) * small.height
                                return android.graphics.Color.rgb((rr / n).toInt(), (gg / n).toInt(), (bb / n).toInt())
                            }
                            screenColor = intArrayOf(avg(0, small.width), avg(0, small.width / 2), avg(small.width / 2, small.width))
                        }
                    } catch (e: Exception) { Log.w(TAG, "screen colour: ${e.message}") }
                    finally { r.hardwareBuffer.close(); sampling = false }
                }
                override fun onFailure(errorCode: Int) { sampling = false }
            })
        } catch (e: Exception) { sampling = false }
    }

    private fun onTopAppMaybeChanged() {
        if (swapInProgress) return   // displayApps churns mid-swap; the watcher catches up
        QuickPanelWindow.displayId?.let { d -> if (displayApps[d] != panelOver && displayApps[d] != null) QuickPanelWindow.close() }
        NavBarGuard.poke()           // 1.3 (GitHub #23): a navigation bar that came back although hidden
        if (!restoringLayout) Layouts.recordLast(displayApps[PRIMARY_DISPLAY], secondDisplayId()?.let { displayApps[it] })
        maybeOpenCompanion()
        applyPerf()
        applyDnd()
        applyFps()
        applyLights()
        // 1.3 (GitHub #25): the game inside an emulator counts — detected a moment later, it re-applies
        val top = displayApps[PRIMARY_DISPLAY]?.let { GameProfiles.activeKey(it) }
        if (top == policyTopApp) return
        policyTopApp = top
        val second = secondDisplayId() ?: return
        val b = blanker ?: return
        when (val p = topAppPolicy()) {
            SecondScreenPolicy.BLANK -> if (!b.isBlanked(second)) {
                Log.d(TAG, "policy: $top → BLANK 2nd screen")
                b.blank(second); policyBlanked = true
            }
            SecondScreenPolicy.KEEP_ON -> {
                if (b.isBlanked(second)) { Log.d(TAG, "policy: $top → KEEP_ON, waking 2nd screen"); b.wake(second) }
                policyBlanked = false; idleBlanked = false
            }
            SecondScreenPolicy.DEFAULT -> if (policyBlanked) {
                Log.d(TAG, "policy: $top → DEFAULT, restoring 2nd screen")
                if (b.isBlanked(second)) b.wake(second)
                policyBlanked = false
            }
        }
    }

    /** Re-apply after the user edits the policy of the app that's on top right now. */
    fun reapplyTopAppPolicy() = handler.post { policyTopApp = null; onTopAppMaybeChanged() }

    private fun checkIdle() {
        onTopAppMaybeChanged()  // backstop for events missed mid-swap
        val second = secondDisplayId() ?: return
        val b = blanker ?: return
        // An app that asked to keep the 2nd screen on suspends the idle timer.
        if (topAppPolicy() == SecondScreenPolicy.KEEP_ON) return
        if (!AppSettings.idleBlankEnabled) {
            if (idleBlanked) { b.wake(second); idleBlanked = false }
            return
        }
        // typing with the controller on the bottom-screen keyboard / deck: no touches, but in use
        // (the black cover went over the keyboard — review 2026-09-25)
        if (keyboardOverlay?.displayId == second || inputDeck?.isShowing == true) return
        // played with the controller (a game swapped there, the quick panel, the Hub): no touches either
        if (controllerDisplay() == second) return
        // Raw evdev last-touch on the bottom screen (event5). 0 = no data yet
        // (helper not up) — never blank blindly in that case.
        val last = InputMonitor.lastEventUptime(InputMonitor.DEV_BOTTOM_TOUCH)
        if (last == 0L) return
        val idleMs = android.os.SystemClock.uptimeMillis() - last
        val threshold = AppSettings.idleBlankSeconds * 1000L
        if (!b.isBlanked(second)) {
            if (idleMs >= threshold) {
                Log.d(TAG, "idle ${idleMs}ms ≥ ${threshold}ms → auto-blank display $second")
                b.blank(second); idleBlanked = true
            } else idleBlanked = false
        } else if (idleBlanked && idleMs < threshold) {
            // A touch arrived after our auto-blank (backstop; the overlay's own
            // tap-to-wake usually beats us to it) → wake.
            Log.d(TAG, "touch after auto-blank → wake display $second")
            b.wake(second); idleBlanked = false
        }
    }

    /** 1.3 (GitHub #17): AYN's virtual mouse on / off — its own setting (the Keyboard & mouse deck has the
     *  same switch); once on, clicking a stick makes it the pointer. */
    /** 1.3: the game's gyro paused / back on — the deck's chip, as an action. */
    private fun toggleGyro() {
        val where = controllerDisplay()
        if (GyroEngine.configuredForCurrent == null) {
            focusCue?.show(where, "No gyro for this game — set it up in Game controls (Home + X), then Gyro", 3000); return
        }
        val off = !GyroEngine.pausedByUser
        GyroEngine.setPausedByUser(off)
        focusCue?.show(where, if (off) "Gyro off" else "Gyro on", 1500)
    }

    private fun toggleAynMouse() = Thread {
        val on = runCatching { android.provider.Settings.System.getInt(contentResolver, "global_gamepad_to_mouse_mode", 0) == 1 }.getOrDefault(false)
        PServiceBridge.exec("settings put system global_gamepad_to_mouse_mode ${if (on) 0 else 1}")
        handler.post {
            vibrateShort()
            focusCue?.show(controllerDisplay(), if (on) "Mouse mode off" else "Mouse mode on — click L3 or R3: that stick is the pointer", 2500)
        }
    }.apply { isDaemon = true }.start()

    /** 1.3: sleep like the power button — through the root bridge, else Android's lock action
     *  (which also turns the screens off; for devices without AYN's bridge). */
    private fun sleepNow() = Thread {
        if (PServiceBridge.isAvailable()) PServiceBridge.exec("input keyevent KEYCODE_SLEEP")
        else handler.post { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
    }.apply { isDaemon = true }.start()

    // ── AYN's native screen mode ─────────────────────────────────────────
    // Long-press on the AYN button makes AYN's assistant blank a screen with its
    // own touch-swallowing overlay ("primaryScreenTopLayout"), recorded in
    // Settings.System `dual_screen_display_mode`: 0 both on, 1 bottom off, 2 top
    // off. Apps can't read that non-public key, so we go through root. Waking via
    // Wayfinder resets it to 0, so our gesture/toggle also undo AYN's blank.
    private fun aynScreenMode(): Int =
        PServiceBridge.exec("settings get system dual_screen_display_mode")?.trim()?.toIntOrNull() ?: 0

    private fun aynBothScreensOn() { PServiceBridge.exec("settings put system dual_screen_display_mode 0") }

    /** Blank/wake the second (non-primary) screen — OLED black overlay, tap to wake. */
    private fun toggleSecondScreen(): Boolean {
        val b = blanker ?: DisplayBlanker(this).also { blanker = it }
        refreshDisplays()
        val second = availableDisplayIds.firstOrNull { it != PRIMARY_DISPLAY }
            ?: return false.also { Log.w(TAG, "No second display to blank") }
        vibrateShort()
        // If AYN's own long-press blank is active, "toggle" means wake it.
        Thread {
            if (!b.isBlanked(second) && PServiceBridge.cachedAvailable() && aynScreenMode() == 1) {
                Log.d(TAG, "AYN bottom-off mode active → waking via AYN mode 0")
                aynBothScreensOn()
            } else handler.post { b.toggle(second) }
        }.apply { isDaemon = true }.start()
        return true
    }

    /** Keep the second screen awake / allow it to sleep again. */
    /**
     * "Stay awake": the screens don't time out. Both screens share ONE power state, so
     * this is the whole Thor. A screen wake lock — the old 1-px FLAG_KEEP_SCREEN_ON overlay
     * was ignored by Android (measured 2026-09-23: asleep after the timeout anyway; it only
     * looked fine on the charger with "Stay awake while charging" on).
     */
    private var stayAwake: PowerManager.WakeLock? = null
    private fun setStayAwake(on: Boolean) {
        if (on == (stayAwake?.isHeld == true)) return
        if (on) {
            @Suppress("DEPRECATION")
            stayAwake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "Wayfinder:stay-awake")
                .apply { setReferenceCounted(false); acquire() }
        } else { runCatching { stayAwake?.release() }; stayAwake = null }
        Log.d(TAG, "stay awake ${if (on) "ON" else "OFF"}")
    }

    private fun toggleKeepAwakeSecond(): Boolean {
        vibrateShort()
        val on = stayAwake?.isHeld != true
        setStayAwake(on)
        AppSettings.rememberKeepBottom(on)
        return true
    }

    // Run on background thread so Thread.sleep doesn't block the main thread.
    // Trampoline activities need the main thread free to execute their onCreate.
    private fun startSwapInBackground(): Boolean {
        if (!swapBusy.compareAndSet(false, true)) {
            Log.w(TAG, "Swap already in progress — ignoring")
            return false
        }
        Thread {
            try {
                performSwapOrSend()
            } catch (e: Exception) {
                // An uncaught exception on this thread would kill the whole
                // process (and the accessibility service with it).
                Log.e(TAG, "Swap failed: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                swapBusy.set(false)
            }
        }.start()
        return true
    }
    private val clearBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun startClearInBackground(): Boolean {
        if (!clearBusy.compareAndSet(false, true)) { Log.w(TAG, "Clear already in progress"); return false }
        vibrateShort()
        Thread {
            try { clearInProgress = true; clearBackgroundApps() }
            catch (e: Exception) { Log.e(TAG, "Clear failed: ${e.message}", e) }
            finally { clearInProgress = false; clearBusy.set(false) }
        }.apply { isDaemon = true }.start()
        return true
    }

    // Remove background app tasks like Recents' "Clear all", but keep the
    // launcher, this app, and whatever is currently on each screen.
    private fun clearBackgroundApps() {
        scanAllDisplayApps()
        val keep = HashSet<String>().apply {
            addAll(ignoredPackages)      // systemui, self, odin assistants, launchers
            addAll(launcherPackages)
            add(packageName)
            addAll(displayApps.values)   // apps currently shown on the two screens
        }
        val csv = keep.joinToString(",")
        Log.d(TAG, "Clear keep=[$csv] (${csv.length} chars)")
        fun run() = when {
            PServiceBridge.isAvailable() -> PServiceBridge.runEntryPoint(this, "RecentsTool", csv)
            RootHelper.isAvailable() -> RootHelper.runEntryPoint(this, "RecentsTool", csv)
            else -> null
        }
        // One retry: a missing answer isn't a refusal (the bridge can be busy).
        val out = run()?.takeIf { it.startsWith("OK") || it.startsWith("ERR") }
            ?: run { Thread.sleep(400); run() }
        if (out?.contains("OK") == true) {
            Log.i(TAG, "Clear background apps: $out")
        } else {
            // No privileged backend: best-effort background-process kill (weak; a
            // no-op against other apps on Android 14+, but harmless).
            if (BuildConfig.DEBUG) Log.w(TAG, "Clear via backend failed ($out); best-effort fallback")
            try {
                val am = getSystemService(ActivityManager::class.java)
                am.runningAppProcesses?.forEach { p ->
                    p.pkgList?.forEach { pkg -> if (pkg !in keep) am.killBackgroundProcesses(pkg) }
                }
            } catch (e: Exception) { Log.d(TAG, "fallback clear: ${e.message}") }
        }
    }

    // Self is added in onServiceConnected via packageName (survives applicationId
    // suffixes, e.g. debug builds)
    /** The keyboard app in use (Gboard…): its window must never become "the app on the screen" —
     *  per-app combos off, perf, lights and DND followed it (review 2026-09-25). Cached 5 s. */
    private var imeCache: Pair<Long, String?> = 0L to null
    private fun currentImePackage(): String? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - imeCache.first > 5000) imeCache = now to runCatching {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        return imeCache.second
    }
    private val ignoredPackages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf(
        "com.android.systemui",
        "com.odin.gameassistant",
        "com.odin.dualscreen.assistant",
        // Dialogs shown INSIDE an app's task (permission prompts, choosers): the app
        // underneath is still the one on that screen (they broke Swap/Send and pairs).
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "android",
    )) }
    private val DIALOG_PACKAGES = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "android")
    private val launcherPackages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ── lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        // THIS is why back button wasn't intercepted before:
        // canRequestFilterKeyEvents in XML sets the CAPABILITY but the
        // FLAG must also be enabled for the system to deliver key events.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED   // + removals: see [reconcileGone]
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        ignoredPackages.add(packageName)
        blanker = DisplayBlanker(this)
        focusCue?.dismissAll()                         // a reconnect must not orphan the old cue's pills
        focusCue = FocusCue(this)
        keyboardOverlay = app.wayfinder.keyboard.KeyboardOverlay(this)
        inputDeck = app.wayfinder.deck.InputDeckOverlay(this)
        app.wayfinder.lights.LightSettings.init(this)
        app.wayfinder.lights.StickLights.screenSampler = { requestScreenColor() }
        app.wayfinder.lights.StickLights.screenStop = { if (ambientPhys != null) { InputMonitor.send("A -"); ambientPhys = null } }
        // 1.3: a launcher / frontend installed while Wayfinder runs is known at once
        runCatching {
            registerReceiver(packagesReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            })
        }
        registerReceiver(screenPowerReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
        handler.postDelayed({ applyLights(force = true) }, 1500)
        screenshotter = Screenshotter(this)
        AppSettings.init(this)
        ControlsStore.init(this)
        buttonEngine = ButtonEngine(buttonHost)  // settings available even if the UI was never opened
        AppConfigStore.init(this)
        LinkedVolume.start(this)                        // Volume keys move both screens
        Layouts.init(this)                              // BEFORE anything records a layout
        PerfProfiles.restoreIfLeftOver(this)            // Undo an override left by a crash
        PerfProfiles.keepCustomFan(this)                // 1.3: a Custom fan survives performance changes
        ButtonNames.init(this)                          // 1.3 (GitHub #27)
        SleepEngine.start(this)                         // Sleep & standby
        SpeakerTune.start(this)                         // speaker fix (replaces the external DSP)
        // "Keep the bottom screen on" survives restarts (the holder overlay doesn't).
        if (AppSettings.keepBottomOn) setStayAwake(true)
        maybeRestoreLayoutAtBoot()
        // Probe privileged backends so they're ready even if the UI was never opened
        PServiceBridge.probeAsync()
        RootHelper.probeAsync()
        // Global raw-input monitor (root evdev) → touch-idle, gestures, chords
        PadLayerCtl.init(this)
        PadLayerCtl.onEmergencyOff = {
            handler.post { focusCue?.show(focusedDisplayId(), "Input layer off (Home + Back) — turn it back on in Wayfinder, then Controller") }
        }
        PadLayerCtl.onLayerFailed = { why ->
            handler.post { focusCue?.show(focusedDisplayId(), "Input layer couldn't start ($why) — your controller works as usual. Try again in Wayfinder, then Controller", 6000) }
        }
        InputMonitor.gatedListener = { t, c, v -> onGatedEvent(t, c, v) }
        InputMonitor.extListener = { c, v -> ExtEngine.onExt(c, v) }
        InputMonitor.stickListener = { s, x, y -> ExtEngine.onStick(s, x, y) }
        ExtEngine.display = { controllerDisplay() }
        ExtEngine.perform = { a -> Companion.perform(a) }
        GyroEngine.init(this)
        GameProfiles.init(this)
        // A full restore restarted Wayfinder (Backup.restartApp): back to where the user was
        if (Backup.reopenAfterRestore(this)) handler.postDelayed({
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page", HubPage.HELP)) }
            focusCue?.show(PRIMARY_DISPLAY, "Backup restored", 2500)
        }, 1500)
        InputMonitor.onHelperConnected = {
            handler.post { fpsWatching = null; ambientPhys = null; applyFps(); applyLights(force = true) }
            InputMonitor.send(if (screenOn) "S 1" else "S 0")
            if (PadLayerCtl.wanted) PadLayerCtl.apply(this)
        }
        // The helper may ALREADY be connected (the app was opened before this service — a fresh
        // install — or the service restarted while the helper lived): its "connected" moment is
        // past, so tell it now. Without this the input layer never started after setup.
        if (InputMonitor.connected) InputMonitor.onHelperConnected?.invoke()
        handler.post(padProfileTick)
        InputMonitor.start(this)
        installGestureListener()                        // Blank gesture, focus chord
        syncFocusModel()
        applyFocusLock()                                // Re-apply a saved lock
        handler.postDelayed(idleWatcher, idleCheckMs)  // Auto-off idle timer
        refreshDisplays()
        detectLaunchers()
        postPersistentNotification()
        instance = this
        MainActivity.announceHub()
        isRunning = true
        Log.d(TAG, "Connected — displays: $availableDisplayIds, flags: ${serviceInfo.flags}, ignored: $ignoredPackages")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stayAwake?.release() }; stayAwake = null
        keyboardOverlay?.hide()
        inputDeck?.hide()
        QuickPanelWindow.close()
        runCatching { unregisterReceiver(screenPowerReceiver) }
        runCatching { unregisterReceiver(packagesReceiver) }
        // every timer of this instance (the 500 ms profile tick, idle watcher, reconcilers…) and every
        // listener pointing at it: a rebound service must never run beside a dead one
        handler.removeCallbacksAndMessages(null)
        // The shared hooks are cleared ONLY if no newer instance took over: Android can create the
        // new service before this one's onDestroy runs (a rebind), and clearing them then killed
        // every combo of the new one (release test 2026-09-24).
        val current = instance === this
        if (current) {
            // only when no newer instance took over (it had already started them: stopping here
            // killed its linked volume, and its lights — review 2026-09-25)
            LinkedVolume.stop()
            // Leave the sticks as AYN's own settings say, not on our last effect.
            app.wayfinder.lights.StickLights.apply(this, app.wayfinder.lights.LightProfile())
            InputMonitor.listener = null; InputMonitor.gatedListener = null; InputMonitor.extListener = null; InputMonitor.stickListener = null
            InputMonitor.onHelperConnected = null; InputMonitor.fpsListener = null; InputMonitor.ambientListener = null
            app.wayfinder.lights.StickLights.screenSampler = null; app.wayfinder.lights.StickLights.screenStop = null
            PadLayerCtl.onEmergencyOff = null
            PadLayerCtl.onLayerFailed = null
            ExtEngine.releaseAll(); GyroEngine.follow(null, null)
            // the root helper outlives us (same process): stop its samplers, give every app the plain
            // pad, and put back performance / Hz / DND (review 2026-09-25: they kept running)
            InputMonitor.send("A -"); InputMonitor.send("F - -")
            PadLayerCtl.setProfile("")
            PerfProfiles.apply(this, null, null, null)
            val perfPrefs = getSharedPreferences("thor_perf", MODE_PRIVATE)
            if (perfPrefs.getBoolean("dnd_by_us", false)) Thread {
                val zen = runCatching { android.provider.Settings.Global.getInt(contentResolver, "zen_mode") }.getOrDefault(0)
                if (zen == 1) PServiceBridge.exec("cmd notification set_dnd off")
                perfPrefs.edit().putBoolean("dnd_by_us", false).apply()
            }.apply { isDaemon = true }.start()
            brightnessWorker.shutdown()
        }
        blanker?.wakeAll()
        // Don't leave AYN's native lock on once Wayfinder isn't managing it.
        if (current && AppSettings.focusLockEnabled && AppSettings.focusLockTop)
            Thread { PServiceBridge.exec("settings put system screen_focus_lock 0") }.start()
        // Note: InputMonitor is process-scoped and idempotent — we deliberately do
        // NOT stop it here. The a11y service can be unbound/rebound while the process
        // lives; tearing the socket down and rebinding races into "address in use".
        // The root helper self-exits when this process dies (its socket closes).
        if (current) { instance = null; isRunning = false }
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(1)
    }

    private fun postPersistentNotification() {
        val channelId = "thor_wayfinder_svc"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId, "Wayfinder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps Wayfinder running for your combos and screens" }
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Wayfinder")
            .setContentText("Running — open Wayfinder for settings")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(1, notification)
    }

    // ── foreground tracking (per-display) ────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0) {
                handler.removeCallbacks(reconcileGone); handler.postDelayed(reconcileGone, 400)
            }
            return
        }
        val pkg = event?.packageName?.toString() ?: return

        // Strategy 1: get displayId from event.source → window → displayId
        var displayId = getDisplayIdFromEvent(event)

        // Strategy 2: try windowId → find matching window in windows list
        if (displayId == null) {
            displayId = getDisplayIdFromWindowId(event.windowId)
        }

        // Recents (Android's overview) opened / left → controller mode for it.
        // Any other window change (an app — Wayfinder included — a toast, a dialog…) only
        // ends it once Recents really isn't the top app any more: a "controller
        // disconnected" toast used to switch the mode off with Recents still open.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            if (pkg != packageName && (cls.contains("RecentsActivity") || (cls.contains("quickstep", ignoreCase = true) && cls.contains("Recents"))))
                enterRecents(displayId ?: focusedDisplayId())
            else if (recentsDisplay != null) handler.postDelayed({
                val d = recentsDisplay
                if (d != null && !recentsStillOpen(d)) { Log.d(TAG, "recents: left ($pkg/$cls)"); exitRecents() }
            }, 250)
        }

        // If an actual launcher came to the foreground, the user went Home
        // → clear that display's tracked app so we don't swap with a ghost.
        // Suppress during swaps: the launcher briefly flashes on the target
        // display between the two 'am start' commands, which would falsely
        // clear the app we just moved there.
        if (pkg in launcherPackages) {
            // Remember WHICH launcher screen each display shows (its home for [goHomeOnDisplay]).
            if (displayId != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.className?.toString()?.let { cls -> rememberLauncherScreen(displayId, pkg, cls) }
            if (swapInProgress) {
                Log.d(TAG, "Launcher $pkg on display $displayId — ignored (swap in progress)")
                return
            }
            if (displayId != null) {
                if (displayApps.containsKey(displayId)) {
                    Log.d(TAG, "Launcher $pkg on display $displayId — clearing tracked app")
                    displayApps.remove(displayId)
                    onTopAppMaybeChanged()
                }
            } else {
                // displayId unknown — launcher is visible somewhere; clear any
                // display whose tracked app no longer has a visible window.
                // This prevents ghost entries when the event system can't resolve
                // which display the launcher appeared on.
                Log.d(TAG, "Launcher $pkg (display unknown) — will be reconciled on next scan")
            }
            return
        }

        if (pkg in ignoredPackages || pkg == currentImePackage()) return

        if (displayId != null) {
            val fresh = pkg !in displayApps.values   // just opened (not already on a screen)
            if (!Shell.isPkg(pkg)) return
            displayApps[displayId] = pkg
            Log.d(TAG, "Display $displayId → $pkg")
            maybeRoute(pkg, displayId, fresh)
            if (fresh) maybeFirstGameTip(pkg, displayId)
            // An app coming to the front on a screen takes input focus there (Android's
            // rule) — keep the focus model honest without waiting for a touch.
            if (!swapInProgress) focusModel = displayId
            onTopAppMaybeChanged()
        } else {
            if (!Shell.isPkg(pkg)) return
            Log.d(TAG, "FG → $pkg (display unknown)")
            // Find its screen another way: its window, or (hidden under a permission
            // prompt) its task in Android's activity list.
            val fresh = pkg !in displayApps.values
            handler.postDelayed({
                val d = displayOfApp(pkg)
                if (d != null) resolvedDisplay(pkg, d, fresh)
                else Thread { taskDisplayOf(pkg)?.let { t -> handler.post { resolvedDisplay(pkg, t, fresh) } } }.start()
            }, 250)
        }
    }

    // ── Per-screen brightness ───────────────────────────────────────
    private val brightnessWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Brighter / dimmer on BOTH screens, keeping the difference between them. */
    private fun stepBrightness(dir: Int) {
        val ids = listOfNotNull(PRIMARY_DISPLAY, secondDisplayId())
        val pillOn = if (AppSettings.focusLockEnabled || AppSettings.focusSticky) lockTarget() else focusedDisplayId()
        brightnessWorker.execute {
            val cur = ids.mapNotNull { d -> ScreenLevels.get(this, d)?.let { d to it } }.toMap()
            if (cur.isEmpty()) return@execute
            val next = cur.mapValues { (_, v) -> ScreenLevels.step(v, dir) }
            next.forEach { (d, v) -> ScreenLevels.set(this, d, v) }
            val txt = ids.mapNotNull { d -> next[d]?.let { (if (d == PRIMARY_DISPLAY) "top " else "bottom ") + ScreenLevels.percent(it) + " %" } }
            handler.post { focusCue?.show(pillOn, "Brightness — " + txt.joinToString(", "), 1500) }
        }
    }

    // ── The AYN button ──────────────────────────────────────────────
    // AYN's drawer is opened from PhoneWindowManager.interceptKeyBeforeDispatching, on
    // scan code 194 (KEY_F24, gpio-keys) — which runs AFTER the accessibility key filter.
    // So consuming the key here keeps AYN's drawer shut: nothing of AYN's is disabled or
    // changed, and turning the option off gives the button straight back.
    // 1.3: a tap and a hold can each do a chosen action (Controller → Quick panel). The hold fires
    // while the button is still down; its release then does nothing more.
    private var aynHoldFired = false
    private val aynHold = Runnable {
        val a = AppSettings.aynHold ?: return@Runnable
        aynHoldFired = true
        Log.d(TAG, "AYN button held → $a")
        vibrateShort()
        Companion.perform(a)
    }

    private fun aynButtonKey(event: KeyEvent): Boolean {
        if (!AppSettings.aynButtonOurs || event.scanCode != 194) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                aynHoldFired = false
                handler.removeCallbacks(aynHold)
                if (AppSettings.aynHold != null) handler.postDelayed(aynHold, AYN_HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(aynHold)
                if (!event.isCanceled && !aynHoldFired) handler.post { onAynButton() }
            }
        }
        return true
    }

    private fun onAynButton() {
        val a = AppSettings.aynTap
        Log.d(TAG, "AYN button → $a")
        if (a != ThorAction.QUICK_MENU) { Companion.perform(a); return }
        if (TourPractice.intercept(ThorAction.QUICK_MENU)) return
        toggleQuickPanel()
    }

    private val AYN_HOLD_MS = 600L

    // ── Quick panel (our drawer) ────────────────────────────────────
    private var panelReturnTo: Int? = null
    /** Between the AYN press and the panel's window (main thread only). */
    private var panelOpening = false
    /** The panel took the controller when it opened (GitHub #31: off = touch only, nothing to give back). */
    private var panelTook = true
    /** The app under the panel when it opened: another app there = the user left, the panel closes. */
    private var panelOver: String? = null
    /** Open it on the bottom screen, or close it if it's open. While it's open it has the
     *  controller: AYN's top lock would otherwise keep sending buttons to the top. */
    fun toggleQuickPanel(onDisplay: Int? = null) {
        inputDeck?.hide()   // the panel goes on top of everything — the deck included
        if (QuickPanelWindow.isOpen) { QuickPanelWindow.close(); return }
        // pressed again while it was still opening (the lock is released first, a root round-trip): cancelled
        if (panelOpening) { panelOpening = false; onQuickPanelClosed(); return }
        val bottom = secondDisplayId()
        // The bottom screen — over a dual-screen game's second screen too (1.3): the panel is an
        // accessibility overlay, drawn above its Presentation window (1.1's activity opened invisible
        // underneath, hence a side sheet on the top screen, still the fallback below)
        val d = onDisplay ?: bottom ?: PRIMARY_DISPLAY
        panelReturnTo = focusedDisplayId()
        panelApp()?.let { a -> GameProfiles.ask(a); handler.postDelayed({ if (QuickPanelWindow.isOpen) GameProfiles.ask(a) }, 1500) }
        if (blanker?.isBlanked(d) == true) blanker?.wake(d)   // else it'd open under the black cover
        val takes = AppSettings.panelTakesController
        panelTook = takes
        panelOver = displayApps[d]
        panelOpening = true
        Thread {
            if (takes && panelOpening && AppSettings.focusLockEnabled && AppSettings.focusLockTop) PServiceBridge.exec("settings put system screen_focus_lock 0")
            handler.post {
                if (!panelOpening) return@post   // cancelled by a second press
                panelOpening = false
                // an overlay window (1.3, GitHub #30 / #32): the app under it is neither paused nor resumed
                val shown = QuickPanelWindow.show(this, d) ||
                    (d != PRIMARY_DISPLAY && QuickPanelWindow.show(this, PRIMARY_DISPLAY).also { if (it) panelOver = displayApps[PRIMARY_DISPLAY] })
                if (!shown) { Log.w(TAG, "quick panel: couldn't open on $d"); onQuickPanelClosed(); return@post }
                if (takes) focusPanel(QuickPanelWindow.displayId ?: d, 0)   // the controller follows the panel
            }
        }.apply { isDaemon = true }.start()
    }

    /** The controller onto the panel's screen, checked: the focus tap can land before the new window takes
     *  input — over a dual-screen game on its second screen (a Presentation, never focused), so focus stayed on
     *  the game and B went to it (1.3). Retried while the panel is open. */
    private fun focusPanel(d: Int, attempt: Int) {
        focusDisplay(d) {
            Thread {
                val real = rootFocusedDisplay()
                if (real != null && real != d && attempt < 4 && QuickPanelWindow.isOpen && QuickPanelWindow.displayId == d) {
                    Log.w(TAG, "quick panel: focus still on $real (attempt $attempt) — again")
                    handler.postDelayed({ focusPanel(d, attempt + 1) }, 200)
                } else { if (real != null) focusModel = real }
            }.apply { isDaemon = true }.start()
        }
    }

    // ── Game controls while playing (plan §6h) ──
    private var controlsReturnTo: Int? = null

    // the TRACKED apps first: they ignore Wayfinder's own windows (the controls cover the game)
    private fun screenGamesNow(): List<String> = listOfNotNull(PRIMARY_DISPLAY, secondDisplayId())
        .mapNotNull { d -> displayApps[d]?.takeIf { it != packageName } ?: appOnDisplay(d) }
        .filter { Shell.isPkg(it) && it != packageName && it !in ignoredPackages && it !in launcherPackages }.distinct()

    /** Open "Buttons for <game>" where the Hub lives; the controller follows it while it's open. */
    fun openGameControls() {
        // already open (Home + X again, or the tile): close it — back to the game
        MainActivity.gameControls?.let { a ->
            MainActivity.gameControls = null
            a.finish(); onGameControlsClosed(); return
        }
        val game = controllerDisplay()
        val pkg = (appOnDisplay(game) ?: displayApps[game])
            ?.takeIf { Shell.isPkg(it) && it != packageName && it !in launcherPackages && it !in ignoredPackages }
        // no game on the controller's screen (a launcher, Wayfinder): say so, open nothing — a page
        // opened here couldn't be closed by the same combo, and "start the game first" is the fix
        if (pkg == null) { focusCue?.show(game, "Game controls: start a game on this screen first"); return }
        val hubDisplay = if (AppSettings.hubOnTop) PRIMARY_DISPLAY else secondDisplayId() ?: PRIMARY_DISPLAY
        controlsReturnTo = game
        // which game it is — the page offers "controls just for <game>" (the answer comes in ~50 ms)
        if (pkg != null && pkg !in launcherPackages) GameProfiles.ask(pkg)
        Thread {
            // AYN's top lock would keep the buttons on the top screen (as for the quick panel)
            if (hubDisplay != PRIMARY_DISPLAY && AppSettings.focusLockEnabled && AppSettings.focusLockTop)
                PServiceBridge.exec("settings put system screen_focus_lock 0")
            handler.post {
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra("page", if (pkg != null) HubPage.playFor(GameProfiles.activeKey(pkg)) else HubPage.APPS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(hubDisplay).toBundle())
                }.onFailure { Log.w(TAG, "game controls: ${it.message}") }
                if (hubDisplay != game) handler.postDelayed({ focusDisplay(hubDisplay) { syncFocusModel() } }, 250)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Open the Hub on [page], where the Hub lives (Appearance → Hub screen). */
    private fun openHubPage(page: String) {
        val d = if (AppSettings.hubOnTop) PRIMARY_DISPLAY else secondDisplayId() ?: PRIMARY_DISPLAY
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).putExtra("page", page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                android.app.ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle())
        }.onFailure { Log.w(TAG, "open page: ${it.message}"); return }
        // the page is driven with the controller: send it there, exactly as Home + right stick does
        // (a lock moves with it) — it stayed on the game's screen (review 2026-09-25)
        if (d != controllerDisplay()) handler.postDelayed({ focusAction(top = d == PRIMARY_DISPLAY) }, 300)
    }

    private fun onGameControlsClosed() {
        val back = controlsReturnTo ?: return
        controlsReturnTo = null
        handler.postDelayed({
            if (AppSettings.focusLockEnabled) applyFocusLock(announce = false)
            else focusDisplay(back) { syncFocusModel() }
        }, 150)
    }

    // ── The Hub has the controller while it's open (2026-09-26: tapped open on the bottom
    // screen with the controller locked there, the Hub — which lives on top — couldn't be driven).
    // The lock is set aside meanwhile (AYN's top lock lifted if the Hub is on the bottom; our own
    // bottom lock doesn't pull focus back), and restored when the Hub is left.
    private var hubOn: Int? = null
    private var hubReturnTo: Int? = null
    private var hubToken = 0
    private fun onHubShown(d: Int, token: Int) {
        if (MainActivity.gameControls != null) return        // Game controls does this itself
        hubOn = d; hubToken = token
        applyFps()
        val ctl = controllerDisplay()
        if (ctl == d) return
        if (AppSettings.focusLockEnabled) hubReturnTo = ctl
        Log.i(TAG, "Hub on display $d, controller on $ctl: the Hub takes it")
        Thread {
            if (AppSettings.focusLockEnabled && AppSettings.focusLockTop && d != PRIMARY_DISPLAY)
                PServiceBridge.exec("settings put system screen_focus_lock 0")
            handler.post { focusDisplay(d) { syncFocusModel() } }
        }.apply { isDaemon = true }.start()
    }
    private fun onHubHidden(token: Int) {
        if (token != hubToken) return
        hubOn = null
        applyFps()
        hubReturnTo ?: return
        hubReturnTo = null
        handler.postDelayed({ if (AppSettings.focusLockEnabled) applyFocusLock(announce = false) }, 150)
    }
    /** A page that holds the controller against the lock is open (the Hub, Game controls). */
    private fun lockSuspended() = hubReturnTo != null || controlsReturnTo != null

    /** The panel closed: the controller (and its lock) go back where they were. */
    private fun onQuickPanelClosed() {
        val back = panelReturnTo ?: PRIMARY_DISPLAY
        panelReturnTo = null; panelOver = null
        if (!panelTook) return   // touch-only (#31): the game never lost the controller
        handler.postDelayed({
            val hub = hubOn
            if (hub != null && hubReturnTo != null) focusDisplay(hub) { syncFocusModel() }
            else if (AppSettings.focusLockEnabled) applyFocusLock(announce = false)
            else focusDisplay(back) { syncFocusModel() }
        }, 150)
    }

    /** 0 both screens · 1 top only · 2 bottom only (the other one blanked). */
    private fun currentScreenMode(): Int {
        val second = secondDisplayId() ?: return 0
        val b = blanker ?: return 0
        return when { b.isBlanked(second) -> 1; b.isBlanked(PRIMARY_DISPLAY) -> 2; else -> 0 }
    }

    private fun applyScreenMode(mode: Int) {
        val second = secondDisplayId() ?: return
        val b = blanker ?: return
        if (mode != 1 && b.isBlanked(second)) b.wake(second)
        if (mode != 2 && b.isBlanked(PRIMARY_DISPLAY)) b.wake(PRIMARY_DISPLAY)
        when (mode) { 1 -> b.blank(second); 2 -> b.blank(PRIMARY_DISPLAY) }
    }

    // ── FPS counter ─────────────────────────────────────────────────
    private var fpsOverlay: FpsOverlay? = null
    private var fpsWatching: String? = null

    /** Watch the top-screen app while the counter is on (sampled by the root helper). */
    /** FPS counter on the chosen screen(s), in the chosen corner. */
    fun applyFps() {
        // the idle watcher (every 2 s) and a helper reconnect both land here: with the screen off
        // they restarted the frame-rate sampler all night (review 2026-09-25)
        if (!screenOn) return
        // not over the Hub: Wayfinder is ignored in the screen tracking, so the game under it
        // still looked like that screen's app and the counter stayed over the Hub (seen while filming)
        fun appOn(d: Int?) = d?.takeIf { it != hubOn }?.let { displayApps[it] ?: appOnDisplay(it) }?.takeIf { Shell.isPkg(it) && it !in ignoredPackages && it !in launcherPackages }
        val second = secondDisplayId()
        val on = AppSettings.fpsCounter
        // 1.3 (GitHub #22): an app / game can turn the counter on or off for itself
        fun wants(app: String?, screenOk: Boolean) = app != null && (GameProfiles.effective(app).fps ?: (on && screenOk))
        val top = appOn(PRIMARY_DISPLAY)?.takeIf { wants(it, AppSettings.fpsScreens != 1) }
        val bottom = appOn(second)?.takeIf { wants(it, AppSettings.fpsScreens != 0) }
        val key = "$top|$bottom|${AppSettings.fpsCorner}"
        if (key == fpsWatching) return
        fpsWatching = key
        fun place(o: FpsOverlay?, app: String?, d: Int?): FpsOverlay? {
            if (app == null || d == null) { o?.hide(); return null }
            return (o ?: FpsOverlay(this)).also { it.show(d, AppSettings.fpsCorner) }
        }
        fpsOverlay = place(fpsOverlay, top, PRIMARY_DISPLAY)
        fpsOverlayBottom = place(fpsOverlayBottom, bottom, second)
        InputMonitor.fpsListener = { t, b -> fpsOverlay?.update(t); fpsOverlayBottom?.update(b) }
        val sent = InputMonitor.send(if (top == null && bottom == null) "F -" else "F ${top ?: "-"} ${bottom ?: "-"}")
        if (!sent) fpsWatching = null   // helper not there yet: retried when it connects
        Log.d(TAG, "fps counter → top ${top ?: "-"} · bottom ${bottom ?: "-"}${if (sent) "" else " (helper not connected yet)"}")
    }
    private var fpsOverlayBottom: FpsOverlay? = null

    // ── Per-app performance / fan ───────────────────────────────────
    /** Two apps on screen with different wishes: the chip can only run one mode, so the
     *  most demanding request wins (a game on top asking for High isn't slowed down by a
     *  browser below asking for Standard). "Default" is not a request. Buttons and stick
     *  lights don't need this rule: they follow the app that has the controller. */
    private fun applyPerf() {
        val onScreens = listOfNotNull(displayApps[PRIMARY_DISPLAY], secondDisplayId()?.let { displayApps[it] }).distinct()
        GameProfiles.forgetExcept(onScreens.toSet())
        val cfgs = onScreens.map { GameProfiles.effective(it) }      // a running game's own values first
        PerfProfiles.apply(this, cfgs.mapNotNull { it.perf }.maxByOrNull { it.ordinal }, cfgs.mapNotNull { it.fan }.maxByOrNull { it.ordinal },
            cfgs.mapNotNull { it.hz }.maxOrNull())
    }

    // ── Round 8: Do not disturb while playing ─────────────────────────────
    private val gameCache = HashMap<String, Boolean>()
    @Volatile private var dndBusy = false
    private var gameCacheVersion = -1
    /** A game (Android's own flag, a known emulator, or one with game profiles) on either screen →
     *  Do not disturb (priority only), if the user turned the switch on. Put back when no game is
     *  left — but only if WE turned it on and it's still as we set it (the user's own DND, or a
     *  change they made meanwhile, is never touched). The flag survives a crash. */
    private fun applyDnd() {
        // runtime state: thor_perf, which backups leave out (restoring "ours" turned the user's own DND off)
        val prefs = getSharedPreferences("thor_perf", MODE_PRIVATE)
        if (AppConfigStore.version.intValue != gameCacheVersion) { gameCache.clear(); gameCacheVersion = AppConfigStore.version.intValue }
        val byUs = prefs.getBoolean("dnd_by_us", false)
        val playing = AppSettings.dndWhilePlaying && listOfNotNull(displayApps[PRIMARY_DISPLAY], secondDisplayId()?.let { displayApps[it] })
            .any { p -> gameCache.getOrPut(p) { GameApps.isGame(this, p) } }
        if (playing == byUs || dndBusy) return
        dndBusy = true
        Thread {
          try {
            val zen = runCatching { android.provider.Settings.Global.getInt(contentResolver, "zen_mode") }.getOrDefault(0)
            if (playing) {
                if (zen != 0) return@Thread                     // DND already on: the user's, leave it alone
                if (PServiceBridge.exec("cmd notification set_dnd priority") != null) {
                    prefs.edit().putBoolean("dnd_by_us", true).apply(); Log.i(TAG, "Game on screen: Do not disturb on")
                }
            } else {
                if (zen == 1) PServiceBridge.exec("cmd notification set_dnd off")   // still ours (priority)
                prefs.edit().putBoolean("dnd_by_us", false).apply(); Log.i(TAG, "No game on screen: Do not disturb ${if (zen == 1) "off" else "left as the user set it"}")
            }
          } finally { dndBusy = false }   // a change made meanwhile: caught at the next app change
        }.apply { isDaemon = true }.start()
    }

    // ── Game companion ──────────────────────────────────────────────
    private var companionFor: String? = null

    /** A game with a companion just came to the top screen, and the bottom one is free
     *  (home, not blanked) → open its companion there. Once per arrival. */
    private fun maybeOpenCompanion() {
        val top = displayApps[PRIMARY_DISPLAY]
        if (top == companionFor) return
        companionFor = top
        if (top == null || !AppConfigStore.get(top).companion) return
        val second = secondDisplayId() ?: return
        if (displayApps[second] != null || blanker?.isBlanked(second) == true) return
        if (coveredByPresentation(second)) return   // the game's own second screen is there (1.1)
        openCompanion(top, second)
    }

    private fun openCompanion(pkg: String, display: Int) = Thread {
        if (!Shell.isPkg(pkg)) return@Thread
        val cmp = "$packageName/${CompanionActivity::class.java.name}"
        // -f NEW_TASK | NO_USER_ACTION: the game isn't told the user left it (video apps → picture-in-picture)
        val out = PServiceBridge.exec("am start --display $display -f 0x10040000 -n $cmp --es ${CompanionActivity.EXTRA_PKG} ${Shell.q(pkg)}")
        Log.i(TAG, "Companion for $pkg on display $display: ${out?.trim()}")
    }.start()

    // ── App pairs / restore after a restart ─────────────────────
    @Volatile private var restoringLayout = false

    /** Open [top] on the top screen and [bottom] on the bottom one (null = leave as is).
     *  Running apps are MOVED (live task), others started there. */
    private fun openLayout(top: String?, bottom: String?, why: String): Boolean {
        if (!swapBusy.compareAndSet(false, true)) return false
        val second = secondDisplayId()
        Log.i(TAG, "Layout ($why): top=$top bottom=$bottom")
        Thread {
            try {
                refreshDisplays(); scanAllDisplayApps()
                fun place(pkg: String?, d: Int?) {
                    if (pkg == null || d == null || displayApps[d] == pkg) return
                    if (packageManager.getLaunchIntentForPackage(pkg) == null) { if (BuildConfig.DEBUG) Log.w(TAG, "Layout: $pkg not installed"); return }
                    if (blanker?.isBlanked(d) == true) handler.post { blanker?.wake(d) }
                    val from = displayApps.entries.firstOrNull { it.value == pkg }?.key
                    launchOnDisplay(pkg, d, aggressive = false, fromDisplay = from)
                    Thread.sleep(350)
                }
                place(top, PRIMARY_DISPLAY)
                place(bottom, second)
                Thread.sleep(700)
                scanAllDisplayApps()
            } catch (e: Exception) {
                Log.e(TAG, "Layout failed: ${e.message}")
            } finally {
                swapBusy.set(false)
                restoringLayout = false
            }
            handler.post { onTopAppMaybeChanged() }
        }.start()
        return true
    }

    private fun maybeRestoreLayoutAtBoot() {
        val (top, bottom) = Layouts.bootLayout
        if (!Layouts.restoreOnBoot || (top == null && bottom == null)) return
        if (android.os.SystemClock.elapsedRealtime() > 5 * 60_000) return   // not a fresh boot (e.g. reinstall)
        val boot = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        if (Layouts.bootRestoreDone(boot)) return
        restoringLayout = true
        // Let AYN's boot launches and the frontends settle first, then put the screens back.
        // Claimed only when it RUNS: if Android kills us before (low memory at boot), the
        // restarted service schedules it again (still within the 5 min above).
        handler.postDelayed({
            if (!Layouts.claimBootRestore(boot)) { restoringLayout = false; return@postDelayed }
            if (!openLayout(top, bottom, "restore after restart")) restoringLayout = false
        }, 20_000)
    }

    // ── Per-app screen routing ──────────────────────────────────────
    // An app set to "opens on Top/Bottom" that just OPENED on the other screen is moved
    // (its live task — no relaunch). Only on a fresh open: an app you moved yourself
    // (swap/send, or dragged there) is left alone.
    private val routedAt = ConcurrentHashMap<String, Long>()

    /**
     * Is [pkg] on [displayId]? The window scan can't see an app hidden under a permission
     * prompt — and a "failed" verify escalates to force-stop + relaunch (state lost!) —
     * so when the scan found nothing there, ask the task list before saying no.
     */
    private fun isOn(pkg: String, displayId: Int): Boolean {
        if (displayApps[displayId] == pkg) return true
        if (displayApps[displayId] != null) return false
        val t = taskDisplayOf(pkg) == displayId
        if (t && Shell.isPkg(pkg)) { displayApps[displayId] = pkg; Log.d(TAG, "Verify: $pkg found on $displayId via the task list") }
        return t
    }

    private fun resolvedDisplay(pkg: String, displayId: Int, fresh: Boolean) {
        if (swapInProgress || displayApps[displayId] == pkg || !Shell.isPkg(pkg)) return
        displayApps[displayId] = pkg
        Log.d(TAG, "Display $displayId → $pkg (resolved)")
        onTopAppMaybeChanged()
        maybeRoute(pkg, displayId, fresh)
        if (fresh) maybeFirstGameTip(pkg, displayId)
    }

    /** Once ever: the first game played gets a tip on how to open its own buttons (the tour
     *  no longer explains Game controls up front — it's told when it's useful, 2026-09-25). */
    private fun maybeFirstGameTip(pkg: String, displayId: Int) {
        val prefs = getSharedPreferences("thor_settings", MODE_PRIVATE)
        if (prefs.getBoolean("tip_first_game", false) || pkg == packageName) return
        // Not for the apps found already open when the service starts (after an update it
        // fired for a launcher left open, behind the tour — test 2026-09-25)
        if (android.os.SystemClock.uptimeMillis() - connectedAt < 15_000) return
        if (AppConfigStore.get(pkg).buttonsMode == ButtonsMode.OFF || !GameApps.isGame(this, pkg)) return
        val gc = ControlsStore.triggerFor(ThorAction.GAME_CONTROLS)?.label()
        handler.postDelayed({
            // still in front? (it may have been moved by its profile in the meantime)
            val d = displayApps.entries.firstOrNull { it.value == pkg }?.key ?: return@postDelayed
            if (prefs.getBoolean("tip_first_game", false)) return@postDelayed
            prefs.edit().putBoolean("tip_first_game", true).apply()
            Log.i(TAG, "First game ($pkg): showing the Game controls tip")
            focusCue?.showTable(d, listOf("Your first game with Wayfinder"),
                listOfNotNull(gc?.let { it to "its own buttons, turbo, gyro" }, "Hold Home" to "all your combos"), 8000)
        }, 3000)
    }
    private val connectedAt = android.os.SystemClock.uptimeMillis()

    /** The screen holding [pkg]'s top task, from `dumpsys activity` (root; one line). */
    private fun taskDisplayOf(pkg: String): Int? {
        if (!PServiceBridge.isAvailable()) return null
        val out = PServiceBridge.exec("dumpsys activity activities | awk '/^Display #/{d=substr(\$2,2)} " +
            "index(\$0,\"mActivityComponent=$pkg/\"){print \"OK \" d; exit}'")?.trim() ?: return null
        return out.removePrefix("OK ").trim().toIntOrNull()?.takeIf { out.startsWith("OK") && it in availableDisplayIds }
    }

    /** The screen showing an app window of [pkg], from the a11y window list. */
    private fun displayOfApp(pkg: String): Int? = runCatching {
        val all = windowsOnAllDisplays
        (0 until all.size()).firstNotNullOfOrNull { i ->
            all.keyAt(i).takeIf { all.valueAt(i).any { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName == pkg } }
        }
    }.getOrNull()

    private fun maybeRoute(pkg: String, displayId: Int, fresh: Boolean) {
        if (!fresh || swapInProgress) return
        val want = when (AppConfigStore.get(pkg).route) {
            Route.ANY -> return
            Route.TOP -> PRIMARY_DISPLAY
            Route.BOTTOM -> secondDisplayId() ?: return
        }
        if (want == displayId) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - (routedAt[pkg] ?: 0L) < 4000) return   // never bounce
        if (!swapBusy.compareAndSet(false, true)) return     // busy: not stamped, so it can be retried
        routedAt[pkg] = now
        Log.i(TAG, "Route: $pkg opened on $displayId → display $want")
        Thread {
            try {
                if (blanker?.isBlanked(want) == true) handler.post { blanker?.wake(want) }
                launchOnDisplay(pkg, want, aggressive = false, fromDisplay = displayId)
                Thread.sleep(700)
                scanAllDisplayApps()
            } catch (e: Exception) {
                Log.e(TAG, "Route failed: ${e.message}")
            } finally {
                swapBusy.set(false)
            }
            handler.post { onTopAppMaybeChanged() }
        }.start()
    }

    private fun getDisplayIdFromEvent(event: AccessibilityEvent): Int? {
        var source: AccessibilityNodeInfo? = null
        var window: AccessibilityWindowInfo? = null
        try {
            source = event.source ?: return null
            window = source.window ?: return null
            return window.displayId
        } catch (e: Exception) {
            Log.w(TAG, "getDisplayIdFromEvent: ${e.message}")
            return null
        } finally {
            window?.recycle()
            source?.recycle()
        }
    }

    private fun getDisplayIdFromWindowId(windowId: Int): Int? {
        try {
            for (w in windows) {
                if (w.id == windowId) {
                    return w.displayId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDisplayIdFromWindowId: ${e.message}")
        }
        return null
    }

    // Scan all displays for foreground apps before acting.
    // getWindowsOnAllDisplays() returns SparseArray<List<AccessibilityWindowInfo>>
    /** The app whose window is frontmost on [displayId], straight from the window list. */
    /**
     * The buffer size of [pkg]'s visible surface, from SurfaceFlinger's composition dump
     * (`geomBufferSize=[0 0 W H]`) — tells when a moved app has really re-rendered at its
     * new screen's size. ~20 ms; null when unknown.
     */
    private fun appBufferSize(pkg: String): Pair<Int, Int>? {
        if (!Shell.isPkg(pkg) || !PServiceBridge.isAvailable()) return null   // it goes into an awk string
        val out = PServiceBridge.exec("dumpsys SurfaceFlinger | awk -v p='($pkg/' 'index(\$0,\"* Layer \")&&index(\$0,p){f=1;next} f&&/geomBufferSize/{print;exit}'") ?: return null
        val m = Regex("""geomBufferSize=\[\s*-?\d+\s+-?\d+\s+(\d+)\s+(\d+)\]""").find(out) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** 1.1 — another app's second screen covers [displayId]: dual-screen emulators (melonDS, Azahar,
     *  Cemu…) draw it as a Presentation window, which Android stacks above EVERY activity on that
     *  display — our quick panel or Guide opened there would sit underneath, invisible. Accessibility
     *  reports it as a (nearly) full-screen window of no known type above the apps. Our own
     *  companion screen doesn't count (the Hub's; it steps aside by itself). */
    private fun coveredByPresentation(displayId: Int): Boolean = presentationOwner(displayId) != null

    /** The app whose second screen covers [displayId] (see [coveredByPresentation]), "" if it can't be
     *  read, or null. */
    private fun presentationOwner(displayId: Int): String? = try {
        if (MainActivity.companionShowing()) null
        else {
            val known = setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_SYSTEM, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
            val full = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
                ?.let { d -> android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) } }
            val area = full?.let { it.widthPixels.toLong() * it.heightPixels } ?: 0L
            getWindowsOnAllDisplays().get(displayId).orEmpty().firstNotNullOfOrNull { w ->
                if (w.type in known) return@firstNotNullOfOrNull null
                val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                val pkg = w.root?.let { n -> n.packageName?.toString().also { n.recycle() } }
                // unreadable (a busy game's Presentation) = covered too, owner unknown ("")
                (pkg ?: "").takeIf { it != packageName && area > 0 && r.width().toLong() * r.height() >= area * 8 / 10 }
            }
        }
    } catch (e: Exception) { null }

    private fun appOnDisplay(displayId: Int): String? = try {
        val wins = getWindowsOnAllDisplays().get(displayId)
        if (BuildConfig.DEBUG) Log.d(TAG, "appOnDisplay $displayId: " + wins?.joinToString { w ->
            "${w.type}/${w.layer}/${w.isActive}/${w.root?.packageName}" })
        wins?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.let { w -> w.root?.let { r -> r.packageName?.toString().also { r.recycle() } } }
    } catch (e: Exception) { null }

    /**
     * A window went away. If it was a tracked app's (force-stopped, crashed, swiped from
     * Recents), the launcher behind it doesn't always send a "came to the front" event —
     * the dead app then stayed tracked: its performance mode, buttons and lights kept
     * applying, and "Save app pair" saved it (found 2026-09-24). Drop only what is
     * provably gone: that screen now shows the launcher and nothing else. Wayfinder's
     * own Hub / panel covering a game (its window then counts as removed too), a
     * permission dialog, an unreadable window → keep it.
     */
    private val reconcileGone = Runnable {
        if (swapInProgress || displayApps.isEmpty()) return@Runnable
        val sparse = runCatching { getWindowsOnAllDisplays() }.getOrNull() ?: return@Runnable
        var changed = false
        for ((d, pkg) in displayApps.toMap()) {
            val wins = sparse.get(d) ?: continue           // screen unknown right now: leave it
            var launcher = false; var other = false
            for (w in wins) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: run { other = true; null } ?: continue
                val p = root.packageName?.toString(); root.recycle()
                if (p != null && p in launcherPackages) launcher = true else other = true
            }
            if (launcher && !other) {
                Log.d(TAG, "Display $d: $pkg's window is gone — no longer tracked")
                displayApps.remove(d); changed = true
            }
        }
        if (changed) onTopAppMaybeChanged()
    }

    private fun scanAllDisplayApps() {
        val fallback = displayApps.toMap() // save event-based data as fallback
        // built aside and applied at the end: other threads read displayApps meanwhile (an empty
        // map for a moment reset perf / fan / lights and could wake a blanked screen)
        val found = HashMap<Int, String>()
        val dialogOn = HashSet<Int>()   // screens showing only a dialog over an app's task
        try {
            val sparse = getWindowsOnAllDisplays()
            Log.d(TAG, "scanAll: ${sparse.size()} displays")

            for (i in 0 until sparse.size()) {
                val displayId = sparse.keyAt(i)
                val windowList = sparse.valueAt(i) ?: continue
                Log.d(TAG, "scanAll: display $displayId has ${windowList.size} windows")
                for (w in windowList) {
                    val typeName = when (w.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                        else -> "OTHER(${w.type})"
                    }
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        Log.d(TAG, "scanAll: display $displayId — skipping window type=$typeName")
                        continue
                    }
                    val root = w.root
                    if (root == null) {
                        Log.d(TAG, "scanAll: display $displayId — app window root null")
                        continue
                    }
                    val pkg = root.packageName?.toString()
                    root.recycle()
                    if (Shell.isPkg(pkg) && pkg !in ignoredPackages) {
                        found[displayId] = pkg!!
                        if (BuildConfig.DEBUG) Log.d(TAG, "Scan: display $displayId → $pkg")
                        break
                    } else if (pkg != null) {
                        if (pkg in DIALOG_PACKAGES) dialogOn.add(displayId)
                        Log.d(TAG, "scanAll: display $displayId — skipping ignored pkg $pkg")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanAll failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        // Don't restore event-based fallback data: if the scan can't confirm
        // an app via its window root, the app may have been closed. Restoring
        // stale entries leads to swaps trying to reopen dead apps.
        for ((id, pkg) in fallback) {
            // A permission prompt / chooser hides its app's own window: the app is still there.
            if (!found.containsKey(id) && id in dialogOn) found[id] = pkg
        }
        // apply only the differences
        for (id in displayApps.keys.toList()) if (!found.containsKey(id)) displayApps.remove(id)
        for ((id, pkg) in found) if (displayApps[id] != pkg) displayApps[id] = pkg
    }

    // ── keys: everything runs through the user's bindings ───────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (aynButtonKey(event)) return true
        // An open Thor Keyboard owns the whole pad (nothing leaks to the app).
        if (app.wayfinder.keyboard.ThorKeyboardService.instance?.captureKey(event) == true) return true
        if (recentsKey(event)) return true
        return buttonEngine?.onKey(event) ?: false
    }

    private val buttonHost = object : ButtonEngine.Host {
        override fun perform(action: ThorAction, fromHold: Boolean, arg: String?) {
            if (fromHold) vibrateShort()
            if (action == ThorAction.OPEN) Companion.open(arg) else Companion.perform(action)
        }

        override fun nativePress(button: ThorButton) {
            if (button == ThorButton.BACK) { performGlobalAction(GLOBAL_ACTION_BACK); return }
            QuickPanelWindow.close()   // Home leaves the panel too (it's a drawer)
            swapWatchGen++             // and the user's Home wins over a post-swap watch
            // Android's Home only knows the TOP screen: with the controller on the bottom one,
            // Home left that screen alone and sent the top one home (2026-09-24). Home
            // goes home on the screen that HAS the controller. Only the controller's Home comes
            // here — the AYN button (also a Home key) stays AYN's drawer ([printedButton]).
            // Android's own focused screen, not our model: an app moved or opened on the other
            // screen takes focus without going through us.
            Thread {
                val target = if (AppSettings.focusLockEnabled) lockTarget()
                    else rootFocusedDisplay()?.also { focusModel = it } ?: focusedDisplayId()
                if (target == PRIMARY_DISPLAY) handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
                else runCatching { goHomeOnDisplay(target) }
            }.apply { isDaemon = true }.start()
        }

        override fun showChordHint(modifier: ThorButton, chords: List<Binding>) {
            if (chords.isEmpty()) return
            if (modifier == ThorButton.HOME) TourPractice.sawHint()
            val where = if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()
            // In words: where the controller is and how it behaves (several ways to route it —
            // the hint says which one is on), then the combos, the button in bold
            focusCue?.showHint(where, listOf(controllerWhere(where), "Keep ${modifier.label} held and press:"),
                chords.map { it.trigger.button.spoken to chordLabel(it) })
        }

        override fun combosOffApp(): String? {
            val pkg = currentApp() ?: return null
            if (AppConfigStore.get(pkg).buttonsMode != ButtonsMode.OFF) return null
            return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        override fun showCombosOff(app: String) {
            val where = if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()
            focusCue?.showHint(where, listOf("Wayfinder's button combos are off in $app",
                "Home and Back work as usual here. To change it, open Wayfinder, then App profiles, then $app."), emptyList())
        }

        override fun hideChordHint() { focusCue?.hideHint() }

        override fun currentApp(): String? =
            displayApps[if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId()]

        override fun shieldAnalog(on: Boolean) {
            if (on) analogShield.raise(if (AppSettings.focusLockEnabled) lockTarget() else focusedDisplayId())
            else analogShield.lower()
        }
    }
    private val analogShield by lazy { AnalogShield(this) }

    private fun chordLabel(b: Binding): String =
        if (b.action == ThorAction.OPEN) OpenTargets.label(this, b.arg).replaceFirstChar { it.lowercase() } else chordLabel(b.action)
    private fun chordLabel(a: ThorAction): String = when (a) {
        ThorAction.FOCUS_SWITCH_UP -> "controller to the top screen"
        ThorAction.FOCUS_SWITCH_DOWN -> "controller to the bottom screen"
        else -> a.title.replace(" → ", " to ").replace("→", "to").lowercase()
    }

    /** Where the controller is, in words, and what moves it. */
    private fun controllerWhere(d: Int) = when {
        !AppSettings.focusLockEnabled -> "Controller on the ${screenName(d)} — it follows your touch" +
            if (AppSettings.focusSticky) " until you send it (Home + right stick)" else ""
        AppSettings.focusSticky -> "Controller on the ${screenName(d)} — it stays there until you send it"
        else -> "Controller locked to the ${screenName(d)}"
    }

    private fun vibrateShort() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed: ${e.message}")
        }
    }

    // ── swap / send logic ────────────────────────────────────────────────

    private data class Move(val pkg: String, val fromDisplay: Int, val toDisplay: Int)

    /**
     * Apps that hang for good if they're paused while moving to the other screen. RetroArch
     * (N64 core, threaded renderer): the move makes it rebuild its video driver; paused at
     * that moment (a swap's incoming app covering it first), its main thread waits for the
     * core, which waits for the main thread — ANR, black screen (Conker, 2026-09-24;
     * reproduced on the first swap). A plain move never
     * pauses it, so these just leave first.
     */
    private val PAUSE_FRAGILE = setOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32")

    private fun performSwapOrSend() {
        swapWatchGen++
        refreshDisplays()
        scanAllDisplayApps() // fresh scan from all displays before acting
        val ids = availableDisplayIds
        if (ids.size < 2) {
            Log.w(TAG, "Only ${ids.size} display(s) — nothing to do")
            return
        }

        val d0 = ids[0]
        val d1 = ids[1]
        // Validate tracked apps are actually launchable (not uninstalled/closed ghosts)
        var app0 = displayApps[d0]
        var app1 = displayApps[d1]
        if (app0 != null && packageManager.getLaunchIntentForPackage(app0) == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Tracked app $app0 on display $d0 has no launch intent — dropping")
            displayApps.remove(d0)
            app0 = null
        }
        if (app1 != null && packageManager.getLaunchIntentForPackage(app1) == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Tracked app $app1 on display $d1 has no launch intent — dropping")
            displayApps.remove(d1)
            app1 = null
        }

        Log.d(TAG, "State: display $d0→$app0, display $d1→$app1")

        // Duplicate from MULTIPLE_TASK: same app on both displays — clean up
        if (app0 != null && app0 == app1) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Duplicate $app0 on both displays — force-stop + relaunch on d$d1")
            launchOnDisplay(app0, d1, aggressive = true)
            Thread.sleep(1500)
            scanAllDisplayApps()
            Log.d(TAG, "After cleanup: d$d0→${displayApps[d0]}, d$d1→${displayApps[d1]}")
            return
        }

        // Build list of intended moves
        val moves: List<Move>
        when {
            app0 != null && app1 != null -> {
                Log.d(TAG, "Swap: $app0 \u2194 $app1")
                moves = listOf(Move(app0, d0, d1), Move(app1, d1, d0))
            }
            app0 != null -> {
                Log.d(TAG, "Send: $app0 \u2192 display $d1")
                moves = listOf(Move(app0, d0, d1))
            }
            app1 != null -> {
                Log.d(TAG, "Send: $app1 \u2192 display $d0")
                moves = listOf(Move(app1, d1, d0))
            }
            else -> {
                Log.w(TAG, "No tracked apps on any display")
                return
            }
        }

        // 1.3 (GitHub #29): another app's second screen (NeoStation's, a dual-screen game's) covers the
        // target screen — Android keeps it above every app there: the moved app would be hidden under it
        // (the screen looks frozen). Say so instead. (An app's OWN second screen is its business.)
        for (move in moves) {
            val owner = presentationOwner(move.toDisplay)?.takeIf { it.isNotEmpty() && it != move.pkg } ?: continue
            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(owner, 0)).toString() }.getOrDefault(owner)
            Log.w(TAG, "swap: ${move.toDisplay} is $owner's second screen — not moving ${move.pkg} under it")
            handler.post { focusCue?.show(move.fromDisplay, "The ${screenName(move.toDisplay)} shows $name's second screen — an app can't go under it. Turn it off in $name to swap.", 4500) }
            return
        }

        // ── Phase 1: gentle launches ──
        // Fast path: binder task-move or shell am commands (pservice/Shizuku/root);
        // slow path: trampoline activities that need UI round-trips.
        val fastPath = PServiceBridge.isAvailable() || TaskMover.isUsable() || RootHelper.isAvailable()

        // Anti-flash ordering: bring the app INCOMING to the built-in/top screen
        // in FIRST, so it covers the outgoing app before that one leaves. Moving
        // the top app away first would briefly reveal the task beneath it in the
        // top display's back-stack (launcher or last app) — the visible "flash".
        // The unavoidable sub-frame gap is pushed to the secondary screen instead.
        // (Built-in display is the lower id — 0 on the Thor.)
        val primaryDisplay = minOf(d0, d1)
        // …except an app that hangs when it's PAUSED mid-move: it leaves first, so nothing
        // covers it on its own screen at the moment it rebuilds its video for the new one
        // (the slide overlay hides the flash anyway). See [PAUSE_FRAGILE].
        val gentleOrder = moves.sortedWith(
            compareByDescending<Move> { it.pkg in PAUSE_FRAGILE }.thenByDescending { it.toDisplay == primaryDisplay })

        // The visible part of a move: apply the reparents, then (for a send)
        // return the vacated screen to Home so only the selected app moves — it
        // would otherwise reveal whatever sits beneath in its back-stack.
        val applyMoves = {
            for ((i, move) in gentleOrder.withIndex()) {
                // No inter-move delay on the binder fast path: the two reparents
                // must land back-to-back so the cover lands in one frame window.
                if (i > 0) Thread.sleep(if (fastPath) 0 else 300)
                launchOnDisplay(move.pkg, move.toDisplay, aggressive = false, fromDisplay = move.fromDisplay)
            }
            if (moves.size == 1) goHomeOnDisplay(moves[0].fromDisplay)
        }

        // Wrap the move in a content slide (fast/binder path only — the
        // trampoline path is too slow to hide cleanly). We pre-capture each
        // source screen (fast root screencap) and hand the bitmaps to the
        // animator, which slides them between displays while the real reparent
        // happens underneath — motion and move are simultaneous.
        val slides = moves.map { it.fromDisplay to it.toDisplay }
        // Capture ALL involved displays (from AND to). For a send, the target
        // display needs its own frosted backdrop too, otherwise the incoming card
        // slides in over a black BASE_DIM (a black gap).
        val involved = (slides.map { it.first } + slides.map { it.second }).distinct()
        val caps = if (animateMoves && fastPath && ScreenCapture.isAvailable())
            ScreenCapture.captureDisplays(this, involved) else emptyMap()
        if (caps.size == involved.size && caps.isNotEmpty()) {
            // Each covered screen waits for the moved app to show up on it — or, for a
            // send, the vacated screen for its home (anything but the app that left).
            val expect = moves.map { OverlayAnimator.Expect(it.toDisplay, it.pkg, arrives = true) } +
                (if (moves.size == 1) listOf(OverlayAnimator.Expect(moves[0].fromDisplay, moves[0].pkg, arrives = false)) else emptyList())
            (overlayAnimator ?: OverlayAnimator(this).also { overlayAnimator = it })
                .animateSlide(slides, primaryDisplay, caps, expect, ::appOnDisplay, ::appBufferSize, applyMoves)
        } else {
            applyMoves()
        }

        // ── Phase 2: verify after delay ──
        Thread.sleep(if (fastPath) 800 else 1500)
        scanAllDisplayApps()

        val clean = mutableSetOf<Move>()
        val duplicates = mutableListOf<Move>()
        val failed = mutableListOf<Move>()
        val isSend = moves.size == 1
        for (move in moves) {
            val onTarget = isOn(move.pkg, move.toDisplay)
            val onSource = displayApps[move.fromDisplay] == move.pkg
            when {
                onTarget && !onSource -> {
                    Log.d(TAG, "Verify OK: ${move.pkg} clean on display ${move.toDisplay}")
                    clean.add(move)
                }
                onTarget && onSource -> {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Verify DUPLICATE: ${move.pkg} on BOTH display " +
                        "${move.fromDisplay} and ${move.toDisplay}")
                    duplicates.add(move)
                }
                else -> {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Verify FAIL: ${move.pkg} NOT on display ${move.toDisplay} " +
                        "(found: ${displayApps[move.toDisplay]})")
                    failed.add(move)
                }
            }
        }

        // Handle duplicates: app IS on target, just also lingers on source.
        // For sends: accept — source will be covered by launcher, no action needed.
        // For swaps: companion app should cover source; if not, skip to aggressive.
        val needsAggressive = mutableListOf<Move>()
        for (move in duplicates) {
            if (isSend) {
                Log.d(TAG, "DUPLICATE (send): ${move.pkg} on target — accepting, " +
                    "source covered by launcher")
                clean.add(move)
            } else {
                val companionCoversSource = moves.any {
                    it != move && it.toDisplay == move.fromDisplay && it in clean
                }
                if (companionCoversSource) {
                    Log.d(TAG, "DUPLICATE (swap): ${move.pkg} — companion covers source, accepting")
                    clean.add(move)
                } else {
                    if (BuildConfig.DEBUG) Log.w(TAG, "DUPLICATE (swap): ${move.pkg} — companion didn't cover source, " +
                        "needs aggressive cleanup")
                    needsAggressive.add(move)
                }
            }
        }

        // 1.3 (GitHub #28): on its screen but covered — a frontend (iiSU, NeoStation…) brought its main
        // screen forward when Wayfinder returned the other screen to it. To the front, as it was.
        if (failed.isNotEmpty() && PServiceBridge.isAvailable()) {
            val covered = failed.filter { taskDisplayOf(it.pkg) == it.toDisplay }
            for (move in covered) {
                Log.i(TAG, "covered after the move: ${move.pkg} on ${move.toDisplay} (found ${displayApps[move.toDisplay]}) — to the front")
                PServiceBridge.frontTask(this, move.pkg, move.toDisplay)
            }
            if (covered.isNotEmpty()) {
                Thread.sleep(500)
                scanAllDisplayApps()
                covered.filter { isOn(it.pkg, it.toDisplay) }.forEach { failed.remove(it); clean.add(it) }
            }
        }

        // ── Phase 3: gentle retry for moves that haven't landed yet ──
        // Some apps (RetroArch) are slow to appear on the target display.
        // Re-attempt with gentle launch to preserve app state before force-stopping.
        // Only for genuine failures — duplicates already on target skip this.
        val needsForceRetry = mutableListOf<Move>()
        if (failed.isNotEmpty()) {
            for ((i, move) in failed.withIndex()) {
                if (i > 0) Thread.sleep(300)
                Log.d(TAG, "Gentle retry: ${move.pkg} → display ${move.toDisplay}")
                launchOnDisplay(move.pkg, move.toDisplay, aggressive = false, fromDisplay = move.fromDisplay)
            }
            Thread.sleep(if (fastPath) 1000 else 2000)
            scanAllDisplayApps()

            for (move in failed) {
                val onTarget = isOn(move.pkg, move.toDisplay)
                if (onTarget) {
                    Log.d(TAG, "Gentle retry OK: ${move.pkg} now on display ${move.toDisplay}")
                    clean.add(move)
                } else {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Gentle retry FAIL: ${move.pkg} still not on display ${move.toDisplay}")
                    needsForceRetry.add(move)
                }
            }
        }
        // Merge: duplicates that need aggressive go straight to force retry
        needsForceRetry.addAll(needsAggressive)

        // ── Phase 4: aggressive retry (force-stop + relaunch) as last resort ──
        // Force-stop kills ALL instances (including duplicates), then CLEAR_TASK
        // relaunches a single fresh instance on the target display.
        for ((i, move) in needsForceRetry.withIndex()) {
            val stillOnSource = displayApps[move.fromDisplay] == move.pkg
            val companionCoversSource = moves.any { it != move && it.toDisplay == move.fromDisplay && it in clean }
            if (stillOnSource && !isSend && !companionCoversSource) {
                if (BuildConfig.DEBUG) Log.w(TAG, "${move.pkg} still on display ${move.fromDisplay}, " +
                    "companion didn't cover it — skipping aggressive")
                continue
            }
            // 1.3 (GitHub #28): never kill an app that is still running — a game in progress (RetroArch came
            // back at its main menu). Its task goes to the front of its target screen instead.
            val liveOn = if (move in needsAggressive) null else taskDisplayOf(move.pkg)   // duplicates: cleaned up as before
            if (liveOn != null) {
                Log.w(TAG, "${move.pkg} is running (task on $liveOn) — not force-stopping it; to the front of ${move.toDisplay}")
                if (liveOn != move.toDisplay) launchOnDisplay(move.pkg, move.toDisplay, aggressive = false, fromDisplay = liveOn)
                PServiceBridge.frontTask(this, move.pkg, move.toDisplay)
                clean.add(move)
                continue
            }
            if (i > 0) Thread.sleep(300)
            Log.d(TAG, "Aggressive retry: ${move.pkg} → display ${move.toDisplay} " +
                "(send=$isSend, companionCovers=$companionCoversSource)")
            launchOnDisplay(move.pkg, move.toDisplay, aggressive = true)
            clean.add(move)
        }

        // Update tracking: build final state first to avoid swap conflicts
        // (sequential remove+put would cause second move to delete first move's result)
        val finalState = mutableMapOf<Int, String?>()
        for (move in clean) {
            finalState[move.fromDisplay] = null
            finalState[move.toDisplay] = move.pkg
        }
        for ((displayId, pkg) in finalState) {
            if (Shell.isPkg(pkg)) displayApps[displayId] = pkg!!
            else displayApps.remove(displayId)
        }

        // 1.3 (GitHub #28, #29): a frontend (iiSU…) answers its screen coming back by bringing its main
        // screen forward — over the app that just moved there. Back to the front (the game as it was).
        // Its own thread (the swap is over), ~3 s; a new swap or the user's Home stops it.
        if (PServiceBridge.isAvailable() && clean.isNotEmpty()) {
            val gen = ++swapWatchGen
            val watched = clean.toList()
            Thread {
                for (wait in longArrayOf(600, 900, 1500)) {
                    Thread.sleep(wait)
                    for (move in watched) {
                        if (gen != swapWatchGen) return@Thread
                        val now = appOnDisplay(move.toDisplay) ?: continue
                        if (now == move.pkg || now !in launcherPackages || taskDisplayOf(move.pkg) != move.toDisplay) continue
                        Log.w(TAG, "$now covered ${move.pkg} after the move — bringing it back to the front")
                        PServiceBridge.frontTask(this, move.pkg, move.toDisplay)
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** Bumped by every swap and the user's Home: a running post-swap watch stops. */
    @Volatile private var swapWatchGen = 0

    private fun launchOnDisplay(pkg: String, targetDisplayId: Int, aggressive: Boolean, fromDisplay: Int? = null) {
        val shizukuReady = ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()

        if (aggressive) {
            // A real force-stop needs root/shell authority; killBackgroundProcesses
            // is a weak last resort (and a no-op against other apps on Android 14+).
            when {
                PServiceBridge.isAvailable() -> {
                    Log.d(TAG, "Aggressive: pservice force-stop $pkg")
                    PServiceBridge.forceStop(pkg)
                }
                shizukuReady -> {
                    Log.d(TAG, "Aggressive: Shizuku force-stop $pkg")
                    ShizukuHelper.forceStop(pkg)
                }
                RootHelper.isAvailable() -> {
                    Log.d(TAG, "Aggressive: root force-stop $pkg")
                    RootHelper.forceStop(pkg)
                }
                else -> {
                    Log.d(TAG, "Aggressive: fallback kill $pkg")
                    tryKillApp(pkg)
                }
            }
            Thread.sleep(500)
        }

        if (!aggressive) {
            // Best move quality: reparent the LIVE task via binder — no new
            // instance (no duplicates), no relaunch (state preserved), works on
            // singleTask apps that ignore every launch-time hint. Ordered by how
            // little setup each needs on this device:
            //   1) AYN's pservice root daemon (zero setup, always on) → RootMover
            //   2) Shizuku's binder (needs the Shizuku app)
            //   3) su/root binder (needs a rooted device)
            if (PServiceBridge.isAvailable() &&
                PServiceBridge.moveTaskViaBinder(this, pkg, targetDisplayId, fromDisplay)) {
                Log.i(TAG, "pservice binder move → $pkg → display $targetDisplayId")
                return
            }
            if (TaskMover.moveTaskToDisplay(pkg, targetDisplayId)) return
            if (RootHelper.isAvailable() && RootHelper.moveTaskViaBinder(this, pkg, targetDisplayId, fromDisplay)) {
                Log.i(TAG, "Root binder move → $pkg → display $targetDisplayId")
                return
            }

            // Next: 'am start --display' with root/shell authority —
            // reuses the existing task in most cases (no MULTIPLE_TASK dupes)
            if (PServiceBridge.isAvailable() && PServiceBridge.startOnDisplay(this, pkg, targetDisplayId)) {
                Log.i(TAG, "pservice am start → $pkg → display $targetDisplayId")
                return
            }
            if (shizukuReady && ShizukuHelper.startOnDisplay(this, pkg, targetDisplayId)) {
                Log.i(TAG, "Shizuku am start → $pkg → display $targetDisplayId")
                return
            }
            if (RootHelper.isAvailable() && RootHelper.startOnDisplay(this, pkg, targetDisplayId)) {
                Log.i(TAG, "Root am start → $pkg → display $targetDisplayId")
                return
            }
            if (BuildConfig.DEBUG) Log.w(TAG, "No privileged launch worked for $pkg, falling back to trampoline")
        }

        // Trampoline fallback — CLEAR_TASK for aggressive, MULTIPLE_TASK for gentle
        val trampolineIntent = Intent(this, TrampolineActivity::class.java).apply {
            putExtra(TrampolineActivity.EXTRA_TARGET_PKG, pkg)
            putExtra(TrampolineActivity.EXTRA_TARGET_DISPLAY, targetDisplayId)
            putExtra(TrampolineActivity.EXTRA_AGGRESSIVE, aggressive)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        try {
            val opts = ActivityOptions.makeBasic()
            opts.setLaunchDisplayId(targetDisplayId)
            startActivity(trampolineIntent, opts.toBundle())
            Log.i(TAG, "${if (aggressive) "AGGRESSIVE" else "Gentle"} trampoline → $pkg → display $targetDisplayId")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Trampoline launch failed for $pkg: ${e.message}", e)
        }
    }

    /** Return a display to its launcher/home, via the best available backend. */
    // display → "pkg/Activity" of the launcher screen last seen there.
    private val launcherScreens = ConcurrentHashMap<Int, String>()

    private fun rememberLauncherScreen(displayId: Int, pkg: String, cls: String) {
        if (launcherScreens[displayId] == "$pkg/$cls") return
        val isActivity = runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
        if (isActivity) launcherScreens[displayId] = "$pkg/$cls"
    }

    /** Not seen yet (e.g. just after a restart): ask the activity list which launcher
     *  screen sits on [displayId]. One output line — pservice returns only the first. */
    private fun findLauncherScreen(displayId: Int): String? {
        if (!PServiceBridge.isAvailable() || launcherPackages.isEmpty()) return null
        val pk = launcherPackages.joinToString("|")   // (a "." matching any char is harmless here)
        val out = PServiceBridge.exec("dumpsys activity activities | sed -n '/^Display #$displayId/,/^Display #[^$displayId]/p' | grep -m1 -oE 'u0 ($pk)/[^ }]+'")
            ?.trim()?.removePrefix("u0 ")?.takeIf { it.contains('/') } ?: return null
        val (pkg, c) = out.split('/', limit = 2)
        val full = if (c.startsWith(".")) "$pkg/$pkg$c" else out
        Log.d(TAG, "Launcher screen on display $displayId: $full")
        return full
    }

    /** 1.1 — "Close this app": the app on the screen that has the controller is closed (force-stopped,
     *  like swiping it away in Recents) and that screen goes home. Never Wayfinder, a launcher or
     *  the system UI; a note says what was closed. */
    fun closeCurrentApp() {
        // 1.3: from the quick panel, the app it's about (the controller is on the panel's own screen)
        val d = if (QuickPanelWindow.isOpen) (panelReturnTo ?: PRIMARY_DISPLAY).also { QuickPanelWindow.close() }
            else focusedDisplayId()
        val pkg = (appOnDisplay(d) ?: displayApps[d])
            ?.takeIf { Shell.isPkg(it) && it != packageName && it !in ignoredPackages && it !in launcherPackages }
        if (pkg == null) { focusCue?.show(d, "Nothing to close on this screen"); return }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        vibrateShort()
        Thread {
            val ok = PServiceBridge.isAvailable() && PServiceBridge.forceStop(pkg)
            handler.post {
                if (!ok) { focusCue?.show(d, "Couldn't close $label"); return@post }
                displayApps.entries.removeAll { it.value == pkg }
                focusCue?.show(d, "Closed $label")
            }
            if (ok) goHomeOnDisplay(d)
        }.apply { isDaemon = true }.start()
    }

    private fun goHomeOnDisplay(displayId: Int) {
        // The bottom screen's home is a secondary launcher activity (e.g. a frontend's
        // external-display screen), which the HOME intent can't reach: "am start --display 4
        // -c HOME" brought the TOP screen's home to the front instead, covering the app just
        // sent up (2026-09-23). So re-open the launcher screen we saw there.
        if (displayId != PRIMARY_DISPLAY) {
            val comp = launcherScreens[displayId] ?: findLauncherScreen(displayId)?.also { launcherScreens[displayId] = it }
            if (comp != null && PServiceBridge.isAvailable()) {
                val out = Shell.component(comp)?.let { PServiceBridge.exec("am start --display $displayId -n $it -f 0x10020000") }
                Log.d(TAG, "Home on display $displayId → $comp: ${out?.trim()}")
                if (out != null && !out.contains("Error")) return
            }
            if (comp == null) { Log.d(TAG, "No known home for display $displayId — left as is"); return }
        }
        val ok = when {
            PServiceBridge.isAvailable() -> PServiceBridge.goHomeOnDisplay(displayId)
            ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission() ->
                ShizukuHelper.goHomeOnDisplay(displayId)
            RootHelper.isAvailable() -> RootHelper.goHomeOnDisplay(displayId)
            else -> false
        }
        if (ok) {
            Log.d(TAG, "Sent display $displayId to Home")
            return
        }
        // Non-privileged fallback: start the HOME intent aimed at that display.
        // Secondary displays may refuse it, but it's the best we can do without a backend.
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opts = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            startActivity(home, opts.toBundle())
            Log.d(TAG, "Sent display $displayId to Home (intent fallback)")
        } catch (e: Exception) {
            Log.w(TAG, "goHomeOnDisplay $displayId failed: ${e.message}")
        }
    }

    private fun tryKillApp(pkg: String) {
        // killBackgroundProcesses is the only kill available without Shizuku/root.
        // Below Android 14 it only kills background processes; on Android 14+ it
        // no longer affects other apps AT ALL (silently ignored). Best effort only —
        // the real fix is Shizuku's force-stop.
        try {
            val am = getSystemService(ActivityManager::class.java)
            am.killBackgroundProcesses(pkg)
            Log.d(TAG, "killBackgroundProcesses $pkg called")
        } catch (e: Exception) {
            Log.d(TAG, "killBackgroundProcesses $pkg: ${e.message}")
        }
    }

    private fun refreshDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        availableDisplayIds = dm.displays.map { it.displayId }
    }

    private val packagesReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { handler.post { detectLaunchers() } }
    }

    private fun detectLaunchers() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        // FallbackHome in com.android.settings responds to CATEGORY_HOME but isn't a real launcher
        val falsePositives = setOf("com.android.settings", "com.android.permissioncontroller")
        val launchers = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        for (ri in launchers) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in falsePositives) continue
            launcherPackages.add(pkg)
            ignoredPackages.add(pkg)
        }
        Log.d(TAG, "Detected launchers: $launcherPackages")
    }
}
