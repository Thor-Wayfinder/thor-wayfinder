package app.wayfinder

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateListOf

/**
 * Round 8 (2026-09-25): the quick panel's cards — which ones show, in which order — arranged in its
 * edit mode (the pencil), next to the shortcut tiles. Two are widgets you add: live details
 * (temperatures, clocks, battery time left; build 18: power, fan, memory) and media controls. Stored in thor_panel as
 * `id:1,id:0,…` (1 = shown); unknown ids are dropped, missing ones added hidden at the end. Build 18 (E10, V04): "now" is named
 * "This app's settings" (its id kept) — "Now playing" sat under the media card's "Nothing playing".
 */
object PanelCards {
    val NAMES = linkedMapOf(
        "now" to "This app's settings", "screens" to "Screen modes", "levels" to "Brightness and volume",
        "details" to "Details — temperatures, clocks, power, fan, memory", "media" to "Media — what's playing",
        "tiles" to "Shortcuts",
    )
    private val DEFAULT = listOf("now" to true, "screens" to true, "levels" to true, "details" to false, "media" to false, "tiles" to true)

    /** (id, shown) in order — Compose-observable. */
    val cards = mutableStateListOf<Pair<String, Boolean>>()

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences("thor_panel", Context.MODE_PRIVATE).getString("cards", null)
        val parsed = s?.split(',')?.mapNotNull { e ->
            val (id, on) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (id to (on == "1")).takeIf { id in NAMES }
        }?.distinctBy { it.first } ?: DEFAULT
        cards.clear()
        cards.addAll(parsed + DEFAULT.filter { d -> parsed.none { it.first == d.first } }.map { it.first to false })
    }

    private fun save(ctx: Context) = ctx.getSharedPreferences("thor_panel", Context.MODE_PRIVATE).edit()
        .putString("cards", cards.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" }).apply()

    fun toggle(ctx: Context, id: String) {
        val i = cards.indexOfFirst { it.first == id }.takeIf { it >= 0 } ?: return
        cards[i] = id to !cards[i].second; save(ctx)
    }
    fun move(ctx: Context, id: String, by: Int) {
        val i = cards.indexOfFirst { it.first == id }.takeIf { it >= 0 } ?: return
        val j = (i + by).coerceIn(0, cards.lastIndex)
        if (i == j) return
        val c = cards.removeAt(i); cards.add(j, c); save(ctx)
    }
    fun reset(ctx: Context) { cards.clear(); cards.addAll(DEFAULT); save(ctx) }
}

/** What's playing, from Android's media sessions (root `dumpsys`: no notification access needed). */
object MediaNow {
    data class Now(val app: String, val title: String, val artist: String?, val playing: Boolean)

    /** The active session: playing first, else the most recent paused one. Off the main thread. */
    fun read(): Now? {
        // pservice returns ONE line of output: the lines are joined on the device with a record
        // separator (0x1e), split here. Titles are media metadata — any app's text: a tab or newline in
        // one could forge a "package=" line (security review 2026-09-25). So: a separator titles don't
        // carry (any 0x1e inside a title is deleted first — pass-2 review), the exact shape of each
        // line (6-space indent), and a real package name.
        val out = PServiceBridge.exec("dumpsys media_session | tr -d '\\036' | grep -E '^      (package=|state=PlaybackState|metadata:)' | tr '\\n' '\\036'")
            ?: return null
        var pkg: String? = null; var state = -1; var desc: String? = null
        val found = mutableListOf<Now>()
        fun flush() {
            val p = pkg; val d = desc
            if (p != null && d != null && d != "null" && (state == 2 || state == 3)) {
                val parts = d.split(", ")
                found += Now(p, parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }, state == 3)
            }
            pkg = null; state = -1; desc = null
        }
        val pkgLine = Regex("^ {6}package=([A-Za-z0-9_.]+)$")
        val stateLine = Regex("^ {6}state=PlaybackState \\{state=(\\d+),")
        val metaLine = Regex("^ {6}metadata: size=\\d+, description=(.*)$")
        for (l in out.split('\u001e')) {
            pkgLine.find(l)?.let { m -> flush(); pkg = m.groupValues[1].takeIf { Shell.isPkg(it) }; return@let }
                ?: stateLine.find(l)?.let { m -> state = m.groupValues[1].toIntOrNull() ?: -1 }
                ?: metaLine.find(l)?.let { m -> desc = m.groupValues[1] }
        }
        flush()
        return found.firstOrNull { it.playing } ?: found.firstOrNull()
    }

    /** Previous / play-pause / next to the active session. Android's media-key route went to the
     *  Bluetooth session on the Thor (Spotify didn't react — test 2026-09-25): root `cmd media_session
     *  dispatch` reaches the playing app; the plain media key only if the root bridge is missing. */
    fun key(ctx: Context, code: Int) = Thread {
        val verb = when (code) { KeyEvent.KEYCODE_MEDIA_NEXT -> "next"; KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"; else -> "play-pause" }
        if (PServiceBridge.exec("cmd media_session dispatch $verb") == null) {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }.apply { isDaemon = true }.start()
}
