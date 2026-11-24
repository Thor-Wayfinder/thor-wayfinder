package app.wayfinder.deck

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wayfinder.ButtonEngine
import app.wayfinder.ButtonNames
import app.wayfinder.DeckWords
import app.wayfinder.ExtEngine
import app.wayfinder.ExtPadsCtl
import app.wayfinder.PadLayerCtl
import app.wayfinder.Say
import app.wayfinder.ThorButton
import app.wayfinder.ThorInput
import app.wayfinder.ui.PadNav

/** b19 (H1a): the rules of Keyboard & mouse holding the controller ([DeckHold]) — pure (DeckHoldRulesTest). */
internal object DeckHoldRules {
    /** The app renews its hold this often; wfpad gives it back by itself after 6 s without a renewal (fx/wfmap.h WF_HOLD_MS). */
    const val RENEW_MS = 2000L
    /** A direction held moves the light at once, again after this long, then every [REPEAT_MS] (ui.PadNav's timing). */
    const val REPEAT_FIRST_MS = 400L
    const val REPEAT_MS = 120L

    /** Why the deck can't take the controller (it stays touch-only, and says why: [DeckWords.cant]). */
    enum class CantHold { GUIDE, OWN_PLAYER, WAYFINDER, LAYER_OFF }

    /** Can Keyboard & mouse take the controller: not the guide alone ([guideOnly]); the controller asking is the Thor's or one
     *  playing as it ([src] 0: the input layer's copy is what it holds; a controller that is its own player keeps the game);
     *  its keys go to another app than Wayfinder ([servesWayfinder]: the Hub works with the controller already; b2 review F1:
     *  so does any Wayfinder window in front there, [ownInFront]); and Game
     *  controls run for that app ([layerActive]: the hold is the input layer's gate). Null = it can. */
    fun canHold(layerActive: Boolean, src: Int, servesWayfinder: Boolean, guideOnly: Boolean): CantHold? = when {
        guideOnly -> CantHold.GUIDE
        src > 0 -> CantHold.OWN_PLAYER
        servesWayfinder -> CantHold.WAYFINDER
        !layerActive -> CantHold.LAYER_OFF
        else -> null
    }

    /** b19 fix (H1a, review): Keyboard & mouse [showing] on screen [deck] covers [screen]: all of it (not [beside] a game on the
     *  game's own screen). Nothing under it is seen: a send there is refused (ForegroundAppService.deckInTheWay), Game controls
     *  doesn't open there beside the game, and a Wayfinder window that comes up there closes it (it was under it, unseen, and
     *  took the controller). */
    fun covers(showing: Boolean, beside: Boolean, deck: Int?, screen: Int?): Boolean =
        showing && !beside && screen != null && deck == screen

    /** Why a hold ended ([DeckHold.end]). [pill]: it ended by itself, so the pill says the controller is back with the game.
     *  b2 (switch): [SWITCH] = the footer's switch turned off (a tap, or A on it), the switch shows it (no pill, as B). */
    enum class End(val pill: Boolean = false) {
        BACK, SENT, HOME, CLOSED, FOCUS, KEYBOARD, SCREEN_OFF, SWITCH, LAYER(pill = true)
    }

    /** b2 (O2): what Keyboard & mouse's keys go to ("Sending to"), read on its game's screen, one rule for the deck's open and
     *  its follow (ForegroundAppService.deckContext / deckFollowRun). [hub]: Wayfinder's Hub is in front there (the app's
     *  own word, MainActivity.hubOn: the window event and the window list can come later) = Wayfinder ([own]). Else the
     *  screen's front app window [top] (null: unread), or [tracked] (the app the service tracks there, never Wayfinder) for
     *  another Wayfinder window (Game controls, the combos sheet: it belongs to the app under it) or an unread window. A
     *  launcher ([launchers]) = null, "the other screen". The deck opened over Cocoon read "Sending to: Cocoon", then "the
     *  other screen" at the first window change (the open skipped this launcher rule); with the Hub opened on its game's
     *  screen it kept "Sending to: RetroArch" (the follow read the window before the app knew its Hub was up, and nothing
     *  read it again). */
    fun target(hub: Boolean, top: String?, tracked: String?, own: String, launchers: Collection<String>): String? =
        front(hub, top, tracked, own)?.takeIf { it == own || it !in launchers }

    /** b2 (review F3): the app in front on the deck's game screen as [target] reads it, a launcher kept: the app the deck's pad
     *  is remembered for ([DeckState.padKey]). With the launcher rule at the open, a pad picked over a front-end set as the
     *  home app (ES-DE, Daijisho) was no longer remembered: it opened on the default pad each time. */
    fun front(hub: Boolean, top: String?, tracked: String?, own: String): String? =
        if (hub) own else (top?.takeIf { it != own } ?: tracked)?.takeIf { it != own }

    /** 1.5-beta2 (GitHub #142): the deck's game screen. The controller's screen [ctl] shows a launcher ([ctlFront], [front] read
     *  there, in [launchers]) and the other screen [other] an app ([otherFront]: not a launcher, not Wayfinder [own]): that app
     *  is the game, so the deck goes over the launcher and its keys go to the app. A game opened on the top screen from the
     *  bottom launcher takes Android's focus (and the controller) without going through Wayfinder, whose lock still said
     *  bottom: the deck opened over the game, "Sending to: the other screen" (the launcher; 1.4.1 named it). Anything else
     *  (the Hub or nothing read there, one screen, an app on both, a launcher on both): [ctl], as before. */
    fun gameScreen(ctl: Int, other: Int?, ctlFront: String?, otherFront: String?, own: String, launchers: Collection<String>): Int =
        if (other != null && other != ctl && ctlFront != null && ctlFront != own && ctlFront in launchers &&
            otherFront != null && otherFront != own && otherFront !in launchers) other else ctl

    /** b2 (O2): the deck keeps the app it serves ([target] not read): a Wayfinder window that isn't the Hub is in front on its
     *  game's screen (build 18 review: Game controls over the game kept the game; retargeted, B in it closed the deck).
     *  b2 (review): not when it serves Wayfinder itself ([servesOwn]: the Hub turned into Game controls in place, "All apps"
     *  then B): Game controls belongs to the game under it, so the deck goes back to that game. And the guide alone
     *  ([guideOnly]) keeps its game when the Hub comes up there: Wayfinder is no game with a guide, and the pane rebuilt
     *  the game's guide when the Hub went (the page read and its scroll lost). */
    fun keepsTarget(hub: Boolean, top: String?, own: String, servesOwn: Boolean = false, guideOnly: Boolean = false): Boolean =
        if (hub) guideOnly else top == own && !servesOwn

    /** b2 (review F1): a Wayfinder window is in front on the deck's game screen ([front]: the app's own word, MainActivity.frontOn;
     *  or the window read, [top] = [own]: the Guide page too): it works with the controller, so the deck can't take it
     *  ([canHold]'s servesWayfinder). Game controls over the game read "Sending to: <the game>" at the open, and the deck took
     *  the controller from it: its keys went into Game controls' rows. */
    fun ownInFront(front: Boolean, top: String?, own: String): Boolean = front || top == own

    /** A line of the input layer's status ([PadLayerCtl.onStatus]): a wfpad started or stopped — a hold went with it (a new
     *  one starts with none). */
    fun layerReset(line: String): Boolean =
        line.startsWith("starting ") || line.startsWith("stopped") || line == "off" || line == "emergency-off"

    /** What a button does while the deck holds the controller, by its menu job (the face style's: [ButtonNames.menuPos]).
     *  1.5-beta2 (Q2, GitHub D#127): [SHIFT], L2 = the pad's Shift key, held while L2 is held ([DeckState.shiftHold]). */
    enum class Job { PRESS, BACK, PREV, NEXT, DELETE, SPACE, ENTER, ESCAPE, SHIFT }

    fun jobOf(menu: ThorButton): Job? = when (menu) {
        ThorButton.A -> Job.PRESS
        ThorButton.B -> Job.BACK
        ThorButton.L1 -> Job.PREV
        ThorButton.R1 -> Job.NEXT
        ThorButton.X -> Job.DELETE
        ThorButton.Y -> Job.SPACE
        ThorButton.START -> Job.ENTER
        ThorButton.SELECT -> Job.ESCAPE
        ThorButton.L2 -> Job.SHIFT
        else -> null
    }

    /** The menu button doing a key job — its badge on that key ([jobKey]). */
    val BADGES = listOf(Job.DELETE to ThorButton.X, Job.SPACE to ThorButton.Y, Job.ENTER to ThorButton.START, Job.ESCAPE to ThorButton.SELECT,
        Job.SHIFT to ThorButton.L2)

    /** 1.5-beta2 (Q2): what L2's release does to the deck's Shift ([DeckState.shiftLet]), [latch] being its latch then and
     *  [tap] the release a tap's (Wayfinder Keyboard's rule, KeyboardRules.shiftTap). */
    enum class ShiftUp { LATCH, TAP, LET_GO, KEEP }

    fun shiftUp(latch: Latch, tap: Boolean): ShiftUp = when {
        tap && latch == Latch.OFF -> ShiftUp.LATCH      // a tap of the Shift key: for the next key (it went down at the press)
        tap -> ShiftUp.TAP                              // the Shift key's tap on a latched Shift: twice quickly locked, else off
        latch == Latch.OFF -> ShiftUp.LET_GO            // a hold: the Shift L2 pressed comes up
        else -> ShiftUp.KEEP                            // a hold over a latched Shift: it stays as it was
    }

    /** The key codes a key job presses: found on the pad by code, never a list per pad. */
    fun codesOf(job: Job): Set<Int> = when (job) {
        Job.DELETE -> setOf(KeyEvent.KEYCODE_DEL)
        Job.SPACE -> setOf(KeyEvent.KEYCODE_SPACE)
        Job.ENTER -> setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        Job.ESCAPE -> setOf(KeyEvent.KEYCODE_ESCAPE)
        else -> emptySet()
    }

    /** The key a key job presses on the pad drawn ([rows]): the first plain key (no modifier, snippet or action) with one of
     *  its codes, as (row, column); null = none there (the button does nothing on that pad). */
    fun jobKey(rows: List<List<DeckKey>>, job: Job): Pair<Int, Int>? {
        // 1.5-beta2 (Q2): Shift is the pad's Shift key, a modifier (found by it, not by a list per pad)
        if (job == Job.SHIFT) {
            rows.forEachIndexed { r, row -> row.forEachIndexed { c, k -> if (k.modifier == Modifier.SHIFT && k.label.isNotEmpty()) return r to c } }
            return null
        }
        val codes = codesOf(job)
        if (codes.isEmpty()) return null
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, k -> if (k.code in codes && k.modifier == null && k.text == null && k.action == DeckAction.NONE && k.label.isNotEmpty()) return r to c }
        }
        return null
    }

    /** Where a withheld event of the Thor's copy goes ([Router.route]). */
    enum class Route { DECK, TODAY }

    /**
     * b19 (H1a): the withheld events of the Thor's copy ("J" lines), in the stream's order (the helper's reader thread). While
     * the deck holds the controller, a press goes to the deck — but not under Home or Back (a combo's, as before: [TODAY]),
     * known from their own echo in the same stream (fx/wfmap.h wf_hold), so a press that reaches the app before Home's own key
     * still counts as Home's. A release follows its press (the ledger), after the hold too: the B that gave the controller back
     * lets go here. Home / Back's echo is the deck's (wfpad sends it only while it holds). The D-pad (HAT axes) as buttons, a
     * direction held from its push to its centre; the sticks always go their usual way (the deck reads its left one: [free]).
     */
    class Router {
        @Volatile var held = false
            private set
        private var home = false
        private var back = false
        private val taken = HashSet<Long>()

        /** The hold starts: [homeHeld] what the app knows now (wfpad's echo at the start says it again, in the stream). A
         *  new hold starts with an empty ledger: a release the last one never saw is not waited for. */
        @Synchronized fun start(homeHeld: Boolean) { held = true; home = homeHeld; back = false; taken.clear() }
        /** The hold ends: what the deck took still lets go here. */
        @Synchronized fun end() { held = false; home = false; back = false }
        /** b19 fix (H1a): the wfpad went (a new one, [layerReset]): no release of what the deck took will ever come from it
         *  (a new wfpad state has no gate), so the ledger goes too. Else the next press of that button, under Home, was the
         *  deck's: its combo never fired, and Home's release counted as a Home tap. */
        @Synchronized fun reset() { end(); taken.clear() }
        /** The deck reads the left stick now: it holds, and neither Home nor Back is down. */
        @Synchronized fun free(): Boolean = held && !home && !back

        @Synchronized fun route(type: Int, code: Int, value: Int): Route {
            if (type == ThorInput.EV_KEY && (code == ThorInput.KEY_HOME || code == ThorInput.KEY_BACK)) {
                if (value != 2) { if (code == ThorInput.KEY_HOME) home = value != 0 else back = value != 0 }
                return Route.DECK
            }
            val hat = type == ThorInput.EV_ABS && (code == ThorInput.ABS_HAT_X || code == ThorInput.ABS_HAT_Y)
            if (type != ThorInput.EV_KEY && !hat) return Route.TODAY
            val k = (type.toLong() shl 16) or (code.toLong() and 0xffff)
            val on = value != 0
            if (k in taken) {
                if (!on) { taken.remove(k); return Route.DECK }
                // b19 fix (H1a): a new press of a key still in the ledger (or any press once the hold is over): its release never
                // came (a resync, a pad made again). The entry goes and the press is routed as a new one. A repeat stays the
                // deck's, and so does a D-pad direction changed during the hold
                if (value == 2 || (held && type != ThorInput.EV_KEY)) return Route.DECK
                taken.remove(k)
            }
            if (on && value != 2 && held && !home && !back) { taken += k; return Route.DECK }
            return Route.TODAY
        }
    }

    /** b19 fix (H1a): what A pressed while the deck holds the controller: a key (the key itself: a pad changed under A
     *  still lets go of it) or another cell (a mouse button). It lets go at A's release, wherever the light went: never the
     *  cell lit at the release (W held stayed down in the game when the light moved to S before A was let go). Main thread. */
    class APress {
        var key: DeckKey? = null
            private set
        var cell: String? = null
            private set
        /** A pressed on the lit cell [id], whose key is [k] (null: a cell that isn't a key). */
        fun down(id: String, k: DeckKey?) { if (k != null) key = k else cell = id }
        /** A let go (or the hold ended): what to let go of, the key and the cell A pressed. Nothing is held after. */
        fun up(): Pair<DeckKey?, String?> = (key to cell).also { key = null; cell = null }
    }
}

/**
 * b19 (H1a): Keyboard & mouse holds the controller — the input layer's gate held by the app ("G h", fx/wfmap.h wf_hold), so
 * the game keeps its window focus, its screen and its controller (the same device: what the hand held is let go and centred
 * for it, and comes back at the give-back), and every press of the Thor's controller (or one playing as it) comes here. The
 * D-pad and the left stick light a cell ([DeckNav]); A presses the lit one (held while A is held: a key, a mouse button);
 * B or Back gives the controller back (the deck stays); L1 / R1 change pads; X, Y, Start and Select press the pad's ⌫,
 * Space, Enter and Esc when it has them ([DeckHoldRules.jobKey]). Home stays Wayfinder's (its combos; a tap gives the
 * controller back first). One [end] for every way out. Main thread, but [takes] (the helper's reader thread, in order).
 */
internal object DeckHold {
    private const val TAG = "ThorDeck"
    private val main = Handler(Looper.getMainLooper())
    private val router = DeckHoldRules.Router()

    /** Drawn: the deck holds the controller (the lit cell, the badges, the header's words). */
    var held by mutableStateOf(false)
        private set
    /** The lit cell ([DeckNav.Cell.id]); null = none. */
    var lit by mutableStateOf<String?>(null)
        private set
    /** A pill (vibrating) on screen [display] — the service's ([Overlays][app.wayfinder.Overlays]). */
    @Volatile var say: (text: String, display: Int?) -> Unit = { _, _ -> }

    private var deck: InputDeckOverlay? = null
    private var rows: List<List<DeckNav.Cell>> = emptyList()
    private var at = DeckNav.Lit(0, 0)
    /** What A pressed ([DeckHoldRules.APress]): it lets go at A's release, wherever the light went. */
    private val aPress = DeckHoldRules.APress()
    private val jobKeys = HashMap<DeckHoldRules.Job, DeckKey>()
    private var backTaken = false
    private var hatX = 0; private var hatY = 0; private var stickX = 0; private var stickY = 0
    private var dir = 0
    /** The pad's cell the light left for the header or the footer: back into the pad, it lands there ([DeckNav.move]). */
    private var left: String? = null

    private val renew = object : Runnable {
        override fun run() {
            if (!held) return
            if (!PadLayerCtl.active) { end(DeckHoldRules.End.LAYER); return }
            PadLayerCtl.hold(true)
            main.postDelayed(this, DeckHoldRules.RENEW_MS)
        }
    }
    private val repeat = object : Runnable {
        override fun run() { if (!held || dir == 0) return; step(); main.postDelayed(this, DeckHoldRules.REPEAT_MS) }
    }

    /** The deck [d] takes the controller (its chip, a send toward its screen from controller [src], [srcName] its name):
     *  true = it holds it now (said on the deck's screen), false = it can't (its reason said, [DeckWords.cant]). */
    fun start(d: InputDeckOverlay, src: Int = 0, srcName: String? = null): Boolean {
        val look = ButtonNames.lookOfSrc(0)
        val why = DeckHoldRules.canHold(PadLayerCtl.active, src, d.servesWayfinder || d.ownInFront, d.guideOnly)   // b2 (review F1)
        if (why != null) { say(DeckWords.cant(why, d.appName, srcName), d.displayId); return false }
        if (held && deck === d) { say(DeckWords.took(Say.menu(ThorButton.B, look)), d.displayId); return true }
        if (held) end(DeckHoldRules.End.CLOSED)
        deck = d
        router.start(ButtonEngine.systemHeld)
        held = true
        PadLayerCtl.hold(true)
        // what the app plays for the game on the Thor's controller (a button's keys, a macro, the mouse) lets go, as at Home
        ExtPadsCtl.rawSrcs(0).forEach { ExtEngine.releaseAll(it) }
        rows = d.navRows(); at = DeckNav.first(rows, DeckNav.tab(d.padId)); lit = rows.getOrNull(at.row)?.getOrNull(at.col)?.id; left = null
        main.removeCallbacks(renew); main.postDelayed(renew, DeckHoldRules.RENEW_MS)
        say(DeckWords.took(Say.menu(ThorButton.B, look)), d.displayId)
        Log.d(TAG, "the deck holds the controller")
        return true
    }

    /** b2 (switch): the footer's switch "Use the controller here" on deck [d] (a tap, or A on its cell): on, the controller
     *  goes back to the game ([DeckHoldRules.End.SWITCH], as B); off, the deck takes it ([start]: its reason said when it
     *  can't). The switch is [held]: off each time the deck opens (its show and its close end the hold, [closing]). */
    fun toggle(d: InputDeckOverlay) {
        if (held && deck === d) end(DeckHoldRules.End.SWITCH) else start(d)
    }

    /** The controller back to the game ([why]): the hold's release first, then what the deck held (A's key, a job's key). Any
     *  thread (posted to the main one). */
    fun end(why: DeckHoldRules.End) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { end(why) }; return }
        if (!held) return
        PadLayerCtl.hold(false)
        held = false; router.end()
        main.removeCallbacks(renew); stopNav()
        val d = deck
        aPress.up().let { (k, c) -> k?.let { d?.pressKey(it, false) }; c?.let { d?.runCell(it, false) } }
        jobKeys.values.forEach { d?.pressKey(it, false) }; jobKeys.clear()
        d?.shiftHold(false, null)                     // 1.5-beta2 (Q2): a Shift L2 holds comes up (L2's release may never come)
        lit = null
        if (why.pill) say(DeckWords.BACK_WITH_GAME, d?.displayId)
        Log.d(TAG, "the controller is back with the game ($why)")
    }

    /** The deck [d] goes (any way): its hold ends first ([InputDeckOverlay.hide]). */
    internal fun closing(d: InputDeckOverlay) { if (deck === d) { end(DeckHoldRules.End.CLOSED); deck = null } }

    /** b19 fix (H1a): a wfpad started or stopped ([PadLayerCtl.onHoldGone], [DeckHoldRules.layerReset]): the router's
     *  ledger goes, held or not (no release of what the deck took will come from the new wfpad), then the hold ends with
     *  its pill. The reader's thread, in the stream's order (the ledger's own), the rest posted to the main one. */
    fun layerGone() {
        router.reset()
        end(DeckHoldRules.End.LAYER)
    }

    /**
     * A withheld event of the Thor's copy ("J" line, the helper's reader thread): true = the deck's (taken, or Home / Back's
     * echo); false = its usual way (combos, flicks: [DeckHoldRules.Router]). [b]: the printed button of a key.
     */
    fun takes(type: Int, code: Int, value: Int, b: ThorButton?): Boolean {
        if (type == ThorInput.EV_ABS && (code == ThorInput.ABS_LX || code == ThorInput.ABS_LY)) {
            if (router.held) { val v = if (router.free()) value else 0; main.post { stick(code, v) } }
            return false                                   // flicks still read it (a combo under Home)
        }
        if (router.route(type, code, value) == DeckHoldRules.Route.TODAY) return false
        if (type == ThorInput.EV_KEY && b != null && value != 2) main.post { key(b, value != 0) }
        else if (type == ThorInput.EV_ABS) main.post { hat(code, value) }
        return true
    }

    /** The Thor's copy's Back key while the deck holds the controller: its press gives the controller back; its press and its
     *  release are the deck's (consumed). Main thread. */
    fun backKey(down: Boolean): Boolean {
        if (down) { if (backTaken) return true; if (!held) return false; backTaken = true; end(DeckHoldRules.End.BACK); return true }
        if (backTaken) { backTaken = false; return true }
        return false
    }

    private fun key(printed: ThorButton, down: Boolean) {
        val job = DeckHoldRules.jobOf(ButtonNames.menuPos(printed, ButtonNames.lookOfSrc(0))) ?: return
        val d = deck
        if (!down) {
            when (job) {
                DeckHoldRules.Job.PRESS -> aPress.up().let { (k, c) -> k?.let { d?.pressKey(it, false) }; c?.let { d?.runCell(it, false) } }
                DeckHoldRules.Job.SHIFT -> d?.shiftHold(false, SystemClock.uptimeMillis())     // 1.5-beta2 (Q2): a tap's, or the hold ends
                else -> jobKeys.remove(job)?.let { d?.pressKey(it, false) }
            }
            return
        }
        if (!held || d == null) return
        when (job) {
            // 1.5-beta2 (Q2, GitHub D#127): L2 held = Shift held, on a pad with a Shift key (a tap = a tap of it)
            DeckHoldRules.Job.SHIFT -> if (d.jobKey(job) != null) d.shiftHold(true, SystemClock.uptimeMillis())
            DeckHoldRules.Job.PRESS -> {
                val id = lit ?: return
                val k = d.keyAt(id)
                aPress.down(id, k)
                if (k != null) d.pressKey(k, true) else d.runCell(id, true)
                relight()
            }
            DeckHoldRules.Job.BACK -> end(DeckHoldRules.End.BACK)
            DeckHoldRules.Job.PREV, DeckHoldRules.Job.NEXT -> { d.stepTab(if (job == DeckHoldRules.Job.PREV) -1 else 1); relight() }
            else -> d.jobKey(job)?.let { k -> jobKeys[job] = k; d.pressKey(k, true) }
        }
    }

    /** The rows changed (a tab, the clipboard, a mode): the light on the same cell, or the nearest one. */
    private fun relight() {
        val d = deck ?: return
        if (!held) return
        val now = d.navRows()
        at = DeckNav.find(now, lit) ?: DeckNav.keep(rows, at, now)
        rows = now
        lit = now.getOrNull(at.row)?.getOrNull(at.col)?.id
    }

    private fun hat(axis: Int, v: Int) { if (axis == ThorInput.ABS_HAT_X) hatX = v else hatY = v; steer() }
    private fun stick(axis: Int, v: Int) { if (axis == ThorInput.ABS_LX) stickX = v else stickY = v; steer() }

    /** The D-pad and the left stick as one direction (ui.PadNav's rule): a new one moves at once, then repeats while held. */
    private fun steer() {
        val m = ThorInput.STICK_MAX.toFloat()
        val want = if (!held) 0 else PadNav.direction(hatX.toFloat(), hatY.toFloat(), stickX / m, stickY / m)
        if (want == dir) return
        main.removeCallbacks(repeat); dir = want
        if (want == 0) return
        step(); main.postDelayed(repeat, DeckHoldRules.REPEAT_FIRST_MS)
    }

    private fun step() {
        val d = deck ?: return
        val (dx, dy) = when (dir) {
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1; KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0; KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            else -> return
        }
        val now = d.navRows()
        val from = DeckNav.find(now, lit) ?: DeckNav.keep(rows, at, now)
        val to = DeckNav.move(now, from, dx, dy, left)
        if (DeckNav.inPad(now, from.row) && !DeckNav.inPad(now, to.row)) left = now[from.row][from.col].id
        else if (DeckNav.inPad(now, to.row)) left = null
        at = to; rows = now
        lit = now.getOrNull(at.row)?.getOrNull(at.col)?.id
    }

    private fun stopNav() { main.removeCallbacks(repeat); dir = 0; hatX = 0; hatY = 0; stickX = 0; stickY = 0 }
}
