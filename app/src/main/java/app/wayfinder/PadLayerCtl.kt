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
 * On by default with a master switch; holding Home + Back 5 s turns it
 * off (and the switch with it) — handled inside wfpad, so it works even if the app hangs.
 */
object PadLayerCtl {
    private const val TAG = "ThorPadLayer"
    private const val KEY = "input_layer"
    private var prefs: SharedPreferences? = null

    /** The layer should run NOW (the mode and the app with the controller decide — [follow]). */
    @Volatile var wanted = true
        private set

    /** 1.4 (2026-09-30): where the layer runs. */
    enum class Mode(val label: String) { EVERYWHERE("Everywhere"), SET_UP("Only games I set up"), NOWHERE("Nowhere") }
    @Volatile var mode = Mode.EVERYWHERE
        private set
    private const val MODE_KEY = "layer_mode"
    private const val BEFORE_OFF_KEY = "layer_mode_before_off"
    /** The layer is running: our copy IS the pad. Shortcuts then read the events withheld from
     *  the game ([InputMonitor.gatedListener]) and nothing needs the [AnalogShield]. */
    @Volatile var active = false
        private set
    @Volatile var lastStatus: String = "off"
    /** The copy's controller number from the last "on …" status (0 = unknown). */
    @Volatile var controllerNumber: Int = 0
    /** 1.4 (GitHub #39): the layer runs in compatibility mode (AYN's pad didn't restart; it keeps #1, hidden). */
    @Volatile var compatMode = false
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
        val p = ctx.applicationContext.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        prefs = p
        // 1.4: the old on / off switch becomes Everywhere / Nowhere; an update keeps Everywhere; a new install starts
        // with "Only games I set up" (the Thor's own controller everywhere else)
        mode = when {
            p.contains(MODE_KEY) -> runCatching { Mode.valueOf(p.getString(MODE_KEY, null)!!) }.getOrDefault(Mode.EVERYWHERE)
            p.contains(KEY) -> if (p.getBoolean(KEY, true)) Mode.EVERYWHERE else Mode.NOWHERE
            AppSettings.isUpdate(ctx) -> Mode.EVERYWHERE
            else -> Mode.SET_UP
        }
        p.edit().putString(MODE_KEY, mode.name).apply()
        wanted = mode == Mode.EVERYWHERE    // Only games I set up: off until such an app has the controller
    }

    fun setMode(ctx: Context, m: Mode) {
        init(ctx)
        // 1.4: turned off → remember what it was, for "Turn on" (the Features switch, Help & status, the tour)
        if (m == Mode.NOWHERE && mode != Mode.NOWHERE) prefs?.edit()?.putString(BEFORE_OFF_KEY, mode.name)?.apply()
        mode = m
        prefs?.edit()?.putString(MODE_KEY, m.name)?.apply()
        wanted = need(lastApp) ?: (m == Mode.EVERYWHERE)
        apply(ctx)
    }

    /** This app has Game controls of its own (buttons, gyro, macros, face buttons) — or one of its games has. */
    fun isSetUp(pkg: String): Boolean {
        fun r(x: PadRemap?) = x != null && !x.isEmpty
        val c = AppConfigStore.get(pkg)
        return c.face != null || r(c.remap) || GameProfiles.forApp(pkg).any { (_, g) -> g.face != null || r(g.remap) }
    }

    /** What [pkg] wants: true on, false off, null = it doesn't mind (the layer stays as it is). [neutral]: a launcher,
     *  Wayfinder itself or nothing — never flips "Only games I set up" (a hop through them on the way). */
    fun need(pkg: String?, neutral: Boolean = pkg == null, overlay: Boolean = false): Boolean? {
        pkg?.let { AppConfigStore.get(it).layer }?.let { return it }
        // Wayfinder's own pages (Game controls over a game), system overlays: never a reason to flip it (any mode)
        if (overlay) return null
        return when (mode) {
            Mode.EVERYWHERE -> true
            Mode.NOWHERE -> false
            // 1.4 (review): any other app turns it off again — the Thor's own controller everywhere else
            Mode.SET_UP -> if (pkg != null && isSetUp(pkg)) true else if (neutral) null else false
        }
    }

    /** The layer runs for [pkg] (for the pages: "these changes do nothing"). */
    fun runsFor(pkg: String?): Boolean = need(pkg) ?: wanted

    @Volatile private var lastApp: String? = null
    @Volatile private var candidate: Boolean? = null
    @Volatile private var candidateSince = 0L

    /** The app with the controller now (the service's tick, twice a second): the layer starts / stops for it once
     *  it has stayed 1.5 s — a swap, Recents or a launcher on the way never flips it. Each flip changes the
     *  controller for a moment (on ≈ 0.6 s, off ≈ 1.6 s, measured 2026-09-30). */
    fun follow(ctx: Context, pkg: String?, neutral: Boolean = pkg == null, overlay: Boolean = false) {
        if (pkg != null && !neutral && !overlay) lastApp = pkg
        val want = need(pkg, neutral, overlay)
        if (want == null || want == wanted) { candidate = null; return }
        val now = android.os.SystemClock.uptimeMillis()
        if (candidate != want) { candidate = want; candidateSince = now; return }
        if (now - candidateSince < 1500) return
        candidate = null
        wanted = want
        Log.w(TAG, "input layer ${if (want) "on" else "off"} (${mode.name})")   // no app names in the log
        apply(ctx)
    }

    /** Right away for [pkg] (its layer setting or its Game controls just changed on a page) — if it's the app
     *  the controller was last on (Game controls over the game); any other app waits until it's in front. */
    fun nowFor(ctx: Context, pkg: String) {
        if (pkg != lastApp) return
        val want = need(pkg) ?: return
        if (want != wanted) { wanted = want; candidate = null; apply(ctx) }
    }

    /** Per app: true on, false off, null automatic — applied at once. */
    fun setForApp(ctx: Context, pkg: String, on: Boolean?) {
        AppConfigStore.update(pkg) { it.copy(layer = on) }
        nowFor(ctx, pkg)
    }

    /** The binary checked in this process (it only changes with an app update = a new process). */
    @Volatile private var binaryChecked: String? = null
    private fun binary(ctx: Context): String? = binaryChecked?.takeIf { File(it).canExecute() } ?: binaryCheck(ctx)?.also { binaryChecked = it }
    private fun binaryCheck(ctx: Context): String? = runCatching {
        val f = File(ctx.filesDir, "wfpad")
        val bytes = ctx.assets.open("fx/wfpad").use { it.readBytes() }
        if (!f.exists() || !f.readBytes().contentEquals(bytes)) f.writeBytes(bytes)
        // Owner only (0700): the helper runs it as root and checks nobody else can write it.
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
        // 1.3.2 (GitHub #39): make sure it really is 0700 (the helper refuses anything else)
        runCatching {
            val mode = android.system.Os.stat(f.absolutePath).st_mode and "7777".toInt(8)
            if (mode != "700".toInt(8)) { Log.w(TAG, "wfpad mode ${Integer.toOctalString(mode)} → 0700"); android.system.Os.chmod(f.absolutePath, "700".toInt(8)) }
        }
        f.absolutePath
    }.onFailure { Log.w(TAG, "binary: $it") }.getOrNull()

    /** The old master switch: on = Everywhere, off = Nowhere. */
    fun set(ctx: Context, on: Boolean) {
        // 1.4: "Turn on" / "try again" keeps the chosen mode (Only games I set up stays); from Nowhere → Everywhere
        init(ctx)
        if (on && mode != Mode.NOWHERE) { apply(ctx); return }
        val before = prefs?.getString(BEFORE_OFF_KEY, null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        setMode(ctx, if (on) (before?.takeIf { it != Mode.NOWHERE } ?: Mode.EVERYWHERE) else Mode.NOWHERE)
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
                compatMode = line.contains("compatibility mode")
            }
            line == "emergency-off" -> {
                if (mode != Mode.NOWHERE) prefs?.edit()?.putString(BEFORE_OFF_KEY, mode.name)?.apply()
                active = false; wanted = false; mode = Mode.NOWHERE
                prefs?.edit()?.putString(MODE_KEY, Mode.NOWHERE.name)?.apply()
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
