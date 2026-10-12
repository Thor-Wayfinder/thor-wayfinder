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
    @Volatile private var named = false
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
        // …not what ExtPads hides on purpose: the Thor's own pad while docked, and (dev build 15, C2) what ExtNodes switched
        // off for a controller it handles (ExtPads.covers: by Android id). The devices it removed (unlinked nodes) aren't
        // listed at all, its touchpads / motion sensors aren't gamepads: the C2 devices are left alone
        for (d in snap) if (d.gamepad && !d.enabled && d.reader >= 0 && !ExtPads.covers(d.name, d.reader) && d.reader !in ExtPads.dockHidden &&
            !d.location.startsWith(ExtPads.PHYS_PREFIX)) {                // ExtPads' own pads: it shows / hides them itself
            setEnabled(d.reader, true); Log.i(TAG, "re-enabled ${d.name} (#${d.reader})") }
    }

    /** Helper start: undo what a helper that died mid-way left behind — disabled pads, AYN's
     *  layout left switched, AYN's pad left at #2. The number fix waits a moment: if the app
     *  turns the layer on right away, its takeover does the rebuild anyway. */
    fun startup() {
        // A previous helper's copy still running (it hadn't stopped yet): two copies fighting
        // over AYN's pad left NO controller after app updates (release test 2026-09-24).
        // `-x` = the process name exactly: can't match the shell running pkill.
        runCatching { if (sh("pkill", "-x", "wfpad") != null) Thread.sleep(300) }
        // external controllers (ExtPads): a previous helper's readers go too — the new one starts its own
        runCatching { sh("pkill", "-x", "wfhid") }
        // dev build 15 (C2): what that helper's ExtNodes took out of Android's list (this boot) comes back
        runCatching { ExtNodes.cleanupLeftovers() }
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
            // G name <0|1> — the copy shows "<name> (Wayfinder)" (opt-in, Controller → Other controllers); a change
            // remakes the copy (the app sends it BEFORE "G on", so a start doesn't make it twice)
            "name" -> { val n = p.getOrNull(2) == "1"; if (n != named) { named = n; if (proc != null) { plannedStop = true; quitWfpad() } } }   // a remake, not a crash
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
    internal fun trustedBinary(file: String = "wfpad"): String? {
        if (appUid < 0) { untrustedWhy = "no app uid"; return null }
        val user = appUid / 100_000
        val internal = "/data/user/$user/${BuildConfig.APPLICATION_ID}/files/$file"
        // 1.4 (GitHub #39): moved to an SD card set up as internal storage, the app's files live on that card
        val path = if (java.io.File(internal).exists()) internal else runCatching {
            java.io.File("/mnt/expand").listFiles().orEmpty()
                .map { java.io.File(it, "user/$user/${BuildConfig.APPLICATION_ID}/files/$file") }.firstOrNull { it.exists() }?.path
        }.getOrNull() ?: internal
        val st = try { android.system.Os.stat(path) } catch (e: android.system.ErrnoException) {
            untrustedWhy = if (e.errno == android.system.OsConstants.ENOENT) "file missing (user $user)"
                else "unreadable: ${android.system.OsConstants.errnoName(e.errno) ?: e.errno} (user $user)"
            Log.w(TAG, "$file refused: $path — $untrustedWhy"); return null
        }
        val why = when {
            st.st_uid != appUid -> "owner ${st.st_uid}, app $appUid"
            (st.st_mode and "022".toInt(8)) != 0 -> "mode ${Integer.toOctalString(st.st_mode and "7777".toInt(8))}"
            else -> ""
        }
        untrustedWhy = why
        if (why.isNotEmpty()) Log.w(TAG, "$file refused: $path — $why")
        return if (why.isEmpty()) path else null
    }

    @Volatile private var lastError = ""
    private fun status(s: String) {
        // device names come from sysfs — any app can create a uinput device named "x\nX 30 1":
        // one line, or the app would read forged helper lines (review 2026-09-25)
        val line = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")
        if (line.startsWith("error")) lastError = line
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
        // NEWEST first (sysfs inputN only grows): AYN can leave an old pad behind it never feeds again — taking the
        // lowest eventN started the copy on that dead pad, no Thor buttons at all (2026-10-06)
        // (one with no location — exactly like AYN's — before any other of its vendor, as wfpad does)
        for (n in nodes.sortedWith(compareByDescending<File> { runCatching { File(it, "device/phys").readText().isBlank() }.getOrDefault(false) }
                .thenByDescending { AynPad.inputNumber(it) })) {
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
    internal class Dev(val hub: Int, val name: String, val path: String, val location: String, val enabled: Boolean,
                      val number: Int, val vendor: String, val gamepad: Boolean, var reader: Int = -1) {
        val isClone get() = location == CLONE_PHYS
        /** "0x0111"… (with [vendor]: what Eden & co. call the controller — its "guid") */
        var product = ""
        /** EventHub class JOYSTICK (dev build 15: the identity table counts game controllers as Eden does) */
        var joystick = false
    }

    /** Every input device, fresh from `dumpsys input` (~6 ms). This process's InputManager cache
     *  can't be trusted for pads: after an app update it still listed the previous copy at #1,
     *  and its descriptors can't tell our copy from AYN's pad (Android ignores the location for
     *  this pad: SAME descriptor) — both made the takeover give up and leave AYN's pad disabled,
     *  i.e. no controller at all (release test 2026-09-24). */
    internal fun snapshot(): List<Dev> {
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
        var hub = -1; var name = ""; var classes = ""; var path = ""; var enabled = true; var loc = ""; var num = 0; var vendor = ""; var product = ""
        fun flush() { if (hub >= 0 && path.isNotEmpty()) devs += Dev(hub, name, path, loc, enabled, num, vendor, "GAMEPAD" in classes).also { it.product = product; it.joystick = "JOYSTICK" in classes }; hub = -1 }
        var reader = -1
        for (line in text.lineSequence()) {
            if (!inReader) {
                if (line.startsWith("Input Reader State")) { flush(); inReader = true; continue }
                val h = hubHeader.find(line)
                if (h != null) { flush(); hub = h.groupValues[1].toInt(); name = h.groupValues[2].trim(); classes = ""; path = ""; enabled = true; loc = ""; num = 0; vendor = ""; product = ""; continue }
                if (hub < 0) continue
                val t = line.trim()
                when {
                    t.startsWith("Classes:") -> classes = t
                    t.startsWith("Path:") -> path = t.removePrefix("Path:").trim()
                    t.startsWith("Enabled:") -> enabled = t.removePrefix("Enabled:").trim() != "false"
                    t.startsWith("Location:") -> loc = t.removePrefix("Location:").trim()
                    t.startsWith("ControllerNumber:") -> num = t.removePrefix("ControllerNumber:").trim().toIntOrNull() ?: 0
                    t.startsWith("Identifier:") -> {
                        vendor = Regex("vendor=(0x[0-9a-fA-F]+)").find(t)?.groupValues?.get(1) ?: ""
                        product = Regex("product=(0x[0-9a-fA-F]+)").find(t)?.groupValues?.get(1) ?: ""
                    }
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
        rebuilding = true
        try { return rebuildAynPadFlip(done) } finally { rebuilding = false; aynRebuildEnd = System.currentTimeMillis() }
    }

    private fun rebuildAynPadFlip(done: CountDownLatch?): Boolean {
        // 1.4 (GitHub #39): on a Thor whose controller style was never changed these read null — AYN's defaults
        // then (Standard, not flipped: the same as AYN's drawer shows), written back as such afterwards
        val mode = getSetting(MODE) ?: 1; val flip = getSetting(FLIP) ?: 0
        if (mode !in 0..1 || flip !in 0..1) {
            status(if (mode == 2) "error layout-unknown controller style \"Ban On Use\" (AYN drawer → Controller style → Standard or Xbox)"
                else "error layout-unknown $mode $flip"); return false
        }
        runCatching { marker().writeText("$mode $flip") }
        // dev build 14 (lifecycle 4): the app's style observer leaves this flip alone — it told the controllers' readers,
        // whose A / B then swapped for as long as the flip lasted
        send?.invoke("Z - L 1\n")
        try {
            putSetting(MODE, 1 - mode!!); putSetting(FLIP, 1 - flip!!)
            if (done != null) done.await(3, TimeUnit.SECONDS) else Thread.sleep(500)
        } finally {
            putSetting(MODE, mode!!); putSetting(FLIP, flip!!)
            marker().delete()
            send?.invoke("Z - L 0\n")
        }
        return true
    }

    @Volatile private var exiting = false

    // ---- player order (ExtPads) ---------------------------
    /** Android numbers a controller when it appears (the lowest free number): to make an external controller
     *  player 1, the copy goes away, the external one is made, then the copy comes back (it then takes the
     *  next free number — AYN's pad is rebuilt as usual). */
    @Volatile private var held = false
    @Volatile private var plannedStop = false
    /** AYN's pad couldn't be rebuilt for its controller style ([rebuildAynPadNow] said why). */
    @Volatile private var layoutRefused = false

    /** The copy is running (an external controller can only go before it while the layer is on). */
    internal val copyRunning get() = wanted && proc != null
    /** The copy as a UHID device with a serial (dev build 13): on unless `setprop debug.wayfinder.uhidcopy 0`. */
    private val uhidCopy: Boolean get() = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.wayfinder.uhidcopy") != "0"
    }.getOrDefault(true)
    /** What the copy is now: "uhid <serial>" or "uinput <why>" (from wfpad's "I copy" lines). */
    @Volatile var copyKind = ""
        private set

    /** The app wants the layer on (for the app in front) — the copy may be restarting this moment. */
    internal val layerWanted get() = wanted

    // ── dev build 15 (C3): when the Thor's devices won't move (ExtPads makes a new pad only then) ──
    /** The copy is made and won't be made again by itself: false when wfpad starts and at each "R", true at its "I copy"
     *  line (the UHID swap done, or uinput kept) — at once without the UHID copy. */
    @Volatile private var copySettled = false
    /** The last "R" (or wfpad start) — the UHID swap comes ~1.5 s after the first "R". */
    @Volatile private var copyRAt = 0L
    /** AYN's pad is being made again ([rebuildAynPadNow]) / since when it is done. */
    @Volatile private var rebuilding = false
    @Volatile private var aynRebuildEnd = 0L

    /** The Thor's devices won't move now: the copy settled for 2.5 s (the sweeper then needs ≤ 0.5 s to start aynFirst's
     *  0.9 s count — [firstSince] covers the count), AYN's pad not being made again (and 1.5 s after), the copy not held
     *  back for the player order. "I copy" never said (an older wfpad): 6 s after the last "R". */
    internal fun thorQuiet(): Boolean {
        val now = System.currentTimeMillis()
        val copyOk = !wanted || proc == null || docked || (copySettled && now - copyRAt > 2500) || now - copyRAt > 6000
        // review H3: aynFirst's count only while the layer runs — a stop in the middle of it left [firstSince] set, and the
        // Thor never quiet until the next wfpad start (every new controller waited 15 s, C3's fixes stopped)
        val firstOk = proc == null || !wanted || firstSince == 0L
        return copyOk && !rebuilding && firstOk && now - aynRebuildEnd > 1500 && !held
    }

    /** The Thor's own devices in [snap]: the copy, and the AYN pad wfpad reads — without the layer, AYN's newest pad. An
     *  orphan AYN pad never moves: not counted. */
    internal fun thorPaths(snap: List<Dev>): Set<String> {
        val out = HashSet<String>()
        snap.filter { it.isClone }.forEach { out += it.path }
        if (proc != null) sourceNode?.let { out += it } else resolveOriginal()?.first?.let { out += it }
        return out
    }

    /** [on]: the copy goes away and stays away until [holdCopy] (false). Waits ≤ 3 s for it to go. */
    internal fun holdCopy(on: Boolean) {
        held = on
        if (!on || proc == null) return
        plannedStop = true; quitWfpad()
        val until = System.currentTimeMillis() + 3000
        while (proc != null && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    /** Docked on a TV / monitor with an external controller as the Thor's (ExtPads): no copy at all until undocked —
     *  the external controller takes its place (its name, player 1). */
    @Volatile private var docked = false
    internal fun dockHold(on: Boolean) {
        if (on == docked) return
        docked = on
        if (on && proc != null) {
            plannedStop = true; quitWfpad()
            val until = System.currentTimeMillis() + 3000
            while (proc != null && System.currentTimeMillis() < until) Thread.sleep(50)
        }
    }
    internal val isDocked get() = docked

    /** Docked with the layer running (ExtPads): the copy is fed by an external controller's pad (wfpad "s eventN") — the
     *  same copy for games, nothing re-made (a running game keeps its controller). null = AYN's pad, as usual. */
    @Volatile var feedNode: String? = null
        private set
    @Volatile private var feedWanted: String? = null
    /** dev build 18f: the feed asked for is the MERGED one (wfpad "s eventN m": AYN's pad and the fed pad both drive the copy
     *  — a controller that plays as the Thor's by its switch, undocked); false = the drained one (docked: AYN's pad only
     *  drained). The requested mode, not wfpad's answer ([feedMerge]). */
    @Volatile private var feedMergeWanted = false
    /** dev build 18f: wfpad says the feed is merged ("F on <eventN> m"); "F off" or a drained "F on <eventN>" clears it. */
    @Volatile var feedMerge = false
        private set
    /** Fed, or to be fed when the copy (re)starts. */
    internal val feedOn get() = feedWanted != null || feedNode != null
    /** dev build 18f: aynFirst / closeGap may run — no feed wanted, or the feed ASKED FOR is the merged one (the copy must
     *  stay before AYN's pad for Eden's port 0 while a controller plays as the Thor's undocked). The requested mode: during
     *  the in-place merged → drained switch at docking wfpad's answer is stale (aynFirst would make AYN rebuild its pad
     *  mid-dock). A feed being stopped (none wanted, [feedNode] still set) keeps them off, as before; docked stays out. */
    private val orderFixesAllowed get() = !(feedOn && !(feedWanted != null && feedMergeWanted))
    /** wfpad refused the feed ("E feed ...") this many times since it last worked: 3 = docking takes the rename way. */
    @Volatile var feedRefusals = 0
    /** Feed the copy from our pad [node] (null: AYN's pad again). [merge] (dev build 18f): AYN's pad drives it too ("s
     *  eventN m") — a controller playing as the Thor's undocked; else the drained feed of the dock ("s eventN"). */
    internal fun feedFrom(node: String?, merge: Boolean = false) {
        if (node != null && !NODE.matches(node)) return
        feedWanted = node; feedMergeWanted = node != null && merge
        toWfpad(feedLine(node, feedMergeWanted))
    }
    private fun feedLine(node: String?, merge: Boolean) = if (node == null) "s -" else if (merge) "s $node m" else "s $node"

    /** The copy is made again now (it takes #1 if free: the Thor goes back to player 1). */
    internal fun restartCopy() {
        if (proc == null) return
        plannedStop = true; quitWfpad()
    }

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
        // …unless Wayfinder replaces that controller right now (ExtPads hides AYN's incomplete copy of it)
        for (d in snap) if (!d.isClone && d.gamepad && !d.enabled && d.reader >= 0 && d.vendor == clone.vendor &&
            !AynPad.isAyn(File(d.path), foreign) && !ExtPads.covers(d.name, d.reader) && !d.location.startsWith(ExtPads.PHYS_PREFIX)) { setEnabled(d.reader, true); Log.i(TAG, "external pad enabled again: ${d.name}") }
        for (d in snap) {
            if (d.isClone || !d.gamepad || !d.enabled || d.reader < 0 || d.vendor != clone.vendor) continue
            // an external pad's copy (AYN re-emits it under its own ids): it's the player's controller — leave it
            if (!AynPad.isAyn(File(d.path), foreign)) { Log.i(TAG, "external pad left alone: ${d.name}"); continue }
            setEnabled(d.reader, false); Log.i(TAG, "hid ${d.name} (#${d.reader})")
        }
    }

    @Volatile private var firstFixes = 0
    @Volatile private var firstSince = 0L
    /** The copy's product when [firstFixes] was last counted: a style flip (0111 ↔ 0112) is a new copy and a new AYN pad. */
    @Volatile private var firstFixProduct = ""
    /** The AYN pad wfpad reads now ("S" lines): the only one a re-make can move. */
    @Volatile private var sourceNode: String? = null

    /** AYN's (hidden) pad listed BEFORE the copy with the same ids. Emulators tell controllers apart by vendor:product +
     *  a port = the order of Android's device ids, hidden devices included (Eden: "guid:…0111…2020,port:0"), so Player 1
     *  was AYN's silent pad (found 2026-10-06: the UHID copy is made after AYN's pad). True = AYN should make its pad again
     *  now (it then comes after the copy): once that has lasted ~1 s, twice at most per wfpad run and AYN style, and only when it's the
     *  pad wfpad reads — an orphan AYN keeps is never re-made (nothing would move; each re-make releases held buttons).
     *  Not in compatibility mode (AYN's pad doesn't restart there), not docked / fed by the dock / held — dev build 18f: the
     *  merged feed asked for ([orderFixesAllowed]: a controller playing as the Thor's undocked) keeps it on. */
    private fun aynFirst(node: String, snap: List<Dev>): Boolean {
        val me = snap.firstOrNull { it.isClone && it.path == node }
        // dev build 15: the two re-makes are per AYN style, not per run — seen on the Thor: start + a style flip and back used
        // both, and the copy stayed listed after AYN's pad (Eden's port pointed at the hidden one). A flip comes from the user
        // or a game, never from a re-make, so this can't loop
        if (me != null && me.product.isNotEmpty() && me.product != firstFixProduct) { firstFixProduct = me.product; firstFixes = 0 }
        val foreign by lazy { AynPad.foreignNames() }
        val src = sourceNode
        val bad = me != null && me.reader >= 0 && me.number > 0 && me.product.isNotEmpty() && !compatMode && !docked && orderFixesAllowed &&
            !held && src != null && firstFixes < 2 && snap.any { !it.isClone && it.path == src && it.reader in 0 until me.reader &&
                it.vendor == me.vendor && it.product == me.product && AynPad.isAyn(File(it.path), foreign) }
        val now = System.currentTimeMillis()
        if (!bad) { firstSince = 0L; return false }
        if (firstSince == 0L) { firstSince = now; return false }
        if (now - firstSince < 900) return false
        firstSince = 0L; firstFixes++
        status("order: AYN's pad is listed before the copy — AYN makes it again (${firstFixes}/2)")
        return true
    }

    @Volatile private var gapSince = 0L
    @Volatile private var gapFixedAt = 0L

    /** The copy alone (no other controller in view) but not #1 while a lower number is free — an external controller
     *  that came first has gone (seen 2026-10-06: the Thor left at #3, a game reading player 1 got nothing). True = make
     *  the copy again now (it takes the lowest free number): once that has lasted 4 s, at most once per 30 s. Not
     *  docked / fed by the dock (the external controller is the Thor's then; dev build 18f: the merged feed asked for keeps it
     *  on, [orderFixesAllowed] — the copy is re-fed in that mode when it is made again), not in compatibility mode (AYN's
     *  hidden pad keeps #1). */
    private fun closeGap(node: String, snap: List<Dev>): Boolean {
        val me = snap.firstOrNull { it.isClone && it.path == node }
        val gap = me != null && me.number > 1 && !compatMode && !docked && orderFixesAllowed && !held &&
            snap.none { it.gamepad && !it.isClone && it.enabled } &&
            (1 until me.number).any { k -> snap.none { it.number == k } }
        val now = System.currentTimeMillis()
        if (!gap) { gapSince = 0L; return false }
        if (gapSince == 0L) { gapSince = now; return false }
        if (now - gapSince < 4000 || now - gapFixedAt < 30_000) return false
        gapSince = 0L; gapFixedAt = now
        status("renumber: the copy is #${me!!.number}, a lower number is free — taking it")
        return true
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
            // docked (ExtPads): the external controller is the Thor's — no copy until undocked
            if (docked) { Thread.sleep(300); continue }
            val (path, name) = resolveOriginal() ?: run { Thread.sleep(1000); null } ?: continue
            val exe = trustedBinary() ?: run { status("error binary-not-trusted: $untrustedWhy"); wanted = false; null } ?: break
            // The number the copy should end up with: the lowest one no OTHER pad holds (AYN's
            // pad may sit at #2 after an earlier run; the copy takes #1 all the same).
            val wantNumber = snapshot().takeIf { it.isNotEmpty() }?.let { targetNumber(it, path) } ?: 0
            // "uhid" (dev build 13): the copy becomes a UHID device with a serial ~1.5 s after it's made — Android's
            // descriptor for it then never changes, so emulators keep the Thor (fx/wfuhid.h); uinput if that fails
            firstFixes = 0; firstSince = 0L; sourceNode = null      // per wfpad run (review 2026-10-06: not per copy)
            val pb = ProcessBuilder(exe, path, "grab", CLONE_PHYS, if (compatMode) "0" else "4000", if (named) "named" else "-",
                if (uhidCopy) "uhid" else "-").redirectErrorStream(true)
            val p = runCatching { pb.start() }.getOrElse { status("error start ${it.message}"); Thread.sleep(2000); null } ?: continue
            copySettled = false; copyRAt = System.currentTimeMillis()
            proc = p
            status("starting $name ($path, controller #$wantNumber)")
            if (profile.isNotEmpty()) toWfpad("m $profile")
            toWfpad(watch)
            feedNode = null; feedMerge = false
            // docked: a new copy is fed by the external pad again — dev build 18f: in the mode asked (merged: a controller
            // playing as the Thor's undocked, the Thor's own buttons too)
            feedWanted?.let { toWfpad(feedLine(it, feedMergeWanted)) }
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
                    node?.let { n -> runCatching {
                        val snap = snapshot(); hideOthers(n, snap)
                        if (closeGap(n, snap)) { plannedStop = true; quitWfpad() }
                        // (review H3: not from a late pass while the layer stops — no count left behind, no re-make)
                        else if (sweeping.get() && wanted && aynFirst(n, snap)) { busyUntil.set(System.currentTimeMillis() + 15_000); rebuildAynPad(null) }
                    } }
                } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            p.inputStream.bufferedReader().forEachLine { line ->
                val f = line.split(' ')
                when (f[0]) {
                    // 1.4 (GitHub #39): wfpad doesn't read "q" while it waits for AYN's pad — stop it for real
                    "A" -> { busyUntil.set(System.currentTimeMillis() + 15_000); Thread { if (!rebuildAynPad(created)) { layoutRefused = true; quitWfpad(); runCatching { p.destroy() } } }.apply { isDaemon = true; start() } }
                    "R" -> {
                        // dev build 15 (C3): the copy moves now (made, then swapped to UHID ~1.5 s later)
                        copyRAt = System.currentTimeMillis(); copySettled = !uhidCopy
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
                        sourceNode = f.lastOrNull()?.takeIf { NODE.matches(it) }?.let { "/dev/input/$it" }
                        if (!compatMode) status("source ${f.drop(1).joinToString(" ")}") }
                    "W" -> status("waiting for AYN's pad")
                    "L" -> status("latency $line")      // L n p50 p99 max (µs)
                    // withheld from the game (Home/Back held): to the app, for its shortcuts
                    // X = a button mapped to a key / action; V = a watched control (gyro on / off);
                    // T = a stick that is Wayfinder's (1.3: mouse / scroll / keys); b18o: TU = a turbo button the hand holds
                    // or let go (the copy's button it pulses, the time)
                    "J", "X", "V", "T", "TU" -> send?.invoke("$line\n")
                    "M" -> if (f.getOrNull(1) != "ok") status("error profile-refused")
                    // which copy it is (UHID with a serial, or uinput and why) — information, not an error
                    "I" -> { if (f.getOrNull(1) == "copy") { copyKind = f.drop(2).joinToString(" "); copySettled = true; status("copy ${copyKind}") } }
                    // docked: the copy is fed by an external controller's pad (on), or by AYN's pad again (off). dev build 18f:
                    // "F on <eventN> m" = the merged feed (AYN's pad drives it too)
                    "F" -> { feedNode = if (f.getOrNull(1) == "on") f.getOrNull(2)?.takeIf { NODE.matches(it) } else null
                        feedMerge = feedNode != null && f.getOrNull(3) == "m"
                        if (f.getOrNull(1) == "on") feedRefusals = 0
                        if (f.getOrNull(1) == "off") { feedWanted = null; feedMergeWanted = false }
                        status("feed ${f.drop(1).joinToString(" ")}") }
                    // a refused feed: counted (ExtPads takes the rename way after 3), not an exit reason
                    else -> if (line.startsWith("E feed")) { feedRefusals++; feedWanted = null; feedMergeWanted = false; status("wfpad $line") }
                        else { lastWfpad = line.take(80); status("wfpad $line") }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            send?.invoke("TU 0 0 0\n")                                 // b18o: its engine gone — no turbo button held any more
            sweeping.set(false); sweeper.interrupt(); runCatching { sweeper.join(2000) }
            firstSince = 0L; firstFixes = 0                             // review H3: no count left running past the run
            proc = null
            enableAllPads()
            onCloneChanged?.invoke()
            status("stopped (exit $code)")
            if (!wanted) { plannedStop = false; break }                 // (not carried into the next "on")
            // AYN's controller style can't be rebuilt (Ban On Use, unknown): off with that reason, not "keeps crashing"
            if (layoutRefused) { layoutRefused = false; status(lastError.takeIf { it.startsWith("error layout") } ?: "error layout-unknown"); wanted = false; break }
            if (code == 5) {                                   // Home + Back held 5 s
                status("emergency-off"); wanted = false; break
            }
            // player order (ExtPads): stopped on purpose — not a failure; back once the external pads are made
            if (plannedStop) {
                plannedStop = false
                val until = System.currentTimeMillis() + 10_000
                while (held && wanted && System.currentTimeMillis() < until) Thread.sleep(100)
                held = false
                continue                                       // (docked: the loop's top waits until undocked)
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
