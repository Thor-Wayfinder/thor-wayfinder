package app.wayfinder

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * Input layer, phase 3 — the buttons the app plays. wfpad sends
 * their presses as "X" lines ([PadRemap.byApp]); here they become keyboard keys, mouse buttons,
 * Wayfinder actions, several pad buttons at once, typed key sequences, macros, long / double
 * presses and chords. Pad outputs go back to the game's pad as wfpad VIRTUAL presses
 * ("G p <code> <0|1>", silent while Home / Back are held).
 *
 * Own thread (timing must not wait for the UI). Everything it holds is tracked, so a profile switch or
 * the layer going away release all of it ([releaseAll]); Home / Back release their controller's.
 *
 * Several SOURCES: 0 = the Thor (wfpad), n > 0 = an external controller (wfhid "W" / "T"
 * lines, n = its hidraw number + 1). Each source has its own presses, macros and sticks; a pad output goes back to
 * the controller that asked for it ([padSink]: "G p" for the Thor, "E p <hidrawN>" for an external one).
 */
object ExtEngine {
    private const val TAG = "ThorExt"
    const val CHORD_MS = 60L
    const val LONG_MS = 400L
    const val DOUBLE_MS = 250L
    const val TAP_MS = 60L
    const val TYPE_MS = 30L

    private val thread by lazy { HandlerThread("ext").apply { start() } }
    private val h by lazy { Handler(thread.looper) }
    private val main = Handler(Looper.getMainLooper())
    /** Token of everything scheduled here: [releaseAll] cancels it all at once. */
    private val TOK = Any()
    /** dev build 14: what one source scheduled (its presses' timers, macros, typing) — [releaseAll] (src) cancels only it. */
    private val toks = HashMap<Int, Any>()
    private fun tok(src: Int) = toks.getOrPut(src) { Any() }

    /** The profile of the app that has the controller (the service's 500 ms tick). */
    @Volatile var remap: PadRemap? = null
    /** A connected controller's OWN controls (Game controls → that controller), else the Thor's ([remap]). */
    @Volatile var padRemap: (Int) -> PadRemap? = { null }
    private fun remapOf(src: Int): PadRemap? = if (src > 0) padRemap(src) ?: remap else remap
    /** Build 18 (review, GitHub #81): controller [src]'s hold-to-shift button (§6l, null = none) — what its gate withholds
     *  is no sign of a Home already down ([ButtonEngine.onGated]). */
    fun shiftOf(src: Int): ThorButton? = remapOf(src)?.shift
    /** Where keyboard keys go: the screen that has the controller. */
    @Volatile var display: () -> Int = { 0 }
    /** A Wayfinder action (run on the main thread) — dev build 17 (theme G): with its source, a controller's own "→ Android
     *  Back" presses Back on THAT controller's pad. */
    @Volatile var perform: (ThorAction, Int) -> Unit = { _, _ -> }

    fun onExt(code: Int, value: Int, src: Int = 0) { if (value == 1) app.wayfinder.ui.InputMode.notePadKey(); h.post { handle(code, value, src) } }   // build 18 (A0)

    /** A pad press for source [src] > 0 (an external controller): set by the app (ExtPadsCtl). */
    @Volatile var padSink: (src: Int, code: Int, down: Int) -> Unit = { _, _, _ -> }
    /** b18o (2026-10-11 — #81's turbo leftover): a turbo played here whose job is pad button [b] of source [src] — the
     *  hand holds it ([held], since [at]: uptime, the keys' clock) or let go. Set by the app: ButtonEngine takes its pulses
     *  on the pad for no presses ([ButtonEngine.onTurbo]), as the input layer's ("TU"). */
    @Volatile var turboSink: (src: Int, b: ThorButton, held: Boolean, at: Long) -> Unit = { _, _, _, _ -> }
    /** b18o: [t]'s pad buttons, a turbo of [src] held or let go ([turboSink]); nothing for a job that isn't pad buttons. */
    private fun turboSaid(t: RemapTarget?, src: Int, held: Boolean) {
        val bs = when (t) { is RemapTarget.Button -> listOf(t.b); is RemapTarget.Buttons -> t.bs; else -> return }
        val at = android.os.SystemClock.uptimeMillis()
        bs.forEach { turboSink(src, it, held, at) }
    }
    private fun srcOf(who: Int) = who ushr 16
    private fun whoOf(src: Int, id: Int) = (src shl 16) or id

    // ── hold-to-shift (§6l): while the profile's shift button is held, wfpad withholds everything
    // from the game and echoes it ("J" lines); a button with a "With Shift held" job plays it here.
    // per source (the Thor, each external controller): whose shift button is held, what its layer holds
    private val shiftHeldBy = HashSet<Int>()
    private val shiftActive = HashMap<Int, RemapTarget>()      // (source, button) → its shifted output
    /** dev build 17 (critique K12, WP10): the controller whose shift button was pressed last — its list shows after the delay. */
    private var shiftHintFor = 0
    private val shiftHint = Runnable { if (shiftHintFor in shiftHeldBy) showShiftHint(shiftHintFor) }
    /** dev build 17 (critique K12): the controllers whose shift list is up — each one's release, shifted press or end hides
     *  only its own (one held list per controller, [Held.SHIFT]). */
    private val shiftListsUp = HashSet<Int>()
    private fun hideShiftList(src: Int) { if (shiftListsUp.remove(src)) Overlays.release(Held.SHIFT, src) }
    fun onGated(code: Int, value: Int, src: Int = 0) { h.post { gated(code, value, src) } }
    private fun gated(code: Int, value: Int, src: Int) {
        if (value == 2) return
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remapOf(src) ?: return
        val sh = r.shift
        if (b == sh) {
            // dev build 16 (theme F): "Combo list while you hold a button" — later when asked (off: [showShiftHint] skips it)
            if (value != 0) {
                shiftHeldBy.add(src); shiftHintFor = src
                h.removeCallbacks(shiftHint); h.postDelayed(shiftHint, if (AppSettings.chordHint == 1) 850L else 450L)
            } else {
                shiftHeldBy.remove(src)
                if (shiftHintFor == src) h.removeCallbacks(shiftHint)
                hideShiftList(src)
            }
            return
        }
        val k = srcKey(src, b)
        if (value == 1) {
            if (src !in shiftHeldBy || sh == null) return
            val t = r.shifted[b] ?: return
            if (shiftHintFor == src) h.removeCallbacks(shiftHint)
            hideShiftList(src)
            shiftActive[k] = t; out(t, true, whoOf(src, 0x2000 + code))   // a pad output: that controller's
        } else shiftActive.remove(k)?.let { out(it, false, whoOf(src, 0x2000 + code)) }   // its own release ends it
    }
    /** dev build 16 (theme F): the layer of the controller holding its shift button, in that controller's names (it read the
     *  Thor's profile and names: a DualSense's own shifted jobs never showed). It obeys "Combo list while you hold a
     *  button" too (off: none; the tour still shows it). dev build 17 (WP10): a held list of that controller, on the
     *  controller's screen ([Overlays.held]). */
    private fun showShiftHint(src: Int) {
        if (AppSettings.chordHint == 2 && !TourPractice.active) return
        val r = remapOf(src) ?: return
        val sh = r.shift ?: return
        if (r.shifted.isEmpty()) return
        val look = ButtonNames.lookOfSrc(src)
        if (Overlays.held(Held.SHIFT, CueOrigin.Press(src), listOf(Say.keepHeld(sh, look)),
                r.shifted.map { (k, v) -> k.spoken(look) to v.short(look) })) shiftListsUp += src
    }
    /** A new profile, the layer off: stop macros, release everything. */
    fun releaseAll() { h.post { reset() } }

    /** Home / Back pressed on one controller ([src]): only what IT holds goes — another player's macros, keys and mouse
     *  stick keep going (dev build 14, combos 5: either player's Home stopped the other's). */
    fun releaseAll(src: Int) { h.post { release(src) } }

    /** An external controller went away ([src]): what it held of Wayfinder's (a button's output, a running macro, a stick
     *  moving the mouse or holding keys, its shift layer) is let go, and its state goes: a new controller on that node
     *  starts clean (review 2026-10-03). Keys and the mouse are counted per controller now, so the others keep theirs. */
    fun gone(src: Int) { if (src > 0) h.post { release(src) } }

    /** Everything [src] holds goes, the others' stays: its presses and their timers, its macros and typing, its sticks and
     *  shift layer; its pad presses, keys and mouse buttons ([keyBy] / [mouseBy]: what it counts of the shared ones). */
    private fun release(src: Int) {
        h.removeCallbacksAndMessages(tok(src))
        srcs.forEach { (k, s) -> if (k ushr 8 == src && s.turbo != null) turboSaid(s.active, src, false) }   // b18o: its turbos end
        srcs.keys.removeAll { it ushr 8 == src }
        plays.keys.removeAll { srcOf(it) == src }
        if (shiftHeldBy.remove(src) && shiftHintFor == src) h.removeCallbacks(shiftHint)
        hideShiftList(src)
        shiftActive.keys.removeAll { it ushr 8 == src }
        for (side in 0..1) { stickPos.remove(src * 2 + side); stickKeys.remove(src * 2 + side); scrollAcc.remove(src * 2 + side); scrolling.remove(src * 2 + side) }
        if (padDown.keys.any { srcOf(it) == src }) {
            if (src == 0) InputMonitor.send("G p 0 0") else padSink(src, 0, 0)
            padDown.keys.removeAll { srcOf(it) == src }
        }
        keyBy.remove(src)?.forEach { (c, n) -> repeat(n) { if (ref(keyDown, c, false)) InputMonitor.send("K $c 0 0 ${keyDisplay.remove(c) ?: display()}") } }
        mouseBy.remove(src)?.forEach { (b, n) -> repeat(n) { if (ref(mouseDown, b, false)) InputMonitor.send("B $b 0") } }
        if (BuildConfig.DEBUG) Log.d(TAG, "released source $src")
    }

    // ── what's held (reference counts: two buttons may hold the same output) ──
    private val padDown = HashMap<Int, Int>()
    private val keyDown = LinkedHashMap<Int, Int>()
    private val mouseDown = HashMap<Int, Int>()
    // the same, per source: what each one holds of [keyDown] / [mouseDown] (its own release lets go of only that)
    private val keyBy = HashMap<Int, HashMap<Int, Int>>()
    private val mouseBy = HashMap<Int, HashMap<Int, Int>>()

    private fun later(ms: Long, src: Int, r: () -> Unit) { h.postDelayed(r, tok(src), ms) }

    private fun ref(m: MutableMap<Int, Int>, c: Int, down: Boolean): Boolean {
        val n = m[c] ?: 0
        return if (down) { m[c] = n + 1; n == 0 } else if (n > 0) { if (n == 1) m.remove(c) else m[c] = n - 1; n == 1 } else false
    }
    /** [src]'s own count of [c]: an up from a source that doesn't hold it changes nothing (false). */
    private fun refBy(by: HashMap<Int, HashMap<Int, Int>>, src: Int, c: Int, down: Boolean): Boolean {
        val m = by.getOrPut(src) { HashMap() }
        if (!down && (m[c] ?: 0) == 0) return false
        ref(m, c, down); return true
    }
    private fun pad(b: ThorButton, down: Boolean, who: Int) {
        val c = PadRemap.outCode(b) ?: return
        val src = srcOf(who)
        if (!ref(padDown, whoOf(src, c), down)) return
        if (src == 0) InputMonitor.send("G p $c ${if (down) 1 else 0}") else padSink(src, c, if (down) 1 else 0)
    }
    private fun meta() = keyDown.keys.fold(0) { m, c -> m or RemapTarget.metaOf(c) }
    private val keyDisplay = HashMap<Int, Int>()
    private fun key(code: Int, down: Boolean, src: Int, extraMeta: Int = 0) {
        if (!refBy(keyBy, src, code, down) || !ref(keyDown, code, down)) return
        // the up goes where the down went: a touch on the other screen in between left it held (review 2026-09-25)
        val d = if (down) display().also { keyDisplay[code] = it } else keyDisplay.remove(code) ?: display()
        InputMonitor.send("K $code ${if (down) 1 else 0} ${meta() or extraMeta or (if (down) 0 else RemapTarget.metaOf(code))} $d")
    }
    private fun mouse(b: Int, down: Boolean, src: Int) {
        if (b > 2) { if (down) InputMonitor.send("W ${if (b == 3) 1 else -1} 0"); return }
        if (refBy(mouseBy, src, b, down) && ref(mouseDown, b, down)) InputMonitor.send("B $b ${if (down) 1 else 0}")
    }

    /** Press / release one output. [who] = the source (a button code, or a chord's id). */
    private fun out(t: RemapTarget, down: Boolean, who: Int) {
        val src = srcOf(who)
        when (t) {
            is RemapTarget.Button -> pad(t.b, down, who)
            is RemapTarget.Buttons -> (if (down) t.bs else t.bs.reversed()).forEach { pad(it, down, who) }
            RemapTarget.None -> {}
            is RemapTarget.Key -> key(t.code, down, src, t.meta)
            is RemapTarget.Keys -> if (t.inOrder) { if (down) type(t.codes, src) }
                else (if (down) t.codes else t.codes.reversed()).forEach { key(it, down, src) }
            is RemapTarget.Mouse -> mouse(t.b, down, src)
            is RemapTarget.Action -> if (down) main.post { perform(t.a, src) }
            is RemapTarget.Macro -> if (!t.armed) {}                       // imported, not turned on yet
                else if (down) play(who, t) else plays[who]?.held = false
        }
    }
    private fun tap(t: RemapTarget, who: Int) { out(t, true, who); later(TAP_MS, srcOf(who)) { out(t, false, who) } }

    /** Keys typed one after another. */
    private fun type(codes: List<Int>, src: Int) {
        var at = 0L
        for (c in codes) { later(at, src) { key(c, true, src) }; later(at + TYPE_MS, src) { key(c, false, src) }; at += 2 * TYPE_MS }
    }

    // ── macros ──
    private class Play(val m: RemapTarget.Macro) { var held = true; var step = 0 }
    private val plays = HashMap<Int, Play>()

    private fun play(who: Int, m: RemapTarget.Macro) {
        plays[who]?.let { it.held = true; return }          // already running: keep it going
        val p = Play(m); plays[who] = p
        fun next() {
            if (plays[who] !== p) return
            if (p.step >= m.steps.size) {
                if (m.repeat && p.held) p.step = 0 else { plays.remove(who); return }
            }
            val s = m.steps[p.step++]
            out(s.out, true, who)
            later(s.hold.toLong(), srcOf(who)) { if (plays[who] === p) out(s.out, false, who); later(s.gap.toLong(), srcOf(who)) { next() } }
        }
        next()
    }

    // ── the sources ──
    private class Src {
        var active: RemapTarget? = null         // what its press holds (normal / turbo)
        var latched: RemapTarget? = null        // toggle
        var pendingChord: Runnable? = null
        var chord: Chord? = null
        var swallow = false
        var longFired = false
        var holdTimer: Runnable? = null         // long press, or a double-press button held
        var holding = false                     // double-press button held past the window
        var dblWait: Runnable? = null
        var dblActive = false
        var turbo: Runnable? = null
    }
    private val srcs = HashMap<Int, Src>()                  // (source, button) → its state
    private fun srcKey(src: Int, b: ThorButton) = (src shl 8) or b.ordinal

    private fun cancel(r: Runnable?) { if (r != null) h.removeCallbacks(r) }
    private fun post(ms: Long, src: Int, f: () -> Unit): Runnable = Runnable(f).also { h.postDelayed(it, tok(src), ms) }

    private fun handle(code: Int, value: Int, src: Int) {
        if (value == 2) return
        if (BuildConfig.DEBUG) Log.d(TAG, "X $code $value from $src")
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remapOf(src) ?: PadRemap()
        // a release whose press was forgotten by reset(): nothing to finish (it fired a spurious
        // tap of the button's output — review 2026-09-25)
        if (value == 0 && srcs[srcKey(src, b)] == null) return
        val s = srcs.getOrPut(srcKey(src, b)) { Src() }
        if (value == 1) {
            val chord = r.chords.firstOrNull { it.has(b) }
            if (chord != null) {
                val other = if (chord.a == b) chord.b else chord.a
                val o = srcs[srcKey(src, other)]
                if (o?.pendingChord != null) {                   // the pair, within the window (same controller)
                    cancel(o.pendingChord); o.pendingChord = null
                    o.chord = chord; s.chord = chord
                    out(chord.target, true, chordId(r, chord, src))
                    return
                }
                s.pendingChord = post(CHORD_MS, src) { s.pendingChord = null; start(b, s, r, src) }
                return
            }
            start(b, s, r, src)
        } else {
            s.chord?.let { ch ->                                 // a chord ends with its first release
                s.chord = null
                val o = srcs[srcKey(src, if (ch.a == b) ch.b else ch.a)]
                if (o?.chord === ch) { o.chord = null; o.swallow = true }
                out(ch.target, false, chordId(r, ch, src))
                return
            }
            if (s.swallow) { s.swallow = false; return }
            s.pendingChord?.let { cancel(it); s.pendingChord = null; start(b, s, r, src) }   // a quick tap
            stop(b, s, r, src)
        }
    }
    private fun chordId(r: PadRemap, c: Chord, src: Int) = whoOf(src, 0x1000 + r.chords.indexOf(c).coerceAtLeast(0))

    private fun mainOf(b: ThorButton, r: PadRemap) = r.buttons[b] ?: RemapTarget.Button(b)
    private fun altOf(b: ThorButton, r: PadRemap) = r.alt[b] ?: RemapTarget.None

    private fun start(b: ThorButton, s: Src, r: PadRemap, src: Int) {
        val who = whoOf(src, PadRemap.outCode(b) ?: 0)
        val t = mainOf(b, r)
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.NORMAL -> { s.active = t; out(t, true, who) }
            Fire.TOGGLE -> s.latched?.let { out(it, false, who); s.latched = null } ?: run { s.latched = t; out(t, true, who) }
            Fire.TURBO -> {
                s.active = t
                val half = (500L / r.turboHz.coerceIn(2, 30)).coerceAtLeast(10)
                var on = true
                turboSaid(t, src, true)                          // b18o: before its first press — its pulses are no presses
                out(t, true, who)
                s.turbo = object : Runnable { override fun run() {
                    on = !on; out(t, on, who); h.postDelayed(this, tok(src), half)
                } }.also { h.postDelayed(it, tok(src), half) }
            }
            Fire.LONG -> { s.longFired = false; s.holdTimer = post(LONG_MS, src) { s.holdTimer = null; s.longFired = true; out(altOf(b, r), true, who) } }
            Fire.DOUBLE -> {
                val w = s.dblWait
                if (w != null) { cancel(w); s.dblWait = null; s.dblActive = true; out(altOf(b, r), true, who) }
                else s.holdTimer = post(DOUBLE_MS, src) { s.holdTimer = null; s.holding = true; out(t, true, who) }
            }
        }
    }

    private fun stop(b: ThorButton, s: Src, r: PadRemap, src: Int) {
        val who = whoOf(src, PadRemap.outCode(b) ?: 0)
        if (s.turbo != null) turboSaid(s.active, src, false)          // b18o: the hand let go of its turbo
        cancel(s.turbo); s.turbo = null
        s.active?.let { out(it, false, who); s.active = null }
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.LONG -> if (s.longFired) { s.longFired = false; out(altOf(b, r), false, who) }
                else { cancel(s.holdTimer); s.holdTimer = null; tap(mainOf(b, r), who) }
            Fire.DOUBLE -> when {
                s.dblActive -> { s.dblActive = false; out(altOf(b, r), false, who) }
                s.holding -> { s.holding = false; out(mainOf(b, r), false, who) }
                else -> { cancel(s.holdTimer); s.holdTimer = null
                    s.dblWait = post(DOUBLE_MS, src) { s.dblWait = null; tap(mainOf(b, r), who) } }
            }
            else -> {}
        }
    }

    // ── 1.3: a stick that is Wayfinder's — mouse, wheel or 4 keys. wfpad sends its shaped position
    // ("T" lines, −1000..1000, a deadzone of at least 10 % already applied: at rest it's exactly 0) ──
    private const val TICK_MS = 10L
    // per (source, side): key = source * 2 + side
    private val stickPos = HashMap<Int, FloatArray>()
    private val stickKeys = HashMap<Int, HashSet<Int>>()
    private val scrollAcc = HashMap<Int, FloatArray>()
    private val scrolling = HashMap<Int, BooleanArray>()
    private fun pos(k: Int) = stickPos.getOrPut(k) { FloatArray(2) }
    private fun keysOf(k: Int) = stickKeys.getOrPut(k) { HashSet() }
    private fun acc(k: Int) = scrollAcc.getOrPut(k) { FloatArray(2) }
    private fun rolling(k: Int) = scrolling.getOrPut(k) { BooleanArray(2) }
    private val mouseAcc = FloatArray(2)
    private var ticking = false

    fun onStick(side: Int, x: Int, y: Int, src: Int = 0) { h.post { stick(side, x / 1000f, y / 1000f, src) } }

    // ── dev build 18f: a PlayStation controller's touchpad moves Android's pointer (wfhid "H" lines, ExtPadsCtl) — the same
    // virtual mouse as a stick set as a mouse. wfhid already accelerated the motion and rate-limited every line. No
    // InputMode.notePadKey(): a pointer move is no controller key ──
    /** The pointer moved [dx], [dy] px (controller [src]'s touchpad). */
    @Suppress("UNUSED_PARAMETER")
    fun onPointer(dx: Int, dy: Int, src: Int) { if (dx != 0 || dy != 0) h.post { InputMonitor.send("M $dx $dy") } }
    /** A light tap's click pressed / let go — counted per controller ([mouse]), so its going away ([gone]), its own release
     *  ([release]) and [reset] let go of a click held. */
    fun onPointerButton(down: Boolean, src: Int) { h.post { mouse(0, down, src) } }
    /** Two fingers dragging: wheel notches ([v] > 0 up, [hz] > 0 right — wfhid made them natural, the page follows). */
    @Suppress("UNUSED_PARAMETER")
    fun onWheel(v: Int, hz: Int, src: Int) { if (v != 0 || hz != 0) h.post { InputMonitor.send("W $v $hz") } }

    private fun job(side: Int, src: Int = 0): StickJob = remapOf(src)?.let { if (side == 0) it.jobL else it.jobR } ?: StickJob()

    private fun stick(side: Int, x: Float, y: Float, src: Int) {
        if (side !in 0..1 || src < 0) return
        val k = src * 2 + side
        pos(k)[0] = x.coerceIn(-1f, 1f); pos(k)[1] = y.coerceIn(-1f, 1f)
        val j = job(side, src)
        if (j.use == StickUse.KEYS || keysOf(k).isNotEmpty()) stickKeysUpdate(k, j)
        if (!ticking && (x != 0f || y != 0f) && (j.use == StickUse.MOUSE || j.use == StickUse.SCROLL)) {
            ticking = true; h.postDelayed(tick, TOK, TICK_MS)
        }
    }

    /** 4 keys: a direction presses its key past 50 %, lets go below 35 % (no flicker at the edge);
     *  diagonals press two. Arrow keys, or W A S D. */
    private fun stickKeysUpdate(k: Int, j: StickJob) {
        val (x, y) = pos(k)[0] to pos(k)[1]
        val held0 = keysOf(k)
        val codes = if (j.wasd) intArrayOf(android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_D)
            else intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        val amount = floatArrayOf(-y, y, -x, x)
        val keysJob = j.use == StickUse.KEYS
        for (i in 0..3) {
            val c = codes[i]; val held = c in held0
            val on = keysJob && if (held) amount[i] > .35f else amount[i] > .5f
            if (on != held) { key(c, on, k / 2); if (on) held0.add(c) else held0.remove(c) }
        }
        // the job changed from 4 keys (or W A S D ↔ arrows): whatever it still holds goes
        for (c in held0.toList()) if (c !in codes || !keysJob) { key(c, false, k / 2); held0.remove(c) }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            var any = false
            for (k in stickPos.keys.toList()) {
                val x = pos(k)[0]; val y = pos(k)[1]
                val j = job(k % 2, k / 2)
                if (x == 0f && y == 0f) { rolling(k).fill(false); acc(k).fill(0f); continue }
                when (j.use) {
                    StickUse.MOUSE -> {
                        any = true
                        // px / s at full deflection; slower near the centre (x · √r) for fine aiming. b18h: the Pointer
                        // speed curve (the trackpad's too), the same numbers
                        val pps = app.wayfinder.deck.PointerSpeed.stickPps(j.speed)
                        val r = kotlin.math.sqrt(kotlin.math.hypot(x, y).coerceAtMost(1f))
                        mouseAcc[0] += x * r * pps * TICK_MS / 1000f; mouseAcc[1] += y * r * pps * TICK_MS / 1000f
                    }
                    StickUse.SCROLL -> { any = true; scroll(k, x, y, j) }
                    else -> {}
                }
            }
            val ix = mouseAcc[0].toInt(); val iy = mouseAcc[1].toInt()
            if (ix != 0 || iy != 0) { InputMonitor.send("M $ix $iy"); mouseAcc[0] -= ix.toFloat(); mouseAcc[1] -= iy.toFloat() }
            if (any) h.postDelayed(this, TOK, TICK_MS) else { ticking = false; mouseAcc.fill(0f) }
        }
    }

    /** The wheel: starts past 25 % (after the deadzone), stops below 15 %; notches per second grow
     *  with the push. Up = wheel up, right = wheel right. */
    private fun scroll(k: Int, x: Float, y: Float, j: StickJob) {
        val comp = floatArrayOf(y, x)
        val scrolling = rolling(k); val scrollAcc = acc(k)
        for (a in 0..1) {
            val v = comp[a]; val m = kotlin.math.abs(v)
            val on = if (scrolling[a]) m > .15f else m > .25f
            scrolling[a] = on
            if (!on) { scrollAcc[a] = 0f; continue }
            val rate = (2f + j.speed * 2f) * ((m - .15f) / .85f).coerceIn(0f, 1f).let { it * kotlin.math.sqrt(it) }   // notches / s
            // the first notch at once: a push should answer immediately
            scrollAcc[a] += if (scrollAcc[a] == 0f) 1f else rate * TICK_MS / 1000f
            while (scrollAcc[a] >= 1f) {
                scrollAcc[a] -= 1f
                if (a == 0) InputMonitor.send("W ${if (v < 0) 1 else -1} 0") else InputMonitor.send("W 0 ${if (v > 0) 1 else -1}")
            }
            if (scrollAcc[a] == 0f) scrollAcc[a] = 1e-4f     // "started" (not the first notch again)
        }
    }

    private fun reset() {
        h.removeCallbacksAndMessages(TOK)
        for (t in toks.values) h.removeCallbacksAndMessages(t)
        keyBy.clear(); mouseBy.clear()
        ticking = false; mouseAcc.fill(0f)
        stickPos.clear(); stickKeys.clear(); scrollAcc.clear(); scrolling.clear()
        shiftHeldBy.clear(); shiftActive.clear(); h.removeCallbacks(shiftHint)
        shiftListsUp.toList().forEach { hideShiftList(it) }
        srcs.forEach { (k, s) -> if (s.turbo != null) turboSaid(s.active, k ushr 8, false) }           // b18o: every turbo ends
        plays.clear(); srcs.clear()
        // every source that holds a pad output lets go of all of it (the Thor: "G p 0 0", an external: its own)
        for (src in padDown.keys.map { srcOf(it) }.toSet()) if (src == 0) InputMonitor.send("G p 0 0") else padSink(src, 0, 0)
        padDown.clear()
        for (c in keyDown.keys.reversed()) InputMonitor.send("K $c 0 0 ${keyDisplay[c] ?: display()}")
        keyDown.clear(); keyDisplay.clear()
        for (b in mouseDown.keys) InputMonitor.send("B $b 0")
        mouseDown.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "released everything")
    }
}
