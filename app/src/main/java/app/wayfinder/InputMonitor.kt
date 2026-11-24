package app.wayfinder

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.util.Log

/**
 * App-side receiver for [InputMonitorTool]. Hosts an abstract LocalServerSocket,
 * launches the root helper (via pservice), and receives raw evdev events. Tracks
 * last-event time per device (for touch-idle) and forwards events to a
 * listener (for gestures / controller chords in the InputEngine).
 *
 * Device index order = [ThorInput.DEVICE_MATCHERS] (resolved + re-resolved by the helper):
 *   0 bottom touch · 1 top touch · 2 controller (any layout) · 3 gpio-keys (AYN, Vol+) · 4 pmic_resin (Vol−)
 */
object InputMonitor {

    private const val TAG = "ThorInput"
    private const val SOCK = "app.wayfinder.input"
    /** The root helper's own stdout/stderr: kept only in debug builds (and never in a shared
     *  place like /data/local/tmp, where root would follow a planted symlink). */
    private val HELPER_LOG get() = if (BuildConfig.DEBUG) "/data/local/tmp/thor_input_helper.log" else "/dev/null"
    const val DEV_BOTTOM_TOUCH = ThorInput.DEV_BOTTOM_TOUCH
    const val DEV_TOP_TOUCH = ThorInput.DEV_TOP_TOUCH
    const val DEV_CONTROLLER = ThorInput.DEV_CONTROLLER

    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var clientConnected = false
    /** The root helper is connected now. */
    val connected get() = clientConnected
    @Volatile private var lastLaunch = 0L
    private val lastEvent = LongArray(8)

    /** (idx, type, code, value) for every non-SYN event; set by the InputEngine. */
    @Volatile var listener: ((Int, Int, Int, Int) -> Unit)? = null

    fun lastEventUptime(idx: Int): Long = lastEvent.getOrElse(idx) { 0L }
    fun isRunning(): Boolean = running

    fun start(context: Context) {
        Log.d(TAG, "start() called; running=$running clientConnected=$clientConnected")
        appCtx = context.applicationContext
        ensureServer()
        maybeLaunchHelper(context) // self-heals if the helper died; single-flighted
    }

    /**
     * Launch the helper only if we don't already have one streaming, and coalesce
     * rapid double-starts. start() fires from BOTH onServiceConnected and onResume,
     * ~ms apart; without this guard the two launch threads race and one thread's
     * root `pkill` kills the other thread's just-spawned helper, leaving none.
     */
    private fun maybeLaunchHelper(context: Context) {
        synchronized(this) {
            if (clientConnected) { Log.d(TAG, "helper already connected — skip launch"); return }
            val now = SystemClock.uptimeMillis()
            if (now - lastLaunch < 3000) { Log.d(TAG, "launch debounced"); return }
            lastLaunch = now
        }
        launchHelper(context)
    }

    private fun ensureServer() {
        if (running) return
        // Bind SYNCHRONOUSLY before returning so the helper (launched right after)
        // can't race ahead of the socket being ready.
        val ss = try { LocalServerSocket(SOCK) } catch (e: Exception) {
            Log.w(TAG, "server socket failed: ${e.message}"); return
        }
        server = ss
        running = true
        Thread {
            while (running) {
                try { readClient(ss.accept()) }
                catch (e: Exception) { if (running) Log.w(TAG, "accept: ${e.message}") }
            }
            try { ss.close() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stop(context: Context) {
        running = false
        try { server?.close() } catch (_: Exception) {}  // release the abstract socket name now
        server = null
        try { PServiceBridge.exec("pkill -f app.wayfinder.InputMonitorTool") } catch (_: Exception) {}
    }

    @Volatile private var clientOut: java.io.OutputStream? = null
    /** Frames presented in the last second by the watched app (see [FpsSampler]): its busiest layer's, then the kept surface's
     *  (b18j, D1: the game's own surface while Game controls watches it; the busiest's when nothing is kept). */
    @Volatile var fpsListener: ((top: Int, bottom: Int, keptTop: Int, keptBottom: Int) -> Unit)? = null
    /** Input layer: (type, code, value) of pad events the GAME did not get because Home or Back
     *  was held — Wayfinder's shortcuts read them here. Keys come as PRINTED buttons. */
    /** (type, code, value, source): source 0 = the Thor, n = an external controller (ExtPads, its hidraw number + 1). */
    @Volatile var gatedListener: ((Int, Int, Int, Int) -> Unit)? = null
    /** b18o: (printed code, 1 = held / 0 = let go, when — CLOCK_MONOTONIC ms, the key events' clock, source) of a turbo
     *  button whose job is that button of the copy / a controller's pad ("TU" lines: wfpad, wfhid tagged by ExtPads); code 0
     *  = every one let go (the input layer's engine gone). */
    @Volatile var turboListener: ((Int, Int, Long, Int) -> Unit)? = null
    /** Input layer: (printed evdev code, 1 = down / 0 = up) of a button the current app's remap
     *  sends to Wayfinder — a keyboard key or a Wayfinder action ([PadRemap]). */
    @Volatile var extListener: ((Int, Int) -> Unit)? = null
    /** 1.3: a stick that is Wayfinder's (side 0 left / 1 right, x y −1000..1000). */
    @Volatile var stickListener: ((Int, Int, Int) -> Unit)? = null
    /** Stick lights: screen colour from the helper's [AmbientSampler] — (whole screen, left half, right half). */
    @Volatile var ambientListener: ((Int, Int, Int) -> Unit)? = null
    /** A (new) helper is connected: it knows nothing yet — re-send what it should be doing
     *  (FPS watch, screen-colour sampling). Without this, a counter switched on while the
     *  helper was restarting stayed on "idle" for good. */
    @Volatile var onHelperConnected: (() -> Unit)? = null

    /**
     * Send one command line to the root helper (input deck: keys, virtual mouse — see
     * RootInjector). False if no helper is connected (it is then (re)started).
     */
    fun send(line: String): Boolean {
        // One command per line: a value carrying a newline would inject a second command.
        if (line.any { it == '\n' || it == '\r' }) { Log.w(TAG, "send: refused a line with a line break"); return false }
        val out = clientOut ?: run { Log.w(TAG, "send ${line.substringBefore(' ')}: no helper connected"); return false }
        return try {
            synchronized(this) { out.write((line + '\n').toByteArray()); out.flush() }
            true
        } catch (e: Exception) { Log.w(TAG, "send: ${e.message}"); false }
    }

    private fun readClient(client: LocalSocket) {
        // Only OUR root helper (uid 0) may talk on this channel. Abstract sockets have no
        // file permissions: without this check any app could connect, inject fake
        // controller/touch events, or sit here while the helper is down and receive what
        // the user types on Wayfinder's keyboard.
        val uid = runCatching { client.peerCredentials.uid }.getOrDefault(-1)
        if (uid != 0) {
            Log.w(TAG, "refused a connection from uid $uid (not the root helper)")
            try { client.close() } catch (_: Exception) {}
            return
        }
        var count = 0
        clientConnected = true
        clientOut = client.outputStream
        Log.d(TAG, "client connected")
        // The handshake: a helper waits for this before touching anything. A second helper
        // (launched while the first was slow to connect) sits in the accept backlog and never
        // gets it — it used to run its startup anyway and kill the live input layer (review).
        send("H")
        onHelperConnected?.invoke()
        try {
            val br = client.inputStream.bufferedReader()
            while (running) {
                val line = br.readLine() ?: break
                val p = line.split(' ')
                if (p[0] == "C" && (p.size == 4 || p.size == 10)) {
                    val v = p.drop(1).map { it.toIntOrNull()?.coerceIn(0, 255) ?: 0 }
                    fun c(i: Int) = android.graphics.Color.rgb(v[i], v[i + 1], v[i + 2])
                    val avg = c(0)
                    ambientListener?.invoke(avg, if (v.size >= 9) c(3) else avg, if (v.size >= 9) c(6) else avg)
                    continue
                }
                if (p[0] == "P") { PadLayerCtl.onStatus(line.removePrefix("P ")); continue }   // input layer status
                if (p[0] == "Z") { ExtPadsCtl.onLine(line); continue }                       // external controllers
                if (p[0] == "B" && p.size == 3) {   // a screen's brightness (answer to "Q")
                    val d = p[1].toIntOrNull(); val v = p[2].toFloatOrNull()
                    if (d != null && v != null) ScreenLevels.onHelperLevel(d, v)
                    continue
                }
                if (p[0] == "X" && p.size == 3) {   // input layer: a button mapped to a keyboard key / action
                    val c = p[1].toIntOrNull(); val v = p[2].toIntOrNull()
                    if (c != null && v != null) extListener?.invoke(c, v)
                    continue
                }
                if (p[0] == "T" && p.size == 4) {   // input layer: a stick that is Wayfinder's (mouse / scroll / keys)
                    val sd = p[1].toIntOrNull(); val x = p[2].toIntOrNull(); val y = p[3].toIntOrNull()
                    if (sd != null && x != null && y != null) stickListener?.invoke(sd, x, y)
                    continue
                }
                if (p[0] == "D" && p.size >= 2) {   // which game an app runs: D <pkg> [<game-id> <title…>]
                    val id = p.getOrNull(2)?.takeIf { Regex("^[a-z0-9-]{1,64}$").matches(it) }
                    GameProfiles.onDetected(p[1], id, if (id != null) p.drop(3).joinToString(" ").filter { it >= ' ' }.take(120).ifEmpty { null } else null)
                    continue
                }
                if (p[0] == "V" && p.size == 4) {   // input layer: a watched control (gyro activation)
                    val t = p[1].toIntOrNull(); val c = p[2].toIntOrNull(); val v = p[3].toIntOrNull()
                    if (t != null && c != null && v != null) GyroEngine.onWatched(t, c, v)
                    continue
                }
                if (p[0] == "TU" && (p.size == 4 || p.size == 5)) {  // b18o: a turbo button the hand holds / let go
                    val c = p[1].toIntOrNull(); val v = p[2].toIntOrNull(); val at = p[3].toLongOrNull()
                    val src = p.getOrNull(4)?.toIntOrNull()?.takeIf { it in 1..1000 } ?: 0
                    if (c != null && v != null && at != null) turboListener?.invoke(c, v, at, src)
                    continue
                }
                if (p[0] == "J" && (p.size == 4 || p.size == 5)) {   // an event withheld from the game (Home/Back/shift held)
                    val t = p[1].toIntOrNull(); val c = p[2].toIntOrNull(); val v = p[3].toIntOrNull()
                    val src = p.getOrNull(4)?.toIntOrNull()?.takeIf { it in 1..1000 } ?: 0
                    if (t != null && c != null && v != null) gatedListener?.invoke(t, c, v, src)
                    continue
                }
                // F <top> [<bottom>] [<kept top> <kept bottom>]  (-1 = not watched). b18j (D1): the kept counts follow one surface
                // (FpsSampler); a helper that sends none (an older one) gives the busiest layer's, as before
                if (p[0] == "F" && p.size in 2..5) {
                    val t = p[1].toIntOrNull() ?: -1; val b = p.getOrNull(2)?.toIntOrNull() ?: -1
                    fpsListener?.invoke(t, b, p.getOrNull(3)?.toIntOrNull() ?: t, p.getOrNull(4)?.toIntOrNull() ?: b); continue
                }
                if (p.size < 4) continue
                val idx = p[0].toIntOrNull() ?: continue
                val type = p[1].toIntOrNull() ?: continue
                val code = p[2].toIntOrNull() ?: continue
                val value = p[3].toIntOrNull() ?: continue
                // EV_SYN doesn't count as activity, but IS forwarded: SYN_REPORT marks
                // a complete, consistent frame — gesture logic samples positions there.
                if (type != 0 && idx in lastEvent.indices) lastEvent[idx] = SystemClock.uptimeMillis()
                listener?.invoke(idx, type, code, value)
                if (BuildConfig.DEBUG && (++count <= 5 || count % 100 == 0)) Log.d(TAG, "event #$count: $line")
            }
        } catch (e: Exception) { Log.w(TAG, "read: ${e.message}") }
        finally { clientConnected = false; clientOut = null; try { client.close() } catch (_: Exception) {} }
        Log.d(TAG, "client ended after $count events")
        PadLayerCtl.onStatus("stopped (helper gone)")
        ExtPadsCtl.helperLost()             // its controllers' readers went with it (the next helper tells the live ones)
        relaunchSoon()
    }

    @Volatile private var appCtx: Context? = null
    private val relaunches = ArrayDeque<Long>()

    /** 1.5-beta2 (GitHub #145): when the root helper is tried again. It used to give up for good after 5 relaunches in a
     *  minute ("helper keeps dying"), and a launch that never connected was never tried again: no Home combos, no cursor, no
     *  Game controls (AYN's pad left set aside) until Wayfinder was opened or turned off and on. Now: soon while it went fewer
     *  than [QUICK_TRIES] times in the last [WINDOW_MS], then once every [LATER_MS] while it keeps failing. Pure (HelperRetryTest). */
    internal object HelperRetry {
        const val SOON_MS = 3_200L          // past the launch debounce; a helper that reconnected meanwhile wins
        const val LATER_MS = 60_000L
        const val WINDOW_MS = 600_000L
        const val QUICK_TRIES = 5
        /** No helper connected this long after a launch: that launch failed (pservice dropped it, app_process never came up). */
        const val CONNECT_WAIT_MS = 20_000L
        /** The tries of [recent] (uptime ms, oldest first) still inside the window at [now]. */
        fun keep(recent: List<Long>, now: Long): List<Long> = recent.filter { now - it <= WINDOW_MS }
        /** The wait before the next try, given the [recent] tries and [now]. Never "no more tries". */
        fun wait(recent: List<Long>, now: Long): Long = if (keep(recent, now).size < QUICK_TRIES) SOON_MS else LATER_MS
        /** 1.5-beta2 (GitHub #145, review R6): the once-a-minute tries go on only while Wayfinder's service runs ([serviceUp]);
         *  with it turned off, a cached Wayfinder kept starting a root helper nobody listened to, once a minute for good. The
         *  quick tries always go (the Hub open with the service off still gets its helper back). */
        fun goOn(wait: Long, serviceUp: Boolean): Boolean = wait <= SOON_MS || serviceUp
        /** 1.5-beta2 (GitHub #145, review R1): no pservice at the launch. Just after a boot it may not be up yet ("not up yet"
         *  in PServiceBridge): tried again every [NO_ROOT_WAIT_MS], [NO_ROOT_TRIES] times in a row (about 3 minutes), then left
         *  to the next start (a ROM without AYN's root, LineageOS, never gets one). */
        const val NO_ROOT_TRIES = 12
        const val NO_ROOT_WAIT_MS = 15_000L     // past PServiceBridge's 10 s between failed probes
        /** [tries]: the launches in a row that found no pservice, this one counted. True = try again later. */
        fun noRootAgain(tries: Int): Boolean = tries in 1 until NO_ROOT_TRIES
    }

    /** The helper went away (killed, crashed) while we still want it: bring it back without
     *  waiting for the app to be reopened — combos, gestures and the keyboard depend on it.
     *  1.5-beta2 (GitHub #145): soon at first, then once a minute ([HelperRetry]), never given up. */
    private fun relaunchSoon() {
        val ctx = appCtx ?: return
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val wait = synchronized(relaunches) {
            val kept = HelperRetry.keep(relaunches, now)
            relaunches.clear(); relaunches.addAll(kept)
            HelperRetry.wait(relaunches, now).also { relaunches.addLast(now) }
        }
        if (wait > HelperRetry.SOON_MS) Log.w(TAG, "helper keeps dying: trying again in ${wait / 1000} s")
        Thread {
            Thread.sleep(wait)
            if (running && !clientConnected && !HelperRetry.goOn(wait, ForegroundAppService.isRunning))
                Log.w(TAG, "helper: Wayfinder's service is off, no more tries until it starts")
            else if (running && !clientConnected) maybeLaunchHelper(ctx)
        }.apply { isDaemon = true }.start()
    }

    /** 1.5-beta2 (GitHub #145): the launches asked for; a connect check only counts for the last one. Review R2: counted
     *  when the launch starts (before pservice's call, which can take seconds), atomically (two launch threads at once). */
    private val launchGen = java.util.concurrent.atomic.AtomicInteger()
    /** 1.5-beta2 (GitHub #145, review R1): launches in a row that found no pservice ([HelperRetry.noRootAgain]). */
    private val noRootTries = java.util.concurrent.atomic.AtomicInteger()

    private fun launchHelper(context: Context) {
        val apk = context.applicationInfo.sourceDir
        // Runs off the main thread: pservice transacts + the launch keeper below
        // (`sleep 1`) block for ~1s, and start() is called from onResume/onServiceConnected.
        Thread {
            val gen = launchGen.incrementAndGet()
            if (!PServiceBridge.isAvailable()) {
                // 1.5-beta2 (GitHub #145, review R1): right after a boot pservice may not be up yet: tried again a few times
                val tries = noRootTries.incrementAndGet()
                Log.w(TAG, "no pservice → no input monitor (try $tries)")
                if (HelperRetry.noRootAgain(tries)) Thread {
                    Thread.sleep(HelperRetry.NO_ROOT_WAIT_MS)
                    if (running && !clientConnected) maybeLaunchHelper(context)
                }.apply { isDaemon = true }.start()
                return@Thread
            }
            noRootTries.set(0)
            // NOTE: we deliberately do NOT pkill old helpers. `pkill -f
            // ...InputMonitorTool` self-matches: the pservice shell running it has the
            // class name in its OWN cmdline, so it kills its own shell mid-run and
            // intermittently leaves the pservice binder in a bad state that silently
            // drops the very next transact (the launch). It is also unnecessary — the
            // helper self-terminates (Runtime.halt) the moment its socket dies, and
            // maybeLaunchHelper() single-flights + debounces so we never spawn a second
            // live helper alongside a connected one.
            // Backgrounded launch, with a trailing `sleep 1` that is ESSENTIAL: pservice
            // runs this via popen and reaps (pclose) the launcher shell as soon as it
            // returns. With a BARE `&` the shell exits instantly and the just-forked
            // app_process is torn down before it detaches — the helper never comes up.
            // The `sleep 1` keeps the launcher shell (and the child's process group)
            // alive long enough for app_process to fully spawn and reparent to init,
            // after which it survives pclose. (setsid breaks app_process; don't use it.)
            // Unquoted CLASSPATH — the apk path has no spaces, and single-quoting it
            // stopped app_process from starting. Output → a diagnostic logfile.
            // We run the launch from a SCRIPT FILE, not as an inline string. pservice
            // does not reliably interpret inline shell operators (redirects, `&`,
            // trailing `sleep`) — an inline "CLASSPATH=… app_process … & sleep 1"
            // returns instantly having done nothing. But `sh <file>` (a plain two-token
            // command) it runs faithfully, and the script's operators work normally.
            // Devices are resolved by the helper itself from matchers, and re-resolved
            // whenever one disappears (AYN re-creates the controller on layout switches).
            val script =
                "CLASSPATH=$apk app_process /system/bin " +
                "app.wayfinder.InputMonitorTool $SOCK uid:${android.os.Process.myUid()} " +
                "${ThorInput.DEVICE_MATCHERS.joinToString(" ")} " +
                "</dev/null >$HELPER_LOG 2>&1 &\n" +
                "sleep 1\n"  // keep the launcher shell alive so app_process detaches (survives pclose)
            val scriptFile = java.io.File(context.cacheDir, "thor_input_launch.sh")
            val ok = try {
                scriptFile.writeText(script)
                PServiceBridge.exec("sh ${scriptFile.absolutePath}") != null
            } catch (e: Exception) {
                Log.w(TAG, "helper launch failed: ${e.message}"); false
            }
            Log.d(TAG, "helper launch requested (ok=$ok)")
            // 1.5-beta2 (GitHub #145): a launch that never connects is a death too (pservice can drop a call silently, see
            // above): tried again as one ([relaunchSoon]). Only the last launch's check counts (gen: taken at the start)
            Thread.sleep(HelperRetry.CONNECT_WAIT_MS)
            if (gen == launchGen.get() && running && !clientConnected) {
                Log.w(TAG, "helper launch: no helper connected after ${HelperRetry.CONNECT_WAIT_MS / 1000} s")
                relaunchSoon()
            }
        }.apply { isDaemon = true }.start()
    }
}
