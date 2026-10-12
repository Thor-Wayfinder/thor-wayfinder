package app.wayfinder

/*
 * dev build 16 (WP6): Game controls' tabs — the rules, apart from the page (pure Kotlin: app/src/test loads it). One page
 * for every controller: which tabs in which order, which one shows, what each one's
 * status chip says and offers, when the shortcuts work, which controller an intent may name. What a controller plays is
 * never worked out here: it comes from WP4's resolver ([PadControls.tab], [PadRules]). The sentences are in [GameWords]
 * (Words.kt since dev build 17, WP9: every word in one file).
 */

/** One controller as the tab row needs it (the Thor: [key] null). [slot] = its hidraw number (ties in the player order);
 *  [docked]: it plays as the Thor's controller now. */
data class TabPad(val key: String?, val name: String, val kind: ControllerKind, val number: Int = 0, val slot: Int = -1,
                  val away: Boolean = false, val docked: Boolean = false)

/** What a chip pill does: "Make its own" / "Its own for <page> again" ([PadControls.makeOwn]), "Only for <game>" (the
 *  same, over its app's or every-game own), "Use the Thor's again" … ([PadControls.useBelow], D7: kept, switched off —
 *  build 18, GC1: an untouched copy the page made from the Thor's is deleted instead, [GameTabRules.useThorsAgain]). */
enum class ChipAction { MAKE_OWN, ONLY_FOR, USE_BELOW }
data class ChipPill(val action: ChipAction, val label: String)
/** A pad tab's status chip: its line and its pills. */
data class Chip(val text: String, val pills: List<ChipPill>)

/** Build 18 (GC6, T18-01): Game controls for an app as the page tells it — on, off, or off until its first change (a game not
 *  set up yet in "Games you set up": that change sets it up and starts them). */
enum class LayerState { ON, PENDING, OFF }

object GameTabRules {
    /** The controllers by player number (none yet = last), then by slot, numerically (hidraw10 after hidraw9); two with
     *  one key (identical, no serial: ExtRules) are one tab — the first. The Thor's tab goes before them (the page). */
    fun <T> order(items: List<T>, number: (T) -> Int, slot: (T) -> Int, key: (T) -> String): List<T> =
        items.sortedWith(compareBy<T>({ number(it).takeIf { n -> n > 0 } ?: Int.MAX_VALUE }, { slot(it) })).distinctBy(key)

    /** L1 ([dir] −1) / R1 (+1): the next tab, round. [current] not listed: the first (R1) or the last (L1). */
    fun step(keys: List<String?>, current: String?, dir: Int): String? {
        if (keys.isEmpty()) return current
        val i = keys.indexOf(current)
        if (i < 0) return if (dir > 0) keys.first() else keys.last()
        return keys[Math.floorMod(i + dir, keys.size)]
    }

    /** The tab shown: [want] while it is listed, else the Thor's (null). [want] itself is kept by the page: the controller
     *  that opened it comes back to its tab when it reconnects in the session. */
    fun shown(keys: List<String?>, want: String?): String? = want?.takeIf { it in keys }

    /** D9 (+ WP6 S6 / S7): a shortcut works only where it means something — L1 / R1 (switch tabs) on the drawing or the tab
     *  row, X / Y (face buttons, reset) on the drawing; never behind a popup or while a press is captured. False = the
     *  page's own handling (a header pill pressed: nothing done behind it). */
    fun shortcut(b: ThorButton, onDrawing: Boolean, onTabs: Boolean, popup: Boolean, capturing: Boolean, tabs: Int): Boolean = when (b) {
        ThorButton.L1, ThorButton.R1 -> !popup && !capturing && tabs > 1 && (onDrawing || onTabs)
        ThorButton.X, ThorButton.Y -> !popup && !capturing && onDrawing
        else -> false
    }

    /** The intent's pad=<key> (any app can send an intent): a controller key as ExtPads makes them (critique M13). */
    fun padKeyOk(k: String?): Boolean = k != null && ExtRules.KEY.matches(k)

    /** D7: "Use the Thor's again" … is offered only for own controls at the page's own level (an app's or every game's
     *  are switched off from their own page — from a game, "Only for <game>" instead). */
    fun canUseBelow(src: PadSource, base: String) = src is PadSource.Own && src.level == PadRules.levelOf(base)

    /** Build 18 (GC1, decision H4): own controls [copy] still are the Thor's controls [thorsNow] — they differ at most in
     *  their face pick (and the mark of who made them). Compared as a pad profile is stored ([PadRules.forPad], its JSON). */
    fun untouched(copy: AppConfig, thorsNow: AppConfig): Boolean {
        fun bare(c: AppConfig) = AppConfig.fromJson(PadRules.forPad(c).copy(face = null, madeFrom = null).toJson())
        return bare(copy) == bare(thorsNow)
    }

    /** Build 18 (GC1, decision H4): "Use the Thor's again" on own entry [key] — a copy the page made from the Thor's by itself
     *  ([MadeFrom.THOR]) and [untouched] since is deleted, with no Bring back entry (true); any other is switched off and
     *  kept (D7, false). [thorsNow]: the Thor's controls there now ([PadControls.thorsCopy]). */
    fun useThorsAgain(s: PadStore, key: String, thorsNow: AppConfig): Boolean {
        val c = s.json(key)?.let { AppConfig.fromJson(it) } ?: return false
        if (c.madeFrom == MadeFrom.THOR && untouched(c, thorsNow)) { s.write(key, null, off = false); return true }
        PadRules.setEnabled(s, key, false)
        return false
    }

    /**
     * A pad tab's status chip — [t] is [PadControls.tab]'s answer, the engine's very one. [everyEditor]: the controller's
     * every-game editor (its page owns the mode: no pills); [madeFrom]: what an edit in this session made its own from;
     * [label]: the page (the game, the app, "every game"); [layerOff]: the input layer is off for the app (critique m3:
     * the Thor's controls don't reach it there).
     */
    fun chip(t: PadTab, everyEditor: Boolean, madeFrom: PadSource?, appLabel: String, label: String,
             away: Boolean = false, layerOff: Boolean = false): Chip {
        val src = t.source
        val pills = ArrayList<ChipPill>()
        val again = ChipPill(ChipAction.MAKE_OWN, GameWords.ownAgain(label))
        val text = when (src) {
            PadSource.Thor, PadSource.Plain -> {
                pills += if (t.switchedOff) again else ChipPill(ChipAction.MAKE_OWN, "Make its own")
                (if (src == PadSource.Thor) GameWords.usesThors(appLabel, layerOff) else "Plain buttons") +
                    (if (t.switchedOff) " · its own for $label are kept (off)" else "")
            }
            is PadSource.Own -> {
                val atPage = src.level == PadRules.levelOf(t.base)
                if (atPage) t.below?.let { pills += ChipPill(ChipAction.USE_BELOW, GameWords.useBelow(it, appLabel)) }
                else pills += if (t.switchedOff) again else ChipPill(ChipAction.ONLY_FOR, "Only for $label")
                "Its own · " + GameWords.where(src.level, appLabel) +
                    (if (atPage) madeFrom?.let { " (made from ${GameWords.from(it, appLabel)})" } ?: "" else "")
            }
        }
        // dev build 18f: by its switch (undocked), these apply when that's off
        val line = (if (!t.docked) "" else if (t.seated) "Plays as the Thor's controller — these apply when that's off · "
            else "Docked: plays as the Thor's controller — these apply when undocked · ") + text +
            (if (away) " · away (its place is kept)" else "")
        return Chip(line, if (everyEditor) emptyList() else pills)
    }

    /** critique G1: a pad tab's face pick — Usual (its page's face: null) or an explicit one, stored even when it is the
     *  usual one (an explicit choice never falls back silently). PlayStation = the Xbox positions. */
    fun padFace(c: FaceChoice): FaceStyle? = when (c) {
        FaceChoice.NINTENDO -> FaceStyle.NINTENDO
        FaceChoice.XBOX, FaceChoice.PLAYSTATION -> FaceStyle.XBOX
        else -> null
    }
    /** The Thor's tab, as before: picking the one all apps use = following all apps (null). */
    fun thorFace(f: FaceStyle, usual: FaceStyle): FaceStyle? = f.takeIf { it.xboxPositions != usual.xboxPositions }

    /** Build 18 (GC8, gd-D03 / FTU-12): the face pills of every tab — Usual (this game without a face of its own: [usual]'s
     *  positions), then the one style whose positions differ ([kind]: a PlayStation controller's is named PlayStation). Two
     *  pills never give the same positions (a DualSense offered "PlayStation" beside its usual PlayStation). */
    fun faceOptions(usual: FaceStyle, kind: ControllerKind): List<FaceChoice> =
        listOf(FaceChoice.AUTO, if (usual.xboxPositions) FaceChoice.NINTENDO
            else if (kind == ControllerKind.PLAYSTATION) FaceChoice.PLAYSTATION else FaceChoice.XBOX)

    /** The usual face's name in a tab's names ([ps]: PlayStation's — the Xbox positions are called PlayStation). */
    fun faceUsualName(usual: FaceStyle, ps: Boolean): String =
        if (usual.xboxPositions) (if (ps) FaceStyle.PLAYSTATION.label else FaceStyle.XBOX.label) else FaceStyle.NINTENDO.label

    /** A face pill's words: "Usual (Nintendo)" ([compact], the bottom screen's header: "Usual" — no room), "Nintendo", "Xbox" /
     *  "PlayStation" ([ps] names; compact "PS"). */
    fun faceLabel(c: FaceChoice, usualName: String, ps: Boolean, compact: Boolean): String = when (c) {
        FaceChoice.AUTO -> Say.usual(if (compact) null else usualName)
        FaceChoice.NINTENDO -> FaceStyle.NINTENDO.label
        else -> if (ps) (if (compact) "PS" else FaceStyle.PLAYSTATION.label) else FaceStyle.XBOX.label
    }

    /** Every word a tab's face pills can show in [ps] names (both usuals): the header measures the widest, so the pills keep
     *  one width on every tab (gd-D05: L1 / R1 never reflow the header). */
    fun faceLabelsAll(ps: Boolean): List<String> = listOf(FaceStyle.NINTENDO, FaceStyle.XBOX).flatMap { u ->
        faceOptions(u, ControllerKind.THOR).map { faceLabel(it, faceUsualName(u, ps), ps, compact = false) }
    }

    /** Build 18 (GC6, T18-01): Game controls in an app — its own setting ([appLayer]: true on, false off) first, else the mode;
     *  "Games you set up" on an app not set up yet ([setUp]) is PENDING: its first change sets it up and turns them on (it
     *  said "Game controls are off", in red). The engine's own answer stays [PadLayerCtl.need]. */
    fun layer(mode: PadLayerCtl.Mode, appLayer: Boolean?, setUp: Boolean): LayerState = when {
        appLayer == false -> LayerState.OFF
        appLayer == true -> LayerState.ON
        mode == PadLayerCtl.Mode.NOWHERE -> LayerState.OFF
        mode == PadLayerCtl.Mode.EVERYWHERE -> LayerState.ON
        setUp -> LayerState.ON
        else -> LayerState.PENDING
    }

    /** Where Back goes, in words: [backTo] (a controller's every-game editor: "Back to DualSense"), the game, its app, Games
     *  (dev build 17, theme H: "Back to Games" — it said "Games", like a title). */
    fun backLabel(inGame: Boolean, gamey: Boolean, appLabel: String, backTo: String?): String =
        backTo ?: if (!inGame) "Back to Games" else if (gamey) "Back to game" else "Back to $appLabel"

    /** Device pass (P1-3): the header controls a popup opens from — the title (Which game?), Performance, Game settings, the
     *  changes count. A popup closed gives the focus back to its opener (GC2); a popup's row that leaves the page (Which
     *  game? › All apps) records its opener's [id] as the row the Hub comes back to ([app.wayfinder.ui.ReturnFocus]), the
     *  row itself being gone with the popup: back on the page, the opener has the focus. Ids, never words; never a "game:"
     *  row ([GamePageRules.ROW_PREFIX]): the page opens on Game controls. b18r3 (review): Performance › "Colours and
     *  brightness ›" too (Stick lights) — and [PERF] is the way back from a Stick lights no row opened ([Nav.rowTo]). */
    enum class Opener(val id: String) { TITLE("gc-open:title"), PERF("gc-open:perf"), MORE("gc-open:more"), CHANGES("gc-open:changes") }

    /** The opener [returnTo] (the row the page comes back to) names, null = none of them (a row, a page, nothing). */
    fun openerOf(returnTo: String?): Opener? = Opener.values().firstOrNull { it.id == returnTo }
}
