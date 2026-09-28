package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * App side of the input layer. Ships `wfpad` (fx/wfpad.c, built by
 * tools/build_wfpad.sh) from the assets to the app's files, asks the root helper's [PadLayer]
 * to run it, and tells it the profile of the app that has the controller.
 *
 * On by default (plan §9, decision 1) with a master switch; holding Home + Back 5 s turns it
 * off (and the switch with it) — handled inside wfpad, so it works even if the app hangs.
 */
object PadLayerCtl {
    private const val TAG = "ThorPadLayer"
    private const val KEY = "input_layer"
    private var prefs: SharedPreferences? = null

    @Volatile var wanted = true
        private set
    /** The layer is running: our copy IS the pad. Shortcuts then read the events withheld from
     *  the game ([InputMonitor.gatedListener]) and nothing needs the [AnalogShield]. */
    @Volatile var active = false
        private set
    @Volatile var lastStatus: String = "off"
    /** The copy's controller number from the last "on …" status (0 = unknown). */
    @Volatile var controllerNumber: Int = 0
        private set
    /** Called (any thread) when Home + Back turned the layer off. */
    @Volatile var onEmergencyOff: (() -> Unit)? = null
    /** The helper gave up on the layer by itself (it keeps failing): tell the user, don't just
     *  leave them without the layer's features. It retries at the next helper start. */
    @Volatile var onLayerFailed: ((String) -> Unit)? = null
    /** Why the layer last gave up ("" = it didn't), for Help & status (GitHub #39). */
    @Volatile var failReason: String = ""
    @Volatile private var profile = ""
    /** The face-button layout forced for the current app: 'n' (as printed), 'x' (Xbox), or
     *  null = AYN's own setting. Keys from the copy are read through it ([ButtonEngine]). */
    @Volatile var layoutOverride: Char? = null
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        wanted = prefs!!.getBoolean(KEY, true)
    }

    private fun binary(ctx: Context): String? = runCatching {
        val f = File(ctx.filesDir, "wfpad")
        val bytes = ctx.assets.open("fx/wfpad").use { it.readBytes() }
        if (!f.exists() || !f.readBytes().contentEquals(bytes)) f.writeBytes(bytes)
        // Owner only (0700): the helper runs it as root and checks nobody else can write it.
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
        f.absolutePath
    }.onFailure { Log.w(TAG, "binary: $it") }.getOrNull()

    fun set(ctx: Context, on: Boolean) {
        init(ctx)
        wanted = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
        apply(ctx)
    }

    /** (Re)send the wanted state — also called when the helper (re)connects. */
    fun apply(ctx: Context) {
        init(ctx)
        if (wanted) binary(ctx)?.let {
            InputMonitor.send("G on")   // the helper finds the binary itself
            InputMonitor.send("G map $profile")
            GyroEngine.resync()
        } else InputMonitor.send("G off")
    }

    /** The next [setProfile] is sent even if unchanged (1.3). */
    @Volatile private var forceNext = false
    fun forgetProfile() { forceNext = true }

    /** The profile of the app that has the controller (tokens of fx/wfmap.h `wf_parse`;
     *  "" = no change to the pad). Sent only when it changes. */
    fun setProfile(spec: String): Boolean {
        if (spec == profile && !forceNext) return false
        forceNext = false
        profile = spec
        layoutOverride = Regex("(^| )L=([nx])").find(spec)?.groupValues?.get(2)?.single()
        if (wanted) InputMonitor.send("G map $spec")
        if (BuildConfig.DEBUG) Log.d(TAG, "profile: $spec")
        return true
    }

    fun onStatus(line: String) {
        lastStatus = line
        Log.i(TAG, line)
        when {
            line.startsWith("on ") -> {
                active = true; failReason = ""
                controllerNumber = Regex("controller #(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            line == "emergency-off" -> {
                active = false; wanted = false
                prefs?.edit()?.putBoolean(KEY, false)?.apply()
                onEmergencyOff?.invoke()
            }
            line.startsWith("error") && (line.endsWith("layer off") || line.contains("not-trusted")) -> {
                active = false
                failReason = line.removePrefix("error ").removeSuffix(" — layer off")
                onLayerFailed?.invoke(failReason)
            }
            line == "off" || line.startsWith("stopped") || line.startsWith("error") -> active = false
        }
    }
}
