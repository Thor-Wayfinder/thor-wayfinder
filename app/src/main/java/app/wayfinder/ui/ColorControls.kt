package app.wayfinder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The right stick's position (-1..1 each axis), fed by MainActivity. */
object RightStick {
    var pos by mutableStateOf(Offset.Zero)
        private set
    fun set(x: Float, y: Float) { pos = Offset(x, y) }
}

/** Fraction of the radius where colour reaches full saturation; the band outside it is pure colour. */
private const val FULL_SAT = 0.72f

/**
 * A hue/saturation colour wheel: touch or drag to pick. RIGHT STICK (while the wheel
 * is focused): point it at a colour — direction = hue, how far you push = saturation. With the controller: focus it,
 * press A to start moving the picker with the D-pad / left stick (←/→ around the wheel,
 * ↑/↓ out to the rim or in to white), A or B to finish — so the pad never gets stuck
 * inside the wheel. [argb] in, [onPick] out (full brightness; brightness is its own slider).
 * Build 18 (A19, MORE-D12): [legend] = a line under it that says how the controller drives it now ([wheelLegend]) — the
 * steering mode was invisible. Stick lights and Your own colours show it (this overload's default).
 */
@Composable
fun ColorWheel(argb: Int, size: Dp, onPick: (Int) -> Unit) = ColorWheel(argb, size, legend = true, onPick = onPick)

/** Build 18 (A19): the line under the wheel, in the hand's buttons (A7's tokens; ◀▶▲▼ stay text arrows, as A14's legend):
 *  focused or not, how to start ([steering] false); after A, what the D-pad does and how to finish. */
internal fun wheelLegend(steering: Boolean, look: app.wayfinder.PadLook): String {
    val a = app.wayfinder.Say.keyMenu(app.wayfinder.ThorButton.A, look)
    // ▲ = toward the rim (more colour), ▼ = toward the middle (paler): the wheel's own key handling
    return if (steering) "◀▶ colour · ▲▼ stronger / paler · $a done" else "Right stick: point at a colour · $a: use the D-pad"
}

/** [ColorWheel] with its legend line or not. */
@Composable
fun ColorWheel(argb: Int, size: Dp, legend: Boolean, onPick: (Int) -> Unit) {
    val g = LocalGlass.current
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    val latestPick by androidx.compose.runtime.rememberUpdatedState(onPick)
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // dev build 17 (WP8): focus = the one look (focusIndicator, its scale); the accent ring stays only while steering — it
    // marks the mode, not the focus
    val ring by animateFloatAsState(if (editing) 1f else 0f, label = "ring")
    val pad = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    fun pick(h: Float, s: Float) = latestPick(android.graphics.Color.HSVToColor(floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), 1f)))
    fun pickAt(o: Offset, w: Float) {
        val c = w / 2f - pad
        val dx = o.x - w / 2f; val dy = o.y - w / 2f
        pick(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(), hypot(dx, dy) / (c * FULL_SAT))
    }
    // Right stick PUSHES the puck from where it is (like a cursor), while focused.
    // The puck keeps its own position so moving through the pure-colour band is smooth.
    var puck by remember { mutableStateOf<Offset?>(null) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (!focused) { puck = null; return@LaunchedEffect }
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val s = RightStick.pos
                val mag = hypot(s.x, s.y)
                if (mag > 0.15f) {
                    val start = puck ?: run {
                        val a = Math.toRadians(hsv[0].toDouble())
                        val f = if (hsv[1] >= 0.999f) (1f + FULL_SAT) / 2f else hsv[1] * FULL_SAT
                        Offset((cos(a) * f).toFloat(), (sin(a) * f).toFloat())
                    }
                    val speed = 0.9f * ((mag - 0.15f) / 0.85f)   // radius per second at full tilt
                    var x = start.x + s.x / mag * speed * dt
                    var y = start.y + s.y / mag * speed * dt
                    val len = hypot(x, y)
                    if (len > 1f) { x /= len; y /= len }
                    puck = Offset(x, y)
                    pick(Math.toDegrees(atan2(y, x).toDouble()).toFloat(), hypot(x, y) / FULL_SAT)
                }
            }
        }
    }
    val gate = remember { ReleaseGate() }   // dev build 16 (WP7): A toggles moving on a release seen here
    val req = remember { FocusRequester() }
    OfferEntry(req)
    // build 18 (A19): the wheel, then its legend line
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size)
                .focusRequester(req)
                .focusIndicator(CircleShape)
                .onFocusChanged { FocusTruth.report(req, it.hasFocus); focused = it.isFocused; if (!it.isFocused) { editing = false; gate.armed = false } }
                .onKeyEvent { e ->
                    if (ReleaseGate.isSelect(e)) return@onKeyEvent gate.onKey(e) { editing = !editing }
                    if (editing && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape)) {
                        if (e.type == KeyEventType.KeyUp) editing = false
                        return@onKeyEvent true
                    }
                    if (!editing || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { pick(hsv[0] - 6f, hsv[1]); true }
                        Key.DirectionRight -> { pick(hsv[0] + 6f, hsv[1]); true }
                        Key.DirectionUp -> { pick(hsv[0], hsv[1] + 0.08f); true }
                        Key.DirectionDown -> { pick(hsv[0], hsv[1] - 0.08f); true }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures { puck = null; pickAt(it, this.size.width.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> puck = null; pickAt(change.position, this.size.width.toFloat()) }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val r = this.size.minDimension / 2f - pad
                // Steering (A pressed): a ring OUTSIDE the wheel (on top of it, an accent ring vanished into the blues)
                if (ring > 0.01f) drawCircle(g.accent.copy(alpha = ring), r + 6.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                val hues = (0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) }
                drawCircle(Brush.sweepGradient(hues), r)
                // White only in the middle: the outer band is fully saturated colour.
                drawCircle(Brush.radialGradient(0f to Color.White, FULL_SAT to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = 0f), radius = r), r)
                // The picker: where the colour sits on the wheel (full saturation → middle of the pure band).
                val pk = puck
                val p = if (pk != null) Offset(center.x + pk.x * r, center.y + pk.y * r) else {
                    val a = Math.toRadians(hsv[0].toDouble())
                    val frac = if (hsv[1] >= 0.999f) (1f + FULL_SAT) / 2f else hsv[1] * FULL_SAT
                    Offset(center.x + (cos(a) * frac * r).toFloat(), center.y + (sin(a) * frac * r).toFloat())
                }
                drawCircle(Color.Black.copy(alpha = 0.35f), 15.dp.toPx(), p)
                drawCircle(Color.White, 13.dp.toPx(), p)
                drawCircle(Color(argb), 10.dp.toPx(), p)
            }
        }
        if (legend) WheelLegend(editing, Modifier.padding(top = 6.dp).widthIn(max = maxOf(size, 160.dp)))
    }
}

/** Build 18 (A19): [wheelLegend] under the wheel — both lines laid out, the other one invisible, so pressing A never moves
 *  the page (the taller of the two keeps its room). */
@Composable
private fun WheelLegend(steering: Boolean, modifier: Modifier) {
    val g = LocalGlass.current
    val look = currentLook()
    val style = MaterialTheme.typography.labelSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        for (s in listOf(false, true)) {
            val shown = s == steering
            GlyphText(wheelLegend(s, look),
                Modifier.alpha(if (shown) 1f else 0f).then(if (shown) Modifier else Modifier.clearAndSetSemantics { }),
                color = g.textTertiary, style = style)
        }
    }
}

/** dev build 16 (WP7): a row holding a slider — Left / Right never leave it (a slider at its limit lets the key go to its
 *  own row's other controls, never to another card). */
internal val LocalSliderRow = compositionLocalOf { false }

/** Build 18 (A6): [sideExit] = Left / Right may leave the row at its ends (the quick panel's level cards side by side: Right
 *  from the Brightness card's last control goes on to the Volume card); false keeps them in the row, as before. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.sliderRow(sideExit: Boolean = false): Modifier = (if (sideExit) this else focusProperties {
    exit = { d -> if (d == FocusDirection.Left || d == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
}).focusGroup()

/** A [Row] made a [sliderRow]: its slider lets go of Left / Right at a limit, toward this row's other controls only (the
 *  quick panel's level rows: the expand button beside the slider couldn't be reached) — and beyond the row's ends when
 *  [sideExit] (build 18, A6). */
@Composable
fun SliderRow(modifier: Modifier = Modifier, horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
              verticalAlignment: Alignment.Vertical = Alignment.Top, sideExit: Boolean = false,
              content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalSliderRow provides true) {
        Row(modifier.sliderRow(sideExit), horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
    }
}

/**
 * A glass slider 0..1: drag or tap with touch; with the controller, focus it and
 * ←/→ step by [step] (hold to keep going).
 * Build 18 (A6): [enabled] false = its value isn't known yet (a level still loading) — drawn empty with no knob, keys and
 * touch change nothing ([onChange] is never called), but it is focusable and offers the page's entry, so a page opens on it.
 */
@Composable
fun GlassSlider(value: Float, modifier: Modifier = Modifier, step: Float = 0.05f, height: androidx.compose.ui.unit.Dp = 44.dp,
                enabled: Boolean = true, onChange: (Float) -> Unit) {
    val g = LocalGlass.current
    var focused by remember { mutableStateOf(false) }
    val on by androidx.compose.runtime.rememberUpdatedState(enabled)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val latest: (Float) -> Unit = { v -> if (on) change(v) }
    val shape = CapsuleShape   // the round knob nests in its ends
    // dev build 16 (WP7): in a SliderRow, at a limit the key goes on (to the row's other controls); elsewhere kept as before.
    // A page whose first content is a slider opens on it (OfferEntry)
    val inRow = LocalSliderRow.current
    val req = remember { FocusRequester() }
    OfferEntry(req)
    Box(
        modifier.fillMaxWidth().height(height)
            .focusRequester(req)
            // dev build 17 (WP8): the one focus look, inside its edge (was a 2 dp white border)
            .focusIndicator(CapsuleShape, inset = true)
            .onFocusChanged { FocusTruth.report(req, it.hasFocus); focused = it.isFocused }   // device pass (P1-3): requestAfterLayout
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // not known yet (A6): nothing to change — the key goes on, as at a limit
                if (!enabled) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> if (inRow && value <= 0.0005f) false else { latest((value - step).coerceIn(0f, 1f)); true }
                    Key.DirectionRight -> if (inRow && value >= 0.9995f) false else { latest((value + step).coerceIn(0f, 1f)); true }
                    else -> false
                }
            }
            .focusRole(FocusRole.SLIDER)        // build 18 (A14): the page's button legend ("◀▶ change")
            .focusable()
            .clip(shape)
            // The empty part must read on light glass too (white-on-white vanished).
            .background(if (g.dark) Color(0x26FFFFFF) else Color(0x1F28306A), shape)
            .border(1.dp, g.rimBottom, shape)
            .pointerInput(Unit) { detectTapGestures { latest(posToValue(it.x, size.width.toFloat(), size.height.toFloat())) } }
            // Sideways drags only: a vertical swipe that starts on the slider scrolls the page
            // (it used to move the slider — the saturation changed while scrolling, 2026-09-24)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, _ ->
                    latest(posToValue(c.position.x, size.width.toFloat(), size.height.toFloat()))
                }
            },
    ) {
        // One geometry for knob, fill and touch: the knob (nearly the track's height, like
        // a switch) travels between the rounded ends; the fill runs to the knob's CENTRE so
        // its end always hides under it — no sliver at 0, no bulge beside the knob.
        Canvas(Modifier.matchParentSize()) {
            val h = this.size.height; val w = this.size.width
            // The knob is exactly the track's height (a smaller round button looked detached
            // from the slider), nested in the capsule's rounded ends.
            val r = h / 2f
            if (!enabled) return@Canvas             // A6: not known yet — the empty track only
            val x = r + (w - 2f * r) * value.coerceIn(0f, 1f)
            drawRect(Brush.horizontalGradient(listOf(g.accent2, g.accent), endX = w), size = androidx.compose.ui.geometry.Size(x, h))
            val c = Offset(x, h / 2f)
            drawCircle(Color.Black.copy(alpha = 0.22f), r, c + Offset(1.dp.toPx(), 0f))   // soft edge on the track side
            drawCircle(Color.White, r - 0.5.dp.toPx(), c)
            if (focused) drawCircle(g.accent, r - 2.5.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        }
    }
}

/** Touch x → slider value, with the same knob travel the slider draws (see GlassSlider). */
private fun androidx.compose.ui.unit.Density.posToValue(x: Float, w: Float, h: Float): Float {
    val edge = h / 2f   // inset + knob radius = half the height
    return ((x - edge) / (w - 2f * edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

/**
 * dev build 17 (WP8): a colour as a hex code — the colour's square and a field (A starts typing). Was LightsPage's HexField and
 * the Hub's custom-colour row: one control. [stacked]: the square above the field and "Colour code" under it (Stick lights);
 * else the square beside the field (Appearance). [onPick] gets an opaque colour.
 * Build 18 (A19, MORE-D09 / MORE-D10): the square has a thin rim (a colour equal to its card still reads as a sample); typing
 * starts with the code selected (the first key replaces it) on a capitals keyboard without corrections.
 */
@Composable
fun HexColorField(argb: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, previewSize: Dp = 22.dp, fieldWidth: Dp = 96.dp,
                  textSize: TextUnit = TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), stacked: Boolean = false) {
    val g = LocalGlass.current
    var hex by remember(argb) { mutableStateOf("#%06X".format(argb and 0xFFFFFF)) }
    val preview: @Composable () -> Unit = {
        Box(Modifier.size(previewSize).background(Color(argb or 0xFF000000.toInt()), SwatchPreviewShape)
            .border(1.dp, g.textTertiary.copy(alpha = 0.5f), SwatchPreviewShape))
    }
    val field: @Composable () -> Unit = {
        val shape = RoundedCornerShape(Radii.Chip)
        ControllerTextField(hex, { t ->
            hex = t.take(7).uppercase()
            Regex("^#?([0-9A-F]{6})$").find(hex)?.groupValues?.get(1)?.let { onPick(it.toLong(16).toInt() or 0xFF000000.toInt()) }
        }, Modifier.width(fieldWidth), textStyle = TextStyle(color = g.textPrimary, fontSize = textSize),
            surface = Modifier.glassSurface(g, shape, raised = false), shape = shape,
            contentPadding = if (stacked) PaddingValues(horizontal = 12.dp, vertical = 9.dp) else PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false,
                keyboardType = KeyboardType.Ascii),
            selectAllOnStart = true)
    }
    val labelStyle = MaterialTheme.typography.labelSmall
    val label: @Composable () -> Unit = { Text(COLOUR_CODE, color = g.textTertiary, style = labelStyle, maxLines = 1) }
    if (stacked) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview(); field(); label()
    }
    // device pass (A19 leftover): the label in this layout too (it only drew stacked, and no page uses that). Before the
    // square where the row has the room (Stick lights: nothing moves down), else under the row (Your own colours' four narrow
    // pickers); either way the block keeps the callers' place and centring
    else androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val labelW = remember(labelStyle, density) {
            with(density) { measurer.measure(COLOUR_CODE, labelStyle, maxLines = 1, softWrap = false).size.width.toDp() }
        }
        if (hexLabelLeads(maxWidth.value, labelW.value, previewSize.value, fieldWidth.value, HEX_GAP_DP))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HEX_GAP_DP.dp)) {
                label(); preview(); field()
            }
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HEX_GAP_DP.dp)) { preview(); field() }
            label()
        }
    }
}

/** The hex field's label (glossary: "the field under a colour: Colour code"). */
private const val COLOUR_CODE = "Colour code"

/** The gap between the hex field's label, square and field, dp. */
private const val HEX_GAP_DP = 8f
