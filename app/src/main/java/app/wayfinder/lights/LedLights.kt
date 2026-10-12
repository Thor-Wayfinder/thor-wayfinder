package app.wayfinder.lights

import java.io.File

/*
 * b18p (GitHub #109, LED class): the stick rings as the standard Linux LED class shows them — LineageOS's Thor build drives
 * them through `/sys/class/leds/{left,right}:stick:0..3`; any system that names its stick LEDs alike gets the same. Used only
 * when the stock SN3112 nodes ([LightNodes]) aren't there. Format facts only (the kernel's multicolor LED class):
 * `multi_index` names the channels in order ("red green blue"), `multi_intensity` takes one value per channel in that order
 * (0..`max_brightness`), and `brightness` (0..`max_brightness`) scales them all (the kernel rounds each channel down, so a
 * low `brightness` turns dim channels off: the level goes into `multi_intensity`, as the stock rings take it, and
 * `brightness` is the top while on, 0 = off). An LED's `trigger` (a system blink or pattern) is set to "none" while
 * Wayfinder drives it and put back with "AYN's own", then the settings the kernel drops with it ([LedMath.triggerSettings]:
 * a timer's times, a pattern's steps; another trigger comes back by name, with its defaults). Written directly only - the
 * system must let the app write them (no root path: one root command per frame isn't acceptable). Pure (java.io only):
 * LedLightsTest runs the writer end to end on a folder of plain files.
 */

/** Which stick LEDs a name is: a "stick" part and one side, the last number its place in the ring ("left:stick:2"). */
internal object LedNames {
    const val ROOT = "/sys/class/leds"
    /** Per ring, the names tried one by one when the folder can't be listed (left:stick:0..7) — and at most twice that
     *  many taken from a listing. */
    const val PROBED = 8

    private fun parts(name: String) = name.lowercase().split(':', '_', '-').filter { it.isNotEmpty() }

    /** 0 left, 1 right, null = not a stick's LED ("left:stick:0", "right:stick:3", also "left_stick_1" or "stick:right"). */
    fun ring(name: String): Int? {
        val p = parts(name)
        if (p.none { it == "stick" || it == "joystick" }) return null
        val left = "left" in p; val right = "right" in p
        return if (left == right) null else if (left) 0 else 1
    }

    /** Its place in the ring: the last number in its name (0 without one). */
    fun index(name: String): Int = parts(name).lastOrNull { s -> s.all { it.isDigit() } }?.toIntOrNull() ?: 0

    /** The names tried when listing is refused: left:stick:0..7 and right:stick:0..7 ([sep] stands for ':'). */
    fun probes(sep: String = ":"): List<String> =
        (0 until PROBED).flatMap { listOf("left${sep}stick$sep$it", "right${sep}stick$sep$it") }

    /** [names]' stick LEDs by ring ([0] left, [1] right), each in its order around the ring. */
    fun rings(names: Collection<String>): List<List<String>> = (0..1).map { r ->
        names.filter { ring(it) == r }.distinct().sortedWith(compareBy({ index(it) }, { it })).take(PROBED * 2)
    }
}

/** Where the rings' LEDs are. */
internal object LedScan {
    /** The rings' LED folders under [root] ([0] left, [1] right): from its listing when it can be listed, else each
     *  expected name tried ([LedNames.probes] — a listing can be refused while the files are reachable). An LED counts when
     *  its `multi_intensity` and `brightness` are there. Both empty = none. */
    fun find(root: File, list: (File) -> Array<String>? = { it.list() }, exists: (File) -> Boolean = { it.exists() },
             sep: String = ":"): List<List<File>> {
        val names = list(root)?.toList() ?: LedNames.probes(sep)
        return LedNames.rings(names).map { r ->
            r.map { File(root, it) }.filter { exists(File(it, "multi_intensity")) && exists(File(it, "brightness")) }
        }
    }
}

/** A colour and a level as the LED class takes them. */
internal object LedMath {
    /** multi_index's words → each channel's colour: 0 red, 1 green, 2 blue, -1 another one (white, amber…: kept dark).
     *  Unreadable or empty: red green blue. */
    fun order(multiIndex: String?): IntArray {
        val words = multiIndex?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isEmpty()) return intArrayOf(0, 1, 2)
        return words.map { when (it) { "red" -> 0; "green" -> 1; "blue" -> 2; else -> -1 } }.toIntArray()
    }

    /** [argb]'s red, green, blue at [level] (0..1) as the rings show them, each 0..[max] — the stock rings' shaping, as their
     *  `drive()` does (the same LEDs): gamma 2.8, × the level, a channel under 6/255 of the brightest one off, a lit one at
     *  least 1 (a dim colour at a low level stays on). Level 0 = all dark. */
    fun rgb(argb: Int, max: Int, level: Float = 1f): IntArray {
        val top = max.coerceAtLeast(1)
        val lv = level.coerceIn(0f, 1f).toDouble()
        val lin = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF).map { Math.pow(it / 255.0, 2.8) }
        val peak = lin.max()
        return lin.map { v ->
            if (lv <= 0.0 || peak <= 0.0 || v < peak * (6.0 / 255)) 0 else (v * lv * top).toInt().coerceIn(1, top)
        }.toIntArray()
    }

    /** `multi_intensity`'s text for [argb] at [level]: [rgb] in [order] ("255 0 0"). */
    fun intensity(argb: Int, order: IntArray, max: Int, level: Float = 1f): String {
        val c = rgb(argb, max, level)
        return order.joinToString(" ") { if (it in 0..2) c[it].toString() else "0" }
    }

    /** `brightness`'s value: the LED's top while lit ([level] above 0 — the level is in `multi_intensity`), 0 = off. */
    fun brightness(level: Float, max: Int): Int = if (level <= 0f) 0 else max.coerceAtLeast(1)

    /** The active trigger in a `trigger` file's text ("none timer [heartbeat]" → "heartbeat"); null = none or unreadable. */
    fun activeTrigger(text: String?): String? =
        text?.let { Regex("\\[([^\\]\\s]+)\\]").find(it)?.groupValues?.get(1) }?.takeIf { it != "none" }

    /** b18q review: [trigger]'s own settings files, in the order they're written back after it. Changing an LED's trigger
     *  drops them (the kernel stops the blink and resets a timer's times — started again without them it blinks at its
     *  default 500/500 ms; a pattern's steps and the triggers' `invert` live with the trigger). Another trigger: none known,
     *  it comes back by its name only. */
    fun triggerSettings(trigger: String): List<String> = when (trigger) {
        "timer" -> listOf("delay_on", "delay_off")
        "oneshot" -> listOf("delay_on", "delay_off", "invert")
        "heartbeat", "activity" -> listOf("invert")
        "pattern" -> listOf("pattern", "hw_pattern", "repeat")
        else -> emptyList()
    }
}

/** Where the writer's reads and writes go: the files themselves ([LedFiles]; a test's own in LedLightsTest). */
internal interface LedIo {
    /** SAME = nothing to write. GONE = a node isn't there. REFUSED = this system won't let it be written. */
    enum class Result { OK, SAME, GONE, REFUSED, FAILED }
    /** Writes [writes] (file → text) in order, up to the first that fails. */
    fun write(writes: List<Pair<String, String>>): Result
    /** Each file's text (trimmed), null where it can't be read. */
    fun read(paths: List<String>): List<String?>
}

/** The files themselves, as the app. */
internal object LedFiles : LedIo {
    override fun write(writes: List<Pair<String, String>>): LedIo.Result {
        if (writes.isEmpty()) return LedIo.Result.SAME
        for ((path, value) in writes) {
            try { File(path).writeText(value) } catch (e: Exception) { return why(e, File(path)) }
        }
        return LedIo.Result.OK
    }

    /** Refused (EACCES, EPERM, a read-only file), gone (ENOENT), or another failure (a value it didn't take). */
    fun why(e: Exception, f: File): LedIo.Result {
        val m = e.message.orEmpty()
        return when {
            listOf("EACCES", "EPERM", "EROFS", "ermission denied", "not permitted", "ccess is denied", "ead-only").any { it in m } ->
                LedIo.Result.REFUSED
            "ENOENT" in m || "No such file" in m || !f.exists() -> LedIo.Result.GONE
            else -> LedIo.Result.FAILED
        }
    }

    override fun read(paths: List<String>): List<String?> = paths.map { runCatching { File(it).readText().trim() }.getOrNull() }
}

/**
 * Writes the rings' colours to their LEDs: each ring's colour, at the level, to all of its LEDs (`multi_intensity` in each
 * LED's own `multi_index` order; `brightness` the LED's top while lit), only what changed since the last write. A refused
 * write sets [refused]: nothing more is written (the page says the system doesn't let Wayfinder change them). The first
 * write since the last [restore] copies what the LEDs held and sets an active `trigger` to "none"; "AYN's own" ([restore])
 * puts the colours, the levels and the triggers back (each with its own settings, [LedMath.triggerSettings]), when they
 * could be read — else it only stops writing. One thread only (StickLights' lights thread).
 */
internal class LedWriter(val rings: List<List<File>>, private val io: LedIo) {
    /** A write was refused: nothing more is written. */
    var refused = false
        private set
    /** For StickLights: the next look doesn't count on writing directly. */
    val directRefused: Boolean get() = refused

    private class Node(val dir: String, val ring: Int, val order: IntArray, val max: Int)
    private var nodes: List<Node>? = null
    /** What each file was last sent. */
    private val sent = HashMap<String, String>()
    /** What the LEDs held before the first write since the last [restore] (file → text; only what could be read): [values]
     *  the colours and levels; [triggers] each active trigger, its `trigger` file first, then its own settings. */
    private class Saved(val values: List<Pair<String, String>>, val triggers: List<List<Pair<String, String>>>)
    /** Null = not copied yet. */
    private var saved: Saved? = null

    /** The nodes whose write times StickLights watches after a wake. */
    val files: List<File> get() = rings.flatten().flatMap { listOf(File(it, "multi_intensity"), File(it, "brightness")) }

    /** Each LED's channel order and top level, read once (unreadable: red green blue, 255). */
    private fun nodes(): List<Node> = nodes ?: run {
        val dirs = rings.flatMapIndexed { ring, l -> l.map { ring to it.path } }
        val got = io.read(dirs.flatMap { (_, d) -> listOf("$d/multi_index", "$d/max_brightness") })
        dirs.mapIndexed { i, (ring, d) ->
            Node(d, ring, LedMath.order(got.getOrNull(2 * i)), got.getOrNull(2 * i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: 255)
        }
    }.also { nodes = it }

    /** The left ring [left], the right ring [right], at [level] (0..1; 0 = dark). */
    fun write(left: Int, right: Int, level: Float): LedIo.Result = send {
        val out = ArrayList<Pair<String, String>>()
        for (n in nodes()) {
            val c = if (n.ring == 0) left else right
            changed(out, "${n.dir}/multi_intensity", LedMath.intensity(c, n.order, n.max, level))
            changed(out, "${n.dir}/brightness", LedMath.brightness(level, n.max).toString())
        }
        out
    }

    /** Every LED dark — always written (it's what ends a system's own flash at wake, as `enable` 0 does on the stock rings). */
    fun off(): LedIo.Result = send { nodes().map { "${it.dir}/brightness" to "0" } }

    /** The next write sends everything again (the system may have written the LEDs meanwhile: a wake). */
    fun forget() = sent.clear()

    /** What the LEDs held before the first write — colours, levels, then triggers with their own settings — if it could be
     *  read (else nothing: the system's own lights take over). */
    fun restore(): LedIo.Result {
        val s = saved
        saved = null; sent.clear()
        if (s == null || (s.values.isEmpty() && s.triggers.isEmpty()) || refused) return LedIo.Result.SAME
        val r = if (s.values.isEmpty()) LedIo.Result.OK else io.write(s.values)
        putBack(s.triggers)
        return r
    }

    /** Each saved trigger, then its own settings (they exist only once it runs again) — one write each, best effort: a
     *  trigger the system won't take back stays off, a setting it refuses keeps the trigger's default. */
    private fun putBack(triggers: List<List<Pair<String, String>>>) {
        for (t in triggers) if (io.write(listOf(t.first())) == LedIo.Result.OK) t.drop(1).forEach { io.write(listOf(it)) }
    }

    private fun changed(out: MutableList<Pair<String, String>>, file: String, value: String) {
        if (sent[file] != value) out += file to value
    }

    /** Each LED's `multi_intensity` and `brightness` (an LED only when both read), then each active `trigger` with its own
     *  settings, read while that trigger still has them (an empty or unreadable one is left to the trigger's default). */
    private fun snapshot(): Saved {
        val ns = nodes()
        val files = ns.flatMap { listOf("${it.dir}/multi_intensity", "${it.dir}/brightness", "${it.dir}/trigger") }
        val got = io.read(files)
        val values = ns.indices.flatMap { i ->
            val a = got.getOrNull(3 * i); val b = got.getOrNull(3 * i + 1)
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) emptyList() else listOf(files[3 * i] to a, files[3 * i + 1] to b)
        }
        val active = ns.indices.mapNotNull { i -> LedMath.activeTrigger(got.getOrNull(3 * i + 2))?.let { ns[i].dir to it } }
        val own = active.flatMap { (d, t) -> LedMath.triggerSettings(t).map { "$d/$it" } }
        val ownText = if (own.isEmpty()) emptyMap<String, String?>() else own.zip(io.read(own)).toMap()
        val triggers = active.map { (d, t) ->
            listOf("$d/trigger" to t) +
                LedMath.triggerSettings(t).mapNotNull { n -> ownText["$d/$n"]?.takeIf { it.isNotEmpty() }?.let { "$d/$n" to it } }
        }
        return Saved(values, triggers)
    }

    private fun send(batch: () -> List<Pair<String, String>>): LedIo.Result {
        if (refused) return LedIo.Result.REFUSED
        val writes = batch()
        if (writes.isEmpty()) return LedIo.Result.SAME
        if (saved == null) {
            val s = snapshot()
            saved = s
            // a system trigger would keep blinking over Wayfinder's colours: off while Wayfinder drives the LED (best effort)
            s.triggers.forEach { io.write(listOf(it.first().first to "none")) }
        }
        val r = io.write(writes)
        if (r == LedIo.Result.REFUSED) {
            refused = true
            // nothing more is written: a trigger turned off just now goes back at once, with its settings (best effort)
            saved?.let { putBack(it.triggers) }
        }
        if (r == LedIo.Result.OK) for ((f, v) in writes) sent[f] = v else sent.clear()
        return r
    }
}

/** Which backend drives the rings. Pure: LedLightsTest. */
internal object LightBackends {
    enum class Kind { NONE, SN3112, LEDS, LEDS_LOCKED }

    /** The stock SN3112 nodes first, always ([sn3112]); else the LED class's [rings] — written directly when [direct]
     *  (writable, not refused before), else found but locked. */
    fun pick(sn3112: Boolean, rings: List<List<File>>, direct: Boolean): Kind = when {
        sn3112 -> Kind.SN3112
        rings.all { it.isEmpty() } -> Kind.NONE
        direct -> Kind.LEDS
        else -> Kind.LEDS_LOCKED
    }

    /** Every LED's two nodes writable by the app (access(2): permissions and the system's policy). */
    fun writable(rings: List<List<File>>, canWrite: (File) -> Boolean = { it.canWrite() }): Boolean =
        rings.flatten().let { l -> l.isNotEmpty() && l.all { canWrite(File(it, "multi_intensity")) && canWrite(File(it, "brightness")) } }
}
