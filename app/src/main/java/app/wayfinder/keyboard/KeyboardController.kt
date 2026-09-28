package app.wayfinder.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Where the keyboard's output goes (the IME's InputConnection). */
interface KeySink {
    fun commit(text: String)
    fun deleteBackward()
    fun enter()
    fun moveCursor(delta: Int)
    /** Should the next letter be capitalised (sentence start, per the field's caps mode)? */
    fun autoCapitalize(): Boolean
    fun textBeforeCursor(n: Int): String
}

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, GLOBE, TO_SYMBOLS, TO_SYMBOLS_2, TO_LETTERS }

data class KeySpec(val kind: KeyKind, val text: String = "", val weight: Float = 1f)

enum class Shift { OFF, ONCE, LOCKED }
enum class Page { LETTERS, SYMBOLS, SYMBOLS_2 }

/** How the Enter key looks/acts for the current field. */
enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, DONE }

/**
 * The keyboard's state and behaviour, shared by every host (the IME window on the
 * same screen, or the overlay on the other screen). Compose reads the state directly.
 *
 * Controller (big-picture style): left stick = pick a key, D-pad = text cursor,
 * A = press, B = close, X = backspace, Y = space, L2 = shift, R2 = symbols,
 * L1/R1 = jump a word, Start = enter, Select = next language.
 * Holding A on a key opens its accents (then left/right + A picks one).
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
    var enterAction by mutableStateOf(EnterAction.NEWLINE)

    /** Controller highlight (row, col) — null until the controller is used. */
    var focus by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** Open long-press popup: the key and its options, and the highlighted option. */
    var popup by mutableStateOf<Pair<KeySpec, List<String>>?>(null)
        private set
    var popupIndex by mutableIntStateOf(0)
        private set

    /** The controller is being used → keys show their button badges. */
    var padActive by mutableStateOf(false)
        private set
    /** The controls legend is open (the "?" key, or L3). */
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
            KeySpec(KeyKind.CHAR, if (l.id == "ar") "،" else ","),
            KeySpec(KeyKind.SPACE, weight = 5f),
            KeySpec(KeyKind.CHAR, "."),
            KeySpec(KeyKind.ENTER, weight = 1.5f),
        )
        return when (page) {
            Page.LETTERS -> {
                val r = l.rows
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
        }
    }

    /** What a CHAR key types right now (shift + the layout's casing rules, e.g. Turkish i → İ). */
    fun display(key: KeySpec): String =
        if (key.kind == KeyKind.CHAR && page == Page.LETTERS && shift != Shift.OFF) upper(key.text) else key.text

    // ── pressing ─────────────────────────────────────────────────────────
    /** 1.3: Space held and slid — the text cursor moves ([delta] characters). */
    fun slideCursor(delta: Int) { if (delta != 0) sink()?.moveCursor(delta) }

    fun press(key: KeySpec) {
        val s = sink() ?: return
        when (key.kind) {
            KeyKind.CHAR -> {
                s.commit(display(key))
                if (shift == Shift.ONCE) shift = Shift.OFF
                // Punctuation ends a symbols run, like every phone keyboard.
                if (page != Page.LETTERS && key.text in listOf("'", "\"") ) page = Page.LETTERS
                afterEdit()
            }
            KeyKind.SPACE -> {
                // Double space → ". " (and a capital next).
                val before = s.textBeforeCursor(2)
                if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    s.deleteBackward(); s.commit(". ")
                } else s.commit(" ")
                if (page != Page.LETTERS) page = Page.LETTERS
                afterEdit()
            }
            KeyKind.BACKSPACE -> { s.deleteBackward(); afterEdit() }
            KeyKind.ENTER -> { s.enter(); afterEdit() }
            KeyKind.SHIFT -> shift = when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> if (System.currentTimeMillis() - lastShiftTap < 400) Shift.LOCKED else Shift.OFF
                Shift.LOCKED -> Shift.OFF
            }.also { lastShiftTap = System.currentTimeMillis() }
            KeyKind.GLOBE -> nextLayout()
            KeyKind.TO_SYMBOLS -> { page = Page.SYMBOLS; clampFocus() }
            KeyKind.TO_SYMBOLS_2 -> { page = Page.SYMBOLS_2; clampFocus() }
            KeyKind.TO_LETTERS -> { page = Page.LETTERS; clampFocus() }
        }
    }
    private var lastShiftTap = 0L

    fun nextLayout() {
        if (layouts.size > 1) layoutIndex = (layoutIndex + 1) % layouts.size
        page = Page.LETTERS; clampFocus()
        onLayoutChanged?.invoke(layout.id)
    }
    var onLayoutChanged: ((String) -> Unit)? = null

    /** Field (re)started or text changed: sentence-start capitals, unless caps lock. */
    fun afterEdit() {
        edits++
        if (shift != Shift.LOCKED && layout.cased) shift = if (sink()?.autoCapitalize() == true) Shift.ONCE else Shift.OFF
    }

    fun reset() {
        page = Page.LETTERS; popup = null
        if (shift == Shift.LOCKED) shift = Shift.OFF
        afterEdit()
    }

    // ── long-press accents ───────────────────────────────────────────────
    fun alternates(key: KeySpec): List<String> {
        if (key.kind != KeyKind.CHAR) return emptyList()
        val alts = layout.alternatesFor(key.text)
        return if (page == Page.LETTERS && shift != Shift.OFF) alts.map { upper(it) } else alts
    }

    /** Upper case for this layout — but ß stays ß (no capital in normal use; "SS" is two letters). */
    private fun upper(s: String) = if (s == "ß") s else s.uppercase(layout.locale)

    fun hint(key: KeySpec): String? {
        if (key.kind != KeyKind.CHAR || page != Page.LETTERS) return null
        val h = layout.hintFor(key.text) ?: return null
        return if (shift != Shift.OFF) upper(h) else h
    }

    fun openPopup(key: KeySpec): Boolean {
        val alts = alternates(key)
        if (alts.isEmpty()) return false
        popup = key to alts; popupIndex = 0
        return true
    }

    fun choosePopup(option: String?) {
        popup = null
        if (option == null) return
        sink()?.commit(option)
        if (shift == Shift.ONCE) shift = Shift.OFF
        afterEdit()
    }

    fun movePopup(delta: Int) { popup?.let { popupIndex = (popupIndex + delta).coerceIn(0, it.second.lastIndex) } }

    // ── controller ───────────────────────────────────────────────────────
    /** D-pad / stick step. dx, dy ∈ {-1, 0, 1}. */
    fun moveFocus(dx: Int, dy: Int) {
        padActive = true
        if (popup != null) { if (dx != 0) movePopup(dx) ; if (dy > 0) choosePopup(null); return }
        val grid = rows()
        val (r0, c0) = focus ?: run { focus = defaultFocus(grid); return }
        if (dy != 0) {
            val r1 = (r0 + dy).coerceIn(0, grid.lastIndex)
            if (r1 != r0) focus = r1 to nearestColumn(grid[r0], c0, grid[r1])
        } else if (dx != 0) {
            val row = grid[r0]
            focus = r0 to ((c0 + dx) % row.size + row.size) % row.size   // wrap around
        }
    }

    fun focusedKey(): KeySpec? = focus?.let { (r, c) -> rows().getOrNull(r)?.getOrNull(c) }

    fun controllerPress() {
        popup?.let { (_, opts) -> choosePopup(opts[popupIndex]); return }
        val k = focusedKey() ?: run { focus = defaultFocus(rows()); return }
        press(k)
    }

    fun controllerLongPress() { focusedKey()?.let { openPopup(it) } }

    fun dismissFocus() { focus = null; popup = null; padActive = false; helpOpen = false }

    private fun defaultFocus(grid: List<List<KeySpec>>): Pair<Int, Int> =
        (grid.size / 2 - 1).coerceAtLeast(0).let { r -> r to grid[r].size / 2 }

    private fun clampFocus() {
        val f = focus ?: return
        val grid = rows()
        val r = f.first.coerceIn(0, grid.lastIndex)
        focus = r to f.second.coerceIn(0, grid[r].lastIndex)
    }

    /** Column in [to] whose centre is closest to key [c] of [from] (rows differ in width). */
    private fun nearestColumn(from: List<KeySpec>, c: Int, to: List<KeySpec>): Int {
        fun centres(row: List<KeySpec>): List<Float> {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            return row.map { k -> val mid = (x + k.weight / 2f) / total; x += k.weight; mid }
        }
        val target = centres(from)[c.coerceIn(0, from.lastIndex)]
        return centres(to).withIndex().minByOrNull { abs(it.value - target) }!!.index
    }
}
