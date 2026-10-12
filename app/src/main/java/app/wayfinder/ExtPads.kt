package app.wayfinder

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * External controllers — runs INSIDE the root input helper ([InputMonitorTool], uid 0).
 *
 * AYN's rsinput grabs every external gamepad and re-publishes a copy that drops what its layout doesn't have (a
 * Joy-Con's stick, a Nimbus+'s D-pad / triggers / Start / Select). The HID reports still reach /dev/hidrawN: one
 * `wfhid` (fx/wfhid.c) per controller reads them and publishes a complete pad in the Thor's own layout (phys
 * `wayfinder-ext-N`). Here: find the controllers, run their reader (a left + right Joy-Con: ONE reader, one
 * controller, when "combine" is on), take what Android shows for the same controller out of its list ([ExtNodes]: AYN's
 * copy, or the raw device when AYN didn't take it), keep the player order, give it all back when off.
 * dev build 15: a controller that goes away keeps its pad (at rest) for 30 minutes and takes it again when it is back
 * (C4); AYN's hold list keeps AYN off our pads (C1, [AynHold]); our pads stay listed after the Thor's devices (C3); the
 * player order re-makes pads in place, never while a game runs (C5).
 *
 * Commands (from the app): `E on` · `E off` · `E map <key> <spec>` · `E learn <key> <0|1>` · `E center <key>` ·
 * `E face <n|x>` (the Thor's face-button layout) · `E side <key> <0|1>` (a single Joy-Con sideways) ·
 * `E gyro <key> <0|1|2> <sens> <flags>` (aim with motion) · `E prof <profile>` (the game's controls) ·
 * `E order <e|t>` (player 1: an external controller / the Thor) · `E combine <0|1>` · `E buzz <key>` ·
 * `E p <slot> <code> <0|1>` (a virtual press from Wayfinder's ExtEngine into that controller) ·
 * dev build 16 (WP4): `E uses <key> <plain 0|1> <its own controls|->` (what it plays in the game now, from the app's
 * resolver, PadControls: its page says Plain, its own controls or none) · `E smap <key> <spec>` (a
 * single Joy-Con's mapping when sideways) · `E home <key> <1|0>` (its Home: the Thor's — Wayfinder's combos — or the
 * game's). Raw `J …` lines (withheld while its Home is held) go to the app like the input layer's — b18o: so do `TU …`
 * lines (a turbo button its hand holds / let go), tagged with the controller the same way.
 * dev build 15: `E ident <ident> <pid4hex> <key> <title…>` (the saved identity table, before `E on`) ·
 * `E hold ok|no <name>` · `E hold off` · `E hold on` (AYN's hold list: that name is on it / couldn't be added / can't be
 * used / usable — review H11: "on" undoes an "off" a helper kept from an earlier app process) ·
 * `E fg <pkg|-|@wf> <0|1>` (the game app that has the controllers now — `-` launcher / none, `@wf` Wayfinder; 1 = a game
 * was just detected in it) · `E order-now` (the order card's "Apply now") · `E forget <key>` (drop an away
 * controller's kept pad now). dev build 15 (WP2): `E pface <key> <a|t|n|x>` (one controller's own face: a = Automatic, t =
 * like the Thor's) · `E pfaces <key>=<t|n|x>,…` (the whole table, before `E on`; absent = Automatic, empty = none).
 * dev build 16 (theme E): `E dz <key> <l> <r>` · `E dz <key> -` (a controller's own measured deadzone, 0..30 % each, or
 * none) · `E dzbase <l> <r>` (the Thor's every-game deadzone, what the app merges into every game's profile) — the
 * deadzone rule in [tellProfile] ([ExtRules.withPadDeadzone], critique M2).
 * dev build 17 (theme G): `E watch [<k…|a…>…]` (controls every reader says as "V" lines — the app's D-pad watch while
 * Recents is open or a press is captured; never told to a reader feeding the Thor's copy) · `E unmute <key>` (its controls
 * back after its Home + Back held 5 s).
 * Lines to the app: `Z <slot> K <key> <name>` (a reader started) · `Z <slot> <wfhid line>` (I F D R C O B P E S) ·
 * `Z <slot> N <rank>` (the player it is) · `Z <slot> X <exit code>` (it stopped) · `Z - Q <ext|thor|nolayer>` (who is
 * player 1 now) · `Z - Q dock <slot>` (that controller plays as the Thor's).
 * dev build 15: `Z <slot> Y <pid4hex> <ident>` (its pad's product id and its identity) · `Z <slot> E restore <eventN>`
 * (a device couldn't be put back) · `Z - H add <name>` (please put it on AYN's list) · `Z - H broken` (AYN took a pad
 * anyway: it now has AYN's id) · `Z - M 1|0` (the player order is being made / done) · `Z - W 1 <pkg>` · `Z - W 0`
 * (the order asked waits for that app to end / no longer waits).
 * dev build 17 (theme G): `Z <slot> A <0|1>` (its AYN button) · `Z <slot> V <type> <code> <value>` (a watched control) ·
 * `Z <slot> M <1|0>` (its Home + Back held 5 s: its controls off / back — told again to an app that reconnects).
 * dev build 16 (theme F): `Z - R 1` … `Z - R 0` around readers told again (the app reconnected: [replay]) and around the
 * "K" of a controller that was already there when this loop started (an app restart makes every reader again) — the app
 * shows no "ready" / player pill for those.
 * dev build 18f: `E seat <key> <0|1>` · `E seats <key>,<key>…` (the whole table, before `E on`; bare = none) — "Plays as the
 * Thor's controller": undocked, the first connected one with it on FEEDS the Thor's copy while the Thor's own buttons keep
 * working (wfpad's merged feed, "s eventN m"); its own pad is hidden from games ([keepSeat], code name "the seat" — never
 * [Runner.asThor], the dock's rename way) · `E tpad <key> <0|1>` · `E tpadoff <key>,…` (the keys whose touchpad moves NO
 * pointer, the whole table; a reader is told "t 1" / "t 0"). Lines: `Z - T <slot>` (that controller plays as the Thor's by
 * its switch) · `Z - T -` (none) · `Z <slot> H m|b|w …` (its touchpad: the pointer, a tap's click, two-finger wheel — not
 * kept for a replay; while the screens are off only "H b 0" passes).
 * b18m: `E seatnew <0|1>` ("New controllers play as Thor's", always sent before `E on`) — on, a reader that just started
 * with its key not seated waits up to [ExtRules.SEAT_ASK_MS] after its `K` for the app's `E seat <key> 0|1` (said right
 * after the app adopted it: a controller seen for the first time starts seated — a combined Joy-Con pair too) before it is
 * numbered or put in the player order.
 * dev build 18f2 (device test t2 BUG-1): `Z - N <n>` (the Thor's own controller's player now — its place among the
 * controllers games see, as a controller's "N"; 0 = none: docked, not listed, or the last controller gone) — said with the
 * controllers' numbers, so the app shows every number from what games see now (also while a new order waits for a game).
 * `<slot>` = `hidraw<N>` (≤ 999): the controller's hidraw node when its reader started, `hidraw<N+100k>` when a kept pad
 * still had that name (see [ExtRules.slotFor]).
 * The binary is never taken from a command: always the app's own `files/wfhid`, owned by the app uid and writable by
 * nobody else ([PadLayer.trustedBinary]).
 */
object ExtPads {
    private const val TAG = "ThorExtPads"
    const val PHYS_PREFIX = "wayfinder-ext-"
    /** A combined Joy-Con pair's key (its mapping, motion…): one per kind, like every controller's. */
    const val PAIR_KEY = "057e2006-Joy-Con_pair"
    var send: ((String) -> Unit)? = null
    @Volatile private var wanted = false
    /** dev build 16 (theme F): the HID devices of the controllers there at this loop's first scan — their readers are no
     *  new connection ("Z - R" around their "K"); a reconnect is a new HID device ([hidOf]). */
    private val bootHids = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var bootScan = true
    @Volatile var screenOn = true
    private var sweeps = 0

    private class Runner(val slot: String, nodes: List<String>, val key: String, val ident: String, names: List<String>,
                         val pids: List<String>, val phys: String, val proc: Process) {
        /** Its controller's hidraw nodes now — none while it is away (dev build 15, C4). */
        @Volatile var nodes: List<String> = nodes
        @Volatile var names: List<String> = names
        /** Its HID devices (now, or the last ones): what [ExtNodes] took out of Android's list for it is kept under them. */
        @Volatile var hids: Set<String> = emptySet()
        @Volatile var ourPath: String? = null
        @Volatile var number = 0
        @Volatile var nintendo = false
        @Volatile var battery = ""
        @Volatile var shoulderSince = 0L                   // a single Joy-Con's main shoulder (L / R) held since
        @Volatile var title = ""
        /** Docked with the input layer: its pad feeds the Thor's copy (wfpad); it's hidden itself, its controls plain. */
        @Volatile var feeding = false
        /** Its pad's product id ([pidFor]; 0x0111 = AYN's). */
        @Volatile var pid = 0x0111
        /** dev build 18f: when its reader started ([start]; an attach keeps the Runner, so it keeps its place) — "Plays as the
         *  Thor's controller": the first connected one with it on takes the seat ([ExtRules.seatFor]). */
        @Volatile var connectedAt = 0L
        /** dev build 18f: wfpad refused its merged feed 3 times — its own player until its reader restarts or its switch is
         *  toggled ("E seat" / "E seats"). */
        @Volatile var seatRefused = false
        /** b18m: started while "New controllers play as Thor's" was on and its key not seated — it waits for the app's
         *  "E seat <key> 0|1" (≤ [ExtRules.SEAT_ASK_MS] from [seatAskAt]): no number, no lights, no place in the order
         *  meanwhile ([seatAsking]). */
        @Volatile var seatAsk = false
        /** b18m review 2: when that wait began — just before its "K" went to the app (the line the app answers), not
         *  [connectedAt]: [start] may first wait ≤ 1.5 s for AYN's hold list ([ensureHold], a new model's usual case). */
        @Volatile var seatAskAt = 0L
        /** Docked the rename way: it plays as the Thor's controller (its name, AYN's id). */
        @Volatile var asThor = false
        /** Its pad's name ([ExtRules.padName]). */
        @Volatile var shown = ""
        /** dev build 15 (C4): its controller went away ("S 0") at — 0 = here. Its pad stays, at rest. */
        @Volatile var idleSince = 0L
        /** The controller back: the nodes given to wfhid ("a"), until "S 1" / "E attach …". */
        @Volatile var attaching: List<String>? = null
        @Volatile var attachingNames: List<String> = emptyList()
        @Volatile var attachingHids: Set<String> = emptySet()
        /** wfhid refused to take it back (another layout / ids): the next scan makes a fresh pad for it. */
        @Volatile var attachRefused = false
        /** Review P1: "a" failed for a moment (open / info / desc: the node not made yet, the link dropped again) — tried
         *  again, refused only after 3 on the same HID device. */
        @Volatile var attachFails = 0
        @Volatile var attachFailHids: Set<String> = emptySet()
        /** Review P1: since when its hidraw node is awaited before "a" (0 = not waiting). */
        @Volatile var nodeWaitSince = 0L
        /** Review H2: it fed the Thor's copy (docked) and went away — its kept pad stays hidden, no player, until it is
         *  back or undocked (it was shown, an idle extra player, then hidden again on its return). */
        @Volatile var dockAway = false
        /** Stopped to be made again at once (a Joy-Con pair, the dock): what [ExtNodes] removed for it stays removed. */
        @Volatile var keepNodes = false
        @Volatile var stopping = false
        /** C1: AYN took its pad, made again in place at (a second take within a minute: the last resort). */
        @Volatile var tookAt = 0L
        @Volatile var taking = false
        /** wfhid's "R" lines (a pad made / made again) — critique R6: the eventN is often the same one. */
        @Volatile var rSeq = 0
        /** Its pad is being made again by us until then: no "AYN took it" sign meanwhile. */
        @Volatile var quietUntil = 0L
        /** Its pad is down for the player order ("n 0"). */
        @Volatile var down = false
        /** Review P3: [rSeq] when it went down — a pad up again by itself (wfhid's 10 s) before its turn has another. */
        @Volatile var downSeq = 0
        /** The controls ("P") it was told last: a "P" only on a change ([tellProfile]). */
        var toldProfile = ""
        // what the app was told about it, to tell again when the app (re)connects while this helper kept running:
        // the reader's I / F / U lines (once), its latest D / R / O / B / P / S / Y / M lines
        val told = java.util.Collections.synchronizedList(ArrayList<String>())
        val latest = ConcurrentHashMap<Char, String>()
        /** Review P5: [told] / [latest] and the line to the app in one step, and the app's replay of them in one — a stale
         *  "S 0" replayed after a live "S 1" left the app showing a working controller away. */
        val outLock = Any()
        val single get() = pids.size == 1
        val away get() = idleSince > 0L
    }
    private val runners = ConcurrentHashMap<String, Runner>()   // slot → its reader
    private val maps = ConcurrentHashMap<String, String>()      // controller key → mapping spec
    private val sides = ConcurrentHashMap.newKeySet<String>()   // keys of single Joy-Cons held sideways
    private val gyros = ConcurrentHashMap<String, String>()     // key → "stick sens flags"
    private val learning = ConcurrentHashMap.newKeySet<String>()
    private val ownControls = ConcurrentHashMap.newKeySet<String>()   // keys that don't follow the game's controls
    private val ownSpecs = ConcurrentHashMap<String, String>()        // key → its own controls for the game now
    private val smaps = ConcurrentHashMap<String, String>()           // key → mapping spec when held sideways
    private val homeGame = ConcurrentHashMap.newKeySet<String>()      // keys whose Home is the game's (not the Thor's)
    /** dev build 16 (theme E): each controller's own measured deadzone (left, right %), and the Thor's every-game one. */
    private val padDz = ConcurrentHashMap<String, Pair<Int, Int>>()
    @Volatile private var dzBase = 0 to 0
    /** The Thor's face-button layout ("n" Nintendo · "x" Xbox), from the app. */
    @Volatile private var face = "n"
    /** Each controller's own face (the app's choice): "n" / "x" / "t" (like the Thor's); none = Automatic. */
    private val pfaces = ConcurrentHashMap<String, String>()
    private val PFACE = setOf("n", "x", "t")
    private fun choiceOf(key: String) = FaceChoice.ofWire(pfaces[key])
    /** dev build 15 (WP2, D1): its reader's face — its own (Automatic: its maker's convention, [ControllerKind.auto]), else
     *  the Thor's. Docked (feeding the copy, or under the Thor's name) always the Thor's: the copy's engine reads a fed pad's
     *  codes as AYN's pad's (wfpad's wf_event on the source, wfmap's wf_in with the source's xbox flag) — with a face of its
     *  own, position combos and per-game remaps landed on the mirror button (Home + top became Home + left, critique R12). */
    private fun faceFor(r: Runner) = FaceRules.reader(choiceOf(r.key), ControllerKind.ofKey(r.key), face.single(), r.feeding || r.asThor)
    /** Its face and the controls that go with it ([tellProfile]'s "L=" depends on its face) — x then P: wfhid swaps in
     *  place, one output change at most. */
    private fun faceTo(r: Runner) { tell(r, "x ${faceFor(r)}"); tellProfile(r) }
    /** The game's controls (the input layer's profile, without what only the Thor's own pad can do). */
    @Volatile private var profile = ""
    /** Player 1 = an external controller (true) or the Thor (false). */
    @Volatile private var extFirst = false
    /** The player order asked (`E rank`): "thor", controllers' keys and "*" (any other controller), player 1 first.
     *  Empty = by [extFirst] alone (the Thor first or last). Requested 2026-10-03: "an option to manually order controllers". */
    @Volatile private var rank: List<String> = emptyList()
    @Volatile private var orderTries = 0
    @Volatile private var orderTriedAt = 0L
    @Volatile private var lastOrder = ""
    @Volatile private var combine = false
    // docked on a TV / monitor (the app's Dock): one external controller takes the Thor's place — its name, player 1 —
    // and the Thor's own controller (the input layer's copy, or AYN's pad) goes away until undocked
    @Volatile private var docked = false
    /** The reader playing as the Thor's controller (the Runner itself: a reader re-made on the same node is another one). */
    @Volatile private var dockRunner: Runner? = null
    /** dev build 18f: the reader that plays as the Thor's controller UNDOCKED by its switch ("the seat"): it feeds the copy
     *  merged with AYN's pad ([PadLayer.feedFrom] merge), its own pad hidden. Changed only under [dockLock] ([keepSeat], the
     *  dock's in-place switches, [thorBack]). */
    @Volatile private var seat: Runner? = null
    /** dev build 18f: the keys whose switch "Plays as the Thor's controller" is on ("E seat" / "E seats"). */
    private val seatKeys = ConcurrentHashMap.newKeySet<String>()
    /** b18m: "New controllers play as Thor's" ("E seatnew"): a reader that just started waits for the app's word on its seat
     *  ([Runner.seatAsk]). Off by default (and for a Thor-only user: no reader, nothing waits). */
    @Volatile private var seatNew = false
    /** dev build 18f: the keys whose touchpad moves NO pointer ("E tpad <key> 0" / "E tpadoff"): on by default. */
    private val tpadOff = ConcurrentHashMap.newKeySet<String>()
    /** Every dock change (enter / keep / exit / Thor back) under one lock: the loop's enterDock and an undock from the
     *  app arriving together left the Thor without a controller after undocking (review 2026-10-03). */
    private val dockLock = Any()
    /** A reader's "K" (started) and an old reader's "X" (gone) on the same slot: in order. */
    private val xLock = Any()
    /** One pad re-make at a time (the player order, C3's list, AYN taking a pad). */
    private val remakeLock = Any()
    /** Blocking reactions off the command / loop threads: AYN took a pad, a kept pad dropped or forgotten. */
    private val work = Executors.newSingleThreadExecutor { Thread(it, "ThorExtPadsWork").apply { isDaemon = true } }
    /** A wake a moment later (review P1: a controller back before its hidraw node is made). */
    private val later = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThorExtPadsLater").apply { isDaemon = true } }

    // ── dev build 15 (C4): who a controller is ──
    /** The saved identity table (`E ident`) + identities seen since: its pad's product id, settings key, name. */
    private class IdEntry(val pid: Int, val key: String, val title: String)
    private val idents = ConcurrentHashMap<String, IdEntry>()

    /** A controller's pad product id: from the table (what an emulator mapped), else 0xF000 + 12 bits of its key
     *  ([extPadProduct] — the id Eden already has), the next free one when another controller has it. Android finds its
     *  buttons layout in /data/system/devices/keylayout (a copy of AYN's, written once); without it, 0x0111. */
    private fun pidFor(ident: String, key: String): Int {
        val want = idents[ident]?.pid ?: run {
            val used = idents.filterKeys { it != ident }.values.map { it.pid }.toMutableSet()
            runners.values.filter { it.ident != ident }.mapTo(used) { it.pid }
            ExtRules.pickPid(extPadProduct(key), used)
        }
        return if (ensureLayout(want)) want else 0x0111
    }
    /** AYN's buttons layout (of [from]: 0x0111 Nintendo, 0x0112 Xbox) copied as 2020:[pid]'s, once. */
    internal fun ensureLayout(pid: Int, from: Int = 0x0111): Boolean = runCatching {
        val dir = File("/data/system/devices/keylayout")
        val f = File(dir, String.format("Vendor_2020_Product_%04x.kl", pid))
        if (f.isFile && f.length() > 0) return true
        val name = String.format("Vendor_2020_Product_%04x.kl", from)
        val src = listOf("/system/usr/keylayout/$name", "/vendor/usr/keylayout/$name")
            .map { File(it) }.firstOrNull { it.isFile } ?: return false
        if (!dir.isDirectory) dir.mkdirs()
        for (d in listOf(dir.parentFile!!, dir)) { d.setReadable(true, false); d.setExecutable(true, false) }
        f.writeText("# Thor Wayfinder: a connected controller's pad, under its own id — the same layout as AYN's controller\n" + src.readText())
        f.setReadable(true, false)
        Log.w(TAG, "buttons layout for 2020:${String.format("%04x", pid)} written")
        true
    }.getOrDefault(false)

    // ── dev build 15 (C1): AYN's hold list ──
    /** Names AYN's list has (the app confirmed them). */
    private val holdOk = ConcurrentHashMap.newKeySet<String>()
    /** AYN's list can't be used on this Thor: no waits, AYN taking a pad = the last resort at once. */
    @Volatile private var holdOff = false
    private val holdWaits = ConcurrentHashMap<String, CountDownLatch>()

    /** Controllers whose reader failed at once: not before (key → time), how many times in a row, on which HID device. */
    private val badUntil = ConcurrentHashMap<String, Long>()
    private val badCount = ConcurrentHashMap<String, Int>()
    private val badDev = ConcurrentHashMap<String, String>()
    /** Reader ids of the Thor's own pads hidden while docked ([PadLayer.enableAllPads] leaves them alone). */
    val dockHidden: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    private var thread: Thread? = null

    /** A controller's key (dev build 16, WP4: the pattern lives in [ExtRules], the app checks the same one). */
    private val KEY = ExtRules.KEY
    /** Mapping tokens wfhid understands (it re-validates each one): `S=3`, `LY=23i`, `DU=-1`. */
    private val SPEC = Regex("^([A-Z0-9]{1,2}=-?[0-9]{1,3}i?( [A-Z0-9]{1,2}=-?[0-9]{1,3}i?)*)?$")
    /** Profile tokens (fx/wfmap.h wf_parse re-validates each one) — [ExtRules.PROFILE]. */
    private val PROFILE = ExtRules.PROFILE
    private val NODE = Regex("^hidraw[0-9]{1,3}$")
    private val J_LINE = Regex("^J [0-9]{1,2} [0-9]{1,4} -?[0-9]{1,6}$")
    /** b18o: wfhid's "TU <code> <1|0> <ms>" (a turbo button its hand holds / let go). */
    private val TU_LINE = Regex("^TU [0-9]{1,4} [01] [0-9]{1,18}$")
    /** dev build 17 (theme G): what every reader is told to say as "V" lines ("E watch"): wfhid's own "w" line, at most 4
     *  controls ("k<code>" / "a<axis>", decimal — wfhid re-checks them). */
    @Volatile private var watch = "w"
    private val WATCH = Regex("^[ka][0-9]{1,3}$")
    private val PID4 = Regex("^f[0-9a-f]{3}$")

    fun command(p: List<String>) {
        when (p.getOrNull(1)) {
            "on" -> {
                wanted = true
                // dev build 15 (C2): what Android lists besides our pads goes as it appears (and the C1 sign)
                ExtNodes.owners = { owners() }
                ExtNodes.onTook = { slot, _ -> runners[slot]?.let { r -> work.execute { runCatching { aynTook(r) }.onFailure { Log.w(TAG, "took: $it") } } } }
                ExtNodes.onAppeared = { wake() }
                ExtNodes.start()
                ensureThread()
                // no setup wizard is open in an app that just (re)connected: a controller left learning drives games
                // again (dev build 14: learn mode outlived the app, and every new reader of that controller got it too)
                val wasLearning = learning.toSet(); learning.clear()
                runners.values.filter { it.key in wasLearning }.forEach { tell(it, "l 0") }
                // the app (re)connected — maybe after it restarted while this helper kept its readers running: it knows
                // none of them; tell it again (dev build 16, theme F: marked as told again — no pills)
                if (runners.isNotEmpty()) {
                    send?.invoke("Z - R 1\n")
                    runners.values.forEach { replay(it) }
                    // dev build 18f: who plays as the Thor's by its switch (its withheld presses, its pills, its page)
                    seat?.let { send?.invoke("Z - T ${it.slot}\n") }
                    // dev build 18f2: and the Thor's own player number now (the app's one source while an order waits)
                    lastThorNo.takeIf { it >= 0 }?.let { send?.invoke("Z - N $it\n") }
                    send?.invoke("Z - R 0\n")
                }
                waitingFor?.let { send?.invoke("Z - W 1 $it\n") }
            }
            "off" -> {
                wanted = false
                holdOk.clear(); holdOff = false                     // the app takes our names off AYN's list now; "on" tells again
                Thread { runCatching { synchronized(dockLock) { thorBack() } } }.apply { isDaemon = true; start() }; stopAll()
            }
            "map" -> {
                val key = p.getOrNull(2) ?: return
                val spec = p.drop(3).joinToString(" ")
                if (!KEY.matches(key) || spec.length > 400 || !SPEC.matches(spec)) { Log.w(TAG, "map refused"); return }
                maps[key] = spec
                runners.values.filter { it.key == key }.forEach { tell(it, "m $spec") }
            }
            "smap" -> {                                             // a single Joy-Con's mapping when held sideways
                val key = p.getOrNull(2) ?: return
                val spec = p.drop(3).joinToString(" ")
                if (!KEY.matches(key) || spec.length > 400 || !SPEC.matches(spec)) { Log.w(TAG, "map refused"); return }
                smaps[key] = spec
                runners.values.filter { it.key == key && it.single }.forEach { tell(it, "M $spec") }
            }
            "home" -> {                                             // its Home: the Thor's (1, default) or the game's (0)
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                val wf = p.getOrNull(3) != "0"
                if (wf) homeGame.remove(key) else homeGame.add(key)
                runners.values.filter { it.key == key }.forEach { tell(it, "h ${if (wf) 1 else 0}") }
            }
            "learn" -> {
                val key = p.getOrNull(2) ?: return; val on = p.getOrNull(3) == "1"
                if (!KEY.matches(key)) return
                if (on) learning.add(key) else learning.remove(key)
                runners.values.filter { it.key == key }.forEach { tell(it, if (on) "l 1" else "l 0") }
            }
            "face" -> {
                val f = p.getOrNull(2)?.takeIf { it == "n" || it == "x" } ?: return
                face = f
                runners.values.forEach { tell(it, "x ${faceFor(it)}") }        // only like-the-Thor and docked readers change
            }
            "pface" -> {                                            // one controller's own face: a (Automatic) | t | n | x
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                when (val f = p.getOrNull(3)) { in PFACE -> pfaces[key] = f!!; "a" -> pfaces.remove(key); else -> return }
                runners.values.filter { it.key == key }.forEach { faceTo(it) }
            }
            // dev build 15 (WP2): the whole table at once (the app's apply) — only the readers whose face changed are told.
            // critique R14: a bare "E pfaces" clears it; 64 entries at most; a bad entry is left out (that controller
            // Automatic), the others still count
            "pfaces" -> {
                val toks = p.getOrNull(2)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                if (toks.size > 64) { Log.w(TAG, "pfaces refused"); return }
                val m = HashMap<String, String>()
                for (t in toks) {
                    val k = t.substringBefore('='); val v = t.substringAfter('=', "")
                    if (KEY.matches(k) && v in PFACE) m[k] = v else Log.w(TAG, "pfaces: an entry left out")
                }
                val changed = (pfaces.keys + m.keys).filter { pfaces[it] != m[it] }.toSet()
                pfaces.putAll(m); pfaces.keys.retainAll(m.keys)
                runners.values.filter { it.key in changed }.forEach { faceTo(it) }
            }
            // dev build 18f: "Plays as the Thor's controller" — one controller's switch, or the whole table (the app's apply,
            // before "E on"; bare = none). A change for a key gives its readers a fresh try (seatRefused); the loop decides
            "seat" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                val on = when (p.getOrNull(3)) { "1" -> true; "0" -> false; else -> return }
                if (if (on) seatKeys.add(key) else seatKeys.remove(key)) runners.values.filter { it.key == key }.forEach { it.seatRefused = false }
                // b18m: the app's word on a reader that waits for it (or its switch from its page): it is decided now
                runners.values.filter { it.key == key }.forEach { it.seatAsk = false }
                wake()
            }
            // b18m: "New controllers play as Thor's" — on, a reader that just started waits for the word above (its seat)
            "seatnew" -> {
                val on = when (p.getOrNull(2)) { "1" -> true; "0" -> false; else -> return }
                if (on == seatNew) return                               // (apply() says it every time: a Thor-only user's "0")
                seatNew = on
                if (!on) runners.values.forEach { it.seatAsk = false }
                wake()
            }
            "seats" -> {
                val t = ExtRules.keyTable(p.getOrNull(2)) ?: run { Log.w(TAG, "seats refused"); return }
                val changed = (seatKeys + t).filter { (it in seatKeys) != (it in t) }.toSet()
                seatKeys.addAll(t); seatKeys.retainAll(t)
                runners.values.filter { it.key in changed }.forEach { it.seatRefused = false }
                wake()
            }
            // dev build 18f: "Touchpad moves the pointer" (on by default) — the keys with it OFF; each reader told "t 1" / "t 0"
            "tpad" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                val on = when (p.getOrNull(3)) { "1" -> true; "0" -> false; else -> return }
                if (on) tpadOff.remove(key) else tpadOff.add(key)
                runners.values.filter { it.key == key }.forEach { touchTo(it) }
            }
            "tpadoff" -> {
                val t = ExtRules.keyTable(p.getOrNull(2)) ?: run { Log.w(TAG, "tpadoff refused"); return }
                val changed = (tpadOff + t).filter { (it in tpadOff) != (it in t) }.toSet()
                tpadOff.addAll(t); tpadOff.retainAll(t)
                runners.values.filter { it.key in changed }.forEach { touchTo(it) }
            }
            "center" -> {
                val key = p.getOrNull(2) ?: return
                runners.values.filter { it.key == key }.forEach { tell(it, "c") }
            }
            "side" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return; val on = p.getOrNull(3) == "1"
                if (on) sides.add(key) else sides.remove(key)
                runners.values.filter { it.key == key && it.single }.forEach { tell(it, "o ${if (on) 1 else 0}") }
            }
            "gyro" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                val st = p.getOrNull(3)?.toIntOrNull(); val sens = p.getOrNull(4)?.toIntOrNull(); val fl = p.getOrNull(5)?.toIntOrNull()
                if (st == null || st !in 0..2 || sens == null || sens !in 1..50 || fl == null || fl !in 0..7) return
                // dev build 16 review (B16-INT-5): the same aim again changes nothing — "i 1" + "g" restarted its motion
                // mid-game (an app rebind tells every controller again). A new reader gets it at its start; wfhid turns
                // motion back on after a reconnect itself
                val nv = if (st == 0) null else "$st $sens $fl"
                if (gyros[key] == nv) return
                if (nv == null) gyros.remove(key) else gyros[key] = nv
                runners.values.filter { it.key == key }.forEach { gyroTo(it) }
            }
            "prof" -> {
                val spec = forExternal(p.drop(2).joinToString(" "))
                if (spec.length > 1500 || !PROFILE.matches(spec)) { Log.w(TAG, "profile refused"); return }
                if (spec == profile) return
                profile = spec
                runners.values.forEach { tellProfile(it) }
            }
            // dev build 16 (WP4): its controls in ONE line — plain (its page says Plain) and its own controls ("-" none),
            // from the app's resolver (PadControls). Was "own" + "follow": two lines, two P's, and the app mirrored the pair
            // from its live prefs (critique L3)
            "uses" -> {
                val u = PadRules.parseUses(p) ?: run { Log.w(TAG, "uses refused"); return }
                if (u.plain) ownControls.add(u.key) else ownControls.remove(u.key)
                if (u.own == null) ownSpecs.remove(u.key) else ownSpecs[u.key] = u.own
                runners.values.filter { it.key == u.key }.forEach { tellProfile(it) }
            }
            // dev build 16 (theme E): a controller's own deadzone (the Test page measured it) — "-" none; the Thor's
            // every-game one (it stands in where a controller has none). Both in its controls ([tellProfile])
            "dz" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                if (p.getOrNull(3) == "-") padDz.remove(key) else {
                    val l = p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..30 } ?: return
                    val r = p.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..30 } ?: return
                    padDz[key] = l to r
                }
                runners.values.filter { it.key == key }.forEach { tellProfile(it) }
            }
            "dzbase" -> {
                val l = p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..30 } ?: return
                val r = p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..30 } ?: return
                if (dzBase == l to r) return
                dzBase = l to r
                runners.values.forEach { tellProfile(it) }
            }
            "order" -> {
                val e = when (p.getOrNull(2)) { "e" -> true; "t" -> false; else -> return }
                // (review C-APP-3: a new change needs its own "Apply now")
                if (e != extFirst) { extFirst = e; orderTries = 0; orderTriedAt = 0L; explicitUntil = 0L }
            }
            "rank" -> {                                             // E rank thor,<key>,*,<key>… — player 1 first
                val toks = p.getOrNull(2)?.split(',')?.filter { it == "thor" || it == "*" || KEY.matches(it) }?.distinct()?.take(20) ?: emptyList()
                if (toks == rank) return
                rank = toks
                extFirst = toks.isNotEmpty() && toks.indexOf("thor") != 0
                orderTries = 0; orderTriedAt = 0L; explicitUntil = 0L          // (review C-APP-3, as "order")
            }
            "combine" -> {
                val c = p.getOrNull(2) == "1"
                if (c == combine) return
                combine = c
                // separate: the pair's reader stops, the next scan starts one per Joy-Con; combine: single Joy-Cons
                // running stop, the next scan pairs them (off this thread: a stop waits for the reader). Made again at
                // once: what was removed for them stays removed.
                // Review H8: live and away ones apart — an away Joy-Con's kept pad was stopped with them and the live one
                // made again for nothing (its partner wasn't there to pair with)
                if (c) {
                    val joy = runners.values.filter { it.single && !it.stopping && (it.pids[0] == "2006" || it.pids[0] == "2007") }
                    val live = joy.filter { !it.away && it.nodes.isNotEmpty() }
                    // an away single's kept pad can't join a pair (its return would take it again as a single, and two
                    // running singles are left alone): it goes, a real give-back — back, it pairs up (pairUp) — only when
                    // the other side is there
                    val awayOnes = joy.filter { a -> a.away && a.attaching == null && joy.any { o -> o !== a && o.pids[0] != a.pids[0] } }
                    val going = if (live.map { it.pids[0] }.toSet().size == 2) live else emptyList()   // a lone live half: left alone
                    if (awayOnes.isEmpty() && going.isEmpty()) return
                    Thread { awayOnes.forEach { stop(it) }; going.forEach { stop(it, keepNodes = true) } }.apply { isDaemon = true; start() }
                } else {
                    // an away pair can never be taken back by singles: a real give-back; a live one is made again at once
                    val pairs = runners.values.filter { !it.single && !it.stopping }
                    if (pairs.isEmpty()) return
                    Thread { pairs.forEach { stop(it, keepNodes = !it.away) } }.apply { isDaemon = true; start() }
                }
            }
            "p" -> {                                                // a virtual press from Wayfinder (ExtEngine)
                val slot = p.getOrNull(2)?.takeIf { NODE.matches(it) } ?: return
                val c = p.getOrNull(3)?.toIntOrNull(); val v = p.getOrNull(4)?.toIntOrNull()
                if (c == null || c !in 0 until 0x300 || (v != 0 && v != 1)) return
                runners[slot]?.let { tell(it, "p $c $v") }
            }
            // dev build 17 (theme G): controls the app reads from every controller ("V" lines) — the D-pad while Recents is open
            // or a press is captured. A reader feeding the Thor's copy (docked) is never told: its D-pad comes through the copy
            "watch" -> {
                val t = p.drop(2).filter { it.isNotEmpty() }
                if (t.size > 4 || !t.all { WATCH.matches(it) }) { Log.w(TAG, "watch refused"); return }
                watch = ("w " + t.joinToString(" ")).trimEnd()
                runners.values.forEach { watchTo(it) }
            }
            // dev build 17 (theme G): its controls back after its Home + Back held 5 s (its page, or docked at once) — the
            // reader applied nothing meanwhile: its controls told again ("P"); its motion aim it kept itself
            "unmute" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                runners.values.filter { it.key == key }.forEach { r -> tell(r, "u"); synchronized(r) { r.toldProfile = "-" }; tellProfile(r) }
            }
            "dock" -> {                                             // docked on a TV / monitor: 1 · undocked: 0
                val d = p.getOrNull(2) == "1"
                if (d == docked) return
                docked = d
                if (!d) Thread { runCatching { synchronized(dockLock) { if (!docked) exitDock() } }.onFailure { Log.w(TAG, "undock: $it") } }.apply { isDaemon = true; start() }
                wake()
            }
            "buzz" -> {
                val key = p.getOrNull(2) ?: return
                runners.values.filter { it.key == key }.forEach { tell(it, "b 400") }
            }
            // dev build 15 (C4): the saved identity table — a controller keeps its pad's id, name and settings across
            // links (Bluetooth / USB) and reconnects
            "ident" -> {
                val id = p.getOrNull(2)?.takeIf { ExtRules.IDENT.matches(it) } ?: return
                val pid = p.getOrNull(3)?.takeIf { PID4.matches(it) }?.toInt(16) ?: return
                val key = p.getOrNull(4)?.takeIf { KEY.matches(it) } ?: return
                val title = p.drop(5).joinToString(" ")
                if (title.isEmpty() || title.length > 66 || title.any { it < ' ' || it == '\u007f' }) return
                if (idents.size < 200 || idents.containsKey(id)) idents[id] = IdEntry(pid, key, title)
            }
            // dev build 15 (C1): AYN's hold list — E hold ok|no <name> · E hold off
            "hold" -> when (p.getOrNull(2)) {
                "off" -> { holdOff = true; holdWaits.values.forEach { it.countDown() } }
                "on" -> holdOff = false                             // review H11: usable (again) — an earlier "off" ends
                "ok", "no" -> {
                    val name = p.drop(3).joinToString(" ").takeIf { ExtRules.validHoldName(it) } ?: return
                    if (p[2] == "ok") holdOk.add(name)
                    holdWaits[name]?.countDown()
                }
            }
            // dev build 15 (C5): the game app that has the controllers now (critique R7: games only; L1: "@wf" =
            // Wayfinder in front, for build 16's neutral controls) — a player-order change waits while it runs
            "fg" -> {
                val a = p.getOrNull(2) ?: return
                val now = System.currentTimeMillis()
                val wasWayfinder = fgWayfinder
                when {
                    a == "-" -> { fg = null; fgWayfinder = false }
                    a == "@wf" -> { fg = null; fgWayfinder = true }
                    a.length <= 200 && GameDetector.PKG.matches(a) -> {
                        if (a != fg) gameStartAt = 0L
                        fg = a; fgWayfinder = false
                        // review H9: never refused (the 21st game wasn't kept, and could be re-made under) — the dead ones
                        // go in keepOrder ([pruneHolders]); a hard cap, the oldest out, only past 64
                        synchronized(holders) { if (a !in holders) { if (holders.size >= 64) holders.remove(holders.keys.first()); holders[a] = now } }
                    }
                    else -> return
                }
                if (p.getOrNull(3) == "1" && fg != null) gameStartAt = now
                // dev build 16 (theme E): Wayfinder in front plays no controller's own deadzone (the Test page reads raw);
                // its controls themselves come neutral from the app's resolver ("E uses": no game, the Thor's — none here)
                if (fgWayfinder != wasWayfinder) runners.values.forEach { tellProfile(it) }
                wake()
            }
            "order-now" -> { explicitUntil = System.currentTimeMillis() + 30_000; orderTries = 0; orderTriedAt = 0L; wake() }
            // dev build 15 (C4): Forget — an away controller's kept pad goes now (the next connection makes a new one)
            "forget" -> {
                val key = p.getOrNull(2)?.takeIf { KEY.matches(it) } ?: return
                runners.values.filter { it.key == key && it.away && !it.stopping }.forEach { r -> r.stopping = true; work.execute { stop(r) } }
            }
        }
    }

    /** The game's profile for an external controller: all of it. Buttons and sticks given to Wayfinder come back as
     *  "W" / "T" lines, the shift button's layer as tagged "J" lines — played as that controller (ExtEngine sources). */
    private fun forExternal(spec: String): String = spec.split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun tell(r: Runner, line: String) =
        runCatching { synchronized(r.proc) { r.proc.outputStream.write("$line\n".toByteArray()); r.proc.outputStream.flush() } }

    /** A controller's controls now: its own for this game (the controls editor), else the game's (the Thor's), unless
     *  it doesn't follow them (plain buttons). Only when they changed for it: a "P" re-applies the reader's map, and the
     *  game's controls sent again to a controller with its own let go of a held Home (dev build 14). */
    private fun tellProfile(r: Runner) {
        synchronized(r) {
            // feeding the Thor's copy (docked): plain — the copy applies the game's controls, exactly as for the Thor.
            // dev build 15 (WP2, theme E): the game's face ("L=") only when it is like the Thor's ([FaceRules])
            // dev build 16 (theme E, critique M2): its own measured deadzone in place of the Thor's every-game one; none in
            // Wayfinder (the Test page measures raw) or docked as the Thor's controller
            // dev build 17 (critique F2): feeding, its Back never gates ([ExtRules.FEEDING_SPEC]); plain or its own controls,
            // the game's "Back goes to the game" all the same ([FaceRules.withGameBack])
            val own = ownSpecs[r.key]; val plain = r.key in ownControls
            val spec = if (r.feeding) ExtRules.FEEDING_SPEC else ExtRules.withPadDeadzone(FaceRules.profileFor(own, plain, profile,
                choiceOf(r.key), ControllerKind.ofKey(r.key), r.asThor), padDz[r.key], dzBase, plain = own == null && plain,
                raw = fgWayfinder || r.asThor)
            if (spec == r.toldProfile) return
            r.toldProfile = spec
            tell(r, "P $spec".trimEnd())
            if (BuildConfig.DEBUG) Log.d(TAG, "P ${r.slot} ${spec.take(120)}")    // dev build 16 (WP4): the device checks read it
        }
    }

    /** Feeding the Thor's copy (docked) or not, and the controls that go with it. dev build 15 (critique X-1): every
     *  change goes through here — a flag changed without telling the reader left a controller back from away (C4) with
     *  plain controls until the game's changed. */
    private fun setFeeding(r: Runner, on: Boolean) {
        r.feeding = on
        faceTo(r)                                                       // dev build 15 (WP2): feeding = the Thor's face
        watchTo(r)                                                      // dev build 17: feeding, its D-pad is the copy's
    }

    /** dev build 17 (theme G): what [r] says as "V" lines — the app's watch, none while it feeds the Thor's copy. */
    private fun watchTo(r: Runner) { tell(r, if (r.feeding) "w" else watch) }

    /** dev build 18f: whether [r]'s touchpad moves Android's pointer ("H" lines) — on unless its switch is off ([tpadOff]).
     *  wfhid moves nothing until told; a reader without a PlayStation touchpad ignores it. */
    private fun touchTo(r: Runner) { tell(r, if (r.key in tpadOff) "t 0" else "t 1") }

    private fun gyroTo(r: Runner) {
        val g = gyros[r.key]
        tell(r, if (g != null) "i 1" else "i 0")
        tell(r, "g ${g ?: "0 10 0"}")
    }

    private fun out(slot: String, line: String) {
        val clean = line.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("").take(500)
        send?.invoke("Z $slot $clean\n")
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread {
            // an "on" arriving while a stopping loop still cleans up found the thread alive and was lost: go round again.
            // Review H10: the /dev/input watch too — that loop's stopAll stopped the one the "on" had just started (one
            // thread, in order: this start comes after that stop; does nothing when it is running)
            while (true) { if (wanted) ExtNodes.start(); loop(); if (!wanted) break }
        }.apply { isDaemon = true; start() }
    }

    private fun rd(f: File) = runCatching { f.readText().trim() }.getOrNull()

    /** The gamepad among the input devices (evdev) the kernel made for this HID controller (a DualSense also has its
     *  motion sensors and touchpad): its name is the one AYN's copy carries. In number order — by name, input100 came
     *  before input99 and the controller was skipped (dev build 14). */
    private fun inputOf(hid: File): File? =
        File(hid, "device/input").listFiles()?.sortedBy { it.name.removePrefix("input").toIntOrNull() ?: Int.MAX_VALUE }
            ?.firstOrNull { isPad(it) }

    /** The HID device behind a hidraw node ("0005:054C:0CE6.000A"): its number only grows — a reconnect is a new one. */
    private fun hidOf(node: String): String =
        runCatching { File("/sys/class/hidraw/$node/device").canonicalFile.name }.getOrDefault("")

    /** A gamepad: its input device has gamepad / joystick buttons (not a Bluetooth keyboard, mouse or headset). */
    internal fun isPad(input: File): Boolean {
        val words = rd(File(input, "capabilities/key"))?.split(' ')?.reversed() ?: return false
        fun bit(code: Int): Boolean {
            val w = words.getOrNull(code / 64)?.toULongOrNull(16) ?: return false
            return (w shr (code % 64)) and 1uL == 1uL
        }
        return bit(0x130) || bit(0x120)          // BTN_GAMEPAD (BTN_SOUTH) · BTN_JOYSTICK (BTN_TRIGGER)
    }

    /** dev build 15: the loop sleeps until woken — a controller appearing (inotify), one going away ("S 0": the dock
     *  gives the Thor its controller at once), a game starting. */
    private val wakeQ = LinkedBlockingQueue<Unit>(1)
    private fun wake() { wakeQ.offer(Unit) }

    private fun loop() {
        Log.w(TAG, "on")
        bootHids.clear(); bootScan = true                       // dev build 16 (theme F): what is there now isn't new
        while (wanted) {
            runCatching { scan() }.onFailure { Log.w(TAG, "scan: $it") }
            // dev build 18f: who plays as the Thor's by its switch — BEFORE the sweep (no "player 2" said for one about to be
            // seated), under the dock lock (every "who is the Thor's" change); the player order below never holds it (it can
            // run for seconds: an undock or "E off" would wait)
            runCatching { if (screenOn && !docked) synchronized(dockLock) { if (!docked && wanted) keepSeat() } }.onFailure { Log.w(TAG, "seat: $it") }
            runCatching { if (screenOn) sweep() }.onFailure { Log.w(TAG, "sweep: $it") }
            runCatching { if (docked) synchronized(dockLock) { if (docked && wanted) keepDock() } else if (screenOn) keepOrder() }.onFailure { Log.w(TAG, "order: $it") }
            runCatching { dropAway() }.onFailure { Log.w(TAG, "drop: $it") }
            Thread.sleep(250)                                   // a burst of events: one pass
            wakeQ.poll(if (screenOn) 1750L else 4750L, TimeUnit.MILLISECONDS)
            wakeQ.clear()
        }
        // turned off while this pass was docking: the Thor's controller back (the "off" thread may have gone first)
        runCatching { synchronized(dockLock) { thorBack() } }
        stopAll()
        Log.w(TAG, "off")
    }

    /** Since when a controller waits for the Thor to be player 1 (0 = nobody waits). */
    @Volatile private var waitingSince = 0L

    /** The Thor's own controller (the input layer's copy, or AYN's pad) holds #1: an external one may come now —
     *  Android gives a new controller the lowest free number, so ours, created after it, can't take #1. */
    private fun thorIsFirst(): Boolean {
        val snap = PadLayer.snapshot()
        if (snap.isEmpty()) return true
        val foreign = AynPad.foreignNames()
        // its enabled controller has a number, and every lower one is taken (by anything, a hidden pad too): a new
        // controller can't go before it. #1 itself in the usual case; #2 in compatibility mode (AYN's hidden pad keeps #1)
        val n = snap.filter { it.gamepad && it.enabled && it.number > 0 && (it.isClone || AynPad.isAyn(File(it.path), foreign)) }
            .minOfOrNull { it.number } ?: return false
        return (1 until n).all { k -> snap.any { it.number == k } }
    }

    /** A controller connected now: its hidraw [node], settings [key], [ident]ity, the [title] its pad is named after, its
     *  evdev [name], ids, and its HID device. */
    private class Cand(val node: String, val key: String, val ident: String, val title: String, val name: String,
                       val vid: String, val pid: String, val hid: String)
    /** critique R10: controllers about to get a reader (HID device → since): what Android lists for them goes at once. */
    private val pending = ConcurrentHashMap<String, Long>()
    private val pendingDone = ConcurrentHashMap.newKeySet<String>()
    /** critique R1: candidates waiting for their other link to go (node → first seen). */
    private val deferred = ConcurrentHashMap<String, Long>()

    /** Its last refusal holds it back (connected again — a new HID device — it gets a fresh try: dev build 14). */
    private fun backedOff(key: String, dev: String): Boolean {
        if (badDev[key].let { it != null && it != dev }) { badUntil.remove(key); badCount.remove(key); badDev.remove(key) }
        return badUntil[key]?.let { System.currentTimeMillis() < it } == true
    }

    /** Start a reader for each controller that appeared (a left + right Joy-Con together when "combine" is on); one that
     *  is back takes its kept pad again. */
    private fun scan() {
        val hids = File("/sys/class/hidraw").listFiles()?.filter { NODE.matches(it.name) }
            ?.sortedBy { it.name.removePrefix("hidraw").toIntOrNull() ?: 0 } ?: return
        val busy = runners.values.flatMap { it.nodes + (it.attaching ?: emptyList()) }.toSet()
        val cands = ArrayList<Cand>()
        for (h in hids) {
            if (h.name in busy) continue
            val ev = rd(File(h, "device/uevent")) ?: continue
            val id = Regex("HID_ID=([0-9A-Fa-f]{4}):([0-9A-Fa-f]{8}):([0-9A-Fa-f]{8})").find(ev) ?: continue
            val bus = id.groupValues[1].toInt(16)
            if (bus != 0x05 && bus != 0x03) continue                    // Bluetooth or USB only
            val input = inputOf(h) ?: continue
            val name = rd(File(input, "name"))?.take(70) ?: continue
            val vid = id.groupValues[2].takeLast(4).lowercase(); val pid = id.groupValues[3].takeLast(4).lowercase()
            if (vid == "2020") continue                                  // AYN's own
            // one controller (its Bluetooth / USB serial, when it has one) — two identical controllers keep their own
            // settings; the app moves a kind's earlier settings to the first unit it sees ([ExtPadsCtl])
            val uniq = Regex("HID_UNIQ=(\\S+)").find(ev)?.groupValues?.get(1)?.trim().orEmpty()
            val key0 = "$vid$pid-" + name.replace(Regex("[^A-Za-z0-9_.+-]"), "_").take(40) + ExtRules.unit(uniq)
            // dev build 15 (C4): who it is (its serial, the same over Bluetooth and USB) → the key, name and id it had
            val ident = ExtRules.ident(vid, pid, uniq, key0)
            val e = idents[ident]
            cands += Cand(h.name, e?.key ?: key0, ident, e?.title ?: name, name, vid, pid, hidOf(h.name))
        }
        if (bootScan) { bootScan = false; cands.forEach { if (it.hid.isNotEmpty()) bootHids += it.hid } }
        val now = System.currentTimeMillis()
        // critique R10: AYN's copy of a controller about to get a reader leaves Android's list now (not after the waits
        // below); no reader within 20 s → it comes back
        // (never when no reader can run: wfhid missing or not trusted)
        val canRun = cands.isNotEmpty() && PadLayer.trustedBinary("wfhid") != null
        // review H1: not one that failed at once before (a refusal on record: its retry, after each back-off, unlinked
        // AYN's copy again — the controller left Android and came back as a new device every few minutes); it is claimed
        // once its pad exists ([owners]). backedOff first: a new HID device clears the record
        val soon = if (!canRun) emptySet() else cands.filter { it.hid.isNotEmpty() && !backedOff(it.key, it.hid) && badCount[it.key] == null }.map { it.hid }.toSet()
        pendingDone.retainAll(soon)
        var claim = false
        for ((h, since) in pending) {
            if (h !in soon) pending.remove(h)
            else if (now - since > 20_000) { pending.remove(h); pendingDone.add(h); ExtNodes.giveBack(setOf(h), wait = false) }
        }
        for (h in soon) if (h !in pendingDone && pending.putIfAbsent(h, now) == null) claim = true
        if (claim) ExtNodes.claimAll()
        deferred.keys.retainAll(cands.map { it.node }.toSet())
        if (cands.isEmpty()) { waitingSince = 0L; return }
        // critique R1: the cable plugged while its Bluetooth link is still up (or both stay up): it waits for that reader
        // to go away (then takes its kept pad) — one controller, one pad. 10 s at most, then a reader of its own
        // review H5: both links of one serial controller in the same pass — the second counts the first as live (it was
        // only decided after, by start: an attach, then a fresh reader at once for the other, two pads with one id)
        val picked = HashSet<String>()
        val ready = cands.filter { c ->
            val serial = ExtRules.hasSerial(c.ident)
            val liveSame = (serial && c.ident in picked) ||
                runners.values.any { it.ident == c.ident && (!it.away || it.attaching != null) && !it.stopping }
            val go = !ExtRules.waitHandover(serial, liveSame, deferred.getOrPut(c.node) { now }, now)
            if (go && serial) picked += c.ident
            go
        }
        // back from away (C4): its kept pad at once — nothing new is made, so nothing to wait for
        fun keptFor(c: Cand) = runners.values.firstOrNull { it.ident == c.ident && it.away && it.attaching == null && !it.attachRefused && !it.stopping && it.single }
        val (back, fresh) = ready.partition { keptFor(it) != null }
        for (c in back) start(listOf(c.node), c.key, c.ident, listOf(c.name), listOf(c.pid), c.title)
        if (fresh.isEmpty()) { waitingSince = 0L; return }
        // the Thor stays player 1 (unless the order says otherwise): wait until its own controller has #1 (after a
        // restart the input layer rebuilds its copy first) — at most 15 s, then go anyway (a Thor without its pad
        // must not block this)
        if (!extFirst && !docked) {
            // dev build 15 (C3): and the Thor's side settled — its copy is made twice (uinput, then UHID ~1.5 s later) and
            // AYN's pad may be made again after it (aynFirst): a pad of ours made in between ended up listed before the Thor's
            if (!thorIsFirst() || !PadLayer.thorQuiet()) {
                if (waitingSince == 0L) waitingSince = now
                if (now - waitingSince < 15_000) return
            }
            waitingSince = 0L
        } else waitingSince = 0L
        val list = ArrayList(fresh)
        if (combine) pairUp(list)
        val order = base()
        fun pos(key: String) = order.indexOf(key).takeIf { it >= 0 } ?: order.indexOf("*")
        for (c in list.sortedBy { pos(it.key) }) start(listOf(c.node), c.key, c.ident, listOf(c.name), listOf(c.pid), c.title)
    }

    private fun isJoyCon(c: Cand, pid: String) = c.vid == "057e" && c.pid == pid

    /** "Combine": each left Joy-Con with a right one — new ones, or one already running on its own (its reader stops
     *  first). The ones paired are taken out of [cands]. */
    private fun pairUp(cands: ArrayList<Cand>) {
        val lefts = cands.filter { isJoyCon(it, "2006") }.map { it.node to it }.toMutableList<Pair<String, Cand?>>()
        val rights = cands.filter { isJoyCon(it, "2007") }.map { it.node to it }.toMutableList<Pair<String, Cand?>>()
        if (lefts.isEmpty() && rights.isEmpty()) return
        // single Joy-Cons already running can pair with a new one (not one that is away: it has no node now)
        val names = HashMap<String, String>()
        fun running(pid: String) = runners.values.filter { it.single && it.pids[0] == pid && !it.away && it.nodes.isNotEmpty() }
        running("2006").forEach { lefts += it.nodes[0] to null; names[it.nodes[0]] = it.names[0] }
        running("2007").forEach { rights += it.nodes[0] to null; names[it.nodes[0]] = it.names[0] }
        while (lefts.isNotEmpty() && rights.isNotEmpty()) {
            val (ln, lc) = lefts.removeAt(0); val (rn, rc) = rights.removeAt(0)
            if (lc == null && rc == null) continue                      // both already running on their own: leave them
            fun runnerOf(n: String) = runners.values.firstOrNull { it.single && !it.away && it.nodes.firstOrNull() == n }
            listOfNotNull(runnerOf(ln).takeIf { lc == null }, runnerOf(rn).takeIf { rc == null }).forEach { stop(it, keepNodes = true) }
            lc?.let { cands.remove(it) }; rc?.let { cands.remove(it) }
            val nl = lc?.name ?: names[ln] ?: "Joy-Con (L)"; val nr = rc?.name ?: names[rn] ?: "Joy-Con (R)"
            start(listOf(ln, rn), PAIR_KEY, PAIR_KEY, listOf(nl, nr), listOf("2006", "2007"), idents[PAIR_KEY]?.title ?: "Joy-Con pair")
        }
    }

    /** Ask the app to put [name] on AYN's hold list; wait ≤ [ms] for its answer. True: it is on it. */
    private fun askHold(name: String, ms: Long): Boolean {
        if (name in holdOk) return true
        val l = holdWaits.getOrPut(name) { CountDownLatch(1) }
        send?.invoke("Z - H add $name\n")
        runCatching { l.await(ms, TimeUnit.MILLISECONDS) }
        holdWaits.remove(name, l)
        return name in holdOk
    }

    /** dev build 15 (C1): a pad whose name AYN's list hasn't confirmed: asked first, ≤ 1.5 s — AYN's slot is normally
     *  full with the real controller (it appears first), so a late answer is harmless. */
    private fun ensureHold(name: String) {
        if (holdOff || name in holdOk || !ExtRules.validHoldName(name)) return
        askHold(name, 1500)
    }

    private fun start(nodes: List<String>, key: String, ident: String, names: List<String>, pids: List<String>, title: String, asThor: String? = null): Runner? {
        if (nodes.isEmpty()) return null                                // review H6: a controller gone meanwhile (its nodes cleared)
        if (asThor == null) {
            // dev build 15 (C4): the same controller back (another hidraw, maybe another link): it takes its kept pad again
            runners.values.firstOrNull { it.ident == ident && it.away && it.attaching == null && !it.attachRefused && !it.stopping && it.pids.size == nodes.size }
                ?.let { attach(it, nodes, names); return it }
            // its kept pad refused it (another layout or ids): that pad goes (a real give-back), a fresh one is made
            runners.values.filter { it.ident == ident && it.attachRefused }.forEach { stop(it) }
        }
        val node = nodes[0]
        val exe = PadLayer.trustedBinary("wfhid") ?: run { Log.w(TAG, "wfhid not trusted"); return null }
        // connected again since its reader was refused (a new HID device for it): a fresh try (dev build 14)
        val dev = nodes.joinToString(",") { hidOf(it) }
        if (backedOff(key, dev)) return null                            // it failed at once lately: later
        val slot = ExtRules.slotFor(node, runners.keys.toSet()) ?: run { Log.w(TAG, "$node: no slot free"); return null }
        val startedAt = System.currentTimeMillis()
        val phys = PHYS_PREFIX + slot.removePrefix("hidraw")
        // games list every controller Android knows, a hidden one too (Eden: AYN's copy next to ours, same name):
        // ours carries "(Wayfinder)" so it's the one to pick. Docked: the Thor's controller's own name (an emulator set up
        // for it keeps its buttons). dev build 15: 79 bytes at most, cut on a character (ExtRules.padName)
        val shown = asThor?.take(79) ?: ExtRules.padName(title)
        // its own product id (AYN's buttons layout under it): Android's descriptor for it stays the same across reconnects —
        // as AYN's 2020:0111 it was a counter of the 2020:0111 devices there at the time (Eden lost it, 2026-10-03).
        // Docked as the Thor (the rename way): AYN's id, as the Thor's.
        val pid = if (asThor != null) 0x0111 else pidFor(ident, key)
        if (asThor == null) ensureHold(shown)                           // C1: on AYN's list before it exists
        val proc = runCatching { ProcessBuilder(exe, nodes.joinToString(",") { "/dev/$it" }, phys, shown, String.format("%04x", pid)).redirectErrorStream(true).start() }
            .getOrElse { Log.w(TAG, "start $slot: $it"); return null }
        val r = Runner(slot, nodes, key, ident, names, pids, phys, proc)
        r.title = title; r.pid = pid; r.shown = shown; r.asThor = asThor != null; r.connectedAt = startedAt
        r.hids = nodes.map { hidOf(it) }.filter { it.isNotEmpty() }.toSet()
        // C4: its pad stays when the controller goes away — not docked as the Thor (the Thor gets its controller back)
        if (asThor == null) tell(r, "k 1")
        // never after an old reader's "X". dev build 16 (theme F): one that was there before this loop: told as such
        val boot = r.hids.count { bootHids.remove(it) } > 0
        // b18m: "New controllers play as Thor's" on and its key not seated — it waits for the app's word on its seat (said
        // once the app has its "K" below and adopted it), set before that "K" so the answer can't come first (and before
        // runners[slot]: no pass sees it unmarked). Review 2: the wait counts from here, just before the "K" the app answers —
        // not from startedAt, taken before ensureHold's ≤ 1.5 s. A pass wakes when the wait ends (an app that never
        // answers: it goes on as its own player)
        if (asThor == null && ExtRules.seatAsks(seatNew, key in seatKeys)) {
            r.seatAskAt = System.currentTimeMillis(); r.seatAsk = true
            later.schedule(Runnable { wake() }, ExtRules.SEAT_ASK_MS + 50, TimeUnit.MILLISECONDS)
        }
        synchronized(xLock) {
            runners[slot] = r
            if (boot) send?.invoke("Z - R 1\n")
            out(slot, "K $key $title")
            if (boot) send?.invoke("Z - R 0\n")
        }
        pending.keys.removeAll(r.hids)
        if (asThor == null && pid and 0xF000 == 0xF000) {
            idents.putIfAbsent(ident, IdEntry(pid, key, title))
            val y = String.format("Y %04x %s", pid, ident)
            synchronized(r.outLock) { r.latest['Y'] = y; out(slot, y) }
        }
        tell(r, "x ${faceFor(r)}")                                     // (asThor set above: docked, the Thor's)
        maps[key]?.let { tell(r, "m $it") }
        if (r.single) smaps[key]?.let { tell(r, "M $it") }
        if (key in homeGame) tell(r, "h 0")
        if (r.single && key in sides) tell(r, "o 1")
        tellProfile(r)                                                  // (none yet: only when it has some)
        if (gyros[key] != null) gyroTo(r)
        if (key in learning) tell(r, "l 1")
        watchTo(r)                                                      // dev build 17 (theme G)
        touchTo(r)                                                      // dev build 18f: its touchpad and the pointer
        ExtNodes.claimAll()
        Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { line -> readerLine(r, line) } }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            val ran = System.currentTimeMillis() - startedAt
            // refused at once (bad arguments, no fields it can map, no pad made): 30 s, 1 min, 2… up to 10 min before
            // again. The controller going away (6, or 3 when it couldn't be opened) is a normal disconnect, not a failure:
            // turned off within 5 s, it was locked out for minutes (dev build 14). A crash (128 + its signal) is a refusal
            // too; SIGKILL / SIGTERM come from stop()'s destroy()
            if (ExtRules.refusedAtOnce(code, ran)) {
                val n = (badCount[key] ?: 0) + 1; badCount[key] = n
                badUntil[key] = System.currentTimeMillis() + ExtRules.backoffMs(n)
                badDev[key] = dev
                Log.w(TAG, "$slot: reader failed at once (exit $code) — again in ${(badUntil[key]!! - System.currentTimeMillis()) / 1000} s")
            } else if (ExtRules.ranLongEnough(ran)) { badCount.remove(key); badUntil.remove(key); badDev.remove(key) }
            runners.remove(slot, r)
            // dev build 15 (C2): a real give-back unless it is made again at once (a Joy-Con pair, the dock) — AYN's copy,
            // its own node, its touchpad back; one that can't be put back is said (the app: "turn it off and on").
            // Review P4: up to 10 s (a node can take mknod 3 s + restorecon 3 s + 1 s for Android); later still, said
            // after the "X" (the app keeps a gone controller 15 s for it) — never unbounded (dumpsys has no timeout)
            if (!r.keepNodes) ExtNodes.giveBack(r.hids, waitMs = 10_000, late = { l -> l.forEach { out(slot, "E restore $it") } })
                .forEach { out(slot, "E restore $it") }
            // re-made on this slot meanwhile (combining Joy-Cons, the L + R gesture): the app already has the new one
            synchronized(xLock) { if (runners[slot] == null) out(slot, "X $code") }
            wake()
            Log.i(TAG, "$slot stopped ($code)")
        }.apply { isDaemon = true; start() }
        Log.i(TAG, "$slot started")
        return r
    }

    /** One line from a reader. */
    private fun readerLine(r: Runner, line: String) {
        if (line.startsWith("I ") && line.contains(" nintendo ")) r.nintendo = true
        // its Home held: what it withholds from the game goes to Wayfinder's combos, as the Thor's copy does
        // tagged with this controller (its slot number + 1): the shift layer plays on it
        if (line.startsWith("J ")) {
            if (J_LINE.matches(line)) send?.invoke("$line ${(r.slot.removePrefix("hidraw").toIntOrNull() ?: 0) + 1}\n")
            return
        }
        // b18o: a turbo button its hand holds / let go (the button its pad pulses, the time) — straight to the app, tagged
        // like "J": ButtonEngine takes the pulses for no presses (a Home tap with a turbo button held goes home)
        if (line.startsWith("TU ")) {
            if (TU_LINE.matches(line)) send?.invoke("$line ${(r.slot.removePrefix("hidraw").toIntOrNull() ?: 0) + 1}\n")
            return
        }
        if (line.startsWith("G sh ")) { shoulder(r, line.endsWith("1")); return }
        // dev build 18f: its touchpad (the pointer, a tap's click, two-finger wheel) — straight to the app, never kept for a
        // replay. Screens off: nothing that moves or clicks (the trackpad mouse's click can wake the Thor); a click's release
        // always passes, so none is left held
        if (line.startsWith("H ")) {
            if (!screenOn && line != "H b 0") return
            synchronized(r.outLock) { out(r.slot, line) }
            return
        }
        // it ended learn mode itself (a minute without a press; or the controller went away): not re-sent on a reconnect
        if (line == "L 0") learning.remove(r.key)
        if (line.startsWith("R ")) {
            val n = line.substringAfter("R ").trim()
            val first = r.ourPath == null
            if (Regex("^event[0-9]{1,4}$").matches(n)) r.ourPath = "/dev/input/$n"
            r.rSeq++; r.down = false
            // review H1: its pad exists — a controller retrying after a refusal is claimed now ([owners] left it out).
            // dev build 18f: and the loop runs now — one that plays as the Thor's is seated (its pad hidden) in ≤ 0.3 s
            if (first && r.ourPath != null) { ExtNodes.claimAll(); wake() }
        }
        when {
            // dev build 15 (C4): the controller went away — its pad stays (at rest) so games keep it and its place;
            // docked, the Thor's own controller takes over at once (the loop is woken)
            line == "S 0" -> { r.idleSince = System.currentTimeMillis(); r.nodes = emptyList(); r.battery = ""; wake() }
            // …the same controller back ("a"): it drives the kept pad again
            line == "S 1" -> {
                r.attaching?.let { r.nodes = it; r.names = r.attachingNames; r.hids = r.attachingHids }
                r.attaching = null; r.idleSince = 0L; r.attachRefused = false; r.attachFails = 0
                ExtNodes.claimAll(); wake()
            }
            // refused (another layout or ids): still away — the next scan makes it a fresh pad. Review P1: open / info /
            // desc are a moment's (its hidraw node not made yet when the scan woke on its eventN, the link dropping
            // again): tried again by the next pass, refused after 3 on the same HID device — a kept pad lost its place
            // (Eden's port) for an error gone a few ms later
            line.startsWith("E attach") -> {
                r.attaching = null
                val moment = line.startsWith("E attach open") || line.startsWith("E attach info") || line.startsWith("E attach desc")
                if (moment && ++r.attachFails < 3) Log.w(TAG, "${r.slot}: taking its kept pad again failed for a moment (${line.removePrefix("E attach ")}) — again")
                else r.attachRefused = true
                wake()
            }
            line == "E pad-up timeout" -> r.down = false
        }
        // review P5: the bookkeeping the app's replay reads and this line to the app in one step ([replay] holds it too)
        synchronized(r.outLock) {
            when (line.firstOrNull()) {
                'I', 'F', 'U' -> if (line.getOrNull(1) == ' ' && r.told.size < 400) r.told.add(line)
                // dev build 17 (theme G): "M" too — an app that reconnects learns a controller's controls are off
                'D', 'R', 'O', 'B', 'P', 'S', 'M' -> if (line.getOrNull(1) == ' ') r.latest[line[0]] = line
            }
            out(r.slot, line)
        }
    }

    /** dev build 15 (C4): [r]'s controller is back on [nodes] — it takes its kept pad again (same id, same place). */
    private fun attach(r: Runner, nodes: List<String>, names: List<String>) {
        // review P1: its hidraw node not made yet (the scan woke on its eventN: hid-generic makes the input device before
        // the hidraw one) — not now: wfhid's open failed and the kept pad was thrown away. Again in 0.3 s; 3 s at most
        // (then "a" all the same: its refusal path ends in a fresh pad — never a controller left waiting)
        if (nodes.any { !File("/dev/$it").exists() }) {
            val now = System.currentTimeMillis()
            if (r.nodeWaitSince == 0L) r.nodeWaitSince = now
            if (now - r.nodeWaitSince < 3000) { later.schedule(Runnable { wake() }, 300, TimeUnit.MILLISECONDS); return }
        }
        r.nodeWaitSince = 0L
        val hids = nodes.map { hidOf(it) }.filter { it.isNotEmpty() }.toSet()
        if (hids != r.attachFailHids) { r.attachFails = 0; r.attachFailHids = hids }   // (P1: counted per HID device)
        r.attachingNames = names
        r.attachingHids = hids
        r.attaching = nodes
        r.told.removeIf { it.startsWith("I ") || it.startsWith("F ") }   // it says them again
        pending.keys.removeAll(r.attachingHids)
        Log.w(TAG, "${r.slot}: its controller is back — it takes its kept pad again")
        tell(r, "a " + nodes.joinToString(",") { "/dev/$it" })
    }

    /** What [ExtNodes] needs of the controllers handled now (and of those about to get a reader). */
    private fun owners(): List<ExtNodes.Owner> {
        if (!wanted) return emptyList()
        val now = System.currentTimeMillis()
        // review H1: a reader retrying after a refusal claims nothing until its pad exists ("R") — it failed again in
        // milliseconds and AYN's copy came back as a new device, every back-off, mid-game
        val out = runners.values.filter { it.ourPath != null || it.attaching != null || badCount[it.key] == null }.map { r ->
            // never "AYN took it" for a pad under AYN's own id (AYN never holds 2020:0111), one we're re-making, the Thor's
            val quiet = r.asThor || r.pid == 0x0111 || r.down || r.stopping || now < r.quietUntil
            ExtNodes.Owner(r.slot, r.hids + r.attachingHids.takeIf { r.attaching != null }.orEmpty(), r.ourPath?.removePrefix("/dev/input/"),
                r.phys, if (r.asThor) "" else r.shown, quiet)
        }
        return out + pending.keys.map { ExtNodes.Owner("", setOf(it), null, "", "", true) }
    }

    /**
     * dev build 15 (C1): AYN took [r]'s pad (its node deleted while it lives, or AYN's copy of it appeared — [ExtNodes]).
     * Its name goes on AYN's list again (it may have been taken off) and the pad is made again in place: AYN lets go of
     * the old one, the new one is on the list. Not confirmed, AYN's list unusable, or taken again within a minute: the
     * last resort — made again under AYN's own id (0111, which AYN never takes; the controller works, its id changed).
     */
    private fun aynTook(r: Runner) {
        // (review H7: nor while we make it again — a take queued just before keepIds re-made it)
        if (r.asThor || r.pid == 0x0111 || runners[r.slot] !== r || r.stopping || r.taking || r.down ||
            System.currentTimeMillis() < r.quietUntil) return
        r.taking = true
        try {
            val name = r.shown
            var ok = false
            if (!holdOff && ExtRules.validHoldName(name)) { holdOk.remove(name); ok = askHold(name, 2000) }
            val now = System.currentTimeMillis()
            synchronized(remakeLock) {
                if (ok && now - r.tookAt > 60_000) {
                    r.tookAt = now
                    Log.w(TAG, "${r.slot}: AYN took its pad — on AYN's list again: made again in place")
                    remakePad(r)
                } else {
                    Log.w(TAG, "${r.slot}: AYN took its pad — made again as 2020:0111 (the last resort)")
                    r.quietUntil = now + 5000
                    val before = r.rSeq
                    tell(r, "A"); r.pid = 0x0111
                    send?.invoke("Z - H broken\n")
                    val until = System.currentTimeMillis() + 3000
                    while (r.rSeq == before && System.currentTimeMillis() < until) Thread.sleep(100)
                }
            }
            // critique R5: made again, it is listed last — the pads after it go behind it again (Eden's places)
            if (!docked && screenOn) PadLayer.snapshot().takeIf { it.isNotEmpty() }?.let { keepIds(it, runners.values.filter { x -> x.ourPath != null }, force = true) }
        } finally { r.taking = false }
    }

    /**
     * L + R gesture (as on a Switch): a single LEFT Joy-Con's L and a single RIGHT Joy-Con's R held together for a
     * second = combine them. Turns "Combine Joy-Cons" on (the app keeps it, "Z - C 1"); both readers stop and the next
     * scan starts one for the pair. Sideways Joy-Cons don't use L / R, so two players holding them can do it too.
     */
    private fun shoulder(r: Runner, down: Boolean) {
        r.shoulderSince = if (down) System.currentTimeMillis() else 0L
        if (!down || combine) return
        val side = r.pids.getOrNull(0)
        val other = runners.values.firstOrNull { it.single && it.shoulderSince > 0 && it.pids[0] != side && (it.pids[0] == "2006" || it.pids[0] == "2007") }
            ?: return
        if (side != "2006" && side != "2007") return
        val a0 = r.shoulderSince; val b0 = other.shoulderSince
        Thread {
            Thread.sleep(1000)
            // both still held — and not let go in between (a new press would have moved its time)
            if (r.shoulderSince != a0 || other.shoulderSince != b0 || combine) return@Thread
            Log.w(TAG, "L + R: combining two Joy-Cons")
            combine = true
            send?.invoke("Z - C 1\n")
            stop(r, keepNodes = true); stop(other, keepNodes = true)
        }.apply { isDaemon = true; start() }
    }

    // ── dev build 18f: "Plays as the Thor's controller" undocked — the seat ──

    /** [r] may play as the Thor's: its switch on, wfpad never refused it 3 times, its pad not under AYN's id (it would share
     *  the copy's guid — [ExtRules.SeatCand]); never the dock's rename way. */
    private fun seatEligible(r: Runner) = ExtRules.seatEligible(r.key in seatKeys, r.seatRefused, r.pid, r.asThor)

    /** Who [ExtRules.seatFor] seats now (null: nobody — always nobody docked: the dock decides then). The candidates: readers
     *  whose pad exists, not stopping, minus one down for the player order mid-way (never the seat itself). */
    private fun seatPick(): Runner? {
        if (docked) return null
        val cur = seat?.takeIf { runners[it.slot] === it }
        val cands = runners.values.filter { it.ourPath != null && !it.stopping && (!it.down || it === cur) }
            .map { ExtRules.SeatCand(it.slot, it.connectedAt, it.away, seatEligible(it)) }
        return ExtRules.seatFor(cur?.slot, cands, PadLayer.layerWanted)?.let { runners[it] }
    }
    /** One about to be seated ([seatPick], not seated yet: its "R" came in after this pass's [keepSeat]) — no player number
     *  and no lights for it ([sweep]), not in the player order ([keepOrder]): the next pass seats it. */
    private fun seatPending(): Runner? = seatPick()?.takeIf { it !== seat }
    /** b18m: [r] still waits for the app's word on its seat ([Runner.seatAsk], "New controllers play as Thor's") — like one
     *  about to be seated: no player number, no lights ([sweep]), not in the player order ([keepOrder]). */
    private fun seatAsking(r: Runner) = ExtRules.seatAsking(r.seatAsk, r.seatAskAt, System.currentTimeMillis())

    /**
     * dev build 18f (b18f): undocked, a controller whose switch "Plays as the Thor's controller" is on FEEDS the Thor's
     * copy — merged with AYN's pad (wfpad "s eventN m"): games see only the copy, an emulator set up for the Thor needs no
     * change, and the Thor's own buttons keep working. Its own pad is hidden. The first connected one with it on; the others
     * are their own players. Without the input layer for the app in front: nobody (each its own player until it is back).
     * Under [dockLock]: every change of who is the Thor's goes through it ([enterDock] / [exitDock] switch in place).
     */
    private fun keepSeat() {
        // just undocked, the undock thread not through yet ([exitDock]: it hands the docked one's feed over, or gives the
        // Thor its controller back) — it decides first (a seat started now beside a docked feed was left fed and hidden)
        if (dockRunner?.let { runners[it.slot] === it } == true) return
        // review H2's rule: one back from away is shown again (an away seat stopped meanwhile was hidden: dockAway)
        runners.values.forEach { if (it.dockAway && !it.away) it.dockAway = false }
        val pick = seatPick()
        // another one takes over: the feed moves straight to it (wfpad "s <its eventN> m": the Thor's holds kept, one grab)
        // and the app hears "T <old>" → "T <new>" (its pill), never a moment with nobody
        seat?.let { if (it !== pick) stopSeat(it, moving = pick != null) }
        if (pick == null) return
        if (seat == null) { startSeat(pick); return }
        // kept. wfpad refused its feed 3 times: its own player (until its reader restarts or its switch is toggled)
        if (PadLayer.feedRefusals >= 3) {
            Log.w(TAG, "seat: ${pick.slot} refused 3 times — its own player"); pick.seatRefused = true; stopSeat(pick); return
        }
        // the feed lost or not merged (a refusal, the dock's drained switch not undone) — a copy restarting is fed again by
        // PadLayer itself, in the mode asked
        val node = pick.ourPath?.removePrefix("/dev/input/")
        if (node != null && PadLayer.copyRunning && (PadLayer.feedNode != node || !PadLayer.feedMerge)) PadLayer.feedFrom(node, merge = true)
    }

    private fun startSeat(r: Runner) {
        val node = r.ourPath?.removePrefix("/dev/input/") ?: return
        Log.w(TAG, "seat: ${r.slot}")
        PadLayer.feedRefusals = 0                                       // the seat's own count starts fresh
        setFeeding(r, true)                                             // the Thor's face (H6), no controls of its own (bg=1)
        seatHide(r)
        PadLayer.feedFrom(node, merge = true)
        seat = r
        send?.invoke("Z - T ${r.slot}\n")
        // dev build 18f2 (device test t1 BUG-1): who is seated changes the player order — its own tries now (as "E order"):
        // a pad numbered before the copy is put after it by keepOrder at once, not 20 s after the last re-make
        orderTries = 0; orderTriedAt = 0L
    }

    /** [r]'s pad hidden at once, not a sweep later: a game never sees the seat as a player of its own. */
    private fun seatHide(r: Runner) {
        PadLayer.snapshot().firstOrNull { it.path == r.ourPath && it.location == r.phys }
            ?.let { if (it.enabled && it.reader >= 0) PadLayer.setEnabled(it.reader, false) }
    }

    /** [r] no longer plays as the Thor's (replaced, its switch off, the layer off for the app, the dock, [thorBack]): its own
     *  face, controls and pad back. Review H2: an away one's kept pad (idle) stays hidden until it is back — shown, games
     *  saw an extra player appear mid-game. [moving]: another one is seated right after ([startSeat] moves the feed and says
     *  who) — the feed isn't stopped in between, nor "nobody" said. */
    private fun stopSeat(r: Runner, moving: Boolean = false) {
        Log.w(TAG, "seat: ${r.slot} no longer plays as the Thor's")
        if (!moving) PadLayer.feedFrom(null)
        if (r.away && runners[r.slot] === r) r.dockAway = true
        setFeeding(r, false)                                            // (the sweep shows its pad again, unless dockAway)
        seat = null
        PadLayer.feedRefusals = 0                                       // seat refusals never leak into the dock's rename-way rule
        if (!moving) send?.invoke("Z - T -\n")
        orderTries = 0; orderTriedAt = 0L                               // (dev build 18f2: its own player again — a new change)
    }

    /**
     * Docked: one external controller is the Thor's. The first one connected takes the Thor's controller's NAME and
     * #1 — the input layer's copy is held back ([PadLayer.dockHold]) and AYN's own pad hidden — so an emulator set up
     * for "Odin Controller" plays with it unchanged. It going away (off, out of battery) gives the Thor its controller
     * back until another one connects; undocking gives everything back ([exitDock]).
     */
    private fun keepDock() {
        // dev build 15 (C4): a controller away is gone for the dock — the Thor drives its copy until it is back
        val live = runners.values.filter { it.ourPath != null && !it.away }
        // review H2: one back that another controller's dock meanwhile replaced is a normal pad again (shown)
        runners.values.forEach { if (it.dockAway && !it.away) it.dockAway = false }
        val cur = dockRunner?.takeIf { runners[it.slot] === it && !it.away }
        if (cur == null) {
            val wasFeeding = dockRunner?.feeding == true
            // re-made, gone or away. Review H2: an away one that fed the copy stays hidden while away (sweep) — shown,
            // games saw an idle extra player appear mid-game, then go again on its return
            dockRunner?.let { if (it.feeding) { if (it.away && runners[it.slot] === it) it.dockAway = true; setFeeding(it, false); PadLayer.feedFrom(null) } }
            dockRunner = null
            // dev build 18f: the one playing as the Thor's by its switch docks in place when it is here; else (away, gone)
            // it stops FIRST (hidden while away) — it kept the Thor's face and plain controls, hidden, with nothing fed from
            // its pad, and came back dead during the dock
            val pick = seat?.takeIf { it in live } ?: live.minByOrNull { it.slot.removePrefix("hidraw").toIntOrNull() ?: 0 }
            seat?.let { if (it !== pick) stopSeat(it) }
            if (live.isEmpty()) { thorBack(); if (wasFeeding) send?.invoke("Z - Q thor\n"); return }
            enterDock(pick ?: return)
            return
        }
        if (cur.feeding) {
            // the layer turned off (for the app in front): the rename way. A copy restarting meanwhile is not that — the
            // new copy is fed again by itself (PadLayer re-sends the feed when it starts)
            // ...or wfpad refused the feed 3 times (its pad can't be opened / grabbed): the rename way until undocked
            if (!PadLayer.layerWanted || PadLayer.feedRefusals >= 3) { stopFeed(cur); dockRunner = null; return }
            val node = cur.ourPath?.removePrefix("/dev/input/")
            // dev build 18f: and a feed still merged (an in-place switch from the seat that didn't reach wfpad) is made drained
            if (node != null && PadLayer.copyRunning && (PadLayer.feedNode != node || PadLayer.feedMerge)) PadLayer.feedFrom(node)
            return
        }
        // the rename way while the layer is wanted again (an app with the layer on in front): back to feeding the copy —
        // the renamed pad goes, the copy comes back, and the next pass feeds it
        if (PadLayer.layerWanted && PadLayer.feedRefusals < 3) { Log.w(TAG, "dock: the layer is on again — back to feeding the Thor's copy"); dockRunner = null; thorBack(); stop(cur, keepNodes = true); return }
        hideThor()                                                      // AYN may re-create its pad: hidden again
    }

    private fun stopFeed(r: Runner) {
        PadLayer.feedFrom(null)
        setFeeding(r, false)
        Log.w(TAG, "dock: ${r.slot} no longer feeds the Thor's copy")
    }

    private fun thorName(): String {
        val snap = PadLayer.snapshot()
        val foreign = AynPad.foreignNames()
        return snap.firstOrNull { it.isClone && it.gamepad }?.name
            ?: snap.firstOrNull { it.gamepad && !it.location.startsWith(PHYS_PREFIX) && AynPad.isAyn(File(it.path), foreign) }?.name
            ?: "Odin Controller"
    }

    private fun hideThor() {
        val snap = PadLayer.snapshot().takeIf { it.isNotEmpty() } ?: return
        val foreign = AynPad.foreignNames()
        for (d in snap) {
            if (!d.gamepad || !d.enabled || d.reader < 0 || d.location.startsWith(PHYS_PREFIX)) continue
            if (!(d.isClone || AynPad.isAyn(File(d.path), foreign))) continue
            PadLayer.setEnabled(d.reader, false); dockHidden.add(d.reader)
            Log.w(TAG, "dock: the Thor's pad #${d.reader} hidden")
        }
    }

    private fun enterDock(r: Runner) {
        // the input layer running: the external controller FEEDS the Thor's copy — the same controller for games (name,
        // number, buttons, saved mappings), nothing re-made, a running game keeps it. Else: its pad takes the name.
        val node = r.ourPath?.removePrefix("/dev/input/")
        r.dockAway = false                                              // (review H2: it plays again)
        if (PadLayer.layerWanted && !PadLayer.isDocked && PadLayer.feedRefusals < 3 && node != null) {   // (a copy restarting is fed when it starts)
            // dev build 18f: the one playing as the Thor's by its switch, already fed — the same node switched in place to
            // the drained feed (no grab, nothing re-made, no face or controls edge). "Q dock" before "T -": the app's
            // seatPad() is never null in between (its controls, the unmute rule, the pills)
            if (r === seat && r.feeding) {
                Log.w(TAG, "dock: ${r.slot} plays as the Thor's already — its feed docks in place")
                PadLayer.feedRefusals = 0
                PadLayer.feedFrom(node, merge = false)
                dockRunner = r
                send?.invoke("Z - Q dock ${r.slot}\n")
                seat = null
                send?.invoke("Z - T -\n")
                return
            }
            Log.w(TAG, "dock: ${r.slot} feeds the Thor's copy")
            setFeeding(r, true)
            PadLayer.feedFrom(node)
            dockRunner = r
            send?.invoke("Z - Q dock ${r.slot}\n")                      // critique X-2: which one (build 16 D needs it)
            return
        }
        if (r === seat) stopSeat(r)                                     // dev build 18f: the rename way re-makes its pad
        if (r.away || r.nodes.isEmpty()) return                         // (review H6: gone already — the Thor keeps its own)
        val name = thorName()
        Log.w(TAG, "dock: ${r.slot} takes the Thor's place")
        PadLayer.dockHold(true)                                         // the copy goes (and stays away)
        Thread.sleep(300)
        hideThor()
        stop(r, keepNodes = true)
        // review H6: its controller went away meanwhile ("S 0" cleared its nodes: start threw on nodes[0], the Thor's own
        // controller held back and hidden until the next pass) — or the new reader refused: the Thor's controller back now
        val nodes = r.nodes                                             // once, after the old reader is gone
        if (nodes.isEmpty() || r.away) {
            Log.w(TAG, "dock: ${r.slot} went away meanwhile — the Thor's controller back")
            ExtNodes.giveBack(r.hids)                                   // (keepNodes skipped its give-back)
            thorBack(); return
        }
        val nr = start(nodes, r.key, r.ident, r.names, r.pids, r.title, asThor = name)
        if (nr == null) { thorBack(); return }
        dockRunner = nr
        send?.invoke("Z - Q dock ${nr.slot}\n")
    }

    /** The Thor's own controller back (its copy, AYN's pad). */
    private fun thorBack(keepSeat: Boolean = false) {
        // dev build 18f: the one playing as the Thor's by its switch stops too ("E off", exit, the loop's end) — not at an
        // undock that found no dock to end ([exitDock]: docked and undocked before the loop's dock pass, the seat is still
        // the undocked one, and its feed stays)
        if (!keepSeat) seat?.let { stopSeat(it) }
        if (PadLayer.feedOn && seat == null) PadLayer.feedFrom(null)    // also one pending for a copy restarting
        if (!PadLayer.isDocked && dockHidden.isEmpty()) return
        for (id in dockHidden) PadLayer.setEnabled(id, true)
        dockHidden.clear()
        PadLayer.dockHold(false)
        Log.w(TAG, "dock: the Thor's controller is back")
        send?.invoke("Z - Q thor\n")
    }

    /** Undocked: the controller that played as the Thor stops (the next scan starts it again under its own name, after
     *  the Thor's controller is back at #1), the Thor gets its controller back. */
    private fun exitDock() {
        PadLayer.feedRefusals = 0                                       // the next dock tries feeding again
        runners.values.forEach { it.dockAway = false }                  // review H2: undocked, an away pad is shown (C4)
        val r = dockRunner?.takeIf { runners[it.slot] === it }
        // dev build 18f: the docked one plays as the Thor's by its switch too — its feed stays, switched in place to the merged
        // one (the Thor's own buttons work again). "T <slot>" before "Q thor": the app's seatPad() never null in between
        val node = r?.ourPath?.removePrefix("/dev/input/")
        if (r != null && node != null && !r.away && r.feeding && seatEligible(r) && PadLayer.layerWanted) {
            Log.w(TAG, "undock: ${r.slot} still plays as the Thor's — its feed is merged again")
            PadLayer.feedFrom(node, merge = true)
            seat = r; dockRunner = null
            send?.invoke("Z - T ${r.slot}\n")
            send?.invoke("Z - Q thor\n")
            return
        }
        dockRunner?.let { if (it.feeding) { setFeeding(it, false); if (r == null) PadLayer.feedFrom(null) } }
        dockRunner = null
        if (r != null && PadLayer.feedOn) { stopFeed(r); send?.invoke("Z - Q thor\n"); return }   // its own pad is shown again
        thorBack(keepSeat = true)                                       // (dev build 18f: the dock's first pass already moved it)
        if (r != null) stop(r, keepNodes = true)
    }

    /** Tell the app again about a reader that is running (the app restarted; this helper didn't). Review P5: under its
     *  [Runner.outLock] — a reader line goes before it (its value replayed) or after it (the live order), never between. */
    private fun replay(r: Runner) = synchronized(r.outLock) {
        out(r.slot, "K ${r.key} ${r.title}")
        synchronized(r.told) { r.told.toList() }.forEach { out(r.slot, it) }
        r.latest.values.forEach { out(r.slot, it) }
        if (r.number > 0) out(r.slot, "N ${r.number}")
    }

    /** Stop one reader and wait for it (≤ 3 s): its pad is gone when this returns. [keepNodes]: it is made again at once
     *  (a Joy-Con pair, the dock) — what [ExtNodes] removed for it stays removed; else a real give-back. */
    private fun stop(r: Runner, keepNodes: Boolean = false) {
        r.keepNodes = keepNodes; r.stopping = true
        tell(r, "q")
        if (!runCatching { r.proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
            runCatching { r.proc.destroy(); r.proc.waitFor(1, TimeUnit.SECONDS) }
        runners.remove(r.slot, r)
        // the reader's thread gives back as it ends (then "E restore", then "X"); only a reader that didn't end here
        if (!keepNodes && runCatching { r.proc.isAlive }.getOrDefault(false)) ExtNodes.giveBack(r.hids)
    }

    /** dev build 15 (C4): a kept pad whose controller stayed away 30 minutes goes (debug builds:
     *  `setprop debug.wayfinder.extidle <seconds>`). wfhid quits → "X 0" → the app forgets it. */
    private fun dropAway() {
        val ms = idleMs(); val now = System.currentTimeMillis()
        for (r in runners.values) if (r.away && !r.stopping && now - r.idleSince > ms) {
            r.stopping = true
            Log.w(TAG, "${r.slot}: away for ${ms / 1000} s — its pad goes")
            work.execute { stop(r) }
        }
    }
    private fun idleMs(): Long {
        if (BuildConfig.DEBUG) runCatching {
            (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.wayfinder.extidle") as String)
                .toLongOrNull()?.takeIf { it in 5..86_400 }?.let { return it * 1000 }
        }
        return 30 * 60_000L
    }

    /** While a controller is ours: our pad stays enabled (hidden while it feeds the Thor's copy), its player number is
     *  said, and [ExtNodes] takes out of Android's list what it shows for it besides our pad (re-checked each pass). */
    private fun sweep() {
        val live = runners.values.filter { it.ourPath != null }          // away ones too: they keep their place
        val pass = sweeps++
        if (BuildConfig.DEBUG || pass % 30 == 0) Log.w(TAG, "sweep: ${live.size} of ${runners.size} live, screen $screenOn")
        // dev build 15 (C2): the backstop (missed events, devices Android listed late) — and C1's sign. Replaces the
        // name-only hiding, which also hid the devices of an identical controller Wayfinder didn't handle
        if (runners.isNotEmpty() || pending.isNotEmpty() || ExtNodes.hasRecords()) ExtNodes.claimAll()
        // (dev build 18f2: said again with the next one — and "none" now (review: the app reads it outside a wait too; a
        // number kept from the last controller would be the Thor's until the next one's first pass). A Thor-only user: nothing)
        if (live.isEmpty()) { if (lastThorNo > 0) send?.invoke("Z - N 0\n"); lastThorNo = -1; return }
        val snap = PadLayer.snapshot()
        if (snap.isEmpty()) return
        // dev build 18f: one about to play as the Thor's by its switch (seated by the next pass) — no player said for it
        val pending = seatPending()
        // the player it is = its place among the controllers games see (enabled, by number): a hidden pad (AYN's own, behind
        // the input layer's copy) keeps its number but isn't a player
        val players = snap.filter { it.gamepad && it.enabled && it.number > 0 }.sortedBy { it.number }
        // dev build 18f2 (device test t2 BUG-1): the Thor's own player the same way (its copy, or AYN's pad without the input
        // layer; docked none) — the app read the Thor's from the order ASKED and each controller's from this: while a new
        // order waited for a game, both said "player 2"
        val thorNo = if (docked) 0 else (players.indexOfFirst { it.isClone }.takeIf { it >= 0 }
            ?: AynPad.foreignNames().let { foreign -> players.indexOfFirst { !it.location.startsWith(PHYS_PREFIX) && AynPad.isAyn(File(it.path), foreign) } }) + 1
        if (thorNo != lastThorNo) { lastThorNo = thorNo; send?.invoke("Z - N $thorNo\n") }
        for (r in live) {
            val ours = snap.firstOrNull { it.path == r.ourPath && it.location == r.phys }
            if (ours != null) {
                // feeding the Thor's copy (docked, or by its switch): hidden — games see only the copy; otherwise always
                // shown. Review H2: one that fed it and went away stays hidden too, no player said, until it is back or
                // undocked. dev build 18f: undocked, dockAway is only set by stopSeat (an away seat) and cleared on its return
                val dockAway = r.dockAway
                if (r.feeding || dockAway) { if (ours.enabled && ours.reader >= 0) PadLayer.setEnabled(ours.reader, false) }
                else if (!ours.enabled && ours.reader >= 0) PadLayer.setEnabled(ours.reader, true)
                if (dockAway) continue                                  // (away: no battery to read either)
                val rank = players.indexOfFirst { it.path == ours.path } + 1
                if (r.feeding && r === seat && !docked) {
                    // dev build 18f: by its switch it is the Thor's copy's player (2 or later with "Connected controllers first"
                    // or a rank) — and its lights say so; the copy not listed (restarting): the number stays
                    val copy = players.indexOfFirst { it.isClone } + 1
                    if (copy > 0 && copy != r.number) { r.number = copy; out(r.slot, "N $copy"); tell(r, "L ${copy.coerceAtMost(4)}") }
                }
                else if (r.feeding) { if (r.number != 1) { r.number = 1; out(r.slot, "N 1") } }
                else if (r === pending || seatAsking(r)) { /* seated by the next pass (b18m: or the app's word on it awaited): no "player 2" for it, nor its lights */ }
                else if (rank > 0 && rank != r.number) {
                    r.number = rank; out(r.slot, "N $rank")
                    tell(r, "L ${rank.coerceAtMost(4)}")                 // Nintendo controllers: their player lights
                }
            }
            if (pass % 15 == 1 && !r.nintendo) battery(r)
        }
        logIds(snap)
    }

    /** A controller's battery from the kernel (power_supply of its HID device), as wfhid's "B" line: level 0..4
     *  (empty, low, half, good, full), charging. Nintendo controllers say it in their reports. */
    private fun battery(r: Runner) {
        val node = r.nodes.firstOrNull() ?: return                       // away: nothing to read
        val ps = File("/sys/class/hidraw/$node/device/power_supply").listFiles()?.firstOrNull() ?: return
        val cap = rd(File(ps, "capacity"))?.toIntOrNull() ?: return
        val chg = rd(File(ps, "status")) == "Charging"
        val level = when { cap <= 10 -> 0; cap <= 35 -> 1; cap <= 65 -> 2; cap <= 90 -> 3; else -> 4 }
        val b = "B $level ${if (chg) 1 else 0} 0"
        if (b != r.battery) { r.battery = b; out(r.slot, b) }
    }

    /** dev build 18f2: the Thor's own player number last said ("Z - N"; −1 = not said since the last controller went). */
    @Volatile private var lastThorNo = -1
    @Volatile private var lastIds = ""
    /** dev build 15 (C3): the identity table in the log when it changed — every game controller in Android's id order
     *  (Eden's walk), what C2 took out. No names, no serials (release-safe). */
    private fun logIds(snap: List<PadLayer.Dev>, force: Boolean = false) {
        val foreign by lazy { AynPad.foreignNames() }
        val rows = snap.filter { (it.gamepad || it.joystick) && it.reader >= 0 }.map { d ->
            val ayn = d.vendor.equals("0x2020", true) && !d.isClone && !d.location.startsWith(PHYS_PREFIX) && AynPad.isAyn(File(d.path), foreign)
            ExtRules.IdDev(d.reader, ExtRules.idKind(d.location, d.vendor, d.product, d.name, ayn) { ph -> runners.values.firstOrNull { it.phys == ph }?.slot },
                d.vendor, d.product, d.number, d.enabled)
        }
        val (removed, off) = ExtNodes.counts()
        val t = ExtRules.idTable(rows, removed, off)
        if (t == lastIds && !force) return
        lastIds = t
        Log.w(TAG, "ids: $t")
    }

    // ── dev build 15 (C5): when the player order may make pads again ──
    /** The game app that has the controllers now (null: launcher / Wayfinder / none) and whether that is Wayfinder (dev
     *  build 16: no controller's own deadzone there, [tellProfile]). */
    @Volatile private var fg: String? = null
    @Volatile private var fgWayfinder = false
    /** Apps that had the controllers since the pads were last made in order, oldest first (review H9: the ended ones
     *  pruned from 15 on — [pruneHolders] — 64 at most). */
    private val holders = LinkedHashMap<String, Long>()
    @Volatile private var prunedAt = 0L
    /** Review H9: holders whose app no longer runs go (on the loop thread: a /proc pass per holder; every 30 s at most,
     *  from 15). A dead one never blocks ([ExtRules.blocker] skips it): nothing changes but room. */
    private fun pruneHolders() {
        val now = System.currentTimeMillis()
        if (now - prunedAt < 30_000) return
        val hs = synchronized(holders) { if (holders.size < 15) return; holders.keys.toList() }
        prunedAt = now
        val f = fg
        val dead = hs.filter { it != f && GameDetector.pidsOf(it).isEmpty() }.toSet()
        if (dead.isNotEmpty()) synchronized(holders) { holders.keys.removeAll(dead) }
    }
    @Volatile private var gameStartAt = 0L
    /** The order card's "Apply now": until then a running game doesn't hold the order back. */
    @Volatile private var explicitUntil = 0L
    /** The app the order waits for (said once per change: "Z - W"). */
    @Volatile private var waitingFor: String? = null

    private fun blocker(): String? {
        val hs = synchronized(holders) { holders.keys.toList() }
        val now = System.currentTimeMillis()
        return ExtRules.blocker(hs, fg, { GameDetector.pidsOf(it).isNotEmpty() }, { pkg ->
            GameDetector.pidsOf(pkg).takeIf { it.isNotEmpty() }?.let { pids ->
                // a start it can't read counts as old: blocking is the safe side
                now - (pids.map { GameDetector.startMs(it) }.filter { it != Long.MAX_VALUE }.minOrNull() ?: 0L)
            }
        }, gameStartAt, now)
    }

    private fun waiting(pkg: String?) {
        if (pkg == waitingFor) return
        waitingFor = pkg
        send?.invoke(if (pkg != null) "Z - W 1 $pkg\n" else "Z - W 0\n")
    }

    /**
     * Player 1 as asked ([extFirst], global or the game's). Android numbers a controller when it appears, so an order
     * is made by re-creating pads in that order: an external one first = our pads are made again while the input layer's
     * copy is held back (it then comes back with the next number); the Thor first = the copy is made again (it takes
     * #1; without the layer AYN's pad is rebuilt), ours after it. An external player 1 needs the input layer: AYN's own
     * pad can't be held back. Two tries per change, 20 s apart.
     * dev build 15 (C5, D5): never while a game runs (it keeps its mapping) — the change waits for the next game start,
     * or the order card's "Apply now"; our readers keep running (each pad goes down and up in place: "n 0" / "n 1").
     * The order right: the list as games walk it is kept ([keepIds]).
     * b18o (2026-10-11): an away controller (its place kept 30 minutes) never puts the Thor behind it
     * ([ExtRules.awayAfterThor]) — the Thor keeps its number until that controller is back.
     */
    private fun keepOrder() {
        runCatching { pruneHolders() }                                  // (review H9: before the early returns)
        // away pads keep their place. dev build 18f (critique 1): never the one playing as the Thor's (feeding), an away
        // seat's hidden kept pad (dockAway) nor one about to be seated — remakeOrder's "n 0" would stop the copy's feed
        // (wfpad: its pad gone) and its "n 1" bring the pad back ENABLED (games see it); in [desired] alone, a re-make
        // every 20 s. actual, want, remakeOrder and keepIds all read this list
        // b18m: nor one still waiting for the app's word on its seat ("New controllers play as Thor's": a new controller
        // about to be seated must not re-make the order — with "Connected controllers first" the copy went to #2)
        val pending = seatPending()
        val live = runners.values.filter { it.ourPath != null && !it.feeding && !it.dockAway && it !== pending && !seatAsking(it) }
        // dev build 18f2 (device test t1 BUG-1): the one playing as the Thor's by its switch, fed with its pad hidden — kept
        // out of the list above, but Android keeps a hidden pad's number: switched on while it was numbered before the copy
        // ("Connected controllers first"), the copy stayed #2. Checked below ([ExtRules.seatBeforeCopy])
        val sat = seat?.takeIf { it.feeding && !docked && runners[it.slot] === it && it.ourPath != null && !it.stopping && PadLayer.copyRunning }
        if (live.isEmpty()) {
            // dev build 18f (review): only the one playing as the Thor's (or about to): the copy is player 1 — said, so the
            // app doesn't keep an older state (the launcher's "nolayer" while Game controls are on). No controller at all: as
            // before, nothing said
            if (seat?.feeding == true || pending != null) {
                if (lastOrder != "thor") { lastOrder = "thor"; send?.invoke("Z - Q thor\n") }
            }
            if (sat == null) { waiting(null); return }
        }
        val snap = PadLayer.snapshot().takeIf { it.isNotEmpty() } ?: return
        // its pad and the copy as Android numbers them now — only once the Thor's devices are settled (the copy is made twice,
        // AYN's pad may be made again after it: a number read mid-way would re-make for nothing)
        val seatLate = sat != null && PadLayer.thorQuiet() && ExtRules.seatBeforeCopy(
            snap.firstOrNull { it.path == sat.ourPath && it.location == sat.phys }?.number ?: 0,
            snap.filter { it.isClone && it.gamepad && it.enabled && it.number > 0 }.minOfOrNull { it.number } ?: 0)
        if (live.isEmpty() && !seatLate) { waiting(null); return }
        val foreign = AynPad.foreignNames()
        // who has which number now: our readers by their slot, the Thor's copy / AYN's pad as "thor"
        val actual = snap.filter { it.gamepad && it.enabled && it.number > 0 }.sortedBy { it.number }.mapNotNull { d ->
            live.firstOrNull { it.ourPath == d.path && it.phys == d.location }?.slot
                ?: if (!d.location.startsWith(PHYS_PREFIX) && (d.isClone || AynPad.isAyn(File(d.path), foreign))) "thor" else null
        }.distinct()
        val layer = PadLayer.copyRunning
        // b18o (2026-10-11): an away controller keeps its place but never moves the Thor back — asked before the Thor,
        // it is left out of the check (alone it never re-makes the order, a game closing included: its kept pad keeps its
        // number), and a re-make another change needs makes it right after the Thor (b18o review: also one numbered before
        // the Thor now — that re-make makes its pad again anyway); the order asked applies again once it is back
        val awayOrder = ExtRules.awayAfterThor(desired(live), live.filter { it.away }.map { it.slot }.toSet())
        val want = awayOrder.want
        val oursFirst = actual.firstOrNull()?.let { it != "thor" } == true
        val state = when {
            oursFirst -> "ext"
            extFirst && !layer -> "nolayer"
            else -> "thor"
        }
        if (state != lastOrder) { lastOrder = state; send?.invoke("Z - Q $state\n") }
        // without the input layer the Thor's pad can't be held back: only "the Thor first" (AYN's pad rebuilt) and the
        // order of the others
        val cmpA = (if (layer) actual else actual.filter { it != "thor" }).filter { it !in awayOrder.moved }
        val cmpW = (if (layer) want else want.filter { it != "thor" }).filter { it !in awayOrder.moved }
        val thorOk = layer || extFirst || !oursFirst
        // (dev build 18f2: and the seat's hidden pad after the copy)
        if (cmpA == cmpW && thorOk && !seatLate) { orderTries = 0; explicitUntil = 0L; waiting(null); keepIds(snap, live); return }   // right: the next upset gets its tries
        val now = System.currentTimeMillis()
        val b = if (now < explicitUntil) null else blocker()
        if (b != null) { waiting(b); return }                            // a game runs: it keeps its controllers
        waiting(null)
        if (orderTries >= 2 || now - orderTriedAt < 20_000) return
        orderTries++; orderTriedAt = now
        Log.w(TAG, "order: ${want.joinToString(" ")} (was ${actual.joinToString(" ")}, try $orderTries)" +
            (if (seatLate) " — ${sat!!.slot} plays as the Thor's: made again after the copy" else "") +
            if (awayOrder.moved.isNotEmpty()) " — away, kept after the Thor: ${awayOrder.moved.joinToString(" ")}" else "")
        send?.invoke("Z - M 1\n")
        try { synchronized(remakeLock) { remakeOrder(want, live, layer, actual.firstOrNull() == "thor", sat?.takeIf { seatLate }) } }
        finally { send?.invoke("Z - M 0\n") }
        // review C-APP-3: the holders still running stay (only "Apply now" passed them by: Eden in the background still
        // holds the NEXT change — cleared, a later change was made under it at once), + the game in front. Normally
        // none is alive (blocker said so): the same as clearing. "Apply now" ends with the order right, or a new change
        // (its second try, 20 s on, still inside its 30 s)
        val f = fg
        val keep = synchronized(holders) { holders.keys.toList() }.filter { it == f || GameDetector.pidsOf(it).isNotEmpty() }.toSet()
        synchronized(holders) { holders.keys.retainAll(keep); f?.let { holders.putIfAbsent(it, System.currentTimeMillis()) } }
    }

    /** The order asked, as slots and "thor" — the controllers connected now. */
    private fun desired(live: List<Runner>): List<String> {
        val out = ArrayList<String>()
        for (t in base()) when (t) {
            "thor" -> out += "thor"
            "*" -> live.filter { r -> r.key !in rank }.sortedBy { it.slot.removePrefix("hidraw").toIntOrNull() ?: 0 }.forEach { out += it.slot }
            else -> live.filter { it.key == t }.sortedBy { it.slot.removePrefix("hidraw").toIntOrNull() ?: 0 }.forEach { out += it.slot }
        }
        return out.distinct()
    }

    /** [rank] with its "others" place: given, else just before the Thor when the Thor isn't first, else at the end. */
    private fun base(): List<String> {
        val r = rank.ifEmpty { if (extFirst) listOf("*", "thor") else listOf("thor", "*") }
        if ("*" in r) return r
        val t = r.indexOf("thor")
        return if (t > 0) r.subList(0, t) + "*" + r.subList(t, r.size) else r + "*"
    }

    /** Make the controllers again in [want]'s order — Android gives each the lowest free number as it appears. dev build
     *  15 (C5): our pads go down and up in place (their readers keep running: nothing lost, no reconnect).
     *  dev build 18f2 (device test t1 BUG-1): [seatPad] = the seat's hidden pad numbered before the copy — down FIRST (its
     *  number free for the copy, which is made again), up LAST (after the copy and every player, hidden and fed again): as if
     *  it had connected with its switch on. Never in [want] / [live]. */
    private fun remakeOrder(want: List<String>, live: List<Runner>, layer: Boolean, thorFirstNow: Boolean, seatPad: Runner? = null) {
        // the copy takes a moment to be made and numbered (seen 2026-10-03): wait for it before the next one
        fun waitFor(ok: () -> Boolean) { val until = System.currentTimeMillis() + 8000; while (System.currentTimeMillis() < until && !ok()) { keepDown(); Thread.sleep(120) } }
        val early = ArrayList<String>()
        fun upAgain(slot: String) { live.firstOrNull { it.slot == slot }?.let {
            if (!it.down && it.rSeq != it.downSeq) early += it.slot         // review P3: up by itself before its turn
            up(it) } }
        downNow = live + listOfNotNull(seatPad); downToldAt = System.currentTimeMillis()
        try {
            // dev build 18f2: the seat's feed stops before its pad goes (wfpad would lose it mid-read, and a copy made again
            // meanwhile would be told to feed from a pad that isn't there: a refusal); AYN's pad alone drives the copy meanwhile
            seatPad?.let { r -> synchronized(dockLock) { if (seat === r) PadLayer.feedFrom(null) }; down(r) }
            live.forEach { down(it) }
            if (!layer) {
                // AYN's pad stays where it is — rebuilt to take #1 when the Thor is to be first
                if (!extFirst) { Thread.sleep(300); keepDown(); PadLayer.normalize() }
                Thread.sleep(300)
                want.filter { it != "thor" }.forEach { upAgain(it) }
                return
            }
            // the Thor already first and to stay first: only ours again, in order (no need to re-make the Thor's copy) — dev
            // build 18f2: unless the seat's pad holds the number the copy must take (then the copy is made again)
            if (thorFirstNow && want.firstOrNull() == "thor" && seatPad == null) {
                Thread.sleep(300)
                want.drop(1).forEach { upAgain(it) }
                return
            }
            keepDown()
            PadLayer.holdCopy(true)
            try {
                Thread.sleep(300)
                for (w in want) {
                    keepDown()
                    if (w != "thor") { upAgain(w); continue }
                    PadLayer.holdCopy(false)                             // the copy comes back now: its turn
                    waitFor { PadLayer.snapshot().any { it.isClone && it.number > 0 } }
                }
            } finally { PadLayer.holdCopy(false) }
            // dev build 18f2: the seat's pad LAST, once the Thor's devices are settled (the UHID copy is made ~1.5 s after the
            // first and AYN's pad after it: made before them, the seat's pad would be listed before the copy again)
            seatPad?.let { waitFor { PadLayer.thorQuiet() }; seatUp(it) }
        } finally {
            downNow = emptyList()
            // never a controller left without its pad (wfhid also brings it up by itself 10 s after its last "n 0" / "n r")
            live.filter { it.down }.forEach { up(it) }
            seatPad?.let { if (it.down) seatUp(it) }
            if (early.isNotEmpty()) Log.w(TAG, "order: ${early.joinToString(" ")} came up by itself before its turn (wfhid's 10 s) — the order may be off (tried again)")
        }
    }

    /** dev build 18f2 (device test t1 BUG-1): the seat's pad back after the player order's re-make — hidden at once and fed
     *  again while it still plays as the Thor's (its switch turned off meanwhile: shown, its own player — the sweep's rule).
     *  "n 1" brings a pad back enabled: hidden in the same breath, never left for the sweep. */
    private fun seatUp(r: Runner) {
        up(r)
        synchronized(dockLock) {
            if (seat !== r || !r.feeding || docked) return
            seatHide(r)
            r.ourPath?.removePrefix("/dev/input/")?.let { PadLayer.feedFrom(it, merge = true) }
        }
    }

    /** Review N2 / P3: the pads down for the player order now — "n r" (wfhid's 10 s start again; nothing once a pad is
     *  up) every 2 s while it waits: the self-heal (the net for a lost "n 1") brought one up out of turn, before the
     *  Thor's slow copy, and Eden's ports came out wrong. */
    @Volatile private var downNow: List<Runner> = emptyList()
    @Volatile private var downToldAt = 0L
    private fun keepDown() {
        val now = System.currentTimeMillis()
        if (now - downToldAt < 2000) return
        downToldAt = now
        downNow.filter { it.down }.forEach { tell(it, "n r") }
    }

    /** "n 0": its pad goes (its number is free); its reader keeps reading the controller. Waits ≤ 1 s until Android
     *  dropped it. */
    private fun down(r: Runner) {
        r.downSeq = r.rSeq
        val path = r.ourPath ?: return
        r.quietUntil = System.currentTimeMillis() + 15_000; r.down = true
        tell(r, "n 0")
        val until = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < until && PadLayer.snapshot().any { it.path == path && it.location == r.phys }) { keepDown(); Thread.sleep(100) }
    }

    /** "n 1": its pad made again (same name, id, location) — it takes the lowest free number. Waits ≤ 3 s until Android
     *  listed and numbered it. */
    private fun up(r: Runner): Boolean {
        if (!r.down) return true
        val before = r.rSeq
        tell(r, "n 1")
        val ok = waitRemade(r, before, -1)
        r.down = false
        r.quietUntil = System.currentTimeMillis() + 3000
        return ok
    }

    /** critique R6: made again = a new "R" (the eventN is usually the same one) AND Android lists it under another id
     *  than [oldReader], with a number. */
    private fun waitRemade(r: Runner, rSeq: Int, oldReader: Int): Boolean {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            if (r.rSeq != rSeq) {
                val d = PadLayer.snapshot().firstOrNull { it.path == r.ourPath && it.location == r.phys }
                if (d != null && d.reader >= 0 && d.reader != oldReader && d.number > 0) return true
            }
            keepDown()                                                  // (the player order's other pads still down)
            Thread.sleep(120)
        }
        return false
    }

    /** One pad made again back to back ("n"): it frees its number and takes it again, listed last. */
    private fun remakePad(r: Runner): Boolean {
        val old = PadLayer.snapshot().firstOrNull { it.path == r.ourPath && it.location == r.phys }?.reader ?: -1
        val before = r.rSeq
        r.quietUntil = System.currentTimeMillis() + 5000
        tell(r, "n")
        return waitRemade(r, before, old)
    }

    @Volatile private var idsBadSince = 0L
    private val idsFixes = ArrayDeque<Long>()
    @Volatile private var idsNote = ""

    /**
     * dev build 15 (C3, critique R5 / R6): the list as games walk it (Android's ids — Eden's ports count every controller
     * listed before) agrees with the order games keep (the controller numbers). The Thor's devices made again (a style
     * flip, a wfpad restart, aynFirst) or a pad of ours made again (AYN took it) are listed last: from the first one out
     * of place, our pads are made again in that order — back to back, each takes its number again. Allowed while a game
     * runs: it puts back the list the game had. 3 a minute at most (more = something re-makes the Thor's devices for
     * ever). A pad of ours listed after the Thor although it comes before it can't be fixed this way (the order card) —
     * nor any misplacement with the Thor after it (review H4).
     */
    private fun keepIds(snap: List<PadLayer.Dev>, live: List<Runner>, force: Boolean = false) {
        synchronized(remakeLock) { keepIdsNow(snap, live, force) }
    }
    private fun keepIdsNow(snap: List<PadLayer.Dev>, live: List<Runner>, force: Boolean) {
        if (docked || !screenOn || live.isEmpty()) return
        val foreign = AynPad.foreignNames()
        val thor = PadLayer.thorPaths(snap)
        val maxThor = snap.filter { it.path in thor && it.reader >= 0 }.maxOfOrNull { it.reader } ?: -1
        val seq = ArrayList<Pair<Runner?, Int>>()                         // null = the Thor (its newest device)
        var thorSeen = false
        for (d in snap.filter { it.gamepad && it.enabled && it.number > 0 }.sortedBy { it.number }) {
            val r = live.firstOrNull { it.ourPath == d.path && it.phys == d.location && !it.feeding && !it.down }
            if (r != null) { seq += r to d.reader; continue }
            if (!thorSeen && !d.location.startsWith(PHYS_PREFIX) && (d.isClone || AynPad.isAyn(File(d.path), foreign))) {
                thorSeen = true; seq += null to maxOf(maxThor, d.reader)
            }
        }
        val i = ExtRules.firstMisplaced(seq.map { it.second })
        if (i < 0) { idsBadSince = 0L; idsNote = ""; return }
        // review H4: the Thor anywhere from there on (not only at i) — a pad re-made always gets an id above the Thor's:
        // re-making ours can't put it back (B re-made behind the Thor too, for nothing, mid-game); only the copy re-made can
        if (seq.subList(i, seq.size).any { it.first == null }) {
            idsBadSince = 0L
            if (idsNote != "thor") { idsNote = "thor"; Log.w(TAG, "order: a pad before the Thor was made again — listed after it (the order card puts it back)") }
            return
        }
        if (!PadLayer.thorQuiet()) { idsBadSince = 0L; return }
        val now = System.currentTimeMillis()
        if (!force) {
            if (idsBadSince == 0L) { idsBadSince = now; return }
            if (now - idsBadSince < 1500) return
        }
        while (idsFixes.isNotEmpty() && now - idsFixes.first() > 60_000) idsFixes.removeFirst()
        if (idsFixes.size >= 3) {
            if (idsNote != "pause") { idsNote = "pause"; Log.w(TAG, "order: made again 3 times this minute — paused") }
            return
        }
        idsFixes.addLast(now)
        val suffix = seq.drop(i).mapNotNull { it.first }
        Log.w(TAG, "order: the Thor's controller or a pad was made again — ${suffix.size} pad(s) of ours go behind it again")
        suffix.forEach { remakePad(it) }
        idsBadSince = 0L; idsNote = ""
        PadLayer.snapshot().takeIf { it.isNotEmpty() }?.let { logIds(it, force = true) }
    }

    private fun stopAll() {
        for (r in runners.values) {
            r.stopping = true
            tell(r, "q")
            Thread { Thread.sleep(1000); runCatching { r.proc.destroy() } }.apply { isDaemon = true; start() }
        }
        // dev build 15 (C2): everything taken out of Android's list comes back — once, for all of them
        ExtNodes.giveBackAll()
        ExtNodes.stop()
        pending.clear(); pendingDone.clear(); deferred.clear()
    }

    /** dev build 15: a device the input layer must leave off — one [ExtNodes] switched off (a controller's touchpad or
     *  motion sensors, or a copy it may not remove), the Thor's own pad while docked. By Android id only: nothing hidden
     *  is left by name any more (AYN's copies and raw nodes are removed from the list), so the Thor's names never count. */
    @Suppress("UNUSED_PARAMETER")
    fun covers(name: String, reader: Int): Boolean = reader >= 0 && (ExtNodes.isOurs(reader) || reader in dockHidden)

    /** The app went away or was uninstalled: readers stop, everything removed comes back. */
    fun stopForExit() { wanted = false; docked = false; runCatching { synchronized(dockLock) { thorBack() } }; stopAll() }
}
