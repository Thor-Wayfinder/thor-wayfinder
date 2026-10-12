package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.GlassSheet

private fun label(ctx: android.content.Context, pkg: String?): String =
    if (pkg == null) "(as is)" else runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

/** "Firefox + Spotify", or just "Firefox" when the other screen is left as it is. */
internal fun pairTitle(ctx: android.content.Context, top: String?, bottom: String?) =
    listOfNotNull(top, bottom).joinToString("  +  ") { label(ctx, it) }

/** "Firefox on top · Spotify below". Build 18 (E3, A0's rule): words only — a ▲ / ▼ screen mark read as PlayStation's △. */
internal fun pairWhere(ctx: android.content.Context, top: String?, bottom: String?) = listOf(
    top?.let { "${label(ctx, it)} on top" } ?: "top screen unchanged",
    bottom?.let { "${label(ctx, it)} below" } ?: "bottom screen unchanged",
).joinToString(" · ")

/**
 * The quick panel's "App pairs" tile: open a saved pair (the panel closes, both apps
 * go to their screens), or save the two apps under the panel as a new pair. Editing
 * (swap ⇅, delete, build from a list) stays on Wayfinder's App pairs page, under Screens ([NavCards.PAIRS]).
 */
@Composable
internal fun PanelPairsDialog(closePanel: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    val (top, bottom) = remember { ForegroundAppService.currentLayout() }
    var saved by remember { mutableStateOf(Layouts.pairs.any { it.top == top && it.bottom == bottom }) }
    // 1.3: drawn over the panel (the panel is an overlay window now — it can't host a Dialog). dev build 16 (WP7): the one
    // in-window sheet (B or a tap outside closes it, the D-pad stays in it, it opens on [first])
    GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.92f), first = first, contentPadding = 0.dp) {
        Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // dev build 17 (WP8): Close on the left with the hand's Back button
            app.wayfinder.ui.ModalHeader("App pairs", onClose, subtitle = "Pick one: both apps open, each on its screen")
            Layouts.pairs.forEachIndexed { i, p ->
                FocusableGlass(
                    onClick = { onClose(); closePanel(); ForegroundAppService.later(350) { ForegroundAppService.openPair(p) } },
                    modifier = Modifier.fillMaxWidth(), radius = app.wayfinder.ui.Radii.Row, focusRequester = if (i == 0) first else null,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(pairTitle(ctx, p.top, p.bottom), color = g.textPrimary,
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pairWhere(ctx, p.top, p.bottom), color = g.textTertiary,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (Layouts.pairs.isEmpty()) app.wayfinder.ui.EmptyState("No pairs yet.")
            val nothing = top == null && bottom == null
            FocusableGlass(
                onClick = { if (!nothing && !saved) { Layouts.add(top, bottom); saved = true } },
                modifier = Modifier.fillMaxWidth(), radius = app.wayfinder.ui.Radii.Row,
                focusRequester = if (Layouts.pairs.isEmpty()) first else null,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(when { nothing -> "Nothing to save — no app on the screens"; saved -> "✓ Saved as a pair"; else -> "+ Save what's on the screens now" },
                        color = if (nothing) g.textTertiary else g.accent, style = MaterialTheme.typography.bodyLarge)
                    if (!nothing) Text(pairWhere(ctx, top, bottom), color = g.textTertiary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // build 18: the page moved under Screens (dev build 17, WP11) — the path from the registry, never typed
            Text("Swap, delete or build pairs from a list: ${Say.path(NavCards.PAIRS, inWayfinder = true)}.",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Build 18 (GitHub #71): the quick panel's "Screenshot" tile asks which screen, this time — the top, the bottom or both
 * (stacked). It opens on the usual one (Screens' choice, the one the combo takes without asking): A then A shoots it as
 * before. The panel closes first: the picture is of the game, not of the panel.
 */
@Composable
internal fun PanelShotSheet(closePanel: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val first = remember { FocusRequester() }
    val usual = AppSettings.shotTarget
    val two = remember { ForegroundAppService.hasSecondScreen() }
    // without a second screen, only the top one can be shot (the others would shoot it too)
    fun can(t: ShotTarget) = t == ShotTarget.TOP || two
    val opensOn = if (can(usual)) usual else ShotTarget.TOP
    GlassSheet(onDismiss = onClose, modifier = Modifier.fillMaxWidth(0.92f), first = first, contentPadding = 0.dp) {
        Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            app.wayfinder.ui.ModalHeader("Screenshot", onClose, subtitle = "Which screen?")
            for (t in ShotTarget.values()) androidx.compose.runtime.key(t) {
                val ok = can(t)
                FocusableGlass(
                    onClick = {
                        if (ok) { onClose(); closePanel(); ForegroundAppService.later(600) { ForegroundAppService.screenshot(t) } }
                    },
                    modifier = Modifier.fillMaxWidth(), radius = app.wayfinder.ui.Radii.Row,
                    focusRequester = if (t == opensOn) first else null,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (t == ShotTarget.BOTH) "Both (stacked)" else "${t.label} screen",
                                color = if (ok) g.textPrimary else g.textTertiary, style = MaterialTheme.typography.bodyLarge)
                            if (!ok) Text("No second screen now", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (t == usual) Text(Say.usual(null), color = g.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text("The screenshot combo doesn't ask: it takes the usual one, set in ${Say.path(NavCards.SCREENSHOTS, inWayfinder = true)}.",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
