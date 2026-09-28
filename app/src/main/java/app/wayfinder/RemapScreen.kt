package app.wayfinder

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.wayfinder.ui.AuroraSpan
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.glassSurface
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Input layer, phase 2b — "Buttons for <app>".
 * ONE thing to navigate: the Thor's controller, centred. The D-pad moves between controls (or
 * touch one); A opens that control's popup: what it becomes — Controller (it listens for the
 * button you press), Keyboard, Mouse, Actions — and what it does when held (normal, turbo,
 * toggle). L1 / R1 switch the popup's tabs, Y resets the control, B closes; outside the popup
 * X switches the face buttons. Everything applies only while the app has the controller.
 * Critic round 2 applied (one focus style, tabs look like tabs, "when held" at the bottom,
 * an inspector in the drawing's empty screen, hints hidden while listening).
 */

/** Selectable controls on the map, in the design's units (geometry of gen_remap_mockup.py).
 *  Tag: where "what it becomes" is drawn — [tagX] is the tag's start (align 1) or end (−1). */
private enum class MapCtl(val src: ThorButton?, val x: Float, val y: Float, val r: Float,
                          val tagX: Float, val tagY: Float, val align: Int) {
    L2(ThorButton.L2, 141f, -130f, 60f, 256f, -130f, 1), L1(ThorButton.L1, 141f, -69f, 60f, 256f, -69f, 1),
    R2(ThorButton.R2, 1339f, -130f, 60f, 1224f, -130f, -1), R1(ThorButton.R1, 1339f, -69f, 60f, 1224f, -69f, -1),
    SELECT(ThorButton.SELECT, 285f, 80f, 34f, 322f, 80f, 1), START(ThorButton.START, 1195f, 80f, 34f, 1158f, 80f, -1),
    L3(ThorButton.L3, 178f, 240f, 118f, 312f, 240f, 1),
    X(ThorButton.X, 1302f, 156f, 44f, 1466f, 150f, 1), Y(ThorButton.Y, 1218f, 240f, 44f, 1160f, 240f, -1),
    A(ThorButton.A, 1386f, 240f, 44f, 1466f, 240f, 1), B(ThorButton.B, 1302f, 324f, 44f, 1466f, 330f, 1),
    DPAD(null, 178f, 522f, 112f, 312f, 522f, 1), R3(ThorButton.R3, 1302f, 522f, 118f, 1466f, 522f, 1),
    /** The gyro: a wide pill in the drawing's screen (its state is written inside it). */
    GYRO(null, 740f, 600f, 60f, 740f, 600f, 1);

    val title get() = src?.let { if (it == ThorButton.L3) "Left stick (L3)" else if (it == ThorButton.R3) "Right stick (R3)" else it.label }
        ?: if (this == GYRO) "Gyro" else "D-pad"
}

private enum class Popup { BIND, DPAD, GYRO, CHANGES, PRESETS, CHORDS, APPS, PERF, MORE }
private enum class Tab(val label: String) { CONTROLLER("Controller"), KEYBOARD("Keyboard"), MOUSE("Mouse"), ACTIONS("Actions"), MACRO("Macro") }

/** Short words for tags and chips: ⌨ = a keyboard key, 🖱 = the mouse, ★ = a Wayfinder action. */
internal fun RemapTarget.short(): String = when (this) {
    is RemapTarget.Button -> b.spoken
    RemapTarget.None -> "Nothing"
    is RemapTarget.Key -> "⌨ " + label()
    is RemapTarget.Keys -> "⌨ " + label()
    is RemapTarget.Buttons -> label()
    is RemapTarget.Macro -> "Macro (${steps.size})" + if (armed) "" else " · off"
    is RemapTarget.Mouse -> "🖱 " + label()
    is RemapTarget.Action -> if (a == ThorAction.BACK) "Android Back" else "★ " + when (a) {
        ThorAction.SWAP_OR_SEND -> "Move/swap"; ThorAction.CLEAR_BACKGROUND -> "Close apps"; ThorAction.CLOSE_APP -> "Close this app"; ThorAction.RECENTS -> "Recents"
        ThorAction.SCREENSHOT -> "Screenshot"; ThorAction.TOGGLE_SECOND_SCREEN -> "Bottom screen"; ThorAction.TOGGLE_KEEP_AWAKE -> "Stay awake"
        ThorAction.FOCUS_SWITCH_UP -> "Controller ↑"; ThorAction.FOCUS_SWITCH_DOWN -> "Controller ↓"; ThorAction.FOCUS_LOCK_TOGGLE -> "Lock controller"
        ThorAction.KEYBOARD -> "Kbd & mouse"; ThorAction.QUICK_MENU -> "Quick panel"; ThorAction.BRIGHTER -> "Brighter"
        ThorAction.DIMMER -> "Dimmer"; ThorAction.FPS_COUNTER -> "FPS"; else -> a.title
    }
}

@Composable
fun RemapScreen(myDisplayId: Int, pkg: String, onBack: () -> Unit, inGame: Boolean = false,
                onSwitchApp: (String) -> Unit = {}, onAllApps: () -> Unit = {}) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val v = AppConfigStore.version.intValue
    // pkg = an app, or a game profile (`<pkg>#<game>`, GameProfiles): same page, its own controls
    val appPkg = GameProfiles.pkgOf(pkg)
    val game = remember(v, pkg) { GameProfiles.get(pkg) }
    var sharing by remember { mutableStateOf(false) }
    if (sharing) ShareChoice(game?.title ?: appLabelOf(ctx, GameProfiles.pkgOf(pkg)), onSave = {
        sharing = false
        val f = ProfileShare.export(ctx, pkg)
        ForegroundAppService.pill(if (f != null && ProfileShare.save(ctx, f.first, f.second)) "Saved in Download / Wayfinder: ${f.first}"
            else "The file couldn't be saved", myDisplayId, 3500)
    }, onSend = {
        sharing = false
        val f = ProfileShare.export(ctx, pkg)
        if (f == null || !ProfileShare.send(ctx, f.first, f.second, "Send these controls")) ForegroundAppService.pill("Couldn't open the share menu", myDisplayId, 3000)
    }) { sharing = false }
    val cfg = remember(v, pkg) { Profiles.get(pkg) }
    val remap = cfg.remap ?: PadRemap()
    val app by produceState<AppEntry?>(null, appPkg) {
        value = withContext(Dispatchers.IO) { runCatching {
            val pm = ctx.packageManager; val ai = pm.getApplicationInfo(appPkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(),
                runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull())
        }.getOrNull() }
    }
    val appLabel = app?.label ?: appPkg
    val label = game?.title ?: appLabel
    // A near-solid wash inside the cards: the wallpaper showing through made pills look focused.
    val wash = if (g.dark) Color(0xFF1B1E26).copy(alpha = .62f) else Color.White.copy(alpha = .5f)
    fun save(r: PadRemap) = Profiles.update(pkg) { it.copy(remap = r.takeIf { !r.isEmpty }) }

    var sel by remember { mutableStateOf(MapCtl.A) }
    // 1.3: the D-pad direction being edited (its page opens the same editor as a button)
    var dirSel by remember { mutableStateOf<ThorButton?>(null) }
    var popup by remember { mutableStateOf<Popup?>(null) }
    var tab by remember { mutableStateOf(Tab.CONTROLLER) }
    var kbPage by remember { mutableStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var mapFocused by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // the B press that closed a popup (its down-time): its release is eaten too. A time, not a flag: that
    // release can reach no element (the popup's focus went with it) — a flag stayed set and ate the next B
    var bHeldDown by remember { mutableStateOf(-1L) }
    // phase 3: which output a long / double press button's tabs edit (0 = its press, 1 = the 2nd
    // action); macro recording; a new chord's two buttons
    var slot by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var chordListen by remember { mutableStateOf(false) }
    var chordPair by remember { mutableStateOf(listOf<ThorButton>()) }
    val recDown = remember { HashMap<ThorButton, Long>() }
    var recLastUp by remember { mutableStateOf(0L) }
    val mapFocus = remember { FocusRequester() }
    val popupFirst = remember { FocusRequester() }
    val open = popup != null
    LaunchedEffect(Unit) { delay(300); runCatching { mapFocus.requestFocus() } }
    LaunchedEffect(popup, tab, kbPage) { delay(120); runCatching { if (popup != null) popupFirst.requestFocus() else mapFocus.requestFocus() } }
    LaunchedEffect(live) { if (live != null) { delay(2500); live = null } }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(3000); confirmReset = false } }
    LaunchedEffect(popup) {
        if (popup != Popup.BIND) dirSel = null
        if (popup == null) { listening = false; recording = false; chordListen = false; chordPair = emptyList() }
    }

    // Face buttons: two choices; picking the one all apps use = follow all apps (no override).
    val globalXbox = remember { runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "temp_abxy_layout_mode", 1) == 0 }.getOrDefault(false) }
    val global = if (globalXbox) FaceLayout.XBOX else FaceLayout.NINTENDO
    val effective = cfg.face ?: global
    fun setFace(f: FaceLayout) = Profiles.update(pkg) { it.copy(face = if (f == global) null else f) }
    fun cycleFace() = setFace(if (effective == FaceLayout.NINTENDO) FaceLayout.XBOX else FaceLayout.NINTENDO)
    fun setTarget(src: ThorButton, t: RemapTarget?) =
        save(remap.copy(buttons = if (t == null || t == RemapTarget.Button(src)) remap.buttons - src else remap.buttons + (src to t)))
    /** The stored profile NOW (capture callbacks outlive a recomposition). */
    fun fresh() = Profiles.get(pkg).remap ?: PadRemap()
    /** What the popup's tabs pick: the control's press, or its long / double press output. */
    fun bind(src: ThorButton, t: RemapTarget?) {
        val r = fresh()
        if (slot == 2 && r.shift != null && src != r.shift) save(r.copy(shifted = if (t == null) r.shifted - src else r.shifted + (src to t)))
        else if (slot == 1 && r.fire[src]?.hasAlt == true) save(r.copy(alt = if (t == null) r.alt - src else r.alt + (src to t)))
        else save(r.copy(buttons = if (t == null || t == RemapTarget.Button(src)) r.buttons - src else r.buttons + (src to t)))
    }
    fun resetSel() {
        if (sel == MapCtl.GYRO) save(remap.copy(gyro = GyroSettings()))
        (dirSel ?: sel.src)?.let { s -> save(remap.copy(buttons = remap.buttons - s, fire = remap.fire - s, alt = remap.alt - s, shifted = remap.shifted - s)) }
        slot = 0
    }
    fun openSel() { dirSel = null; popup = when (sel) { MapCtl.DPAD -> Popup.DPAD; MapCtl.GYRO -> Popup.GYRO; else -> Popup.BIND }; tab = Tab.CONTROLLER; slot = 0 }

    // "Find a control by pressing it" (map) and "Listen for a button" (popup): the next press
    // is captured (ButtonEngine swallows it, its release too). Home cancels listening.
    DisposableEffect(picking, listening, recording, chordListen) {
        if (!picking && !listening && !recording && !chordListen) return@DisposableEffect onDispose { }
        ForegroundAppService.startCapture { b, down ->
            if (recording) {                                     // a macro: presses + timing, appended
                val src = dirSel ?: sel.src ?: return@startCapture
                if (b == ThorButton.HOME) { if (down) recording = false; return@startCapture }
                if (b.isFlick || b == ThorButton.BACK || b !in PadRemap.OUTPUTS) return@startCapture
                val now = android.os.SystemClock.uptimeMillis()
                val r = fresh()
                val cur = (if (slot == 2 && r.shift != null) r.shifted[src] else if (slot == 1 && r.fire[src]?.hasAlt == true) r.alt[src] else r.buttons[src]) as? RemapTarget.Macro
                val steps = cur?.steps.orEmpty()
                if (down) {
                    recDown[b] = now
                    // the pause before this press belongs to the step before it
                    if (steps.isNotEmpty() && recLastUp > 0)
                        bind(src, RemapTarget.Macro(steps.dropLast(1) + steps.last().copy(gap = (now - recLastUp).toInt().coerceIn(0, 5000)), cur!!.repeat, cur.armed))
                } else {
                    val t0 = recDown.remove(b) ?: return@startCapture
                    val n = steps + MacroStep(RemapTarget.Button(b), (now - t0).toInt().coerceIn(10, 5000), 60)
                    bind(src, RemapTarget.Macro(n.take(MacroStep.MAX), cur?.repeat ?: false, cur?.armed ?: true))
                    recLastUp = now
                    if (n.size >= MacroStep.MAX) recording = false
                }
                return@startCapture
            }
            if (!down) return@startCapture
            if (chordListen) {                                   // a new chord's two buttons
                if (b == ThorButton.HOME) { chordListen = false; chordPair = emptyList() }
                else if (b in PadRemap.BUTTONS && b !in chordPair) { chordPair = chordPair + b; if (chordPair.size == 2) chordListen = false }
                return@startCapture
            }
            if (picking) {
                val c = MapCtl.values().firstOrNull { it.src == b } ?: if (b.isDpad) MapCtl.DPAD else null
                if (c != null) { sel = c; picking = false }
                // 1.3: a D-pad direction opens that direction's editor straight away
                if (b.isDpad) { dirSel = b; tab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }
            } else if (listening && sel == MapCtl.GYRO) {        // the gyro's on / off button
                if (b == ThorButton.HOME) listening = false
                else if (b in GyroSettings.BUTTONS) { save(remap.copy(gyro = remap.gyro.copy(button = b))); listening = false }
            } else if (listening) {
                val src = dirSel ?: sel.src
                when {
                    b == ThorButton.HOME -> listening = false
                    src == null || b.isFlick -> {}
                    b == ThorButton.BACK -> { bind(src, RemapTarget.Action(ThorAction.BACK)); listening = false }
                    else -> { bind(src, RemapTarget.Button(b)); listening = false }
                }
            }
        }
        onDispose { ForegroundAppService.stopCapture() }
    }
    LaunchedEffect(picking) { if (picking) { delay(6000); picking = false } }
    LaunchedEffect(listening) { if (listening) { delay(8000); listening = false } }
    LaunchedEffect(recording) { if (recording) { recLastUp = 0L; recDown.clear(); delay(15000); recording = false } }
    LaunchedEffect(chordListen) { if (chordListen) { chordPair = emptyList(); delay(8000); chordListen = false } }

    /** Everything behind the popup is out of the controller's reach while it's open. */
    val behind = Modifier.focusProperties { canFocus = !open }
    // What the drawing's empty screen says about the selected control.
    val inspector: Pair<String, String> = sel.src?.let { s ->
        val t = remap.buttons[s]; val f = remap.fire[s]
        val job = when (s) { ThorButton.L3 -> remap.jobL; ThorButton.R3 -> remap.jobR; else -> null }?.takeIf { !it.isDefault }
        (if (job != null) "${if (s == ThorButton.L3) "Left" else "Right"} stick  →  ${job.summary}"
            else if (t == null) "${s.label} — as usual" else "${s.label}  →  ${t.short()}") to
            ((f?.let { if (it.hasAlt) "${it.label} → ${remap.alt[s]?.short() ?: "nothing yet"}   ·   " else "${it.label} · " } ?: "") + "${ButtonNames.m("A")}  change   ·   ${ButtonNames.m("Y")}  reset")
    } ?: if (sel == MapCtl.GYRO) ("Gyro — ${remap.gyro.summary()}" to "Mouse or stick from moving the Thor · ${ButtonNames.m("A")}  change   ·   ${ButtonNames.m("Y")}  reset")
    else ("D-pad" to (PadRemap.DIRS.count { remap.buttons.containsKey(it) || remap.fire.containsKey(it) }.let { n ->
        listOfNotNull("$n direction${if (n > 1) "s" else ""} changed".takeIf { n > 0 }, "swapped with the left stick".takeIf { remap.dpadStick })
            .joinToString(" · ").ifEmpty { "Each direction can do something else" }.replaceFirstChar { it.uppercase() } + " · ${ButtonNames.m("A")}  change" }))

    GlassScreen(span = if (myDisplayId == 0) AuroraSpan.TOP else AuroraSpan.BOTTOM) {
      Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // while a press is being captured (listening, recording, a chord) nothing navigates:
            // the D-pad reaches the screen as well (Android makes keys of its HAT inside the app)
            if (listening || recording || chordListen) return@onPreviewKeyEvent true
            val n = e.nativeKeyEvent
            val b = ButtonEngine.menuButton(n)
            // B closes the popup (both halves kept, or Android's Back leaves the screen on the release)
            if (b == ThorButton.B && (open || bHeldDown == n.downTime)) {
                if (e.type == KeyEventType.KeyDown) { bHeldDown = n.downTime; popup = if (popup == Popup.BIND && dirSel != null) Popup.DPAD else null } else bHeldDown = -1L
                return@onPreviewKeyEvent true
            }
            // 1.3: the D-pad's page — press a direction to change it, X to swap with the left stick
            if (popup == Popup.DPAD) {
                val d = when (n.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> ThorButton.UP; KeyEvent.KEYCODE_DPAD_DOWN -> ThorButton.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> ThorButton.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> ThorButton.RIGHT
                    else -> null
                }
                if (d != null) {
                    if (e.type == KeyEventType.KeyDown && n.repeatCount == 0) { dirSel = d; tab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }
                    return@onPreviewKeyEvent true
                }
                if (b == ThorButton.X) {
                    if (e.type == KeyEventType.KeyDown && n.repeatCount == 0) save(remap.copy(dpadStick = !remap.dpadStick))
                    return@onPreviewKeyEvent true
                }
            }
            if (e.type != KeyEventType.KeyDown || n.repeatCount > 0) return@onPreviewKeyEvent false
            if (open) when (b) {
                ThorButton.L1, ThorButton.R1 -> {
                    if (popup == Popup.BIND && !recording) {
                        val tabs = Tab.values(); val i = (tab.ordinal + if (b == ThorButton.R1) 1 else tabs.size - 1) % tabs.size
                        tab = tabs[i]; listening = false
                    }
                    return@onPreviewKeyEvent true
                }
                ThorButton.L2, ThorButton.R2 -> {        // the keyboard's two pages
                    if (popup == Popup.BIND && tab == Tab.KEYBOARD) { kbPage = if (b == ThorButton.R2) 1 else 0; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                ThorButton.Y -> { if (popup == Popup.BIND || popup == Popup.GYRO) resetSel(); return@onPreviewKeyEvent true }
                else -> return@onPreviewKeyEvent false
            }
            if (b != null && b in PadRemap.BUTTONS) live = "${b.label}  →  $label gets  ${(remap.buttons[b] ?: RemapTarget.Button(b)).short()}"
            when (b) {
                ThorButton.X -> { cycleFace(); true }
                ThorButton.Y -> { resetSel(); true }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            // ── header ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusableGlass(onClick = onBack, radius = 16.dp, modifier = behind) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = g.accent, modifier = Modifier.size(20.dp))
                        Text(if (inGame) "Back to game" else "App profiles", color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
                        Hint(ButtonNames.m("B"))
                    }
                }
                app?.icon?.let { Image(it, null, Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))) }
                // the name switches to another app or game (wrong one picked, or a game's own profile)
                Box(Modifier.weight(1f)) {
                    FocusableGlass(onClick = { popup = Popup.APPS }, radius = 16.dp, modifier = behind) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            // the page's name small, the app / game big: "Game controls · <name>" didn't fit
                            Text("GAME CONTROLS", color = g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text("$label  ▾", color = g.textPrimary, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                game != null -> "$appLabel · this game only"
                                GameProfiles.runningIn(appPkg) != null -> "$appLabel · all its games — switch for this game only"
                                inGame -> "Not this game? Switch"
                                else -> "Only while $label has the controller"
                            }, color = g.accent, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Share = a file with this profile's controller mapping (never its Wayfinder
                // actions), in Download/Wayfinder — see ProfileShare
                Pill("Performance  ›", behind, fill = false) { popup = Popup.PERF }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GlassPanel(radius = 16.dp, strong = true) {
                        Row(Modifier.padding(4.dp).width(200.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (f in FaceLayout.values()) Pill(f.label, Modifier.weight(1f).then(behind), selected = f == effective) { setFace(f) }
                            Hint(ButtonNames.m("X"))
                        }
                    }
                    Text(if (cfg.face == null) "Face buttons: following all apps" else "Face buttons: only for $label",
                        color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!PadLayerCtl.wanted) {
                Row(Modifier.fillMaxWidth().background(Color(0x33D64545), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The input layer is off, so these changes do nothing right now.", color = g.textPrimary,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Pill("Turn it on", behind, fill = false) { PadLayerCtl.set(ctx, true) }
                }
                Spacer(Modifier.height(8.dp))
            }
            // ── the controller, centred: the only thing to navigate ──
            GlassPanel(Modifier.fillMaxSize(), radius = 24.dp, strong = true) {
                Column(Modifier.fillMaxSize().background(wash, RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    ControllerMap(
                        remap, sel, mapFocused && !open, inspector,
                        Modifier.weight(1f).fillMaxWidth()
                            .focusRequester(mapFocus)
                            .onFocusChanged { mapFocused = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                val n = e.nativeKeyEvent
                                val confirm = n.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    ButtonEngine.menuButton(n) == ThorButton.A
                                // A opens the control on its RELEASE (both halves kept here, so the release
                                // can't "click" the popup's first choice).
                                if (confirm) { if (e.type == KeyEventType.KeyUp) openSel(); return@onPreviewKeyEvent true }
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val dir = when (n.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f; KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
                                    KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f; KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
                                    else -> null
                                }
                                if (dir != null) { neighbour(sel, dir.first, dir.second)?.let { sel = it; true } ?: false } else false
                            }
                            .then(behind)
                            .focusable()
                            .pointerInput(Unit) {
                                detectTapGestures { p ->
                                    mapHit(p, size.width.toFloat(), size.height.toFloat())?.let { sel = it; openSel() }
                                }
                            },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            live ?: if (picking) "Press the control you want…" else "L1 L2 · R1 R2 are on the back edge",
                            color = if (live != null || picking) g.accent else g.textSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Pill(if (picking) "Cancel" else "Find by pressing", behind, fill = false) { picking = !picking }
                        Pill(if (remap.chords.isEmpty()) "Chords  ›" else "Chords (${remap.chords.size})  ›", behind, fill = false) { popup = Popup.CHORDS }
                        Pill(if (remap.changes == 0) "No changes" else "${remap.changes} change${if (remap.changes > 1) "s" else ""}  ›", behind, fill = false) { popup = Popup.CHANGES }
                        Pill("More  ›", behind, fill = false) { popup = Popup.MORE }
                    }
                }
            }
        }
        // ── the popup, over the whole screen ──
        if (open) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { popup = null } },
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.93f)
                    .background(if (g.dark) Color(0xF51C1E26) else Color(0xFAF4F5FA), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }       // taps inside don't close it
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    when (popup) {
                        Popup.BIND -> BindPopup(sel, remap, tab, kbPage, { kbPage = it }, listening, popupFirst,
                            slot = slot, onSlot = { slot = it }, recording = recording, onRecord = { recording = !recording },
                            onTab = { tab = it; listening = false; recording = false }, onListen = { listening = !listening },
                            setTarget = { t -> (dirSel ?: sel.src)?.let { bind(it, t) } }, save = ::save, reset = ::resetSel,
                            close = { popup = if (dirSel != null) Popup.DPAD else null }, dir = dirSel)
                        Popup.APPS -> AppsPanel(pkg, appLabel, popupFirst, onPick = { popup = null; if (it != pkg) onSwitchApp(it) },
                            onAll = { popup = null; onAllApps() }) { popup = null }
                        Popup.CHORDS -> ChordsPanel(remap, popupFirst, chordListen, chordPair, onListen = { chordListen = !chordListen },
                            onDone = { chordPair = emptyList(); chordListen = false }, save = ::save) { popup = null }
                        Popup.DPAD -> DpadPopup(remap, popupFirst, ::save, onEdit = { d -> dirSel = d; tab = Tab.CONTROLLER; slot = 0; popup = Popup.BIND }) { popup = null }
                        Popup.GYRO -> GyroPopup(remap.gyro, listening, popupFirst, PadLayerCtl.wanted, onListen = { listening = !listening },
                            set = { save(remap.copy(gyro = it)) }, reset = ::resetSel, close = { popup = null })
                        Popup.CHANGES -> ChangesPanel(remap, popupFirst, ::save) { popup = null }
                        Popup.PRESETS -> PresetsPanel(pkg, remap, popupFirst, ::save, { setFace(FaceLayout.XBOX) }) { popup = null }
                        Popup.PERF -> PerfPanel(pkg, label, appPkg, popupFirst) { popup = null }
                        Popup.MORE -> MorePanel(remap, popupFirst, onPresets = { popup = Popup.PRESETS },
                            onShare = {
                                popup = null
                                if (ProfileShare.export(ctx, pkg) == null) ForegroundAppService.pill("Nothing to share yet — map some buttons first", myDisplayId, 3000)
                                else sharing = true
                            }, onReset = { save(PadRemap()); popup = null },
                            onShift = { b -> save(remap.copy(shift = b, shifted = if (b == null) emptyMap() else remap.shifted - b)) }) { popup = null }
                        null -> {}
                    }
                }
            }
        }
      }
    }
}

// ── the popup ─────────────────────────────────────────────────────────────

@Composable
private fun BindPopup(
    sel: MapCtl, remap: PadRemap, tab: Tab, kbPage: Int, onPage: (Int) -> Unit, listening: Boolean, first: FocusRequester,
    slot: Int, onSlot: (Int) -> Unit, recording: Boolean, onRecord: () -> Unit,
    onTab: (Tab) -> Unit, onListen: () -> Unit, setTarget: (RemapTarget?) -> Unit, save: (PadRemap) -> Unit,
    reset: () -> Unit, close: () -> Unit, dir: ThorButton? = null,
) {
    val g = LocalGlass.current
    val src = dir ?: sel.src ?: return
    // 1.3: a D-pad direction edits like a button ("D-pad down becomes")
    val title = dir?.spoken?.replaceFirstChar { it.uppercase() } ?: sel.title
    val fire = remap.fire[src] ?: Fire.NORMAL
    val onAlt = fire.hasAlt && slot == 1
    // the Shift layer is for buttons (while it's held the D-pad goes to Wayfinder as an axis, not presses)
    val shiftBtn = remap.shift?.takeIf { it != src && !src.isDpad }
    val onShift = shiftBtn != null && slot == 2
    val cur = if (onShift) remap.shifted[src] else if (onAlt) remap.alt[src] else remap.buttons[src]
    // while listening, only the listening matters: everything else fades, the hints go
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).border(2.5.dp, g.accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(src.label, color = g.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            val stickJob = when (src) { ThorButton.L3 -> remap.jobL; ThorButton.R3 -> remap.jobR; else -> null }
            Text(if (onShift) "$title with ${shiftBtn!!.spoken} held" else if (onAlt) "$title — ${fire.label.lowercase()}"
                else if (stickJob != null) title else "$title becomes", color = g.textPrimary,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(if (stickJob != null && !onAlt && !onShift) "Now: ${stickJob.summary.lowercase()} · click → ${cur?.short() ?: src.label}"
                else if (cur == null) (if (onAlt || onShift) "Now: nothing yet" else "Now: ${src.label} — as usual") else "Now: ${cur.short()}", color = g.accent,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!listening) {
                Pill("Reset", fill = false, hint = ButtonNames.m("Y"), onClick = reset)
                Pill("Close", fill = false, hint = ButtonNames.m("B"), onClick = close)
            }
        }
        // a long / double press button has two outputs: which one the tabs edit
        if (fire.hasAlt || shiftBtn != null) Row(rest, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val main = remap.buttons[src]?.short() ?: src.label
            Choice("Press  →  $main", "a quick press", slot == 0, Modifier.weight(1f)) { onSlot(0) }
            if (fire.hasAlt) Choice("${fire.label}  →  ${remap.alt[src]?.short() ?: "pick it"}",
                if (fire == Fire.LONG) "held ${ExtEngine.LONG_MS} ms" else "two quick presses", slot == 1, Modifier.weight(1f)) { onSlot(1) }
            // hold-to-shift (§6l): the same pickers, for the Shift layer
            if (shiftBtn != null) Choice("With ${shiftBtn.spoken} held  →  ${remap.shifted[src]?.short() ?: "pick it"}",
                "the Shift layer", slot == 2, Modifier.weight(1f)) { onSlot(2) }
        }
        // tabs: L1 / R1 — they look like tabs (an underline), not like choices
        Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!listening) Hint("◀ L1", wide = true)
            Tab.values().forEach { t -> TabItem(t.label, t == tab, Modifier.weight(1f)) { onTab(t) } }
            if (!listening) Hint("R1 ▶", wide = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                Tab.CONTROLLER -> ControllerTab(src, cur, listening, first, remap, onListen, setTarget, save)
                Tab.KEYBOARD -> Box(rest) { KeyboardTab(cur, kbPage, first, onPage, setTarget) }
                Tab.MOUSE -> Column(rest.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubLabel("A mouse button or the wheel — for PC games (GameNative) and apps with a cursor")
                    RemapTarget.MOUSE_NAMES.chunked(3).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEachIndexed { i, name ->
                                val b = r * 3 + i
                                Pill("🖱 $name", Modifier.weight(1f), selected = cur == RemapTarget.Mouse(b),
                                    focus = if (b == 0) first else null) { setTarget(RemapTarget.Mouse(b)) }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Tab.ACTIONS -> Box(rest) { ActionPicker((cur as? RemapTarget.Action)?.a, first) { setTarget(RemapTarget.Action(it)) } }
                Tab.MACRO -> MacroTab(cur, first, recording, onRecord, setTarget)
            }
        }
        // what it does when held (not for "Nothing")
        if (tab != Tab.KEYBOARD && tab != Tab.MACRO) Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (src == ThorButton.L3 || src == ThorButton.R3) "Its click fires" else "How it fires", color = g.textSecondary,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(84.dp))
            for ((f, caption) in listOf(Fire.NORMAL to "one press", Fire.TURBO to "rapid presses", Fire.TOGGLE to "stays on",
                    Fire.LONG to "hold = 2nd action", Fire.DOUBLE to "twice = 2nd action")) {
                FocusableGlass(onClick = {
                    save(remap.copy(fire = if (f == Fire.NORMAL) remap.fire - src else remap.fire + (src to f),
                        alt = if (f.hasAlt) remap.alt else remap.alt - src))
                    onSlot(if (f.hasAlt) 1 else 0)
                },
                    radius = 12.dp, modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().then(if (f == fire) Modifier.padding(2.dp).background(g.accent, RoundedCornerShape(10.dp)) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(f.label, color = if (f == fire) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge)
                        Text(caption, color = if (f == fire) Color.White.copy(alpha = .85f) else g.textSecondary,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItem(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = 12.dp, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (active) g.accent else g.textSecondary, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            Box(Modifier.padding(top = 5.dp).width(56.dp).height(4.dp)
                .background(if (active) g.accent else Color.Transparent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun ControllerTab(src: ThorButton, cur: RemapTarget?, listening: Boolean, first: FocusRequester, remap: PadRemap,
                          onListen: () -> Unit, setTarget: (RemapTarget?) -> Unit, save: (PadRemap) -> Unit) {
    val g = LocalGlass.current
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    // 1.3: a stick's page is about THE STICK first (what it does, its feel); its click comes after
    val stick = src == ThorButton.L3 || src == ThorButton.R3
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stick) Column(rest, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (src == ThorButton.L3) {
                StickJobRows(remap.jobL, first) { save(remap.copy(jobL = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Invert up / down", remap.invertLeftY, Modifier.weight(1f)) { save(remap.copy(invertLeftY = it)) }
                    Toggle("Invert left / right", remap.invertLeftX, Modifier.weight(1f)) { save(remap.copy(invertLeftX = it)) }
                    Toggle("Swap with the right stick", remap.swapSticks, Modifier.weight(1f)) { save(remap.copy(swapSticks = it)) } }
                StickShapeRows(remap.stickL) { save(remap.copy(stickL = it)) }
            } else {
                StickJobRows(remap.jobR, first) { save(remap.copy(jobR = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Invert up / down", remap.invertRightY, Modifier.weight(1f)) { save(remap.copy(invertRightY = it)) }
                    Toggle("Invert left / right", remap.invertRightX, Modifier.weight(1f)) { save(remap.copy(invertRightX = it)) }
                    Toggle("Swap with the left stick", remap.swapSticks, Modifier.weight(1f)) { save(remap.copy(swapSticks = it)) } }
                StickShapeRows(remap.stickR) { save(remap.copy(stickR = it)) }
            }
            Spacer(Modifier.height(6.dp))
            SubLabel("Its click (${src.label}) becomes")
        }
        // the big one: it listens
        FocusableGlass(onClick = onListen, radius = 16.dp, modifier = Modifier.fillMaxWidth(), focusRequester = if (stick) null else first) {
            Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(16.dp)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Text("🎮", fontSize = 20.sp)
                if (listening) Text("Listening — press the button ${src.label} should become  ·  Home cancels",
                    color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                else {
                    Text("Listen for a button", color = g.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Hint(ButtonNames.m("A"))
                }
            }
        }
        Column(rest, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // several at once (Select + Start): the grid then toggles buttons in and out
            var multi by remember(src) { mutableStateOf(cur is RemapTarget.Buttons) }
            val list = (cur as? RemapTarget.Buttons)?.bs ?: listOf((cur as? RemapTarget.Button)?.b ?: src)
            fun pick(b: ThorButton) {
                if (!multi) { setTarget(RemapTarget.Button(b)); return }
                val n = if (b in list) list - b else (list + b).take(4)
                setTarget(when (n.size) { 0 -> RemapTarget.None; 1 -> RemapTarget.Button(n[0]); else -> RemapTarget.Buttons(n) })
            }
            fun isOn(b: ThorButton) = if (multi) b in list && cur != RemapTarget.None else (cur as? RemapTarget.Button)?.b == b || (cur == null && b == src)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubLabel(if (multi) "Pick up to 4 — pressed together" else "Or pick it")
                Spacer(Modifier.weight(1f))
                Toggle("Several at once", multi, Modifier.width(210.dp)) { on ->
                    multi = on
                    if (!on && cur is RemapTarget.Buttons) setTarget(RemapTarget.Button(cur.bs.first()))
                }
            }
            val grid = listOf(ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y, ThorButton.L1, ThorButton.R1,
                ThorButton.L2, ThorButton.R2, ThorButton.L3, ThorButton.R3, ThorButton.SELECT, ThorButton.START)
            grid.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { b -> Pill(if (b == src) "${b.label} (default)" else b.label, Modifier.weight(1f), selected = isOn(b)) { pick(b) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for ((d, name) in listOf(ThorButton.UP to "↑ Up", ThorButton.DOWN to "↓ Down", ThorButton.LEFT to "← Left", ThorButton.RIGHT to "→ Right"))
                    Pill(name, Modifier.weight(1f), selected = isOn(d)) { pick(d) }
                Pill("Android Back", Modifier.weight(1.2f), selected = (cur as? RemapTarget.Action)?.a == ThorAction.BACK) { setTarget(RemapTarget.Action(ThorAction.BACK)) }
                Spacer(Modifier.width(8.dp))
                Pill("Nothing", Modifier.weight(1f), selected = cur == RemapTarget.None) { setTarget(RemapTarget.None) }
            }
            // what belongs to this control
            when (src) {
                ThorButton.L2, ThorButton.R2 -> { SubLabel("Triggers")
                    Toggle("All-or-nothing (no half press)", remap.digitalTriggers) { save(remap.copy(digitalTriggers = it)) }
                    if (!remap.digitalTriggers) TriggerRangeRows(remap) { save(it) } }
                else -> {}
            }
        }
    }
}

/**
 * 1.3 — the D-pad's page, laid out as the D-pad itself (a list of four rows wasn't ergonomic).
 * Each arm says what that direction does; PRESS the direction on the D-pad to change it (RemapScreen's
 * key handler), or touch it. X swaps the whole D-pad with the left stick.
 */
@Composable
private fun DpadPopup(remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, onEdit: (ThorButton) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("D-pad", onClose)
        Text("Press a direction on the D-pad to change it — or touch it. The directions you leave alone stay the D-pad.",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // the cross
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f)); DirArm(ThorButton.UP, remap, Modifier.weight(1f), first) { onEdit(it) }; Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DirArm(ThorButton.LEFT, remap, Modifier.weight(1f)) { onEdit(it) }
                    Box(Modifier.weight(1f).height(96.dp), contentAlignment = Alignment.Center) {
                        Text("✚", color = g.textTertiary, fontSize = 44.sp)
                    }
                    DirArm(ThorButton.RIGHT, remap, Modifier.weight(1f)) { onEdit(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f)); DirArm(ThorButton.DOWN, remap, Modifier.weight(1f)) { onEdit(it) }; Spacer(Modifier.weight(1f))
                }
            }
            // the whole D-pad
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubLabel("The whole D-pad")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Swap with the left stick", remap.dpadStick, Modifier.weight(1f)) { save(remap.copy(dpadStick = it)) }
                    Hint(ButtonNames.m("X"))
                }
                Text("For old games that only read one of them.", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                val changed = PadRemap.DIRS.filter { remap.buttons.containsKey(it) || remap.fire.containsKey(it) }
                if (changed.isNotEmpty()) Pill("Put all four back", Modifier.fillMaxWidth(), fill = false) {
                    save(remap.copy(buttons = remap.buttons - changed.toSet(), fire = remap.fire - changed.toSet(), alt = remap.alt - changed.toSet()))
                }
            }
        }
        Text("↑ ↓ ← →  change that direction   ·   ${ButtonNames.m("X")}  swap   ·   ${ButtonNames.m("B")}  back", color = g.textTertiary, style = MaterialTheme.typography.labelMedium)
    }
}

/** One arm of the D-pad page: its arrow, its name, and what it does now (accent = changed). */
@Composable
private fun DirArm(d: ThorButton, remap: PadRemap, modifier: Modifier, focus: FocusRequester? = null, onEdit: (ThorButton) -> Unit) {
    val g = LocalGlass.current
    val t = remap.buttons[d]; val f = remap.fire[d]
    val on = t != null || f != null
    val arrow = when (d) { ThorButton.UP -> "↑"; ThorButton.DOWN -> "↓"; ThorButton.LEFT -> "←"; else -> "→" }
    val name = when (d) { ThorButton.UP -> "Up"; ThorButton.DOWN -> "Down"; ThorButton.LEFT -> "Left"; else -> "Right" }
    FocusableGlass(onClick = { onEdit(d) }, radius = 16.dp, modifier = modifier, focusRequester = focus) {
        Column(Modifier.fillMaxWidth().height(96.dp).then(if (on) Modifier.padding(2.dp).background(g.accent, RoundedCornerShape(14.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$arrow  $name", color = if (on) Color.White else g.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text((t?.short() ?: "as usual") + (f?.let { " · ${it.label.lowercase()}" } ?: ""),
                color = if (on) Color.White.copy(alpha = .9f) else g.textSecondary, style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 1.3 — a stick's job: itself, or the mouse / wheel / 4 keys (Wayfinder plays it; the game sees it centred). */
@Composable
private fun StickJobRows(j: StickJob, first: FocusRequester? = null, set: (StickJob) -> Unit) {
    val g = LocalGlass.current
    SubLabel("What the stick does")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (u in StickUse.values()) Choice(u.label, u.caption, j.use == u, Modifier.weight(1f), focus = if (u == StickUse.STICK) first else null) {
            set(if (u == StickUse.STICK) StickJob() else j.copy(use = u))
        }
    }
    when (j.use) {
        StickUse.MOUSE, StickUse.SCROLL -> Stepper(if (j.use == StickUse.MOUSE) "Pointer speed" else "Scroll speed", "${j.speed}",
            { set(j.copy(speed = (j.speed - 1).coerceAtLeast(1))) }, { set(j.copy(speed = (j.speed + 1).coerceAtMost(10))) })
        StickUse.KEYS -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Arrow keys", "↑ ↓ ← →", !j.wasd, Modifier.weight(1f)) { set(j.copy(wasd = false)) }
            Choice("W A S D", "PC games", j.wasd, Modifier.weight(1f)) { set(j.copy(wasd = true)) }
        }
        StickUse.STICK -> {}
    }
    if (!j.isDefault) Text("The game doesn't see this stick any more. It only counts past its deadzone (at least 10 %, or the " +
        "one set below if bigger), so a stick at rest — or one that drifts — never moves or scrolls anything.",
        color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ActionPicker(current: ThorAction?, first: FocusRequester, onPick: (ThorAction) -> Unit) {
    val g = LocalGlass.current
    val actions = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.BACK && it != ThorAction.OPEN }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        actions.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, a ->
                    FocusableGlass(onClick = { onPick(a) }, radius = 12.dp, modifier = Modifier.weight(1f),
                        focusRequester = if (r == 0 && i == 0) first else null) {
                        Column(Modifier.fillMaxWidth().then(if (a == current) Modifier.background(g.accent.copy(alpha = .9f), RoundedCornerShape(12.dp)) else Modifier)
                            .padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Text(a.title, color = if (a == current) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge)
                            Text(a.description, color = if (a == current) Color.White.copy(alpha = .85f) else g.textSecondary,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChangesPanel(remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PanelTitle("Changes", onClose, first)
        if (remap.isEmpty) Text("Nothing changed — every control does what the Thor sends.", color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
        remap.buttons.forEach { (b, t) -> ChangeRow("${b.label}  →  ${t.short()}") { save(remap.copy(buttons = remap.buttons - b)) } }
        remap.fire.forEach { (b, f) -> ChangeRow("${b.label}  fires: ${f.label.lowercase()}") { save(remap.copy(fire = remap.fire - b)) } }
        if (remap.swapSticks) ChangeRow("Sticks swapped") { save(remap.copy(swapSticks = false)) }
        if (remap.invertLeftY) ChangeRow("Left stick inverted") { save(remap.copy(invertLeftY = false)) }
        if (remap.invertRightY) ChangeRow("Right stick inverted") { save(remap.copy(invertRightY = false)) }
        if (remap.invertLeftX) ChangeRow("Left stick: left / right inverted") { save(remap.copy(invertLeftX = false)) }
        if (remap.invertRightX) ChangeRow("Right stick: left / right inverted") { save(remap.copy(invertRightX = false)) }
        if (remap.dpadStick) ChangeRow("D-pad ↔ left stick") { save(remap.copy(dpadStick = false)) }
        if (remap.digitalTriggers) ChangeRow("Triggers all-or-nothing") { save(remap.copy(digitalTriggers = false)) }
        if (remap.gyro.isOn) ChangeRow("Gyro: ${remap.gyro.summary()}") { save(remap.copy(gyro = GyroSettings())) }
        fun shape(s: StickShape) = listOfNotNull("deadzone ${s.dead} %".takeIf { s.dead != 0 }, "full at ${s.full} %".takeIf { s.full != 100 },
            listOf("", "precise centre", "fast")[s.curve.coerceIn(0, 2)].ifEmpty { null }).joinToString(" · ")
        if (!remap.jobL.isDefault) ChangeRow("Left stick  →  ${remap.jobL.summary}") { save(remap.copy(jobL = StickJob())) }
        if (!remap.jobR.isDefault) ChangeRow("Right stick  →  ${remap.jobR.summary}") { save(remap.copy(jobR = StickJob())) }
        if (!remap.stickL.isDefault) ChangeRow("Left stick: ${shape(remap.stickL)}") { save(remap.copy(stickL = StickShape())) }
        if (!remap.stickR.isDefault) ChangeRow("Right stick: ${shape(remap.stickR)}") { save(remap.copy(stickR = StickShape())) }
        if (remap.trigRanged) ChangeRow("Triggers: ${remap.trigStart}–${remap.trigFull} %") { save(remap.copy(trigStart = 0, trigFull = 100)) }
        remap.shift?.let { sh ->
            ChangeRow("Shift button: ${sh.spoken}") { save(remap.copy(shift = null, shifted = emptyMap())) }
            remap.shifted.forEach { (b, t) -> ChangeRow("${sh.spoken} held + ${b.spoken}  →  ${t.short()}") { save(remap.copy(shifted = remap.shifted - b)) } }
        }
        remap.alt.forEach { (b, t) -> ChangeRow("${b.label}  ${remap.fire[b]?.label?.lowercase()}  →  ${t.short()}") { save(remap.copy(alt = remap.alt - b)) } }
        remap.chords.forEach { c -> ChangeRow("${c.a.label} + ${c.b.label}  →  ${c.target.short()}") { save(remap.copy(chords = remap.chords - c)) } }
    }
}

@Composable
private fun PresetsPanel(pkg: String, remap: PadRemap, first: FocusRequester, save: (PadRemap) -> Unit, xboxFace: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val others = remember { AppConfigStore.configured().filter { it != pkg && AppConfigStore.get(it).remap != null } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PanelTitle("Presets", onClose, first)
        // was "Swap A ↔ B and X ↔ Y" as four remaps: the same thing as the face-button switch,
        // under a second name — now it IS that switch (naming pass 2026-09-25)
        Pill("Xbox face buttons (A at the bottom)", Modifier.fillMaxWidth()) { xboxFace(); onClose() }
        Pill("Swap the sticks", Modifier.fillMaxWidth()) { save(remap.copy(swapSticks = true)); onClose() }
        // round 8: emulator hotkeys — only what each emulator does BY DEFAULT on
        // Android (researched with sources 2026-09-25: RetroArch's keyboard table; the others only open
        // their menu with Back). Chords: Select held + a button; those two wait 60 ms before the game.
        val app = GameProfiles.pkgOf(pkg)
        if (app.startsWith("com.retroarch")) {
            SubLabel("RetroArch hotkeys — RetroArch's own default keys, nothing to set up in RetroArch")
            Pill("Hold Select + R1: save · + L1: load · + R2: fast-forward · + Start: menu", Modifier.fillMaxWidth()) {
                save(remap.copy(shift = ThorButton.SELECT, shifted = remap.shifted + EmuPresets.retroArch)); onClose()
            }
            Text("Turns on the Shift button (Select) for this app. Select alone still reaches RetroArch. Save and load use the current state slot (F6 / F7 on Keyboard & mouse's Emulator pad change it).",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        } else if (EmuPresets.menuByBack.any { app.startsWith(it) }) {
            SubLabel("Emulator menu")
            Pill("Hold Select + Start: the emulator's menu", Modifier.fillMaxWidth()) {
                save(remap.copy(shift = ThorButton.SELECT, shifted = remap.shifted + (ThorButton.START to EmuPresets.menuTarget))); onClose()
            }
            Text("This emulator has no default keys for save / load / fast-forward: set those up in its own settings (they can use any button).",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
        Pill("Triggers all-or-nothing", Modifier.fillMaxWidth()) { save(remap.copy(digitalTriggers = true)); onClose() }
        // 1.3: browsers and apps with a cursor (GitHub #9)
        SubLabel("Web browsing and apps with a cursor")
        Pill("Left stick: mouse · right stick: scroll · A: click · X: right click · B: back", Modifier.fillMaxWidth()) {
            // B → Android Back: Firefox keeps the pad's B for the web's gamepad API, so B did nothing there (1.3 test)
            save(remap.copy(jobL = StickJob(StickUse.MOUSE), jobR = StickJob(StickUse.SCROLL),
                buttons = remap.buttons + (ThorButton.A to RemapTarget.Mouse(0)) + (ThorButton.X to RemapTarget.Mouse(1)) +
                    (ThorButton.B to RemapTarget.Action(ThorAction.BACK)))); onClose()
        }
        Text("The D-pad keeps moving between links — each direction can be changed on its own (the D-pad's page). While Wayfinder's " +
            "keyboard is open, the controller types (these changes pause).", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        if (others.isNotEmpty()) {
            SubLabel("Copy from another app")
            val pm = LocalContext.current.packageManager
            others.forEach { o ->
                val name = remember(o) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(o, 0)).toString() }.getOrDefault(o) }
                Pill("$name  ·  ${(AppConfigStore.get(o).remap?.changes ?: 0).let { "$it change${if (it == 1) "" else "s"}" }}", Modifier.fillMaxWidth()) {
                    AppConfigStore.get(o).remap?.let { save(it) }; onClose()
                }
            }
        } else Text("Copy from another app: none has changes yet.", color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}



// ── the gyro popup (plan §6f, 3) ─────────────────────────────────────────────

private val SENS_STEPS = listOf(.3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 1.2f, 1.4f, 1.6f, 1.8f, 2f, 2.5f, 3f, 3.5f, 4f)
private val VSCALE_STEPS = listOf(.5f, .6f, .7f, .8f, .9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
private val DZ_STEPS = listOf(0, 5, 10, 15, 20, 25, 30, 40)

private fun <T : Comparable<T>> List<T>.step(cur: T, up: Boolean): T {
    val i = indexOfFirst { it >= cur }.let { if (it < 0) size - 1 else it }
    return this[(if (up) (if (this[i] > cur) i else i + 1) else i - 1).coerceIn(0, size - 1)]
}

@Composable
private fun GyroPopup(gyro: GyroSettings, listening: Boolean, first: FocusRequester, layerOn: Boolean,
                      onListen: () -> Unit, set: (GyroSettings) -> Unit, reset: () -> Unit, close: () -> Unit) {
    val g = LocalGlass.current
    val rest = if (listening) Modifier.alpha(.35f) else Modifier
    // live preview: the engine reads the gyro for these settings while the popup is open (sends nothing)
    var liveX by remember { mutableStateOf(0f) }; var liveY by remember { mutableStateOf(0f) }; var liveOn by remember { mutableStateOf(false) }
    var calib by remember { mutableStateOf<String?>(null) }
    DisposableEffect(gyro) {
        GyroEngine.onLive = { x, y, on -> liveX = x; liveY = y; liveOn = on }
        GyroEngine.setPreview(gyro)
        onDispose { GyroEngine.setPreview(null); GyroEngine.onLive = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).border(2.5.dp, g.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ScreenRotation, null, tint = g.accent, modifier = Modifier.size(18.dp))
            }
            Text("Gyro", color = g.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Now: ${gyro.summary()}", color = g.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!listening) {
                Pill("Reset", fill = false, hint = ButtonNames.m("Y"), onClick = reset)
                Pill("Close", fill = false, hint = ButtonNames.m("B"), onClick = close)
            }
        }
        if (!layerOn) Text("Gyro needs the input layer — turn it on in Wayfinder, then Controller.",
            color = Color(0xFFD64545), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // left: what it drives, and when
            Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubLabel("What it moves")
                Row(rest, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GyroMode.values().forEachIndexed { i, m ->
                        Choice(m.label, null, gyro.mode == m, Modifier.weight(1f), if (i == 0) first else null) { set(gyro.copy(mode = m)) }
                    }
                }
                Text(gyro.mode.caption, color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                if (gyro.isOn) {
                    SubLabel("When it works")
                    GyroOn.values().toList().chunked(3).forEach { row ->
                        Row(rest, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { o ->
                                Choice(o.label, null, gyro.on == o, Modifier.weight(1f)) {
                                    val b = if (o.trigger && gyro.button != ThorButton.L2 && gyro.button != ThorButton.R2) ThorButton.L2 else gyro.button
                                    set(gyro.copy(on = o, button = b))
                                }
                            }
                        }
                    }
                    if (gyro.on.usesButton) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (gyro.on.trigger) "Trigger" else "Button", color = g.textSecondary,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(62.dp))
                        if (gyro.on.trigger) {
                            for (b in listOf(ThorButton.L2, ThorButton.R2))
                                Pill(b.label, Modifier.weight(1f), selected = gyro.button == b) { set(gyro.copy(button = b)) }
                            Spacer(Modifier.weight(2f))
                        } else {
                            FocusableGlass(onClick = onListen, radius = 12.dp, modifier = Modifier.weight(2.2f)) {
                                Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(12.dp)) else Modifier)
                                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                                    Text(if (listening) "Press it… · Home cancels" else "${gyro.button.label}  ·  press to change",
                                        color = if (listening) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                            for (b in listOf(ThorButton.L2, ThorButton.R2, ThorButton.L1, ThorButton.R1))
                                Pill(b.label, Modifier.weight(1f).then(rest), selected = gyro.button == b) { set(gyro.copy(button = b)) }
                        }
                    }
                    Text(when (gyro.on) {
                        GyroOn.ALWAYS -> "On as soon as the game has the controller."
                        GyroOn.HOLD -> "On while ${gyro.button.label} is held — it still does its usual job."
                        GyroOn.TOGGLE -> "Each press of ${gyro.button.label} turns it on or off (a short buzz)."
                        GyroOn.OFF_WHILE_HOLD -> "On, and off while ${gyro.button.label} is held (to re-centre your hands)."
                        GyroOn.TRIGGER_FULL -> "On while ${gyro.button.label} is pulled all the way — aim down sights, then fine-aim."
                        GyroOn.TRIGGER_HALF -> "On from a light pull of ${gyro.button.label}."
                    } + (ControlsStore.triggerFor(ThorAction.KEYBOARD)?.let { "  ${it.label()} (keyboard & mouse panel) can switch it off for this game." } ?: ""),
                        color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            // right: how it feels, and a live look
            if (gyro.isOn) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).then(rest), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubLabel("How it feels")
                Stepper("Speed", "×${"%.1f".format(gyro.sens)}", { set(gyro.copy(sens = SENS_STEPS.step(gyro.sens, false))) }) {
                    set(gyro.copy(sens = SENS_STEPS.step(gyro.sens, true))) }
                if (gyro.mode != GyroMode.LEFT_STICK) {
                    Stepper("Up / down speed", "×${"%.1f".format(gyro.vScale)}", { set(gyro.copy(vScale = VSCALE_STEPS.step(gyro.vScale, false))) }) {
                        set(gyro.copy(vScale = VSCALE_STEPS.step(gyro.vScale, true))) }
                    Toggle("Invert up / down", gyro.invertY) { set(gyro.copy(invertY = it)) }
                }
                Text("Left / right comes from", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GyroAxis.values().forEach { a -> Choice(a.label, a.caption, gyro.axis == a, Modifier.weight(1f)) { set(gyro.copy(axis = a)) } }
                }
                Text("Steadiness (hand shake)", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Off", "Light", "Strong").forEachIndexed { i, s -> Choice(s, null, gyro.steady == i, Modifier.weight(1f)) { set(gyro.copy(steady = i)) } }
                }
                if (gyro.mode != GyroMode.MOUSE)
                    Stepper("Game's stick deadzone", "${gyro.gameDeadzone} %", { set(gyro.copy(gameDeadzone = DZ_STEPS.step(gyro.gameDeadzone, false))) }) {
                        set(gyro.copy(gameDeadzone = DZ_STEPS.step(gyro.gameDeadzone, true))) }
                // live: move the Thor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Canvas(Modifier.size(64.dp).background(Color.Black.copy(alpha = .05f), RoundedCornerShape(12.dp))) {
                        val c = Offset(size.width / 2, size.height / 2)
                        drawCircle(ACC.copy(alpha = .25f), size.width * .42f, c, style = Stroke(2f))
                        drawCircle(if (liveOn) ACC else Color.Gray, 7f * density,
                            Offset(c.x + liveX * size.width * .42f, c.y + liveY * size.height * .42f))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (liveOn) "Working — move the Thor" else "Waiting for ${if (gyro.on.usesButton) gyro.button.label else "the gyro"}",
                            color = if (liveOn) g.accent else g.textSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(calib ?: "Drifting? Put the Thor down and calibrate.", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Pill(if (calib == "Hold still…") "…" else "Calibrate", fill = false) {
                        calib = "Hold still…"
                        GyroEngine.calibrateNow { ok -> calib = if (ok) "Calibrated." else "It moved — try again on a table." }
                    }
                }
            }
        }
    }
}

/** A choice pill with an optional caption under it (the "When held" style). */
@Composable
private fun Choice(text: String, caption: String?, selected: Boolean, modifier: Modifier, focus: FocusRequester? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = 12.dp, modifier = modifier, focusRequester = focus) {
        Column(Modifier.fillMaxWidth().then(if (selected) Modifier.padding(2.dp).background(g.accent, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 6.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (selected) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, color = if (selected) Color.White.copy(alpha = .85f) else g.textSecondary,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, dec: () -> Unit, inc: () -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Pill("−", Modifier.width(46.dp), onClick = dec)
        Text(value, color = g.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Pill("+", Modifier.width(46.dp), onClick = inc)
    }
}

// ── the keyboard tab ────────────────────────────────────────────────────────────────────────

/** Legacy single key + meta → the keys it stands for (modifiers first). */
private fun RemapTarget.Key.asCodes(): List<Int> = buildList {
    if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
    if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
    if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
    add(code)
}

private fun kc(name: String) = KeyEvent.keyCodeFromString("KEYCODE_$name")
private val ARROWS = setOf("↑", "↓", "←", "→")

@Composable
private fun KeyboardTab(cur: RemapTarget?, page: Int, first: FocusRequester, onPage: (Int) -> Unit, setTarget: (RemapTarget?) -> Unit) {
    val g = LocalGlass.current
    val keys: List<Int> = when (cur) { is RemapTarget.Keys -> cur.codes; is RemapTarget.Key -> cur.asCodes(); else -> emptyList() }
    fun set(list: List<Int>) = setTarget(if (list.isEmpty()) null else RemapTarget.Keys(list, (cur as? RemapTarget.Keys)?.inOrder == true))
    val toggle: (Int) -> Unit = { code -> set(if (code in keys) keys - code else (keys + code).take(6)) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // the chosen keys, in order
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keys", color = g.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
            if (keys.isEmpty()) Text("Pick one or more keys — held together, pressed in this order",
                color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
            keys.forEachIndexed { i, c ->
                if (i > 0) Text("+", color = g.textSecondary, fontWeight = FontWeight.Bold)
                KeyChip(i + 1, RemapTarget.keyName(c),
                    onMove = { set(keys.toMutableList().apply { removeAt(i); add(if (i > 0) i - 1 else size, c) }) },
                    onRemove = { set(keys - c) })
            }
            Spacer(Modifier.weight(1f))
            if (keys.size > 1) Text("A on a chip: move it · ✕ removes", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
            val inOrder = (cur as? RemapTarget.Keys)?.inOrder == true
            Pill("Together", Modifier.width(110.dp), selected = !inOrder) { if (keys.isNotEmpty()) setTarget(RemapTarget.Keys(keys, false)) }
            Pill("One after another", Modifier.width(160.dp), selected = inOrder) { if (keys.isNotEmpty()) setTarget(RemapTarget.Keys(keys, true)) }
        }
        // the two pages: L2 / R2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Hint("◀ L2", wide = true)
            Pill("Main keyboard", Modifier.width(170.dp), selected = page == 0) { onPage(0) }
            Pill("Arrows, nav & numpad", Modifier.width(200.dp), selected = page == 1) { onPage(1) }
            Hint("R2 ▶", wide = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black.copy(alpha = .035f), RoundedCornerShape(14.dp)).padding(6.dp)) {
            if (page == 0) MainKeys(keys, first, toggle) else NavKeys(keys, first, toggle)
        }
    }
}

@Composable
private fun KeyChip(n: Int, name: String, onMove: () -> Unit, onRemove: () -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.background(g.accent, RoundedCornerShape(15.dp)), verticalAlignment = Alignment.CenterVertically) {
        FocusableGlass(onClick = onMove, radius = 15.dp) {
            Row(Modifier.background(g.accent).padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(18.dp).background(Color.White.copy(alpha = .28f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("$n", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        FocusableGlass(onClick = onRemove, radius = 15.dp) {
            Text("✕", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(g.accent).padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

/** One key: [w] = its width in key units; chosen keys are filled with their order number. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(label: String, code: Int?, keys: List<Int>, w: Float = 1f,
                                                                focus: FocusRequester? = null, onClick: (Int) -> Unit) {
    val g = LocalGlass.current
    if (code == null) { Spacer(Modifier.weight(w)); return }
    val i = keys.indexOf(code)
    FocusableGlass(onClick = { onClick(code) }, radius = 8.dp, modifier = Modifier.weight(w).fillMaxHeight(), focusRequester = focus) {
        Box(Modifier.fillMaxSize().then(if (i >= 0) Modifier.background(g.accent, RoundedCornerShape(8.dp)) else Modifier),
            contentAlignment = Alignment.Center) {
            Text(label, color = if (i >= 0) Color.White else g.textPrimary, fontSize = if (label in ARROWS) 18.sp else if (label.length > 4) 10.sp else 12.sp,
                fontWeight = if (i >= 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            if (i >= 0) Text("${i + 1}", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp))
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)

@Composable
private fun MainKeys(keys: List<Int>, first: FocusRequester, t: (Int) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val r = Modifier.weight(1f)
        KeyRow(r) {
            KeyCap("Esc", kc("ESCAPE"), keys, focus = first, onClick = t); KeyCap("", null, keys, .5f, onClick = t)
            for (i in 1..12) {
                KeyCap("F$i", kc("F$i"), keys, onClick = t)
                if (i == 4 || i == 8) KeyCap("", null, keys, .3f, onClick = t)
            }
        }
        KeyRow(r) {
            KeyCap("`", kc("GRAVE"), keys, onClick = t)
            for (d in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")) KeyCap(d, kc(d), keys, onClick = t)
            KeyCap("-", kc("MINUS"), keys, onClick = t); KeyCap("=", kc("EQUALS"), keys, onClick = t)
            KeyCap("⌫ Bksp", kc("DEL"), keys, 2f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Tab", kc("TAB"), keys, 1.5f, onClick = t)
            for (c in "QWERTYUIOP") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap("[", kc("LEFT_BRACKET"), keys, onClick = t); KeyCap("]", kc("RIGHT_BRACKET"), keys, onClick = t)
            KeyCap("\\", kc("BACKSLASH"), keys, 1.5f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Caps", kc("CAPS_LOCK"), keys, 1.75f, onClick = t)
            for (c in "ASDFGHJKL") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap(";", kc("SEMICOLON"), keys, onClick = t); KeyCap("'", kc("APOSTROPHE"), keys, onClick = t)
            KeyCap("Enter", kc("ENTER"), keys, 2.25f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Shift", kc("SHIFT_LEFT"), keys, 2.25f, onClick = t)
            for (c in "ZXCVBNM") KeyCap("$c", kc("$c"), keys, onClick = t)
            KeyCap(",", kc("COMMA"), keys, onClick = t); KeyCap(".", kc("PERIOD"), keys, onClick = t); KeyCap("/", kc("SLASH"), keys, onClick = t)
            KeyCap("Shift", kc("SHIFT_RIGHT"), keys, 2.75f, onClick = t)
        }
        KeyRow(r) {
            KeyCap("Ctrl", kc("CTRL_LEFT"), keys, 1.5f, onClick = t); KeyCap("Win", kc("META_LEFT"), keys, 1.25f, onClick = t)
            KeyCap("Alt", kc("ALT_LEFT"), keys, 1.25f, onClick = t); KeyCap("Space", kc("SPACE"), keys, 6.25f, onClick = t)
            KeyCap("Alt", kc("ALT_RIGHT"), keys, 1.25f, onClick = t); KeyCap("Win", kc("META_RIGHT"), keys, 1.25f, onClick = t)
            KeyCap("Menu", kc("MENU"), keys, 1.25f, onClick = t); KeyCap("Ctrl", kc("CTRL_RIGHT"), keys, 1.5f, onClick = t)
        }
    }
}

@Composable
private fun NavKeys(keys: List<Int>, first: FocusRequester, t: (Int) -> Unit) {
    val g = LocalGlass.current
    @Composable fun Cap(text: String) = Text(text, color = g.textSecondary, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // arrows & navigation
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Arrows & navigation")
            val r = Modifier.weight(1f)
            KeyRow(r) { KeyCap("PrtSc", kc("SYSRQ"), keys, focus = first, onClick = t); KeyCap("ScrLk", kc("SCROLL_LOCK"), keys, onClick = t); KeyCap("Pause", kc("BREAK"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Ins", kc("INSERT"), keys, onClick = t); KeyCap("Home", kc("MOVE_HOME"), keys, onClick = t); KeyCap("PgUp", kc("PAGE_UP"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Del", kc("FORWARD_DEL"), keys, onClick = t); KeyCap("End", kc("MOVE_END"), keys, onClick = t); KeyCap("PgDn", kc("PAGE_DOWN"), keys, onClick = t) }
            KeyRow(r) { KeyCap("", null, keys, onClick = t); KeyCap("↑", kc("DPAD_UP"), keys, onClick = t); KeyCap("", null, keys, onClick = t) }
            KeyRow(r) { KeyCap("←", kc("DPAD_LEFT"), keys, onClick = t); KeyCap("↓", kc("DPAD_DOWN"), keys, onClick = t); KeyCap("→", kc("DPAD_RIGHT"), keys, onClick = t) }
        }
        // numpad: + and Enter two keys tall, 0 two keys wide
        Column(Modifier.weight(4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Numpad")
            Row(Modifier.weight(5f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val r = Modifier.weight(1f)
                    KeyRow(r) { KeyCap("NumLk", kc("NUM_LOCK"), keys, onClick = t); KeyCap("/", kc("NUMPAD_DIVIDE"), keys, onClick = t); KeyCap("*", kc("NUMPAD_MULTIPLY"), keys, onClick = t) }
                    KeyRow(r) { for (d in 7..9) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { for (d in 4..6) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { for (d in 1..3) KeyCap("$d", kc("NUMPAD_$d"), keys, onClick = t) }
                    KeyRow(r) { KeyCap("0", kc("NUMPAD_0"), keys, 2f, onClick = t); KeyCap(".", kc("NUMPAD_DOT"), keys, onClick = t) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyRow(Modifier.weight(1f)) { KeyCap("−", kc("NUMPAD_SUBTRACT"), keys, onClick = t) }
                    KeyRow(Modifier.weight(2f)) { KeyCap("+", kc("NUMPAD_ADD"), keys, onClick = t) }
                    KeyRow(Modifier.weight(2f)) { KeyCap("Enter", kc("NUMPAD_ENTER"), keys, onClick = t) }
                }
            }
        }
        // media & web
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap("Media & web")
            val r = Modifier.weight(1f)
            KeyRow(r) { KeyCap("Vol −", kc("VOLUME_DOWN"), keys, onClick = t); KeyCap("Vol +", kc("VOLUME_UP"), keys, onClick = t); KeyCap("Mute", kc("VOLUME_MUTE"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Prev", kc("MEDIA_PREVIOUS"), keys, onClick = t); KeyCap("Play", kc("MEDIA_PLAY_PAUSE"), keys, onClick = t); KeyCap("Next", kc("MEDIA_NEXT"), keys, onClick = t) }
            KeyRow(r) { KeyCap("Stop", kc("MEDIA_STOP"), keys, onClick = t); KeyCap("Web ◀", kc("BACK"), keys, onClick = t); KeyCap("Web ▶", kc("FORWARD"), keys, onClick = t) }
            Spacer(Modifier.weight(2f))
        }
    }
}

// ── the macro tab (phase 3, plan §6g) ─────────────────────────────────────────

private val HOLD_STEPS = listOf(10, 20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 3000, 5000)
private val GAP_STEPS = listOf(0) + HOLD_STEPS

private fun MacroStep.name(): String = when (val o = out) {
    is RemapTarget.Button -> o.b.spoken
    is RemapTarget.Key -> "⌨ " + RemapTarget.keyName(o.code)
    is RemapTarget.Mouse -> "🖱 " + RemapTarget.MOUSE_NAMES.getOrElse(o.b) { "Mouse" }
    else -> "?"
}

@Composable
private fun MacroTab(cur: RemapTarget?, first: FocusRequester, recording: Boolean, onRecord: () -> Unit, setTarget: (RemapTarget?) -> Unit) {
    val g = LocalGlass.current
    val m = cur as? RemapTarget.Macro
    val steps = m?.steps ?: emptyList()
    val repeat = m?.repeat ?: false
    var selStep by remember { mutableStateOf(-1) }
    var addingKey by remember { mutableStateOf(false) }
    var keyPage by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(3000); confirmClear = false } }
    // editing keeps an imported macro off: only the switch below turns it on
    fun set(list: List<MacroStep>, rep: Boolean = repeat) =
        setTarget(if (list.isEmpty()) null else RemapTarget.Macro(list.take(MacroStep.MAX), rep, m?.armed ?: true))
    LaunchedEffect(addingKey, keyPage) { delay(120); runCatching { first.requestFocus() } }
    if (addingKey) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a key to the macro", color = g.textPrimary, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Pill("Main keyboard", Modifier.width(150.dp), selected = keyPage == 0) { keyPage = 0 }
                Pill("Arrows, nav & numpad", Modifier.width(190.dp), selected = keyPage == 1) { keyPage = 1 }
                Pill("Cancel", fill = false) { addingKey = false }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black.copy(alpha = .035f), RoundedCornerShape(14.dp)).padding(6.dp)) {
                val add: (Int) -> Unit = { code -> set(steps + MacroStep(RemapTarget.Key(code))); selStep = steps.size; addingKey = false }
                if (keyPage == 0) MainKeys(emptyList(), first, add) else NavKeys(emptyList(), first, add)
            }
        }
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // An imported macro arrives OFF: it plays nothing until the user has looked at its steps
        if (m != null && !m.armed) FocusableGlass(onClick = { setTarget(m.copy(armed = true)) }, radius = 16.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("This macro came from a shared file — it's off", color = g.textPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Check its steps below, then turn it on.", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Text("Turn it on", color = g.accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Hint(ButtonNames.m("A"))
            }
        }
        // record: presses and their timing, added at the end
        FocusableGlass(onClick = onRecord, radius = 16.dp, modifier = Modifier.fillMaxWidth(), focusRequester = first) {
            Row(Modifier.fillMaxWidth().then(if (recording) Modifier.background(Color(0xFFD64545), RoundedCornerShape(16.dp)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Text("●", color = if (recording) Color.White else Color(0xFFD64545), fontSize = 18.sp)
                if (recording) Text("Recording — press the buttons, timing is kept  ·  Home stops",
                    color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                else {
                    Text(if (steps.isEmpty()) "Record with the controller" else "Record more (added at the end)", color = g.textPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Hint(ButtonNames.m("A"))
                }
            }
        }
        val rest = if (recording) Modifier.alpha(.35f) else Modifier
        // the steps, in order
        Row(rest.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (steps.isEmpty()) Text("No steps yet — record them, or add keys and clicks below.", color = g.textSecondary,
                style = MaterialTheme.typography.labelMedium)
            steps.forEachIndexed { i, s ->
                if (i > 0) Text("›", color = g.textSecondary)
                FocusableGlass(onClick = { selStep = if (selStep == i) -1 else i }, radius = 12.dp) {
                    Column(Modifier.then(if (i == selStep) Modifier.background(g.accent, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 9.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${i + 1} · ${s.name()}", color = if (i == selStep) Color.White else g.textPrimary,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${s.hold} ms" + if (s.gap > 0) " · wait ${s.gap}" else "", color = if (i == selStep) Color.White.copy(alpha = .85f) else g.textSecondary,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        // the selected step
        if (selStep in steps.indices) {
            val s = steps[selStep]
            fun put(n: MacroStep) = set(steps.toMutableList().also { it[selStep] = n })
            Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Stepper("Held", "${s.hold} ms", { put(s.copy(hold = HOLD_STEPS.step(s.hold, false))) }) {
                    put(s.copy(hold = HOLD_STEPS.step(s.hold, true))) } }
                Box(Modifier.weight(1f)) { Stepper("Then wait", "${s.gap} ms", { put(s.copy(gap = GAP_STEPS.step(s.gap, false))) }) {
                    put(s.copy(gap = GAP_STEPS.step(s.gap, true))) } }
            }
            Row(rest, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("◀ Earlier", Modifier.weight(1f)) { if (selStep > 0) { set(steps.toMutableList().apply { add(selStep - 1, removeAt(selStep)) }); selStep-- } }
                Pill("Later ▶", Modifier.weight(1f)) { if (selStep < steps.size - 1) { set(steps.toMutableList().apply { add(selStep + 1, removeAt(selStep)) }); selStep++ } }
                Pill("Remove step", Modifier.weight(1f), danger = true) { set(steps.filterIndexed { i, _ -> i != selStep }); selStep = -1 }
            }
        }
        Row(rest, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add", color = g.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
            Pill("+ Key", Modifier.weight(1f)) { addingKey = true }
            Pill("+ Left click", Modifier.weight(1f)) { set(steps + MacroStep(RemapTarget.Mouse(0))) }
            Pill("+ Right click", Modifier.weight(1f)) { set(steps + MacroStep(RemapTarget.Mouse(1))) }
            Pill(if (confirmClear) "Press again" else "Clear all", Modifier.weight(1f), danger = true) {
                if (!confirmClear) confirmClear = true else { confirmClear = false; set(emptyList()); selStep = -1 }
            }
        }
        Row(rest, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Once per press", "plays to the end", !repeat, Modifier.weight(1f)) { if (steps.isNotEmpty()) set(steps, false) }
            Choice("Repeats while held", "stops when released", repeat, Modifier.weight(1f)) { if (steps.isNotEmpty()) set(steps, true) }
        }
        val total = steps.sumOf { it.hold + it.gap }
        Text("${steps.size} / ${MacroStep.MAX} steps · ${"%.1f".format(total / 1000f)} s   ·   Home or Back stops a macro. " +
            "Macros can break online games' rules — keep them for offline play.",
            color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

// ── which game (Game controls opened from a game, plan §6h) ─────────────────────

@Composable
private fun AppsPanel(current: String, appLabel: String, first: FocusRequester, onPick: (String) -> Unit, onAll: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val v = AppConfigStore.version.intValue
    val appPkg = GameProfiles.pkgOf(current)
    val onScreens = remember { ForegroundAppService.screenGames() }
    val others = remember { AppConfigStore.configured().filter { it !in onScreens && AppConfigStore.get(it).let { c -> c.remap != null || c.face != null } } }
    val games = remember(v) { GameProfiles.forApp(appPkg) }
    val running = GameProfiles.runningIn(appPkg)
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirmDelete) { if (confirmDelete != null) { delay(3000); confirmDelete = null } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Which game?", onClose, first)
        // per-game profiles of this app (Mario Kart ≠ Zelda in the same Dolphin)
        if (running != null || games.isNotEmpty()) {
            SubLabel("Games in $appLabel — each can have its own controls")
            if (running != null && games.none { it.first == GameProfiles.key(appPkg, running.game) })
                Pill("+  Controls just for ${running.title}  (playing now — starts as a copy)", fill = false) {
                    onPick(GameProfiles.create(appPkg, running.game, running.title))
                }
            AppRow(appPkg, current == appPkg, title = "$appLabel — all its games") { onPick(appPkg) }
            games.forEach { (k, gp) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppRow(appPkg, current == k, title = gp.title + if (running?.let { GameProfiles.key(appPkg, it.game) } == k) "   · playing now" else "",
                            changes = gp.remap?.changes ?: 0) { onPick(k) }
                    }
                    Pill(if (confirmDelete == k) "Press again to delete" else "Delete", fill = false, danger = true) {
                        if (confirmDelete != k) confirmDelete = k
                        else { confirmDelete = null; if (current == k) onPick(appPkg); GameProfiles.remove(k) }
                    }
                }
            }
        }
        SubLabel("On the screens now")
        if (onScreens.isEmpty()) Text("No app on the screens.", color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
        // this app is already listed above with its games
        onScreens.filter { it != appPkg || (running == null && games.isEmpty()) }.forEach { p -> AppRow(p, p == current) { onPick(GameProfiles.activeKey(p)) } }
        if (others.isNotEmpty()) {
            SubLabel("Other apps with a profile")
            others.forEach { AppRow(it, it == current) { onPick(it) } }
        }
        Pill("All apps…", fill = false, onClick = onAll)
    }
}

@Composable
private fun AppRow(pkg: String, current: Boolean, title: String? = null, changes: Int? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val entry by produceState<AppEntry?>(null, pkg) {
        value = withContext(Dispatchers.IO) { runCatching {
            val pm = ctx.packageManager; val ai = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai).toBitmap(72, 72).asImageBitmap() }.getOrNull())
        }.getOrNull() }
    }
    FocusableGlass(onClick = onClick, radius = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entry?.icon?.let { Image(it, null, Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))) }
            Text(title ?: entry?.label ?: pkg, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            val n = changes ?: AppConfigStore.get(pkg).remap?.changes
            Text(if (current) "Showing" else if (n != null && n > 0) "$n change${if (n == 1) "" else "s"}" else "",
                color = if (current) g.accent else g.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── chords: two buttons together (phase 3) ────────────────────────────────────

@Composable
private fun ChordsPanel(remap: PadRemap, first: FocusRequester, listening: Boolean, pair: List<ThorButton>,
                        onListen: () -> Unit, onDone: () -> Unit, save: (PadRemap) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    fun add(t: RemapTarget) {
        val a = pair[0]; val b = pair[1]
        save(remap.copy(chords = remap.chords.filterNot { it.has(a) && it.has(b) } + Chord(a, b, t))); onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Chords — two buttons pressed together", onClose, first)
        Text("Press two buttons at once to get something else (L3 + R3 → Select). Those two buttons wait a moment " +
            "(${ExtEngine.CHORD_MS} ms) before reaching the game; the others don't.", color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
        remap.chords.forEach { c -> ChangeRow("${c.a.label} + ${c.b.label}   →   ${c.target.short()}") { save(remap.copy(chords = remap.chords - c)) } }
        if (pair.size == 2) {
            // the "+ New chord" button that had focus is gone now: focus goes to the first output
            // (nothing had it — the controller had to find its way back, review 2026-09-25)
            val outFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(120); runCatching { outFocus.requestFocus() } }
            SubLabel("${pair[0].label} + ${pair[1].label} does…")
            PadRemap.OUTPUTS.chunked(8).forEachIndexed { ri, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEachIndexed { bi, b -> Pill(b.spoken, Modifier.weight(1f), focus = if (ri == 0 && bi == 0) outFocus else null) { add(RemapTarget.Button(b)) } }
                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            SubLabel("Or a Wayfinder action")
            Box(Modifier.fillMaxWidth().height(150.dp)) { ActionPicker(null, remember { FocusRequester() }) { add(RemapTarget.Action(it)) } }
            Pill("Cancel", fill = false) { onDone() }
        } else if (remap.chords.size < PadRemap.MAX_CHORDS) {
            FocusableGlass(onClick = onListen, radius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().then(if (listening) Modifier.background(g.accent, RoundedCornerShape(16.dp)) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Text(if (listening) "Press the two buttons…  ${pair.joinToString(" + ") { it.label }}   ·   Home cancels"
                        else "+ New chord: press two buttons", color = if (listening) Color.White else g.textPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        } else Text("${PadRemap.MAX_CHORDS} chords at most — remove one to add another.", color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

// ── small parts ──────────────────────────────────────────────────────────

/** A controller shortcut, the same everywhere: the button's letter in a filled accent dot. */
@Composable
private fun Hint(letter: String, wide: Boolean = false) {
    val g = LocalGlass.current
    Box((if (wide) Modifier.height(24.dp).background(g.accent, RoundedCornerShape(12.dp)).padding(horizontal = 9.dp)
        else Modifier.size(22.dp).background(g.accent, CircleShape)), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** One pill style for every choice: glass, or solid accent when it's the current setting. */
@Composable
private fun appLabelOf(ctx: android.content.Context, pkg: String): String = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Share: a file in Download/Wayfinder, or Android's share menu (Discord, mail, Drive…). */
@Composable
private fun ShareChoice(title: String, onSave: () -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(26.dp)
        Box(Modifier.fillMaxWidth(0.55f).background(g.base.copy(alpha = 0.97f), shape).glassSurface(g, shape, raised = true)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Share the controls for $title", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text("Only the controller mapping goes in the file — never Wayfinder's shortcuts.",
                    color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Pill("Save to Download / Wayfinder", focus = first) { onSave() }
                Pill("Send with another app…") { onSend() }
                Pill("Cancel", fill = true, hint = ButtonNames.m("B")) { onClose() }
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, focus: FocusRequester? = null,
                 fill: Boolean = true, danger: Boolean = false, hint: String? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = 12.dp, modifier = modifier, focusRequester = focus) {
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .then(if (selected) Modifier.padding(2.dp).background(g.accent, RoundedCornerShape(10.dp)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = when { selected -> Color.White; danger -> Color(0xFFD64545); else -> g.textPrimary },
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint != null) Hint(hint)
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), focus: FocusRequester? = null, set: (Boolean) -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = { set(!on) }, radius = 12.dp, modifier = modifier, focusRequester = focus) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (on) "On" else "Off", color = if (on) g.accent else g.textSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChangeRow(text: String, onRemove: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onRemove, radius = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("Remove", color = g.accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Round 8 (2026-09-25): this app's — or this game's — own performance, fan, refresh rate and stick
 * lights, changed in game where you feel them (Home + X). "Usual" = for a game, what its app uses;
 * for an app, the user's own setting. Applied while it runs; the user's own values come back after.
 */
@Composable
private fun PerfPanel(key: String, label: String, appPkg: String, first: FocusRequester, onClose: () -> Unit) {
    val g = LocalGlass.current
    val v = AppConfigStore.version.intValue
    val cfg = remember(v, key) { Profiles.get(key) }
    val isGame = GameProfiles.isGame(key)
    fun set(change: (AppConfig) -> AppConfig) {
        Profiles.update(key, change); ForegroundAppService.reapplyPerf(); ForegroundAppService.reapplyLights()
    }
    val modes = listOf(app.wayfinder.lights.LightMode.OFF, app.wayfinder.lights.LightMode.STATIC, app.wayfinder.lights.LightMode.BREATHING,
        app.wayfinder.lights.LightMode.SPECTRUM, app.wayfinder.lights.LightMode.SCREEN, app.wayfinder.lights.LightMode.AYN)
    // a game's lights start from its app's own colours, else the user's
    val base = cfg.lights ?: (if (isGame) AppConfigStore.get(appPkg).lights else null) ?: app.wayfinder.lights.LightSettings.global
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Performance & lights · $label", onClose, first)
        Text(if (isGame) "Only while this game runs. “Usual” = what its app uses (App profiles), else your own setting."
            else "While $label is on a screen. “Usual” = your own setting.",
            color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
        SubLabel("Performance")
        Choice(listOf("Usual") + PerfMode.values().map { it.label }, (cfg.perf?.ordinal ?: -1) + 1) { i ->
            set { it.copy(perf = if (i == 0) null else PerfMode.values()[i - 1]) } }
        SubLabel("Fan")
        Choice(listOf("Usual") + FanMode.values().map { it.label }, (cfg.fan?.ordinal ?: -1) + 1) { i ->
            set { it.copy(fan = if (i == 0) null else FanMode.values()[i - 1]) } }
        SubLabel("Refresh rate")
        Choice(listOf("Usual", "60 Hz", "120 Hz"), when (cfg.hz) { 60 -> 1; 120 -> 2; else -> 0 }) { i ->
            set { it.copy(hz = when (i) { 1 -> 60; 2 -> 120; else -> null }) } }
        // 1.3 (GitHub #25, #22): the bottom screen and the frame-rate counter, per game too
        SubLabel(if (isGame) "Bottom screen while this game is on top" else "Bottom screen while $label is on top")
        Choice(listOf("Usual", "Keep on", "Off"), cfg.second.ordinal) { i ->
            set { it.copy(second = SecondScreenPolicy.values()[i]) }; ForegroundAppService.reapplyPolicy() }
        SubLabel("Frame-rate counter")
        Choice(listOf("Usual", "Shown", "Hidden"), when (cfg.fps) { true -> 1; false -> 2; null -> 0 }) { i ->
            set { it.copy(fps = when (i) { 1 -> true; 2 -> false; else -> null }) }; ForegroundAppService.reapplyFps() }
        SubLabel("Stick lights")
        Choice(listOf("Usual") + modes.map { if (it == app.wayfinder.lights.LightMode.SCREEN) "Screen" else it.label }, cfg.lights?.let { l -> modes.indexOf(l.mode) + 1 } ?: 0) { i ->
            set { it.copy(lights = if (i == 0) null else base.copy(mode = modes[i - 1])) } }
        Text("Two apps on the screens: the more demanding performance, fan and refresh rate win. Colours and brightness of the lights: Stick lights.",
            color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

/** Round 8 — a stick's shape, in steps (controller-friendly). "Every game" = the all-games value
 *  from the controller test page (Controller → Test the controller). */
@Composable
internal fun StickShapeRows(s: StickShape, set: (StickShape) -> Unit) {
    val g = LocalGlass.current
    val dz = (listOf(0, 4, 6, 8, 10, 12, 15, 20, 25) + s.dead).distinct().sorted()
    Text("Deadzone — hides drift (a stick that moves on its own). Usual = every game's: ${AppSettings.stickDefaults.stickL.dead} % left, " +
        "${AppSettings.stickDefaults.stickR.dead} % right (set in Controller, Test the controller).", color = g.textTertiary,
        style = MaterialTheme.typography.bodySmall)
    Choice(dz.map { if (it == 0) "Usual" else "$it %" }, dz.indexOf(s.dead).coerceAtLeast(0)) { set(s.copy(dead = dz[it])) }
    val full = (listOf(100, 95, 90, 85, 80) + s.full).distinct().sortedDescending()
    SubLabel("Full at — for a stick that never quite reaches the edge")
    Choice(full.map { "$it %" }, full.indexOf(s.full).coerceAtLeast(0)) { set(s.copy(full = full[it])) }
    SubLabel("Response")
    Choice(listOf("Linear", "Precise centre", "Fast"), s.curve) { set(s.copy(curve = it)) }
}

/** Round 8 — both triggers' range: nothing below the start, a full pull from the end. */
@Composable
internal fun TriggerRangeRows(r: PadRemap, set: (PadRemap) -> Unit) {
    val start = (listOf(0, 5, 10, 20, 30) + r.trigStart).distinct().sorted()
    val end = (listOf(100, 90, 80, 70, 60) + r.trigFull).distinct().sortedDescending()
    SubLabel("Starts at — a light touch does nothing")
    Choice(start.map { if (it == 0) "0 %" else "$it %" }, start.indexOf(r.trigStart).coerceAtLeast(0)) { set(r.copy(trigStart = start[it])) }
    SubLabel("Full at — a short pull is enough")
    Choice(end.map { "$it %" }, end.indexOf(r.trigFull).coerceAtLeast(0)) { set(r.copy(trigFull = end[it])) }
}

/** "More ›": the less frequent things, off the header (2026-09-26). */
@Composable
private fun MorePanel(remap: PadRemap, first: FocusRequester, onPresets: () -> Unit, onShare: () -> Unit, onReset: () -> Unit,
                      onShift: (ThorButton?) -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("More", onClose, first)
        Pill("Presets  ›  (web browsing, emulator hotkeys, Xbox face buttons, copy from another app…)", Modifier.fillMaxWidth(), onClick = onPresets)
        Pill("Share these controls  ›", Modifier.fillMaxWidth(), onClick = onShare)
        // hold-to-shift (§6l): opt-in, off by default (2026-09-26)
        SubLabel("Shift button — hold it to give every button a second job")
        val opts = listOf<ThorButton?>(null) + PadRemap.SHIFTS
        Choice(opts.map { it?.spoken ?: "Off" }, opts.indexOf(remap.shift).coerceAtLeast(0)) { onShift(opts[it]) }
        Text(if (remap.shift == null) "Off: every button works as usual."
            else "Hold ${remap.shift.spoken} and press a button: its “With ${remap.shift.spoken} held” job (set on each button's page). " +
                "Nothing reaches the game while it's held; pressed alone, ${remap.shift.spoken} still reaches the game (a moment late).",
            color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        Pill(if (confirm) "Press ${ButtonNames.m("A")} again to reset everything" else "Reset all", Modifier.fillMaxWidth(), danger = true) {
            if (remap.isEmpty) return@Pill
            if (confirm) onReset() else confirm = true
        }
        if (remap.isEmpty) Text("Nothing to reset: every control does what the Thor sends.", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

/** One row of choices (a loop, not nested lambdas: those sent the Compose compiler into a StackOverflow). */
@Composable
internal fun Choice(options: List<String>, selected: Int, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in options.indices) Pill(options[i], Modifier.weight(1f), selected = i == selected) { pick(i) }
    }
}

@Composable
private fun PanelTitle(text: String, onBack: () -> Unit, focus: FocusRequester? = null) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = g.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Pill("‹ Back", fill = false, hint = ButtonNames.m("B"), focus = focus, onClick = onBack)
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text, color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
}

// ── the map ──────────────────────────────────────────────────────────────

/** Design units shown: x 10..1770 (tags right of the controller), y -165..858. */
private const val VX0 = 10f; private const val VY0 = -185f; private const val VW = 1760f; private const val VH = 1043f

private fun mapScale(w: Float, h: Float): Triple<Float, Float, Float> {
    val k = minOf(w / VW, h / VH)
    return Triple(k, (w - VW * k) / 2 - VX0 * k, (h - VH * k) / 2 - VY0 * k)
}

private fun mapHit(p: Offset, w: Float, h: Float): MapCtl? {
    val (k, ox, oy) = mapScale(w, h)
    val ux = (p.x - ox) / k; val uy = (p.y - oy) / k
    if (abs(ux - MapCtl.GYRO.x) < 175f && abs(uy - MapCtl.GYRO.y) < 45f) return MapCtl.GYRO
    return MapCtl.values().filter { hypot(it.x - ux, it.y - uy) < maxOf(it.r * 1.25f, 70f) }
        .minByOrNull { hypot(it.x - ux, it.y - uy) }
}

/** The control in that direction: the nearest one, favouring those straight ahead. 1.3: it must lie IN
 *  that direction (within ~63°) — Left on Y went up to Start (a hair to the left, far above) and never
 *  reached the left stick; only when nothing does, the nearest ahead. */
private fun neighbour(from: MapCtl, dx: Float, dy: Float): MapCtl? {
    fun along(c: MapCtl) = (c.x - from.x) * dx + (c.y - from.y) * dy
    fun across(c: MapCtl) = abs((c.x - from.x) * dy - (c.y - from.y) * dx)
    val ahead = MapCtl.values().filter { it != from && along(it) > 20f }
    return (ahead.filter { along(it) > 0.5f * across(it) }.ifEmpty { ahead })
        .minByOrNull { along(it) + 2.2f * across(it) }
}

private val INK = Color(0xFF363C58)
private val ACC = Color(0xFF0A84FF)
private val RING = Color(0xFF5E5CE6)

@Composable
private fun ControllerMap(remap: PadRemap, sel: MapCtl, focused: Boolean, inspector: Pair<String, String>, modifier: Modifier) {
    val g = LocalGlass.current
    val tm = rememberTextMeasurer()
    val ink = if (g.dark) Color(0xFFE2E5F5) else INK
    val face = if (g.dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .70f)
    val accText = if (g.dark) Color(0xFF6CB4FF) else Color(0xFF0A6BDA)
    Canvas(modifier) {
        val (k, ox, oy) = mapScale(size.width, size.height)
        fun X(u: Float) = ox + u * k
        fun Y(u: Float) = oy + u * k
        fun circle(cx: Float, cy: Float, r: Float, sw: Float, a: Float, fill: Color? = face, stroke: Color = ink, dashed: Boolean = false) {
            if (fill != null) drawCircle(fill, r * k, Offset(X(cx), Y(cy)), style = Fill)
            drawCircle(stroke.copy(alpha = a), r * k, Offset(X(cx), Y(cy)),
                style = Stroke(sw * k, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f * k, 7f * k)) else null))
        }
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, sw: Float, a: Float, fill: Color? = face, stroke: Color = ink) {
            if (fill != null) drawRoundRect(fill, Offset(X(x), Y(y)), Size(w * k, h * k), CornerRadius(r * k), style = Fill)
            drawRoundRect(stroke.copy(alpha = a), Offset(X(x), Y(y)), Size(w * k, h * k), CornerRadius(r * k), style = Stroke(sw * k))
        }
        fun label(s: String, cx: Float, cy: Float, sz: Float, color: Color = ink, bold: Boolean = true) {
            val l = tm.measure(s, TextStyle(fontSize = (sz * k / density).sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
            drawText(l, topLeft = Offset(X(cx) - l.size.width / 2f, Y(cy) - l.size.height / 2f))
        }
        val changed = { c: MapCtl -> when (c) {
            MapCtl.DPAD -> remap.dpadStick || PadRemap.DIRS.any { remap.buttons.containsKey(it) || remap.fire.containsKey(it) }
            MapCtl.L3 -> remap.buttons.containsKey(ThorButton.L3) || remap.invertLeftY || remap.invertLeftX || remap.swapSticks || !remap.jobL.isDefault
            MapCtl.R3 -> remap.buttons.containsKey(ThorButton.R3) || remap.invertRightY || remap.invertRightX || remap.swapSticks || !remap.jobR.isDefault
            MapCtl.L2, MapCtl.R2 -> remap.buttons.containsKey(c.src) || remap.fire.containsKey(c.src) || remap.digitalTriggers
            MapCtl.GYRO -> remap.gyro.isOn
            else -> remap.buttons.containsKey(c.src) || remap.fire.containsKey(c.src)
        } }
        // a changed control: its label in the accent colour (the tag says what it does)
        fun labelColor(c: MapCtl) = if (changed(c)) accText else ink

        val CX = 740f
        // bumpers L1 / R1: pills on the back edge
        for (c in listOf(MapCtl.L1, MapCtl.R1)) {
            rrect(c.x - 95f, c.y - 23f, 190f, 46f, 23f, 6f, .9f)
            label(c.src!!.label, c.x, c.y, 42f, labelColor(c))
        }
        // triggers L2 / R2: behind them, a little dome (their rounded top), flat underside
        for (c in listOf(MapCtl.L2, MapCtl.R2)) {
            val x0 = c.x - 95f; val x1 = c.x + 95f; val top = c.y - 44f; val bot = c.y + 23f
            val dome = Path().apply {
                moveTo(X(x0), Y(bot - 8f)); lineTo(X(x0), Y(c.y + 6f))
                cubicTo(X(x0), Y(top + 10f), X(c.x - 70f), Y(top), X(c.x), Y(top))
                cubicTo(X(c.x + 70f), Y(top), X(x1), Y(top + 10f), X(x1), Y(c.y + 6f))
                lineTo(X(x1), Y(bot - 8f)); quadraticBezierTo(X(x1), Y(bot), X(x1 - 8f), Y(bot))
                lineTo(X(x0 + 8f), Y(bot)); quadraticBezierTo(X(x0), Y(bot), X(x0), Y(bot - 8f)); close()
            }
            drawPath(dome, face); drawPath(dome, ink.copy(alpha = .9f), style = Stroke(6f * k))
            label(c.src!!.label, c.x, c.y + 4f, 42f, labelColor(c))
        }
        rrect(40f, -24f, 2 * CX - 80f, 24f, 12f, 3f, .35f, face.copy(alpha = face.alpha * .4f))
        val body = Path().apply {
            moveTo(X(30f), Y(36f)); quadraticBezierTo(X(30f), Y(22f), X(44f), Y(22f)); lineTo(X(1436f), Y(22f))
            quadraticBezierTo(X(1450f), Y(22f), X(1450f), Y(36f)); lineTo(X(1450f), Y(784f))
            quadraticBezierTo(X(1450f), Y(830f), X(1404f), Y(830f)); lineTo(X(76f), Y(830f))
            quadraticBezierTo(X(30f), Y(830f), X(30f), Y(784f)); close()
        }
        drawPath(body, face.copy(alpha = face.alpha * .55f)); drawPath(body, ink.copy(alpha = .85f), style = Stroke(7f * k))
        rrect(CX - 367.5f, 58f, 735f, 640f, 12f, 3f, .3f, face.copy(alpha = face.alpha * .35f))
        // sticks
        for (c in listOf(MapCtl.L3, MapCtl.R3)) {
            circle(c.x, c.y, 118f, 4f, .5f, face.copy(alpha = face.alpha * .5f))
            drawCircle(RING.copy(alpha = .75f), 103f * k, Offset(X(c.x), Y(c.y)), style = Stroke(8f * k))
            circle(c.x, c.y, 76f, 6f, .9f)
            label(c.src!!.label, c.x, c.y, 48f, labelColor(c))
        }
        // D-pad, with its arrows
        val LX = 178f; val LOW = 522f; val aw = 31f; val R = 104f
        circle(LX, LOW, 112f, 4f, .5f, face.copy(alpha = face.alpha * .5f))
        val cross = Path().apply {
            moveTo(X(LX - aw), Y(LOW - R)); lineTo(X(LX + aw), Y(LOW - R)); lineTo(X(LX + aw), Y(LOW - aw)); lineTo(X(LX + R), Y(LOW - aw))
            lineTo(X(LX + R), Y(LOW + aw)); lineTo(X(LX + aw), Y(LOW + aw)); lineTo(X(LX + aw), Y(LOW + R)); lineTo(X(LX - aw), Y(LOW + R))
            lineTo(X(LX - aw), Y(LOW + aw)); lineTo(X(LX - R), Y(LOW + aw)); lineTo(X(LX - R), Y(LOW - aw)); lineTo(X(LX - aw), Y(LOW - aw)); close()
        }
        drawPath(cross, face); drawPath(cross, (if (changed(MapCtl.DPAD)) accText else ink).copy(alpha = .9f), style = Stroke(6f * k))
        for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
            val px = LX + dx * 66f; val py = LOW + dy * 66f
            drawPath(Path().apply {
                moveTo(X(px + dx * 13f), Y(py + dy * 13f))
                lineTo(X(px - dx * 6f + dy * 13f), Y(py - dy * 6f + dx * 13f))
                lineTo(X(px - dx * 6f - dy * 13f), Y(py - dy * 6f - dx * 13f)); close()
            }, ink.copy(alpha = .7f))
        }
        // ABXY
        circle(1302f, 240f, 132f, 4f, .5f, face.copy(alpha = face.alpha * .5f))
        for (c in listOf(MapCtl.X, MapCtl.Y, MapCtl.A, MapCtl.B)) {
            circle(c.x, c.y, 40f, 6f, .9f)
            label(c.src!!.label, c.x, c.y, 50f, labelColor(c))
        }
        // Select ▢ / Start ▷
        for (c in listOf(MapCtl.SELECT, MapCtl.START)) circle(c.x, c.y, 24f, 6f, .9f)
        drawRect(labelColor(MapCtl.SELECT), Offset(X(277f), Y(72f)), Size(16f * k, 16f * k), style = Stroke(3.5f * k))
        drawPath(Path().apply { moveTo(X(1189f), Y(71f)); lineTo(X(1204f), Y(80f)); lineTo(X(1189f), Y(89f)); close() },
            labelColor(MapCtl.START), style = Stroke(3.5f * k))
        label("Select", 196f, 80f, 42f, bold = false); label("Start", 1278f, 80f, 42f, bold = false)
        // Home / Back: Wayfinder's own keys — drawn dashed, never selectable
        circle(250f, 722f, 28f, 4f, .5f, dashed = true); label("⌂", 250f, 722f, 36f, ink.copy(alpha = .6f))
        circle(1230f, 722f, 28f, 4f, .5f, dashed = true); label("↺", 1230f, 722f, 36f, ink.copy(alpha = .6f))
        label("Home", 250f, 784f, 38f, ink.copy(alpha = .6f), bold = false); label("Back", 1230f, 784f, 38f, ink.copy(alpha = .6f), bold = false)
        label("Home and Back stay with Wayfinder", CX, 770f, 34f, ink.copy(alpha = .6f), bold = false)
        // the gyro: a pill in the screen, its state written inside
        run {
            val on = remap.gyro.isOn
            val text = if (on) "Gyro → " + remap.gyro.summary() else "Gyro · off"
            val l = tm.measure(text, TextStyle(fontSize = (36f * k / density).sp, color = if (on) Color.White else ink, fontWeight = FontWeight.SemiBold))
            val w = maxOf(l.size.width + 48f * k, 330f * k); val hh = 70f * k
            val left = X(MapCtl.GYRO.x) - w / 2; val top = Y(MapCtl.GYRO.y) - hh / 2
            if (on) drawRoundRect(ACC, Offset(left, top), Size(w, hh), CornerRadius(hh / 2))
            else { drawRoundRect(face, Offset(left, top), Size(w, hh), CornerRadius(hh / 2)); drawRoundRect(ink.copy(alpha = .6f), Offset(left, top), Size(w, hh), CornerRadius(hh / 2), style = Stroke(4f * k)) }
            drawText(l, topLeft = Offset(X(MapCtl.GYRO.x) - l.size.width / 2f, Y(MapCtl.GYRO.y) - l.size.height / 2f))
            if (sel == MapCtl.GYRO) {
                if (focused) drawRoundRect(ACC.copy(alpha = .28f), Offset(left - 13f * k, top - 13f * k), Size(w + 26f * k, hh + 26f * k), CornerRadius(hh), style = Stroke(16f * k))
                drawRoundRect(ACC, Offset(left - 7f * k, top - 7f * k), Size(w + 14f * k, hh + 14f * k), CornerRadius(hh), style = Stroke(6f * k))
            }
        }
        label(inspector.first, CX, 350f, 64f, accText)
        label(inspector.second, CX, 440f, 36f, ink.copy(alpha = .7f), bold = false)

        // tags: what a changed control now does, joined to it by a dotted line
        for (c in MapCtl.values()) {
            val fire = c.src?.let { s -> remap.fire[s]?.let { " · " + if (it.hasAlt) "${it.label.lowercase()} → ${remap.alt[s]?.short() ?: "?"}" else it.label } } ?: ""
            val becomes = c.src != null && remap.buttons[c.src] != null
            // 1.3: a stick with a job (mouse / scroll / keys) says so first
            val job = when (c) { MapCtl.L3 -> remap.jobL; MapCtl.R3 -> remap.jobR; else -> null }?.takeIf { !it.isDefault }
            val text = when {
                c == MapCtl.DPAD -> {
                    val arrow = mapOf(ThorButton.UP to "↑", ThorButton.DOWN to "↓", ThorButton.LEFT to "←", ThorButton.RIGHT to "→")
                    val parts = PadRemap.DIRS.mapNotNull { d -> remap.buttons[d]?.let { "${arrow[d]} ${it.short()}" } } +
                        listOfNotNull("↔ Left stick".takeIf { remap.dpadStick })
                    if (parts.isEmpty()) null else if (parts.size <= 2) parts.joinToString("  ·  ") else parts.take(2).joinToString("  ·  ") + "  +${parts.size - 2}"
                }
                job != null -> "→ " + job.summary
                becomes -> "→ " + remap.buttons.getValue(c.src!!).short() + fire
                fire.isNotEmpty() -> fire.removePrefix(" · ").replaceFirstChar { it.uppercase() }
                c == MapCtl.L3 && (remap.invertLeftY || remap.invertLeftX || remap.swapSticks) -> if (remap.swapSticks) "↔ Right stick"
                    else listOfNotNull("↕".takeIf { remap.invertLeftY }, "↔".takeIf { remap.invertLeftX }).joinToString(" ", "Inverted ")
                c == MapCtl.R3 && (remap.invertRightY || remap.invertRightX || remap.swapSticks) -> if (remap.swapSticks) "↔ Left stick"
                    else listOfNotNull("↕".takeIf { remap.invertRightY }, "↔".takeIf { remap.invertRightX }).joinToString(" ", "Inverted ")
                (c == MapCtl.L2 || c == MapCtl.R2) && remap.digitalTriggers -> "All-or-nothing"
                else -> null
            } ?: continue
            val filled = becomes || c == MapCtl.DPAD || job != null
            val l = tm.measure(text, TextStyle(fontSize = (36f * k / density).sp, color = if (filled) Color.White else accText, fontWeight = FontWeight.SemiBold))
            val w = l.size.width + 32f * k; val h = 54f * k
            val left = if (c.align > 0) X(c.tagX) else X(c.tagX) - w
            val top = Y(c.tagY) - h / 2
            // dotted leader from the control's edge to the tag's near end
            val nearX = if (c.align > 0) left else left + w
            val ang = kotlin.math.atan2(Y(c.tagY) - Y(c.y), nearX - X(c.x))
            val sx = X(c.x) + kotlin.math.cos(ang) * (c.r + 8f) * k; val sy = Y(c.y) + kotlin.math.sin(ang) * (c.r + 8f) * k
            if (hypot(nearX - sx, Y(c.tagY) - sy) > 10f * k)
                drawLine(ACC, Offset(sx, sy), Offset(nearX, Y(c.tagY)), strokeWidth = 3f * k,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * k, 6f * k)))
            if (filled) drawRoundRect(ACC, Offset(left, top), Size(w, h), CornerRadius(h / 2))
            else { drawRoundRect(face, Offset(left, top), Size(w, h), CornerRadius(h / 2)); drawRoundRect(ACC, Offset(left, top), Size(w, h), CornerRadius(h / 2), style = Stroke(4f * k)) }
            drawText(l, topLeft = Offset(left + 16f * k, top + (h - l.size.height) / 2))
        }

        // selection: glowing ring while the map has the controller, a plain "pinned" ring otherwise
        val glow = focused
        when (sel) {
            MapCtl.GYRO -> {}       // drawn with the pill
            MapCtl.L1, MapCtl.L2, MapCtl.R1, MapCtl.R2 -> {
                val dome = sel == MapCtl.L2 || sel == MapCtl.R2
                val t = if (dome) 52f else 29f; val h = if (dome) 81f else 58f
                if (glow) drawRoundRect(ACC.copy(alpha = .28f), Offset(X(sel.x - 108f), Y(sel.y - t - 7f)), Size(216f * k, (h + 14f) * k), CornerRadius(36f * k), style = Stroke(16f * k))
                drawRoundRect(ACC, Offset(X(sel.x - 101f), Y(sel.y - t)), Size(202f * k, h * k), CornerRadius(29f * k), style = Stroke(6f * k))
            }
            else -> {
                val r = when (sel) { MapCtl.DPAD -> 122f; MapCtl.L3, MapCtl.R3 -> 90f; MapCtl.SELECT, MapCtl.START -> 36f; else -> 52f }
                if (glow) drawCircle(ACC.copy(alpha = .28f), (r + 7f) * k, Offset(X(sel.x), Y(sel.y)), style = Stroke(16f * k))
                drawCircle(ACC, r * k, Offset(X(sel.x), Y(sel.y)), style = Stroke(6f * k))
            }
        }
    }
}

/** Round 8 — emulator presets built only from verified DEFAULTS. */
private object EmuPresets {
    private fun keys(code: Int) = RemapTarget.Keys(listOf(code))
    /** The Shift layer (hold Select) — RetroArch's default keyboard hotkeys. */
    val retroArch = mapOf(
        ThorButton.R1 to keys(android.view.KeyEvent.KEYCODE_F2),       // save state
        ThorButton.L1 to keys(android.view.KeyEvent.KEYCODE_F4),       // load state
        ThorButton.R2 to keys(android.view.KeyEvent.KEYCODE_SPACE),    // fast-forward (toggle)
        ThorButton.START to keys(android.view.KeyEvent.KEYCODE_F1),    // menu
    )
    /** PPSSPP, Dolphin, melonDS, Azahar / Citra / Lime3DS, Lemuroid: Back opens the menu by default. */
    val menuByBack = listOf("org.ppsspp", "org.dolphinemu", "me.magnum.melon", "org.azahar", "org.citra", "io.github.lime3ds", "com.swordfish.lemuroid")
    val menuTarget: RemapTarget = RemapTarget.Action(ThorAction.BACK)
}
