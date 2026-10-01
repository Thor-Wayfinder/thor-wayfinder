package app.wayfinder

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.ThorGlassTheme
import app.wayfinder.ui.VSpace
import kotlinx.coroutines.delay

/**
 * The Control Deck: a glass companion on the SECOND screen while the Hub is open.
 * It's a live cheat sheet of the user's controls (every combo + gesture), what's on
 * each screen, and the controller-focus state — so you tweak settings on top and see
 * the result below. Uses Android's Presentation API; Compose is hosted by giving the
 * ComposeView the host activity's lifecycle/VM/saved-state owners.
 */
class CompanionPresentation(
    private val host: ComponentActivity,
    display: Display,
) : Presentation(host, display) {

    private val padFallback = app.wayfinder.ui.PadFallbackFilter()

    /** Real glass on this screen too: blur what's behind the deck (the bottom screen's app/launcher). */
    private val realGlass = androidx.compose.runtime.mutableStateOf(false)
    fun applyBackdrop(on: Boolean) {
        realGlass.value = on
        val w = window ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            w.setBackgroundBlurRadius(if (on) AppSettings.glassBlurPx() else 0)
            // Also blur-behind (the other system blur path) for this dialog-type window.
            if (on) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = if (on) AppSettings.glassBlurPx() else 0; dimAmount = 0f }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (padFallback.shouldDrop(event)) return true   // R3/L3/Start must not "click" a tile
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                val dark = when (AppSettings.themeMode) {
                    ThemeMode.DARK, ThemeMode.BLACK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                }
                androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalRealGlass provides (realGlass.value && !AppSettings.flatLook)) {
                    ThorGlassTheme(dark = dark) { app.wayfinder.ui.CappedFontScale { CompanionScreen() } }
                }
            }
        }
        setContentView(view)
        // Let the aurora run edge-to-edge under the second screen's nav/gesture bar.
        window?.let { w ->
            // Transparent window so the system blur (real glass) can show through.
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
}

@androidx.compose.runtime.Composable
private fun CompanionScreen() {
    // While the Hub is open it IS the top app, so "swap"/"recents" tiles would do
    // nothing useful here. Instead the deck is a live cheat sheet of YOUR controls:
    // tweak settings on top, see the combos + gestures that result below.
    val g = LocalGlass.current
    var displayApps by remember { mutableStateOf(mapOf<Int, String>()) }
    var displayIds by remember { mutableStateOf(listOf<Int>()) }
    LaunchedEffect(Unit) {
        while (true) {
            displayApps = ForegroundAppService.displayApps.toMap()
            displayIds = ForegroundAppService.availableDisplayIds
            delay(1500)
        }
    }
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue
    val bindings = ControlsStore.active()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Each card: badges for the combo · action icon · action name.
    class Card(val glyph: @androidx.compose.runtime.Composable () -> Unit, val icon: androidx.compose.ui.graphics.vector.ImageVector, val what: String)
    val cards = buildList {
        bindings.forEach { b -> add(Card({ app.wayfinder.ui.TriggerGlyphs(b.trigger, 28.dp) }, app.wayfinder.ui.actionIcon(b.action), OpenTargets.title(ctx, b))) }
        when (AppSettings.gestureBlankMode) {
            BlankGesture.TAP -> add(Card({ app.wayfinder.ui.ThreeFingerGlyph(28.dp) }, Icons.Rounded.DarkMode, "3-finger tap · bottom screen off / on"))
            BlankGesture.SWIPE -> add(Card({ app.wayfinder.ui.ThreeFingerGlyph(28.dp, swipe = true) }, Icons.Rounded.DarkMode, "3-finger swipe · bottom screen off"))
            BlankGesture.OFF -> {}
        }
        // 1.3.1 — the AYN button, when its tap or hold does something else than the quick panel
        if (AppSettings.aynButtonOurs) {
            if (AppSettings.aynTap != ThorAction.QUICK_MENU)
                add(Card({ app.wayfinder.ui.AynGlyph(false, 28.dp) }, app.wayfinder.ui.actionIcon(AppSettings.aynTap), AppSettings.aynTap.title))
            AppSettings.aynHold?.let { a -> add(Card({ app.wayfinder.ui.AynGlyph(true, 28.dp) }, app.wayfinder.ui.actionIcon(a), a.title)) }
        }
    }
    GlassScreen(span = app.wayfinder.ui.AuroraSpan.BOTTOM) {
        Column(
            Modifier.fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your controls", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                    // one line, words only (it wrapped, leaving "to" alone on a line)
                    Text(
                        if (!AppSettings.focusLockEnabled) "Controller: follows your touch" + if (AppSettings.focusSticky) " until you send it" else ""
                        else if (AppSettings.focusSticky) "Controller: stays where you send it"
                        else "Controller: locked to the ${if (AppSettings.focusLockTop) "top" else "bottom"} screen",
                        color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                }
                ScreenChip("TOP", displayApps[displayIds.getOrNull(0) ?: 0] ?: "app.wayfinder", Modifier.weight(0.6f))
                ScreenChip("BOTTOM", displayApps[displayIds.getOrNull(1) ?: 4], Modifier.weight(0.6f))
            }
            // Two-column cheat sheet — takes the space left, scrolls if there are many
            // combos, so the Clear button below always stays on screen. Compact enough for the
            // default 12 combos + the 3-finger card; beyond that a fade says "more below" (a
            // half row cut off by the edge looked broken — release critique 2026-09-24).
            val listScroll = androidx.compose.foundation.rememberScrollState()
            val fadeColor = g.base
            Column(Modifier.weight(1f).verticalScroll(listScroll)
                .drawWithContent {
                    drawContent()
                    if (listScroll.canScrollForward) drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(androidx.compose.ui.graphics.Color.Transparent, fadeColor.copy(alpha = 0.85f)),
                            startY = size.height - 36.dp.toPx(), endY = size.height),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 36.dp.toPx()),
                    )
                },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cards.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        GlassPanel(Modifier.weight(1f), radius = 16.dp) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                c.glyph()
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                androidx.compose.material3.Icon(c.icon, null, tint = g.accent, modifier = Modifier.size(20.dp))
                                Text(c.what, color = g.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    modifier = Modifier.widthIn(max = 120.dp))
                            }
                        }
                    }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            }
            FocusableGlass(onClick = { ActionRegistry.perform(ThorAction.CLEAR_BACKGROUND) }, modifier = Modifier.fillMaxWidth(), radius = 20.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                    androidx.compose.material3.Icon(Icons.Rounded.DeleteSweep, null, tint = g.accent, modifier = Modifier.size(22.dp))
                    Text("Close background apps", color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ScreenChip(label: String, app: String?, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    GlassPanel(modifier, radius = 16.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = g.textTertiary, style = MaterialTheme.typography.labelLarge)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val name = androidx.compose.runtime.remember(app) {
                app?.let { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it.substringAfterLast('.')) } ?: "Home"
            }
            Text(name, color = g.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@androidx.compose.runtime.Composable
private fun DeckTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier.fillMaxHeight(), radius = 22.dp) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(icon, null, tint = g.accent, modifier = Modifier.size(40.dp))
            VSpace(10)
            Text(label, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        }
    }
}
