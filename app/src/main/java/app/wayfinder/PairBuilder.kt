package app.wayfinder

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.glassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Build a pair by picking its two apps: one list per screen, side by side (left =
 * top screen, right = bottom). Replaces "save what's on the screens now", which could
 * only ever see Wayfinder itself from inside Wayfinder (that one lives in the quick
 * panel now: the "App pairs" tile). Closes with Cancel, B / Back or a tap outside.
 */
@Composable
internal fun PairBuilderDialog(onClose: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx).sortedBy { it.label.lowercase() } }
    }
    var top by remember { mutableStateOf<String?>(null) }
    var bottom by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(apps != null) { if (apps != null) { delay(150); runCatching { firstFocus.requestFocus() } } }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(26.dp)
        Box(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)
                .background(g.base.copy(alpha = 0.97f), shape)
                .glassSurface(g, shape, raised = true),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("New app pair", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pick what opens on each screen. " +
                                if (top == null && bottom == null) "Pick at least one app." else pairWhere(LocalContext.current, top, bottom),
                            color = g.textTertiary, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FocusableGlass(onClick = onClose, radius = 14.dp) {
                        Text("Cancel  ·  ${ButtonNames.m("B")}", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    val ready = top != null || bottom != null
                    FocusableGlass(onClick = { if (ready) { Layouts.add(top, bottom); onClose() } }, radius = 14.dp) {
                        Text("Save pair", color = if (ready) g.accent else g.textTertiary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
                val list = apps
                if (list == null) Text("Loading apps…", color = g.textSecondary)
                else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PickColumn("▲ Top screen", list, top, Modifier.weight(1f), firstFocus) { top = it }
                    PickColumn("▼ Bottom screen", list, bottom, Modifier.weight(1f), null) { bottom = it }
                }
            }
        }
    }
}

@Composable
private fun PickColumn(
    title: String, apps: List<AppEntry>, selected: String?, modifier: Modifier,
    focus: FocusRequester?, onPick: (String?) -> Unit,
) {
    val g = LocalGlass.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = g.textSecondary, style = MaterialTheme.typography.labelLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
            item(key = "-") {
                PickRow(null, "Leave that screen as is", selected == null, focus) { onPick(null) }
            }
            items(apps, key = { it.pkg }) { a -> PickRow(a, a.label, selected == a.pkg, null) { onPick(a.pkg) } }
        }
    }
}

@Composable
private fun PickRow(app: AppEntry?, text: String, on: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = Modifier.fillMaxWidth(), radius = 14.dp, focusRequester = focus) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (app?.icon != null) Image(app.icon, null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
            else Box(Modifier.size(28.dp))
            Text(text, color = if (on) g.accent else g.textPrimary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (on) Text("✓", color = g.accent, style = MaterialTheme.typography.titleMedium)
        }
    }
}
