package app.wayfinder.keyboard

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Where the keyboard's output goes (the IME's InputConnection: [FieldSink], 1.5-beta2 (GitHub #129): never asked on the
 *  main thread). */
interface KeySink {
    fun commit(text: String)
    fun deleteBackward()
    fun enter()
    fun moveCursor(delta: Int)
    /** Should the next letter be capitalised (sentence start, per the field's caps mode)? */
    fun autoCapitalize(): Boolean
    fun textBeforeCursor(n: Int): String
    /** b18p (GitHub #108): [autoCapitalize]'s answer, handed to [answer] — at once here. A sink that asks its field off the
     *  main thread answers later, on the main thread. */
    fun capsLater(answer: (Boolean) -> Unit) = answer(autoCapitalize())
    /** b18p (#108): a space, with the double-space rule ([KeyboardRules.doubleSpace]: "word␣" + space → "word. "). A sink
     *  that asks its field off the main thread does it there. */
    fun space() {
        if (KeyboardRules.doubleSpace(textBeforeCursor(2))) { deleteBackward(); commit(". ") } else commit(" ")
    }
    /** 1.5-beta2 (GitHub #129, review): the field has not had all that was sent or asked yet (a held Backspace waits for it,
     *  [KeyboardController.repeatReady]). A sink that sends at once is never busy. */
    fun busy(): Boolean = false
}

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, GLOBE, TO_SYMBOLS, TO_SYMBOLS_2, TO_LETTERS, CLIPBOARD, TO_EMOJI, EMOJI_NEXT }

data class KeySpec(val kind: KeyKind, val text: String = "", val weight: Float = 1f)

enum class Shift { OFF, ONCE, LOCKED }
enum class Page { LETTERS, SYMBOLS, SYMBOLS_2, EMOJI }

/** b18n (N3-ui 1): the top bar's buttons, in their order on it — the controller lights them as it lights keys
 *  ([KeyboardController.BAR]). */
enum class BarKey { BUTTONS, MOVE, HIDE }

/** How the Enter key looks/acts for the current field. */
enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, DONE }

/**
 * The field's own Enter, as other Android keyboards read it: only IME_FLAG_NO_ENTER_ACTION means "a new line".
 * A multi-line field may still ask for an action — a Compose search box that isn't singleLine says MULTI_LINE +
 * Search, Gboard shows Search, and ours typed a new line (build 18, GitHub #94). TextView multi-line fields set
 * the flag themselves, so they still get the new line.
 */
internal fun enterActionOf(imeOptions: Int): EnterAction =
    if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) EnterAction.NEWLINE
    else when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_GO -> EnterAction.GO
        EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
        EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
        EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
        EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
        else -> EnterAction.NEWLINE
    }

/**
 * The keyboard's state and behaviour, shared by every host (the IME window on the
 * same screen, or the overlay on the other screen). Compose reads the state directly.
 *
 * Controller (big-picture style): left stick = pick a key, D-pad = text cursor,
 * A = press, B = close, X = backspace, Y = space, L2 = shift, R2 = symbols,
 * L1/R1 = jump a word, Start = enter, Select = next language.
 * Holding A on a key opens its accents (then left/right + A picks one).
 * 1.5-beta2 (Q2, GitHub D#127): L2 held = Shift held ([shiftDown] / [shiftUp]); a tap is Shift as before.
 */
class KeyboardController(private val sink: () -> KeySink?) {

    var layouts by mutableStateOf(listOf(ALL_LAYOUTS.first()))
        private set
    var layoutIndex by mutableIntStateOf(0)
        private set
    val layout: KbLayout get() = layouts[layoutIndex.coerceIn(0, layouts.lastIndex)]

    var page by mutableStateOf(Page.LETTERS)
        private set
    var shift by mutableStateOf(Shift.OFF)
        private set
    /** 1.3.2 (GitHub #48): which emoji group the emoji page shows. */
    var emojiGroup by mutableIntStateOf(0)
        private set
    var enterAction by mutableStateOf(EnterAction.NEWLINE)

    /** Controller highlight (row, col) — null until the controller is used. Row [BAR]: the top bar's button col ([barKeys]). */
    var focus by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    companion object {
        /**
         * b18n (N3-ui 1, b18v #9): [focus]'s row for the top bar. Up from the top key row lights its buttons — Buttons, the move
         * button, the hide button ([barKeys]) — ←/→ steps along them, Down comes back to the key it left, A presses the lit
         * one ([onBar]). "Move up" / "Move down" has no button of its own (the legend has L3, the hide button B): the picker
         * stopped at the top key row and it couldn't be reached with the controller.
         */
        const val BAR = -1
    }
    /** The top bar shows its move button (another screen is there) — the host says, as it draws the bar. */
    var canMove: () -> Boolean = { false }
    /** A lit bar button pressed with A: the host does what its tap does (the legend: [toggleHelp] here). */
    var onBar: ((BarKey) -> Unit)? = null
    /** 1.5-beta2 (Q7, GitHub D#125 / #106): Keyboard's "Number row above the letters" is on — the host says
     *  ([KeyboardSettings.numberRow]). */
    var numberRowOn: () -> Boolean = { false }
    /** Q7: the letters page has the number row as its row 0 (its letters start at row 1). The other pages never: the
     *  symbols page's top row already is the digits. */
    fun numberRowShown(): Boolean = page == Page.LETTERS && numberRowOn()
    /** The top bar's buttons, in their order on it. */
    fun barKeys(): List<BarKey> = if (canMove()) listOf(BarKey.BUTTONS, BarKey.MOVE, BarKey.HIDE) else listOf(BarKey.BUTTONS, BarKey.HIDE)
    /** The lit bar button; null = a key (or nothing) is lit. */
    fun focusedBar(): BarKey? = focus?.takeIf { it.first == BAR }?.let { barKeys().getOrNull(it.second) }
    /** The column of the top key row Up left for the bar — Down comes back to it. */
    private var barFrom = 0
    private fun pressBar(k: BarKey) { if (k == BarKey.BUTTONS) toggleHelp() else onBar?.invoke(k) }
    /** Open long-press popup: the key and its options, and the highlighted option. */
    var popup by mutableStateOf<Pair<KeySpec, List<String>>?>(null)
        private set
    var popupIndex by mutableIntStateOf(0)
        private set

    /** The controller is being used → keys show their button badges. */
    var padActive by mutableStateOf(false)
        private set
    /** The controls legend is open (the Buttons key, or L3). */
    var helpOpen by mutableStateOf(false)
        private set

    fun notePad() { padActive = true }
    fun toggleHelp() { helpOpen = !helpOpen }

    /** Bumped on every edit so hosts can refresh their text preview. */
    var edits by mutableIntStateOf(0)
        private set

    fun setLayouts(ids: List<String>, currentId: String?) {
        val list = ids.mapNotNull { layoutById(it) }.ifEmpty { listOf(ALL_LAYOUTS.first()) }
        layouts = list
        layoutIndex = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    }

    /** The key grid of the current page. */
    fun rows(): List<List<KeySpec>> {
        val l = layout
        fun chars(r: List<String>) = r.map { KeySpec(KeyKind.CHAR, it.replace("\$CUR", l.currency)) }
        val bottom = listOf(
            KeySpec(if (page == Page.LETTERS) KeyKind.TO_SYMBOLS else KeyKind.TO_LETTERS, weight = 1.5f),
            KeySpec(KeyKind.GLOBE),
            KeySpec(KeyKind.CLIPBOARD),      // 1.3.2 (GitHub #45)
            KeySpec(KeyKind.TO_EMOJI),       // 1.3.2 (GitHub #48)
            KeySpec(KeyKind.CHAR, if (l.id == "ar") "،" else ","),
            KeySpec(KeyKind.SPACE, weight = 5f),
            KeySpec(KeyKind.CHAR, "."),
            KeySpec(KeyKind.ENTER, weight = 1.5f),
        )
        return when (page) {
            Page.LETTERS -> {
                val r = l.rows
                // Q7: the number row (the symbols page's digits) above the letters while Keyboard's switch is on
                (if (numberRowShown()) listOf(chars(SYMBOLS_1[0])) else emptyList()) +
                listOf(chars(r[0]), chars(r[1])) +
                    listOf(
                        (if (l.cased) listOf(KeySpec(KeyKind.SHIFT, weight = 1.5f)) else emptyList()) +
                            chars(r[2]) + KeySpec(KeyKind.BACKSPACE, weight = 1.5f)
                    ) + listOf(bottom)
            }
            Page.SYMBOLS -> listOf(chars(SYMBOLS_1[0]), chars(SYMBOLS_1[1]),
                listOf(KeySpec(KeyKind.TO_SYMBOLS_2, weight = 1.5f)) + chars(SYMBOLS_1[2]) + KeySpec(KeyKind.BACKSPACE, weight = 1.5f), bottom)
            Page.SYMBOLS_2 -> listOf(chars(SYMBOLS_2[0]), chars(SYMBOLS_2[1]),
                listOf(KeySpec(KeyKind.TO_SYMBOLS, weight = 1.5f)) + chars(SYMBOLS_2[2]) + KeySpec(KeyKind.BACKSPACE, weight = 1.5f), bottom)
            // 1.3.2 (GitHub #48): three rows of emoji, then ABC · the next group · space · delete
            Page.EMOJI -> {
                val g = EMOJI_GROUPS[emojiGroup.coerceIn(0, EMOJI_GROUPS.lastIndex)].second
                g.map { r -> r.map { KeySpec(KeyKind.CHAR, it) } } + listOf(listOf(
                    KeySpec(KeyKind.TO_LETTERS, weight = 1.5f), KeySpec(KeyKind.EMOJI_NEXT, weight = 1.5f),
                    KeySpec(KeyKind.SPACE, weight = 5f), KeySpec(KeyKind.BACKSPACE, weight = 1.5f), KeySpec(KeyKind.ENTER, weight = 1.5f)))
            }
        }
    }

    /** What a CHAR key types right now (shift + the layout's casing rules, e.g. Turkish i → İ). */
    fun display(key: KeySpec): String =
        if (key.kind == KeyKind.CHAR && page == Page.LETTERS && shiftOn()) upper(key.text) else key.text

    /** The letters are capitals now: Shift on, or held (Q2: L2 down, [shiftHeld]). The Shift key is drawn on too. */
    fun shiftOn(): Boolean = shift != Shift.OFF || shiftHeld

    // ── pressing ─────────────────────────────────────────────────────────
    /** 1.3: Space held and slid — the text cursor moves ([delta] characters). */
    fun slideCursor(delta: Int) { if (delta != 0) sink()?.moveCursor(delta) }

    /** 1.5-beta2 (GitHub #129, review): a held key's next repeat may go (Backspace held, KeyboardUi): only once the field has
     *  had the previous one. The typing goes out on its own thread now, so without this a slow field (Chrome loading a page)
     *  queued 18 deletes a second, and the text went on disappearing after the key was let go. */
    fun repeatReady(): Boolean = sink()?.busy() != true

    fun press(key: KeySpec) {
        val s = sink() ?: return
        if (shiftHeld && key.kind != KeyKind.SHIFT) shiftUsed = true          // Q2: L2's release is a hold's then
        when (key.kind) {
            KeyKind.CHAR -> {
                s.commit(display(key))
                if (shift == Shift.ONCE) shift = Shift.OFF
                // Punctuation ends a symbols run, like every phone keyboard.
                if (page != Page.LETTERS && key.text in listOf("'", "\"") ) toPage(Page.LETTERS)     // 1.5-beta2 (Q7, review): the lit key kept (toPage)
                afterEdit()
            }
            KeyKind.SPACE -> {
                // Double space → ". " (and a capital next) — b18p (GitHub #108): the sink's, which may ask its field off the
                // main thread
                s.space()
                if (page != Page.LETTERS && page != Page.EMOJI) toPage(Page.LETTERS)     // 1.5-beta2 (Q7, review): the lit key kept (toPage)
                afterEdit()
            }
            KeyKind.BACKSPACE -> { s.deleteBackward(); afterEdit() }
            KeyKind.ENTER -> { s.enter(); afterEdit() }
            KeyKind.SHIFT -> shift = when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> if (System.currentTimeMillis() - lastShiftTap < 400) Shift.LOCKED else Shift.OFF
                Shift.LOCKED -> Shift.OFF
            }.also { lastShiftTap = System.currentTimeMillis(); shiftTaps++ }
            KeyKind.GLOBE -> nextLayout()
            KeyKind.TO_SYMBOLS -> toPage(Page.SYMBOLS)
            KeyKind.TO_SYMBOLS_2 -> toPage(Page.SYMBOLS_2)
            KeyKind.TO_LETTERS -> toPage(Page.LETTERS)
            // 1.3.2 (GitHub #45): the last copied texts in the strip above — pick one to paste it
            KeyKind.TO_EMOJI -> toPage(Page.EMOJI)
            KeyKind.EMOJI_NEXT -> { emojiGroup = (emojiGroup + 1) % EMOJI_GROUPS.size; clampFocus() }
            KeyKind.CLIPBOARD -> {
                ClipHistory.read()
                if (ClipHistory.items.isNotEmpty()) { popup = key to ClipHistory.items.toList(); popupIndex = 0 }
            }
        }
    }
    private var lastShiftTap = 0L

    /** Q7: another page, the lit key kept by its row counted from the bottom ([clampFocus]: the number row's letters page
     *  has a row more than the others). */
    private fun toPage(p: Page) { val n = rows().size; page = p; clampFocus(n) }

    fun nextLayout() {
        val n = rows().size
        if (layouts.size > 1) layoutIndex = (layoutIndex + 1) % layouts.size
        page = Page.LETTERS; clampFocus(n)
        onLayoutChanged?.invoke(layout.id)
    }

    // ── 1.5-beta2 (Q2, GitHub D#127): L2 held = Shift held, while the controller drives the keys ──
    /** L2 is down: the letters are capitals until it comes up ([shiftUp]), the Shift key drawn on ([shiftOn]). */
    var shiftHeld by mutableStateOf(false)
        private set
    private var shiftDownAt = 0L
    /** A key typed while L2 is down: its release is a hold's, however short. */
    private var shiftUsed = false

    /** L2 pressed at [at] (the event's uptime ms). Nothing changes yet: a tap is told at the release. */
    fun shiftDown(at: Long) { shiftHeld = true; shiftDownAt = at; shiftUsed = false }

    /** L2 let go at [at]. A tap ([KeyboardRules.shiftTap]) is the Shift key's (once, twice quickly = caps lock, again =
     *  off); a hold leaves Shift as it is: the letters typed meanwhile were capitals. */
    fun shiftUp(at: Long) {
        if (!shiftHeld) return
        shiftHeld = false
        if (KeyboardRules.shiftTap(at - shiftDownAt, shiftUsed)) press(KeySpec(KeyKind.SHIFT))
    }
    var onLayoutChanged: ((String) -> Unit)? = null

    /** Field (re)started or text changed: sentence-start capitals, unless caps lock. */
    fun afterEdit() {
        edits++
        if (shift == Shift.LOCKED || !layout.cased) return
        val s = sink() ?: run { shift = Shift.OFF; return }
        // b18p (GitHub #108): the answer may come later (a field of Wayfinder's own process is asked off the main thread) —
        // then it counts only if nothing changed meanwhile: a newer edit asks again, a Shift tap is the user's own
        val asked = edits; val taps = shiftTaps
        s.capsLater { caps ->
            if (edits == asked && shiftTaps == taps && shift != Shift.LOCKED && layout.cased) shift = if (caps) Shift.ONCE else Shift.OFF
        }
    }
    /** b18p (#108): Shift taps so far — a caps answer asked before one is dropped. */
    private var shiftTaps = 0

    fun reset() {
        toPage(Page.LETTERS); popup = null                                  // 1.5-beta2 (Q7, review): the lit key kept (toPage)
        shiftHeld = false                                                   // Q2: a new field, L2's release not ours
        // 1.5-beta2 (GitHub #129, review): off, not the previous field's: the new field's caps answer comes a moment later
        // (asked off the main thread), and a letter typed before it took the old field's Shift
        shift = Shift.OFF
        afterEdit()
    }

    // ── long-press accents ───────────────────────────────────────────────
    /** The long-press options of [key] at ([row], [col]) (build 18, KB4: the top row's digit comes by position — only on
     *  the letters page; -1 = no position, no digit). */
    fun alternates(key: KeySpec, row: Int = -1, col: Int = -1): List<String> {
        if (key.kind != KeyKind.CHAR) return emptyList()
        val letters = page == Page.LETTERS
        val alts = layout.alternatesFor(key.text, if (letters) digitRow(row) else -1, col)
        return if (letters && shiftOn()) alts.map { upper(it) } else alts
    }

    /** Q7: [row] for the top row's digit ([topRowDigit]) — none (-1) while the number row shows: the digits are on it. */
    private fun digitRow(row: Int): Int = if (numberRowShown()) -1 else row

    /** Upper case for this layout — but ß stays ß (no capital in normal use; "SS" is two letters). */
    private fun upper(s: String) = if (s == "ß") s else s.uppercase(layout.locale)

    /** The small hint on [key] at ([row], [col]) of the letters page (KB4: the top row reads 1…0 by position). */
    fun hint(key: KeySpec, row: Int, col: Int): String? {
        if (key.kind != KeyKind.CHAR || page != Page.LETTERS) return null
        val h = layout.hintFor(key.text, digitRow(row), col) ?: return null
        return if (shiftOn()) upper(h) else h
    }

    fun openPopup(key: KeySpec, row: Int = -1, col: Int = -1): Boolean {
        val alts = alternates(key, row, col)
        if (alts.isEmpty()) return false
        popup = key to alts; popupIndex = 0
        return true
    }

    fun choosePopup(option: String?) {
        popup = null
        if (option == null) return
        if (shiftHeld) shiftUsed = true                                     // Q2
        sink()?.commit(option)
        if (shift == Shift.ONCE) shift = Shift.OFF
        afterEdit()
    }

    fun movePopup(delta: Int) { popup?.let { popupIndex = (popupIndex + delta).coerceIn(0, it.second.lastIndex) } }

    /** 1.5-beta2 (Q6, GitHub D#127): the held key let go — the lit option is typed (the first one if the finger didn't
     *  slide: the top row's digit, else the key's hint). */
    fun chooseLit() { popup?.let { (_, opts) -> choosePopup(opts.getOrNull(popupIndex)) } }

    // ── controller ───────────────────────────────────────────────────────
    /** D-pad / stick step. dx, dy ∈ {-1, 0, 1}. */
    fun moveFocus(dx: Int, dy: Int, at: Long = android.os.SystemClock.uptimeMillis()) {
        padActive = true
        if (popup != null) { if (dx != 0) movePopup(dx) ; if (dy > 0) choosePopup(null); return }
        val grid = rows()
        val before = focus
        val (r0, c0) = focus ?: run { focus = defaultFocus(grid); return }
        if (r0 == BAR) {
            // b18n (N3-ui 1): on the top bar — ←/→ along its buttons (wrapping, as a key row), Down back to the key it left
            val bar = barKeys()
            if (dy > 0) focus = 0 to barFrom.coerceIn(0, grid[0].lastIndex)
            else if (dx != 0) focus = BAR to ((c0 + dx) % bar.size + bar.size) % bar.size
        } else if (dy < 0 && r0 == 0) {
            barFrom = c0; focus = BAR to 0                                  // b18n (N3-ui 1): Up from the top key row: the bar
        } else if (dy != 0) {
            val r1 = (r0 + dy).coerceIn(0, grid.lastIndex)
            if (r1 != r0) focus = r1 to nearestColumn(grid[r0], c0, grid[r1])
        } else if (dx != 0) {
            val row = grid[r0]
            focus = r0 to ((c0 + dx) % row.size + row.size) % row.size   // wrap around
        }
        if (focus != before) { moves.addLast(Triple(at, page, before)); while (moves.size > 8) moves.removeFirst() }
    }

    fun focusedKey(): KeySpec? = focus?.let { (r, c) -> rows().getOrNull(r)?.getOrNull(c) }

    /** 1.4 (GitHub #61): typing fast, A then a quick D-pad move typed the NEXT key — the key was read when A came
     *  back up. Now the key is the one that had the focus when A went down: each move keeps the focus it left and
     *  when (the input's own time), so even a move handled before A's down (A comes a longer way) is undone. */
    private val moves = ArrayDeque<Triple<Long, Page, Pair<Int, Int>?>>()
    private var armed: Pair<Page, Pair<Int, Int>>? = null
    fun controllerArm(downAt: Long) {
        armed = null
        // Q2: A went down with L2 held — its key is a capital even if L2 comes up first (a roll: L2, A, L2 up, A up)
        armedCaps = shiftHeld
        if (shiftHeld) shiftUsed = true
        if (popup != null) return
        // the focus at downAt = what the first move made after it left behind (else the focus now)
        val m = moves.firstOrNull { it.first > downAt }
        val f = if (m != null) m.third?.let { m.second to it } else focus?.let { page to it }
        armed = f
    }

    fun controllerPress() {
        popup?.let { (_, opts) -> choosePopup(opts[popupIndex]); return }
        val a = armed; armed = null
        // the focus when A went down (#61); the bar's buttons are on every page
        val at = a?.takeIf { it.first == page || it.second.first == BAR }?.second
        // b18n (N3-ui 1): a lit bar button does what its tap does
        (at ?: focus)?.takeIf { it.first == BAR }?.let { f -> barKeys().getOrNull(f.second)?.let { pressBar(it) }; return }
        val k = at?.let { rows().getOrNull(it.first)?.getOrNull(it.second) }
            ?: focusedKey() ?: run { focus = defaultFocus(rows()); return }
        val caps = armedCaps; armedCaps = false
        if (caps && !shiftHeld) { shiftHeld = true; press(k); shiftHeld = false } else press(k)
    }
    /** Q2: L2 was held when A went down ([controllerArm]). */
    private var armedCaps = false

    fun controllerLongPress() { val (r, c) = focus ?: return; focusedKey()?.let { openPopup(it, r, c) } }

    /** Build 18 (KB1): the keyboard opened from a controller — a key is lit (the middle one) and the badges show, so the
     *  first press types instead of only lighting a key. A key lit already stays where it is. */
    fun lightKey() {
        padActive = true
        if (focus == null && popup == null) focus = defaultFocus(rows())
    }

    // Q2: the keys closed with L2 down — its release goes nowhere, so Shift held ends here
    fun dismissFocus() { focus = null; popup = null; padActive = false; helpOpen = false; shiftHeld = false }

    /** The middle key of the middle letter row (Q7: the number row above doesn't move it). */
    private fun defaultFocus(grid: List<List<KeySpec>>): Pair<Int, Int> {
        val top = if (numberRowShown()) 1 else 0
        return ((grid.size - top) / 2 - 1).coerceAtLeast(0).let { it + top }.coerceAtMost(grid.lastIndex).let { r -> r to grid[r].size / 2 }
    }

    /** The lit key kept in the grid shown now. [fromRows]: the rows the page had before a change (Q7) — a row keeps its
     *  place counted from the bottom, so the bottom row stays the bottom row (and Shift's row Shift's) between the letters
     *  with the number row and a page without one. The same row count: as before. */
    private fun clampFocus(fromRows: Int = rows().size) {
        val f = focus ?: return
        if (f.first == BAR) { focus = BAR to f.second.coerceIn(0, barKeys().lastIndex); return }   // b18n: the bar stays lit
        val grid = rows()
        val r = (f.first + grid.size - fromRows).coerceIn(0, grid.lastIndex)
        focus = r to f.second.coerceIn(0, grid[r].lastIndex)
    }

    /** Column in [to] whose centre is closest to key [c] of [from] (rows differ in width). b19 (H1a): the one rule
     *  ([WeightedRows.nearest]), shared with Keyboard & mouse's controller. */
    private fun nearestColumn(from: List<KeySpec>, c: Int, to: List<KeySpec>): Int =
        WeightedRows.nearest(from.map { it.weight }, c, to.map { it.weight })
}

/** b19 (H1a): moving up or down between two rows of keys of different widths (pure): Wayfinder Keyboard's and Keyboard &
 *  mouse's controller move the same way (KeyboardMoveTest, DeckNavTest). */
object WeightedRows {
    /** The cell of the row weighted [to] whose centre is closest to cell [c] of the row weighted [from] (each row spread over
     *  the same width, a cell as wide as its weight). */
    fun nearest(from: List<Float>, c: Int, to: List<Float>): Int {
        fun centres(row: List<Float>): List<Float> {
            val total = row.sumOf { it.toDouble() }.toFloat()
            var x = 0f
            return row.map { w -> val mid = (x + w / 2f) / total; x += w; mid }
        }
        val target = centres(from)[c.coerceIn(0, from.lastIndex)]
        return centres(to).withIndex().minByOrNull { abs(it.value - target) }!!.index
    }
}

/** Build 18: the keyboard's rules that need no Android (KeyboardHintTest). */
internal object KeyboardRules {
    /** KB1: a controller key pressed this recently opened the field (A0's InputMode feeds the time; the keyboard's own
     *  presses too, [countsAsPadPress]). */
    const val PAD_OPEN_MS = 1500L

    /** KB1: light a key on open — a controller key was pressed in the last [PAD_OPEN_MS] (uptime ms; 0 = never) and no
     *  touch came after it (the Hub knows its touches; another app's tap is only told by the time). */
    fun openedByPad(now: Long, lastPadKeyAt: Long, touchSince: Boolean): Boolean =
        !touchSince && lastPadKeyAt > 0L && now - lastPadKeyAt in 0 until PAD_OPEN_MS

    /** KB1 (build 18 review): a press the keyboard takes is a controller press for InputMode too — every one but Back (the
     *  navigation bar's Back is no controller). The keyboard eats its presses before the engines feed InputMode, and Start
     *  on a Next field opens the next field from one of them: without it, that field came up unlit. */
    fun countsAsPadPress(keyCode: Int): Boolean = keyCode != android.view.KeyEvent.KEYCODE_BACK

    /** Double space → ". ": the two characters before the cursor are a letter or digit, then a space. */
    fun doubleSpace(before: CharSequence): Boolean = before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()

    // b18p (GitHub #108) sent only a field of Wayfinder's own process off the main thread (offMain). 1.5-beta2 (GitHub #129):
    // every app's field is, so the rule is gone: [FieldSink] sends and asks on the typing thread for all of them.

    /** b19 (1.5-beta1 device pass F1): the keys go to the other screen when the keyboard is asked for. [moved], the move
     *  button's choice for this field, wins on either screen (true: there; false: the field's screen). No move yet:
     *  "Where it opens" ([placementOther] = Bottom screen) for a field on the top screen. Never without [otherScreen]. */
    fun keysOnOther(moved: Boolean?, placementOther: Boolean, fieldOnTop: Boolean, otherScreen: Boolean): Boolean =
        otherScreen && (moved ?: (placementOther && fieldOnTop))

    /** 1.5-beta2 (GitHub #144): the screen the keys can go to besides the field's ([other]: the first other display), none
     *  while the Thor is [docked] on a TV or monitor. Docked, the TV shows the top screen and the bottom one is dark (AYN's
     *  black cover, or Wayfinder's dock blank) or is the TV itself: "Bottom screen" put the keys where nobody could see or
     *  touch them. So docked, the keys open on the field's screen (the one the TV shows) and "Move" isn't offered. */
    fun keysOtherScreen(docked: Boolean, other: Int?): Int? = if (docked) null else other

    /** 1.5-beta2 (Q2): a hold of a controller button on the keys (ms) — A's accents ([ThorKeyboardService]), L2's Shift
     *  (here and on Keyboard & mouse, [app.wayfinder.deck.DeckState.shiftLet]). */
    const val HOLD_MS = 450L

    /** Q2 (GitHub D#127): L2 let go [heldMs] after its press, [used] = a key typed meanwhile. A tap (today's Shift: once,
     *  twice quickly = caps lock) is a press shorter than [HOLD_MS] with nothing typed; anything else was Shift held. */
    fun shiftTap(heldMs: Long, used: Boolean): Boolean = !used && heldMs < HOLD_MS

    /** 1.5-beta2 (Q6, GitHub D#127): a key held, its options open, the finger slid [dx] px from where it went down: the option
     *  lit of [count], one more each [step] px the way the options run ([rtl]: right to left), the first one where it was
     *  held (and back past it). */
    fun slidePick(dx: Float, step: Float, count: Int, rtl: Boolean = false): Int =
        if (count <= 0 || step <= 0f) 0 else ((if (rtl) -dx else dx) / step).toInt().coerceIn(0, count - 1)

    /** 1.5-beta2 (big test t7): a start of the input is a new field (the letters page, Shift off, the lit row kept:
     *  [KeyboardController.reset]) unless the app restarted the same box ([sameBox]: a restart, [FieldSink.sameEditor])
     *  while its keys stayed up. [keysClosed]: the keys closed since they last showed. Compose gives every box of a window
     *  one view and the same editor, so a box of Wayfinder's own opened after another came as a restart of the same box
     *  and kept the last box's page (symbols stayed symbols). Chrome's restart of the box being typed in (GitHub #129)
     *  keeps its keys up, and its page with them. */
    fun newField(sameBox: Boolean, keysClosed: Boolean): Boolean = !sameBox || keysClosed

    /** 1.5-beta2 (big test t8): the field's input type asks for text (any class but none). */
    fun typesText(inputType: Int?): Boolean =
        inputType != null && (inputType and android.text.InputType.TYPE_MASK_CLASS) != android.text.InputType.TYPE_NULL

    /** 1.5-beta2 (big test t8): the app's box went while the keys stay up: the input starts again on nothing to type
     *  into ([nowText] false) on the same view of the same app ([sameView]; review: another view that takes typing itself,
     *  a terminal's or a remote desktop's, is not a box that went) right after a box ([wasText]), and the keys are on the other
     *  screen with no keyboard window on the box's screen ([noWindowHere]). An app hides the keyboard through its window's
     *  insets (Compose does), which see only a keyboard WINDOW on that screen: with Keyboard's "Keep a thin bar" off there
     *  is none, so that hide reached nobody and the keys stayed up with the controller. With a window there the app's own
     *  hide works, as before. A game whose view starts with no box (input type none) is not a box that went. */
    fun fieldGone(wasText: Boolean, nowText: Boolean, sameView: Boolean, noWindowHere: Boolean): Boolean =
        wasText && !nowText && sameView && noWindowHere

    /** 1.5-beta2 (gv2 F2, h_diag A): a show of the keys right after a box went ([fieldGone]: [boxWent]) on what has nothing to
     *  type into ([typesNow] false) is Android's, not the app's: the app's hide reached nobody, so Android still counts the
     *  keys up and shows them again on the input that replaced the box (ATTACH_NEW_INPUT). Counted as a request, it stopped
     *  both closes of the box gone (the keys stayed up with the controller). A next box that types text still counts. */
    fun carriedOver(boxWent: Boolean, typesNow: Boolean): Boolean = boxWent && !typesNow

    /** 1.5-beta2 (gv2 F2): Start's done / go / search / send ([action]) closes the keys when they are on the other screen
     *  ([keysOnOther]), with "Keep a thin bar" on (1.4's, Eden) and off (no keyboard window on the box's screen: the app's
     *  own hide reached nobody). New line and next keep them. On the box's screen the app hides them itself, as before. */
    fun closesOnAction(keysOnOther: Boolean, action: EnterAction): Boolean =
        keysOnOther && action != EnterAction.NEWLINE && action != EnterAction.NEXT
}

/** Build 18: the keyboard's own words (the move button, its keys, the typing line). */
internal object KeyboardWords {
    /** KB2: the move button says what it does — the screen it sends the keys to is drawn beside it (ui.ScreenIcon). */
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    fun move(toTop: Boolean): String = if (toTop) MOVE_UP else MOVE_DOWN

    /** KB2: the key that opens the controls legend (was "?"), and the empty preview's placeholder. */
    const val BUTTONS = "Buttons"
    const val TYPE_HERE = "Type here"

    /** KB1 (decision H2): what picks the keys — the left stick, or the D-pad when Keyboard's switch says so (GitHub #11). */
    fun pickWith(dpadKeys: Boolean): String = if (dpadKeys) "D-pad" else "Left stick"

    /** KB3: the field's own Enter in words (b18r0 WI-1's action). */
    fun enterWord(a: EnterAction): String = when (a) {
        EnterAction.SEARCH -> "search"
        EnterAction.GO -> "go"
        EnterAction.SEND -> "send"
        EnterAction.NEXT -> "next"
        EnterAction.DONE -> "done"
        EnterAction.NEWLINE -> "new line"
    }

    /**
     * KB1 + KB3: the line on the field's screen while the keys are on the other one ("strip" mode) — where the keys are,
     * what picks them, then Start and the Back job as button tokens (A7: drawn as the hand's badges; GlyphText), each
     * with what it does. No "↵": the Enter key isn't on this screen. B "close", as the first-time tip and the Buttons
     * legend say it.
     */
    fun strip(screen: String, dpadKeys: Boolean, enter: EnterAction, look: app.wayfinder.PadLook): String =
        "⌨  Typing on the $screen · ${pickWith(dpadKeys)}: pick a key · " +
            "${app.wayfinder.Say.key(app.wayfinder.ThorButton.START)} ${enterWord(enter)} · " +
            "${app.wayfinder.Say.keyMenu(app.wayfinder.ThorButton.B, look)} close"
}
