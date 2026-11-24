package app.wayfinder.ui

/**
 * dev build 17 (WP8): the theme tokens' values as ARGB, per theme — [glassColorsFor] turns them into Colors, the JVM tests
 * read them here (no Compose class on the test classpath). ARGB as Long: 0xFFFF453A doesn't fit an Int literal; an Int
 * colour (AppSettings) is `toLong() and 0xFFFFFFFFL`.
 */
internal object TokenArgb {
    /** Red for what deletes: #FF453A reads on dark glass (5.9 : 1 on #06070F), #D64545 on light (4.0 : 1 on #F3F4FA). */
    fun danger(dark: Boolean): Long = if (dark) 0xFFFF453A else 0xFFD64545

    /** The amber of warnings (dots, tints, the notice's icon) — the one already used on dark glass. */
    const val WARN = 0xFFE0A63A

    /** dev build 17 (WP8): warning TEXT and marks — on light glass the amber read at 2 : 1; a darker
     *  amber there (5.2 : 1 on #F3F4FA). Tints keep [WARN]. */
    fun warn(dark: Boolean): Long = if (dark) WARN else 0xFF8F5B00

    /** A popup's or sheet's card: opaque, no frost. [card] = "Your own colours"' card (its RGB). Build 18 (A12): alpha 0xFF
     *  everywhere (was F5 / FA / F7 — the page's words showed through the New pair sheet's headings). */
    fun surfaceModal(dark: Boolean, card: Long? = null): Long =
        card?.let { (it and 0x00FFFFFFL) or 0xFF000000 } ?: if (dark) 0xFF1C1E26 else 0xFFF4F5FA

    /** The wash inside Game controls' drawing panel (the wallpaper showing through made pills look focused). */
    fun wash(dark: Boolean, card: Long? = null): Long =
        card?.let { (it and 0x00FFFFFFL) or 0x99000000 } ?: if (dark) 0x9E1B1E26 else 0x80FFFFFF

    /** Text and marks on an accent fill: white, or near-black on a light accent ("Your own colours"). */
    fun onAccent(accent: Long): Long = if (luminance(accent) > 0.5) 0xFF0A0C14 else 0xFFFFFFFF

    /** Build 18 (A1): how far the light theme's focus ring is darkened from the accent toward #0A0C14 — 0.75 reads at 3 : 1
     *  against the accent and against the selected fill's accent → accent2 middle (the spec's 0.45 gave 2.3 : 1; TokensTest). */
    const val LIGHT_RING_DARKEN = 0.75

    /**
     * Build 18 (A1): the focus ring — it must read on a selected option's accent fill and on the card. Dark and black: white
     * (0xF2FFFFFF — the most any colour gets against their #3D9BFF accent: 2.9 : 1, and 3 : 1 against the selected fill's
     * accent → accent2 gradient); light: the accent darkened toward #0A0C14 ([LIGHT_RING_DARKEN]); "Your own colours"
     * ([card] given): their [text] or the colour on the accent, whichever reads better against BOTH the accent and the card.
     */
    fun focusRing(dark: Boolean, accent: Long, text: Long, card: Long? = null): Long {
        if (card != null) {
            val on = onAccent(accent)
            fun worst(c: Long) = minOf(contrast(c, accent), contrast(c, card))
            return (if (worst(on) > worst(text)) on else text) or 0xFF000000
        }
        return if (dark) 0xF2FFFFFF else mix(accent, 0xFF0A0C14, LIGHT_RING_DARKEN)
    }

    /** Build 18 (A3): an unselected option's opaque base under its glass — the app or the aurora behind no longer tints it
     *  (a blue button behind read as half-selected). [card] = "Your own colours"' card (alpha 0xC7). */
    fun optionWell(dark: Boolean, card: Long? = null): Long =
        card?.let { (it and 0x00FFFFFFL) or 0xC7000000 } ?: if (dark) 0xB31B1E26 else 0xC7FFFFFF

    /** Build 18 (A23, MORE-D31): what went right ("All set", "working ✓", a done step) — a green that reads on the card:
     *  #1E8E3E on light glass (#30D158 read at 2 : 1 there), #30D158 on the dark themes; "Your own colours" ([card]): the
     *  one of the two that contrasts more with their card. */
    fun positive(dark: Boolean, card: Long? = null): Long {
        if (card != null) return if (contrast(POSITIVE_LIGHT, card) > contrast(POSITIVE_DARK, card)) POSITIVE_LIGHT else POSITIVE_DARK
        return if (dark) POSITIVE_DARK else POSITIVE_LIGHT
    }
    const val POSITIVE_LIGHT = 0xFF1E8E3E
    const val POSITIVE_DARK = 0xFF30D158

    /** Build 18 (A24, MORE-D01): the least alpha, from [from] up in steps of 0.04 (1.0 at most), at which [text] over [card]
     *  reads on [card] at [target] : 1 — "Your own colours"' secondary (4.5) and tertiary (3.0) text; 1.0 when even the full
     *  colour doesn't reach it. */
    fun minAlpha(text: Long, card: Long, target: Double, from: Double): Double {
        var a = from.coerceIn(0.0, 1.0)
        while (a < 1.0) {
            if (contrast(mix(card, text, a), card) >= target) return a
            a = minOf(1.0, Math.round((a + 0.04) * 100) / 100.0)
        }
        return 1.0
    }

    /** [a] moved [t] (0–1) of the way to [b], per channel; opaque. */
    fun mix(a: Long, b: Long, t: Double): Long {
        fun ch(s: Int): Long { val x = (a shr s) and 0xFF; val y = (b shr s) and 0xFF; return Math.round(x + (y - x) * t).coerceIn(0, 255) }
        return 0xFF000000 or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The dim behind popups and sheets. */
    const val MODAL_DIM = 0x99000000

    /** WCAG relative luminance (sRGB → linear), alpha ignored. */
    fun luminance(argb: Long): Double {
        fun lin(c: Long): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    /** WCAG contrast ratio, (L1 + 0.05) / (L2 + 0.05) with the lighter first. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Build 18 (A9): the first of [candidates] that reads on [on] at [target] : 1 (WCAG contrast), else the one that reads
     *  best — a face badge's letter ([app.wayfinder.ui.ButtonGlyph]'s ink). [candidates] must not be empty. */
    fun readableIndex(candidates: List<Long>, on: Long, target: Double): Int {
        val first = candidates.indexOfFirst { contrast(it, on) >= target }
        return if (first >= 0) first else candidates.indices.maxByOrNull { contrast(candidates[it], on) } ?: 0
    }

    /** An Int colour (AppSettings) as this object's Long. */
    fun of(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

    /** 1.5-beta2 (GitHub #134): the face buttons' built-in colours, slot 0-3 = the Xbox-way letter A B X Y — an Xbox pad's
     *  (green, red, blue, yellow) and PlayStation's marks' (1.4, GitHub #50: ✕ ○ □ △ in the same slots). Were ControlGlyphs'
     *  FACE / PS_FACE literals, unchanged. */
    val FACE_XBOX = listOf(0xFF34C759, 0xFFFF453A, 0xFF0A84FF, 0xFFFFD60A)
    val FACE_PS = listOf(0xFF6F9FE6, 0xFFFF5A5F, 0xFFE38AC8, 0xFF3DDC97)

    /** 1.5-beta2 (GitHub #134): the colour of face slot [slot] (0-3, [FACE_XBOX]'s order) as Button colours says — [mode]
     *  [ButtonColours.COLOURED] (or anything unknown): the built-in one ([ps]: PlayStation's); [ButtonColours.ONE]: null, the
     *  badge takes the other buttons' neutral look; [ButtonColours.OWN]: the pick in [own] (made opaque), else the built-in. */
    fun faceColour(slot: Int, ps: Boolean, mode: Int, own: List<Long?>): Long? {
        val built = (if (ps) FACE_PS else FACE_XBOX).getOrNull(slot) ?: return null
        return when (mode) {
            ButtonColours.ONE -> null
            ButtonColours.OWN -> own.getOrNull(slot)?.let { it or 0xFF000000 } ?: built
            else -> built
        }
    }

    /** 1.5-beta2 (GitHub #134): the least contrast a picked colour's rim needs against the card to be its badge's edge (the
     *  built-in colours all have more: the yellow Y on Light is about 1.3); under it the badge takes the neutral rim. */
    const val FACE_RIM_MIN = 1.2

    /** 1.5-beta2 (GitHub #134): a picked colour [c] keeps its own rim on card [under] ([FACE_RIM_MIN]). */
    fun faceRimReads(c: Long, under: Long): Boolean = contrast(c, under) >= FACE_RIM_MIN

    /** 1.5-beta2 (GitHub #134, review F1): the least contrast a picked colour's letter needs on a controller drawing's face
     *  disc (WCAG's 3 : 1 for large text and shapes); under it the drawing's own ink is used. */
    const val FACE_LETTER_MIN = 3.0

    /** 1.5-beta2 (GitHub #134, review F1): letter colour [letter] reads on the drawing's face disc [disc]. */
    fun faceLetterReads(letter: Long, disc: Long): Boolean = contrast(letter, disc) >= FACE_LETTER_MIN
}

/**
 * 1.5-beta2 (GitHub #134): Appearance's "Button colours" — the face buttons' badges [COLOURED] (the default, as before), in
 * [ONE] colour (the other buttons' neutral chip and the text colour) or in [OWN] colours: [own] = the picks for A B X Y (the
 * Xbox-way letters; a PlayStation mark takes its letter's slot), null = as Coloured. A colour goes with its letter or mark,
 * wherever the names put it. A data class: the theme ([GlassColors.faces]) compares it by value.
 */
data class ButtonColours(val mode: Int = COLOURED, val own: List<Long?> = NONE) {
    /** Slot [slot]'s colour ([TokenArgb.faceColour]); null = One colour. */
    fun colour(slot: Int, ps: Boolean): Long? = TokenArgb.faceColour(slot, ps, mode, own)
    /** Slot [slot] has a colour of the user's own (only then may its rim give way: [TokenArgb.faceRimReads]). */
    fun picked(slot: Int): Boolean = mode == OWN && own.getOrNull(slot) != null

    companion object {
        const val COLOURED = 0
        const val ONE = 1
        const val OWN = 2
        val NONE: List<Long?> = listOf(null, null, null, null)
    }
}
