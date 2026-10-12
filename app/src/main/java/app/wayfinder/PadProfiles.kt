package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * A connected controller's OWN controls — for a game, an app or every game: key "<game key | package | *>@<controller
 * key>". Edited on the very same Game controls page as the Thor's (2026-10-03: the other controller's page was a
 * different screen, and Home + X from it showed the Thor's). Only the face buttons and the controls (remap) are used.
 * No entry, or one switched off = it uses what's below ([PadRules.resolve]: its app's or every game's own, its page's
 * Plain, else the Thor's controls). dev build 16 (WP4): the ONE store — the older own controls moved in once
 * ([migrateExtControls]); switched off = kept (thor_padprof_off, D7); what an edit or "Remove" replaced is kept
 * (thor_padprof_prev, B5's "Bring back").
 */
object PadProfiles : PadStore {
    const val EVERY = PadRules.EVERY
    private const val TAG = "ThorPadProfiles"
    @Volatile private var prefs: SharedPreferences? = null
    private var off: SharedPreferences? = null
    private var prev: SharedPreferences? = null
    private var appCtx: Context? = null
    val version = mutableIntStateOf(0)

    fun init(ctx: Context) { if (prefs == null) open(ctx.applicationContext) }
    /** dev build 16 (WP4): one thread opens the stores and runs the once-only passes (ExtPadsCtl and the service may start
     *  together); the others wait — nobody reads the stores half moved. Each pass on its own: one failing leaves the
     *  others (and is tried again at the next start, its flag unset). */
    @Synchronized private fun open(app: Context) {
        if (prefs != null) return
        appCtx = app
        val p = app.getSharedPreferences("thor_padprof", Context.MODE_PRIVATE)
        off = app.getSharedPreferences("thor_padprof_off", Context.MODE_PRIVATE)
        prev = app.getSharedPreferences("thor_padprof_prev", Context.MODE_PRIVATE)
        fun pass(name: String, f: () -> Unit) = runCatching(f).onFailure { android.util.Log.w(TAG, "$name: $it") }
        pass("faces") { dropCopiedFaces(app, p) }          // first: it must see the older own controls as they were
        pass("extctl") { migrateExtControls(app, p) }
        pass("gyro") { stripPadGyro(app, p) }
        pass("star") { dropStarApp(app) }
        prefs = p
    }

    /**
     * dev build 15 (critique M1), once: "Give it its own controls" copied the Thor's face for that game into a controller's
     * own controls. A controller's own face now beats the game's (WP2, theme E), so that copy kept the Thor's face on it —
     * a DualSense's ✕ didn't confirm in the very game it had its own controls for. A face equal to the Thor's there is
     * dropped (the controller's page decides); one chosen differently stays. The same for the older own controls
     * ([ExtControls], "Start from the Thor's"). The entries as they were are kept in thor_padprof_b14 for one release.
     */
    private fun dropCopiedFaces(app: Context, p: SharedPreferences) {
        val bak = app.getSharedPreferences("thor_padprof_b14", Context.MODE_PRIVATE)
        if (bak.getBoolean("faces_done", false)) return
        AppConfigStore.init(app); GameProfiles.init(app)            // the Thor's faces (idempotent)
        val old = HashMap<String, String>()
        val e = p.edit()
        for ((k, v) in p.all) {
            if (v !is String || !isPad(k)) continue
            val c = AppConfig.fromJson(v)
            if (!FaceRules.copiedThorFace(c.face, Profiles.get(baseOf(k)).face)) continue
            old[k] = v; e.putString(k, c.copy(face = null).toJson())
        }
        if (old.isNotEmpty()) e.commit()
        val ext = ExtControls.dropCopiedFaces(app) { game -> Profiles.get(game).face }
        // the changes first, then the backup and the flag: a death in between redoes nothing harmful (the faces are gone)
        bak.edit().apply {
            old.forEach { (k, v) -> putString("p:$k", v) }
            ext.forEach { (k, v) -> putString("e:$k", v) }
            putBoolean("faces_done", true)
        }.commit()
        if (old.isNotEmpty() || ext.isNotEmpty()) android.util.Log.i(TAG, "a copied Thor's face dropped: ${old.keys + ext.map { it.first }}")
    }

    /**
     * dev build 16 (WP4), once: the older own controls (thor_extctl, "<base>|<pad>" → engine tokens) become pad profiles —
     * ONE store, one resolver. D8 = what played stays: PadProfiles were looked at first at every level, so an older entry
     * with a PadProfile for that controller at its level or a more general one never played — dropped ([PadRules.plan],
     * judged against the profiles BEFORE this pass). A moved entry sends the same "E uses" spec byte for byte
     * ([PadRules.fromLegacy]; the exceptions are noted). Order: new profiles → backup (thor_padprof_b16 "e:" / "r:", one
     * release) → thor_extctl emptied → flag. A death before the flag runs it again: what moved is then an existing profile
     * ("dropped (D8)"), the same final state.
     */
    private fun migrateExtControls(app: Context, p: SharedPreferences) {
        val bak = app.getSharedPreferences("thor_padprof_b16", Context.MODE_PRIVATE)
        if (bak.getBoolean("extctl_done", false)) return
        val old = ExtControls.legacy(app)
        if (old.isNotEmpty()) {
            AppConfigStore.init(app); GameProfiles.init(app)        // the Thor's controls: what "the Thor's job" parts played
            val existing = p.all.keys.filter { isPad(it) }.toSet()
            // dev build 16 review: an app or game with no Thor controls played those parts as themselves — an empty remap,
            // not "every game"; each game with a Thor game profile played its own (WP4-3); the every-game deadzone (WP4-2)
            val moves = PadRules.plan(old, existing, { pkg -> GameProfiles.forApp(pkg).map { it.first } }, everyGameDeadzone(app)) { base ->
                if (base == EVERY) null else PadControls.thorConfigFor(base).remap ?: PadRemap() }
            p.edit().apply { moves.forEach { m ->
                if (m.newKey != null && m.config != null) putString(m.newKey, PadRules.forPad(m.config).toJson())
                m.games.forEach { (gk, c) -> putString(gk, PadRules.forPad(c).toJson()) }
            } }.commit()
            bak.edit().apply { moves.forEach { putString("e:${it.old}", it.spec); putString("r:${it.old}", it.result) } }.commit()
            ExtControls.clearLegacy(app, old.keys)
            android.util.Log.i(TAG, "own controls moved: ${moves.count { it.newKey != null }} (+${moves.sumOf { it.games.size }} game copies), dropped: ${moves.count { it.newKey == null }} " +
                moves.joinToString(" · ") { "${it.old} → ${it.result}" }.take(600))
        }
        bak.edit().putBoolean("extctl_done", true).commit()
    }
    /** The Thor's every-game deadzone as stored (thor_settings "stick_defaults") — read here, AppSettings may not be loaded. */
    private fun everyGameDeadzone(app: Context): Pair<Int, Int> = runCatching {
        val r = PadRemap.fromJson(org.json.JSONObject(app.getSharedPreferences("thor_settings", Context.MODE_PRIVATE).getString("stick_defaults", "{}") ?: "{}")) ?: PadRemap()
        r.stickL.dead.coerceIn(0, 30) to r.stickR.dead.coerceIn(0, 30)
    }.getOrDefault(0 to 0)

    /** dev build 16 (WP4, critique M4), once: a Thor gyro in a controller's own controls never played (the helper runs a
     *  controller's motion aim from its page, ExtPadsCtl.gyroFor) — taken out, not turned into a motion aim (that would
     *  switch motion on suddenly). The entries as they were: thor_padprof_b16 "g:". */
    private fun stripPadGyro(app: Context, p: SharedPreferences) {
        val bak = app.getSharedPreferences("thor_padprof_b16", Context.MODE_PRIVATE)
        if (bak.getBoolean("gyro_done", false)) return
        val changed = p.all.mapNotNull { (k, v) ->
            val j = v as? String ?: return@mapNotNull null
            val c = AppConfig.fromJson(j)
            if (!isPad(k) || c.remap?.gyro?.let { it != GyroSettings() } != true) null else Triple(k, j, PadRules.forPad(c).toJson())
        }
        if (changed.isNotEmpty()) {
            // dev build 16 review (WP4-4): the backup first, then the strip — a death in between redoes the same backup
            bak.edit().apply { changed.forEach { (k, j, _) -> putString("g:$k", j) } }.commit()
            p.edit().apply { changed.forEach { (k, _, n) -> putString(k, n) } }.commit()
            android.util.Log.i(TAG, "a Thor gyro taken out of own controls: ${changed.map { it.first }}")
        }
        bak.edit().putBoolean("gyro_done", true).commit()
    }

    /** dev build 16 (WP4), once: Game controls' More on a controller's every-game controls wrote an app setting for a
     *  package named "*" (input layer, follow-move) that nothing reads — removed (thor_padprof_b16 "a:*"). */
    private fun dropStarApp(app: Context) {
        val bak = app.getSharedPreferences("thor_padprof_b16", Context.MODE_PRIVATE)
        if (bak.getBoolean("star_done", false)) return
        AppConfigStore.init(app)
        val c = AppConfigStore.get(EVERY)
        if (!c.isDefault) {
            bak.edit().putString("a:$EVERY", c.toJson()).commit()
            AppConfigStore.set(EVERY, AppConfig())
            android.util.Log.i(TAG, "the \"*\" app setting removed")
        }
        bak.edit().putBoolean("star_done", true).commit()
    }

    fun isPad(key: String) = '@' in key
    fun key(base: String, pad: String) = "$base@$pad"
    fun baseOf(key: String) = key.substringBefore('@')
    fun padOf(key: String) = key.substringAfter('@')

    fun has(key: String): Boolean = prefs?.contains(key) == true
    fun get(key: String): AppConfig = prefs?.getString(key, null)?.let { AppConfig.fromJson(it) } ?: AppConfig()
    /** dev build 16 (WP4, D7): it exists and is switched on — only then it plays ([PadRules.resolve]). */
    fun enabled(key: String): Boolean = has(key) && !isOff(key)
    /** null = gone (only [remove] does that from a page: D7, nothing goes silently). An own entry stays even with nothing
     *  changed: "its own, plain" is a choice too. A switched-off one stays switched off. */
    fun set(key: String, c: AppConfig?) = write(key, c?.let { PadRules.forPad(it).toJson() }, off = c != null && isOff(key))
    fun update(key: String, change: (AppConfig) -> AppConfig) = set(key, change(get(key)))
    /** D7: switched off (kept, not played) / on again. */
    fun setEnabled(key: String, on: Boolean) = PadRules.setEnabled(this, key, on)
    /** D7's "Remove its own controls": gone, kept in thor_padprof_prev. */
    fun remove(key: String) = PadRules.remove(this, key)
    /** B5's "Bring back": what an edit or "Remove" replaced, back in its place (the newest first; again = one further back). */
    fun bringBack(key: String): Boolean = PadRules.bringBack(this, key)

    // ── the store as [PadRules]' writes see it ──
    override fun json(key: String): String? = prefs?.getString(key, null)
    override fun isOff(key: String): Boolean = off?.getBoolean(key, false) == true
    /** dev build 16 review (WP4-1): a short history, newest first ([PadRules.decodeKept]: build 16's first one slot reads as one). */
    override fun previous(key: String): List<String> = PadRules.decodeKept(prev?.getString(key, null))
    override fun write(key: String, json: String?, off: Boolean) {
        val p = prefs ?: return
        p.edit().apply { if (json == null) remove(key) else putString(key, json) }.apply()
        this.off?.edit()?.apply { if (off && json != null) putBoolean(key, true) else remove(key) }?.apply()
        changed()
    }
    override fun writePrevious(key: String, list: List<String>) {
        prev?.edit()?.apply { if (list.isEmpty()) remove(key) else putString(key, PadRules.encodeKept(list)) }?.apply()
    }
    override fun dropped(key: String, json: String) {
        // release: neither the key (it names the app or game and the controller) nor the controls — only that it happened
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, "$key: the oldest of its ${PadRules.KEEP} kept controls dropped: ${json.take(400)}")
        else android.util.Log.w(TAG, "the oldest of ${PadRules.KEEP} kept controls dropped")
    }
    private fun changed() {
        games = null
        version.intValue++
        appCtx?.let { ExtPadsCtl.refreshOwn(it) }
    }

    /** critique m7: the apps with own controls for one of their games — the service's tick asks twice a second (game
     *  detection for them), so kept until the next change. */
    @Volatile private var games: Set<String>? = null
    fun hasGamesOf(pkg: String): Boolean {
        val g = games ?: (prefs?.all?.keys.orEmpty().filter { isPad(it) && '#' in baseOf(it) }.map { GameProfiles.pkgOf(it) }.toSet()).also { games = it }
        return pkg in g
    }
    /** Every own entry of [pad] (on or off). */
    fun keysFor(pad: String): List<String> = prefs?.all?.keys.orEmpty().filter { isPad(it) && padOf(it) == pad }

    /** A unit seen for the first time: its kind's own controls ("<base>@<kind>", earlier builds kept them per kind) become
     *  its own where it has none — switched off ones stay off. Returns how many. Replaces ExtControls.adoptKind. */
    fun adoptKind(kind: String, unit: String): Int {
        val p = prefs ?: return 0
        val e = p.edit(); val o = off?.edit(); var n = 0
        for ((k, v) in p.all) {
            if (v !is String || !isPad(k) || padOf(k) != kind) continue
            val nk = key(baseOf(k), unit)
            if (p.contains(nk)) continue
            e.putString(nk, v); if (isOff(k)) o?.putBoolean(nk, true); n++
        }
        if (n > 0) { e.apply(); o?.apply(); games = null; version.intValue++ }
        return n
    }
}
