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
 */
object FpsSampler {
    private const val TAG = "ThorFps"
    @Volatile private var pkgs: List<String?> = listOf(null, null)
    private var thread: Thread? = null
    var send: ((String) -> Unit)? = null

    /** `F <top pkg|-> [<bottom pkg|->]`. */
    fun watch(top: String?, bottom: String? = null) {
        fun clean(p: String?) = p?.takeIf { it.isNotBlank() && it != "-" }
        pkgs = listOf(clean(top), clean(bottom))
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
        while (true) {
            val watch = pkgs
            if (watch.all { it == null }) { thread = null; return }   // stopped; watch() starts a new loop
            val now = System.nanoTime()
            val out = IntArray(2) { -1 }
            var all: List<String>? = null
            for (i in 0..1) {
                val p = watch[i] ?: continue
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
                var best = 0
                for (l in layers[i]) {
                    val ts = sh("dumpsys SurfaceFlinger --latency '${l.replace("'", "")}'").drop(1).mapNotNull {
                        it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.takeIf { v -> v in 1 until Long.MAX_VALUE / 2 }
                    }
                    val lastSec = ts.count { now - it in 0..1_000_000_000L }
                    if (lastSec > best) best = lastSec
                }
                if (best == 0) layersAt[i] = 0L   // look for a new surface next time
                out[i] = best
            }
            send?.invoke("F ${out[0]} ${out[1]}\n")
            val spent = (System.nanoTime() - now) / 1_000_000
            Thread.sleep((1000 - spent).coerceIn(200, 1000))
        }
    }

    init { Log.d(TAG, "ready") }
}
