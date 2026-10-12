package app.wayfinder

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.AppIconShape
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.EmptyState
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.ModalHeader
import app.wayfinder.ui.Radii
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.GlassSheet
import app.wayfinder.ui.ScreenIcon
import app.wayfinder.ui.requestAfterLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build a pair by picking its two apps: one list per screen, side by side (left =
 * top screen, right = bottom). Replaces "save what's on the screens now", which could
 * only ever see Wayfinder itself from inside Wayfinder (that one lives in the quick
 * panel now: the "App pairs" tile). Closes with Cancel, B / Back or a tap outside.
 * Build 18 (B8, D16 / hF16 / D17): Save pair is dimmed until an app is picked — pressed then, it says why and puts the focus
 * on the first app; "Leave that screen as is" is ticked only once the other screen has an app; the columns' heads are the
 * two-screen icon and words (▲ / ▼ read as PlayStation's △). [displayId]: where its pill shows.
 */
@Composable
internal fun PairBuilderDialog(displayId: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx).sortedBy { it.label.lowercase() } }
    }
    var top by remember { mutableStateOf<String?>(null) }
    var bottom by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val firstApp = remember { FocusRequester() }
    val topList = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the apps come after the sheet: their first row then (the sheet opened on its first control meanwhile)
    LaunchedEffect(apps != null) { if (apps != null) firstFocus.requestAfterLayout() }

    // dev build 16 (WP7): an in-window sheet (was a Dialog window)
    GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), first = firstFocus, contentPadding = 0.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // dev build 17 (WP8): Cancel on the left, Save on the right
            val ready = top != null || bottom != null
            ModalHeader("New app pair", onClose, closeLabel = "Cancel",
                subtitle = "Pick what opens on each screen." + if (ready) " " + pairWhere(LocalContext.current, top, bottom) else "") {
                GlassButton("Save pair", { Layouts.add(top, bottom); onClose() }, kind = ButtonKind.PRIMARY, enabled = ready,
                    onDisabledClick = {
                        Overlays.pill("Pick an app for the top or the bottom screen", CueOrigin.Window(displayId))
                        scope.launch { runCatching { topList.scrollToItem(1) }; firstApp.requestAfterLayout() }
                    })
            }
            val list = apps
            if (list == null) EmptyState("Loading apps…")
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PickColumn(true, list, top, bottom != null, Modifier.weight(1f), firstFocus, firstApp, topList) { top = it }
                PickColumn(false, list, bottom, top != null, Modifier.weight(1f), null, null, rememberLazyListState()) { bottom = it }
            }
        }
    }
}

/** One screen's list ([top]: the top screen's). [otherPicked]: the other screen has an app — only then is "Leave that screen
 *  as is" the choice here (with nothing picked anywhere, nothing is ticked). [firstApp]: its first app (Save pair's way back). */
@Composable
private fun PickColumn(
    top: Boolean, apps: List<AppEntry>, selected: String?, otherPicked: Boolean, modifier: Modifier,
    focus: FocusRequester?, firstApp: FocusRequester?, state: androidx.compose.foundation.lazy.LazyListState,
    onPick: (String?) -> Unit,
) {
    val g = LocalGlass.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScreenIcon(top)
            Text(if (top) "Top screen" else "Bottom screen", color = g.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
            item(key = "-") {
                PickRow(null, "Leave that screen as is", selected == null && otherPicked, focus) { onPick(null) }
            }
            itemsIndexed(apps, key = { _, a -> a.pkg }) { i, a -> PickRow(a, a.label, selected == a.pkg, firstApp.takeIf { i == 0 }) { onPick(a.pkg) } }
        }
    }
}

@Composable
private fun PickRow(app: AppEntry?, text: String, on: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = Modifier.fillMaxWidth(), radius = Radii.Control, focusRequester = focus) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (app?.icon != null) Image(app.icon, null, Modifier.size(28.dp).clip(AppIconShape))
            else Box(Modifier.size(28.dp))
            Text(text, color = if (on) g.accent else g.textPrimary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // dev build 17 (WP8): the list's check mark (GlassChoiceRow's), not a typed ✓
            if (on) Icon(Icons.Rounded.Check, null, tint = g.accent, modifier = Modifier.size(18.dp))
        }
    }
}
