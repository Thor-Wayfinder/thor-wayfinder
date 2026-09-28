package app.wayfinder

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * App side of per-screen brightness (the root side is [ScreenBrightness]).
 * Levels are 0..1 as Android stores them; the steps and the % shown are on a square-root
 * scale so each press looks like the same change to the eye. Call off the main thread.
 */
object ScreenLevels {
    private val cache = ConcurrentHashMap<Int, Float>()

    /** Bumped on every write (combos, sliders) — UI sliders follow it live via [known]. */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    /** The last level we know for [displayId] (no I/O). */
    fun known(displayId: Int): Float? = cache[displayId]

    private val usedAt = ConcurrentHashMap<Int, Long>()

    /** Cached while presses follow each other; re-read after a pause (AYN's slider or
     *  auto-brightness may have moved it meanwhile). */
    fun get(ctx: Context, displayId: Int): Float? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - (usedAt[displayId] ?: 0L) > 5000) cache.remove(displayId)
        usedAt[displayId] = now
        return read(ctx, displayId)
    }

    private fun read(ctx: Context, displayId: Int): Float? = cache[displayId]
        ?: PServiceBridge.runEntryPoint(ctx, "BrightnessTool", "get", displayId.toString())
            ?.takeIf { it.startsWith("OK ") }?.removePrefix("OK ")?.trim()?.toFloatOrNull()
            ?.also { cache[displayId] = it }

    fun set(ctx: Context, displayId: Int, value: Float) {
        val v = value.coerceIn(0.0004f, 1f)
        cache[displayId] = v
        android.os.Handler(android.os.Looper.getMainLooper()).post { changes.intValue++ }
        // Fast path: the input helper is already running as root.
        if (!InputMonitor.send("L $displayId $v"))
            PServiceBridge.runEntryPoint(ctx, "BrightnessTool", "set", displayId.toString(), v.toString())
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingSet = ConcurrentHashMap<Int, Float>()

    /** For sliders: coalesces fast moves into the latest value. */
    fun setAsync(ctx: Context, displayId: Int, value: Float) {
        cache[displayId] = value
        usedAt[displayId] = android.os.SystemClock.uptimeMillis()
        if (pendingSet.put(displayId, value) == null)
            worker.execute { pendingSet.remove(displayId)?.let { set(ctx, displayId, it) } }
    }

    private val fresh = ConcurrentHashMap<Int, Float>()

    /** The input helper's answer to "Q": a screen's current level. */
    fun onHelperLevel(displayId: Int, value: Float) {
        cache[displayId] = value; fresh[displayId] = value
        synchronized(fresh) { (fresh as Object).notifyAll() }
    }

    /**
     * For UI: the screens' levels. What we already know is shown at once (no empty slider
     * while reading); the fresh values then come from the input helper — already root, so
     * milliseconds — or, if it's not running, from a root process per screen (~0.25 s each).
     */
    fun readAsync(ctx: Context, displayIds: List<Int>, done: (Map<Int, Float>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val known = displayIds.mapNotNull { d -> cache[d]?.let { d to it } }.toMap()
        if (known.size == displayIds.size) done(known)
        worker.execute {
            displayIds.forEach { fresh.remove(it) }
            if (InputMonitor.send("Q ${displayIds.joinToString(" ")}")) {
                val end = android.os.SystemClock.uptimeMillis() + 400
                synchronized(fresh) {
                    while (displayIds.any { !fresh.containsKey(it) } && android.os.SystemClock.uptimeMillis() < end)
                        (fresh as Object).wait(50)
                }
            }
            val m = displayIds.mapNotNull { d -> (fresh[d] ?: run { cache.remove(d); read(ctx, d) })?.let { d to it } }.toMap()
            main.post { done(m) }
        }
    }

    /**
     * "Both": move every screen by the same amount on the eye's scale, so the difference
     * between them is kept (until one hits an end). Returns the new levels.
     */
    fun shiftBoth(ctx: Context, levels: Map<Int, Float>, delta: Float): Map<Int, Float> =
        levels.mapValues { (d, v) -> fromSlider(toSlider(v) + delta).also { setAsync(ctx, d, it) } }

    /** Where the "Both" slider sits: the average of the screens (eye scale). */
    fun bothPos(levels: Map<Int, Float>): Float = levels.values.map { toSlider(it) }.average().toFloat()

    /** One press: ±5 % on the eye's scale (the % the sliders show). */
    fun step(value: Float, dir: Int): Float = fromSlider(sqrt(value) + dir * 0.05f)

    fun percent(value: Float): Int = (sqrt(value) * 100).roundToInt()

    /** Slider position (0..1, eye scale) ↔ level. */
    fun toSlider(value: Float): Float = sqrt(value)
    fun fromSlider(pos: Float): Float = snap(pos).let { it * it }

    /** Brightness always lands on 5 % steps (5 … 100 % — never 2 % or 77 %). */
    fun snap(pos: Float): Float = (Math.round(pos * 20f) / 20f).coerceIn(0.05f, 1f)

    /** Forget cached levels (something else may have changed them). */
    fun invalidate() = cache.clear()
}
