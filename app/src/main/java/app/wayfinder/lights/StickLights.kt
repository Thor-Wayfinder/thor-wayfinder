package app.wayfinder.lights

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import kotlin.math.PI

/** What the stick lights do. AYN = leave AYN's own setting alone (the default). Build 18 (B19, MORE-D13): its label "AYN's own"
 *  (was "AYN default"; stored by its name, so nothing stored changes). */
enum class LightMode(val label: String) {
    AYN("AYN's own"), OFF("Off"), STATIC("Colour"), BREATHING("Breathing"),
    STROBE("Strobe"), SPECTRUM("Spectrum"), SCREEN("Screen colour"),
}

/**
 * A stick-light profile. [left]/[right] are colours (ARGB); [speed] scales animation
 * (0.5 slow · 1 normal · 2 fast); [brightness] 0..1.
 */
data class LightProfile(
    val mode: LightMode = LightMode.AYN,
    val left: Int = 0xFF2BE0D8.toInt(),
    val right: Int = 0xFF2BE0D8.toInt(),
    val speed: Float = 1f,
    val brightness: Float = 1f,
    /** 1.3 (GitHub #15): Screen colour — each stick its own side (left ring = left half, right = right half). */
    val split: Boolean = false,
    /** 1.3 (GitHub #33): Screen colour from 0 the controller's screen · 1 the top screen · 2 the bottom screen. */
    val screenFrom: Int = 0,
    /** 1.4 (Reddit): dim and brighten with the screens ([brightness] = with the screens at full). */
    val followScreen: Boolean = false,
    /** Build 18 (GitHub #82): Spectrum — both rings the same hue as it cycles (false: opposite hues). */
    val together: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("left", left).put("right", right)
        .put("speed", speed.toDouble()).put("brightness", brightness.toDouble()).put("split", split).put("from", screenFrom)
        .put("follow", followScreen).put("together", together)

    companion object {
        fun fromJson(o: JSONObject?): LightProfile? = o?.let {
            runCatching {
                LightProfile(
                    LightMode.valueOf(it.optString("mode", "AYN")), it.optInt("left", 0xFF2BE0D8.toInt()),
                    it.optInt("right", 0xFF2BE0D8.toInt()), it.optDouble("speed", 1.0).toFloat(),
                    it.optDouble("brightness", 1.0).toFloat(), it.optBoolean("split", false), it.optInt("from", 0).coerceIn(0, 2),
                    it.optBoolean("follow", false), it.optBoolean("together", false),
                )
            }.getOrNull()
        }
    }
}

/**
 * b18p (GitHub #109): the stick lights' nodes Wayfinder drives — the stock Thor's SN3112 drivers, one per ring — and the rule
 * that says they're there (pure: StickLightsTest). LineageOS's Thor build drives the rings through other nodes
 * (`/sys/class/leds/left:stick:0..3`, …): the LED class's backend (LedLights.kt), looked for only when these aren't there.
 */
internal object LightNodes {
    val SIDES = listOf("/sys/class/sn3112l/led", "/sys/class/sn3112r/led")
    /** Each ring's brightness node exists (a stock Thor has both; before b18p only the left one was looked for). */
    fun present(exists: (String) -> Boolean): Boolean = SIDES.all { exists("$it/brightness") }
}

/**
 * b18p (GitHub #109, LED class): what drives the rings — (a) the stock Thor's SN3112 nodes (StickLights' code, as it was) or
 * (b) the standard LED class's stick LEDs ([LedWriter]). Lights thread only.
 */
private interface RingBackend {
    /** The left ring [left], the right one [right] (ARGB), at [level] 0..1. */
    fun write(left: Int, right: Int, level: Float)
    /** The rings on (the next write sends everything) or off. */
    fun enable(on: Boolean)
    /** "AYN's own": the system's own lights back. */
    fun restore(ctx: Context)
    /** The nodes whose write times show a write by the system after a wake. */
    val watched: List<File>
    /** An effect's frame, in ms. */
    val frameMs: Long
}

/**
 * Drives the Thor's stick RGB rings. They're SN3112 LED drivers exposed in sysfs,
 * world-writable: `enable` takes "1"/"0", `brightness` takes "1-R:G:B" (zone 1 = the
 * ring). So no root is needed. Effects run on their own thread at ~30 fps; "Screen
 * colour" follows the average colour of the game's screen a few times a second
 * (sampled by [screenSampler], supplied by the service). AYN mode puts back exactly
 * what AYN's own settings say, and Wayfinder then stays out of the way.
 * b18p (GitHub #109, LED class): without the SN3112 nodes, the standard LED class's stick LEDs (LineageOS's Thor build) —
 * written directly when the system lets the app, else the page says they're locked (no root path: a root command per frame
 * isn't acceptable).
 */
object StickLights {
    private const val TAG = "ThorLights"
    private val SIDES = LightNodes.SIDES

    // b18p (GitHub #109): made on the first apply with the rings' nodes there — a system without them never starts it
    private val thread by lazy { HandlerThread("ThorLights").apply { start() } }
    private val h by lazy { Handler(thread.looper) }
    @Volatile private var profile: LightProfile = LightProfile()
    @Volatile private var touched = false          // have we changed the LEDs since AYN last set them?
    private var start = 0L
    @Volatile private var screenColor: Int = Color.WHITE

    /** The game's screen colour — (whole screen, left half, right half) — or null (set by the service). */
    @Volatile var screenSampler: (() -> IntArray?)? = null
    /** Which screen the Screen colour comes from (the profile's [LightProfile.screenFrom]). */
    val screenFrom: Int get() = profile.screenFrom
    /** Called when Screen colour stops (the service stops its sampler). */
    @Volatile var screenStop: (() -> Unit)? = null
    /** What the rings fade toward (the latest screen colour); [screenColor] is what they show. */
    @Volatile private var screenTarget: Int = Color.WHITE
    /** The right ring's, when each stick follows its own side ([LightProfile.split]). */
    @Volatile private var screenTargetR: Int = Color.WHITE
    @Volatile private var screenColorR: Int = Color.WHITE

    // ── b18p (GitHub #109, LED class): what drives the rings ──────────
    /** (a) the stock Thor's SN3112 nodes: the code below, as it was ([sn3112Write], [sn3112Enable], [sn3112Restore]). */
    private val sn3112 = object : RingBackend {
        override fun write(left: Int, right: Int, level: Float) = sn3112Write(left, right, level)
        override fun enable(on: Boolean) = sn3112Enable(on)
        override fun restore(ctx: Context) = sn3112Restore(ctx)
        override val watched: List<File> get() = NODES
        override val frameMs: Long get() = 33L
    }
    /** What drives the rings: [sn3112], or (b) the LED class's [leds] ([ledBackend]). */
    @Volatile private var backend: RingBackend = sn3112
    @Volatile private var leds: LedWriter? = null
    /** The LED class refused a write once: the next look doesn't count on writing it. */
    @Volatile private var ledDirectRefused = false
    /** LED writes failed in a row (a value the nodes didn't take): ten stop the effects, as a node gone does. */
    private var ledFails = 0
    /** The rings were found but this system doesn't let Wayfinder change them (the LED class, not writable by the app). The
     *  page says so; Compose state, so an open page follows a first write refused. */
    var locked by mutableStateOf(false)
        private set

    /**
     * b18p (GitHub #109): the rings' nodes are there and Wayfinder may drive them ([detect]). Without them no effect runs and
     * nothing is written — no lights thread, no frames, no screen sampling — and Stick lights says so ([locked]: found but
     * locked). Looked for when first asked, on [recheck] (the page, on open), and again on each apply or wake while missing
     * (a few file checks).
     */
    @Volatile var available: Boolean = detect()
        private set

    /** b18p (#109): look for the rings' nodes now; true = there (and Wayfinder may drive them). */
    fun recheck(): Boolean {
        val now = detect()
        if (now != available) Log.i(TAG, if (now) "stick-light nodes found" else if (locked) "stick lights locked: effects off"
            else "no stick-light nodes: effects off")
        available = now
        return now
    }

    /**
     * b18p (GitHub #109, LED class): which backend drives the rings ([LightBackends.pick]) — the SN3112 nodes first, untouched;
     * else the LED class's stick LEDs ([LedScan]): written directly when the app may ([LightBackends.writable]), else found
     * but [locked]. A writer already driving the same LEDs is kept (its "before" copy with it). True = the rings can be driven.
     */
    private fun detect(): Boolean {
        if (LightNodes.present { File(it).exists() }) {
            backend = sn3112; leds = null
            if (locked) locked = false
            return true
        }
        val rings = LedScan.find(File(LedNames.ROOT))
        val kind = LightBackends.pick(false, rings, LightBackends.writable(rings) && !ledDirectRefused)
        val lock = kind == LightBackends.Kind.LEDS_LOCKED
        if (locked != lock) locked = lock
        if (kind != LightBackends.Kind.LEDS) { backend = sn3112; leds = null; return false }
        val cur = leds
        if (cur == null || cur.rings != rings || cur.refused) {
            val w = LedWriter(rings, LedFiles)
            leds = w; backend = ledBackend(w)
            Log.i(TAG, "stick LEDs (${rings[0].size} + ${rings[1].size}): written directly")
        }
        return true
    }

    /** (b) the LED class's stick LEDs: `enable` off = every LED dark, on = the next write sends everything. */
    private fun ledBackend(w: LedWriter) = object : RingBackend {
        override fun write(left: Int, right: Int, level: Float) = done(w, w.write(left, right, level))
        override fun enable(on: Boolean) {
            if (on) w.forget() else done(w, w.off())
            ringsOn = on
        }
        override fun restore(ctx: Context) {
            done(w, w.restore()); ringsOn = null
            Log.d(TAG, "restored the system's lights")
        }
        override val watched: List<File> get() = w.files
        override val frameMs: Long get() = 33L
    }

    /** After an LED write (lights thread): our write's time; or every loop stopped — a node gone, or the writes refused. */
    private fun done(w: LedWriter, r: LedIo.Result) {
        if (w.directRefused) ledDirectRefused = true
        if (r != LedIo.Result.FAILED) ledFails = 0
        when (r) {
            LedIo.Result.OK -> ourWriteAt = System.currentTimeMillis()
            LedIo.Result.SAME -> {}
            LedIo.Result.GONE -> { w.forget(); gone() }
            LedIo.Result.REFUSED -> {
                gone("stick LEDs found, but this system doesn't let Wayfinder write them: effects off")
                locked = true
            }
            LedIo.Result.FAILED -> if (++ledFails >= 10) { ledFails = 0; w.forget(); gone("stick LEDs don't take Wayfinder's writes: effects off") }
        }
    }

    /** Screen off: stop animating (no LED writes while the Thor sleeps). */
    fun pause() {
        if (!available) return                         // b18p (#109): nothing runs without the nodes
        h.post {
            h.removeCallbacks(frame); h.removeCallbacks(sample); h.removeCallbacks(follow); h.removeCallbacks(watchAyn)
            paused = true; ringsOn = null
        }
    }

    /** Screen on again: pick the effect back up. */
    fun resume(ctx: Context) {
        if (!available && !recheck()) { paused = false; return }   // b18p (#109): nothing to pick up — the next apply, if they come
        h.post { resumeNow(ctx) }
    }

    private fun resumeNow(ctx: Context) {
        if (!paused) return
        paused = false
        ringsOn = null
        reapply(ctx)
        // build 18 (GitHub #64): Off and Colour — ours again as soon as a node shows a write newer than ours (AYN's, at
        // wake), not 1.5 s later. The effects rewrite the rings every frame anyway.
        if (profile.mode == LightMode.OFF || profile.mode == LightMode.STATIC) {
            watchUntil = SystemClock.uptimeMillis() + 6000; aynSeenAt = 0L; watchFixes = 0
            h.removeCallbacks(watchAyn); h.postDelayed(watchAyn, 100)
        }
        // 1.4 (GitHub #64): AYN's own software writes the rings when the Thor wakes (after us, sometimes) — ours again
        // (build 18: the fallback, if the nodes' times don't tell)
        h.postDelayed({ if (!paused && profile.mode != LightMode.AYN) reapply(ctx) }, 1500)
        h.postDelayed({ if (!paused && profile.mode != LightMode.AYN) reapply(ctx) }, 4000)
    }

    /** Write the current profile again, whatever we think the LEDs show. */
    private fun reapply(ctx: Context) {
        val p = wanted ?: profile        // build 18: a change still on its way is the current one
        profile = p.copy(speed = -1f)   // never equal: a fresh apply
        ringsOn = null                  // and `enable` written again (it also forgets what the rings were last sent)
        // AYN mode: AYN's lights are put back only if Wayfinder had changed them (review: untouched stays untouched)
        if (p.mode != LightMode.AYN) touched = true
        apply(ctx, p)
    }
    @Volatile private var paused = false

    /**
     * Build 18 (GitHub #64): the profile last asked for. The lights thread applies only the latest — a wheel steered by
     * the stick asks every frame, and one runnable per ask had the thread walk through old colours behind the stick.
     */
    @Volatile private var wanted: LightProfile? = null
    /** Build 18: what `enable` was last set to by us (null = unknown: written again). Lights thread only. */
    private var ringsOn: Boolean? = null

    private val applyLatest = Runnable {
        val p = wanted ?: return@Runnable
        val ctx = appCtx ?: return@Runnable
        if (paused) { profile = p; return@Runnable }   // applied on resume
        if (p == profile && touched) return@Runnable
        profile = p
        h.removeCallbacks(frame); h.removeCallbacks(sample); h.removeCallbacks(follow)
        if (p.followScreen) h.post(follow)
        if (p.mode != LightMode.SCREEN) screenStop?.invoke()
        start = SystemClock.uptimeMillis()
        when (p.mode) {
            LightMode.AYN -> if (touched) { restoreAyn(ctx); touched = false }
            LightMode.OFF -> { enable(false); touched = true }      // always written: it's what ends AYN's flash at wake
            LightMode.STATIC -> { if (ringsOn != true) enable(true); write(p.left, p.right, lvl(p)); touched = true }
            else -> {
                enable(true); touched = true
                if (p.mode == LightMode.SCREEN) h.post(sample)
                h.post(frame)
            }
        }
    }

    /** Apply [asked] (animations keep running until another profile or AYN mode). */
    fun apply(ctx: Context, asked: LightProfile) {
        // 1.4: stick lights turned off in Features = AYN's own lights, untouched
        val p = if (app.wayfinder.Features.lights) asked else LightProfile(mode = LightMode.AYN)
        appCtx = ctx.applicationContext
        wanted = p
        if (!available && !recheck()) return   // b18p (GitHub #109): no rings' nodes — nothing runs or writes (kept for if they come)
        h.removeCallbacks(applyLatest); h.post(applyLatest)   // latest wins
    }

    // ── animation ───────────────────────────────────────────────────────
    private val frame = object : Runnable {
        override fun run() {
            if (!available) return                     // b18p (#109): the nodes went — the loop ends
            val p = profile
            val t = (SystemClock.uptimeMillis() - start) / 1000f * p.speed
            when (p.mode) {
                LightMode.BREATHING -> {
                    val k = (0.5f - 0.5f * kotlin.math.cos(2 * PI.toFloat() * t / 3f)).coerceIn(0.03f, 1f)
                    write(p.left, p.right, lvl(p) * k)
                }
                LightMode.STROBE -> {
                    val on = ((t * 4f).toInt() % 2) == 0
                    write(p.left, p.right, if (on) lvl(p) else 0f)
                }
                LightMode.SPECTRUM -> {
                    val hue = (t * 60f) % 360f
                    write(hsv(hue), hsv(if (p.together) hue else (hue + 180f) % 360f), lvl(p))   // build 18 (GitHub #82)
                }
                LightMode.SCREEN -> {
                    // Glide toward the latest screen colour (~0.1 s time constant at 30 fps):
                    // smooth, but close behind the picture.
                    screenColor = blend(screenColor, screenTarget, 0.28f)
                    screenColorR = blend(screenColorR, screenTargetR, 0.28f)
                    write(screenColor, if (p.split) screenColorR else screenColor, lvl(p))
                }
                else -> return
            }
            h.postDelayed(this, backend.frameMs)
        }
    }

    private val sample = object : Runnable {
        override fun run() {
            if (!available || profile.mode != LightMode.SCREEN) return     // b18p (#109): no nodes, no screen sampling
            screenSampler?.invoke()?.takeIf { it.size >= 3 }?.let { c ->
                if (profile.split) { screenTarget = vivid(c[1]); screenTargetR = vivid(c[2]) }
                else { screenTarget = vivid(c[0]); screenTargetR = screenTarget }
            }
            h.postDelayed(this, 60)   // the root sampler streams ~12 colours/s (fallback: a11y, ~3/s)
        }
    }

    // ── 1.4 (Reddit): follow the screens' brightness ─────────────────────
    @Volatile private var appCtx: Context? = null
    /** The brighter lit screen's level, on the eye's scale (0.05..1). */
    @Volatile private var screenFactor = 1f
    private fun lvl(p: LightProfile) = if (p.followScreen) p.brightness * screenFactor else p.brightness

    /** Every 2 s: the screens' levels (the root input helper answers in milliseconds). */
    private val follow = object : Runnable {
        override fun run() {
            val p = profile; val ctx = appCtx ?: return
            if (paused || !p.followScreen || p.mode == LightMode.AYN || p.mode == LightMode.OFF) return
            val ids = listOf(0) + app.wayfinder.ForegroundAppService.availableDisplayIds.filter { it != 0 }.take(1)
            app.wayfinder.ScreenLevels.readAsync(ctx, ids) { m -> h.post {
                // a screen Wayfinder turned off doesn't count (1 = bottom off, 2 = top off)
                val off = when (app.wayfinder.ForegroundAppService.screenMode()) { 1 -> ids.drop(1); 2 -> listOf(0); else -> emptyList() }
                val f = (m.filterKeys { it !in off }.values.maxOrNull()?.let { kotlin.math.sqrt(it) } ?: return@post).coerceIn(0.05f, 1f)
                if (kotlin.math.abs(f - screenFactor) < 0.01f) return@post
                screenFactor = f
                Log.i(TAG, "rings follow the screens: ${(f * 100).toInt()} %")
                val now = profile
                if (!paused && now.followScreen && now.mode == LightMode.STATIC) write(now.left, now.right, lvl(now))
            } }
            h.postDelayed(this, 2000)
        }
    }

    // ── build 18 (GitHub #64): AYN's write at wake, seen in the nodes' times ──
    private val NODES = SIDES.flatMap { listOf(File(it, "enable"), File(it, "brightness")) }
    /** When we last wrote a node (wall clock, like the nodes' mtimes). Lights thread only. */
    private var ourWriteAt = 0L
    private var watchUntil = 0L
    /** The newest node time already answered (a node dated ahead of us is answered once). */
    private var aynSeenAt = 0L
    private var watchFixes = 0

    /**
     * Every 100 ms for 6 s after a wake (Off and Colour): a node written more than 50 ms after our last write is
     * AYN's software setting its own lights, so ours go back at once (the mtimes move on each write).
     */
    private val watchAyn = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            val mode = profile.mode
            if (paused || SystemClock.uptimeMillis() > watchUntil || (mode != LightMode.OFF && mode != LightMode.STATIC)) return
            val t = backend.watched.maxOfOrNull { it.lastModified() } ?: 0L   // b18p (#109, LED class): its nodes
            if (t > ourWriteAt + 50 && t > aynSeenAt) {
                aynSeenAt = t
                Log.i(TAG, "rings written after us at wake: ours again")
                reapply(ctx)
                if (++watchFixes >= 4) return   // a node that keeps looking newer (clock trouble): the fixed rewrites carry on
            }
            h.postDelayed(this, 100)
        }
    }

    // ── sysfs ───────────────────────────────────────────────────────────
    private var lastWritten = arrayOf("", "")

    /** b18p (GitHub #109, LED class): the rings' writes go to the [backend] — the stock Thor's: the three below. */
    private fun write(left: Int, right: Int, level: Float) = backend.write(left, right, level)
    private fun enable(on: Boolean) = backend.enable(on)
    private fun restoreAyn(ctx: Context) = backend.restore(ctx)

    private fun sn3112Write(left: Int, right: Int, level: Float) {
        listOf(left, right).forEachIndexed { i, c ->
            val s = drive(c, level).joinToString(":", prefix = "1-")
            if (s != lastWritten[i]) {
                put("${SIDES[i]}/brightness", s); lastWritten[i] = s
                if (i == 0 && app.wayfinder.BuildConfig.DEBUG && profile.mode == LightMode.SCREEN) Log.v(TAG, "led $s")
            }
        }
    }

    private fun sn3112Enable(on: Boolean) {
        SIDES.forEach { put("$it/enable", if (on) "1" else "0") }
        lastWritten = arrayOf("", ""); ringsOn = on
    }

    /**
     * Screen colour → LED drive. On the Thor's rings even small green/blue values glow
     * visibly: a UI red (255,59,48) came out pink, still pinkish at gamma 2.2, while a pure
     * 255:0:0 is a clean red (2026-09-23). So: gamma 2.8, and channels that end up
     * nearly dark are switched fully off.
     */
    private fun drive(c: Int, level: Float): List<Int> {
        val lin = listOf(Color.red(c), Color.green(c), Color.blue(c)).map { Math.pow(it / 255.0, 2.8) }
        val peak = lin.max()
        val l = level.coerceIn(0f, 1f)
        // "nearly dark" is measured against the brightest channel (it was 6/255 at any level, so a
        // dim setting — 1 %, GitHub #44 — switched every channel off); a channel that stays on
        // keeps at least one step, so the rings still glow at 1 %
        return lin.map { v ->
            if (l <= 0f || peak <= 0.0 || v < peak * (6.0 / 255)) 0
            else (v * l * 255).toInt().coerceIn(1, 255)
        }
    }

    private fun put(path: String, value: String) {
        if (!available) return                         // b18p (GitHub #109)
        try { File(path).writeText(value); ourWriteAt = System.currentTimeMillis() }
        catch (e: Exception) {
            // b18p (#109): the node isn't there (gone, or never was) — every effect stops at once, said once; any other
            // failure (a refused write) is logged as before
            if (!File(path).exists()) gone() else Log.w(TAG, "write $path: ${e.message}")
        }
    }

    /** b18p (GitHub #109): a ring's node went missing while Wayfinder drove the rings (lights thread) — the frames, the screen
     *  sampling, the wake watch and the screens' levels stop; nothing more is written until [recheck] finds them again. */
    private fun gone(why: String = "no stick-light nodes: effects off") {
        if (!available) return
        available = false
        h.removeCallbacks(applyLatest); h.removeCallbacks(frame); h.removeCallbacks(sample); h.removeCallbacks(follow)
        h.removeCallbacks(watchAyn)
        screenStop?.invoke()
        ringsOn = null; lastWritten = arrayOf("", ""); profile = profile.copy(speed = -1f)   // a fresh apply if they come back
        Log.i(TAG, why)
    }

    /** Exactly what AYN's own settings say (the state we leave the lights in). */
    private fun sn3112Restore(ctx: Context) {
        val cr = ctx.contentResolver
        val enabled = (Settings.System.getString(cr, "joystick_light_enabled") ?: "1,1").split(",")
        val colors = (Settings.System.getString(cr, "joystick_led_light_picker_color") ?: "#2BE0D8,#2BE0D8").split(",")
        val level = runCatching { Settings.System.getString(cr, "led_light_brightness_percent")?.toFloat() }.getOrNull() ?: 0.5f
        SIDES.forEachIndexed { i, side -> put("$side/enable", enabled.getOrElse(i) { "1" }.trim()) }
        lastWritten = arrayOf("", ""); ringsOn = null
        val l = runCatching { Color.parseColor(colors[0].trim()) }.getOrDefault(0xFF2BE0D8.toInt())
        val r = runCatching { Color.parseColor(colors.getOrElse(1) { colors[0] }.trim()) }.getOrDefault(l)
        write(l, r, level)
        Log.d(TAG, "restored AYN lights")
    }

    // ── colour helpers ──────────────────────────────────────────────────
    private fun hsv(h: Float) = Color.HSVToColor(floatArrayOf(h, 1f, 1f))

    /** Screen colours are often dull; push saturation/value so the ring actually shows it. */
    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.6f).coerceAtMost(1f); hsv[2] = (0.35f + hsv[2]).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    private fun blend(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )
}
