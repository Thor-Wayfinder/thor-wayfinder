package app.wayfinder.keyboard

import android.view.inputmethod.EditorInfo
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
    /** b18p (GitHub #108): [autoCapitalize]'s answer, handed to [answer] — at once here. A sink that asks its field off the
     *  main thread answers later, on the main thread. */
    fun capsLater(answer: (Boolean) -> Unit) = answer(autoCapitalize())
    /** b18p (#108): a space, with the double-space rule ([KeyboardRules.doubleSpace]: "word␣" + space → "word. "). A sink
     *  that asks its field off the main thread does it there. */
    fun space() {
        if (KeyboardRules.doubleSpace(textBeforeCursor(2))) { deleteBackward(); commit(". ") } else commit(" ")
    }
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
                // Double space → ". " (and a capital next) — b18p (GitHub #108): the sink's, which may ask its field off the
                // main thread
                s.space()
                if (page != Page.LETTERS && page != Page.EMOJI) page = Page.LETTERS
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
            KeyKind.TO_SYMBOLS -> { page = Page.SYMBOLS; clampFocus() }
            KeyKind.TO_SYMBOLS_2 -> { page = Page.SYMBOLS_2; clampFocus() }
            KeyKind.TO_LETTERS -> { page = Page.LETTERS; clampFocus() }
            // 1.3.2 (GitHub #45): the last copied texts in the strip above — pick one to paste it
            KeyKind.TO_EMOJI -> { page = Page.EMOJI; clampFocus() }
            KeyKind.EMOJI_NEXT -> { emojiGroup = (emojiGroup + 1) % EMOJI_GROUPS.size; clampFocus() }
            KeyKind.CLIPBOARD -> {
                ClipHistory.read()
                if (ClipHistory.items.isNotEmpty()) { popup = key to ClipHistory.items.toList(); popupIndex = 0 }
            }
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
        page = Page.LETTERS; popup = null
        if (shift == Shift.LOCKED) shift = Shift.OFF
        afterEdit()
    }

    // ── long-press accents ───────────────────────────────────────────────
    /** The long-press options of [key] at ([row], [col]) (build 18, KB4: the top row's digit comes by position — only on
     *  the letters page; -1 = no position, no digit). */
    fun alternates(key: KeySpec, row: Int = -1, col: Int = -1): List<String> {
        if (key.kind != KeyKind.CHAR) return emptyList()
        val letters = page == Page.LETTERS
        val alts = layout.alternatesFor(key.text, if (letters) row else -1, col)
        return if (letters && shift != Shift.OFF) alts.map { upper(it) } else alts
    }

    /** Upper case for this layout — but ß stays ß (no capital in normal use; "SS" is two letters). */
    private fun upper(s: String) = if (s == "ß") s else s.uppercase(layout.locale)

    /** The small hint on [key] at ([row], [col]) of the letters page (KB4: the top row reads 1…0 by position). */
    fun hint(key: KeySpec, row: Int, col: Int): String? {
        if (key.kind != KeyKind.CHAR || page != Page.LETTERS) return null
        val h = layout.hintFor(key.text, row, col) ?: return null
        return if (shift != Shift.OFF) upper(h) else h
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
        sink()?.commit(option)
        if (shift == Shift.ONCE) shift = Shift.OFF
        afterEdit()
    }

    fun movePopup(delta: Int) { popup?.let { popupIndex = (popupIndex + delta).coerceIn(0, it.second.lastIndex) } }

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
        press(k)
    }

    fun controllerLongPress() { val (r, c) = focus ?: return; focusedKey()?.let { openPopup(it, r, c) } }

    /** Build 18 (KB1): the keyboard opened from a controller — a key is lit (the middle one) and the badges show, so the
     *  first press types instead of only lighting a key. A key lit already stays where it is. */
    fun lightKey() {
        padActive = true
        if (focus == null && popup == null) focus = defaultFocus(rows())
    }

    fun dismissFocus() { focus = null; popup = null; padActive = false; helpOpen = false }

    private fun defaultFocus(grid: List<List<KeySpec>>): Pair<Int, Int> =
        (grid.size / 2 - 1).coerceAtLeast(0).let { r -> r to grid[r].size / 2 }

    private fun clampFocus() {
        val f = focus ?: return
        if (f.first == BAR) { focus = BAR to f.second.coerceIn(0, barKeys().lastIndex); return }   // b18n: the bar stays lit
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

    /**
     * b18p (GitHub #108): the keyboard sends to and asks the field off the main thread — for a field of Wayfinder's own process
     * only ([fieldPkg], the field's EditorInfo package, is [ownPkg]). There a web page (the Guide) answers through the main
     * thread the keyboard would be waiting on: each question ran out Android's 2 s limit, several a key (one key froze
     * Wayfinder ~12 s and raised "not responding", on a device). Any other app's field answers from its own process: as before.
     */
    fun offMain(fieldPkg: String?, ownPkg: String): Boolean = fieldPkg != null && fieldPkg == ownPkg

    /**
     * KB5: where the app's content may end, in px from the top of the strip's window (onComputeInsets'
     * contentTopInsets): the window's full height minus the strip's measured height — never below the strip's own top
     * ([stripTop]: something under the strip, such as AYN's keyboard-mode bar, would put the app under the strip) and only
     * when it gives the app more room than [current]. null = keep [current]; unmeasured (≤ 0 / -1) = null.
     */
    fun stripInsetTop(windowH: Int, stripH: Int, stripTop: Int, current: Int): Int? {
        if (windowH <= 0 || stripH <= 0 || stripTop < 0) return null
        return minOf(windowH - stripH, stripTop).takeIf { it > current }
    }
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
