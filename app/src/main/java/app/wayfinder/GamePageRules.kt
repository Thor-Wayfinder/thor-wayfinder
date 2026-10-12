package app.wayfinder

/*
 * dev build 17 (WP11, D11): the Game page's rules, apart from the page (pure — GamePageRulesTest). One page per app or game,
 * from the Games tile and from Home + the top button in a game: Game controls first, then the app's own settings Game
 * controls doesn't have. One setting, one editor: what Game controls' Performance and More edit is never a row of the app's
 * settings, and the stick lights have one editor (Stick lights) that both only link to.
 */
object GamePageRules {
    enum class Start { CONTROLS, APP }
    /** The app settings' row ids ("game:combos", "game:lights"… — [NavCards.APP_COMBOS]): back from one of them, its row. */
    const val ROW_PREFIX = "game:"

    /** A game, or an app with games or controls of its own — its page opens on Game controls. */
    fun gamey(isGameApp: Boolean, hasGames: Boolean, running: Boolean, hasControls: Boolean) =
        isGameApp || hasGames || running || hasControls

    /** Where the page opens: back from one of its app settings' rows → that row (in a game too); in a game → Game controls;
     *  an app that isn't a game, or Game controls switched off ([Features.gameControls]) → its settings; else Game controls.
     *  [returnTo]: the row the page comes back to ([app.wayfinder.ui.ReturnFocus]), null = opened anew. */
    fun start(inGame: Boolean, gamey: Boolean, controlsOn: Boolean, returnTo: String?): Start = when {
        returnTo?.startsWith(ROW_PREFIX) == true -> Start.APP
        inGame || returnTo != null -> Start.CONTROLS
        !gamey || !controlsOn -> Start.APP
        else -> Start.CONTROLS
    }

    /** What the app settings edit (AppConfig fields) … */
    val APP_FIELDS = setOf("route", "reopenOnMove", "buttonsMode", "backToGame", "companion")
    /** … what Game controls edits (its drawing, Performance, More) — never the same field (the test) … */
    val CONTROLS_FIELDS = setOf("perf", "fan", "hz", "second", "fps", "extFirst", "layer", "followMove", "face", "remap")
    /** … and what the app settings only show, as a link to its editor (rule 7: Stick lights, a game's own lights too; review
     *  WP11-INGAME-LIGHTS-PATH: Game controls' Performance picks their mode too, as in build 16 — never the app settings). */
    val LINKED_FIELDS = setOf("lights")
}
