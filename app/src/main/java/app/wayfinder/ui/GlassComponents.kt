package app.wayfinder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable glass components + controller-focus behaviour. Every interactive glass
 * element is focusable (D-pad / left-stick-as-dpad), shows a spring focus ring,
 * and fires onClick on touch, D-pad center/Enter, OR gamepad A.
 */

/** Which slice of the shared two-screen aurora a window shows. */
enum class AuroraSpan(val offset: Float) { TOP(0f), BOTTOM(1f) }

// Aurora blob paths in a virtual canvas: x ∈ [0,1] of each screen's width, y ∈ [0,2]
// in screen heights — the top screen shows y∈[0,1], the bottom screen y∈[1,2]. Both
// windows live in one process and read the same uptime clock, so a blob drifting
// down visibly passes from the top screen onto the bottom one.
private class Blob(val wx: Float, val px: Float, val wy: Float, val py: Float, val r: Float)
private val BLOBS = listOf(
    Blob(wx = 0.090f, px = 0.0f, wy = 0.061f, py = 0.3f, r = 0.90f),
    Blob(wx = 0.071f, px = 2.1f, wy = 0.083f, py = 1.9f, r = 0.82f),
    Blob(wx = 0.105f, px = 4.2f, wy = 0.052f, py = 3.4f, r = 0.78f),
    Blob(wx = 0.063f, px = 1.3f, wy = 0.074f, py = 5.0f, r = 0.86f),
    Blob(wx = 0.082f, px = 3.3f, wy = 0.057f, py = 0.9f, r = 0.74f),
    Blob(wx = 0.058f, px = 5.4f, wy = 0.069f, py = 2.6f, r = 0.84f),
    Blob(wx = 0.097f, px = 0.8f, wy = 0.048f, py = 4.3f, r = 0.76f),
)

/**
 * Full-screen glass scaffold: an animated aurora (saturated colour blobs on a
 * base) that the translucent panels float over. Animation is read only in the
 * draw phase, so it never recomposes the UI.
 */
@Composable
fun GlassScreen(
    modifier: Modifier = Modifier,
    span: AuroraSpan = AuroraSpan.TOP,
    content: @Composable () -> Unit,
) {
    val g = LocalGlass.current
    val realGlass = LocalRealGlass.current
    // The aurora animates every frame; with real glass there's nothing to animate.
    val clock = if (realGlass) null else androidx.compose.runtime.produceState(android.os.SystemClock.uptimeMillis() / 1000f) {
        // The aurora drifts over tens of seconds: 30 fps looks identical to 120 and
        // costs a quarter of the GPU (it matters with refraction, which redraws
        // every panel through its lens on each aurora frame).
        while (true) {
            androidx.compose.runtime.withFrameMillis { value = android.os.SystemClock.uptimeMillis() / 1000f }
            kotlinx.coroutines.delay(30)
        }
    }
    // Refraction (GlassLens.kt): the backdrop is also recorded into a RenderNode the
    // panels bend. Aurora → recorded every frame; snapshot → recorded once.
    val backdrop = remember { GlassBackdrop() }
    val lensOn = GlassLensConfig.supported && GlassLensConfig.enabled
    backdrop.tick = clock
    val drawAurora: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = { t ->
        drawRect(g.base)
        val w = size.width; val h = size.height
        BLOBS.forEachIndexed { i, b ->
            val color = g.blobs[i % g.blobs.size]
            val cx = (0.5f + 0.44f * kotlin.math.sin(t * b.wx + b.px)) * w
            val cy = (1.0f + 0.88f * kotlin.math.sin(t * b.wy + b.py) - span.offset) * h
            val rad = b.r * h
            if (cy + rad < 0f || cy - rad > h) return@forEachIndexed  // off this screen
            val c = androidx.compose.ui.geometry.Offset(cx, cy)
            drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = g.blobAlpha),
                    0.45f to color.copy(alpha = g.blobAlpha * 0.55f),
                    1f to Color.Transparent,
                    center = c, radius = rad,
                ),
                radius = rad, center = c,
            )
        }
        drawRect(g.scrim)
    }
    val realTint: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
        // Frost over the live blur: Appearance → Transparency (0.5 = the default look; a
        // heavier frost read as opaque).
        val clear = app.wayfinder.AppSettings.glassClarity
        // A floor for readability: with little blur the app behind stays sharp and fights the
        // text (fully see-through + no blur over a white app: the Hub washed out — critique
        // 2026-09-25). The sharper the background, the more frost it keeps.
        val floor = 0.42f - 0.24f * app.wayfinder.AppSettings.glassBlur
        drawRect(if (g.dark) Color(0xFF06070F).copy(alpha = maxOf(0.72f - 0.62f * clear, floor))
                 else Color(0xFFF3F4FA).copy(alpha = maxOf(0.64f - 0.56f * clear, floor)))
        drawRect(g.scrim)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
    Box(
        modifier.fillMaxSize().drawBehind {
            val hw = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            if (realGlass) {
                // The system is blurring the real background behind this window, live —
                // just add a tint so text stays readable, like frosted glass. (It can't be
                // refracted: apps never get those pixels. Panels still catch the light.)
                backdrop.ready = false
                realTint()
                return@drawBehind
            }
            val t = clock?.value ?: 0f
            if (lensOn && hw) {
                backdrop.record(this) { drawAurora(t) }
                drawIntoCanvas { it.nativeCanvas.drawRenderNode(backdrop.node) }
            } else drawAurora(t)
        }
    ) { content() }
    }
}



/**
 * The shared glass surface: translucent top-lit frost + specular rim (bright at
 * the top-left, fading through clear, faint at the bottom-right).
 */
fun Modifier.glassSurface(
    g: GlassColors,
    shape: androidx.compose.ui.graphics.Shape,
    raised: Boolean = false,
): Modifier = this
    .clip(shape)
    .glassLens(shape)
    .background(if (raised) g.panelFillFocused else g.panelFill, shape)
    .glassLight(shape, g.dark)
    .border(
        1.dp,
        Brush.linearGradient(0f to g.rimTop, 0.42f to Color.Transparent, 1f to g.rimBottom),
        shape,
    )

/** A static frosted glass panel (no interaction). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = Glass.CardRadius,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val g = LocalGlass.current
    Box(modifier.glassSurface(g, RoundedCornerShape(radius), raised = strong)) { content() }
}

/**
 * Interactive glass container. Handles focus + all input paths and passes the
 * focused state to [content] so callers can react if needed. Draws the ring here.
 */
@Composable
fun FocusableGlass(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = Glass.CardRadius,
    strong: Boolean = true,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val g = LocalGlass.current
    // The touch handler below is installed once; without this it kept calling the FIRST
    // onClick — a toggle's second tap repeated its first action (found 2026-09-23).
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, spring(dampingRatio = 0.55f), label = "s")
    val halo by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = 400f), label = "h")
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // Focus halo: a blurred accent STROKE just outside the edge. (Not a
            // Modifier.shadow — a filled shadow shows THROUGH translucent glass as
            // a dark band; a ring around the outside doesn't.)
            .drawBehind {
                if (halo <= 0.01f) return@drawBehind
                val pad = 3.dp.toPx()
                val rPx = radius.toPx() + pad
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 5.dp.toPx()
                    color = g.accent.copy(alpha = 0.75f * halo).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(10.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawRoundRect(-pad, -pad, size.width + pad, size.height + pad, rPx, rPx, paint)
                }
            }
            .glassSurface(g, shape, raised = focused)
            .then(
                if (halo > 0.01f) Modifier.border(
                    1.5.dp,
                    Brush.linearGradient(listOf(g.accent.copy(alpha = halo), g.accent2.copy(alpha = halo))),
                    shape,
                ) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                // (R3/L3/Start's synthesized "center" fallback is dropped earlier, per
                // window, by PadFallbackFilter — so only a real select reaches here.)
                if (e.type == KeyEventType.KeyUp &&
                    (e.key == Key.ButtonA || e.key == Key.DirectionCenter ||
                        e.key == Key.Enter || e.key == Key.NumPadEnter)
                ) { click(); true } else false
            }
            // One explicit focus target (so requestFocus is reliable), plus touch.
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures { click() } }
    ) { content(focused) }
}

/** Big primary action tile: icon + label + optional subtitle. */
@Composable
fun GlassActionButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier, radius = Glass.ButtonRadius, focusRequester = focusRequester) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = tint ?: g.accent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = g.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (subtitle != null)
                    Text(subtitle, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tappable list row (label + value + chevron). */
@Composable
fun GlassListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier, radius = 16.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp))
            Text(label, color = g.textPrimary, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Text(
        text.uppercase(),
        color = g.textTertiary,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** A small status pill: colored dot + text. */
@Composable
fun StatusPill(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    GlassPanel(modifier, radius = 14.dp) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (ok) Glass.Positive else g.textTertiary))
            Text(text, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Controller-navigable segmented control (e.g. theme: System / Light / Dark). */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val g = LocalGlass.current
    // 1.3: five or more options (the fan: Usual · Off · Quiet · Smart · Sports · Custom) — no check mark
    // (the fill says it), smaller text, one line: "Usual" wrapped into "Usua / l"
    val tight = options.size >= 5
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (tight) 6.dp else 8.dp)) {
        options.forEachIndexed { i, opt ->
            val selected = i == selectedIndex
            // Selected = the blue fill + a check; focused (the controller is HERE) = a thick ring in
            // the text colour. Both were blue before: with the controller you couldn't tell which
            // option was chosen and which one A would pick (release critique 2026-09-24).
            FocusableGlass(onClick = { onSelect(i) }, modifier = Modifier.weight(1f), radius = 14.dp) { focused ->
                Box(
                    Modifier.fillMaxWidth()
                        .then(
                            if (selected) Modifier
                                .background(Brush.linearGradient(listOf(g.accent, g.accent2)), RoundedCornerShape(14.dp))
                                .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color.Transparent)), RoundedCornerShape(14.dp))
                            else Modifier
                        )
                        .then(if (focused) Modifier.border(3.dp, g.textPrimary, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected && !tight) androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Rounded.Check, null, tint = Color.White,
                            modifier = Modifier.padding(end = 6.dp).size(18.dp),
                        )
                        Text(
                            opt,
                            color = if (selected) Color.White else g.textSecondary,
                            style = if (tight) androidx.compose.material3.MaterialTheme.typography.labelMedium
                                else androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            maxLines = 1, softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
