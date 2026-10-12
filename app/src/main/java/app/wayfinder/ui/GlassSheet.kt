package app.wayfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * dev build 16 (WP7): popups are drawn in the window, never in a Dialog window — a Dialog is another window, where the
 * window's controller rules don't apply (R3 / Start "clicked", the stick moved through Android's one-latch adapter, MODE
 * went home) and B went to the dialog's own back handling.
 */

/** A window's open sheets, drawn over its page ([WindowRoot]). */
class SheetHost internal constructor(internal val scope: kotlinx.coroutines.CoroutineScope) {
    internal val sheets = mutableStateListOf<SheetEntry>()
}

internal class SheetEntry { var content: @Composable () -> Unit by mutableStateOf({}) }

val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }

/** The window root draws every open sheet after the page, in the order they opened. */
@Composable
internal fun SheetLayer(host: SheetHost) {
    for (s in host.sheets) key(s) { s.content() }
}

/**
 * A popup in the window: a scrim, a glass card ([modifier] sizes it — today's dialogs: 0.55 … 0.94 of the width),
 * B or a tap outside closes it ([onDismiss]), the D-pad can't leave it, it opens on [first] (else its first control) and
 * gives the focus back to the control that opened it. Called anywhere (deep in a scrolling page): drawn by the window's
 * [SheetHost] with the caller's composition locals (names, theme, Back depth); no host → drawn in place.
 */
@Composable
fun GlassSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(0.7f),
    first: FocusRequester? = null,
    contentPadding: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    val dismiss by rememberUpdatedState(onDismiss)
    if (host == null) { SheetFrame({ dismiss() }, modifier, first, contentPadding, null, content); return }
    val locals = currentCompositionLocalContext
    val entry = remember { SheetEntry() }
    entry.content = { CompositionLocalProvider(locals) { SheetFrame({ dismiss() }, modifier, first, contentPadding, host, content) } }
    DisposableEffect(host) {
        host.sheets += entry
        onDispose { host.sheets -= entry }
    }
}

@Composable
private fun SheetFrame(onDismiss: () -> Unit, modifier: Modifier, first: FocusRequester?, contentPadding: Dp, host: SheetHost?,
                       content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    // the control that opened it (focused, or tapped) gets the focus back when it closes
    val mem = LocalFocusMemory.current
    val opener = remember { mem?.last }
    DisposableEffect(Unit) { onDispose { if (host != null && opener != null) host.scope.launch { opener.requestAfterLayout() } } }
    val shape = RoundedCornerShape(Radii.Modal)
    BackLayer(onBack = onDismiss) {
        // the scrim covers the whole window; the card stays clear of the system bars (a Dialog window's insets)
        Box(Modifier.fillMaxSize().background(g.modalDim).pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .systemBarsPadding(), contentAlignment = Alignment.Center) {
            // build 18 (A14): a sheet's controls tell no legend (its header has none; the page's goes quiet while it is open)
            // device pass (final): and no page edge scroll — the page behind never scrolls from a choice in the popup
            CompositionLocalProvider(LocalInSheet provides true, LocalFocusRole provides null, LocalPageEdgeScroll provides null) {
                val entry = rememberEntryFocus(first)
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                CompositionLocalProvider(LocalEntryFocus provides entry) {
                    Column(modifier
                        .pointerInput(Unit) { detectTapGestures { } }               // a tap on the card isn't "outside"
                        .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()   // the page behind is out of reach
                        // dev build 17 (WP8): one popup surface (was the base at .97 under raised frost)
                        .modalSurface(g, shape)
                        .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
                }
            }
        }
    }
}

/** dev build 17 (WP8): a popup's or sheet's card — opaque [GlassColors.surfaceModal] with the glass rim and light, no frost and
 *  no lens (GlassSheet, Game controls' and Combos' popups, the tile picker). Their dim: [modalDim]. Build 18 (A12): fully
 *  opaque now (alpha FF); glassLight draws its bevel over it with the default SrcOver — nothing of the page comes through. */
fun Modifier.modalSurface(g: GlassColors, shape: Shape): Modifier = this
    .clip(shape)
    .background(g.surfaceModal, shape)
    .glassLight(shape, g.dark)
    .border(1.dp, Brush.linearGradient(0f to g.rimTop, 0.42f to Color.Transparent, 1f to g.rimBottom), shape)
