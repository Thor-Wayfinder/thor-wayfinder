package app.wayfinder

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.FileInputStream

/**
 * Persistent root helper (app_process, uid 0) that reads raw /dev/input evdev
 * devices and streams events to the app over an abstract LocalSocket. This is
 * the ONLY way to see the D-pad/stick (MotionEvents), multi-finger touch, and
 * per-screen touch globally — the accessibility service can't. Powers global
 * controller chords, touch gestures, and per-screen touch-idle.
 *
 * Launched (backgrounded) by [InputMonitor] via pservice:
 *   CLASSPATH=<apk> app_process /system/bin app.wayfinder.InputMonitorTool \
 *       <socketName> uid:<appUid> <matcher0> <matcher1> ...   (exact device name, or "@controller")
 * Both ends check the other's uid (SO_PEERCRED): the app accepts only uid 0, the helper
 * only the app's uid.
 * Each line sent: "<devIndex> <type> <code> <value>\n". Exits when the socket dies.
 */
object InputMonitorTool {

    @JvmStatic
    fun main(args: Array<String>) {
        val sockName = args.getOrNull(0) ?: return
        // "uid:<n>" = the Wayfinder app's uid: the only server we'll talk to.
        val appUid = args.getOrNull(1)?.takeIf { it.startsWith("uid:") }?.removePrefix("uid:")?.toIntOrNull()
        if (appUid == null) { Log.w("ThorInputTool", "no app uid given → refusing to start"); return }
        val devices = args.drop(2)
        Log.i("ThorInputTool", "start; connecting to @$sockName (uid $appUid); devices=$devices")
        var sock = LocalSocket()
        var ok = false
        for (attempt in 0 until 6) {
            try {
                sock.connect(LocalSocketAddress(sockName, LocalSocketAddress.Namespace.ABSTRACT))
                // Abstract names are first-come: another app could have claimed ours to
                // receive the (root) event stream and feed us commands. Only the app's uid.
                val peer = sock.peerCredentials.uid
                if (peer == appUid) { ok = true; break }
                Log.w("ThorInputTool", "@$sockName is owned by uid $peer, not the app ($appUid) → not talking to it")
                runCatching { sock.close() }
                sock = LocalSocket()
                Thread.sleep(200)
            } catch (e: Exception) { runCatching { sock.close() }; sock = LocalSocket(); Thread.sleep(200) }
        }
        if (!ok) { Log.w("ThorInputTool", "connect failed after retries"); return }
        Log.i("ThorInputTool", "connected")
        val out = sock.outputStream
        val lock = Any()
        // Wait for the app to ACCEPT us (its "H"): connecting only means we're in its backlog —
        // a second helper would otherwise clean up (kill the live copy of the controller, show
        // AYN's pad) and then flood the app with stale events once accepted (review 2026-09-25).
        val br = sock.inputStream.bufferedReader()
        while (true) {
            val first = runCatching { br.readLine() }.getOrNull() ?: run { Log.i("ThorInputTool", "never accepted → exit"); Runtime.getRuntime().halt(0); return }
            if (first == "H") break
        }

        // FPS counter replies ride the same stream as the input events.
        FpsSampler.send = { l -> try { synchronized(lock) { out.write(l.toByteArray()); out.flush() } } catch (_: Exception) {} }
        AmbientSampler.send = FpsSampler.send
        // Input layer (phase 0): a previous helper may have died with AYN's pad disabled, its
        // layout switched, or at controller #2 — PadLayer.startup() puts all that back.
        PadLayer.send = FpsSampler.send
        PadLayer.appUid = appUid
        PadLayer.onCloneChanged = { streams.forEach { (i, s) -> if (devices.getOrNull(i) == "@controller") runCatching { s.close() } } }
        runCatching { PadLayer.startup() }

        // Started only AFTER PadLayer.startup(): an app "G on" handled while startup was still
        // cleaning up had its fresh copy killed / its marker "restored" / AYN's pad re-shown.
        // The app → helper direction: one command per line for the input deck (keys and
        // the virtual mouse, see RootInjector). It doubles as the watchdog: die the moment
        // the app side goes away. Reader threads only notice a dead socket when they next
        // WRITE, and a reader on a quiet device (e.g. the controller) can sit in read()
        // for hours — without this, every app restart left a zombie root helper.
        Thread {
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    try { RootInjector.handle(line) } catch (e: Exception) { Log.w("ThorInputTool", "cmd '${line.substringBefore(' ')}': $e") }
                }
            } catch (_: Exception) {}
            Log.i("ThorInputTool", "app socket closed → exit")
            RootInjector.release()
            runCatching { PadLayer.stopForExit() }
            // Wayfinder UNINSTALLED (not just restarting): its data folder disappears a moment after
            // its process is killed. Nothing of it may stay behind: the copy of the controller goes,
            // AYN's pad gets controller #1 back, AYN's top-screen lock (Wayfinder's lock) is released.
            val data = java.io.File("/data/data/${BuildConfig.APPLICATION_ID}")
            for (i in 0 until 12) {
                if (!data.exists()) { runCatching { PadLayer.onAppUninstalled() }; break }
                Thread.sleep(500)
            }
            Runtime.getRuntime().halt(0)
        }.apply { isDaemon = true; start() }

        // One reader per slot. A slot is a device MATCHER, not a path: the helper finds
        // the device itself and re-finds it whenever it disappears — AYN re-creates the
        // controller (e.g. "Odin Controller" → "Xbox Wireless Controller") when it
        // switches layout for a game, and a plain path/fd would silently go dead.
        val threads = devices.mapIndexed { idx, matcher ->
            Thread {
                // 64-bit input_event: timeval(16) + type(2)+code(2)+value(4). evdev reads return whole
                // events: read up to 64 at once and send them in ONE write (it was a syscall + a
                // flush per event — thousands a second while playing).
                val buf = ByteArray(24 * 64)
                val sb = StringBuilder()
                // Only what the app reads (ForegroundAppService.installGestureListener): top screen =
                // fingers down / up; bottom = fingers + position; controller = everything, except the
                // sticks / triggers while the input layer runs (the app doesn't use them then — one
                // every 250 ms still counts as activity for the idle timer).
                val top = matcher == "fts_ts"; val bottom = matcher == "fts_ts_3"; val pad = matcher == "@controller"
                var stickAt = 0L
                while (true) {
                    val path = resolve(matcher)
                    if (path == null) { Thread.sleep(1000); continue }
                    try {
                        FileInputStream(path).use { fis ->
                            streams[idx] = fis
                            Log.i("ThorInputTool", "opened [$idx] $matcher → $path")
                            while (true) {
                                val r = fis.read(buf, 0, buf.size)
                                if (r <= 0) throw java.io.IOException("device gone")
                                sb.setLength(0)
                                for (o in 0 until r - 23 step 24) {
                                    val type = u16(buf, o + 16); val code = u16(buf, o + 18); val value = s32(buf, o + 20)
                                    if (type == 3) {
                                        if (top && code != 47 && code != 57) continue
                                        if (bottom && code != 47 && code != 53 && code != 54 && code != 57) continue
                                        if (pad && PadLayer.running && (code <= 5 || code == 9 || code == 10)) {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (now - stickAt < 250) continue
                                            stickAt = now
                                        }
                                    } else if (type == 1 && top) continue      // BTN_TOUCH: the app uses the slots
                                    sb.append(idx).append(' ').append(type).append(' ').append(code).append(' ').append(value).append('\n')
                                }
                                if (sb.isEmpty()) continue
                                val line = sb.toString().toByteArray()
                                try { synchronized(lock) { out.write(line); out.flush() } }
                                catch (e: Exception) {
                                    // the watchdog above does the exit, AFTER its cleanup (the copy
                                    // quit, AYN's pad back, the lock released at uninstall): halting
                                    // here cut that short (review 2026-09-25). Last resort: 20 s.
                                    Log.i("ThorInputTool", "write failed (${e.message}) → leaving the exit to the watchdog")
                                    Thread.sleep(20_000)
                                    Runtime.getRuntime().halt(0)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ThorInputTool", "[$idx] $path lost (${e.message}) — re-resolving")
                        Thread.sleep(300)
                    }
                }
            }.apply { isDaemon = false; start() }
        }
        // Readers never end on their own; the socket watchdog halts us when the app goes.
        threads.forEach { runCatching { it.join() } }
        runCatching { sock.close() }
        Runtime.getRuntime().halt(0)
    }

    /** The open stream of each reader slot (the input layer closes the controller's to make
     *  it re-resolve when the clone appears / goes). */
    private val streams = java.util.concurrent.ConcurrentHashMap<Int, FileInputStream>()

    /** Device matcher → /dev/input/eventN. "@controller" = the AYN gamepad in any layout —
     *  or, while the input layer runs, its clone (the original is grabbed and silent). */
    private fun resolve(matcher: String): String? {
        val nodes = java.io.File("/sys/class/input").listFiles { f -> f.name.startsWith("event") } ?: return null
        var first: String? = null
        val foreign = if (matcher == "@controller") AynPad.foreignNames() else emptySet()
        for (n in nodes.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: 0 }) {
            val name = runCatching { java.io.File(n, "device/name").readText().trim() }.getOrNull() ?: continue
            // the Thor's pad (or the layer's copy of it) — not an external pad's copy (GitHub #4)
            val hit = if (matcher == "@controller")
                CONTROLLER.containsMatchIn(name) && !name.contains("Mouse", ignoreCase = true) &&
                    (runCatching { java.io.File(n, "device/phys").readText().trim() }.getOrDefault("") == PadLayer.CLONE_PHYS || AynPad.isAyn(n, foreign))
            else name == matcher
            if (!hit) continue
            if (matcher != "@controller") return "/dev/input/${n.name}"
            val phys = runCatching { java.io.File(n, "device/phys").readText().trim() }.getOrDefault("")
            if (phys == PadLayer.CLONE_PHYS) return "/dev/input/${n.name}"
            if (first == null) first = "/dev/input/${n.name}"
        }
        return first
    }

    private val CONTROLLER = Regex("(?i)controller|gamepad|xbox")

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
