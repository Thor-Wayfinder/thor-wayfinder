package app.wayfinder

import org.json.JSONArray
import org.json.JSONObject

/**
 * Build 19 (the backup gap): what a backup holds of the connected controllers, and how a restore writes it back. Pure
 * Kotlin and org.json, no Android types: BackupRulesTest loads it. [Backup] reads and writes the files.
 *
 * The controllers' group is three files: thor_padprof (a controller's own Game controls), thor_padprof_off (the ones
 * switched off, D7) and thor_extpads, whose keys are of three classes ([classOf]):
 *  - SETTING: set by a switch, a page or the setup wizard. Restored as the backup has them; one the backup lacks goes back
 *    to its default, which is an absent key.
 *  - MARK: "adopted_<key>", this unit took its kind's settings once. Restored as a union, never removed: removing one runs
 *    the adoption again (its kind's old values come back over settings the user reset, ExtPadsCtl.adoptKindSettings).
 *  - RUNTIME: the identity table ("id_"), the names AYN's service confirmed ("hold_names"), AYN's self-test
 *    ("aynhold_ok_"). They describe this device now: never in a backup, never touched by a restore.
 * A key no class knows (a later build's) is never in a backup and stays as it is on a restore.
 *
 * The group is written even when it is empty, and the backup's data says it holds it ([MARKER]): without the marker (a
 * 1.4.1 or 1.5-beta1 backup) a restore leaves every controller file as it is. thor_padprof_prev (Bring back's history)
 * is never in a backup: a restore keeps there what it replaces ([restorePlan]). The envelope stays version 1: older
 * builds read a backup with the group and ignore it (their restore loops over their own file list).
 * 1.5-beta2 (Q12): the Thor's own files are written even when empty too ([Backup.make]), each one its own marker: in the
 * backup = it held that (an empty one clears the file on a restore), not in it = a backup made before (the file is left).
 */
object BackupRules {
    const val PADPROF = "thor_padprof"
    const val PADPROF_OFF = "thor_padprof_off"
    const val PADPROF_PREV = "thor_padprof_prev"
    const val EXTPADS = "thor_extpads"
    /** The controllers' group, as a backup holds it. */
    val PAD_FILES = listOf(PADPROF, PADPROF_OFF, EXTPADS)
    /** In the backup's data: 1 = it holds the controllers' group. */
    const val MARKER = "controllers"
    /** In the backup's data: the version code of the build that made it (a later build can move older own controls). */
    const val APP = "app"
    /** The most own-controls entries a backup that isn't this Thor's gives (its own cap: never crowding out the Thor's). */
    const val MAX_OWN = 500

    enum class Cls { SETTING, MARK, RUNTIME, UNKNOWN }

    /** The global switches of thor_extpads and their type ('b' Boolean, 's' String). */
    private val GLOBAL: Map<String, Char> = mapOf(
        "on" to 'b', "thor_named" to 'b', "dock_swap" to 'b', ExtRules.SEAT_NEW to 'b', "combine" to 'b',
        "ext_first" to 'b', "rank" to 's')
    /** Each controller's settings, "<prefix><controller key>", and their type ("gyro_<key>@<app or game>" too). b19 (H7):
     *  "homet_", its Home's "Game + combos" ([ExtRules.homeRole]). */
    private val PER_PAD: Map<String, Char> = mapOf(
        "map_" to 's', "smap_" to 's', "homeg_" to 'b', "homet_" to 'b', "pface_" to 's', "sdz_" to 's', "side_" to 'b', "own_" to 'b',
        "seat_" to 'b', "tpoff_" to 'b', "gyro_" to 's')
    private const val ADOPTED = "adopted_"
    private const val HOLD_NAMES = "hold_names"
    private val RUNTIME_PREFIXES = listOf("id_", "aynhold_ok_")

    /** The class of a thor_extpads key. A controller's setting or mark with a key that isn't a controller's is UNKNOWN. */
    fun classOf(key: String): Cls {
        if (key in GLOBAL) return Cls.SETTING
        if (key == HOLD_NAMES || RUNTIME_PREFIXES.any { key.startsWith(it) }) return Cls.RUNTIME
        if (key.startsWith(ADOPTED)) return if (ExtRules.KEY.matches(key.removePrefix(ADOPTED))) Cls.MARK else Cls.UNKNOWN
        val pre = PER_PAD.keys.firstOrNull { key.startsWith(it) } ?: return Cls.UNKNOWN
        val rest = key.removePrefix(pre)
        val ok = if (pre == "gyro_" && '@' in rest) {
            val base = rest.substringAfter('@')
            ExtRules.KEY.matches(rest.substringBefore('@')) && base != PadRules.EVERY && PadRules.validBase(base)
        } else ExtRules.KEY.matches(rest)
        return if (ok) Cls.SETTING else Cls.UNKNOWN
    }

    /** The class of a key prefix as the source writes it ("map_", "id_"); a whole key is [classOf]'s. */
    fun prefixClass(prefix: String): Cls = when {
        prefix in PER_PAD -> Cls.SETTING
        prefix == ADOPTED -> Cls.MARK
        prefix in RUNTIME_PREFIXES -> Cls.RUNTIME
        else -> Cls.UNKNOWN
    }

    /** [v] has the type thor_extpads keeps at [key] (a wrong one would throw later, in getBoolean). */
    private fun typed(key: String, v: Any?): Boolean = when (GLOBAL[key] ?: if (key.startsWith(ADOPTED)) 'b' else PER_PAD.entries.firstOrNull { key.startsWith(it.key) }?.value) {
        'b' -> v is Boolean
        's' -> v is String
        else -> false
    }
    private fun setting(k: String, v: Any?) = classOf(k) == Cls.SETTING && typed(k, v)
    private fun mark(k: String, v: Any?) = classOf(k) == Cls.MARK && v == true

    /** A controller's own controls key: "<* | package | package#game>@<controller key>", one "@". */
    fun ownKey(key: String): Boolean = key.count { it == '@' } == 1 &&
        PadRules.validBase(key.substringBefore('@')) && ExtRules.KEY.matches(key.substringAfter('@'))

    /** What a backup holds of group file [name]: thor_extpads' settings and marks only, own controls with a good key. */
    fun forBackup(name: String, all: Map<String, *>): Map<String, Any> = when (name) {
        EXTPADS -> all.filter { (k, v) -> setting(k, v) || mark(k, v) }
        PADPROF -> padprofEntries(all)
        PADPROF_OFF -> all.filter { (k, v) -> v == true && ownKey(k) }
        else -> emptyMap()
    }.mapValues { it.value!! }

    /** The own controls of a thor_padprof map: a good key, a String value. */
    fun padprofEntries(all: Map<String, *>): Map<String, String> =
        all.mapNotNull { (k, v) -> if (v is String && ownKey(k)) k to v else null }.toMap()

    /** Anything of the controllers' in these maps (thor_extpads, thor_padprof): a controller's setting, a mark or own controls.
     *  Build 19 review: not the global switches. The Thor's "Its name in games" and the Controllers page's switches can be set
     *  with no controller ever connected (their setters write false too), and a Thor-only user's sheet stays as in
     *  1.5-beta1. A controller seen once has its mark ("adopted_"). The switches are still backed up and restored. */
    fun holdsControllers(extpads: Map<String, *>, padprof: Map<String, *>): Boolean =
        extpads.any { (k, v) -> (k !in GLOBAL && setting(k, v)) || mark(k, v) } || padprofEntries(padprof).isNotEmpty()

    /** Build 19 review: the controllers' group as [Backup.make] writes it, each file through [forBackup] ([read]: this Thor's
     *  file by name), every file even when empty. Only settings, marks and own controls leave the device: never its
     *  identity table or AYN's lists. */
    fun groupForBackup(read: (String) -> Map<String, *>): Map<String, JSONObject> =
        PAD_FILES.associateWith { encode(forBackup(it, read(it))) }

    // ── the typed values of a backup ("t" + "v"), as Backup.make writes them ──

    fun encode(all: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in all) when (v) {
            is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
            is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
            is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
            is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
            is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
            is Set<*> -> o.put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>())))
        }
        return o
    }

    /** A backup's file as values: only well-typed ones (a "b" whose value isn't a Boolean is left out). */
    fun decode(o: JSONObject?): Map<String, Any> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (k in o.keys()) {
            val x = o.optJSONObject(k) ?: continue
            val v = x.opt("v") ?: continue
            when (x.optString("t")) {
                "s" -> (v as? String)?.let { out[k] = it }
                "b" -> (v as? Boolean)?.let { out[k] = it }
                "i" -> (v as? Number)?.let { out[k] = it.toInt() }
                "l" -> (v as? Number)?.let { out[k] = it.toLong() }
                "f" -> (v as? Number)?.let { out[k] = it.toFloat() }
                "ss" -> (v as? JSONArray)?.let { a -> out[k] = (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }
            }
        }
        return out
    }

    // ── a full restore (a backup of this Thor) ──

    /** One file's writes, in this order: [clear] it, [remove] keys, [put] values (disjoint from [remove]). */
    class FileEdit(val clear: Boolean, val remove: Set<String>, val put: Map<String, Any>)

    /**
     * The controllers' group as a full restore writes it: file name to its edit, in the order they are written (the history
     * first: what the restore replaces is kept before it goes). [marker] false (an older backup): nothing at all. [backup]
     * is the backup's "prefs"; [now] reads a file as it is on the device.
     *  - thor_padprof_prev: each own entry the restore changes or removes is kept on top of its history ([PadRules.keep]),
     *    so Bring back on that controller's tab undoes the restore for it. Nothing else of it changes.
     *  - thor_padprof and thor_padprof_off: replaced (cleared, the backup's written; an empty one in the backup clears it);
     *    an off flag only for an entry the restore writes.
     *  - thor_extpads: never cleared. Its settings become the backup's (one it lacks is removed), its marks a union;
     *    runtime and unknown keys are left alone.
     */
    fun restorePlan(marker: Boolean, backup: JSONObject, now: (String) -> Map<String, *>,
                    onDropped: (String, String) -> Unit = { _, _ -> }): LinkedHashMap<String, FileEdit> {
        val out = LinkedHashMap<String, FileEdit>()
        if (!marker) return out
        val pads = padprofEntries(decode(backup.optJSONObject(PADPROF)))
        val prevNow = now(PADPROF_PREV)
        val prev = keptAfter(now(PADPROF), pads, { k -> PadRules.decodeKept(prevNow[k] as? String) }, onDropped)
        out[PADPROF_PREV] = FileEdit(false, emptySet(), prev.mapValues { PadRules.encodeKept(it.value) })
        out[PADPROF] = FileEdit(true, emptySet(), pads)
        out[PADPROF_OFF] = FileEdit(true, emptySet(),
            decode(backup.optJSONObject(PADPROF_OFF)).filter { (k, v) -> v == true && k in pads })
        out[EXTPADS] = extpadsEdit(now(EXTPADS), decode(backup.optJSONObject(EXTPADS)))
        return out
    }

    /** thor_extpads restored from [backup]: see [restorePlan]. */
    fun extpadsEdit(now: Map<String, *>, backup: Map<String, *>): FileEdit {
        val settings = backup.filter { (k, v) -> setting(k, v) }.mapValues { it.value!! }
        val marks: Map<String, Any> = backup.filter { (k, v) -> mark(k, v) && now[k] != true }.keys.associateWith { true }
        val remove = now.keys.filter { classOf(it) == Cls.SETTING && it !in settings }.toSet()
        return FileEdit(false, remove, settings.filter { (k, v) -> now[k] != v } + marks)
    }

    /** The histories (thor_padprof_prev) after own entries [now] become [new]: each one changed or removed kept on top of
     *  [kept] ([PadRules.keep]: at most [PadRules.KEEP], the same JSON never twice). Only the keys it changes. */
    fun keptAfter(now: Map<String, *>, new: Map<String, String>, kept: (String) -> List<String>,
                  onDropped: (String, String) -> Unit = { _, _ -> }): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        val store = object : PadStore {
            override fun json(key: String): String? = null
            override fun isOff(key: String) = false
            override fun previous(key: String) = out[key] ?: kept(key)
            override fun write(key: String, json: String?, off: Boolean) {}
            override fun writePrevious(key: String, list: List<String>) { out[key] = list }
            override fun dropped(key: String, json: String) = onDropped(key, json)
        }
        for ((k, v) in now) {
            val j = v as? String ?: continue
            if ('@' in k && new[k] != j) PadRules.keep(store, k, j)
        }
        return out
    }

    // ── a backup that isn't this Thor's: a controller's own controls, imported live ──

    /** One own entry imported into [s] (PadProfiles): its [face] and [remap] (already filtered: ProfileShare) over what is
     *  there (the rest of an existing entry stays), with no Thor gyro in it ([PadRules.forPad]). What it replaces is kept
     *  in its history (Bring back). [off] is honoured only as off: an entry switched off here stays off. Never a Thor game
     *  profile: a controller's own controls for a game need none. */
    fun importOwn(s: PadStore, key: String, face: FaceStyle?, remap: PadRemap, off: Boolean) {
        val cur = s.json(key)
        val c = (cur?.let { AppConfig.fromJson(it) } ?: AppConfig()).copy(face = face, remap = remap.takeIf { !it.isEmpty }, madeFrom = null)
        val n = PadRules.forPad(c).toJson()
        if (cur != null && cur != n) PadRules.keep(s, key, cur)
        s.write(key, n, off = off || (cur != null && s.isOff(key)))
    }

    // ── the restore sheet and the file's size ──

    /** What the restore sheet says of the controllers: nothing (THOR: the words a Thor-only user always read), that it
     *  brings them back too, or that the backup is older than them and they stay as they are. */
    enum class Sheet { THOR, WITH_CONTROLLERS, BEFORE_CONTROLLERS }
    /** [marker]: the backup holds the group; [inBackup] / [here]: it holds something of the controllers / this Thor does. */
    fun sheet(marker: Boolean, inBackup: Boolean, here: Boolean): Sheet = when {
        marker && (inBackup || here) -> Sheet.WITH_CONTROLLERS
        !marker && here -> Sheet.BEFORE_CONTROLLERS
        else -> Sheet.THOR
    }
    /** Build 19 review: the sheet for a backup of this Thor ([Backup.parse]): [marker] it holds the group, [prefs] its files,
     *  [here] reads this Thor's file by name. */
    fun restoreSheet(marker: Boolean, prefs: JSONObject, here: (String) -> Map<String, *>): Sheet = sheet(marker,
        inBackup = holdsControllers(decode(prefs.optJSONObject(EXTPADS)), decode(prefs.optJSONObject(PADPROF))),
        here = holdsControllers(here(EXTPADS), here(PADPROF)))

    /** A backup file that a restore can read ([Backup.MAX_BYTES]): a bigger one isn't saved. */
    fun fits(bytes: ByteArray) = bytes.size <= Backup.MAX_BYTES
}
