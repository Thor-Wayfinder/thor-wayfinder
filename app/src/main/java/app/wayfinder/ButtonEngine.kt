package app.wayfinder

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Runs the user's [ControlsStore] bindings.
 *
 * Inputs:
 *  - [onKey]: every key from the accessibility key filter (main thread). Gamepad
 *    buttons, Home and Back all arrive here (verified on the Thor) and CAN be
 *    swallowed by returning true.
 *  - [onHat]: D-pad changes from the root evdev monitor (the D-pad is a HAT axis,
 *    never a key — it can be observed, not swallowed).
 *
 * Rules (see [Controls.kt] header): Home/Back are only taken over when something
 * is bound to them; unbound presses are re-performed via global actions. A chord
 * swallows its second button; a Home/Back modifier is swallowed as well.
 *
 * Per controller (dev build 14): a combo is one controller's — its first and second button from the same one. Each
 * press is counted for its SOURCE: 0 the Thor (its copy, AYN's pad), n a connected controller ([ExtPadsCtl.srcOfDevice]
 * for keys, [ExtPadsCtl.srcOfGated] for what its reader withheld).
 *
 * dev build 17 (theme G): a connected controller's Back is its own key (wfhid target BK), its AYN a line ([onAyn] with its
 * source); with neither, Home + Select / Home + Start stand in ([standDown]).
 */
class ButtonEngine(private val host: Host) {

    interface Host {
        /** dev build 17 (theme G): [src] = the controller whose press it is (as its presses count) — "Back" goes to ITS pad. */
        fun perform(action: ThorAction, fromHold: Boolean, arg: String? = null, src: Int = 0)
        /** Re-perform a system button's normal job (Home / Back) — dev build 17: Back on controller [src]'s own pad. */
        fun nativePress(button: ThorButton, src: Int = 0)
        /** Shows the combo list; false = nothing shown (none, or the list is off). [src]: the controller holding the
         *  modifier — the list in its names (dev build 15, WP2: "Keep PS held… △ Game controls"). */
        fun showChordHint(modifier: ThorButton, chords: List<Binding>, src: Int = 0): Boolean
        /** The app with the controller has "Buttons: Off" (its name), or null. */
        fun combosOffApp(): String?
        fun showCombosOff(app: String, src: Int = 0)
        /** Hides controller [src]'s combo list (dev build 17, critique K12: the engine never asks for another controller's —
         *  [hideList]). */
        fun hideChordHint(src: Int)
        /** Package of the app that has the controller right now (per-app buttons). */
        fun currentApp(): String?
        /** Hold the triggers' ANALOG signal back from the app (see [AnalogShield]). */
        fun shieldAnalog(on: Boolean) {}
        /** Home and Back are let go, on every controller ([systemHeld] false again): what waited for it can go. */
        fun systemReleased() {}
        /** dev build 17 (theme G, D4): Home + Select / Home + Start stand for controller [src]'s Back / AYN button ([b]) — it
         *  has none of its own ([ExtRules.standIns]). [src] as its presses count (0 = the Thor: only while a docked controller
         *  plays as it). */
        fun standsIn(src: Int, b: ThorButton): Boolean = false
        /** dev build 17: controller [src]'s AYN button pressed / let go (Home + Start standing in for it). */
        fun padAyn(src: Int, down: Boolean) {}
    }

    companion object {
        /** Home or Back is held right now, on any controller (the keyboard lets a combo's second button through). */
        @Volatile var systemHeld = false

        /** b18o: who says a turbo button is held ([onTurbo]) — the input layer or a controller's reader ("TU" lines) … */
        const val TURBO_LAYER = 1
        /** …or the app's own turbo ([ExtEngine]: a D-pad direction's, a button whose job Wayfinder plays). */
        const val TURBO_APP = 2

        /** See the note above [onKey]: keycode → the PRINTED button, whatever the pad layout. */
        private val CONTROLLER_NAME = Regex("(?i)controller|gamepad|xbox")

        /** The Thor's built-in pad (either layout) — not gpio-keys (AYN button), not virtual. */
        private fun isController(dev: android.view.InputDevice?): Boolean =
            dev != null && !dev.isVirtual && (CONTROLLER_NAME.containsMatchIn(dev.name) ||
                (dev.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD)

        fun printedButton(event: KeyEvent): ThorButton? {
            val raw = ThorButton.fromKeyCode(event.keyCode) ?: return null
            val dev = event.device
            // The AYN button is ALSO a Home key (gpio-keys: 194 → HOME) and must stay AYN's
            // (its menu). Home/Back are only ours when they come from the controller itself.
            if ((raw == ThorButton.HOME || raw == ThorButton.BACK) && !isController(dev)) return null
            // dev build 15 (WP2, plan F): the positions THIS device sends ([ButtonNames.xboxOut]) — a connected controller's
            // pad its own face (its page, its own controls, the game's only when like the Thor's); the Thor's copy / AYN's
            // pad the game's layout, else AYN's 2020:0112 Xbox mode. Read through the Thor's, an Xbox-faced pad's A was
            // taken for its mirror image (Find by pressing, Listen, macros). Every controller key says who is in hand
            // (its names on the hints)
            val ctl = isController(dev)
            val src = if (ctl) ExtPadsCtl.srcOfDevice(dev) else 0
            if (ctl) ButtonNames.inHand(src)
            return if (ButtonNames.xboxOut(dev, src)) ButtonNames.faceSwap(raw) else raw
        }

        /** 1.3 (GitHub #8): what a face button MEANS in a menu (keyboard, Recents). Combos use the
         *  printed label ([printedButton]); menus follow the style, like the Hub and every app:
         *  in Xbox style the bottom button confirms (A) and the right one goes back (B). */
        fun menuButton(event: KeyEvent): ThorButton? = printedButton(event)?.let { ThorButton.fromKeyCode(event.keyCode) ?: it }

        /** Menu meaning ↔ printed button (the same swap both ways) — for the button badges. dev build 15 (WP2): the face
         *  style of the controller in hand (was: product 0x0112 / the last face press). */
        fun menuSwap(b: ThorButton): ThorButton = ButtonNames.menuPos(b)

        private const val TAG = "ThorButtons"
        private const val HOLD_MS = 1000L
        private const val MULTI_TAP_MS = 300L
        private const val HINT_MS = 450L
        /** 1.4 (GitHub #52): the list shows after a longer hold, or not at all (then [Host.showChordHint] skips it). */
        private fun hintDelay(): Long = if (AppSettings.chordHint == 1) 850L else HINT_MS
        /** Device pass (P2-10): the AYN button's hold when it has combos — ForegroundAppService's AynPress waits its
         *  AYN_HOLD_MS (600) + 400 then ("time to press the combo's button"); the list must come before it. */
        const val AYN_COMBO_HOLD_MS = 1000L
    }

    /** Configurator capture mode: receives every press (button, down) and swallows keys. */
    @Volatile var capture: ((ThorButton, Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    /** b18g (WI-6e): Home / Back reaching [onKey] late — logged only ([LateKeyLog]). Main thread. */
    private val lateKeys = LateKeyLog()

    // Per system button (Home/Back) tap state.
    private class Taps { var count = 0; var holdFired = false; var resolve: Runnable? = null; var hold: Runnable? = null }

    /** One controller's presses (main thread only). dev build 14 (combos 5): with one held set for every controller,
     *  one player's Home and another player's X fired Game controls, and the X never reached that player's game. */
    private class Pad(val src: Int) {                  // dev build 16 (WP6): which controller it is (counted source)
        val held = LinkedHashSet<ThorButton>()
        val swallowed = HashSet<ThorButton>()         // keys whose UP we must also swallow
        val chordUsed = HashSet<ThorButton>()         // modifiers that completed a chord this press
        val capturedUps = HashSet<ThorButton>()       // captured presses whose release must be swallowed too
        val taps = mapOf(ThorButton.HOME to Taps(), ThorButton.BACK to Taps())
        // 1.4 (GitHub #59): a two-button combo's second button — its taps and hold, per (first, second) pair
        val chordTaps = HashMap<Pair<ThorButton, ThorButton>, Taps>()
        /** 1.4: this Back press goes straight to the game (a game button was held when it went down). */
        var backToGameNow = false
        /** When a Home / Back press already used (a combo, its hold, the list read) was let go — the event's time. */
        var usedUpAt = 0L
        /** Its Home now is a Home-less pad's Select + Start held (wfhid's "J 1 102 1"): a game's own combo as often as
         *  not — its release is never a Home tap (dev build 14, combos 10). Cleared at that Home's release. */
        var synthHome = false
        /** dev build 17 (theme G, D4): Select / Start held under its Home standing for its Back / AYN (what each stands
         *  for), till its release. */
        val standIns = HashMap<ThorButton, ThorButton>()
        /** Build 18 (GitHub #81): held presses its reader withheld ([onGated]) — its Home / Back was already down there,
         *  even when that key reaches [onKey] after them. Spent when its Home / Back are let go. */
        val gatedDown = HashSet<ThorButton>()
        /** b18h (review): held presses its reader withheld ([onGated]), until each is let go — the native layer's consumed keys
         *  (fx/wfmap.h `ckey`): withheld until let go, even after the Home / Back that withheld them. Back's "a game button is
         *  held" skips them ([onKey]), as the native layer's `back_hot` does. Not [gatedDown]: that one is spent at Home /
         *  Back's release on purpose, so a button still held from Home + X can't make Home + X again at the next Home. */
        val withheld = HashSet<ThorButton>()
        /** Build 18 (#81): when each button the game got went down (the key's event time) — [ChordRules.early]. */
        val downAt = HashMap<ThorButton, Long>()
        /** b18l (#81, b18w B1): what the input layer's gate let go at its Home / Back press, and gives back at the release —
         *  as the copy's keys show it, whatever the order they come in ([GateLifts]). b18o: and the turbo buttons its hand
         *  holds ([onTurbo]), whose pulses are no presses. */
        val lifts = GateLifts()
    }
    private val pads = HashMap<Int, Pad>()                 // by source (see the class header)
    private fun pad(src: Int) = pads.getOrPut(src) { Pad(src) }

    /** dev build 16 (WP6): the controller (its counted source: 0 the Thor — a docked one playing as the Thor's too) whose
     *  combo fired the last action, and when — Game controls opens on its tab (the 1.5 s guess from the last withheld press
     *  took the raw source: docked, it opened the controller's own tab). */
    @Volatile private var actionSrc = 0
    @Volatile private var actionAt = 0L
    fun recentActionSrc(withinMs: Long): Int? = actionSrc.takeIf { actionAt != 0L && android.os.SystemClock.uptimeMillis() - actionAt < withinMs }
    private fun fire(src: Int, a: ThorAction, fromHold: Boolean, arg: String?) {
        actionSrc = src; actionAt = android.os.SystemClock.uptimeMillis()
        // b18g (GitHub #99): a press asking for a move / swap (Back's hold, a combo), in release logs too — a quick second swap
        // was lost with no line at all; beside the service's "queued" / "ignoring" / "swap done", this tells a press that
        // never fired from a swap that was refused
        if (a == ThorAction.SWAP_OR_SEND) Log.w(TAG, "move / swap asked (controller $src, ${if (fromHold) "hold" else "press"})")
        host.perform(a, fromHold, arg, src)
    }

    init { systemHeld = false }                            // a new engine (the service restarted) holds nothing
    private fun updateSystemHeld() {
        val was = systemHeld
        systemHeld = pads.values.any { p -> p.held.any { it.isSystem } }
        if (was && !systemHeld) host.systemReleased()
    }

    private var hintFor: ThorButton? = null
    private var hintSrc = 0
    private var shieldFor: ThorButton? = null
    private var shieldSrc = 0
    // Home held in an app whose combos are off: say so, instead of nothing (the combos just
    // seemed broken — release test 2026-09-24). Home itself still goes to Android.
    private var offHintShown = false
    private var offHintSrc = 0
    private val offHintRunnable = Runnable {
        if (ThorButton.HOME !in pad(offHintSrc).held) return@Runnable
        host.combosOffApp()?.let { host.showCombosOff(it, offHintSrc); offHintShown = true; listUp[offHintSrc] = ThorButton.HOME }
    }
    private val hintRunnable = Runnable {
        val m = hintFor ?: return@Runnable
        val p = pad(hintSrc)
        if (m in p.held && m !in p.chordUsed) {
            // only a list that showed makes the release a peek (off: a slow Home press is still Home)
            if (host.showChordHint(m, ControlsStore.effective(host.currentApp()).filter { it.trigger.modifier == m }, hintSrc)) {
                hintShownAt = android.os.SystemClock.uptimeMillis()
                listUp[hintSrc] = m
            }
        }
    }
    /** dev build 17 (critique K12): the combo lists up — each controller's and the button it holds. One player's combo,
     *  release or controller gone hides only ITS list: with one list for every controller, player 1's combo hid player 2's
     *  list while it was still held. WP10: the overlay host keeps one list per controller ([Held.key]) — two players' lists
     *  stack, so each one's own release must take its own down (one remembered list left the other's behind). */
    private val listUp = HashMap<Int, ThorButton>()
    /** Hides controller [src]'s combo list — never another controller's ([Host.hideChordHint] is keyed by [src]). Main
     *  thread (the service's AYN hold too). */
    fun hideList(src: Int) {
        listUp.remove(src)
        host.hideChordHint(src)
    }
    /** [b] of controller [src] let go: its list goes even when another controller's press took the hint over meanwhile. */
    private fun releaseList(src: Int, b: ThorButton) { if (listUp[src] == b) hideList(src) }
    // Held to read the combos, then let go without one: that's a peek, not a Home / Back press —
    // it used to go Home and leave the game (found by the tour's "hold Home" step, 2026-09-25).
    // Only once the hint has been up a moment, so a slow deliberate tap still goes Home.
    private var hintShownAt = 0L
    private fun peeked(): Boolean = hintShownAt != 0L && android.os.SystemClock.uptimeMillis() - hintShownAt >= ChordRules.PEEK_MS

    /** Device pass (P2-10): when [b]'s held list shows — the setting's delay ([hintDelay]: "Later" 850 ms), but before [b]'s
     *  own hold acts ([ChordRules.listDelay]): a touchpad click held showed its list at 850 ms, its hold (swap apps) fired at
     *  1000 and the list counted as read only at 1250 — never readable. [bindings]: the app's. */
    private fun listDelay(b: ThorButton, bindings: List<Binding>): Long = ChordRules.listDelay(hintDelay(), ownHoldMs(b, bindings))

    /** When [b]'s own hold action fires, held alone (null = it has none): Home / Back with a "Hold" binding ([systemDown]);
     *  the AYN button with a hold action while it has combos ([AYN_COMBO_HOLD_MS]). */
    private fun ownHoldMs(b: ThorButton, bindings: List<Binding>): Long? = when {
        b == ThorButton.AYN -> AYN_COMBO_HOLD_MS.takeIf { AppSettings.aynHold != null }
        b.isSystem && bindings.any { it.trigger.button == b && !it.trigger.isChord && it.trigger.press == Press.HOLD } -> HOLD_MS
        else -> null
    }

    // ── keys ───────────────────────────────────────────────────────────
    fun onKey(event: KeyEvent): Boolean {
        val b = physicalButton(event) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) app.wayfinder.ui.InputMode.notePadKey()   // build 18 (A0): a controller is driving (the keyboard lights a key)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        // b18g (WI-6e, #102 a / #41): a Home / Back the system handed over late — a release log line, rate-limited (b18n: a Back
        // late enough that the system gave it to the game anyway is the game's, below)
        if (b.isSystem && (!down || event.repeatCount == 0))
            lateKeys.line(b.label, down, android.os.SystemClock.uptimeMillis(), event.eventTime)?.let { Log.w(TAG, it) }
        val src = ExtPadsCtl.srcOfDevice(event.device)
        val p = pad(src)
        // 1.4 (GitHub #43): the short Back we just pressed on the controller copy — the game's, untouched. dev build 17
        // (theme G): on that controller's own pad — keyed by the source its keys count as
        if (b == ThorButton.BACK && android.os.SystemClock.uptimeMillis() < (replayBackUntil[src] ?: 0L)) {
            if (!down) replayBackUntil.remove(src)
            return false
        }
        // 1.4 (GitHub #43): Back while a game button is held (Select + Back: RetroArch's menu) = the game's hotkey. Build 18
        // (#81): not a press its reader withheld — that proves Back came first (its key just arrived after it). b18h (review):
        // withheld until it is let go ([Pad.withheld]), as the native layer counts it for the same Back (fx/wfmap.h).
        // b18n (#102 a): a Back the system already handed to the game — it reached us too late for our answer to count
        // ([ChordRules.handedOn]) — is the game's whole press too: no taps, hold, combo or replay (the replay made it two
        // Backs). Under a held Home / AYN it still counts as something pressed with it (never their tap). Its log line: lateKeys'
        if (b == ThorButton.BACK) {
            if (down && event.repeatCount == 0 && capture == null) {
                val handedOn = ChordRules.handedOn(android.os.SystemClock.uptimeMillis(), event.eventTime)
                if (handedOn) touchedDuringModifier(p)
                if (handedOn || p.held.any { !it.isSystem && it != ThorButton.AYN && it !in p.withheld }) { p.backToGameNow = true; return false }
            }
            // b18n review: its release spends what the gate withheld meanwhile, as any Back's does ([spendGated]) — a late Back
            // still gates natively (nothing held before it), so a press under it came as a J line ([onGated]); kept, it made the
            // next Home "Home + that button"
            if (p.backToGameNow) { if (!down) { p.backToGameNow = false; spendGated(p) }; return false }
        }

        // A key whose press was captured: its release is swallowed too, even if the capture
        // ended in between (Home cancels "listening" on its press — its release then went Home).
        if (!down && p.capturedUps.remove(b)) { capture?.invoke(b, false); return true }
        capture?.let { cb ->
            if (BuildConfig.DEBUG) Log.d(TAG, "capture $b ${if (down) "down" else "up"}")
            if (down && event.repeatCount == 0) { p.capturedUps.add(b); cb(b, true) } else if (!down) cb(b, false)
            return true
        }
        if (down && event.repeatCount > 0) return b in p.swallowed
        val bindings = ControlsStore.effective(host.currentApp())

        if (down) {
            // dev build 14 (combos 1, safety net): Home let go and pressed again within 20 ms by the same controller, its
            // press already used — a reader letting go of everything and pressing it again, not a hand — is the SAME
            // press. As a new one, its real release was a Home tap: Home + X opened Game controls, then went home.
            if (b.isSystem && p.usedUpAt != 0L && event.eventTime - p.usedUpAt in 0L..20L) {
                Log.d(TAG, "${b.label} let go and pressed again at once — the same press")
                p.usedUpAt = 0L
                p.held.add(b); systemHeld = true; p.chordUsed.add(b); p.swallowed.add(b)
                return true
            }
            if (b.isSystem) p.usedUpAt = 0L
            // b18l (#81, b18w B1): the gate giving back what it let go at Home / Back's press — no hand's press: it keeps the time
            // it was pressed, and under the held Home / Back it makes no combo and touches nothing ([GateLifts.keyDown])
            val given = if (b.isSystem) ChordRules.PRESSED else p.lifts.keyDown(b, event.eventTime, p.held.any { it.isSystem })
            p.held.add(b)
            if (!b.isSystem) { if (given == ChordRules.PRESSED) p.downAt[b] = event.eventTime; p.gatedDown.remove(b); p.withheld.remove(b) }   // #81: the game got this one
            if (b.isSystem) { systemHeld = true; p.lifts.systemDown(b, event.eventTime, p.held) }
            // b18o (2026-10-11 — #81's turbo leftover): a turbo's on-pulse — the hand still holds that turbo button
            // ([onTurbo]) and its own press came already: no press — no key time (above), no combo, touches nothing. b18o
            // review: it gets the answer the hand's press got ([GateLifts.pulseDown]) — a combo's second button (AYN + a turbo
            // R2) stays the combo's, its pulses and their UPs swallowed, as long as the hand holds it (the game got R2 at the
            // turbo's rate after the combo)
            if (given == ChordRules.PULSE) { if (!p.lifts.pulseDown(b)) return false; p.swallowed.add(b); return true }
            // Home / Back stop the game's macros and release what the app holds for it — this controller's, with what its
            // reader plays under its own source (dev build 14, review: docked the rename way, its Home comes as the Thor's
            // and stopped none of its macros or mouse)
            if (b.isSystem && !(b == ThorButton.BACK && host.currentApp()?.let { ControlsStore.backToGame(it) } == true))
                ExtPadsCtl.rawSrcs(src).forEach { ExtEngine.releaseAll(it) }
            // 1) Second button of a chord whose modifier is held (on this controller) → fire (or count its taps / hold), swallow.
            val mod = if (given == ChordRules.GIVEN_UNDER) null else modifierOf(p, b, bindings)
            val chord = mod != null && chordDown(p, mod, b, bindings)
            p.lifts.pressAnswered(b, swallowed = chord)       // b18o review: what its turbo's pulses get, if it is one
            if (chord) { p.swallowed.add(b); return true }
            // 2) A modifier: arm the hint.
            if (bindings.any { it.trigger.modifier == b }) {
                p.chordUsed.remove(b); hintFor = b; hintSrc = src; hintShownAt = 0L
                main.removeCallbacks(hintRunnable); main.postDelayed(hintRunnable, listDelay(b, bindings))
                // Its combos use L2/R2 or a stick: that analog movement would still reach the
                // app — unless the input layer runs (it withholds everything while Home/Back
                // is held, so no shield and no lost window focus). dev build 14 (combos 8): a connected
                // controller's Home neither — its own reader withholds everything while it's held.
                if (!((layerActive || src > 0) && b.isSystem) && bindings.any { it.trigger.modifier == b &&
                        (it.trigger.button == ThorButton.L2 || it.trigger.button == ThorButton.R2 || it.trigger.button.isFlick) }) {
                    shieldFor = b; shieldSrc = src; host.shieldAnalog(true)
                }
            }
            // 3) Home + Back together = the input layer's emergency switch (held 5 s), never a
            //    shortcut: cancel whatever either was about to do (Back's hold = move/swap).
            val other = when (b) { ThorButton.HOME -> ThorButton.BACK; ThorButton.BACK -> ThorButton.HOME; else -> null }
            if (other != null && other in p.held && bindings.none { it.trigger.isChord && it.trigger.modifier == other && it.trigger.button == b }) {
                for (s in listOf(b, other)) p.taps.getValue(s).let { t ->
                    t.hold?.let { main.removeCallbacks(it) }; t.hold = null
                    t.resolve?.let { main.removeCallbacks(it) }; t.resolve = null; t.count = 0
                }
                p.chordUsed.add(b); p.chordUsed.add(other)
                main.removeCallbacks(hintRunnable); hideList(src)
                p.swallowed.add(b); return true
            }
            // 4) Home/Back with anything bound → we own it. Build 18 (#81): a partner already down counts ([earlyPartners])
            if (b.isSystem && owns(b, bindings)) {
                systemDown(p, b, bindings); earlyPartners(p, b, event.eventTime, bindings)
                p.swallowed.add(b); return true
            }
            // another button pressed while Home / Back is held: it's not a tap of Home / Back any
            // more (the layer off, Home + A released Home as a tap and left the game — review). b18l: not the gate's give-back
            if (!b.isSystem && given != ChordRules.GIVEN_UNDER) touchedDuringModifier(p)
            if (b == ThorButton.HOME && bindings.isEmpty()) { offHintSrc = src; main.removeCallbacks(offHintRunnable); main.postDelayed(offHintRunnable, HINT_MS) }
            return false
        } else {
            p.held.remove(b)
            // b18l (#81, b18w B1): its key time, and whether the gate let it go at Home / Back's press ([GateLifts.keyUp]); a
            // Home / Back let go after a give-back that came at another time than this release — a hand's press: no tap
            if (!b.isSystem) p.lifts.keyUp(b, event.eventTime, p.held.any { it.isSystem })
            else if (p.lifts.systemUp(event.eventTime)) p.chordUsed.add(b)
            if (b == ThorButton.HOME && src == offHintSrc) { main.removeCallbacks(offHintRunnable); if (offHintShown) { offHintShown = false; hideList(src) } }
            if (shieldFor == b && shieldSrc == src) { shieldFor = null; host.shieldAnalog(false) }
            val mine = hintFor == b && hintSrc == src
            val peek = mine && peeked()
            if (mine) { main.removeCallbacks(hintRunnable); hideList(src); hintFor = null; hintShownAt = 0L }
            else releaseList(src, b)
            if (peek && b.isSystem) { Log.d(TAG, "${b.label} released after reading the combos — nothing"); p.chordUsed.add(b) }
            if (b == ThorButton.HOME && p.synthHome) { p.synthHome = false; p.chordUsed.add(b) }
            // b18o review: a swallowed turbo pulse's UP ([GateLifts.pulseUp]) — swallowed with it, never the combo's release
            // (its taps were counted at the hand's press's own UP; counted at each pulse, one long press read as a double tap)
            val pulseUp = !b.isSystem && p.lifts.pulseUp(b)
            val swallow = p.swallowed.remove(b)
            if (swallow && !pulseUp && !chordUp(p, b) && b.isSystem) {
                // used (a combo, its hold, the list read): pressed again at once, it's the same press (above)
                if (b in p.chordUsed || p.taps.getValue(b).holdFired) p.usedUpAt = event.eventTime
                systemUp(p, b, bindings)
            }
            if (b.isSystem) { spendGated(p); updateSystemHeld() }
            return swallow
        }
    }

    /**
     * The PRINTED button that was pressed ([printedButton]). AYN's "controller style" setting (and its
     * per-game auto-switch) flips the pad between two identities:
     *  - Odin / Nintendo layout — "Odin Controller", product 0x0111: Android keycodes
     *    match the printed labels (verified by pressing every button, 2026-09-22);
     *  - Xbox layout — "Xbox Wireless Controller", product 0x0112: face buttons are
     *    reported by POSITION, so printed A↔B and X↔Y swap (verified: printed A, B,
     *    X, Y → BUTTON_B, A, Y, X).
     * Bindings are stored as printed buttons, so a combo stays on the same physical
     * button whichever layout is active. A connected controller's pad: its own face (dev build 15).
     */
    private fun physicalButton(event: KeyEvent): ThorButton? = printedButton(event)

    // ── Input layer: presses the game did NOT get (Home/Back held) — any thread ──
    /**
     * A button withheld from the game by the input layer (wfpad's gate, or a connected controller's reader: [src]). Keys
     * pressed while Home/Back is held never reach the accessibility filter any more, so a combo's second button arrives
     * here — and needs no swallowing: the game never saw it. [shift]: the hold-to-shift button of that controller's profile
     * (§6l, null = none) — its reader withholds it, and what's pressed under it, with no Home / Back down.
     */
    fun onGated(b: ThorButton, down: Boolean, src: Int = 0, shift: ThorButton? = null) = main.post {
        if (b.isSystem) return@post                       // Home/Back themselves still come via onKey
        // b18h (review): withheld until let go ([Pad.withheld]) — also while a press is being captured, so its release unmarks it
        pad(src).withheld.let { if (down) it.add(b) else it.remove(b) }
        capture?.let { it(b, down); return@post }
        val p = pad(src)
        // dev build 17 (theme G, D4): Select / Start standing for its Back / AYN under its Home — its release is theirs
        if (!down) {
            p.gatedDown.remove(b)
            p.lifts.letGo(b)                              // b18l: let go under its gate — nothing to give back
            p.standIns.remove(b)?.let { standUp(p, it); return@post }; p.held.remove(b); chordUp(p, b); return@post
        }
        if (b in p.standIns || standDown(p, b)) return@post
        if (!p.held.add(b)) return@post
        // build 18 (GitHub #81): withheld = its Home / Back is down at its reader — even when that key reaches onKey after
        // this line ([earlyPartners] then makes the combo). Not the shift layer's: held shift + a Home tap still goes home
        if (ChordRules.withheldForHome(b, shift, p.held)) p.gatedDown.add(b)
        p.downAt.remove(b)
        touchedDuringModifier(p)
        val bindings = ControlsStore.effective(host.currentApp())
        modifierOf(p, b, bindings)?.let { chordDown(p, it, b, bindings) }
    }

    /** The modifier a press of [b] completes on [p]'s controller ([ChordRules.modifierOf]). */
    private fun modifierOf(p: Pad, b: ThorButton, bindings: List<Binding>) = ChordRules.modifierOf(p.standIns.values, p.held, b, bindings)

    /**
     * dev build 17 (theme G, D4): Select / Start pressed under its Home on a controller with no Back / AYN button of its own
     * ([Host.standsIn]) — that press is its Back / AYN button (its Home: used, no tap at its release). Not when the user has
     * a combo of their own on it (theirs wins), nor under a Home-less pad's Select + Start Home, nor when Home isn't
     * Wayfinder's here (it went to Android: a Back on top of a Home going home). True = it stands in.
     */
    private fun standDown(p: Pad, b: ThorButton): Boolean {
        if (ThorButton.HOME !in p.held || p.synthHome) return false
        val stands = when (b) { ThorButton.SELECT -> ThorButton.BACK; ThorButton.START -> ThorButton.AYN; else -> return false }
        val bindings = ControlsStore.effective(host.currentApp())
        if (!owns(ThorButton.HOME, bindings)) return false
        if (bindings.any { it.trigger.isChord && it.trigger.modifier == ThorButton.HOME && it.trigger.button == b }) return false
        if (!host.standsIn(p.src, stands)) return false
        touchedDuringModifier(p); main.removeCallbacks(hintRunnable); hideList(p.src)
        p.standIns[b] = stands
        Log.d(TAG, "${b.label} under Home stands for ${stands.label} (controller ${p.src})")
        if (stands == ThorButton.BACK) backDown(p, bindings) else host.padAyn(p.src, true)
        return true
    }

    /** dev build 17: Back's own press, standing in — its taps, hold and combos as a Back key's ([onKey] steps 2 and 4), never
     *  step 3 ("Home + Back together": Home is what calls it). */
    private fun backDown(p: Pad, bindings: List<Binding>) {
        val b = ThorButton.BACK
        p.usedUpAt = 0L
        p.held.add(b); systemHeld = true; p.chordUsed.remove(b)
        if (host.currentApp()?.let { ControlsStore.backToGame(it) } != true) ExtPadsCtl.rawSrcs(p.src).forEach { ExtEngine.releaseAll(it) }
        if (bindings.any { it.trigger.modifier == b }) {
            hintFor = b; hintSrc = p.src; hintShownAt = 0L
            main.removeCallbacks(hintRunnable); main.postDelayed(hintRunnable, listDelay(b, bindings))
        }
        if (owns(b, bindings)) systemDown(p, b, bindings)
    }

    /** dev build 17: Select / Start standing in let go — its Back's release (a tap, the end of its hold or combo) / its AYN's. */
    private fun standUp(p: Pad, stands: ThorButton) {
        if (stands == ThorButton.AYN) { host.padAyn(p.src, false); return }
        val b = ThorButton.BACK
        p.held.remove(b)
        val mine = hintFor == b && hintSrc == p.src
        val peek = mine && peeked()
        if (mine) { main.removeCallbacks(hintRunnable); hideList(p.src); hintFor = null; hintShownAt = 0L } else releaseList(p.src, b)
        if (peek) { Log.d(TAG, "Back released after reading the combos — nothing"); p.chordUsed.add(b) }
        val bindings = ControlsStore.effective(host.currentApp())
        if (owns(b, bindings)) { if (!chordUp(p, b)) systemUp(p, b, bindings) }
        else if (!p.chordUsed.remove(b)) host.nativePress(b, p.src)
        spendGated(p); updateSystemHeld()
    }

    /** Controller [src]'s Home is now its Select + Start held (no Home button): see [Pad.synthHome]. */
    fun markSynthHome(src: Int) = main.post { pad(src).synthHome = true }

    /**
     * b18o (2026-10-11 — GitHub #81's turbo leftover): controller [src]'s hand holds ([held]) — or let go of — a turbo
     * button whose job is its pad's button [b], pressed at [at] (the keys' clock). Said by [by]: the input layer or the
     * controller's reader ([TURBO_LAYER], "TU" lines), or the app's own turbo ([TURBO_APP], [ExtEngine]). The job goes on and
     * off on the pad while it is held: each on-pulse looked like a new press, and a Home tap within 150 ms of one was no tap
     * about half the time. Now the pulses are no presses ([GateLifts.keyDown]: no key time, no combo, nothing touched) and
     * the button counts as held, from the hand's press, till the hand lets go ([ChordRules.early], Home's partners). Home,
     * Back and AYN are never turbo buttons here.
     */
    fun onTurbo(b: ThorButton, held: Boolean, src: Int, at: Long, by: Int) = main.post {
        if (b.isSystem || b == ThorButton.AYN) return@post
        val p = if (held) pad(src) else pads[src] ?: return@post          // a release for a controller already gone: nothing
        if (p.lifts.turboSaid(b, held, at, by, p.downAt[b])) p.downAt[b] = at
    }
    /** b18o: every turbo button [by] said held on controller [src] is let go (the input layer's engine ended: "TU 0"). */
    fun turboGone(src: Int, by: Int) = main.post { pads[src]?.lifts?.turboGone(by) }

    /** A stick flick (see [ThorButton.isFlick]) on controller [src]: a press and release in one. */
    fun onFlick(b: ThorButton, src: Int = 0) = main.post {
        capture?.let { it(b, true); it(b, false); return@post }
        val p = pad(src)
        touchedDuringModifier(p)
        // 1.4 (review): press types too — a flick is a press and a release
        val bindings = ControlsStore.effective(host.currentApp())
        modifierOf(p, b, bindings)?.let { m -> if (chordDown(p, m, b, bindings)) chordUp(p, b) }
    }

    /** Something else was used while Home/Back was held: letting go of it is then NOT a tap of
     *  Home/Back (you were trying a combo, even an unbound one) — never an unwanted Home. */
    private fun touchedDuringModifier(p: Pad) { p.held.filter { it.isSystem || it == ThorButton.AYN }.forEach { p.chordUsed.add(it); cancelHold(p, it) } }

    /**
     * Build 18 (GitHub #81): [m] (Home / Back, ours) went down at [at] with a partner already down on [p]'s controller
     * ([ChordRules.early]) — its line beat [m]'s own key here. [m]'s release is then no tap (it went home and left the game)
     * and it has no hold. A partner its reader withheld makes its combo now: [m] was down first there. Select / Start
     * withheld under its Home stand in for its Back / AYN, as they would have ([standDown]). A partner the game got (pressed
     * just before) makes none: the game has it.
     */
    private fun earlyPartners(p: Pad, m: ThorButton, at: Long, bindings: List<Binding>) {
        // b18l: and, for Home, a partner its gate let go in Home's own report, written before it (b18j's copy). b18o: and a
        // turbo button the hand holds, in its off half too — by the time the hand pressed it
        val early = ChordRules.early(p.held, p.gatedDown, p.downAt, m, at, lifted = p.lifts.lifted, turbo = p.lifts.turbo)
        if (early.isEmpty()) return
        Log.d(TAG, "${m.label} after ${early.joinToString { it.label }} (controller ${p.src}) — not a tap")
        p.chordUsed.add(m); cancelHold(p, m)
        for (b in early.filter { it in p.gatedDown }) {
            // standing in: its release is the stand-in's ([onGated]), never in the held set (as when it comes under Home)
            if (m == ThorButton.HOME && standDown(p, b)) { p.held.remove(b); p.gatedDown.remove(b) }
            else chordDown(p, m, b, bindings)
        }
    }

    /** Build 18 (#81): [p]'s Home and Back both let go — what its reader withheld for them proves nothing about the next
     *  press (a button still held from Home + X would make Home + X again at the next Home). */
    private fun spendGated(p: Pad) { if (p.held.none { it.isSystem }) { p.gatedDown.clear(); p.lifts.spend() } }

    /** A connected controller went away (or the root helper did): what it held is forgotten — a controller on that node
     *  starts clean, and its Home can't stay "held" ([systemHeld]) for good. */
    fun gone(src: Int) = main.post {
        val p = pads.remove(src) ?: return@post
        for (t in p.taps.values + p.chordTaps.values) { t.hold?.let { main.removeCallbacks(it) }; t.resolve?.let { main.removeCallbacks(it) } }
        if (hintSrc == src && hintFor != null) { main.removeCallbacks(hintRunnable); hideList(src); hintFor = null; hintShownAt = 0L }
        if (shieldSrc == src && shieldFor != null) { shieldFor = null; host.shieldAnalog(false) }
        // dev build 14 (review): "combos are off" too — no other controller's Home release takes it down
        if (offHintSrc == src) { main.removeCallbacks(offHintRunnable); if (offHintShown) { offHintShown = false; hideList(src) } }
        if (src in listUp) hideList(src)                                // dev build 17 (K12): its list, whoever armed the hint last
        updateSystemHeld()
    }

    // ── The AYN button as a combo's first button (1.3.1, GitHub #44) ──────────
    /** AYN pressed / released (from the service: it's not a pad key — the Thor's). While it's held, a button
     *  bound as "AYN + button" fires its combo (step 1 of [onKey]). Returns, on release, whether
     *  the press was used for a combo (or to read the combos) — then its own tap must not run.
     *  dev build 17 (theme G): [src] = whose AYN button — the Thor's (0), or a connected controller's (its spare button's "A"
     *  line, Home + Start standing in), as its presses count: AYN + X is that controller's combo. */
    fun onAyn(down: Boolean, src: Int = 0): Boolean {
        val b = ThorButton.AYN
        val p = pad(src)
        capture?.let { cb -> if (down) { p.capturedUps.add(b); cb(b, true) } else { p.capturedUps.remove(b); cb(b, false) }; return true }
        if (!down && p.capturedUps.remove(b)) return true
        if (down) {
            p.held.add(b); p.chordUsed.remove(b)
            val bindings = ControlsStore.effective(host.currentApp())
            if (bindings.any { it.trigger.modifier == b }) {
                hintFor = b; hintSrc = src; hintShownAt = 0L
                main.removeCallbacks(hintRunnable); main.postDelayed(hintRunnable, listDelay(b, bindings))
            }
            return false
        }
        p.held.remove(b)
        val mine = hintFor == b && hintSrc == src
        val peek = mine && peeked()
        if (mine) { main.removeCallbacks(hintRunnable); hideList(src); hintFor = null; hintShownAt = 0L } else releaseList(src, b)
        return p.chordUsed.remove(b) || peek
    }

    /** AYN is being used for a combo right now on controller [src] (its hold action must wait). */
    fun aynInCombo(src: Int = 0): Boolean = pads[src]?.let { ThorButton.AYN in it.chordUsed } == true
    /** Is the engine recording a combo (the configurator)? */
    val capturing: Boolean get() = capture != null
    /** A Home / Back hold that was going to fire: not any more — the button became a combo's modifier
     *  (its hold ran at 1 s in the middle of a combo, review 2026-09-25). */
    private fun cancelHold(p: Pad, b: ThorButton) { p.taps[b]?.let { t -> t.hold?.let { main.removeCallbacks(it) }; t.hold = null } }

    /** The input layer's copy is the pad: Home/Back hold everything else back themselves. */
    private val layerActive get() = PadLayerCtl.active
    /** 1.4 (GitHub #43): until when a Back from the pad is our own replay ([pressBackOnPad]) — dev build 17 (theme G): per
     *  controller, by the source its keys count as (a raw source from [ExtEngine] mismatched: the replay was taken for a new
     *  Back and pressed again — a loop). */
    private val replayBackUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** A short Back for the app on controller [src]'s own pad — the game sees its Back (RetroArch's menu, a binding): the
     *  Thor's copy when the input layer runs ("G p 158"), a connected controller's pad (dev build 17, theme G: "E p … 158",
     *  out even while its Home is held); false = none (the caller uses Android's Back). */
    fun pressBackOnPad(src: Int = 0): Boolean {
        if (src > 0) {
            if (!ExtPadsCtl.canPress(src)) return false
            replayBackUntil[ExtPadsCtl.srcOfGated(src)] = android.os.SystemClock.uptimeMillis() + 800   // its keys count as that one
            ExtPadsCtl.press(src, 158, 1)
            main.postDelayed({ ExtPadsCtl.press(src, 158, 0) }, 60)
            return true
        }
        if (!layerActive) return false
        // dev build 17 (review G-ENG-1): docked, the controller feeding the copy — its Home is often still down (Home + Select
        // stands in for its Back), and the copy says no virtual press under Home (wfmap.h): the Back goes into its own
        // reader (wfhid's Back is out even under Home; the copy passes a system key through) and comes out of the copy as
        // the Thor's — its keys count as 0, so the replay is keyed 0
        // (dev build 18f: also the one playing as the Thor's by its switch — merged, its keys come through the copy too)
        ExtPadsCtl.seatRawSrc()?.takeIf { ExtPadsCtl.canPress(it) }?.let { raw ->
            replayBackUntil[0] = android.os.SystemClock.uptimeMillis() + 800
            Log.d(TAG, "Back on the docked controller's own reader (source $raw)")
            ExtPadsCtl.press(raw, 158, 1)
            main.postDelayed({ ExtPadsCtl.press(raw, 158, 0) }, 60)
            return true
        }
        // dev build 17 (review G-ENG-1): Home held here — the copy would drop a virtual Back (silent under Home): Android's
        // Back instead (the caller's), never nothing
        if (ThorButton.HOME in pad(0).held) { Log.d(TAG, "Back under a held Home: Android's Back (the copy drops it)"); return false }
        replayBackUntil[0] = android.os.SystemClock.uptimeMillis() + 800
        InputMonitor.send("G p 158 1")   // KEY_BACK, the pad's Back
        // held ~3 frames: games that read the pad once a frame see it
        main.postDelayed({ InputMonitor.send("G p 158 0") }, 60)
        return true
    }

    // ── D-pad (from evdev, any thread): the Thor's, or what a connected controller's reader withheld ([src]) ──
    fun onHat(axis: Int, value: Int, src: Int = 0) = run {
        // device pass (P2-3): a D-pad press is a controller driving, as [onKey]'s keys are (the Thor's D-pad is a HAT: it
        // never counted — the legend, hidden, stayed hidden through D-pad moves). Any thread
        if (value != 0) app.wayfinder.ui.InputMode.notePadKey()
        main.post { hat(axis, value, src) }
    }

    private fun hat(axis: Int, value: Int, src: Int) {
        val p = pad(src)
        val dirs = ThorButton.values().filter { it.hatAxis == axis }
        if (value == 0) {
            dirs.forEach { d -> if (p.held.remove(d)) { val c = capture; if (c != null) c(d, false) else chordUp(p, d) } }
            return
        }
        val b = ThorButton.fromHat(axis, value) ?: return
        // a direction replaced without passing the centre: its press ends (its combo's hold mustn't fire later)
        dirs.filter { it != b }.forEach { if (p.held.remove(it)) { val c = capture; if (c != null) c(it, false) else chordUp(p, it) } }
        if (!p.held.add(b)) return
        capture?.let { it(b, true); return }
        touchedDuringModifier(p)
        // 1.4 (review): the D-pad's press types (Double / Triple / Hold) like any button's
        val bindings = ControlsStore.effective(host.currentApp())
        modifierOf(p, b, bindings)?.let { chordDown(p, it, b, bindings) }
    }

    /** 1.4 (GitHub #59): [b] went down while [m] is held (both on [p]'s controller). Only plain presses bound → fire at once
     *  (as always); else count its taps and time its hold, like Home and Back on their own. False = no combo for this pair. */
    private fun chordDown(p: Pad, m: ThorButton, b: ThorButton, bindings: List<Binding>): Boolean {
        val cands = bindings.filter { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier == m }
        if (cands.isEmpty()) return false
        if (cands.all { it.trigger.press == Press.TAP }) { fireChord(p, cands.first()); return true }
        p.chordUsed.add(m); cancelHold(p, m)
        main.removeCallbacks(hintRunnable); hideList(p.src)
        val t = p.chordTaps.getOrPut(m to b) { Taps() }
        t.resolve?.let { main.removeCallbacks(it) }; t.resolve = null
        t.holdFired = false
        cands.firstOrNull { it.trigger.press == Press.HOLD }?.let { h ->
            t.hold = Runnable {
                t.holdFired = true; t.count = 0; t.hold = null
                Log.d(TAG, "${h.trigger.label()} → ${h.action}")
                fire(p.src, h.action, fromHold = true, arg = h.arg)
            }.also { main.postDelayed(it, HOLD_MS) }
        }
        return true
    }

    /** [b] came back up: if it's counting as a combo's second button, the taps are resolved. True = it was. */
    private fun chordUp(p: Pad, b: ThorButton): Boolean {
        val key = p.chordTaps.entries.firstOrNull { it.key.second == b && (it.value.hold != null || it.value.holdFired || it.value.count >= 0) }?.key ?: return false
        val t = p.chordTaps.getValue(key)
        t.hold?.let { main.removeCallbacks(it) }; t.hold = null
        if (t.holdFired) { t.holdFired = false; t.count = 0; p.chordTaps.remove(key); return true }
        t.count++
        val cands = ControlsStore.effective(host.currentApp()).filter { it.trigger.isChord && it.trigger.modifier == key.first && it.trigger.button == b }
        val maxTaps = cands.maxOfOrNull { when (it.trigger.press) { Press.DOUBLE_TAP -> 2; Press.TRIPLE_TAP -> 3; else -> 1 } } ?: 1
        val resolve = Runnable {
            val n = t.count; p.chordTaps.remove(key)
            val press = when (n) { 1 -> Press.TAP; 2 -> Press.DOUBLE_TAP; else -> Press.TRIPLE_TAP }
            cands.firstOrNull { it.trigger.press == press }?.let { c ->
                Log.d(TAG, "${c.trigger.label()} → ${c.action}")
                fire(p.src, c.action, fromHold = false, arg = c.arg)
            }
        }
        if (t.count >= maxTaps) resolve.run() else { t.resolve = resolve; main.postDelayed(resolve, MULTI_TAP_MS) }
        return true
    }

    private fun fireChord(p: Pad, chord: Binding) {
        val m = chord.trigger.modifier!!
        p.chordUsed.add(m)
        cancelHold(p, m)
        main.removeCallbacks(hintRunnable); hideList(p.src)
        Log.d(TAG, "chord ${chord.trigger.label()} → ${chord.action}")
        fire(p.src, chord.action, fromHold = false, arg = chord.arg)
    }

    // ── Home / Back press types ───────────────────────────────────────
    private fun owns(b: ThorButton, bindings: List<Binding>) =
        bindings.any { (it.trigger.button == b && !it.trigger.isChord) || it.trigger.modifier == b }

    private fun systemDown(p: Pad, b: ThorButton, bindings: List<Binding>) {
        val t = p.taps.getValue(b)
        t.resolve?.let { main.removeCallbacks(it) }       // a further tap may be coming
        // b18g (WI-6c, #102): a hold still pending is this press's, never a second one — a second DOWN of a held Home / Back
        // (the same press from two devices on one controller, an UP lost) left the first press's hold posted with nothing to
        // cancel it: its move / swap fired 1 s later
        t.hold?.let { main.removeCallbacks(it) }; t.hold = null
        t.holdFired = false
        val hold = bindings.firstOrNull { it.trigger.button == b && !it.trigger.isChord && it.trigger.press == Press.HOLD }
        if (hold != null) {
            t.hold = Runnable {
                t.holdFired = true; t.count = 0
                Log.d(TAG, "${b.label} hold → ${hold.action}")
                fire(p.src, hold.action, fromHold = true, arg = hold.arg)
            }.also { main.postDelayed(it, HOLD_MS) }
        }
    }

    private fun systemUp(p: Pad, b: ThorButton, bindings: List<Binding>) {
        val t = p.taps.getValue(b)
        t.hold?.let { main.removeCallbacks(it) }; t.hold = null
        if (t.holdFired || p.chordUsed.remove(b)) { t.count = 0; t.holdFired = false; return }
        t.count++
        val singles = bindings.filter { it.trigger.button == b && !it.trigger.isChord }
        val maxTaps = singles.maxOfOrNull { when (it.trigger.press) { Press.DOUBLE_TAP -> 2; Press.TRIPLE_TAP -> 3; else -> 1 } } ?: 1
        if (t.count >= maxTaps) resolveTaps(p.src, t, b, singles)
        else t.resolve = Runnable { resolveTaps(p.src, t, b, singles) }.also { main.postDelayed(it, MULTI_TAP_MS) }
    }

    private fun resolveTaps(src: Int, t: Taps, b: ThorButton, singles: List<Binding>) {
        val n = t.count; t.count = 0
        val press = when (n) { 1 -> Press.TAP; 2 -> Press.DOUBLE_TAP; else -> Press.TRIPLE_TAP }
        val binding = singles.firstOrNull { it.trigger.press == press }
        if (binding != null) { Log.d(TAG, "${b.label} ${press.label} → ${binding.action}"); fire(src, binding.action, fromHold = false, arg = binding.arg) }
        // not bound → keep Home/Back working; each press whole, one after the other (a copied pad's Back needs
        // its release before the next press — review 2026-10-01)
        else repeat(n.coerceAtMost(3)) { i -> if (i == 0) host.nativePress(b, src) else main.postDelayed({ host.nativePress(b, src) }, 120L * i) }
    }
}

/** dev build 17 (theme G): the engine's pure rules — no Android, JVM-tested (SparesTest; build 18: ChordRulesTest). */
internal object ChordRules {
    /** How long a held list is up before letting go of its button counts as having read it (no tap then). Device pass
     *  (P2-10): here, so [listDelay] is tested with it (was ButtonEngine's). */
    const val PEEK_MS = 400L

    /** Device pass (P2-10): a held list is up at least this long before its button's own hold acts — [PEEK_MS] to count as
     *  read, then 150 ms to let go (the default's room: "Soon" 450 ms, read at 850, the 1 s hold at 1000). */
    const val LIST_BEFORE_HOLD_MS = PEEK_MS + 150L

    /** Device pass (P2-10): when a button's held list shows — [listMs] (the "Combo list while holding" setting), but never
     *  so late that the button's own hold ([holdMs]: when it fires, held alone; null = none) acts before the list can be
     *  read: [holdMs] − [LIST_BEFORE_HOLD_MS] at the latest (never below 0). "Later" (850 ms) on the touchpad click (its
     *  hold: swap apps at 1000) showed the list 150 ms before the hold and counted it read only at 1250. */
    fun listDelay(listMs: Long, holdMs: Long?): Long =
        if (holdMs == null) listMs else minOf(listMs, (holdMs - LIST_BEFORE_HOLD_MS).coerceAtLeast(0L))

    /** The modifier a press of [b] completes on one controller: what its Select / Start stand for under its Home first
     *  ([standIns]: its Back / AYN — "AYN + X" wins over "Home + X" while Home + Start is held), then what it holds, in the
     *  order pressed; null = no combo. With no stand-in: exactly the order before build 17. */
    fun modifierOf(standIns: Collection<ThorButton>, held: Collection<ThorButton>, b: ThorButton, bindings: List<Binding>): ThorButton? =
        (standIns.asSequence() + held.asSequence()).firstOrNull { m ->
            m != b && bindings.any { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier == m } }

    /** Build 18 (GitHub #81): how long before Home / Back a button the game got still counts as pressed WITH it (its
     *  release then is no tap). 0 keeps only a press in the same report; a withheld partner counts whatever the time. */
    const val EARLY_MS = 150L

    /** The partners already down when [m] (Home / Back) went down at [at], in the order held: a press its reader withheld
     *  ([gated]: [m] was down first there — its key just came after), or one the game got at most [windowMs] before
     *  ([downAt], the keys' event times). Never Home, Back or AYN; a button held longer is no partner (Home still goes home
     *  while you hold accelerate). b18l (#81, b18w B1): for Home, [lifted] counts as held too — let go by Home's gate in
     *  Home's own report and written before it (b18j's copy: X UP + KEY_HOME DOWN at one time): held up to Home's press. Not
     *  for Back: a Back that gates lets go only what neither side counts as held (b18h WI-4: a turbo, a toggle, a macro),
     *  so its tap stays a tap.
     *  b18o (2026-10-11): for Home, a turbo button the hand holds ([turbo], [GateLifts.turbo]) counts as held too, in
     *  its off half as well, by the time the hand pressed it ([downAt]: never refreshed by its pulses) — held a while, Home
     *  goes home every time; pressed just before Home, it is Home's partner as any button. Not for Back (WI-4, as above). */
    fun early(held: Collection<ThorButton>, gated: Set<ThorButton>, downAt: Map<ThorButton, Long>, m: ThorButton, at: Long,
              windowMs: Long = EARLY_MS, lifted: Collection<ThorButton> = emptyList(),
              turbo: Collection<ThorButton> = emptyList()): List<ThorButton> =
        (held + (if (m == ThorButton.HOME) (lifted + turbo).filter { it !in held } else emptyList())).distinct().filter { b ->
            b != m && !b.isSystem && b != ThorButton.AYN && (b in gated || downAt[b]?.let { t -> at - t in 0L..windowMs } == true) }

    /** b18l (#81, b18w B1): the buttons the input layer's gate let go in [m]'s own report, written BEFORE it (b18j's copy:
     *  the UHID report's buttons before Home and Back, one event time): their last key UP ([upAt]) at [at], [m]'s down, and
     *  not held again. Never Home, Back or AYN. */
    fun lifted(held: Collection<ThorButton>, upAt: Map<ThorButton, Long>, m: ThorButton, at: Long): Set<ThorButton> =
        upAt.filter { (b, t) -> t == at && b != m && !b.isSystem && b != ThorButton.AYN && b !in held }.keys.toSet()

    /** b18l: the same, let go AFTER [m]'s down in a report of its own (the copy since b18l): [b]'s key UP at [at], with Home /
     *  Back held ([systemHeld]), at the event time of their press ([sysDownAt]). */
    fun liftedNow(b: ThorButton, systemHeld: Boolean, at: Long, sysDownAt: Long): Boolean =
        systemHeld && at == sysDownAt && !b.isSystem && b != ThorButton.AYN

    /** b18l: a key DOWN from the copy that is a hand's press ([given]). */
    const val PRESSED = 0
    /** b18l: the gate giving back, with its Home / Back still held — written before their release in the same report (b18j). */
    const val GIVEN_UNDER = 1
    /** b18l: the gate giving back right after Home / Back's release, at its event time (the copy since b18l). */
    const val GIVEN_AFTER = 2
    /** b18o: a turbo's on-pulse — the hand still holds that turbo button and its own press came already ([GateLifts.keyDown]):
     *  no press at all (no key time, no combo, nothing touched, never a give-back that was a hand's). */
    const val PULSE = 3

    /** b18l: what a key DOWN of [b] from the copy at [at] is: the gate giving back what it let go at Home / Back's press
     *  ([lifted]) — under the Home / Back still held ([systemHeld]) → [GIVEN_UNDER]: no combo, not "touched" (b18j made it
     *  Home + R2 = Brighter, and Home didn't go home); right after their release, at its time ([sysUpAt]) → [GIVEN_AFTER].
     *  Either way the button keeps the time it was pressed ([early]). Else [PRESSED]. */
    fun given(b: ThorButton, lifted: Set<ThorButton>, systemHeld: Boolean, at: Long, sysUpAt: Long): Int = when {
        b !in lifted -> PRESSED
        systemHeld -> GIVEN_UNDER
        at == sysUpAt -> GIVEN_AFTER
        else -> PRESSED
    }

    /** b18l: Home / Back let go at [at] after buttons given back under it ([givenAt]: their event times, [GIVEN_UNDER]): one
     *  that came at another time was a hand's press (the gate gives back in that very report) — the release is no tap then,
     *  as after any button pressed under it. */
    fun handPressed(givenAt: Collection<Long>, at: Long): Boolean = givenAt.any { it != at }

    /** Build 18 (#81, review): does a press its reader withheld ([b], now in [held]) prove its Home / Back was down there?
     *  Not the profile's hold-to-shift button ([shift], §6l: its reader always withholds it) nor a press while it's held
     *  (the shift's gate withheld it): a held shift + a Home tap made "Home + R1" and never went home. */
    fun withheldForHome(b: ThorButton, shift: ThorButton?, held: Collection<ThorButton>): Boolean =
        shift == null || (b != shift && shift !in held)

    /** b18n (#102 a): Android gives a key-filtering accessibility service 500 ms to answer a key (KeyEventDispatcher), from
     *  when it hands the key over — at or after the key's event time — then passes it to the app anyway. A key reaching the
     *  engine this late may already be the app's, and an answer sent now has under 100 ms to get back: it is treated as
     *  passed on. */
    const val HANDED_ON_MS = 400L

    /** b18n: a key with [eventTime] reaching the engine at [now] (both uptime ms) is one Android has passed on, or may yet
     *  pass on whatever we answer ([HANDED_ON_MS] or later). No key time (0) or a time ahead of [now]: not late. */
    fun handedOn(now: Long, eventTime: Long): Boolean = eventTime > 0 && now - eventTime >= HANDED_ON_MS
}

/**
 * b18l (GitHub #81, b18w B1): one controller's buttons the input layer's gate lets go at a Home / Back press and gives back
 * at their release (fx/wfmap.h), as its copy's keys show them. The copy writes Home / Back's change in a report of its own
 * since b18l; b18j wrote one report, buttons first — R2 given back before Home's release made Home + R2 (Brighter, no home),
 * X let go before Home's press was no partner (it went home). Either order reads the same here ([ChordRules.lifted],
 * [ChordRules.given]). Pure (ChordRulesTest replays b18w's captures). Main thread.
 * b18o: and the turbo buttons the hand holds ([turboSaid]) — their on / off pulses on the copy are no presses ([keyDown]:
 * [ChordRules.PULSE]), never a give-back that was a hand's press either (TurboHomeTest). b18o review: and each pulse gets the
 * answer the hand's press got ([pulseDown]): a combo's turbo button never reaches the game while the hand holds it.
 */
internal class GateLifts {
    /** Each button's last key UP from the copy (its event time). */
    private val upAt = HashMap<ThorButton, Long>()
    /** Let go by the gate at the last Home / Back press — until given back, let go for good, or the next first press. */
    val lifted = HashSet<ThorButton>()
    /** Given back under the held Home / Back ([ChordRules.GIVEN_UNDER]): when, each. */
    private val givenAt = HashMap<ThorButton, Long>()
    private var sysDownAt = Long.MIN_VALUE
    private var sysUpAt = Long.MIN_VALUE

    /** [m] (Home / Back) went down at [at]; [held]: the controller's held buttons, [m] among them. A first Home / Back
     *  starts afresh; what the gate let go in its report before it joins [lifted]. */
    fun systemDown(m: ThorButton, at: Long, held: Collection<ThorButton>) {
        if (held.none { it.isSystem && it != m }) lifted.clear()
        lifted += ChordRules.lifted(held, upAt, m, at)
        sysDownAt = at
    }

    /** A button's key UP from the copy at [at] ([systemHeld]: Home / Back held on that controller). */
    fun keyUp(b: ThorButton, at: Long, systemHeld: Boolean) {
        upAt[b] = at
        if (ChordRules.liftedNow(b, systemHeld, at, sysDownAt)) lifted += b else lifted -= b
    }

    /** A button's key DOWN from the copy at [at]: a hand's press ([ChordRules.PRESSED]) or the gate giving it back. b18o: or a
     *  turbo's on-pulse ([ChordRules.PULSE]) — a turbo button the hand holds ([turbo]) whose own press came already: no
     *  press, and no longer let go by the gate (it is on again). Its first DOWN at or after the hand's press is the press. */
    fun keyDown(b: ThorButton, at: Long, systemHeld: Boolean): Int {
        turbos[b]?.let { t -> if (t.seen) { lifted -= b; return ChordRules.PULSE }; if (at >= t.at) t.seen = true }
        val g = ChordRules.given(b, lifted, systemHeld, at, sysUpAt)
        if (g != ChordRules.PRESSED) lifted -= b
        if (g == ChordRules.GIVEN_UNDER) givenAt[b] = at
        return g
    }

    /** Home / Back let go at [at]: true = something given back under it was a hand's press after all — no tap. */
    fun systemUp(at: Long): Boolean {
        sysUpAt = at
        return ChordRules.handPressed(givenAt.values, at)
    }

    /** [b] let go under the gate (its reader withheld the release): nothing to give back. */
    fun letGo(b: ThorButton) { lifted -= b }

    /** Home and Back both let go. */
    fun spend() { givenAt.clear() }

    // ── b18o (2026-10-11 — GitHub #81's turbo leftover): turbo buttons the hand holds ──
    /** One held: when the hand pressed it ([at], the keys' clock), who says so (bits: ButtonEngine.TURBO_LAYER / TURBO_APP),
     *  and whether the press's own key DOWN came yet ([seen]: every DOWN after it is a pulse). */
    private class Turbo(val at: Long, var by: Int, var seen: Boolean)
    private val turbos = HashMap<ThorButton, Turbo>()
    /** The buttons a turbo the hand holds drives — held, in the off half of their pulse or under a gate as well. */
    val turbo: Set<ThorButton> get() = turbos.keys

    /** [by] says the hand holds ([held]) — or let go of — a turbo button whose job is [b], pressed at [at]; [lastDown]: [b]'s
     *  last key time as the engine recorded it. True = record [at] as [b]'s press time. A new hold: its own key DOWN came
     *  already if [lastDown] is at or after [at] (the line came after the key), else the next DOWN is it. Said held again by
     *  the same [by] (a release that never came: an engine that restarted): a new press. Held by the other one too: one hold,
     *  the first press's time; let go when neither holds it. */
    fun turboSaid(b: ThorButton, held: Boolean, at: Long, by: Int, lastDown: Long?): Boolean {
        val t = turbos[b]
        if (!held) {
            if (t != null) { t.by = t.by and by.inv(); if (t.by == 0) turbos.remove(b) }
            return false
        }
        if (t != null && (t.by and by) == 0) { t.by = t.by or by; return false }
        turbos[b] = Turbo(at, (t?.by ?: 0) or by, seen = lastDown != null && lastDown >= at)
        return true
    }

    /** Every turbo button [by] said held is let go (its engine ended). */
    fun turboGone(by: Int) {
        turbos.values.forEach { it.by = it.by and by.inv() }
        turbos.values.removeAll { it.by == 0 }
    }

    // ── b18o review: a pulse gets the answer its hand's press got ──
    /** The buttons whose last press (a key DOWN that was no pulse) the engine swallowed: a combo's second button. */
    private val swallowedPress = HashSet<ThorButton>()
    /** Pulses swallowed ([pulseDown]) whose UP hasn't come yet. */
    private val pulsesDown = HashSet<ThorButton>()

    /** The engine's answer to a press of [b] (a key DOWN that was no pulse): [swallowed] or passed to the game. A new press
     *  also ends what an older one's pulses left (an UP that never came). */
    fun pressAnswered(b: ThorButton, swallowed: Boolean) {
        if (swallowed) swallowedPress += b else swallowedPress -= b
        pulsesDown -= b
    }

    /** A turbo's pulse of [b] came down ([ChordRules.PULSE]): true = swallow it — the hand's press was swallowed (a combo's
     *  second button: AYN + a turbo R2, or R3 + it). It is that press still held: the game never sees it, as a button that
     *  isn't turbo, till the hand lets go — the modifier let go meanwhile too. False = the game's, as its press was. */
    fun pulseDown(b: ThorButton): Boolean = (b in swallowedPress).also { if (it) pulsesDown += b }

    /** A key UP of [b]: true = the UP of a pulse swallowed ([pulseDown]) — swallowed too, and no release of the combo. */
    fun pulseUp(b: ThorButton): Boolean = pulsesDown.remove(b)
}

/**
 * b18g (WI-6e, GitHub #102 a / #41): Home and Back that reach the engine late — the evidence (the log changes nothing).
 * Android gives a key-filtering accessibility service 500 ms to answer a key, then hands it to the app anyway; Wayfinder
 * treated a press it answered that late as its own and replayed it (the game got two Backs) — b18n: a Back that late is the
 * game's ([ChordRules.handedOn], [ButtonEngine.onKey]). One release log line per late key, at most [PER_WINDOW] a minute
 * ([WINDOW_MS]); the next line counts the ones left out. Pure (P3BundleTest).
 */
internal class LateKeyLog {
    private var windowAt: Long? = null
    private var shown = 0
    private var notLogged = 0

    /** The line for [label] (down or up) that happened at [eventTime] and reached the engine at [now] (uptime ms), or null:
     *  on time, or this minute's lines are spent (counted for the next line). */
    fun line(label: String, down: Boolean, now: Long, eventTime: Long): String? {
        val late = now - eventTime
        if (late <= LATE_MS) return null
        val at = windowAt
        if (at == null || now - at >= WINDOW_MS) { windowAt = now; shown = 0 }
        if (shown >= PER_WINDOW) { notLogged++; return null }
        shown++
        val more = if (notLogged > 0) " (+$notLogged more not logged)" else ""
        notLogged = 0
        return "late key $label ${if (down) "down" else "up"} $late ms$more"
    }

    companion object {
        /** Late: well past a normal hand-over (a few ms), still under the system's 500 ms. */
        const val LATE_MS = 300L
        const val PER_WINDOW = 5
        const val WINDOW_MS = 60_000L
    }
}
