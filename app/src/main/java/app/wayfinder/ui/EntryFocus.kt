package app.wayfinder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/*
 * dev build 16 (WP7): where the controller lands. Opening a page focuses its first content, never Back (an A then left
 * the page); coming back focuses the control that opened it, found by a stable id (a row's id, "pad:<key>") — not its
 * label, which changes with the names. Both after the first layout, never after a timer (300 / 360 ms raced each other:
 * the row's 360 ms had to come after the page's 300 ms).
 */

/** A page's (or sheet's) focus targets — the first content offered in composition order (top to bottom), and controls
 *  with a stable id to come back to. */
class EntryFocus internal constructor() {
    internal val ids = HashMap<String, FocusRequester>()
    internal val offered = ArrayList<FocusRequester>()
    internal var resolved = false
    internal var wanted by mutableStateOf<String?>(null)
    /** A sub-screen of this page closed: focus [id] once it is laid out again (the next frames). */
    fun request(id: String) { wanted = id }

    /** Review WP9-1: the ids whose target says whether it has the focus ([focusId]: a card's group) — and which have it.
     *  A group with nothing focusable in it takes requestFocus without an error and focuses nothing (Compose 1.6): such
     *  an id hasn't "landed". An id that doesn't say (a control's own) is trusted, as before. */
    internal val reports = HashSet<String>()
    internal val focused = HashSet<String>()
    internal fun landed(id: String) = id !in reports || id in focused
}

val LocalEntryFocus = compositionLocalOf<EntryFocus?> { null }

/** Set by a sheet around its own content: a page behind an open sheet doesn't take the focus from it. */
internal val LocalInSheet = staticCompositionLocalOf { false }

/** Device pass (P1-3): [r] asked for the focus and HOLDS it — not only "didn't throw" (it throws while not attached). A
 *  request can go elsewhere without an error: a group's `enter` on the way redirects it (Game controls' page sends every
 *  focus coming from outside to the drawing — a closed popup's opener lost to it), and a group with nothing focusable in
 *  it takes it and focuses nothing (Compose 1.6). A target that says whether it holds the focus ([FocusTruth]) is believed;
 *  one that doesn't is trusted, as before. Device pass (GC: P1-3, P2-1): an explicit request — the arrival rules on its way
 *  stand aside ([arrivalTo]), so it lands where it was sent on its first try (not one frame on the drawing first). */
private fun tryFocus(r: FocusRequester): Boolean {
    FocusTruth.explicit++
    val asked = try { runCatching { r.requestFocus() }.isSuccess } finally { FocusTruth.explicit-- }
    return asked && FocusTruth.of(r) != false
}

/**
 * Device pass (GC: P1-3, P2-1): a page's ARRIVAL rule — where the focus coming into a group from nowhere lands (the first
 * D-pad press, after a touch): `focusProperties { enter = { arrivalTo(drawing) } }`. Compose 1.6 also runs that `enter` for
 * a request coming into the group from outside (`requestFocus` asks every group on its way that has nothing focused,
 * outermost first): a closed popup's opener given the focus back went to Game controls' drawing, the row that opened
 * Performance to the Game page's "Opens on". An explicit request ([tryFocus]: [requestAfterLayout], a page's entry and
 * return) goes where it was sent; every arrival still lands on [target]. Not told apart by the direction: a centre key
 * with nothing focused arrives as `Enter` too. A group's own landing rule (a choice's current option) is no arrival rule:
 * it applies to every request, as before. Device pass (final): Your colours' first colour (Background) is one — a code
 * field's typing and "Fix the text colour" were sent to it.
 */
fun arrivalTo(target: FocusRequester): FocusRequester = if (FocusTruth.explicit > 0) FocusRequester.Default else target

/**
 * Device pass (P1-3): which requesters say whether their target holds the focus (a group: something in it). Every control
 * of ui/ that attaches its own requester reports here from its own focus events — [FocusableGlass] (so GlassButton,
 * GlassOption, list rows), the text field's gate and field, a slider, the colour wheel, a choice's group, [focusId] — and
 * any other target with [reportingFocusRequester]. Compose sends a node's focus events during the request itself, so the
 * answer is there when [requestFocus] returns. Weak keys: a requester forgotten with its composition leaves. Main thread.
 */
internal object FocusTruth {
    private val has = java.util.WeakHashMap<FocusRequester, Boolean>()
    fun report(r: FocusRequester, hasFocus: Boolean) { has[r] = hasFocus }
    /** true / false = it says; null = it doesn't report (a plain `Modifier.focusRequester`). */
    fun of(r: FocusRequester): Boolean? = has[r]
    /** Device pass (GC): > 0 while [tryFocus]'s request runs (Compose asks the groups' `enter` inside it) — [arrivalTo]. */
    var explicit = 0
}

/** Device pass (P1-3): `focusRequester(r)` on a target that also says whether it holds the focus ([FocusTruth]) — for a
 *  target [requestAfterLayout] focuses that isn't one of ui/'s controls (they report already). Put it where the plain
 *  `focusRequester(r)` was, before the target. */
fun Modifier.reportingFocusRequester(r: FocusRequester): Modifier =
    focusRequester(r).onFocusChanged { FocusTruth.report(r, it.hasFocus) }

/** [id]'s target took the focus — or, review WP9-1, a group with nothing focusable in it didn't (it says so by the next
 *  frame at the latest). */
private suspend fun EntryFocus.focusOn(id: String): Boolean {
    val r = ids[id] ?: return false
    if (!tryFocus(r)) return false
    if (landed(id)) return true
    withFrameNanos { }
    return landed(id)
}

/**
 * [first] = the scaffold's own first target (the setup button, the tour's Next…); null = the first focusable content
 * offered (a [FocusableGlass] under this entry's [LocalEntryFocus]). [returnKey] = the id to come back to
 * ([ReturnFocus.take], a sheet's opener…). Resolves after the first layout (lazy rows are composed by then); a return id
 * not laid out within 3 frames → [first]; [first] never attached → the first content. A page behind an open sheet
 * leaves the focus to the sheet. dev build 16 review (WP7-2): [skip] true when it resolves = the user already put the
 * focus somewhere on the page (an entry made late — the Games grid once its apps are loaded — took it from them).
 */
@Composable
fun rememberEntryFocus(first: FocusRequester? = null, returnKey: String? = null, skip: () -> Boolean = { false }): EntryFocus {
    val entry = remember { EntryFocus() }
    val sheets = LocalSheetHost.current
    val inSheet = LocalInSheet.current
    val skipNow by androidx.compose.runtime.rememberUpdatedState(skip)
    LaunchedEffect(entry) {
        try {
            if (returnKey != null) for (n in 1..3) {
                withFrameNanos { }
                if (skipNow()) return@LaunchedEffect
                val r = entry.ids[returnKey] ?: continue
                if (!tryFocus(r)) continue                                  // not attached yet: the next frame
                if (!entry.landed(returnKey)) withFrameNanos { }
                if (entry.landed(returnKey)) {
                    // dev build 17 (WP9): the row a link or Back landed on (device test S14) — debug builds only
                    if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorEntry", returnKey)
                    return@LaunchedEffect
                }
                // review WP9-1: there but nothing took the focus (Player order's card with no controller: no Up / Down) —
                // the page's first content, as for an id not laid out (not nothing focused: the next D-pad press started
                // from the top, on Back)
                if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorEntry", "$returnKey empty")
                break
            } else withFrameNanos { }
            if (skipNow()) return@LaunchedEffect
            if (!inSheet && sheets?.sheets?.isNotEmpty() == true) return@LaunchedEffect
            if (first != null && (tryFocus(first) || first.requestAfterLayout(2))) return@LaunchedEffect
            entry.offered.firstOrNull()?.let { tryFocus(it) || it.requestAfterLayout(2) }
        } finally { entry.resolved = true }
    }
    LaunchedEffect(entry.wanted) {
        val id = entry.wanted ?: return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (entry.focusOn(id)) { entry.wanted = null; return@LaunchedEffect }   // review WP9-1: really focused
        }
        entry.wanted = null
    }
    return entry
}

/** [r] (a control's own requester) is one of the page's entry candidates, in composition order (top to bottom): every
 *  focus target of the app's own controls offers itself — [FocusableGlass], a slider, a card's row — so a page whose first
 *  content is a slider opens on it, not on the card after it. */
@Composable
internal fun OfferEntry(r: FocusRequester) {
    val entry = LocalEntryFocus.current
    DisposableEffect(entry, r) { onDispose { entry?.offered?.remove(r) } }
    androidx.compose.runtime.SideEffect { entry?.let { if (!it.resolved && r !in it.offered) it.offered += r } }
}

/** A control's stable id on its page (registered with the page's [EntryFocus]). [FocusableGlass] takes it as `focusId`. */
fun Modifier.focusId(id: String): Modifier = composed {
    val entry = LocalEntryFocus.current
    val r = remember { FocusRequester() }
    DisposableEffect(entry, id) {
        entry?.ids?.put(id, r); entry?.reports?.add(id)
        onDispose { if (entry?.ids?.get(id) === r) { entry.ids.remove(id); entry.reports.remove(id); entry.focused.remove(id) } }
    }
    // review WP9-1: it says whether it (a group: something in it) has the focus — [EntryFocus.landed]; device pass (P1-3):
    // and to [FocusTruth] (requestAfterLayout)
    focusRequester(r).onFocusChanged {
        FocusTruth.report(r, it.hasFocus)
        if (it.hasFocus) entry?.focused?.add(id) else entry?.focused?.remove(id)
    }
}

/** Focus [this] once laid out — the next frame(s), no fixed delay. Device pass (P1-3): true only when it HOLDS the focus
 *  ([tryFocus]); a request that went elsewhere (a group's `enter` redirected it) or focused nothing is asked again on the
 *  next frame — by then the focus is inside that group, and the same request lands — and false after [frames]: the
 *  caller's fallback runs (it ran on a "success" that focused the drawing). false = never attached, too. */
suspend fun FocusRequester.requestAfterLayout(frames: Int = 3): Boolean {
    repeat(frames) { withFrameNanos { }; if (tryFocus(this)) return true }
    return false
}

/** The window's last focused control ([FocusableGlass] reports it on focus and on tap) — a closed sheet gives the focus
 *  back to the control that opened it. */
class FocusMemory { var last: FocusRequester? = null }

val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }
