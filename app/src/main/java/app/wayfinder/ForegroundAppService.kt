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
         * connected, or a swap runs and another already waits for it (b18g: one is kept).
         */
        fun requestSwap(): Boolean = instance?.startSwapInBackground() ?: false

        /** Round 8 — an "Open…" combo: an app (on its screen: [OpenTargets.opensOnTop]), an app pair, or a Wayfinder page.
         *  Every Wayfinder launch of an app comes here: "Open…" combos, the quick panel's app tiles, the media card's Open. */
        fun open(arg: String?): Boolean {
            val svc = instance ?: return false
            if (!OpenTargets.valid(arg)) return false
            if (TourPractice.intercept(ThorAction.OPEN)) return true
            val a = arg!!
            return when {
                OpenTargets.isApp(a) -> {
                    val pkg = OpenTargets.appPkg(a)
                    if (svc.packageManager.getLaunchIntentForPackage(pkg) == null) {
                        Overlays.pill("That app isn't installed any more", CueOrigin.Press(ButtonNames.handSrc)); return false }
                    // 1.3.2 (GitHub #42): on a chosen screen, or the controller's. b18p (GitHub #107): the app's own "Opens on"
                    // counts too — this launch holds the move gate, so the window-event routing (maybeRoute) can't fix it after
                    val second = svc.secondDisplayId()
                    val shown = displayApps.entries.firstOrNull { it.value == pkg }?.key
                    val route = AppConfigStore.get(pkg).route
                    val top = OpenTargets.opensOnTop(a, route, second != null,
                        shownOnTop = shown?.let { it == PRIMARY_DISPLAY },
                        controllerOnTop = { svc.controllerDisplay() == PRIMARY_DISPLAY },
                        // another app's second screen there (its own doesn't count): it would open hidden under it
                        covered = { t -> (if (t) PRIMARY_DISPLAY else second)?.let { svc.presentationOwner(it) }?.let { it != pkg } == true })
                    if (BuildConfig.DEBUG) Log.d(TAG, "Open $pkg: ${if (top) "top" else "bottom"} (route $route, shown on $shown)")
                    val d = if (top) PRIMARY_DISPLAY else second
                    // already there, on a screen Wayfinder darkened: openLayout leaves the app alone (no wake) — wake the screen
                    svc.openLayout(if (top) pkg else null, if (top) null else pkg, "open").also { ok ->
                        if (ok && d != null && d == shown && svc.blanker?.isBlanked(d) == true) svc.handler.post { svc.blanker?.wake(d) } }
                }
                a.startsWith("pair:") -> Layouts.pairs.firstOrNull { it.id == a.removePrefix("pair:").toLongOrNull() }
                    ?.let { openPair(it) } ?: run { Overlays.pill("That app pair was deleted", CueOrigin.Press(ButtonNames.handSrc)); false }
                else -> { svc.handler.post { svc.openHubPage(a.removePrefix("page:")) }; true }   // takes the controller along
            }
        }

        /** "Stay awake" on? For the switches. */
        fun isSecondKeptAwake(): Boolean = instance?.stayAwake?.isHeld == true

        /** Route every button press to [cb] (and swallow it) until [stopCapture]. dev build 17 (theme G): connected
         *  controllers' D-pads come too meanwhile ([updatePadWatch]). */
        fun startCapture(cb: (ThorButton, Boolean) -> Unit): Boolean {
            val s = instance ?: return false
            val e = s.buttonEngine ?: return false
            e.capture = cb; s.updatePadWatch(); return true
        }
        fun stopCapture() { instance?.let { s -> s.buttonEngine?.capture = null; s.updatePadWatch() } }

        /** Apply the controller-lock settings now (with the on-screen cue). */
        fun reapplyFocusLock() { instance?.let { s -> s.handler.post { s.applyFocusLock(announce = true) } } }

        /** Re-apply the top app's 2nd-screen policy (after the user edits it). */
        fun quickPanelClosed() { instance?.let { it.handler.post { it.onQuickPanelClosed() } } }
        /** "Game controls" closed (Back to game): the controller goes back to the game. */
        /** 1.4 (Reddit): a finger is on the top or the bottom screen now (gyro "While touching a screen"). */
        fun fingerOnAScreen(): Boolean = instance?.let { it.topSlots.isNotEmpty() || it.mtActive.isNotEmpty() } ?: false
        fun gameControlsClosed() { instance?.let { it.handler.post { it.onGameControlsClosed() } } }
        /** The apps on the two screens now (not Wayfinder, not launchers) — the switch chip. */
        fun screenGames(): List<String> = instance?.screenGamesNow().orEmpty()
        fun screenMode(): Int = instance?.currentScreenMode() ?: 0
        /** The screen the controller works right now (null: service not running). */
        fun controllerScreen(): Int? = instance?.controllerDisplay()
        fun setScreenMode(mode: Int) { instance?.let { it.handler.post { it.applyScreenMode(mode) } } }
        /** QA2: bring a dark bottom screen back, whatever made it dark (Help → Fix). */
        fun repairBottomScreen() { instance?.repairBottom() }
        /** Docked ([Dock]): the second screen, when docking left it lit, gets Wayfinder's blank. True if done. */
        fun blankForDock(displayId: Int): Boolean {
            val b = instance?.blanker ?: return false
            if (b.isBlanked(displayId)) return false
            b.blank(displayId); return true
        }
        fun wakeFromDock(displayId: Int) { instance?.blanker?.let { if (it.isBlanked(displayId)) it.wake(displayId) } }

        fun later(ms: Long, block: () -> Unit) { instance?.handler?.postDelayed(block, ms) ?: block() }

        /** Build 18 (GitHub #71): a screenshot of [t] — the quick panel's tile, which asks (the combo takes Screens' choice). */
        fun screenshot(t: ShotTarget) { instance?.let { s -> s.handler.post { s.takeScreenshot(t) } } }
        /** A second (bottom) screen is there now. */
        fun hasSecondScreen(): Boolean = instance?.secondDisplayId() != null

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

        /** The Hub is on screen [displayId] / left: it takes the controller, then gives it back. */
        fun hubShown(displayId: Int, token: Int) { instance?.let { s -> s.handler.post { s.onHubShown(displayId, token) } } }
        /** Only the Hub that took it gives it back ([token]): a relocating Hub's old copy stops AFTER the new one took it. */
        fun hubHidden(token: Int) { instance?.let { s -> s.handler.post { s.onHubHidden(token) } } }
        /** Device pass (d3 N2): the window [token] on [displayId] that showed Game controls is the Hub now (a Hub page was asked
         *  of it — the quick panel's compass, a tile, an "Open Wayfinder" combo). */
        fun gameControlsToHub(displayId: Int, token: Int) { instance?.let { s -> s.handler.post { s.onGameControlsToHub(displayId, token) } } }
        /** Device pass (d3 N2): the Hub's window [token] shows Game controls from now (Home + X over a game asked it for it). */
        fun hubToGameControls(token: Int) { instance?.let { s -> s.handler.post { s.onHubToGameControls(token) } } }
        /** Build 18 review (E1): B on "Your combos" — the controller back to the Hub on [displayId], no lock written. */
        fun controllerToHub(displayId: Int) { instance?.let { s -> s.handler.post { s.giveHubTheController(displayId) } } }
        /** Keyboard & mouse (the deck) is on screen — it uses the virtual mouse too. */
        fun keyboardDeckShowing(): Boolean? = instance?.inputDeck?.isShowing
        /** Performance / fan / refresh rate changed: apply them — and ONLY them (re-running the
         *  bottom-screen policy blanked the screen the quick panel was open on — review 2026-09-25). */
        fun reapplyPerf() { instance?.let { s -> s.handler.post { s.applyPerf() } } }
        /** Re-apply the stick lights for the app that has the controller (after an edit). */
        fun reapplyLights() { instance?.let { s -> s.handler.post { s.applyLights(force = true) } } }
        /** The "Do not disturb while playing" switch changed: apply it now. */
        fun reapplyDnd() { instance?.let { s -> s.handler.post { s.applyDnd() } } }
        /** The running game changed: its own performance / fan / Hz / lights (or back to the app's). b18g (WI-6, g1 gn16): and
         *  Game controls opened before its game was known switches to it ([controlsGameFound]). */
        fun gameChanged() { instance?.let { s -> s.handler.post { s.applyPerf(); s.applyLights(force = true); s.controlsGameFound() } } }
        /** The app the quick panel was opened over (for "Now playing" and "Keep for this game"). */
        fun panelApp(): String? = instance?.let { s ->
            fun ok(p: String?) = p?.takeIf { it !in s.launcherPackages && it != s.packageName }
            val d = s.panelReturnTo ?: PRIMARY_DISPLAY
            // build 18 (E3, pF07): over Wayfinder's Hub there's no app to play or keep for — the tracked app is the one behind
            // the Hub (Wayfinder is never tracked): "Now playing" and "Keep" named a game that wasn't in front
            if (MainActivity.hubOn(d)) return@let null
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
         * connected or the action isn't wired yet. dev build 17 (theme G): [src] = the controller whose press it is (as its
         * presses count; 0 the Thor) — only Back reads it: on THAT controller's pad.
         */
        fun perform(action: ThorAction, src: Int = 0): Boolean {
            val svc = instance ?: return false
            // The tour's "try it" steps: ticked, not carried out — practising "move the app"
            // must not move the tour away (or send the controller off it)
            if (TourPractice.intercept(action)) return true
            return when (action) {
                ThorAction.SWAP_OR_SEND -> svc.startSwapInBackground()
                ThorAction.CLEAR_BACKGROUND -> svc.startClearInBackground()
                ThorAction.CLOSE_APP -> { svc.handler.post { svc.closeCurrentApp() }; true }
                ThorAction.RECENTS -> { svc.handler.post { svc.openRecents() }; true }
                // 1.4 (GitHub #43): on the controller copy when the input layer runs — the game's own Back. dev build 17 (theme
                // G): a connected controller's on its own pad
                ThorAction.BACK -> { svc.handler.post { if (svc.buttonEngine?.pressBackOnPad(src) != true) svc.performGlobalAction(GLOBAL_ACTION_BACK) }; true }
                ThorAction.TOGGLE_SECOND_SCREEN -> svc.toggleSecondScreen()
                // build 18 (GitHub #91, #83, #81)
                ThorAction.TOGGLE_TOP_SCREEN -> svc.toggleTopScreen()
                ThorAction.WAYFINDER_OFF -> { svc.handler.post { svc.turnOff() }; true }
                ThorAction.NOTHING -> true
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
                ThorAction.TOP_BRIGHTER -> { svc.stepBrightness(+1, PRIMARY_DISPLAY); true }
                ThorAction.TOP_DIMMER -> { svc.stepBrightness(-1, PRIMARY_DISPLAY); true }
                ThorAction.BOTTOM_BRIGHTER -> svc.secondDisplayId()?.let { svc.stepBrightness(+1, it); true } ?: false
                ThorAction.BOTTOM_DIMMER -> svc.secondDisplayId()?.let { svc.stepBrightness(-1, it); true } ?: false
                ThorAction.LOUDER -> { svc.stepVolume(+1, null); true }
                ThorAction.QUIETER -> { svc.stepVolume(-1, null); true }
                ThorAction.TOP_LOUDER -> { svc.stepVolume(+1, true); true }
                ThorAction.TOP_QUIETER -> { svc.stepVolume(-1, true); true }
                ThorAction.BOTTOM_LOUDER -> { svc.stepVolume(+1, false); true }
                ThorAction.BOTTOM_QUIETER -> { svc.stepVolume(-1, false); true }
                ThorAction.HOME_TOP -> { svc.homeOn(top = true, bottom = false); true }
                ThorAction.HOME_BOTTOM -> if (svc.secondDisplayId() == null) false else { svc.homeOn(top = false, bottom = true); true }
                ThorAction.HOME_BOTH -> { svc.homeOn(top = true, bottom = true); true }
                // 1.4
                ThorAction.RECORD_SCREEN -> { if (ScreenRecorder.recording) ScreenRecorder.stop() else ScreenRecorder.start(); true }
                ThorAction.CLOSE_OTHER -> if (svc.secondDisplayId() == null) false else { svc.handler.post { svc.closeCurrentApp(other = true) }; true }
                ThorAction.AYN_DRAWER -> { svc.openAynDrawer(); true }
                ThorAction.HOME_HERE -> { svc.handler.post { svc.buttonHost.nativePress(ThorButton.HOME) }; true }
                ThorAction.SWIPE_UP -> { svc.swipe(0, -1); true }
                ThorAction.SWIPE_DOWN -> { svc.swipe(0, 1); true }
                ThorAction.SWIPE_LEFT -> { svc.swipe(-1, 0); true }
                ThorAction.SWIPE_RIGHT -> { svc.swipe(1, 0); true }
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
    /** One move / swap / layout / route at a time — claimed atomically by whoever starts one, and every one ends with
     *  [endMove]. b18g (GitHub #99): a swap asked for while one runs waits for it ([MoveGate]). */
    private val moveGate = MoveGate()
    private val swapInProgress get() = moveGate.busy
    @Volatile private var clearInProgress = false
    private val handler = Handler(Looper.getMainLooper())
    private var overlayAnimator: OverlayAnimator? = null
    private var blanker: DisplayBlanker? = null

    // ── Auto-off idle timer ───────────────────────────────────────────
    private val idleCheckMs = 2000L
    // True only while the SECOND screen is blanked BY the idle timer (so we never
    // auto-wake a screen the user blanked manually, and know when to auto-wake).
    @Volatile private var idleBlanked = false
    /** 1.4: "Always bottom" and the bottom went dark — the controller is on top until it wakes. */
    @Volatile private var darkLockedBottom = false
    /** 1.4: the app on the second screen and when it came (the auto-off timer counts from it). */
    private var idleApp: String? = null
    private var idleAppAt = 0L
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
    private val mtActive: MutableMap<Int, Touch> = java.util.concurrent.ConcurrentHashMap()   // slot → finger (bottom panel); read by the gyro (1.4)
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
        val game = controllerDisplay()
        val deckDisplay = (if (game == PRIMARY_DISPLAY) secondDisplayId() else PRIMARY_DISPLAY) ?: run {
            Overlays.pill("Keyboard & mouse needs the second screen", CueOrigin.Screen(game)); return
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
        val game = controllerDisplay()
        val other = if (game == PRIMARY_DISPLAY) secondDisplayId() else PRIMARY_DISPLAY
        val (pkg, label, dark) = deckContext(game)
        deck.show(other ?: game, game, pkg, label, dark, onOpenHub = { deck.hide() },
            onFocusGame = { focusAction(top = game == PRIMARY_DISPLAY) }, guideOnly = true, beside = other == null)
    }

    /** Build 18 (E5, pF04): a window changed on [d] (null: somewhere): once it has settled, the deck's "Sending to" names the
     *  app now on its game's screen. Only while the deck is up and serves another screen than its own. */
    private fun deckFollow(d: Int?) {
        val deck = inputDeck?.takeIf { it.isShowing } ?: return
        val game = deck.gameDisplay ?: return
        if (d != null && d != game) return
        handler.removeCallbacks(deckFollowRun); handler.postDelayed(deckFollowRun, 300)
    }
    private val deckFollowRun = Runnable {
        val deck = inputDeck?.takeIf { it.isShowing } ?: return@Runnable
        val game = deck.gameDisplay ?: return@Runnable
        val top = appOnDisplay(game) ?: displayApps[game]
        // build 18 review (E5): a Wayfinder window that isn't the Hub — Game controls opened over the game (Home + X), the
        // combos sheet — belongs to the app under it (as MainActivity.hubOn says): the deck keeps serving that app. Retargeted,
        // it read "Sending to: Wayfinder", its pad pick went to Wayfinder, and B in Game controls closed the deck, not the page
        if (top == packageName && !MainActivity.hubOn(game)) return@Runnable
        val pkg = top?.takeIf { it !in launcherPackages }
        val label = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        deck.retarget(pkg, label)
    }

    /** For the deck: the app on [display], its name, and whether the deck is dark (the Hub's theme). */
    private fun deckContext(display: Int): Triple<String?, String?, Boolean> {
        // Read the screen's real top app now: the event-tracked map can lag (e.g. an app
        // opened from a launcher alias arrives with no display id).
        val pkg = appOnDisplay(display) ?: displayApps[display]
        val label = pkg?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        return Triple(pkg, label, app.wayfinder.ui.GlassTokens.isDark(this))   // dev build 17 (WP10): the one dark rule
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
        // 1.4 (review): "Always bottom" while the bottom screen is dark → the top one, until it wakes
        if (AppSettings.focusLockTop || darkLockedBottom) PRIMARY_DISPLAY else (secondDisplayId() ?: PRIMARY_DISPLAY)

    fun applyFocusLock(announce: Boolean = false) {
        val on = AppSettings.focusLockEnabled
        val top = AppSettings.focusLockTop
        val target = lockTarget()
        fun done() {
            if (announce) {
                vibrateShort()
                if (on) Overlays.pill("Controller locked to the ${screenName(target)}", CueOrigin.Screen(target), group = "focus")
                else Overlays.pill("Controller unlocked — it follows your touch", CueOrigin.Press(ButtonNames.handSrc), group = "focus")
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
                        // 4 quick tries, then slower ones (~5 s in all): just after a wake the screens ignore the
                        // probe's tap for a moment (1.4 — the controller stayed on the bottom after waking)
                        if (real != null && real != PRIMARY_DISPLAY && attempt < 10) {
                            Log.v(TAG, "top lock: focus still on $real (attempt $attempt) — retrying before arming")
                            handler.postDelayed({ armWhenOnTop(attempt + 1) }, if (attempt < 4) 250L else 600L)
                            return@Thread
                        }
                        // never armed from the bottom screen (AYN's bad case) — asleep: at the wake
                        if (real != null && real != PRIMARY_DISPLAY) {
                            Log.w(TAG, "top lock: focus still on $real after $attempt tries — not arming")
                            if (!screenOn) lockOnWake = true
                            handler.post { if (!announced) { announced = true; done() } }; return@Thread
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
    /** dev build 17 (WP10): the cues back if a capture never called back — one at a time (a second screenshot restarts it). */
    private val unstashLate = Runnable { Overlays.unstash() }

    /** [target]: the Screens choice (the combo), or the one picked on the quick panel's tile (build 18, GitHub #71). */
    fun takeScreenshot(target: ShotTarget = AppSettings.shotTarget) {
        val s = screenshotter ?: return
        val ours = currentScreenMode()                          // Wayfinder's own blank (1 bottom off, 2 top off)
        // 1.4 (GitHub #67): the game, not Wayfinder — close the quick panel, hide the hints, let them fade out
        // (only when the panel is on a screen that's captured)
        val shotDisplays = when (target) {
            ShotTarget.TOP -> setOf(PRIMARY_DISPLAY); ShotTarget.BOTTOM -> setOfNotNull(secondDisplayId())
            else -> setOfNotNull(PRIMARY_DISPLAY, secondDisplayId()) }
        val panelWasOpen = QuickPanelWindow.isOpen && QuickPanelWindow.displayId in shotDisplays
        if (panelWasOpen) QuickPanelWindow.close()
        // dev build 17 (WP10): the cues hidden for the capture and put back after it (they were destroyed) — 6 s at the
        // latest, if the capture never calls back
        Overlays.stash(); handler.removeCallbacks(unstashLate); handler.postDelayed(unstashLate, 6000)
        Thread {
            if (panelWasOpen) Thread.sleep(450)
            // 1.3 (GitHub #18): "both screens" leaves out a screen that's off — Wayfinder's blank, AYN's own
            // (holding the AYN button: dual_screen_display_mode), or a display that's really off
            var t = target
            if (t == ShotTarget.BOTH) {
                val second = secondDisplayId()
                val mode = if (ours != 0) ours else if (PServiceBridge.cachedAvailable()) aynScreenMode() else 0
                fun off(id: Int) = getSystemService(DisplayManager::class.java).getDisplay(id)?.state == android.view.Display.STATE_OFF
                val bottomOff = second == null || mode == 1 || off(second)
                val topOff = mode == 2 || off(PRIMARY_DISPLAY)
                t = when { bottomOff && !topOff -> ShotTarget.TOP; topOff && !bottomOff -> ShotTarget.BOTTOM; else -> ShotTarget.BOTH }
            }
            handler.post { shoot(s, t) }
        }.apply { isDaemon = true }.start()
    }

    private fun shoot(s: Screenshotter, target: ShotTarget) {
        s.capture(PRIMARY_DISPLAY, secondDisplayId(), target) { name ->
            val where = if (target == ShotTarget.BOTTOM) (secondDisplayId() ?: PRIMARY_DISPLAY) else PRIMARY_DISPLAY
            handler.removeCallbacks(unstashLate); Overlays.unstash()
            if (name != null) { vibrateShort(); Overlays.pill("Screenshot saved in Pictures / Screenshots", CueOrigin.Screen(where), group = "shot") }
            else Overlays.pill("The screenshot didn't work", CueOrigin.Screen(where), group = "shot")
        }
    }

    /** Stick flicks for Home/Back combos: from the raw pad when the input layer is off (the
     *  stick then still reaches the game — [AnalogShield]), from the events the layer withheld
     *  from the game when it's on. Two detectors: the streams never mix. */
    private val rawFlicks = FlickDetector { b -> buttonEngine?.onFlick(b) }
    // one per controller (dev build 14): two controllers' sticks mixed in one detector, and a combo is one controller's
    private val gatedFlicks = java.util.concurrent.ConcurrentHashMap<Int, FlickDetector>()

    /** Input layer: a pad event the game did NOT get because Home/Back was held (printed keys). */
    private fun onGatedEvent(type: Int, code: Int, value: Int, src: Int = 0) {
        // Wayfinder's combos: this controller as its keys count it (dev build 14, combos 5: per controller)
        when (type) {
            ThorInput.EV_KEY -> if (value != 2) {
                if (value == 1) Overlays.anyButton()            // dev build 17 (WP10): a press the game didn't get counts too
                // a Home-less pad's Select + Start just became its Home: that Home's release is no Home tap (combos 10)
                if (code == ThorInput.KEY_HOME && value == 1 && src > 0) buttonEngine?.markSynthHome(ExtPadsCtl.srcOfGated(src))
                ExtEngine.onGated(code, value, src)             // the shift layer (§6l) — of that controller
                GATED_KEYS[code]?.let { buttonEngine?.onGated(it, value != 0, ExtPadsCtl.srcOfGated(src), ExtEngine.shiftOf(src)) }
            }
            ThorInput.EV_ABS -> when (code) {
                ThorInput.ABS_HAT_X, ThorInput.ABS_HAT_Y -> buttonEngine?.onHat(code, value, ExtPadsCtl.srcOfGated(src))
                ThorInput.ABS_LX, ThorInput.ABS_LY, ThorInput.ABS_RX, ThorInput.ABS_RY -> gatedFlicks.getOrPut(src) {
                    FlickDetector { b -> buttonEngine?.onFlick(b, ExtPadsCtl.srcOfGated(src)) } }.onAbs(code, value)
            }
        }
    }
    /** b18o (2026-10-11 — #81's turbo leftover): a turbo button the hand holds ([value] 1) or let go, whose job is the
     *  copy's / a controller pad's button [code] (printed), pressed at [at] — said by the input layer or a controller's reader
     *  ("TU"). Its on / off pulses are then no presses for the combos ([ButtonEngine.onTurbo]). Code 0: every one let go. */
    private fun onTurboLine(code: Int, value: Int, at: Long, src: Int) {
        val s = ExtPadsCtl.srcOfGated(src)
        if (code == 0) { buttonEngine?.turboGone(s, ButtonEngine.TURBO_LAYER); return }
        GATED_KEYS[code]?.let { buttonEngine?.onTurbo(it, value != 0, s, at, ButtonEngine.TURBO_LAYER) }
    }
    private val GATED_KEYS = mapOf(
        ThorInput.BTN_A to ThorButton.A, ThorInput.BTN_B to ThorButton.B, ThorInput.BTN_X to ThorButton.X,
        ThorInput.BTN_Y to ThorButton.Y, ThorInput.BTN_L1 to ThorButton.L1, ThorInput.BTN_R1 to ThorButton.R1,
        ThorInput.BTN_L2 to ThorButton.L2, ThorInput.BTN_R2 to ThorButton.R2, ThorInput.BTN_SELECT to ThorButton.SELECT,
        ThorInput.BTN_START to ThorButton.START, ThorInput.BTN_L3 to ThorButton.L3, ThorInput.BTN_R3 to ThorButton.R3,
    )


    /** The app that really has the controller (its top window), null for Wayfinder itself. */
    private fun controllerApp(): String? {
        val top = appOnDisplay(controllerDisplay())
        wayfinderOnTop = top == packageName
        return if (top == packageName) null else top ?: buttonHost.currentApp()
    }
    /** dev build 15: Wayfinder's own window has the controller ([controllerApp] said null for it). */
    @Volatile private var wayfinderOnTop = false

    /** The ONE answer to "where is the controller" ([ControllerScreen]): the Hub's strip, the held list and every cue, the app
     *  combos act on, Home, the deck, the panel's return. Device pass (P2-2): while a page holds it against the lock ([lockSuspended]
     *  — B on "Your combos" gave it back to the Hub, Game controls) it is where the input focus is, as with no lock: the lock's
     *  screen was read instead — the strip said "Bottom screen" and holding Home put the list on the bottom with the Hub driven
     *  on top. */
    private fun controllerDisplay(): Int = ControllerScreen.of(AppSettings.focusLockEnabled, lockSuspended(), lockTarget(), focusedDisplayId())
    /** The lock decides where the controller is: it's on and no page holds the controller against it. */
    private fun lockHolds(): Boolean = AppSettings.focusLockEnabled && !lockSuspended()

    /** Device pass (P2-2): where the controller is — pure (OverlayRulesTest). [lockOn] and nothing holding it against the lock
     *  ([suspended]): the lock's screen; else where the input focus is. */
    internal object ControllerScreen {
        fun of(lockOn: Boolean, suspended: Boolean, lockTarget: Int, focused: Int): Int = if (lockOn && !suspended) lockTarget else focused
        /** Device pass (final): a page holds the controller against the lock — the Hub that took it ([hubTook]), or Game controls
         *  open ([controlsOpen]) on another screen than the game's ([controlsAside]). On the lock's own screen nothing is held. */
        fun suspended(hubTook: Boolean, controlsOpen: Boolean, controlsAside: Boolean): Boolean = hubTook || (controlsOpen && controlsAside)
    }
    /** Keeps the layer on the current app's profile. A cheap check twice a second is simpler and
     *  sturdier than hooking every way the controller's app can change (focus, swaps, locks). */
    private var gameAskedFor: String? = null
    private var gameTicks = 0
    /** b18i (D2): the tick's count while Game controls waits for its game ([controlsWait]). */
    private var waitTicks = 0
    private var lastProfileKey: String? = null
    private var lastOrderKey: String? = null
    private val padProfileTick = object : Runnable {
        override fun run() {
            // The REAL top app of the controller's screen: the tracked one ignores Wayfinder's own
            // windows, so with the Hub open over a game the game's remap applied to the Hub — A
            // became B (= Back) in the remap editor itself (2026-09-24). Wayfinder never gets one.
            val app = controllerApp()
            // which game it runs (per-game profiles): only for an app that HAS game profiles, or while
            // the controls page is open (to offer one) — at once on a switch, then every 4 s. Never
            // for launchers: the root helper scans /proc for this.
            // dev build 16 (WP4): or a game a connected controller has its own controls for (the Thor none)
            val wantGame = app != null && app !in launcherPackages && app !in ignoredPackages &&
                (GameProfiles.forApp(app).isNotEmpty() || PadProfiles.hasGamesOf(app) || MainActivity.gameControls != null)
            if (!wantGame) { if (gameAskedFor != null) { gameAskedFor = null; GameProfiles.ask(null) } }
            else if (app != gameAskedFor || ++gameTicks >= 8) { gameAskedFor = app; gameTicks = 0; GameProfiles.ask(app) }
            // b18i (D2, B11): Game controls opened on its app's page before the game was known, and nothing above asks while the
            // page has the controller (Wayfinder's window: no app). Ask again every 2 s while the page can still switch to it
            // (its 10 s, on the page it opened on): a later look sees what one look couldn't (a game read among several files)
            val waiting = controlsWait
            if (waiting == null || !waiting.inTime(android.os.SystemClock.uptimeMillis()) || !waiting.onItsPage(MainActivity.gameControlsPage())) waitTicks = 0
            else if (++waitTicks >= 4) { waitTicks = 0; GameProfiles.ask(waiting.pkg) }
            val key = app?.let { GameProfiles.activeKey(it) }
            PadLayerCtl.follow(this@ForegroundAppService, app,   // 1.4: where the layer runs
                neutral = app == null || app in launcherPackages || app in ignoredPackages, overlay = app == null || app in ignoredPackages)
            // the app's own buttons (keys, macros, long press…): nothing held survives a switch — of
            // the profile itself, not only of the engine's tokens (two profiles can share tokens)
            // 1.3: the Wayfinder keyboard open = the controller is the keyboard's (A types, the D-pad moves
            // the cursor): the app's Game controls pause until it closes (web preset: A = click)
            val kbOpen = ThorKeyboardService.isOpen()
            val spec = if (kbOpen) "" else padProfileFor(key)
            val switched = PadLayerCtl.wanted && PadLayerCtl.setProfile(spec)
            // Wayfinder itself on top (Game controls over a game): the game's player order stays - following "no app"
            // (the global order) re-made every controller twice, at the page's opening and closing
            if (app != null) lastOrderKey = key
            // external controllers: the same game controls (where the layer runs), this game's player 1, their own controls.
            // dev build 14 (combos 1): not while Home / Back is held — a change made a controller's reader let go of
            // everything and press it again, Home included: Home + X opened Game controls, its release then went home.
            // Sent at the release ([ButtonEngine.Host.systemReleased])
            // dev build 15 (C5): and the game app that has them — the player order waits while a game runs (critique R8:
            // "just started" = a game detected in it ≤ 15 s ago, any game, not only one with a profile)
            val fg = app?.takeIf { it !in launcherPackages && it !in ignoredPackages }
            val start = fg != null && GameProfiles.runningIn(fg) != null &&
                android.os.SystemClock.uptimeMillis() - GameProfiles.gameStartedAt(fg) < 15_000
            // dev build 16 (WP4, X7): the controllers' own controls follow the game RUNNING there, not the Thor's profile key —
            // in a game the Thor has no profile for they only flicked on as Game controls opened (Home + X makes the Thor's
            // game profile a moment before it takes the controller). No game app (Wayfinder, a launcher): null, neutral (X3)
            val padGame = fg?.let { GameProfiles.runningKey(it) }
            extPending = ExtState(if (PadLayerCtl.wanted) spec else "", if (app == null) lastOrderKey else key, padGame, fg, wayfinderOnTop, start)
            if (!ButtonEngine.systemHeld) sendExtState()
            val profKey = if (kbOpen) "keyboard" else key
            if (switched || profKey != lastProfileKey) { lastProfileKey = profKey; ExtEngine.releaseAll() }
            ExtEngine.remap = if (kbOpen) null else key?.let { Profiles.get(it).remap }
            // gyro: needs the layer (its stick goes through our copy of the pad, its on/off button too)
            GyroEngine.follow(key, key?.takeIf { PadLayerCtl.wanted }?.let { Profiles.get(it).remap?.gyro })
            handler.postDelayed(this, 500)
        }
    }

    /** What the tick has for the connected controllers (main thread): their game controls, whose player order, which game
     *  runs ([padGame], dev build 16: their own controls' — not the Thor's profile key). */
    private class ExtState(val spec: String, val orderKey: String?, val padGame: String?, val fg: String?, val wf: Boolean, val start: Boolean)
    private var extPending: ExtState? = null
    private fun sendExtState() {
        val s = extPending ?: return
        extPending = null
        ExtPadsCtl.setGameProfile(s.spec)
        ExtPadsCtl.follow(this, s.orderKey)
        ExtPadsCtl.setGame(this, s.padGame)
        ExtPadsCtl.setForeground(this, s.fg, s.wf, s.start)
    }
    /** Home / Back let go: the controllers' part of the tick now — a moment after, so a reader pressing Home again at
     *  once (still held: [ButtonEngine]'s same-press rule) keeps it waiting. */
    private val extAfterRelease = Runnable { if (!ButtonEngine.systemHeld) sendExtState() }

    /** Raw controller events (evdev): only the D-pad matters here — keys come via the a11y filter. */
    private fun onControllerEvent(type: Int, code: Int, value: Int) {
        if (type == ThorInput.EV_ABS && !PadLayerCtl.active &&
            (code == ThorInput.ABS_LX || code == ThorInput.ABS_LY || code == ThorInput.ABS_RX || code == ThorInput.ABS_RY)) {
            rawFlicks.onAbs(code, value)
            return
        }
        if (type == ThorInput.EV_ABS && (code == ThorInput.ABS_HAT_X || code == ThorInput.ABS_HAT_Y)) {
            recentsHat(code, value, lastHatX) { lastHatX = it }
            buttonEngine?.onHat(code, value)
        }
    }

    /** Recents browses on a D-pad's fresh left / right ([last]: that D-pad's X before, [setLast] keeps it) — the HAT can
     *  report the same direction again. dev build 17 (theme G): the Thor's raw D-pad's and each connected controller's. */
    private fun recentsHat(code: Int, value: Int, last: Int, setLast: (Int) -> Unit) {
        val freshX = code == ThorInput.ABS_HAT_X && value != 0 && last == 0
        if (code == ThorInput.ABS_HAT_X) setLast(value)
        if (recentsDisplay != null && freshX) recentsClearAt = 0L           // build 18 (E8): browsing starts "close all" over
        if (recentsDisplay != null && freshX) handler.post {
            val d = recentsDisplay
            if (d != null && recentsStillOpen(d)) recentsBrowse(value) else exitRecents()
        }
    }

    // ── dev build 17 (theme G): a connected controller's D-pad, watched ──
    /** Connected controllers' D-pads to the app ("V" lines) while it reads them — Recents open, a press being captured (Find
     *  by pressing, Listen, macro recording, the Combos page). The Thor's comes raw anyway; one feeding its copy too. */
    private fun updatePadWatch() = ExtPadsCtl.setWatch(buttonEngine?.capturing == true || recentsDisplay != null)
    /** Each controller's last D-pad X (its raw source): Recents browses on a fresh press. */
    private val padHatX = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** A watched control of controller [raw] (any thread): its D-pad browses Recents and reaches the combos / a capture as its
     *  presses count. Under its Home the same change also comes as a withheld press: [ButtonEngine.onHat] takes it once. */
    private fun onPadWatched(raw: Int, type: Int, code: Int, value: Int) {
        if (type != ThorInput.EV_ABS || (code != ThorInput.ABS_HAT_X && code != ThorInput.ABS_HAT_Y)) return
        recentsHat(code, value, padHatX[raw] ?: 0) { padHatX[raw] = it }
        buttonEngine?.onHat(code, value, ExtPadsCtl.srcOfGated(raw))
    }

    // ── Recents with the controller ─────────────────────────────────────
    // Android's overview ignores the pad, so while it's open we drive it with small
    // touch gestures: ←/→ (or L1/R1) browse, A opens, Y closes the app, Select = Clear
    // all, B leaves. A pill shows the controls.
    @Volatile private var recentsDisplay: Int? = null
    @Volatile private var recentsEnteredAt = 0L

    private fun enterRecents(displayId: Int, retry: Boolean = false) {
        // The window list can lag the event a little: check now, and once more shortly after.
        if (!recentsStillOpen(displayId)) {
            if (!retry) handler.postDelayed({ enterRecents(displayId, retry = true) }, 350)
            else Log.d(TAG, "recents: event on $displayId but not on top — ignored")
            return
        }
        Log.d(TAG, "recents: enter on $displayId")
        // Recents sends its event twice — a duplicate comes within moments; later = a new visit (a missed exit)
        val now = android.os.SystemClock.uptimeMillis()
        if (recentsDisplay == displayId && now - recentsEnteredAt < 1500) return
        recentsEnteredAt = now
        // Remember the app we came from (the card Recents opens centred on) for B.
        // Where B goes back to: what was on screen when WE opened Recents (the home screen
        // counts — Recents centres on the last app, so its card was wrong from home);
        // opened some other way → the card Recents centres on.
        val asked = recentsOrigin?.takeIf { android.os.SystemClock.uptimeMillis() - recentsOriginAt < 3000 }
        // build 18 review (E4, pF02): over the Hub = Recents came up on the screen where the Hub was in front — not the lock's
        // screen: the Hub holds the controller while it's open, the lock set aside ([onHubShown]). Asked over the Hub but up on
        // the other screen: that screen's app
        val origin = when {
            asked == null -> null
            recentsHub == displayId -> WAYFINDER_ORIGIN
            asked == WAYFINDER_ORIGIN -> displayApps[displayId] ?: HOME_ORIGIN
            else -> asked
        }
        recentsOrigin = null; recentsHub = null
        recentsFrom = when {
            origin == HOME_ORIGIN -> HOME_ORIGIN
            origin == WAYFINDER_ORIGIN -> WAYFINDER_ORIGIN          // build 18 (E4, pF02)
            origin != null -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(origin, 0)).toString() }.getOrNull()
            else -> null
        }
        if (recentsFrom == null) handler.postDelayed({ if (recentsFrom == null) recentsFrom = centredCard(displayId)?.contentDescription?.toString() }, 300)
        browseBusy = false; browseQueued = 0
        recentsClearAt = 0L                                     // build 18 (E8): a new visit asks again
        recentsDisplay = displayId
        updatePadWatch()                                        // dev build 17 (theme G): connected controllers' D-pads too
        // A few seconds is enough (it covers the card's app icon).
        fun m(b: ThorButton) = Say.menu(b)   // the button for that meaning, in the hand's names (dev build 15, WP2; 17: Say)
        // build 18 (E4, pF19 / MORE-11): Select and Start named on the controller in hand ("Create" / "Options" on a DualSense) —
        // plain names, a cue is View text
        val select = Say.btn(ThorButton.SELECT); val start = Say.btn(ThorButton.START)
        // build 18 (E8, ps-F3): X closes a card too (a Thor habit) — the hint names both; close all takes two presses
        val close = "${m(ThorButton.Y)} / ${m(ThorButton.X)}"
        // 1.3 (GitHub #13): full, compact (one slim line of symbols, no title) or off; top centre or a corner
        when (AppSettings.recentsHint) {
            2 -> {}
            // build 18 (E4 addendum, MORE-D19): the symbols are drawn icons (FocusCue: CueIcons), never a typed ↗ × ⌂ ↩
            1 -> Overlays.held(Held.RECENTS, CueOrigin.Screen(displayId), emptyList(), listOf(
                "◀ ▶" to "", m(ThorButton.A) to CueIcons.OPEN.toString(), close to CueIcons.CLOSE.toString(),
                "$select ×2" to CueIcons.CLOSE_ALL.toString(), start to CueIcons.HOME.toString(), m(ThorButton.B) to CueIcons.BACK.toString()),
                at = AppSettings.recentsHintAt, compact = true)
            // 1.3.2: one line with words, at the very top (a titled two-row card covered the middle card)
            else -> Overlays.held(Held.RECENTS, CueOrigin.Screen(displayId), emptyList(), listOf(
                "Recent apps" to "", "◀ ▶" to "browse", m(ThorButton.A) to "open", close to "close",
                select to "twice: close all", start to "home", m(ThorButton.B) to "back"), at = AppSettings.recentsHintAt, compact = true)
        }
    }

    private fun exitRecents() {
        val d = recentsDisplay ?: return
        Log.d(TAG, "recents: exit")
        recentsDisplay = null
        updatePadWatch()
        Overlays.release(Held.RECENTS)                          // dev build 17 (WP10): only its own hint
        recentsLeftOn = d; recheckAfterRecents()                // b18j: what is in front there once Recents is gone
    }

    /** b18j (device b18v v3): the screen Recents was left on, read again once Recents is gone from it ([recheckAfterRecents]);
     *  null = nothing to read. An app or home window event for that screen settles it too. */
    private var recentsLeftOn: Int? = null

    /** b18j (device b18v v3, PPSSPP 2 of 2): Recents closed — the screen it covered is read once Recents is gone from it (now, or
     *  at the next window change: Recents may still be animating out). The app in front is tracked (an app Quickstep kept live
     *  under Recents comes back with no window event of its own: RetroArch's window was hidden under Recents and sent one when
     *  it showed again, PPSSPP's stayed visible and sent none); the home in front: nothing is tracked there. Main thread. */
    private fun recheckAfterRecents() {
        val d = recentsLeftOn ?: return
        if (recentsDisplay == d || !recentsGone(d)) return   // still (or again) Recents there, or unreadable: the next window change
        if (swapInProgress) { recentsLeftOn = null; return }
        val top = appOnDisplay(d)
        val launcher = top != null && top in launcherPackages
        val app = top != null && !launcher && Shell.isPkg(top) && top != packageName && top !in ignoredPackages && top != currentImePackage()
        when (RecentsRules.after(top, displayApps[d], launcher, app, own = top == packageName)) {
            RecentsRules.After.WAIT -> return
            RecentsRules.After.DONE -> {}
            RecentsRules.After.DROP -> {
                Log.d(TAG, "after Recents: the home on display $d — no app tracked there")
                displayApps.remove(d); onTopAppMaybeChanged()
            }
            // the app Recents was over, back without its own event: not one just opened (no route, no first-game tip)
            RecentsRules.After.TRACK -> top?.let { resolvedDisplay(it, d, fresh = false) }
            RecentsRules.After.CHECK -> displayApps[d]?.let { checkStillThere(it, d) }
        }
        recentsLeftOn = null
    }

    /** b18j (review, E4): Recents opened from the Hub over a game, the game's card closed (Y / X / a finger), B back to the Hub —
     *  the closed game stayed tracked while the Hub was open: Recents no longer clears it, the gone-window reconcile skips the
     *  screen while Recents is up and then counts the Hub's window as another one, and the read after Recents sees only the Hub.
     *  Do not disturb while playing, its performance mode, fan and lights, the panel's game card stayed on it (b18i cleared it
     *  when Recents opened, closed or not). One read of the task list (root, off the main thread) settles it: [pkg] with no
     *  activity left on [d] is gone. Unknown (no root, a failed read) or still there: kept, as before. Main thread. */
    private fun checkStillThere(pkg: String, d: Int) {
        Thread {
            val on = taskOnDisplay(pkg, d)
            handler.post {
                // still tracked there and still under Wayfinder's window: what was read is what is on that screen now
                if (on == false && !swapInProgress && displayApps[d] == pkg && appOnDisplay(d) == packageName) {
                    Log.d(TAG, "after Recents: display $d's app has no activity left there — no longer tracked")
                    displayApps.remove(d); onTopAppMaybeChanged()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** b18j (review): whether [pkg] has an activity on display [d] in Android's activity list (root; one line) — true / false;
     *  null: unknown (no root daemon, the read failed or listed no display). [pkg] is a tracked package (Shell.isPkg). */
    private fun taskOnDisplay(pkg: String, d: Int): Boolean? {
        if (!PServiceBridge.isAvailable()) return null
        return RecentsRules.onDisplay(PServiceBridge.exec("dumpsys activity activities | awk '/^Display #/{n++; s=substr(\$2,2)} " +
            "index(\$0,\"mActivityComponent=$pkg/\") && s==\"$d\"{f=1; exit} END{print (f ? \"ON\" : (n ? \"NONE\" : \"UNREAD\"))}'"))
    }

    /** b18j: Recents — Android's overview — and what follows it. Pure (RecentsTrackingTest). */
    internal object RecentsRules {
        /** [pkg]'s window event with class [cls] is Recents' own ([own]: Wayfinder, never). */
        fun isRecents(pkg: String, cls: String, own: String): Boolean =
            pkg != own && (cls.contains("RecentsActivity") || (cls.contains("quickstep", ignoreCase = true) && cls.contains("Recents")))

        /** The top app window of a screen, read, holds [panels] overview panels: none = Recents is gone from it. */
        fun gone(panels: Int): Boolean = panels == 0

        enum class After { WAIT, DONE, TRACK, DROP, CHECK }
        /** Recents gone from a screen: [top] is the package of the app window in front there (null: unreadable), [tracked] the app
         *  tracked there, [launcher] / [app] / [own] what [top] is (a home; an app to track — not Wayfinder, the keyboard or an
         *  ignored one; Wayfinder's own window). WAIT: read again at the next window change; DONE: as it is; TRACK: [top] is the
         *  screen's app; DROP: no app; CHECK (b18j review): Wayfinder's window (Recents opened from the Hub, E4, and B back to
         *  it) hides the tracked app, whose card may have been closed in Recents — the task list says whether it is still there. */
        fun after(top: String?, tracked: String?, launcher: Boolean, app: Boolean, own: Boolean): After = when {
            top == null -> After.WAIT
            launcher -> if (tracked != null) After.DROP else After.DONE
            own && tracked != null -> After.CHECK
            !app || top == tracked -> After.DONE
            else -> After.TRACK
        }

        /** b18n (GitHub #102 b): the post-swap watch's call — [now], the package in front on the moved app's new screen, covers
         *  [moved] and the moved app is brought back: a home ([launcher]: a frontend such as iiSU answering its screen coming
         *  back), but never Recents ([recents]: that screen shows the overview; launcher3 hosts it and answers CATEGORY_HOME, yet
         *  it is the user's own — closed for the moved app, it "jumped to the previous app"). */
        fun coversMoved(now: String, moved: String, launcher: Boolean, recents: Boolean): Boolean = now != moved && launcher && !recents

        /** b18j (review): the task list's answer for an app on a screen ([taskOnDisplay]'s one line). ON: it has an activity there;
         *  NONE: the list was read (a display seen) and it has none; anything else (UNREAD, no answer, an error): unknown. */
        fun onDisplay(out: String?): Boolean? = when (out?.trim()) { "ON" -> true; "NONE" -> false; else -> null }
    }

    /** b18g (GitHub #49): the whole screen from [ScreenSize], never this service's configuration (it follows the focus). */
    private fun recentsSize(d: Int): Pair<Float, Float>? =
        ScreenSize.real(this, d)?.let { it.x.toFloat() to it.y.toFloat() }

    private fun recentsGesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val d = recentsDisplay ?: return
        val (w, h) = recentsSize(d) ?: return
        val path = android.graphics.Path().apply { moveTo(x1 * w, y1 * h); lineTo(x2 * w, y2 * h) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, ms))
            .setDisplayId(d).build()
        val sent = injectGesture(g, object : GestureResultCallback() {
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

    /** b18j: Recents is provably gone from display [d] — its top app window is read and shows no overview. An unreadable window
     *  proves nothing ([recentsStillOpen] says "not open" for it): the window list changes often while Recents is up. */
    private fun recentsGone(d: Int): Boolean {
        val top = runCatching { windowsOnAllDisplays.get(d) }.getOrNull().orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer } ?: return false
        val root = top.root ?: return false
        return RecentsRules.gone(root.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/overview_panel")?.size ?: 0)
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
    /** Build 18 (E4, pF02): Recents opened over Wayfinder's Hub — B comes back to it (it went to the launcher: Wayfinder is
     *  never a tracked app, nor a card Recents can be told to open). */
    private val WAYFINDER_ORIGIN = "<wayfinder>"   // never a package or app name
    /** Build 18 review (E4): the screen where the Hub was in front when our RECENTS asked (null: none) — Recents coming up there
     *  covers the Hub, whatever [lockTarget] says (it named the bottom app with the Hub on top and the controller sent down). */
    @Volatile private var recentsHub: Int? = null

    /** Our RECENTS action: note what's on the screen being left. */
    private fun openRecents() {
        // device pass (P2-2): where the controller is ([controllerDisplay]) — "stays where you send it" before its first move
        // reads the lock's screen, as before; a page holding the controller against the lock has it
        val d = if (AppSettings.focusSticky && !lockSuspended()) lockTarget() else controllerDisplay()
        recentsOrigin = if (MainActivity.hubOn(d)) WAYFINDER_ORIGIN else displayApps[d] ?: HOME_ORIGIN
        recentsHub = listOfNotNull(PRIMARY_DISPLAY, secondDisplayId()).firstOrNull { MainActivity.hubOn(it) }
        recentsOriginAt = android.os.SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    @Volatile private var lastHatX = 0

    private fun recentsGoBack() {
        val d = recentsDisplay
        val from = recentsFrom
        // build 18 (E4, pF02): opened from Wayfinder — back to the Hub on that screen, its page and focus as they were (no
        // `page` extra: the running Hub comes forward)
        if (d != null && from == WAYFINDER_ORIGIN) {
            exitRecents()
            val opened = runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle())
            }.onFailure { Log.w(TAG, "recents → Wayfinder: ${it.message}") }.isSuccess
            if (!opened) performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val card = if (d != null && from != null) recentsCards(d).firstOrNull { it.contentDescription?.toString() == from } else null
        if (d != null && card != null && tapRecentsCard(d, card)) { exitRecents(); return }
        exitRecents()
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** 1.4 (GitHub #68): open a card with a real tap at its centre. The accessibility click on the live tile (the app
     *  Recents was opened from) left the launcher frozen the second time; a tap, like a finger, never does. */
    private fun tapRecentsCard(d: Int, card: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect(); card.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)).setDisplayId(d).build()
        return injectGesture(g, null, null)
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
                val sent = injectGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(hold).setDisplayId(d).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { handler.postDelayed({ finish() }, 180) }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { finish() }
                    }, handler)
                if (!sent) finish()
            }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { finish() }
        }
        if (!injectGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(move).setDisplayId(d).build(), cb, handler)) finish()
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
        // build 18 (E8): any other press between the two Selects starts over
        if (event.action == KeyEvent.ACTION_DOWN && b != ThorButton.SELECT) recentsClearAt = 0L
        if (b == ThorButton.HOME) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return b in recentsDowns
        recentsDowns.add(b)
        when (b) {
            ThorButton.A -> {                                                          // open the middle card
                val d = recentsDisplay
                val card = d?.let { centredCard(it) }
                // 1.4 (GitHub #68): a real tap on it, never the accessibility click (it froze Recents)
                if (d == null || card == null || !tapRecentsCard(d, card)) recentsGesture(0.50f, 0.48f, 0.50f, 0.48f, 40)
            }
            ThorButton.Y, ThorButton.X -> recentsGesture(0.50f, 0.50f, 0.50f, 0.04f, 160)   // swipe it away
            ThorButton.L1 -> recentsBrowse(-1)
            ThorButton.R1 -> recentsBrowse(1)
            ThorButton.SELECT -> recentsSelect(event, d)
            ThorButton.START -> { exitRecents(); performGlobalAction(GLOBAL_ACTION_HOME) }
            ThorButton.B, ThorButton.BACK -> recentsGoBack()
            else -> {}
        }
        return true
    }

    /** Build 18 (E8, ps-F3; decision H8): when the first Select of "close all" was pressed (0: none waiting). */
    @Volatile private var recentsClearAt = 0L

    /** Build 18 (E8, ps-F3; decision H8): Recents' "Clear all" asks twice, on every controller (the Thor's too) — one press of
     *  Select (Create on a DualSense, which a PS5 player presses by habit to capture) closed every app, an emulator's unsaved
     *  game included. The first press says so in the pressing controller's names; a second one within [ClearAll.MS] closes
     *  them all. The hint goes for the pill (a held list would cover it on its screen). */
    private fun recentsSelect(event: KeyEvent, d: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (ClearAll.second(recentsClearAt, now)) {
            recentsClearAt = 0L
            clickRecentsButton("com.android.launcher3:id/clear_all_button", "Clear all")
            return
        }
        recentsClearAt = now
        val look = ButtonNames.lookOfSrc(ExtPadsCtl.srcOfDevice(event.device))
        Overlays.release(Held.RECENTS)
        Overlays.pill("Press ${Say.btn(ThorButton.SELECT, look)} again to close all apps", CueOrigin.Screen(d), group = "recents-clear-all")
    }

    /** Build 18 (E8): the two presses of Recents' "close all" — pure (OverlayRulesTest). */
    internal object ClearAll {
        /** The second press must come this soon after the first. */
        const val MS = 2500L
        /** A press at [now] is the second one: a first one is waiting ([firstAt] > 0) and recent enough. */
        fun second(firstAt: Long, now: Long): Boolean = firstAt > 0L && now - firstAt in 0..MS
    }

    /** Focus actions: move the controller — or, while locked, move the lock. */
    private fun focusAction(top: Boolean) {
        val target = if (top) PRIMARY_DISPLAY else secondDisplayId()
        if (deckInTheWay(target)) return
        // Locked — or "stays where you send it": the move sets (or moves) the lock, so a
        // touch on the other screen can't pull the controller back.
        if (AppSettings.focusLockEnabled || AppSettings.focusSticky) { AppSettings.setFocusLock(true, top = top); applyFocusLock(announce = true) }
        else switchControllerFocus(if (top) PRIMARY_DISPLAY else (secondDisplayId() ?: return))
    }

    /** Build 18 (E5, pF18): never onto the screen Keyboard & mouse covers — it's touch-only, and the app under it got the
     *  controller unseen (the D-pad moved that app). The deck beside a game on its own screen (the guide) isn't in the way.
     *  True = [target] is covered: said, and the controller stays (review E1: the combos sheet's B asks too). */
    private fun deckInTheWay(target: Int?): Boolean {
        val d = inputDeck?.takeIf { it.isShowing && !it.beside && target != null && it.displayId == target } ?: return false
        vibrateShort()
        Overlays.pill("${if (d.guideOnly) ThorAction.GUIDE.title else ThorAction.KEYBOARD.title} is touch-only — the controller stays here",
            CueOrigin.Screen(controllerDisplay()), group = "focus")
        return true
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
                        Overlays.pill("The controller is already on the ${screenName(target)}", CueOrigin.Screen(target), group = "focus")
                    } else { focusModel = real; switchControllerFocus(target) }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        // b18g (#102 d): no second read — a move the user asked for, and the "already there" case above read the real focus
        focusDisplay(target, ifNeeded = false) { _ ->
            // Verify against the real input state (root), then update the model.
            Thread {
                val real = rootFocusedDisplay()
                if (real != null) focusModel = real
                val ok = (real ?: target) == target
                Log.d(TAG, "controller focus → display $target: ${if (ok) "OK" else "FAILED"} (input focus now $real)")
                handler.post {
                    if (ok) { vibrateShort(); Overlays.pill("Controller on the ${screenName(target)}", CueOrigin.Screen(target), group = "focus") }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** Device pass (P2-3): every touch this service injects — the focus probe's tap below, Recents' drags and taps, the swipe
     *  combo — goes through here: it isn't a finger, so a Wayfinder page's button legend stays ([InputMode.noteSyntheticTouch]).
     *  The probe's tap hid it with no finger on the screen (the quick panel opening / closing, the controller sent away and back). */
    private fun injectGesture(g: android.accessibilityservice.GestureDescription, cb: GestureResultCallback?, h: android.os.Handler?): Boolean {
        app.wayfinder.ui.InputMode.noteSyntheticTouch()
        return dispatchGesture(g, cb, h)
    }

    /** b18g (GitHub #102 d): the probe takes the app's window focus even on the screen that already has the focus (device,
     *  2026-10-10: "Focus leaving ‹game›" → app.wayfinder → back, 75–95 ms, on each lock to the top and ~2 s after each swap).
     *  A Wine game takes that for an alt-tab (pause, menu: "like Esc"). So the real focused display is read first. Pure. */
    internal object FocusProbe {
        /** The probe is needed unless the real focused display [real] (null: unknown, pservice down) is already [want]. */
        fun needed(real: Int?, want: Int): Boolean = real != want
    }

    // b18g (#102 d), main thread only: the reads run one at a time, in order, so a later request is never overtaken by an
    // earlier one still reading; [probesRunning]: a probe whose tap may not have landed yet makes a read stale. One daemon
    // thread at most, gone when idle (nothing to shut down when the service stops).
    private val focusReads = java.util.concurrent.ThreadPoolExecutor(0, 1, 5, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue()) { r -> Thread(r, "wf-focus-read").apply { isDaemon = true } }
    private var focusReadsPending = 0
    private var probesRunning = 0

    /** Raise [displayId] to input focus without touching its app (see block comment). [ifNeeded] (b18g, #102 d): first the
     *  real focused display, read off the main thread — already [displayId]: no probe, and [onDone] (true) as after one. False:
     *  the probe at once (a move the user asked for, whose "already there" case was read just before). */
    fun focusDisplay(displayId: Int, ifNeeded: Boolean = true, onDone: (Boolean) -> Unit = {}) {
        val read = ifNeeded && PServiceBridge.cachedAvailable()   // no root, nothing to read: the probe, as before
        if (!read && focusReadsPending == 0) return runProbe(displayId, onDone)
        focusReadsPending++
        focusReads.execute {
            val real = if (read) rootFocusedDisplay() else null
            handler.post {
                focusReadsPending--
                if (!read || probesRunning > 0 || FocusProbe.needed(real, displayId)) runProbe(displayId, onDone)
                else { Log.d(TAG, "focus already on $displayId — no probe"); onDone(true) }
            }
        }
    }

    /** b18i (WI-8): each screen keeps its own focused window (Android's `config_perDisplayFocusEnabled`). The Thor: yes
     *  (`dumpsys input`, 2026-10-10: a focused window on display 4 while display 0 has the focus). Then the probe gets the
     *  window focus on its screen before any tap, and [probeDisplay]'s tap waits for it. Read once; unreadable = no. */
    private val perDisplayFocus: Boolean by lazy {
        val on = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_perDisplayFocusEnabled", "bool", "android")
            id != 0 && res.getBoolean(id)
        } catch (_: Exception) { false }
        Log.i(TAG, "focus probe: per-screen focus ${if (on) "on: its tap waits for its window focus" else "off: its tap goes at its first frame"}")
        on
    }

    private fun runProbe(displayId: Int, onDone: (Boolean) -> Unit) {
        probesRunning++
        probeDisplay(displayId) { ok -> probesRunning--; onDone(ok) }
    }

    /** The focus probe (b18g: split out of [focusDisplay]): a tiny focusable overlay on [displayId], tapped once it has the
     *  window focus there (b18i, WI-8), then removed. [onDone] comes exactly once. */
    private fun probeDisplay(displayId: Int, onDone: (Boolean) -> Unit) {
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
        ).apply {
            // 1.4: mid-height on the left edge — at the top corner it was under the status bar when one shows
            gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = 0
            // b18i (leftovers, #49): the height from ScreenSize — a display context's metrics follow the process configuration
            // once it changes (the other screen's on a device whose screens differ in height; the Thor's are both 1080)
            y = ((ScreenSize.real(this@ForegroundAppService, displayId)?.y ?: 0) / 2 - 3).coerceAtLeast(0)
        }
        // 1.4 (GitHub #63): while it has the focus it decides the navigation bar — ask for it hidden when it's set
        // hidden, or it showed for a moment each time the controller moved (the quick panel opening / closing). Not
        // the status bar: a small window only controls it while the app shows it (asking hidden made it blink).
        @Suppress("DEPRECATION")
        if (NavBarGuard.navHiddenNow(this)) lp.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.postDelayed({
                try { wm.removeViewImmediate(probe) } catch (_: Exception) {}
                handler.postDelayed({ onDone(ok) }, 60)
            }, 40)
        }
        // b18i (WI-8, B7): the tap goes when the probe gets the window focus on its screen. That is the moment input knows
        // the probe (logged "Focus entering ‹probe›"), so the tap lands on it. A window gets the focus only once input has
        // its frame, never earlier. The probe is focusable and above apps, and each screen keeps its own focus
        // ([perDisplayFocus]), so its screen gives it the focus with no tap. Sent at its attach (View.post), the tap came
        // 8–14 ms before that 2 times in 4 on the device (b18f2) and hit the window under it. Once it was the launcher's
        // task under a translucent Hub still sliding in, and Android brought
        // that task forward over the Hub (setFocusedTask: the Hub moved to the bottom vanished). A tap on the probe moves the
        // focus and no task. Waits on an event, no fixed delay. No focus (a focusable window above it, the screen off): no
        // tap, and the safety nets end it as before.
        var tapped = false
        fun tap() {
            if (tapped || finished) return
            tapped = true
            val path = android.graphics.Path().apply { moveTo(2f, lp.y + 2f) }
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 20))
                .setDisplayId(displayId)
                .build()
            val sent = injectGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(true)
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = finish(false)
            }, handler)
            if (!sent) finish(false)
            handler.postDelayed({ finish(false) }, 800)   // safety net
        }
        // before addView: the view's own observer until it attaches, then moved to its window's (ViewTreeObserver.merge)
        probe.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> if (hasFocus) tap() }
        // without per-screen focus (not the Thor), a screen that doesn't have the focus gives the probe none before its tap:
        // there the tap goes at the probe's first frame, the last event this app sees before input knows the probe
        if (!perDisplayFocus) probe.viewTreeObserver.registerFrameCommitCallback { tap() }
        try { wm.addView(probe, lp) } catch (e: Exception) { Log.w(TAG, "focus probe: ${e.message}"); return onDone(false) }
        // safety net OUTSIDE the probe's own queue: if the window never attaches (screens off, no
        // frame) the probe would otherwise stay on screen and onDone never come
        handler.postDelayed({
            if (!tapped && !finished) Log.w(TAG, "focus probe on display $displayId: no window focus in 1.5 s — no tap")
            finish(false)
        }, 1500)
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
        val app = displayApps[controllerDisplay()]
            ?: displayApps[PRIMARY_DISPLAY]
        if (!force && app == lightsApp) return
        lightsApp = app
        val p = app?.let { GameProfiles.effective(it).lights } ?: LightSettings.global
        StickLights.apply(this, p)
    }

    @Volatile private var screenOn = true
    /** 1.4: the controller's lock to put back once the screens are on again (the panel closed as they went off). */
    @Volatile private var lockOnWake = false
    /** 1.4 (review): no lock — the screen the controller goes back to at the wake (the panel closed as it slept). */
    @Volatile private var focusOnWake: Int? = null

    /** No stick-light animation while the Thor sleeps. */
    private val screenPowerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    if (QuickPanelWindow.isOpen && panelTook && AppSettings.focusLockEnabled) lockOnWake = true
                    InputMonitor.send("S 0")   // the root helper rests too (GitHub #40)
                    QuickPanelWindow.close()
                    app.wayfinder.lights.StickLights.pause(); SleepEngine.onScreenOff(); GyroEngine.setScreenOn(false)
                    // the root helper's samplers (screen colour ~12/s, frame rate) sleep too
                    if (ambientPhys != null) { InputMonitor.send("A -"); ambientPhys = null }
                    if (fpsWatching != null) { InputMonitor.send("F - -"); fpsWatching = null }
                    // b18i (D1): a game the screens' going off paused isn't one that paused itself for Game controls
                    pauseWatch = null; pauseFor = null
                    handler.removeCallbacks(padProfileTick)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    InputMonitor.send("S 1")
                    NavBarGuard.poke()   // 1.3 (GitHub #23)
                    app.wayfinder.lights.StickLights.resume(this@ForegroundAppService); SleepEngine.onScreenOn(); GyroEngine.setScreenOn(true)
                    applyFps()     // the lights ask for the screen colour again themselves
                    handler.removeCallbacks(padProfileTick); handler.post(padProfileTick)
                    if (lockOnWake) { lockOnWake = false; handler.postDelayed({ applyFocusLock(announce = false) }, 300) }
                    focusOnWake?.let { d -> focusOnWake = null; handler.postDelayed({ focusDisplay(d) { syncFocusModel() } }, 300) }
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
        val touched = InputMonitor.lastEventUptime(InputMonitor.DEV_BOTTOM_TOUCH)
        if (touched == 0L) return
        // 1.4 (Reddit): counted from the last touch only, a screen that just got an app (ES-DE's second screen, a game
        // sent there) went dark at once, and one just woken went dark again — count from its last use instead
        val now = android.os.SystemClock.uptimeMillis()
        val app = displayApps[second]
        if (app != idleApp) { idleApp = app; idleAppAt = now }
        val last = maxOf(touched, b.wokeAt(second), idleAppAt)
        val idleMs = now - last
        val threshold = AppSettings.idleBlankSeconds * 1000L
        if (!b.isBlanked(second)) {
            if (idleMs >= threshold) {
                Log.d(TAG, "idle ${idleMs}ms ≥ ${threshold}ms → auto-blank display $second")
                b.blank(second); idleBlanked = true
            } else idleBlanked = false
        } else if (idleBlanked && idleMs < threshold && !AppSettings.wakeDoubleTap) {
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
        if (GyroEngine.configuredForCurrent == null) {
            // dev build 16 (theme F): the combo as the controller in hand says it (this app's own combos), none when off
            val gc = Say.combo(ThorAction.GAME_CONTROLS, ButtonNames.handLook, buttonHost.currentApp())
            Overlays.pill("No gyro for this game — set it up in Game controls" + (gc?.let { " ($it)" } ?: "") + ", then Gyro",
                CueOrigin.Press(ButtonNames.handSrc), group = "gyro"); return
        }
        val off = !GyroEngine.pausedByUser
        GyroEngine.setPausedByUser(off)
        Overlays.pill(if (off) "Gyro off" else "Gyro on", CueOrigin.Press(ButtonNames.handSrc), group = "gyro")
    }

    private fun toggleAynMouse() = Thread {
        val on = runCatching { android.provider.Settings.System.getInt(contentResolver, "global_gamepad_to_mouse_mode", 0) == 1 }.getOrDefault(false)
        PServiceBridge.exec("settings put system global_gamepad_to_mouse_mode ${if (on) 0 else 1}")
        handler.post {
            vibrateShort()
            Overlays.pill(if (on) "Mouse mode off" else "Mouse mode on — click L3 or R3: that stick is the pointer",
                CueOrigin.Press(ButtonNames.handSrc), group = "mouse")
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

    /** QA2 (users: "bottom screen stuck black, touch still works"): what can leave the bottom screen dark without
     *  Wayfinder's blank — its panel's power cut underneath Android (DRM connector off: another app or tool), AYN's own
     *  bottom-off mode, or a brightness near 0. Root reads; null = can't tell. */
    private fun bottomDarkElsewhere(second: Int): Boolean? {
        if (!PServiceBridge.cachedAvailable()) return null
        val dpms = PServiceBridge.exec("cat /sys/class/drm/card0-DSI-2/dpms")?.trim()
        val lvl = ScreenLevels.get(this, second)
        return dpms == "Off" || aynScreenMode() == 1 || (lvl != null && lvl < 0.002f)
    }

    /** Undo everything that can keep the bottom screen dark: our blank, AYN's bottom-off, a cut panel power, a near-0
     *  brightness. */
    private fun repairBottom() = Thread {
        val second = secondDisplayId() ?: return@Thread
        handler.post { blanker?.wake(second) }
        if (PServiceBridge.cachedAvailable()) {
            if (aynScreenMode() != 0) aynBothScreensOn()
            val phys = getSystemService(DisplayManager::class.java).getDisplay(second)?.let { ScreenCapture.physicalId(it) }
            if (phys != null) PServiceBridge.runEntryPoint(this, "DisplayPowerTool", phys, "2")
            ScreenLevels.get(this, second)?.let { if (it < 0.002f) ScreenLevels.set(this, second, 0.09f) }
        }
        Log.i(TAG, "bottom screen repaired")
        Overlays.pill("Bottom screen back on", CueOrigin.Screen(second))
    }.apply { isDaemon = true }.start()

    /** Blank/wake the second (non-primary) screen — OLED black overlay, tap to wake. */
    private fun toggleSecondScreen(): Boolean {
        val b = blanker ?: DisplayBlanker(this).also { blanker = it }
        refreshDisplays()
        val second = availableDisplayIds.firstOrNull { it != PRIMARY_DISPLAY }
            ?: return false.also { Log.w(TAG, "No second display to blank") }
        vibrateShort()
        // build 18 (review): the top dark by our hand ("Top screen off / on", the panel's "Bottom screen only"): the top
        // comes back as the bottom goes — never both screens dark (the mirror of [toggleTopScreen])
        if (currentScreenMode() == 2) { handler.post { applyScreenMode(1) }; return true }
        // If AYN's own long-press blank is active, "toggle" means wake it.
        Thread {
            // not ours but dark anyway (AYN's bottom-off, a panel switched off by another app, brightness 0): the press
            // brings it back — never blanks a screen the user already sees as black (QA2)
            if (!b.isBlanked(second) && bottomDarkElsewhere(second) == true) {
                Log.d(TAG, "bottom screen dark without our blank → repairing instead of blanking")
                repairBottom()
            } else handler.post { b.toggle(second) }
        }.apply { isDaemon = true }.start()
        return true
    }

    /** Build 18 (GitHub #91): blank / wake the TOP screen — the quick panel's "Bottom screen only" (a tap on it wakes it
     *  too). With one screen there's nothing to show the rest on: the only screen is never blanked. */
    private fun toggleTopScreen(): Boolean {
        refreshDisplays()
        secondDisplayId() ?: return false.also { Log.w(TAG, "No second display — the top screen stays on") }
        vibrateShort()
        handler.post { applyScreenMode(if (currentScreenMode() == 2) 0 else 2) }
        return true
    }

    /**
     * Build 18 (GitHub #83): "Turn Wayfinder off" — for games that refuse to run beside an accessibility service. The
     * service turns itself off the way Android's settings do ([onDestroy] puts the rest back), and stays off: it's no
     * longer listed, so [ServiceWatch] leaves it alone. One press in Wayfinder turns it back on ([Setup.enableService]).
     */
    private fun turnOff() {
        if (turningOff) return                          // a second press while it goes
        turningOff = true
        Setup.markOffByUser(this)
        // the tick's follow() would start the input layer again before we go (it flips after 1.5 s)
        handler.removeCallbacks(padProfileTick)
        PadLayerCtl.pauseForOff(this)                   // AYN's own controller comes back; the mode is kept
        Overlays.pill("Wayfinder is off — open Wayfinder to turn it back on", CueOrigin.Press(ButtonNames.handSrc))
        Log.i(TAG, "turned off by the user")
        // the pill's shortest time on screen (it goes with the service). Refused: Wayfinder carries on (the layer wanted again,
        // the one tick restarted)
        handler.postDelayed({ runCatching { disableSelf() }.onFailure {
            Log.w(TAG, "disableSelf: $it"); turningOff = false; Setup.clearOffByUser(this@ForegroundAppService)
            PadLayerCtl.resumeAfterOff(this@ForegroundAppService, send = true)
            handler.removeCallbacks(padProfileTick); handler.post(padProfileTick) } }, 1500)
    }
    private var turningOff = false

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

    /** b18g (GitHub #99): one move at a time — a swap, a layout (an app pair, Open), a route — and ONE swap asked for while a
     *  move runs, kept and done right after it. A quick swap back was dropped without a word (device, 2026-10-10: the second
     *  of two swaps 1.4–3 s apart, 5 / 5; done at 4 s). One atomic state: a swap queued just as the move ends is never left
     *  waiting for a move that doesn't come, and nothing gets in between a move and the swap waiting for it. Pure:
     *  MoveGateTest. */
    internal class MoveGate {
        enum class Ask { RUN, QUEUED, REFUSED }
        private val state = java.util.concurrent.atomic.AtomicInteger(FREE)
        val busy: Boolean get() = state.get() != FREE
        /** A layout or a route: only when nothing runs, never queued. True: claimed — end it with [end]. */
        fun claim(): Boolean = state.compareAndSet(FREE, RUNNING)
        /** A swap: [Ask.RUN] now (claimed), [Ask.QUEUED] for right after the running move, [Ask.REFUSED] when one waits already. */
        fun askSwap(): Ask {
            while (true) {
                when (state.get()) {
                    FREE -> if (state.compareAndSet(FREE, RUNNING)) return Ask.RUN
                    RUNNING -> if (state.compareAndSet(RUNNING, WAITING)) return Ask.QUEUED
                    else -> return Ask.REFUSED
                }
            }
        }
        /** The running move ended. True: the waiting swap takes over now (still claimed — end it with [end] too); false: free. */
        fun end(): Boolean {
            while (true) {
                when (state.get()) {
                    WAITING -> if (state.compareAndSet(WAITING, RUNNING)) return true
                    RUNNING -> if (state.compareAndSet(RUNNING, FREE)) return false
                    else -> return false                       // not claimed: nothing to end
                }
            }
        }
        private companion object { const val FREE = 0; const val RUNNING = 1; const val WAITING = 2 }
    }

    // Run on background thread so Thread.sleep doesn't block the main thread.
    // Trampoline activities need the main thread free to execute their onCreate.
    // b18g (#99): busy, it waits for the running move (one buzz: taken) — a third is refused, with a pill. W lines: release
    // keeps them, so a device log tells a refused swap from one never asked for.
    private fun startSwapInBackground(): Boolean = when (moveGate.askSwap()) {
        MoveGate.Ask.RUN -> { runSwap(null); true }
        MoveGate.Ask.QUEUED -> { Log.w(TAG, "Swap in progress — queued, it runs right after"); vibrateShort(); true }
        MoveGate.Ask.REFUSED -> { Log.w(TAG, "Swap in progress and one already queued — ignoring"); sayStillMoving(); false }
    }

    /** One swap on its own thread. [ctlHint]: where the swap just before left the controller (a queued swap: that swap's
     *  follow is still on its way, the focus model not updated yet); null: read it. */
    private fun runSwap(ctlHint: Int?) {
        Thread {
            var ctlAfter: Int? = null
            try {
                ctlAfter = performSwapOrSend(ctlHint)
            } catch (e: Exception) {
                // An uncaught exception on this thread would kill the whole
                // process (and the accessibility service with it).
                Log.e(TAG, "Swap failed: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                endMove(ctlAfter)
            }
        }.start()
    }

    /** Every move's end (swap, layout, route — their `finally`): the swap waiting for it starts now, the gate still claimed,
     *  so no route or layout gets in between; else the gate is free. [ctlAfter]: where a swap left the controller. */
    private fun endMove(ctlAfter: Int? = null) {
        if (!moveGate.end()) return
        if (instance !== this) { Log.w(TAG, "queued swap dropped — the service stopped"); return }
        Log.w(TAG, "queued swap — starting it now")
        runSwap(ctlAfter)
    }

    /** A move the gate can't take: said, not dropped without a word (b18g, #99). */
    private fun sayStillMoving() {
        handler.post { Overlays.pill("Still moving the apps — try again in a moment", CueOrigin.Press(ButtonNames.handSrc), group = "move") }
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
        Setup.clearOffByUser(this)                               // build 18 (GitHub #83): on again, however it was turned on
        app.wayfinder.deck.InputDeckOverlay.undoLeftover(this)   // 1.4 (GitHub #53)
        handler.postDelayed({ Dock.start(this) }, 1500)          // a TV / monitor: controllers + screens
        blanker = DisplayBlanker(this).also { b ->
            // 1.4 (Reddit): the controller stayed on the bottom screen when it went dark (3 fingers, the AYN button,
            // the auto-off) — you had to touch the top screen. AYN's own blank moved it; so do we.
            b.onBlanked = { d -> if (d != PRIMARY_DISPLAY && controllerDisplay() == d) handler.postDelayed({
                // woken again meanwhile, or the top is dark too (build 18, review: nothing lit to go to)
                if (!b.isBlanked(d) || b.isBlanked(PRIMARY_DISPLAY)) return@postDelayed
                val alwaysBottom = AppSettings.focusLockEnabled && !AppSettings.focusSticky && !AppSettings.focusLockTop
                if (alwaysBottom) { darkLockedBottom = true; focusDisplay(PRIMARY_DISPLAY) { syncFocusModel() } }
                else focusAction(top = true)
            }, 150)
            // build 18 (GitHub #91): the mirror — the TOP screen went dark ("Top screen off / on", the panel's "Bottom screen
            // only"): the controller goes to the bottom one, if that one is on. "Always top" keeps it (AYN's lock pins it there)
            else if (d == PRIMARY_DISPLAY && controllerDisplay() == d) handler.postDelayed({
                val second = secondDisplayId() ?: return@postDelayed
                if (!b.isBlanked(d) || b.isBlanked(second)) return@postDelayed   // woken again meanwhile, or the bottom is dark too
                val alwaysTop = AppSettings.focusLockEnabled && !AppSettings.focusSticky && AppSettings.focusLockTop
                if (!alwaysTop) focusAction(top = false)
            }, 150) }
            b.onWoken = { d -> if (d != PRIMARY_DISPLAY && darkLockedBottom) { darkLockedBottom = false; handler.postDelayed({ applyFocusLock(announce = false) }, 150) } }
        }
        focusCue?.dismissAll()                         // a reconnect must not orphan the old cue's pills
        // dev build 17 (WP10): the one overlay host — on the one controllerDisplay(), the displays that exist now
        focusCue = FocusCue(this, ctlDisplay = { controllerDisplay() },
            live = { getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet() }).also { Overlays.host = it }
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
        // 1.4.1 (GitHub #79): the user's saturation back after AYN's own boot value (twice: AYN applies it late)
        handler.postDelayed({ Saturation.restore(this) }, 20_000); handler.postDelayed({ Saturation.restore(this) }, 60_000)
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
        PadLayerCtl.resumeAfterOff(this)           // build 18 (review): on again in the same process — the layer is wanted again
        PadLayerCtl.onEmergencyOff = {
            // dev build 17 (WP11): its place from the registry (it named More options — the card isn't there any more). dev build
            // 17 (WP9): "Game controls" (the input layer is Help's word); the combo in the Thor's names, or the docked
            // controller's (critique F1)
            Overlays.pill("Game controls off (${Say.emergencyCombo(this@ForegroundAppService)} held) — back on: " +
                Say.path(NavCards.LAYER, inWayfinder = true), CueOrigin.System)
        }
        PadLayerCtl.onLayerFailed = { why ->
            Overlays.pill("Game controls couldn't start ($why) — your controller works as usual. Try again in " +
                Say.path(NavCards.HELP_LAYER, inWayfinder = true), CueOrigin.System)
        }
        InputMonitor.gatedListener = { t, c, v, src -> onGatedEvent(t, c, v, src) }
        // b18o: turbo buttons the hand holds — the input layer's and a controller's ("TU"), and the app's own (ExtEngine)
        InputMonitor.turboListener = { c, v, at, src -> onTurboLine(c, v, at, src) }
        ExtEngine.turboSink = { src, b, held, at -> buttonEngine?.onTurbo(b, held, ExtPadsCtl.srcOfGated(src), at, ButtonEngine.TURBO_APP) }
        InputMonitor.extListener = { c, v -> ExtEngine.onExt(c, v) }
        InputMonitor.stickListener = { s, x, y -> ExtEngine.onStick(s, x, y) }
        ExtEngine.display = { controllerDisplay() }
        ExtEngine.perform = { a, src -> Companion.perform(a, src) }   // dev build 17: a controller's "→ Android Back" on its pad
        ExtEngine.padRemap = { src -> ExtPadsCtl.ownRemaps[src] }
        // a controller gone (or the helper): its combos' state and stick flicks go too — dev build 17: its AYN press, its D-pad
        ExtPadsCtl.onGone = { src -> gatedFlicks.remove(src); padHatX.remove(src); buttonEngine?.gone(src); handler.post { aynPresses.remove(src)?.cancel() } }
        // dev build 17 (theme G): a connected controller's AYN button and its watched D-pad
        ExtPadsCtl.aynListener = { src, down -> handler.post { padAyn(src, down) } }
        ExtPadsCtl.watchListener = { raw, t, c, v -> onPadWatched(raw, t, c, v) }
        GyroEngine.init(this)
        GameProfiles.init(this)
        PadProfiles.init(this)                     // dev build 16 (WP4): the older own controls moved in before the first tick
        runCatching { GameProfiles.dropPlain() }   // 1.4: untouched game copies left by a crash or an update
        // A full restore restarted Wayfinder (Backup.restartApp): back to where the user was
        if (Backup.reopenAfterRestore(this)) handler.postDelayed({
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page", HubPage.HELP)) }
            Overlays.pill("Backup restored", CueOrigin.System)
        }, 1500)
        InputMonitor.onHelperConnected = {
            handler.post { fpsWatching = null; ambientPhys = null; applyFps(); applyLights(force = true) }
            InputMonitor.send(if (screenOn) "S 1" else "S 0")
            if (PadLayerCtl.wanted) PadLayerCtl.apply(this)
            ExtPadsCtl.apply(this)                  // external controllers
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
            InputMonitor.turboListener = null; ExtEngine.turboSink = { _, _, _, _ -> }                 // b18o
            InputMonitor.onHelperConnected = null; InputMonitor.fpsListener = null; InputMonitor.ambientListener = null
            ExtPadsCtl.onGone = null; ExtPadsCtl.aynListener = null; ExtPadsCtl.watchListener = null
            ExtPadsCtl.setWatch(false)                                  // dev build 17: nobody reads them any more
            app.wayfinder.lights.StickLights.screenSampler = null; app.wayfinder.lights.StickLights.screenStop = null
            PadLayerCtl.onEmergencyOff = null
            PadLayerCtl.onLayerFailed = null
            ExtEngine.releaseAll(); GyroEngine.follow(null, null)
            // the root helper outlives us (same process): stop its samplers, give every app the plain
            // pad, and put back performance / Hz / DND (review 2026-09-25: they kept running)
            InputMonitor.send("A -"); InputMonitor.send("F - -")
            PadLayerCtl.setProfile("")
            ExtPadsCtl.setGameProfile("")
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
        // dev build 17 (WP10): this instance's cues go with it; the host is cleared only if it is still ours
        focusCue?.let { if (Overlays.host === it) Overlays.host = null; it.dismissAll() }
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
            // b18j (device b18v v3): Recents can close with no app window event at all — an app Quickstep kept live under it
            // (PPSSPP) comes back with a focus change only. The window list tells: Recents gone → its exit, and the screen it
            // covered is looked at again ([recheckAfterRecents])
            recentsDisplay?.let { d -> if (recentsGone(d)) exitRecents() }
            recheckAfterRecents()
            return
        }
        val pkg = event?.packageName?.toString() ?: return

        // Strategy 1: get displayId from event.source → window → displayId
        var displayId = getDisplayIdFromEvent(event)

        // Strategy 2: try windowId → find matching window in windows list
        if (displayId == null) {
            displayId = getDisplayIdFromWindowId(event.windowId)
        }
        // build 18 (E5, pF04): Keyboard & mouse follows the app on its screen — Wayfinder's own windows included (the
        // tracked apps leave Wayfinder out)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) deckFollow(displayId)

        // Recents (Android's overview) opened / left → controller mode for it.
        // Any other window change (an app — Wayfinder included — a toast, a dialog…) only
        // ends it once Recents really isn't the top app any more: a "controller
        // disconnected" toast used to switch the mode off with Recents still open.
        // b18j: Recents' own window event (launcher3's RecentsActivity on the Thor) — the one rule, also for the launcher branch below
        val recents = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            RecentsRules.isRecents(pkg, event.className?.toString().orEmpty(), packageName)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            if (recents) {
                // b18n (GitHub #102 b, b18g_102 H-b3): the user's Recents wins over a post-swap watch, as the user's Home does —
                // the watch took Recents (a "launcher" package) for a frontend covering the moved app and brought that app back
                // over it; and once Recents was left for the home or another card, the next look would bring it back again
                swapWatchGen++
                enterRecents(displayId ?: focusedDisplayId())
            } else if (recentsDisplay != null) for (ms in listOf(250L, 900L)) handler.postDelayed({
                // twice: Recents can still be animating out at the first look
                val d = recentsDisplay
                if (d != null && !recentsStillOpen(d)) { Log.d(TAG, "recents: left ($pkg/$cls)"); exitRecents() }
            }, ms)
        }

        // If an actual launcher came to the foreground, the user went Home
        // → clear that display's tracked app so we don't swap with a ghost.
        // Suppress during swaps: the launcher briefly flashes on the target
        // display between the two 'am start' commands, which would falsely
        // clear the app we just moved there.
        if (pkg in launcherPackages) {
            // b18j (device b18v v3, PPSSPP 2 of 2): Recents is no home screen, though launcher3 hosts it and answers CATEGORY_HOME.
            // It covers the app the user came from — Quickstep keeps that app live under it — and B (or its card) brings the
            // app back with a focus change only, no window event: cleared here, it was never tracked again (Home + X named the
            // app, not its game; "Do not disturb while playing" went off; the panel's game card went). Leaving Recents for
            // Home sends the home's own event; an app closed from it goes with its window ([reconcileGone] once Recents is
            // gone); what is in front when it closes is read again ([recheckAfterRecents])
            if (recents) { Log.d(TAG, "Recents on display $displayId — the app under it stays tracked"); return }
            if (displayId != null && displayId == recentsLeftOn) recentsLeftOn = null   // the home in front: that settles it
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
            if (displayId == recentsLeftOn) recentsLeftOn = null   // b18j: its own event says what is in front after Recents
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
    private fun stepBrightness(dir: Int, only: Int? = null) {
        val ids = if (only != null) listOf(only) else listOfNotNull(PRIMARY_DISPLAY, secondDisplayId())
        brightnessWorker.execute {
            val cur = ids.mapNotNull { d -> ScreenLevels.get(this, d)?.let { d to it } }.toMap()
            if (cur.isEmpty()) return@execute
            val next = cur.mapValues { (_, v) -> ScreenLevels.step(v, dir) }
            next.forEach { (d, v) -> ScreenLevels.set(this, d, v) }
            val txt = ids.mapNotNull { d -> next[d]?.let { (if (d == PRIMARY_DISPLAY) "top " else "bottom ") + ScreenLevels.percent(it) + " %" } }
            Overlays.pill("Brightness — " + txt.joinToString(", "), CueOrigin.Press(ButtonNames.handSrc), group = "brightness")
        }
    }

    /** 1.3.2: when an app was last reopened on the other screen (its cold start isn't a failed move). */
    private val reopenedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 1.3.2 (GitHub #42): a screen's home (or both), whichever screen has the controller. b18p (GitHub #97): each screen by its
     *  own display, the controller's screen last ([HomeSteps]). Android's Home (GLOBAL_ACTION_HOME: a Home with no display) goes
     *  home on the FOCUSED screen, and the bottom's launcher start had just moved the focus there: "both" sent the bottom home
     *  twice and left the top as it was (device, AYN lock off; with it on, AYN routed that Home to the top). It stays only where
     *  nothing can have moved the focus: the top alone, with the controller on it ([nativePress]'s rule). */
    private fun homeOn(top: Boolean, bottom: Boolean) {
        handler.post { QuickPanelWindow.close() }
        swapWatchGen++
        Thread {
            if (!lockHolds()) rootFocusedDisplay()?.let { focusModel = it }
            val controller = controllerDisplay()
            val steps = HomeSteps.order(top, if (bottom) secondDisplayId() else null, controller)
            var topStarted = false
            var started = false   // b18p review: a start by its display (either screen) — it can take Android's focus there
            for (d in steps) {
                if (d != PRIMARY_DISPLAY) { if (runCatching { goHomeOnDisplay(d) }.getOrDefault(false)) started = true }
                else if (!HomeSteps.topByKey(steps, controller) && runCatching { homeByBackend(PRIMARY_DISPLAY) }.getOrDefault(false)) {
                    topStarted = true; started = true
                }
                else handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }   // the top alone with the controller, or no backend
            }
            // AYN's lock routes the buttons to the top whatever Android's focus
            val aynTopLock = runCatching { android.provider.Settings.System.getInt(contentResolver, "screen_focus_lock", 0) == 1 }.getOrDefault(false)
            val back = HomeSteps.handBack(steps, controller, started, aynTopLock, canRead = PServiceBridge.cachedAvailable())
            val how = if (PRIMARY_DISPLAY !in steps) "" else ", top ${if (topStarted) "by its display" else "by Android's Home"}"
            Log.d(TAG, "Home on ${steps.joinToString(" then ")} (controller on $controller$how${if (back) ", controller handed back" else ""})")
            // a start on the other screen took the focus from a controller that stays where it was (read first: the top's start
            // had moved it ~30 ms after its START line on the device, before `am start` returned; nothing moved: no probe)
            if (back) handler.post { focusDisplay(controller) { syncFocusModel() } }
        }.apply { isDaemon = true }.start()
    }

    /** b18p (GitHub #97): pure (OverlayRulesTest) — which screens go home, in which order, and how the top does. */
    internal object HomeSteps {
        /** The top and/or [second], the [controller]'s screen last: its own start leaves Android's focus where the controller was. */
        fun order(top: Boolean, second: Int?, controller: Int): List<Int> =
            listOfNotNull(PRIMARY_DISPLAY.takeIf { top }, second).sortedBy { it == controller }
        /** Android's Home for the top only when it's the one screen and has the controller (nothing can have moved the focus). */
        fun topByKey(steps: List<Int>, controller: Int): Boolean = steps == listOf(PRIMARY_DISPLAY) && controller == PRIMARY_DISPLAY
        /** The controller is handed back to its screen when a step [started] something by its display and the controller's own
         *  screen wasn't a step (when it is, it's the last one, and its start leaves the focus there). Both mirrors: Home on the
         *  top with the controller on the bottom, and (b18p review) Home on the bottom with the controller on the top — except
         *  while AYN's lock is armed ([aynTopLock]): it already routes the buttons to the top whatever Android's focus, and a
         *  probe there would only take the game's window focus for a moment (#102 d). b18q review: and only where the real
         *  focus can be read ([canRead]: pservice). Without it the controller's screen is the model's guess and nothing says
         *  whether the start moved the focus: a hand-back would be a blind probe on that screen's game (#102 d), which these
         *  Homes never ran before — there the controller isn't handed back, as in 1.4.1. With it, [focusDisplay]'s read
         *  decides (a start whose focus move it doesn't see yet only costs the hand-back, never a probe). */
        fun handBack(steps: List<Int>, controller: Int, started: Boolean, aynTopLock: Boolean, canRead: Boolean): Boolean =
            canRead && started && controller !in steps && !(aynTopLock && controller == PRIMARY_DISPLAY)
    }

    /** 1.3.1 (GitHub #44): the volume, both screens ([top] null — keeping their difference) or one. */
    private fun stepVolume(dir: Int, top: Boolean?) {
        LinkedVolume.init(this)
        val t0 = LinkedVolume.top(); val b0 = LinkedVolume.bottom()
        val (t, b) = when (top) {
            null -> (t0 + dir).coerceIn(0, 15).let { it to (it + LinkedVolume.offset).coerceIn(0, 15) }
            true -> (t0 + dir).coerceIn(0, 15) to b0
            false -> t0 to (b0 + dir).coerceIn(0, 15)
        }
        when (top) {
            null -> LinkedVolume.setBoth(t)
            true -> LinkedVolume.setTopOnly(t)
            false -> LinkedVolume.setBottomOnly(b)
        }
        val txt = if (secondDisplayId() == null) "$t / 15" else "top $t, bottom $b"
        Overlays.pill("Volume — $txt", CueOrigin.Press(ButtonNames.handSrc), group = "volume")
    }

    // ── The AYN button ──────────────────────────────────────────────
    // AYN's drawer is opened from PhoneWindowManager.interceptKeyBeforeDispatching, on
    // scan code 194 (KEY_F24, gpio-keys) — which runs AFTER the accessibility key filter.
    // So consuming the key here keeps AYN's drawer shut: nothing of AYN's is disabled or
    // changed, and turning the option off gives the button straight back.
    // 1.3: a tap and a hold can each do a chosen action (Controller → Quick panel). The hold fires
    // while the button is still down; its release then does nothing more.
    /** dev build 17 (theme G): one AYN button's press — the Thor's (gpio-keys, [src] 0) or a connected controller's (its
     *  spare button, or Home + Start on one with none; [src] as its presses count): its hold fires while it's down, its
     *  release taps unless the hold fired or it made a combo (AYN + a button). Main thread. */
    private inner class AynPress(val src: Int) {
        var holdFired = false
        val hold = Runnable {
            val a = AppSettings.aynHold ?: return@Runnable
            if (buttonEngine?.aynInCombo(src) == true) return@Runnable      // AYN + a button: a combo, not a hold
            holdFired = true
            buttonEngine?.hideList(src) ?: Overlays.release(Held.CHORDS, src)   // 1.3.2: the combo list (if up) closes as the hold acts
            Log.d(TAG, "AYN button held → $a")
            vibrateShort()
            Companion.perform(a, src)
        }
        fun down() {
            holdFired = false
            handler.removeCallbacks(hold)
            val engine = buttonEngine
            // 1.3.1 (GitHub #44): recording a combo, or AYN + a button — the engine has it first
            if (engine != null && engine.capturing) { engine.onAyn(true, src); return }
            engine?.onAyn(true, src)
            // with AYN combos, the hold waits a little longer (time to press the combo's button). Device pass (P2-10, A's note):
            // the engine's one value — its combo list comes up before this hold ([ButtonEngine.listDelay])
            val aynCombos = ControlsStore.effective(buttonHost.currentApp()).any { it.trigger.modifier == ThorButton.AYN }
            if (AppSettings.aynHold != null) handler.postDelayed(hold, if (aynCombos) ButtonEngine.AYN_COMBO_HOLD_MS else AYN_HOLD_MS)
        }
        fun up(canceled: Boolean) {
            handler.removeCallbacks(hold)
            val combo = buttonEngine?.onAyn(false, src) == true
            if (!canceled && !holdFired && !combo) handler.post { onAynButton(src) }
        }
        fun cancel() = handler.removeCallbacks(hold)
    }
    private val aynPresses = HashMap<Int, AynPress>()
    private fun ayn(src: Int) = aynPresses.getOrPut(src) { AynPress(src) }

    private fun aynButtonKey(event: KeyEvent): Boolean {
        if (!AppSettings.aynButtonOurs || event.scanCode != 194) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) ayn(0).down()
            KeyEvent.ACTION_UP -> ayn(0).up(event.isCanceled)
        }
        return true
    }

    /** dev build 17 (theme G): a connected controller's AYN button ([src]: as its presses count — 0 docked, the Thor's): the
     *  Thor's AYN button. With the AYN button left to AYN ("Just switching screens"), its tap opens AYN's drawer, as the
     *  Thor's does. Main thread. */
    private fun padAyn(src: Int, down: Boolean) {
        if (src > 0) ButtonNames.inHand(src)
        if (!AppSettings.aynButtonOurs) { if (!down) openAynDrawer(); return }
        if (down) ayn(src).down() else ayn(src).up(false)
    }

    private fun onAynButton(src: Int = 0) {
        val a = AppSettings.aynTap
        Log.d(TAG, "AYN button → $a" + if (src != 0) " (controller $src)" else "")
        if (a != ThorAction.QUICK_MENU) { Companion.perform(a, src); return }
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

    // ── Game controls while playing ──
    // device pass (P2-2): read off the main thread too (controllerDisplay: the engines, Home's thread)
    @Volatile private var controlsReturnTo: Int? = null
    /** Device pass (final): Game controls opened on another screen than the game's, so it took the controller against the lock
     *  (AYN's top lock lifted, the focus sent to it). Opened on the lock's own screen it holds nothing against the lock: the lock
     *  still says where the controller is ([lockSuspended]) — a bottom touch had the controller read "bottom" with the page driven
     *  on top. */
    @Volatile private var controlsAside = false
    /** dev build 16 (WP6): the controller that opened Game controls (its tab) — passed in the intents, for this opening. */
    private var controlsPad: String? = null
    /** b18g (WI-6a, g2 BUG-g2-5): the app whose controls this session opened. When the session ends, its game profiles left with
     *  nothing of their own go ([endGameControls]), not only one this session made. */
    private var controlsApp: String? = null
    /** b18g (WI-6, g1 gn16 → gn25): the session opened on its app's page because the game wasn't known yet. The page switches
     *  to the game when it is detected ([controlsGameFound]). null = nothing to wait for. */
    private var controlsWait: ControlsWait? = null
    /** b18i (D1): the screen this session's Game controls opened on (null: none open) — the Hub's, or the other one beside the
     *  game ("Open on the other screen"); and whether its window took the controller yet ([controlsShown]: once a session). */
    private var controlsAt: Int? = null
    private var controlsHanded = false

    /** b18i (D1): Game controls' window is up on [d] — its start, or a window already in front asked for it (MainActivity's
     *  announce). Opened on another screen than the game's ([controlsAside]), it takes the controller now, once per opening.
     *  It was a move 250 ms after the launch, a guess at when the window would be there. Usually the launch has given that
     *  screen the focus already: the move reads so and does nothing (b18g, #102 d). */
    private fun controlsShown(d: Int) {
        if (MainActivity.gameControls == null || controlsReturnTo == null || !controlsAside || d != controlsAt || controlsHanded) return
        controlsHanded = true
        focusDisplay(d) { syncFocusModel() }
    }

    /** b18g (WI-6): Game controls opened on [pkg]'s page on [display] before its game was known ([openedAt]: uptime ms). [app]
     *  and [padVersion] are the app's controls and the controllers' own ([PadProfiles.version]) at that moment. The page
     *  switches to the game only while both are unchanged ([untouched]): a change made on the app's page stays where it was
     *  made, and so does the page. b18h (review): only within a bound ([inTime]), and only while the window still shows [page],
     *  the page it opened on ([onItsPage]). b18i (D2): the bound is about 10 s (1.4 polled 3 s; GameNative's first answer
     *  came later, g1 gn16, and a game read among several open files can need a second look). Pure (P3BundleTest). */
    internal class ControlsWait(val pkg: String, val display: Int, val app: AppConfig, val padVersion: Int, val openedAt: Long,
                                val page: String = HubPage.playFor(pkg)) {
        fun untouched(appNow: AppConfig, padVersionNow: Int): Boolean = appNow == app && padVersionNow == padVersion
        /** The game came [now] (uptime ms) within [SWITCH_MS] of the page opening. */
        fun inTime(now: Long): Boolean = now - openedAt in 0L..SWITCH_MS
        /** The window shows [shown] — null: not drawn yet, so the page it was opened on. A page the user went to since
         *  (another app's from "Which game?", a sub-page) stays. */
        fun onItsPage(shown: String?): Boolean = shown == null || shown == page
        companion object {
            /** The window for a game detected after the page opened. b18i (D2, the B11 call): 10 s (1.4 polled 3 s). */
            const val SWITCH_MS = 10_000L
        }
    }

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
        val hubDisplay = if (AppSettings.hubOnTop) PRIMARY_DISPLAY else secondDisplayId() ?: PRIMARY_DISPLAY
        // no game on the controller's screen (a launcher, Wayfinder): Game controls has nothing to open. Build 18 (E3, pF17 /
        // gd-D29): Wayfinder opens on Games, where each app's controls are (the tile and Home + X were a dead end); the pill
        // only if it can't open
        if (pkg == null) {
            val opened = runCatching {
                startActivity(Intent(this, MainActivity::class.java).putExtra("page", HubPage.APPS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(hubDisplay).toBundle())
            }.onFailure { Log.w(TAG, "game controls → Games: ${it.message}") }.isSuccess
            if (!opened) Overlays.pill("Start a game first", CueOrigin.Screen(game))
            return
        }
        endParkedControls()   // b18i (leftovers): a session set aside by a Hub page shown in place ends before a new one
        controlsReturnTo = game
        // the lift of AYN's lock and the focus move below happen only then
        controlsAside = hubDisplay != game
        // b18i (D1, 2026-10-10): "Open on the other screen" — the screen the game isn't on, where the game keeps running
        // (Android keeps an app on another screen resumed; covered by the page, Eden and GameNative paused). Only when that
        // screen is free (lit, not covered by a dual-screen game's second screen nor by Keyboard & mouse): else, and with one
        // screen or docked, where the Hub is, as before. AYN's own bottom-off is read in the thread (root)
        val other = if (AppSettings.controlsOtherScreen && !Dock.docked) ControlsPlace.other(game, PRIMARY_DISPLAY, secondDisplayId()) else null
        val otherTaken = other != null && (blanker?.isBlanked(other) == true || coveredByPresentation(other) ||
            inputDeck?.let { it.isShowing && !it.beside && it.displayId == other } == true)
        // b18i (D1, option C): whether the game pauses itself while the page is open — its frames, from now ([onFpsLook])
        startPauseWatch(pkg, game)
        // Home + X pressed on a connected controller: the page opens on ITS controls (2026-10-03). dev build 16 (WP6):
        // the controller whose combo fired it (ButtonEngine counts a docked one feeding the Thor's copy as the Thor: the
        // Thor's tab), else the one in hand (the quick-panel tile) — in the intent; the page keeps it for the session
        val src = buttonEngine?.recentActionSrc(1500) ?: ButtonNames.handSrc
        controlsPad = src.takeIf { it > 0 }?.let { ExtPadsCtl.keyOfSrc(it) }
        // which game it is — the page offers "controls just for <game>" (the answer comes in ~50 ms)
        if (pkg != null && pkg !in launcherPackages) GameProfiles.ask(pkg)
        Thread {
            val dark = otherTaken || (other != null && other != PRIMARY_DISPLAY && PServiceBridge.cachedAvailable() && aynScreenMode() == 1)
            val at = ControlsPlace.pick(AppSettings.controlsOtherScreen, hubDisplay, other, Dock.docked, dark)
            // AYN's top lock would keep the buttons on the top screen (as for the quick panel)
            if (at != PRIMARY_DISPLAY && AppSettings.focusLockEnabled && AppSettings.focusLockTop)
                PServiceBridge.exec("settings put system screen_focus_lock 0")
            // 1.4: the GAME's controls when a game runs — what you expect in a game. A new game profile starts
            // as a copy of the app's controls and is dropped again when nothing was changed ([onGameControlsClosed]).
            // The detection answers in ~50 ms: wait for it a little.
            var tries = 0
            while (GameProfiles.runningIn(pkg) == null && tries++ < 6) Thread.sleep(40)
            handler.post {
                val running = GameProfiles.runningIn(pkg)
                val key = if (running == null) GameProfiles.activeKey(pkg) else {
                    val k = GameProfiles.key(pkg, running.game)
                    if (GameProfiles.get(k) == null) {
                        GameProfiles.create(pkg, running.game, running.title)
                        controlsNewGame = k to GameProfiles.get(k)
                    }
                    k
                }
                // b18i (D1): where it opens. On another screen than the game's it takes the controller when its window is up
                // ([controlsShown]: an event, no fixed wait — it was a move 250 ms after the launch)
                controlsAt = at
                controlsAside = at != game
                controlsHanded = false
                if (at != hubDisplay) Log.w(TAG, "game controls: on the other screen ($at), the game stays on $game")
                runCatching {
                    startActivity(controlsIntent(HubPage.playFor(key), at),
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(at).toBundle())
                }.onFailure { Log.w(TAG, "game controls: ${it.message}") }
                applyFps()   // b18i (D1): a counter over the app it covers steps aside, as for the Hub (b18n: on either screen)
                // b18g (WI-6a): the app whose game profiles this session's end looks at
                controlsApp = pkg
                // 1.4: the game wasn't known yet (just started): if it's detected within 3 s, the page switches to it. b18g
                // (WI-6): on events — the detection answering ([gameChanged] → [controlsGameFound]) and the window coming up —
                // not a polling thread. b18h (review): only while the window shows this page. b18i (D2): within 10 s
                // ([ControlsWait.SWITCH_MS]; GameNative's slow first answer, g1 gn16 → gn25), the tick asking again meanwhile
                controlsWait = if (running == null) ControlsWait(pkg, at, AppConfigStore.get(pkg), PadProfiles.version.intValue,
                    android.os.SystemClock.uptimeMillis(), HubPage.playFor(key)) else null
            }
        }.apply { isDaemon = true }.start()
    }

    /** b18i (D1): Game controls' window on [page], opened on [display]. On a screen that isn't the Hub's it is a window of its
     *  own (MULTIPLE_TASK): a Hub left in the background would otherwise be taken from the Hub's screen and moved across
     *  (Android recreates a window moved to a screen of another size, and the Hub's state with the intent went), and its
     *  screen goes in the intent ([MainActivity.EXTRA_SCREEN]): the window stays there, never moved to the Hub's screen
     *  (MainActivity.relocateIfNeeded), and its task goes when it closes (MainActivity.finish). */
    private fun controlsIntent(page: String, display: Int): Intent = Intent(this, MainActivity::class.java)
        .putExtra("page", page).putExtra("pad", controlsPad)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        .apply {
            if (display != (if (AppSettings.hubOnTop) PRIMARY_DISPLAY else secondDisplayId() ?: PRIMARY_DISPLAY))
                putExtra(MainActivity.EXTRA_SCREEN, display).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

    /** b18i (D1, 2026-10-10): where Game controls opens. Pure (D1RulesTest). */
    internal object ControlsPlace {
        /** The other one of the Thor's two screens beside a game on [game]; null with one screen, or a game on neither. */
        fun other(game: Int, primary: Int, second: Int?): Int? = when {
            second == null || second == primary -> null
            game == primary -> second
            game == second -> primary
            else -> null
        }
        /** "Open on the other screen" [on] and [other] free (not [docked], not [taken]: dark, or covered by a dual-screen game's
         *  second screen or by Keyboard & mouse): [other]. Else the Hub's screen [hub] — today's place. */
        fun pick(on: Boolean, hub: Int, other: Int?, docked: Boolean, taken: Boolean): Int =
            if (on && other != null && !docked && !taken) other else hub
    }

    /** b18g (WI-6, g1 gn16 → gn25): Game controls opened on its app's page because the game wasn't known yet ([controlsWait]).
     *  Now the game is known: the page switches to it, as 1.4 did within 3 s. It waits on events, never on a timer: the game's
     *  detection ([gameChanged]) and the page's window coming up ([onHubShown]: an answer can come before the window exists).
     *  The session's end forgets it ([endGameControls]). Nothing changed on the app's page ([ControlsWait.untouched]): a
     *  change made there stays where it was made, and the page with it — its "Not this game? Switch" then offers the game.
     *  b18h (review): within a bound only ([ControlsWait.inTime]: b18i D2, 10 s), and only while the window still shows the
     *  page it opened on ([ControlsWait.onItsPage]): a late answer took the user back from a page they went to. */
    private fun controlsGameFound() {
        val w = controlsWait ?: return
        val r = GameProfiles.runningIn(w.pkg) ?: return
        // the window isn't there yet (its onResume comes back here) — or it became the Hub's page in place: no switch
        if (MainActivity.gameControls == null) return
        controlsWait = null
        val now = android.os.SystemClock.uptimeMillis()
        val after = now - w.openedAt
        if (controlsNewGame != null) return
        // release log, no app name: how late the game came (for D2)
        if (!w.inTime(now)) { Log.w(TAG, "game controls: the game was found $after ms after the page opened, too late — the page stays"); return }
        if (!w.onItsPage(MainActivity.gameControlsPage())) {
            Log.w(TAG, "game controls: the game was found $after ms after the page opened, on another page now — the page stays")
            return
        }
        if (!w.untouched(AppConfigStore.get(w.pkg), PadProfiles.version.intValue)) {
            Log.w(TAG, "game controls: the game was found $after ms after the page opened, after a change there — the page stays")
            return
        }
        val k = GameProfiles.key(w.pkg, r.game)
        if (GameProfiles.get(k) == null) { GameProfiles.create(w.pkg, r.game, r.title); controlsNewGame = k to GameProfiles.get(k) }
        Log.w(TAG, "game controls: the game was found $after ms after the page opened — the page switches to it")
        // the same controller: the page keeps whichever tab the user is on (not keyed on the game). b18i (D1): handed to the
        // window itself, as its onNewIntent would — an intent could land in another Wayfinder window (the Hub's, left on its
        // own screen) now that Game controls can be a window of its own beside the game
        MainActivity.gameControls?.switchTo(HubPage.playFor(k), controlsPad)
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

    /** 1.4: a game profile Home + X just made (key, as made): removed on close if still untouched. */
    private var controlsNewGame: Pair<String, GameProfile?>? = null

    private fun onGameControlsClosed() {
        val back = endGameControls() ?: return
        // b18i (D1, option C): did the game pause itself while the page was open? Its next frames tell ([onFpsLook])
        pauseWatchClosed()
        applyFps()   // b18i (D1): the counter of the app the page covered comes back (b18n: on either screen)
        handler.postDelayed({
            if (AppSettings.focusLockEnabled) applyFocusLock(announce = false)
            else focusDisplay(back) { syncFocusModel() }
        }, 150)
    }

    /** Game controls' session ends (closed, or turned into the Hub): a new game profile left untouched goes. Returns the screen
     *  it took the controller from (null: none open). */
    private fun endGameControls(): Int? {
        controlsNewGame?.let { (k, made) -> if (made != null && GameProfiles.get(k) == made) GameProfiles.remove(k) }
        controlsNewGame = null
        // b18g (WI-6a, g2 BUG-g2-5): also each game profile of the session's app left with nothing of its own (its app's
        // controls, no setting) — a copy an earlier session made, changed here and changed back, outlived this session until
        // the next service start ([GameProfiles.dropPlain]); one the page switched to or made ("Controls just for …") too
        controlsApp?.let { a -> GameProfiles.forApp(a).forEach { (k, _) -> if (GameProfiles.isGame(k)) GameProfiles.removeIfPlain(k) } }
        controlsApp = null
        controlsWait = null
        controlsPad = null
        controlsAside = false
        controlsAt = null; controlsHanded = false   // b18i (D1)
        controlsParked = 0                          // b18i (leftovers)
        return controlsReturnTo.also { controlsReturnTo = null }
    }

    /** b18i (leftovers): the window (its token) whose Game controls session is set aside while that window shows a Hub page in
     *  place ([onGameControlsToHub]); 0 = none. Back on the game's page in place, the session goes on ([onHubToGameControls]);
     *  the window left as the Hub ([onHubHidden]), another window taking the Hub ([onHubShown]) or a new Game controls
     *  ([openGameControls]) ends it ([endParkedControls]). */
    private var controlsParked = 0
    private fun endParkedControls() { if (controlsParked != 0) endGameControls() }

    /** Device pass (d3 N2): Game controls on [d] became the Hub (MainActivity.becomes): the controller stays on the page — lent to
     *  the Hub now, as [onHubShown] lends it: the lock set aside until the Hub is left ([onHubHidden] restores it). Sent back to
     *  the game here, it left the Hub just opened. b18i (leftovers): the session is set aside ([controlsParked]), not ended at
     *  once — Game controls' "All apps" (now through becomes()) shows the Hub's Games page, and B there comes back to the game's
     *  page (FEATURES B5.7): an untouched new game profile dropped here left that page on a profile that no longer existed, its
     *  edits lost (Profiles.update). Leaving the Hub ends it as B would. */
    private fun onGameControlsToHub(d: Int, token: Int) {
        val back = controlsReturnTo
        if (back != null) controlsParked = token   // the one session, set aside by the window that holds it
        stopPauseWatch()   // b18i (D1): the Hub stays over the game — no "paused itself" pill under it
        hubOn = d; hubToken = token
        if (AppSettings.focusLockEnabled && back != null && back != d && hubReturnTo == null) hubReturnTo = back
        applyFps()
    }

    /** Device pass (d3 N2): the Hub on screen became Game controls (MainActivity.becomes): the Hub's lend ends without a move —
     *  Game controls holds the controller now, and its close puts it back ([onGameControlsClosed]). Left set, nothing cleared it:
     *  the lock stayed set aside after Game controls closed. b18i (leftovers): the session it set aside goes on (B from the Games
     *  page "All apps" opened); with no session at all (a restored window), the lend stays and the window's stop ends it. */
    private fun onHubToGameControls(token: Int) {
        if (token != hubToken || hubOn == null) return
        if (controlsParked == token) controlsParked = 0
        else if (controlsReturnTo == null) return   // cleared here, nothing would give the lock back
        hubOn = null; hubReturnTo = null
        applyFps()
    }

    // ── The Hub has the controller while it's open (2026-09-26: tapped open on the bottom
    // screen with the controller locked there, the Hub — which lives on top — couldn't be driven).
    // The lock is set aside meanwhile (AYN's top lock lifted if the Hub is on the bottom; our own
    // bottom lock doesn't pull focus back), and restored when the Hub is left.
    private var hubOn: Int? = null
    /** b18j (WI-8, device b18v v2 runs 2b / 4b): the Hub's lend of the controller — the screen it goes back to when the Hub is
     *  left — and the lock's restore an ended lend asks for. Every lend taken or ended counts: a restore still waiting when
     *  another lend is taken or ended is moot, so a finished or superseded move leaves nothing behind. Pure (FocusProbeTest). */
    internal class HubLend {
        /** The screen the controller goes back to when the Hub is left; null = no lend. Read from any thread (P2-2). */
        @Volatile var returnTo: Int? = null
            private set
        private var seq = 0
        /** A lend taken ([back]: where the controller goes back to) or ended without a move (null). */
        fun set(back: Int?) { returnTo = back; seq++ }
        /** The window holding it was left: the lend ends. Returns the lock's restore ticket ([due]), or null when none was held. */
        fun end(): Int? { if (returnTo == null) return null; set(null); return seq }
        /** Ticket [t]'s restore is still the last word on the lend: nothing was taken or ended since. */
        fun due(t: Int): Boolean = t == seq
        /** Window [token] takes the Hub while window [holder]'s lend is held: that lend is superseded and ends here (the old
         *  window's stop no longer counts: its token isn't the Hub's). True: one was. */
        fun supersede(holder: Int, token: Int): Boolean {
            if (returnTo == null || holder == token) return false
            set(null); return true
        }
    }
    private val hubLend = HubLend()
    /** device pass (P2-2): controllerDisplay reads it from any thread. b18j: [hubLend]'s; every write counts as a lend taken / ended. */
    private var hubReturnTo: Int?
        get() = hubLend.returnTo
        set(v) = hubLend.set(v)
    private var hubToken = 0
    private fun onHubShown(d: Int, token: Int) {
        controlsGameFound()   // b18g (WI-6): Game controls' window is up — its game, detected before the window existed, switches it
        controlsShown(d)      // b18i (D1): Game controls on another screen than the game's takes the controller now
        if (MainActivity.gameControls != null) return        // Game controls does this itself
        // b18i (leftovers): another window is the Hub now (a relocation's new window): the session the old one set aside ends —
        // that window's stop no longer counts (token)
        if (token != controlsParked) endParkedControls()
        // b18j (WI-8): so does the old window's lend. Hub screen › Top from the bottom: the new top window found the controller
        // on its own screen and kept the old window's lend (AYN's top lock left lifted). The next › Bottom then ended that lend
        // as the top window stopped, and its lock restore, 150 ms later, pulled the controller and AYN's lock back to the top
        // under the new bottom Hub. Superseded, it ends here: this window takes its own lend, or the lock comes back now
        val superseded = hubLend.supersede(hubToken, token)
        hubOn = d; hubToken = token
        applyFps()
        val ctl = controllerDisplay()
        if (ctl == d) { if (superseded && AppSettings.focusLockEnabled) applyFocusLock(announce = false); return }
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
        endParkedControls()   // b18i (leftovers): Game controls turned Hub in place, left — its session ends as on B
        applyFps()
        // b18j (WI-8): the lock's restore only while it is still the last word on the lend — a Hub window shown meanwhile that
        // took the controller (a relocation's new window: its start can come after this stop) holds a new lend, ended by its own stop
        val ticket = hubLend.end() ?: return
        handler.postDelayed({ if (hubLend.due(ticket) && AppSettings.focusLockEnabled) applyFocusLock(announce = false) }, 150)
    }
    /** Build 18 review (E1): the Hub on [d] gets the controller back (B on "Your combos") the way it took it when shown: a move,
     *  never a lock written — a set lock is set aside while the Hub is open and [onHubHidden] restores it. B took Home + right
     *  stick's path, which saves the lock: one B turned "Always on the bottom screen" into "Always on the top screen". */
    private fun giveHubTheController(d: Int) {
        if (deckInTheWay(d)) return
        val ctl = controllerDisplay()
        // only for the Hub session that will give it back (its onHubHidden clears it): a stray one would hold the lock off
        if (AppSettings.focusLockEnabled && ctl != d && hubOn == d && hubReturnTo == null) hubReturnTo = ctl
        // AYN's top lock lifted only when the Hub's close re-arms it (as onHubShown does)
        val lift = hubReturnTo != null && AppSettings.focusLockEnabled && AppSettings.focusLockTop && d != PRIMARY_DISPLAY
        Thread {
            if (lift) PServiceBridge.exec("settings put system screen_focus_lock 0")
            handler.post { switchControllerFocus(d) }
        }.apply { isDaemon = true }.start()
    }
    /** A page that holds the controller against the lock is open (the Hub, Game controls) — one that took it from another screen
     *  (as [onHubShown]'s `ctl == d`): on the lock's own screen the lock keeps it there, and the focus follows any finger. */
    private fun lockSuspended() = ControllerScreen.suspended(hubReturnTo != null, controlsReturnTo != null, controlsAside)

    /** The panel closed: the controller (and its lock) go back where they were. */
    private fun onQuickPanelClosed() {
        val back = panelReturnTo ?: PRIMARY_DISPLAY
        panelReturnTo = null; panelOver = null
        if (!panelTook) return   // touch-only (#31): the game never lost the controller
        // 1.4: closed because the screens went off — nothing can take focus now: at the wake
        if (!screenOn) { lockOnWake = AppSettings.focusLockEnabled; if (!lockOnWake) focusOnWake = back; return }
        handler.postDelayed({
            val hub = hubOn
            if (hub != null && hubReturnTo != null) focusDisplay(hub) { syncFocusModel() }
            // device pass (P2-2): Game controls holding it against the lock gets it back too (the lock sent it to the game's
            // screen with the page still open) — [back] is where it was when the panel opened
            else if (lockHolds()) applyFocusLock(announce = false)
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
        // b18i (D1): nor over the app Game controls covers on the other screen (it covers it as the Hub does). b18n (device b18v
        // #11): nor over Game controls on the game's own screen — the panel tile says "Not over Wayfinder", yet the counter
        // stayed over the page there, reading "idle" once the game under it stopped. Only the counter steps aside: the game is
        // still counted for the pause watch (countTop / countBottom below)
        val covered = controlsAt?.takeIf { controlsReturnTo != null }
        fun appOn(d: Int?) = d?.takeIf { it != hubOn && it != covered }?.let { displayApps[it] ?: appOnDisplay(it) }?.takeIf { Shell.isPkg(it) && it !in ignoredPackages && it !in launcherPackages }
        val second = secondDisplayId()
        val on = AppSettings.fpsCounter
        // 1.3 (GitHub #22): an app / game can turn the counter on or off for itself
        fun wants(app: String?, screenOk: Boolean) = app != null && (GameProfiles.effective(app).fps ?: (on && screenOk))
        val top = appOn(PRIMARY_DISPLAY)?.takeIf { wants(it, AppSettings.fpsScreens != 1) }
        val bottom = appOn(second)?.takeIf { wants(it, AppSettings.fpsScreens != 0) }
        // b18i (D1, option C): the game Game controls opened for is counted too, while the page is open and just after — its
        // frames tell whether it paused itself ([onFpsLook]). Its counter shows only if it is on; a screen whose counter
        // counts another app can't tell ([pauseSlotOk] false)
        val watch = pauseFor
        val countTop = top ?: watch?.takeIf { it.second == PRIMARY_DISPLAY }?.first
        val countBottom = bottom ?: watch?.takeIf { it.second != PRIMARY_DISPLAY && it.second == second }?.first
        pauseSlotOk = watch != null && (if (watch.second == PRIMARY_DISPLAY) countTop else countBottom) == watch.first
        // b18j (D1, device b18v v2): the watched game's slot is kept on its own surface — the layer that drew the most when the
        // watch began (FpsSampler / LayerPick), not the app's busiest one: Eden's window redrew its paused screen (2 frames) after
        // the close while its game surface drew none, and that read as "the game draws again"
        val keepTop = pauseSlotOk && watch?.second == PRIMARY_DISPLAY
        val keepBottom = pauseSlotOk && watch?.second != PRIMARY_DISPLAY
        // build 18 (GitHub #86): a new size or style places the counter again too
        val key = "$top|$bottom|${AppSettings.fpsCorner}|${AppSettings.fpsSize}|${AppSettings.fpsCompact}|$countTop|$countBottom|$keepTop|$keepBottom"
        if (key == fpsWatching) return
        fpsWatching = key
        fun place(o: FpsOverlay?, app: String?, d: Int?): FpsOverlay? {
            if (app == null || d == null) { o?.hide(); return null }
            return (o ?: FpsOverlay(this)).also { it.show(d, AppSettings.fpsCorner, AppSettings.fpsSize, AppSettings.fpsCompact) }
        }
        fpsOverlay = place(fpsOverlay, top, PRIMARY_DISPLAY)
        fpsOverlayBottom = place(fpsOverlayBottom, bottom, second)
        // b18i (review): counts of what was counted before go; a line in flight from before is no count of now
        val ct = countTop; val cb = countBottom; val gen = ++countsGen
        lastCounts = null
        InputMonitor.fpsListener = { t, b, kt, kb ->
            fpsOverlay?.update(t); fpsOverlayBottom?.update(b)   // the counter: the busiest layer, as before
            if (gen == countsGen) lastCounts = (ct to t) to (cb to b)
            if (pauseWatch != null) handler.post { onFpsLook(kt, kb) }   // b18i (D1): the helper's thread; b18j: the kept surface
        }
        val keep = if (keepTop || keepBottom) " ${if (keepTop) 1 else 0} ${if (keepBottom) 1 else 0}" else ""
        val sent = InputMonitor.send(if (countTop == null && countBottom == null) "F -" else "F ${countTop ?: "-"} ${countBottom ?: "-"}$keep")
        if (!sent) fpsWatching = null   // helper not there yet: retried when it connects
        Log.d(TAG, "fps counter → top ${top ?: "-"} · bottom ${bottom ?: "-"}${if (sent) "" else " (helper not connected yet)"}" +
            if (watch != null) " · counting $countTop / $countBottom" else "")
    }
    private var fpsOverlayBottom: FpsOverlay? = null

    // ── b18i (D1, option C): "The game paused itself" after Game controls ──
    // Covered by the page, some games pause (Eden, GameNative: g1 OBS-1) and stay paused once it closes; RetroArch and PPSSPP
    // go on. The signal is the root helper's frame count ([FpsSampler]: frames SurfaceFlinger presented in the last second for
    // the app's busiest layer; 0 = nothing drawn, the counter's "idle"). Watched from the page's opening to two looks after its
    // close: it ran, then stopped while open, and still draws nothing after → the pill. A game that goes on by itself: none.
    // b18j (device b18v v2, Eden ×2): the looks count the game's own surface — the helper keeps the watched slot on the layer
    // that drew the most when the watch began ([applyFps]'s keep, FpsSampler / LayerPick): Eden's window redrew its paused screen
    // (2 frames) while its game surface drew nothing, and the busiest layer read "draws again". Any frame of the kept surface after
    // the close is still "draws again" ([GamePauseWatch.after], b18j review): an app that redraws once as it resumes and then draws
    // nothing because nothing changes has not paused itself.
    /** The judgement of the current (or just closed) opening; null = nothing watched. Read on the helper's thread. */
    @Volatile private var pauseWatch: GamePauseWatch? = null
    /** The game watched and its screen. */
    private var pauseFor: Pair<String, Int>? = null
    /** The frame count of [pauseFor]'s screen is the game's own (the counter there doesn't count another app). */
    private var pauseSlotOk = false

    /** The helper's last frame counts and the apps they counted (top, bottom); null after what is counted changed ([applyFps]). */
    @Volatile private var lastCounts: Pair<Pair<String?, Int>, Pair<String?, Int>>? = null
    @Volatile private var countsGen = 0

    /** b18i (review): "ran" needs the game drawing steadily ([GamePauseWatch]) — judged against its screen's refresh rate. A game
     *  the counter already counts there starts with its last count: the second before the page (the next look may cover
     *  only part of it). */
    private fun startPauseWatch(pkg: String, d: Int) {
        val w = GamePauseWatch(getSystemService(DisplayManager::class.java)?.getDisplay(d)?.refreshRate ?: 60f)
        lastCounts?.let { (t, b) -> (if (d == PRIMARY_DISPLAY) t else b).takeIf { it.first == pkg }?.let { w.look(it.second) } }
        pauseWatch = w; pauseFor = pkg to d
        applyFps()
    }
    private fun stopPauseWatch() {
        if (pauseWatch == null && pauseFor == null) return
        pauseWatch = null; pauseFor = null
        applyFps()
    }
    /** The page closed: watched on for the next looks only when the game ran, then stopped while it was open. */
    private fun pauseWatchClosed() { if (pauseWatch?.close() != true) stopPauseWatch() }

    /** One look of the frame count (main thread): top, bottom (-1: not counted) — the kept surface's (b18j). */
    private fun onFpsLook(top: Int, bottom: Int) {
        val (pkg, d) = pauseFor ?: return
        val w = pauseWatch ?: return
        val frames = if (!pauseSlotOk) -1 else if (d == PRIMARY_DISPLAY) top else bottom
        if (controlsReturnTo != null) { w.look(frames); return }   // the page is open
        // closed: said only while the game is the one in front on its screen, nothing of Wayfinder's over it
        val still = screenOn && displayApps[d] == pkg && !MainActivity.hubOn(d) && MainActivity.gameControls == null
        // b18i (review): the page's window is still going away (B: the service hears it before the window's stop; Home + X
        // again: before its pause) — nothing is settled in front yet: look again once it is gone, not "it left its screen"
        if (!still && MainActivity.closing()) return
        val paused = if (still) w.after(frames) ?: return else false
        stopPauseWatch()
        // release lines, no app name
        if (paused) {
            Log.w(TAG, "game controls: the game stopped drawing while the page was open and still draws nothing — the pill")
            Overlays.pill(GameWords.PAUSED_ITSELF, CueOrigin.Screen(d))
        } else Log.w(TAG, "game controls: the game " + (if (!still) "left its screen" else if (frames < 0) "can't be counted any more"
            else "draws again") + " after the page closed — no pill")
    }

    // ── Per-app performance / fan ───────────────────────────────────
    /** Two apps on screen with different wishes: the chip can only run one mode, so the
     *  most demanding request wins (a game on top asking for High isn't slowed down by a
     *  browser below asking for Standard). "Default" is not a request. Buttons and stick
     *  lights don't need this rule: they follow the app that has the controller. */
    private fun applyPerf() {
        val onScreens = listOfNotNull(displayApps[PRIMARY_DISPLAY], secondDisplayId()?.let { displayApps[it] }).distinct()
        GameProfiles.forgetExcept(onScreens.toSet())
        val cfgs = onScreens.map { GameProfiles.effective(it) }      // a running game's own values first
        // the most demanding of the apps on the screens wins; none asks → AYN's own setting stays
        val own = cfgs.mapNotNull { it.perf }.maxByOrNull { it.ordinal }
        // ClusterTune / Pulse installed → no clocks, no AYN mode from us (fan and Hz still)
        val tuner = Tuners.installed(this) != null
        val pulse = Tuners.ownsFanAndHz(this)
        PerfProfiles.apply(this, if (tuner) null else own,
            if (pulse) null else cfgs.mapNotNull { it.fan }.maxByOrNull { it.ordinal },
            if (pulse) null else cfgs.mapNotNull { it.hz }.maxOrNull())
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
        if (!moveGate.claim()) {
            // b18g (#99): a move runs — never queued (a pair or Open is the user's choice of now), but said, not silent
            Log.w(TAG, "Layout ($why): a move is running — not opened")
            if (why != "restore after restart") sayStillMoving()
            return false
        }
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
                restoringLayout = false
                endMove()                   // b18g (#99): a swap asked for meanwhile runs now
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
        handler.postDelayed({
            // still in front? (it may have been moved by its profile in the meantime)
            val d = displayApps.entries.firstOrNull { it.value == pkg }?.key ?: return@postDelayed
            if (prefs.getBoolean("tip_first_game", false)) return@postDelayed
            // dev build 16 (theme F): in the names of the controller in hand, with this game's own combos
            val look = ButtonNames.handLook
            val gc = Say.combo(ThorAction.GAME_CONTROLS, look, pkg)
            // dev build 17 (WP10): seen once it has been on screen 2 s (the host writes the flag) — written before showing, a
            // tip covered 50 ms later by another cue was never seen again
            if (Overlays.tip(this, "tip_first_game", CueOrigin.Screen(d), listOf("Your first game with Wayfinder"),
                    listOfNotNull(gc?.let { it to "its own buttons, turbo, gyro" },
                        Say.hold(ThorButton.HOME.spoken(look), cap = true) to "all your combos")))
                Log.i(TAG, "First game ($pkg): showing the Game controls tip")
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
        if (!moveGate.claim()) { Log.w(TAG, "Route: a move is running — not routed now"); return }   // not stamped: it can be retried
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
                endMove()                   // b18g (#99): a swap asked for meanwhile runs now
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
            // b18g (GitHub #49): the whole screen from ScreenSize; the configuration's read made the top one 1240 px wide
            val full = ScreenSize.real(this, displayId)
            val area = full?.let { it.x.toLong() * it.y } ?: 0L
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
            // b18j: Recents over that screen (or just closing): it covers the app, whose window isn't listed under it — nothing
            // is gone for that; once Recents is gone, [recheckAfterRecents] reads the screen
            if (d == recentsDisplay || d == recentsLeftOn) continue
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
        // dev build 17 (WP10): any button, every controller — read only (nothing consumed, nothing routed differently)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Overlays.anyButton()
        if (aynButtonKey(event)) return true
        // An open Thor Keyboard owns the whole pad (nothing leaks to the app).
        if (app.wayfinder.keyboard.ThorKeyboardService.instance?.captureKey(event) == true) return true
        if (recentsKey(event)) return true
        return buttonEngine?.onKey(event) ?: false
    }

    internal val buttonHost = object : ButtonEngine.Host {
        override fun perform(action: ThorAction, fromHold: Boolean, arg: String?, src: Int) {
            if (fromHold) vibrateShort()
            if (action == ThorAction.OPEN) Companion.open(arg) else Companion.perform(action, src)
        }

        override fun nativePress(button: ThorButton, src: Int) {
            // dev build 17 (theme G): a connected controller's Back on its own pad (its game sees its Back)
            if (button == ThorButton.BACK) { if (buttonEngine?.pressBackOnPad(src) != true) performGlobalAction(GLOBAL_ACTION_BACK); return }
            // the welcome tour in front: a Home tap stays on it (it asks you to HOLD Home; a short press lost the tour)
            if (TourPractice.inFront) {
                // build 18 (E12, ps-F7): the pressing controller's Home ("PS goes home…"), and the tour's own button's name (B13)
                Overlays.pill("${Say.btn(ThorButton.HOME, ButtonNames.lookOfSrc(src))} goes home after the tour — hold it to see your " +
                    "combos" + (if (TourPractice.leaveShown) ", or Leave the tour" else ""), CueOrigin.Window(TourPractice.display))
                return
            }
            QuickPanelWindow.close()   // Home leaves the panel too (it's a drawer)
            swapWatchGen++             // and the user's Home wins over a post-swap watch
            // Android's Home only knows the TOP screen: with the controller on the bottom one,
            // Home left that screen alone and sent the top one home (2026-09-24). Home
            // goes home on the screen that HAS the controller. Only the controller's Home comes
            // here — the AYN button (also a Home key) stays AYN's drawer ([printedButton]).
            // Android's own focused screen, not our model: an app moved or opened on the other
            // screen takes focus without going through us.
            // Device pass (P2-2): [controllerDisplay] — with the Hub holding the controller against the lock, Home went home on
            // the lock's screen (the game there left), not on the Hub's.
            Thread {
                if (!lockHolds()) rootFocusedDisplay()?.let { focusModel = it }
                val target = controllerDisplay()
                if (target == PRIMARY_DISPLAY) handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
                else runCatching { goHomeOnDisplay(target) }
            }.apply { isDaemon = true }.start()
        }

        override fun showChordHint(modifier: ThorButton, chords: List<Binding>, src: Int): Boolean {
            // dev build 17 (theme G, D4): on a controller with no Back / AYN of its own, Home + Select / Start stand for them —
            // not where the user has a combo of their own (it wins, [ButtonEngine.standDown])
            val look = ButtonNames.lookOfSrc(src)
            val extra = if (modifier == ThorButton.HOME) ExtPadsCtl.standInsOf(this@ForegroundAppService, src)?.let { si ->
                Say.standInLines(ExtRules.StandIns(si.back && chords.none { it.trigger.button == ThorButton.SELECT },
                    si.ayn && chords.none { it.trigger.button == ThorButton.START }), look, aynJob()) }.orEmpty() else emptyList()
            if (chords.isEmpty() && extra.isEmpty()) return false
            if (AppSettings.chordHint == 2 && !TourPractice.active) return false   // 1.4 (GitHub #52): off (the tour still shows it)
            if (modifier == ThorButton.HOME) TourPractice.sawHint()
            // In words: where the controller is and how it behaves (several ways to route it —
            // the hint says which one is on), then the combos, the button in bold
            // 1.3.2: the AYN button's own hold fires while you read its combos — so the list says what it does
            // build 18 (E6, MORE-21): actions by their titles, as everywhere else ("Game controls", not "game controls")
            val holdLine = if (modifier == ThorButton.AYN) AppSettings.aynHold?.let { listOf("keep holding" to chordLabel(it)) }.orEmpty() else emptyList()
            // build 18 (E12, ps-F14): holding a connected controller's Home, what its own Back / AYN-job buttons do alone (a
            // DualSense player never found Recent apps on a touchpad double-click) — a line under the list, at its width
            val also = if (modifier == ThorButton.HOME && src > 0) HeldWords.also(ownButtons(), look)?.let { listOf("Also" to it) }.orEmpty()
                else emptyList()
            // dev build 15 (WP2, plan F-b): in the names of the controller holding it ("Keep PS held… △") — FocusCue draws
            // PlayStation's marks from the text. dev build 17 (WP10): a held list of ITS controller (critique K12), on the
            // controller's screen — it covers a tip or a pill there, never ends one. Build 18 (E12, MORE-D32): what to do
            // first, then where the controller is
            return Overlays.held(Held.CHORDS, CueOrigin.Press(src), listOf(Say.keepHeld(modifier, look), controllerWhere()),
                chords.map { it.trigger.button.spoken(look) to chordLabel(it) } + extra + holdLine, footer = also)
        }

        override fun combosOffApp(): String? {
            val pkg = currentApp() ?: return null
            if (AppConfigStore.get(pkg).buttonsMode != ButtonsMode.OFF) return null
            return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        override fun showCombosOff(app: String, src: Int) {
            Overlays.held(Held.CHORDS, CueOrigin.Press(src), listOf("Wayfinder's button combos are off in $app",
                "Home and Back work as usual here. To change it, open Wayfinder, then Games, then $app."), emptyList())
        }

        // dev build 17 (critique K12, WP10): only [src]'s list — not another controller's, the Recents hint, the shift list
        // or a tip (it hid every table)
        override fun hideChordHint(src: Int) { Overlays.release(Held.CHORDS, src) }

        override fun currentApp(): String? = displayApps[controllerDisplay()]

        override fun shieldAnalog(on: Boolean) {
            if (on) analogShield.raise(controllerDisplay())
            else analogShield.lower()
        }

        override fun systemReleased() { handler.removeCallbacks(extAfterRelease); handler.postDelayed(extAfterRelease, 50) }

        override fun standsIn(src: Int, b: ThorButton): Boolean = ExtPadsCtl.standsIn(this@ForegroundAppService, src, b)
        override fun padAyn(src: Int, down: Boolean) = this@ForegroundAppService.padAyn(src, down)
    }
    /** dev build 17 (theme G): what a controller's AYN button's tap does, as the combo list says actions. */
    private fun aynJob(): String = if (AppSettings.aynButtonOurs) chordLabel(AppSettings.aynTap) else ThorAction.AYN_DRAWER.title
    private val analogShield by lazy { AnalogShield(this) }

    /** A combo's action in the held list. Build 18 (E6, MORE-21): its title as the app writes it ("Keyboard & mouse", "Open
     *  Chrome") — the list had lower-case copies ("keyboard & mouse"). */
    private fun chordLabel(b: Binding): String =
        if (b.action == ThorAction.OPEN) OpenTargets.label(this, b.arg) else chordLabel(b.action)
    private fun chordLabel(a: ThorAction): String = a.title.replace(" → ", " to ").replace("→", "to")

    /** Build 18 (E12, ps-F14): what a controller's own Back and AYN-job buttons do alone — the combos with no first button on
     *  Back (its tap doing Back says nothing), and the AYN button's tap (AYN's drawer while the button isn't Wayfinder's) and
     *  hold. [HeldWords.also] keeps the ones that controller has a button of its own for. */
    private fun ownButtons(): List<HeldWords.Own> =
        ControlsStore.active().filter { it.trigger.modifier == null && it.trigger.button == ThorButton.BACK && it.action != ThorAction.BACK }
            .map { HeldWords.Own(ThorButton.BACK, it.trigger.press, chordLabel(it)) } +
            listOfNotNull(HeldWords.Own(ThorButton.AYN, Press.TAP, aynJob()),
                AppSettings.aynHold?.takeIf { AppSettings.aynButtonOurs }?.let { HeldWords.Own(ThorButton.AYN, Press.HOLD, chordLabel(it)) })

    /** Where the controller is now, in words — build 18 (E12, V16): the strip's and the combos sheet's mode words
     *  ([Say.controllerMode]); the combos that move it are in the list itself. */
    private fun controllerWhere(d: Int = controllerDisplay()) =
        HeldWords.where(screenName(d), AppSettings.focusLockEnabled, AppSettings.focusSticky, AppSettings.focusLockTop)

    /** Build 18 (E12): the held list's words — pure (OverlayRulesTest). */
    internal object HeldWords {
        /** A press of a controller's own Back / AYN-job button with no modifier, and what it does. */
        class Own(val button: ThorButton, val press: Press, val what: String)

        /** Which ones a short line keeps first: Back twice (Recent apps), Back held, the AYN-job tap, then the rest. */
        private val RANK = listOf(ThorButton.BACK to Press.DOUBLE_TAP, ThorButton.BACK to Press.HOLD, ThorButton.AYN to Press.TAP,
            ThorButton.BACK to Press.TRIPLE_TAP, ThorButton.AYN to Press.HOLD, ThorButton.BACK to Press.TAP)

        /** ps-F14: "touchpad click ×2: Recent apps · hold: Move / swap apps · Mute: Quick panel" — at most [max], each button
         *  named once, in [look]'s names (A15). A button the controller has none of its own for (null name: Home + Select stands
         *  in, and the list's stand-in lines say it) is left out; null = nothing to add. */
        fun also(own: List<Own>, look: PadLook, max: Int = 3): String? {
            fun name(b: ThorButton) = when (b) { ThorButton.BACK -> look.back; ThorButton.AYN -> look.ayn; else -> null }
            val shown = own.filter { name(it.button) != null }
                .sortedBy { o -> RANK.indexOf(o.button to o.press).let { if (it < 0) RANK.size else it } }.take(max)
                .sortedWith(compareBy<Own>({ if (it.button == ThorButton.BACK) 0 else 1 }, { it.press.ordinal }))
            if (shown.isEmpty()) return null
            var last: ThorButton? = null
            return shown.joinToString(" · ") { o ->
                val p = when (o.press) { Press.TAP -> ""; Press.DOUBLE_TAP -> "×2"; Press.TRIPLE_TAP -> "×3"; Press.HOLD -> "hold" }
                val head = if (o.button != last) listOfNotNull(name(o.button), p.ifEmpty { null }).joinToString(" ") else p
                last = o.button
                "$head: ${o.what}"
            }
        }

        /** V16: "Controller on the top screen — follows touch" ([Say.controllerMode], the strip's words); a lock names its
         *  screen once: "Controller always on the bottom screen". */
        fun where(screen: String, lock: Boolean, sticky: Boolean, top: Boolean): String {
            val mode = Say.controllerMode(lock, sticky, top)
            return if (lock && !sticky) "Controller $mode" else "Controller on the $screen — $mode"
        }
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

    /** Returns where the controller belongs after the swap (b18g, #99: a queued swap starts from there — [runSwap]); null
     *  when nothing moved. b18g: each way out that moves nothing leaves a W line — release keeps W, and W lines carry no
     *  app name (those stay DEBUG-gated, proguard-release.pro). */
    private fun performSwapOrSend(ctlHint: Int? = null): Int? {
        swapWatchGen++
        refreshDisplays()
        scanAllDisplayApps() // fresh scan from all displays before acting
        val ids = availableDisplayIds
        if (ids.size < 2) {
            Log.w(TAG, "Only ${ids.size} display(s) — nothing to do")
            return null
        }

        val d0 = ids[0]
        val d1 = ids[1]
        // Validate tracked apps are actually launchable (not uninstalled/closed ghosts)
        var app0 = displayApps[d0]
        var app1 = displayApps[d1]
        if (app0 != null && packageManager.getLaunchIntentForPackage(app0) == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Tracked app $app0 on display $d0 has no launch intent — dropping")
            else Log.w(TAG, "swap: the app tracked on display $d0 has no launch intent — dropped")
            displayApps.remove(d0)
            app0 = null
        }
        if (app1 != null && packageManager.getLaunchIntentForPackage(app1) == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Tracked app $app1 on display $d1 has no launch intent — dropping")
            else Log.w(TAG, "swap: the app tracked on display $d1 has no launch intent — dropped")
            displayApps.remove(d1)
            app1 = null
        }

        Log.d(TAG, "State: display $d0→$app0, display $d1→$app1")
        // 1.4: the screen the controller is on now — its app may be the one that moves. b18g (#99): a swap queued
        // behind another starts where that one left the controller ([ctlHint]): its follow is still on its way to the main
        // thread, and the focus model isn't updated during a move — read now, the controller "went with" the wrong app.
        val ctlDisplay = ctlHint ?: controllerDisplay()

        // Duplicate from MULTIPLE_TASK: same app on both displays — clean up
        if (app0 != null && app0 == app1) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Duplicate $app0 on both displays — force-stop + relaunch on d$d1")
            else Log.w(TAG, "swap: the same app on both displays — force-stop + relaunch on d$d1, no swap")
            launchOnDisplay(app0, d1, aggressive = true)
            Thread.sleep(1500)
            scanAllDisplayApps()
            Log.d(TAG, "After cleanup: d$d0→${displayApps[d0]}, d$d1→${displayApps[d1]}")
            return null
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
                return null
            }
        }

        // 1.3 (GitHub #29): another app's second screen (NeoStation's, a dual-screen game's) covers the
        // target screen — Android keeps it above every app there: the moved app would be hidden under it
        // (the screen looks frozen). Say so instead. (An app's OWN second screen is its business.)
        for (move in moves) {
            val owner = presentationOwner(move.toDisplay)?.takeIf { it.isNotEmpty() && it != move.pkg } ?: continue
            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(owner, 0)).toString() }.getOrDefault(owner)
            Log.w(TAG, "swap: display ${move.toDisplay} is another app's second screen — not moving under it")
            Overlays.pill("The ${screenName(move.toDisplay)} shows $name's second screen — an app can't go under it. Turn it off in $name to swap.",
                CueOrigin.Screen(move.fromDisplay))
            return null
        }

        // 1.4: the controller goes with its app ("Follows touch", "Stays where sent"; not "Always top / bottom")
        // …per app too (Games page / Game controls → More): its own choice, else the Controller page's switch
        val followMove = if (!(AppSettings.focusLockEnabled && !AppSettings.focusSticky))
            moves.firstOrNull { it.fromDisplay == ctlDisplay }?.takeIf { AppSettings.followsMove(it.pkg) } else null
        var followed = false
        var ctlAfter = ctlDisplay           // b18g (#99): where the controller belongs after this swap (the return value)
        fun follow(move: Move) {
            if (followed) return
            followed = true
            ctlAfter = move.toDisplay
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(move.pkg, 0)).toString() }.getOrDefault("the game")
            handler.post {
                Log.i(TAG, "controller follows ${move.pkg} to ${move.toDisplay}")
                if (AppSettings.focusSticky) {
                    // "Stays where sent": as if sent there — the lock moves with it
                    AppSettings.setFocusLock(true, top = move.toDisplay == PRIMARY_DISPLAY)
                    applyFocusLock(announce = false)
                } else focusDisplay(move.toDisplay) { syncFocusModel() }
                Overlays.pill("The controller went with $label", CueOrigin.Screen(move.toDisplay), group = "focus")
            }
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
                launchOnDisplay(move.pkg, move.toDisplay, aggressive = false, fromDisplay = move.fromDisplay, reopenOk = true)
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
        // 1.3.2: an app reopened on the other screen (Firefox) cold-starts: give it time instead of "retrying" it
        if (moves.any { android.os.SystemClock.uptimeMillis() - (reopenedAt[it.pkg] ?: 0L) < 5000 }) {
            for (i in 0 until 10) {
                if (moves.all { taskDisplayOf(it.pkg) == it.toDisplay }) break
                Thread.sleep(300)
            }
        }
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

        followMove?.takeIf { it in clean }?.let { follow(it) }

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
                Log.w(TAG, "the app is running (task on $liveOn) — not force-stopping it; to the front of ${move.toDisplay}")
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
        followMove?.takeIf { it in clean }?.let { follow(it) }
        // not following: the controller where it belongs — the vacated screen's home took the focus on a send
        if (!followed) {
            val real = rootFocusedDisplay()
            // device pass (P2-2): the lock only where it holds the controller — a page holding it against the lock keeps it
            val want = if (lockHolds()) lockTarget() else ctlDisplay
            ctlAfter = want
            if (real != null && real != want) handler.post {
                Log.i(TAG, "after the move: input focus on $real, the controller belongs on $want — putting it back")
                if (lockHolds()) applyFocusLock(announce = false) else focusDisplay(want) { syncFocusModel() }
            }
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
                        // b18n (GitHub #102 b): Recents over the moved app is the user's, never a frontend covering it — launcher3
                        // hosts it and answers CATEGORY_HOME, so it is a "launcher" package. Read on that screen too: the window
                        // list can show Recents before its own event has reached the main thread (which ends this watch)
                        val launcher = now in launcherPackages
                        val recents = launcher && (recentsDisplay == move.toDisplay || recentsStillOpen(move.toDisplay))
                        if (!RecentsRules.coversMoved(now, move.pkg, launcher, recents) || taskDisplayOf(move.pkg) != move.toDisplay) continue
                        Log.w(TAG, "another app covered the moved one — bringing it back to the front")
                        PServiceBridge.frontTask(this, move.pkg, move.toDisplay)
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        // b18g (#99): one line per swap that ran, in release too — with the early ways out above, every swap asked for leaves one
        Log.w(TAG, "swap done: ${clean.size} of ${moves.size} moved, controller → $ctlAfter${if (followed) " (went with its app)" else ""}")
        return ctlAfter
    }

    /** Bumped by every swap, the user's Home and (b18n, #102 b) Recents opening: a running post-swap watch stops. */
    @Volatile private var swapWatchGen = 0

    private fun launchOnDisplay(pkg: String, targetDisplayId: Int, aggressive: Boolean, fromDisplay: Int? = null,
                                reopenOk: Boolean = false) {
        val shizukuReady = ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()
        // 1.3.2: apps that break after a live move (Firefox) are reopened on the other screen instead — only for
        // the user's own move of a running app, with a real force-stop (never in retries, routing, Open, pairs)
        val reopen = !aggressive && reopenOk && reopensOnMove(pkg) && PServiceBridge.isAvailable() &&
            taskDisplayOf(pkg)?.let { it != targetDisplayId } == true
        if (reopen) reopenedAt[pkg] = android.os.SystemClock.uptimeMillis()
        @Suppress("NAME_SHADOWING") val aggressive = aggressive || reopen

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

    /** 1.3.1 — a second-screen home (SECONDARY_HOME): the default launcher's own if it has one,
     *  else any; "pkg/Activity" as `am start -n` wants it. */
    private fun secondaryHome(): String? = runCatching {
        val i = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.SECONDARY_HOME")
        val all = packageManager.queryIntentActivities(i, 0).mapNotNull { it.activityInfo }
        val def = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        (all.firstOrNull { it.packageName == def } ?: all.firstOrNull())?.let { "${it.packageName}/${it.name}" }
    }.getOrNull()

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
    /** 1.4 (Reddit): a quick finger swipe across the middle of the controller's screen ([dx], [dy] = direction). */
    fun swipe(dx: Int, dy: Int) = handler.post {
        val d = controllerDisplay()
        // b18g (GitHub #49): the whole screen from ScreenSize (the configuration's read centred it at x 620 on the top)
        val size = ScreenSize.real(this, d) ?: return@post
        val w = size.x.toFloat(); val h = size.y.toFloat()
        // from 25 % before the centre to 25 % after it, in the swipe's direction
        val path = android.graphics.Path().apply {
            moveTo(w / 2 - dx * w * 0.25f, h / 2 - dy * h * 0.25f); lineTo(w / 2 + dx * w * 0.25f, h / 2 + dy * h * 0.25f)
        }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 180)).setDisplayId(d).build()
        injectGesture(g, null, null)
    }

    /** 1.4 (GitHub #51): AYN's own drawer — the broadcast AYN's framework sends for the AYN button's short press.
     *  A protected broadcast: sent through the system service. */
    fun openAynDrawer() = Thread {
        QuickPanelWindow.close()
        val out = PServiceBridge.exec("am broadcast -a action.tcc.button.key.event -p com.odin.dualscreen.assistant " +
            "--ez key_action_down false --ez key_isLongPress false")
        if (out == null) Overlays.pill("AYN's drawer can't open (system access missing)", CueOrigin.Press(ButtonNames.handSrc))
    }.apply { isDaemon = true }.start()

    fun closeCurrentApp(other: Boolean = false) {
        // 1.3: from the quick panel, the app it's about (the controller is on the panel's own screen)
        val here = if (QuickPanelWindow.isOpen) (panelReturnTo ?: PRIMARY_DISPLAY).also { QuickPanelWindow.close() }
            else focusedDisplayId()
        // 1.4 (Reddit): or the OTHER screen's app
        val d = if (!other) here else (if (here == PRIMARY_DISPLAY) secondDisplayId() ?: return else PRIMARY_DISPLAY)
        val pkg = (appOnDisplay(d) ?: displayApps[d])
            ?.takeIf { Shell.isPkg(it) && it != packageName && it !in ignoredPackages && it !in launcherPackages }
        if (pkg == null) { Overlays.pill("Nothing to close on this screen", CueOrigin.Screen(d), group = "close"); return }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        vibrateShort()
        Thread {
            val ok = PServiceBridge.isAvailable() && PServiceBridge.forceStop(pkg)
            handler.post {
                if (!ok) { Overlays.pill("Couldn't close $label", CueOrigin.Screen(d), group = "close"); return@post }
                displayApps.entries.removeAll { it.value == pkg }
                Overlays.pill("Closed $label", CueOrigin.Screen(d), group = "close")
            }
            if (ok) goHomeOnDisplay(d)
        }.apply { isDaemon = true }.start()
    }

    /** b18p review (GitHub #97): true when something was started on [displayId] (a start can take Android's focus there,
     *  [homeOn]); false: nothing to start, or it failed. The other callers ignore it. */
    private fun goHomeOnDisplay(displayId: Int): Boolean {
        // The bottom screen's home is a secondary launcher activity (e.g. a frontend's
        // external-display screen), which the HOME intent can't reach: "am start --display 4
        // -c HOME" brought the TOP screen's home to the front instead, covering the app just
        // sent up (2026-09-23). So re-open the launcher screen we saw there.
        if (displayId != PRIMARY_DISPLAY) {
            val comp = launcherScreens[displayId] ?: findLauncherScreen(displayId)?.also { launcherScreens[displayId] = it }
            if (comp != null && PServiceBridge.isAvailable()) {
                val out = Shell.component(comp)?.let { PServiceBridge.exec("am start --display $displayId -n $it -f 0x10020000") }
                Log.d(TAG, "Home on display $displayId → $comp: ${out?.trim()}")
                if (out != null && !out.contains("Error")) return true
            }
            if (comp == null) {
                val second = secondaryHome()
                if (second != null && PServiceBridge.isAvailable()) {
                    val out = PServiceBridge.exec("am start --display $displayId -n $second -f 0x10020000")
                    Log.d(TAG, "Home on display $displayId → second-screen home $second: ${out?.trim()}")
                    if (out != null && !out.contains("Error")) return true
                }
                Log.d(TAG, "No known home for display $displayId — left as is")
                Overlays.pill("No home screen found for this screen — open your launcher there once", CueOrigin.Screen(displayId))
                return false
            }
        }
        val ok = homeByBackend(displayId)
        if (ok) {
            Log.d(TAG, "Sent display $displayId to Home")
            return true
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
            return true
        } catch (e: Exception) {
            Log.w(TAG, "goHomeOnDisplay $displayId failed: ${e.message}")
            return false
        }
    }

    /** [displayId]'s HOME started on that display by a privileged backend (`am start --display`); false: none, or it failed.
     *  b18p (GitHub #97): also the top's Home in [homeOn] — named by its display, never Android's focused one. */
    private fun homeByBackend(displayId: Int): Boolean = when {
        PServiceBridge.isAvailable() -> PServiceBridge.goHomeOnDisplay(displayId)
        ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission() ->
            ShizukuHelper.goHomeOnDisplay(displayId)
        RootHelper.isAvailable() -> RootHelper.goHomeOnDisplay(displayId)
        else -> false
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

/** The input layer's profile for the app that has the controller (fx/wfmap.h tokens). */
/** [pkg] = an app, or a game profile's key (`<pkg>#<game>`, see [GameProfiles]). */
internal fun padProfileFor(pkg: String?): String {
    val cfg = pkg?.let { Profiles.get(it) } ?: return ""     // Wayfinder itself: raw (the test page)
    val face = cfg.face?.let { "L=${it.wire}" }.orEmpty()       // dev build 15 (WP2): positions only (PlayStation = x)
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
