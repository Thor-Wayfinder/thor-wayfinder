package app.wayfinder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.PadLook
import app.wayfinder.Say
import app.wayfinder.ThorButton

/*
 * Build 18 (A14, decision H3): a quiet legend in a page's header — what the controller's
 * buttons do on the focused control ("(A) choose · ◀▶ move", "◀▶ change"), in the hand's own badges (A7's tokens), shown
 * only while a controller drives (a touch hides it until the next pad key: InputMode). The components say what they are
 * while they have the focus ([FocusRole], via FocusableGlass's role / [focusRole]); the page's GlassScreen keeps it
 * ([LocalFocusRole]); PageHeader draws it — its ONE call site: a "no" deletes that line (the roles then cost nothing).
 * Game controls has its own legend and no PageHeader: none there. A sheet's controls set none (its header has no legend).
 */

/** What the focused control is, for the legend. */
enum class FocusRole { OPTION, PICK, SLIDER, SWITCH, OPEN, LIST }

/** The legend's words for [role] on [look]'s controller — button tokens ([Say.keyMenu]), drawn as badges by GlyphText. */
internal fun legendWords(role: FocusRole, look: PadLook): String {
    val a = Say.keyMenu(ThorButton.A, look)
    return when (role) {
        FocusRole.OPTION -> "$a choose · ◀▶ move"      // a choice among options side by side
        FocusRole.PICK -> "$a choose"                  // an open list's option (GlassChoiceRow)
        FocusRole.SLIDER -> "◀▶ change"
        FocusRole.SWITCH -> "$a on / off"
        FocusRole.OPEN -> "$a open"                    // a row that opens a page (GlassListRow's chevron)
        FocusRole.LIST -> "$a open the list"           // a folded choice (GlassChoiceRow)
    }
}

/** The page's focused control's role (one per GlassScreen): set by the control that gains the focus, cleared by the one
 *  that loses it — only if it is still the one set (the next control's focus can arrive first). */
class FocusRoleState internal constructor() {
    var role by mutableStateOf<FocusRole?>(null)
        private set
    private var owner: Any? = null
    fun set(who: Any, r: FocusRole) { owner = who; role = r }
    fun clear(who: Any) { if (owner === who) { owner = null; role = null } }
}

/** The page's [FocusRoleState] (GlassScreen provides one; a sheet provides none). */
val LocalFocusRole = staticCompositionLocalOf<FocusRoleState?> { null }

/** Tells the page's legend that this focus target is a [role] while it has the focus (put it before `.focusable()`). */
fun Modifier.focusRole(role: FocusRole?): Modifier = if (role == null) this else composed {
    val state = LocalFocusRole.current
    if (state == null) this else {
        val me = remember { Any() }
        DisposableEffect(state) { onDispose { state.clear(me) } }
        onFocusChanged { if (it.isFocused) state.set(me, role) else state.clear(me) }
    }
}

/** The legend's slot in a header: the longest words fit ("(A) choose · ◀▶ move" at labelMedium with an 18 dp badge). */
val LEGEND_WIDTH = 170.dp
/** A header narrower than this has no legend (the Thor's bottom screen, ~400 dp: the title needs the room; the top
 *  screen's header is ~690 dp). */
val LEGEND_MIN_HEADER = 520.dp

/** The legend itself (PageHeader's trailing slot; [modifier] sizes the slot). Nothing at all after a touch; while a
 *  controller drives, its slot stays — the header's words don't re-wrap as the focus moves — and holds the focused
 *  control's words (none on a control without a role: a button, a tile). Build 18 review (C): in the hand's badges
 *  ([ButtonNames.handLook], as the header's Back pill) — not the page's look (a controller's page provides its own names:
 *  the Thor in hand there read ✕), and the Thor's while the controller in hand is the one being set up. */
@Composable
fun ButtonLegend(modifier: Modifier = Modifier) {
    val state = LocalFocusRole.current ?: return
    if (InputMode.lastWasTouch) return
    val role = state.role
    val g = LocalGlass.current
    val hand = app.wayfinder.ButtonNames.handLook
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        // the hand's look for the badges too (GlyphText draws a token with the composition's look)
        if (role != null) CompositionLocalProvider(LocalPadLook provides hand) {
            GlyphText(legendWords(role, hand), color = g.textTertiary,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, badge = 18.dp)
        }
    }
}
