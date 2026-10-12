package app.wayfinder

/*
 * dev build 17 (WP11, theme H): the controller pages' rules, apart from the pages (pure Kotlin: app/src/test loads it).
 * Every controller, the Thor first, gets ONE page (ControllerPage.kt); what it shows comes from what the controller can do
 * (its caps), never from which controller it is. The words are in [CtlWords] (Words.kt since dev build 17, WP9).
 */

/** Which controller a page is about: the Thor ([HubPage.THOR]) or a connected one (its [ExtRules.KEY]). */
sealed class CtlRef(val key: String) {
    object Thor : CtlRef(HubPage.THOR)
    class Pad(key: String) : CtlRef(key)
    companion object { fun of(key: String): CtlRef = if (key == HubPage.THOR) Thor else Pad(key) }
}

/** The template's sections, in the page's order (WP11: face; test; in games; Home; motion; setup). */
enum class CtlSection { FACE, TEST, IN_GAMES, HOME, MOTION, SETUP, ABOUT }

/** What a controller's page can show. [away]: gone for a while (its place kept — nothing to read); [motionOnPage]: a motion
 *  aim of its own (the Nintendo kinds, D10 — the Thor's gyro is set per game); [readByWayfinder]: Wayfinder reads it
 *  itself (its buttons can be set up, games see a copy of it) — the Thor's is AYN's. dev build 18f: [touchpad] a PlayStation
 *  touchpad wfhid reads (its "Touchpad moves the pointer" card: wfhid's I line says "sony" — the kinds it decodes). */
data class CtlCaps(val thor: Boolean, val away: Boolean, val motionOnPage: Boolean, val readByWayfinder: Boolean, val touchpad: Boolean = false)

enum class CtlState { THOR, HANDLED, STARTING, AWAY }

/** Build 18 (C7, VD-12): the rows of a controller's page that come and go ([ControllerRules.rows]): [ownEdit] its own
 *  controls on (their Edit row), [removable] / [previous] Remove / Bring back shown, [muted] "Turn its controls back on",
 *  [joyCon] its Held sideways card, [custom] "Back to automatic buttons", [rumbles] "Find it". */
data class CtlRowFacts(val ownEdit: Boolean = false, val removable: Boolean = false, val previous: Boolean = false,
                       val muted: Boolean = false, val joyCon: Boolean = false, val custom: Boolean = false, val rumbles: Boolean = false)

/** The status header's facts, every name already in the controller's own words (the page passes Vocab / Say text):
 *  [home] its Home's name ("PS", "Capture", "Select + Start (hold)"); [homeOurs] its Home is the Thor's Home; [back] its
 *  Back (theme G's seam: null until its page fills it); [gameControls] the Game controls combo it can press — the Thor's
 *  ([gcOnThor]) when its own Home goes to the game; [docked] it plays as the Thor's controller ([dockedName]: which one, on
 *  the Thor's page). dev build 18f: [seated] it plays as the Thor's controller by its switch, undocked ([player]: the copy's
 *  number; the Thor's page: [dockedName] which one). dev build 18f2: [after] what its number becomes when the game a new
 *  player order waits for closes ([CtlWords.afterClose]; [ControllerRules.playerNo]). */
data class CtlStatus(val state: CtlState, val player: Int = 0, val home: String? = null, val homeOurs: Boolean = true,
                     val back: String? = null, val gameControls: String? = null, val gcOnThor: Boolean = false,
                     val docked: Boolean = false, val dockedName: String? = null, val seated: Boolean = false,
                     val after: String? = null)

object ControllerRules {
    fun caps(thor: Boolean, kind: ControllerKind, away: Boolean, touchpad: Boolean = false) =
        CtlCaps(thor, away && !thor, motionOnPage = !thor && kind.gyro, readByWayfinder = !thor, touchpad = !thor && touchpad)

    /** The sections a controller's page shows, in order: face buttons first, its games and its Home always; the test only
     *  while it is here; motion where it has some; setup where Wayfinder reads it; what games see where Wayfinder reads it
     *  and on the Thor's (build 18, C11 / T18-10: the name games list for the Thor's controller — it was under "Settings for
     *  every controller"). */
    fun sections(c: CtlCaps): List<CtlSection> = buildList {
        add(CtlSection.FACE)
        if (!c.away) add(CtlSection.TEST)
        add(CtlSection.IN_GAMES); add(CtlSection.HOME)
        if (c.motionOnPage) add(CtlSection.MOTION)
        if (c.readByWayfinder) add(CtlSection.SETUP)
        if (c.thor || c.readByWayfinder) add(CtlSection.ABOUT)
    }

    /** Build 18 (C7, VD-12): how many cards and rows section [s] shows now — a header over a single one only repeated its
     *  title ("MOTION" over "Aim with motion"). [f]: the rows that come and go. */
    fun rows(s: CtlSection, c: CtlCaps, f: CtlRowFacts = CtlRowFacts()): Int = when (s) {
        CtlSection.FACE -> if (c.thor) 2 else 1                                  // the Thor's style + Names as printed
        CtlSection.TEST -> 1
        CtlSection.IN_GAMES -> if (c.thor) 1 else 1 + listOf(f.ownEdit, f.removable, f.previous).count { it }
        // its Home, Back and AYN, Combos (+ controls back on; + dev build 18f: Touchpad moves the pointer)
        CtlSection.HOME -> if (c.thor) 2 else 3 + (if (f.muted) 1 else 0) + (if (c.touchpad) 1 else 0)
        CtlSection.MOTION -> 1
        CtlSection.SETUP -> listOf(f.joyCon, !c.away, !c.away, f.custom).count { it }     // sideways, set up, re-centre, automatic
        // its name in games (C11) / dev build 18f: Plays as the Thor's controller, what games see, Find it
        CtlSection.ABOUT -> if (c.thor) 1 else 2 + (if (f.rumbles && !c.away) 1 else 0)
    }
    /** A section's header shows over 2 cards or rows or more ([rows]) — or over a lone card that needs it ([headedAlone]). */
    fun headed(s: CtlSection, c: CtlCaps, f: CtlRowFacts = CtlRowFacts()): Boolean = rows(s, c, f) >= 2 || headedAlone(s, c)
    /** Device pass (C, d2 N1): a lone card whose title doesn't say its section keeps the header — the Thor's "Its name in
     *  games", last on its page, read as one of Home and combos' cards with no "What games see" over it (C11's accept names
     *  that section). [rows] stays the count: a connected controller's lone line already says "Games see it as …", and a
     *  lone "Aim with motion" or "Games" only repeated its header (VD-12). */
    private fun headedAlone(s: CtlSection, c: CtlCaps): Boolean = s == CtlSection.ABOUT && c.thor

    /** Build 18 (C7, VD-07): one summary for every controller's row — "player 2 · Nintendo buttons · battery good", then
     *  what else is true of it ([extras]). Nothing unknown is said (no player yet, no battery reading). dev build 18f2:
     *  [after] = what its number becomes when the game a new order waits for closes ([CtlWords.afterClose]), right after it. */
    fun ctlSummary(player: Int, faceLabel: String?, battery: String?, extras: List<String> = emptyList(), after: String? = null): String =
        (listOfNotNull(player.takeIf { it > 0 }?.let { "player $it" }, after?.takeIf { player > 0 }, faceLabel?.let { "$it buttons" },
            battery?.let { "battery $it" }) + extras).joinToString(" · ")

    /** dev build 18f2: a row's player number — [now] what games see this moment, [after] the one it gets when the game a new
     *  player order waits for closes (0 = no change). */
    data class PlayerNo(val now: Int, val after: Int = 0)

    /**
     * dev build 18f2 (device test t2 BUG-1): ONE source for every player number the Controller list, the controllers' pages
     * and the order card show ([ExtPadsCtl.playerNo]). Every row says what games see NOW — [now], the helper's numbers: a
     * controller's "N", the Thor's "Z - N" — and, while a new order waits for a game ([waits]), [PlayerNo.after] its place
     * in the order asked ([shown], [shownOrder]) when that differs. The Thor's row read the order asked and a controller's
     * what games see: both said "player 2" while RetroArch ran. Now first because the game that keeps the old order is the
     * one running (its players are the numbers now); the order asked is what comes after it, and the card that changes it
     * says so too. dev build 18f2 (review): the Thor's number now also when nothing waits — the order asked came back with
     * "Z - W 0", before the re-make it starts had given anyone a new number (seconds: the copy held back, made again, then
     * settled), and after two failed tries for good: both "player 2" again. In steady state the two are the same. The
     * Thor's number not said yet (0: a helper that hasn't swept, its copy not listed, no controller any more): its place
     * asked ([thorPlayer]), as before. [place] = the row's key in [shown] ("thor" for the Thor and the one playing as it by
     * its switch); [thorRow] = the Thor's own row. Docked: as before (the Thor none, the docked one player 1 — its own "N").
     * [layerOff] ("nolayer"): the Thor can't be held back — it stays first, the others in their order.
     */
    fun playerNo(place: String, now: Int, shown: List<String>, waits: Boolean, docked: Boolean, layerOff: Boolean, thorRow: Boolean): PlayerNo {
        if (docked) return PlayerNo(if (thorRow) 0 else now)
        val n = if (now > 0) now else if (thorRow) thorPlayer(shown, docked = false, layerOff = layerOff) else 0
        if (!waits) return PlayerNo(n)
        val asked = if (layerOff) listOf(HubPage.THOR) + shown.filter { it != HubPage.THOR } else shown
        val want = asked.indexOf(place) + 1
        return PlayerNo(n, if (n > 0 && want > 0 && want != n) want else 0)
    }

    // ── dev build 18f2 (device test t1 BUG-2): the order card's controls keep the focus they were pressed with ──

    /** What the order card's Apply row is: its "Apply now" while a new order waits for a game; once pressed it stays where
     *  it is while it has the focus — inert, saying what happens ([ApplyRow.MAKING] / [ApplyRow.APPLIED]) — and goes when the
     *  focus has moved on. It handed the focus to the last row's Up as it went: one more A moved a player and rewrote the
     *  saved order. */
    enum class ApplyRow { NONE, APPLY, MAKING, APPLIED }
    fun applyRow(waits: Boolean, making: Boolean, focused: Boolean): ApplyRow = when {
        waits && !making -> ApplyRow.APPLY
        !focused -> ApplyRow.NONE
        making -> ApplyRow.MAKING
        else -> ApplyRow.APPLIED
    }

    /** A row's Up ([up]) or Down at place [i] of [size] takes the focus: not the first's Up nor the last's Down (build 18,
     *  C5 / CTL-12: they did nothing) — unless it was just pressed and keeps the focus ([held]): moved to the top, its Up
     *  stays focused (dimmed, it does nothing); the focus went to its Down and one more A moved it back. */
    fun orderButtonFocusable(i: Int, size: Int, up: Boolean, held: Boolean): Boolean = held || (if (up) i > 0 else i < size - 1)

    /** The Thor's player number in the usual order: docked none (the docked one plays as it); the input layer off for the
     *  app in front ("nolayer") 1; else its place in [order] ([ExtPadsCtl.orderList]: "thor" and the controllers' keys) —
     *  alone, 1. */
    fun thorPlayer(order: List<String>, docked: Boolean, layerOff: Boolean): Int = when {
        docked -> 0
        layerOff -> 1
        else -> (order.indexOf(HubPage.THOR) + 1).takeIf { it > 0 } ?: 1
    }

    /** dev build 18f (review): the player order as games number it — without [seatKey], the controller playing as the
     *  Thor's by its switch (its own controller is hidden: games see the Thor's copy in its place, so the Thor's number IS
     *  its number). The order card, the Thor's row and its page all read this ([ExtPadsCtl.orderListShown]). */
    fun shownOrder(order: List<String>, seatKey: String?): List<String> =
        if (seatKey == null) order else order.filter { it == HubPage.THOR || it != seatKey }

    /** dev build 18f (review): the order card's Up / Down — [shown] ([shownOrder]) with rows [i] and [j] swapped, as the
     *  WHOLE order to save: every key of [full] ([ExtPadsCtl.orderList]) kept, so setOrder's "not connected" fallback never
     *  moves the hidden seat. The seat keeps its place (as many rows before it as before), never crossing the Thor: it
     *  comes back on the Thor's side it was on when it stops playing as the Thor's. */
    fun moveShown(full: List<String>, shown: List<String>, i: Int, j: Int): List<String> {
        if (i !in shown.indices || j !in shown.indices) return shown
        val moved = shown.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
        if (!full.containsAll(shown)) return moved
        val out = moved.toMutableList()
        val thorWas = full.indexOf(HubPage.THOR)
        for ((p, k) in full.withIndex()) {
            if (k in shown) continue
            var at = full.subList(0, p).count { it in out }.coerceAtMost(out.size)
            val t = out.indexOf(HubPage.THOR)
            if (t >= 0 && thorWas >= 0) {
                if (p < thorWas && at > t) at = t                       // it was before the Thor: still before
                if (p > thorWas && at <= t) at = t + 1                  // it was after the Thor: still after
            }
            out.add(at, k)
        }
        return out
    }
}
