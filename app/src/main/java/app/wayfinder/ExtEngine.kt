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
 * Own thread (timing must not wait for the UI). Everything it holds is tracked, so Home / Back,
 * a profile switch or the layer going away release all of it ([releaseAll]).
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

    /** The profile of the app that has the controller (the service's 500 ms tick). */
    @Volatile var remap: PadRemap? = null
    /** Where keyboard keys go: the screen that has the controller. */
    @Volatile var display: () -> Int = { 0 }
    /** A Wayfinder action (run on the main thread). */
    @Volatile var perform: (ThorAction) -> Unit = {}

    fun onExt(code: Int, value: Int) { h.post { handle(code, value) } }

    // ── hold-to-shift (§6l): while the profile's shift button is held, wfpad withholds everything
    // from the game and echoes it ("J" lines); a button with a "With Shift held" job plays it here.
    private var shiftHeld = false
    private val shiftActive = HashMap<ThorButton, RemapTarget>()
    private val shiftHint = Runnable { if (shiftHeld) showShiftHint() }
    fun onGated(code: Int, value: Int) { h.post { gated(code, value) } }
    private fun gated(code: Int, value: Int) {
        if (value == 2) return
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remap ?: return
        val sh = r.shift
        if (b == sh) {
            shiftHeld = value != 0
            h.removeCallbacks(shiftHint)
            if (shiftHeld) h.postDelayed(shiftHint, 450) else ForegroundAppService.hideShiftHint()
            return
        }
        if (value == 1) {
            if (!shiftHeld || sh == null) return
            val t = r.shifted[b] ?: return
            h.removeCallbacks(shiftHint); ForegroundAppService.hideShiftHint()
            shiftActive[b] = t; out(t, true, 0x2000 + code)
        } else shiftActive.remove(b)?.let { out(it, false, 0x2000 + code) }   // its own release ends it
    }
    private fun showShiftHint() {
        val r = remap ?: return
        val sh = r.shift ?: return
        if (r.shifted.isEmpty()) return
        ForegroundAppService.showShiftHint(sh.spoken, r.shifted.map { (k, v) -> k.spoken to v.short() })
    }
    /** Home / Back pressed, a new profile, the layer off: stop macros, release everything. */
    fun releaseAll() { h.post { reset() } }

    // ── what's held (reference counts: two buttons may hold the same output) ──
    private val padDown = HashMap<Int, Int>()
    private val keyDown = LinkedHashMap<Int, Int>()
    private val mouseDown = HashMap<Int, Int>()

    private fun later(ms: Long, r: () -> Unit) { h.postDelayed(r, TOK, ms) }

    private fun ref(m: MutableMap<Int, Int>, c: Int, down: Boolean): Boolean {
        val n = m[c] ?: 0
        return if (down) { m[c] = n + 1; n == 0 } else if (n > 0) { if (n == 1) m.remove(c) else m[c] = n - 1; n == 1 } else false
    }
    private fun pad(b: ThorButton, down: Boolean) {
        val c = PadRemap.outCode(b) ?: return
        if (ref(padDown, c, down)) InputMonitor.send("G p $c ${if (down) 1 else 0}")
    }
    private fun meta() = keyDown.keys.fold(0) { m, c -> m or RemapTarget.metaOf(c) }
    private val keyDisplay = HashMap<Int, Int>()
    private fun key(code: Int, down: Boolean, extraMeta: Int = 0) {
        if (!ref(keyDown, code, down)) return
        // the up goes where the down went: a touch on the other screen in between left it held (review 2026-09-25)
        val d = if (down) display().also { keyDisplay[code] = it } else keyDisplay.remove(code) ?: display()
        InputMonitor.send("K $code ${if (down) 1 else 0} ${meta() or extraMeta or (if (down) 0 else RemapTarget.metaOf(code))} $d")
    }
    private fun mouse(b: Int, down: Boolean) {
        if (b > 2) { if (down) InputMonitor.send("W ${if (b == 3) 1 else -1} 0"); return }
        if (ref(mouseDown, b, down)) InputMonitor.send("B $b ${if (down) 1 else 0}")
    }

    /** Press / release one output. [who] = the source (a button code, or a chord's id). */
    private fun out(t: RemapTarget, down: Boolean, who: Int) {
        when (t) {
            is RemapTarget.Button -> pad(t.b, down)
            is RemapTarget.Buttons -> (if (down) t.bs else t.bs.reversed()).forEach { pad(it, down) }
            RemapTarget.None -> {}
            is RemapTarget.Key -> key(t.code, down, t.meta)
            is RemapTarget.Keys -> if (t.inOrder) { if (down) type(t.codes) }
                else (if (down) t.codes else t.codes.reversed()).forEach { key(it, down) }
            is RemapTarget.Mouse -> mouse(t.b, down)
            is RemapTarget.Action -> if (down) main.post { perform(t.a) }
            is RemapTarget.Macro -> if (!t.armed) {}                       // imported, not turned on yet
                else if (down) play(who, t) else plays[who]?.held = false
        }
    }
    private fun tap(t: RemapTarget, who: Int) { out(t, true, who); later(TAP_MS) { out(t, false, who) } }

    /** Keys typed one after another. */
    private fun type(codes: List<Int>) {
        var at = 0L
        for (c in codes) { later(at) { key(c, true) }; later(at + TYPE_MS) { key(c, false) }; at += 2 * TYPE_MS }
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
            later(s.hold.toLong()) { if (plays[who] === p) out(s.out, false, who); later(s.gap.toLong()) { next() } }
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
    private val srcs = HashMap<ThorButton, Src>()

    private fun cancel(r: Runnable?) { if (r != null) h.removeCallbacks(r) }
    private fun post(ms: Long, f: () -> Unit): Runnable = Runnable(f).also { h.postDelayed(it, TOK, ms) }

    private fun handle(code: Int, value: Int) {
        if (value == 2) return
        if (BuildConfig.DEBUG) Log.d(TAG, "X $code $value")
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remap ?: PadRemap()
        // a release whose press was forgotten by reset(): nothing to finish (it fired a spurious
        // tap of the button's output — review 2026-09-25)
        if (value == 0 && srcs[b] == null) return
        val s = srcs.getOrPut(b) { Src() }
        if (value == 1) {
            val chord = r.chords.firstOrNull { it.has(b) }
            if (chord != null) {
                val other = if (chord.a == b) chord.b else chord.a
                val o = srcs[other]
                if (o?.pendingChord != null) {                   // the pair, within the window
                    cancel(o.pendingChord); o.pendingChord = null
                    o.chord = chord; s.chord = chord
                    out(chord.target, true, chordId(r, chord))
                    return
                }
                s.pendingChord = post(CHORD_MS) { s.pendingChord = null; start(b, s, r) }
                return
            }
            start(b, s, r)
        } else {
            s.chord?.let { ch ->                                 // a chord ends with its first release
                s.chord = null
                val o = srcs[if (ch.a == b) ch.b else ch.a]
                if (o?.chord === ch) { o.chord = null; o.swallow = true }
                out(ch.target, false, chordId(r, ch))
                return
            }
            if (s.swallow) { s.swallow = false; return }
            s.pendingChord?.let { cancel(it); s.pendingChord = null; start(b, s, r) }   // a quick tap
            stop(b, s, r)
        }
    }
    private fun chordId(r: PadRemap, c: Chord) = 0x1000 + r.chords.indexOf(c).coerceAtLeast(0)

    private fun mainOf(b: ThorButton, r: PadRemap) = r.buttons[b] ?: RemapTarget.Button(b)
    private fun altOf(b: ThorButton, r: PadRemap) = r.alt[b] ?: RemapTarget.None

    private fun start(b: ThorButton, s: Src, r: PadRemap) {
        val who = PadRemap.outCode(b) ?: 0
        val t = mainOf(b, r)
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.NORMAL -> { s.active = t; out(t, true, who) }
            Fire.TOGGLE -> s.latched?.let { out(it, false, who); s.latched = null } ?: run { s.latched = t; out(t, true, who) }
            Fire.TURBO -> {
                s.active = t
                val half = (500L / r.turboHz.coerceIn(2, 30)).coerceAtLeast(10)
                var on = true
                out(t, true, who)
                s.turbo = object : Runnable { override fun run() {
                    on = !on; out(t, on, who); h.postDelayed(this, TOK, half)
                } }.also { h.postDelayed(it, TOK, half) }
            }
            Fire.LONG -> { s.longFired = false; s.holdTimer = post(LONG_MS) { s.holdTimer = null; s.longFired = true; out(altOf(b, r), true, who) } }
            Fire.DOUBLE -> {
                val w = s.dblWait
                if (w != null) { cancel(w); s.dblWait = null; s.dblActive = true; out(altOf(b, r), true, who) }
                else s.holdTimer = post(DOUBLE_MS) { s.holdTimer = null; s.holding = true; out(t, true, who) }
            }
        }
    }

    private fun stop(b: ThorButton, s: Src, r: PadRemap) {
        val who = PadRemap.outCode(b) ?: 0
        cancel(s.turbo); s.turbo = null
        s.active?.let { out(it, false, who); s.active = null }
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.LONG -> if (s.longFired) { s.longFired = false; out(altOf(b, r), false, who) }
                else { cancel(s.holdTimer); s.holdTimer = null; tap(mainOf(b, r), who) }
            Fire.DOUBLE -> when {
                s.dblActive -> { s.dblActive = false; out(altOf(b, r), false, who) }
                s.holding -> { s.holding = false; out(mainOf(b, r), false, who) }
                else -> { cancel(s.holdTimer); s.holdTimer = null
                    s.dblWait = post(DOUBLE_MS) { s.dblWait = null; tap(mainOf(b, r), who) } }
            }
            else -> {}
        }
    }

    // ── 1.3: a stick that is Wayfinder's — mouse, wheel or 4 keys. wfpad sends its shaped position
    // ("T" lines, −1000..1000, a deadzone of at least 10 % already applied: at rest it's exactly 0) ──
    private const val TICK_MS = 10L
    private val stickPos = Array(2) { FloatArray(2) }
    private val stickKeys = Array(2) { HashSet<Int>() }
    private val scrollAcc = Array(2) { FloatArray(2) }
    private val scrolling = Array(2) { BooleanArray(2) }
    private val mouseAcc = FloatArray(2)
    private var ticking = false

    fun onStick(side: Int, x: Int, y: Int) { h.post { stick(side, x / 1000f, y / 1000f) } }

    private fun job(side: Int): StickJob = remap?.let { if (side == 0) it.jobL else it.jobR } ?: StickJob()

    private fun stick(side: Int, x: Float, y: Float) {
        if (side !in 0..1) return
        stickPos[side][0] = x.coerceIn(-1f, 1f); stickPos[side][1] = y.coerceIn(-1f, 1f)
        val j = job(side)
        if (j.use == StickUse.KEYS || stickKeys[side].isNotEmpty()) stickKeysUpdate(side, j)
        if (!ticking && (x != 0f || y != 0f) && (j.use == StickUse.MOUSE || j.use == StickUse.SCROLL)) {
            ticking = true; h.postDelayed(tick, TOK, TICK_MS)
        }
    }

    /** 4 keys: a direction presses its key past 50 %, lets go below 35 % (no flicker at the edge);
     *  diagonals press two. Arrow keys, or W A S D. */
    private fun stickKeysUpdate(side: Int, j: StickJob) {
        val (x, y) = stickPos[side][0] to stickPos[side][1]
        val codes = if (j.wasd) intArrayOf(android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_D)
            else intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        val amount = floatArrayOf(-y, y, -x, x)
        val keysJob = j.use == StickUse.KEYS
        for (i in 0..3) {
            val c = codes[i]; val held = c in stickKeys[side]
            val on = keysJob && if (held) amount[i] > .35f else amount[i] > .5f
            if (on != held) { key(c, on); if (on) stickKeys[side].add(c) else stickKeys[side].remove(c) }
        }
        // the job changed from 4 keys (or W A S D ↔ arrows): whatever it still holds goes
        for (c in stickKeys[side].toList()) if (c !in codes || !keysJob) { key(c, false); stickKeys[side].remove(c) }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            var any = false
            for (side in 0..1) {
                val x = stickPos[side][0]; val y = stickPos[side][1]
                val j = job(side)
                if (x == 0f && y == 0f) { scrolling[side][0] = false; scrolling[side][1] = false; scrollAcc[side].fill(0f); continue }
                when (j.use) {
                    StickUse.MOUSE -> {
                        any = true
                        // px / s at full deflection; slower near the centre (x · √r) for fine aiming
                        val pps = 300f + j.speed * 170f
                        val r = kotlin.math.sqrt(kotlin.math.hypot(x, y).coerceAtMost(1f))
                        mouseAcc[0] += x * r * pps * TICK_MS / 1000f; mouseAcc[1] += y * r * pps * TICK_MS / 1000f
                    }
                    StickUse.SCROLL -> { any = true; scroll(side, x, y, j) }
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
    private fun scroll(side: Int, x: Float, y: Float, j: StickJob) {
        val comp = floatArrayOf(y, x)
        for (a in 0..1) {
            val v = comp[a]; val m = kotlin.math.abs(v)
            val on = if (scrolling[side][a]) m > .15f else m > .25f
            scrolling[side][a] = on
            if (!on) { scrollAcc[side][a] = 0f; continue }
            val rate = (2f + j.speed * 2f) * ((m - .15f) / .85f).coerceIn(0f, 1f).let { it * kotlin.math.sqrt(it) }   // notches / s
            // the first notch at once: a push should answer immediately
            scrollAcc[side][a] += if (scrollAcc[side][a] == 0f) 1f else rate * TICK_MS / 1000f
            while (scrollAcc[side][a] >= 1f) {
                scrollAcc[side][a] -= 1f
                if (a == 0) InputMonitor.send("W ${if (v < 0) 1 else -1} 0") else InputMonitor.send("W 0 ${if (v > 0) 1 else -1}")
            }
            if (scrollAcc[side][a] == 0f) scrollAcc[side][a] = 1e-4f     // "started" (not the first notch again)
        }
    }

    private fun reset() {
        h.removeCallbacksAndMessages(TOK)
        ticking = false; mouseAcc.fill(0f)
        for (s in 0..1) { stickPos[s].fill(0f); stickKeys[s].clear(); scrollAcc[s].fill(0f); scrolling[s].fill(false) }
        shiftHeld = false; shiftActive.clear(); h.removeCallbacks(shiftHint)
        plays.clear(); srcs.clear()
        if (padDown.isNotEmpty()) InputMonitor.send("G p 0 0")
        padDown.clear()
        for (c in keyDown.keys.reversed()) InputMonitor.send("K $c 0 0 ${keyDisplay[c] ?: display()}")
        keyDown.clear(); keyDisplay.clear()
        for (b in mouseDown.keys) InputMonitor.send("B $b 0")
        mouseDown.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "released everything")
    }
}
