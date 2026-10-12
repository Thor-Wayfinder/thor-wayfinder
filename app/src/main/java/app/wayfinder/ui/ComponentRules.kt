package app.wayfinder.ui

/*
 * dev build 17 (WP8): the components' pure rules (no Compose import — ComponentRulesTest runs them on the JVM).
 */

/** A segmented control's Left / Right: the index one step [dir] from [cur] in the same row of [columns] (WP1's roving,
 *  row-bounded), null at a row's end or outside — the key then goes on (the focus may leave sideways). */
internal fun segmentStep(cur: Int, dir: Int, size: Int, columns: Int): Int? {
    if (dir == 0 || cur !in 0 until size) return null
    val cols = columns.coerceAtLeast(1)
    val next = cur + dir
    if (next !in 0 until size || next / cols != cur / cols) return null
    return next
}

/** Build 18 (A2): a segmented control's Up / Down — in a grid of [columns] a row, the option in the row above or below at
 *  the nearest column (clamped to that row's last option: a short last row); null at the grid's top or bottom, in a
 *  one-row control, or outside — the key then goes on and the focus leaves the control. */
internal fun segmentStepVertical(cur: Int, dir: Int, size: Int, columns: Int): Int? {
    if (dir == 0 || cur !in 0 until size) return null
    val cols = columns.coerceAtLeast(1)
    val rows = (size + cols - 1) / cols
    val row = cur / cols + (if (dir > 0) 1 else -1)
    if (row !in 0 until rows) return null
    return minOf(row * cols + cur % cols, size - 1)
}

/** Device pass (P2-6, A2): Up / Down ([up] / [down]) that found no row above / below inside the control
 *  ([segmentStepVertical] null) from one of its options ([cur] of [size]) leave the control — moved out by the control itself,
 *  its other options no candidates: in a one-row choice, Down from "Frame rate" went to "Everything" in the same row (taller,
 *  it has a caption). Left / Right, or no option known yet: the key goes on, as before. */
internal fun segmentLeavesVertically(up: Boolean, down: Boolean, cur: Int, size: Int): Boolean =
    (up || down) && cur in 0 until size

/** Device pass (final): that leave is always the control's key — handed on, the page's own search (unrestricted) brought
 *  P2-6 back. Its search [moved] the focus: done. Nothing beyond: the page's edge scroll when the page has one
 *  ([pageScrolls]: SubPage's "nothing left below: the page scrolls", QA2) — the text after a page's last choice (Lights'
 *  "Colour from", a game's "Guide & notes") stayed out of reach; none (a popup): nothing moves. */
internal fun segmentEdgeScrolls(moved: Boolean, pageScrolls: Boolean): Boolean = !moved && pageScrolls

// ── build 18 (A21): a choice folds by width; a hint while an option has the focus ──

/** Build 18 (A21, MORE-D03): what one segment needs, dp — fewer each and the choice folds into one value row (its list
 *  opens with A). Theme's 4 and Volume boost's 5 fit the top screen's cards (~780 dp); the bottom screen folds them. */
internal const val SEGMENT_MIN_DP = 150f

/** A choice of [count] options shows as one value row when they can't have [SEGMENT_MIN_DP] each in [maxWidthDp] (it was
 *  "4 or more" whatever the room), or when its widest word ([widestTextDp], measured in the segments' style; [tight] as
 *  [GlassSegmentedControl]'s) wouldn't fit its segment — a cut word can't be read ("Automatic (PlayStation)" among a
 *  controller's five faces). Never with [captions] or in several rows ([columns]), and never under 4 options: a 2–3
 *  option row stays a row, as before (the quick panel's screen modes). */
internal fun segmentsFold(count: Int, maxWidthDp: Float, captions: Boolean, columns: Int, widestTextDp: Float = 0f,
                          tight: Boolean = false): Boolean {
    if (!segmentsMayFold(count, captions, columns)) return false
    if (count * SEGMENT_MIN_DP > maxWidthDp) return true
    return widestTextDp > segmentTextDp(maxWidthDp, count, if (tight) 6f else 8f, tight)
}

/** Whether [segmentsFold] can say yes at any width (the control measures its room only then). */
internal fun segmentsMayFold(count: Int, captions: Boolean, columns: Int): Boolean = !captions && columns >= count && count >= 4

/** Build 18 (A21, ps-F18): the card's line while option [focused] of a choice has the focus — "<option>: <hint>" when it
 *  isn't the [selected] one and has a hint; null = the card's own line (the selected option, no hint, outside). */
internal fun segmentHint(options: List<String>, hints: List<String>?, focused: Int, selected: Int): String? =
    if (hints == null || focused == selected || focused !in options.indices) null
    else hints.getOrNull(focused)?.takeIf { it.isNotBlank() }?.let { "${options[focused]}: $it" }

/** Device pass (A19 leftover): HexColorField's "Colour code" label goes before its square when the row has room for the
 *  three — the label [labelDp], the square [previewDp], the field [fieldDp], [gapDp] between them — in [maxWidthDp]
 *  (Stick lights); else under the row (Your own colours' four pickers side by side). */
internal fun hexLabelLeads(maxWidthDp: Float, labelDp: Float, previewDp: Float, fieldDp: Float, gapDp: Float): Boolean =
    maxWidthDp >= labelDp + previewDp + fieldDp + 2 * gapDp

/** Anything that deletes what the user made asks twice: the first press asks, the second acts ([ConfirmState]). */
internal class TwoPress {
    var asked = false
        private set
    /** true = act now (the second press); false = it asked. */
    fun press(): Boolean {
        if (asked) { asked = false; return true }
        asked = true
        return false
    }
    fun cancel() { asked = false }
}

/** dev build 17 (review WP8-2): a tight [GlassOption]'s text padding a side, dp (a normal one: 8) — Game controls' compact
 *  header has three 43 dp cells and "Usual" needs ~32 of one. */
internal const val TIGHT_PAD_DP = 2

/** The text width (dp) a cell of a [GlassSegmentedControl] row leaves: [rowDp] wide, [cells] equal ones [gapDp] apart. */
internal fun segmentTextDp(rowDp: Float, cells: Int, gapDp: Float, tight: Boolean): Float =
    (rowDp - gapDp * (cells - 1)) / cells - 2 * (if (tight) TIGHT_PAD_DP else 8)

/** A [KeyHint] is a capsule when its name is longer than a dot holds (a sideways Joy-Con's "Right"). */
internal fun keyHintWide(text: String): Boolean = text.length > 2

// ── build 18 (A5): each controller's own system buttons ──

/** The icons a system button's round badge can carry (the Compose icon is picked in ControlGlyphs.kt). b18r3: and the short
 *  forms of the words ([sysGlyphShort], [aynShort]) — a DualSense's printed Create mark, a left Joy-Con's printed Capture
 *  mark (both drawn), Share's icon, the Mute button's crossed microphone. */
internal enum class SysIcon { HOUSE, REPLAY, PLAY, SQUARE, MENU, VIEW, CREATE, CAPTURE, SHARE, MIC_OFF }

/** How [ButtonGlyph] draws Home, Back, Start or Select: an icon in the round chip, a word in a shoulder-style capsule
 *  ([small]: a smaller word — a Home-less controller's "Select+Start"), a letter ("−", "+"), or a touchpad. */
internal sealed class SysGlyph {
    data class Icon(val icon: SysIcon) : SysGlyph()
    data class Capsule(val text: String, val small: Boolean = false) : SysGlyph()
    data class Letter(val text: String) : SysGlyph()
    object Touchpad : SysGlyph()
}

/**
 * Build 18 (A5): [b]'s system-button badge on [look]'s controller — as that controller prints it. The Thor and an unknown
 * pad keep the Thor's icons (house, Back's loop, Start's triangle, Select's square); a PlayStation pad: "PS", its Create /
 * Share, Options' ≡, the touchpad for Back; an Xbox pad: "Xbox", View, Menu; the Nintendo kinds: − and +, a left Joy-Con's
 * Capture for Home; a pad without Home: its Select + Start. A Select never looks like PlayStation's □, a Start never like a
 * △ (GlyphRulesTest). Not for the face buttons, the shoulders, the sticks or the D-pad.
 */
internal fun systemGlyphKind(b: app.wayfinder.ThorButton, look: app.wayfinder.PadLook): SysGlyph {
    val k = look.kind
    val thors = thorSysGlyph(b)
    if (k == app.wayfinder.ControllerKind.THOR) return thors
    fun name(x: app.wayfinder.ThorButton) = app.wayfinder.Vocab.name(x, look)
    // its Home: the same order as Vocab.name (a left Joy-Con's Capture, then a Home-less pad's Select + Start)
    if (b == app.wayfinder.ThorButton.HOME) when {
        k == app.wayfinder.ControllerKind.JOYCON_L -> return SysGlyph.Capsule(name(b))
        look.noHome -> return SysGlyph.Capsule("${name(app.wayfinder.ThorButton.SELECT)}+${name(app.wayfinder.ThorButton.START)}", small = true)
    }
    return when {
        k == app.wayfinder.ControllerKind.PLAYSTATION -> when (b) {
            app.wayfinder.ThorButton.HOME -> SysGlyph.Capsule("PS")
            app.wayfinder.ThorButton.SELECT -> SysGlyph.Capsule(name(b))            // Create / Share
            app.wayfinder.ThorButton.START -> SysGlyph.Icon(SysIcon.MENU)           // Options
            else -> SysGlyph.Touchpad                                               // Back: the touchpad click
        }
        k == app.wayfinder.ControllerKind.XBOX -> when (b) {
            app.wayfinder.ThorButton.HOME -> SysGlyph.Capsule("Xbox")
            app.wayfinder.ThorButton.SELECT -> SysGlyph.Icon(SysIcon.VIEW)
            app.wayfinder.ThorButton.START -> SysGlyph.Icon(SysIcon.MENU)
            else -> thors
        }
        k.nintendo -> when (b) {
            app.wayfinder.ThorButton.SELECT, app.wayfinder.ThorButton.START -> SysGlyph.Letter(name(b))   // − / + (sideways: its −)
            else -> thors
        }
        else -> thors                                                               // an unknown pad: the Thor's
    }
}

/** The Thor's badge for [b]: its printed icons — Home's house, Back's loop, Start's triangle, Select's square. */
internal fun thorSysGlyph(b: app.wayfinder.ThorButton): SysGlyph = when (b) {
    app.wayfinder.ThorButton.HOME -> SysGlyph.Icon(SysIcon.HOUSE)
    app.wayfinder.ThorButton.BACK -> SysGlyph.Icon(SysIcon.REPLAY)
    app.wayfinder.ThorButton.START -> SysGlyph.Icon(SysIcon.PLAY)
    else -> SysGlyph.Icon(SysIcon.SQUARE)
}

/**
 * b18r3 (r2 BUG-1, P2-8's side effect): a capsule badge's short form — one badge wide, for a slot narrower than its word at
 * the 11 sp floor ([BADGE_WORD_MIN_SP]; ControlGlyphs.kt measures, the word is never shrunk under it): the keyboard's globe
 * key (≈ 36 dp) cut a DualSense's "Create" (≈ 47 dp on its 16 dp badge) to "Creat". The controller's own printed mark where
 * it has one (a DualSense's Create, a left Joy-Con's Capture), an icon for a word it prints without one (a DualShock 4's
 * Share), else the Thor's look for that button ("PS", "Xbox", a Home-less pad's "Select+Start": Home's house). Its name
 * stays its description. Only for what [systemGlyphKind] draws as a [SysGlyph.Capsule]; the rest is one badge wide already.
 */
internal fun sysGlyphShort(b: app.wayfinder.ThorButton, look: app.wayfinder.PadLook): SysGlyph = when {
    b == app.wayfinder.ThorButton.HOME && look.kind == app.wayfinder.ControllerKind.JOYCON_L -> SysGlyph.Icon(SysIcon.CAPTURE)
    b == app.wayfinder.ThorButton.SELECT && look.kind == app.wayfinder.ControllerKind.PLAYSTATION ->
        SysGlyph.Icon(if (app.wayfinder.Vocab.name(b, look) == "Create") SysIcon.CREATE else SysIcon.SHARE)
    else -> thorSysGlyph(b)
}

/** b18r3: the AYN chip's short form ([aynGlyphText]'s word, from Vocab.spareName), for a slot its word doesn't fit at the
 *  floor: the button's mark — a DualSense's Mute, a Nintendo pad's Capture, a Share; null = none shorter ("AYN", "button 5"):
 *  the chip as it is. */
internal fun aynShort(text: String): SysIcon? = when (text) {
    "Mute" -> SysIcon.MIC_OFF
    "Capture" -> SysIcon.CAPTURE
    "Share" -> SysIcon.SHARE
    else -> null
}

/** Device pass (P2-8, A5 / A17): the smallest word a badge draws, sp — a shoulder's (A17), a system button's capsule ("PS",
 *  "Create", "Xbox") and the AYN chip's ("AYN", "Mute") alike: one size family inline. A capsule's 0.36 of an 18 dp inline
 *  badge drew "PS" ~6 px tall ("Press the control you want · PS cancels", "Remove · hold Create"). */
internal const val BADGE_WORD_MIN_SP = 11f

/** A badge's word, sp, on a badge [sizeDp] tall: [ratio] of it (its look on a large badge), never under [BADGE_WORD_MIN_SP].
 *  [sizeDp] 0 = unsized: the ratio alone (in badge heights — the widths below, without a size). */
internal fun badgeWordSp(sizeDp: Float, ratio: Float): Float = if (sizeDp <= 0f) ratio else maxOf(sizeDp * ratio, BADGE_WORD_MIN_SP)

/** A system button capsule's word, sp, on a badge [sizeDp] tall: 0.36 of it — 0.26 when [SysGlyph.Capsule.small] (a
 *  Home-less pad's "Select+Start") — never under [BADGE_WORD_MIN_SP]. */
internal fun capsuleTextSp(s: SysGlyph.Capsule, sizeDp: Float): Float = badgeWordSp(sizeDp, if (s.small) 0.26f else 0.36f)

/** A system badge's width, × its height: the round chip 1; a capsule as its word needs (bold, ~0.62 em a letter at
 *  [capsuleTextSp] — device pass P2-8: with its floor on a small badge — plus 0.2 a side), never under a shoulder's 1.35 —
 *  the inline placeholders ([rememberButtonInline]) reserve the same width the badge draws. [sizeDp] = the badge's height
 *  (0 = unsized: the word at its ratio, as before the floor). */
internal fun sysGlyphWidth(s: SysGlyph, sizeDp: Float = 0f): Float = when (s) {
    is SysGlyph.Capsule -> maxOf(1.35f, 0.4f + s.text.length * 0.62f * wordShare(capsuleTextSp(s, sizeDp), sizeDp))
    else -> 1f
}

/** A word's size as a share of its badge's height ([badgeWordSp] / [sizeDp]; unsized: already a share). */
private fun wordShare(wordSp: Float, sizeDp: Float): Float = if (sizeDp <= 0f) wordSp else wordSp / sizeDp

// ── build 18 (A15 / A17): the AYN chip and the shoulders' words ──

/** Build 18 (A15): the AYN button's chip word — the controller's own button doing AYN's job ("Mute" on a DualSense), else
 *  "AYN" (the Thor, a controller whose AYN is Home + Start). */
internal fun aynGlyphText(look: app.wayfinder.PadLook): String = look.ayn ?: "AYN"

/** The AYN chip's word, sp, on a chip [sizeDp] tall: 0.38 of it, never under [BADGE_WORD_MIN_SP] (device pass P2-8). */
internal fun aynTextSp(sizeDp: Float): Float = badgeWordSp(sizeDp, 0.38f)

/** The AYN chip's width × its height: 1.5 for "AYN" (as before), wider for a longer word (bold, ~0.62 em a letter at
 *  [aynTextSp], plus 0.25 a side) — the inline placeholders ([rememberButtonInline]) reserve the same width the chip draws.
 *  [sizeDp] = the chip's height (0 = unsized: the word at 0.38, as before the floor). */
internal fun aynGlyphWidth(text: String, sizeDp: Float = 0f): Float =
    maxOf(1.5f, 0.5f + text.length * 0.62f * wordShare(aynTextSp(sizeDp), sizeDp))

/** Build 18 (A17): a shoulder badge's word (L1, RB, ZR…) in sp on a badge of [sizeDp] — 0.55 of it, never under 11 sp (0.42
 *  drew Game controls' legend "L1 R1" at 7–9 px). Device pass (P2-8): the floor every badge word shares. */
internal fun shoulderTextSp(sizeDp: Float): Float = maxOf(sizeDp * 0.55f, BADGE_WORD_MIN_SP)

/** A shoulder badge's width × its height: 1.5, wider where the 11 sp floor needs it on a small badge (two bold letters at
 *  ~0.62 em, 6 dp a side) — [rememberButtonInline] reserves it. */
internal fun shoulderGlyphWidth(sizeDp: Float): Float =
    if (sizeDp <= 0f) 1.5f else maxOf(1.5f, (2 * 0.62f * shoulderTextSp(sizeDp) + 12f) / sizeDp)
