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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as UiModifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
class DeckState(pkg: String?, appLabel: String?, initialPad: String, val guideOnly: Boolean = false) {
    var padId by mutableStateOf(initialPad)
    /** Build 18 (E5, pF04): the app the keys go to — it follows the app on the game's screen ([InputDeck.retarget]). */
    var pkg by mutableStateOf(pkg)
        private set
    var appLabel by mutableStateOf(appLabel)
        private set
    fun retarget(pkg: String?, appLabel: String?) { this.pkg = pkg; this.appLabel = appLabel }
    private val latches = mutableStateMapOf<Modifier, Latch>()
    private val latchTime = HashMap<Modifier, Long>()

    fun latch(m: Modifier): Latch = latches[m] ?: Latch.OFF
    private fun meta(): Int = Modifier.values().filter { latch(it) != Latch.OFF }.fold(0) { acc, m -> acc or m.metaOn }

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
    private fun comboMods(k: DeckKey) = Modifier.values().filter { k.meta and it.metaOn != 0 && latch(it) == Latch.OFF }

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
        // One-shot modifiers end with the key they modified.
        Modifier.values().filter { latch(it) == Latch.ONCE }.forEach { m ->
            latches[m] = Latch.OFF; VirtualInput.key(m.keyCode, false, meta())
        }
    }

    fun releaseAll() {
        held.toList().forEach { keyUp(it) }
        Modifier.values().filter { latch(it) != Latch.OFF }.forEach { m ->
            latches[m] = Latch.OFF; VirtualInput.key(m.keyCode, false, 0)
        }
        VirtualInput.releaseMouse()
    }

    val shiftOn get() = latch(Modifier.SHIFT) != Latch.OFF
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

    /** Build 18 (E5, pF04): the app on its game's screen changed — "Sending to: <it>" (null: "the other screen"). */
    fun retarget(pkg: String?, appLabel: String?) {
        val st = state ?: return
        if (st.pkg == pkg) return
        st.retarget(pkg, appLabel)
        InputDeck.set(true, pkg)
    }

    /** [guideOnly]: just the Guide & notes (1.3, GitHub #12); [beside]: a panel on the game's own screen
     *  (single-screen devices, or the other screen covered by a dual-screen game). */
    fun show(displayId: Int, gameDisplay: Int, pkg: String?, appLabel: String?, dark: Boolean, onOpenHub: () -> Unit, onFocusGame: () -> Unit,
             guideOnly: Boolean = false, beside: Boolean = false): Boolean {
        hide()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        DeckSettings.init(ctx)
        VirtualInput.targetDisplay = gameDisplay
        TouchPointer.hide()
        val st = DeckState(pkg, appLabel, if (guideOnly) PAD_GUIDE else DeckSettings.padFor(ctx, pkg), guideOnly)
        val owner = ComposeOwner()
        val v = ComposeView(ctx).apply {
            owner.attach(this)
            setContent {
                ThorGlassTheme(dark = dark) { app.wayfinder.ui.CappedFontScale {
                    DeckPanel(st, UiModifier.fillMaxSize(), onClose = { hide() }, onOpenHub = onOpenHub, onFocusGame = onFocusGame)
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
            this.gameDisplay = gameDisplay; this.beside = beside; this.guideOnly = guideOnly
            InputDeck.live = this; InputDeck.set(true, pkg)
            true
        } catch (e: Exception) {
            Log.w("ThorDeck", "deck on display $displayId failed: ${e.message}"); owner.destroy(); false
        }
    }

    fun hide() {
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
        view = null; wm = null; owner = null; state = null; displayId = null; gameDisplay = null
        if (InputDeck.live === this) InputDeck.set(false, null)
    }
}

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
fun DeckPanel(st: DeckState, modifier: UiModifier, onClose: () -> Unit, onOpenHub: () -> Unit, onFocusGame: () -> Unit) {
    val g = LocalGlass.current
    val pads = BUILT_IN_PADS + DeckSettings.customPad()
    Column(
        modifier.background(g.surfaceOverlay).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: pad tabs · focus the game · close.
        Row(Modifier_fillWidthHeight(52), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(UiModifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!st.guideOnly) pads.forEach { p ->
                    // Only the open pad spells its name — all seven tabs fit the screen.
                    Tab(if (st.padId == p.id) p.name else null, padIcon(p.id), selected = st.padId == p.id) {
                        if (st.padId == PAD_TRACKPAD && p.id != PAD_TRACKPAD) VirtualInput.releaseMouse()
                        st.padId = p.id; DeckSettings.choose(st.pkg, p.id)
                    }
                }
                // 1.2: the game's Guide & notes, right here (shows even over a dual-screen game)
                Tab(if (st.padId == PAD_GUIDE) "Guide" else null, Icons.Rounded.MenuBook, selected = st.padId == PAD_GUIDE) {
                    if (st.padId == PAD_TRACKPAD) VirtualInput.releaseMouse()
                    st.padId = PAD_GUIDE; DeckSettings.choose(st.pkg, PAD_GUIDE)
                }
            }
            // the game's gyro (Buttons for <app> → Gyro): off / on for this game, from here
            if (!st.guideOnly && app.wayfinder.GyroEngine.configuredForCurrent != null) {
                var gyroOn by remember { mutableStateOf(!app.wayfinder.GyroEngine.pausedByUser) }
                Tab(if (gyroOn) "Gyro on" else "Gyro off", Icons.Rounded.ScreenRotation, selected = gyroOn) {
                    gyroOn = !gyroOn; app.wayfinder.GyroEngine.setPausedByUser(!gyroOn)
                }
            }
            // build 18 (E5, pF03 minimum): it's touch-only — the header says which combo closes it (in the hand's names)
            app.wayfinder.Say.combo(app.wayfinder.ThorAction.KEYBOARD)?.takeIf { !st.guideOnly }?.let { combo ->
                Text("$combo closes", color = g.textTertiary, fontSize = 13.sp, maxLines = 1)
            }
            Tab(null, Icons.Rounded.Close, selected = false, onClick = onClose)
        }
        val pad = (pads.firstOrNull { it.id == st.padId } ?: pads.first()).let { if (it.id == PAD_PC && DeckSettings.simpleKeys) PC_SIMPLE else it }
        when {
            st.padId == PAD_GUIDE -> DeckGuidePane(st.pkg, st.appLabel, onClose, UiModifier.fillMaxWidth().weight(1f))
            pad.isTrackpad -> Trackpad(UiModifier.fillMaxWidth().weight(1f), service = deckService)
            pad.id == PAD_CUSTOM && pad.rows.isEmpty() -> Box(UiModifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your own buttons: text snippets and key combos", color = g.textSecondary, fontSize = 18.sp)
                    Box(UiModifier.height(56.dp)) { Tab("Make them in ${app.wayfinder.Nav.place(app.wayfinder.HubPage.KEYBOARD, inWayfinder = true)}", Icons.Rounded.Star, selected = true, onClick = onOpenHub) }
                }
            }
            else -> PadGrid(st, pad, UiModifier.fillMaxWidth().weight(1f))
        }
        // 1.3.2 (GitHub #45): the last copied texts — tap one to type it into the game's screen
        var clipOpen by remember { mutableStateOf(false) }
        val deckCtx = androidx.compose.ui.platform.LocalContext.current
        if (clipOpen && !st.guideOnly) ClipRow { VirtualInput.paste(deckCtx, it) }
        // Footer: where the keys go, and a way to hand the controller to that screen.
        if (!st.guideOnly) Row(Modifier_fillWidthHeight(40), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sending to: ${st.appLabel ?: "the other screen"}", color = g.textTertiary, fontSize = 13.sp,
                modifier = UiModifier.weight(1f))
            // 1.3 (GitHub #26): the PC keys, full or simple (bigger keys) — here, not in the tab strip (it pushed
            // the last pads off the edge)
            if (st.padId == PAD_PC) Tab(if (DeckSettings.simpleKeys) "All keys" else "Simpler keys", Icons.Rounded.Keyboard, selected = false) {   // what a tap does
                DeckSettings.chooseSimpleKeys(!DeckSettings.simpleKeys)
            }
            Tab(null, Icons.Rounded.ContentPaste, selected = clipOpen) { clipOpen = !clipOpen }
            Tab("Give the controller to the game", Icons.Rounded.CenterFocusStrong, selected = false, onClick = onFocusGame)
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
            else -> clips.items.toList().forEach { t ->
                Box(UiModifier.fillMaxHeight().clip(RoundedCornerShape(Radii.Chip))
                    .background(g.fillChip)
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

@Composable
private fun Tab(label: String?, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        UiModifier.fillMaxHeight().clip(RoundedCornerShape(Radii.Control))
            .background(
                if (selected) Brush.linearGradient(listOf(g.accent, g.accent2))
                else Brush.linearGradient(listOf(g.fillSubtle, g.fillSubtle))
            )
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = if (label == null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, tint = if (selected) g.onAccent else g.textSecondary, modifier = UiModifier.size(22.dp))
        if (label != null) Text(label, color = if (selected) g.onAccent else g.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PadGrid(st: DeckState, pad: DeckPad, modifier: UiModifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pad.rows.forEach { row ->
            Row(UiModifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k -> DeckCell(st, k, UiModifier.weight(k.weight).fillMaxHeight()) }
            }
        }
    }
}

/** A key, or an empty cell (blank label) that just holds its place in the grid. */
@Composable
private fun DeckCell(st: DeckState, k: DeckKey, modifier: UiModifier) {
    if (k.label.isEmpty()) Box(modifier) else DeckKeyCap(st, k, modifier)
}

@Composable
private fun DeckKeyCap(st: DeckState, k: DeckKey, modifier: UiModifier) {
    val g = LocalGlass.current
    val view = LocalView.current
    val ctx = view.context
    var pressed by remember { mutableStateOf(false) }
    val latched = k.modifier?.let { st.latch(it) != Latch.OFF } == true
    val shape = RoundedCornerShape(Radii.Chip)
    Box(
        modifier.clip(shape)
            .background(
                when {
                    latched -> Brush.linearGradient(listOf(g.accent, g.accent2))
                    pressed -> g.keyPressed
                    else -> g.keyFill(functional = false)
                }, shape,
            )
            .glassLight(shape, g.dark)
            .border(1.dp, Brush.linearGradient(0f to g.rimTop.copy(alpha = 0.7f), 0.5f to Color.Transparent, 1f to g.rimBottom), shape)
            .pointerInput(k) {
                // Held like a real key: down on touch, up on release (movement keys work).
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when {
                        k.modifier != null -> st.modifierTap(k.modifier)
                        k.text != null -> VirtualInput.type(k.text)
                        k.action != DeckAction.NONE -> runAction(ctx, k.action)
                        k.code != 0 -> st.keyDown(k)
                    }
                    try { waitForUpOrCancellation() }
                    finally {
                        // also when the gesture is cancelled (window removed, tab switched)
                        if (k.modifier == null && k.text == null && k.action == DeckAction.NONE && k.code != 0) st.keyUp(k)
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val label = if (st.shiftOn && k.shifted != null) k.shifted else if (st.shiftOn && k.label.length == 1) k.label.uppercase() else k.label
            Text(label, color = if (latched) g.onAccent else g.textPrimary, fontSize = if (label.length <= 2) 22.sp else 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            k.caption?.let { Text(it, color = if (latched) g.onAccent else g.textTertiary, fontSize = 11.sp, maxLines = 1) }
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
            if (!direct) SpeedStepper(UiModifier.width(64.dp).fillMaxHeight())
        }
        Row(UiModifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 1.3 (GitHub #36): a mouse, or finger touches (for games that ignore a mouse)
            Tab(if (direct) "Direct" else if (touch) "Touch" else "Mouse", if (direct) Icons.Rounded.Swipe else if (touch) Icons.Rounded.TouchApp else Icons.Rounded.Mouse, selected = touch) {
                when {
                    !DeckSettings.touchMode -> { DeckSettings.chooseTouchMode(true); DeckSettings.chooseDirect(false) }
                    !DeckSettings.directTouch -> DeckSettings.chooseDirect(true)
                    else -> { DeckSettings.chooseTouchMode(false); DeckSettings.chooseDirect(false) }
                }
            }
            if (!touch) {
                MouseButton("Left", 0, UiModifier.weight(1f).fillMaxHeight())
                MouseButton("Middle", 2, UiModifier.weight(0.6f).fillMaxHeight())
                MouseButton("Right", 1, UiModifier.weight(1f).fillMaxHeight())
                AynMouseLink(UiModifier.weight(1.2f).fillMaxHeight())
            } else {
                // build 18 (GitHub #65): Direct with or without the pointer on the game's screen (the touches are the same)
                if (direct) Tab(if (DeckSettings.directPointer) "Pointer" else "No pointer",
                    if (DeckSettings.directPointer) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    selected = DeckSettings.directPointer) { DeckSettings.chooseDirectPointer(!DeckSettings.directPointer) }
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
        SpeedStep(faster = true, can = s < PointerSpeed.MAX, UiModifier.fillMaxWidth().weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$s", color = g.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(app.wayfinder.Say.POINTER_SPEED, color = g.textTertiary, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
        }
        SpeedStep(faster = false, can = s > PointerSpeed.MIN, UiModifier.fillMaxWidth().weight(1f))
    }
}

/** One step of [SpeedStepper]: [faster] (+) or slower (−); [can] false at 10 / 1 (dimmed, a tap does nothing). The tap
 *  reads the speed it changes (never a value captured when drawn). */
@Composable
private fun SpeedStep(faster: Boolean, can: Boolean, modifier: UiModifier) {
    val g = LocalGlass.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.Control)
    Box(
        modifier.clip(shape)
            .then(if (pressed) UiModifier.background(g.keyPressed, shape) else UiModifier.background(g.fillChip, shape))
            .glassLight(shape, g.dark)
            .clearAndSetSemantics { contentDescription = if (faster) app.wayfinder.Say.FASTER else app.wayfinder.Say.SLOWER }
            .pointerInput(faster) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }) {
                    val now = DeckSettings.pointerSpeed
                    val next = PointerSpeed.step(now, faster)
                    if (next != now) {
                        DeckSettings.choosePointerSpeed(next)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
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
private fun MouseButton(name: String, button: Int, modifier: UiModifier) {
    val g = LocalGlass.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.Control)
    Box(
        modifier.clip(shape)
            .then(if (pressed) UiModifier.background(g.keyPressed, shape) else UiModifier.background(g.fillChip, shape))
            .glassLight(shape, g.dark)
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(); pressed = true; VirtualInput.mouseButton(button, true)
                    waitForUpOrCancellation(); VirtualInput.mouseButton(button, false); pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(name, color = g.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

