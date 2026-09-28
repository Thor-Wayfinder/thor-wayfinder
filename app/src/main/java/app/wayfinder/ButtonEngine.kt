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
 */
class ButtonEngine(private val host: Host) {

    interface Host {
        fun perform(action: ThorAction, fromHold: Boolean, arg: String? = null)
        /** Re-perform a system button's normal job (Home / Back). */
        fun nativePress(button: ThorButton)
        fun showChordHint(modifier: ThorButton, chords: List<Binding>)
        /** The app with the controller has "Buttons: Off" (its name), or null. */
        fun combosOffApp(): String?
        fun showCombosOff(app: String)
        fun hideChordHint()
        /** Package of the app that has the controller right now (per-app buttons). */
        fun currentApp(): String?
        /** Hold the triggers' ANALOG signal back from the app (see [AnalogShield]). */
        fun shieldAnalog(on: Boolean) {}
    }

    companion object {
        /** Home or Back is held right now (the keyboard lets a combo's second button through). */
        @Volatile var systemHeld = false

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
            // AYN's Xbox mode = its pad as 2020:0112 (by id, not by name: an external "Xbox Wireless Controller"
            // is re-emitted by AYN as 2020:0111 under that name, and its buttons are already where they're printed)
            val identityXbox = dev != null && dev.vendorId == 0x2020 && dev.productId == 0x0112
            // The input layer may force a face layout for the current app (its copy then sends
            // swapped codes under the same identity): read the button through that layout.
            val xbox = when (if (PadLayerCtl.active) PadLayerCtl.layoutOverride else null) {
                'x' -> true; 'n' -> false; else -> identityXbox
            }
            if (isController(dev) && raw in FACE) padXbox = xbox && dev?.vendorId == 0x2020
            if (!xbox) return raw
            return when (raw) {
                ThorButton.A -> ThorButton.B; ThorButton.B -> ThorButton.A
                ThorButton.X -> ThorButton.Y; ThorButton.Y -> ThorButton.X
                else -> raw
            }
        }

        private val FACE = setOf(ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y)

        /** The pad was in Xbox style at its last face-button press (AYN's style or the game's layout). */
        @Volatile var padXbox: Boolean? = null
            private set

        /** 1.3 (GitHub #8): what a face button MEANS in a menu (keyboard, Recents). Combos use the
         *  printed label ([printedButton]); menus follow the style, like the Hub and every app:
         *  in Xbox style the bottom button confirms (A) and the right one goes back (B). */
        fun menuButton(event: KeyEvent): ThorButton? = printedButton(event)?.let { ThorButton.fromKeyCode(event.keyCode) ?: it }

        /** Menu meaning ↔ printed button (the same swap both ways) — for the button badges. */
        fun menuSwap(b: ThorButton): ThorButton = if (!(padXbox ?: ButtonNames.aynXbox)) b else ButtonNames.faceSwap(b)

        private const val TAG = "ThorButtons"
        private const val HOLD_MS = 1000L
        private const val MULTI_TAP_MS = 300L
        private const val HINT_MS = 450L
        private const val PEEK_MS = 400L
    }

    /** Configurator capture mode: receives every press (button, down) and swallows keys. */
    @Volatile var capture: ((ThorButton, Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val held = LinkedHashSet<ThorButton>()        // main-thread only
    private val swallowed = HashSet<ThorButton>()         // keys whose UP we must also swallow
    private val chordUsed = HashSet<ThorButton>()         // modifiers that completed a chord this press
    private val capturedUps = HashSet<ThorButton>()       // captured presses whose release must be swallowed too

    // Per system button (Home/Back) tap state.
    private class Taps { var count = 0; var holdFired = false; var resolve: Runnable? = null; var hold: Runnable? = null }
    private val taps = mapOf(ThorButton.HOME to Taps(), ThorButton.BACK to Taps())
    private var hintFor: ThorButton? = null
    private var shieldFor: ThorButton? = null
    // Home held in an app whose combos are off: say so, instead of nothing (the combos just
    // seemed broken — release test 2026-09-24). Home itself still goes to Android.
    private var offHintShown = false
    private val offHintRunnable = Runnable {
        if (ThorButton.HOME !in held) return@Runnable
        host.combosOffApp()?.let { host.showCombosOff(it); offHintShown = true }
    }
    private val hintRunnable = Runnable {
        val m = hintFor ?: return@Runnable
        if (m in held && m !in chordUsed) {
            host.showChordHint(m, ControlsStore.effective(host.currentApp()).filter { it.trigger.modifier == m })
            hintShownAt = android.os.SystemClock.uptimeMillis()
        }
    }
    // Held to read the combos, then let go without one: that's a peek, not a Home / Back press —
    // it used to go Home and leave the game (found by the tour's "hold Home" step, 2026-09-25).
    // Only once the hint has been up a moment, so a slow deliberate tap still goes Home.
    private var hintShownAt = 0L
    private fun peeked(): Boolean = hintShownAt != 0L && android.os.SystemClock.uptimeMillis() - hintShownAt >= PEEK_MS

    // ── keys ───────────────────────────────────────────────────────────
    fun onKey(event: KeyEvent): Boolean {
        val b = physicalButton(event) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false

        // A key whose press was captured: its release is swallowed too, even if the capture
        // ended in between (Home cancels "listening" on its press — its release then went Home).
        if (!down && capturedUps.remove(b)) { capture?.invoke(b, false); return true }
        capture?.let { cb ->
            if (BuildConfig.DEBUG) Log.d(TAG, "capture $b ${if (down) "down" else "up"}")
            if (down && event.repeatCount == 0) { capturedUps.add(b); cb(b, true) } else if (!down) cb(b, false)
            return true
        }
        if (down && event.repeatCount > 0) return b in swallowed
        val bindings = ControlsStore.effective(host.currentApp())

        if (down) {
            held.add(b)
            if (b.isSystem) systemHeld = true
            // Home / Back stop the game's macros and release what the app holds for it
            if (b.isSystem && !(b == ThorButton.BACK && host.currentApp()?.let { ControlsStore.backToGame(it) } == true)) ExtEngine.releaseAll()
            // 1) Second button of a chord whose modifier is held → fire, swallow.
            val chord = bindings.firstOrNull { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier != b && it.trigger.modifier in held }
            if (chord != null) { fireChord(chord); swallowed.add(b); return true }
            // 2) A modifier: arm the hint.
            if (bindings.any { it.trigger.modifier == b }) {
                chordUsed.remove(b); hintFor = b; hintShownAt = 0L
                main.removeCallbacks(hintRunnable); main.postDelayed(hintRunnable, HINT_MS)
                // Its combos use L2/R2 or a stick: that analog movement would still reach the
                // app — unless the input layer runs (it withholds everything while Home/Back
                // is held, so no shield and no lost window focus).
                if (!(layerActive && b.isSystem) && bindings.any { it.trigger.modifier == b &&
                        (it.trigger.button == ThorButton.L2 || it.trigger.button == ThorButton.R2 || it.trigger.button.isFlick) }) {
                    shieldFor = b; host.shieldAnalog(true)
                }
            }
            // 3) Home + Back together = the input layer's emergency switch (held 5 s), never a
            //    shortcut: cancel whatever either was about to do (Back's hold = move/swap).
            val other = when (b) { ThorButton.HOME -> ThorButton.BACK; ThorButton.BACK -> ThorButton.HOME; else -> null }
            if (other != null && other in held) {
                for (s in listOf(b, other)) taps.getValue(s).let { t ->
                    t.hold?.let { main.removeCallbacks(it) }; t.hold = null
                    t.resolve?.let { main.removeCallbacks(it) }; t.resolve = null; t.count = 0
                }
                chordUsed.add(b); chordUsed.add(other)
                main.removeCallbacks(hintRunnable); host.hideChordHint()
                swallowed.add(b); return true
            }
            // 4) Home/Back with anything bound → we own it.
            if (b.isSystem && owns(b, bindings)) { systemDown(b, bindings); swallowed.add(b); return true }
            // another button pressed while Home / Back is held: it's not a tap of Home / Back any
            // more (the layer off, Home + A released Home as a tap and left the game — review)
            if (!b.isSystem) touchedDuringModifier()
            if (b == ThorButton.HOME && bindings.isEmpty()) { main.removeCallbacks(offHintRunnable); main.postDelayed(offHintRunnable, HINT_MS) }
            return false
        } else {
            held.remove(b)
            if (b.isSystem) systemHeld = held.any { it.isSystem }
            if (b == ThorButton.HOME) { main.removeCallbacks(offHintRunnable); if (offHintShown) { offHintShown = false; host.hideChordHint() } }
            if (shieldFor == b) { shieldFor = null; host.shieldAnalog(false) }
            val peek = hintFor == b && peeked()
            if (hintFor == b) { main.removeCallbacks(hintRunnable); host.hideChordHint(); hintFor = null; hintShownAt = 0L }
            if (peek && b.isSystem) { Log.d(TAG, "${b.label} released after reading the combos — nothing"); chordUsed.add(b) }
            if (swallowed.remove(b)) { if (b.isSystem) systemUp(b, bindings); return true }
            return false
        }
    }

    /**
     * The PRINTED button that was pressed. AYN's "controller style" setting (and its
     * per-game auto-switch) flips the pad between two identities:
     *  - Odin / Nintendo layout — "Odin Controller", product 0x0111: Android keycodes
     *    match the printed labels (verified by pressing every button, 2026-09-22);
     *  - Xbox layout — "Xbox Wireless Controller", product 0x0112: face buttons are
     *    reported by POSITION, so printed A↔B and X↔Y swap (verified: printed A, B,
     *    X, Y → BUTTON_B, A, Y, X).
     * Bindings are stored as printed buttons, so a combo stays on the same physical
     * button whichever layout is active.
     */
    private fun physicalButton(event: KeyEvent): ThorButton? = printedButton(event)

    // ── Input layer: presses the game did NOT get (Home/Back held) — any thread ──
    /**
     * A button withheld from the game by the input layer (wfpad's gate). Keys pressed while
     * Home/Back is held never reach the accessibility filter any more, so a combo's second
     * button arrives here — and needs no swallowing: the game never saw it.
     */
    fun onGated(b: ThorButton, down: Boolean) = main.post {
        if (b.isSystem) return@post                       // Home/Back themselves still come via onKey
        capture?.let { it(b, down); return@post }
        if (!down) { held.remove(b); return@post }
        if (!held.add(b)) return@post
        touchedDuringModifier()
        ControlsStore.effective(host.currentApp())
            .firstOrNull { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier in held }
            ?.let { fireChord(it) }
    }

    /** A stick flick (see [ThorButton.isFlick]): a press and release in one. */
    fun onFlick(b: ThorButton) = main.post {
        capture?.let { it(b, true); it(b, false); return@post }
        touchedDuringModifier()
        ControlsStore.effective(host.currentApp())
            .firstOrNull { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier in held }
            ?.let { fireChord(it) }
    }

    /** Something else was used while Home/Back was held: letting go of it is then NOT a tap of
     *  Home/Back (you were trying a combo, even an unbound one) — never an unwanted Home. */
    private fun touchedDuringModifier() { held.filter { it.isSystem }.forEach { chordUsed.add(it); cancelHold(it) } }
    /** A Home / Back hold that was going to fire: not any more — the button became a combo's modifier
     *  (its hold ran at 1 s in the middle of a combo, review 2026-09-25). */
    private fun cancelHold(b: ThorButton) { taps[b]?.let { t -> t.hold?.let { main.removeCallbacks(it) }; t.hold = null } }

    /** The input layer's copy is the pad: Home/Back hold everything else back themselves. */
    private val layerActive get() = PadLayerCtl.active

    // ── D-pad (from evdev, any thread) ─────────────────────────────────
    fun onHat(axis: Int, value: Int) = main.post {
        val dirs = ThorButton.values().filter { it.hatAxis == axis }
        if (value == 0) {
            dirs.forEach { d -> if (held.remove(d)) capture?.invoke(d, false) }
            return@post
        }
        val b = ThorButton.fromHat(axis, value) ?: return@post
        dirs.filter { it != b }.forEach { held.remove(it) }
        if (!held.add(b)) return@post
        capture?.let { it(b, true); return@post }
        touchedDuringModifier()
        ControlsStore.effective(host.currentApp())
            .firstOrNull { it.trigger.isChord && it.trigger.button == b && it.trigger.modifier in held }
            ?.let { fireChord(it) }
    }

    private fun fireChord(chord: Binding) {
        val m = chord.trigger.modifier!!
        chordUsed.add(m)
        cancelHold(m)
        main.removeCallbacks(hintRunnable); host.hideChordHint()
        Log.d(TAG, "chord ${chord.trigger.label()} → ${chord.action}")
        host.perform(chord.action, fromHold = false, arg = chord.arg)
    }

    // ── Home / Back press types ───────────────────────────────────────
    private fun owns(b: ThorButton, bindings: List<Binding>) =
        bindings.any { (it.trigger.button == b && !it.trigger.isChord) || it.trigger.modifier == b }

    private fun systemDown(b: ThorButton, bindings: List<Binding>) {
        val t = taps.getValue(b)
        t.resolve?.let { main.removeCallbacks(it) }       // a further tap may be coming
        t.holdFired = false
        val hold = bindings.firstOrNull { it.trigger.button == b && !it.trigger.isChord && it.trigger.press == Press.HOLD }
        if (hold != null) {
            t.hold = Runnable {
                t.holdFired = true; t.count = 0
                Log.d(TAG, "${b.label} hold → ${hold.action}")
                host.perform(hold.action, fromHold = true, arg = hold.arg)
            }.also { main.postDelayed(it, HOLD_MS) }
        }
    }

    private fun systemUp(b: ThorButton, bindings: List<Binding>) {
        val t = taps.getValue(b)
        t.hold?.let { main.removeCallbacks(it) }; t.hold = null
        if (t.holdFired || chordUsed.remove(b)) { t.count = 0; t.holdFired = false; return }
        t.count++
        val singles = bindings.filter { it.trigger.button == b && !it.trigger.isChord }
        val maxTaps = singles.maxOfOrNull { when (it.trigger.press) { Press.DOUBLE_TAP -> 2; Press.TRIPLE_TAP -> 3; else -> 1 } } ?: 1
        if (t.count >= maxTaps) resolveTaps(b, singles)
        else t.resolve = Runnable { resolveTaps(b, singles) }.also { main.postDelayed(it, MULTI_TAP_MS) }
    }

    private fun resolveTaps(b: ThorButton, singles: List<Binding>) {
        val t = taps.getValue(b)
        val n = t.count; t.count = 0
        val press = when (n) { 1 -> Press.TAP; 2 -> Press.DOUBLE_TAP; else -> Press.TRIPLE_TAP }
        val binding = singles.firstOrNull { it.trigger.press == press }
        if (binding != null) { Log.d(TAG, "${b.label} ${press.label} → ${binding.action}"); host.perform(binding.action, fromHold = false, arg = binding.arg) }
        else repeat(n.coerceAtMost(3)) { host.nativePress(b) }   // not bound → keep Home/Back working
    }
}
