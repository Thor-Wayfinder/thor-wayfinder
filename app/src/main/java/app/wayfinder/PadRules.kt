package app.wayfinder

/*
 * dev build 16 (WP4): where a connected controller's controls come from — ONE answer for the engine, Game controls' tabs and
 * status, and the controller's page ([PadRules.resolve]; the app's stores in [PadControls]). One spelling of its own controls
 * ([PadRules.ownSpec], the helper's "E uses"), the move of the older own controls (prefs thor_extctl) into PadProfiles
 * ([PadRules.plan]), and the writes of "its own vs the Thor's" (D3, D7) on a [PadStore]. Pure Kotlin, no Android or Compose:
 * the JVM unit tests load it (app/src/test). The root helper uses [PadRules.parseUses].
 */

/** dev build 16 (WP4): where a connected controller's controls come from in a game. */
sealed class PadSource {
    /** Its own controls: the PadProfiles entry [key] ("<base>@<pad>"), found at [level]. */
    data class Own(val key: String, val level: PadLevel) : PadSource()
    /** The Thor's controls for that game (it follows them — what the helper's "E prof" carries). */
    object Thor : PadSource() { override fun toString() = "Thor" }
    /** Its plain buttons (its page says Plain). */
    object Plain : PadSource() { override fun toString() = "Plain" }
}

/** Where own controls sit: one game, all of an app, every game — in that order ([PadRules.below] compares them). */
enum class PadLevel { GAME, APP, EVERY }

/**
 * dev build 16 (WP4): the pad profiles' three stores as the writes see them — [PadProfiles] on its prefs, plain maps in the
 * JVM tests. Values are AppConfig JSON. [isOff]: kept but not played (D7, thor_padprof_off); [previous]: the entries edits
 * and "Remove" replaced (thor_padprof_prev) — nothing is lost silently. dev build 16 review (WP4-1): a short history per
 * key, newest first ([PadRules.KEEP]) — one slot lost the entry a pill had promised at the next edit or Remove.
 */
interface PadStore {
    fun json(key: String): String?
    fun isOff(key: String): Boolean
    /** What edits and "Remove" replaced at [key], newest first (empty = nothing kept). */
    fun previous(key: String): List<String>
    /** [key] written ([json] null = gone) with its off flag (a gone entry has none). */
    fun write(key: String, json: String?, off: Boolean)
    /** [key]'s history as a whole (empty = none kept). */
    fun writePrevious(key: String, list: List<String>)
    /** The oldest kept entry pushed out of a full history ([PadRules.KEEP]): told (a log line), never silent. */
    fun dropped(key: String, json: String) {}
}

object PadRules {
    const val EVERY = "*"

    /** What the helper was told about one controller ("E uses"): [plain] its page says Plain, [own] its own controls (null =
     *  none: the Thor's, or plain). */
    data class Uses(val key: String, val plain: Boolean, val own: String?)
    /** An older own-controls entry read for the move: the pad profile it becomes, and what changed on the way (X9). */
    data class Legacy(val config: AppConfig, val notes: List<String>)
    /** One older entry's fate: moved to [newKey] as [config], or dropped ([newKey] null). [result] goes in the backup.
     *  dev build 16 review (WP4-3): [games] = game-level copies ("pkg#id@pad" → config) an app's entry also becomes. */
    data class Move(val old: String, val spec: String, val newKey: String?, val config: AppConfig?, val result: String,
                    val games: List<Pair<String, AppConfig>> = emptyList())

    fun levelOf(base: String) = when { base == EVERY -> PadLevel.EVERY; '#' in base -> PadLevel.GAME; else -> PadLevel.APP }

    /** The bases looked at, most specific first: a game "pkg#id" → it, its app, every game · an app → it, every game ·
     *  [EVERY] → every game · null (no game app has the controllers: Wayfinder, a launcher, nothing) → none (X3). */
    fun bases(game: String?): List<String> = when {
        game == null -> emptyList()
        game == EVERY -> listOf(EVERY)
        '#' in game -> listOf(game, game.substringBefore('#'), EVERY)
        else -> listOf(game, EVERY)
    }

    /** THE resolver. [enabled]: an own profile exists for that key and is switched on (D7: a switched-off one is skipped —
     *  transparent, X5); [plain]: its page says Plain; [docked]: it plays as the Thor's controller (X4). [game] null only
     *  from the engine: no game app has the controllers (X3, theme E: neutral in Wayfinder and launchers). */
    fun resolve(game: String?, pad: String, enabled: (String) -> Boolean, plain: Boolean, docked: Boolean = false): PadSource {
        if (docked || game == null) return PadSource.Thor
        for (b in bases(game)) { val k = "$b@$pad"; if (enabled(k)) return PadSource.Own(k, levelOf(b)) }
        return if (plain) PadSource.Plain else PadSource.Thor
    }

    /** What it would use without its own controls at [level] (and below it): what D7's pill names — "Use the Thor's again",
     *  "Use its every-game controls", "Use plain buttons". */
    fun below(game: String, pad: String, level: PadLevel, enabled: (String) -> Boolean, plain: Boolean): PadSource =
        resolve(game, pad, { k -> enabled(k) && levelOf(k.substringBefore('@')) > level }, plain)

    /** D8 (Part 3 §4): the PadProfile that beat an older own-controls entry for [base] — one for [pad] at [base]'s level or a
     *  more general one; PadProfiles were looked at first at every level, so that entry never played. null = none. */
    fun winner(base: String, pad: String, existing: Set<String>): String? = bases(base).map { "$it@$pad" }.firstOrNull { it in existing }
    fun overridden(base: String, pad: String, existing: Set<String>) = winner(base, pad, existing) != null

    // ── one spelling of a controller's own controls (X8) ──

    /** The engine's token order — build 15's own-controls order (ExtCtl.tokens), extended: a moved entry comes out byte for
     *  byte as build 15 sent it, and one profile always sends one line (PadRemap.fire's JSON order no longer changes it). */
    private val ORDER = listOf("L", "k", "f", "tr", "sw", "dl", "il", "ir", "xl", "xr", "td", "dzl", "dzr", "ozl", "cvl", "ozr", "cvr",
        "tlo", "thi", "sh", "apl", "apr", "bg")
    private val DEC = Regex("^[1-9][0-9]{0,8}$")
    private val HEX = Regex("^0x[0-9a-fA-F]{1,8}$")
    /** A number as wf_parse reads it (strtol base 0) — hex "0x…" or plain decimal; null for anything else, a leading 0 too
     *  (strtol would read octal). */
    private fun num(s: String): Int? = when {
        s == "0" -> 0
        HEX.matches(s) -> s.drop(2).toLongOrNull(16)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        DEC.matches(s) -> s.toIntOrNull()
        else -> null
    }

    /** [spec] in canonical form: the LAST token of each name wins (as in wf_parse), k / f once per source code, sorted by
     *  [ORDER] then by code; k codes spelled 0x-hex (wf_parse reads both bases). A token the engine doesn't know, or a number
     *  this can't read (a leading 0), stays verbatim at the end: the engine refuses that line either way. Only the order,
     *  overwritten duplicates and the base of k codes change — wf_parse gives the same map (fx/wfmap_test.c checks it). */
    fun canonical(spec: String): String {
        val named = HashMap<String, String>()
        val ks = java.util.TreeMap<Int, String>(); val fs = java.util.TreeMap<Int, String>()
        val tail = ArrayList<String>()
        for (t in spec.split(' ', '\t')) {
            if (t.isEmpty()) continue
            val eq = t.indexOf('=')
            if (eq <= 0) { tail += t; continue }
            val name = t.substring(0, eq); val v = t.substring(eq + 1)
            when {
                name.startsWith("k") -> {
                    val s = num(name.drop(1)); val d = num(v)
                    if (s != null && d != null) ks[s] = "k0x${s.toString(16)}=0x${d.toString(16)}" else tail += t
                }
                name.startsWith("f0") -> num(name.drop(1))?.let { fs[it] = t } ?: run { tail += t }
                name in ORDER -> named[name] = t
                else -> tail += t
            }
        }
        val out = ArrayList<String>()
        for (n in ORDER) when (n) { "k" -> out += ks.values; "f" -> out += fs.values; else -> named[n]?.let { out += it } }
        return (out + tail).joinToString(" ")
    }

    /** A pad profile's own controls as its reader runs them ("E uses"): its face, its controls (its own parts too), Back to
     *  the game when the profile says so (a moved "bg=1") — canonical. X2: no all-games stick shape (the Thor's sticks'
     *  drift) and no app-level Back-to-game merged in here: dev build 17 (critique F2) adds the game's "bg=1" where its reader
     *  is told ([FaceRules.withGameBack]), when this says none. */
    fun ownSpec(c: AppConfig): String = canonical(listOfNotNull(
        c.face?.let { "L=${it.wire}" },
        (c.remap ?: PadRemap()).engineTokens().takeIf { it.isNotEmpty() },
        "bg=1".takeIf { c.backToGame == true },
    ).joinToString(" "))

    // ── the helper's line ──

    /** "E uses <key> <plain 0|1> <its own controls|->" split in words: null = refused (the helper keeps what it had, never
     *  no controls). An empty own (the transport may trim the space) = its own controls, nothing changed. */
    fun parseUses(p: List<String>): Uses? {
        val key = p.getOrNull(2)?.takeIf { ExtRules.KEY.matches(it) } ?: return null
        val plain = when (p.getOrNull(3)) { "0" -> false; "1" -> true; else -> return null }
        val spec = p.drop(4).joinToString(" ")
        if (spec == "-") return Uses(key, plain, null)
        if (spec.length > 1500 || !ExtRules.PROFILE.matches(spec)) return null
        return Uses(key, plain, spec)
    }

    // ── the writes: D3 (a copy on the first edit), D7 (switched off, never deleted), "Remove" ──

    fun enabled(s: PadStore, key: String) = s.json(key) != null && !s.isOff(key)
    /** A pad profile as stored: no Thor gyro in it (a controller's motion aim is its own: ExtPadsCtl.gyroFor). */
    fun forPad(c: AppConfig) = c.copy(remap = c.remap?.forPad()?.takeIf { !it.isEmpty })

    /** D3: the key an edit on [pad]'s tab at [base] goes to. Its own at any level → that level (editing an app-level or
     *  every-game profile from a game edits that level). Else [copy] (what the tab drew: the Thor's, or plain) saved at
     *  [base]'s level, switched on — a switched-off one there first goes to [PadStore.previous] (B5: never lost; dev build 16
     *  review WP4-1: kept on top of what was kept before, never over it). */
    fun ownForEdit(s: PadStore, base: String, pad: String, plain: Boolean, copy: () -> AppConfig): String {
        val src = resolve(base, pad, { enabled(s, it) }, plain)
        if (src is PadSource.Own) return src.key
        val k = "$base@$pad"
        val n = forPad(copy()).toJson()
        s.json(k)?.takeIf { it != n }?.let { keep(s, k, it) }
        s.write(k, n, off = false)
        return k
    }

    /** "Make its own" / "Its own for <page> again" / "Only for <game>": one switched off at [base]'s level comes back as it
     *  was (D7); else [copy] (what it uses now — the Thor's, its app's or every-game own controls, or plain). */
    fun makeOwn(s: PadStore, base: String, pad: String, copy: () -> AppConfig): String {
        val k = "$base@$pad"
        val j = s.json(k)
        if (j != null) { if (s.isOff(k)) s.write(k, j, off = false) }
        else s.write(k, forPad(copy()).toJson(), off = false)
        return k
    }

    /** D7: switched off (kept, not played) or on again. Nothing to do for a key with no profile. */
    fun setEnabled(s: PadStore, key: String, on: Boolean) {
        val j = s.json(key) ?: return
        if (s.isOff(key) == on) s.write(key, j, off = !on)
    }

    /** D7's separate "Remove its own controls": gone — kept in [PadStore.previous]. */
    fun remove(s: PadStore, key: String) {
        val j = s.json(key) ?: return
        keep(s, key, j)
        s.write(key, null, off = false)
    }

    /** B5's "Bring back": the newest kept entry back (switched on); the one it replaces goes to the END of the history —
     *  dev build 16 review (WP4-1): each press goes one further back, and after the oldest the first comes round again
     *  (one kept = a swap, as before). Nothing hidden, nothing dropped. False = nothing kept. */
    fun bringBack(s: PadStore, key: String): Boolean {
        val l = s.previous(key)
        val p = l.firstOrNull() ?: return false
        val cur = s.json(key)
        val rest = l.drop(1).filter { it != cur }
        s.writePrevious(key, if (cur != null && cur != p) rest + cur else rest)
        s.write(key, p, off = false)
        return true
    }

    // ── what edits and "Remove" replaced (dev build 16 review, WP4-1) ──

    /** How many replaced entries a key keeps: past it the OLDEST goes ([PadStore.dropped] tells it). */
    const val KEEP = 5
    /** [json] (what is replaced at [key] now) kept on top; the same JSON kept already moves up — never twice. */
    fun keep(s: PadStore, key: String, json: String) {
        val l = listOf(json) + s.previous(key).filter { it != json }
        l.drop(KEEP).forEach { s.dropped(key, it) }
        s.writePrevious(key, l.take(KEEP))
    }
    /** The history as stored (thor_padprof_prev): a JSON array of AppConfig JSONs, newest first. A single object — the
     *  first build 16's one slot — reads as a history of one; empty / unreadable = none. */
    fun decodeKept(stored: String?): List<String> {
        val t = stored?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        if (!t.startsWith("[")) return listOf(t)
        return runCatching { val a = org.json.JSONArray(t); (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotEmpty() } } }
            .getOrDefault(emptyList())
    }
    fun encodeKept(list: List<String>): String = org.json.JSONArray(list).toString()

    // ── the move of the older own controls (thor_extctl) — once, PadProfiles.migrateExtControls ──

    private const val WAYFINDER = 0xfffe
    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val GAME_ID = Regex("^[a-z0-9-]{1,64}$")
    /** "*", a package, or "pkg#id" (GameDetector's ids). */
    fun validBase(base: String): Boolean = base == EVERY || PKG.matches(base) ||
        ('#' in base && PKG.matches(base.substringBefore('#')) && GAME_ID.matches(base.substringAfter('#')))

    /** §4.1's decisions without prefs: each old "<base>|<pad>" entry → moved (new key, config, notes) or dropped (why).
     *  D8 is judged against [existing] (the PadProfiles keys BEFORE the move: what played), never against the move's own
     *  writes. [thorOf]: the Thor's controls for a base (null for every game) — what "the Thor's job" parts played.
     *  dev build 16 review: [gamesOf] (WP4-3) = an app's games with a Thor game profile — build 15 played an app's entry
     *  there with THAT game's controls for "the Thor's job" parts (its shift layer, chords, stick jobs): a game-level copy
     *  where that differs, unless the game's own older entry or a PadProfile there played instead (D8). [dzBase] (WP4-2) =
     *  the Thor's every-game deadzone now: an older entry with none on a side played none there, its move gets the
     *  every-game one (no "none" in a pad profile) — the backup's result line says so. */
    fun plan(legacy: Map<String, String>, existing: Set<String>, gamesOf: (String) -> List<String> = { emptyList() },
             dzBase: Pair<Int, Int> = 0 to 0, thorOf: (String) -> PadRemap?): List<Move> =
        legacy.toSortedMap().map { (k, spec) ->
            val base = k.substringBeforeLast('|', ""); val pad = k.substringAfterLast('|', "")
            if (!validBase(base) || !ExtRules.KEY.matches(pad)) return@map Move(k, spec, null, null, "dropped: bad key")
            winner(base, pad, existing)?.let { return@map Move(k, spec, null, null, "dropped: $it wins (D8)") }
            val l = fromLegacy(spec, thorOf(base))
            val games = if (levelOf(base) != PadLevel.APP) emptyList() else gamesOf(base).filter { g ->
                levelOf(g) == PadLevel.GAME && g.substringBefore('#') == base && validBase(g) && "$g|$pad" !in legacy && "$g@$pad" !in existing
            }.distinct().mapNotNull { g -> fromLegacy(spec, thorOf(g)).config.takeIf { it != l.config }?.let { "$g@$pad" to it } }
            Move(k, spec, "$base@$pad", l.config, (listOf("moved") + l.notes + deadzoneNotes(spec, dzBase) +
                games.map { "also ${it.first} (that game's Thor's job)" }).joinToString("; "), games)
        }

    /** dev build 16 review (WP4-2): an older entry's side with no deadzone played none; moved, it gets [base] (the Thor's
     *  every-game one) where that is > 0 — a pad profile has no "none" (0 = usual). The Test page can give it its own. */
    fun deadzoneNotes(spec: String, base: Pair<Int, Int>): List<String> {
        val c = ExtCtl.parse(spec)
        return listOfNotNull(
            "left stick deadzone none → the every-game ${base.first} % now (the Test page can give it its own)".takeIf { c.deadL <= 0 && base.first > 0 },
            "right stick deadzone none → the every-game ${base.second} % now (the Test page can give it its own)".takeIf { c.deadR <= 0 && base.second > 0 })
    }

    /**
     * An older own-controls spec ([ExtCtl] tokens) as a pad profile whose [ownSpec] is the same string (byte for byte; the
     * exceptions are noted, X9). [thor] = the Thor's controls for that base (null: every game) — a part given "the Thor's
     * job" (0xfffe) played the Thor's job there live (ExtEngine fell back to the Thor's remap); it becomes a copy of it.
     */
    fun fromLegacy(spec: String, thor: PadRemap?): Legacy {
        val c = ExtCtl.parse(spec)
        val notes = ArrayList<String>()
        fun hex(code: Int) = "0x${code.toString(16)}"
        val by = PadRemap.BY_CODE
        val buttons = LinkedHashMap<ThorButton, RemapTarget>(); val fire = LinkedHashMap<ThorButton, Fire>()
        val alt = LinkedHashMap<ThorButton, RemapTarget>()
        val pads = LinkedHashMap<Int, Int>(); val padFire = LinkedHashMap<Int, Int>()
        val theThors = LinkedHashSet<ThorButton>()               // parts given the Thor's job here
        for ((s, d) in c.map.toSortedMap()) {
            val b = by[s]
            when {
                d == WAYFINDER -> when {
                    b == null -> notes += "k${hex(s)}: the Thor's job on a part the Thor hasn't — itself"
                    thor == null -> notes += "k${hex(s)}: the Thor's job in every game — itself"
                    else -> { thor.buttons[b]?.let { buttons[b] = it }; thor.fire[b]?.let { fire[b] = it }; thor.alt[b]?.let { alt[b] = it }; theThors += b }
                }
                b != null && (d == 0xffff || by[d] != null) -> buttons[b] = if (d == 0xffff) RemapTarget.None else RemapTarget.Button(by[d]!!)
                s == d -> notes += "k${hex(s)}: itself"
                s in PadRemap.PAD_CODES && (d in PadRemap.PAD_CODES || d == 0xffff) -> pads[s] = d
                else -> notes += "k${hex(s)}=${hex(d)}: no such part, dropped"
            }
        }
        for ((s, f) in c.fire.toSortedMap()) {
            val b = by[s]
            when {
                c.map[s] == WAYFINDER -> notes += "f${hex(s)}: its own turbo / toggle on the Thor's job never played, dropped"
                b != null && !b.isDpad -> fire[b] = Fire.values()[f]
                s in PadRemap.PAD_CODES -> padFire[s] = f
                else -> notes += "f${hex(s)}: no such part, dropped"
            }
        }
        var r = PadRemap(buttons = buttons, fire = fire, alt = alt, pads = pads, padFire = padFire, turboHz = c.turboHz,
            swapSticks = c.swapSticks, dpadStick = c.dpadStick, invertLeftY = c.invLY, invertRightY = c.invRY,
            invertLeftX = c.invLX, invertRightX = c.invRX, digitalTriggers = c.digitalTriggers,
            stickL = StickShape(dead = c.deadL), stickR = StickShape(dead = c.deadR),
            // a chord of two parts that both had the Thor's job: the Thor's chord played for them
            chords = thor?.chords?.filter { it.a in theThors && it.b in theThors }.orEmpty())
        var bg: Boolean? = null
        for (t in c.extra) {
            val n = t.substringBefore('='); val v = t.substringAfter('=', ""); val x = num(v)
            when (n) {
                "ozl" -> r = r.copy(stickL = r.stickL.copy(full = x ?: 100))
                "ozr" -> r = r.copy(stickR = r.stickR.copy(full = x ?: 100))
                "cvl" -> r = r.copy(stickL = r.stickL.copy(curve = x ?: 0))
                "cvr" -> r = r.copy(stickR = r.stickR.copy(curve = x ?: 0))
                "tlo" -> r = r.copy(trigStart = x ?: 0)
                "thi" -> r = r.copy(trigFull = x ?: 100)
                "sh" -> {
                    val b = x?.let { by[it] }?.takeIf { it in PadRemap.SHIFTS }
                    if (b == null) notes += "$t: not a shift button, dropped"
                    else {
                        // its shift layer's jobs were the Thor's (ExtEngine fell back to them): a copy when it's the same button
                        r = r.copy(shift = b, shifted = thor?.takeIf { it.shift == b }?.shifted.orEmpty())
                        if (thor?.shift != b) notes += "$t: the Thor's shift button differs here — no jobs with it held"
                    }
                }
                "apl", "apr" -> {
                    val job = if (n == "apl") thor?.jobL else thor?.jobR
                    if (v != "1" || job == null || job.isDefault) notes += "$t: the Thor's stick there is a stick, dropped"
                    else r = if (n == "apl") r.copy(jobL = job) else r.copy(jobR = job)
                }
                "bg" -> if (v == "1") bg = true
            }
        }
        // a part given the Thor's job that the copy no longer gives the app: its reader runs it now (same effect, other bytes)
        for (b in theThors) if (!r.byApp(b)) notes += "${b.name}: the Thor's job there is ${buttons[b]?.token() ?: "itself"} — run by its reader"
        val cfg = AppConfig(face = c.face, remap = r.takeIf { !it.isEmpty }, backToGame = bg)
        if (notes.isEmpty() && ownSpec(cfg) != spec) notes += "spelled differently"
        return Legacy(cfg, notes)
    }
}
