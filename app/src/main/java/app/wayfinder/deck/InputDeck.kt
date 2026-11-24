package app.wayfinder.deck

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as UiModifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.MainActivity
import app.wayfinder.ScreenSize
import app.wayfinder.keyboard.ComposeOwner
import app.wayfinder.ui.DISABLED_ALPHA
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.ThorGlassTheme
import app.wayfinder.ui.glassLight
import app.wayfinder.ui.Radii
import app.wayfinder.ui.fillChip
import app.wayfinder.ui.fillSubtle
import app.wayfinder.ui.fillWell
import app.wayfinder.ui.keyFill
import app.wayfinder.ui.keyPressed
import app.wayfinder.ui.surfaceOverlay
import kotlin.math.abs
import kotlin.math.roundToInt

/** A sticky modifier: one tap = the next key only, two quick taps = locked. */
enum class Latch { OFF, ONCE, LOCKED }

/**
 * The input deck's state: which pad, the sticky modifiers, and the app it serves.
 * Keys go to the game's screen through [VirtualInput] (root injection).
 */
class DeckState(pkg: String?, appLabel: String?, initialPad: String, val guideOnly: Boolean = false, padKey: String? = pkg) {
    var padId by mutableStateOf(initialPad)
    /** Build 18 (E5, pF04): the app the keys go to — it follows the app on the game's screen ([InputDeck.retarget]). */
    var pkg by mutableStateOf(pkg)
        private set
    var appLabel by mutableStateOf(appLabel)
        private set
    fun retarget(pkg: String?, appLabel: String?) { this.pkg = pkg; this.appLabel = appLabel }
    /** b2 (review F3): the app a picked pad is remembered for ([choosePad]): [pkg], or the launcher in front where [pkg] is
     *  null (DeckHoldRules.front: a front-end set as the home app keeps its own pad). */
    var padKey: String? = padKey
    /** b2 (review F1): a Wayfinder window is in front on the game's screen (Game controls, the Guide page): the deck can't
     *  take the controller (DeckHoldRules.ownInFront), its switch greyed. Set by the open and the follow. */
    var ownInFront by mutableStateOf(false)
    private val latches = mutableStateMapOf<Modifier, Latch>()
    private val latchTime = HashMap<Modifier, Long>()

    fun latch(m: Modifier): Latch = latches[m] ?: Latch.OFF
    /** 1.5-beta2 (Q2): [m] is down for the game now: latched, or Shift held by L2 ([shiftHeld]). Drawn on. */
    fun isDown(m: Modifier): Boolean = latch(m) != Latch.OFF || (m == Modifier.SHIFT && shiftHeld)
    private fun meta(): Int = Modifier.values().filter { isDown(it) }.fold(0) { acc, m -> acc or m.metaOn }

    fun modifierTap(m: Modifier) {
        val now = System.currentTimeMillis()
        when (latch(m)) {
            Latch.OFF -> { latches[m] = Latch.ONCE; VirtualInput.key(m.keyCode, true, meta()) }
            Latch.ONCE -> if (now - (latchTime[m] ?: 0) < 450) latches[m] = Latch.LOCKED
                else { latches[m] = Latch.OFF; VirtualInput.key(m.keyCode, false, meta()) }
            Latch.LOCKED -> { latches[m] = Latch.OFF; VirtualInput.key(m.keyCode, false, meta()) }
        }
        latchTime[m] = now
    }

    /** Modifiers baked into a custom combo key, pressed for real around it (PC games watch them). */
    private fun comboMods(k: DeckKey) = Modifier.values().filter { k.meta and it.metaOn != 0 && !isDown(it) }

    /** Keys held down right now (a finger on them): released if the deck closes mid-press —
     *  the character kept walking forever (review 2026-09-25). */
    private val held = LinkedHashSet<DeckKey>()

    fun keyDown(k: DeckKey) {
        held += k
        val m = meta() or k.meta
        comboMods(k).forEach { VirtualInput.key(it.keyCode, true, m) }
        VirtualInput.key(k.code, true, m)
    }

    fun keyUp(k: DeckKey) {
        if (!held.remove(k)) return          // already released (the deck closed meanwhile)
        val m = meta() or k.meta
        VirtualInput.key(k.code, false, m)
        comboMods(k).reversed().forEach { VirtualInput.key(it.keyCode, false, meta()) }
        // One-shot modifiers end with the key they modified. 1.5-beta2 (Q2): a Shift L2 still holds stays down (it comes up
        // with L2, [shiftLet])
        Modifier.values().filter { latch(it) == Latch.ONCE }.forEach { m ->
            latches[m] = Latch.OFF
            if (!(m == Modifier.SHIFT && shiftHeld)) VirtualInput.key(m.keyCode, false, meta())
        }
    }

    fun releaseAll() {
        held.toList().forEach { keyUp(it) }
        val l2Only = shiftHeld && latch(Modifier.SHIFT) == Latch.OFF          // Q2: down for L2 alone
        shiftHeld = false
        Modifier.values().filter { latch(it) != Latch.OFF }.forEach { m ->
            latches[m] = Latch.OFF; VirtualInput.key(m.keyCode, false, 0)
        }
        if (l2Only) VirtualInput.key(Modifier.SHIFT.keyCode, false, 0)
        VirtualInput.releaseMouse()
    }

    val shiftOn get() = isDown(Modifier.SHIFT)

    // ── 1.5-beta2 (Q2, GitHub D#127): L2 held = Shift held, while the deck holds the controller ([DeckHold]) ──
    /** L2 is down: Shift is down for the game until L2 comes up ([shiftLet]), the Shift key drawn on ([isDown]). */
    var shiftHeld by mutableStateOf(false)
        private set
    private var shiftAt = 0L
    /** A key pressed while L2 is down: its release is a hold's, however short. */
    private var shiftUsed = false

    /** L2 pressed at [at] (uptime ms): Shift goes down at once (a game that reads Shift held, to run, gets it); a latched
     *  Shift is down already. */
    fun shiftHold(at: Long) {
        if (shiftHeld) return
        shiftHeld = true; shiftAt = at; shiftUsed = false
        if (latch(Modifier.SHIFT) == Latch.OFF) VirtualInput.key(Modifier.SHIFT.keyCode, true, meta())
    }

    /** L2 let go at [at] (null: the deck's hold ended, a hold's release). A tap (Wayfinder Keyboard's rule,
     *  [app.wayfinder.keyboard.KeyboardRules.shiftTap]) is a tap of the Shift key: for the next key, twice quickly locked,
     *  again off. A hold lets go of the Shift it pressed ([DeckHoldRules.shiftUp]). */
    fun shiftLet(at: Long?) {
        if (!shiftHeld) return
        shiftHeld = false
        val tap = at != null && app.wayfinder.keyboard.KeyboardRules.shiftTap(at - shiftAt, shiftUsed)
        when (DeckHoldRules.shiftUp(latch(Modifier.SHIFT), tap)) {
            DeckHoldRules.ShiftUp.LATCH -> { latches[Modifier.SHIFT] = Latch.ONCE; latchTime[Modifier.SHIFT] = System.currentTimeMillis() }
            DeckHoldRules.ShiftUp.TAP -> modifierTap(Modifier.SHIFT)
            DeckHoldRules.ShiftUp.LET_GO -> VirtualInput.key(Modifier.SHIFT.keyCode, false, meta())
            DeckHoldRules.ShiftUp.KEEP -> {}
        }
    }

    /** b19 (H1a): the clipboard row is open, and the gyro's tab is on — state here, not in the drawing: the controller walks
     *  and changes them too ([DeckNav], [InputDeckOverlay.runCell]). */
    var clipOpen by mutableStateOf(false)
    var gyroOn by mutableStateOf(!app.wayfinder.GyroEngine.pausedByUser)

    /** A pad's tab (or the Guide's) picked — by a finger or the controller. */
    fun choosePad(id: String) {
        if (padId == PAD_TRACKPAD && id != PAD_TRACKPAD) VirtualInput.releaseMouse()
        padId = id; DeckSettings.choose(padKey, id)   // b2 (review F3): a launcher's own too
    }

    /** b19 (H1a): key [k] pressed — a finger on it or the controller's A, one path: a modifier latches (twice = locked), a
     *  snippet types, an action runs, a key goes down until [release] (movement keys work, like a finger held). */
    fun press(ctx: android.content.Context, k: DeckKey) {
        if (shiftHeld && k.modifier != Modifier.SHIFT) shiftUsed = true     // 1.5-beta2 (Q2): L2's release is a hold's then
        when {
            k.modifier != null -> modifierTap(k.modifier)
            k.text != null -> VirtualInput.type(k.text)
            k.action != DeckAction.NONE -> runAction(ctx, k.action)
            k.code != 0 -> keyDown(k)
        }
    }

    /** [k] let go ([press]): only a key that went down comes up. */
    fun release(k: DeckKey) { if (k.modifier == null && k.text == null && k.action == DeckAction.NONE && k.code != 0) keyUp(k) }
}

/** The pad drawn for [padId]: its keys (the PC keys full or simple), the trackpad, My pad (empty: no buttons yet). */
internal fun shownPad(padId: String): DeckPad {
    val pads = BUILT_IN_PADS + DeckSettings.customPad()
    return (pads.firstOrNull { it.id == padId } ?: pads.first()).let { if (it.id == PAD_PC && DeckSettings.simpleKeys) PC_SIMPLE else it }
}

/**
 * Build 18 (E5): Keyboard & mouse as the rest of the app sees it — whether it is up, and the app its keys go to. The Hub's
 * Back layer closes it when it serves Wayfinder itself (pF04: B left the app with the deck still up); the service follows
 * the app on its game's screen ([InputDeckOverlay.retarget]). Main thread (the service's handler).
 */
object InputDeck {
    private val shownState = mutableStateOf(false)
    val shown: androidx.compose.runtime.State<Boolean> get() = shownState
    /** The package the keys go to now — null: not known ("the other screen"). */
    var targetPkg by mutableStateOf<String?>(null)
        private set
    @Volatile internal var live: InputDeckOverlay? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    internal fun set(shown: Boolean, pkg: String?) { shownState.value = shown; targetPkg = pkg }
    /** Close it (any thread). */
    fun hide() { main.post { live?.hide() } }
}

/**
 * The input deck window on the screen the game is NOT on: an accessibility overlay
 * that never takes focus (the game keeps the controller and the keyboard focus), so
 * everything it sends lands in the game. Touch-only: the pad stays the game's.
 */
class InputDeckOverlay(private val service: AccessibilityService) {
    companion object {
        @Volatile var stickMouseByDeck = false
            private set
        /** 1.4 (GitHub #53): remembered on disk — a crash, an update or a reboot with it on left AYN's mouse on
         *  for good (the pointer stuck on a screen); the service undoes it when it starts ([undoLeftover]). */
        fun markStickMouse(ctx: android.content.Context, on: Boolean) {
            stickMouseByDeck = on
            runCatching { ctx.getSharedPreferences("thor_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("deck_stick_mouse", on).apply() }
        }
        fun undoLeftover(ctx: android.content.Context) {
            val p = ctx.getSharedPreferences("thor_settings", android.content.Context.MODE_PRIVATE)
            if (!p.getBoolean("deck_stick_mouse", false)) return
            p.edit().putBoolean("deck_stick_mouse", false).apply()
            Thread { app.wayfinder.PServiceBridge.exec("settings put system global_gamepad_to_mouse_mode 0") }.apply { isDaemon = true }.start()
        }
    }
    init { deckService = service }

    private var view: ComposeView? = null
    private var wm: WindowManager? = null
    private var owner: ComposeOwner? = null
    private var state: DeckState? = null
    /** b19 (H1a): what a cell runs with the controller ([runCell]): the deck's context and its Make button's job. b2 (switch):
     *  the Give button and its job are gone (the switch is [DeckHold.toggle]). */
    private var ctx: android.content.Context? = null
    private var openHub: () -> Unit = {}
    var displayId: Int? = null
        private set
    /** Build 18 (E5): the screen its keys go to, and whether it sits beside the game on that screen (the guide alone). */
    var gameDisplay: Int? = null
        private set
    var beside = false
        private set
    var guideOnly = false
        private set
    val isShowing get() = view != null
    /** b19 fix (H1a, review): it covers [screen] now ([DeckHoldRules.covers]). */
    fun covers(screen: Int?): Boolean = DeckHoldRules.covers(isShowing, beside, displayId, screen)

    /** Build 18 (E5, pF04): the app on its game's screen changed — "Sending to: <it>" (null: "the other screen"). b2 (review
     *  F3): [padKey], the app a pad picked now is remembered for ([DeckState.padKey]). */
    fun retarget(pkg: String?, appLabel: String?, padKey: String? = pkg) {
        val st = state ?: return
        st.padKey = padKey
        if (st.pkg == pkg) return
        st.retarget(pkg, appLabel)
        InputDeck.set(true, pkg)
    }

    /** b2 (review F1): a Wayfinder window is in front on its game's screen, or no longer ([DeckState.ownInFront]). */
    fun ownInFront(on: Boolean) { state?.ownInFront = on }

    /** [guideOnly]: just the Guide & notes (1.3, GitHub #12); [beside]: a panel on the game's own screen
     *  (single-screen devices, or the other screen covered by a dual-screen game). b2 (review): [padKey] the app its pad is
     *  remembered for ([DeckState.padKey]), [ownFront] a Wayfinder window in front there ([DeckState.ownInFront]). */
    fun show(displayId: Int, gameDisplay: Int, pkg: String?, appLabel: String?, dark: Boolean, onOpenHub: () -> Unit,
             guideOnly: Boolean = false, beside: Boolean = false, padKey: String? = pkg, ownFront: Boolean = false): Boolean {
        hide()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        DeckSettings.init(ctx)
        VirtualInput.targetDisplay = gameDisplay
        TouchPointer.hide()
        val st = DeckState(pkg, appLabel, if (guideOnly) PAD_GUIDE else DeckSettings.padFor(ctx, padKey), guideOnly, padKey)
            .also { it.ownInFront = ownFront }
        val owner = ComposeOwner()
        val v = ComposeView(ctx).apply {
            owner.attach(this)
            setContent {
                ThorGlassTheme(dark = dark) { app.wayfinder.ui.CappedFontScale {
                    // b2 (switch): the footer's switch turns the hold on and off ([DeckHold.toggle])
                    DeckPanel(st, UiModifier.fillMaxSize(), onClose = { hide() }, onOpenHub = onOpenHub,
                        onUse = { DeckHold.toggle(this@InputDeckOverlay) }, ownPkg = service.packageName)
                }}
            }
        }
        // b18g (GitHub #49): the screen's own width (currentWindowMetrics read the process configuration, any screen's)
        val screenW = ScreenSize.real(service, displayId)?.x ?: 0
        val lp = WindowManager.LayoutParams(
            if (beside && screenW > 0) (screenW * 0.45f).toInt() else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (!beside && Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = MainActivity.BLUR_RADIUS_PX
            }
            dimAmount = 0f
            if (beside) gravity = android.view.Gravity.END or android.view.Gravity.TOP   // beside the game, on the right
            title = "ThorInputDeck"
        }
        return try {
            wm.addView(v, lp)
            view = v; this.wm = wm; this.owner = owner; state = st; this.displayId = displayId
            this.ctx = ctx; openHub = onOpenHub
            this.gameDisplay = gameDisplay; this.beside = beside; this.guideOnly = guideOnly
            InputDeck.live = this; InputDeck.set(true, pkg)
            true
        } catch (e: Exception) {
            Log.w("ThorDeck", "deck on display $displayId failed: ${e.message}"); owner.destroy(); false
        }
    }

    fun hide() {
        DeckHold.closing(this)   // b19 (H1a): the controller back to the game first, then what the deck held lets go
        TouchPointer.hide()   // 1.3 (GitHub #36): the touch pointer goes with the deck
        state?.releaseAll()   // never leave a key or modifier held or a mouse behind
        // AYN's stick mouse, turned on from the deck: off again, or every game afterwards had
        // L3 / R3 turning the stick into a cursor and the D-pad into volume (review 2026-09-25)
        if (stickMouseByDeck) {
            markStickMouse(service, false)
            Thread { app.wayfinder.PServiceBridge.exec("settings put system global_gamepad_to_mouse_mode 0") }.apply { isDaemon = true }.start()
        }
        val v = view ?: return
        runCatching { wm?.removeViewImmediate(v) }
        owner?.destroy()
        view = null; wm = null; owner = null; state = null; displayId = null; gameDisplay = null; ctx = null
        if (InputDeck.live === this) InputDeck.set(false, null)
    }

    // ── b19 (H1a): the controller's side ([DeckHold]) — what it lights and runs is what the drawing shows and a tap does ──

    /** The open pad's id ([DeckState.padId]). */
    internal val padId: String get() = state?.padId ?: PAD_PC
    /** The app its keys go to, by name (null: not known). */
    internal val appName: String? get() = state?.appLabel
    /** Its keys go to Wayfinder itself (the Hub on the game's screen). */
    internal val servesWayfinder: Boolean get() = state?.pkg == service.packageName
    /** b2 (review F1): a Wayfinder window is in front on its game's screen ([DeckState.ownInFront]). */
    internal val ownInFront: Boolean get() = state?.ownInFront == true

    /** The cells drawn now, as rows ([DeckNav.rows]: the inputs [DeckPanel] reads). */
    internal fun navRows(): List<List<DeckNav.Cell>> {
        val st = state ?: return emptyList()
        val guide = st.padId == PAD_GUIDE
        val pad = shownPad(st.padId)
        val touch = DeckSettings.touchMode && deckService != null
        val clips = app.wayfinder.keyboard.ClipHistory
        return DeckNav.rows(DeckNav.Shown(
            pads = if (st.guideOnly) emptyList() else (BUILT_IN_PADS + DeckSettings.customPad()).map { it.id },
            gyro = !st.guideOnly && app.wayfinder.GyroEngine.configuredForCurrent != null,
            keys = pad.rows.takeIf { !guide && !pad.isTrackpad && it.isNotEmpty() },
            trackpad = if (guide || !pad.isTrackpad) null else when {
                touch && DeckSettings.directTouch -> DeckNav.Trackpad.DIRECT
                touch -> DeckNav.Trackpad.TOUCH
                else -> DeckNav.Trackpad.MOUSE
            },
            makeOwn = !guide && pad.id == PAD_CUSTOM && pad.rows.isEmpty(),
            clips = if (st.clipOpen && !st.guideOnly && clips.listening) clips.items.size else 0,
            pcKeys = st.padId == PAD_PC,
            footer = !st.guideOnly,
        ))
    }

    /** Cell [id] pressed ([down]) or let go with the controller's A — what a finger does there: a key held while A is held, a
     *  mouse button too; a tab, a chip or a step at the press. */
    internal fun runCell(id: String, down: Boolean) {
        val st = state ?: return
        val c = ctx ?: return
        DeckNav.keyOf(id)?.let { (r, col) ->
            val k = shownPad(st.padId).rows.getOrNull(r)?.getOrNull(col) ?: return
            if (down) st.press(c, k) else st.release(k)
            return
        }
        when (id) {
            DeckNav.TP_LEFT -> { VirtualInput.mouseButton(0, down); return }
            DeckNav.TP_MIDDLE -> { VirtualInput.mouseButton(2, down); return }
            DeckNav.TP_RIGHT -> { VirtualInput.mouseButton(1, down); return }
        }
        if (!down) return
        DeckNav.tabOf(id)?.let { st.choosePad(it); return }
        DeckNav.clipOf(id)?.let { i -> app.wayfinder.keyboard.ClipHistory.items.toList().getOrNull(i)?.let { VirtualInput.paste(c, it) }; return }
        when (id) {
            DeckNav.CLOSE -> hide()
            DeckNav.GYRO -> { st.gyroOn = !st.gyroOn; app.wayfinder.GyroEngine.setPausedByUser(!st.gyroOn) }
            DeckNav.MAKE -> openHub()
            DeckNav.SIMPLE -> DeckSettings.chooseSimpleKeys(!DeckSettings.simpleKeys)
            DeckNav.CLIPBOARD -> st.clipOpen = !st.clipOpen
            DeckNav.USE -> DeckHold.toggle(this)   // b2 (switch): A on it while held = off, the controller back to the game
            DeckNav.TP_MODE -> nextTouchMode()
            DeckNav.TP_POINTER -> DeckSettings.chooseDirectPointer(!DeckSettings.directPointer)
            DeckNav.TP_SLOWER -> stepSpeed(faster = false)
            DeckNav.TP_FASTER -> stepSpeed(faster = true)
        }
    }

    /** L1 / R1: the previous / next tab (the pads, then the Guide), round the strip. */
    internal fun stepTab(dir: Int) {
        val st = state ?: return
        if (st.guideOnly) return
        val ids = (BUILT_IN_PADS + DeckSettings.customPad()).map { it.id } + PAD_GUIDE
        val i = ids.indexOf(st.padId).coerceAtLeast(0)
        st.choosePad(ids[(i + dir).mod(ids.size)])
    }

    /** The key a job's button presses on the pad drawn ([DeckHoldRules.jobKey]); null = none there. */
    internal fun jobKey(job: DeckHoldRules.Job): DeckKey? {
        val st = state ?: return null
        if (st.padId == PAD_GUIDE) return null
        val rows = shownPad(st.padId).rows
        return DeckHoldRules.jobKey(rows, job)?.let { (r, c) -> rows[r][c] }
    }

    /** The key cell [id] stands for on the pad drawn now (null: another cell, or gone). */
    internal fun keyAt(id: String): DeckKey? {
        val st = state ?: return null
        return DeckNav.keyOf(id)?.let { (r, c) -> shownPad(st.padId).rows.getOrNull(r)?.getOrNull(c) }?.takeIf { it.label.isNotEmpty() }
    }

    /** A key pressed / let go by the controller ([DeckState.press]): A's, or a job's. */
    internal fun pressKey(k: DeckKey, down: Boolean) {
        val st = state ?: return
        val c = ctx ?: return
        if (down) st.press(c, k) else st.release(k)
    }

    /** 1.5-beta2 (Q2, GitHub D#127): L2 pressed ([down]) or let go at [at] (uptime ms) while the deck holds the controller:
     *  Shift held while it is ([DeckState.shiftHold], [DeckState.shiftLet]). [at] null = the hold ended (a hold's release). */
    internal fun shiftHold(down: Boolean, at: Long?) {
        val st = state ?: return
        if (down) st.shiftHold(at ?: android.os.SystemClock.uptimeMillis()) else st.shiftLet(at)
    }
}

/** The trackpad's mode tab: Mouse → Touch → Direct → Mouse (1.4, GitHub #65). */
private fun nextTouchMode() {
    when {
        !DeckSettings.touchMode -> { DeckSettings.chooseTouchMode(true); DeckSettings.chooseDirect(false) }
        !DeckSettings.directTouch -> DeckSettings.chooseDirect(true)
        else -> { DeckSettings.chooseTouchMode(false); DeckSettings.chooseDirect(false) }
    }
}

/** One step of Pointer speed ([faster] or slower); false at 10 / 1 (nothing changes). */
private fun stepSpeed(faster: Boolean): Boolean {
    val now = DeckSettings.pointerSpeed
    val next = PointerSpeed.step(now, faster)
    if (next == now) return false
    DeckSettings.choosePointerSpeed(next)
    return true
}

/** b19 (H1a): the controller's light on a cell ([DeckHold.lit]): Wayfinder Keyboard's lit key, in the focus ring's colour
 *  (it reads on a selected tab's accent too). Drawn state only: the deck's window never takes the focus.
 *  b19 fix (H1a, F1): the lit cell also comes into view ([inView]): the header's tabs scroll, and the light went onto tabs
 *  off the screen (Media, Video, My pad, Guide while the held header's words took their room). */
private fun UiModifier.litRim(lit: Boolean, color: Color, shape: Shape): UiModifier =
    inView(lit).then(if (lit) UiModifier.border(2.5.dp, color, shape) else UiModifier)

/** b19 fix (H1a, F1): the cell comes into view each time [on] turns true. In a strip that scrolls (the header's tabs, the
 *  clipboard row) the strip scrolls to it; anywhere else nothing moves. After a frame, so a cell is measured as drawn. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun UiModifier.inView(on: Boolean): UiModifier = composed {
    val bring = remember { BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(on) {
        if (on) { androidx.compose.runtime.withFrameNanos { }; runCatching { bring.bringIntoView() } }
    }
    UiModifier.bringIntoViewRequester(bring)
}

/** b19 fix (H1a, F1 review): [placed] hears the element's pixels along its row (its parent's content, scroll left out) each
 *  time it is placed; null: nothing. */
private fun UiModifier.placedAt(placed: ((IntRange) -> Unit)?): UiModifier = if (placed == null) this else onPlaced { c ->
    val x = c.positionInParent().x.roundToInt()
    placed(x until x + c.size.width)
}

/** The cell [id] is lit now. */
@Composable
private fun isLit(id: String): Boolean = DeckHold.held && DeckHold.lit == id

// ─────────────────────────────────────────────────────────────────────────────

private fun padIcon(id: String): ImageVector = when (id) {
    PAD_PC -> Icons.Rounded.Keyboard
    PAD_TRACKPAD -> Icons.Rounded.Mouse
    PAD_NUMPAD -> Icons.Rounded.Dialpad
    PAD_EMU -> Icons.Rounded.SportsEsports
    PAD_MEDIA -> Icons.Rounded.MusicNote
    PAD_VIDEO -> Icons.Rounded.Movie
    else -> Icons.Rounded.Star
}

@Composable
fun DeckPanel(st: DeckState, modifier: UiModifier, onClose: () -> Unit, onOpenHub: () -> Unit,
              onUse: () -> Unit = {}, ownPkg: String? = null) {
    val g = LocalGlass.current
    val pads = BUILT_IN_PADS + DeckSettings.customPad()
    // b19 (H1a): the controller's look (the Thor's, or one playing as it: what the hold drives) and whether it holds it
    val look = app.wayfinder.ButtonNames.lookOfSrc(0)
    val held = DeckHold.held
    Column(
        modifier.background(g.surfaceOverlay).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: pad tabs · focus the game · close.
        Row(Modifier_fillWidthHeight(52), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // b19 fix (H1a, F1): the strip shows the open pad's tab (R1 onto a tab off the screen left it there, its name cut:
            // "Me"), then the lit one when the light is on a tab of it: both when they fit, else the light ([DeckNav.stripScroll]).
            // F1 review: one scroll, not two requests (a newer one cancels the one before: R1 with the light on My pad left
            // the Guide it opened cut, "G"). Each time the pad changes (the tabs' widths change with it) and the strip's own
            // width does (the held header's longer words pushed the open tab out as a hold started). The light moving along
            // the strip brings its own tab ([litRim]). b2 (O1): and each time the light leaves the strip (for a key, the gyro,
            // close): R1 with the light on a tab can leave the open tab off the strip (the light wins), and the light going
            // down onto a key with no pad change left it there. The light coming onto the strip brings its own tab, so that
            // change alone runs nothing here (two scrolls at once: [inView])
            val strip = rememberScrollState()
            val spans = remember { HashMap<String, IntRange>() }   // each tab's pixels along the strip, as last placed
            var stripWidth by remember { mutableStateOf(0) }
            val onStrip = DeckHold.held && DeckHold.lit?.let { DeckNav.tabOf(it) } != null
            val scrolledFor = remember { arrayOf("") }   // the pad and the width the strip last scrolled for
            androidx.compose.runtime.LaunchedEffect(st.padId, stripWidth, onStrip) {
                val why = "${st.padId} $stripWidth"
                if (!DeckNav.stripRuns(onStrip, why, scrolledFor[0])) return@LaunchedEffect   // the light came onto the strip: its own bring
                scrolledFor[0] = why
                androidx.compose.runtime.withFrameNanos { }   // after a frame: the tab that opened is placed with its name
                val litTab = DeckHold.lit?.takeIf { DeckHold.held && DeckNav.tabOf(it) != null }
                val to = DeckNav.stripScroll(strip.value, stripWidth, strip.maxValue, spans[DeckNav.tab(st.padId)], litTab?.let { spans[it] })
                if (to != strip.value) runCatching { strip.animateScrollTo(to) }
            }
            Row(UiModifier.weight(1f).onSizeChanged { stripWidth = it.width }.horizontalScroll(strip),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!st.guideOnly) pads.forEach { p ->
                    // Only the open pad spells its name — all seven tabs fit the screen.
                    Tab(if (st.padId == p.id) p.name else null, padIcon(p.id), selected = st.padId == p.id, lit = isLit(DeckNav.tab(p.id)),
                        placed = { spans[DeckNav.tab(p.id)] = it }) {
                        st.choosePad(p.id)
                    }
                }
                // 1.2: the game's Guide & notes, right here (shows even over a dual-screen game)
                Tab(if (st.padId == PAD_GUIDE) "Guide" else null, Icons.Rounded.MenuBook, selected = st.padId == PAD_GUIDE,
                    lit = isLit(DeckNav.tab(PAD_GUIDE)), placed = { spans[DeckNav.tab(PAD_GUIDE)] = it }) { st.choosePad(PAD_GUIDE) }
            }
            // the game's gyro (Buttons for <app> → Gyro): off / on for this game, from here
            if (!st.guideOnly && app.wayfinder.GyroEngine.configuredForCurrent != null) {
                Tab(if (st.gyroOn) "Gyro on" else "Gyro off", Icons.Rounded.ScreenRotation, selected = st.gyroOn, lit = isLit(DeckNav.GYRO)) {
                    st.gyroOn = !st.gyroOn; app.wayfinder.GyroEngine.setPausedByUser(!st.gyroOn)
                }
            }
            // build 18 (E5, pF03 minimum): the header says which combo closes it (in the hand's names). b19 (H1a): while it
            // holds the controller, first which button gives it back (in the names of the controller it holds)
            val combo = app.wayfinder.Say.combo(app.wayfinder.ThorAction.KEYBOARD)?.takeIf { !st.guideOnly }
            if (held) Text(app.wayfinder.DeckWords.heldHeader(app.wayfinder.Say.menu(app.wayfinder.ThorButton.B, look), combo),
                color = g.textSecondary, fontSize = 13.sp, maxLines = 1)
            else combo?.let { Text("$it closes", color = g.textTertiary, fontSize = 13.sp, maxLines = 1) }
            Tab(null, Icons.Rounded.Close, selected = false, lit = isLit(DeckNav.CLOSE), onClick = onClose)
        }
        val pad = shownPad(st.padId)
        when {
            st.padId == PAD_GUIDE -> DeckGuidePane(st.pkg, st.appLabel, onClose, UiModifier.fillMaxWidth().weight(1f))
            pad.isTrackpad -> Trackpad(UiModifier.fillMaxWidth().weight(1f), service = deckService)
            pad.id == PAD_CUSTOM && pad.rows.isEmpty() -> Box(UiModifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your own buttons: text snippets and key combos", color = g.textSecondary, fontSize = 18.sp)
                    Box(UiModifier.height(56.dp)) { Tab("Make them in ${app.wayfinder.Nav.place(app.wayfinder.HubPage.KEYBOARD, inWayfinder = true)}", Icons.Rounded.Star, selected = true, lit = isLit(DeckNav.MAKE), onClick = onOpenHub) }
                }
            }
            else -> PadGrid(st, pad, UiModifier.fillMaxWidth().weight(1f), badges = if (held) badgesOf(pad, look) else emptyMap())
        }
        // 1.3.2 (GitHub #45): the last copied texts — tap one to type it into the game's screen
        val deckCtx = androidx.compose.ui.platform.LocalContext.current
        if (st.clipOpen && !st.guideOnly) ClipRow { VirtualInput.paste(deckCtx, it) }
        // Footer: where the keys go, the deck's own chips, and the controller here. b2 (switch): one line again (as 1.5-beta1),
        // the keys get b19's second line back. "Give the controller to the game" is gone: the deck opens on the screen the
        // controller isn't on, so the game has it already, and while the deck has it the switch (or B) gives it back.
        // "Sending to" takes what the chips leave, on at most two lines (a long name ends in "…"). b2 (review): the mockup's
        // layout: Sending to and the deck's chips from the left, the switch alone at the right end ("Sending to" takes only
        // what it needs: weight, not filled; the chips sat against the switch)
        if (!st.guideOnly) Row(Modifier_fillWidthHeight(40), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(UiModifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sending to: ${st.appLabel ?: "the other screen"}", color = g.textTertiary, fontSize = 13.sp, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = UiModifier.weight(1f, fill = false))
                // 1.3 (GitHub #26): the PC keys, full or simple (bigger keys) — here, not in the tab strip (it pushed
                // the last pads off the edge)
                if (st.padId == PAD_PC) Tab(if (DeckSettings.simpleKeys) "All keys" else "Simpler keys", Icons.Rounded.Keyboard, selected = false,
                        lit = isLit(DeckNav.SIMPLE)) {   // what a tap does
                    DeckSettings.chooseSimpleKeys(!DeckSettings.simpleKeys)
                }
                Tab(null, Icons.Rounded.ContentPaste, selected = st.clipOpen, lit = isLit(DeckNav.CLIPBOARD)) { st.clipOpen = !st.clipOpen }
            }
            // b19 (H1a): the controller here (the deck holds it: the game keeps its screen and its focus); dimmed with its
            // reason when it can't (the tap says why). b2 (switch): a switch, on while the deck has the controller (selected):
            // a tap or A on it gives it back, as B. Off each time the deck opens. Drawn in both states, so nothing moves.
            // b2 (review F1): greyed too while a Wayfinder window is in front on the game's screen (Game controls)
            val why = if (held) null else DeckHoldRules.canHold(app.wayfinder.PadLayerCtl.active, 0, st.pkg != null && st.pkg == ownPkg || st.ownInFront, st.guideOnly)
            Tab(app.wayfinder.DeckWords.USE, Icons.Rounded.Gamepad, selected = held, lit = isLit(DeckNav.USE), dim = why != null,
                switch = held, onClick = onUse)
        }
    }
}

@Suppress("FunctionName")
private fun Modifier_fillWidthHeight(h: Int) = UiModifier.fillMaxWidth().height(h.dp)

/** 1.3.2 (GitHub #45): the deck's clipboard row — kept by Wayfinder Keyboard (the only one allowed to read it). */
@Composable
private fun ClipRow(onPick: (String) -> Unit) {
    val g = LocalGlass.current
    val clips = app.wayfinder.keyboard.ClipHistory
    LaunchedEffectRead()
    Row(Modifier_fillWidthHeight(48).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !clips.listening -> Text("The clipboard list needs Wayfinder Keyboard as your keyboard (${app.wayfinder.Nav.place(app.wayfinder.HubPage.KEYBOARD, inWayfinder = true)}).",
                color = g.textTertiary, fontSize = 14.sp)
            clips.items.isEmpty() -> Text("Nothing copied yet — copy some text and it shows here.", color = g.textTertiary, fontSize = 14.sp)
            else -> clips.items.toList().forEachIndexed { i, t ->
                Box(UiModifier.fillMaxHeight().clip(RoundedCornerShape(Radii.Chip))
                    .background(g.fillChip)
                    .litRim(isLit(DeckNav.clip(i)), g.focusRing, RoundedCornerShape(Radii.Chip))
                    .pointerInput(t) { detectTapGestures { onPick(t) } }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(clips.preview(t), color = g.textPrimary, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A copy made while the deck is open shows up when the row opens. */
@Composable
private fun LaunchedEffectRead() = androidx.compose.runtime.LaunchedEffect(Unit) { app.wayfinder.keyboard.ClipHistory.read() }

/** A tab or a chip. b19 (H1a): [lit] = the controller's light on it ([DeckHold]); [dim] = it can't act now (its tap says why).
 *  b19 fix (H1a, F1 review): [placed] hears its pixels along its row each time it is placed (the header strip's tabs: where
 *  the strip scrolls to, [DeckNav.stripScroll]).
 *  b2 (switch): [switch] non-null = an on/off switch drawn at its right end (the footer's "Use the controller here"): a
 *  screen reader hears a switch with its words and its state, and a double tap does what a tap does. */
@Composable
private fun Tab(label: String?, icon: ImageVector, selected: Boolean, lit: Boolean = false, dim: Boolean = false,
                placed: ((IntRange) -> Unit)? = null, switch: Boolean? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        UiModifier.placedAt(placed).fillMaxHeight().clip(RoundedCornerShape(Radii.Control))
            .background(
                if (selected) Brush.linearGradient(listOf(g.accent, g.accent2))
                else Brush.linearGradient(listOf(g.fillSubtle, g.fillSubtle))
            )
            .litRim(lit, g.focusRing, RoundedCornerShape(Radii.Control))
            // 1.5-beta2 (b2d pass, FAIL 1): the greyed look in a layer that is always there, only its alpha changes. alpha()
            // adds no layer at 1f, so a tab first drawn greyed lost its layer when it turned normal, and on the Thor its pill
            // (the background above) was gone until a hold on/off. The pill stays full and its content greys, as before
            .graphicsLayer { alpha = if (dim) DISABLED_ALPHA else 1f; clip = true }
            .then(if (switch == null) UiModifier else UiModifier.clearAndSetSemantics {
                role = Role.Switch; toggleableState = ToggleableState(switch); label?.let { contentDescription = it }
                this.onClick(null) { onClick(); true }   // b2 (review): no label, so "double-tap to toggle" (on, it said "double-tap to Use the controller here")
            })
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(start = if (label == null) 12.dp else 14.dp, end = if (switch != null) 6.dp else if (label == null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, tint = if (selected) g.onAccent else g.textSecondary, modifier = UiModifier.size(22.dp))
        if (label != null) Text(label, color = if (selected) g.onAccent else g.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        // on: the selected look's own colours (a light track, an accent knob); off: GlassSwitchRow's. Not a control of its
        // own: the tab takes the tap (ComponentRulesTest: no Switch without the glass colours). Drawn at 3/4 of Material's
        // 52 x 32 dp (centred in 40 x 24: the name keeps its room beside it)
        if (switch != null) Switch(checked = switch, onCheckedChange = null, modifier = UiModifier.size(40.dp, 24.dp).scale(0.75f),
            colors = SwitchDefaults.colors(checkedThumbColor = g.accent, checkedTrackColor = g.onAccent,
                checkedBorderColor = Color.Transparent, uncheckedThumbColor = g.textTertiary,
                uncheckedTrackColor = g.textTertiary.copy(alpha = 0.18f), uncheckedBorderColor = g.textTertiary.copy(alpha = 0.35f)))
    }
}

/** b19 (H1a): while the deck holds the controller, the keys its X, Y, Start and Select press ([DeckHoldRules.jobKey]) carry
 *  that button's badge, by (row, column): the button doing that job on [look]'s controller. */
private fun badgesOf(pad: DeckPad, look: app.wayfinder.PadLook): Map<Pair<Int, Int>, app.wayfinder.ThorButton> =
    DeckHoldRules.BADGES.mapNotNull { (job, b) -> DeckHoldRules.jobKey(pad.rows, job)?.let { it to app.wayfinder.ButtonNames.menuPos(b, look) } }.toMap()

/** 1.5-beta2 (as Wayfinder Keyboard): the 6 dp between the deck's keys. */
private const val DECK_GAP = 6f

@Composable
private fun PadGrid(st: DeckState, pad: DeckPad, modifier: UiModifier, badges: Map<Pair<Int, Int>, app.wayfinder.ThorButton> = emptyMap()) {
    // 1.5-beta2 (as Wayfinder Keyboard): no dead strip between keys. Each key's touch area takes half of the gap
    // around it (app.wayfinder.keyboard.KeyGeometry.touch) and its face is drawn inset by that half, so the faces are the
    // size and place they were (rows spaced 6 dp, keys 6 dp apart, shared by weight)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val half = (DECK_GAP / 2).dp
        val width = maxWidth.value
        val heights = app.wayfinder.keyboard.KeyGeometry.touch(List(pad.rows.size) { 1f }, maxHeight.value, DECK_GAP, 0f, 0f)
        Column(UiModifier.fillMaxSize()) {
            pad.rows.forEachIndexed { r, row ->
                val widths = app.wayfinder.keyboard.KeyGeometry.touch(row.map { it.weight }, width, DECK_GAP, 0f, 0f)
                Row(UiModifier.fillMaxWidth().weight(heights[r].coerceAtLeast(0.01f))) {
                    row.forEachIndexed { c, k ->
                        val face = androidx.compose.foundation.layout.PaddingValues(start = if (c == 0) 0.dp else half,
                            top = if (r == 0) 0.dp else half, end = if (c == row.lastIndex) 0.dp else half,
                            bottom = if (r == pad.rows.lastIndex) 0.dp else half)
                        DeckCell(st, k, UiModifier.weight(widths[c].coerceAtLeast(0.01f)).fillMaxHeight(), DeckNav.key(r, c), badges[r to c], face)
                    }
                }
            }
        }
    }
}

/** A key, or an empty cell (blank label) that just holds its place in the grid. [id]: its cell ([DeckNav]); [badge]: the
 *  button that presses it while the deck holds the controller. */
@Composable
private fun DeckCell(st: DeckState, k: DeckKey, modifier: UiModifier, id: String, badge: app.wayfinder.ThorButton?,
                     face: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
    if (k.label.isEmpty()) Box(modifier) else DeckKeyCap(st, k, modifier, isLit(id), badge, face)
}

@Composable
private fun DeckKeyCap(st: DeckState, k: DeckKey, modifier: UiModifier, lit: Boolean = false, badge: app.wayfinder.ThorButton? = null,
                       face: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
    val g = LocalGlass.current
    val view = LocalView.current
    val ctx = view.context
    var pressed by remember { mutableStateOf(false) }
    val latched = k.modifier?.let { st.isDown(it) } == true                  // 1.5-beta2 (Q2): Shift held by L2 too
    val shape = RoundedCornerShape(Radii.Chip)
    // 1.5-beta2: the touch box is the whole cell (with its share of the gaps); the face inside it, inset by [face]
    Box(modifier.pointerInput(k) {
        // Held like a real key: down on touch, up on release (movement keys work). b19 (H1a): the controller's A
        // takes the same path ([DeckState.press])
        awaitEachGesture {
            awaitFirstDown()
            pressed = true
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            st.press(ctx, k)
            try { waitForUpOrCancellation() }
            finally {
                // also when the gesture is cancelled (window removed, tab switched)
                st.release(k)
                pressed = false
            }
        }
    }) {
    Box(
        UiModifier.fillMaxSize().padding(face).clip(shape)
            .background(
                when {
                    latched -> Brush.linearGradient(listOf(g.accent, g.accent2))
                    pressed -> g.keyPressed
                    else -> g.keyFill(functional = false)
                }, shape,
            )
            .glassLight(shape, g.dark)
            .border(1.dp, Brush.linearGradient(0f to g.rimTop.copy(alpha = 0.7f), 0.5f to Color.Transparent, 1f to g.rimBottom), shape)
            .litRim(lit, g.focusRing, shape),
        contentAlignment = Alignment.Center,
    ) {
        badge?.let { Box(UiModifier.align(Alignment.TopEnd).padding(3.dp)) { app.wayfinder.ui.ButtonGlyph(it, size = 16.dp, look = app.wayfinder.ButtonNames.lookOfSrc(0)) } }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val label = if (st.shiftOn && k.shifted != null) k.shifted else if (st.shiftOn && k.label.length == 1) k.label.uppercase() else k.label
            Text(label, color = if (latched) g.onAccent else g.textPrimary, fontSize = if (label.length <= 2) 22.sp else 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            k.caption?.let { Text(it, color = if (latched) g.onAccent else g.textTertiary, fontSize = 11.sp, maxLines = 1) }
        }
    }
    }
}

private fun runAction(ctx: android.content.Context, a: DeckAction) {
    when (a) {
        DeckAction.VOLUME_DOWN -> VirtualInput.volume(ctx, android.media.AudioManager.ADJUST_LOWER)
        DeckAction.VOLUME_UP -> VirtualInput.volume(ctx, android.media.AudioManager.ADJUST_RAISE)
        DeckAction.MUTE -> VirtualInput.volume(ctx, android.media.AudioManager.ADJUST_TOGGLE_MUTE)
        DeckAction.BRIGHT_TOP_DOWN -> VirtualInput.brightness(ctx, true, -1)
        DeckAction.BRIGHT_TOP_UP -> VirtualInput.brightness(ctx, true, 1)
        DeckAction.BRIGHT_BOTTOM_DOWN -> VirtualInput.brightness(ctx, false, -1)
        DeckAction.BRIGHT_BOTTOM_UP -> VirtualInput.brightness(ctx, false, 1)
        DeckAction.NONE -> {}
    }
}

/** The deck's service (the touch pointer injects gestures through it). */
@Volatile private var deckService: AccessibilityService? = null

/** 1.3 (GitHub #36): the trackpad in Touch mode — one finger moves the pointer, a quick tap taps there,
 *  hold (0.45 s) then move drags a finger, two fingers swipe from the pointer. */
private suspend fun touchGestures(scope: androidx.compose.ui.input.pointer.PointerInputScope, view: android.view.View) = scope.awaitEachGesture {
    val first = awaitFirstDown(requireUnconsumed = false)
    val start = first.uptimeMillis
    // b18h (GitHub #49, #99): Pointer speed, read as the gesture starts (no restart of the pad); 5 = ×1, as before
    val factor = PointerSpeed.factor(DeckSettings.pointerSpeed)
    var maxFingers = 1; var travel = 0f; var holding = false
    var sx = 0f; var sy = 0f
    while (true) {
        val ev = awaitPointerEvent()
        val down = ev.changes.filter { it.pressed }
        if (down.isEmpty()) break
        maxFingers = maxOf(maxFingers, down.size)
        if (!holding && maxFingers == 1 && travel < 12f && ev.changes.first().uptimeMillis - start > 450) {
            holding = true; TouchPointer.press(); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        if (ev.type == PointerEventType.Move) {
            val c = down.first()
            val dx = c.position.x - c.previousPosition.x; val dy = c.position.y - c.previousPosition.y
            travel += abs(dx) + abs(dy)
            if (down.size >= 2) { sx += dx * 3f; sy += dy * 3f }
            else {
                // the tap / hold tests above stay on the finger's own travel; two-finger swipes aren't on the speed
                val gain = (1.3f + ((abs(dx) + abs(dy)) / 25f).coerceAtMost(2.5f)) * factor
                TouchPointer.move(dx * gain, dy * gain)
            }
        }
        ev.changes.forEach { it.consume() }
    }
    when {
        holding -> TouchPointer.release()
        maxFingers >= 2 && (abs(sx) + abs(sy)) > 30f -> TouchPointer.swipe(sx, sy)
        travel < 14f && maxFingers == 1 -> TouchPointer.tap()
    }
}

/** 1.4 (GitHub #65): Direct — the pad is the game's screen: a finger down touches the same place there, moves drag it,
 *  lifting lets go — so one-finger swipes, taps and drags work as on the game's own screen. */
private suspend fun directGestures(scope: androidx.compose.ui.input.pointer.PointerInputScope) = scope.awaitEachGesture {
    val w = scope.size.width.toFloat().coerceAtLeast(1f); val h = scope.size.height.toFloat().coerceAtLeast(1f)
    val first = awaitFirstDown(requireUnconsumed = false)
    TouchPointer.jumpTo(first.position.x / w, first.position.y / h)
    TouchPointer.press()
    while (true) {
        val ev = awaitPointerEvent()
        val c = ev.changes.firstOrNull { it.id == first.id } ?: break
        if (!c.pressed) break
        if (ev.type == PointerEventType.Move) TouchPointer.jumpTo(c.position.x / w, c.position.y / h)
        ev.changes.forEach { it.consume() }
    }
    TouchPointer.release()
}

/**
 * Laptop-style trackpad driving a real (virtual) mouse, so the game gets the system
 * cursor and true relative motion. One finger moves; tap = click; two-finger tap =
 * right click; two-finger drag = scroll; press-and-hold then move = drag.
 */
@Composable
private fun Trackpad(modifier: UiModifier, service: AccessibilityService?) {
    val g = LocalGlass.current
    val view = LocalView.current
    // 1.3 (GitHub #36): Touch = a pointer on the game's screen and finger touches there
    val touch = DeckSettings.touchMode && service != null
    androidx.compose.runtime.DisposableEffect(touch) {
        if (touch) TouchPointer.show(service!!, VirtualInput.targetDisplay) else TouchPointer.hide()
        onDispose { TouchPointer.hide() }
    }
    // build 18 (GitHub #65): Direct can go without the pointer; Touch (or leaving Direct) shows it again
    androidx.compose.runtime.LaunchedEffect(touch, DeckSettings.directTouch, DeckSettings.directPointer) {
        TouchPointer.setDotHidden(touch && DeckSettings.directTouch && !DeckSettings.directPointer)
    }
    // 1.4 (GitHub #65): Mouse → Touch → Direct
    val direct = touch && DeckSettings.directTouch
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(Radii.Action)
        // b18h (GitHub #49, #99): the pad, and Pointer speed beside it in Mouse and Touch — outside the pad (its
        // awaitFirstDown(requireUnconsumed = false) would also take a press on it and click in the game). Not in Direct:
        // its pad is the game's screen (1:1), and keeps its whole width
        Row(UiModifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                UiModifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(g.fillWell, shape)
                    .glassLight(shape, g.dark)
                    .border(1.dp, Brush.linearGradient(0f to g.rimTop, 0.5f to Color.Transparent, 1f to g.rimBottom), shape)
                    .pointerInput(touch, DeckSettings.directTouch) {
                        if (touch && DeckSettings.directTouch) { directGestures(this); return@pointerInput }
                        if (touch) { touchGestures(this, view); return@pointerInput }
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val start = first.uptimeMillis
                            // b18h: Pointer speed, read as the gesture starts (no restart of the pad); 5 = ×1, as before
                            val factor = PointerSpeed.factor(DeckSettings.pointerSpeed)
                            var maxFingers = 1
                            var travel = 0f
                            var dragging = false
                            var fx = 0f; var fy = 0f; var scrollAcc = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val down = ev.changes.filter { it.pressed }
                                if (down.isEmpty()) break
                                maxFingers = maxOf(maxFingers, down.size)
                                if (!dragging && maxFingers == 1 && travel < 12f && ev.changes.first().uptimeMillis - start > 450) {
                                    dragging = true; VirtualInput.mouseButton(0, true)
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                if (ev.type == PointerEventType.Move) {
                                    val c = down.first()
                                    val dx = c.position.x - c.previousPosition.x
                                    val dy = c.position.y - c.previousPosition.y
                                    travel += abs(dx) + abs(dy)
                                    if (down.size >= 2) {
                                        scrollAcc += dy
                                        val steps = (scrollAcc / 40f).toInt()
                                        if (steps != 0) { VirtualInput.scroll(-steps); scrollAcc -= steps * 40f }
                                    } else {
                                        // Gentle acceleration: slow = precise, fast = across the screen. The click and drag
                                        // tests stay on the finger's own travel; the two-finger scroll isn't on the speed
                                        val speed = abs(dx) + abs(dy)
                                        val gain = (1.3f + (speed / 25f).coerceAtMost(2.5f)) * factor
                                        fx += dx * gain; fy += dy * gain
                                        val mx = fx.roundToInt(); val my = fy.roundToInt()
                                        if (mx != 0 || my != 0) { VirtualInput.mouseMove(mx, my); fx -= mx; fy -= my }
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                            }
                            val quick = travel < 14f
                            when {
                                dragging -> VirtualInput.mouseButton(0, false)
                                quick && maxFingers >= 2 -> { VirtualInput.mouseButton(1, true); VirtualInput.mouseButton(1, false) }
                                quick -> { VirtualInput.mouseButton(0, true); VirtualInput.mouseButton(0, false) }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (touch && DeckSettings.directTouch) "Direct · this pad is the game's screen — touch, drag and swipe with one finger"
                    else if (touch) "Touch · move = the pointer · tap = a finger tap there · hold + move = drag · 2 fingers = swipe"
                    else "Trackpad · tap = click · 2 fingers: tap = right click, drag = scroll · hold + move = drag",
                    color = g.textTertiary, fontSize = 14.sp)
            }
            if (!direct) SpeedStepper(UiModifier.width(64.dp).fillMaxHeight())   // b19 (H1a): its steps lit as DeckNav.TP_FASTER / TP_SLOWER
        }
        Row(UiModifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 1.3 (GitHub #36): a mouse, or finger touches (for games that ignore a mouse)
            Tab(if (direct) "Direct" else if (touch) "Touch" else "Mouse", if (direct) Icons.Rounded.Swipe else if (touch) Icons.Rounded.TouchApp else Icons.Rounded.Mouse, selected = touch,
                lit = isLit(DeckNav.TP_MODE)) { nextTouchMode() }
            if (!touch) {
                MouseButton("Left", 0, UiModifier.weight(1f).fillMaxHeight(), lit = isLit(DeckNav.TP_LEFT))
                MouseButton("Middle", 2, UiModifier.weight(0.6f).fillMaxHeight(), lit = isLit(DeckNav.TP_MIDDLE))
                MouseButton("Right", 1, UiModifier.weight(1f).fillMaxHeight(), lit = isLit(DeckNav.TP_RIGHT))
                AynMouseLink(UiModifier.weight(1.2f).fillMaxHeight())
            } else {
                // build 18 (GitHub #65): Direct with or without the pointer on the game's screen (the touches are the same)
                if (direct) Tab(if (DeckSettings.directPointer) "Pointer" else "No pointer",
                    if (DeckSettings.directPointer) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    selected = DeckSettings.directPointer, lit = isLit(DeckNav.TP_POINTER)) { DeckSettings.chooseDirectPointer(!DeckSettings.directPointer) }
                Text(if (direct) "Where you touch here = where the finger lands there — for swipes (menus, pages, Shorts)"
                    else "Touches go where the pointer is, on the game's screen — for games a mouse doesn't work in",
                    color = g.textTertiary, fontSize = 13.sp, modifier = UiModifier.weight(1f).align(Alignment.CenterVertically))
            }
        }
    }
}

/**
 * b18h (GitHub #49, #99): Pointer speed on the deck — a slim column beside the pad: faster (+) on top, the number and its
 * name, slower (−) below; the same setting as Keyboard's Pointer speed card ([DeckSettings.pointerSpeed], 1–10, 5 = as
 * before). Touch-only, like the deck.
 */
@Composable
private fun SpeedStepper(modifier: UiModifier) {
    val g = LocalGlass.current
    val s = DeckSettings.pointerSpeed
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SpeedStep(faster = true, can = s < PointerSpeed.MAX, UiModifier.fillMaxWidth().weight(1f), lit = isLit(DeckNav.TP_FASTER))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$s", color = g.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(app.wayfinder.Say.POINTER_SPEED, color = g.textTertiary, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
        }
        SpeedStep(faster = false, can = s > PointerSpeed.MIN, UiModifier.fillMaxWidth().weight(1f), lit = isLit(DeckNav.TP_SLOWER))
    }
}

/** One step of [SpeedStepper]: [faster] (+) or slower (−); [can] false at 10 / 1 (dimmed, a tap does nothing). The tap
 *  reads the speed it changes (never a value captured when drawn). */
@Composable
private fun SpeedStep(faster: Boolean, can: Boolean, modifier: UiModifier, lit: Boolean = false) {
    val g = LocalGlass.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.Control)
    Box(
        modifier.clip(shape)
            .then(if (pressed) UiModifier.background(g.keyPressed, shape) else UiModifier.background(g.fillChip, shape))
            .glassLight(shape, g.dark)
            .litRim(lit, g.focusRing, shape)
            .clearAndSetSemantics { contentDescription = if (faster) app.wayfinder.Say.FASTER else app.wayfinder.Say.SLOWER }
            .pointerInput(faster) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }) {
                    if (stepSpeed(faster)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (faster) "+" else "−", color = g.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            modifier = UiModifier.alpha(if (can) 1f else DISABLED_ALPHA))
    }
}

/**
 * AYN's own stick-as-mouse mode. AYN's switch only writes `global_gamepad_to_mouse_mode`
 * (checked in its code), so we write exactly that — through the root bridge — and read
 * the real value back. Once on, clicking a stick (AYN's L3/R3 keys) turns it into the
 * cursor, and AYN also turns the D-pad into volume/enter until you click the stick again.
 */
@Composable
private fun AynMouseLink(modifier: UiModifier) {
    val g = LocalGlass.current
    val ctx = LocalView.current.context
    fun read() = android.provider.Settings.System.getInt(ctx.contentResolver, "global_gamepad_to_mouse_mode", 0) == 1
    var on by remember { mutableStateOf(read()) }
    val keys = remember {
        listOfNotNull(
            android.provider.Settings.System.getString(ctx.contentResolver, "first_gamepad_to_mouse_key"),
            android.provider.Settings.System.getString(ctx.contentResolver, "second_gamepad_to_mouse_key"),
        ).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "L3 / R3" }
    }
    val shape = RoundedCornerShape(Radii.Control)
    Box(
        modifier.clip(shape)
            .background(if (on) Brush.linearGradient(listOf(g.accent, g.accent2)) else Brush.linearGradient(listOf(g.fillChip, g.fillChip)), shape)
            .glassLight(shape, g.dark)
            .pointerInput(Unit) {
                detectTapGestures {
                    val want = !on
                    InputDeckOverlay.markStickMouse(ctx, want)
                    Thread {
                        app.wayfinder.PServiceBridge.exec("settings put system global_gamepad_to_mouse_mode ${if (want) 1 else 0}")
                        Thread.sleep(250)
                        val real = read()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { on = real }
                    }.apply { isDaemon = true }.start()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (on) "Stick mouse: on" else "Stick mouse: off", color = if (on) g.onAccent else g.textPrimary,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (on) "click $keys · again to exit" else "AYN mouse mode", color = if (on) g.onAccent else g.textTertiary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MouseButton(name: String, button: Int, modifier: UiModifier, lit: Boolean = false) {
    val g = LocalGlass.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.Control)
    Box(
        modifier.clip(shape)
            .then(if (pressed) UiModifier.background(g.keyPressed, shape) else UiModifier.background(g.fillChip, shape))
            .glassLight(shape, g.dark)
            .litRim(lit, g.focusRing, shape)
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(); pressed = true; VirtualInput.mouseButton(button, true)
                    waitForUpOrCancellation(); VirtualInput.mouseButton(button, false); pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(name, color = g.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

