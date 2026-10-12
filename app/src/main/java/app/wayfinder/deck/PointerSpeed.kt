package app.wayfinder.deck

/**
 * b18h (GitHub #49 "a tad too sensitive", #99 "sensitivity for Touch"): Pointer speed — one name and one scale, 1 to 10,
 * for a pointer Wayfinder moves by a relative amount: Keyboard & mouse's trackpad in Mouse and Touch
 * ([DeckSettings.pointerSpeed]) and a stick set to Mouse in Game controls (`StickJob.speed`, ExtEngine). It is the stick's
 * curve, which already existed (300 + 170 · speed px/s at full deflection): [factor] is that curve divided by its value at
 * [DEFAULT], so 5 moves exactly as before (×1) and the stick keeps its numbers ([stickPps]). Direct is not on it: its pad
 * is the game's screen, 1:1. Pure Kotlin (PointerSpeedTest).
 */
object PointerSpeed {
    const val MIN = 1
    const val MAX = 10
    /** The speed before b18h: a new install, and anyone who never touches the setting. */
    const val DEFAULT = 5

    fun clamp(s: Int): Int = s.coerceIn(MIN, MAX)

    /** One step [faster] (+) or slower (−), kept inside [MIN]..[MAX]. */
    fun step(s: Int, faster: Boolean): Int = clamp(s + if (faster) 1 else -1)

    /** A stick set to Mouse: px / s at full deflection — ExtEngine's numbers since 1.3, the same float arithmetic. Not
     *  clamped: a value outside 1–10 read from a file keeps the speed it had. */
    fun stickPps(s: Int): Float = 300f + s * 170f

    /** How far a move goes compared with [DEFAULT]: 1 ×0.41, 3 ×0.70, 5 ×1 exactly, 8 ×1.44, 10 ×1.74. */
    fun factor(s: Int): Float = stickPps(clamp(s)) / stickPps(DEFAULT)
}
