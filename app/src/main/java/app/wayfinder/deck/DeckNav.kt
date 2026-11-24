package app.wayfinder.deck

import app.wayfinder.keyboard.WeightedRows

/**
 * b19 (H1a): Keyboard & mouse driven by the controller — the cells it draws, as rows, and the lit cell's moves (pure:
 * DeckNavTest). The rows are what [DeckPanel] draws, top to bottom: the header (the pads' tabs, the Guide, the gyro, close),
 * the pad's keys (blank cells left out: My pad's), the trackpad's row, My pad's "Make them" button when it has none, the
 * clipboard row while it is open, the footer (b2: Simpler keys, the clipboard, the switch). Left / Right move along a row
 * and round it; Up / Down go to the cell nearest by position (the rows' weights: [WeightedRows], as on Wayfinder Keyboard)
 * and stop at the top and the bottom row.
 */
internal object DeckNav {
    /** A cell the controller can light: its [id] (what the deck runs: [InputDeckOverlay.runCell]) and its width. */
    data class Cell(val id: String, val weight: Float = 1f)
    /** Where the light is: cell [col] of row [row]. */
    data class Lit(val row: Int, val col: Int)

    const val CLOSE = "close"
    const val GYRO = "gyro"
    const val MAKE = "make"
    const val SIMPLE = "simple"
    const val CLIPBOARD = "clipboard"
    /** b2 (switch): the footer's "Use the controller here" switch (it replaced "Give the controller to the game": the deck
     *  opens on the screen the controller isn't on, so the game has it already; A on it gives it back, as B does). */
    const val USE = "use"
    const val TP_MODE = "tp:mode"
    const val TP_LEFT = "tp:left"
    const val TP_MIDDLE = "tp:middle"
    const val TP_RIGHT = "tp:right"
    const val TP_POINTER = "tp:pointer"
    const val TP_SLOWER = "tp:slower"
    const val TP_FASTER = "tp:faster"
    private const val TAB = "tab:"
    private const val KEY = "key:"
    private const val CLIP = "clip:"

    fun tab(padId: String) = "$TAB$padId"
    fun key(row: Int, col: Int) = "$KEY$row:$col"
    fun clip(i: Int) = "$CLIP$i"
    /** The pad a tab's cell opens; null for another cell. */
    fun tabOf(id: String): String? = id.takeIf { it.startsWith(TAB) }?.removePrefix(TAB)
    /** A key cell's place in its pad's rows (blank cells counted, as the pad has them); null for another cell. */
    fun keyOf(id: String): Pair<Int, Int>? = id.takeIf { it.startsWith(KEY) }?.removePrefix(KEY)?.split(':')
        ?.takeIf { it.size == 2 }?.let { (r, c) -> r.toIntOrNull()?.let { rr -> c.toIntOrNull()?.let { rr to it } } }
    /** A clipboard text's place in the row; null for another cell. */
    fun clipOf(id: String): Int? = id.takeIf { it.startsWith(CLIP) }?.removePrefix(CLIP)?.toIntOrNull()

    /** The trackpad's mode: Mouse, Touch or Direct ([DeckSettings.touchMode], [DeckSettings.directTouch]). */
    enum class Trackpad { MOUSE, TOUCH, DIRECT }

    /**
     * What the deck draws now — the inputs [DeckPanel] reads. [pads]: the pads' tabs in order (none for the guide alone);
     * [gyro]: the gyro's tab shows; one body: [keys] (the pad's rows) or [trackpad] or [makeOwn] (My pad with no buttons), none
     * for the Guide; [clips]: the clipboard row's texts while it is open (0: closed, or nothing to pick); [pcKeys]: the PC keys
     * (the footer's Simpler keys / All keys); [footer]: not the guide alone.
     */
    data class Shown(
        val pads: List<String>,
        val gyro: Boolean = false,
        val keys: List<List<DeckKey>>? = null,
        val trackpad: Trackpad? = null,
        val makeOwn: Boolean = false,
        val clips: Int = 0,
        val pcKeys: Boolean = false,
        val footer: Boolean = true,
    )

    /** The rows the controller walks, top to bottom ([Shown]); a row with no cell is left out. */
    fun rows(s: Shown): List<List<Cell>> {
        val out = ArrayList<List<Cell>>()
        out += s.pads.map { Cell(tab(it)) } + Cell(tab(PAD_GUIDE)) + listOfNotNull(Cell(GYRO).takeIf { s.gyro }) + Cell(CLOSE)
        s.keys?.forEachIndexed { r, row ->
            val cells = row.withIndex().filter { it.value.label.isNotEmpty() }.map { (c, k) -> Cell(key(r, c), k.weight) }
            if (cells.isNotEmpty()) out += cells
        }
        s.trackpad?.let { t ->
            out += listOfNotNull(
                Cell(TP_MODE),
                Cell(TP_LEFT).takeIf { t == Trackpad.MOUSE }, Cell(TP_MIDDLE, 0.6f).takeIf { t == Trackpad.MOUSE },
                Cell(TP_RIGHT).takeIf { t == Trackpad.MOUSE },
                Cell(TP_POINTER).takeIf { t == Trackpad.DIRECT },
                Cell(TP_SLOWER).takeIf { t != Trackpad.DIRECT }, Cell(TP_FASTER).takeIf { t != Trackpad.DIRECT },
            )
        }
        if (s.makeOwn) out += listOf(Cell(MAKE))
        if (s.clips > 0) out += (0 until s.clips).map { Cell(clip(it)) }
        if (s.footer) out += listOfNotNull(Cell(SIMPLE).takeIf { s.pcKeys }, Cell(CLIPBOARD), Cell(USE))
        return out
    }

    /** Where cell [id] is in [rows]; null = not there. */
    fun find(rows: List<List<Cell>>, id: String?): Lit? {
        if (id == null) return null
        rows.forEachIndexed { r, row -> row.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return Lit(r, it) } }
        return null
    }

    private fun weights(row: List<Cell>) = row.map { it.weight }
    private fun header(row: List<Cell>) = row.any { it.id == CLOSE }
    private fun footer(row: List<Cell>) = row.any { it.id == USE }
    private fun body(rows: List<List<Cell>>) = rows.indices.filter { !header(rows[it]) && !footer(rows[it]) }

    /** Where the light starts: the middle of the pad's middle row (Wayfinder Keyboard's), else [prefer] (the open pad's tab),
     *  else the first cell. */
    fun first(rows: List<List<Cell>>, prefer: String? = null): Lit {
        val b = body(rows)
        if (b.isNotEmpty()) { val r = b[(b.size - 1) / 2]; return Lit(r, rows[r].size / 2) }
        return find(rows, prefer) ?: Lit(0, 0)
    }

    /** Row [r] of [rows] is the pad's (or the clipboard row): not the header, not the footer. */
    fun inPad(rows: List<List<Cell>>, r: Int): Boolean = rows.getOrNull(r)?.let { !header(it) && !footer(it) } == true

    /** One step from [from]: [dx] along the row and round it, or [dy] to the nearest cell of the row above / below (none past
     *  the top or the bottom: it stays). [left]: the pad's cell the light left for the header or the footer — back into the
     *  pad from there, the light lands on it (Wayfinder Keyboard's bar: "Down back to the key it left"). */
    fun move(rows: List<List<Cell>>, from: Lit, dx: Int, dy: Int, left: String? = null): Lit {
        if (rows.isEmpty()) return from
        val r = from.row.coerceIn(0, rows.lastIndex)
        val c = from.col.coerceIn(0, rows[r].lastIndex)
        if (dy != 0) {
            val to = (r + dy).coerceIn(0, rows.lastIndex)
            if (to == r) return Lit(r, c)
            if (!inPad(rows, r)) find(rows, left)?.takeIf { it.row == to }?.let { return it }
            return Lit(to, WeightedRows.nearest(weights(rows[r]), c, weights(rows[to])))
        }
        if (dx != 0) return Lit(r, (c + dx).mod(rows[r].size))
        return Lit(r, c)
    }

    /** After the rows changed ([old] → [new]: a pad changed, the clipboard row opened, a mode): the same cell if it is still
     *  there, else the nearest in the same part of the deck — the header, the pad (its row, as far down as the new one
     *  goes) or the footer; a pad with no keys now: the header. */
    fun keep(old: List<List<Cell>>, lit: Lit, new: List<List<Cell>>): Lit {
        if (new.isEmpty()) return Lit(0, 0)
        val was = old.getOrNull(lit.row) ?: return first(new)
        find(new, was.getOrNull(lit.col)?.id)?.let { return it }
        val col = lit.col.coerceIn(0, was.lastIndex)
        val nb = body(new)
        val r = when {
            header(was) -> new.indexOfFirst { header(it) }
            footer(was) -> new.indexOfFirst { footer(it) }
            nb.isEmpty() -> new.indexOfFirst { header(it) }
            else -> nb[body(old).indexOf(lit.row).coerceIn(0, nb.lastIndex)]
        }.takeIf { it >= 0 } ?: return first(new)
        return Lit(r, WeightedRows.nearest(weights(was), col, weights(new[r])))
    }

    /** b19 fix (H1a, F1 review): where the header's tab strip scrolls to when the open pad or the strip's width changes. From
     *  [now] (its scroll), showing [width] pixels, it shows [open] (the open pad's tab: its pixels along the strip) and then
     *  [lit] (the tab the light is on, if it is on one), each by the least move from where the one before left the strip:
     *  both show when they fit, and the light's when they don't. A tab wider than the strip shows from its start. Null = not
     *  drawn (nothing to show for it). Within 0..[max] (the strip's last scroll). */
    fun stripScroll(now: Int, width: Int, max: Int, open: IntRange?, lit: IntRange?): Int {
        if (width <= 0) return now
        var s = now
        for (t in listOfNotNull(open, lit)) s = when {
            t.first < s || t.last + 1 - t.first > width -> t.first
            t.last + 1 > s + width -> t.last + 1 - width
            else -> s
        }
        return s.coerceIn(0, max.coerceAtLeast(0))
    }

    /** b2 (O1, review): whether the header strip's scroll ([stripScroll]) runs now, [why] the pad and the strip's width it is
     *  for and [last] the ones it last ran for. It runs each time, except when the light just came onto the strip
     *  ([onStrip]) with the same pad and width: the lit tab's own bring shows it (two scrolls at once cancel each other).
     *  So the light leaving the strip runs it again, and the open tab R1 left off the strip comes back. */
    fun stripRuns(onStrip: Boolean, why: String, last: String): Boolean = !(onStrip && why == last)
}
