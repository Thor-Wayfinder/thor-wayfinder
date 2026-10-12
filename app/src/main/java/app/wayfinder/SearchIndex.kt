package app.wayfinder

/*
 * Build 18 (B2, B3, B4): Search — every setting and feature, by its name or by what you'd call it. Pure Kotlin (moved from
 * Hub.kt, so SearchIndexTest reads it): the entries, their places, the ranking. The page itself is Hub.kt's SearchPage.
 * - B3 (hF2 / hF20 / D7 / D8): an entry names a registry card ([NavCards]) when its page has one: its title is the card's
 *   (plus a short aside after " · "), its place is the card's path, and A opens the page ON the card (More options too).
 *   An action with a setting card ([ACTION_CARDS]) opens the card; an action alone opens Combos on its row.
 * - B4 (D9 / hF24): typed words match the start of a title's words, or — from 2 letters — of a keyword; titles first.
 */

/** What Search remembers between visits (B2, hF5): the query, the result opened, and that Back is bringing you back (the
 *  page then opens on that result, keyboard closed). Plain values: the page reads them once. */
object SearchState {
    @Volatile var query = ""
    @Volatile var lastResult: String? = null
    @Volatile var returning = false
}

/** One result. [card]: the registry card it opens (its title and place come from there); [action]: an action it is about
 *  (its line says how it is done — [Say.howTo]); [needsPad]: about connected controllers only — left out with the Thor
 *  alone (T18-09). Keep a call on one line: its keywords are its last string (SourceRulesTest lets synonyms through). */
class SearchEntry(
    val title: String, val keywords: String, val page: String,
    val action: ThorAction? = null, val card: NavCard? = null, val needsPad: Boolean = false,
) {
    /** A registry card's entry: the card's title, then [aside] after " · " ("Brightness · both, top, bottom"). */
    constructor(card: NavCard, aside: String?, keywords: String, action: ThorAction? = null, needsPad: Boolean = false) :
        this(card.title + (aside?.let { " · $it" } ?: ""), keywords, card.page, action, card, needsPad)

    /** Its place, from the registry: the card's path ("Screens › More options › Do not disturb while playing"), else its
     *  page's ("Controller › Combos"). */
    val where: String get() = card?.let { Nav.path(it) } ?: Nav.place(page)

    /** Its stable id on the Search page (Back comes back to it): its card's id, else "search:" + its title. */
    val id: String get() = card?.id ?: "search:$title"
}

/** B2: a result's stable id ([SearchEntry.id]). */
fun entryId(e: SearchEntry): String = e.id

/** Round 2 (B3): where Combos lands — [focus], a row's id; or [addOn], an action to open "Add a combo"'s list on. */
class ComboLanding(val focus: String?, val addOn: ThorAction? = null)

/** Round 2 (B3): where Combos lands for the row it is sent to ([key]: a Search result's "combo:<ACTION>", a link's way back).
 *  An action's own combo row when the page draws one ([drawn]); else the AYN button's tap or hold row when that is what does
 *  it ([aynTap] / [aynHold]: null while those rows aren't drawn — the result's line said "AYN hold"); else, when Add a combo
 *  offers the action ([offered]: its part is on), its list opens on it — one A reaches its buttons, B lands on "+ Add a combo"
 *  (it opened on the page's first control: the AYN tap's row, unrelated). Any other key is kept. Pure: SearchIndexTest. */
fun comboLanding(key: String?, drawn: (ThorAction) -> Boolean, offered: (ThorAction) -> Boolean,
                 aynTap: ThorAction?, aynHold: ThorAction?): ComboLanding {
    val a = key?.let { k -> ThorAction.values().firstOrNull { "combo:${it.name}" == k } } ?: return ComboLanding(key)
    return when {
        drawn(a) -> ComboLanding(key)
        a == aynTap -> ComboLanding("ayn:tap")
        a == aynHold -> ComboLanding("ayn:hold")
        offered(a) -> ComboLanding(null, a)
        else -> ComboLanding(key)
    }
}

/** B3 (D7): the actions that have a setting card — their entry opens the card, never Combos. */
val ACTION_CARDS: Map<ThorAction, NavCard> = mapOf(
    ThorAction.TOGGLE_KEEP_AWAKE to NavCards.AWAKE,
    ThorAction.SCREENSHOT to NavCards.SCREENSHOTS,
    ThorAction.TOGGLE_SECOND_SCREEN to NavCards.GESTURE,
)

// B4: the eight suggestions an empty search shows (named: SUGGESTIONS lists them)
private val S_BRIGHTNESS = SearchEntry(NavCards.BRIGHTNESS, "both, top, bottom", "brightness bright dim dimmer brighter light screen top bottom backlight both")
private val S_SHOTS = SearchEntry(NavCards.SCREENSHOTS, "which screen, where recordings go", "screenshot screenshots take capture picture photo print screen top bottom both stacked target record recording video folder movies gallery saved", ThorAction.SCREENSHOT)
private val S_COMBOS = SearchEntry("Combos · Home, Back and the AYN button", "buttons combo combos system shortcuts bind chord shortcut hotkey ayn tap hold long press", HubPage.CONTROLS)
private val S_GAME_CONTROLS = SearchEntry("Game controls: each game's buttons, gyro and macros", "remap mapping gyro motion aim macro turbo toggle chord game controls per game emulator buttons touching touch steam deck", HubPage.APPS)
private val S_AWAKE = SearchEntry(NavCards.AWAKE, "no screen timeout", "keep awake sleep screen on stay bottom timeout never", ThorAction.TOGGLE_KEEP_AWAKE)
private val S_DND = SearchEntry(NavCards.DND, null, "dnd do not disturb notifications quiet silent game playing focus")
private val S_LIGHTS = SearchEntry("Stick lights (RGB): colour, breathing, strobe, spectrum, screen colour", "rgb led joystick stick lights ring colour color breathing strobe spectrum rainbow screen same together sync both opposite", HubPage.LIGHTS)
private val S_TOUR = SearchEntry("Take the tour · how to use Wayfinder", "tour tutorial help guide how to start welcome intro", HubPage.TOUR)

/** B4: what an empty search shows, under "Suggestions". */
val SUGGESTIONS: List<SearchEntry> = listOf(S_BRIGHTNESS, S_SHOTS, S_COMBOS, S_GAME_CONTROLS, S_AWAKE, S_DND, S_LIGHTS, S_TOUR)

val SEARCH_INDEX: List<SearchEntry> = listOf(
    // ── Screens ──
    S_BRIGHTNESS,
    SearchEntry(NavCards.GESTURE, "turn the bottom screen off / on", "gesture three 3 finger tap swipe blank off black wake screen bottom oled battery turn", ThorAction.TOGGLE_SECOND_SCREEN),
    SearchEntry(NavCards.WAKE2, "the bottom screen", "double tap wake bottom screen off touch brush thumb"),
    SearchEntry(NavCards.IDLE, "the bottom screen", "auto off idle timer timeout sleep blank unused bottom screen"),
    SearchEntry("App pairs · two apps, one press", "pair pairs two apps both screens layout combo open together", HubPage.PAIRS),
    SearchEntry(NavCards.RESTORE, null, "put my screens back restore reboot restart boot apps reopen layout"),
    S_SHOTS,
    SearchEntry(NavCards.COLOUR, "saturation", "colour color saturation vivid oversaturated natural washed oled"),
    S_DND,
    S_AWAKE,
    SearchEntry(NavCards.FPS, "FPS", "fps frame rate framerate counter performance overlay hz size small large compact corner"),
    SearchEntry(NavCards.NAVBAR, null, "navigation bar nav bar hide hidden flicker gesture buttons"),
    SearchEntry(NavCards.ANIMATE, null, "animation slide glass transition move"),
    // ── Controller ──
    SearchEntry(NavCards.WHICH_SCREEN, "follow touch, lock, move it", "lock focus controller gamepad screen stay follow touch sticky move switch send right stick goes with game moved swap"),
    SearchEntry(NavCards.LAYER, null, "input layer where game controls work everywhere nowhere copy controller emergency home back game controls"),
    SearchEntry(NavCards.GC_SCREEN, "Game controls", "game controls other screen second screen bottom top pause paused pauses keep running resume emulator eden"),
    SearchEntry("Test it · stick drift · deadzone", "test controller drift deadzone stick joystick calibrate trigger buttons check", HubPage.PADTEST),
    SearchEntry("Connected controllers (Joy-Con, Bluetooth, USB) · their pages", "joycon joy-con bluetooth usb controller gamepad xbox playstation 8bitdo pro controller pair player remap set up buttons", HubPage.CONTROLLER),
    SearchEntry(NavCards.NAMES_PRINTED, null, "button names xbox nintendo printed on the thor a b x y labels hints"),
    SearchEntry(NavCards.HOME_TWICE, "AYN's setting", "home two presses double press accident accidental twice ayn"),
    // build 18 (C11, T18-10): the name games list for the Thor's controller (on the Thor's page)
    SearchEntry(NavCards.THOR_NAME, "the Thor's controller", "odin controller name wayfinder emulator eden list"),
    // T18-09: one entry for the Thor's face buttons (was two: the styles, and the PlayStation symbols)
    SearchEntry("Face buttons: Nintendo, Xbox or PlayStation", "face buttons nintendo xbox playstation layout abxy swap a b x y button names ps cross circle square triangle symbols prompts", HubPage.THOR),
    // dev build 15 (WP2, D1): each controller's own face (its page) — Automatic by default. T18-09: with a controller only
    SearchEntry("A controller's face buttons: Automatic, like the Thor, Nintendo, Xbox or PlayStation", "face buttons controller dualsense ps5 playstation xbox nintendo joy-con automatic like the thor cross circle confirm back swap a b button names", HubPage.CONTROLLER, needsPad = true),
    SearchEntry("Remove a controller's own controls", "delete remove reset own controls controller every game undo", HubPage.CONTROLLER, needsPad = true),
    SearchEntry("A controller's Back and AYN buttons", "back ayn button touchpad mute capture share select start spare missing set up", HubPage.CONTROLLER, needsPad = true),
    // dev build 18f: two switches on a controller's page (its card needs which controller: the Controller page, as above)
    SearchEntry("Plays as the Thor's controller (a connected controller)", "seat same as the thor odin emulator eden player 1 one player no mapping again remap merge both drive copy hidden", HubPage.CONTROLLER, needsPad = true),
    SearchEntry("Touchpad moves the pointer (PlayStation controllers)", "touchpad trackpad mouse pointer cursor click tap scroll two fingers dualsense dualshock ps4 ps5 laptop", HubPage.CONTROLLER, needsPad = true),
    SearchEntry(NavCards.ORDER, "who is player 1", "player order first one two thor connected controller player 1", needsPad = true),
    // b18m: the global switch (Settings for every controller) — with a controller, as the other controller entries
    SearchEntry(NavCards.SEAT_NEW, null, "new controller first time connect plug mapping map again remap bind rebind dolphin eden emulator same as the thor seat player 1", needsPad = true),
    // ── Combos: the page's own entries, and the actions without a setting card (A opens Combos on their row) ──
    S_COMBOS,
    SearchEntry("Add a combo · two-button combos held or double-pressed", "add combo new shortcut hotkey chord hold double triple two buttons home back", HubPage.CONTROLS),
    SearchEntry("AYN's own drawer (keep it with the quick panel)", "ayn drawer odin menu fps dial hold double both panel assistant", HubPage.CONTROLS),
    SearchEntry("Swipe with the controller (Shorts, web pages)", "swipe scroll shorts tiktok reels touch finger gesture up down next video", HubPage.CONTROLS),
    // round 2: the topics that are one action land on it as the actions below do ([comboLanding])
    SearchEntry("Record the screen with a combo", "record video screen recording capture clip combo hotkey", HubPage.CONTROLS, ThorAction.RECORD_SCREEN),
    SearchEntry("Close the other screen's app", "close other screen app bottom top kill quit combo", HubPage.CONTROLS, ThorAction.CLOSE_OTHER),
    SearchEntry("Move / swap apps between the screens", "move switch apps screens top bottom send swap", HubPage.CONTROLS, ThorAction.SWAP_OR_SEND),
    SearchEntry("Turn the top screen off / on", "blank off black wake screen top oled battery turn off", HubPage.CONTROLS, ThorAction.TOGGLE_TOP_SCREEN),
    SearchEntry("Nothing (a combo that does nothing, e.g. Home's tap)", "nothing block disable ignore home tap button does nothing", HubPage.CONTROLS, ThorAction.NOTHING),
    SearchEntry("Gyro on / off (an action for a combo or a long press)", "gyro motion aim tilt toggle pause long press hold", HubPage.CONTROLS, ThorAction.GYRO_TOGGLE),
    SearchEntry("Guide & notes (the game's guide and your notes)", "guide notes walkthrough map single screen overlay companion", HubPage.CONTROLS, ThorAction.GUIDE),
    SearchEntry("Mouse mode (AYN's virtual mouse)", "mouse cursor pointer virtual stick ayn gamenative web", HubPage.CONTROLS, ThorAction.AYN_MOUSE),
    SearchEntry("Sleep (both screens off)", "sleep power off screens standby suspend lock button", HubPage.CONTROLS, ThorAction.SLEEP),
    SearchEntry("Close background apps", "clear close kill recent memory ram background", HubPage.CONTROLS, ThorAction.CLEAR_BACKGROUND),
    SearchEntry("Recent apps", "recents multitask overview switcher", HubPage.CONTROLS, ThorAction.RECENTS),
    // ── Games ──
    S_GAME_CONTROLS,
    SearchEntry("Games (each app's settings)", "per app profile game application app profiles emulator", HubPage.APPS),
    SearchEntry("Game controls off for one game (PokeMMO, native games)", "input layer off one app game pokemmo castlevania native copy controller not working per app", HubPage.APPS),
    SearchEntry("The controller goes with one game when it moves (per game)", "follow move swap controller focus per game app stays goes with", HubPage.APPS),
    SearchEntry("Share or import game controls", "share import export send file controls profile mapping", HubPage.APPS),
    SearchEntry("Wayfinder combos for one app (usual / custom / off)", "per app buttons combos controls custom off game", HubPage.APPS),
    SearchEntry("Bottom screen while an app plays (per app)", "per app second screen blank keep on game bottom", HubPage.APPS),
    SearchEntry("Open an app on a chosen screen (per app)", "route open launch always top bottom screen per app routing start", HubPage.APPS),
    // ── Battery, Sleep ──
    SearchEntry(NavCards.LIMIT80, null, "battery charge limit 80 percent charging odin tools"),
    SearchEntry(NavCards.DIRECT, null, "bypass direct power charging separation plugged battery odin tools"),
    SearchEntry("Battery: what the Thor draws now, time left", "battery watts power draw time left hours drain usage", HubPage.BATTERY),
    SearchEntry("Sleep & standby: Wi-Fi / Bluetooth off, lid, battery drain", "sleep standby battery drain wifi bluetooth lid case closed hall deep sleep vpn sync dock", HubPage.SLEEP),
    SearchEntry(NavCards.LID_PAUSE, "music and videos", "lid close pause music video audio case"),
    // ── Quick panel ──
    SearchEntry("Quick panel shortcuts (choose and arrange)", "quick panel drawer shortcuts tiles customize customise choose order arrange grid ayn wifi bluetooth airplane dnd rotate location battery saver dark night dim invert record hotspot cast controller style xbox trigger landscape vibration charge limit direct power", HubPage.PANEL),
    SearchEntry(NavCards.PANEL_AYN, null, "ayn button drawer quick panel menu settings performance fan refresh 120hz stats"),
    SearchEntry(NavCards.PANEL_TAKES, null, "quick panel controller touch only keep playing game focus takes"),
    SearchEntry("Quick panel tile that opens an app, a pair or a page", "app tile shortcut launch open app pair page quick panel favourite favorite", HubPage.PANEL),
    // ── Keyboard ──
    SearchEntry("Wayfinder Keyboard (turn on, languages)", "keyboard ime type typing languages layout azerty qwertz french accents input", HubPage.KEYBOARD),
    SearchEntry(NavCards.KB_WHERE, "the keyboard on the bottom screen", "keyboard placement bottom screen other screen dual type"),
    SearchEntry(NavCards.KB_DPAD, "Wayfinder Keyboard", "dpad d-pad keys keyboard typing cursor stick"),
    SearchEntry(NavCards.KB_BAR, "Wayfinder Keyboard", "black bar keyboard bar navigation bar nav bar strip thin bar hide typing screen top screen eden yuzu name box submits empty"),
    SearchEntry(NavCards.KB_SPEED, "the trackpad", "mouse sensitivity speed cursor trackpad touch slower faster"),
    SearchEntry("Keyboard & mouse for games", "deck trackpad mouse cursor pc keys f1 esc numpad emulator retroarch hotkeys media video volume game keys send keys", HubPage.KEYBOARD),
    SearchEntry("My pad: your own buttons (snippets, key combos)", "my pad custom buttons snippet text shortcut combo macro ctrl", HubPage.KEYBOARD),
    SearchEntry("Trackpad Direct mode (touch = the game's screen)", "direct touch trackpad swipe touchscreen game screen mouse deck megingiard pointer dot hide cursor invisible", HubPage.KEYBOARD),
    // ── More: Sound, Stick lights, Appearance, Hints, Features, Help ──
    SearchEntry(NavCards.VOLUME, "both, top, bottom", "volume sound audio both screens bottom speaker balance louder quieter linked"),
    SearchEntry(NavCards.SPEAKER, "EQ", "speaker audio sound fix eq equalizer dsp loud bass treble tuning widener stereo"),
    SearchEntry(NavCards.HEADEQ, "per output", "headphones earbuds airpods bluetooth usb dac jack wired eq equalizer bass treble curve preset per output jamesdsp"),
    SearchEntry(NavCards.BOOST, "louder than the maximum", "volume boost booster louder amplify gain loud"),
    SearchEntry(NavCards.LINKED, null, "volume linked both screens top bottom buttons same jamesdsp widener stereo"),
    S_LIGHTS,
    SearchEntry(NavCards.LIGHTS_FOLLOW, "stick lights", "lights brightness follow dim screen auto rgb led night"),
    SearchEntry(NavCards.THEME, "light, dark, black, your colours", "theme dark light mode colours colors night black oled pure amoled flat custom own"),
    SearchEntry(NavCards.OWN_COLOURS, "flat look, colour codes", "colour color custom hex picker flat theme background text accent card"),
    SearchEntry(NavCards.BACKDROP, "what's behind or aurora", "background wallpaper blur glass aurora transparent backdrop behind"),
    SearchEntry(NavCards.SEE_THROUGH, "transparency, blur", "transparent transparency blur glass see through opaque background frost"),
    SearchEntry(NavCards.REFRACTION, null, "glass refraction distortion lens light liquid highlight performance"),
    SearchEntry(NavCards.HUB_SCREEN, "which screen Wayfinder opens on", "hub screen top bottom placement deck opens"),
    SearchEntry("Hints & pop-ups · turn them off", "hints tips popups pop-ups tooltips notifications overlay off your controls", HubPage.HINTS),
    SearchEntry(NavCards.RECENTS_HINT, "smaller, off, corner", "recents hint compact small corner hide recent apps"),
    SearchEntry(NavCards.COMBO_LIST, null, "hint tooltip cheat sheet popup pop-up hold home back ayn list off"),
    SearchEntry(NavCards.YOUR_COMBOS, null, "your controls sheet bottom screen popup pop-up combos companion off"),
    SearchEntry("Features · make Wayfinder simpler (lite)", "features lite simple simpler minimal just swap screens turn off parts disable", HubPage.FEATURES),
    S_TOUR,
    SearchEntry(NavCards.A11Y, "turn Wayfinder off or on", "accessibility service permission setup enable turn off disable stop pause quit wayfinder game refuses blocked anticheat cheat detection"),
    SearchEntry(NavCards.UPDATES, "once a day", "update updates new version github check release"),
    SearchEntry(NavCards.BACKUP, "and restore your settings", "backup back up restore export import save settings file new thor reinstall"),
    // round 2 (B20, FTU-18): the setup check's own words on Help (its "Battery optimisation" row is gone: a keyword now)
    SearchEntry("Android won't put it to sleep · keep Wayfinder running", "battery optimisation optimization background killed stopped sleep allow", HubPage.HELP),
    SearchEntry(NavCards.HELP_ACCESS, "moving apps, brightness, sleep", "root shizuku backend pservice status system access selinux"),
)

/** [s]'s words, lower case: letters and digits ("Wi-Fi" → "wi", "fi"; "80 %" → "80", "%"). */
private fun searchWords(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}%]+")).filter { it.isNotEmpty() }

/** B4 (D9 / hF24): the entries matching [q] — each typed word starts a word of the title, or (from 2 letters) of a keyword
 *  — best first: the title starts with the query (3), every word starts a title word (2), a keyword (1); then the shorter
 *  title. [hasPad]: a controller is connected (T18-09: else the controller-only entries are left out). Round 2 (B3):
 *  [allows] = a combo's action is in use with the parts on now ([Features.allows]) — an action alone whose part is off is left
 *  out, as Add a combo's list leaves it out (nothing on Combos to land on). An empty query matches nothing (the page shows
 *  [SUGGESTIONS]). */
fun search(q: String, hasPad: Boolean = true, allows: (ThorAction) -> Boolean = { true }): List<SearchEntry> {
    val typed = searchWords(q)
    if (typed.isEmpty()) return emptyList()
    val whole = q.trim().lowercase()
    return SEARCH_INDEX.asSequence()
        .filter { hasPad || !it.needsPad }
        .filter { e -> e.card != null || e.action?.let(allows) != false }
        .mapNotNull { e ->
            val title = searchWords(e.title)
            val keys = searchWords(e.keywords)
            fun inTitle(w: String) = title.any { it.startsWith(w) }
            if (!typed.all { w -> inTitle(w) || (w.length >= 2 && keys.any { it.startsWith(w) }) }) null
            else e to when {
                e.title.lowercase().startsWith(whole) -> 3
                typed.all { inTitle(it) } -> 2
                else -> 1
            }
        }
        .sortedWith(compareByDescending<Pair<SearchEntry, Int>> { it.second }.thenBy { it.first.title.length })
        .map { it.first }.toList()
}
