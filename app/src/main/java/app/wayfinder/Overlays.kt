package app.wayfinder

import android.content.Context

/**
 * dev build 17 (WP10): the ONE way to put a cue on screen — a pill, a tip or a held list.
 * Callers say what it is and where it comes from ([CueOrigin]); the host ([FocusCue], the service's) decides the screen,
 * how long it stays and what it covers ([CueSlots]). A new cue never silently destroys another one. Any thread.
 */
object Overlays {
    /** A tip shown this many times without being seen (2 s on screen) counts as seen: no nagging. */
    const val TIP_MAX_SHOWS = 3
    /** A tip stays at least this long: it must be seen ([CueSlots.TIP_SEEN_MS]) before a button dismisses it. */
    private const val TIP_MIN_MS = 3000L

    /** The service's host — set in onServiceConnected, cleared only by the instance that set it (a rebind creates the new
     *  service before the old one's onDestroy). */
    interface Host {
        /** The screen the controller works now (the service's one rule: the lock's target, else the focus). */
        fun controllerDisplay(): Int
        fun liveDisplays(): Set<Int>
        fun post(c: Cue)
        fun release(key: String)
        fun anyButton()
        fun stash()
        fun unstash()
        /** A tip a button can dismiss is up. */
        val tipUp: Boolean
    }
    @Volatile var host: Host? = null

    /** The screen a cue from [o] goes to — [CueWhere.of] on the ONE controllerDisplay(). */
    fun where(o: CueOrigin): Int = host?.let { CueWhere.of(o, it.controllerDisplay(), it.liveDisplays()) } ?: 0

    /** A short message, for as long as its words take to read. [group]: pills of one group replace each other (brightness
     *  steps, "controller on …"); default: the text itself. */
    fun pill(text: String, origin: CueOrigin, group: String? = null) {
        val h = host ?: return
        val t = plain(text, origin)
        // build 18 (E9): a pill from Wayfinder's own window sits at the bottom (CueSpot) — not over its header
        h.post(Cue(CueKind.PILL, where(origin), CueContent.Line(t), group ?: t, CueTiming.readMs(t), fromWindow = origin is CueOrigin.Window))
    }

    /** A one-time tip, flag [id] in thor_settings (true = seen: written once it has been on screen 2 s, by the host). False:
     *  seen already, or no service (try again next time). */
    fun tip(ctx: Context, id: String, origin: CueOrigin, title: List<String>, cells: List<Pair<String, String>>): Boolean {
        val h = host ?: return false
        val prefs = ctx.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(id, false)) return false
        if (prefs.getInt("${id}_n", 0) >= TIP_MAX_SHOWS) { prefs.edit().putBoolean(id, true).apply(); return false }
        val content = CueContent.Table(title.map { plain(it, origin) }, cells.map { (b, w) -> plain(b, origin) to plain(w, origin) })
        h.post(Cue(CueKind.TIP, where(origin), content, id, CueTiming.readMs(content.words()).coerceAtLeast(TIP_MIN_MS)))
        return true
    }

    /** A list kept up while something is held ([token]: [release] takes it down; [Held.maxMs] at most). Its controller
     *  ([CueOrigin.Press]; else 0) keys it (critique K12). [at] / [compact] / [footer] as [CueContent.Table]. False: no service. */
    fun held(token: Held, origin: CueOrigin, title: List<String>, cells: List<Pair<String, String>>, at: Int = 0,
             compact: Boolean = false, footer: List<Pair<String, String>> = emptyList()): Boolean {
        val h = host ?: return false
        val src = (origin as? CueOrigin.Press)?.src ?: 0
        h.post(Cue(CueKind.HELD, where(origin), CueContent.Table(title.map { plain(it, origin) },
            cells.map { (b, w) -> plain(b, origin) to plain(w, origin) }, at, compact,
            footer.map { (b, w) -> plain(b, origin) to plain(w, origin) }), token.key(src), token.maxMs))
        return true
    }

    /** Controller [src]'s [token] list let go — never another controller's, a tip or a pill. */
    fun release(token: Held, src: Int = 0) { host?.release(token.key(src)) }

    /** Any button pressed on any controller (the service's key paths): a tip that has been read goes. Cheap unless one is up. */
    fun anyButton() { host?.takeIf { it.tipUp }?.anyButton() }

    /** A screenshot: the cues off screen for the capture, kept ([unstash] puts them back). */
    fun stash() { host?.stash() }
    fun unstash() { host?.unstash() }

    /** Build 18 (A7): a cue is View text — a button token ([Say.key]) can't be drawn there: its button's name instead, in the
     *  names of the controller the cue comes from (a press: that controller's; anything else: the one in hand). */
    private fun plain(s: String, o: CueOrigin): String =
        if (Say.KEY_OPEN !in s) s else Say.plain(s, (o as? CueOrigin.Press)?.let { ButtonNames.lookOfSrc(it.src) } ?: ButtonNames.handLook)

    /** The quick panel's tiles: the panel's screen while it is open, else the controller's. */
    fun panelOrigin(): CueOrigin = QuickPanelWindow.displayId?.let { CueOrigin.Window(it) } ?: CueOrigin.Press(ButtonNames.handSrc)
}
