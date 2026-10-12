package app.wayfinder

/**
 * dev build 15 — the external controllers' rules that need no device: pure functions, no Android types, so plain JVM
 * tests check them (app/src/test, ExtRulesTest). Used by the root helper ([ExtPads], [ExtNodes]) and the app
 * ([ExtPadsCtl], [AynHold]) — one rule, the same answer on both sides.
 */
internal object ExtRules {
    /** The ending of every pad Wayfinder makes for a controller (AYN's hold list, wfpad and [AynPad] key on it). */
    const val WF_SUFFIX = " (Wayfinder)"
    /** AYN's own pad's name in its standard style (2020:0111; in Xbox style it is 2020:0112, never claimed). */
    const val AYN_PAD_NAME = "Odin Controller"
    /** A pad's name, in bytes: wfhid copies UINPUT_MAX_NAME_SIZE − 1 (fx/wfhid_core.h HX_NAME_MAX). */
    const val NAME_MAX = 79

    fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun noControls(s: String) = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")

    /** A controller's key: vendor + product + its name, letters / digits only (it comes from the radio). dev build 16 (WP4):
     *  from [ExtPads] — the helper's lines and the app's ("E uses", a page's controller) check the same pattern. */
    val KEY = Regex("^[0-9a-f]{8}-[A-Za-z0-9_.+-]{0,52}$")
    /** Profile tokens (fx/wfmap.h wf_parse re-validates each one) — the same shape PadLayer accepts. */
    val PROFILE = Regex("^([A-Za-z0-9]{1,8}=[A-Za-z0-9]{1,8}( [A-Za-z0-9]{1,8}=[A-Za-z0-9]{1,8})*)?$")

    // ── dev build 16 (theme E, critique M2): the deadzone saved to the controller measured ──

    /**
     * The deadzone in a controller's controls ("P"), one rule for each stick. A "dzl" / "dzr" that is absent or the Thor's
     * every-game value ([base]: what padProfileFor merges into every game's profile) becomes the controller's own measured
     * one ([pad], the Test page's; 0 = none) — else [base]: its own controls carry no every-game shape (WP4 X2) and keep the
     * Thor's, as before. A game's own other value stays. [plain] (its page says Plain: nothing from Game controls) → the
     * controller's only. [raw] (Wayfinder in front: the Test page measures the raw stick; docked: it plays as the Thor's
     * controller) → [spec] as it is. Values 0..30 (wf_parse's range).
     */
    fun withPadDeadzone(spec: String, pad: Pair<Int, Int>?, base: Pair<Int, Int>, plain: Boolean, raw: Boolean): String {
        if (raw) return spec
        val toks = spec.split(' ').filter { it.isNotEmpty() }.toMutableList()
        for ((i, name) in listOf("dzl", "dzr").withIndex()) {
            val mine = pad?.let { if (i == 0) it.first else it.second }
            val b = if (i == 0) base.first else base.second
            val at = toks.indexOfLast { it.startsWith("$name=") }        // wf_parse: the last one counts
            val cur = if (at >= 0) toks[at].substringAfter('=').toIntOrNull() else null
            if (at >= 0 && cur != b && !plain) continue                   // a game's own deadzone
            val want = (if (plain) mine ?: 0 else mine ?: b).coerceIn(0, 30)
            if (at >= 0 && cur == want) continue
            val tok = if (want > 0) "$name=$want" else ""
            if (at >= 0) { for (j in 0 until at) if (toks[j].startsWith("$name=")) toks[j] = ""; toks[at] = tok }
            else if (tok.isNotEmpty()) toks += tok
        }
        return toks.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** dev build 17 (critique F2): what a reader feeding the Thor's copy (docked) is told — no controls of its own (the copy
     *  applies the game's), and its Back never gates: the copy's engine is then the only gate, exactly the Thor's path (with
     *  its own gate, a game's "Back goes to the game" — RetroArch's Back + R1 — was withheld before the copy saw it). */
    const val FEEDING_SPEC = "bg=1"

    // ── dev build 17 (theme G, D4): Back and AYN on a connected controller ──

    private val MAP_TOKEN = Regex("^([A-Z0-9]{1,2})=([0-9]{1,3})i?$")
    /** A wfhid mapping ("S=2 LY=1i BK=-1 …", its D line) as target → field; "Not on it" (-1) and bad tokens left out. */
    fun mapTargets(spec: String?): Map<String, Int> = spec.orEmpty().split(' ').mapNotNull { t ->
        MAP_TOKEN.find(t)?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }.toMap()

    /** What Home + Select / Home + Start stand for on a controller: its Back / its AYN button. */
    data class StandIns(val back: Boolean, val ayn: Boolean)
    /** Home + Select = its Back when it has none of its own ("BK=" naming a field in [mapping], wfhid's D line), Home + Start =
     *  its AYN button when it has no "AY=" — only on a controller with a Home button of its own ("HO=": never a Home-less
     *  pad's Select + Start Home) that is the Thor's Home ([homeWf]; given to the game, Home + Select is the game's), and
     *  with the Select / Start it takes. */
    fun standIns(mapping: String?, homeWf: Boolean): StandIns {
        val t = mapTargets(mapping)
        if (!homeWf || "HO" !in t) return StandIns(back = false, ayn = false)
        return StandIns(back = "BK" !in t && "SE" in t, ayn = "AY" !in t && "ST" in t)
    }

    /** dev build 18f (review): its Back ("BK=" in [mapping]) IS the touchpad's press — the field [fieldOf] gives as (page,
     *  usage) is a PlayStation controller's touchpad click (wfhid's Sony field, page 0xFF01 usage 14: Vocab.spareName's
     *  "touchpad click"). False: its Back is another button, Home + Select stands in, or it has none. */
    fun backIsTouchClick(mapping: String?, fieldOf: (Int) -> Pair<Int, Int>?): Boolean =
        mapTargets(mapping)["BK"]?.let(fieldOf)?.let { (page, usage) -> page == 0xFF01 && usage == 14 } == true

    // ── C1: names ──

    /** The name Android shows for a controller's pad: its [title] (66 characters at most, controls made spaces) +
     *  " (Wayfinder)", 79 UTF-8 bytes at most — a longer one keeps the ending and cuts the title on a character
     *  boundary (wfhid's fx/wfhid_core.h hx_pad_name makes the same name). dev build 14 refused a long non-ASCII
     *  title ("E usage", exit 2). The one place a pad's shown name is made. */
    fun padName(title: String): String {
        var t = noControls(title.take(66))
        if (t.isNotEmpty() && t.last().isHighSurrogate()) t = t.dropLast(1)
        val full = t + WF_SUFFIX
        if (utf8(full) <= NAME_MAX) return full
        val room = NAME_MAX - utf8(WF_SUFFIX)
        val sb = StringBuilder(); var used = 0; var i = 0
        while (i < t.length) {
            val n = Character.charCount(t.codePointAt(i)).coerceAtMost(t.length - i)
            val b = utf8(t.substring(i, i + n))
            if (used + b > room) break
            sb.append(t, i, i + n); used += b; i += n
        }
        return sb.toString() + WF_SUFFIX
    }

    /** A controller's title as the identity table keeps it (the app's prefs `id_<ident>`; the helper refuses more than 66
     *  characters): [padName] makes the same pad name from it as from the whole title. */
    fun tableTitle(title: String): String {
        val t = title.take(66)
        return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
    }

    /** Review P6: an "E" line's words in the helper — "E ident …" split from the raw line (its title may end in a space;
     *  every line trimmed first, the next session's pad lost that space and changed name once); the rest trimmed. */
    fun eWords(line: String): List<String> {
        val t = line.trim().split(' ')
        return if (t.getOrNull(1) == "ident") line.trimStart().trimEnd('\r', '\n').split(' ') else t
    }

    /** A name that may go on AYN's hold list: one of our pads' (entries outlive an uninstall — nothing else, ever). */
    fun validHoldName(name: String): Boolean =
        name.length > WF_SUFFIX.length && name.endsWith(WF_SUFFIX) && utf8(name) <= NAME_MAX && name.none { it < ' ' || it == '\u007f' }

    // ── C4: identity ──

    /** A controller's serial (HID_UNIQ) as 6 hex digits — the per-unit hash its settings key already carries ("+u…"). */
    fun unitHash(uniq: String): String = String.format("%06x", uniq.lowercase().hashCode() and 0xFFFFFF)
    /** The "+u…" part of a key: "" without a serial. */
    fun unit(uniq: String): String = if (uniq.isEmpty()) "" else "+u" + unitHash(uniq)
    /** A controller's identity: vendor + product + its serial's hash (the same over Bluetooth and USB), else its settings
     *  [key] (what tells it apart today). Separate from the key: the key's format (every saved setting) never changes. */
    fun ident(vid: String, pid: String, uniq: String, key: String): String = if (uniq.isEmpty()) key else "$vid$pid+u${unitHash(uniq)}"
    val IDENT = Regex("^[0-9a-f]{8}(\\+u[0-9a-f]{6}|-[A-Za-z0-9_.+-]{0,52})$")
    private val SERIAL_IDENT = Regex("^[0-9a-f]{8}\\+u[0-9a-f]{6}$")
    /** The identity comes from a serial (one controller for sure, whatever its link). */
    fun hasSerial(ident: String) = SERIAL_IDENT.matches(ident)

    /** The slot a new reader gets — its id for the app (`Z <slot>`, `E p <slot>`, its pad's location): its hidraw name,
     *  or — a kept pad (C4) still has that name while the kernel gave its number to another controller —
     *  hidraw<N + 100·k> (k = 1..9, ≤ 999: the app's regexes allow 3 digits). null: none free. */
    fun slotFor(node: String, taken: Set<String>): String? {
        val n = node.removePrefix("hidraw").toIntOrNull()?.takeIf { node.startsWith("hidraw") && it in 0..999 } ?: return null
        if (node !in taken) return node
        for (k in 1..9) { val s = n + 100 * k; if (s > 999) break; if ("hidraw$s" !in taken) return "hidraw$s" }
        return null
    }

    /** A new controller's pad product id: [base] (from its key — what an emulator already mapped), else the next free
     *  one, wrapping inside 0xF000..0xFFFF (never AYN's 0x0111 / 0x0112). */
    fun pickPid(base: Int, used: Set<Int>): Int {
        var p = base
        repeat(0x1000) {
            if (p !in used) return p
            p = if (p >= 0xFFFF) 0xF000 else p + 1
        }
        return base
    }

    /** R1: the cable plugged while the Bluetooth link is still up (or the other way): a controller with a serial whose
     *  reader still runs waits for that link to go ([HANDOVER_MS]), then takes its kept pad. After that: a fresh reader
     *  (never no controls). [since] = when this candidate was first seen. */
    const val HANDOVER_MS = 10_000L
    fun waitHandover(serial: Boolean, liveSame: Boolean, since: Long, now: Long): Boolean = serial && liveSame && now - since < HANDOVER_MS

    // ── G2: backing off a refused reader ──

    private val REFUSED = setOf(2, 4, 5)
    /** A reader that ended [code] [ranMs] after its start was refused (then backed off): wfhid's 2 bad arguments ·
     *  4 no usable fields · 5 uinput, or a crash (128 + its signal; not SIGKILL / SIGTERM: stop()'s destroy) — within
     *  5 s. The controller going away (6, or 3: it couldn't be opened) is a normal disconnect. */
    fun refusedAtOnce(code: Int, ranMs: Long): Boolean = (code in REFUSED || (code > 128 && code != 128 + 9 && code != 128 + 15)) && ranMs < 5000
    /** The wait after the [n]th refusal in a row: 30 s, 1 min, 2… up to 10 min. */
    fun backoffMs(n: Int): Long = (30_000L shl (n - 1).coerceIn(0, 5)).coerceAtMost(600_000L)
    /** It ran this long: its refusals are forgotten. */
    fun ranLongEnough(ranMs: Long) = ranMs > 10_000

    // ── C5: when the player order may make the pads again ──

    /** The app whose game keeps the controllers now, or null (a re-make is allowed). [holders]: apps that had the
     *  controllers since the pads were last made in order (oldest first); [fg]: the one in front now (a game app; null =
     *  launcher, Wayfinder, nothing). Another holder still running blocks (a game in the background keeps its mapping);
     *  [fg] blocks unless its game is just starting (its process ≤ 20 s old, or a game detected in it ≤ 15 s ago). */
    fun blocker(holders: List<String>, fg: String?, alive: (String) -> Boolean, ageMs: (String) -> Long?, gameStartAt: Long, now: Long): String? {
        holders.firstOrNull { it != fg && alive(it) }?.let { return it }
        val f = fg ?: return null
        val age = ageMs(f) ?: return null                       // not running (any more): nothing to keep
        return if (age < 20_000 || now - gameStartAt < 15_000) null else f
    }

    /** b18o: what the player order calls the Thor (the input layer's copy, or AYN's pad) — ExtPads' order lists, the app's
     *  [HubPage.THOR]. */
    private const val ORDER_THOR = "thor"
    /** b18o: [awayAfterThor]'s answer — the order to make ([want]) and the away controllers it put after the Thor ([moved]). */
    data class AwayOrder(val want: List<String>, val moved: Set<String>)
    /** b18o (2026-10-11): a controller that is its own player and goes away keeps its place 30 minutes (its pad kept,
     *  at rest, C4) — but it never makes Wayfinder renumber the Thor. In the order asked [want] (the Thor and the controllers,
     *  player 1 first), each one [away] asked BEFORE the Thor goes right after the Thor, in its turn: with "Connected
     *  controllers first", a re-make (a game closing included) left the Thor's copy #2 or later behind controllers that
     *  weren't there. The Thor keeps its number until that controller is back; then the order asked applies again, and waits
     *  for a game as any change does. One asked after the Thor is as before. [AwayOrder.moved] is left out when the order is
     *  checked (ExtPads keepOrder): an away controller alone never starts a re-make, so one whose kept pad is numbered before
     *  the Thor keeps that number (its kept place, the Thor's number unchanged) — until a re-make another change needs, which
     *  makes its pad again anyway: then it comes after the Thor too. b18o review: it stayed before the Thor in that re-make (B
     *  connected: B #1, the away one #2, the Thor #3 — #2 without it). */
    fun awayAfterThor(want: List<String>, away: Set<String>): AwayOrder {
        val t = want.indexOf(ORDER_THOR)
        if (t <= 0 || away.isEmpty()) return AwayOrder(want, emptySet())
        val moved = want.subList(0, t).filter { it in away }
        if (moved.isEmpty()) return AwayOrder(want, emptySet())
        val rest = want.filter { it !in moved }
        val after = rest.indexOf(ORDER_THOR) + 1
        return AwayOrder(rest.subList(0, after) + moved + rest.subList(after, rest.size), moved.toSet())
    }

    // ── C3: the list stays as games had it ──

    /** [readers] = Android ids (InputDevice ids, the order Eden walks) along the order games keep (controller numbers):
     *  the first place listed before one that comes earlier in that order, or −1. From there our pads are made again,
     *  in order. A place < 0 (not listed) is skipped. */
    fun firstMisplaced(readers: List<Int>): Int {
        var max = -1
        readers.forEachIndexed { i, r ->
            if (r < 0) return@forEachIndexed
            if (r < max) return i
            max = r
        }
        return -1
    }

    /** One game controller in the identity table (the log): [kind] as [idKind]. */
    class IdDev(val reader: Int, val kind: String, val vendor: String, val product: String, val number: Int, val enabled: Boolean)
    /** The identity table as one log line (no names, no serials):
     *  `1 copy 2020:0111 n1 on · 2 ayn 2020:0111 n2 off · 3 pad:hidraw3 2020:f1a2 n3 on | removed c1 r1 | off t2`.
     *  Place = index among game controllers in Android-id order (hidden ones included: Eden's walk). */
    fun idTable(devs: List<IdDev>, removed: Map<Char, Int>, off: Map<Char, Int>): String {
        val sb = StringBuilder(devs.sortedBy { it.reader }.mapIndexed { i, d ->
            "${i + 1} ${d.kind} ${hex4(d.vendor)}:${hex4(d.product)} n${d.number} ${if (d.enabled) "on" else "off"}"
        }.joinToString(" · "))
        fun counts(m: Map<Char, Int>) = m.filterValues { it > 0 }.toSortedMap().entries.joinToString(" ") { "${it.key}${it.value}" }
        if (removed.values.any { it > 0 }) sb.append(" | removed ").append(counts(removed))
        if (off.values.any { it > 0 }) sb.append(" | off ").append(counts(off))
        return sb.toString()
    }
    fun hex4(s: String): String = s.removePrefix("0x").lowercase().padStart(4, '0').takeLast(4)
    /** A game controller's kind: copy (the Thor's copy) · ayn (AYN's own pad) · pad:<slot> (ours) · aync-ours (AYN's
     *  copy of a pad of ours: AYN took it) · aync (AYN's copy of a controller) · raw (a controller itself) · other. */
    fun idKind(phys: String, vendor: String, product: String, name: String, ayn: Boolean, slotOfPhys: (String) -> String?): String {
        val v = hex4(vendor); val p = hex4(product)
        return when {
            phys == PadLayer.CLONE_PHYS -> "copy"
            phys.startsWith(ExtPads.PHYS_PREFIX) -> "pad:" + (slotOfPhys(phys) ?: "?")
            ayn -> "ayn"
            v == "2020" && p == "0111" && phys.isEmpty() -> if (name.endsWith(WF_SUFFIX)) "aync-ours" else "aync"
            v != "2020" -> "raw"
            else -> "other"
        }
    }

    // ── C2: what Android lists besides our pads ──

    /** One input device (/sys/class/input/eventN) as [classify] needs it. [hid]: its HID device ("0005:054C:0CE6.000A"),
     *  "" for a virtual one (uinput / AYN's copies); [props]: its INPUT_PROP bits; [hasNode]: /dev/input/eventN exists;
     *  [ayn]: [AynPad.isAyn]. */
    class NodeDev(val event: String, val name: String, val phys: String, val vendor: String, val product: String, val hid: String,
                  val gamepad: Boolean, val props: Long, val hasNode: Boolean, val ayn: Boolean)
    /** What to do with a device: [kind] 'c' (AYN's copy of a controller we handle) / 'r' (its own gamepad AYN didn't take):
     *  unlink its node · 't' (its touchpad / motion sensors) / 'd' (a 'c' / 'r' we may not unlink): off in Android.
     *  [owner] = the HID device it belongs to (it comes back when that controller is given back). [dropRaw]: AYN took
     *  that raw after Wayfinder removed its node — AYN's business now, its record goes. */
    class Claim(val kind: Char, val owner: String, val dropRaw: String? = null)
    /** INPUT_PROP_POINTER (0), _BUTTONPAD (2), _ACCELEROMETER (6): a touchpad or motion sensors, not a controller. */
    const val PROPS_NOT_PAD = (1L shl 0) or (1L shl 2) or (1L shl 6)

    /**
     * C2: [d]'s fate, given every device ([all]), the HID devices of the controllers Wayfinder handles now ([owned]) and
     * the devices already recorded ([recorded]: eventN → kind). null = not ours to touch. Never: our pads and the Thor's
     * copy (location "wayfinder-…"), AYN's own pad, AYN's copy of a pad of ours ("… (Wayfinder)": that is AYN taking it,
     * C1), a device without a node or recorded already. [unlink] false (debug.wayfinder.unlink 0, or a failed restore):
     * 'c' / 'r' become 'd'.
     * AYN's copy belongs to the controller AYN holds: the gamepad with that name and no node — not one whose node
     * Wayfinder removed itself (critique R3: two identical DualSenses). Two such, all ours: 'd' (whose it is can't be
     * told); one not ours among them: left alone (it may be an unhandled controller's only device).
     */
    fun classify(d: NodeDev, all: List<NodeDev>, owned: Set<String>, recorded: Map<String, Char>, unlink: Boolean): Claim? {
        if (d.event in recorded || !d.hasNode) return null
        if (d.phys.startsWith("wayfinder-") || d.ayn) return null
        if (d.vendor == "2020") {
            if (d.name.endsWith(WF_SUFFIX) || d.product != "0111" || d.phys.isNotEmpty() || !d.gamepad) return null
            // 1.5-beta1 review: [AynPad.isAyn] tells AYN's pad by its name only, so a controller named like it would make
            // the Thor's own pad look like AYN's copy of that controller (its node removed): that name is never claimed
            if (d.name == AYN_PAD_NAME) return null
            val held = all.filter { it.gamepad && !it.hasNode && it.vendor != "2020" && it.name == d.name && it.hid.isNotEmpty() }
            val cands = held.filter { recorded[it.event] != 'r' }
            if (cands.size == 1) return if (cands[0].hid in owned) Claim(if (unlink) 'c' else 'd', cands[0].hid) else null
            if (cands.size > 1) return if (cands.all { it.hid in owned }) Claim('d', cands.minOf { it.hid }) else null
            // none: AYN took a raw whose node Wayfinder had removed (it opened it first) — that one
            val raws = held.filter { recorded[it.event] == 'r' && it.hid in owned }
            return if (raws.size == 1) Claim(if (unlink) 'c' else 'd', raws[0].hid, dropRaw = raws[0].event) else null
        }
        if (d.hid.isEmpty() || d.hid !in owned) return null
        if (d.gamepad) return Claim(if (unlink) 'r' else 'd', d.hid)
        if (d.props and PROPS_NOT_PAD != 0L) return Claim('t', d.hid)
        return null
    }

    /** The HID device of an input device: /sys/class/input/eventN/device resolved = …/<HID id>/input/inputM. "" for a
     *  virtual one (uinput devices live under /sys/devices/virtual/input). */
    val HID_ID = Regex("^[0-9A-F]{4}:[0-9A-F]{4}:[0-9A-F]{4}\\.[0-9A-F]{4}$")
    fun hidOfInputPath(canonical: String): String {
        val parts = canonical.trimEnd('/').split('/')
        if (parts.size < 3 || parts[parts.size - 2] != "input") return ""
        return parts[parts.size - 3].takeIf { HID_ID.matches(it) } ?: ""
    }
    /** The last word of a sysfs bitmap ("properties"): bits 0..63. */
    fun propBits(s: String?): Long = s?.trim()?.split(' ')?.lastOrNull()?.toULongOrNull(16)?.toLong() ?: 0L
    /** st_rdev → major / minor (bionic's sys/sysmacros.h encoding). */
    fun devMajor(rdev: Long): Int = (((rdev ushr 32) and 0xfffff000L) or ((rdev ushr 8) and 0xfffL)).toInt()
    fun devMinor(rdev: Long): Int = (((rdev ushr 12) and 0xffffff00L) or (rdev and 0xffL)).toInt()

    /** One removed / switched-off device (C2), as the records file keeps it: `<eventN> <maj>:<min> <inputN> <kind>
     *  <ownerHid> <reader>`. */
    class Rec(val event: String, val maj: Int, val min: Int, val input: String, val kind: Char, val owner: String, val reader: Int)
    fun recLine(r: Rec) = "${r.event} ${r.maj}:${r.min} ${r.input} ${r.kind} ${r.owner} ${r.reader}"
    private val REC = Regex("^(event[0-9]{1,4}) ([0-9]{1,4}):([0-9]{1,7}) (input[0-9]{1,7}) ([crtd]) ([0-9A-F]{4}:[0-9A-F]{4}:[0-9A-F]{4}\\.[0-9A-F]{4}) (-?[0-9]{1,6})$")
    /** A records-file line, every field checked (the file sits in the app's folder); null = not one. */
    fun parseRec(line: String): Rec? {
        val m = REC.find(line.trim()) ?: return null
        val g = m.groupValues
        return Rec(g[1], g[2].toInt(), g[3].toInt(), g[4], g[5][0], g[6], g[7].toInt())
    }

    // ── dev build 18f: "Plays as the Thor's controller" (the seat) and "Touchpad moves the pointer" ──

    /** A controller [seatFor] may seat: [slot] its reader, [connectedAt] when its reader started (an attach keeps it),
     *  [away] its controller gone for a while (its pad kept), [eligible] its switch on, wfpad never refused it 3 times, its
     *  pad not under AYN's id (a 2020:0111 pad shares the copy's guid: hidden and listed before a re-made copy, it would take
     *  Eden's port 0 — so it is never seated). */
    data class SeatCand(val slot: String, val connectedAt: Long, val away: Boolean, val eligible: Boolean)
    /** A reader may be seated: its switch on ([switchOn]), wfpad not refused 3 times ([refused]), its pad not under AYN's id
     *  ([pid] 0x0111: AYN took it, or no buttons layout — it shares the copy's guid), never the dock's rename way ([asThor]). */
    fun seatEligible(switchOn: Boolean, refused: Boolean, pid: Int, asThor: Boolean): Boolean =
        switchOn && !refused && pid != 0x0111 && !asThor

    /** Who plays as the Thor's controller undocked (its slot; null = nobody). Only while the input layer is wanted for the app
     *  in front ([layerOn]). The current seat [cur] is kept while it is eligible and here, or eligible and away while no other
     *  eligible one is here (its kept pad, at rest, stays fed). Else the first connected eligible one that is here: an away
     *  one only keeps the seat, never newly takes it. */
    fun seatFor(cur: String?, cands: List<SeatCand>, layerOn: Boolean): String? {
        if (!layerOn) return null
        val here = cands.filter { it.eligible && !it.away }
        cands.firstOrNull { it.slot == cur && it.eligible }?.let { c -> if (!c.away || here.isEmpty()) return c.slot }
        return here.minWithOrNull(compareBy<SeatCand>({ it.connectedAt }, { slotNumber(it.slot) }))?.slot
    }
    private fun slotNumber(slot: String) = slot.removePrefix("hidraw").toIntOrNull() ?: Int.MAX_VALUE

    /** dev build 18f2 (device test t1 BUG-1): the hidden pad of the controller playing as the Thor's by its switch holds a
     *  controller number BEFORE the copy's ([seatNumber] < [copyNumber]): it was a player before its switch went on
     *  ("Connected controllers first", a rank — Android keeps a hidden device's number), so the copy stayed Android's #2 and
     *  an emulator keyed on the number (Dolphin's "Android/<n>/…") lost the Thor. The player order makes it again
     *  ([ExtPads] keepOrder → remakeOrder): the copy, made again, takes the lower number and the seat's pad comes back last —
     *  as if it had connected with its switch on. Not for the list order alone (a copy made again while seated is listed
     *  after the seat's older pad): Eden counts its port among the copy's own guid, which the seat never shares
     *  ([seatEligible]: never 0x0111), and Dolphin keys on the number — re-making for it would blink the seat at every copy
     *  re-make (each game start with "Only games you set up"). Unknown numbers (0: not listed, the copy restarting): no. */
    fun seatBeforeCopy(seatNumber: Int, copyNumber: Int): Boolean = seatNumber > 0 && copyNumber > 0 && seatNumber < copyNumber

    /** A connected controller's presses count as the Thor's (source 0): docked, any one whose own pad isn't shown (it feeds
     *  the copy, or plays under the Thor's name — as before); undocked, the one playing as the Thor's by its switch
     *  ([seatSlot], the helper's "Z - T") while its own pad is hidden. [ownShown] reads Android's list: asked only then. */
    fun countsAsSeat(node: String, order: String, seatSlot: String?, ownShown: () -> Boolean): Boolean =
        (order == "dock" || (seatSlot != null && node == seatSlot)) && !ownShown()

    /** A reader's touchpad line (wfhid "H …", fx/wfhid.c): the pointer moved (px, after acceleration), a tap's click, wheel
     *  notches (two fingers, natural). */
    sealed class Touch {
        data class Move(val dx: Int, val dy: Int) : Touch()
        data class Click(val down: Boolean) : Touch()
        data class Wheel(val v: Int, val h: Int) : Touch()
    }
    private val TOUCH_INT = Regex("^-?[0-9]{1,6}$")
    /** [w] = an "H" line's words after the "H": "m <dx> <dy>" · "b <1|0>" · "w <v> <h>" — every number checked, dx / dy
     *  clamped to ±2000, v / h to ±10; null = not one (garbage, a word missing or extra). */
    fun parseTouch(w: List<String>): Touch? {
        fun n(i: Int, lim: Int): Int? = w.getOrNull(i)?.takeIf { TOUCH_INT.matches(it) }?.toIntOrNull()?.coerceIn(-lim, lim)
        return when (w.getOrNull(0)) {
            "m" -> if (w.size != 3) null else Touch.Move(n(1, 2000) ?: return null, n(2, 2000) ?: return null)
            "b" -> if (w.size != 2) null else when (w[1]) { "1" -> Touch.Click(true); "0" -> Touch.Click(false); else -> null }
            "w" -> if (w.size != 3) null else Touch.Wheel(n(1, 10) ?: return null, n(2, 10) ?: return null)
            else -> null
        }
    }

    /** A whole table of controllers' keys from the app ("E seats <k1>,<k2>…", "E tpadoff …"; [arg] its third word): bare =
     *  none, a bad key is left out (the others still count), more than 64 = refused (null) — as "E pfaces". */
    fun keyTable(arg: String?): Set<String>? {
        val toks = arg?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (toks.size > 64) return null
        return toks.filter { KEY.matches(it) }.toSet()
    }
    /** The app's switches stored "<prefix><key>" = true (seat_ · tpoff_) as that table's line, [cmd] first ("E seats
     *  k1,k2"): keys sorted, 64 at most; none = the bare [cmd] (a Thor-only user's, or nobody switched it). A global key
     *  under the same prefix ([SEAT_NEW], "seat_new") is no controller's key: [KEY] leaves it out. */
    fun tableLine(cmd: String, all: Map<String, *>, prefix: String): String =
        ("$cmd " + all.filter { (k, v) -> v == true && k.startsWith(prefix) }.keys.map { it.removePrefix(prefix) }
            .filter { KEY.matches(it) }.sorted().take(64).joinToString(",")).trimEnd()

    // ── b18m (2026-10-11): "New controllers play as Thor's" — a new controller starts seated ──

    /** The global switch's pref in "thor_extpads": true = on, absent = off (the default: each new controller its own
     *  player). Never a controller's key ([KEY] needs 8 hex digits and a dash), so no "seat_<key>" table reads it. */
    const val SEAT_NEW = "seat_new"
    /** A controller's "Plays as the Thor's controller" at its first sight. [first]: its unit adopted for the first time
     *  (ExtPadsCtl.adoptKindSettings — the once-per-unit point that writes "adopted_<key>"; a key with no unit: [firstSight]);
     *  [newOn]: "New controllers play
     *  as Thor's". First time with it on → on: it starts seated, so games keep seeing only the Thor's copy and an emulator
     *  bound to it (Dolphin's "Android/<controller number>/…", Eden's guid + port) needs nothing again. A known controller,
     *  or the switch off → its own switch [current], unchanged. */
    fun seatAtFirstSight(first: Boolean, newOn: Boolean, current: Boolean): Boolean = if (first && newOn) true else current
    /** b18m review 1: a controller not marked "adopted_<key>" yet — is this its first sight? A unit ([unit]: its key has
     *  "+u", from a serial): yes (every unit is marked at its first connection since per-unit settings came). A key with no
     *  unit — a combined Joy-Con pair (ExtPads.PAIR_KEY: every pair shares it, its page and its settings) or a controller
     *  with no serial (its kind's key) — was never marked before b18m: one seen before has its identity-table entry
     *  ([inTable]: the app's "id_<key>", its ident being its key) and is known; without one, it is new. */
    fun firstSight(unit: Boolean, inTable: Boolean): Boolean = unit || !inTable
    /** The global switch as apply()'s line, sent always (as "E combine"): a helper that outlived the app learns it is off. */
    fun seatNewLine(all: Map<String, *>): String = "E seatnew ${if (all[SEAT_NEW] == true) 1 else 0}"
    /** How long a reader that just started waits for the app's word on its seat ([seatAsks]) — then it goes on as its own
     *  player (an app that never answers can't hold a controller back). */
    const val SEAT_ASK_MS = 2000L
    /** The helper: a reader that starts while "New controllers play as Thor's" is on ([newOn]) and its key isn't seated yet
     *  ([keyOn]) waits for the app's "E seat <key> 0|1" — said right after the app adopted it, so a controller seen for the
     *  first time is seated on its very first pass: never numbered, lit or put in the player order as its own player first
     *  (with "Connected controllers first" that re-made the order and pushed the Thor's copy to #2). */
    fun seatAsks(newOn: Boolean, keyOn: Boolean): Boolean = newOn && !keyOn
    /** …and still waits at [now] ([since]: when its "K" went to the app — the line the app answers; review 2: not its
     *  reader's start, which can come ≤ 1.5 s earlier, AYN's hold list asked first): at most [SEAT_ASK_MS], a clock gone
     *  back ends it too. */
    fun seatAsking(asks: Boolean, since: Long, now: Long): Boolean = asks && now - since in 0 until SEAT_ASK_MS

    // ── the app's apply() — its lines from the prefs (pure since 18f, unchanged: the pref-safety test reads them) ──

    /** A controller's own deadzone as stored ("sdz_<key>" = "<left> <right>"), each 0..30; null = none, or not two numbers. */
    fun parseSticks(s: String?): Pair<Int, Int>? =
        s?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0].coerceIn(0, 30) to it[1].coerceIn(0, 30) }
    /** Each controller's mapping, sideways mapping, Home to the game, sideways and own deadzone — in the prefs' order. */
    fun prefLines(all: Map<String, *>): List<String> = all.flatMap { (k, v) ->
        listOfNotNull(
            if (k.startsWith("map_") && v is String) "E map ${k.removePrefix("map_")} $v" else null,
            if (k.startsWith("smap_") && v is String) "E smap ${k.removePrefix("smap_")} $v" else null,
            if (k.startsWith("homeg_") && v == true) "E home ${k.removePrefix("homeg_")} 0" else null,
            if (k.startsWith("side_") && v == true) "E side ${k.removePrefix("side_")} 1" else null,
            // dev build 16 (theme E): each controller's own deadzone
            if (k.startsWith("sdz_") && v is String) parseSticks(v)?.let { (l, r) -> "E dz ${k.removePrefix("sdz_")} $l $r" } else null,
        )
    }
    /** dev build 15 (WP2): each controller's own face, the whole table in ONE line (absent = Automatic; empty = none chose). */
    fun pfacesLine(all: Map<String, *>): String = ("E pfaces " + all.mapNotNull { (k, v) ->
        if (!k.startsWith("pface_") || v !is String) return@mapNotNull null
        FaceChoice.ofStore(v).takeIf { it != FaceChoice.AUTO }?.let { "${k.removePrefix("pface_")}=${it.wire}" }
    }.take(64).joinToString(",")).trimEnd()
}
