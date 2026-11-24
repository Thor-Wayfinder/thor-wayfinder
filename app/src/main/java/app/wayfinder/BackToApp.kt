package app.wayfinder

/** b19 (V02): a page the quick panel opened over an app (the Stick lights and Wayfinder tiles, the compass, an "Open…" page
 *  tile), or an "Open Wayfinder: ‹page›" combo pressed over one (Q7a): one B on it goes back to that app, like Game
 *  controls' "Back to game". The ask lives in this process only: the intent carries a random nonce, so no other app can mark
 *  a page (MainActivity is exported). Main thread. */
object BackToApp {
    class Ask(val page: String, val screen: Int, val pkg: String, val at: Long, val nonce: Long)
    const val EXTRA = "back_to_app"
    @Volatile private var last: Ask? = null
    private val random by lazy { java.security.SecureRandom() }

    /** [page] is opened with the controller on [screen] showing [pkg] (null: Wayfinder's Hub, a launcher, Wayfinder): the nonce
     *  for the intent, or null (not over an app, or a Wayfinder window is in front: the page is plain Hub navigation). */
    fun ask(page: String, screen: Int?, pkg: String?, wayfinderInFront: Boolean, now: Long): Long? {
        if (!BackToAppRules.marks(pkg, screen, wayfinderInFront)) { last = null; return null }
        val n = random.nextLong().takeIf { it != 0L } ?: 1L
        last = Ask(page, screen!!, pkg!!, now, n)
        return n
    }

    /** The window's check ([nonce]: the intent's, 0 = none; [shown]: the page it shows). Never consumed: a relocation's
     *  relaunch reads the same extras again (MainActivity.relocateIfNeeded). */
    fun taken(nonce: Long, shown: String, wasInFront: Boolean, now: Long): Ask? =
        last?.takeIf { BackToAppRules.takes(it.nonce, nonce, it.page, shown, now - it.at, wasInFront) }
}

/** b19 (V02): the marked page's leave (MainActivity's backToApp), for a page that calls its own onBack (a Done): it agrees
 *  with B and the Back pill. Null: not the marked page (HubNavHost goes up its chain). */
val LocalLeave = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** b19 (V02): the rules, pure (BackToAppTest). */
internal object BackToAppRules {
    /** An ask is taken this soon after it was made (a cold start, a relocation's relaunch). */
    const val FRESH_MS = 10_000L

    /** The page is marked: opened over an app ([pkg]) on a known screen, with no Wayfinder window in front. */
    fun marks(pkg: String?, screen: Int?, wayfinderInFront: Boolean): Boolean = pkg != null && screen != null && !wayfinderInFront

    /** The window takes the mark: its nonce [got] is the one asked, it shows the page asked ([shown]; the first-run tour for
     *  the home: no), never Game controls, the ask is fresh, and the window wasn't in front when the intent came. */
    fun takes(asked: Long, got: Long, askedPage: String, shown: String, ageMs: Long, wasInFront: Boolean): Boolean =
        got != 0L && got == asked && askedPage == shown && ageMs in 0..FRESH_MS && !wasInFront &&
            !shown.startsWith("play:") && shown != HubPage.TOUR

    enum class Back { CLOSE_CONTROLS, TO_APP, UP, PLATFORM }

    /** What B does on [page] ([marked]: the marked page, null = none). Game controls first, as before; the home unmarked is
     *  Android's own Back (the window's root layer is off there). */
    fun back(page: String, marked: String?): Back = when {
        page.startsWith("play:") -> Back.CLOSE_CONTROLS
        page == marked -> Back.TO_APP
        page == HubPage.HOME -> Back.PLATFORM
        else -> Back.UP
    }

    /** The window's root Back layer is on (off: Android's Back). */
    fun rootEnabled(page: String, marked: String?): Boolean = back(page, marked) != Back.PLATFORM

    /** The Back pill's words on [page]: Game controls' own ("Back to game", "Back to ‹app›" for an app that isn't a game)
     *  on the marked page, else "Back" (and with no app name). */
    fun pill(page: String, marked: String?, gamey: Boolean, appLabel: String?): String =
        if (page != marked || appLabel == null) "Back" else GameTabRules.backLabel(true, gamey, appLabel, null)

    /** Where the controller goes after that B: [screen], as Game controls' close does. Lock on: null, the Hub's lend ends at
     *  the window's stop and its restore puts the controller on the lock's screen (no lend: the lock already holds it). */
    fun returnTo(lockOn: Boolean, screen: Int): Int? = if (lockOn) null else screen
}
