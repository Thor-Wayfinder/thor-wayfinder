package app.wayfinder

import androidx.compose.foundation.verticalScroll

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlyphText
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.LocalPadLook
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Round 8 (2026-09-25) — "Test the controller": what the pad really sends
 * (Wayfinder's own screens get the raw copy, no profile), each stick's drift at rest in %, a
 * suggested deadzone applied to every game in one press (build 18, C8: a 1 s hold of Start — a press only tests it), the
 * triggers' travel and every button.
 * While it's open the pad feeds this page ([PadTest.active]): A / B / the D-pad don't navigate.
 * b19 (ps-F17): Home, Back and the AYN button (Wayfinder's: PS, touchpad click and Mute on a DualSense) only light up here
 * while the page is in front with the controller ([PadTest.inFront], the service's test sink in [ButtonEngine]); Back held
 * 1 s leaves, as B. The nav bar's Back still leaves.
 * dev build 16 (theme E): ONE controller at a time — [selected]: the Thor, or a connected controller (another one's
 * deliberate press takes the page, its other events are ignored: no mixing); its B (its own "back": ○ on a DualSense)
 * held for a second leaves; the deadzone is saved to the controller measured.
 */
object PadTest {
    /** The Thor: its own pad, AYN's, or the controller playing as it (docked) — what [ExtPadsCtl.srcOfDevice] counts as 0.
     *  dev build 17 (WP11): the Thor's page's route too ("padtest:thor"). */
    const val THOR = HubPage.THOR
    @Volatile var active = false
    /** b19 (ps-F17): the page is in front, resumed with its window focused (each screen has a focused window: the tour's
     *  rule), on screen [display]. The service holds Home, Back and the AYN button back for it only then, and only on the
     *  controller's screen ([ButtonEngine.Host.testHolds]). Written by the page only. */
    @Volatile var inFront = false
    @Volatile var display = -1
    var lx by mutableFloatStateOf(0f); var ly by mutableFloatStateOf(0f)
    var rx by mutableFloatStateOf(0f); var ry by mutableFloatStateOf(0f)
    var l2 by mutableFloatStateOf(0f); var r2 by mutableFloatStateOf(0f)
    var hx by mutableFloatStateOf(0f); var hy by mutableFloatStateOf(0f)
    val down = mutableStateListOf<ThorButton>()

    /** From MainActivity: true = consumed. */
    /** Start / Select on this page: "Use the suggestion" / "Remove" (its buttons can't take focus:
     *  the pad feeds the page — review 2026-09-25). Build 18 (C8, ps-F2): on a 1 s hold, never on a press — the page asks
     *  you to press every button, and a press of Options applied the deadzone, one of Create removed it. */
    var onStart: (() -> Unit)? = null
    var onSelect: (() -> Unit)? = null
    /** Build 18 (C8): Start or Select held now, before its second is up (its card button says "Keep holding…"). */
    var holding by mutableStateOf<ThorButton?>(null)
        private set
    private val hold = PadTestRules.Hold()
    private val holdFire = Runnable {
        val b = holding ?: return@Runnable
        if (!hold.check(android.os.SystemClock.uptimeMillis())) return@Runnable
        holding = null
        if (b == ThorButton.START) onStart?.invoke() else onSelect?.invoke()
    }
    private fun holdStart(b: ThorButton) {
        main.removeCallbacks(holdFire)
        hold.down(android.os.SystemClock.uptimeMillis(), 0); holding = b
        main.postDelayed(holdFire, PadTestRules.HOLD_MS)
    }
    private fun holdEnd() { main.removeCallbacks(holdFire); hold.up(android.os.SystemClock.uptimeMillis()); holding = null }
    /** dev build 16 (theme E): the page's Back (its pill's path) — a held B calls it. */
    var onLeave: (() -> Unit)? = null
    /** Build 18 (C2, MORE-23): a tap of B, let go before the hold that leaves — the page says to hold it. */
    var onTapB: (() -> Unit)? = null
    private var bDownAt = 0L

    /** dev build 16 (theme E): whose readings these are — [THOR] or a controller's key. */
    var selected by mutableStateOf(THOR)
        private set
    /** Bumped when [selected] changes: the page measures again from nothing (drift, the triggers' travel). */
    var epoch by mutableIntStateOf(0)
        private set
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val leave = Runnable { onLeave?.invoke() }

    // dev build 17 (WP11): which controller the page opens on is its route's ("padtest:<thor|key>", [PadTestRules.initial]) —
    // no "picked" flag a later page could inherit
    /** Its readings from now on (a chip, a press, the one gone): everything read so far is forgotten. */
    internal fun show(who: String) {
        if (who == selected) return
        selected = who; epoch++
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; l2 = 0f; r2 = 0f; hx = 0f; hy = 0f; down.clear()
        main.removeCallbacks(leave); holdEnd()
    }
    /** The page goes: a B held as it went leaves nothing behind (nor a Start / Select hold, C8). */
    internal fun stop() { main.removeCallbacks(leave); holdEnd(); onLeave = null; onTapB = null }

    private fun whoOf(dev: android.view.InputDevice?): String = whoOfSrc(ExtPadsCtl.srcOfDevice(dev))
    /** [s] as its presses count: a connected controller's key, else the Thor (its own pad, or what plays as it). */
    private fun whoOfSrc(s: Int): String = if (s > 0) ExtPadsCtl.keyOfSrc(s) ?: THOR else THOR

    fun onMotion(e: MotionEvent): Boolean {
        if (!active || e.source and android.view.InputDevice.SOURCE_JOYSTICK != android.view.InputDevice.SOURCE_JOYSTICK) return false
        val x = e.getAxisValue(MotionEvent.AXIS_X); val y = e.getAxisValue(MotionEvent.AXIS_Y)
        val z = e.getAxisValue(MotionEvent.AXIS_Z); val rz = e.getAxisValue(MotionEvent.AXIS_RZ)
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_BRAKE), e.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_GAS), e.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        val hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        // dev build 16 (theme E): another controller's moves are ignored — a deliberate one (past half) takes the page
        val who = whoOf(e.device)
        show(PadTestRules.next(selected, who, PadTestRules.deliberate(x, y, z, rz, lt, rt, hatX, hatY)))
        if (who != selected) return true
        lx = x; ly = y; rx = z; ry = rz; l2 = lt; r2 = rt; hx = hatX; hy = hatY
        return true
    }
    fun onKey(e: KeyEvent): Boolean {
        if (!active) return false
        // b19 (ps-F17): a PS given to the game (MODE) lights its Home ([PadTestRules.litButton]). No button: the nav bar's or a
        // gesture's Back leaves the page (false)
        val b = PadTestRules.litButton(e.keyCode, ButtonEngine.printedButton(e)) ?: return KeyEvent.isGamepadButton(e.keyCode)
        // a Home never reaches a window. b19 (ps-F17): a controller's Back that does (the service late, or not running) lights
        // as the service's own ([onHeld]): held 1 s it leaves
        if (b == ThorButton.HOME && e.keyCode != KeyEvent.KEYCODE_BUTTON_MODE) return false
        return press(whoOf(e.device), b, e.action == KeyEvent.ACTION_DOWN, e.repeatCount == 0,
            leaves = e.keyCode == KeyEvent.KEYCODE_BUTTON_B || b == ThorButton.BACK)
    }

    /** b19 (ps-F17): Home, Back or the AYN button of controller [src] (as its presses count), or what its reader withheld under
     *  them, that the service held back for the page ([ButtonEngine.Host.testPress]): lit, nothing else runs. Its Back held 1 s
     *  leaves, as its B. Main thread. */
    fun onHeld(b: ThorButton, down: Boolean, src: Int) {
        if (!active) return
        // device test S16b, and a logs-kept release build's device pass (a release build drops it: R8's Log rule)
        android.util.Log.d("ThorPadTest", "held ${b.name} ${if (down) 1 else 0} src=$src")
        press(whoOfSrc(src), b, down, first = true, leaves = b == ThorButton.BACK)
    }

    /** One press of [b] on [who]'s controller ([isDown]; [first]: no key repeat), a key the window got ([onKey]) or a press the
     *  service held back ([onHeld]). [leaves]: its B or its Back, held 1 s the page goes. True = consumed. */
    private fun press(who: String, b: ThorButton, isDown: Boolean, first: Boolean, leaves: Boolean): Boolean {
        // dev build 16 (theme E): another controller's press takes the page; its other keys are ignored
        val next = PadTestRules.next(selected, who, isDown && first)
        // dev build 16 review (E-TEST-STALE-START-SELECT): the press that switches controllers does nothing else — its Start
        // / Select ran the PREVIOUS controller's Use / Remove (the page hadn't recomposed: the callbacks were still its)
        val switched = next != selected
        show(next)
        if (who != selected) return true
        // its B — "back" on every controller's own face (the reader sends it so: ○ on a DualSense) — held 1 s leaves. b19
        // (ps-F17): its Back too (still a way out; a tap says to hold B)
        if (leaves) {
            if (isDown && first) {
                main.removeCallbacks(leave); main.postDelayed(leave, PadTestRules.HOLD_MS); bDownAt = android.os.SystemClock.uptimeMillis()
            } else if (!isDown) {
                main.removeCallbacks(leave)
                // build 18 (C2, MORE-23): let go before the second — a tap, not the hold that leaves
                if (bDownAt > 0 && android.os.SystemClock.uptimeMillis() - bDownAt < PadTestRules.HOLD_MS) onTapB?.invoke()
                bDownAt = 0L
            }
        }
        if (isDown) {
            if (b !in down) down.add(b)
            // build 18 (C8, ps-F2): a press of Start / Select only lights its part — held 1 s, it acts (a key repeat
            // starts nothing again)
            if (first && !switched && (b == ThorButton.START || b == ThorButton.SELECT)) holdStart(b)
        } else {
            down.remove(b)
            if (b == holding) holdEnd()                                    // let go before its second: nothing happens
        }
        return true
    }
}

/** dev build 16 (theme E): whose readings the Test page shows. Pure (JVM test, PadTestRulesTest). */
internal object PadTestRules {
    /** [who] takes the page from [selected] only with a deliberate input: a press, a stick / trigger / D-pad past half — a
     *  stick drifting at rest, a release, a key repeat never do. */
    fun next(selected: String, who: String, deliberate: Boolean) = if (who != selected && deliberate) who else selected
    fun deliberate(vararg v: Float) = v.any { kotlin.math.abs(it) > 0.5f }
    /** dev build 17 (WP11): the controller the page opens on — the route's ([route]: "thor" or a key, from its page's "Test
     *  it"), else the one in hand ([hand]), else the Thor. One not connected: [gone]. */
    fun initial(route: String?, hand: String?): String = route?.takeIf { it.isNotEmpty() } ?: hand ?: PadTest.THOR
    /** b19 (device pass O2): the page names a controller ([route]: a key, from its page's "Test it" or a link) and shows it
     *  ([shown]), but it isn't connected ([live]: the keys connected now, not away): the page says it's gone, as its own page
     *  does. Never for the Thor's route, the one in hand's ("padtest") or a controller a press picked. */
    fun gone(route: String?, shown: String, live: Set<String>): Boolean =
        !route.isNullOrEmpty() && route != PadTest.THOR && shown == route && route !in live
    /** 1.5-beta2 (Q5): where Back goes from [route]'s gone page: its own page while it has one ([known]: the keys with a
     *  page, connected or away), else the Controller list at once (a link to a key never connected, or one forgotten: its
     *  page said "Controller gone" a second time). */
    fun goneBack(route: String, known: Collection<String>): String =
        if (route in known) HubPage.extpad(route) else HubPage.CONTROLLER
    /** 1.5-beta2 (b2d pass, FAIL 2, Q5.16): where Back goes up from [page] when it is a controller's Test page
     *  ("padtest:<key>"): [goneBack] ([known] read only then). Null for any other page, the Thor's Test page and the one in
     *  hand's ("padtest"): their parent, as before. Hub.kt's hubBack asks it, so B, the Back pill and the gone page's button
     *  take one way (B and the pill went to its page, a second "Controller gone", for a key with no page). */
    fun backFrom(page: String, known: () -> Collection<String>): String? =
        page.takeIf { it.startsWith("padtest:") }?.removePrefix("padtest:")?.takeIf { it.isNotEmpty() && it != PadTest.THOR }
            ?.let { goneBack(it, known()) }
    /** Build 18 (C2, VD-04): the drawing's parts lit by what is held now — [down] positions (a face button by where it is:
     *  A right "E", B bottom "S"…), the D-pad's arms from the hat ([hx] / [hy] past half) — as the setup wizard's targets
     *  (PadArt `targets`, the same on every drawing). */
    fun litTargets(down: Collection<ThorButton>, hx: Float, hy: Float): Set<String> = buildSet {
        for (b in down) when (b) {
            ThorButton.A -> add("E"); ThorButton.B -> add("S"); ThorButton.X -> add("N"); ThorButton.Y -> add("W")
            ThorButton.SELECT -> add("SE"); ThorButton.START -> add("ST"); ThorButton.HOME -> add("HO")
            ThorButton.UP -> add("DU"); ThorButton.DOWN -> add("DD"); ThorButton.LEFT -> add("DL"); ThorButton.RIGHT -> add("DR")
            ThorButton.L1, ThorButton.R1, ThorButton.L2, ThorButton.R2, ThorButton.L3, ThorButton.R3 -> add(b.name)
            else -> {}
        }
        if (hy < -0.5f) add("DU"); if (hy > 0.5f) add("DD"); if (hx < -0.5f) add("DL"); if (hx > 0.5f) add("DR")
    }
    /** b19 (ps-F17, replaces build 18's "PS and touchpad click are Wayfinder's" line): the tested controller's buttons that
     *  only light up here, their jobs held back, in [look]'s names: its Home while it holds Wayfinder's combos ([homeIsThors];
     *  given to the game, PS is the game's key and lights the drawing), its own Back and AYN buttons ([ownBack] / [ownAyn]:
     *  "touchpad click", "Mute"; null = none of its own, and a stand-in Home + Select lights as itself). The Thor ([thor]):
     *  all three (a controller playing as it: its names). */
    fun kept(look: PadLook, thor: Boolean, homeIsThors: Boolean, ownBack: String?, ownAyn: String?): List<Pair<ThorButton, String>> = buildList {
        // 1.5-beta2 (GitHub #137): Home moved to another button: "Home (touchpad click)", as Back and AYN
        if (thor || homeIsThors) add(ThorButton.HOME to (look.home?.let { job(ThorButton.HOME, it) } ?: Vocab.name(ThorButton.HOME, look)))
        (if (thor) Vocab.name(ThorButton.BACK, look) else ownBack)?.let { add(ThorButton.BACK to job(ThorButton.BACK, it)) }
        (if (thor) Vocab.name(ThorButton.AYN, look) else ownAyn)?.let { add(ThorButton.AYN to job(ThorButton.AYN, it)) }
    }
    /** "Back (touchpad click)", "AYN (Mute)": the job, then its own button's name; the Thor's say the job alone. */
    private fun job(b: ThorButton, own: String) = if (own == b.printed) own else "${b.printed} ($own)"
    /** b19 (ps-F17): the line about [kept] as (words, the button a name is; null = plain words): "Here PS, Back (touchpad
     *  click) and AYN (Mute) only light up." / "Here Home only lights up."; nothing kept: empty (no line). The page lights
     *  each name while it is held. */
    fun keptWords(kept: List<Pair<ThorButton, String>>): List<Pair<String, ThorButton?>> {
        if (kept.isEmpty()) return emptyList()
        val out = arrayListOf<Pair<String, ThorButton?>>("Here " to null)
        kept.forEachIndexed { i, (b, name) ->
            if (i > 0) out += (if (i == kept.lastIndex) " and " else ", ") to null
            out += name to b
        }
        out += (if (kept.size == 1) " only lights up." else " only light up.") to null
        return out
    }
    fun keptLine(kept: List<Pair<ThorButton, String>>): String = keptWords(kept).joinToString("") { it.first }
    /** b19 (ps-F17): the button a key the window got lights: a PS given to the game (MODE: "Its Home is the Thor's" off) its
     *  Home, the game's key with nothing to hold back; else the printed one ([printed], [ButtonEngine.printedButton]). */
    fun litButton(keyCode: Int, printed: ThorButton?): ThorButton? = if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) ThorButton.HOME else printed
    /** b19 (ps-F17): the tested controller's Home or Back is held ([down]): its reader's gate holds the sticks at rest
     *  meanwhile, so the drift reading waits (it read "0.0 % from centre"). */
    fun gateHeld(down: Collection<ThorButton>): Boolean = ThorButton.HOME in down || ThorButton.BACK in down

    /** b19 (ps-F17): the presses the Test page took, by (source as its presses count, button), while it is in front with the
     *  controller ([ButtonEngine.Host.testHolds]). Pure (PadTestRulesTest); the engine's, main thread. */
    class Taken {
        private val keys = HashSet<Pair<Int, ThorButton>>()
        /** A press of [b] on controller [src]: a first one ([first]) decides again (an entry a lost release left is dropped,
         *  so a later Home is never swallowed as a repeat) and is the page's only if [holds]; a key repeat follows its press.
         *  True = the page's. */
        fun press(src: Int, b: ThorButton, first: Boolean, holds: () -> Boolean): Boolean {
            val k = src to b
            if (!first) return k in keys
            keys.remove(k)
            return holds().also { if (it) keys.add(k) }
        }
        /** [b] of [src] let go: true = its press was the page's, whatever holds by now (a Home held as the page goes does
         *  nothing at its release; one pressed before the page came keeps its usual path). */
        fun release(src: Int, b: ThorButton): Boolean = keys.remove(src to b)
        /** [src]'s Home or Back is the page's now: what its reader withholds meanwhile is the page's too. Never the AYN
         *  button's (no reader withholds under it). */
        fun gates(src: Int): Boolean = (src to ThorButton.HOME) in keys || (src to ThorButton.BACK) in keys
        /** A press of [b] while [src]'s Home / Back is the page's ([gates]): the page's. True = new (said twice: lit once). */
        fun under(src: Int, b: ThorButton): Boolean = gates(src) && keys.add(src to b)
        /** Controller [src] went away ([ButtonEngine.gone]): what the page took of it goes. */
        fun forget(src: Int) { keys.removeAll { it.first == src } }
    }
    /** Build 18 (C2 addendum, VD-25): "The Thor" inside a sentence ("Testing the Thor"). */
    fun inSentence(name: String): String = if (name.startsWith("The ")) "the " + name.removePrefix("The ") else name

    /** Build 18 (C8, ps-F2): how long Start / Select are held to act — B's leave too. */
    const val HOLD_MS = 1000L
    /** A key pressed at [downAt] (0 = not down) and let go at [upAt] (0 = still held) has been held [HOLD_MS] by [now]. */
    fun holdFires(downAt: Long, upAt: Long, now: Long): Boolean =
        downAt > 0 && (if (upAt > 0) upAt else now) - downAt >= HOLD_MS

    /** Build 18 (C8): one key's hold — [down] its press (a key repeat changes nothing), [up] its release, [check] at a moment:
     *  true once, when it has been held [HOLD_MS]. Pure: the page's timer only asks it. */
    class Hold {
        private var downAt = 0L
        private var upAt = 0L
        private var fired = false
        fun down(now: Long, repeat: Int) { if (repeat == 0) { downAt = now; upAt = 0L; fired = false } }
        fun up(now: Long) { if (downAt > 0 && upAt == 0L) upAt = now }
        fun check(now: Long): Boolean = (!fired && holdFires(downAt, upAt, now)).also { if (it) fired = true }
    }
}

/** [key]: the controller to test — [PadTest.THOR] or a controller's key (dev build 17, WP11: "padtest:<thor|key>", its
 *  page's "Test it"); null = the one in hand ("padtest"). */
@Composable
fun ControllerTestPage(myDisplayId: Int, key: String?, onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val pv = ExtPadsCtl.version.intValue
    // dev build 17 (WP11): the controller is the route's (padtest:<thor|key>, its page's "Test it"), else the one in hand
    remember(key) { PadTest.show(PadTestRules.initial(key, ExtPadsCtl.keyOfSrc(ButtonNames.handSrc))) }
    val sel = PadTest.selected
    // b19 (device pass O2): its route names a controller that isn't connected (away, or gone): said, as on its own page (it
    // tested the Thor, unsaid). Before the page reads the pad: the Hub's buttons work here (B goes back). It comes back by
    // itself when the controller does ([pv] above)
    val live = ExtPadsCtl.pads.values.filter { !it.away && it.key.isNotEmpty() }.mapTo(HashSet()) { it.key }
    if (PadTestRules.gone(key, sel, live)) {
        if (BuildConfig.DEBUG) LaunchedEffect(Unit) { android.util.Log.d("ThorPadTest", "gone") }   // device test S16
        // 1.5-beta2 (Q5): one with no page (never connected, or forgotten) goes back to the controllers at once, the way
        // its page would have. 1.5-beta2 (b2d pass, FAIL 2): the way is hubBack's (PadTestRules.backFrom), the button's, B's
        // and the Back pill's alike (only the button set it, in its click: B and the pill found a second "Controller gone")
        val to = PadTestRules.goneBack(key.orEmpty(), ExtPadsCtl.byPlayer().map { it.key })
        if (to == HubPage.CONTROLLER) ControllerGone(myDisplayId, onBack)
        else ControllerGone(myDisplayId, onBack, backLabel = "Back")
        return
    }
    val isPad = sel != PadTest.THOR
    // device test S16 (debug log): whose readings (its kind only, never a name)
    if (BuildConfig.DEBUG) LaunchedEffect(sel) {
        android.util.Log.d("ThorPadTest", "who=${if (isPad) "pad" else "thor"} kind=${(if (isPad) ExtPadsCtl.kindOf(sel) else ControllerKind.THOR).name}")
    }
    // a held B leaves the way the pill does (dev build 16, WP7: B's path)
    val back = app.wayfinder.ui.rememberBackPress(onBack)
    PadTest.onLeave = back
    // start from centre: a stick at rest sends nothing, so values from the last visit would read as drift
    DisposableEffect(Unit) {
        PadTest.lx = 0f; PadTest.ly = 0f; PadTest.rx = 0f; PadTest.ry = 0f; PadTest.l2 = 0f; PadTest.r2 = 0f; PadTest.hx = 0f; PadTest.hy = 0f
        PadTest.active = true; PadTest.down.clear()
        onDispose { PadTest.active = false; PadTest.inFront = false; PadTest.onStart = null; PadTest.onSelect = null; PadTest.stop() }
    }
    // b19 (ps-F17): Home, Back and the AYN button are held back for the page only while it is in front: resumed is not enough
    // on two screens (both can be), its window must have the focus (the tour's rule); the service also asks for the
    // controller's screen ([PadTest.display])
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        // ON_PAUSE writes it at once: a page that stops recomposes no more (its SideEffect would not run)
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true
            else if (e == Lifecycle.Event.ON_PAUSE) { resumed = false; PadTest.inFront = false }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); PadTest.inFront = false }
    }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    SideEffect { PadTest.inFront = resumed && focused; PadTest.display = myDisplayId }
    // a controller a press picked went away: the Thor (the route's own: [PadTestRules.gone], above)
    LaunchedEffect(pv, sel) { if (isPad && ExtPadsCtl.pads.values.none { it.key == sel && !it.away }) PadTest.show(PadTest.THOR) }
    // its names (dev build 16: the controller tested — the Thor's, or what plays as it docked)
    val look = if (isPad) ExtPadsCtl.padLook(sel) else ButtonNames.lookOfSrc(0)
    // (dev build 18f2: its number from the one source the list and its page read — ExtPadsCtl.playerNo)
    val name = if (isPad) ExtPadsCtl.byPlayer().firstOrNull { it.key == sel }?.let { p -> p.name + (ExtPadsCtl.playerNo(ctx, p).now.takeIf { it > 0 }?.let { " · player $it" } ?: "") } ?: padNameOf(sel)
        else ExtPadsCtl.seatPadHere()?.let { "${it.name} (as the Thor's controller)" } ?: "The Thor"   // b18n: while connected
    // drift: the largest distance from centre over the last 3 s while the stick is "at rest" (< 30 %)
    val histL = remember { ArrayDeque<Pair<Long, Float>>() }
    val histR = remember { ArrayDeque<Pair<Long, Float>>() }
    var driftL by remember { mutableStateOf<Float?>(null) }
    var driftR by remember { mutableStateOf<Float?>(null) }
    var l2Max by remember { mutableFloatStateOf(0f) }
    var r2Max by remember { mutableFloatStateOf(0f) }
    // a reading only once the stick was pushed (> 30 %) and let go — at rest a stick sends nothing, so
    // before that "0 %" meant "not measured" and the page offered to REMOVE a deadzone (review 2026-09-25)
    val movedL = remember { booleanArrayOf(false) }
    val movedR = remember { booleanArrayOf(false) }
    // b19 (ps-F17): after a held Home / Back, each stick's last reading stays until its 3 s are measured again
    val keepL = remember { booleanArrayOf(false) }
    val keepR = remember { booleanArrayOf(false) }
    // dev build 16 (theme E): another controller tested — measured again from nothing
    LaunchedEffect(PadTest.epoch) {
        histL.clear(); histR.clear(); movedL[0] = false; movedR[0] = false; l2Max = 0f; r2Max = 0f; driftL = null; driftR = null
        keepL[0] = false; keepR[0] = false
    }
    LaunchedEffect(Unit) {
        var gated = false
        while (true) {
            val now = System.currentTimeMillis()
            // b19 (ps-F17): the tested controller's Home or Back held — its reader's gate holds the sticks at rest (they read
            // "0.0 % from centre"): the readings wait, and the 3 s start again at the release
            if (PadTestRules.gateHeld(PadTest.down)) { gated = true; delay(50); continue }
            if (gated) { gated = false; histL.clear(); histR.clear(); keepL[0] = true; keepR[0] = true }
            fun sample(h: ArrayDeque<Pair<Long, Float>>, moved: BooleanArray, x: Float, y: Float, side: Char, keep: BooleanArray, last: Float?): Float? {
                val r = sqrt(x * x + y * y)
                if (r >= 0.3f) {                                                // moved on purpose: start again
                    // device test S16 (debug log): the first push of each stick for this controller
                    if (!moved[0] && BuildConfig.DEBUG) android.util.Log.d("ThorPadTest", "moved $side")
                    moved[0] = true; h.clear(); keep[0] = false; return null
                }
                if (!moved[0]) return null
                h.addLast(now to r)
                while (h.isNotEmpty() && now - h.first().first > 3000) h.removeFirst()
                if (h.size >= 55 && now - h.first().first >= 2700) { keep[0] = false; return h.maxOf { it.second } * 100f }
                return if (keep[0]) last else null
            }
            driftL = sample(histL, movedL, PadTest.lx, PadTest.ly, 'L', keepL, driftL)
            driftR = sample(histR, movedR, PadTest.rx, PadTest.ry, 'R', keepR, driftR)
            l2Max = maxOf(l2Max, PadTest.l2); r2Max = maxOf(r2Max, PadTest.r2)
            delay(50)
        }
    }
    fun suggest(d: Float?) = d?.let { if (it < 1.5f) 0 else listOf(4, 6, 8, 10, 12, 15, 20, 25).firstOrNull { s -> s >= ceil(it * 1.5f) + 2 } ?: 25 }
    val sugL = suggest(driftL); val sugR = suggest(driftR)
    // dev build 16 (theme E): the deadzone of the controller measured — the Thor's every game (it reaches the controllers
    // without one of their own), or a controller's own (ExtPadsCtl.padSticks: every game, in place of the Thor's)
    val defaults = AppSettings.stickDefaults
    val own = if (isPad) ExtPadsCtl.padSticks(ctx, sel) else null
    val shape = own?.let { (l, r) -> defaults.copy(stickL = defaults.stickL.copy(dead = l), stickR = defaults.stickR.copy(dead = r)) } ?: defaults
    val canUse = sugL != null && sugR != null && (sugL != shape.stickL.dead || sugR != shape.stickR.dead)
    // build 18 (C8, ps-F2): the Thor's Remove is about its deadzone only — a curve or an outer range of every game stays
    val usualDead = StickShape().dead
    val canRemove = if (isPad) own != null else defaults.stickL.dead != usualDead || defaults.stickR.dead != usualDead
    // dev build 16 review (E-TEST-STALE-START-SELECT): a callback of an earlier frame acts only on the controller it measured
    fun use() {
        if (PadTest.selected != sel) return
        if (sugL == null || sugR == null) return
        if (isPad) ExtPadsCtl.setPadSticks(ctx, sel, sugL to sugR)
        else {
            AppSettings.setStickDefaultsTo(defaults.copy(stickL = defaults.stickL.copy(dead = sugL), stickR = defaults.stickR.copy(dead = sugR)))
            ExtPadsCtl.sendDzBase(ctx)
        }
        // build 18 (C8): it says what changed (a hold on the controller shows nothing else)
        Overlays.pill("Deadzone $sugL / $sugR % for every game", CueOrigin.Window(myDisplayId))
    }
    fun remove() {
        if (PadTest.selected != sel) return
        if (isPad) ExtPadsCtl.setPadSticks(ctx, sel, null)
        else {
            // build 18 (C8, ps-F2): only the deadzone — it reset the whole every-game shape of both sticks (curve, outer range)
            AppSettings.setStickDefaultsTo(defaults.copy(stickL = defaults.stickL.copy(dead = usualDead), stickR = defaults.stickR.copy(dead = usualDead)))
            ExtPadsCtl.sendDzBase(ctx)
        }
        // a controller without its own deadzone uses the Thor's (the card says "— the Thor's")
        Overlays.pill(if (isPad) "Deadzone back to the Thor's" else "Deadzone back to the usual", CueOrigin.Window(myDisplayId))
    }
    PadTest.onStart = if (canUse) ::use else null
    PadTest.onSelect = if (canRemove) ::remove else null
    // build 18 (C2, MORE-23): a tap of B does nothing here (the page reads every button) — say to hold it, at most every 3 s
    val tapPillAt = remember { longArrayOf(0L) }
    PadTest.onTapB = {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - tapPillAt[0] >= 3000) {
            tapPillAt[0] = now
            Overlays.pill("Hold ${Say.plain(Say.keyMenu(ThorButton.B, look), look)} to leave", CueOrigin.Window(myDisplayId))
        }
    }
    // touch: which controller (its own buttons feed the page — a press on it picks it too). dev build 17 (WP8): a choice
    val pads = ExtPadsCtl.byPlayer().filter { !it.away }
    val testedPad = if (isPad) ExtPadsCtl.pads.values.firstOrNull { it.key == sel } else null
    // b19 (ps-F17): what only lights up here (its jobs held back), in the tested controller's names: its Home while it holds
    // the combos, its own Back and AYN buttons (a stand-in lights as itself); the Thor all three
    val spares = testedPad?.let { ExtPadsCtl.spares(ctx, it) }
    val kept = PadTestRules.kept(look, thor = !isPad, homeIsThors = isPad && ExtPadsCtl.homeIsThors(ctx, sel),
        ownBack = (spares?.back as? Say.Spare.Own)?.name, ownAyn = (spares?.ayn as? Say.Spare.Own)?.name)
    val shaped = !shape.stickL.isDefault || !shape.stickR.isDefault

    CompositionLocalProvider(LocalPadLook provides look) {
    app.wayfinder.ui.GlassScreen(span = if (myDisplayId == 0) app.wayfinder.ui.AuroraSpan.TOP else app.wayfinder.ui.AuroraSpan.BOTTOM) {
        // scrolls by touch only (the page reads every button): build 18 (C2, CTL-02) — the deadzone card right under the
        // header, so it is in view on the top screen without scrolling (it was under the sticks, out of the controller's reach)
        Column(Modifier.fillMaxWidth()
            // under the status bar like every page (QA2: the title was cut off)
            .then(Modifier.statusBarsPadding().navigationBarsPadding())
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            // build 18 (A11, C2): 16 dp on top like SubPage — the Back pill at one height on every page
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // dev build 16 (WP7): the pill is B's path. dev build 17 (WP8): the shared page header. Build 18 (C2, VD-25 / VD-26):
            // which one, in a sentence, and how to leave (a hold: the pill says it too)
            app.wayfinder.ui.PageHeader(NavPages.title(HubPage.PADTEST), back, backLabel = "Hold to leave",
                subtitle = "Testing ${PadTestRules.inSentence(name)}" +
                    (if (pads.isNotEmpty()) " — a press on another controller tests that one" else ". Move both sticks a little, let go, wait 3 s") +
                    ". Hold ${Say.keyMenu(ThorButton.B, look)} to leave.")
            if (pads.isNotEmpty()) {
                val choices = listOf(PadTest.THOR to (ExtPadsCtl.seatPadHere()?.let { "${it.name} (as the Thor's)" } ?: "The Thor")) +
                    pads.map { it.key to it.name }
                app.wayfinder.ui.GlassSegmentedControl(choices.map { it.second }, choices.indexOfFirst { it.first == sel }, Modifier.fillMaxWidth(),
                    collapse = false) { PadTest.show(choices[it].first) }
            }
            // the fix, for every game (a game's own Game controls values still win)
            GlassPanel(Modifier.fillMaxWidth(), radius = app.wayfinder.ui.Radii.Card) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text((if (isPad) "Deadzone for ${testedPad?.name ?: padNameOf(sel)}, every game: "
                            else "Deadzone for every game: ") + "left ${shape.stickL.dead} %, right ${shape.stickR.dead} %" +
                            (if (isPad && own == null) " — the Thor's" else ""),
                            color = g.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            sugL == null || sugR == null -> "Measuring — move both sticks a little, let go, and leave them alone…"
                            sugL == 0 && sugR == 0 -> "No drift worth fixing: both sticks rest at the centre."
                            else -> "Suggested: left $sugL %, right $sugR % — just enough to hide the drift."
                        }, color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    // dev build 16 (theme F): Start / Select as the tested controller calls them (Options / Create…). Build 18
                    // (C8, ps-F2): held, not pressed — the badge of the tested controller (the page provides its look); while
                    // one is held, its button says to keep going (same row: the card never grows)
                    val holding = PadTest.holding
                    if (canUse) app.wayfinder.ui.GlassButton(if (holding == ThorButton.START) "Keep holding…"
                        else "Use the suggestion · hold ${Say.key(ThorButton.START)}", ::use, kind = app.wayfinder.ui.ButtonKind.PRIMARY)
                    // (the suggestion is one hold away: not a two-press delete)
                    if (canRemove) app.wayfinder.ui.GlassButton(if (holding == ThorButton.SELECT) "Keep holding…"
                        else "Remove · hold ${Say.key(ThorButton.SELECT)}", ::remove, kind = app.wayfinder.ui.ButtonKind.QUIET)
                }
            }
            // build 18 (C2, VD-04 / CTL-19): [left stick] [the drawing + the two trigger bars] [right stick]
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                StickCard("Left stick", PadTest.lx, PadTest.ly, driftL, shape.stickL, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TriggerBar("L2", PadTest.l2, l2Max)
                    TriggerBar("R2", PadTest.r2, r2Max)
                    TestDrawing(look, Modifier.fillMaxWidth().height(110.dp))
                    KeptLine(kept)
                    // (VD-25): the dots' legend only when a game gets something else than the stick (a deadzone, a curve)
                    if (shaped) app.wayfinder.ui.Caption("Grey: the stick · blue: what a game gets")
                }
                StickCard("Right stick", PadTest.rx, PadTest.ry, driftR, shape.stickR, Modifier.weight(1f))
            }
        }
    }
    }
}

/** Build 18 (C2, VD-04): the tested controller's own drawing, the parts held now lit. Round-2 review: its own scope — the
 *  page's scope reads the sticks and triggers (every motion event recomposes it), and the drawing lays out all its labels
 *  on each draw. Stable parameters only ([look] a data class, a Modifier chain), so the page's recompositions skip it; the
 *  lit parts are derived (structural equality), so it redraws only when the set of held parts changes. */
@Composable
private fun TestDrawing(look: PadLook, modifier: Modifier) {
    val art = remember(look) { PadArts.full(look) }
    val lit by remember { derivedStateOf(structuralEqualityPolicy()) { PadTestRules.litTargets(PadTest.down, PadTest.hx, PadTest.hy) } }
    PadArtCanvas(art, modifier, lit = lit, look = look)
}

/** b19 (ps-F17): the line under the drawing, what only lights up here ([PadTestRules.keptWords]), each name lit while held: an
 *  accent pill behind it, its words onAccent (a key hint's colours) — the drawing has no part for some (the Thor's AYN, an
 *  Xbox pad's Share). A sentence that wraps (no row of chips): the middle column is narrow on the bottom screen. The lit set
 *  is derived, as the drawing's. */
@Composable
private fun KeptLine(kept: List<Pair<ThorButton, String>>) {
    val words = remember(kept) { PadTestRules.keptWords(kept) }
    if (words.isEmpty()) return
    val g = LocalGlass.current
    val lit by remember(kept) { derivedStateOf(structuralEqualityPolicy()) { kept.map { it.first }.filter { it in PadTest.down }.toSet() } }
    val spans = ArrayList<IntRange>()
    val text = buildAnnotatedString {
        for ((t, b) in words) {
            val at = length
            if (b != null && b in lit) { withStyle(SpanStyle(color = g.onAccent)) { append(t) }; spans += at until length } else append(t)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, Modifier.fillMaxWidth().drawBehind {
        val l = layout ?: return@drawBehind
        for (r in spans) {
            val path = l.getPathForRange(r.first, r.last + 1)
            drawPath(path, g.accent)
            drawPath(path, g.accent, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))        // its rounded edge
        }
    }, color = g.textTertiary, style = MaterialTheme.typography.bodySmall, onTextLayout = { layout = it })
}

@Composable
private fun StickCard(name: String, x: Float, y: Float, drift: Float?, shape: StickShape, modifier: Modifier) {
    val g = LocalGlass.current
    // what a game gets: the same maths as the engine's wf_shape (fx/wfmap.h), with the all-games shape
    val (gx, gy) = shaped(x, y, shape)
    val accent = g.accent; val ring = g.textTertiary; val warn = g.warn
    val dead = shape.dead
    GlassPanel(modifier, radius = app.wayfinder.ui.Radii.Card) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // build 18 (C2): the stick's name and where it is on one line — what a game gets beside it when that differs
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = g.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${"%.0f".format(sqrt(x * x + y * y) * 100)} %" +
                    (if (!shape.isDefault) "  →  game ${"%.0f".format(sqrt(gx * gx + gy * gy) * 100)} %" else ""),
                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Canvas(Modifier.size(150.dp)) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 - 6f
                drawCircle(ring, r, c, style = Stroke(3f))
                if (dead > 0) drawCircle(warn.copy(alpha = 0.35f), r * dead / 100f, c)          // the deadzone now
                drawLine(ring.copy(alpha = 0.4f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
                drawLine(ring.copy(alpha = 0.4f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
                // build 18 (C2 addendum, VD-25): the stick as a hollow ring, so what a game gets stays visible on it
                drawCircle(ring, 11f, Offset(c.x + x.coerceIn(-1f, 1f) * r, c.y + y.coerceIn(-1f, 1f) * r), style = Stroke(3f))   // raw
                drawCircle(accent, 9f, Offset(c.x + gx.coerceIn(-1f, 1f) * r, c.y + gy.coerceIn(-1f, 1f) * r))   // the game
            }
            Text(drift?.let { "At rest: ${"%.1f".format(it)} % from centre" } ?: "At rest: move it, let go, leave it 3 s…",
                color = if ((drift ?: 0f) > 3f) warn else g.textSecondary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun TriggerBar(name: String, v: Float, max: Float) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
        Box(Modifier.weight(1f).height(16.dp).background(g.textTertiary.copy(alpha = 0.2f), app.wayfinder.ui.CapsuleShape)) {
            Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(16.dp).background(g.accent, app.wayfinder.ui.CapsuleShape))
        }
        Text("${(v * 100).toInt()} % · max ${(max * 100).toInt()} %", color = g.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp))
    }
}

/** A stick position through a shape — a copy of wf_shape (fx/wfmap.h) for the preview. */
internal fun shaped(x: Float, y: Float, s: StickShape): Pair<Float, Float> {
    val r = sqrt(x * x + y * y)
    if (r < 1e-6f || s.isDefault) return x to y
    val lo = s.dead / 100f; val hi = s.full / 100f
    var t = if (r <= lo) 0f else if (r >= hi) 1f else (r - lo) / (hi - lo)
    if (s.curve == 1) t *= t else if (s.curve == 2) t = sqrt(t)
    val k = t / r
    return x * k to y * k
}
