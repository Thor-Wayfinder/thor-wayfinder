package app.wayfinder

/**
 * b18i (D2, "detect harder"; device g1 BUG-2): which of the game files an app keeps open it is READING. Eden keeps its
 * whole library open (10+ .nsp / .xci in `/proc/<pid>/fd`), so "the one game file it keeps open" said nothing, and a game
 * started from Eden's own list was never named. The root helper ([GameDetector], uid 0) now looks at what the app does with
 * those files:
 *  - a descriptor's offset (`/proc/<pid>/fdinfo/<fd>` "pos") that moved between two looks: the app read that file;
 *  - a game file mapped since the previous look (`/proc/<pid>/maps`): the newest mapping. On a process's first look, the only
 *    game file mapped, if just one is.
 * One game, several files (base / update / DLC, a disc's tracks): one game here ([Families]); the answer is a file again.
 * A game seen read stays the answer while what showed it lasts ([Kept], [lasting]: the descriptor it was read on, its mapping)
 * and no other game is seen read alone at two looks in a row (or newly mapped): a game in a menu reads nothing, and the answer
 * must not come and go
 * (each change swaps the controls, performance and lights). b18i (review): a game quit to the app's own list reads nothing
 * either — only what it held tells the two apart; it was kept as long as any file of it stayed open (Eden: always).
 * "Null beats wrong" stays: read one game and mapped another, or several read with none remembered among them — no answer
 * from these signals. Cocoon's record ([choose]'s `byName`: the open file whose name matches the game it launched) is still the
 * answer when they say nothing; when they clearly point at another game, no answer at all ([Choice.veto]).
 * General: the same rule for every app, no app list. Pure (GameReadingTest).
 */
internal object GameReading {
    /** One look at one open game file: [fd] = "<pid>/<fd>", [game] the game id its name gives, [pos] its offset. */
    data class Fd(val fd: String, val game: String, val pos: Long)

    /** The descriptors read between two looks: the same descriptor, still on the same game, at another offset. A descriptor
     *  opened or closed in between says nothing here (an opening is GameDetector's "newly opened" rule); one reused for another
     *  game file neither. */
    fun moved(before: Collection<Fd>, after: Collection<Fd>): List<Fd> {
        val b = before.associateBy { it.fd }
        return after.filter { a -> b[a.fd]?.let { it.game == a.game && it.pos != a.pos } == true }
    }

    /** The games read between two looks ([moved]). */
    fun read(before: Collection<Fd>, after: Collection<Fd>): Set<String> = moved(before, after).map { it.game }.toSet()

    /** The games mapped [now] that weren't at the previous look ([before]). [before] null (this process's first look): the only
     *  game file mapped, if just one is — the newest by construction. */
    fun newlyMapped(before: Set<String>?, now: Set<String>): Set<String> =
        if (before == null) (if (now.size == 1) now else emptySet()) else now - before

    /**
     * b18i (review): one game, several files — the base, its update and DLC .nsp, a disc's Track 1 / Track 2 .bin. Their ids
     * differ (the id is the raw name: profiles are stored under it) but their tidied names don't ([tidy]: each open file's id
     * → its tidied name's id). With Cocoon's name, the files it matches as well as the one it picked ([byName], [ties]) are
     * one game too. Read as separate games, a read of the update vetoed Cocoon's answer (the base), and reads switching
     * between the files flipped the answer between them.
     */
    class Families(private val tidy: Map<String, String>, private val byName: String?, ties: Set<String>) {
        private val nameFamily = byName?.let { tidy[it] }
        private val merged = if (nameFamily == null) emptySet() else ties.mapNotNull { tidy[it] }.toSet() + nameFamily

        /** The family of a tidied id (a mapped file's, or one remembered from an earlier look). */
        fun of(tidyId: String): String = if (nameFamily != null && tidyId in merged) nameFamily else tidyId

        /** The family of an open file (its id); null: not open. */
        fun ofFile(id: String): String? = tidy[id]?.let { of(it) }

        /** The file that answers for [family]: Cocoon's pick when it is in it (its id, as before D2), else the family's first
         *  id in sorted order — the base before its update or DLC, Track 1 first, whatever order the app opened them in. */
        fun fileOf(family: String): String? =
            byName?.takeIf { ofFile(it) == family } ?: tidy.keys.filter { ofFile(it) == family }.minOrNull()
    }

    enum class Why {
        /** Nothing seen, no name: no answer. */ NONE,
        /** The one game read now. */ READ,
        /** The one game newly mapped now (nothing read, or read the same). */ MAPPED,
        /** Nothing new: the game last seen read, still held (also when it is among several read now). */ KEPT,
        /** b18i (review): another game read alone, once: the remembered one stays until that one is seen alone again. */ NEXT,
        /** Cocoon's name, nothing seen against it. */ NAME,
        /** Several read, none remembered among them: no answer from the signals. */ SEVERAL,
        /** Read one game, newly mapped another: no answer from the signals, the remembered one forgotten. */ SIGNALS,
        /** What it reads is not the game Cocoon names: no answer at all. */ OTHER,
    }

    /** [game] the answer (null: none from here); [keep] the game to remember as read (same process); [next] another game seen
     *  read alone at this look — it replaces [keep] if seen alone again at the next one; [why] for the log. */
    data class Choice(val game: String?, val keep: String?, val why: Why, val next: String? = null) {
        /** No answer at all — not even Cocoon's own name (GameDetector's last fallback). */
        val veto get() = why == Why.OTHER
    }

    /**
     * [open] the game ids of the open files; [read] the ids read between the two looks ([read]); [mapped] the ids newly mapped
     * ([newlyMapped]); [kept] the game last seen read in this process, still held ([lasting]); [byName] the open file Cocoon's
     * record names (null: Cocoon launched nothing in this session); [next] the other game seen read alone at the previous look.
     */
    fun choose(open: Set<String>, read: Set<String>, mapped: Set<String>, kept: String?, byName: String?, next: String? = null): Choice {
        val r = read intersect open
        val m = mapped intersect open
        val clash = r.isNotEmpty() && m.isNotEmpty() && (r intersect m).isEmpty()
        // both signals point: where they agree
        val pointed = when {
            clash -> emptySet()
            r.isNotEmpty() && m.isNotEmpty() -> r intersect m
            else -> r + m
        }
        val keptOpen = kept?.takeIf { it in open }
        val keep = when {
            clash -> null
            // b18i (review): another game than the remembered one, read alone: seen twice in a row first. A new mapping (an event:
            // new once) or Cocoon's own game: at once
            pointed.size == 1 -> pointed.single().let { p ->
                if (keptOpen == null || p == keptOpen || p == next || p == byName || p in m) p else keptOpen
            }
            pointed.size > 1 -> keptOpen?.takeIf { it in pointed }   // still read (with others): it stays
            else -> keptOpen
        }
        val other = pointed.singleOrNull()?.takeIf { keep != null && it != keep }
        return when {
            keep == null -> Choice(byName, null, when {
                clash -> Why.SIGNALS
                byName != null -> Why.NAME
                pointed.size > 1 -> Why.SEVERAL
                else -> Why.NONE
            })
            byName != null && byName != keep -> Choice(null, keep, Why.OTHER, other)
            other != null -> Choice(keep, keep, Why.NEXT, other)
            pointed.size == 1 -> Choice(keep, keep, if (keep in r) Why.READ else Why.MAPPED)
            else -> Choice(keep, keep, Why.KEPT)
        }
    }

    /** b18i (review): the game remembered as read in one process, and what showed it: [fds] the descriptors ("pid/fd") it was
     *  read or opened on, [mapped] it was mapped; [next] see [Choice.next]. */
    data class Kept(val game: String, val fds: Set<String> = emptySet(), val mapped: Boolean = false, val next: String? = null)

    /** [k] while what showed it is still there: a mapping it had ([mappedNow]: the games mapped now), and one of its
     *  descriptors still on it ([fdsNow]: each open descriptor's game now). A game quit to the app's own list reads nothing,
     *  like a paused one: only what it held tells them apart — no timer (a paused game would come and go). Null: gone. */
    fun lasting(k: Kept?, fdsNow: Map<String, String>, mappedNow: Set<String>): Kept? = k?.takeUnless {
        (it.mapped && it.game !in mappedNow) || (it.fds.isNotEmpty() && it.fds.none { fd -> fdsNow[fd] == it.game })
    }

    /** What to remember after [c] ([was]: the previous [Kept], after [lasting]): its game, with what shows it — the
     *  descriptors it was read on at this look ([readOn]: [moved]) added to those of [was] (the same game), and mapped now or
     *  before ([mappedNow]). Null: nothing to remember. */
    fun remember(c: Choice, was: Kept?, readOn: Collection<Fd>, mappedNow: Set<String>): Kept? {
        val g = c.keep ?: return null
        val same = was?.takeIf { it.game == g }
        val fds = same?.fds.orEmpty() + readOn.filter { it.game == g }.map { it.fd }
        return Kept(g, fds, same?.mapped == true || g in mappedNow, c.next)
    }
}
