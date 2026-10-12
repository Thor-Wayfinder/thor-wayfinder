package app.wayfinder

// ─────────────────────────────────────────────────────────────────────────────
// Hub navigation. Pages are strings so deep links work ("controls", "controls:<pkg>").
// B / Back always goes up one level (see [hubParent]).
// dev build 16 (theme E): the routes on their own (moved from Hub.kt) — pure, the JVM tests load them (HubRoutesTest).
// dev build 17 (WP11): the Thor's page ("thor") and each controller's Test page ("padtest:<thor|key>"); a game's page is
// "remap:<key>" (D11: the Games tile and Home + X open the same page) — its sub-pages (its combos, its lights) go back to it.
// The pages' names, and the rows a text points at: NavPaths.kt (one registry, built on [hubParent]).
// ─────────────────────────────────────────────────────────────────────────────

object HubPage {
    const val HOME = "hub"
    const val SCREENS = "screens"
    const val CONTROLLER = "controller"
    const val CONTROLS = "controls"
    const val APPS = "apps"
    const val SHOTS = "screenshots"
    const val APPEARANCE = "appearance"
    const val HELP = "help"
    const val SEARCH = "search"
    const val KEYBOARD = "keyboard"
    const val LIGHTS = "lights"
    const val PAIRS = "pairs"
    const val SLEEP = "sleep"
    const val BATTERY = "battery"
    const val TOUR = "tour"
    const val PANEL = "panel"
    const val PADTEST = "padtest"
    const val EXTPADS = "extpads"
    const val LICENSES = "licenses"
    // 1.4
    const val MORE = "more"
    const val SOUND = "sound"
    const val HINTS = "hints"
    const val FEATURES = "features"
    /** dev build 17 (WP11): the Thor's page — the same template as each controller's ("extpad:<key>"). Also the Thor's key on
     *  the Test page ("padtest:thor"): it can never match [ExtRules.KEY] (8 hex digits and a dash). */
    const val THOR = "thor"
    fun lightsFor(pkg: String) = "lights:$pkg"
    fun controlsFor(pkg: String) = "controls:$pkg"
    fun remapFor(pkg: String) = "remap:$pkg"
    /** "Buttons for <app>" opened from a game (Game controls): Back returns to the game. */
    fun playFor(pkg: String) = "play:$pkg"
    /** dev build 16 (theme E): one controller's page (its key — the same pattern the helper checks, [ExtRules.KEY]). Its
     *  parent is the Controller page; Back focuses its row ("pad:<key>") on the page that opened it. */
    fun extpad(key: String) = "extpad:$key"
    /** dev build 17 (WP11): the Test page on one controller — [THOR] or a controller's key ("padtest" alone: the one in hand). */
    fun padtestFor(key: String) = "padtest:$key"
    /** dev build 17 (WP11, D11): a game's or an app's page from the Hub (the Games tile) — the same route as before. */
    fun gameFor(key: String) = remapFor(key)

    internal val ALL = setOf(HOME, SCREENS, CONTROLLER, CONTROLS, APPS, SHOTS, APPEARANCE, HELP, SEARCH, KEYBOARD, LIGHTS, PAIRS, SLEEP, TOUR, PANEL, PADTEST, LICENSES,
        MORE, SOUND, HINTS, FEATURES, BATTERY, EXTPADS, THOR)
    /** A `page` extra from an intent (any app can send one): only known pages, and per-app
     *  pages only for a real package name. Anything else → null (the Hub opens normally). */
    fun safe(p: String?): String? = when {
        p == null -> null
        p in ALL -> p
        p.startsWith("controls:") && Shell.isPkg(p.removePrefix("controls:")) -> p
        // dev build 17 (WP11, rule 7): Stick lights is the one lights editor — a game's own lights too (was in Performance)
        p.startsWith("lights:") && profileKeyOk(p.removePrefix("lights:")) && '@' !in p -> p
        p.startsWith("remap:") && profileKeyOk(p.removePrefix("remap:")) -> p
        p.startsWith("play:") && profileKeyOk(p.removePrefix("play:")) -> p
        p.startsWith("extpad:") && ExtRules.KEY.matches(p.removePrefix("extpad:")) -> p   // dev build 16: a well-formed key only
        p.startsWith("padtest:") && p.removePrefix("padtest:").let { it == THOR || ExtRules.KEY.matches(it) } -> p   // dev build 17 (WP11)
        else -> null
    }

    /** Device pass (final): the page a window shows for the page asked of it ([asked]: [safe]'s answer; null = none) — at its
     *  start ([shown] null), or while it shows [shown]. The Hub's home (the quick panel's compass and "Wayfinder" tile name it)
     *  is the tour where the tour is: at a start while the first-run tour isn't done ([tourDone]), as a start with no page; in
     *  a running window while the tour is shown — its "quick panel" step has the controller in the real panel, whose compass
     *  dropped the tour for the home. Any other page: as asked. null = nothing to change (a running window, nothing asked). */
    fun toShow(asked: String?, shown: String?, tourDone: Boolean): String? = when {
        asked != null && asked != HOME -> asked
        shown == null -> if (tourDone) HOME else TOUR
        asked == null -> null
        shown == TOUR -> TOUR
        else -> HOME
    }
}

/** An app's package, or a game profile's key `<pkg>#<game-id>`. */
private fun profileKeyOk(k: String) = Shell.isPkg(GameProfiles.pkgOf(k)) &&
    (!GameProfiles.isGame(k) || Regex("^[a-z0-9-]{1,64}$").matches(k.substringAfter('#')))

fun hubParent(page: String): String? = when {
    page == HubPage.HOME -> null
    // dev build 17 (WP11, D11): an app's combos and lights are rows of its page — Back goes there (was Games)
    page.startsWith("controls:") -> HubPage.remapFor(page.removePrefix("controls:"))
    page.startsWith("lights:") -> HubPage.remapFor(page.removePrefix("lights:"))
    page.startsWith("remap:") -> HubPage.APPS
    page.startsWith("play:") -> HubPage.APPS
    page == HubPage.CONTROLS || page == HubPage.PADTEST || page == HubPage.EXTPADS -> HubPage.CONTROLLER
    page.startsWith("extpad:") -> HubPage.CONTROLLER                                         // dev build 16 (theme E)
    page == HubPage.THOR -> HubPage.CONTROLLER                                               // dev build 17 (WP11)
    page.startsWith("padtest:") -> page.removePrefix("padtest:").let { if (it == HubPage.THOR) HubPage.THOR else HubPage.extpad(it) }
    page == HubPage.SLEEP -> HubPage.BATTERY
    page == HubPage.PAIRS -> HubPage.SCREENS                                                 // dev build 17 (WP11): was the Quick panel
    page == HubPage.LICENSES -> HubPage.HELP
    // 1.4: under More
    page == HubPage.SOUND || page == HubPage.HINTS || page == HubPage.FEATURES || page == HubPage.LIGHTS ||
        page == HubPage.APPEARANCE || page == HubPage.HELP -> HubPage.MORE
    else -> HubPage.HOME
}
