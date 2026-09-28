package app.wayfinder

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln
import kotlin.math.exp

/**
 * The Thor speaker fix, without the external DSP app. The community's fix is a graphic
 * EQ for the Thor's small speakers (sub-bass cut, deep mid scoop, highs kept), +15 dB
 * output gain and a limiter — the curve below is that community preset's curve: "Joey's Retro
 * Handhelds tuning" by Joey (Retro Handhelds), credited in THIRD_PARTY_NOTICES.txt and in the app.
 *
 * Here it's Android's own DynamicsProcessing effect on the global output mix (session 0)
 * — AYN's own equalizer uses the same session — so it's native: no audio capture, no
 * added latency. Stages: input gain → pre-EQ (40 log-spaced bands, gains interpolated
 * from the curve) → limiter. Only while the INTERNAL SPEAKERS play (the curve would ruin
 * headphones); follows plugging in / Bluetooth.
 */
object SpeakerTune {
    private const val TAG = "ThorSpeaker"

    /** (Hz, dB) — the curve, as the fix defines it. */
    val CURVE = listOf(70 to -32f, 110 to -4.5f, 120 to -4f, 200 to -4f, 300 to -5f, 444 to -9f, 600 to -13.5f,
        700 to -17f, 850 to -17f, 1200 to -18.5f, 1860 to -20f, 2800 to -19.5f, 3800 to -21.5f, 5000 to -13f,
        7000 to -6f, 9050 to -0.5f, 11000 to 0.5f, 12000 to -0.5f, 16000 to -3f)
    private const val OUTPUT_GAIN_DB = 15f
    private const val LIMIT_DB = -0.1f
    private const val BANDS = 40

    var enabled by mutableStateOf(false)
        private set
    /** Stereo width of the widener (the community preset: 2 — 1 = no widening). */
    var width by mutableStateOf(2f)
        private set
    /** The EQ + loudness stage (DynamicsProcessing), switchable apart from the widener for A/B. */
    var eqOn by mutableStateOf(true)
        private set
    /** What's happening right now, for the UI. */
    var status by mutableStateOf("Off")
        private set
    /** 1.3 — the volume booster: extra dB (0 = off) before a limiter, on every output. */
    var boost by mutableStateOf(0)
        private set
    /** The boost [dp] was built with (a new boost rebuilds it). */
    private var dpBoost = -1
    /** Boost only (the speaker fix isn't playing): a limiter-only effect. */
    private var boostFx: DynamicsProcessing? = null
    private var boostFxDb = -1
    val BOOST_STEPS = listOf(0, 3, 6, 9, 12)

    private lateinit var app: Context
    private var dp: DynamicsProcessing? = null
    /** The widener, as a GLOBAL effect (session 0) held by us — see [syncWide]. */
    private var wide: android.media.audiofx.AudioEffect? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { app.getSharedPreferences("thor_audio", Context.MODE_PRIVATE) }
    private var started = false
    @Volatile private var installing = false
    @Volatile private var installFailed = false

    /** Settings only (the UI can read and change them before the service runs; the
     *  service's [start] applies them). */
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        enabled = prefs.getBoolean("speaker_tune", false)
        width = prefs.getFloat("stereo_width", 2f)
        eqOn = prefs.getBoolean("speaker_eq", true)
        boost = prefs.getInt("volume_boost", 0).coerceIn(0, 12)
    }

    fun setVolumeBoost(db: Int) {
        boost = db.coerceIn(0, 12)
        prefs.edit().putInt("volume_boost", boost).apply()
        apply()
    }

    fun start(ctx: Context) {
        if (started) return
        init(ctx)
        started = true
        app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(devices, main)
        apply()
        main.postDelayed(watchdog, WATCH_MS)
    }

    fun setOn(on: Boolean) {
        enabled = on
        if (on) installFailed = false
        prefs.edit().putBoolean("speaker_tune", on).apply()
        // Two EQs stacked would be wrong: hand over from the external DSP app, and back.
        // Its state can't be read, so it's paused when the fix starts and never switched back on
        // behind the user's back (that turned it on for people who had it off).
        if (on) externalDsp(false)
        apply()
    }

    fun setEq(on: Boolean) {
        eqOn = on
        prefs.edit().putBoolean("speaker_eq", on).apply()
        apply()
    }

    fun setStereoWidth(w: Float) {
        width = w.coerceIn(1f, 3f)
        prefs.edit().putFloat("stereo_width", width).apply()
        apply()
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = apply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = apply()
    }

    /** Headphones / USB / Bluetooth audio connected → the speakers aren't playing. */
    private fun onSpeaker(): Boolean {
        val outs = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val external = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HDMI)
        return outs.none { it.type in external }
    }

    @Synchronized fun apply() {
        if (!started) return
        val want = enabled && onSpeaker()
        syncBoost(boost > 0 && !(want && eqOn))    // the fix's own effect carries the boost when it plays
        if (dp != null && dpBoost != boost) { runCatching { dp!!.enabled = false; dp!!.release() }; dp = null }
        // The widener (a native effect, see AudioFx): neutral unless the fix is playing.
        AudioFx.setWidth(if (want && AudioFx.installed) width else 0f)
        syncWide(want && AudioFx.installed)
        if (want && !AudioFx.installed && !installing && !installFailed) {
            installing = true
            Log.i(TAG, "adding the stereo widener")
            Thread {
                val ok = runCatching { AudioFx.ensureInstalled(app) }.onFailure { Log.w(TAG, "widener install: $it") }.getOrDefault(false)
                Log.i(TAG, "stereo widener ${if (ok) "in place" else "not available"}")
                main.post {
                    installing = false
                    if (!ok) { installFailed = true; apply(); return@post }   // no retry loop: next time the fix is switched on
                    // The audio server restarted: effects made before it are dead — make ours again.
                    main.postDelayed({
                        dp?.let { runCatching { it.release() } }; dp = null
                        wide?.let { runCatching { it.release() } }; wide = null
                        apply()
                    }, 2500)
                }
            }.start()
        }
        if (!want || !eqOn) {
            dp?.let { runCatching { it.enabled = false; it.release() } }
            dp = null
            status = when {
                !enabled -> "Off"
                !want -> "Paused — headphones / Bluetooth / HDMI audio in use"
                AudioFx.installed -> "On — equalizer off, stereo width ${"%.1f".format(width)}"
                else -> "On — equalizer off"
            }
            return
        }
        if (dp != null) { status = onStatus(dp!!); return }   // width / widener state may have changed
        status = runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS,     // pre-EQ
                false, 0,        // multi-band compressor
                false, 0,        // post-EQ
                true,            // limiter
            ).setPreferredFrameDuration(10f).build()
            val eq = DynamicsProcessing.Eq(true, true, BANDS)
            for (i in 0 until BANDS) {
                // Band i covers up to its cutoff; log-spaced 25 Hz … 20 kHz, gain read off
                // the curve at the band's centre.
                val lo = freqAt(i.toFloat() / BANDS); val hi = freqAt((i + 1f) / BANDS)
                eq.setBand(i, DynamicsProcessing.EqBand(true, hi, gainAt(kotlin.math.sqrt(lo * hi))))
            }
            cfg.setPreEqAllChannelsTo(eq)
            cfg.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 500f, 10f, LIMIT_DB, 0f))
            cfg.setInputGainAllChannelsTo(OUTPUT_GAIN_DB + boost)   // linear stages: order-independent before the limiter
            val fx = DynamicsProcessing(Int.MAX_VALUE, 0, cfg)
            fx.enabled = true
            dp = fx; dpBoost = boost
            Log.i(TAG, "speaker tune ON (enabled=${fx.enabled}, hasControl=${fx.hasControl()})")
            onStatus(fx)
        }.getOrElse { Log.w(TAG, "speaker tune failed: $it"); "Couldn't start the audio effect: ${it.message}" }
    }

    /**
     * The widener on the output mix, created here like the EQ. (As an audio_effects.xml
     * post-process it failed on this ROM: AYN's AudioFlinger moves post-process effects to
     * session 0, then refuses them whenever our EQ's session-0 chain sits on another output —
     * "denied because session 0 effect exists on io 13". Two session-0 effects from one
     * client are kept together and move with the audio.) The public constructor with an
     * effect UUID is hidden API; reflection, as EQ apps do. Our flag INSERT_FIRST puts it
     * before the EQ / limiter.
     */
    private fun syncWide(want: Boolean) {
        if (!want) { wide?.let { runCatching { it.enabled = false; it.release() } }; wide = null; return }
        if (wide != null && runCatching { wide!!.hasControl() }.getOrDefault(false)) return
        wide?.let { runCatching { it.release() } }
        wide = runCatching {
            val c = android.media.audiofx.AudioEffect::class.java.getConstructor(
                java.util.UUID::class.java, java.util.UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            (c.newInstance(java.util.UUID.fromString(AudioFx.TYPE_UUID), java.util.UUID.fromString(AudioFx.UUID), Int.MAX_VALUE, 0)
                as android.media.audiofx.AudioEffect).also { it.enabled = true }
        }.onFailure { Log.w(TAG, "widener effect: ${it.cause ?: it}") }.getOrNull()
        Log.i(TAG, "widener ${if (wide != null) "attached to the output mix" else "NOT attached"}")
    }

    /** 1.3 — the booster alone: input gain + a limiter (no EQ), on the output mix. */
    private fun syncBoost(want: Boolean) {
        if (!want) { boostFx?.let { runCatching { it.enabled = false; it.release() } }; boostFx = null; boostFxDb = -1; return }
        val alive = boostFx?.let { runCatching { it.enabled && it.hasControl() }.getOrDefault(false) } == true
        if (alive && boostFxDb == boost) return
        boostFx?.let { runCatching { it.release() } }
        boostFx = runCatching {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, false, 0, false, 0, true).setPreferredFrameDuration(10f).build()
            cfg.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 500f, 10f, LIMIT_DB, 0f))
            cfg.setInputGainAllChannelsTo(boost.toFloat())
            DynamicsProcessing(Int.MAX_VALUE, 0, cfg).also { it.enabled = true }
        }.onFailure { Log.w(TAG, "volume boost: $it") }.getOrNull()
        boostFxDb = if (boostFx != null) boost else -1
        Log.i(TAG, "volume boost +$boost dB ${if (boostFx != null) "on" else "FAILED"}")
    }

    /** For the UI: what the booster is doing. */
    fun boostStatus(): String = when {
        boost == 0 -> "Off"
        dp != null && dpBoost == boost -> "+$boost dB, with the speaker fix"
        boostFx != null && runCatching { boostFx!!.hasControl() }.getOrDefault(false) -> "+$boost dB"
        boostFx != null -> "+$boost dB (another app has priority on the audio effect)"
        else -> "+$boost dB — couldn't start"
    }

    // Effects die with the audio server (a crash, an update, a config reload) and nothing
    // tells us: check now and then, and rebuild what's dead.
    private const val WATCH_MS = 20_000L
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            // the booster alone: rebuilt when the audio server dropped it
            if (boostFx != null && !runCatching { boostFx!!.enabled && boostFx!!.hasControl() }.getOrDefault(false)) { boostFxDb = -1; apply() }
            if (enabled && !installing) {
                val dpDead = dp != null && !runCatching { dp!!.enabled && dp!!.hasControl() }.getOrDefault(false)
                val wideDead = wide != null && !runCatching { wide!!.enabled }.getOrDefault(false)
                if (dpDead || wideDead) {
                    Log.i(TAG, "audio effects lost (audio server restarted?) — rebuilding")
                    dp?.let { runCatching { it.release() } }; dp = null
                    wide?.let { runCatching { it.release() } }; wide = null
                    apply()
                }
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    private fun onStatus(fx: DynamicsProcessing): String {
        val wide = when {
            AudioFx.installed && width > 1.01f -> " · stereo width ${"%.1f".format(width)}×"
            AudioFx.installed -> " · no widening"
            installing -> " · adding the stereo widener…"
            installFailed -> " · stereo widener unavailable"
            else -> ""
        }
        return if (runCatching { fx.hasControl() }.getOrDefault(false)) "On — internal speakers$wide"
        else "On (another app has priority on the audio effect)"
    }

    private fun freqAt(t: Float): Float = exp(ln(25f) + t * (ln(20000f) - ln(25f)))

    /** The curve, interpolated in log-frequency; flat beyond its ends. */
    fun gainAt(f: Float): Float {
        if (f <= CURVE.first().first) return CURVE.first().second
        if (f >= CURVE.last().first) return CURVE.last().second
        val i = CURVE.indexOfFirst { it.first >= f }
        val (f0, g0) = CURVE[i - 1]; val (f1, g1) = CURVE[i]
        val t = (ln(f) - ln(f0.toFloat())) / (ln(f1.toFloat()) - ln(f0.toFloat()))
        return g0 + t * (g1 - g0)
    }

    // ── the external DSP app (its own public automation intent) ──────────
    private val DSP_PACKAGES = listOf("james.dsp", "me.timschneeberger.rootlessjamesdsp")

    fun externalDspInstalled(): String? = DSP_PACKAGES.firstOrNull {
        runCatching { app.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
    }

    private fun externalDsp(on: Boolean) {
        val p = externalDspInstalled() ?: return
        app.sendBroadcast(Intent("me.timschneeberger.rootlessjamesdsp.SET_POWER_STATE").setPackage(p)
            .putExtra("rootlessjamesdsp.enabled", on))
        Log.i(TAG, "external DSP ${if (on) "back on" else "off"} ($p)")
    }
}
