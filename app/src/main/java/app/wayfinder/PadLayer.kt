package app.wayfinder

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Input layer, phase 0 — runs INSIDE the root input helper ([InputMonitorTool], uid 0).
 * Supervises `wfpad` (fx/wfpad.c): it grabs AYN's pad and forwards everything to an identical
 * copy (phys "wayfinder-clone").
 *
 * Controller number (a blocker): emulators key saved mappings on it
 * (Dolphin "Android/1/Odin Controller"), so the copy must TAKE AYN's number, not sit next to
 * it as #2. Takeover: wfpad arms the copy ("A"), we make AYN rebuild its pad by switching its
 * layout away and straight back, and wfpad creates the copy in the instant AYN's pad is gone
 * — the copy inherits the number, AYN's rebuilt pad gets the next one. We then CHECK the
 * copy's number; a lost race quits wfpad and tries again (3 tries).
 * While on: every AYN pad that isn't the copy is disabled in Android (AYN rebuilds its pad on
 * layout switches — wfpad re-grabs the new one, a sweep here hides it).
 * When the layer stops, the copy is gone and AYN's pad would stay at #2 for good: we rebuild
 * it once more so it gets #1 back ([normalize]) — also at helper start (after a crash).
 * Commands (from the app): `G on` · `G off`. The binary is NOT taken from the command (a
 * forged line must never make root run a file of its choice): it is always the app's own
 * `files/wfpad`, and runs only if owned by the app uid and writable by nobody else.
 * Status lines back to the app: `P <state> <detail>`.
 */
object PadLayer {
    private const val TAG = "ThorPadLayer"
    const val CLONE_PHYS = "wayfinder-clone"
    private const val MODE = "temp_abxy_layout_mode"
    private const val FLIP = "flip_button_layout"
    @Volatile private var wanted = false
    /** wfpad's last message (not a known status line): said with a failure (GitHub #39). */
    @Volatile private var lastWfpad = ""
    /** 1.3 (GitHub #40): the screens are on (the app says so: "S 1" / "S 0"). Off = the sweeper rests. */
    @Volatile private var screenOn = true
    @Volatile private var wokeAt = 0L
    fun setScreen(on: Boolean) {
        if (on && !screenOn) wokeAt = System.currentTimeMillis()
        screenOn = on
    }
    /** The Wayfinder app's uid (from the helper's launch arguments). */
    @Volatile var appUid: Int = -1
    private var thread: Thread? = null
    @Volatile private var proc: Process? = null
    /** wfpad runs: the copy is the pad games read. */
    val running get() = proc != null
    var send: ((String) -> Unit)? = null
    /** Called when the clone appears / disappears, so the controller reader re-resolves. */
    var onCloneChanged: (() -> Unit)? = null

    private val im: InputManager by lazy { InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager }

    fun enabled(id: Int): Boolean = runCatching {
        im.javaClass.getMethod("isInputDeviceEnabled", Int::class.javaPrimitiveType).invoke(im, id) as Boolean
    }.getOrDefault(true)

    fun setEnabled(id: Int, on: Boolean) = runCatching {
        im.javaClass.getMethod(if (on) "enableInputDevice" else "disableInputDevice", Int::class.javaPrimitiveType).invoke(im, id)
    }.onFailure { Log.w(TAG, "setEnabled $id $on: ${it.cause ?: it}") }

    private fun isPad(d: InputDevice) = (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

    /** Re-enable every disabled gamepad — at helper start (a previous helper may have died
     *  with the original disabled) and whenever the layer stops. */
    fun enableAllPads() {
        val snap = snapshot()
        if (snap.isEmpty()) {                                   // dumpsys failed: the cached list
            for (id in im.inputDeviceIds) {
                val d = im.getInputDevice(id) ?: continue
                if (isPad(d) && !enabled(id)) { setEnabled(id, true); Log.i(TAG, "re-enabled ${d.name} (#$id)") }
            }
            return
        }
        for (d in snap) if (d.gamepad && !d.enabled && d.reader >= 0) { setEnabled(d.reader, true); Log.i(TAG, "re-enabled ${d.name} (#${d.reader})") }
    }

    /** Helper start: undo what a helper that died mid-way left behind — disabled pads, AYN's
     *  layout left switched, AYN's pad left at #2. The number fix waits a moment: if the app
     *  turns the layer on right away, its takeover does the rebuild anyway. */
    fun startup() {
        // A previous helper's copy still running (it hadn't stopped yet): two copies fighting
        // over AYN's pad left NO controller after app updates (release test 2026-09-24).
        // `-x` = the process name exactly: can't match the shell running pkill.
        runCatching { if (sh("pkill", "-x", "wfpad") != null) Thread.sleep(300) }
        runCatching { enableAllPads() }
        runCatching { restoreLayoutFromMarker() }
        Thread {
            Thread.sleep(4000)
            if (!wanted && thread?.isAlive != true) runCatching { normalize() }
        }.apply { isDaemon = true; start() }
    }

    fun command(p: List<String>) {
        when (p.getOrNull(1)) {
            "on" -> { wanted = true; ensureThread() }
            "off" -> { wanted = false; quitWfpad() }
            // G map <profile> — the profile of the app that has the controller (see wf_parse
            // in fx/wfmap.h; wfpad re-validates every token). Kept and re-sent to a new wfpad.
            "map" -> {
                val spec = p.drop(2).joinToString(" ")
                if (spec.length > 1500 || !PROFILE.matches(spec)) { status("error bad-profile"); return }
                profile = spec
                toWfpad("m $spec")
            }
            // G g <0|l|r> <x> <y> — the gyro as a stick (the app's GyroEngine), ~200 a second
            "g" -> {
                val c = p.getOrNull(2); val x = p.getOrNull(3)?.toIntOrNull(); val y = p.getOrNull(4)?.toIntOrNull()
                if (p.size == 5 && c in GYRO_STICKS && x != null && y != null && x in -32767..32767 && y in -32767..32767)
                    toWfpad("g $c $x $y")
            }
            // G p <code> <0|1> — a virtual press (the app's macros, combos…); wfpad re-validates
            "p" -> {
                val c = p.getOrNull(2)?.toIntOrNull(); val v = p.getOrNull(3)
                if (p.size == 4 && c != null && c in 0 until 0x300 && (v == "0" || v == "1")) toWfpad("p $c $v")
            }
            // G w [k<code>|a<axis>]… — controls whose raw state the app needs ("V" lines: gyro on/off)
            "w" -> {
                val t = p.drop(2).filter { it.isNotEmpty() }
                if (t.size <= 4 && t.all { WATCH.matches(it) }) { watch = ("w " + t.joinToString(" ")).trimEnd(); toWfpad(watch) }
            }
        }
    }

    private val GYRO_STICKS = setOf("0", "l", "r")
    private val WATCH = Regex("^[ka][0-9]{1,3}$")
    @Volatile private var watch = "w"

    /** Tokens like `L=x`, `k0x130=0x131`, `sw=1` — nothing else can reach wfpad's stdin. */
    // a space BETWEEN tokens only (an optional one made the regex backtrack exponentially)
    private val PROFILE = Regex("^([A-Za-z0-9]{1,8}=[A-Za-z0-9]{1,8}( [A-Za-z0-9]{1,8}=[A-Za-z0-9]{1,8})*)?$")
    @Volatile private var profile = ""

    private fun toWfpad(line: String) {
        proc?.let { runCatching { synchronized(it) { it.outputStream.write("$line\n".toByteArray()); it.outputStream.flush() } } }
    }

    private fun quitWfpad() = toWfpad("q")

    /** Why the last [trustedBinary] refused the file (shown in Help & status). */
    @Volatile private var untrustedWhy = ""

    /** `/data/user/<user>/<app>/files/wfpad`, if it belongs to the app and only the app can write it.
     *  1.3.2 (GitHub #39): the app's Android user comes from its uid (it was always user 0 — a Wayfinder in a
     *  second user or profile never found its file), and a refusal says exactly why. */
    private fun trustedBinary(): String? {
        if (appUid < 0) { untrustedWhy = "no app uid"; return null }
        val user = appUid / 100_000
        val internal = "/data/user/$user/${BuildConfig.APPLICATION_ID}/files/wfpad"
        // 1.4 (GitHub #39): moved to an SD card set up as internal storage, the app's files live on that card
        val path = if (java.io.File(internal).exists()) internal else runCatching {
            java.io.File("/mnt/expand").listFiles().orEmpty()
                .map { java.io.File(it, "user/$user/${BuildConfig.APPLICATION_ID}/files/wfpad") }.firstOrNull { it.exists() }?.path
        }.getOrNull() ?: internal
        val st = try { android.system.Os.stat(path) } catch (e: android.system.ErrnoException) {
            untrustedWhy = if (e.errno == android.system.OsConstants.ENOENT) "file missing (user $user)"
                else "unreadable: ${android.system.OsConstants.errnoName(e.errno) ?: e.errno} (user $user)"
            Log.w(TAG, "wfpad refused: $path — $untrustedWhy"); return null
        }
        val why = when {
            st.st_uid != appUid -> "owner ${st.st_uid}, app $appUid"
            (st.st_mode and "022".toInt(8)) != 0 -> "mode ${Integer.toOctalString(st.st_mode and "7777".toInt(8))}"
            else -> ""
        }
        untrustedWhy = why
        if (why.isNotEmpty()) Log.w(TAG, "wfpad refused: $path — $why")
        return if (why.isEmpty()) path else null
    }

    private fun status(s: String) {
        // device names come from sysfs — any app can create a uinput device named "x\nX 30 1":
        // one line, or the app would read forged helper lines (review 2026-09-25)
        val line = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")
        Log.i(TAG, line); send?.invoke("P $line\n")
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    /** AYN's pad node (never our clone, never an external pad's copy — [AynPad], GitHub #4). */
    private fun resolveOriginal(): Pair<String, String>? {
        val nodes = File("/sys/class/input").listFiles { f -> f.name.startsWith("event") } ?: return null
        val foreign = AynPad.foreignNames()
        for (n in nodes.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: 0 }) {
            val name = runCatching { File(n, "device/name").readText().trim() }.getOrNull() ?: continue
            val phys = runCatching { File(n, "device/phys").readText().trim() }.getOrDefault("")
            if (phys == CLONE_PHYS || name.contains("Mouse", true)) continue
            if (AynPad.isAyn(n, foreign)) return "/dev/input/${n.name}" to name
        }
        return null
    }
    private val CONTROLLER = Regex("(?i)controller|gamepad|xbox")
    private val NODE = Regex("^event[0-9]{1,4}$")

    /** One input device as Android has it NOW. */
    private class Dev(val hub: Int, val name: String, val path: String, val location: String, val enabled: Boolean,
                      val number: Int, val vendor: String, val gamepad: Boolean, var reader: Int = -1) {
        val isClone get() = location == CLONE_PHYS
    }

    /** Every input device, fresh from `dumpsys input` (~6 ms). This process's InputManager cache
     *  can't be trusted for pads: after an app update it still listed the previous copy at #1,
     *  and its descriptors can't tell our copy from AYN's pad (Android ignores the location for
     *  this pad: SAME descriptor) — both made the takeover give up and leave AYN's pad disabled,
     *  i.e. no controller at all (release test 2026-09-24). */
    private fun snapshot(): List<Dev> {
        val text = runCatching {
            val p = ProcessBuilder("dumpsys", "input").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrNull() ?: return emptyList()
        val devs = ArrayList<Dev>()
        // "-1" too: the virtual keyboard is listed as "-1: Virtual" — missed, its lines ("Path:
        // <virtual>", no location, number 0) overwrote the device before it; when that was our
        // copy, the layer couldn't find itself ("#0") and left AYN's pad visible (2026-09-25)
        val hubHeader = Regex("^ {4}(-?\\d+): (.*)$")
        val readerHeader = Regex("^ {2}Device (-?\\d+): ")
        val hubList = Regex("^ {4}EventHub Devices: \\[ ([\\d ]+)\\]")
        var inReader = false
        var hub = -1; var name = ""; var classes = ""; var path = ""; var enabled = true; var loc = ""; var num = 0; var vendor = ""
        fun flush() { if (hub >= 0 && path.isNotEmpty()) devs += Dev(hub, name, path, loc, enabled, num, vendor, "GAMEPAD" in classes); hub = -1 }
        var reader = -1
        for (line in text.lineSequence()) {
            if (!inReader) {
                if (line.startsWith("Input Reader State")) { flush(); inReader = true; continue }
                val h = hubHeader.find(line)
                if (h != null) { flush(); hub = h.groupValues[1].toInt(); name = h.groupValues[2].trim(); classes = ""; path = ""; enabled = true; loc = ""; num = 0; vendor = ""; continue }
                if (hub < 0) continue
                val t = line.trim()
                when {
                    t.startsWith("Classes:") -> classes = t
                    t.startsWith("Path:") -> path = t.removePrefix("Path:").trim()
                    t.startsWith("Enabled:") -> enabled = t.removePrefix("Enabled:").trim() != "false"
                    t.startsWith("Location:") -> loc = t.removePrefix("Location:").trim()
                    t.startsWith("ControllerNumber:") -> num = t.removePrefix("ControllerNumber:").trim().toIntOrNull() ?: 0
                    t.startsWith("Identifier:") -> vendor = Regex("vendor=(0x[0-9a-fA-F]+)").find(t)?.groupValues?.get(1) ?: ""
                }
            } else {
                readerHeader.find(line)?.let { reader = it.groupValues[1].toInt() }
                hubList.find(line)?.let { m ->
                    val ids = m.groupValues[1].trim().split(' ').mapNotNull { it.toIntOrNull() }
                    devs.filter { it.hub in ids }.forEach { it.reader = reader }
                }
            }
        }
        flush()
        return devs
    }

    /** The number the copy should get: the lowest one no OTHER real pad holds (not AYN's pad,
     *  which the copy replaces; not a previous copy, which is on its way out). */
    private fun targetNumber(snap: List<Dev>, aynPath: String): Int {
        val used = snap.filter { it.gamepad && !it.isClone && it.path != aynPath && it.number > 0 }.map { it.number }.toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    // ---- AYN's layout switch = the way to make AYN rebuild its pad -------------------------

    private fun marker() = File("/data/user/0/${BuildConfig.APPLICATION_ID}/files/pad_layout_restore")

    private fun sh(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        // wait first (the output is a line): reading first made the timeout useless, and a hung
        // `settings` blocked the helper's startup (review 2026-09-25)
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
        p.inputStream.bufferedReader().readText().trim()
    }.getOrNull()

    private fun getSetting(k: String): Int? = sh("settings", "get", "system", k)?.toIntOrNull()
    private fun putSetting(k: String, v: Int) { sh("settings", "put", "system", k, v.toString()) }

    /** A helper died between "switch away" and "switch back": put AYN's layout back. */
    private fun restoreLayoutFromMarker() {
        val m = marker(); if (!m.exists()) return
        val v = m.readText().trim().split(' ').mapNotNull { it.toIntOrNull() }
        if (v.size == 2 && v.all { it == 0 || it == 1 }) {
            putSetting(MODE, v[0]); putSetting(FLIP, v[1]); Log.i(TAG, "restored AYN layout $v after a crash")
        }
        m.delete()
    }

    /** Switch AYN's layout away and back, so AYN deletes and re-creates its pad. [done], if
     *  given, is awaited in between (the copy is created) before switching back. */
    private val rebuildLock = Any()

    /** One at a time: a startup normalize overlapping a takeover read the flipped layout as
     *  the original and left AYN's layout flipped (review 2026-09-25). */
    private fun rebuildAynPad(done: CountDownLatch?): Boolean = synchronized(rebuildLock) { rebuildAynPadNow(done) }

    private fun rebuildAynPadNow(done: CountDownLatch?): Boolean {
        // 1.4 (GitHub #39): on a Thor whose controller style was never changed these read null — AYN's defaults
        // then (Standard, not flipped: the same as AYN's drawer shows), written back as such afterwards
        val mode = getSetting(MODE) ?: 1; val flip = getSetting(FLIP) ?: 0
        if (mode !in 0..1 || flip !in 0..1) {
            status(if (mode == 2) "error layout-unknown controller style \"Ban On Use\" (AYN drawer → Controller style → Standard or Xbox)"
                else "error layout-unknown $mode $flip"); return false
        }
        runCatching { marker().writeText("$mode $flip") }
        try {
            putSetting(MODE, 1 - mode!!); putSetting(FLIP, 1 - flip!!)
            if (done != null) done.await(3, TimeUnit.SECONDS) else Thread.sleep(500)
        } finally {
            putSetting(MODE, mode!!); putSetting(FLIP, flip!!)
            marker().delete()
        }
        return true
    }

    @Volatile private var exiting = false

    /** The app went away (restart, update or uninstall): stop the layer NOW — the next helper
     *  starts its own, and it renumbers AYN's pad itself (so no rebuild here). */
    fun stopForExit() {
        exiting = true; wanted = false; quitWfpad()
        runCatching { thread?.join(3000) }
    }

    /** The app was uninstalled: leave the Thor exactly as AYN made it. */
    fun onAppUninstalled() {
        Log.i(TAG, "Wayfinder uninstalled: cleaning up")
        wanted = false; quitWfpad()
        runCatching { thread?.join(3000) }
        runCatching { enableAllPads() }
        Thread.sleep(400)
        runCatching { normalize() }
        runCatching { putSetting("screen_focus_lock", 0) }
    }

    /** No copy around, but AYN's pad isn't the first controller while #1 is free (the copy had
     *  it): rebuild AYN's pad so it takes #1 again. */
    fun normalize() {
        val pads = snapshot().filter { it.gamepad }
        if (pads.isEmpty() || pads.any { it.number == 1 }) return
        val foreign = AynPad.foreignNames()
        if (pads.none { !it.isClone && AynPad.isAyn(File(it.path), foreign) && it.number > 1 }) return
        status("renumber: giving AYN's pad #1 back")
        rebuildAynPad(null)
    }

    // ---- the layer --------------------------------------------------------------------------

    /** Hide (disable) every pad of AYN's vendor that isn't our copy, from a fresh snapshot. The
     *  copy is recognised by its node + location, never by a remembered ID or descriptor (a stale
     *  ID, then a descriptor AYN's pad SHARES, made this disable the copy or spare AYN's pad —
     *  release test 2026-09-24). A disabled copy is switched back on. */
    private fun hideOthers(cloneNode: String, snap: List<Dev> = snapshot()) {
        val clone = snap.firstOrNull { it.isClone && it.path == cloneNode } ?: return
        if (!clone.enabled && clone.reader >= 0) { setEnabled(clone.reader, true); Log.w(TAG, "our copy was disabled (#${clone.reader}) — enabled again") }
        // an external pad's copy that an older Wayfinder disabled (GitHub #4): enabled again
        val foreign = AynPad.foreignNames()
        for (d in snap) if (!d.isClone && d.gamepad && !d.enabled && d.reader >= 0 && d.vendor == clone.vendor &&
            !AynPad.isAyn(File(d.path), foreign)) { setEnabled(d.reader, true); Log.i(TAG, "external pad enabled again: ${d.name}") }
        for (d in snap) {
            if (d.isClone || !d.gamepad || !d.enabled || d.reader < 0 || d.vendor != clone.vendor) continue
            // an external pad's copy (AYN re-emits it under its own ids): it's the player's controller — leave it
            if (!AynPad.isAyn(File(d.path), foreign)) { Log.i(TAG, "external pad left alone: ${d.name}"); continue }
            setEnabled(d.reader, false); Log.i(TAG, "hid ${d.name} (#${d.reader})")
        }
    }

    private fun loop() {
        // a "G on" that arrives while a stopping loop still cleans up found the thread alive and
        // was lost (the layer stayed off) — go round again then (review 2026-09-25)
        while (true) {
            runLayer()
            if (!wanted || exiting) break
        }
    }

    private const val FORCE_COMPAT = false   // test builds only
    /** 1.4 (GitHub #39): AYN's pad didn't go away when its layout was flipped (twice in a row) — start without the
     *  takeover race: copy at once, grab AYN's pad, hide it (kept until the layer stops). */
    @Volatile var compatMode = FORCE_COMPAT
        private set

    private fun runLayer() {
        val failures = ArrayDeque<Long>()
        var lostRaces = 0
        var noSwitch = 0
        var tookNumber = false
        while (wanted) {
            val (path, name) = resolveOriginal() ?: run { Thread.sleep(1000); null } ?: continue
            val exe = trustedBinary() ?: run { status("error binary-not-trusted: $untrustedWhy"); wanted = false; null } ?: break
            // The number the copy should end up with: the lowest one no OTHER pad holds (AYN's
            // pad may sit at #2 after an earlier run; the copy takes #1 all the same).
            val wantNumber = snapshot().takeIf { it.isNotEmpty() }?.let { targetNumber(it, path) } ?: 0
            val pb = ProcessBuilder(exe, path, "grab", CLONE_PHYS, if (compatMode) "0" else "4000").redirectErrorStream(true)
            val p = runCatching { pb.start() }.getOrElse { status("error start ${it.message}"); Thread.sleep(2000); null } ?: continue
            proc = p
            status("starting $name ($path, controller #$wantNumber)")
            if (profile.isNotEmpty()) toWfpad("m $profile")
            toWfpad(watch)
            val created = CountDownLatch(1)
            val cloneNode = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val busyUntil = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + 15_000)
            var lost = false
            val sweeping = java.util.concurrent.atomic.AtomicBoolean(true)
            // While running: keep AYN's (re-created) pads hidden — they register asynchronously.
            // Every 0.5 s around a (re)start, AYN re-creating its pad or a wake-up, every 3 s otherwise —
            // and not at all while the screens are off (each pass runs `dumpsys input`: GitHub #40).
            val sweeper = Thread {
                try { while (sweeping.get()) {
                    val now = System.currentTimeMillis()
                    Thread.sleep(if (screenOn && (now < busyUntil.get() || now - wokeAt < 15_000)) 500 else 3000)
                    if (!sweeping.get()) break
                    if (!screenOn) continue
                    // copy created but not identified yet: the newest device at our location
                    val node = cloneNode.get() ?: if (created.count == 0L)
                        runCatching { snapshot().filter { it.isClone }.maxByOrNull { it.hub }?.path }.getOrNull()?.also { cloneNode.set(it) } else null
                    node?.let { runCatching { hideOthers(it) } }
                } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            p.inputStream.bufferedReader().forEachLine { line ->
                val f = line.split(' ')
                when (f[0]) {
                    // 1.4 (GitHub #39): wfpad doesn't read "q" while it waits for AYN's pad — stop it for real
                    "A" -> { busyUntil.set(System.currentTimeMillis() + 15_000); Thread { if (!rebuildAynPad(created)) { quitWfpad(); runCatching { p.destroy() } } }.apply { isDaemon = true; start() } }
                    "R" -> {
                        created.countDown()
                        tookNumber = true
                        // wfpad says which eventN the copy is — or "?" when sysfs was slow (then:
                        // the newest device at our location)
                        val given = f.getOrNull(f.size - 1)?.takeIf { NODE.matches(it) }?.let { "/dev/input/$it" }
                        fun cloneIn(s: List<Dev>) =
                            if (given != null) s.firstOrNull { it.isClone && it.path == given } else s.filter { it.isClone }.maxByOrNull { it.hub }
                        // Wait until Android has registered the copy AND numbered it: right after
                        // an app update it took over 2 s, and deciding without it said "#0" and
                        // left AYN's pad visible next to the copy (release test 2026-09-24)
                        var snap = snapshot()
                        for (i in 0 until 25) {
                            val c = cloneIn(snap)
                            if (c != null && c.number > 0 && c.reader >= 0) break
                            Thread.sleep(200); snap = snapshot()
                        }
                        val clone = cloneIn(snap)
                        val nodePath = given ?: clone?.path
                        cloneNode.set(nodePath)
                        val n = clone?.number ?: 0
                        // Lost = the copy got a HIGHER number than it should (AYN's pad came back
                        // first). Lower is fine: the previous copy was still around when the target
                        // was worked out (an app update) and has gone since. #1 free NOW but the
                        // copy isn't #1 = the same: go again.
                        val oneFree = n > 1 && snap.none { it.gamepad && !it.isClone && it.number == 1 }
                        // 1.4 (GitHub #39): compatibility mode has no race to lose — AYN's pad keeps its number, hidden
                        if (!compatMode && ((wantNumber > 0 && n > wantNumber) || oneFree)) {
                            lost = true
                            status("lost-race copy is #$n, AYN's pad was #$wantNumber — retrying")
                            quitWfpad()
                        } else {
                            lostRaces = 0; noSwitch = 0
                            nodePath?.let { hideOthers(it, snap) }
                            status("on ${f.drop(1).dropLast(1).joinToString(" ")} · controller #$n" + if (compatMode) " (compatibility mode)" else "")
                            // Not #1 while a previous copy was still listed at #1: look again once
                            // Android has let it go — #1 free by then = take it (one retake)
                            if (n > 1 && !compatMode) Thread {
                                Thread.sleep(2500)
                                val s2 = snapshot()
                                val n2 = s2.firstOrNull { it.isClone && it.path == nodePath }?.number ?: 0
                                if (proc === p && cloneNode.get() == nodePath && n2 > 1 && s2.none { it.gamepad && !it.isClone && it.number == 1 }) {
                                    status("renumber: #1 is free now — taking it"); lost = true; quitWfpad()
                                }
                            }.apply { isDaemon = true; start() }
                            onCloneChanged?.invoke()
                        }
                    }
                    "S" -> { busyUntil.set(System.currentTimeMillis() + 15_000); cloneNode.get()?.let { runCatching { hideOthers(it) } }
                        if (!compatMode) status("source ${f.drop(1).joinToString(" ")}") }
                    "W" -> status("waiting for AYN's pad")
                    "L" -> status("latency $line")      // L n p50 p99 max (µs)
                    // withheld from the game (Home/Back held): to the app, for its shortcuts
                    // X = a button mapped to a key / action; V = a watched control (gyro on / off);
                    // T = a stick that is Wayfinder's (1.3: mouse / scroll / keys)
                    "J", "X", "V", "T" -> send?.invoke("$line\n")
                    "M" -> if (f.getOrNull(1) != "ok") status("error profile-refused")
                    else -> { lastWfpad = line.take(80); status("wfpad $line") }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            sweeping.set(false); sweeper.interrupt(); runCatching { sweeper.join(2000) }
            proc = null
            enableAllPads()
            onCloneChanged?.invoke()
            status("stopped (exit $code)")
            if (!wanted) break
            if (code == 5) {                                   // Home + Back held 5 s
                status("emergency-off"); wanted = false; break
            }
            val why = "exit $code" + if (lastWfpad.isNotEmpty()) ": $lastWfpad" else ""
            if (lost) {
                if (++lostRaces >= 3) { status("error lost-race-3x ($why) — layer off"); wanted = false; break }
            } else if (code == 4 && !compatMode) {             // AYN's pad didn't go away (GitHub #39)
                if (++noSwitch >= 2) { compatMode = true; status("AYN's pad didn't restart — compatibility mode") }
            } else if (code != 2) {                            // 2 = no AYN pad for 10 s: just retry
                val now = System.currentTimeMillis()
                failures.addLast(now); while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
                if (failures.size >= 5) { status("error keeps-crashing ($why) — layer off"); wanted = false; break }
            }
            Thread.sleep(500)
        }
        enableAllPads()
        if (tookNumber && !exiting) { Thread.sleep(300); runCatching { normalize() } }
        status("off")
    }
}
