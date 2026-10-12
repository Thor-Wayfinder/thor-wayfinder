package app.wayfinder

// dev build 17 (WP10): what the overlay host shows on each screen — pure, so CueSlotsTest drives
// it with its own clock. FocusCue turns its effects into windows; Overlays is how the app posts to it.

/** Where a cue comes from — the host decides the screen ([CueWhere.of]), never the caller. */
sealed class CueOrigin {
    /** A press on controller [src] (as ButtonEngine counts them: a docked controller playing as the Thor's is 0). */
    data class Press(val src: Int) : CueOrigin()
    /** About one screen itself: a screenshot of it, it woke, an app moved there, "nothing to close here". */
    data class Screen(val display: Int) : CueOrigin()
    /** A Wayfinder window on [display] (a Hub page, the quick panel, the tour): the user looks there. */
    data class Window(val display: Int) : CueOrigin()
    /** About a connected controller (its key; null: one we can't name) — not a press. */
    data class Pad(val key: String?) : CueOrigin()
    /** Nobody pressed anything: the helper, a restore, the input layer's state. */
    object System : CueOrigin()
}

/** Priority on one screen: [HELD] > [TIP] > [PILL]. */
enum class CueKind { PILL, TIP, HELD }

/** A held list's token. [maxMs]: never on screen longer (a missed release). Critique K12: one list per controller — its
 *  [key] carries the controller, so one's release never hides another's (two held lists on one screen stack). */
enum class Held(val maxMs: Long) {
    CHORDS(8000), SHIFT(8000), RECENTS(5000);
    /** [src]: the controller, as its presses count (0: the Thor, or a list about no controller — Recents). */
    fun key(src: Int = 0) = "$name@$src"
}

sealed class CueContent {
    data class Line(val text: String) : CueContent()
    /** [title] lines, then (button, what it does) cells; [at] 0 top centre · 1 top left · 2 top right · 3 bottom left ·
     *  4 bottom right; [compact] one slim row (Recents). Build 18 (E12): [footer] rows under the cells, as wide as the table
     *  (wrapped there — a long cell among the others widened its column and folded the list into one tall column). */
    data class Table(val title: List<String>, val cells: List<Pair<String, String>>, val at: Int = 0,
                     val compact: Boolean = false, val footer: List<Pair<String, String>> = emptyList()) : CueContent()
    /** Everything it says, for its reading time. */
    fun words(): String = when (this) {
        is Line -> text
        is Table -> (title + (cells + footer).flatMap { listOf(it.first, it.second) }).joinToString(" ")
    }
    /** The debug log's form (device test S8 reads it): a line, or a table's title and cells joined by " | ". */
    fun logLine(): String = when (this) {
        is Line -> text
        is Table -> (title + (cells + footer).map { "${it.first} ${it.second}" }).joinToString(" | ")
    }
}

/**
 * Build 18 (E9, V13 / gd-D21): where a cue's window sits on its screen — pure (OverlayRulesTest). [at]: 0 top centre ·
 * 1 top left · 2 top right · 3 bottom left · 4 bottom right · 5 bottom centre; [yDp] from that edge; [enterDp] where it
 * slides in from (negative: from above).
 */
data class CueSpot(val at: Int, val yDp: Int, val enterDp: Int) {
    companion object {
        /** How far down a pill sits on the screen "Your combos" is up on — under its header cards (E6, MORE-19). */
        const val COMPANION_HEADER_DP = 92
        /** A pill from one of Wayfinder's own windows: this far above the bottom edge. */
        const val WINDOW_PILL_DP = 40

        /** A table where it asks ([CueContent.Table.at]; bottom corners above Recents' own row of buttons, a slim one hugging
         *  the top edge). A pill: on the companion's screen under its cards; from a Wayfinder window ([Cue.fromWindow]) at the
         *  bottom centre, sliding up — top centre it covered the Hub's Search and Ko-fi, Game controls' title and face pills;
         *  over a game (any other origin) top centre, as before. */
        fun of(content: CueContent, fromWindow: Boolean, underCompanion: Boolean): CueSpot = when (content) {
            is CueContent.Table -> CueSpot(content.at, if (content.at >= 3) 96 else if (content.compact) 6 else 22, -8)
            is CueContent.Line -> when {
                underCompanion -> CueSpot(0, COMPANION_HEADER_DP, -8)
                fromWindow -> CueSpot(5, WINDOW_PILL_DP, 8)
                else -> CueSpot(0, 22, -8)
            }
        }
    }
}

/** Screen / Window → that screen while it exists; everything else (and a screen gone) → the controller's screen. */
object CueWhere {
    fun of(o: CueOrigin, controller: Int, live: Set<Int>): Int = when (o) {
        is CueOrigin.Screen -> o.display.takeIf { it in live } ?: controller
        is CueOrigin.Window -> o.display.takeIf { it in live } ?: controller
        else -> controller
    }
}

/** How long a cue stays: read from its text, never typed at the call site. */
object CueTiming {
    const val MIN_MS = 1500L
    const val MAX_MS = 7000L
    /** Words a reader reads: anything with a letter or a digit, and each PlayStation mark (✕ ○ □ △). */
    fun words(s: String): Int = s.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() || it in "✕○□△" } }
    /** 1.2 s + 0.3 s a word, 1.5 to 7 s. */
    fun readMs(s: String): Long = (1200L + 300L * words(s)).coerceIn(MIN_MS, MAX_MS)
}

/** One cue. [key]: a pill's group (same group = updated in place), a tip's id (its "seen" flag), a held list's
 *  [Held.key]. Its clock runs only while it is on screen. Build 18 (E9): [fromWindow] — a pill posted from one of Wayfinder's
 *  own windows ([CueOrigin.Window]): it sits at the bottom ([CueSpot]). */
class Cue(val kind: CueKind, val display: Int, val content: CueContent, val key: String, val limitMs: Long,
          val fromWindow: Boolean = false) {
    internal var shownMs = 0L        // time on screen so far (closed clocks)
    internal var since = -1L         // on screen since (-1: not on screen)
    internal var everShown = false
    internal var seenSaid = false
}

sealed class CueEffect {
    /** Put [cue] up. [fresh]: its first time (fade in, the ThorCue log line); else it comes back from under another one. */
    data class Show(val cue: Cue, val fresh: Boolean) : CueEffect()
    /** Take it down: [fade] when it is finished (expired, released, dismissed), at once when covered or replaced. */
    data class Hide(val cue: Cue, val fade: Boolean, val why: String) : CueEffect()
    /** A tip has been on screen 2 s: write its flag. */
    data class Seen(val cue: Cue) : CueEffect()
    /** Gone without (more) showing — a full queue, its screen gone: the debug log only. */
    data class Drop(val cue: Cue, val why: String) : CueEffect()
}

/**
 * The cues of every screen. One slot per display shows at most one cue — the newest held list, else the current tip, else
 * the current pill; a lower one is covered (its clock paused), never destroyed. Every operation returns what the screen must
 * do ([CueEffect]); the host calls [tick] at [nextDue]. Not thread-safe: the host's main thread only.
 */
class CueSlots(private val now: () -> Long) {
    companion object {
        /** A tip counts as seen after 2 s on screen. */
        const val TIP_SEEN_MS = 2000L
        /** Critique F3: a press in a tip's first 2 s doesn't dismiss it (the first-game tip comes up while the player is
         *  pressing the game's buttons); after them it has been seen and any button does. */
        const val TIP_GRACE_MS = TIP_SEEN_MS
        /** A pill on screen this long has been read: once something covers it, it's done (it doesn't come back later). */
        const val READ_MS = 1500L
        /** Pills waiting per screen (behind the current one). */
        const val MAX_WAITING = 3
    }

    private class Slot {
        val held = ArrayList<Cue>()          // a stack: the newest shows
        val tips = ArrayDeque<Cue>()         // head = the current one
        val pills = ArrayDeque<Cue>()        // head = the current one
        var shown: Cue? = null
        fun want(): Cue? = held.lastOrNull() ?: tips.firstOrNull() ?: pills.firstOrNull()
        fun all(): List<Cue> = held + tips + pills
    }
    private val slots = LinkedHashMap<Int, Slot>()
    private var stashed = false
    private var out = ArrayList<CueEffect>()

    private inline fun op(block: () -> Unit): List<CueEffect> { out = ArrayList(); block(); return out }
    private fun slot(d: Int) = slots.getOrPut(d) { Slot() }
    private fun elapsed(c: Cue): Long = c.shownMs + if (c.since >= 0) now() - c.since else 0L

    /** A new cue on its display ([Cue.display]). */
    fun post(c: Cue): List<CueEffect> = op {
        when (c.kind) {
            CueKind.PILL -> {
                // review WP10-2: the same group on ANOTHER screen goes too — one message per group ("Controller on the
                // bottom screen" then "… top screen": both up for 3 s, the bottom one false; build 16 kept only the last).
                // That screen then shows what waited under it
                for ((d, o) in slots.entries.toList()) if (d != c.display) o.pills.firstOrNull { it.key == c.key }?.let {
                    remove(d, it, fade = false, why = "updated"); settle(d)
                }
                val s = slot(c.display)
                // the same group: replaced (the new one restarts its time)
                s.pills.firstOrNull { it.key == c.key }?.let { remove(c.display, it, fade = false, why = "updated") }
                // another group: the current one waits under it — unless it has been read already
                s.pills.firstOrNull()?.let { if (elapsed(it) >= READ_MS) remove(c.display, it, fade = false, why = "read") }
                s.pills.addFirst(c)
                while (s.pills.size - 1 > MAX_WAITING) out += CueEffect.Drop(s.pills.removeLast(), "queue")
                settle(c.display)
            }
            CueKind.TIP -> {
                // one occasion, one tip: the same one anywhere (shown, covered or waiting) is enough
                if (slots.values.any { s -> s.tips.any { it.key == c.key } }) return@op
                slot(c.display).tips.addLast(c)
                settle(c.display)
            }
            CueKind.HELD -> {
                // the same list again (its controller moved to another screen): the old one goes first
                val old = findHeld(c.key)
                old?.let { (d, o) -> remove(d, o, fade = false, why = "updated") }
                slot(c.display).held.add(c)
                old?.first?.takeIf { it != c.display }?.let { settle(it) }
                settle(c.display)
            }
        }
    }

    /** Held list [key] ([Held.key]) let go — nothing else moves. */
    fun release(key: String): List<CueEffect> = op {
        val (d, c) = findHeld(key) ?: return@op
        remove(d, c, fade = true, why = "released")
        settle(d)
    }

    /** Any button on any controller: a tip that has been up [TIP_GRACE_MS] (shown or covered) goes. */
    fun anyButton(): List<CueEffect> = op {
        for ((d, s) in slots.entries.toList()) {
            val done = s.tips.filter { it.everShown && elapsed(it) >= TIP_GRACE_MS }
            if (done.isEmpty()) continue
            for (t in done) { seen(t); remove(d, t, fade = true, why = "button") }
            settle(d)
        }
    }

    /** The clocks: a tip seen, a cue's time up (a held list's safety maximum). */
    fun tick(): List<CueEffect> = op {
        for ((d, s) in slots.entries.toList()) {
            val c = s.shown ?: continue
            seen(c)
            if (elapsed(c) >= c.limitMs) {
                remove(d, c, fade = true, why = if (c.kind == CueKind.HELD) "max" else "time")
                settle(d)
            }
        }
    }

    /** Milliseconds until [tick] has something to do (null: nothing on screen). */
    fun nextDue(): Long? {
        var best: Long? = null
        for (s in slots.values) {
            val c = s.shown ?: continue
            val e = elapsed(c)
            var due = c.limitMs - e
            if (c.kind == CueKind.TIP && !c.seenSaid) due = minOf(due, TIP_SEEN_MS - e)
            best = minOf(best ?: due, due)
        }
        return best?.coerceAtLeast(0L)
    }

    /** A screenshot: everything off screen, kept, no clock running; posts wait for [unstash]. */
    fun stash(): List<CueEffect> = op {
        if (stashed) return@op
        for (s in slots.values) s.shown?.let { close(it); s.shown = null; out += CueEffect.Hide(it, false, "stash") }
        stashed = true
    }

    /** …and back: each screen shows what it should now (a cue posted meanwhile is fresh). */
    fun unstash(): List<CueEffect> = op {
        if (!stashed) return@op
        stashed = false
        for (d in slots.keys.toList()) settle(d)
    }

    /** Display [d] is gone (undocked): its cues go with it. */
    fun displayGone(d: Int): List<CueEffect> = op {
        val s = slots.remove(d) ?: return@op
        val shown = s.shown
        shown?.let { close(it); out += CueEffect.Hide(it, false, "display gone") }
        for (c in s.all()) if (c !== shown) out += CueEffect.Drop(c, "display gone")
    }

    /** The service is re-created: nothing is kept (its windows are removed by the host). */
    fun clear() { slots.clear(); stashed = false }

    /** What display [d] shows (null: nothing, or stashed). */
    fun visible(d: Int): Cue? = slots[d]?.shown
    /** The displays with cues (on screen or waiting) — the host drops the ones that are gone. */
    fun displays(): Set<Int> = slots.filterValues { it.all().isNotEmpty() }.keys.toSet()
    /** A tip a button can dismiss is up (on screen, or covered after showing) — the key hook's cheap check. */
    val tipUp: Boolean get() = slots.values.any { s -> s.tips.any { it.everShown } }

    // ── the model ──
    private fun findHeld(key: String): Pair<Int, Cue>? {
        for ((d, s) in slots) s.held.firstOrNull { it.key == key }?.let { return d to it }
        return null
    }

    /** [c] out of display [d]'s slot — hidden if it was on screen. The caller settles. */
    private fun remove(d: Int, c: Cue, fade: Boolean, why: String) {
        val s = slots[d] ?: return
        when (c.kind) { CueKind.HELD -> s.held.remove(c); CueKind.TIP -> s.tips.remove(c); CueKind.PILL -> s.pills.remove(c) }
        if (s.shown === c) { close(c); s.shown = null; out += CueEffect.Hide(c, fade, why) }
    }

    /** [c]'s clock stops (it leaves the screen). */
    private fun close(c: Cue) {
        if (c.since >= 0) { c.shownMs += now() - c.since; c.since = -1L }
        seen(c)
    }

    private fun seen(c: Cue) {
        if (c.kind == CueKind.TIP && !c.seenSaid && elapsed(c) >= TIP_SEEN_MS) { c.seenSaid = true; out += CueEffect.Seen(c) }
    }

    /** Display [d] shows what its slot wants now: the newest held list, else the current tip, else the current pill. */
    private fun settle(d: Int) {
        if (stashed) return
        val s = slots[d] ?: return
        val want = s.want()
        val cur = s.shown
        if (want === cur) return
        if (cur != null) {
            close(cur); s.shown = null
            // rule 4: a pill already read is done once something covers it — it doesn't come back later
            if (cur.kind == CueKind.PILL && cur.shownMs >= READ_MS) { s.pills.remove(cur); out += CueEffect.Hide(cur, false, "read") }
            else out += CueEffect.Hide(cur, false, "covered")
        }
        if (want != null) {
            want.since = now()
            val fresh = !want.everShown
            want.everShown = true
            s.shown = want
            out += CueEffect.Show(want, fresh)
        }
    }
}
