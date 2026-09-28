package app.wayfinder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Sharing one app's or one game's CONTROLLER MAPPING as a file (2026-09-25: "just the
 * mapping for the controller, no system input, macros saved but disabled; safety is
 * important").
 *
 * A shared file is written by someone else: everything in it is untrusted. What survives an
 * import — and what doesn't:
 *  - pad buttons (remaps, several at once), turbo / toggle / long / double press, chords,
 *    stick options, gyro (its values are clamped by [GyroSettings.fromJson]): kept;
 *  - Wayfinder actions (screenshot, close apps, swap…): REMOVED — that's system input;
 *  - keyboard keys: only [SAFE_KEYS] (letters, digits, F-keys, arrows, Esc, Enter…), modifiers
 *    only Ctrl / Shift / Alt — Android's system keys (Home, Back, app switch, power, volume,
 *    media, search, Win / Meta shortcuts, PrtSc) are REMOVED;
 *  - mouse clicks and wheel: kept;
 *  - macros: kept but OFF ([RemapTarget.Macro.armed]) until the user turns each one on.
 * Nothing in a file is ever run as a command; it only becomes a [PadRemap] (whose own parser
 * validates every token) + a face layout. Size-capped; unknown fields ignored.
 */
object ProfileShare {
    private const val FORMAT = "wayfinder-controls"
    private const val VERSION = 1
    const val MAX_BYTES = 256 * 1024

    /** Keyboard keys a shared file may send (see the class comment). */
    val SAFE_KEYS: Set<Int> = buildSet {
        addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
        addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
        addAll(KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
        addAll(KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)
        addAll(listOf(
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_INSERT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET,
            KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_NUMPAD_DOT,
        ))
    }
    private const val SAFE_META = KeyEvent.META_CTRL_MASK or KeyEvent.META_SHIFT_MASK or KeyEvent.META_ALT_MASK
    /** With Ctrl or Alt held, these are Android shortcuts (Alt+Tab = the app switcher, Ctrl+Space
     *  = keyboard layout, Alt+Esc = Home): not allowed together in a shared file (review). */
    private val SHORTCUT_KEYS = setOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE)
    private const val CTRL_ALT = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK
    private val CTRL_ALT_KEYS = setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)

    /** What an import removed or switched off — shown in the preview before anything is saved. */
    class Report {
        var actions = 0; var keys = 0; var macrosOff = 0; var emptied = 0
        fun lines(): List<String> = buildList {
            if (actions > 0) add("$actions Wayfinder action${s(actions)} removed (shared files carry controller mapping only)")
            if (keys > 0) add("$keys system key${s(keys)} removed (only regular keyboard keys are allowed)")
            if (macrosOff > 0) add("$macrosOff macro${s(macrosOff)} kept but OFF — check the steps, then turn ${if (macrosOff == 1) "it" else "them"} on")
            if (emptied > 0) add("$emptied button${s(emptied)} left unmapped (nothing safe remained)")
        }
        private fun s(n: Int) = if (n == 1) "" else "s"
    }

    /** One parsed, cleaned file — not saved yet. */
    class Incoming(
        val pkg: String, val appName: String, val game: String?, val title: String,
        val face: FaceLayout?, val remap: PadRemap, val report: Report,
    ) {
        /** The profile it goes to: the game's (created if new) or the app's. */
        val key get() = if (game != null) GameProfiles.key(pkg, game) else pkg
    }

    // ── export ─────────────────────────────────────────────────────────────

    /** The file for profile [key] (app or `pkg#game`), without the actions (never shared). */
    fun export(ctx: Context, key: String): Pair<String, ByteArray>? {
        val cfg = Profiles.get(key)
        val remap = cfg.remap?.let { strip(it, Report(), keepMacrosOn = true) }
        if ((remap == null || remap.isEmpty) && cfg.face == null) return null
        val pkg = GameProfiles.pkgOf(key)
        val game = key.substringAfter('#', "").takeIf { GameProfiles.isGame(key) }
        val appName = label(ctx, pkg)
        val title = if (game != null) GameProfiles.get(key)?.title ?: game else appName
        val o = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("app", pkg).put("appName", appName).put("title", title)
            .apply { game?.let { put("game", it) } }
            .apply { cfg.face?.let { put("face", it.name) } }
            .apply { remap?.takeIf { !it.isEmpty }?.let { put("remap", it.toJson()) } }
        val name = "${fileSafe(title)} - Wayfinder controls.json"
        return name to o.toString(2).toByteArray()
    }

    /** Writes the file to Download/Wayfinder (no permission needed for our own file). */
    fun save(ctx: Context, name: String, bytes: ByteArray): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Wayfinder")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

    /** Android's share menu (Discord, mail, Drive…) with the file: a copy in cache/share (the only
     *  folder the FileProvider serves, emptied first), readable only by the app the user picks. */
    fun send(ctx: Context, name: String, bytes: ByteArray, chooserTitle: String): Boolean = runCatching {
        val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = java.io.File(dir, name).apply { writeBytes(bytes) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, name.removeSuffix(".json"))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(send, chooserTitle)
            .apply { if (ctx !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        true
    }.getOrDefault(false)

    /** Android's file picker, opening in Download/Wayfinder (where Share and Back up save). */
    class PickInWayfinderFolder : androidx.activity.result.contract.ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input)
            .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FWayfinder"))
    }
    val PICK_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    /** Read a picked file, at most [max] bytes (null: too big / unreadable). */
    fun readBytes(ctx: Context, uri: Uri, max: Int): ByteArray? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (out.size() <= max) { val n = s.read(chunk); if (n < 0) break; out.write(chunk, 0, n) }
            if (out.size() > max) null else out.toByteArray()
        }
    }.getOrNull()

    // ── import ─────────────────────────────────────────────────────────────

    /** Read + check + clean a file the user picked. Null + why when it can't be used. */
    fun read(ctx: Context, uri: Uri): Pair<Incoming?, String?> {
        val bytes = readBytes(ctx, uri, MAX_BYTES) ?: return null to "That file is too big or can't be read."
        return parse(ctx, String(bytes, Charsets.UTF_8))
    }

    fun parse(ctx: Context, text: String): Pair<Incoming?, String?> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null to "That isn't a Wayfinder controls file."
        if (o.optString("format") != FORMAT) return null to "That isn't a Wayfinder controls file."
        if (o.optInt("version", 0) > VERSION) return null to "This file was made by a newer Wayfinder — update Wayfinder first."
        val pkg = o.optString("app").takeIf { it.length <= 200 && PKG.matches(it) } ?: return null to "The file doesn't say which app it's for."
        val game = o.optString("game").takeIf { it.isNotEmpty() }?.let { g -> g.takeIf { GAME.matches(it) } ?: return null to "The game in the file isn't valid." }
        val appName = runCatching { label(ctx, pkg) }.getOrNull()?.takeIf { it != pkg }
            ?: clean(o.optString("appName")).takeIf { it.isNotEmpty() }?.let { "$it ($pkg)" } ?: pkg
        // a game's title comes from the file; an app's name never does (the installed label wins)
        val title = if (game == null) appName else clean(o.optString("title")).ifEmpty { game }
        val face = FaceLayout.values().firstOrNull { it.name == o.optString("face") }
        val report = Report()
        // PadRemap.fromJson validates every token; then the safety filter on top
        val remap = PadRemap.fromJson(o.optJSONObject("remap"))?.let { strip(it, report, keepMacrosOn = false) } ?: PadRemap()
        if (remap.isEmpty && face == null) return null to "Nothing usable is left in that file."
        return Incoming(pkg, appName, game, title, face, remap, report) to null
    }

    /** Is [pkg] installed here? (the preview says so: a profile for a missing app waits for it) */
    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** Save it: the game's profile (created) or the app's. */
    fun apply(inc: Incoming): Boolean = runCatching {
        if (inc.game != null) GameProfiles.create(inc.pkg, inc.game, inc.title)
        Profiles.update(inc.key) { it.copy(face = inc.face ?: it.face, remap = inc.remap.takeIf { r -> !r.isEmpty }) }
    }.onFailure { android.util.Log.w("ThorShare", "import failed: $it") }.isSuccess

    // ── the filter ─────────────────────────────────────────────────────────

    private fun strip(r: PadRemap, rep: Report, keepMacrosOn: Boolean): PadRemap {
        // held keys combine across buttons (the injected meta state folds every key down), so
        // Ctrl / Alt anywhere in the file + Tab / Space / Esc anywhere = a system shortcut
        fun hasMod(t: RemapTarget): Boolean = when (t) {
            is RemapTarget.Key -> t.code in CTRL_ALT_KEYS || t.meta and CTRL_ALT != 0
            is RemapTarget.Keys -> t.codes.any { it in CTRL_ALT_KEYS }
            is RemapTarget.Macro -> t.steps.any { hasMod(it.out) }
            else -> false
        }
        val anyMod = (r.buttons.values + r.alt.values + r.chords.map { it.target } + r.shifted.values).any { hasMod(it) }
        fun keep(t: RemapTarget): RemapTarget? = when (t) {
            is RemapTarget.Button, is RemapTarget.Buttons, is RemapTarget.Mouse, RemapTarget.None -> t
            is RemapTarget.Action -> { rep.actions++; null }
            is RemapTarget.Key ->
                if (t.code !in SAFE_KEYS || ((anyMod || t.meta and CTRL_ALT != 0) && t.code in SHORTCUT_KEYS)) { rep.keys++; null }
                else RemapTarget.Key(t.code, t.meta and SAFE_META)
            is RemapTarget.Keys -> {
                val mod = anyMod || (!t.inOrder && t.codes.any { it in CTRL_ALT_KEYS })
                t.codes.filter { c -> (c in SAFE_KEYS && !(mod && c in SHORTCUT_KEYS)).also { ok -> if (!ok) rep.keys++ } }
                    .takeIf { it.isNotEmpty() }?.let { RemapTarget.Keys(it, t.inOrder) }
            }
            is RemapTarget.Macro -> {
                val steps = t.steps.filter { s -> (s.out !is RemapTarget.Key || (s.out.code in SAFE_KEYS && !(anyMod && s.out.code in SHORTCUT_KEYS))).also { ok -> if (!ok) rep.keys++ } }
                if (steps.isEmpty()) null
                else { if (!keepMacrosOn && t.armed) rep.macrosOff++; t.copy(steps = steps, armed = keepMacrosOn && t.armed) }
            }
        }
        val buttons = r.buttons.mapNotNull { (k, v) -> keep(v)?.let { k to it } ?: run { rep.emptied++; null } }.toMap()
        val alt = r.alt.mapNotNull { (k, v) -> keep(v)?.let { k to it } }.toMap()
        val chords = r.chords.mapNotNull { c -> keep(c.target)?.let { c.copy(target = it) } }
        // a long / double press whose second output was removed falls back to normal
        val fire = r.fire.filter { (k, f) -> !f.hasAlt || k in alt }
        val shifted = r.shifted.mapNotNull { (k, v) -> keep(v)?.let { k to it } }.toMap()
        return r.copy(buttons = buttons, alt = alt, chords = chords, fire = fire, shifted = shifted)
    }

    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val GAME = Regex("^[a-z0-9-]{1,64}$")   // = GameDetector's ids (review 2026-09-25)

    /** Text from a file: no control characters, bounded. */
    private fun clean(s: String) = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")
        .replace(Regex(" {2,}"), " ").trim().take(80)
    private fun fileSafe(s: String) = s.map { if (it.isLetterOrDigit() || it in " .-_()'") it else '_' }.joinToString("").trim().take(60).ifEmpty { "Controls" }

    private fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
