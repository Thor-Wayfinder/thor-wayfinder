package app.wayfinder

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Per-game profiles — WHICH game an app is running. Runs INSIDE
 * the root helper ([InputMonitorTool], uid 0): `D <pkg>` → `D <pkg> <game-id> <title>` (or `D <pkg>`
 * when it can't tell). Whatever launched the game (Cocoon, another launcher, the emulator's own
 * library), in this order (verified 2026-09-24 on the Thor):
 *  1. RetroArch — the newest content-history entry for the core it has loaded (the history
 *     isn't rewritten when a game is played again, and a zipped game isn't kept open).
 *  2. GameNative — the active container link `xuser → xuser-STEAM_<appid>` while a Windows game
 *     runs; the name from its own database (a copy — never opened in place).
 *  3. Any emulator — the game file it keeps open (Dolphin .rvz, PPSSPP .iso, Azahar .3ds…). b18i (D2): one that keeps
 *     several open (Eden: its whole library) — the one it is reading ([GameReading]: offsets, mappings).
 *  4. Cocoon's record of what it launched in that app (melonDS and others that close the file).
 */
object GameDetector {
    private const val TAG = "ThorGame"
    private val EXT = Regex("""\.(rvz|iso|gcm|gcz|wbfs|wia|ciso|3ds|cci|cia|cxi|app|nsp|xci|nca|chd|cso|pbp|cue|bin|img|m3u|nds|dsi|z64|n64|v64|sfc|smc|gba|gbc|gb|nes|fds|md|gen|smd|sms|gg|pce|ws|wsc|ngp|zip|7z|wua|wux|rpx|elf|vpk|cdi|gdi)$""", RegexOption.IGNORE_CASE)
    internal val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val gnNames = HashMap<String, String>()

    /** The helper's answer line for [pkg]. */
    fun answer(pkg: String): String {
        if (!PKG.matches(pkg)) return "D -"
        val r = runCatching { detect(pkg) }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "detect $pkg: $it") }.getOrNull()
        if (BuildConfig.DEBUG) Log.d(TAG, "$pkg → $r")
        // a title comes from file names / other apps' data: no control character may reach the
        // app's line protocol (a \r would split the line into forged commands)
        return if (r == null) "D $pkg" else "D $pkg ${r.first} ${clean(r.second)}"
    }

    private val CTRL = Regex("\\p{Cntrl}")
    private fun clean(s: String) = s.replace(CTRL, " ").replace(Regex(" +"), " ").trim().take(120).ifEmpty { "Game" }

    /** A file another app can write, read only if it's small (no multi-GB file read as root). */
    private fun smallText(f: File, max: Long): String? = f.takeIf { it.isFile && it.length() in 1..max }?.readText()

    /** (game id, title) — the id is stable (profiles are stored under it). */
    fun detect(pkg: String): Pair<String, String>? {
        val pids = pidsOf(pkg)
        if (pids.isEmpty()) return null
        val started = pids.minOf { startMs(it) }
        // the id comes from what really runs (stable whatever launched it); Cocoon's name, when it
        // launched this session, is nicer than a file name ("Mario Kart 8 Deluxe" vs "… V2.1.0 Incl. Dlc …")
        val nice = cocoon(pkg, started)
        // RetroArch and GameNative have their own reliable answer: until it's there (content not
        // loaded, wine still starting) there's no game — Cocoon's name would give ANOTHER id
        val own = pkg.startsWith("com.retroarch") || pkg == "app.gamenative"
        val found = (if (pkg.startsWith("com.retroarch")) retroarch(pids) else null)
            ?: (if (pkg == "app.gamenative") gameNative(pids) else null)
            ?: (if (own) return null else chooseGame(pkg, pids, nice))
            ?: return nice
        // b18i (D2): the app reads another game than the one Cocoon names — no answer, not Cocoon's either
        if (found === CANT_TELL) return null
        return found.first to (nice?.second ?: found.second)
    }

    /** [chooseGame]'s "can't tell — not even Cocoon's name" (compared by identity). */
    private val CANT_TELL = Pair("", "")

    private fun fileGame(path: String): Pair<String, String> {
        val base = path.substringAfterLast('/').replace(EXT, "").trim()
        return idOf(base) to tidy(base)
    }

    /** A file name made readable: no [tags] / (regions), versions, "Incl. DLC", scene suffixes. */
    fun tidy(name: String): String = name
        .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        .replace(Regex("""(?i)\bv\d+(\.\d+)*\b.*$"""), " ")
        .replace(Regex("""(?i)\b(incl\.?|dlc|update|supernsp|decrypted)\b.*$"""), " ")
        .replace('_', ' ').replace(Regex("""\s+"""), " ").trim(' ', '-', '.')
        .let { t -> if (' ' !in t && t.count { it == '-' } >= 2)          // the-legend-of-zelda → The Legend Of Zelda
            t.split('-').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else t }
        .ifEmpty { name }

    /** A stable id from a name: lower-case letters and digits, dashes between. */
    fun idOf(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64).ifEmpty { "game" }

    /** A user's game file (the user's files only — not an emulator's own: Eden keeps system .nca files open). */
    private fun userGame(t: String) = (t.startsWith("/storage/") || t.startsWith("/sdcard/") || t.startsWith("/data/media/") || t.startsWith("/mnt/")) &&
        "/Android/data/" !in t && "/Android/obb/" !in t && EXT.containsMatchIn(t)

    /** b18i (D2): one open game file — the process, its descriptor, the file and the game its name gives. */
    private class OpenFd(val pid: Int, val fd: String, val path: String, val game: Pair<String, String>)

    /** Every game file descriptor the app keeps open ([userGame]). */
    private fun openFds(pids: List<Int>): List<OpenFd> {
        val out = ArrayList<OpenFd>()
        for (pid in pids) {
            val fds = File("/proc/$pid/fd").list() ?: continue
            for (fd in fds) {
                val t = runCatching { Os.readlink("/proc/$pid/fd/$fd") }.getOrNull() ?: continue
                if (userGame(t)) out += OpenFd(pid, fd, t, fileGame(t))
            }
        }
        return out
    }

    /** Every game the app keeps open, one per game id. */
    private fun openGames(fds: List<OpenFd>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        fds.forEach { out[it.game.first] = it.game }
        return out.values.toList()
    }

    /** 1.4: the game file the app's task was LAUNCHED with (a launcher's intent: `dat=…game.xci`), cached per process. */
    private val launchCache = HashMap<String, Pair<String, Pair<String, String>?>>()
    private val DAT = Regex(""" dat=(\S+)""")
    private fun launchedWith(pkg: String, pids: List<Int>): Pair<String, String>? {
        val key = pids.sorted().joinToString(",")
        launchCache[pkg]?.let { (k, v) -> if (k == key) return v }
        val v = runCatching {
            val p = ProcessBuilder("dumpsys", "activity", "recents").redirectErrorStream(true).start()
            // at most 400 000 characters are read (never the whole dump), then the process is ended
            val text = p.inputStream.bufferedReader().use { r ->
                val buf = CharArray(400_000); var n = 0
                while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                String(buf, 0, n)
            }
            p.destroy(); text
        }.getOrNull()?.lineSequence()
            ?.filter { "cmp=$pkg/" in it && " dat=" in it }
            ?.mapNotNull { l -> DAT.find(l)?.groupValues?.get(1) }
            ?.map { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.firstOrNull { EXT.containsMatchIn(it.substringBefore('?')) }
            ?.let { fileGame(it.substringBefore('?')) }
        launchCache[pkg] = key to v
        return v
    }

    /** Per app: the process ids and the game files open at the last look — and the game a newly opened file named. */
    private val lastOpen = HashMap<String, Pair<String, Set<String>>>()
    private val newlyOpened = HashMap<String, Pair<String, String>>()

    /** Which of the open game files is the one running (see the class comment). Null = can't tell — better than wrong.
     *  b18i (D2): [CANT_TELL] = not even Cocoon's name (the app reads another game than it). */
    private fun chooseGame(pkg: String, pids: List<Int>, nice: Pair<String, String>?): Pair<String, String>? {
        val fds = openFds(pids)
        val open = openGames(fds)
        // 1.4 (review): a game opened since the last look (same process) is the one now running
        val pidKey = pids.sorted().joinToString(","); val ids = open.map { it.first }.toSet()
        val prev = lastOpen[pkg]; lastOpen[pkg] = pidKey to ids
        if (prev == null || prev.first != pidKey) newlyOpened.remove(pkg)
        else if (prev.second != ids) {
            newlyOpened.remove(pkg); launchCache.remove(pkg)
            open.filter { it.first !in prev.second }.singleOrNull()?.let {
                newlyOpened[pkg] = it
                // b18i (D2): the newest file opened is the one read, once that rule ends — held by its descriptors (review)
                reading[pkg] = pidKey to GameReading.Kept(idOf(it.second), fds.filter { f -> f.game.first == it.first }.map { f -> "${f.pid}/${f.fd}" }.toSet())
            }
        }
        newlyOpened[pkg]?.let { g -> if (g.first in ids) return g else newlyOpened.remove(pkg) }
        val launched = launchedWith(pkg, pids)
        if (launched != null && (open.isEmpty() || open.any { it.first == launched.first })) return launched
        if (open.size == 1) return open[0]
        if (open.isEmpty()) return null
        // several open (a library kept open): the one whose name matches what Cocoon launched
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val want = nice?.let { words(it.second) }.orEmpty()
        fun score(g: Pair<String, String>) = (words(g.second) intersect want).size
        val byName = if (nice == null) null else open.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        // Cocoon names a game none of the open files' names match: its own name answers ([detect]), as before
        if (nice != null && byName == null) return null
        // b18i (review): the files its name matches as well as the one it picked (a DLC named after its game) — one game
        val ties = byName?.let { b -> score(b).let { best -> open.filter { score(it) == best }.map { it.first }.toSet() } }.orEmpty()
        // b18i (D2, "detect harder", g1 BUG-2): the one the app is reading
        return readingGame(pkg, pidKey, pids, fds, open, byName, ties)
    }

    // ── b18i (D2): which of several open game files the app reads ([GameReading]) ──

    /** Per app: the last look at its open game files — its processes, when (elapsed ms), each descriptor's offset, and the game
     *  files mapped (their tidied names' ids). And the game last seen read, per process, with what showed it ([GameReading.Kept]).
     *  Only the helper's detector thread touches them. */
    private class Look(val pids: String, val at: Long, val fds: List<GameReading.Fd>, val mapped: Set<String>)
    private val looks = HashMap<String, Look>()
    private val reading = HashMap<String, Pair<String, GameReading.Kept>>()
    private val lastWhy = HashMap<String, Pair<GameReading.Why, String?>>()
    /** A look this recent (same processes) is the first of the two: no wait. */
    private const val LOOK_FRESH_MS = 30_000L
    /** Else the two looks are this far apart (the helper's detector thread, never the app's). */
    private const val LOOK_GAP_MS = 250L

    /** The offset of [pid]'s descriptor [fd] (`/proc/<pid>/fdinfo/<fd>` "pos"); null: gone or unreadable. */
    private fun posOf(pid: Int, fd: String): Long? = runCatching {
        File("/proc/$pid/fdinfo/$fd").useLines { l -> l.firstOrNull { it.startsWith("pos:") }?.substringAfter(':')?.trim()?.toLong() }
    }.getOrNull()

    /** One look: each descriptor still on its file, and its offset. */
    private fun look(fds: List<OpenFd>): List<GameReading.Fd> = fds.mapNotNull { f ->
        if (runCatching { Os.readlink("/proc/${f.pid}/fd/${f.fd}") }.getOrNull() != f.path) return@mapNotNull null
        posOf(f.pid, f.fd)?.let { GameReading.Fd("${f.pid}/${f.fd}", f.game.first, it) }
    }

    /** A maps line's file (after address, perms, offset, device and inode). */
    private val MAP_FILE = Regex("""^\S+\s+\S+\s+\S+\s+\S+\s+\d+\s+(/.+)$""")

    /** The game files mapped in [pids] (`/proc/<pid>/maps`), as their tidied names' ids (one per game: [GameReading.Families]). */
    private fun mappedGames(pids: List<Int>): Set<String> {
        val out = HashSet<String>()
        for (pid in pids) runCatching {
            File("/proc/$pid/maps").useLines { lines ->
                for (l in lines) {
                    if ("/storage/" !in l && "/sdcard/" !in l && "/data/media/" !in l && "/mnt/" !in l) continue
                    val path = MAP_FILE.find(l)?.groupValues?.get(1) ?: continue
                    if (userGame(path)) out += idOf(fileGame(path).second)
                }
            }
        }
        return out
    }

    /** The game [pkg] reads among the several it keeps open ([open], [fds]); [byName] the one Cocoon's record names, [ties] the
     *  files its name matches as well. Two looks at the descriptors' offsets: the previous call's (≤ [LOOK_FRESH_MS] ago, same
     *  processes) and now — else now and [LOOK_GAP_MS] later (not when Cocoon's name answers: no wait added to it). Null: can't
     *  tell (Cocoon's name, if any, answers); [CANT_TELL]: no answer at all. */
    private fun readingGame(pkg: String, pidKey: String, pids: List<Int>, fds: List<OpenFd>, open: List<Pair<String, String>>,
                            byName: Pair<String, String>?, ties: Set<String>): Pair<String, String>? {
        val before = looks[pkg]?.takeIf { it.pids == pidKey }
        var first = look(fds)
        val moved: List<GameReading.Fd>
        if (before != null && SystemClock.elapsedRealtime() - before.at <= LOOK_FRESH_MS) moved = GameReading.moved(before.fds, first)
        // Cocoon names an open file: its answer now, as fast as before (the next ask compares with this look)
        else if (byName != null) moved = emptyList()
        else {
            // this process's first look (or an old one): the second one a moment later
            Thread.sleep(LOOK_GAP_MS)
            val second = look(fds)
            moved = GameReading.moved(first, second)
            first = second
        }
        val mappedNow = mappedGames(pids)
        looks[pkg] = Look(pidKey, SystemClock.elapsedRealtime(), first, mappedNow)
        // b18i (review): one game, several files (base / update / DLC, a disc's tracks) — the rules work on games, not files
        val fam = GameReading.Families(open.associate { it.first to idOf(it.second) }, byName?.first, ties)
        val openFam = open.mapNotNull { fam.ofFile(it.first) }.toSet()
        val readOn = moved.mapNotNull { f -> fam.ofFile(f.game)?.let { f.copy(game = it) } }
        val read = readOn.map { it.game }.toSet()
        val mappedFam = mappedNow.map { fam.of(it) }.toSet()
        val mapped = GameReading.newlyMapped(before?.mapped?.map { fam.of(it) }?.toSet(), mappedFam)
        // b18i (review): the game remembered in this process while what showed it lasts — a descriptor it was read on, its
        // mapping. Quit to the app's own list, it reads nothing, like a paused game; kept while its file stayed open, it went on
        // answering there (Eden keeps its whole library open)
        val fdsNow = first.mapNotNull { f -> fam.ofFile(f.game)?.let { f.fd to it } }.toMap()
        val was = reading[pkg]?.takeIf { it.first == pidKey }?.second?.let { k -> k.copy(game = fam.of(k.game), next = k.next?.let { fam.of(it) }) }
        val kept = GameReading.lasting(was, fdsNow, mappedFam)
        val c = GameReading.choose(openFam, read, mapped, kept?.game, byName?.let { fam.ofFile(it.first) }, kept?.next)
        val k = GameReading.remember(c, kept, readOn, mappedFam)
        if (k == null) reading.remove(pkg) else reading[pkg] = pidKey to k
        // release log on a change only, no app or file name (the device check: which rule named it, or why none, and what
        // holds the game remembered)
        val gone = if (was != null && kept == null) ", what held the last game gone" else ""
        val held = k?.let { " (held by ${it.fds.size} descriptors" + (if (it.mapped) ", mapped)" else ")") } ?: ""
        if (lastWhy.put(pkg, c.why to c.game) != c.why to c.game)
            Log.w(TAG, "D2: ${open.size} game files open (${openFam.size} games), ${read.size} read, ${mapped.size} newly mapped$gone → ${c.why}$held")
        if (BuildConfig.DEBUG) Log.d(TAG, "D2 $pkg: read $read, mapped $mapped (of $mappedFam), kept $was → $k → ${c.game} (${c.why})")
        if (c.veto) return CANT_TELL
        // the family's file: Cocoon's pick in it, else its first id (stable whatever order the app opened its files in)
        return c.game?.let { g -> fam.fileOf(g) }?.let { id -> open.firstOrNull { it.first == id } }
    }

    private fun retroarch(pids: List<Int>): Pair<String, String>? {
        // the core it runs now (none loaded = its menu, no game)
        val core = pids.firstNotNullOfOrNull { p ->
            runCatching { File("/proc/$p/maps").useLines { l -> l.map { it.substringAfterLast(' ') }.firstOrNull { it.endsWith("_libretro_android.so") } } }.getOrNull()
        }?.substringAfterLast('/') ?: return null
        val f = listOf("/storage/emulated/0/RetroArch/playlists/builtin/content_history.lpl",
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/playlists/builtin/content_history.lpl",
            "/storage/emulated/0/Android/data/com.retroarch/files/playlists/builtin/content_history.lpl")
            .map { File(it) }.filter { it.isFile }.maxByOrNull { it.lastModified() } ?: return null
        // its newest entry for that core (the history isn't rewritten when the same game is played again)
        val items = org.json.JSONObject(smallText(f, 2_000_000) ?: return null).optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val e = items.optJSONObject(i) ?: continue
            val path = e.optString("path")
            if (path.isNotEmpty() && e.optString("core_path").endsWith(core)) return fileGame(path)
        }
        return null
    }

    private fun gameNative(pids: List<Int>): Pair<String, String>? {
        // a Windows game runs (wine) — otherwise the link just names the last one played
        val running = pids.any { p -> runCatching { File("/proc/$p/cmdline").readText() }.getOrDefault("").let { it.contains(".exe", true) || it.contains("wine") } }
        if (!running) return null
        val home = "/data/data/app.gamenative/files/imagefs_shared/home"
        val link = runCatching { Os.readlink("$home/xuser") }.getOrNull() ?: return null
        val id = link.substringAfterLast("xuser-", "").ifEmpty { return null }        // STEAM_1687950, GOG_…
        val name = gnNames.getOrPut(id) { gameNativeName(id) ?: id }
        return idOf(id) to name
    }

    private val DB_FILES = listOf("g.db", "g.db-wal", "g.db-shm", "g.db-journal")

    /** Remove one of OUR temp folders: only a real folder owned by root, only the files we make
     *  (never a recursive delete — a planted symlink would make root delete what it points to). */
    private fun dropTemp(dir: File) {
        val st = runCatching { Os.lstat(dir.path) }.getOrNull() ?: return
        if (!android.system.OsConstants.S_ISDIR(st.st_mode) || st.st_uid != 0) return
        DB_FILES.forEach { runCatching { Os.remove(File(dir, it).path) } }
        runCatching { Os.remove(dir.path) }   // remove() = rmdir for an empty folder
    }

    private fun gameNativeName(id: String): String? {
        val src = File("/data/data/app.gamenative/databases/pluvia.db").takeIf { it.isFile && it.length() < (256L shl 20) } ?: return null
        // leftovers of a helper killed mid-copy
        File("/data/local/tmp").listFiles { f -> f.name.startsWith("wf_gn_") }?.forEach { dropTemp(it) }
        // a fresh root-only folder with an unguessable name, created atomically (fails if it exists):
        // nobody else can plant anything inside it
        val tmp = File("/data/local/tmp/wf_gn_" + java.util.UUID.randomUUID().toString().replace("-", ""))
        try { Os.mkdir(tmp.path, "700".toInt(8)) } catch (e: Exception) { return null }
        return try {
            val st = Os.lstat(tmp.path)
            if (!android.system.OsConstants.S_ISDIR(st.st_mode) || st.st_uid != 0) return null
            src.copyTo(File(tmp, "g.db"))
            File(src.path + "-wal").takeIf { it.isFile }?.copyTo(File(tmp, "g.db-wal"))
            SQLiteDatabase.openDatabase(File(tmp, "g.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val n = id.substringAfter('_')
                val (sql, arg) = when {
                    id.startsWith("STEAM_") -> "select name from steam_app where id = ?" to n
                    id.startsWith("GOG_") -> "select title from gog_games where id = ?" to n
                    else -> return null
                }
                db.rawQuery(sql, arrayOf(arg)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        } catch (e: Exception) { if (BuildConfig.DEBUG) Log.w(TAG, "GameNative name: $e"); null } finally { dropTemp(tmp) }
    }

    private fun cocoon(pkg: String, started: Long): Pair<String, String>? {
        val f = File("/data/data/rip.moth.cocoonshell/shared_prefs/emulator_bindings.xml").takeIf { it.isFile } ?: return null
        val json = Regex("<string name=\"bindings_json\">(.*)</string>", RegexOption.DOT_MATCHES_ALL).find(smallText(f, 1_000_000) ?: return null)
            ?.groupValues?.get(1)?.replace("&quot;", "\"")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.replace("&apos;", "'") ?: return null
        val e = org.json.JSONObject(json).optJSONObject(pkg) ?: return null
        val bound = e.optLong("boundAtMs"); val done = e.optLong("lastFinalizedAtMs")
        // this session: launched around when the app started, and not finished since
        if (done >= bound || bound < started - 60_000) return null
        val name = e.optString("gameName").ifEmpty { return null }
        return idOf(name) to name
    }

    /** The processes of [pkg] (its data folder's uid). dev build 15 (C5): also whether an app still runs and since when
     *  ([ExtPads]: a player-order change waits while a game runs). */
    internal fun pidsOf(pkg: String): List<Int> {
        if (!PKG.matches(pkg)) return emptyList()
        val uid = runCatching { Os.stat("/data/data/$pkg").st_uid }.getOrNull() ?: return emptyList()
        return File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }
            .filter { runCatching { Os.stat("/proc/$it").st_uid == uid }.getOrDefault(false) }
    }

    /** A process's start, in wall-clock ms (/proc/<pid>/stat field 22, in clock ticks); Long.MAX_VALUE unreadable. */
    internal fun startMs(pid: Int): Long = runCatching {
        val s = File("/proc/$pid/stat").readText()
        val ticks = s.substringAfterLast(')').trim().split(' ')[19].toLong()
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        boot + ticks * 10
    }.getOrDefault(Long.MAX_VALUE)
}
