package app.wayfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** What the gyro drives. */
enum class GyroMode(val label: String, val caption: String) {
    OFF("Off", "the game reads the gyro itself, or nothing"),
    MOUSE("Mouse", "camera in PC games, a cursor"),
    RIGHT_STICK("Right stick", "camera in games without a mouse"),
    LEFT_STICK("Left stick", "steering: left / right only"),
}

/** When the gyro works. The button ones use [GyroSettings.button]; the trigger ones L2 or R2. */
enum class GyroOn(val label: String, val usesButton: Boolean, val trigger: Boolean = false) {
    ALWAYS("Always", false),
    HOLD("While holding", true),
    TOGGLE("Toggle with", true),
    OFF_WHILE_HOLD("Off while holding", true),
    TRIGGER_FULL("Full trigger pull", true, trigger = true),
    TRIGGER_HALF("Half trigger pull", true, trigger = true),
    /** 1.4 (Reddit): while a finger rests on either screen. */
    TOUCH("While touching a screen", false),
}

/** Which motion turns the camera left / right. */
enum class GyroAxis(val label: String, val caption: String) {
    BOTH("Turn + tilt", "recommended"),
    TURN("Turn", "swivel chair"),
    TILT("Tilt", "steering wheel"),
}

data class GyroSettings(
    val mode: GyroMode = GyroMode.OFF,
    val on: GyroOn = GyroOn.ALWAYS,
    val button: ThorButton = ThorButton.L2,
    /** 1 = default speed (mouse: 12 px per degree; stick: full tilt at 150 °/s). */
    val sens: Float = 1f,
    /** Up / down speed relative to left / right. */
    val vScale: Float = 1f,
    val invertY: Boolean = false,
    val axis: GyroAxis = GyroAxis.BOTH,
    /** Hand-shake filtering: 0 off, 1 light, 2 strong (tiered smoothing: only slow motion). */
    val steady: Int = 1,
    /** Stick modes: the game's stick deadzone to jump over, in % (small motions still count). */
    val gameDeadzone: Int = 15,
) {
    val isOn get() = mode != GyroMode.OFF
    fun summary(): String = if (!isOn) "Off" else mode.label + " · " + when (on) {
        GyroOn.ALWAYS -> "always"
        GyroOn.HOLD -> "hold ${button.label}"
        GyroOn.TOGGLE -> "toggle ${button.label}"
        GyroOn.OFF_WHILE_HOLD -> "off holding ${button.label}"
        GyroOn.TRIGGER_FULL -> "full ${button.label}"
        GyroOn.TRIGGER_HALF -> "half ${button.label}"
        GyroOn.TOUCH -> "while touching"
    }

    fun toJson(): JSONObject = JSONObject().put("m", mode.name).put("on", on.name).put("b", button.name)
        .put("s", sens.toDouble()).put("v", vScale.toDouble()).put("iy", invertY).put("ax", axis.name)
        .put("st", steady).put("dz", gameDeadzone)

    companion object {
        /** Buttons that can switch the gyro (the D-pad and Home / Back can't). */
        val BUTTONS = listOf(ThorButton.L2, ThorButton.R2, ThorButton.L1, ThorButton.R1, ThorButton.L3, ThorButton.R3,
            ThorButton.A, ThorButton.B, ThorButton.X, ThorButton.Y, ThorButton.SELECT, ThorButton.START)

        fun fromJson(o: JSONObject?): GyroSettings? {
            o ?: return null
            fun <T : Enum<T>> e(v: Array<T>, k: String, d: T) = v.firstOrNull { it.name == o.optString(k) } ?: d
            val d = GyroSettings()
            val on = e(GyroOn.values(), "on", d.on)
            var b = e(ThorButton.values(), "b", d.button).takeIf { it in BUTTONS } ?: d.button
            if (on.trigger && b != ThorButton.L2 && b != ThorButton.R2) b = ThorButton.L2
            return GyroSettings(e(GyroMode.values(), "m", d.mode), on, b,
                // "NaN" in a file passed coerceIn and crashed the app when saved (review 2026-09-25)
                o.optDouble("s", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(.2f, 4f) ?: 1f,
                o.optDouble("v", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(.3f, 2f) ?: 1f,
                o.optBoolean("iy"), e(GyroAxis.values(), "ax", d.axis), o.optInt("st", 1).coerceIn(0, 2),
                o.optInt("dz", 15).coerceIn(0, 40))
        }
    }
}

/**
 * The gyro → mouse / stick engine. Reads the Thor's gyroscope (SENODIA sh5001, ≤ 200 Hz) only
 * while the app that has the controller has gyro on and the screen is on. Pipeline (GyroWiki):
 * calibrated rate → display frame → player space (turn + tilt via gravity) → tiered smoothing →
 * mouse pixels (helper "M", sub-pixel carried) or a stick deflection (wfpad "g", which adds it
 * to the stick and holds it back while Home / Back are held). Activation from wfpad's raw state
 * of the chosen button / trigger ("V" lines).
 */
object GyroEngine : SensorEventListener {
    private const val TAG = "ThorGyro"
    private lateinit var app: Context
    private val thread by lazy { HandlerThread("gyro").apply { start() } }
    private val h by lazy { Handler(thread.looper) }
    private var sm: SensorManager? = null

    // what's wanted (main thread writes, gyro thread reads)
    @Volatile private var cfg: GyroSettings? = null
    @Volatile private var pkg: String? = null
    @Volatile private var screenOn = true
    /** Home + Y panel: gyro off for this game (back on when another app gets the controller). */
    @Volatile var pausedByUser = false
        private set
    /** The remap screen's live preview: reads even with no game in front. */
    @Volatile private var preview: GyroSettings? = null
    /** Live output for the preview, in −1..1 (x, y) and whether the gyro is on right now. */
    @Volatile var onLive: ((Float, Float, Boolean) -> Unit)? = null

    // state (gyro thread only)
    private var listening = false
    private var btnDown = false
    private var trig = 0
    private var latched = false
    private var lastTs = 0L
    private var simulating = false
    private val grav = floatArrayOf(0f, 0f, 1f)
    private var fracX = 0f; private var fracY = 0f
    private var lastStick = Triple('0', 0, 0)
    private val bufX = FloatArray(25); private val bufY = FloatArray(25); private var bufI = 0
    private var rot = Surface.ROTATION_90; private var rotAt = 0L
    private var bias = floatArrayOf(0f, 0f, 0f)
    // auto-calibration: a still Thor (on a table) for 1.5 s
    private var stillN = 0; private val stillSum = FloatArray(3); private var stillMax = 0f; private val stillFirst = FloatArray(3)
    private var calibrateUntil = 0L; private var calibrating: ((Boolean) -> Unit)? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = app.getSharedPreferences("gyro", Context.MODE_PRIVATE)
        bias = floatArrayOf(p.getFloat("bx", 0f), p.getFloat("by", 0f), p.getFloat("bz", 0f))
    }

    /** The app that has the controller and its gyro settings (the service's 500 ms tick). */
    fun follow(app: String?, settings: GyroSettings?) {
        if (app != pkg) { pkg = app; pausedByUser = false; h.post { latched = false } }
        if (settings != cfg) { cfg = settings; h.post { reconfigure() } } else h.post { ensureListening() }
    }

    fun setScreenOn(on: Boolean) { screenOn = on; h.post { ensureListening() } }

    /** Home + Y panel toggle. */
    fun setPausedByUser(p: Boolean) { pausedByUser = p; h.post { ensureListening(); if (p) output(0f, 0f, false) }; buzz() }

    /** A (new) helper / wfpad knows nothing: re-send what it should watch, and the stick. */
    fun resync() = h.post { lastStick = Triple('x', 0, 0); reconfigure() }

    val configuredForCurrent: GyroSettings? get() = cfg?.takeIf { it.isOn }

    /** The remap screen's gyro popup: preview these settings (null = stop). */
    fun setPreview(s: GyroSettings?) { preview = s?.takeIf { it.isOn }; h.post { reconfigure() } }

    /** Put the Thor down: 2 s of readings become the new zero. */
    fun calibrateNow(done: (Boolean) -> Unit) = h.post {
        calibrating = done; calibrateUntil = System.currentTimeMillis() + 2000
        stillN = 0; stillSum.fill(0f); stillMax = 0f
        start()
    }

    /** wfpad "V": a watched control changed (printed key code, or an axis). */
    fun onWatched(type: Int, code: Int, value: Int) = h.post {
        val s = active() ?: return@post
        if (type == ThorInput.EV_KEY && code == PadRemap.CODE[s.button]) {
            val down = value != 0
            if (down && !btnDown && s.on == GyroOn.TOGGLE) { latched = !latched; buzz() }
            btnDown = down
        } else if (type == ThorInput.EV_ABS && code == triggerAxis(s.button)) trig = value
        ensureListening()
    }

    /** Test hook (debug builds only): pretend the Thor turns at x, y, z °/s (display frame) for [ms]. */
    fun simulate(x: Float, y: Float, z: Float, ms: Long) = h.post {
        val t0 = System.nanoTime(); val end = t0 + ms * 1_000_000
        simulating = true
        val r = object : Runnable { override fun run() {
            val now = System.nanoTime()
            process(floatArrayOf(x, y, z), now)
            if (now < end) h.postDelayed(this, 5) else { simulating = false; lastTs = 0; output(0f, 0f, true) }
        } }
        lastTs = t0; r.run()
    }

    // ── internals (gyro thread) ──

    private fun active(): GyroSettings? = preview?.takeIf { screenOn } ?: cfg?.takeIf { it.isOn && screenOn && !pausedByUser }

    private fun triggerAxis(b: ThorButton) = if (b == ThorButton.R2) ThorInput.ABS_R2 else ThorInput.ABS_L2

    private var wasMouse = false
    private fun reconfigure() {
        // a stick the previous settings held (e.g. right-stick gyro → a mouse-gyro game) goes back to centre
        output(0f, 0f, false)
        btnDown = false; trig = 0; latched = false
        val s = active()
        // the mouse-gyro game is gone: its virtual mouse goes too (unless the deck's trackpad uses it)
        val mouse = s?.mode == GyroMode.MOUSE && preview == null
        if (wasMouse && !mouse && ForegroundAppService.keyboardDeckShowing() != true) InputMonitor.send("R")   // = VirtualInput.releaseMouse()
        wasMouse = mouse
        // what wfpad should report: the chosen button, or the trigger's axis
        val watch = when {
            s == null || !s.on.usesButton -> ""
            s.on.trigger -> "a${triggerAxis(s.button)}"
            else -> "k${PadRemap.CODE[s.button]}"
        }
        InputMonitor.send("G w $watch".trimEnd())
        ensureListening()
    }

    /** Is the gyro doing something right now (activation)? */
    private fun engaged(s: GyroSettings): Boolean = when (s.on) {
        GyroOn.ALWAYS -> true
        GyroOn.HOLD -> btnDown
        GyroOn.OFF_WHILE_HOLD -> !btnDown
        GyroOn.TOGGLE -> latched
        GyroOn.TRIGGER_FULL -> trig >= 32767 * .88f
        GyroOn.TRIGGER_HALF -> trig >= 32767 * .30f
        GyroOn.TOUCH -> ForegroundAppService.fingerOnAScreen()
    }

    /** The sensor runs while a mode is on (activation is cheap to check per event, and the
     *  toggle / trigger must react at once): only with an app that has gyro, screen on. */
    private fun ensureListening() { if (active() != null || calibrating != null) start() else stop() }

    private var savedAt = 0L

    private fun start() {
        if (listening || !::app.isInitialized) return
        val m = sm ?: app.getSystemService(SensorManager::class.java).also { sm = it }
        val gyro = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        m.registerListener(this, gyro, 5000, h)                     // 200 Hz (more needs a permission)
        m.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { m.registerListener(this, it, 20000, h) }
        listening = true; lastTs = 0
        Log.i(TAG, "reading the gyro for ${preview?.let { "the preview" } ?: pkg}")
    }

    private fun stop() {
        if (!listening) return
        sm?.unregisterListener(this); listening = false
        output(0f, 0f, false)
        Log.i(TAG, "gyro stopped")
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val v = toDisplay(e.values)
        if (e.sensor.type == Sensor.TYPE_GRAVITY) {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1f } ?: return
            grav[0] = v[0] / n; grav[1] = v[1] / n; grav[2] = v[2] / n
            return
        }
        if (simulating) return
        val deg = floatArrayOf(v[0] * 57.29578f, v[1] * 57.29578f, v[2] * 57.29578f)
        calibrate(deg)
        process(floatArrayOf(deg[0] - bias[0], deg[1] - bias[1], deg[2] - bias[2]), e.timestamp)
    }

    /** Sensor axes are the device's natural (portrait) frame; the Thor's top screen is turned. */
    private fun toDisplay(s: FloatArray): FloatArray {
        val now = System.currentTimeMillis()
        if (now - rotAt > 1000) {
            rotAt = now
            rot = runCatching { app.getSystemService(DisplayManager::class.java).getDisplay(0).rotation }.getOrDefault(rot)
        }
        return when (rot) {
            Surface.ROTATION_90 -> floatArrayOf(s[1], -s[0], s[2])
            Surface.ROTATION_180 -> floatArrayOf(-s[0], -s[1], s[2])
            Surface.ROTATION_270 -> floatArrayOf(-s[1], s[0], s[2])
            else -> floatArrayOf(s[0], s[1], s[2])
        }
    }

    private fun calibrate(deg: FloatArray) {
        if (stillN == 0) for (i in 0..2) stillFirst[i] = deg[i]
        val dev = maxOf(abs(deg[0] - stillFirst[0]), abs(deg[1] - stillFirst[1]), abs(deg[2] - stillFirst[2]))
        val manual = calibrating != null
        if (!manual && dev > 1.2f) { stillN = 0; stillSum.fill(0f); stillMax = 0f; return }   // moving: start over
        stillMax = maxOf(stillMax, dev); stillN++
        for (i in 0..2) stillSum[i] += deg[i]
        val done = if (manual) System.currentTimeMillis() >= calibrateUntil else stillN >= 300
        if (!done) return
        val mean = FloatArray(3) { stillSum[it] / stillN }
        val ok = stillMax < 3f && stillN > 50
        if (ok) {
            // auto: only a small, slow correction (a slow steady turn can't become the zero)
            if (manual) bias = mean
            else if (hypot(hypot(mean[0] - bias[0], mean[1] - bias[1]), mean[2] - bias[2]) < 2f)
                for (i in 0..2) bias[i] += (mean[i] - bias[i]) * .3f
            // stored now and then only: a Thor lying still re-calibrates every 1.5 s (flash writes)
            val now = System.currentTimeMillis()
            if (manual || now - savedAt > 5 * 60_000) {
                savedAt = now
                app.getSharedPreferences("gyro", Context.MODE_PRIVATE).edit()
                    .putFloat("bx", bias[0]).putFloat("by", bias[1]).putFloat("bz", bias[2]).apply()
            }
        }
        calibrating?.let { cb -> calibrating = null; cb(ok); Log.i(TAG, "calibrated: $ok (${bias.joinToString { "%.2f".format(it) }})"); ensureListening() }
        stillN = 0; stillSum.fill(0f); stillMax = 0f
    }

    /** One gyro reading (°/s, display frame: x right, y up the body, z out of the face). */
    private fun process(w: FloatArray, ts: Long) {
        val dt = if (lastTs == 0L) 0f else ((ts - lastTs) / 1e9f).coerceIn(0f, .05f)
        lastTs = ts
        val s = active() ?: return
        if (!engaged(s)) { output(0f, 0f, false); return }
        // left / right (+ = right): player space = turn about gravity, relaxed so a tilt counts too
        val worldYaw = w[1] * grav[1] + w[2] * grav[2]
        val turn = -(w[0] * grav[0] + worldYaw)
        val tilt = w[1]
        var x = when (s.axis) {
            GyroAxis.TURN -> turn
            GyroAxis.TILT -> tilt
            GyroAxis.BOTH -> -sign(worldYaw) * min(abs(worldYaw) * 1.41f, sqrt(w[1] * w[1] + w[2] * w[2]))
        }
        // up / down (+ = down, like the screen): raising the far edge aims up
        var y = if (s.mode == GyroMode.LEFT_STICK) 0f else -w[0] * s.vScale
        if (s.invertY) y = -y
        // tiered smoothing: slow motion (hand shake) averaged, fast motion direct
        if (s.steady > 0) {
            val t1 = if (s.steady == 1) 1.5f else 3f; val t2 = t1 * 2
            val m = hypot(x, y); val direct = ((m - t1) / (t2 - t1)).coerceIn(0f, 1f)
            bufX[bufI] = x * (1 - direct); bufY[bufI] = y * (1 - direct); bufI = (bufI + 1) % bufX.size
            x = x * direct + bufX.sum() / bufX.size; y = y * direct + bufY.sum() / bufY.size
        }
        x *= s.sens; y *= s.sens
        when (s.mode) {
            GyroMode.MOUSE -> {
                fracX += x * dt * 12f; fracY += y * dt * 12f
                val mx = fracX.toInt(); val my = fracY.toInt()
                if (preview == null && (mx != 0 || my != 0)) { InputMonitor.send("M $mx $my"); fracX -= mx; fracY -= my }
                if (preview != null) { fracX -= mx; fracY -= my }
                onLive?.invoke((x / 150f).coerceIn(-1f, 1f), (y / 150f).coerceIn(-1f, 1f), true)
            }
            GyroMode.RIGHT_STICK, GyroMode.LEFT_STICK -> {
                var fx = x / 150f; var fy = y / 150f
                val m = hypot(fx, fy)
                if (m < .004f) { fx = 0f; fy = 0f } else {
                    val adz = s.gameDeadzone / 100f
                    val out = (adz + (1 - adz) * min(1f, m)) / m
                    fx *= out; fy *= out
                }
                output(fx, fy, true)
            }
            GyroMode.OFF -> {}
        }
    }

    /** A stick deflection (−1..1) to wfpad, only when it changes; mouse mode: nothing to hold. */
    private fun output(fx: Float, fy: Float, on: Boolean) {
        onLive?.invoke(fx, fy, on)
        if (!on) { fracX = 0f; fracY = 0f; bufX.fill(0f); bufY.fill(0f) }
        val s = active()
        val c = if (preview == null && s != null && on) when (s.mode) { GyroMode.RIGHT_STICK -> 'r'; GyroMode.LEFT_STICK -> 'l'; else -> '0' } else '0'
        val t = Triple(c, (fx * 32767).toInt().coerceIn(-32767, 32767), (fy * 32767).toInt().coerceIn(-32767, 32767))
        val send = if (c == '0') Triple('0', 0, 0) else t
        if (send != lastStick) { lastStick = send; InputMonitor.send("G g ${send.first} ${send.second} ${send.third}") }
    }

    private fun buzz() = runCatching {
        @Suppress("DEPRECATION")
        (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
