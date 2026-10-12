package app.wayfinder

/**
 * dev build 16 (theme F): one way to say a combo — built from the live binding, in the names of one controller ([PadLook]).
 * No text types "Home + X": it asks here (SourceRulesTest fails on a typed button letter).
 * dev build 17 (WP9): every word that depends on a controller, a combo or a place — a menu key ([Say.menu]), a button
 * ([Say.btn]), the held lists' title, the emergency off, an inherited value ([Say.usual]), a place ([Say.path]) — and
 * the glossary's names (one name per thing). SourceRulesTest keeps typed buttons, combos and paths and the glossary's
 * "Never" words out of the app's strings. The sentences of Game controls ([GameWords]) and of the controller pages
 * ([CtlWords]) are here too.
 */
object Say {
    /** [action]'s trigger where [pkg] has the controller (an app or a game's key: its own combos, else the usual ones);
     *  null = no combo, or its part is off ([Features.allows]). */
    fun trigger(action: ThorAction, pkg: String? = null): Trigger? =
        (if (pkg != null) ControlsStore.effectiveTriggerFor(GameProfiles.pkgOf(pkg), action) else ControlsStore.triggerFor(action))
            ?.takeIf { Features.allows(action) }

    /** "Home + X", "PS + △", "Xbox button + Y", "Capture + top button", "Select + Start (hold) + X" — in [look]'s names (the
     *  controller in hand unless said); null = no combo. */
    fun combo(action: ThorAction, look: PadLook = ButtonNames.handLook, pkg: String? = null): String? =
        trigger(action, pkg)?.let { of(it, look) }

    /** Pure (JVM test): a trigger in [look]'s names. */
    fun of(t: Trigger, look: PadLook): String = t.label(look)

    /** The connect pill (pure): "DualSense ready · player 2 · PS + △ Game controls" — no player yet / no combo: left out. */
    fun ready(name: String, player: Int, combo: String?): String =
        listOfNotNull("$name ready", "player $player".takeIf { player > 0 }, combo?.let { "$it Game controls" }).joinToString(" · ")
    /** dev build 18f: the connect pill of one that plays as the Thor's controller by its switch (pure): "DualSense ready ·
     *  plays as the Thor's controller · PS + △ Game controls" — [combo] in its names at the Thor's positions
     *  ([ButtonNames.lookOfSrc] 0: its presses come through the copy); none: left out. */
    fun readySeat(name: String, combo: String?): String =
        listOfNotNull("$name ready", "plays as the Thor's controller", combo?.let { "$it Game controls" }).joinToString(" · ")

    // ── dev build 17 (theme G, D4): a controller's Back and AYN button ──

    /** Where a controller's Back or AYN button is: a button of its own ([Own.name]: "touchpad click"), Home + Select /
     *  Home + Start standing in ([StandIn.t]), or none. */
    sealed class Spare {
        data class Own(val name: String) : Spare()
        data class StandIn(val t: Trigger) : Spare()
        object None : Spare()
    }
    /** "touchpad click", "Home + Select" in [look]'s names; null = none. */
    fun spare(s: Spare, look: PadLook): String? = when (s) {
        is Spare.Own -> s.name
        is Spare.StandIn -> of(s.t, look)
        Spare.None -> null
    }
    /** The controller page's row: "Back: touchpad click · AYN button: Mute" / "Back: Home + − · AYN button: Home + +" /
     *  "Back: none · AYN button: Capture + −" / "None yet — set them up". */
    fun spares(back: Spare, ayn: Spare, look: PadLook): String {
        val b = spare(back, look); val a = spare(ayn, look)
        if (b == null && a == null) return "None yet — set them up"
        return "Back: ${b ?: "none"} · AYN button: ${a ?: "none"}"
    }
    /** The hold-Home list's lines for the stand-ins, in [look]'s names: (its Select, "Back") and (its Start, [aynJob]: what
     *  its AYN button's tap does, as the list says actions). */
    internal fun standInLines(s: ExtRules.StandIns, look: PadLook, aynJob: String): List<Pair<String, String>> = listOfNotNull(
        (ThorButton.SELECT.spoken(look) to "Back").takeIf { s.back },
        (ThorButton.START.spoken(look) to aynJob).takeIf { s.ayn },
    )
    /** dev build 18f (review, pure): what pressing the touchpad does, for the touchpad card — "stays its Back" only when its
     *  Back IS the touchpad's press ([clickIsBack]: [ExtPadsCtl.backIsTouchClick]); else what its own setup gives it, never
     *  tied to its Back's name. [cap]: a sentence's start. */
    fun touchPress(clickIsBack: Boolean, cap: Boolean = false): String =
        (if (cap) "Pressing" else "pressing") + " the touchpad " + if (clickIsBack) "stays its Back" else "does what you set it to"
    /** dev build 18f (review, pure): the touchpad card's lines — (short, more). On: what a finger does; "more" the press
     *  ([touchPress]) and, when its Back is elsewhere, where ([back]: [spare]'s words, null = none). Off: no pointer, and the
     *  press. */
    fun touchpadLines(on: Boolean, clickIsBack: Boolean, back: String?): Pair<String, String?> =
        if (on) "On: a finger moves Android's pointer, a light tap clicks, two fingers scroll." to
            ("${touchPress(clickIsBack, cap = true)}." + if (clickIsBack) "" else " Its Back: ${back ?: "none"}.")
        else "Off: no pointer — ${touchPress(clickIsBack)}." to null
    /** "PS + touchpad click": its Home and its own Back button ([back]: its name; null = "Back") — its controls-off hold. */
    fun homeAndBack(look: PadLook, back: String?): String =
        "${ThorButton.HOME.spoken(look)} + ${back ?: ThorButton.BACK.spoken(look)}"

    // ── dev build 17 (WP9): a menu key, a button, a held list, the emergency off, a usual value, a place ──

    /** The button doing menu job [job] (A confirm · B back · X · Y) on [look]'s controller — the controller in hand unless
     *  said: "✕" confirms on a DualSense, "A" on the Thor whatever its style (its positions are AYN's). */
    fun menu(job: ThorButton, look: PadLook = ButtonNames.handLook): String = Vocab.name(Vocab.menuPos(job, look), look)

    /** Position [b]'s name on [look]'s controller: "△", "Y", "top button", "Capture", "PS". */
    fun btn(b: ThorButton, look: PadLook = ButtonNames.handLook): String = Vocab.name(b, look)

    /** Review WP9-5: the Home that opens Wayfinder's combos, in [look]'s names, then [then] (" + a button") — but the
     *  Thor's Home "on the Thor" when the controller in hand gives its Home to the game ("Its Home is the Thor's" off:
     *  holding its own does nothing of Wayfinder's — as ControllerPage's Game controls line). */
    fun comboHome(ctx: android.content.Context, look: PadLook = ButtonNames.handLook, then: String = ""): String {
        @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue        // the switch changed: the words follow
        // b18n (N3-ui 3): the hand's controller while it is connected — away, the Thor is in hand (as ButtonNames.lookOfSrc)
        val key = ButtonNames.handSrc.takeIf { it > 0 }?.let { ExtPadsCtl.liveKeyOfSrc(it) }
        return if (key != null && !ExtPadsCtl.homeIsThors(ctx, key)) "${btn(ThorButton.HOME, ButtonNames.thorLook)}$then on the Thor"
            else "${btn(ThorButton.HOME, look)}$then"
    }

    // ── build 18 device pass (C): "hold" said once ──
    /** The mark of a name that only acts held: a Home-less controller's Home is "Select + Start (hold)" ([Vocab.name]) — a
     *  tap of its Select + Start is never Home. */
    const val HELD = " (hold)"
    /** [name] where the words around it already say it is held ("hold …", "Keep … held", "Home + Back for 5 seconds"):
     *  without its "(hold)" — "Select + Start", never "hold Select + Start (hold)". The one rule: every sentence that holds a
     *  button whose name can carry the mark asks here ([hold], [keepHeld], [emergencyCombo], [CtlWords]; ControllerRulesTest
     *  keeps the others out of the source). */
    fun heldName(name: String): String = name.removeSuffix(HELD)
    /** [name] only acts held ([HELD]): a Home-less controller's Select + Start. */
    fun heldOnly(name: String): Boolean = name.endsWith(HELD)
    /** "hold PS", "hold Select + Start" ([heldName]); [cap]: "Hold …" (a sentence's start). */
    fun hold(name: String, cap: Boolean = false): String = (if (cap) "Hold " else "hold ") + heldName(name)

    /** A held list's title: "Keep PS held and press:" — a Home-less controller's "Select + Start" without its "(hold)". */
    fun keepHeld(b: ThorButton, look: PadLook): String = "Keep ${heldName(b.spoken(look))} held and press:"

    /** Game controls' emergency off is the input layer's (wfpad: the Home and Back of the controller it reads — the Thor's,
     *  or a docked controller playing as it; critique F1 / K11): "Home + Back" in [look]'s names, [back] = that docked
     *  controller's own Back ("touchpad click"). A connected controller's own 5-second hold (theme G) is [GameWords.muted]'s,
     *  never this. */
    fun emergencyCombo(look: PadLook, back: String? = null): String =
        "${heldName(ThorButton.HOME.spoken(look))} + ${back ?: ThorButton.BACK.spoken(look)}"
    /** "hold Home + Back for 5 seconds" ([emergencyCombo]). */
    fun emergencyOff(look: PadLook, back: String? = null): String = "hold ${emergencyCombo(look, back)} for 5 seconds"
    /** [emergencyCombo] now: the Thor's names, or the docked controller's (its own Back's name when it has one). dev build
     *  18f: one playing as the Thor's by its switch (merged: the Thor's own Home + Back still work) — [emergencySeat]. */
    fun emergencyCombo(ctx: android.content.Context): String {
        val docked = ExtPadsCtl.dockedPad()
        // b18n (N3-ui 3): its own Home + Back only while it is connected — away, the Thor's names (as [ButtonNames.lookOfSrc] 0)
        if (docked == null) ExtPadsCtl.seatPadHere()?.let { seat ->
            return emergencySeat(ButtonNames.thorLook, ButtonNames.lookOfSrc(0), (ExtPadsCtl.spares(ctx, seat).back as? Spare.Own)?.name)
        }
        return emergencyCombo(ButtonNames.lookOfSrc(0), docked?.let { (ExtPadsCtl.spares(ctx, it).back as? Spare.Own)?.name })
    }
    /** dev build 18f (pure): the emergency off while a controller plays as the Thor's by its switch — wfpad reads both, so
     *  the Thor's Home + Back ([thor]'s names) "on the Thor", or its own Home + its own Back ([seat]'s names, [back] its Back's
     *  name: "touchpad click"); without a Back of its own (Home + Select stands in: the input layer never sees a Back held
     *  there) the Thor's only. */
    fun emergencySeat(thor: PadLook, seat: PadLook, back: String?): String =
        "${emergencyCombo(thor)} on the Thor" + (back?.let { " or ${emergencyCombo(seat, it)}" } ?: "")
    /** Review WP9-4: docked with a controller that has no Back of its own (Home + Select stands in for it — in the app:
     *  the input layer never sees a Back held), no Home + Back exists there — what works: the Thor's own, once undocked
     *  (the input layer reads the Thor again). [thor]: the Thor's names. */
    fun emergencyOffUndocked(thor: PadLook): String = "unplug the TV or monitor, then hold ${emergencyCombo(thor)} on the Thor for 5 seconds"
    /** [emergencyOff] now (as [emergencyCombo]); docked with a controller without a Back: [emergencyOffUndocked]. */
    fun emergencyOff(ctx: android.content.Context): String {
        val docked = ExtPadsCtl.dockedPad()
        if (docked != null && ExtPadsCtl.spares(ctx, docked).back !is Spare.Own) return emergencyOffUndocked(ButtonNames.thorLook)
        return "hold ${emergencyCombo(ctx)} for 5 seconds"
    }

    /** An inherited value (glossary: "Usual (<value>)"): "Usual (Balanced)"; null = "Usual" alone — a row too narrow for
     *  the value (the line above it says it). */
    fun usual(value: String?): String = if (value.isNullOrBlank()) "Usual" else "Usual ($value)"

    /** A place in Wayfinder: "Controller › Where Game controls work" ([Nav.path]: the one registry, NavPaths.kt). */
    fun path(card: NavCard, name: String? = null, from: String? = null, inWayfinder: Boolean = false): String =
        Nav.path(card, name, from, inWayfinder)

    /** 1.4 (§10.3): a card's explanation → its first sentence (or what comes before " — "), and the rest (behind its "?").
     *  dev build 17 (WP9): moved here from Hub.kt's splitExplanation, unchanged (PageWordsTest measures the first part). */
    fun split(s: String): Pair<String, String?> {
        if (s.length <= 72) return s to null
        // (end of the short line, start of the rest) — the earliest one wins
        val cuts = mutableListOf<Pair<Int, Int>>()
        Regex("""(?<!e\.g|i\.e|etc)\.\s""").findAll(s).map { it.range.first }.firstOrNull { it in 18..110 }
            ?.let { cuts += (it + 1) to (it + 2) }
        Regex(" — ").findAll(s).map { it.range.first }.firstOrNull { it in 12..95 }?.let { cuts += it to (it + 3) }
        val p = s.lastIndexOf(" (")
        if (p in 25..100 && s.trimEnd('.').endsWith(")") && s.indexOf(')', p) == s.trimEnd('.').length - 1) cuts += p to (p + 2)
        val (end, rest) = cuts.minByOrNull { it.first } ?: return s to null
        val more = s.substring(rest).trim().let { if (s.getOrNull(rest - 1) == '(') it.trimEnd('.').removeSuffix(")") else it }
            .replaceFirstChar { it.uppercase() }
        if (more.isBlank()) return s to null
        var first = s.substring(0, end).trimEnd()
        var extra = more
        // a "(…)" aside in a still-long line joins the rest
        val a = first.indexOf(" ("); val b = first.indexOf(')', a + 2)
        if (first.length > 64 && a > 20 && b > a) {
            extra = first.substring(a + 2, b).replaceFirstChar { it.uppercase() } + ". " + extra
            first = (first.substring(0, a) + first.substring(b + 1)).trimEnd()
        }
        return first to extra
    }

    // ── build 18 (A7): a button inside a text, drawn as its badge ──

    /** A button token's marks: [key] builds them, ui.GlyphText draws the button they hold as its badge, [plain] turns them
     *  back into the button's name. Never typed in a text (SourceRulesTest): a token comes from here. */
    const val KEY_OPEN = '⟦'
    const val KEY_CLOSE = '⟧'
    private val KEY_TOKEN = Regex("${Regex.escape(KEY_OPEN.toString())}([A-Z0-9_]+)${Regex.escape(KEY_CLOSE.toString())}")

    /** Position [b] as a token in a text ("Hold ${Say.keyMenu(ThorButton.B, look)} to leave"): GlyphText draws its badge
     *  in the names of the page or window showing it (ui.LocalPadLook: the controller in hand, a controller's own page).
     *  [look] never changes the token — it is there so a call reads like [btn]; a menu job's position is [keyMenu]'s. */
    @Suppress("UNUSED_PARAMETER")
    fun key(b: ThorButton, look: PadLook? = null): String = "$KEY_OPEN${b.name}$KEY_CLOSE"

    /** The button doing menu job [job] (A confirm · B back · X · Y) on [look]'s controller, as a token ([menu] in words). */
    fun keyMenu(job: ThorButton, look: PadLook = ButtonNames.handLook): String = key(Vocab.menuPos(job, look), look)

    /** [s] cut into its words and its buttons, in order: (text, null) or (the token's name, its button). An unknown name
     *  stays words (its name, without the marks). */
    fun keyParts(s: String): List<Pair<String, ThorButton?>> {
        if (KEY_OPEN !in s) return listOf(s to null)
        val out = ArrayList<Pair<String, ThorButton?>>()
        var at = 0
        for (m in KEY_TOKEN.findAll(s)) {
            if (m.range.first > at) out += s.substring(at, m.range.first) to null
            val name = m.groupValues[1]
            out += name to ThorButton.values().firstOrNull { it.name == name }
            at = m.range.last + 1
        }
        if (at < s.length) out += s.substring(at) to null
        return out
    }

    /** [s] with each token as its button's name on [look] ("○" for the Back job on a DualSense, "B" on the Thor) — for
     *  what can't draw a badge: the overlays' View text ([Overlays] converts what it gets), descriptions, logs. */
    fun plain(s: String, look: PadLook): String =
        if (KEY_OPEN !in s) s else keyParts(s).joinToString("") { (t, b) -> if (b != null) Vocab.name(b, look) else t }

    // ── build 18 (B4, B5): how an action is done; where the controller goes ──

    /** Every way [action] is done now, in [look]'s names: its combo, the AYN button's tap or hold (while the AYN button is
     *  Wayfinder's), the 3-finger gesture for the bottom screen — "AYN hold · 3-finger tap"; null = nothing does it (D6:
     *  Search said "No combo yet" while AYN hold turned the bottom screen off). */
    fun howTo(action: ThorAction, look: PadLook = ButtonNames.handLook): String? = howToOf(
        ControlsStore.triggerFor(action), look,
        aynTap = AppSettings.aynButtonOurs && AppSettings.aynTap == action,
        aynHold = AppSettings.aynButtonOurs && AppSettings.aynHold == action,
        gesture = AppSettings.gestureBlankMode.takeIf { action == ThorAction.TOGGLE_SECOND_SCREEN })

    /** Pure ([howTo], SearchIndexTest): [trigger] its combo, [aynTap] / [aynHold] the AYN button's, [gesture] the 3 fingers. */
    fun howToOf(trigger: Trigger?, look: PadLook, aynTap: Boolean = false, aynHold: Boolean = false, gesture: BlankGesture? = null): String? =
        listOfNotNull(
            trigger?.let { of(it, look) },
            btn(ThorButton.AYN, look).takeIf { aynTap },
            "${btn(ThorButton.AYN, look)} hold".takeIf { aynHold },
            when (gesture) { BlankGesture.TAP -> "3-finger tap"; BlankGesture.SWIPE -> "3-finger swipe down"; else -> null },
        ).joinToString(" · ").ifEmpty { null }

    /** Where the controller goes, in the strip's and the combos sheet's words (D10: one wording): "follows touch", "follows
     *  touch until sent", "stays where you send it", "always on the top screen", "always on the bottom screen". */
    fun controllerMode(): String = controllerMode(AppSettings.focusLockEnabled, AppSettings.focusSticky, AppSettings.focusLockTop)

    /** Pure ([controllerMode]): "Stays where sent" works by locking the controller where it's sent — [sticky] first. */
    fun controllerMode(lock: Boolean, sticky: Boolean, top: Boolean): String = when {
        !lock -> if (sticky) "follows touch until sent" else "follows touch"
        sticky -> "stays where you send it"
        top -> "always on the top screen"
        else -> "always on the bottom screen"
    }

    // ── b18h (GitHub #49, #99): Pointer speed — one name and one scale for every pointer moved by an amount ──
    /** How far a pointer goes for a move, a number 1–10 (5 = the speed it always had): Keyboard & mouse's trackpad in Mouse
     *  and Touch (Keyboard's card [NavCards.KB_SPEED], the deck's stepper) and a stick set to Mouse in Game controls
     *  (glossary). Never "Usual" or "Default": it is not a value taken from a wider setting. */
    const val POINTER_SPEED = "Pointer speed"
    /** The Hub stepper's words beside the number: the scale's two ends. */
    const val POINTER_SCALE = "1 slowest · 10 fastest"
    /** The deck stepper's + and −, for a screen reader. */
    const val FASTER = "Faster"
    const val SLOWER = "Slower"

    // ── b18i (D3, GitHub #99; 2026-10-10): Keyboard's thin bar ──
    /** The thin keyboard window Wayfinder Keyboard keeps on the typing screen while its keys are on the other one — its
     *  switch's title (the requested "Keep a thin bar on the typing screen (some games need it)": the title keeps the 5-word rule,
     *  its line says the rest). */
    const val THIN_BAR = "Keep a thin bar"
    /** Its switch's line, both states (the shown part ≤ 20 words, the rest behind "?" — KeyboardBarTest). Off = 1.3's: no
     *  keyboard window on the typing screen, so no AYN black keyboard bar, but some games (Eden's name boxes) submit at once. */
    fun thinBarLine(on: Boolean): String =
        if (on) "On — a thin bar stays on the typing screen: some games need it. It shows while the keys are on the other " +
            "screen; Android draws its black keyboard bar under it, and no app can hide that one."
        else "Off — no thin bar, so no black keyboard bar from Android either, but some games take the text at once. " +
            "Eden's name boxes, for one, send an empty name before you type."
}

/** dev build 16 (WP6): the sentences of Game controls' tabs — one place (dev build 17, WP9: moved here from GameTabs.kt). A
 *  pad's tab never says "back edge", "moving the Thor" or "the Thor sends" (PadTabWordsTest). */
object GameWords {
    fun usesThors(appLabel: String, layerOff: Boolean) =
        "Uses the Thor's controls" + if (layerOff) " — Game controls are off in $appLabel: plain buttons now" else ""
    /** Build 18 (GC12, gd-D25): inside the chip's sentence the app's short name ([appShort]). */
    fun where(level: PadLevel, appLabel: String) = when (level) {
        PadLevel.GAME -> "this game"; PadLevel.APP -> "all of ${appShort(appLabel)}"; PadLevel.EVERY -> "every game"
    }
    /** What own controls were made from (a copy of what it used). */
    fun from(src: PadSource, appLabel: String) = when (src) {
        PadSource.Thor -> "the Thor's"; PadSource.Plain -> "plain buttons"
        is PadSource.Own -> if (src.level == PadLevel.EVERY) "its every-game ones" else "its $appLabel ones"
    }
    /** D7's pill: says what comes back ([PadTab.below]). */
    fun useBelow(below: PadSource, appLabel: String) = when (below) {
        PadSource.Thor -> "Use the Thor's again"; PadSource.Plain -> "Plain buttons again"
        is PadSource.Own -> if (below.level == PadLevel.EVERY) "Use its every-game ones" else "Use its $appLabel ones"
    }
    fun ownAgain(label: String) = "Its own for $label again"

    // ── pills (Overlays.pill) ──
    /** The first change on a tab without its own made them (D3). Build 18 (GC1, decision H4): made from the Thor's, the pill
     *  says how to undo it — the chip's [useBelow] pill. */
    fun madeByEdit(pad: String, label: String, from: PadSource, appLabel: String) =
        if (from == PadSource.Thor) "$pad: its own controls for $label now — “${useBelow(PadSource.Thor, appLabel)}” undoes it"
        else "$pad: its own controls for $label now (made from ${from(from, appLabel)})"
    /** Build 18 (GC1): "Use the Thor's again" deleted a copy made from the Thor's by itself ([face]: it had a face pick). */
    fun backToThors(pad: String, face: Boolean) =
        "$pad: back to the Thor's controls" + if (face) " (its face pick is gone)" else ""
    /** critique B5: that first change replaced its own ones kept switched off — kept, and where to bring them back. */
    fun replacedKeptOff(pad: String, label: String, from: PadSource, appLabel: String) =
        "$pad: new controls for $label (from ${from(from, appLabel)}) — your earlier ones are kept: " +
            Say.path(NavCards.GC_BRING_BACK, from = NavPages.GAME)
    fun madeOwn(pad: String, label: String, cameBack: Boolean) =
        if (cameBack) "$pad: its own controls for $label again, as you left them"
        else "$pad: its own controls for $label (a copy of what it used) — the Thor's are unchanged"
    fun switchedOff(pad: String, label: String) = "$pad: its own controls for $label are kept, switched off"
    /** [undo]: where its Bring back is — Game controls' (the default), or "below" on the controller's own page. */
    fun removed(pad: String, label: String, undo: String = Say.path(NavCards.GC_BRING_BACK, from = NavPages.GAME)) =
        "$pad: its own controls for $label removed — $undo undoes it"
    fun broughtBack(pad: String, label: String) = "$pad: its earlier controls for $label are back"
    /** b18i (D1, option C): Game controls closed, and the game stopped drawing while the page was open and hasn't started
     *  again since — it paused itself (covered by the page, Eden and GameNative pause) and waits for you in its own menu. */
    const val PAUSED_ITSELF = "The game paused itself — resume it in the game"

    // ── the page ──
    fun dockedLine(pad: String) = "$pad plays as the Thor's controller while docked"
    /** dev build 18f: the Thor's tab — who plays as the Thor's controller now: docked ([dockedLine]) or by its switch. */
    fun seatLine(pad: String, docked: Boolean) = if (docked) dockedLine(pad) else "$pad plays as the Thor's controller"
    /** dev build 18f: the pill when another controller takes over as the Thor's controller (the first one went). */
    fun seatNow(pad: String) = "$pad now plays as the Thor's controller"
    /** dev build 18f: undocked, the docked controller still plays as the Thor's by its switch. */
    fun undockedSeat(pad: String) = "Undocked — $pad still plays as the Thor's controller, and the Thor's own buttons work too"
    fun nothingChanged(name: String, pad: Boolean) =
        "Nothing changed — every control does what ${if (pad) name else "the Thor"} sends."
    fun nothingOwn(what: String, src: PadSource) =
        "Nothing of its own to $what — " + if (src == PadSource.Plain) "it plays its plain buttons." else "it uses the Thor's controls."
    // ── dev build 17 (theme G) ──
    /** G7 (D10): a PlayStation / Xbox / other tab, the Thor's gyro on for that game — it stays the Thor's. */
    fun thorMotionStays(pad: String) = "Motion: the Thor's gyro stays on the Thor — $pad's own motion isn't read yet"
    /** G4: its Home + Back held 5 s ([combo]: "PS + touchpad click") — its own controls off until its page turns them on. */
    fun muted(pad: String, combo: String) =
        "$pad: Wayfinder's controls off ($combo held) — it plays its own buttons. Its page turns them back on."

    /** A controller's motion aim in words (its tab's pill, its popup). */
    fun motion(g: ExtPadsCtl.Gyro) = when (g.stick) { 1 -> "left stick"; 2 -> "right stick"; else -> "off" } +
        if (g.stick != 0) " · ×${g.sens}" else ""

    /** The presets' button names on [look]'s controller (RetroArch's "Hold Create + R1" on a DualSense, "View + RB" on an
     *  Xbox pad). The emulator hotkeys and the web preset stay on the same POSITIONS whatever they are called. */
    class PresetWords(val face: String, val retroArch: String, val retroArchNote: String, val emuMenu: String, val web: String)
    fun presets(look: PadLook): PresetWords {
        fun n(b: ThorButton) = Vocab.name(b, look)
        val sel = n(ThorButton.SELECT)
        return PresetWords(
            face = if (look.names == FaceStyle.PLAYSTATION) "PlayStation face buttons (${n(ThorButton.B)} at the bottom)"
                else "Xbox face buttons (A at the bottom)",
            retroArch = "Hold $sel + ${n(ThorButton.R1)}: save · + ${n(ThorButton.L1)}: load · + ${n(ThorButton.R2)}: fast-forward · " +
                "+ ${n(ThorButton.START)}: menu",
            retroArchNote = "Turns on the Shift button ($sel) for this app. $sel alone still reaches RetroArch. Save and load use the " +
                "current state slot (F6 / F7 on Keyboard & mouse's Emulator pad change it).",
            emuMenu = "Hold $sel + ${n(ThorButton.START)}: the emulator's menu",
            web = "Left stick: mouse · right stick: scroll · ${n(ThorButton.A)}: click · ${n(ThorButton.X)}: right click · ${n(ThorButton.B)}: back",
        )
    }

    // ── build 18 (GC9, GC11, GC12) ──
    /** GC9 (gd-D06, ps-F5, FTU-03): whose controls a controller's tab plays, in a word — never ● ○ – (○ beside a PlayStation
     *  name read as "press Circle"). */
    fun tabMark(src: PadSource) = when (src) { is PadSource.Own -> "Own"; PadSource.Thor -> "Thor's"; PadSource.Plain -> "Plain" }
    /** GC12 (gd-D25): an app's name inside a sentence — one trailing " (…)" left out ("RetroArch (AArch64)" → "RetroArch");
     *  titles keep the whole name. */
    fun appShort(label: String): String {
        val t = label.trimEnd()
        if (!t.endsWith(")")) return label
        val open = t.lastIndexOf(" (")
        return if (open > 0) t.substring(0, open).trimEnd() else label
    }
    /** GC11 (gd-D02): the drawing's note when this game's face positions aren't the ones printed on the controller —
     *  "Xbox positions for this game: B confirms" ([look] with those positions: the drawing's names, the physical button). */
    fun facePositions(style: FaceStyle, look: PadLook) =
        "${style.label} positions for this game: ${Say.menu(ThorButton.A, look.copy(xbox = style.xboxPositions))} confirms"
}

/** dev build 17 (theme H): the controller pages' words (WP9: moved here from ControllerRules.kt). */
object CtlWords {
    val TITLES = mapOf(CtlSection.FACE to "Face buttons", CtlSection.TEST to "Sticks and deadzone",
        CtlSection.IN_GAMES to "In games", CtlSection.HOME to "Home and combos", CtlSection.MOTION to "Motion",
        CtlSection.SETUP to "When it's read wrong", CtlSection.ABOUT to "What games see")

    private fun player(n: Int) = n.takeIf { it > 0 }?.let { "player $it" }

    /** dev build 18f2 (device test t2 BUG-1): what a player number becomes once the game a new order waits for closes —
     *  "after RetroArch closes: player 1" ([ControllerRules.PlayerNo.after]; [app] the game's name). Null: no change. */
    fun afterClose(app: String?, n: Int): String? = if (n > 0 && app != null) "after $app closes: player $n" else null
    /** The order card's row while a new order waits: what games see this moment — "now player 2" (null: the same). */
    fun nowPlayer(now: Int, row: Int): String? = if (now > 0 && now != row) "now player $now" else null
    /** b18n (N3-ui 3, b18w w1): an away controller's row on the Controller list — its kept place, "away · player 2 kept"
     *  ([now] from [ExtPadsCtl.playerNo], as its page says it), then [after] ([afterClose]); no place: as before. */
    fun awayRow(now: Int, after: String? = null): String =
        if (now > 0) listOfNotNull("away · player $now kept", after).joinToString(" · ") else "away — its place is kept 30 minutes"

    /** dev build 18f: the "Its buttons in games" card's first words for one playing as the Thor's controller by its switch
     *  (its choice stays editable: it applies when the switch is off, as docked). */
    const val seatGames = "It plays as the Thor's controller now: the Thor's controls apply."
    /** The player order card with nothing to order (fewer than two rows). dev build 18f (review): [seated] — the one connected
     *  plays as the Thor's controller (it is the Thor's row), so another one is needed. */
    fun orderNone(seated: Boolean): String =
        if (seated) "Connect another controller to choose who is player 1." else "Connect a controller to choose who is player 1."
    /** b18m (2026-10-11): "New controllers play as Thor's" (Settings for every controller) — what it does now ([on]);
     *  after " — " its "?" ([Say.split]). Never "mapping" (the glossary's Never column): "setting up again", as the seat's. */
    fun seatNew(on: Boolean): String =
        if (on) "On: a controller you connect for the first time plays as the Thor's controller — games keep seeing only " +
            "the Thor's, so nothing needs setting up again. One connected before keeps its own switch, on its page."
        else "Off: a new controller is its own player."

    // ── build 18 round 2 (C9): a connected controller's Home, said by what it does ──
    // [home] is Vocab.name(ThorButton.HOME, its look): its own name ("PS", "Xbox button", "Capture"), the plain "Home" (a
    // Switch Pro, a right Joy-Con, a Joy-Con pair, an unknown controller), or a Home-less one's "Select + Start (hold)".
    /** [home] without a Home-less controller's "(hold)": "Select + Start" ([Say.heldName], the one rule). */
    private fun bare(home: String) = Say.heldName(home)
    /** A Home-less controller's Select + Start held: it is never a tap (ButtonEngine's `synthHome`), so it never goes home
     *  on its own — it is held, for the combos. */
    private fun standIn(home: String) = Say.heldOnly(home)
    /** A Home named "Home" said "Home: Home" / "Home works as Home": it says what it does instead. */
    private fun plainHome(home: String) = bare(home) == ThorButton.HOME.printed

    /** Its Home switch's title, 5 words at most: "PS works as Home", "Select + Start works as Home"; a Home named Home —
     *  "Home works as the Thor's". */
    fun homeTitle(home: String): String = bare(home).let { if (plainHome(home)) "$it works as the Thor's" else "$it works as Home" }
    /** Its Home switch's line: on, what a press and a hold do ([combos]: " (PS + △: Game controls, …)" or ""); off, where
     *  it goes. */
    fun homeLine(home: String, on: Boolean, combos: String): String = when {
        !on -> "Off — ${bare(home)} goes to the game (emulators' menu)."
        standIn(home) -> "${Say.hold(home, cap = true)} for your combos$combos."
        else -> "${bare(home)} goes home; hold it for your combos$combos."
    }
    /** The status header's Home part: "PS: Home, hold for combos"; a Home named Home — "Home: goes home, hold for combos";
     *  a Home-less controller's — "hold Select + Start: your combos" (device pass C: it is only held, never Home on its own,
     *  and "hold" is said once — as the Thor's "hold Home: your combos"). */
    private fun homeStatus(home: String, ours: Boolean): String = when {
        !ours -> "${bare(home)} goes to the game"
        standIn(home) -> "${Say.hold(home)}: your combos"
        plainHome(home) -> "$home: goes home, hold for combos"
        else -> "$home: Home, hold for combos"
    }

    /** Theme H's status header: "player 2 · PS: Home, hold for combos · touchpad click: Back · PS + △: Game controls" —
     *  its Home, Back and Game controls in its own symbols; docked, as the Thor's controller. Build 18 (C9, VD-27 / ps-F8):
     *  what its buttons do, not whose they are ("Handled by Wayfinder", "PS: Wayfinder's Home" said nothing to do); the Thor
     *  alone has no player ([CtlStatus.player] 0: the page passes it so). */
    fun status(s: CtlStatus): String = when (s.state) {
        CtlState.THOR ->
            if (s.docked) "Docked: ${s.dockedName ?: "a controller"} plays as the Thor's controller — the Thor's buttons are off until you undock"
            // dev build 18f: a controller playing as it by its switch ([CtlStatus.seated], [CtlStatus.dockedName] which one)
            else listOfNotNull("The Thor's own controller", player(s.player), s.after?.takeIf { s.player > 0 },
                s.dockedName?.takeIf { s.seated }?.let { "$it plays as it too" },
                s.gameControls?.let { "$it: Game controls" }, "${Say.hold(s.home ?: "Home")}: your combos").joinToString(" · ")
        CtlState.STARTING -> "Starting…"
        CtlState.AWAY -> listOfNotNull("Away — its place is kept 30 minutes", player(s.player), s.after?.takeIf { s.player > 0 }).joinToString(" · ")
        CtlState.HANDLED -> listOfNotNull(
            // dev build 18f: by its switch — the copy's number (2 or later with "Connected controllers first"), not always 1
            if (s.docked) "docked: plays as the Thor's controller, player 1"
            else if (s.seated) "plays as the Thor's controller" + (player(s.player)?.let { ", $it" } ?: "")
            else player(s.player),
            // dev build 18f2: while a new order waits for a game, what that number becomes
            s.after?.takeIf { s.player > 0 && !s.docked },
            s.home?.let { homeStatus(it, s.homeOurs) },
            s.back?.let { "$it: Back" },
            s.gameControls?.let { it + (if (s.gcOnThor) " on the Thor" else "") + ": Game controls" }).joinToString(" · ")
    }
}

/** b18p (GitHub #109): Stick lights' words when Wayfinder can't drive the rings (StickLightsTest) — moved here from
 *  LightsPage.kt. None found (no nodes), or found but this system doesn't let Wayfinder change them (the LED class, not
 *  writable by the app: [app.wayfinder.lights.StickLights.locked]). Glossary: "Stick lights Wayfinder can't drive". */
internal object LightsWords {
    const val NOT_FOUND_TITLE = "No stick lights found"
    const val NOT_FOUND = "Wayfinder can't find the stick lights' controls on this system, so its light effects are off. " +
        "Your system's own light settings aren't affected."
    const val NOT_FOUND_ROW = "Not found on this system"
    const val LOCKED_TITLE = "Can't change the stick lights"
    const val LOCKED = "Stick lights found, but this system doesn't let Wayfinder change them. " +
        "Your system's own light settings aren't affected."
    const val LOCKED_ROW = "This system doesn't let Wayfinder change them"
}
