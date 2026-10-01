package app.wayfinder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.glassSurface
import kotlin.math.roundToInt

/**
 * The quick panel's shortcuts, arranged like a home screen: the chosen tiles in a grid
 * (long-press and drag one anywhere — the others make room; "−" removes), then
 * everything else to add ("+"). Controller: A picks a tile up, the D-pad moves it, A (or
 * B) puts it down, Y removes it. Used in the panel's own edit mode and in the Hub.
 */
@Composable
fun ShortcutGridEditor(columns: Int = 4, cellHeight: Dp = 72.dp) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    var chosen by remember { mutableStateOf(PanelShortcuts.chosen(ctx)) }
    fun save(l: List<String>) { chosen = l; PanelShortcuts.save(ctx, l) }
    var selected by remember { mutableStateOf<String?>(null) }   // tapped / focused: its description shows
    // 1.4 (Reddit): picking what a new "Open…" tile opens — the combos' picker, in place of the grid
    var picking by remember { mutableStateOf<String?>(null) }
    val pickButtons = remember { HashMap<String, androidx.compose.ui.focus.FocusRequester>() }
    var pickedFrom by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(picking) {
        val from = pickedFrom
        if (picking == null && from != null) { kotlinx.coroutines.delay(120); runCatching { pickButtons[from]?.requestFocus() }; pickedFrom = null }
    }
    picking?.let { kind ->
        androidx.activity.compose.BackHandler { picking = null }
        Box(Modifier.fillMaxWidth().height(if (kind == "app") 520.dp else 420.dp)) {
            OpenPicker(kind, onCancel = { picking = null }) { arg -> picking = null; if (arg !in chosen) save(chosen + arg) }
        }
        return
    }
    // where each tile is on screen (window y of its centre) — read while placing the card
    val tileY = remember { HashMap<String, Float>() }
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    // 1.3: the description only floats while its tile is on screen, and the controller leaving the
    // tiles clears it — it stayed over the settings rows above (AYN tap / hold) otherwise
    val screenH = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var selOnScreen by remember { mutableStateOf(true) }
    var hadFocus by remember { mutableStateOf(false) }
    val pick: (String) -> Unit = { selected = it; selOnScreen = true }
    val place: (String, Float) -> Unit = { id, y -> tileY[id] = y; if (id == selected) selOnScreen = y in 0f..screenH }
    Column(Modifier.onFocusChanged { if (hadFocus && !it.hasFocus) selected = null; hadFocus = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The description floats over the page — at the top of the list it was off-screen as
        // soon as you'd scrolled down to a tile. It goes to the edge AWAY from the selected tile
        // and is solid: at the bottom, see-through, it covered the very tile the controller was
        // on and its text ran into the tiles behind (review 2026-09-25).
        // the hint stays in the page (taking it away made the grid jump up under the finger)
        DescriptionCard(null, inPanel = false, add = {}, remove = {})
        if (selected != null && selOnScreen) androidx.compose.ui.window.Popup(
            popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                               layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                               popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset {
                    val y = tileY[selected] ?: 0f
                    val top = y > windowSize.height / 2f
                    return IntOffset((windowSize.width - popupContentSize.width) / 2,
                        if (top) margin else windowSize.height - popupContentSize.height - margin)
                }
            },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .background(g.base.copy(alpha = 0.97f), RoundedCornerShape(18.dp))) {
                DescriptionCard(selected, inPanel = selected in chosen,
                    add = { id -> save(chosen + id) }, remove = { id -> save(chosen - id) })
            }
        }
        Text("In the panel (${chosen.size}) — hold a tile and drag it to move it", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        ChosenGrid(chosen, columns, cellHeight, reorder = { save(it) }, remove = { save(chosen - it) }, select = pick,
            placed = place)
        if (chosen.isEmpty()) Text("Empty — add some below.", color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
        for (group in PanelShortcuts.Group.values()) {
            val rest = PanelShortcuts.CATALOG.filter { it.value.group == group && it.key !in chosen }.keys.toList()
            if (rest.isEmpty()) continue
            Text("Add — ${group.title}", color = g.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            AvailableGrid(rest, columns, cellHeight, add = { save(chosen + it) }, select = pick,
                placed = place)
        }
        Text("Add — a tile that opens…", color = g.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((kind, label) in listOf("app" to "An app", "pair" to "An app pair", "page" to "A Wayfinder page"))
                FocusableGlass(onClick = { pickedFrom = kind; picking = kind }, radius = 14.dp,
                    focusRequester = pickButtons.getOrPut(kind) { androidx.compose.ui.focus.FocusRequester() }) {
                    Text("+ $label", color = g.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
        }
        FocusableGlass(onClick = { save(PanelShortcuts.DEFAULT) }, radius = 14.dp) {
            Text("Back to the default set", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/** A tile's icon — an app tile shows the app's own (1.4). */
@Composable
internal fun TileIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, pkg: String?, tint: androidx.compose.ui.graphics.Color, size: Dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        pkg?.let { p -> runCatching { ctx.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(size))
    else Icon(icon, null, tint = tint, modifier = Modifier.size(size))
}

/** What the selected tile does, with the matching Add / Remove — so touch users can read it too. */
@Composable
private fun DescriptionCard(id: String?, inPanel: Boolean, add: (String) -> Unit, remove: (String) -> Unit) {
    val g = LocalGlass.current
    val info = id?.let { PanelShortcuts.info(LocalContext.current, it) }
    app.wayfinder.ui.GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info == null) {
                Text("Tap or select a tile to see what it does.\n" +
                    "Touch: hold and drag to move · − removes · + adds.   Controller: A picks up and puts down, the D-pad moves it, Y removes.",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                return@Row
            }
            TileIcon(info.icon, info.pkg, g.accent, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(info.name + "  ·  " + info.group.title, color = g.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(info.what + if (inPanel) "  A picks it up to move it (D-pad, then A)." else "",
                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            // Touch: this button. Controller: the button named on it, pressed on the tile — the card
            // can't take focus (it would pull the controller off the tiles), so it says which one.
            FocusableGlass(onClick = { if (inPanel) remove(id) else add(id) }, radius = 12.dp) {
                Text(if (inPanel) "Remove  ·  ${ButtonNames.m("Y")}" else "Add  ·  ${ButtonNames.m("A")}", color = if (inPanel) g.textSecondary else g.accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ChosenGrid(
    ids: List<String>, columns: Int, cellHeight: Dp,
    reorder: (List<String>) -> Unit, remove: (String) -> Unit, select: (String) -> Unit,
    placed: (String, Float) -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val cellH = with(density) { cellHeight.roundToPx() }
    var width by remember { mutableIntStateOf(0) }
    val cellW = if (width == 0) 0 else (width - gap * (columns - 1)) / columns
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf<String?>(null) }   // picked up with the controller
    // Y removes the focused tile: focus goes to its neighbour (it vanished with the tile — the
    // controller was left with nothing selected, review 2026-09-25)
    val requesters = remember { HashMap<String, androidx.compose.ui.focus.FocusRequester>() }
    var focusNext by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(focusNext) {
        val n = focusNext ?: return@LaunchedEffect
        kotlinx.coroutines.delay(60); runCatching { requesters[n]?.requestFocus() }; focusNext = null
    }
    val latestIds by rememberUpdatedState(ids)
    val latestReorder by rememberUpdatedState(reorder)
    fun slot(i: Int) = IntOffset((i % columns) * (cellW + gap), (i / columns) * (cellH + gap))
    fun move(id: String, to: Int) {
        val cur = latestIds.indexOf(id)
        val t = to.coerceIn(0, latestIds.size - 1)
        if (cur >= 0 && t != cur) latestReorder(latestIds.toMutableList().apply { add(t, removeAt(cur)) })
    }
    fun dragTo(id: String) {
        // The slot under the dragged tile's centre.
        val cx = dragPos.x + cellW / 2f; val cy = dragPos.y + cellH / 2f
        val col = (cx / (cellW + gap)).toInt().coerceIn(0, columns - 1)
        val row = (cy / (cellH + gap)).toInt().coerceAtLeast(0)
        move(id, row * columns + col)
    }
    val rows = ((ids.size + columns - 1) / columns).coerceAtLeast(1)
    val height = with(density) { (rows * (cellH + gap) - gap).toDp() }
    Box(Modifier.fillMaxWidth().height(height).onSizeChanged { width = it.width }) {
        if (cellW == 0) return@Box
        ids.forEachIndexed { i, id ->
            key(id) {
                val dragged = dragId == id
                val pos by animateIntOffsetAsState(slot(i), spring(dampingRatio = 0.8f, stiffness = 500f), label = "slot")
                val at = if (dragged) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else pos
                val tap = Modifier.pointerInput(id) { detectTapGestures { select(id) } }
                val touch = Modifier.pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragId = id; select(id); dragPos = Offset(pos.x.toFloat(), pos.y.toFloat()) },
                        onDrag = { change, amount -> change.consume(); dragPos += amount; dragTo(id) },
                        onDragEnd = { dragId = null },
                        onDragCancel = { dragId = null },
                    )
                }
                val keys = Modifier.onKeyEvent { e ->
                    val here = latestIds.indexOf(id)
                    when {
                        e.type == KeyEventType.KeyUp && (e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter) -> {
                            moving = if (moving == id) null else id; true }
                        e.type == KeyEventType.KeyUp && e.key == Key.ButtonY -> {
                            focusNext = latestIds.getOrNull(here + 1) ?: latestIds.getOrNull(here - 1)
                            moving = null; remove(id); true }
                        moving == id && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape) -> {
                            if (e.type == KeyEventType.KeyUp) moving = null; true }
                        moving == id && e.type == KeyEventType.KeyDown -> when (e.key) {
                            Key.DirectionLeft -> { move(id, here - 1); true }
                            Key.DirectionRight -> { move(id, here + 1); true }
                            Key.DirectionUp -> { move(id, here - columns); true }
                            Key.DirectionDown -> { move(id, here + columns); true }
                            else -> false
                        }
                        else -> false
                    }
                }
                val req = requesters.getOrPut(id) { androidx.compose.ui.focus.FocusRequester() }
                EditorCell(id, Modifier.focusRequester(req).offset { at }.width(with(density) { cellW.toDp() }).height(cellHeight)
                    .zIndex(if (dragged || moving == id) 1f else 0f).then(tap).then(touch).then(keys),
                    lifted = dragged || moving == id, badge = "−", onBadge = { remove(id) },
                    onFocus = { f -> if (f) select(id) }, placed = { placed(id, it) })
            }
        }
    }
}

@Composable
private fun AvailableGrid(ids: List<String>, columns: Int, cellHeight: Dp, add: (String) -> Unit, select: (String) -> Unit,
                          placed: (String, Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    EditorCell(id, Modifier.weight(1f).height(cellHeight)
                        .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter)) { add(id); true } else false }
                        .pointerInput(id) { detectTapGestures { select(id) } },
                        lifted = false, badge = "+", onBadge = { add(id) },
                        onFocus = { f -> if (f) select(id) }, dim = true, placed = { placed(id, it) })
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** One tile as the panel shows it, plus a corner badge (− / +). */
@Composable
private fun EditorCell(id: String, modifier: Modifier, lifted: Boolean, badge: String, onBadge: () -> Unit,
                       onFocus: (Boolean) -> Unit, dim: Boolean = false, placed: (Float) -> Unit = {}) {
    val g = LocalGlass.current
    val info = PanelShortcuts.info(LocalContext.current, id) ?: return
    var focused by remember { mutableStateOf(false) }
    // A tile added / removed with the controller: the focus stays in this slot, which now shows
    // ANOTHER tile — no focus change, so say it again (the card kept describing the old tile)
    androidx.compose.runtime.LaunchedEffect(id) { if (focused) onFocus(true) }
    val scale by animateFloatAsState(if (lifted) 1.08f else if (focused) 1.035f else 1f, spring(dampingRatio = 0.6f), label = "lift")
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .onGloballyPositioned { placed(it.positionInWindow().y + it.size.height / 2f) }   // not clipped: off screen = off screen
        .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .focusable()
        .glassSurface(g, shape, raised = focused || lifted)
        .then(if (lifted) Modifier.border(2.dp, g.accent, shape) else if (focused) Modifier.border(1.5.dp, g.accent.copy(alpha = 0.8f), shape) else Modifier)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            TileIcon(info.icon, info.pkg, if (dim) g.textTertiary else g.accent, 20.dp)
            Text(info.name, color = if (dim) g.textSecondary else g.textPrimary, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(if (badge == "+") g.accent else g.textTertiary.copy(alpha = 0.55f), CircleShape)
            .pointerInput(id) { detectTapGestures { onBadge() } }, contentAlignment = Alignment.Center) {
            Text(badge, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
