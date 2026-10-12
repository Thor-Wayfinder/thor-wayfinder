package app.wayfinder

import android.util.Log

/**
 * FPS counter — runs INSIDE the root input helper (app_process, uid 0).
 *
 * Counts the frames SurfaceFlinger actually presented for an app's layers during the
 * last second: `dumpsys SurfaceFlinger --latency <layer>` lists the last 128 frames
 * (desired-present, ACTUAL-present, frame-ready, in System.nanoTime() ns). That's the
 * real on-screen rate for GL/Vulkan games too — not an app's self-reported number.
 * The app's busiest layer wins (a game's SurfaceView "(BLAST)" layer, not its idle UI).
 * Two apps at once: the one on the top screen and the one on the bottom screen.
 * Verified: RetroArch 60.1 fps; Minecraft 60 (its frames are on the SurfaceView BLAST layer).
 *
 * b18j (D1 option C, device b18v v2): a slot can also be KEPT on one surface ([watch]'s keep): the layer that drew the most
 * when the keep was asked (the look before the ask, else the first look with frames), followed by name ([LayerPick]). Its
 * count is sent after the busiest's: `F <top> <bottom> <kept top> <kept bottom>`. Eden's game surface stopped under Game
 * controls while its window layer drew 2 frames at its onResume (its paused screen redrawn): the busiest layer read that as
 * "the game draws again", and the "paused itself" pill never came.
 */
object FpsSampler {
    private const val TAG = "ThorFps"
    /** What to count: the package per slot (top, bottom) and whether that slot is kept on one surface. */
    private class Want(val pkgs: List<String?>, val keep: List<Boolean>)
    @Volatile private var want = Want(listOf(null, null), listOf(false, false))
    private var thread: Thread? = null
    var send: ((String) -> Unit)? = null

    /** `F <top pkg|-> [<bottom pkg|->] [<keep top 0|1> <keep bottom 0|1>]`. */
    fun watch(top: String?, bottom: String? = null, keepTop: Boolean = false, keepBottom: Boolean = false) {
        fun clean(p: String?) = p?.takeIf { it.isNotBlank() && it != "-" }
        val pkgs = listOf(clean(top), clean(bottom))
        want = Want(pkgs, listOf(keepTop && pkgs[0] != null, keepBottom && pkgs[1] != null))
        if (pkgs.any { it != null } && thread?.isAlive != true) {
            thread = Thread { loop() }.apply { isDaemon = true; start() }
        }
    }

    private fun sh(cmd: String): List<String> = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().also { p.waitFor() }
    }.getOrDefault(emptyList())

    private fun loop() {
        val layers = arrayOf(emptyList<String>(), emptyList())
        val layersFor = arrayOfNulls<String>(2)
        val layersAt = LongArray(2)
        // b18j: the surface each slot is kept on (package, layer name without its id), and the previous look's counts per layer
        val kept = arrayOfNulls<Pair<String, String>>(2)
        val last = arrayOfNulls<Map<String, Int>>(2)
        val lastFor = arrayOfNulls<String>(2)
        val lastAt = LongArray(2)
        while (true) {
            val w = want
            val watch = w.pkgs
            if (watch.all { it == null }) { thread = null; return }   // stopped; watch() starts a new loop
            val now = System.nanoTime()
            val out = IntArray(2) { -1 }
            val outKept = IntArray(2) { -1 }
            var all: List<String>? = null
            for (i in 0..1) {
                val p = watch[i]
                if (p == null) { kept[i] = null; last[i] = null; lastFor[i] = null; continue }
                if (!w.keep[i] || kept[i]?.first != p) kept[i] = null
                // a keep just asked: the layer that drew the most at the look before (the second before the ask), when fresh
                if (w.keep[i] && kept[i] == null && lastFor[i] == p && now - lastAt[i] <= 1_500_000_000L)
                    kept[i] = LayerPick.lead(last[i])?.let { p to LayerPick.base(it) }
                // Re-list the layers every 5 s — or at once when nothing was counted: a game
                // that rebuilds its surface (entering a level) gets a NEW layer, and the old
                // name would read "idle" until the next refresh.
                if (p != layersFor[i] || now - layersAt[i] > 5_000_000_000L) {
                    all = all ?: sh("dumpsys SurfaceFlinger --list").map { it.trim() }
                    layers[i] = all.filter {
                        it.contains(p) && !it.startsWith("ActivityRecord") && !it.contains("InputSink") &&
                            !it.contains("Bounds for") && !it.startsWith("Background for") && !it.startsWith("Surface(")
                    }
                    layersFor[i] = p; layersAt[i] = now
                }
                val counts = LinkedHashMap<String, Int>()
                for (l in layers[i]) {
                    val ts = sh("dumpsys SurfaceFlinger --latency '${l.replace("'", "")}'").drop(1).mapNotNull {
                        it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.takeIf { v -> v in 1 until Long.MAX_VALUE / 2 }
                    }
                    counts[l] = ts.count { now - it in 0..1_000_000_000L }
                }
                val best = counts.values.maxOrNull() ?: 0
                if (best == 0) layersAt[i] = 0L   // look for a new surface next time
                out[i] = best
                if (w.keep[i]) {
                    if (kept[i] == null) kept[i] = LayerPick.lead(counts)?.let { p to LayerPick.base(it) }
                    outKept[i] = LayerPick.count(counts, kept[i]?.second)
                    // the kept surface drew nothing: list again next time too — made again, it has a new layer (a new id)
                    if (outKept[i] == 0) layersAt[i] = 0L
                }
                last[i] = counts; lastFor[i] = p; lastAt[i] = now
            }
            send?.invoke("F ${out[0]} ${out[1]}" + (if (w.keep.any { it }) " ${outKept[0]} ${outKept[1]}" else "") + "\n")
            val spent = (System.nanoTime() - now) / 1_000_000
            Thread.sleep((1000 - spent).coerceIn(200, 1000))
        }
    }

    init { Log.d(TAG, "ready") }
}

/**
 * b18j (D1 option C): which of an app's layers a kept count follows. Pure (D1RulesTest). A layer is named
 * `<what>#<id>`; the id changes when the app makes the surface again (a level loaded, its window recreated), the rest stays.
 */
internal object LayerPick {
    /** [name] without its trailing `#<id>`. */
    fun base(name: String): String = name.replace(Regex("#\\d+$"), "")

    /** The layer that drew the most in [counts] (the first one on a tie, in the list's order); null when none drew. */
    fun lead(counts: Map<String, Int>?): String? = counts?.entries?.filter { it.value > 0 }?.maxByOrNull { it.value }?.key

    /** The frames of the surface kept as [kept] (a [base] name): the most among the layers of that name. No layer of that name
     *  any more, or nothing kept yet: the busiest layer, the plain count. */
    fun count(counts: Map<String, Int>, kept: String?): Int {
        val own = if (kept == null) emptyList() else counts.filterKeys { base(it) == kept }.values.toList()
        return (own.ifEmpty { counts.values }).maxOrNull() ?: 0
    }
}
