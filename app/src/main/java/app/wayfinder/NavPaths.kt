package app.wayfinder

/*
 * dev build 17 (WP11, theme H; critique K1 / K2): every page's name and the cards a text points at — ONE registry, so a
 * help text, the tour or a pill can never name a page or a row differently from the page itself, nor send you to a place
 * that moved (the dead references of builds ≤ 16: "Controller → More options", "Other controllers"). A path is built from
 * [hubParent], the routes' own tree: a page moved there moves every path to it. Pure Kotlin (NavPathsTest). WP9 extends
 * it (its words, links that open a page on its card); there is no second registry.
 * dev build 17 (WP9): every card a text, a link or a search entry points at; a link opens its page on it (Hub.kt goTo:
 * More options opened, the card focused by its [NavCard.id]) — NavPathsTest fails when a card is no longer drawn.
 */

/** A card (a row, a switch, a setting) a text points at. [id]: its focus id on its page (a row's `id`, what a link will
 *  focus); [page]: the page drawing it — a route, or a kind of page: "extpad:*" (one controller's page, the Thor's too),
 *  [NavPages.GAME] (a game's page); [inMore]: behind its page's "More options" row (a step of the path); [section]: steps
 *  inside the page you must open first ("Game settings" in Game controls) — never a visual header. */
data class NavCard(val id: String, val page: String, val title: String, val inMore: Boolean = false, val section: String? = null)

object NavPages {
    /** A game's or an app's page — Games › <that app>: Game controls first, then its own settings (D11; "remap:<key>"). In a
     *  game the same page opens on Home + the top button. */
    const val GAME = "remap:*"
    /** One controller's page ("extpad:<key>"; the Thor's is [HubPage.THOR], the same template). */
    const val CONTROLLER_PAGE = "extpad:*"
    /** Page titles: the pages, their rows and every path read them here. */
    val titles: Map<String, String> = mapOf(
        HubPage.HOME to "Wayfinder", HubPage.SCREENS to "Screens", HubPage.SHOTS to "Screens", HubPage.CONTROLLER to "Controller",
        HubPage.THOR to "The Thor", HubPage.EXTPADS to "Settings for every controller", HubPage.PADTEST to "Test it",
        HubPage.CONTROLS to "Combos", HubPage.APPS to "Games", HubPage.APPEARANCE to "Appearance", HubPage.HELP to "Help & status",
        HubPage.SEARCH to "Search", HubPage.KEYBOARD to "Keyboard", HubPage.LIGHTS to "Stick lights", HubPage.PAIRS to "App pairs",
        HubPage.SLEEP to "Sleep & standby", HubPage.BATTERY to "Battery", HubPage.TOUR to "Tour", HubPage.PANEL to "Quick panel",
        HubPage.LICENSES to "Open-source licenses", HubPage.MORE to "More", HubPage.SOUND to "Sound", HubPage.HINTS to "Hints & pop-ups",
        HubPage.FEATURES to "Features",
    )
    /** [page]'s title; a kind of page is named by [name] (the controller's, the app's) or in words. Build 18 (B23, MORE-D27):
     *  "your controller", "the game" — a path reads as a path ("Controller › your controller › Set up its buttons"; it read
     *  "Controller › the controller › …" and "Games › that app › …"). */
    fun title(page: String, name: String? = null): String = titles[page] ?: when (page) {
        CONTROLLER_PAGE -> name ?: "your controller"
        GAME -> name ?: "the game"
        else -> name ?: page
    }
}

object NavCards {
    // ── Controller (the list: the controllers, then combos and Game controls, then the screen) ──
    /** Its row; the page it opens has the same title (glossary: the row and the page say the same). */
    val EVERY = NavCard("extpads", HubPage.CONTROLLER, NavPages.title(HubPage.EXTPADS))
    /** Its row opens the Combos page (the Thor's page and each controller's have the same row). */
    val COMBOS = NavCard("combos", HubPage.CONTROLLER, NavPages.title(HubPage.CONTROLS))
    /** The input layer's card — out of More options since dev build 17 (WP11). */
    val LAYER = NavCard("layer", HubPage.CONTROLLER, "Where Game controls work")
    /** b18i (D1, 2026-10-10): Game controls on the screen the game isn't on, so the game keeps running — under Where
     *  Game controls work. The card's words say Game controls; the title keeps the 5-word rule. */
    val GC_SCREEN = NavCard("gc_screen", HubPage.CONTROLLER, "Open on the other screen")
    val WHICH_SCREEN = NavCard("screen", HubPage.CONTROLLER, "Which screen has the controller")
    // ── the Thor's page ──
    val HOME_TWICE = NavCard("home2", HubPage.THOR, "Home needs two presses")
    val NAMES_PRINTED = NavCard("printed", HubPage.THOR, "Names as printed")
    /** Build 18 (C11, T18-10): the name games list for the Thor's controller — on its page (was "Settings for every controller"). */
    val THOR_NAME = NavCard("thor:name", HubPage.THOR, "Its name in games")
    // ── one controller's page (the template: the Thor's, each connected one's) ──
    val TEST_IT = NavCard("pad:test", NavPages.CONTROLLER_PAGE, "Test it")
    val PAD_GAMES = NavCard("pad:games", NavPages.CONTROLLER_PAGE, "Its buttons in games")
    val PAD_SETUP = NavCard("pad:wizard", NavPages.CONTROLLER_PAGE, "Set up its buttons")
    /** dev build 18f: a connected controller drives the Thor's copy (games see only the Thor's controller) — its page's
     *  What games see. */
    val PAD_SEAT = NavCard("pad:seat", NavPages.CONTROLLER_PAGE, "Plays as the Thor's controller")
    /** dev build 18f: a PlayStation controller's touchpad moves Android's pointer — its page's Home and combos. */
    val PAD_TOUCHPAD = NavCard("pad:touchpad", NavPages.CONTROLLER_PAGE, "Touchpad moves the pointer")
    // ── Settings for every controller ──
    val HANDLES = NavCard("handles", HubPage.EXTPADS, "Wayfinder handles controllers")
    val ORDER = NavCard("order", HubPage.EXTPADS, "Player order")
    val TV = NavCard("tv", HubPage.EXTPADS, "On a TV or monitor")
    val COMBINE = NavCard("combine", HubPage.EXTPADS, "Combine Joy-Cons")
    /** b18m: a controller connected for the first time starts with [PAD_SEAT] on (games keep seeing only the Thor's). The requested
     *  label, "New controllers play as the Thor's controller" (7 words), cut to the 5-word title cap (NavPathsTest
     *  cardsAreWellFormed); its On line says it whole: "… plays as the Thor's controller". */
    val SEAT_NEW = NavCard("seatnew", HubPage.EXTPADS, "New controllers play as Thor's")
    // ── Screens (dev build 17, WP11: screen switching — there whatever the quick panel or Features) ──
    val PAIRS = NavCard("pairs", HubPage.SCREENS, NavPages.title(HubPage.PAIRS))
    val RESTORE = NavCard("screens:restore", HubPage.SCREENS, "Screens back after a restart")
    /** Build 18 (GitHub #98): which screen a screenshot takes, and where screen recordings go. Build 18 (B7, pF24): out of
     *  More options — a section of its own. */
    val SCREENSHOTS = NavCard("screens:shots", HubPage.SCREENS, "Screenshots")
    // build 18 (B3, hF2 / D8): every card Search opens — the page opens on it (its More options too)
    val BRIGHTNESS = NavCard("screens:brightness", HubPage.SCREENS, "Brightness")
    val GESTURE = NavCard("screens:gesture", HubPage.SCREENS, "Off with 3 fingers")
    val WAKE2 = NavCard("screens:wake2", HubPage.SCREENS, "Wake with a double tap")
    val IDLE = NavCard("screens:idle", HubPage.SCREENS, "Turn it off when unused")
    val COLOUR = NavCard("screens:colour", HubPage.SCREENS, "Colour", inMore = true)
    val DND = NavCard("screens:dnd", HubPage.SCREENS, "Do not disturb while playing", inMore = true)
    val AWAKE = NavCard("screens:awake", HubPage.SCREENS, "Stay awake", inMore = true)
    val FPS = NavCard("screens:fps", HubPage.SCREENS, "Show the frame rate", inMore = true)
    val NAVBAR = NavCard("screens:navbar", HubPage.SCREENS, "Keep the navigation bar hidden", inMore = true)
    val ANIMATE = NavCard("screens:animate", HubPage.SCREENS, "Animate apps moving between screens", inMore = true)
    // ── Sound ──
    val VOLUME = NavCard("sound:volume", HubPage.SOUND, "Volume")
    val SPEAKER = NavCard("sound:speaker", HubPage.SOUND, "Speaker sound fix")
    val HEADEQ = NavCard("sound:headeq", HubPage.SOUND, "Headphones & Bluetooth EQ")
    val BOOST = NavCard("sound:boost", HubPage.SOUND, "Volume boost", inMore = true)
    val LINKED = NavCard("sound:linked", HubPage.SOUND, "Volume buttons change both screens", inMore = true)
    // ── Appearance ──
    val THEME = NavCard("appearance:theme", HubPage.APPEARANCE, "Theme")
    val OWN_COLOURS = NavCard("appearance:own", HubPage.APPEARANCE, "Your own colours")
    val BACKDROP = NavCard("appearance:backdrop", HubPage.APPEARANCE, "Glass backdrop", inMore = true)
    val SEE_THROUGH = NavCard("appearance:see", HubPage.APPEARANCE, "See-through glass", inMore = true)
    val REFRACTION = NavCard("appearance:refraction", HubPage.APPEARANCE, "Light refraction", inMore = true)
    val HUB_SCREEN = NavCard("appearance:hub", HubPage.APPEARANCE, "Hub screen", inMore = true)
    // ── Hints, Battery, Sleep, Stick lights, Keyboard ──
    val RECENTS_HINT = NavCard("hints:recents", HubPage.HINTS, "Recent apps — the controller hint")
    val LIMIT80 = NavCard("battery:limit", HubPage.BATTERY, "Stop charging at 80 %")
    val DIRECT = NavCard("battery:direct", HubPage.BATTERY, "Direct power when plugged in")
    val LID_PAUSE = NavCard("sleep:lidpause", HubPage.SLEEP, "Lid closed: pause media")
    val LIGHTS_FOLLOW = NavCard("lights:follow", HubPage.LIGHTS, "Follow the screens' brightness")
    val KB_WHERE = NavCard("keyboard:where", HubPage.KEYBOARD, "Where it opens")
    val KB_DPAD = NavCard("keyboard:dpad", HubPage.KEYBOARD, "The D-pad picks the keys")
    /** b18h (GitHub #49, #99): the trackpad's Pointer speed — Keyboard & mouse for games (DeckEditor.kt). */
    val KB_SPEED = NavCard("keyboard:pointer_speed", HubPage.KEYBOARD, Say.POINTER_SPEED)
    /** b18i (D3, GitHub #99): the thin keyboard window on the typing screen (some games need it) — under Where it opens. */
    val KB_BAR = NavCard("keyboard:thin_bar", HubPage.KEYBOARD, Say.THIN_BAR)
    // ── Help & status (under "System access": a header, not a step) ──
    val HELP_LAYER = NavCard("help:layer", HubPage.HELP, "Input layer")
    /** Build 18 (B11, MORE-05): the System access row (moving apps, brightness, audio, sleep) — Troubleshooting's fix opens it. */
    val HELP_ACCESS = NavCard("help:access", HubPage.HELP, "System access")
    // build 18 (B3): Help's rows Search opens
    val UPDATES = NavCard("help:updates", HubPage.HELP, "Check for updates")
    val BACKUP = NavCard("help:backup", HubPage.HELP, "Back up now")
    val A11Y = NavCard("help:a11y", HubPage.HELP, "Accessibility service")
    // ── dev build 17 (WP9): the cards Help, Search and the Combos page point at ──
    val COMBO_LIST = NavCard("hints:list", HubPage.HINTS, "Combo list while holding")
    val YOUR_COMBOS = NavCard("hints:sheet", HubPage.HINTS, "Combos on the other screen")
    val PANEL_AYN = NavCard("panel:ayn", HubPage.PANEL, "AYN button opens the panel")
    val PANEL_TAKES = NavCard("panel:takes", HubPage.PANEL, "The panel takes the controller", inMore = true)
    /** Features' row for Game controls: its value, and the way to their one editor ([LAYER], rule 7). */
    val FEATURES_GC = NavCard("features:gc", HubPage.FEATURES, "Game controls")
    /** A connected controller's Back and AYN button (theme G's row on its page). */
    val PAD_SPARES = NavCard("pad:spares", NavPages.CONTROLLER_PAGE, "Back and AYN buttons")
    // ── a game's page: its app's own settings, and Game controls' popups ──
    val APP_COMBOS = NavCard("game:combos", NavPages.GAME, "Wayfinder combos")
    val APP_LIGHTS = NavCard("game:lights", NavPages.GAME, "Stick lights")
    // build 18 (B11, MORE-12): Game controls' section is "Game settings" — "More" named the Hub page too
    val GC_PLAYER1 = NavCard("gc:player1", NavPages.GAME, "Player 1", section = "Game settings")
    val GC_APP_LAYER = NavCard("gc:layer", NavPages.GAME, "Game controls in this app", section = "Game settings")
    val GC_LIGHTS = NavCard("gc:lights", NavPages.GAME, "Stick lights", section = "Performance")
    /** What a controller's change or Remove replaced (D7 / critique B5): the pills send here. */
    val GC_BRING_BACK = NavCard("gc:bringback", NavPages.GAME, "Bring back", section = "Game settings › These controls")
    /** The Thor's gyro for a game (its pill on the drawing opens it). */
    val GC_GYRO = NavCard("gc:gyro", NavPages.GAME, "Gyro")

    val all: List<NavCard> = listOf(EVERY, COMBOS, LAYER, WHICH_SCREEN, HOME_TWICE, NAMES_PRINTED, TEST_IT, PAD_GAMES, PAD_SETUP,
        HANDLES, ORDER, TV, COMBINE, PAIRS, RESTORE, SCREENSHOTS, HELP_LAYER, COMBO_LIST, YOUR_COMBOS, PANEL_AYN, PANEL_TAKES, FEATURES_GC,
        PAD_SPARES, APP_COMBOS, APP_LIGHTS, GC_PLAYER1, GC_APP_LAYER, GC_LIGHTS, GC_BRING_BACK, GC_GYRO,
        // build 18 (B3)
        BRIGHTNESS, GESTURE, WAKE2, IDLE, COLOUR, DND, AWAKE, FPS, NAVBAR, ANIMATE, VOLUME, SPEAKER, HEADEQ, BOOST, LINKED,
        THEME, OWN_COLOURS, BACKDROP, SEE_THROUGH, REFRACTION, HUB_SCREEN, RECENTS_HINT, LIMIT80, DIRECT, LID_PAUSE, LIGHTS_FOLLOW,
        KB_WHERE, KB_DPAD, UPDATES, BACKUP, A11Y,
        HELP_ACCESS,                                                       // build 18 (B11)
        THOR_NAME,                                                         // build 18 (C11)
        PAD_SEAT, PAD_TOUCHPAD,                                            // dev build 18f
        KB_SPEED,                                                          // b18h
        GC_SCREEN,                                                         // b18i (D1)
        KB_BAR,                                                            // b18i (D3)
        SEAT_NEW)                                                          // b18m

    /** dev build 17 (WP9): a card by its id — a deep link's `card` extra (an unknown id: null, ignored). */
    fun byId(id: String?): NavCard? = all.firstOrNull { it.id == id }
}

object Nav {
    const val SEP = " › "
    /** A sample route of a kind of page ("extpad:*" → "extpad:x"): what [hubParent] knows. */
    private fun route(page: String) = if (page.endsWith(":*")) page.dropLast(1) + "x" else page

    /** The page chain from the top (Home left out), [hubParent] upward: "Battery", "Sleep & standby". A kind of page ends with
     *  [name] ("Controller", "DualSense"; "Games", "that app"). */
    fun chain(page: String, name: String? = null): List<String> =
        generateSequence(hubParent(route(page))) { hubParent(it) }.takeWhile { it != HubPage.HOME }.toList().reversed()
            .map { NavPages.title(it) } + NavPages.title(page, name)

    /** A page's place: "Battery › Sleep & standby", "Controller › DualSense". */
    fun place(page: String, name: String? = null, inWayfinder: Boolean = false): String =
        ((if (inWayfinder) listOf(NavPages.title(HubPage.HOME)) else emptyList()) + chain(page, name)).joinToString(SEP)

    /** A card's place: "Controller › Where Game controls work". [from] leaves out the chain up to that page (inside Game
     *  controls: "More › Player 1"); [inWayfinder] puts "Wayfinder ›" first (a pill over a game). */
    fun path(card: NavCard, name: String? = null, from: String? = null, inWayfinder: Boolean = false): String {
        val all = chain(card.page, name) + listOfNotNull("More options".takeIf { card.inMore }, card.section) + card.title
        val start = from?.let { chain(it, name) }?.takeIf { all.take(it.size) == it }?.size ?: 0
        return ((if (inWayfinder && start == 0) listOf(NavPages.title(HubPage.HOME)) else emptyList()) + all.drop(start)).joinToString(SEP)
    }

    /** A Hub page can be opened on it — a controller's page needs which one ([key]). dev build 17 (WP9): never a card of a
     *  game's page (Game controls' popups, its app's settings: in a game no Hub page opens, K13) — a path says where. */
    fun canGo(card: NavCard, key: String? = null): Boolean =
        card.section == null && (!card.page.endsWith(":*") || (card.page == NavPages.CONTROLLER_PAGE && key != null))

    /** b18r3 (B12, r1 BUG-2): the id of the row on [page]'s parent page ([hubParent]) that opens it — what Back from a page
     *  no row opened (a deep link, a quick panel tile, an "Open Wayfinder" target) focuses there (Hub.kt hubBack). The rows
     *  draw THIS id (their `id =`; NavPathsTest pins both ends), so the two can't drift: the Combos row is the registry's
     *  "combos" while its page is "controls" — Back from a deep-linked Combos landed on The Thor (the first row). A page whose
     *  row has its own name (the More rows, Battery's Sleep row, Help's licenses row) is its own id. The Test page of the
     *  controller in hand ("padtest") has no row of its own on the Controller page: The Thor's row, the way to a Test it.
     *  b18r3 (review): a game's or an app's own Stick lights ("lights:<key>") is opened on its page by Game controls'
     *  Performance ("Colours and brightness ›"): that header control ([GameTabRules.Opener.PERF] — the page opens on Game
     *  controls). Never the app settings' "Stick lights" row ("game:lights"): drawn only on a game's page (not on an app's,
     *  from the Hub) and opening its APP's lights — Back landed one screen down on "Opens on", or on the other editor's row. */
    fun rowTo(page: String): String = when {
        page == HubPage.CONTROLS -> NavCards.COMBOS.id
        page == HubPage.EXTPADS -> NavCards.EVERY.id
        page == HubPage.PAIRS -> NavCards.PAIRS.id
        page == HubPage.THOR || page == HubPage.PADTEST -> padRow(HubPage.THOR)
        page.startsWith("extpad:") -> padRow(page.removePrefix("extpad:"))
        page.startsWith("padtest:") -> NavCards.TEST_IT.id
        page.startsWith("controls:") -> NavCards.APP_COMBOS.id
        page.startsWith("lights:") -> GameTabRules.Opener.PERF.id
        page.startsWith("remap:") || page.startsWith("play:") -> appTile(GameProfiles.pkgOf(page.substringAfter(':')))
        else -> page
    }

    /** b18r3: a controller's row on the Controller page — the Thor's ([HubPage.THOR]) or one connected controller's (its key). */
    fun padRow(key: String): String = "pad:$key"

    /** b18r3: an app's tile on the Games page (a game of it opens its app's tile's page too). */
    fun appTile(pkg: String): String = "app:$pkg"

    /** Build 18 (B7 addendum, MORE-D18): a "More options" row's value — the titles of the cards inside, lower case, as many
     *  as fit [max] characters ("glass backdrop · see-through glass · light refraction · hub screen"), then "…". Built from
     *  the registry, so the row can't promise what isn't there. */
    fun moreValue(cards: List<NavCard>, max: Int = 70): String {
        val titles = cards.map { it.title.lowercase() }
        val out = StringBuilder()
        for ((i, t) in titles.withIndex()) {
            val next = if (i == 0) t else " · $t"
            if (out.isNotEmpty() && out.length + next.length > max) return "$out…"
            out.append(next)
        }
        return out.toString()
    }
}
