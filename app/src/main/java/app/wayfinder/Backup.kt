package app.wayfinder

import android.content.Context
import android.net.Uri
import org.json.JSONArray
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
 */
object Backup {
    private const val FORMAT = "wayfinder-backup"
    private const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What a backup holds. Not: runtime state (thor_sleep_state, thor_perf), WebView's files. */
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

    /** (file name, bytes) of a backup of everything in [FILES]. */
    fun make(ctx: Context): Pair<String, ByteArray> {
        val prefs = JSONObject()
        for (name in FILES) {
            val all = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = JSONObject()
            for ((k, v) in all) when (v) {
                is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
                is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
                is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                is Set<*> -> o.put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>())))
            }
            prefs.put(name, o)
        }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        val data = JSONObject().put("made", now).put("prefs", prefs).toString()
        val file = JSONObject().put("format", FORMAT).put("version", VERSION).put("data", data).put("sig", sign(ctx, data))
        return "Wayfinder backup ${now.replace(':', '-')}.json" to file.toString().toByteArray()
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
        if (trusted) return Incoming(true, made, prefs, emptyList(), ProfileShare.Report()) to null

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
        if (list.isEmpty()) return null to "This backup can't be fully restored here (it wasn't made by this installation of Wayfinder), and it has no controller mappings to take."
        return Incoming(false, made, null, list.take(500), report) to null
    }

    // ── restore ────────────────────────────────────────────────────────────

    /** Trusted: every file in [FILES] replaced by the backup's (files it doesn't have are left). */
    fun restoreAll(ctx: Context, prefs: JSONObject) {
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
    }

    /** Not trusted: the filtered mappings, like shared files. */
    fun restoreMappings(list: List<ProfileShare.Incoming>) = list.forEach { ProfileShare.apply(it) }

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
