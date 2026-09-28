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
 *  3. Any emulator — the game file it keeps open (Dolphin .rvz, PPSSPP .iso, Azahar .3ds…).
 *  4. Cocoon's record of what it launched in that app (melonDS and others that close the file).
 */
object GameDetector {
    private const val TAG = "ThorGame"
    private val EXT = Regex("""\.(rvz|iso|gcm|gcz|wbfs|wia|ciso|3ds|cci|cia|cxi|app|nsp|xci|nca|chd|cso|pbp|cue|bin|img|m3u|nds|dsi|z64|n64|v64|sfc|smc|gba|gbc|gb|nes|fds|md|gen|smd|sms|gg|pce|ws|wsc|ngp|zip|7z|wua|wux|rpx|elf|vpk|cdi|gdi)$""", RegexOption.IGNORE_CASE)
    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
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
        val uid = runCatching { Os.stat("/data/data/$pkg").st_uid }.getOrNull() ?: return null
        val pids = File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }
            .filter { runCatching { Os.stat("/proc/$it").st_uid == uid }.getOrDefault(false) }
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
            ?: (if (own) return null else openGame(pids))
            ?: return nice
        return found.first to (nice?.second ?: found.second)
    }

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

    private fun openGame(pids: List<Int>): Pair<String, String>? {
        for (pid in pids) {
            val fds = File("/proc/$pid/fd").list() ?: continue
            for (fd in fds) {
                val t = runCatching { Os.readlink("/proc/$pid/fd/$fd") }.getOrNull() ?: continue
                // the user's files only — not an emulator's own (Eden keeps system .nca files open)
                if ((t.startsWith("/storage/") || t.startsWith("/sdcard/") || t.startsWith("/data/media/") || t.startsWith("/mnt/")) &&
                    "/Android/data/" !in t && "/Android/obb/" !in t && EXT.containsMatchIn(t))
                    return fileGame(t)
            }
        }
        return null
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

    /** A process's start, in wall-clock ms (/proc/<pid>/stat field 22, in clock ticks). */
    private fun startMs(pid: Int): Long = runCatching {
        val s = File("/proc/$pid/stat").readText()
        val ticks = s.substringAfterLast(')').trim().split(' ')[19].toLong()
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        boot + ticks * 10
    }.getOrDefault(Long.MAX_VALUE)
}
