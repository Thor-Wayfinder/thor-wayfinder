package app.wayfinder

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * App side of the external controllers (the root side is [ExtPads]). Ships `wfhid`
 * (fx/wfhid.c, tools/build_wfhid.sh) to the app's files, keeps the switch and each controller's own mapping, sends
 * them to the helper whenever it (re)connects, and keeps what the page shows: the controllers connected now, their
 * fields, the mapping in use, their controller number.
 */
object ExtPadsCtl {
    private const val TAG = "ThorExtPadsCtl"
    private const val PREFS = "thor_extpads"

    class Field(val idx: Int, val kind: Char, val bits: Int, val min: Int, val max: Int, val page: Int, val usage: Int)
    class Pad(val node: String) {
        @Volatile var key = ""
        @Volatile var name = ""
        @Volatile var proto = ""
        @Volatile var pid = ""            // the (first) controller's product id (Joy-Con L 2006 / R 2007)
        val fields = ArrayList<Field>()
        @Volatile var mapping = ""        // "S=2 E=3 …" — the mapping in use (wfhid's D line)
        @Volatile var ours: String? = null
        @Volatile var number = 0
        @Volatile var sideways = false
        @Volatile var battery = -1        // 0..4 (a pair: the lower of the two), -1 unknown
        @Volatile var charging = false
        @Volatile var profileRefused = false
        @Volatile var rumbles = false     // wfhid's "U 1"
        /** dev build 15 (C4): its controller went away ("S 0") — its pad stays, at rest, its place kept 30 minutes. */
        @Volatile var away = false
        /** dev build 16 (theme F): just connected — not a reader the helper only tells again (an app or helper restart,
         *  "Z - R"), nor one made again at once: its first player number shows the ready pill ([PadPill.READY]), once. */
        @Volatile var fresh = false
        /** Its pad's product id (vendor 2020) as the helper made it ("Y": from the identity table, or the next free one) —
         *  0 until said ([padPid]). critique X-5: not [pid], which is the controller's own. */
        @Volatile var padProduct = 0
        /** Who the controller is ([ExtRules.ident]: vid + pid + its serial's hash, else its key), from "Y". */
        @Volatile var ident = ""
        /** dev build 17 (theme G): its Home + Back was held 5 s — Wayfinder's controls for it off ("M 1": plain buttons, no
         *  motion aim) until its page turns them back on ([unmute]). */
        @Volatile var muted = false
        /** dev build 18f: AYN took its pad and it was made again under AYN's id (wfhid "I ayn-took-pad 0111") — it can't play
         *  as the Thor's controller then (it would share the copy's guid): its page says why. */
        @Volatile var aynId = false
        /** dev build 15 (WP2): the (first) controller's vendor id, from wfhid's "I" line — its kind. */
        @Volatile var vid = ""
        val kind get() = ControllerKind.of(vid.toIntOrNull(16) ?: ControllerKind.vidOfKey(key), pid.toIntOrNull(16) ?: ControllerKind.pidOfKey(key), pair)
        /** No Home button: its mapping has Select and Start but no Home (wfhid's Select + Start Home). */
        val noHome get() = mapping.split(' ').let { t -> t.none { it.startsWith("HO=") } && t.any { it.startsWith("SE=") } && t.any { it.startsWith("ST=") } }
        val pair get() = key == ExtPads.PAIR_KEY
        /** A single Joy-Con (it can be held sideways). */
        val joyCon get() = !pair && proto == "nintendo" && (pid == "2006" || pid == "2007")
        val nintendo get() = proto == "nintendo"
        private val bats = IntArray(2) { -1 }
        fun battery(src: Int, level: Int, chg: Boolean) {
            if (src in 0..1) bats[src] = level
            battery = bats.filter { it >= 0 }.minOrNull() ?: -1; charging = chg
        }
        /** Away: nothing to read until it is back. */
        fun batteryGone() { bats.fill(-1); battery = -1; charging = false }
    }

    /** Who is player 1 now (the helper's "Q" line): "ext" a connected controller · "thor" · "nolayer" (a connected
     *  controller was asked for, but that needs the input layer) · "dock" (one plays as the Thor's). "" = no external
     *  controller, or a new helper that hasn't said yet — one still running says it again only when it changes. */
    @Volatile var order = ""
    /** Docked: the controller playing as the Thor's (the helper's "Q dock <slot>", critique X-2) — null otherwise. */
    @Volatile var dockSlot: String? = null
    /** dev build 18f: undocked, the controller playing as the Thor's by its switch ("Plays as the Thor's controller": the
     *  helper's "Z - T <slot>", "the seat") — null otherwise. */
    @Volatile var seatSlot: String? = null
    /** dev build 18f: the seat that ended last and when (uptime) — its own 5-second hold turned the layer off first, ending
     *  the seat, then said its "M 1" ([onLine]'s M rule). */
    @Volatile private var lastSeat: String? = null
    @Volatile private var seatEndedAt = 0L

    // ── dev build 15 (C5): the player order card's states ──
    /** The helper makes the controllers again in the asked order ("Z - M 1" … "M 0"); a minute at most (a helper that
     *  died meanwhile never says "M 0" — the arrows must not stay dead). */
    @Volatile private var makingSince = 0L
    val making get() = makingSince != 0L && android.os.SystemClock.uptimeMillis() - makingSince < 60_000
    /** The order asked waits for this app's game to end ("Z - W 1 <pkg>"); null = it doesn't wait. */
    @Volatile var orderWaits: String? = null
    /** dev build 18f2 (device test t2 BUG-1): the Thor's own player number as games see it now — the helper's "Z - N", said
     *  with the controllers' "N" (its place among the controllers games see); 0 = not said yet, or none (docked, not
     *  listed, the last controller gone): [ControllerRules.playerNo] then takes its place asked. */
    @Volatile var thorNumber = 0
    /** AYN took a controller's pad and its hold list didn't stop it ("Z - H broken", C1): that pad has AYN's id now. */
    @Volatile var holdBroken = false
    /** The order card's "Apply now": made now although a game runs (it may lose its controllers). */
    fun applyOrderNow() { InputMonitor.send("E order-now") }
    /** An away controller's page: "Forget it" — its kept pad goes now, the next connection makes a new one. */
    fun forget(key: String) { InputMonitor.send("E forget $key") }

    /** Connected controllers (slot → pad; a slot is "hidraw<N>"); the page recomposes on [version]. */
    val pads = ConcurrentHashMap<String, Pad>()
    val version = mutableIntStateOf(0)
    /** The setup wizard listens here: (hidrawN, field, value, rest). */
    @Volatile var learnListener: ((String, Int, Int, Int) -> Unit)? = null
    /** wfhid ended learn mode itself (a minute without a press: "L 0") — the wizard on that node offers Continue. */
    @Volatile var learnEnded: ((String) -> Unit)? = null
    /** dev build 16 (theme E): a button held while learning — (hidrawN, its Home 1.5 s, field): "L h <f>" / "L k <f>" (a
     *  button heard before in this session, 1 s). The wizard decides ([WizardHolds]): Cancel, "Not on it", or nothing. */
    @Volatile var learnHold: ((String, Boolean, Int) -> Unit)? = null
    /** Debug builds (a device-test hook, S9): that controller's page opens on its wizard, once. */
    @Volatile var wizardNext: String? = null
    /** dev build 17 (theme G): a controller's AYN button pressed / let go ("A": its spare button) — (its source as its presses
     *  count: 0 docked as the Thor's, down). */
    @Volatile var aynListener: ((src: Int, down: Boolean) -> Unit)? = null
    /** dev build 17 (theme G): a watched control ("V", [setWatch]) — (its raw source, type, code, value). */
    @Volatile var watchListener: ((raw: Int, type: Int, code: Int, value: Int) -> Unit)? = null

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        appCtx = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        PadProfiles.init(app)
        // dev build 15 (C1): AYN's hold list known (its self-test ran, [AynHold.init] from apply): the helper hears it
        AynHold.onReady = { if (isOn(app)) holdLines().forEach { InputMonitor.send(it) } }
        // the Thor's face-button layout changes (AYN's drawer, Wayfinder's Controller page): tell the readers once it
        // has settled. dev build 14 (lifecycle 4): not Wayfinder's own flip — every input layer start switches AYN's
        // style away and back to rebuild its pad, and the readers then had A / B swapped for 0.5 to 3 s
        runCatching {
            app.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor("temp_abxy_layout_mode"), false,
                object : android.database.ContentObserver(faceHandler) {
                    override fun onChange(selfChange: Boolean) {
                        if (aynFlipping()) return
                        faceHandler.removeCallbacks(faceCheck); faceHandler.postDelayed(faceCheck, 1000)
                    }
                })
        }
    }

    /** "n" when the Thor's face buttons are Nintendo's (A on the right), "x" for Xbox's (AYN's setting, 0 = Xbox). */
    private fun face(ctx: Context) = if (runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, "temp_abxy_layout_mode", 1) }.getOrDefault(1) == 0) "x" else "n"

    private val faceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** What the readers were told last ("E face"): a style that came back to it says nothing. */
    @Volatile private var sentFace: String? = null
    /** The style as it is now, to the readers if it changed — after Wayfinder's own flip has ended, and not while Home /
     *  Back is held (a new face made a reader let go of Home and press it again, like a new profile — combos 1). */
    private val faceCheck = object : Runnable {
        override fun run() {
            if (aynFlipping() || ButtonEngine.systemHeld) { faceHandler.postDelayed(this, 500); return }
            ButtonNames.refreshAyn()                                 // dev build 15 (WP2): the names leave the flip alone too
            val f = appCtx?.let { face(it) } ?: return
            if (f != sentFace) { sentFace = f; InputMonitor.send("E face $f") }
        }
    }
    /** Since when the input layer flips AYN's style to rebuild its pad ("Z - L 1" … "Z - L 0" from the helper), 0 = not.
     *  10 s at most: a helper that died mid-flip never says "L 0". */
    @Volatile private var aynFlipSince = 0L
    internal fun aynFlipping() = aynFlipSince != 0L && android.os.SystemClock.uptimeMillis() - aynFlipSince < 10_000

    /** "Wayfinder handles controllers" — on by default in the dev builds while the feature is tested. */
    fun isOn(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean("on", true) == true }

    fun setOn(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("on", on)?.apply()
        apply(ctx)
        // dev build 15 (C1): off — our pads are gone ("E off"), their names come off AYN's hold list (after the self-test
        // if it hasn't run: the same thread, in order); on — the names the app knows go back on it
        if (!on) { AynHold.init(ctx); AynHold.removeAll(ctx) } else AynHold.sync(ctx)
    }

    /** Opt-in: the Thor's own controller (the input layer's copy) shows "… (Wayfinder)" — off by default, since
     *  emulators keyed their saved mappings on its name. */
    fun thorNamed(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean("thor_named", false) == true }
    fun setThorNamed(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("thor_named", on)?.apply()
        InputMonitor.send("G name ${if (on) 1 else 0}")
    }

    fun savedMap(ctx: Context, key: String, sideways: Boolean = false): String? {
        init(ctx); return prefs?.getString((if (sideways) "smap_" else "map_") + key, null)
    }

    /** The user's own mapping for [key] (from the setup wizard) — a single Joy-Con has one for each way it's held;
     *  null = back to the default. */
    fun saveMap(ctx: Context, key: String, spec: String?, sideways: Boolean = false) {
        init(ctx)
        val pk = (if (sideways) "smap_" else "map_") + key
        prefs?.edit()?.apply { if (spec == null) remove(pk) else putString(pk, spec) }?.apply()
        InputMonitor.send("E ${if (sideways) "smap" else "map"} $key ${spec ?: ""}".trimEnd())
    }

    /** Its Home button is the Thor's Home (Wayfinder's combos, Home + X…) — on by default; off = the game's. */
    fun homeIsThors(ctx: Context, key: String): Boolean { init(ctx); return prefs?.getBoolean("homeg_$key", false) != true }
    fun setHomeIsThors(ctx: Context, key: String, on: Boolean) {
        init(ctx); prefs?.edit()?.apply { if (on) remove("homeg_$key") else putBoolean("homeg_$key", true) }?.apply()
        InputMonitor.send("E home $key ${if (on) 1 else 0}")
        version.intValue++                                       // review H-STATUS-STALE-HOME: its page's header, its stand-ins, the words follow
    }

    /** Build 18 review (C): the controller being set up (its key; null: none) — learning, its presses answer the wizard and
     *  drive nothing else, so the hand's look names the Thor's keys while it is the hand ([ButtonNames.handLook]). Set by
     *  [learn]; cleared by it, or when wfhid ends learn mode itself ("L 0": the wizard pauses, the controller drives again). */
    var learningKey by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    fun learn(key: String, on: Boolean) {
        if (on) learningKey = key else if (learningKey == key) learningKey = null
        InputMonitor.send("E learn $key ${if (on) 1 else 0}")
    }
    fun center(key: String) { InputMonitor.send("E center $key") }
    fun buzz(key: String) { InputMonitor.send("E buzz $key") }

    /** Its own face buttons, every app — remembered per controller (2026-10-03). dev build 15 (WP2, D1): absent =
     *  Automatic (its maker's convention; an unknown pad like the Thor's), "t" like the Thor's, "n" / "x" / "p". */
    fun faceChoice(ctx: Context, key: String): FaceChoice { init(ctx); return FaceChoice.ofStore(prefs?.getString("pface_$key", null)) }
    fun setFaceChoice(ctx: Context, key: String, c: FaceChoice) {
        init(ctx); prefs?.edit()?.apply { if (c == FaceChoice.AUTO) remove("pface_$key") else putString("pface_$key", c.store) }?.apply()
        InputMonitor.send("E pface $key ${c.wire}")
        version.intValue++                                       // its line, its names, the hints follow
    }
    /** Its face style as its page says (like the Thor's: the Thor's). */
    fun padStyle(ctx: Context, key: String): FaceStyle = FaceRules.style(faceChoice(ctx, key), kindOf(key)) ?: ButtonNames.thorStyle

    /** dev build 15 (WP2): the face positions [key]'s pad sends NOW (true = A at the bottom) — what the helper told its
     *  reader ([FaceRules], the helper's own functions): its own controls, the game's face when it is like the Thor's,
     *  else its own. dev build 16 (WP4, critique L3): mirrors exactly what the helper was told ("E uses", [sentUses]) —
     *  never the live prefs. Not docked: a docked controller's presses come as the Thor's (source 0). */
    fun outXbox(key: String): Boolean {
        val c = appCtx ?: return ButtonNames.aynXbox
        val u = sentUses[key] ?: PadRules.Uses(key, !follows(c, key), null)          // = what [apply] preloaded
        return FaceRules.out(u.own, u.plain, sentProfile.orEmpty(),
            faceChoice(c, key), kindOf(key), sentFace?.firstOrNull() ?: 'n', asThor = false) == 'x'
    }
    /** Its vocabulary ([PadLook]): names from its print (its style for unknown pads), positions as sent now. Build 18 (A15):
     *  and its own Back / AYN button's name ([spares]' Own: "touchpad click", "Mute") — a stand-in or none: null (the words
     *  say "Back" / "AYN"). */
    fun padLook(key: String): PadLook {
        val pad = pads.values.firstOrNull { it.key == key }
        val kind = kindOf(key)
        val style = appCtx?.let { padStyle(it, key) } ?: ButtonNames.thorStyle
        return PadLook(kind.print ?: style, outXbox(key), kind, pad?.pid?.toIntOrNull(16) ?: ControllerKind.pidOfKey(key),
            sideways = pad?.sideways == true && kind.single, noHome = pad?.noHome == true,
            back = pad?.let { ownSpareName(it, "BK") }, ayn = pad?.let { ownSpareName(it, "AY") })
    }
    /** Build 18 (A15): the name of [pad]'s own button that its mapping gives [target] ("BK" Back, "AY" AYN) — as [spares]'
     *  Own reads it (its field's [Vocab.spareName]); null = none of its own, or a field without a known name ([spares] says
     *  "its own button" there: in a combo the job's name, "Back", reads better). */
    private fun ownSpareName(pad: Pad, target: String): String? = ExtRules.mapTargets(pad.mapping)[target]?.let { f ->
        runCatching { pad.fields.firstOrNull { it.idx == f } }.getOrNull()?.let { Vocab.spareName(it.page, it.usage) }
    }
    /** Game controls' header on its tab: the positions it has in [gameKey] without a face of its own there. */
    fun usualXbox(key: String, gameKey: String): Boolean {
        val c = appCtx ?: return false
        return FaceRules.out(null, !follows(c, key), padProfileFor(gameKey), faceChoice(c, key), kindOf(key), sentFace?.firstOrNull() ?: 'n', false) == 'x'
    }
    /** Docked: the controller playing as the Thor's (the helper's "Q dock <slot>", critique X-2) — null otherwise. */
    fun dockedPad(): Pad? = if (order != "dock") null else dockSlot?.let { pads[it] }
    /** dev build 18f: the controller whose keys come through the Thor's copy now — docked ([dockedPad]), or undocked by its
     *  switch ("Plays as the Thor's controller", [seatSlot]); null = none. It plays the Thor's controls and face. */
    fun seatPad(): Pad? = dockedPad() ?: seatSlot?.let { pads[it] }
    /** b18n (N3-ui 3, b18m check obs. 2): [seatPad] while its controller is connected — what the words and the hand's names
     *  say plays as the Thor's ("‹name› plays as it too"). Away (its place kept 30 minutes) it plays nothing: the Thor's. */
    fun seatPadHere(): Pad? = seatPad()?.takeIf { !it.away }
    /** b18n (N3-ui 3, b18w w2): the key of source [src]'s controller while it is connected — null once it is away (its pad
     *  kept) or gone: the hand's names fall back to the Thor's ([ButtonNames.lookOfSrc]). */
    fun liveKeyOfSrc(src: Int): String? = pads["hidraw${src - 1}"]?.takeIf { !it.away }?.key?.takeIf { it.isNotEmpty() }
    /** dev build 18f: [seatPad] is the docked one (the Thor's own buttons are off) — false: by its switch, merged. */
    val seatDocked: Boolean get() = dockedPad() != null
    /** dev build 17 (review G-ENG-1): the controller playing as the Thor's own reader, as a raw source ([press]) — only while
     *  its keys count as the Thor's (it feeds the copy, or plays under the Thor's name); null otherwise. dev build 18f: by its
     *  switch too ([seatPad]). */
    fun seatRawSrc(): Int? = seatPad()?.let { srcOf(it.node) }?.takeIf { it > 0 && srcOfGated(it) == 0 }
    /** dev build 18f: the name games list for the Thor's copy (its page's "Its name in games"). */
    fun copyName(): String = if (appCtx?.let { thorNamed(it) } == true) "Odin Controller" + ExtRules.WF_SUFFIX else "Odin Controller"
    internal fun kindOf(key: String) = pads.values.firstOrNull { it.key == key }?.kind ?: ControllerKind.ofKey(key)

    /** dev build 16 (theme E): [key]'s own deadzone (left, right %), measured on the Test page — every game, in place of the
     *  Thor's every-game one ([ExtRules.withPadDeadzone]); null = none (the Thor's reaches it). Stored "sdz_<key>". */
    fun padSticks(ctx: Context, key: String): Pair<Int, Int>? {
        init(ctx)
        return ExtRules.parseSticks(prefs?.getString("sdz_$key", null))
    }
    fun setPadSticks(ctx: Context, key: String, v: Pair<Int, Int>?) {
        init(ctx)
        prefs?.edit()?.apply { if (v == null) remove("sdz_$key") else putString("sdz_$key", "${v.first} ${v.second}") }?.apply()
        InputMonitor.send(v?.let { "E dz $key ${it.first.coerceIn(0, 30)} ${it.second.coerceIn(0, 30)}" } ?: "E dz $key -")
        version.intValue++
    }
    /** The Thor's every-game deadzone to the helper (what padProfileFor merges into every game's profile): it stands in
     *  where a controller has none of its own. */
    fun sendDzBase(ctx: Context) {
        AppSettings.init(ctx)
        val d = AppSettings.stickDefaults
        InputMonitor.send("E dzbase ${d.stickL.dead.coerceIn(0, 30)} ${d.stickR.dead.coerceIn(0, 30)}")
    }

    /** A single Joy-Con held sideways (its own small pad) — remembered per kind (left / right). */
    fun sideways(ctx: Context, key: String): Boolean { init(ctx); return prefs?.getBoolean("side_$key", false) == true }
    fun setSideways(ctx: Context, key: String, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("side_$key", on)?.apply()
        InputMonitor.send("E side $key ${if (on) 1 else 0}")
    }

    /** This controller follows each game's controls (Game controls: button changes, turbo, sticks…) — on by default.
     *  dev build 16 (WP4): its page's Plain pointer (theme H; false = Plain) — its own controls are PadProfiles' entries,
     *  and what it plays comes from [PadControls.resolve]. */
    fun follows(ctx: Context, key: String): Boolean { init(ctx); return prefs?.getBoolean("own_$key", false) != true }
    fun setFollows(ctx: Context, key: String, on: Boolean) {
        init(ctx); prefs?.edit()?.apply { if (on) remove("own_$key") else putBoolean("own_$key", true) }?.apply()
        version.intValue++
        refreshOwn(ctx)                                         // dev build 16 (WP4): "E uses" from the resolver, no "E follow"
    }

    /** On a TV / monitor: a connected controller takes the Thor's place (its name, player 1; the Thor's own controller
     *  off until undocked) — on by default (plug and play: an emulator set up for the Thor plays as it is). */
    fun dockSwap(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean("dock_swap", true) == true }
    fun setDockSwap(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("dock_swap", on)?.apply()
        InputMonitor.send("E dock ${if (on && Dock.docked) 1 else 0}")
    }
    /** [Dock]: docked / undocked. */
    fun setDocked(ctx: Context, on: Boolean) { InputMonitor.send("E dock ${if (on && dockSwap(ctx)) 1 else 0}") }

    /** dev build 18f: "Plays as the Thor's controller" — [key] connected, it drives the Thor's copy undocked too (the
     *  Thor's own buttons as well): games see only the copy. Off by default (each controller its own player); stored
     *  "seat_<key>" = true when on, absent = off. */
    fun seatOn(ctx: Context, key: String): Boolean { init(ctx); return prefs?.getBoolean("seat_$key", false) == true }
    fun setSeatOn(ctx: Context, key: String, on: Boolean) {
        init(ctx); prefs?.edit()?.apply { if (on) putBoolean("seat_$key", true) else remove("seat_$key") }?.apply()
        InputMonitor.send("E seat $key ${if (on) 1 else 0}")
        version.intValue++
    }
    /** b18m (2026-10-11): "New controllers play as Thor's" (Settings for every controller) — a controller seen for
     *  the first time starts with "Plays as the Thor's controller" on ([adoptKindSettings], once per unit), so games keep
     *  seeing only the Thor's copy and an emulator bound to it once (Dolphin: "Android/<controller number>/…") needs nothing
     *  again. Off by default (each new controller its own player); a known controller keeps its own switch. Stored
     *  [ExtRules.SEAT_NEW] = true when on, absent = off. */
    fun seatNew(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean(ExtRules.SEAT_NEW, false) == true }
    fun setSeatNew(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.apply { if (on) putBoolean(ExtRules.SEAT_NEW, true) else remove(ExtRules.SEAT_NEW) }?.apply()
        InputMonitor.send("E seatnew ${if (on) 1 else 0}")
        version.intValue++
    }
    /** dev build 18f: "Touchpad moves the pointer" — a PlayStation controller's touchpad moves Android's pointer, a
     *  tap clicks, two fingers scroll. On by default; stored "tpoff_<key>" = true when OFF, absent = on. */
    fun touchPointer(ctx: Context, key: String): Boolean { init(ctx); return prefs?.getBoolean("tpoff_$key", false) != true }
    fun setTouchPointer(ctx: Context, key: String, on: Boolean) {
        init(ctx); prefs?.edit()?.apply { if (on) remove("tpoff_$key") else putBoolean("tpoff_$key", true) }?.apply()
        InputMonitor.send("E tpad $key ${if (on) 1 else 0}")
        version.intValue++
    }

    /** "Combine Joy-Cons": a left and a right one connected = ONE controller, as on a Switch. */
    fun combine(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean("combine", false) == true }
    fun setCombine(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("combine", on)?.apply()
        InputMonitor.send("E combine ${if (on) 1 else 0}")
    }

    /** Aim with motion: stick (0 off · 1 left · 2 right), sensitivity 1..50, flags (1 invert x · 2 invert y · 4 swap).
     *  dev build 16 (WP4): a data class — its page's value ([gyro]) or one game's / app's ([gyroFor], critique M4). */
    data class Gyro(val stick: Int = 0, val sens: Int = 10, val flags: Int = 0) {
        /** As stored ("gyro_<key>") and sent ("E gyro <key> <wire>"). */
        val wire get() = "$stick $sens $flags"
        companion object {
            /** A stored "stick sens flags", clamped; null = none, or not three numbers. */
            fun parse(s: String?): Gyro? = s?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
                ?.let { v -> Gyro(v[0].coerceIn(0, 2), v[1].coerceIn(1, 50), v[2].coerceIn(0, 7)) }
        }
    }
    /** Its page's motion aim (every app, every game without its own). */
    fun gyro(ctx: Context, key: String): Gyro { init(ctx); return Gyro.parse(prefs?.getString("gyro_$key", null)) ?: Gyro() }
    fun setGyro(ctx: Context, key: String, g: Gyro) {
        init(ctx)
        prefs?.edit()?.apply { if (g == Gyro()) remove("gyro_$key") else putString("gyro_$key", g.wire) }?.apply()
        version.intValue++                                      // dev build 16 review (WP6-1): Game controls' every-game motion popup re-reads it
        refreshGyro(ctx, key)                                   // dev build 16 (M4): a game's own aim in force is kept
    }
    /** dev build 16 (critique M4): [key]'s motion aim set for [base] itself — a game "pkg#id" or an app — stored beside its
     *  page's value ("gyro_<key>@<base>"); null = none there. Every game "*" has none: that is its page's value. Motion is
     *  the controller's, like its face — never part of its controls (a per-game aim doesn't make them its own). */
    fun gyroAt(ctx: Context, key: String, base: String): Gyro? {
        init(ctx)
        return if (base == PadRules.EVERY) null else Gyro.parse(prefs?.getString("gyro_$key@$base", null))
    }
    /** Its motion aim in [base] (the engine: the game running; a page: its base) — that game's, its app's, else its page's. */
    fun gyroFor(ctx: Context, key: String, base: String?): Gyro {
        for (b in PadRules.bases(base)) if (b != PadRules.EVERY) gyroAt(ctx, key, b)?.let { return it }
        return gyro(ctx, key)
    }
    /** [key]'s motion aim for [base] ([g] null = none there: its app's or its page's again); every game = its page's. */
    fun setGyroFor(ctx: Context, key: String, base: String, g: Gyro?) {
        if (base == PadRules.EVERY) { setGyro(ctx, key, g ?: Gyro()); return }
        init(ctx)
        prefs?.edit()?.apply { if (g == null) remove("gyro_$key@$base") else putString("gyro_$key@$base", g.wire) }?.apply()
        version.intValue++
        refreshGyro(ctx, key)
    }

    /** Player 1, for all apps: a connected controller (true) or the Thor (false, the default). A game can say
     *  otherwise ([AppConfig.extFirst]). */
    fun extFirst(ctx: Context): Boolean { init(ctx); return prefs?.getBoolean("ext_first", false) == true }
    fun setExtFirst(ctx: Context, on: Boolean) {
        init(ctx); prefs?.edit()?.putBoolean("ext_first", on)?.remove("rank")?.apply()
        sentOrder = null; sentRank = null; follow(ctx, lastKey)
    }

    /** The player order (Settings for every controller): "thor", controllers' keys, "*" = any other one; player 1 first.
     *  Not set yet: the Thor first or last as "A connected controller is player 1" said. */
    fun rank(ctx: Context): List<String> {
        init(ctx)
        return prefs?.getString("rank", null)?.split(',')?.filter { it.isNotEmpty() }?.takeIf { "thor" in it }
            ?: if (extFirst(ctx)) listOf("*", "thor") else listOf("thor", "*")
    }
    /** The order as the page shows it: the Thor and the controllers connected now (the others keep their place). */
    fun orderList(ctx: Context): List<String> {
        val r = rank(ctx)
        val keys = byPlayer().map { it.key }      // dev build 16 (WP7): the one player order (keyed, distinct)
        val out = ArrayList<String>()
        val others = keys.filter { it !in r }
        val withStar = if ("*" in r) r else r.indexOf("thor").let { t -> if (t > 0) r.subList(0, t) + "*" + r.subList(t, r.size) else r + "*" }
        for (t in withStar) when (t) {
            "thor" -> out += "thor"
            "*" -> out += others
            else -> if (t in keys) out += t
        }
        return out.distinct()
    }
    /** dev build 18f (review): [orderList] as games number it — without the controller playing as the Thor's by its switch
     *  (undocked: its own controller is hidden, its number is the copy's). The order card, the Thor's row and the Thor's
     *  page read this one, so they can't disagree. Docked: as [orderList] (the Thor's number is none then). */
    fun orderListShown(ctx: Context): List<String> =
        ControllerRules.shownOrder(orderList(ctx), seatPad()?.takeIf { !seatDocked }?.key)
    /** dev build 18f2 (device test t2 BUG-1): the player number every place shows for the Thor ([pad] null) or a controller —
     *  the Controller list, the controllers' pages and the order card all read this ONE source ([ControllerRules.playerNo]):
     *  what games see now, waiting or not (dev build 18f2 review). The one playing as the Thor's by its switch has the
     *  Thor's place (its number is the copy's). */
    fun playerNo(ctx: Context, pad: Pad?): ControllerRules.PlayerNo {
        val seatKey = seatPad()?.takeIf { !seatDocked }?.key
        return ControllerRules.playerNo(place = if (pad == null || pad.key == seatKey) HubPage.THOR else pad.key,
            now = pad?.number ?: thorNumber, shown = awayAfterThor(orderListShown(ctx)), waits = orderWaits != null,
            docked = dockedPad() != null, layerOff = order == "nolayer", thorRow = pad == null)
    }
    /** b18o (2026-10-11): [shown] as the helper makes it ([ExtRules.awayAfterThor], ExtPads keepOrder) — a controller
     *  that is away (each of its pads) and asked before the Thor comes right after the Thor: the "after … closes" numbers
     *  never promise the Thor a place behind it (b18o review: nor behind one numbered before it now — the re-make they
     *  announce makes its pad again; while nothing waits, every row says its number now). The order card's rows keep the
     *  order asked ([orderListShown]: it is what gets saved). */
    private fun awayAfterThor(shown: List<String>): List<String> {
        val away = pads.values.filter { it.key.isNotEmpty() }.groupBy { it.key }.filterValues { ps -> ps.all { it.away } }.keys
        return if (away.isEmpty()) shown else ExtRules.awayAfterThor(shown, away).want
    }
    /** The app a new player order waits for, by its name ([orderWaits]); null = it doesn't wait. Kept for its package: every
     *  row of the Controller list asks on each recomposition. */
    fun orderWaitsLabel(ctx: Context): String? = orderWaits?.let { pkg ->
        waitsLabel?.takeIf { it.first == pkg }?.second ?: runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            .also { waitsLabel = pkg to it } }
    @Volatile private var waitsLabel: Pair<String, String>? = null
    /** Save the page's order ([list]: "thor" and connected controllers' keys); a controller not connected keeps its
     *  place among the others (just before the Thor when the Thor isn't first, else at the end). */
    fun setOrder(ctx: Context, list: List<String>) {
        init(ctx)
        val old = rank(ctx).filter { it != "*" && it != "thor" && it !in list }
        val t = list.indexOf("thor")
        val full = if (t > 0) list.subList(0, t) + old + list.subList(t, list.size) else list + old
        prefs?.edit()?.putString("rank", full.joinToString(","))?.putBoolean("ext_first", t != 0)?.apply()
        sentOrder = null; sentRank = null; follow(ctx, lastKey)
        version.intValue++
    }
    @Volatile private var sentRank: String? = null

    @Volatile private var sentOrder: Boolean? = null
    @Volatile private var lastKey: String? = null
    /** The game (or app) that has the controller now ([GameProfiles] key): the game's player order, else its app's,
     *  else the global one. */
    fun orderFor(ctx: Context, key: String?): Boolean = overrideFor(key) ?: extFirst(ctx)
    /** The game's (or its app's) own "Player 1" choice, null = the usual order. */
    private fun overrideFor(key: String?): Boolean? = key?.let { k ->
        (if (GameProfiles.isGame(k)) GameProfiles.get(k)?.extFirst else null) ?: AppConfigStore.get(GameProfiles.pkgOf(k)).extFirst
    }
    fun follow(ctx: Context, key: String?) {
        lastKey = key
        val want = orderFor(ctx, key)
        if (want != sentOrder) { sentOrder = want; InputMonitor.send("E order ${if (want) "e" else "t"}") }
        // the whole order: the usual one, with the Thor first or last where a game / app says so
        val base = rank(ctx)
        val eff = when (overrideFor(key)) {
            true -> base.filter { it != "thor" } + "thor"
            false -> listOf("thor") + base.filter { it != "thor" }
            null -> base
        }.joinToString(",")
        if (eff != sentRank) { sentRank = eff; InputMonitor.send("E rank $eff") }
    }

    @Volatile private var gameKey: String? = null
    /** What each controller's reader was told ("E uses") and its motion aim ("E gyro") — only a change is sent. */
    private val sentUses = ConcurrentHashMap<String, PadRules.Uses>()
    private val sentGyro = ConcurrentHashMap<String, String>()
    /** dev build 16 (WP4, X7): the game RUNNING where the controllers are ([GameProfiles.runningKey]; the app while none is
     *  detected; null = no game app has them: Wayfinder, a launcher, nothing) — each controller's controls for it. */
    fun setGame(ctx: Context, key: String?) {
        if (key == gameKey) return
        gameKey = key; if (key != null) gyroGame = key
        refreshOwn(ctx)
    }
    /** The game the motion aims follow ([gyroFor]): the last one the controllers were in — Game controls over it or a
     *  launcher on the way change nothing (a new aim restarts a controller's motion: it learns its rest again). */
    @Volatile private var gyroGame: String? = null
    /** Send each connected controller its controls for the game now — [PadControls.resolve], the one answer (dev build
     *  16, WP4): "E uses" only on a change, its motion aim too. critique M11: one run at a time — the main thread and the
     *  helper's socket thread both call it, and two runs crossing left the helper a stale line that [sentUses] agreed with. */
    @Synchronized fun refreshOwn(ctx: Context) {
        init(ctx)
        val docked = seatPad()?.key                             // dev build 18f: by its switch too — the Thor's controls
        for (p in pads.values.filter { it.key.isNotEmpty() }) {
            val k = p.key
            val src = PadControls.resolve(ctx, gameKey, k, docked = k == docked)
            // ExtEngine: its own keys / macros / mouse sticks; plain: nothing of the Thor's either; else the Thor's
            when (src) {
                is PadSource.Own -> ownRemaps[srcOf(p.node)] = PadProfiles.get(src.key).remap ?: PadRemap()
                PadSource.Plain -> ownRemaps[srcOf(p.node)] = PadRemap()
                PadSource.Thor -> ownRemaps.remove(srcOf(p.node))
            }
            refreshGyro(ctx, k)
            val u = PadRules.Uses(k, src !is PadSource.Thor && !follows(ctx, k), (src as? PadSource.Own)?.let { PadControls.ownSpec(it.key) })
            if (sentUses[k] == u) continue
            sentUses[k] = u
            if (BuildConfig.DEBUG) Log.d(TAG, "uses $k ${if (u.plain) 1 else 0} ${u.own?.take(120) ?: "-"}")
            InputMonitor.send("E uses $k ${if (u.plain) 1 else 0} ${u.own ?: "-"}")
        }
    }
    /** [key]'s motion aim for the game now ([gyroFor]) to the helper, when it changed. */
    @Synchronized private fun refreshGyro(ctx: Context, key: String) {
        val w = gyroFor(ctx, key, gyroGame).wire
        if (sentGyro.put(key, w) != w) InputMonitor.send("E gyro $key $w")
    }
    /** [apply]'s first state, before the live one ([refreshOwn]): each page's motion aim, and Plain for a controller its
     *  page says so (one that connects later starts plain, as before). dev build 16 review (B16-INT-5): not for the
     *  controllers connected now — a live helper (a service rebind) took "E uses <key> 1 -" from a Plain page with its own
     *  controls for the game: its reader went plain, then back (two swaps: toggles unlatched, held outputs let go), and a
     *  game's own motion aim was switched to the page's and back. [refreshOwn] right after sends them their real state. */
    @Synchronized private fun preload() {
        sentUses.clear(); sentGyro.clear()
        val live = pads.values.mapNotNull { it.key.takeIf { k -> k.isNotEmpty() } }.toSet()
        prefs?.all?.forEach { (k, v) ->
            if (k.startsWith("gyro_") && '@' !in k && v is String) Gyro.parse(v)?.let { g ->
                val key = k.removePrefix("gyro_")
                if (key !in live) { sentGyro[key] = g.wire; InputMonitor.send("E gyro $key ${g.wire}") } }
            if (k.startsWith("own_") && v == true) k.removePrefix("own_").takeIf { it !in live }?.let { key ->
                sentUses[key] = PadRules.Uses(key, true, null); InputMonitor.send("E uses $key 1 -") }
        }
    }
    /** Each controller's own controls (by source: its hidraw number + 1) — its keys, macros, mouse sticks ([ExtEngine]). */
    val ownRemaps = ConcurrentHashMap<Int, PadRemap>()
    /** dev build 16 (WP6): the controllers by player (none yet = last), then by slot — Game controls' tabs; two with one key
     *  are one ([GameTabRules.order]). (The controller that opened Game controls comes in its intent now: pad=<key>.) */
    fun byPlayer(): List<Pad> = GameTabRules.order(pads.values.filter { it.key.isNotEmpty() }, { it.number },
        { it.node.removePrefix("hidraw").toIntOrNull() ?: 999 }, { it.key })
    fun keyOfSrc(src: Int): String? = pads["hidraw${src - 1}"]?.key?.takeIf { it.isNotEmpty() }

    @Volatile private var sentProfile: String? = null
    /** The game's controls (the input layer's profile) for external controllers too — sent when it changes. */
    fun setGameProfile(spec: String) {
        if (spec == sentProfile) return
        sentProfile = spec
        InputMonitor.send("E prof $spec".trimEnd())
    }

    /** (Re)send the state — also when the helper (re)connects. */
    fun apply(ctx: Context) {
        init(ctx)
        replaying = false                                        // dev build 16 (theme F): a new helper starts fresh
        if (!isOn(ctx)) { InputMonitor.send("E off"); return }
        if (binary(ctx) == null) return
        face(ctx).let { sentFace = it; InputMonitor.send("E face $it") }
        // read during Wayfinder's own flip: the style is read again once it's over
        if (aynFlipping()) { faceHandler.removeCallbacks(faceCheck); faceHandler.postDelayed(faceCheck, 1000) }
        // each controller's mapping, sideways mapping, Home to the game, sideways, own deadzone (dev build 18f: pure,
        // ExtRules.prefLines — the same lines in the same order, the pref-safety test reads them)
        ExtRules.prefLines(prefs?.all.orEmpty()).forEach { InputMonitor.send(it) }
        sendDzBase(ctx)                                          // dev build 16: the Thor's every-game one, where none
        preload()                                                // dev build 16 (WP4): motion aims, Plain ("E uses")
        // dev build 15 (WP2): each controller's own face, the whole table in ONE line (absent = Automatic) — a helper that
        // outlived an app restart would otherwise flip a running reader twice (Automatic, then the choice): an edge on a
        // held button. Empty = none chose (critique R14: it clears the helper's table)
        InputMonitor.send(ExtRules.pfacesLine(prefs?.all.orEmpty()))
        // dev build 18f: who may play as the Thor's controller and whose touchpad moves no pointer — whole tables, like the
        // faces (bare: none — a Thor-only user's apply sends just the two words)
        InputMonitor.send(ExtRules.tableLine("E seats", prefs?.all.orEmpty(), "seat_"))
        InputMonitor.send(ExtRules.tableLine("E tpadoff", prefs?.all.orEmpty(), "tpoff_"))
        // b18m: "New controllers play as Thor's" — always said, as "E combine" (a helper that outlived the app learns it off):
        // on, a reader that just started waits for the app's word on its seat ([adoptKindSettings], the "K" line)
        InputMonitor.send(ExtRules.seatNewLine(prefs?.all.orEmpty()))
        InputMonitor.send("E combine ${if (combine(ctx)) 1 else 0}")
        sentOrder = null; sentRank = null; follow(ctx, lastKey)
        sentProfile?.let { InputMonitor.send("E prof $it".trimEnd()) }
        refreshOwn(ctx)                                          // the connected ones: their real state, where it differs
        // dev build 15 (C4): the identity table — a controller keeps its pad's id, name and settings over Bluetooth and
        // USB, and across reconnects; (C1) AYN's hold list as far as it is known — critique R2: not known yet (its
        // self-test runs once, now) = nothing, the helper asks for each new name; (C5) the game app that has the
        // controllers, without its start flag
        identLines().forEach { InputMonitor.send(it) }
        AynHold.init(ctx)
        holdLines().forEach { InputMonitor.send(it) }
        InputMonitor.send("E fg $lastFgWire 0")
        // "E on" tells again about every controller the helper runs: start from none (no ghost of one gone meanwhile)
        forgetPads()
        makingSince = 0L; orderWaits = null                 // a helper still waiting says it again ("Z - W 1")
        InputMonitor.send(if (watching) "E watch a16 a17" else "E watch")   // dev build 17 (theme G)
        InputMonitor.send("E on")
        InputMonitor.send("E dock ${if (Dock.docked && dockSwap(ctx)) 1 else 0}")
    }

    @Volatile private var binaryChecked: String? = null
    private fun binary(ctx: Context): String? = binaryChecked?.takeIf { File(it).canExecute() } ?: runCatching {
        val f = File(ctx.filesDir, "wfhid")
        val bytes = ctx.assets.open("fx/wfhid").use { it.readBytes() }
        if (!f.exists() || !f.readBytes().contentEquals(bytes)) f.writeBytes(bytes)
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
        val mode = android.system.Os.stat(f.absolutePath).st_mode and "7777".toInt(8)
        if (mode != "700".toInt(8)) android.system.Os.chmod(f.absolutePath, "700".toInt(8))
        f.absolutePath
    }.onFailure { Log.w(TAG, "binary: $it") }.getOrNull()?.also { binaryChecked = it }

    // ── dev build 15 (C4): the identity table — prefs id_<ident> = "<pid4hex> <key> <title>" ──
    private val PID4 = Regex("^f[0-9a-f]{3}$")
    private fun identLines(): List<String> = prefs?.all.orEmpty().mapNotNull { (k, v) ->
        val id = k.removePrefix("id_").takeIf { k.startsWith("id_") && ExtRules.IDENT.matches(it) } ?: return@mapNotNull null
        val f = (v as? String)?.split(' ', limit = 3)?.takeIf { it.size == 3 && PID4.matches(it[0]) } ?: return@mapNotNull null
        "E ident $id ${f[0]} ${f[1]} ${f[2]}"
    }.take(200)

    /** C1: what the helper may know of AYN's hold list now (critique R2) — the confirmed names, "off", or nothing yet.
     *  Review H11: "on" first — a helper that outlived an app whose list was "off" (or a late OK) uses it again. */
    private fun holdLines(): List<String> = when (AynHold.state) {
        AynHold.State.OK -> listOf("E hold on") + AynHold.knownNames().filter { AynHold.valid(it) }.map { "E hold ok $it" }
        AynHold.State.UNUSABLE -> listOf("E hold off")
        AynHold.State.UNKNOWN -> emptyList()
    }

    // ── dev build 15 (C5): the game app that has the controllers ──
    /** What the helper was told last ("E fg"): only a change goes; "-" = launcher / none, "@wf" = Wayfinder in front. */
    @Volatile private var sentFg: String? = null
    @Volatile private var lastFgWire = "-"
    private var gameAppPkg: String? = null
    private var gameAppIs = false
    /**
     * The app that has the controllers now ([app]: null for none or Wayfinder; [wayfinder]: Wayfinder in front) — the
     * player order is never made again while a game runs (it keeps its controllers): the helper waits for its next start.
     * critique R7: games only ([GameApps], or a game detected in it) — Chrome or Settings in the background held every
     * order change back. L1: "@wf" for Wayfinder (build 16's neutral controls). [start]: a game was just detected in it —
     * the order may still change before the game took its controllers. Main thread ([ForegroundAppService]'s tick).
     */
    fun setForeground(ctx: Context, app: String?, wayfinder: Boolean, start: Boolean) {
        val game = app?.takeIf { GameProfiles.runningIn(it) != null || isGameApp(ctx, it) }
        val wire = when { wayfinder -> "@wf"; game != null -> game; else -> "-" }
        val stamp = if (start) game?.let { GameProfiles.gameStartedAt(it) } ?: 0L else 0L
        val s = stamp != 0L
        val k = "$wire $stamp"                                   // another game started in the same app: said again
        if (k == sentFg) return
        sentFg = k; lastFgWire = wire
        InputMonitor.send("E fg $wire ${if (s) 1 else 0}")
    }
    private fun isGameApp(ctx: Context, pkg: String): Boolean {
        if (pkg != gameAppPkg) { gameAppPkg = pkg; gameAppIs = GameApps.isGame(ctx, pkg) }
        return gameAppIs
    }

    // ── dev build 17 (theme G): Back, AYN and the D-pad of every controller ──

    @Volatile private var watching = false
    /** Connected controllers' D-pads as "V" lines while the app reads them ([ForegroundAppService] asks: Recents, a press
     *  being captured) — sent on a change, again by [apply]. */
    fun setWatch(on: Boolean) {
        if (on == watching) return
        watching = on
        InputMonitor.send(if (on) "E watch a16 a17" else "E watch")
        if (BuildConfig.DEBUG) Log.d(TAG, "watch ${if (on) 1 else 0}")
    }
    /** Its controls back after its Home + Back held 5 s (its page; docked at once — critique F4). */
    fun unmute(key: String) { if (ExtRules.KEY.matches(key)) InputMonitor.send("E unmute $key") }
    /** A virtual press into controller [src]'s reader (its counted or raw source; wfhid "p"). */
    fun press(src: Int, code: Int, v: Int) { if (src > 0) InputMonitor.send("E p hidraw${src - 1} $code $v") }
    /** Its pad is there to press on: made ("R"), not away. */
    fun canPress(src: Int): Boolean = src > 0 && pads["hidraw${src - 1}"]?.let { it.ours != null && !it.away } == true
    /** The controller [src] counts as — itself, or (0) the one playing as the Thor's (docked, or by its switch). */
    private fun padForSrc(src: Int): Pad? = if (src > 0) pads["hidraw${src - 1}"] else seatPad()
    /** What Home + Select / Home + Start stand for on controller [src] ([ExtRules.standIns]); null = neither (the Thor
     *  undocked: always). */
    internal fun standInsOf(ctx: Context, src: Int): ExtRules.StandIns? =
        padForSrc(src)?.takeIf { !it.away && it.key.isNotEmpty() }?.let { ExtRules.standIns(it.mapping, homeIsThors(ctx, it.key)) }
            ?.takeIf { it.back || it.ayn }
    fun standsIn(ctx: Context, src: Int, b: ThorButton): Boolean =
        standInsOf(ctx, src)?.let { if (b == ThorButton.BACK) it.back else b == ThorButton.AYN && it.ayn } == true
    /** Its Back and its AYN button — for its page, its status, the tour. */
    data class Spares(val back: Say.Spare, val ayn: Say.Spare)
    fun spares(ctx: Context, pad: Pad): Spares {
        val t = ExtRules.mapTargets(pad.mapping)
        fun own(target: String): Say.Spare? = t[target]?.let { f ->
            val field = runCatching { pad.fields.firstOrNull { it.idx == f } }.getOrNull()
            Say.Spare.Own(field?.let { Vocab.spareName(it.page, it.usage) } ?: "its own button")
        }
        val si = ExtRules.standIns(pad.mapping, homeIsThors(ctx, pad.key))
        return Spares(
            own("BK") ?: if (si.back) Say.Spare.StandIn(Trigger(ThorButton.SELECT, modifier = ThorButton.HOME)) else Say.Spare.None,
            own("AY") ?: if (si.ayn) Say.Spare.StandIn(Trigger(ThorButton.START, modifier = ThorButton.HOME)) else Say.Spare.None)
    }
    /** dev build 18f (review): [pad]'s Back is its touchpad's press ([ExtRules.backIsTouchClick]) — the touchpad card's words. */
    fun backIsTouchClick(pad: Pad): Boolean = ExtRules.backIsTouchClick(pad.mapping) { f ->
        runCatching { pad.fields.firstOrNull { it.idx == f } }.getOrNull()?.let { it.page to it.usage }
    }

    // ── dev build 15: pills about one controller. dev build 17 (WP10): through Overlays — about a controller, on the
    // controller's screen, for as long as its words take to read ──
    enum class PadPill { BACK, RESTORE, BROKEN, READY, MUTED }
    private val restoreSaid = ConcurrentHashMap<String, Long>()
    /** Review P4: a controller whose reader just stopped ("X") — kept 15 s for a late "E restore". */
    private val recentlyGone = ConcurrentHashMap<String, Pair<Pad, Long>>()
    fun padPill(kind: PadPill, pad: Pad?) {
        val name = pad?.name?.takeIf { it.isNotEmpty() } ?: "A controller"
        when (kind) {
            // C4: back on its kept pad (critique R15: only a change, never the replay of an app reconnecting)
            PadPill.BACK -> Overlays.pill("$name is back" + (pad?.number?.takeIf { it > 0 }?.let { " · player $it" } ?: ""), CueOrigin.Pad(pad?.key))
            // C2: AYN's copy (or its own node) couldn't be put back — once per controller, not once per device
            PadPill.RESTORE -> {
                val now = android.os.SystemClock.uptimeMillis()
                val k = pad?.node ?: ""
                if (restoreSaid[k]?.let { now - it < 10_000 } == true) return
                restoreSaid[k] = now
                Overlays.pill("$name: turn it off and on to use it without Wayfinder", CueOrigin.Pad(pad?.key))
            }
            // C1: the last resort — that pad now has AYN's id
            PadPill.BROKEN -> Overlays.pill("AYN's system took over a controller: it now uses AYN's id", CueOrigin.Pad(null))
            // dev build 16 (theme F): just connected — its name, its player and Game controls' combo in its names (when its
            // Home is the Thor's), on the controller's screen. Once; docked, the dock pill says it
            PadPill.READY -> {
                // once: its "N" (the helper's socket thread) and the 4 s fallback (main) may cross
                if (pad == null || synchronized(pad) { !pad.fresh.also { pad.fresh = false } }) return
                if (order == "dock") return
                val c = appCtx ?: return
                // dev build 18f: it plays as the Thor's controller by its switch — said so, with the combo in the seat's names
                // at the Thor's positions (its presses come through the copy: the Thor's controls), never its own look
                if (seatSlot == pad.node) {
                    Overlays.pill(Say.readySeat(name, Say.combo(ThorAction.GAME_CONTROLS, ButtonNames.lookOfSrc(0), gameKey)), CueOrigin.Pad(pad.key))
                    return
                }
                val combo = if (homeIsThors(c, pad.key)) Say.combo(ThorAction.GAME_CONTROLS, padLook(pad.key), gameKey) else null
                Overlays.pill(Say.ready(name, pad.number, combo), CueOrigin.Pad(pad.key))
            }
            // dev build 17 (theme G): its Home + Back held 5 s — its own controls off, in its names ("PS + touchpad click")
            PadPill.MUTED -> {
                val c = appCtx ?: return
                if (pad == null) return
                val back = (spares(c, pad).back as? Say.Spare.Own)?.name
                Overlays.pill(GameWords.muted(name, Say.homeAndBack(padLook(pad.key), back)), CueOrigin.Pad(pad.key))
            }
        }
    }
    /** dev build 16 (theme F): the helper tells the app again about readers it already had ("Z - R 1" … "Z - R 0": an app
     *  that reconnected, a helper that started with controllers already there) — no ready / player pill for those. */
    @Volatile private var replaying = false

    /**
     * Settings are kept per UNIT (key "<kind>+u<id>", from its serial): two identical controllers keep their own. A unit
     * seen for the first time gets the settings its kind had (earlier builds kept them per kind) — then they're its own.
     * b18m review 1: a key with no unit (a combined Joy-Con pair, [ExtPads.PAIR_KEY]; a controller with no serial, its kind's
     * key) has no kind to adopt from, but it is marked once too — its first sight counts for "New controllers play as Thor's".
     */
    private fun adoptKindSettings(ctx: Context, key: String) {
        val kind = key.substringBefore("+u").takeIf { it != key }
        if (kind == null && !ExtRules.KEY.matches(key)) return
        val pr = prefs ?: return
        // once per unit: a setting put back to its default is an ABSENT key — adopting again would bring the old one back
        if (pr.getBoolean("adopted_$key", false)) return
        val e = pr.edit().putBoolean("adopted_$key", true); var any = false
        // b18m (2026-10-11): "New controllers play as Thor's" on — this unit, seen for the first time, starts seated, in
        // the same write as its adoption: the "K" line's answer to the helper ("E seat <key> 1") comes from it, before the
        // helper numbers it (no player number first, no re-made order). A known unit (adopted already, returned above) keeps
        // its own switch; the switch off changes nothing. Review 1: a key with no unit seen before b18m (never marked then) is
        // known by its identity-table entry ("id_<key>", its ident is its key: ExtRules.firstSight) — it keeps its own switch
        val first = ExtRules.firstSight(unit = kind != null, inTable = pr.contains("id_$key"))
        val seatWas = pr.getBoolean("seat_$key", false)
        if (!seatWas && ExtRules.seatAtFirstSight(first = first, newOn = pr.getBoolean(ExtRules.SEAT_NEW, false), current = seatWas))
            e.putBoolean("seat_$key", true)
        // no unit: nothing of a kind to adopt (the key IS the kind's) — only its mark and its seat
        if (kind == null) { e.apply(); return }
        for (pre in listOf("map_", "smap_", "side_", "gyro_", "own_", "homeg_", "sdz_")) {
            val v = pr.all["$pre$kind"] ?: continue
            if (pr.contains("$pre$key")) continue
            when (v) { is String -> e.putString("$pre$key", v); is Boolean -> e.putBoolean("$pre$key", v) }
            any = true
        }
        // dev build 16 (critique M4): its kind's motion aim for a game or an app too
        for ((k, v) in pr.all) {
            if (!k.startsWith("gyro_$kind@") || v !is String) continue
            val nk = "gyro_$key@" + k.removePrefix("gyro_$kind@")
            if (!pr.contains(nk)) { e.putString(nk, v); any = true }
        }
        e.apply()
        if (PadProfiles.adoptKind(kind, key) == 0 && !any) return
        // the helper has the unit's reader running already: send what it now has (its controls and motion aim: refreshOwn)
        savedMap(ctx, key)?.let { InputMonitor.send("E map $key $it") }
        savedMap(ctx, key, true)?.let { InputMonitor.send("E smap $key $it") }
        if (sideways(ctx, key)) InputMonitor.send("E side $key 1")
        if (!homeIsThors(ctx, key)) InputMonitor.send("E home $key 0")
        padSticks(ctx, key)?.let { (l, r) -> InputMonitor.send("E dz $key $l $r") }
        sentUses.remove(key); sentGyro.remove(key); refreshOwn(ctx)
    }

    /** An external controller as a source of [ExtEngine]: its hidraw number + 1 (0 is the Thor). */
    private fun srcOf(node: String) = (node.removePrefix("hidraw").toIntOrNull() ?: -1) + 1
    init {
        // ExtEngine's pad presses for an external controller (a macro, a turbo of a Wayfinder button) go back into it
        ExtEngine.padSink = { src, code, v -> if (src > 0) InputMonitor.send("E p hidraw${src - 1} $code $v") }
    }

    /** A controller went away ([src]): the service forgets what [ButtonEngine] counted for it. */
    @Volatile var onGone: ((Int) -> Unit)? = null

    // ── which controller a press came from (dev build 14: combos are per controller) ──
    /** The name Android shows for a controller's pad — the helper's own rule (dev build 15: 79 bytes at most, cut on a
     *  character, [ExtRules.padName]). */
    private fun shown(p: Pad) = ExtRules.padName(p.name)
    /** The name games list [p] under (its page says it). */
    fun shownName(p: Pad) = shown(p)
    /** dev build 15 (C4): its pad's product id — the helper's ("Y"), else the seed formula until it said ([extPadProduct]). */
    private fun padPid(p: Pad) = p.padProduct.takeIf { it != 0 } ?: extPadProduct(p.key)
    /** The source a controller's presses count as: its own — the lowest of the ones with its key (identical controllers
     *  without a serial share one product id, nothing tells them apart). */
    private fun counted(p: Pad): Int = pads.values.filter { it.key == p.key }.minOfOrNull { srcOf(it.node) } ?: srcOf(p.node)
    /** Input device id → source, worked out once per device (cleared whenever the controllers change). */
    private val devSrc = ConcurrentHashMap<Int, Int>()

    /** The controller a KEY came from, as an [ExtEngine] source: a connected controller's pad, else 0 — the Thor (its copy,
     *  AYN's pad, or a controller docked as the Thor's). dev build 14 (review): by its pad's product id — by the name, two
     *  DualSenses were one controller (one player's PS + the other's △ fired Game controls and took the △). */
    fun srcOfDevice(dev: android.view.InputDevice?): Int {
        if (dev == null || pads.isEmpty() || dev.vendorId != 0x2020) return 0
        devSrc[dev.id]?.let { return it }
        if (dev.productId and 0xF000 == 0xF000) {
            // its own product id ([padPid]: the identity table's since dev build 15); two sharing one are told apart by name
            val m = pads.values.filter { it.key.isNotEmpty() && padPid(it) == dev.productId }
            val s = m.filter { shown(it) == dev.name }.ifEmpty { m }.minOfOrNull { srcOf(it.node) } ?: return 0
            devSrc[dev.id] = s
            return s
        }
        // under AYN's id: a controller's pad made again so (AYN took it, no buttons layout) — by its name — or the Thor's
        // (its copy, AYN's pad; the copy named "… (Wayfinder)" too when that's on). A controller whose pad isn't made yet
        // ("R"), or is listed under its own id, isn't this one. Only "no controller of that name" is kept: from its "K"
        // to its pad, a Thor key was kept as the controller's, and the Thor's Home + X stopped working
        val named = pads.values.filter { it.key.isNotEmpty() && shown(it) == dev.name }
        if (named.isEmpty()) { devSrc[dev.id] = 0; return 0 }
        val listed = android.view.InputDevice.getDeviceIds().asList().mapNotNull { id ->
            android.view.InputDevice.getDevice(id)?.takeIf { id != dev.id && it.vendorId == 0x2020 }?.productId }.toSet()
        return named.filter { it.ours != null && padPid(it) !in listed }.minOfOrNull { srcOf(it.node) } ?: 0
    }

    /** The controller of a withheld press ("J" lines: its hidraw number + 1) as its keys count it ([srcOfDevice]). Docked,
     *  the one playing as the Thor's (it feeds the copy — its own pad hidden — or its pad carries the Thor's name) is 0.
     *  dev build 18f: undocked, the one playing as the Thor's by its switch too ([ExtRules.countsAsSeat]). */
    fun srcOfGated(src: Int): Int {
        if (src <= 0) return src
        val pad = pads["hidraw${src - 1}"] ?: return src
        return if (ExtRules.countsAsSeat(pad.node, order, seatSlot) { ownPadShown(pad) }) 0 else counted(pad)
    }

    /** [p]'s own pad is listed and on: under its own product id, or made again under AYN's with its name. */
    private fun ownPadShown(p: Pad): Boolean {
        val pid = padPid(p); val name = shown(p)
        return android.view.InputDevice.getDeviceIds().any { id -> android.view.InputDevice.getDevice(id)?.let {
            it.isEnabled && it.vendorId == 0x2020 && (it.productId == pid || it.productId and 0xF000 != 0xF000 && it.name == name) } == true }
    }

    /** The readers whose presses count as [src]: [src] itself, the others of its key and, docked, the one playing as the
     *  Thor's (0). What they play of Wayfinder's (W lines: keys, macros, mouse) runs under their own source. */
    fun rawSrcs(src: Int): List<Int> = listOf(src) + pads.values.map { srcOf(it.node) }.filter { it != src && srcOfGated(it) == src }

    /** The root helper is gone ([InputMonitor]): its readers went with it. dev build 14 (review): the player order only
     *  goes here — a new helper tells it again ("Q"), one still running never does (a rebind's "E on" replays no "Q"):
     *  cleared there, a docked controller's withheld △ no longer paired with the Thor's Home it plays as. */
    fun helperLost() {
        aynFlipSince = 0L; replaying = false
        if (order.isNotEmpty()) { order = ""; version.intValue++ }
        // dev build 15: its order work and its pads went with it (a new helper makes every pad again, with its own id).
        // dev build 18f: the seat too (a running helper replays it inside "Z - R"; a new one seats again itself)
        dockSlot = null; seatSlot = null; makingSince = 0L; orderWaits = null; holdBroken = false; thorNumber = 0
        forgetPads()
    }

    /** Every controller forgotten — the helper (re)tells the live ones ("E on" replays them). dev build 14 (lifecycle 13):
     *  one that went away while the helper was down stayed listed, a ghost tab and player. */
    private fun forgetPads() {
        val gone = (pads.keys.map { srcOf(it) } + ownRemaps.keys).filter { it > 0 }.toSet()
        if (gone.isEmpty()) return
        pads.clear(); ownRemaps.clear(); devSrc.clear()
        for (s in gone) { ExtEngine.gone(s); onGone?.invoke(s) }
        version.intValue++
    }

    private val NODE = Regex("^hidraw[0-9]{1,3}$")
    /** The player numbers when the order started being made ("M 1"), and until when their changes make no pill. */
    private val madeFrom = ConcurrentHashMap<String, Int>()
    @Volatile private var settleUntil = 0L
    private fun orderSettling() = making || android.os.SystemClock.uptimeMillis() < settleUntil
    /** The order made: one pill for what changed. */
    private val orderPill = Runnable {
        if (order == "dock") return@Runnable
        // dev build 18f: never the one playing as the Thor's by its switch (its number is the copy's: "the Thor is player 2"
        // would be false)
        val moved = pads.values.filter { p -> p.number > 0 && p.node != seatSlot && madeFrom[p.node]?.let { it != p.number } == true }.sortedBy { it.number }
        when {
            moved.size == 1 -> moved[0].let { Overlays.pill(
                if (it.number == 1) "${it.name}: player 1 — the Thor is player 2" else "${it.name}: player ${it.number} — the Thor is player 1",
                CueOrigin.Pad(it.key), group = "order") }
            moved.size > 1 -> Overlays.pill("Player order: " + moved.joinToString(" · ") { "${it.name} player ${it.number}" }, CueOrigin.Pad(null), group = "order")
        }
        madeFrom.clear()
    }

    /** One `Z <slot> …` line from the helper. */
    fun onLine(line: String) {
        val p = line.split(' ')
        if (p.size < 3) return
        if (p[1] == "-") {                       // not about one controller
            if (p[2] == "Q") {
                val was = order; val wasSlot = dockSlot
                order = p.getOrNull(3) ?: ""
                // critique X-2: the helper says which controller plays as the Thor's (no guess: an away one, a high slot)
                dockSlot = if (order == "dock") p.getOrNull(4)?.takeIf { NODE.matches(it) } else null
                version.intValue++
                // dev build 16 (WP4, X4): docked, it plays the Thor's controls — and its own again once undocked
                if ((was == "dock") != (order == "dock") || wasSlot != dockSlot) {
                    appCtx?.let { refreshOwn(it) }
                    // dev build 17 (critique F4): the controller that plays (or played) as the Thor's never keeps its controls
                    // off — docked, the Thor's emergency covers it; undocked, it would play plain buttons with no cue
                    setOfNotNull(wasSlot, dockSlot).mapNotNull { pads[it] }.filter { it.muted }.forEach { unmute(it.key) }
                }
                if (order == "dock" && was != "dock") {
                    val docked = dockSlot?.let { pads[it] } ?: pads.values.firstOrNull()
                    Overlays.pill("Docked — ${docked?.name ?: "A connected controller"} is the Thor's controller now (player 1)",
                        CueOrigin.Pad(docked?.key), group = "dock")
                } else if (was == "dock" && order == "thor") {
                    // dev build 18f: undocked in place, the docked one still plays as the Thor's by its switch ("T" came first)
                    val still = seatSlot?.takeIf { it == wasSlot }?.let { pads[it] }
                    if (still != null) Overlays.pill(GameWords.undockedSeat(still.name), CueOrigin.Pad(still.key), group = "dock")
                    else Overlays.pill("The Thor's own controller is back", CueOrigin.Pad(null), group = "dock")
                }
            }
            // dev build 18f: who plays as the Thor's controller by its switch ("T <slot>", "T -": none) — its controls are the
            // Thor's, its keys count as the Thor's, it never keeps its own controls off
            if (p[2] == "T") {
                val old = seatSlot
                val now = p.getOrNull(3)?.takeIf { NODE.matches(it) }
                seatSlot = now
                version.intValue++
                if (old != now) {
                    appCtx?.let { refreshOwn(it) }
                    setOfNotNull(old, now).mapNotNull { pads[it] }.filter { it.muted }.forEach { unmute(it.key) }
                    // a move from one controller to another; a first seat is said by its ready pill, one ended by its switch by
                    // nothing (its page shows it)
                    if (old != null && now != null && !replaying) pads[now]?.let { Overlays.pill(GameWords.seatNow(it.name), CueOrigin.Pad(it.key), group = "seat") }
                    if (old != null && now == null) { lastSeat = old; seatEndedAt = android.os.SystemClock.uptimeMillis() }
                }
            }
            if (p[2] == "C" && p.getOrNull(3) == "1") {            // L + R on two Joy-Cons: combined, remembered
                prefs?.edit()?.putBoolean("combine", true)?.apply(); version.intValue++
                Overlays.pill("Joy-Cons combined (L + R) — one controller", CueOrigin.Pad(null))
            }
            // dev build 14: the input layer flips AYN's style to rebuild its pad (1) and has put it back (0) — the style
            // observer leaves that flip alone; the style is read again a second after, in case it really changed meanwhile
            if (p[2] == "L") {
                if (p.getOrNull(3) == "1") aynFlipSince = android.os.SystemClock.uptimeMillis()
                else { aynFlipSince = 0L; faceHandler.removeCallbacks(faceCheck); faceHandler.postDelayed(faceCheck, 1000) }
            }
            if (p[2] == "R") replaying = p.getOrNull(3) == "1"     // dev build 16 (theme F): readers told again
            // dev build 15 (C1): the helper asks for a pad's name on AYN's hold list (before the pad exists, or after AYN
            // took it) — answered once AYN's list has it (the helper waits ≤ 1.5 s, then goes on); "broken": AYN took a
            // pad anyway, it now uses AYN's id (the last resort)
            if (p[2] == "H") when (p.getOrNull(3)) {
                "add" -> {
                    val name = p.drop(4).joinToString(" ")
                    if (!AynHold.valid(name)) InputMonitor.send("E hold no $name")
                    else AynHold.add(name) { ok -> InputMonitor.send("E hold ${if (ok) "ok" else "no"} $name") }
                }
                "broken" -> { holdBroken = true; padPill(PadPill.BROKEN, null); version.intValue++ }
            }
            // dev build 15 (C5): the player order being made again ("Making the controllers again…" on the card) — the
            // number pills wait for it: one pill when it's done, not one per controller
            if (p[2] == "M") {
                val on = p.getOrNull(3) == "1"
                if (on) { madeFrom.clear(); pads.values.forEach { madeFrom[it.node] = it.number }; makingSince = android.os.SystemClock.uptimeMillis() }
                else if (makingSince != 0L) {
                    makingSince = 0L
                    settleUntil = android.os.SystemClock.uptimeMillis() + 5000
                    faceHandler.removeCallbacks(orderPill); faceHandler.postDelayed(orderPill, 5000)
                }
                version.intValue++
            }
            // …the order asked waits for that game app to end (the card names it: "Apply now") / no longer waits
            if (p[2] == "W") {
                orderWaits = if (p.getOrNull(3) == "1") p.getOrNull(4)?.takeIf { it.length <= 200 && GameDetector.PKG.matches(it) } else null
                version.intValue++
            }
            // dev build 18f2 (device test t2 BUG-1): the Thor's own player now (as each controller's "N") — [playerNo]
            if (p[2] == "N") {
                val n = p.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..99 } ?: return
                if (n != thorNumber) { thorNumber = n; version.intValue++ }
            }
            return
        }
        val node = p[1].takeIf { NODE.matches(it) } ?: return
        when (p[2]) {
            "K" -> {
                val pad = Pad(node); pad.key = p.getOrNull(3) ?: ""; pad.name = p.drop(4).joinToString(" ").take(70)
                // dev build 16 (theme F): a new connection — not a reader told again, nor one made again at once (docking,
                // undocking, a Joy-Con turning into a pair: its "X" just before)
                val now = android.os.SystemClock.uptimeMillis()
                pad.fresh = !replaying && recentlyGone.values.none { it.first.key == pad.key && now - it.second < 5000 }
                pads[node] = pad
                devSrc.clear()
                appCtx?.let { c -> adoptKindSettings(c, pad.key) }
                // b18m: "New controllers play as Thor's" on — the helper holds a reader that just started (no number, no
                // place in the player order, ≤ ExtRules.SEAT_ASK_MS) until this word on its seat: after the adoption above,
                // so a unit seen for the first time is seated on its first pass. Said for every "K" (a replay too): idempotent
                appCtx?.let { c -> if (seatNew(c) && ExtRules.KEY.matches(pad.key)) InputMonitor.send("E seat ${pad.key} ${if (seatOn(c, pad.key)) 1 else 0}") }
                // its controls for the game now (a controller connected mid-game). dev build 16 review (B16-INT-5): a reader
                // only told again (the helper's replay, "Z - R") already has them — what was sent stands, only a change goes
                appCtx?.let { c -> if (!replaying) { sentUses.remove(pad.key); sentGyro.remove(pad.key) }; refreshOwn(c) }
                // dev build 15 (C1): its pad's name on AYN's hold list — a backstop for a wait that timed out
                if (AynHold.state == AynHold.State.OK) shown(pad).let { n -> if (n !in AynHold.knownNames()) AynHold.add(n) }
            }
            // its own (bus vid pid fields proto second): not wfhid's "I ayn-took-pad 0111", which emptied a Joy-Con's kind
            // (dev build 18f: that one marks its pad as under AYN's id — it can't play as the Thor's then)
            "I" -> pads[node]?.let { pad -> if (p.getOrNull(3) == "ayn-took-pad") { pad.aynId = true }
                else if (p.getOrNull(3)?.toIntOrNull() != null && p.getOrNull(8) != "1") {
                // dev build 15 (C4): a controller back on its kept pad announces its fields again — not twice in the list
                if (p.getOrNull(8) == "0") pad.fields.clear()
                pad.vid = p.getOrNull(4) ?: ""                       // dev build 15 (WP2): its kind (critique X-6: after the clear)
                pad.proto = p.getOrNull(7) ?: ""; pad.pid = p.getOrNull(5) ?: "" } }
            "F" -> {
                val v = p.drop(3)
                if (v.size >= 7) runCatching {
                    pads[node]?.fields?.add(Field(v[0].toInt(), v[1].single(), v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(16), v[6].toInt(16)))
                }
            }
            "D" -> pads[node]?.mapping = p.drop(3).joinToString(" ")
            "R" -> pads[node]?.let { pad ->
                pad.ours = p.getOrNull(3)
                // dev build 16 (theme F): its pad exists — no player number 4 s later (none said yet): ready without it
                if (pad.fresh) faceHandler.postDelayed({ if (pads[node] === pad && pad.number == 0) padPill(PadPill.READY, pad) }, 4000)
            }
            "N" -> pads[node]?.let { pad ->
                val n = p.getOrNull(3)?.toIntOrNull() ?: 0
                val was = pad.number
                pad.number = n
                // dev build 16 (theme F): its first number — the ready pill if it just connected, nothing if it was only
                // told again (an app restart showed "player 2" again for every controller); a later change as before
                if (n > 0 && pad.fresh) padPill(PadPill.READY, pad)
                // (dev build 18f: never for the one playing as the Thor's by its switch — its number is the copy's)
                else if (n > 0 && was > 0 && n != was && !replaying && order != "dock" && !orderSettling() && node != seatSlot) Overlays.pill(
                    if (n == 1) "${pad.name}: player 1 — the Thor is player 2" else "${pad.name}: player $n — the Thor is player 1",
                    CueOrigin.Pad(pad.key), group = "order")
            }
            "O" -> pads[node]?.let { pad ->
                pad.sideways = p.getOrNull(3) == "1"
                // turned sideways with SL + SR (wfhid): remembered, like the page's switch
                if (pad.joyCon) {
                    // the pill only for a change (wfhid echoes every "o 1", sent again at each connect)
                    val before = prefs?.getBoolean("side_${pad.key}", false) == true
                    prefs?.edit()?.putBoolean("side_${pad.key}", pad.sideways)?.apply()
                    if (pad.sideways && !before) Overlays.pill("${pad.name}: held sideways", CueOrigin.Pad(pad.key))
                }
                version.intValue++                                  // the page's switch follows
            }
            // dev build 17 (theme G): its AYN button (a DualSense's Mute…) — the Thor's AYN button, as this controller's presses
            // count (docked as the Thor's: the Thor's)
            "A" -> { aynListener?.invoke(srcOfGated(srcOf(node)), p.getOrNull(3) == "1"); return }
            // …a watched control ("E watch"): its D-pad while Recents is open or a press is captured
            "V" -> { val t = p.getOrNull(3)?.toIntOrNull(); val c = p.getOrNull(4)?.toIntOrNull(); val v = p.getOrNull(5)?.toIntOrNull()
                if (t != null && c != null && v != null) watchListener?.invoke(srcOf(node), t, c, v); return }
            // …its Home + Back held 5 s: its controls off / back. Docked feeding the Thor's copy, back on at once (critique F4:
            // the Thor's own emergency — the input layer off — covers it, and says it); docked the rename way it is its own
            "M" -> pads[node]?.let { pad ->
                val m = p.getOrNull(3) == "1"; val was = pad.muted
                pad.muted = m
                if (BuildConfig.DEBUG) Log.d(TAG, "$node muted ${if (m) 1 else 0}")
                if (m && order == "dock" && dockSlot == node && PadLayerCtl.active) unmute(pad.key)
                // dev build 18f: playing as the Thor's by its switch, the hold reaches wfpad's emergency (merged keys): Game
                // controls go off for every controller and the layer's pill says so — its own controls back at once, no
                // MUTED pill. Also just after the seat ended: the same hold turned the layer off first
                else if (m && (node == seatSlot || (node == lastSeat && android.os.SystemClock.uptimeMillis() - seatEndedAt < 3000))) unmute(pad.key)
                else if (m && !was && !replaying) padPill(PadPill.MUTED, pad)      // (another controller, docked or not: its own)
            }
            "B" -> pads[node]?.battery(p.getOrNull(5)?.toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull() ?: -1, p.getOrNull(4) == "1")
            "P" -> pads[node]?.profileRefused = p.getOrNull(3) == "bad"
            "U" -> pads[node]?.rumbles = true
            // dev build 15 (C4): the controller went away — its pad stays (at rest: wfhid let go of everything), games
            // keep it and its place for 30 minutes; what Wayfinder counted for it is let go here too. "S 1": back on it
            "S" -> pads[node]?.let { pad ->
                val was = pad.away
                pad.away = p.getOrNull(3) == "0"
                if (pad.away && !was) {
                    pad.batteryGone()
                    val raw = srcOf(node); val counts = counted(pad)
                    ExtEngine.gone(raw); onGone?.invoke(raw)
                    if (counts != raw) onGone?.invoke(counts)
                } else if (!pad.away) {
                    devSrc.clear()                                   // critique X-4
                    if (was) padPill(PadPill.BACK, pad)              // critique R15: a change — not the replay at a reconnect
                }
            }
            // its pad's product id and who the controller is: kept in the identity table, so its next connection (either
            // link) gets the same id, name and settings
            "Y" -> pads[node]?.let { pad ->
                val pid = p.getOrNull(3)?.takeIf { PID4.matches(it) } ?: return@let
                val id = p.getOrNull(4)?.takeIf { ExtRules.IDENT.matches(it) } ?: return@let
                pad.padProduct = pid.toInt(16); pad.ident = id
                devSrc.clear()                                       // critique X-4: a lookup made before "Y" may be stale
                val title = ExtRules.tableTitle(pad.name)
                if (pad.key.isNotEmpty() && title.isNotBlank()) {
                    val entry = "$pid ${pad.key} $title"
                    if (prefs?.getString("id_$id", null) != entry) prefs?.edit()?.putString("id_$id", entry)?.apply()
                }
            }
            // dev build 18f: its touchpad moves Android's pointer — straight to the engine, and no page recomposes for it (up
            // to 125 lines a second)
            "H" -> {
                when (val t = ExtRules.parseTouch(p.drop(3))) {
                    is ExtRules.Touch.Move -> ExtEngine.onPointer(t.dx, t.dy, srcOf(node))
                    is ExtRules.Touch.Click -> ExtEngine.onPointerButton(t.down, srcOf(node))
                    is ExtRules.Touch.Wheel -> ExtEngine.onWheel(t.v, t.h, srcOf(node))
                    null -> {}
                }
                return
            }
            // a button / stick the game's controls give to Wayfinder (keys, actions, macros, mouse): played like the
            // Thor's, as this controller (its macros press ITS buttons)
            "W" -> { val c = p.getOrNull(3)?.toIntOrNull(); val v = p.getOrNull(4)?.toIntOrNull()
                if (c != null && v != null) ExtEngine.onExt(c, v, srcOf(node)); return }
            "T" -> { val sd = p.getOrNull(3)?.toIntOrNull(); val x = p.getOrNull(4)?.toIntOrNull(); val y = p.getOrNull(5)?.toIntOrNull()
                if (sd != null && x != null && y != null) ExtEngine.onStick(sd, x, y, srcOf(node)); return }
            "C" -> {
                val f = p.getOrNull(3)?.toIntOrNull(); val v = p.getOrNull(4)?.toIntOrNull()
                if (f != null && v != null) learnListener?.invoke(node, f, v, p.getOrNull(5)?.toIntOrNull() ?: 0)
                return
            }
            "L" -> when (p.getOrNull(3)) {
                "0" -> {
                    // build 18 review (C): no longer learning — its own keys drive the page again (the hand's look follows)
                    (pads[node] ?: recentlyGone[node]?.first)?.key?.let { if (it == learningKey) learningKey = null }
                    learnEnded?.invoke(node)
                }
                // dev build 16 (theme E): a hold while learning — its Home 1.5 s ("h"), a button heard before 1 s ("k")
                "h", "k" -> p.getOrNull(4)?.toIntOrNull()?.let { f -> learnHold?.invoke(node, p[3] == "h", f) }
            }
            "X" -> {
                val raw = srcOf(node)
                // dev build 14 (review): its presses may count as another one of its key ([counted]) — forgotten there
                // too, or its Home stayed held there
                val counts = pads[node]?.let { counted(it) } ?: raw
                pads.remove(node)?.let { recentlyGone[node] = it to android.os.SystemClock.uptimeMillis() }
                devSrc.clear(); if (pads.isEmpty()) order = ""
                ExtEngine.gone(raw); onGone?.invoke(raw)
                if (counts != raw) onGone?.invoke(counts)
            }
            "E" -> {
                Log.w(TAG, "$node: ${p.drop(3).joinToString(" ")}")
                // dev build 15 (C2): given back, but a device couldn't be put back in Android's list. Review P4: a give-back
                // that ended after its reader's "X" still says it — the controller is looked up among those just gone
                if (p.getOrNull(3) == "restore") {
                    val now = android.os.SystemClock.uptimeMillis()
                    recentlyGone.entries.removeIf { now - it.value.second > 15_000 }
                    (pads[node] ?: recentlyGone[node]?.first)?.let { padPill(PadPill.RESTORE, it) }
                }
            }
        }
        version.intValue++
    }
}

/** A connected controller's pad product id (vendor 2020): 0xF000 + 12 bits of its key — [ExtPads] makes its pad under
 *  it (AYN's 0x0111 when it can't), [ExtPadsCtl] tells its presses apart by it. Top level: the root helper can use it
 *  without starting [ExtPadsCtl] (its init hooks [ExtEngine]). */
fun extPadProduct(key: String): Int = 0xF000 or (key.hashCode() and 0x0FFF)
