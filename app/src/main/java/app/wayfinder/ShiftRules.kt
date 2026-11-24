package app.wayfinder

/*
 * 1.5-beta2 (Q11): the shift button's page and the chords (hold-to-shift). Held, the shift button
 * gives every other button its "With … held" job; alone, its tap reaches the game (or Wayfinder plays its own job, b19) as
 * it is let go. Pure (ShiftRulesTest): RemapScreen.kt draws what it says.
 */
internal object ShiftRules {
    /** How a button fires, as its page offers it ("How it fires"). The shift button ([isShift]): Normal or Double only, a tap
     *  or two (b19). Held, it is the shift, and alone it's a tap as it is let go: a turbo, a toggle or a long press
     *  of its own never comes. Any other button: all five. */
    fun fires(isShift: Boolean): List<Fire> = if (isShift) listOf(Fire.NORMAL, Fire.DOUBLE) else Fire.values().toList()

    /** The shift's page: its line under "How it fires". [was]: a way it fires that its page doesn't offer (set before it
     *  became the shift button, or by an import): the line asks for one it offers. */
    fun firesLine(was: Fire?): String = "It's the shift button: only a tap counts, ${Fire.NORMAL.label} or ${Fire.DOUBLE.label}." +
        (was?.takeIf { it !in fires(true) }?.let { " Pick one (it was set to ${it.label.lowercase()})." } ?: "")

    /** A press for a new chord's two buttons: never the shift button (its chord could never fire: holding it opens its
     *  gate, and the other button under it plays its Shift layer). b19 kept such a chord unplayed ([PadRemap.liveChords]). */
    fun refusedInChord(b: ThorButton, shift: ThorButton?): Boolean = shift != null && b == shift

    /** Why ([name]: the shift button's). */
    fun refusedLine(name: String) = "$name is the shift button: holding it gives the others their second job, so it can't be in a chord."

    /** A chord in the list with the shift button (made before it became the shift): not played, its row says so. */
    fun chordOff(c: Chord, shift: ThorButton?): Boolean = shift != null && c.has(shift)
    fun chordOffWords(name: String) = "off while $name is the shift button"

    /**
     * Game settings' Shift button line, its last sentence: [name] pressed alone. Its own job ([own]: [PadRemap.byApp], keys,
     * a mouse button, an action, a macro, a long / double press; [job] its words, null = none to say): Wayfinder does it
     * as you let go (b19). Else its tap reaches the game as you let go, so late: as itself, as another button ([asButton]),
     * or nothing at all ([nothing]: set to Nothing).
     */
    fun alone(name: String, own: Boolean, job: String?, asButton: String?, nothing: Boolean): String = when {
        own -> "$name alone does " + (job ?: "its own job") + " as you let go."
        nothing -> "$name alone does nothing."
        asButton != null -> "$name alone reaches the game late, as $asButton."
        else -> "$name alone reaches the game late."
    }

    /** 1.5-beta2 (Q11, review): Game settings' whole Shift button line while [shift] is set: the held part, then [alone] from
     *  [remap]'s own job ([PadRemap.byApp]: what makes its tap Wayfinder's), 20 words at most (ShiftRulesTest runs it). */
    fun caption(shift: ThorButton, remap: PadRemap, look: PadLook): String {
        val name = shift.spoken(look)
        val t = remap.buttons[shift]
        return "Hold $name: buttons do only their “With $name held” job. " +
            alone(name, own = remap.byApp(shift), job = t?.short(look), asButton = (t as? RemapTarget.Button)?.b?.spoken(look),
                nothing = t == RemapTarget.None)
    }
}
