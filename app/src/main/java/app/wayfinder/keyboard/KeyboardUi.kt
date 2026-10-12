package app.wayfinder.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.ThorButton
import app.wayfinder.ui.ButtonGlyph
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.ScreenIcon
import app.wayfinder.ui.glassLight
import app.wayfinder.ui.Radii
import app.wayfinder.ui.fillChip
import app.wayfinder.ui.fillSubtle
import app.wayfinder.ui.fillWell
import app.wayfinder.ui.keyFill
import app.wayfinder.ui.pressVeil
import app.wayfinder.ui.surfaceOverlay
import app.wayfinder.ui.surfaceSolid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Thor Keyboard, shared by both hosts:
 *  - [overlay] = true: it owns a whole screen (the text field is on the OTHER screen),
 *    so the top bar shows a live preview of what you're typing;
 *  - false: a normal keyboard at the bottom of the same screen.
 */
/** The field's text on either side of the cursor. */
data class Preview(val before: String, val after: String)

@Composable
fun ThorKeyboardPanel(
    kb: KeyboardController,
    overlay: Boolean,
    preview: Preview,
    modifier: Modifier = Modifier,
    onHide: () -> Unit,
    /** The move button's words, [KeyboardWords.move]: "Move up" / "Move down" (build 18, KB2) — null = no other screen. */
    moveLabel: String?,
    onMove: () -> Unit,
) = androidx.compose.runtime.CompositionLocalProvider(app.wayfinder.ui.LocalPadLook provides app.wayfinder.ButtonNames.handLook) {
    // dev build 15 (WP2): the badges name the buttons of the controller in hand (IME and overlay alike), as menuSwap
    // picks them
    val g = LocalGlass.current
    Column(
        modifier
            // The overlay has the system blur behind it; the IME window doesn't — so
            // it gets a nearly opaque base, or the app's text shows through the keys.
            .background(if (overlay) g.surfaceOverlay else g.surfaceSolid)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (overlay) 8.dp else 6.dp),
    ) {
        TopBar(kb, overlay, preview, onHide, moveLabel, onMove)
        if (kb.helpOpen) {
            KeyboardControlsLegend(Modifier.fillMaxWidth().weight(1f), big = overlay)
        } else {
            kb.rows().forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, key ->
                        KeyCap(kb, key, r, c, focused = kb.focus == r to c, big = overlay, modifier = Modifier.weight(key.weight).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(kb: KeyboardController, overlay: Boolean, preview: Preview, onHide: () -> Unit, moveLabel: String?, onMove: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().height(if (overlay) 64.dp else 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val popup = kb.popup
        if (popup != null && popup.first.kind == KeyKind.CLIPBOARD) {
            // 1.3.2 (GitHub #45): the last copied texts — tap one (or ←/→ + A) to paste it
            val clipScroll = androidx.compose.foundation.rememberScrollState()
            androidx.compose.runtime.LaunchedEffect(kb.popupIndex) {
                val n = popup.second.size
                if (n > 1) clipScroll.animateScrollTo(clipScroll.maxValue * kb.popupIndex / (n - 1))
            }
            Row(Modifier.weight(1f).horizontalScroll(clipScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                popup.second.forEachIndexed { i, opt ->
                    val sel = i == kb.popupIndex
                    Box(
                        Modifier.height(if (overlay) 56.dp else 40.dp).clip(RoundedCornerShape(Radii.KeyCap))
                            .background(if (sel) g.accent else g.fillChip)
                            .pointerInput(opt) { detectTapGestures { kb.choosePopup(opt) } }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(ClipHistory.preview(opt), color = if (sel) g.onAccent else g.textPrimary, fontSize = if (overlay) 20.sp else 16.sp, maxLines = 1) }
                }
                Box(Modifier.size(if (overlay) 56.dp else 40.dp).pointerInput(Unit) { detectTapGestures { kb.choosePopup(null) } },
                    contentAlignment = Alignment.Center) { Text("×", color = g.textSecondary, fontSize = 18.sp) }
            }
        } else if (popup != null) {
            // Accents for the held key — tap one (or ←/→ + A with the controller). Build 18 review (KB4): the row scrolls and
            // the lit option is scrolled into sight — beside the bar's buttons the bottom screen has room for about three
            // boxes, and the ones past them were squeezed to nothing (unseen, untappable, picked blind).
            val box = if (overlay) 56.dp else 40.dp
            val gap = 6.dp
            val accScroll = remember(popup) { androidx.compose.foundation.ScrollState(0) }
            var viewW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.LaunchedEffect(popup, kb.popupIndex, viewW) {
                if (viewW <= 0) return@LaunchedEffect
                val boxPx = with(density) { box.roundToPx() }
                val left = kb.popupIndex * (boxPx + with(density) { gap.roundToPx() })
                val to = when {
                    left < accScroll.value -> left
                    left + boxPx > accScroll.value + viewW -> left + boxPx - viewW
                    else -> return@LaunchedEffect
                }
                accScroll.animateScrollTo(to)
            }
            Row(Modifier.weight(1f).onSizeChanged { viewW = it.width }.horizontalScroll(accScroll),
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                popup.second.forEachIndexed { i, opt ->
                    val sel = i == kb.popupIndex
                    Box(
                        Modifier.size(box).clip(RoundedCornerShape(Radii.KeyCap))
                            .background(if (sel) g.accent else g.fillChip)
                            .pointerInput(opt) { detectTapGestures { kb.choosePopup(opt) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(opt, color = if (sel) g.onAccent else g.textPrimary, fontSize = if (overlay) 26.sp else 20.sp) }
                }
                Box(Modifier.size(box).pointerInput(Unit) { detectTapGestures { kb.choosePopup(null) } },
                    contentAlignment = Alignment.Center) { Text("×", color = g.textSecondary, fontSize = 18.sp) }
            }
        } else if (overlay) {
            // What you're typing on the other screen, right here.
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Radii.Control))
                    .background(g.fillWell)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { PreviewLine(preview) }
        } else Spacer(Modifier.weight(1f))
        // build 18 (KB2): the legend's key says what it opens ("?" read as a question); the move button says what it does,
        // with the screen it sends the keys to (ScreenIcon — "▲ Top screen" read as PlayStation's △ and as a status)
        // b18n (N3-ui 1): the controller lights them too (Up from the top key row, KeyboardController.BAR) — A presses the lit one
        val lit = kb.focusedBar()
        BarButton(Icons.Rounded.SportsEsports, KeyboardWords.BUTTONS, { kb.toggleHelp() }, badge = if (kb.padActive) ThorButton.L3 else null,
            lit = lit == BarKey.BUTTONS)
        if (moveLabel != null) BarButton(null, moveLabel, onMove, screenTop = moveLabel == KeyboardWords.MOVE_UP, lit = lit == BarKey.MOVE)
        BarButton(Icons.Rounded.KeyboardHide, null, onHide, badge = if (kb.padActive) app.wayfinder.ButtonEngine.menuSwap(ThorButton.B) else null,
            lit = lit == BarKey.HIDE)
    }
}

/**
 * The overlay's preview of the field: its text around the cursor, or the placeholder. Build 18 (KB2): the placeholder
 * fits ("Types into the top screen" was cut to "Types into the top" beside the button badges) and a long text keeps its
 * end and the cursor in sight — the start is cut instead of the cursor.
 */
@Composable
private fun PreviewLine(preview: Preview) {
    val g = LocalGlass.current
    if (preview.before.isEmpty() && preview.after.isEmpty()) {
        Text(KeyboardWords.TYPE_HERE, color = g.textTertiary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val shownBefore = if (preview.before.length > 34) "…" + preview.before.takeLast(34) else preview.before
    val shownAfter = if (preview.after.length > 24) preview.after.take(24) + "…" else preview.after
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the text before the cursor ends at the cursor: wider than its room, it is cut on the left
        Box(Modifier.weight(1f, fill = false).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
            Text(shownBefore, Modifier.wrapContentWidth(Alignment.End, unbounded = true),
                color = g.textPrimary, fontSize = 22.sp, maxLines = 1, softWrap = false)
        }
        Text("▏", color = g.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(shownAfter, Modifier.widthIn(max = 160.dp), color = g.textSecondary, fontSize = 22.sp, maxLines = 1,
            softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** A top-bar button: a badge (the controller's button for it), an icon or — [screenTop] set — the two screens with the
 *  one it sends the keys to filled (ui.ScreenIcon), and its words. b18n (N3-ui 1): [lit] = the controller's highlight is on
 *  it — the keys' ring ([KeyCap]'s). */
@Composable
private fun BarButton(icon: ImageVector?, label: String?, onClick: () -> Unit, badge: ThorButton? = null, screenTop: Boolean? = null,
                      lit: Boolean = false) {
    val g = LocalGlass.current
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)   // the tap handler is installed once
    val shape = RoundedCornerShape(Radii.Chip)
    Row(
        Modifier.fillMaxHeight().clip(shape)
            .background(g.fillSubtle)
            .then(if (lit) Modifier.border(2.5.dp, Brush.linearGradient(listOf(g.accent, g.accent2)), shape) else Modifier)
            .pointerInput(Unit) { detectTapGestures { click() } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (badge != null) ButtonGlyph(badge, size = 20.dp)
        if (screenTop != null) ScreenIcon(screenTop, size = 22.dp)
        if (icon != null) Icon(icon, label, tint = g.textSecondary, modifier = Modifier.size(22.dp))
        if (label != null) Text(label, color = g.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun KeyCap(kb: KeyboardController, key: KeySpec, row: Int, col: Int, focused: Boolean, big: Boolean, modifier: Modifier) {
    val g = LocalGlass.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lift by animateFloatAsState(if (pressed) 1f else 0f, label = "press")
    val functional = key.kind != KeyKind.CHAR && key.kind != KeyKind.SPACE
    val accentKey = key.kind == KeyKind.ENTER ||
        (key.kind == KeyKind.SHIFT && kb.shift != Shift.OFF)
    val shape = RoundedCornerShape(if (big) Radii.Control else Radii.KeyCap)
    val fill = if (accentKey) Brush.verticalGradient(listOf(g.accent, g.accent2)) else g.keyFill(functional)
    val tap: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        kb.press(key)
    }
    Box(
        modifier
            .graphicsLayer { val s = 1f + 0.06f * lift; scaleX = s; scaleY = s }
            .clip(shape)
            .background(fill, shape)
            .then(if (pressed) Modifier.background(g.pressVeil, shape) else Modifier)
            .glassLight(shape, g.dark)
            .border(
                if (focused) 2.5.dp else 1.dp,
                if (focused) Brush.linearGradient(listOf(g.accent, g.accent2))
                else Brush.linearGradient(0f to g.rimTop.copy(alpha = 0.7f), 0.5f to Color.Transparent, 1f to g.rimBottom),
                shape,
            )
            .pointerInput(key, kb.page, kb.layoutIndex, row, col) {
                if (key.kind == KeyKind.BACKSPACE) {
                    // Hold to repeat.
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true; tap()
                        val repeat = scope.launch { delay(420); while (true) { kb.press(key); delay(55) } }
                        // finally: a page / layout change mid-hold cancels this gesture — the repeat
                        // kept deleting every 55 ms (review 2026-09-25)
                        try { waitForUpOrCancellation() } finally { repeat.cancel(); pressed = false }
                    }
                } else if (key.kind == KeyKind.SPACE) {
                    // 1.3 (Reddit): hold Space and slide = move the text cursor, like Gboard. A tap types a
                    // space; a long press without sliding still switches the language.
                    val step = 14.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        var moved = 0; var sliding = false; var longDone = false
                        val t0 = down.uptimeMillis
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                val dx = c.position.x - down.position.x
                                if (!sliding && kotlin.math.abs(dx) > step) sliding = true
                                if (sliding) {
                                    val want = (dx / step).toInt()
                                    if (want != moved) { kb.slideCursor(want - moved); moved = want }
                                    c.consume()
                                } else if (!longDone && c.uptimeMillis - t0 > 500) {
                                    longDone = true
                                    if (kb.openPopup(key)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) else kb.nextLayout()
                                }
                            }
                        } finally { pressed = false }
                        if (!sliding && !longDone) tap()
                    }
                } else detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onLongPress = {
                        if (kb.openPopup(key, row, col)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        else if (key.kind == KeyKind.SPACE) kb.nextLayout()
                    },
                    onTap = { tap() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (accentKey) g.onAccent else g.textPrimary
        val size = if (big) 30.sp else 22.sp
        when (key.kind) {
            KeyKind.CHAR -> {
                Text(kb.display(key), color = fg, fontSize = size)
                // The hint: the top row's digit by position, else the language's first accent — a long-press offers it first.
                kb.hint(key, row, col)?.let { hint ->
                    Text(hint, color = g.textTertiary, fontSize = if (big) 13.sp else 10.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 6.dp))
                }
            }
            KeyKind.SPACE -> Text(kb.layout.name, color = g.textTertiary, fontSize = if (big) 17.sp else 14.sp, maxLines = 1)
            KeyKind.SHIFT -> Icon(
                if (kb.shift == Shift.LOCKED) Icons.Rounded.KeyboardCapslock else Icons.Rounded.KeyboardArrowUp,
                "Shift", tint = fg, modifier = Modifier.size(if (big) 34.dp else 26.dp),
            )
            KeyKind.BACKSPACE -> Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = fg, modifier = Modifier.size(if (big) 30.dp else 24.dp))
            KeyKind.ENTER -> Icon(
                when (kb.enterAction) {
                    EnterAction.SEARCH -> Icons.Rounded.Search
                    EnterAction.SEND -> Icons.AutoMirrored.Rounded.Send
                    EnterAction.GO, EnterAction.NEXT -> Icons.AutoMirrored.Rounded.ArrowForward
                    EnterAction.DONE -> Icons.Rounded.Check
                    EnterAction.NEWLINE -> Icons.AutoMirrored.Rounded.KeyboardReturn
                },
                "Enter", tint = fg, modifier = Modifier.size(if (big) 30.dp else 24.dp),
            )
            KeyKind.GLOBE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Language, "Language", tint = fg, modifier = Modifier.size(if (big) 24.dp else 20.dp))
                Text(kb.layout.short, color = g.textSecondary, fontSize = if (big) 12.sp else 10.sp, fontWeight = FontWeight.SemiBold)
            }
            KeyKind.TO_SYMBOLS -> Text("?123", color = fg, fontSize = if (big) 20.sp else 16.sp)
            KeyKind.TO_SYMBOLS_2 -> Text("=\\<", color = fg, fontSize = if (big) 20.sp else 16.sp)
            KeyKind.TO_LETTERS -> Text("ABC", color = fg, fontSize = if (big) 20.sp else 16.sp)
            // 1.3.2 (GitHub #48)
            KeyKind.TO_EMOJI -> Text("🙂", fontSize = if (big) 24.sp else 19.sp)
            KeyKind.EMOJI_NEXT -> Text(EMOJI_GROUPS[(kb.emojiGroup + 1) % EMOJI_GROUPS.size].first + " ›", color = fg, fontSize = if (big) 20.sp else 16.sp)
            // 1.3.2 (GitHub #45): dim until something was copied
            KeyKind.CLIPBOARD -> Icon(Icons.Rounded.ContentPaste, "Clipboard",
                tint = if (ClipHistory.items.isEmpty()) g.textTertiary else fg, modifier = Modifier.size(if (big) 28.dp else 22.dp))
        }
        // With the controller in use, each special key shows the button that does it.
        if (kb.padActive) padButtonFor(key.kind)?.let { app.wayfinder.ButtonEngine.menuSwap(it) }?.let { b ->
            Box(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp)) {
                ButtonGlyph(b, size = if (big) 22.dp else 16.dp)
            }
        }
    }
}

private fun padButtonFor(kind: KeyKind): ThorButton? = when (kind) {
    KeyKind.BACKSPACE -> ThorButton.X
    KeyKind.SPACE -> ThorButton.Y
    KeyKind.SHIFT -> ThorButton.L2
    KeyKind.ENTER -> ThorButton.START
    KeyKind.GLOBE -> ThorButton.SELECT
    KeyKind.TO_SYMBOLS, KeyKind.TO_LETTERS -> ThorButton.R2
    else -> null
}

/** One legend line. (A concrete type on purpose: a list of nested generic pairs made the
 *  Kotlin compiler's type inference explode — builds hung for minutes.) */
private class LegendItem(val buttons: List<ThorButton>, val text: String)

private val LEGEND: List<LegendItem> get() = listOf(
    LegendItem(listOf(ThorButton.L3), if (KeyboardSettings.dpadKeys) "Left stick: move the text cursor · click it: this help"
        else "Left stick: pick a key · click it: this help"),
    LegendItem(listOf(ThorButton.A), "Type the key · hold for accents"),
    LegendItem(listOf(ThorButton.LEFT, ThorButton.RIGHT), if (KeyboardSettings.dpadKeys) "D-pad: pick a key" else "D-pad: move the text cursor"),
    LegendItem(listOf(ThorButton.B), "Close the keyboard (Back too)"),
    LegendItem(listOf(ThorButton.X), "Delete"),
    LegendItem(listOf(ThorButton.Y), "Space"),
    LegendItem(listOf(ThorButton.L2), "Shift (twice: caps lock)"),
    LegendItem(listOf(ThorButton.R2), "Symbols ↔ letters"),
    LegendItem(listOf(ThorButton.L1, ThorButton.R1), "Jump a word left / right"),
    LegendItem(listOf(ThorButton.START), "Enter"),
    LegendItem(listOf(ThorButton.SELECT), "Next language"),
)

/**
 * Every controller control of the keyboard, with the real button glyphs. Shared with
 * the Hub. Kept flat on purpose: nested forEach → Row → forEach lambdas here sent the
 * Compose compiler's target inference into a StackOverflow (builds "hung" for minutes).
 */
@Composable
fun KeyboardControlsLegend(modifier: Modifier = Modifier, big: Boolean = true) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendColumn(LEGEND.subList(0, 6), big, Modifier.weight(1f).fillMaxHeight())
        LegendColumn(LEGEND.subList(6, LEGEND.size), big, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LegendColumn(items: List<LegendItem>, big: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        for (item in items) LegendLine(item, big)
    }
}

@Composable
private fun LegendLine(item: LegendItem, big: Boolean) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in item.buttons) ButtonGlyph(app.wayfinder.ButtonEngine.menuSwap(b), size = if (big) 30.dp else 24.dp)
        Text(item.text, color = g.textPrimary, fontSize = if (big) 17.sp else 14.sp, maxLines = 2)
    }
}
