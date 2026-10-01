package app.wayfinder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.ln

/**
 * 1.4 (Reddit): an equalizer for what's plugged in — wired headphones, USB audio (headsets, DACs) and Bluetooth each
 * have their own (off until chosen). Android's own DynamicsProcessing on the output mix (session 0), like the speaker
 * fix — which only plays on the speakers, so the two never stack. It follows the output: the one connected last is
 * the one Android plays to. Driven by [SpeakerTune.apply] (the device changes, the watchdog, the volume boost).
 */
object HeadphoneEq {
    private const val TAG = "ThorHeadphoneEq"

    enum class Out(val title: String) { WIRED("Wired"), USB("USB"), BT("Bluetooth") }

    /** The ten bands' centres (Hz). */
    val FREQS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    /** The choices, in order; "Custom" is the user's own ten bands. */
    val PRESETS: List<Pair<String, List<Float>?>> = listOf(
        "Off" to null,
        "More bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Warm" to listOf(3f, 3f, 2f, 1f, 0f, 0f, -1f, -1f, -2f, -2f),
        "Clear voices" to listOf(-2f, -1f, 0f, 0f, 1f, 2f, 3f, 3f, 2f, 1f),
        "V-shape" to listOf(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 4f),
        "Custom" to null,
    )
    const val CUSTOM = 5
    private const val BANDS = 40
    private const val LIMIT_DB = -0.1f

    /** Bumped on every change — the Sound page recomposes from it. */
    var version by mutableIntStateOf(0)
        private set
    /** The output playing now (null: the speakers, or HDMI) — for the page. */
    var playing by mutableStateOf<Out?>(null)
        private set

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("thor_audio", Context.MODE_PRIVATE) }
    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    fun preset(o: Out): Int = if (!::app.isInitialized) 0 else prefs.getInt("hpeq_${o.name}", 0).coerceIn(0, PRESETS.size - 1)
    fun custom(o: Out): List<Float> = prefs.getString("hpeq_${o.name}_custom", null)?.split(',')
        ?.mapNotNull { it.toFloatOrNull()?.coerceIn(-12f, 12f) }?.takeIf { it.size == FREQS.size } ?: List(FREQS.size) { 0f }
    fun gains(o: Out): List<Float>? = if (preset(o) == CUSTOM) custom(o) else PRESETS[preset(o)].second

    fun choose(o: Out, i: Int) {
        // Custom starts from the preset it replaces, so a small change is a small change
        if (i == CUSTOM && !prefs.contains("hpeq_${o.name}_custom")) gains(o)?.let { saveCustom(o, it) }
        prefs.edit().putInt("hpeq_${o.name}", i).apply(); changed()
    }
    /** One point moved: the curve shown (a preset, or flat when Off) becomes the Custom one, with that change. */
    fun edit(o: Out, band: Int, db: Float) {
        val base = gains(o) ?: List(FREQS.size) { 0f }
        saveCustom(o, base.toMutableList().apply { this[band] = db.coerceIn(-12f, 12f) })
        prefs.edit().putInt("hpeq_${o.name}", CUSTOM).apply(); changed()
    }
    private fun saveCustom(o: Out, g: List<Float>) = prefs.edit().putString("hpeq_${o.name}_custom", g.joinToString(",")).apply()
    private fun changed() { version++; SpeakerTune.apply() }

    // ── which output ────────────────────────────────────────────────────
    private fun kindOf(type: Int): Out? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Out.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Out.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Out.BT
        else -> null
    }
    @Volatile private var lastAdded: Out? = null
    /** From the device callback: Android plays to the removable output connected last. */
    fun noteAdded(added: Array<out AudioDeviceInfo>?) {
        added?.filter { it.isSink }?.mapNotNull { kindOf(it.type) }?.lastOrNull()?.let { lastAdded = it }
    }
    /** The external output playing now, or null (the speakers — or HDMI, which isn't ours to tune). */
    fun now(ctx: Context): Out? {
        val outs = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outs.any { it.type == AudioDeviceInfo.TYPE_HDMI }) return null
        val kinds = outs.mapNotNull { kindOf(it.type) }.toSet()
        return lastAdded?.takeIf { it in kinds } ?: listOf(Out.USB, Out.WIRED, Out.BT).firstOrNull { it in kinds }
    }

    // ── the effect ──────────────────────────────────────────────────────
    private var fx: DynamicsProcessing? = null
    private var fxKey = ""

    /** Play [out]'s EQ (null: none) with [boost] dB carried; called under SpeakerTune's lock. Returns true if it plays. */
    fun sync(out: Out?, boost: Int): Boolean {
        if (::app.isInitialized) playing = out
        val g = out?.let { gains(it) }
        if (g == null) { release(); return false }
        val key = "$out ${g.joinToString(",")} $boost"
        if (fx != null && key == fxKey) return true
        fun eqOf(): DynamicsProcessing.Eq = DynamicsProcessing.Eq(true, true, BANDS).also { eq ->
            for (i in 0 until BANDS) {
                val lo = freqAt(i.toFloat() / BANDS); val hi = freqAt((i + 1f) / BANDS)
                eq.setBand(i, DynamicsProcessing.EqBand(true, hi, curve(g, pointAt(kotlin.math.sqrt(lo * hi)))))
            }
        }
        // headroom for the boosted bands (the limiter only catches what's left), plus the volume boost
        val gain = boost - (g.maxOrNull() ?: 0f).coerceAtLeast(0f)
        // 1.4 (review): the same output and boost (a curve being dragged) → into the running effect, no dropout
        val running = fx
        if (running != null && fxKey.startsWith("$out ") && fxKey.endsWith(" $boost") &&
            runCatching { running.setPreEqAllChannelsTo(eqOf()); running.setInputGainAllChannelsTo(gain) }.isSuccess) {
            fxKey = key; return true
        }
        release()
        fx = runCatching {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, true).setPreferredFrameDuration(10f).build()
            cfg.setPreEqAllChannelsTo(eqOf())
            cfg.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 500f, 10f, LIMIT_DB, 0f))
            cfg.setInputGainAllChannelsTo(gain)
            DynamicsProcessing(Int.MAX_VALUE, 0, cfg).also { it.enabled = true }
        }.onFailure { Log.w(TAG, "headphone EQ: $it") }.getOrNull()
        fxKey = if (fx != null) key else ""
        Log.i(TAG, "EQ for ${out.title}: ${if (fx != null) "on" else "FAILED"}")
        return fx != null
    }

    private fun release() { fx?.let { runCatching { it.enabled = false; it.release() } }; fx = null; fxKey = "" }

    /** The audio server dropped it (restart): the watchdog rebuilds. */
    fun dead(): Boolean = fx != null && !runCatching { fx!!.enabled && fx!!.hasControl() }.getOrDefault(false)
    fun drop() = release()
    /** Carrying the volume boost right now (for the boost's status line). */
    fun active(): Boolean = fx != null
    fun hasControl(): Boolean = fx?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false

    private fun freqAt(t: Float): Float = exp(ln(25f) + t * (ln(20000f) - ln(25f)))

    /** A frequency as a position among the points (they're an octave apart: 0 = 31 Hz … 9 = 16 kHz). */
    private fun pointAt(f: Float): Float = ln(f / 31.25f) / ln(2f)

    /** The curve at [t] (in points): a smooth line through them that never overshoots (monotone cubic — a raised
     *  point doesn't dip its neighbours), flat beyond the ends. The same curve the EQ page draws. */
    fun curve(g: List<Float>, t: Float): Float {
        val last = g.size - 1
        if (t <= 0f) return g.first()
        if (t >= last) return g.last()
        val d = FloatArray(last) { g[it + 1] - g[it] }
        val m = FloatArray(g.size) { k -> if (k == 0 || k == last || d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f }
        for (k in 0 until last) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
            val a = m[k] / d[k]; val b = m[k + 1] / d[k]; val s = a * a + b * b
            if (s > 9f) { val r = 3f / kotlin.math.sqrt(s); m[k] = r * a * d[k]; m[k + 1] = r * b * d[k] }
        }
        val i = t.toInt(); val u = t - i
        val h00 = 2 * u * u * u - 3 * u * u + 1; val h10 = u * u * u - 2 * u * u + u
        val h01 = -2 * u * u * u + 3 * u * u; val h11 = u * u * u - u * u
        return h00 * g[i] + h10 * m[i] + h01 * g[i + 1] + h11 * m[i + 1]
    }
}
