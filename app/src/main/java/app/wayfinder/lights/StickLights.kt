package app.wayfinder.lights

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.PI

/** What the stick lights do. AYN = leave AYN's own setting alone (the default). */
enum class LightMode(val label: String) {
    AYN("AYN default"), OFF("Off"), STATIC("Colour"), BREATHING("Breathing"),
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
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("left", left).put("right", right)
        .put("speed", speed.toDouble()).put("brightness", brightness.toDouble()).put("split", split).put("from", screenFrom)

    companion object {
        fun fromJson(o: JSONObject?): LightProfile? = o?.let {
            runCatching {
                LightProfile(
                    LightMode.valueOf(it.optString("mode", "AYN")), it.optInt("left", 0xFF2BE0D8.toInt()),
                    it.optInt("right", 0xFF2BE0D8.toInt()), it.optDouble("speed", 1.0).toFloat(),
                    it.optDouble("brightness", 1.0).toFloat(), it.optBoolean("split", false), it.optInt("from", 0).coerceIn(0, 2),
                )
            }.getOrNull()
        }
    }
}

/**
 * Drives the Thor's stick RGB rings. They're SN3112 LED drivers exposed in sysfs,
 * world-writable: `enable` takes "1"/"0", `brightness` takes "1-R:G:B" (zone 1 = the
 * ring). So no root is needed. Effects run on their own thread at ~30 fps; "Screen
 * colour" follows the average colour of the game's screen a few times a second
 * (sampled by [screenSampler], supplied by the service). AYN mode puts back exactly
 * what AYN's own settings say, and Wayfinder then stays out of the way.
 */
object StickLights {
    private const val TAG = "ThorLights"
    private val SIDES = listOf("/sys/class/sn3112l/led", "/sys/class/sn3112r/led")

    private val thread = HandlerThread("ThorLights").apply { start() }
    private val h = Handler(thread.looper)
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

    val available: Boolean get() = File(SIDES[0], "brightness").exists()

    /** Screen off: stop animating (no LED writes while the Thor sleeps). */
    fun pause() = h.post { h.removeCallbacks(frame); h.removeCallbacks(sample); paused = true }

    /** Screen on again: pick the effect back up. */
    fun resume(ctx: Context) = h.post {
        if (!paused) return@post
        paused = false
        val p = profile
        profile = LightProfile(mode = LightMode.AYN)   // force a fresh apply
        apply(ctx, p)
    }
    @Volatile private var paused = false

    /** Apply [p] (animations keep running until another profile or AYN mode). */
    fun apply(ctx: Context, p: LightProfile) {
        h.post {
            if (paused) { profile = p; return@post }   // applied on resume
            if (p == profile && touched) return@post
            profile = p
            h.removeCallbacks(frame); h.removeCallbacks(sample)
            if (p.mode != LightMode.SCREEN) screenStop?.invoke()
            start = SystemClock.uptimeMillis()
            when (p.mode) {
                LightMode.AYN -> if (touched) { restoreAyn(ctx); touched = false }
                LightMode.OFF -> { enable(false); touched = true }
                LightMode.STATIC -> { enable(true); write(p.left, p.right, p.brightness); touched = true }
                else -> {
                    enable(true); touched = true
                    if (p.mode == LightMode.SCREEN) h.post(sample)
                    h.post(frame)
                }
            }
        }
    }

    // ── animation ───────────────────────────────────────────────────────
    private val frame = object : Runnable {
        override fun run() {
            val p = profile
            val t = (SystemClock.uptimeMillis() - start) / 1000f * p.speed
            when (p.mode) {
                LightMode.BREATHING -> {
                    val k = (0.5f - 0.5f * kotlin.math.cos(2 * PI.toFloat() * t / 3f)).coerceIn(0.03f, 1f)
                    write(p.left, p.right, p.brightness * k)
                }
                LightMode.STROBE -> {
                    val on = ((t * 4f).toInt() % 2) == 0
                    write(p.left, p.right, if (on) p.brightness else 0f)
                }
                LightMode.SPECTRUM -> {
                    val hue = (t * 60f) % 360f
                    write(hsv(hue), hsv((hue + 180f) % 360f), p.brightness)
                }
                LightMode.SCREEN -> {
                    // Glide toward the latest screen colour (~0.1 s time constant at 30 fps):
                    // smooth, but close behind the picture.
                    screenColor = blend(screenColor, screenTarget, 0.28f)
                    screenColorR = blend(screenColorR, screenTargetR, 0.28f)
                    write(screenColor, if (p.split) screenColorR else screenColor, p.brightness)
                }
                else -> return
            }
            h.postDelayed(this, 33)
        }
    }

    private val sample = object : Runnable {
        override fun run() {
            if (profile.mode != LightMode.SCREEN) return
            screenSampler?.invoke()?.takeIf { it.size >= 3 }?.let { c ->
                if (profile.split) { screenTarget = vivid(c[1]); screenTargetR = vivid(c[2]) }
                else { screenTarget = vivid(c[0]); screenTargetR = screenTarget }
            }
            h.postDelayed(this, 60)   // the root sampler streams ~12 colours/s (fallback: a11y, ~3/s)
        }
    }

    // ── sysfs ───────────────────────────────────────────────────────────
    private var lastWritten = arrayOf("", "")

    private fun write(left: Int, right: Int, level: Float) {
        listOf(left, right).forEachIndexed { i, c ->
            val s = "1-${ch(Color.red(c), level)}:${ch(Color.green(c), level)}:${ch(Color.blue(c), level)}"
            if (s != lastWritten[i]) {
                put("${SIDES[i]}/brightness", s); lastWritten[i] = s
                if (i == 0 && app.wayfinder.BuildConfig.DEBUG && profile.mode == LightMode.SCREEN) Log.v(TAG, "led $s")
            }
        }
    }

    private fun enable(on: Boolean) {
        SIDES.forEach { put("$it/enable", if (on) "1" else "0") }
        lastWritten = arrayOf("", "")
    }

    /**
     * Screen colour → LED drive. On the Thor's rings even small green/blue values glow
     * visibly: a UI red (255,59,48) came out pink, still pinkish at gamma 2.2, while a pure
     * 255:0:0 is a clean red (2026-09-23). So: gamma 2.8, and channels that end up
     * nearly dark are switched fully off.
     */
    private fun ch(v: Int, level: Float): Int {
        val lin = Math.pow((v / 255.0), 2.8) * level.coerceIn(0f, 1f)
        val out = (lin * 255).toInt().coerceIn(0, 255)
        return if (out < 6) 0 else out
    }

    private fun put(path: String, value: String) {
        try { File(path).writeText(value) }
        catch (e: Exception) { Log.w(TAG, "write $path: ${e.message}") }
    }

    /** Exactly what AYN's own settings say (the state we leave the lights in). */
    private fun restoreAyn(ctx: Context) {
        val cr = ctx.contentResolver
        val enabled = (Settings.System.getString(cr, "joystick_light_enabled") ?: "1,1").split(",")
        val colors = (Settings.System.getString(cr, "joystick_led_light_picker_color") ?: "#2BE0D8,#2BE0D8").split(",")
        val level = runCatching { Settings.System.getString(cr, "led_light_brightness_percent")?.toFloat() }.getOrNull() ?: 0.5f
        SIDES.forEachIndexed { i, side -> put("$side/enable", enabled.getOrElse(i) { "1" }.trim()) }
        lastWritten = arrayOf("", "")
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
