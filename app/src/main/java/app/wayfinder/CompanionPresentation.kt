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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader
import app.wayfinder.ui.ThorGlassTheme
import app.wayfinder.ui.Radii
import app.wayfinder.ui.fillSubtle
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
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
    // dev build 16 (WP7): the Hub's stick / D-pad adapter (latched per controller, touch mode left first), as in every
    // Wayfinder window — the dialog had Android's own one-latch adapter
    private val nav = app.wayfinder.ui.PadNav(
        focusView = { window?.decorView?.findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) },
        send = { dispatchKeyEvent(it) },
        touchMode = { window?.decorView?.isInTouchMode == true },
    )
    // dev build 16 (WP7): a Back dispatcher of its own — B never dismisses the sheet (Dialog's Back cancelled it while the
    // Hub still pointed at it), and a list's layer can't land in the Hub's window through the context
    private val back = androidx.activity.OnBackPressedDispatcher()
    private val backOwner = object : androidx.activity.OnBackPressedDispatcherOwner {
        override val lifecycle: androidx.lifecycle.Lifecycle get() = host.lifecycle
        override val onBackPressedDispatcher: androidx.activity.OnBackPressedDispatcher get() = back
    }

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
        // dev build 14 (critic 2): a connected controller's MODE (its Home is the game's) — unhandled, Android makes it Home
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_MODE) return true
        if (padFallback.shouldDrop(event)) return true   // R3/L3/Start must not "click" a tile
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean = nav.onMotion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) nav.stop() }

    override fun onStop() { nav.stop(); super.onStop() }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (back.hasEnabledCallbacks()) back.onBackPressed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // build 18 (E1, pF07): the Hub's screen — its chip reads "Wayfinder" (the sheet only shows while the Hub is open there)
        @Suppress("DEPRECATION") val hubDisplay = host.windowManager.defaultDisplay.displayId
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
                // dev build 15 (WP2): buttons named as on the controller in hand — dev build 16 (WP7): through WindowRoot, with
                // the window's own Back path and sheets
                androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalRealGlass provides (realGlass.value && !AppSettings.flatLook),
                    androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides backOwner) {
                    ThorGlassTheme(dark = dark) {
                        // build 18 (E6, D1): this screen's status-bar icons follow the theme's colours (dark icons on a light one)
                        val lightBars = !LocalGlass.current.dark
                        androidx.compose.runtime.SideEffect {
                            window?.let { w -> androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightStatusBars = lightBars }
                        }
                        app.wayfinder.ui.CappedFontScale { app.wayfinder.ui.WindowRoot { CompanionScreen(hubDisplay) } }
                    }
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

/** Build 18 (E1, D22): a card's label column — at most this wide, the same in every card, so the action icon sits at one x
 *  (it zigzagged with each label's length). */
private val CARD_LABEL_MAX = 150.dp
/** A card's fixed parts beside its glyph and label: 12 dp before the glyph, 10 dp after the label (most end well short of it),
 *  the 20 dp icon, 8 dp before it and 6 dp after it. */
private val CARD_PADDING = 12.dp
private val CARD_PADDING_END = 10.dp
private val CARD_ICON = 20.dp
private val CARD_GAP_GLYPH = 8.dp
private val CARD_GAP_LABEL = 6.dp

@androidx.compose.runtime.Composable
private fun CompanionScreen(hubDisplay: Int) {
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
    // build 18 (E11, FTU-08): the tour on the top screen — the sheet stays calm: its combos dimmed under one line, no button (a
    // new user saw every combo and a "Close background apps" here). Not State, so read every 500 ms: in front, or the Hub's page
    // (the controller sent down here takes the tour's window focus, not its page)
    var tour by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            tour = TourPractice.inFront || app.wayfinder.ui.ReturnFocus.shown == HubPage.TOUR
            delay(500)
        }
    }
    // build 18 (E1, pF05): B gives the controller back to the Hub's screen (B did nothing here: the controller was stuck on a
    // sheet whose only button closed apps). Review: as the Hub took it, not by the "controller to the top screen" combo — that
    // one saves the lock ("Always on the bottom screen" became "Always on the top screen" after one B)
    app.wayfinder.ui.BackLayer(onBack = { ForegroundAppService.controllerToHub(hubDisplay) })
    @Suppress("UNUSED_VARIABLE") val v = ControlsStore.version.intValue
    val bindings = ControlsStore.active()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Each card: badges for the combo · action icon · action name.
    class Card(val glyph: @androidx.compose.runtime.Composable () -> Unit, val icon: androidx.compose.ui.graphics.vector.ImageVector, val what: String)
    val cards = buildList {
        // build 18 (E1, D22 / pF20): the actions' short names; no "Close background apps" card — the button below says it
        bindings.filter { it.action != ThorAction.CLEAR_BACKGROUND }.forEach { b ->
            add(Card({ app.wayfinder.ui.TriggerGlyphs(b.trigger, 28.dp) }, app.wayfinder.ui.actionIcon(b.action),
                if (b.action == ThorAction.OPEN) OpenTargets.label(ctx, b.arg) else b.action.shortTitle))
        }
        // the 3-finger gesture in the Screens card's words (B7: Never / Tap / Swipe down); the moon says what it does
        when (AppSettings.gestureBlankMode) {
            BlankGesture.TAP -> add(Card({ app.wayfinder.ui.ThreeFingerGlyph(28.dp) }, Icons.Rounded.DarkMode, "3 fingers: tap"))
            BlankGesture.SWIPE -> add(Card({ app.wayfinder.ui.ThreeFingerGlyph(28.dp, swipe = true) }, Icons.Rounded.DarkMode, "3 fingers: swipe down"))
            BlankGesture.OFF -> {}
        }
        // 1.3.1 — the AYN button, when its tap or hold does something else than the quick panel
        if (AppSettings.aynButtonOurs) {
            if (AppSettings.aynTap != ThorAction.QUICK_MENU)
                add(Card({ app.wayfinder.ui.AynGlyph(false, 28.dp) }, app.wayfinder.ui.actionIcon(AppSettings.aynTap), AppSettings.aynTap.shortTitle))
            AppSettings.aynHold?.let { a -> add(Card({ app.wayfinder.ui.AynGlyph(true, 28.dp) }, app.wayfinder.ui.actionIcon(a), a.shortTitle)) }
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
                    Text("Your combos", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                    // one line, words only (it wrapped, leaving "to" alone on a line). Build 18 (E1, D10): the strip's words
                    Text("Controller: ${Say.controllerMode()}",
                        color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                // build 18 (E1, pF07 / hF12): the Hub's screen is Wayfinder's (displayApps never tracks it: the TOP chip named
                // the game behind the Hub); the other one its app, else its home screen
                val top = displayIds.getOrNull(0) ?: 0
                val bottom = displayIds.getOrNull(1) ?: 4
                ScreenChip("TOP", displayApps[top], top == hubDisplay, Modifier.weight(0.6f))
                ScreenChip("BOTTOM", displayApps[bottom], bottom == hubDisplay, Modifier.weight(0.6f))
            }
            // Two-column cheat sheet — takes the space left, scrolls if there are many
            // combos, so the Clear button below always stays on screen. Compact enough for the
            // default 12 combos + the 3-finger card; beyond that a fade says "more below" (a
            // half row cut off by the edge looked broken — release critique 2026-09-24).
            val listScroll = androidx.compose.foundation.rememberScrollState()
            val fadeColor = g.base
            if (tour) Text("The tour is on the top screen", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            WithWidestGlyph(cards.map { it.glyph }, Modifier.weight(1f).fillMaxWidth().alpha(if (tour) 0.5f else 1f)) { maxWidth, glyphW ->
                // one label width for every card (two columns 10 dp apart): the card less its fixed parts and the widest combo
                // on THIS sheet, measured. Review (E1): a fixed 100 dp reserve left the Thor's labels ~84 dp — "Lock the
                // controller", "Keyboard & mouse" ended in "…" (the widest, AYN + hold, is ~87 dp; a DualSense's chips differ)
                val labelW = ((maxWidth - 10.dp) / 2 - CARD_PADDING - CARD_PADDING_END - CARD_GAP_GLYPH - CARD_ICON - CARD_GAP_LABEL - glyphW)
                    .coerceIn(72.dp, CARD_LABEL_MAX)
                Column(Modifier.fillMaxSize().verticalScroll(listScroll)
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
                            row.forEach { c -> ComboCard(c.glyph, c.icon, c.what, labelW, Modifier.weight(1f)) }
                            if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            // build 18 (E1, pF05): two presses — the sheet's only focus target closed every background app on one A. (E11) Not
            // while the tour is on
            if (!tour) ConfirmButton(ThorAction.CLEAR_BACKGROUND.title, "Press again to close them",
                { ActionRegistry.perform(ThorAction.CLEAR_BACKGROUND) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteSweep)
        }
    }
}

/** Build 18 review (E1): [content] laid out in the space given, told its width and the widest of [glyphs] as they draw — measured
 *  in the same pass (no frame drawn with a guess), so the label column is all the card has left. */
@androidx.compose.runtime.Composable
private fun WithWidestGlyph(glyphs: List<@androidx.compose.runtime.Composable () -> Unit>, modifier: Modifier,
                            content: @androidx.compose.runtime.Composable (maxWidth: androidx.compose.ui.unit.Dp, glyphW: androidx.compose.ui.unit.Dp) -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        val glyphW = subcompose("glyphs") { glyphs.forEach { gl -> androidx.compose.foundation.layout.Box { gl() } } }
            .maxOfOrNull { it.measure(androidx.compose.ui.unit.Constraints()).width } ?: 0
        val body = subcompose("body") { content(c.maxWidth.toDp(), glyphW.toDp()) }.map { it.measure(c) }
        layout(body.maxOfOrNull { it.width } ?: c.minWidth, body.maxOfOrNull { it.height } ?: c.minHeight) {
            body.forEach { it.place(0, 0) }
        }
    }
}

/** One combo: its badges (left), then the action's icon at a fixed x and its name in [labelW] (build 18, E1 / D22). Review (E1):
 *  a name that still doesn't fit (a larger font size, "Bottom screen off/on") goes on two balanced lines — never a "…" on the
 *  default names, never one word alone on a line (two lines of bodySmall fit beside the 28 dp glyph at the Thor's font size). */
@androidx.compose.runtime.Composable
private fun ComboCard(glyph: @androidx.compose.runtime.Composable () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector,
                      what: String, labelW: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val g = LocalGlass.current
    GlassPanel(modifier, radius = Radii.Row) {
        Row(Modifier.padding(start = CARD_PADDING, end = CARD_PADDING_END, top = 4.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { glyph() }
            androidx.compose.foundation.layout.Spacer(Modifier.width(CARD_GAP_GLYPH))
            androidx.compose.material3.Icon(icon, null, tint = g.accent, modifier = Modifier.size(CARD_ICON))
            androidx.compose.foundation.layout.Spacer(Modifier.width(CARD_GAP_LABEL))
            Text(what, color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(labelW))
        }
    }
}

/** A screen and what it shows: [hub] = the Hub's screen (Wayfinder), else [app]'s name, else its home screen. Build 18 (E11,
 *  V07 / MORE-D35): drawn flat — a plain fill, no glass rim or light — so it reads as a label, not a button. */
@androidx.compose.runtime.Composable
private fun ScreenChip(label: String, app: String?, hub: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    androidx.compose.foundation.layout.Box(modifier.background(g.fillSubtle, androidx.compose.foundation.shape.RoundedCornerShape(Radii.Row))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = g.textTertiary, style = MaterialTheme.typography.labelLarge)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val name = androidx.compose.runtime.remember(app, hub) {
                if (hub) NavPages.title(HubPage.HOME)
                else app?.let { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it.substringAfterLast('.')) } ?: "Home screen"
            }
            Text(name, color = g.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
