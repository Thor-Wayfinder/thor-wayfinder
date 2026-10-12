package app.wayfinder

import android.content.Context

/**
 * The older own controls of an external controller (prefs thor_extctl): for one game,
 * or for every game. dev build 16 (WP4): read once and moved into PadProfiles ([PadProfiles.migrateExtControls],
 * [PadRules.fromLegacy]); nothing else reads them.
 *
 * Kept as the input layer's engine tokens (fx/wfmap.h `wf_parse`), which wfhid runs as they are. Parts are named by
 * POSITION (bottom / right / left / top…): the engine works on printed buttons, and each position is one printed
 * button on our pads (bottom = B, right = A, left = Y, top = X, Nintendo-style) — so "bottom → right" means the same
 * whatever the face-button layout.
 */
data class ExtCtl(
    /** Face buttons for this controller here — positions only: Nintendo (A right), Xbox (A bottom); null = as on its
     *  page (dev build 15, WP2: was 'n' / 'x' / null = like the Thor — the same "L=" tokens). */
    val face: FaceStyle? = null,
    /** What a part becomes: printed code → printed code (0xffff nothing, 0x220..0x223 a D-pad direction). */
    val map: Map<Int, Int> = emptyMap(),
    /** How a part fires: 1 turbo, 2 toggle (missing = normal). */
    val fire: Map<Int, Int> = emptyMap(),
    val turboHz: Int = 12,
    val swapSticks: Boolean = false,
    val invLY: Boolean = false, val invRY: Boolean = false,
    val invLX: Boolean = false, val invRX: Boolean = false,
    val dpadStick: Boolean = false,
    val digitalTriggers: Boolean = false,
    /** Stick deadzone % (drift), left / right. */
    val deadL: Int = 0, val deadR: Int = 0,
    /** Engine tokens this editor doesn't show (copied from the Thor's: stick "full at" and response, trigger range…),
     *  kept as they are. */
    val extra: List<String> = emptyList(),
) {
    val changes: Int get() = map.size + fire.size + extra.size + listOf(face != null, swapSticks, invLY, invRY, invLX, invRX, dpadStick,
        digitalTriggers, deadL > 0, deadR > 0).count { it }

    fun tokens(): String = buildList {
        face?.let { add("L=${it.wire}") }
        for ((s, d) in map.toSortedMap()) add("k0x${s.toString(16)}=0x${d.toString(16)}")
        for ((s, f) in fire.toSortedMap()) add("f0x${s.toString(16)}=$f")
        if (fire.containsValue(1) && turboHz != 12) add("tr=${turboHz.coerceIn(2, 30)}")
        if (swapSticks) add("sw=1")
        if (dpadStick) add("dl=1")
        if (invLY) add("il=1")
        if (invRY) add("ir=1")
        if (invLX) add("xl=1")
        if (invRX) add("xr=1")
        if (digitalTriggers) add("td=1")
        if (deadL > 0) add("dzl=${deadL.coerceIn(0, 30)}")
        if (deadR > 0) add("dzr=${deadR.coerceIn(0, 30)}")
        addAll(extra)
    }.joinToString(" ")

    companion object {
        /** What "Start from the Thor's" may bring that this page doesn't edit. */
        private val KEPT = setOf("ozl", "ozr", "cvl", "cvr", "tlo", "thi", "apl", "apr", "sh", "bg")
        fun parse(spec: String): ExtCtl {
            var c = ExtCtl()
            val map = HashMap<Int, Int>(); val fire = HashMap<Int, Int>(); val extra = ArrayList<String>()
            for (t in spec.split(' ')) {
                val k = t.substringBefore('=', ""); val v = t.substringAfter('=', "")
                if (k.isEmpty()) continue
                fun num(x: String) = if (x.startsWith("0x")) x.drop(2).toIntOrNull(16) else x.toIntOrNull()
                when {
                    k == "L" -> c = c.copy(face = when (v) { "n" -> FaceStyle.NINTENDO; "x" -> FaceStyle.XBOX; else -> null })
                    k.startsWith("k") -> { val s = num(k.drop(1)); val d = num(v); if (s != null && d != null) map[s] = d }
                    k.startsWith("f") -> { val s = num(k.drop(1)); val f = num(v); if (s != null && f != null && f in 1..2) fire[s] = f }
                    k == "tr" -> c = c.copy(turboHz = v.toIntOrNull()?.coerceIn(2, 30) ?: 12)
                    k == "sw" -> c = c.copy(swapSticks = v == "1")
                    k == "dl" -> c = c.copy(dpadStick = v == "1")
                    k == "il" -> c = c.copy(invLY = v == "1")
                    k == "ir" -> c = c.copy(invRY = v == "1")
                    k == "xl" -> c = c.copy(invLX = v == "1")
                    k == "xr" -> c = c.copy(invRX = v == "1")
                    k == "td" -> c = c.copy(digitalTriggers = v == "1")
                    k == "dzl" -> c = c.copy(deadL = v.toIntOrNull()?.coerceIn(0, 30) ?: 0)
                    k == "dzr" -> c = c.copy(deadR = v.toIntOrNull()?.coerceIn(0, 30) ?: 0)
                    k in KEPT -> extra += t
                }
            }
            return c.copy(map = map, fire = fire, extra = extra)
        }
    }
}

/** dev build 16 (WP4): the older own controls' store — read once by the move into PadProfiles (and build 15's M1 pass). */
object ExtControls {
    private const val PREFS = "thor_extctl"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every entry: "<game key | package | *>|<controller key>" → its tokens. */
    internal fun legacy(ctx: Context): Map<String, String> =
        prefs(ctx).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    /** The moved entries gone (written at once: the move's backup is in thor_padprof_b16 already). */
    internal fun clearLegacy(ctx: Context, keys: Collection<String>) {
        if (keys.isNotEmpty()) prefs(ctx).edit().apply { keys.forEach { remove(it) } }.commit()
    }

    /** dev build 15 (critique M1): "Start from the Thor's" copied the Thor's face for that game into a controller's own
     *  controls — with its own face beating the game's (WP2, theme E) the copy kept the Thor's face on it. That "L=" is
     *  taken out ([FaceRules.withoutCopiedLayout]; [thorFace] = the Thor's face for a game key / package, null for every
     *  game). Returns (key, old spec) of each one changed. Once ([PadProfiles]), before the move. */
    internal fun dropCopiedFaces(ctx: Context, thorFace: (String) -> FaceStyle?): List<Pair<String, String>> {
        val p = prefs(ctx)
        val changed = p.all.mapNotNull { (k, v) ->
            val game = k.substringBeforeLast('|', "").takeIf { it.isNotEmpty() && it != PadRules.EVERY } ?: return@mapNotNull null
            val spec = v as? String ?: return@mapNotNull null
            FaceRules.withoutCopiedLayout(spec, thorFace(game))?.let { Triple(k, spec, it) }
        }
        if (changed.isNotEmpty()) p.edit().apply { changed.forEach { (k, _, s) -> putString(k, s) } }.commit()
        return changed.map { (k, old, _) -> k to old }
    }
}
