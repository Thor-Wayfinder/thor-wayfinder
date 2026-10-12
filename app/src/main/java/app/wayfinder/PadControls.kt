package app.wayfinder

import android.content.Context

/**
 * dev build 16 (WP4): a connected controller's controls — the resolver ([PadRules.resolve]) with the app's stores, and
 * every change of "its own vs the Thor's" (D3, D7, theme H). The engine ([ExtPadsCtl.refreshOwn]), Game controls' tabs
 * ([tab]) and the controller's page ([mode]) all read this: nothing else works out what a controller plays.
 * A base is a game "pkg#id", an app or every game "*" (a page always passes one); null only from the engine (no game app
 * has the controllers: the Thor's, neutral — X3).
 */
object PadControls {
    /** The controller page's "In games": like the Thor's · its own (every game) · plain — a pointer, never a delete. */
    enum class Mode { THOR, OWN, PLAIN }

    private fun plain(ctx: Context, pad: String) = !ExtPadsCtl.follows(ctx, pad)

    /** What [pad] plays in [base] now. [docked]: it plays as the Thor's controller (X4). */
    fun resolve(ctx: Context, base: String?, pad: String, docked: Boolean = false): PadSource {
        PadProfiles.init(ctx)
        return PadRules.resolve(base, pad, PadProfiles::enabled, plain(ctx, pad), docked)
    }

    /** What the Thor plays in [base] (GameProfiles.activeKey's rule): its game profile, else its app's; every game:
     *  nothing (the Thor has no every-game controls). critique m1: a game without a Thor profile plays its app's. */
    fun thorConfigFor(base: String): AppConfig = when {
        base == PadRules.EVERY -> AppConfig()
        '#' in base -> GameProfiles.get(base)?.let { Profiles.get(base) } ?: AppConfigStore.get(GameProfiles.pkgOf(base))
        else -> AppConfigStore.get(base)
    }

    /** What [pad] uses at [base] NOW, as a pad profile — D3's copy, "Make its own", what its tab draws: the Thor's controls
     *  (no face: its page's face applies — build 15 critique M1; no Thor gyro), its own at a more general level (face,
     *  controls, Back to the game), or nothing (plain). critique m3: the Thor's controls reach it only where the input layer
     *  runs — where the layer is off for that app it plays plain buttons, and that is what is copied. */
    fun copyOf(ctx: Context, base: String, pad: String): AppConfig = when (val src = resolve(ctx, base, pad)) {
        is PadSource.Own -> PadProfiles.get(src.key).let { AppConfig(face = it.face, remap = it.remap, backToGame = it.backToGame) }
        PadSource.Plain -> AppConfig()
        PadSource.Thor -> thorsCopy(base)
    }
    /** The Thor's controls at [base] as a controller plays them ([copyOf]'s Thor branch): none where the input layer is off
     *  for that app (critique m3). Build 18 (GC1): what an automatic copy is compared with. */
    fun thorsCopy(base: String): AppConfig =
        if (base != PadRules.EVERY && PadLayerCtl.need(GameProfiles.pkgOf(base)) == false) AppConfig()
        else AppConfig(remap = thorConfigFor(base).remap?.forThor()?.forPad()?.takeIf { !it.isEmpty })

    /** D3: the key an edit on [pad]'s tab at [base] goes to — its own at the level in force, else a copy of what the tab
     *  drew saved at [base]'s level first (a switched-off one there is kept in thor_padprof_prev: critique B5). Callers
     *  then apply their change with [Profiles.update]. Build 18 (GC1): [madeFrom] marks a copy made now (THOR: the page made
     *  it from the Thor's by itself — [useBelow] may delete it later). */
    fun ownForEdit(ctx: Context, base: String, pad: String, madeFrom: MadeFrom? = null): String {
        PadProfiles.init(ctx)
        return PadRules.ownForEdit(PadProfiles, base, pad, plain(ctx, pad)) { copyOf(ctx, base, pad).copy(madeFrom = madeFrom) }
    }
    /** "Make its own" / "Its own for <page> again" / "Only for <game>": a switched-off one at [base]'s level comes back as
     *  it was (D7); else a copy of what it uses now. */
    fun makeOwn(ctx: Context, base: String, pad: String): String {
        PadProfiles.init(ctx)
        return PadRules.makeOwn(PadProfiles, base, pad) { copyOf(ctx, base, pad) }
    }
    /** D7 "Use the Thor's again" (the words come from [PadTab.below]): switched off, kept. */
    fun useBelow(key: String) = PadProfiles.setEnabled(key, false)
    /** The chip's "Use the Thor's again" … ([below]: what it leaves). Build 18 (GC1, decision H4): over the Thor's, a copy the
     *  page made from them by itself ([MadeFrom.THOR]) and changed only in its face is deleted, kept nowhere (nothing of the
     *  user's but that face pick: no "kept (off)" left behind) — true; anything else is D7's: switched off, kept. */
    fun useBelow(ctx: Context, key: String, base: String, below: PadSource?): Boolean {
        PadProfiles.init(ctx)
        if (below == PadSource.Thor) return GameTabRules.useThorsAgain(PadProfiles, key, thorsCopy(base))
        useBelow(key); return false
    }
    /** D7 "Remove its own controls" (two presses on the page): gone — kept in thor_padprof_prev. */
    fun remove(key: String) = PadProfiles.remove(key)
    /** B5 "Bring back": the newest of what edits or "Remove" replaced at [key], back — what is there goes to the end of the
     *  kept ones (dev build 16 review WP4-1: each press one further back, nothing dropped). */
    fun bringBack(key: String): Boolean = PadProfiles.bringBack(key)
    fun hasPrevious(key: String) = PadProfiles.previous(key).isNotEmpty()
    /** How many are kept at [key] (dev build 16 review, WP4-1). */
    fun keptCount(key: String) = PadProfiles.previous(key).size

    /** Theme H: the controller page's pointer — its every-game own controls switched on → OWN, else its page's Plain →
     *  PLAIN, else THOR. */
    fun mode(ctx: Context, pad: String): Mode {
        PadProfiles.init(ctx)
        return when {
            PadProfiles.enabled(PadProfiles.key(PadRules.EVERY, pad)) -> Mode.OWN
            plain(ctx, pad) -> Mode.PLAIN
            else -> Mode.THOR
        }
    }
    /** Never deletes: its every-game own controls are switched off (kept) for Like the Thor's / Plain, and come back for Its
     *  own (a new one = what it uses in every game: nothing — the Thor has no every-game controls). Its own sets the page
     *  back to like the Thor's, so switching its own off later falls to the Thor's, as the pill says. */
    fun setMode(ctx: Context, pad: String, m: Mode) {
        PadProfiles.init(ctx)
        val k = PadProfiles.key(PadRules.EVERY, pad)
        when (m) {
            Mode.THOR, Mode.PLAIN -> { PadProfiles.setEnabled(k, false); ExtPadsCtl.setFollows(ctx, pad, m == Mode.THOR) }
            Mode.OWN -> { ExtPadsCtl.setFollows(ctx, pad, true); makeOwn(ctx, PadRules.EVERY, pad) }
        }
    }

    /** It has motion to aim with (ControllerKind.gyro: the Nintendo kinds; PlayStation not yet, D10). */
    fun hasGyro(pad: String) = ExtPadsCtl.kindOf(pad).gyro
    /** The "E uses" spec of own entry [key]. */
    fun ownSpec(key: String) = PadRules.ownSpec(PadProfiles.get(key))

    /** One controller's tab of Game controls at [base] (WP6 draws it; theme H's status reads it too). */
    fun tab(ctx: Context, base: String, pad: String): PadTab {
        PadProfiles.init(ctx)
        val plain = plain(ctx, pad)
        val source = PadRules.resolve(base, pad, PadProfiles::enabled, plain)
        val own = source as? PadSource.Own
        val k = PadProfiles.key(base, pad)
        return PadTab(pad, base, source, own?.key, own?.let { PadProfiles.get(it.key) } ?: copyOf(ctx, base, pad),
            own?.let { PadRules.below(base, pad, it.level, PadProfiles::enabled, plain) },
            PadProfiles.has(k) && !PadProfiles.enabled(k), ExtPadsCtl.seatPad()?.key == pad, hasGyro(pad),
            seated = ExtPadsCtl.seatPad()?.key == pad && !ExtPadsCtl.seatDocked)       // dev build 18f: by its switch
    }
}

/** dev build 16 (WP4): one controller's tab of Game controls at [base]. [source] what it plays there (games: never docked);
 *  [editKey] the own entry an edit changes as it is (null: the first edit copies — [PadControls.ownForEdit]); [shown] what
 *  the tab draws; [below] what switching its own off leaves (null unless [source] is its own); [switchedOff] an own entry
 *  at [base]'s level is kept switched off; [docked] it plays as the Thor's controller now; [gyro] it has motion. dev build
 *  18f: [seated] — [docked] by its switch, undocked ("Plays as the Thor's controller"). */
data class PadTab(val pad: String, val base: String, val source: PadSource, val editKey: String?, val shown: AppConfig,
                  val below: PadSource?, val switchedOff: Boolean, val docked: Boolean, val gyro: Boolean, val seated: Boolean = false) {
    /** The tab's mark (Part 3 §2) — build 18 (GC9, gd-D06): in words, "Own" · "Thor's" · "Plain" ([GameWords.tabMark]; ● ○ –
     *  were never explained, and ○ read as PlayStation's Circle). */
    val mark: String get() = GameWords.tabMark(source)
}
