package app.wayfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader

/**
 * Hub → Quick panel: the AYN button's panel — whether the button opens it, and its
 * shortcuts in the same home-screen-style grid as the panel's own edit mode (✎).
 */
@Composable
fun PanelShortcutsPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, "Quick panel", "Press the AYN button: screen modes, brightness, volume, live stats and your shortcuts — on the bottom screen", onBack) {
        SettingCard(
            "The AYN button opens the quick panel",
            if (AppSettings.aynButtonOurs) "Press it again (or B) to close it. Off: the AYN button opens AYN's own drawer again — nothing of AYN's is changed either way."
            else "Off — the AYN button opens AYN's drawer. Turn on to open Wayfinder's quick panel instead.",
            checked = AppSettings.aynButtonOurs, onChecked = { AppSettings.setAynButtonOursOn(it) },
        )
        if (AppSettings.aynButtonOurs) AynButtonChoices()
        // 1.3 (GitHub #31)
        SettingCard(
            "The panel takes the controller",
            if (AppSettings.panelTakesController) "On — the D-pad and A move around the panel, B closes it. The game gets the controller back when it closes."
            else "Off — the panel is touch-only and the game keeps the controller while it's open. The AYN button or ✕ closes it.",
            checked = AppSettings.panelTakesController, onChecked = { AppSettings.setPanelTakesControllerOn(it) },
        )
        // App pairs are opened from the panel: they live here now (critique 2026-09-25)
        app.wayfinder.ui.GlassListRow("App pairs", value = "${Layouts.pairs.size} saved · open from the panel's “App pairs” tile",
            icon = androidx.compose.material.icons.Icons.Rounded.ViewAgenda) { go(HubPage.PAIRS) }
        SectionHeader("Shortcuts — also editable in the panel (its Arrange button)")
        ShortcutGridEditor(columns = 4, cellHeight = 72.dp)
    }

/** 1.3 — what a tap and a hold on the AYN button do (Reddit: holding it used to turn a screen off). */
@Composable
private fun AynButtonChoices() {
    val actions = ThorAction.values().filter { ActionRegistry.isImplemented(it) && it != ThorAction.OPEN && it != ThorAction.BACK }
    var open by remember { mutableStateOf<String?>(null) }
    GlassListRow("AYN button — tap", value = AppSettings.aynTap.title) { open = if (open == "tap") null else "tap" }
    if (open == "tap") AynActionList(AppSettings.aynTap, actions) { a -> a?.let { AppSettings.chooseAynTap(it) }; open = null }
    GlassListRow("AYN button — hold", value = AppSettings.aynHold?.title ?: "Same as a tap") { open = if (open == "hold") null else "hold" }
    if (open == "hold") AynActionList(AppSettings.aynHold, listOf<ThorAction?>(null) + actions) { AppSettings.chooseAynHold(it); open = null }
}

@Composable
private fun AynActionList(current: ThorAction?, options: List<ThorAction?>, onPick: (ThorAction?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (a in options) AynActionRow(a, a == current, onPick)
    }
}

@Composable
private fun AynActionRow(a: ThorAction?, selected: Boolean, onPick: (ThorAction?) -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = { onPick(a) }, radius = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(if (selected) Modifier.background(g.accent.copy(alpha = .9f), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(a?.title ?: "Same as a tap", color = if (selected) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge)
            Text(a?.description ?: "Holding it does what a tap does, when you let go",
                color = if (selected) Color.White.copy(alpha = .85f) else g.textSecondary,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
