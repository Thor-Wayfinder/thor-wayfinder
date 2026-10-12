package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.AynGlyph
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassSheet
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.Radii
import app.wayfinder.ui.SectionHeader

/**
 * Hub → Quick panel: the AYN button's panel — whether the button opens it, and its
 * shortcuts in the same home-screen-style grid as the panel's own edit mode (✎).
 */
@Composable
fun PanelShortcutsPage(myDisplayId: Int, onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") go: (String) -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.PANEL), "What the AYN button opens: modes, brightness, volume, stats, shortcuts", onBack) {
        // build 18 (C7, pF23): what the switch does now, in a short line (24 words, both states, before)
        SettingCard(
            NavCards.PANEL_AYN,
            if (AppSettings.aynButtonOurs) "On: AYN opens Wayfinder's panel; press it again or ${Say.keyMenu(ThorButton.B)} to close — nothing of AYN's is changed either way."
            else "Off: AYN opens AYN's drawer — turn it on to open Wayfinder's quick panel instead.",
            checked = AppSettings.aynButtonOurs, onChecked = { AppSettings.setAynButtonOursOn(it) },
        )
        if (AppSettings.aynButtonOurs) AynButtonChoices()
        SectionHeader("Shortcuts")
        // build 18 (C10, V18): plain words (the grid's own title and help line are E2's)
        app.wayfinder.ui.Caption("You can also change them in the panel (Arrange).")
        ShortcutGridEditor(columns = 4, cellHeight = 72.dp)
        // 1.4 (§10.2): the rest behind one row
        MoreOptions("the panel takes the controller", cards = listOf(NavCards.PANEL_TAKES)) {
        // 1.3 (GitHub #31)
        SettingCard(
            NavCards.PANEL_TAKES,
            if (AppSettings.panelTakesController) "On — the D-pad and ${Say.menu(ThorButton.A)} move around the panel, ${Say.menu(ThorButton.B)} closes it. The game gets the controller back when it closes."
            else "Off — the panel is touch-only and the game keeps the controller while it's open. The AYN button or its × closes it.",
            checked = AppSettings.panelTakesController, onChecked = { AppSettings.setPanelTakesControllerOn(it) },
        )
        // dev build 17 (WP11): App pairs are on Screens (screen switching: there with the quick panel off too)
        }
    }

/** 1.3 — what a tap and a hold on the AYN button do (Reddit: holding it used to turn a screen off).
 *  Also on the Combos page (1.3.1), where [canFocus] follows that page's modal rule. Build 18 (C4, CTL-06 / VD-08): each a
 *  row like a combo row (its action, its line, the AYN chip); A opens the one action picker in a sheet, on the current
 *  choice; B closes it and the focus is back on the row (the sheet's opener). */
@Composable
internal fun AynButtonChoices(canFocus: Boolean = true) {
    // every action but Open, Back and — build 18 (review) — "Turn Wayfinder off" (one press, no second press to confirm: a
    // combo's two buttons, the tile's two presses). Build 18 (C4 addendum, FTU-11): the tap never offers AYN's drawer — the
    // switch "AYN button opens the panel" gives it (one editor); a tap already on it stays (nothing written)
    val all = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.OPEN && it != ThorAction.BACK &&
        it != ThorAction.WAYFINDER_OFF }
    var open by remember { mutableStateOf<String?>(null) }
    val focus = Modifier.focusProperties { this.canFocus = canFocus }
    val tap = AppSettings.aynTap
    val hold = AppSettings.aynHold
    AynSlotRow(false, tap.title,
        if (tap == ThorAction.AYN_DRAWER) "The same as turning off “${NavCards.PANEL_AYN.title}”" else tap.description,
        "ayn:tap", focus) { open = "tap" }
    AynSlotRow(true, hold?.title ?: SAME_AS_TAP.first, hold?.description ?: SAME_AS_TAP.second, "ayn:hold", focus) { open = "hold" }
    val which = open ?: return
    val first = remember(which) { FocusRequester() }
    GlassSheet(onDismiss = { open = null }, modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.86f), first = first, contentPadding = 0.dp) {
        val isHold = which == "hold"
        ActionPicker(if (isHold) "Pick what a hold does" else "Pick what a tap does",
            if (isHold) all else all - ThorAction.AYN_DRAWER, current = if (isHold) hold else tap, onCancel = { open = null },
            extraFirst = SAME_AS_TAP.takeIf { isHold }, leading = { AynGlyph(isHold, 26.dp) }, focus = first) { a ->
            if (isHold) AppSettings.chooseAynHold(a) else a?.let { AppSettings.chooseAynTap(it) }
            open = null
        }
    }
}

/** The AYN hold's first choice: no action of its own. */
private val SAME_AS_TAP = "Same as a tap" to "Holding it does what a tap does, when you let go"

/** Build 18 (C4): one AYN slot, drawn like a combo row — the action and its line, the AYN chip ([hold]: its hold's). */
@Composable
private fun AynSlotRow(hold: Boolean, title: String, line: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = Modifier.fillMaxWidth().then(modifier), radius = Radii.Card, focusId = id) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(line, color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
            GlassPanel(radius = Radii.Control, strong = true) {
                Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { AynGlyph(hold, 26.dp) }
            }
        }
    }
}
