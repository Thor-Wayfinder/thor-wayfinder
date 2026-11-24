package app.wayfinder

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Full backup / restore of Wayfinder's settings (2026-09-25).
 *
 * **Trust.** A backup is signed (HMAC-SHA256) with a key that exists only on this Thor, in the
 * app's private files — never in a backup, never copied off the device (dataExtractionRules).
 *  - Signature OK = made by Wayfinder on this Thor → a FULL restore: combos, app + game profiles,
 *    quick panel, deck, lights, keyboard, sound, sleep… macros stay on.
 *  - Anything else (another device, a reset Thor, an edited or hand-made file) is treated like a
 *    shared file: ONLY the controller mappings of its apps and games are taken, through
 *    [ProfileShare]'s filter (no Wayfinder actions, safe keys only, macros OFF). Its combos
 *    and settings are not imported: they could run Wayfinder actions or change system settings.
 * Nothing in a backup is ever run; values are written back as typed preferences of known files.
 * Build 19: the connected controllers too ([BackupRules]: their settings and their own Game controls,
 * never the device's identity table or AYN's lists). Signed: restored with the rest. Anything else:
 * only their own Game controls, through the same filter (their own parts kept, one made Home removed).
 */
object Backup {
    private const val FORMAT = "wayfinder-backup"
    private const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What a backup holds (the Thor's files; the controllers' group is [BackupRules.PAD_FILES]). Not: runtime state
     *  (thor_sleep_state, thor_perf), WebView's files. */
    val FILES = listOf(
        "thor_settings", "thor_apps", "thor_games", "thor_panel", "thor_deck", "thor_lights",
        "thor_layouts", "thor_keyboard", "thor_audio", "thor_volume", "thor_companion", "thor_sleep", "gyro",
    )

    // ── the device key ─────────────────────────────────────────────────────

    private fun key(ctx: Context): ByteArray {
        val f = java.io.File(ctx.filesDir, "backup_key")
        runCatching { f.readBytes() }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(k)
        return k
    }

    private fun sign(ctx: Context, data: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key(ctx), "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ── make ───────────────────────────────────────────────────────────────

    /** (file name, bytes) of a backup of everything in [FILES] and the controllers' group. null = over [MAX_BYTES]: a
     *  file [read] would refuse is never saved (build 19). 1.5-beta2 (Q12): every file of [FILES] is written, an empty one
     *  as `{}`, so a restore clears it ([restoreAll]), as the controllers' group (it was skipped, and a restore left it). */
    fun make(ctx: Context): Pair<String, ByteArray>? {
        val prefs = JSONObject()
        for (name in FILES) {
            val all = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
            prefs.put(name, BackupRules.encode(all))
        }
        // build 19: the controllers' group, written even when empty (the marker below says the backup holds it)
        for ((name, file) in BackupRules.groupForBackup { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all }) prefs.put(name, file)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        val data = JSONObject().put("made", now).put("prefs", prefs)
            .put(BackupRules.MARKER, 1).put(BackupRules.APP, BuildConfig.VERSION_CODE).toString()
        val file = JSONObject().put("format", FORMAT).put("version", VERSION).put("data", data).put("sig", sign(ctx, data))
        val bytes = file.toString().toByteArray()
        if (!BackupRules.fits(bytes)) return null
        return "Wayfinder backup ${now.replace(':', '-')}.json" to bytes
    }

    // ── read ───────────────────────────────────────────────────────────────

    class Incoming(
        /** Signed by this Thor → full restore. */
        val trusted: Boolean,
        val made: String,
        /** Trusted only: the preference files to write back. */
        val prefs: JSONObject?,
        /** Not trusted: the filtered mappings that would be imported. */
        val mappings: List<ProfileShare.Incoming>,
        val report: ProfileShare.Report,
        /** Build 19, trusted: it holds the controllers' group (its marker); only then a restore writes it. */
        val controllers: Boolean = false,
        /** Build 19, trusted: what the restore sheet says of the controllers. */
        val sheet: BackupRules.Sheet = BackupRules.Sheet.THOR,
        /** Build 19, not trusted: the controllers' own controls that would be imported (filtered, at most [BackupRules.MAX_OWN]). */
        val own: List<ProfileShare.Incoming> = emptyList(),
    )

    fun read(ctx: Context, uri: Uri): Pair<Incoming?, String?> {
        val bytes = ProfileShare.readBytes(ctx, uri, MAX_BYTES) ?: return null to "That file is too big or can't be read."
        return parse(ctx, String(bytes, Charsets.UTF_8))
    }

    fun parse(ctx: Context, text: String): Pair<Incoming?, String?> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null to "That isn't a Wayfinder backup."
        if (o.optString("format") != FORMAT) return null to "That isn't a Wayfinder backup."
        if (o.optInt("version", 0) > VERSION) return null to "This backup was made by a newer Wayfinder — update Wayfinder first."
        val data = o.optString("data")
        val d = runCatching { JSONObject(data) }.getOrNull() ?: return null to "That backup is damaged."
        val prefs = d.optJSONObject("prefs") ?: return null to "That backup is damaged."
        val made = d.optString("made").filter { it.isDigit() || it in "-: " }.take(20)
        // constant-time comparison of the signature
        val trusted = MessageDigest.isEqual(sign(ctx, data).toByteArray(), o.optString("sig").toByteArray())
        if (trusted) {
            // build 19: the controllers' group only from a backup that holds it; the sheet names them only where they are
            val marker = d.optInt(BackupRules.MARKER, 0) == 1
            val sheet = BackupRules.restoreSheet(marker, prefs) { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all }
            return Incoming(true, made, prefs, emptyList(), ProfileShare.Report(), controllers = marker, sheet = sheet) to null
        }

        // Not ours: the controller mappings only, each through the shared-file filter
        val report = ProfileShare.Report()
        val list = ArrayList<ProfileShare.Incoming>()
        fun take(shared: JSONObject) {
            val inc = ProfileShare.parse(ctx, shared.toString()).first ?: return
            report.actions += inc.report.actions; report.keys += inc.report.keys
            report.macrosOff += inc.report.macrosOff; report.emptied += inc.report.emptied
            list += inc
        }
        val apps = prefs.optJSONObject("thor_apps")
        apps?.keys()?.asSequence()?.take(500)?.forEach { pkg ->
            val cfg = runCatching { JSONObject(apps.getJSONObject(pkg).optString("v")) }.getOrNull() ?: return@forEach
            if (cfg.has("remap") || cfg.has("face")) take(JSONObject().put("format", "wayfinder-controls").put("version", 1)
                .put("app", pkg).put("remap", cfg.optJSONObject("remap")).put("face", cfg.optString("face")))
        }
        val games = prefs.optJSONObject("thor_games")
        games?.keys()?.asSequence()?.take(500)?.forEach { k ->
            val gp = runCatching { JSONObject(games.getJSONObject(k).optString("v")) }.getOrNull() ?: return@forEach
            take(JSONObject().put("format", "wayfinder-controls").put("version", 1)
                .put("app", k.substringBefore('#')).put("game", k.substringAfter('#', ""))
                .put("title", gp.optString("title")).put("remap", gp.optJSONObject("remap")).put("face", gp.optString("face")))
        }
        // build 19: the controllers' own controls (their own cap), through the same filter with their own parts kept; an
        // entry switched off in the backup comes in off
        val own = ArrayList<ProfileShare.Incoming>()
        val pads = prefs.optJSONObject(BackupRules.PADPROF)
        val offs = BackupRules.decode(prefs.optJSONObject(BackupRules.PADPROF_OFF))
        pads?.keys()?.asSequence()?.take(BackupRules.MAX_OWN)?.forEach { k ->
            val v = pads.optJSONObject(k)?.takeIf { it.optString("t") == "s" }?.optString("v") ?: return@forEach
            val cfg = runCatching { JSONObject(v) }.getOrNull() ?: return@forEach
            val inc = ProfileShare.own(ctx, k, cfg, off = offs[k] == true) ?: return@forEach
            report.actions += inc.report.actions; report.keys += inc.report.keys
            report.macrosOff += inc.report.macrosOff; report.emptied += inc.report.emptied
            own += inc
        }
        if (list.isEmpty() && own.isEmpty()) return null to "This backup can't be fully restored here (it wasn't made by this installation of Wayfinder), and it has no game controls to take."
        return Incoming(false, made, null, list.take(500), report, own = own) to null
    }

    // ── restore ────────────────────────────────────────────────────────────

    /** Trusted: every file in [FILES] replaced by the backup's; an empty one in the backup clears it (1.5-beta2, Q12: [make]
     *  writes every file). A file the backup doesn't have is left: a backup made before 1.5-beta2 skipped the empty ones,
     *  so it can't say they were empty. Build 19: the
     *  controllers' group as [BackupRules.restorePlan] says, only when the backup holds it ([controllers]: its marker).
     *  Every write is committed: [restartApp] follows. */
    fun restoreAll(ctx: Context, prefs: JSONObject, controllers: Boolean = false) {
        for (name in FILES) {
            val o = prefs.optJSONObject(name) ?: continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val x = o.optJSONObject(k) ?: return@forEach
                when (x.optString("t")) {
                    "s" -> e.putString(k, x.optString("v"))
                    "b" -> e.putBoolean(k, x.optBoolean("v"))
                    "i" -> e.putInt(k, x.optInt("v"))
                    "l" -> e.putLong(k, x.optLong("v"))
                    "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                    "ss" -> e.putStringSet(k, x.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                }
            }
            e.commit()
        }
        val plan = BackupRules.restorePlan(controllers, prefs, { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all }) { _, _ ->
            android.util.Log.w("ThorBackup", "the oldest of ${PadRules.KEEP} kept controls dropped")
        }
        for ((name, edit) in plan) {
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            if (edit.clear) e.clear()
            edit.remove.forEach { e.remove(it) }
            for ((k, v) in edit.put) when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.commit()
        }
    }

    /** Not trusted: the filtered mappings, like shared files (build 19: the controllers' own controls too, which need
     *  their store open: after a reinstall the service may not have opened it yet, and a write would be lost). */
    fun restoreMappings(ctx: Context, list: List<ProfileShare.Incoming>) {
        if (list.any { it.pad != null }) PadProfiles.init(ctx)
        list.forEach { ProfileShare.apply(it) }
    }

    /** After a full restore every setting is re-read by starting Wayfinder again: the system
     *  re-binds the accessibility service at once; it reopens the Hub (flag below). */
    fun restartApp(ctx: Context) {
        ctx.getSharedPreferences("thor_restore", Context.MODE_PRIVATE).edit().putBoolean("reopen", true).commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Called when the service starts: reopen the Hub after a restore. */
    fun reopenAfterRestore(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("thor_restore", Context.MODE_PRIVATE)
        if (!p.getBoolean("reopen", false)) return false
        p.edit().remove("reopen").commit()
        return true
    }
}
