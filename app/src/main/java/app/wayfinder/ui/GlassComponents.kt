package app.wayfinder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Reusable glass components + controller-focus behaviour. Every interactive glass
 * element is focusable (D-pad / left-stick-as-dpad), shows a spring focus ring,
 * and fires onClick on touch, D-pad center/Enter, OR gamepad A.
 * dev build 17 (WP8): ONE focus look for everything ([focusIndicator]); a selected option = the accent fill + a check
 * ([selectedFill]), never a ring. The other shared controls (buttons, switch rows, headers, notices) are in GlassControls.kt.
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
        // build 18 (A4): over a light app the light theme keeps more frost (a floor of 0.55), plus its wash at 25 %: a veil
        // that turns the hard edges behind into colour fields (ko-fi's buttons read as Wayfinder's). Dark keeps its numbers.
        drawRect(if (g.dark) Color(0xFF06070F).copy(alpha = maxOf(0.72f - 0.62f * clear, floor))
                 else Color(0xFFF3F4FA).copy(alpha = maxOf(0.64f - 0.56f * clear, 0.55f)))
        if (!g.dark) drawRect(g.wash.copy(alpha = 0.25f))
        drawRect(g.scrim)
    }
    // build 18 (A14): the page's focused control's role, for its header's button legend (ButtonLegend.kt)
    val roles = remember { FocusRoleState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalFocusRole provides roles) {
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
 * Build 18: [well] = an opaque base under the frost (A3: an unselected option — what's behind no longer tints it); on light
 * glass a 1 dp dark line just outside the edge (A4: a card keeps its edge over a white app).
 */
fun Modifier.glassSurface(
    g: GlassColors,
    shape: androidx.compose.ui.graphics.Shape,
    raised: Boolean = false,
    well: Color = Color.Unspecified,
): Modifier = this
    .then(if (g.dark) Modifier else Modifier.drawWithCache {
        val s = 1.dp.toPx()
        val o = shape.createOutline(Size(size.width + s, size.height + s), layoutDirection, this)
        onDrawBehind { translate(-s / 2f, -s / 2f) { drawOutline(o, Color(0x14000000), style = Stroke(s)) } }
    })
    .clip(shape)
    .glassLens(shape)
    .then(if (well.isSpecified) Modifier.background(well, shape) else Modifier)
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
    radius: androidx.compose.ui.unit.Dp = Radii.Panel,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val g = LocalGlass.current
    Box(modifier.glassSurface(g, RoundedCornerShape(radius), raised = strong)) { content() }
}

/**
 * dev build 16 (WP7): select keys act on their RELEASE, and only one whose press this element saw (both halves taken) —
 * the press that opened a page, or that was held while the focus moved here, never clicks on its release.
 */
internal class ReleaseGate {
    var armed = false
    /** [e] is a select key (A, centre, Enter): taken, and [act] runs on a release whose press came here. Else false. */
    fun onKey(e: androidx.compose.ui.input.key.KeyEvent, act: () -> Unit): Boolean {
        if (!isSelect(e)) return false
        return when (e.type) {
            KeyEventType.KeyDown -> { press(e.nativeKeyEvent.repeatCount); true }
            KeyEventType.KeyUp -> { release(e.nativeKeyEvent.isCanceled, act); true }
            else -> false
        }
    }
    /** A press (its first event: repeat 0) arms it. */
    fun press(repeat: Int) { if (repeat == 0) armed = true }
    /** A release acts only on a press it saw — and never on a cancelled one: dev build 16 review (WP7-1), the window losing
     *  focus while A is held (the AYN button's quick panel, a screen switch) sends a cancelled A up, which fired the control
     *  behind the panel. Disarmed either way (still taken: no fallback key). */
    fun release(canceled: Boolean, act: () -> Unit) { val go = armed && !canceled; armed = false; if (go) act() }
    companion object {
        fun isSelect(e: androidx.compose.ui.input.key.KeyEvent) =
            e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    }
}

/**
 * dev build 17 (WP8): THE focus look — a blurred accent halo just outside [shape], a ring (build 18, A1: 2 dp in g.focusRing,
 * 2 dp outside the edge — it reads on a selected option's accent fill too) and a spring scale (build 18, A11: 1.035, at most
 * 12 dp wider — a full-width row grew 30 dp into the gutter), while this element (or something inside it) has the focus.
 * [inset] = an element inside a card or a clipping
 * parent (an inner switch row, a slider, a text field): no scale, the halo drawn just inside the edge so the parent's clip
 * can't cut it. Put it BEFORE the element's own surface (it scales it and draws its rim over it) and before `.focusable()`
 * (it observes that target). The numbers are FocusableGlass's of build 16.
 * Device pass (P2-4): and the element it marks comes into view with [FOCUS_VIEW_MARGIN] above and below as the focus
 * arrives (A11) — every control with the focus look (a card, an option, a slider, a text field, the colour wheel), not only
 * [FocusableGlass]: a slider or a code field focused below the fold stayed there, with only `focusable()`'s own bring
 * (flush, and dropped by a card's later request). A focus that comes before the element's first measure (a page's entry)
 * brings it once it has a size (FocusableGlass skipped it).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.focusIndicator(shape: Shape, inset: Boolean = false): Modifier = composed {
    val g = LocalGlass.current
    var on by remember { mutableStateOf(false) }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    // its size and "focused before it had one" — plain holders, read when the focus or the size changes
    val box = remember { intArrayOf(0, 0) }
    val pending = remember { booleanArrayOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val halo by animateFloatAsState(if (on) 1f else 0f, spring(stiffness = 400f), label = "h")
    // how far it has grown (0 → 1, the spring's bounce kept); the scale it reaches depends on its width (graphicsLayer)
    val grow by animateFloatAsState(if (on && !inset) 1f else 0f, spring(dampingRatio = 0.55f), label = "s")
    // one Paint per element (it was allocated every frame), only its colour changes
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxGrowPx = with(density) { FOCUS_GROW_MAX.toPx() }
    val paint = remember(density, inset) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            with(density) {
                strokeWidth = (if (inset) 3.dp else 5.dp).toPx()
                maskFilter = android.graphics.BlurMaskFilter((if (inset) 6.dp else 10.dp).toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val marginPx = with(density) { FOCUS_VIEW_MARGIN.toPx() }
    // b18r3 (P2-4, r1 BUG-1): the page's view of its focus ([FocusInView]: its header snap never undoes this bring) and where
    // this element is drawn (the bring's own coordinates)
    val page = LocalFocusInView.current
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    fun comeIntoView() {
        pending[0] = false
        val w = box[0].toFloat(); val h = box[1].toFloat()
        val job = scope.launch { runCatching { bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -marginPx, w, h + marginPx)) } }
        page?.track(job)
    }
    if (page != null) androidx.compose.runtime.DisposableEffect(page) { onDispose { page.leave(box) } }
    this
        .bringIntoViewRequester(bring)
        .onPlaced { placed[0] = it }
        .onSizeChanged {
            // b18r3 (P2-4): focused and its size changed (a switch card's "?" line opening under the controller) — it asks
            // again: the bring at the focus's arrival was met before it grew, and its end stayed under the edge (Hints)
            val grew = on && box[0] > 0 && (it.width != box[0] || it.height != box[1])
            box[0] = it.width; box[1] = it.height
            if (pending[0] && on && it.width > 0) comeIntoView()
            else if (grew && !InputMode.lastWasTouch) comeIntoView()
        }
        // hasFocus: a container (a text field's gate + field) lights when either part has it. Device pass (P2-4): the focus
        // arriving (not a move inside the container) brings it into view — now, or at its first size
        .onFocusChanged {
            val arrived = it.hasFocus && !on
            on = it.hasFocus
            if (arrived) {
                page?.arrive(box, { placed[0] }) { if (on && box[0] > 0 && !InputMode.lastWasTouch) comeIntoView() }
                if (box[0] > 0) comeIntoView() else pending[0] = true
            }
            else if (!it.hasFocus) { pending[0] = false; page?.leave(box) }
        }
        // build 18 (A11): 1 + min(0.035, 12 dp / width) — a pill keeps its 3.5 %, a wide row grows ~6 dp a side
        .then(if (inset) Modifier else Modifier.graphicsLayer {
            val w = size.width
            val s = 1f + grow * (if (w > 0f) minOf(FOCUS_SCALE - 1f, maxGrowPx / w) else FOCUS_SCALE - 1f)
            scaleX = s; scaleY = s
        })
        // Focus halo: a blurred accent STROKE just outside the edge. (Not a Modifier.shadow — a filled shadow shows THROUGH
        // translucent glass as a dark band; a ring around the outside doesn't.)
        .drawBehind {
            if (halo <= 0.01f) return@drawBehind
            val pad = if (inset) -2.dp.toPx() else 3.dp.toPx()
            val w = size.width + 2 * pad; val h = size.height + 2 * pad
            if (w <= 0f || h <= 0f) return@drawBehind
            paint.color = g.accent.copy(alpha = (if (inset) 0.6f else 0.75f) * halo).toArgb()
            val outline = shape.createOutline(Size(w, h), layoutDirection, this)
            drawIntoCanvas { c ->
                val n = c.nativeCanvas
                n.save(); n.translate(-pad, -pad)
                when (outline) {
                    is Outline.Rounded -> outline.roundRect.let { r ->
                        n.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint) }
                    is Outline.Rectangle -> outline.rect.let { r -> n.drawRect(r.left, r.top, r.right, r.bottom, paint) }
                    is Outline.Generic -> n.drawPath(outline.path.asAndroidPath(), paint)
                }
                n.restore()
            }
        }
        // build 18 (A1): the ring — 2 dp in g.focusRing (white on the dark themes, a deep accent on light), drawn over
        // everything after it in the chain: outside the element with a 2 dp gap, at the halo's outer edge — or just inside
        // the edge when [inset] (a clipping parent can't cut it). It reads on any fill, a selected option's accent included
        // (the accent→accent2 rim vanished there); the halo stays the accent's glow.
        .drawWithContent {
            drawContent()
            if (halo <= 0.01f) return@drawWithContent
            val stroke = 2.dp.toPx()
            val pad = if (inset) -stroke / 2f else 2.dp.toPx() + stroke / 2f
            val w = size.width + 2 * pad; val h = size.height + 2 * pad
            if (w <= 0f || h <= 0f) return@drawWithContent
            val outline = shape.createOutline(Size(w, h), layoutDirection, this)
            translate(-pad, -pad) { drawOutline(outline, g.focusRing.copy(alpha = g.focusRing.alpha * halo), style = Stroke(stroke)) }
        }
}

/** The focused element's scale (a pill's, a small control's) and, build 18 (A11), the most it may grow in width. */
private const val FOCUS_SCALE = 1.035f
private val FOCUS_GROW_MAX = 12.dp
/** Build 18 (A11): a focused element brought into view keeps this much room above and below it (it stopped flush on the
 *  screen's edge: More's last row). As much as the page's padding allows. Device pass (P2-4, B): internal — a card's own
 *  request (Hub.kt `SettingCard`) holds its focused control's, margin included, so the two nest instead of cancelling. */
internal val FOCUS_VIEW_MARGIN = 24.dp

/**
 * b18r3 (P2-4, r1 BUG-1): a scrolling page's view of its focus, for the page's own scroll moves (Hub.kt SubPage's header
 * snap), which must never undo a focus bring-into-view ([focusIndicator]'s, a card's): the snap sent a 25 px bring back to 0
 * and the focused control stayed cut at the bottom edge. [focused]: where the element with the focus look that holds the
 * focus is drawn (null = none, or gone); [bringing]: the focus's bring-into-view requests still running (snapshot state: the
 * snap waits for 0 — a scroll it started would cancel them); [reflowed]: the page's content changed size (a card's "?" line,
 * the room under the last row) — the focused element asks again (one already in view moves nothing). Provided by the page
 * ([LocalFocusInView]); elsewhere null, and nothing reports.
 */
class FocusInView {
    private var owner: Any? = null
    private var where: () -> androidx.compose.ui.layout.LayoutCoordinates? = { null }
    private var again: () -> Unit = {}
    private var away = false
    val bringing = androidx.compose.runtime.mutableIntStateOf(0)
    /** [owner]'s element took the focus: [where] it is drawn, and its bring-into-view [again]. */
    fun arrive(owner: Any, where: () -> androidx.compose.ui.layout.LayoutCoordinates?, again: () -> Unit) {
        this.owner = owner; this.where = where; this.again = again; away = false
    }
    /** [owner]'s element lost the focus (or left the page). */
    fun leave(owner: Any) { if (this.owner === owner) { this.owner = null; where = { null }; again = {} } }
    fun focused(): androidx.compose.ui.layout.LayoutCoordinates? = where()?.takeIf { it.isAttached }
    /** The page scrolled past its focus on purpose (Down with nothing below: the text under the last control, QA2): a reflow
     *  doesn't bring the focus back until it moves. */
    fun scrolledAway() { away = true }
    fun reflowed() { if (!away) again() }
    /** A focus bring-into-view runs as [job]: counted until it ends (met, cancelled, or never started). */
    fun track(job: kotlinx.coroutines.Job) { bringing.intValue++; job.invokeOnCompletion { bringing.intValue-- } }
}

/** b18r3 (P2-4): the [FocusInView] of the page being drawn (Hub.kt SubPage), null elsewhere. */
val LocalFocusInView = androidx.compose.runtime.compositionLocalOf<FocusInView?> { null }

/**
 * Build 18 (A0): a scrolling list's edges fade out — the content is drawn offscreen, then a [size] tall gradient at each
 * edge asked for keeps it (DstIn). [top] / [bottom]: fade that edge — pass the list's canScrollBackward / canScrollForward
 * so an edge fades only while there is more beyond it.
 * Build 18 review (A): the offscreen buffer is [SCROLL_FADE_BLEED] wider than the list on each side — what its scroll
 * container lets spill sideways. A layer the list's own size (graphicsLayer Offscreen) cut a full-width row's focus ring,
 * halo and focus scale at the list's left and right edges (Combos, Game controls' popups). Top and bottom stay clipped to
 * the list, as the scroll container clips them.
 */
fun Modifier.scrollFade(top: Boolean, bottom: Boolean, size: androidx.compose.ui.unit.Dp = 16.dp): Modifier {
    if (!top && !bottom) return this
    val fade = size
    return this.drawWithCache {
        val w = this.size.width; val h = this.size.height
        val f = fade.toPx().coerceAtMost(h / 2f)
        val bleed = SCROLL_FADE_BLEED.toPx()
        val layer = androidx.compose.ui.geometry.Rect(-bleed, 0f, w + bleed, h)
        val layerPaint = androidx.compose.ui.graphics.Paint()
        val topFade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = f)
        val bottomFade = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = h - f, endY = h)
        onDrawWithContent {
            if (f <= 0f) { drawContent(); return@onDrawWithContent }
            drawIntoCanvas { it.saveLayer(layer, layerPaint) }
            drawContent()
            if (top) drawRect(topFade, topLeft = androidx.compose.ui.geometry.Offset(-bleed, 0f), size = Size(w + 2 * bleed, f),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            if (bottom) drawRect(bottomFade, topLeft = androidx.compose.ui.geometry.Offset(-bleed, h - f),
                size = Size(w + 2 * bleed, f), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            drawIntoCanvas { it.restore() }
        }
    }
}

/** Build 18 review (A): how far [scrollFade]'s buffer reaches past the list's sides — at least the 15 dp a scroll container
 *  lets its content spill sideways, which covers a focused row's ring (8-10 dp out) and most of its halo. */
private val SCROLL_FADE_BLEED = 16.dp

/** dev build 17 (WP8): what "selected" looks like — the accent gradient with a top gloss (the segmented control's fill). With
 *  a check ([GlassOption]): a ring always means focus. */
fun Modifier.selectedFill(g: GlassColors, shape: Shape): Modifier =
    background(Brush.linearGradient(listOf(g.accent, g.accent2)), shape)
        .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color.Transparent)), shape)

/**
 * Interactive glass container. Handles focus + all input paths and passes the
 * focused state to [content] so callers can react if needed. Draws the ring here.
 * dev build 16 (WP7): [focusId] = its stable id on the page ([EntryFocus]: Back comes back to it); the first one under a
 * page's [LocalEntryFocus] is where the page opens; the window's [FocusMemory] keeps the last one focused or tapped.
 * dev build 17 (WP8): the ring is [focusIndicator]; [contentAlignment] places [content] in a box made wider than it
 * (GlassButton, GlassOption: centred).
 * Build 18: focused, it comes into view with [FOCUS_VIEW_MARGIN] above and below (A11); [role] = what it is for the page's
 * button legend (A14, ButtonLegend.kt).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusableGlass(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = Radii.Panel,
    strong: Boolean = true,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    focusId: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    well: Color = Color.Unspecified,
    role: FocusRole? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val g = LocalGlass.current
    // The touch handler below is installed once; without this it kept calling the FIRST
    // onClick — a toggle's second tap repeated its first action (found 2026-09-23).
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    var focused by remember { mutableStateOf(false) }
    // dev build 16 (WP7): always one requester of its own (entry, return and sheet focus reach every element); the page's
    // entry and the window's memory are read in composition (critique m2), registered after it
    val own = remember { androidx.compose.ui.focus.FocusRequester() }
    val req = focusRequester ?: own
    val reqNow by androidx.compose.runtime.rememberUpdatedState(req)
    val entry = LocalEntryFocus.current
    val mem = LocalFocusMemory.current
    val gate = remember { ReleaseGate() }
    androidx.compose.runtime.DisposableEffect(entry, focusId, req) {
        if (entry != null && focusId != null) entry.ids[focusId] = req
        onDispose { if (focusId != null && entry?.ids?.get(focusId) === req) entry.ids.remove(focusId) }
    }
    OfferEntry(req)
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    // build 18 (A11): focused → in view with room above and below — device pass (P2-4): done by focusIndicator now, for every
    // control with the focus look (and once it has a size: an entry focused before its first measure was skipped here)
    // build 18 (A14): what it is, for the page's button legend (cleared when it leaves the composition focused)
    val roles = if (role != null) LocalFocusRole.current else null
    if (roles != null) androidx.compose.runtime.DisposableEffect(roles) { onDispose { roles.clear(own) } }
    Box(
        modifier
            .focusRequester(req)
            .focusIndicator(shape)
            .glassSurface(g, shape, raised = focused, well = well)
            .onFocusChanged {
                focused = it.isFocused
                FocusTruth.report(req, it.hasFocus)          // device pass (P1-3): requestAfterLayout reads it
                if (it.isFocused) {
                    mem?.last = req
                    if (roles != null && role != null) roles.set(own, role)
                } else { gate.armed = false; roles?.clear(own) }
            }
            // (R3/L3/Start's synthesized "center" fallback is dropped earlier, per window, by PadFallbackFilter — so only a
            // real select reaches here.) dev build 16 (WP7): on its release, a press seen here only — both halves taken
            .onKeyEvent { e -> gate.onKey(e) { click() } }
            // One explicit focus target (so requestFocus is reliable), plus touch.
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures { mem?.last = reqNow; click() } },
        contentAlignment = contentAlignment,
    ) { content(focused) }
}

/**
 * A text field the controller passes over like a button: A (or a tap) starts typing — the keyboard opens; ↵, B, or
 * leaving it with the D-pad stops. (QA2: a plain field opened the keyboard as soon as the D-pad crossed it, and the
 * keyboard then kept the D-pad.) [modifier] sizes it; [surface] is its look (glassSurface / background).
 * Build 18 (A0): [startEditing] = typing (and the keyboard) from the start (the Search page); [imeAction] = the keyboard's
 * action key, [onSubmit] runs on it after the typing stops; [gateFocus] = the requester of its not-typing target (a page
 * links its focus order to it: `focusProperties { up = gate }`); [focusId] = that target's id on the page ([EntryFocus]).
 * A field whose gate is hoisted or named, and that doesn't start typing, offers it as the page's entry (a plain field —
 * a picker's search box — never takes the entry from the content below it, as before).
 * Build 18 (A19): [keyboardOptions] = the keyboard's kind (capitals, no correction: a colour code; its action key stays
 * [imeAction]); [selectAllOnStart] = typing starts with the whole text selected, so the first key replaces it (a colour code
 * typed without seven Backspaces) — else the cursor at the end, as before.
 */
@Composable
fun ControllerTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle, placeholder: String? = null, singleLine: Boolean = true,
    surface: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radii.Control),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    startEditing: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction =
        if (singleLine) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
    onSubmit: (() -> Unit)? = null,
    gateFocus: androidx.compose.ui.focus.FocusRequester? = null,
    focusId: String? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    selectAllOnStart: Boolean = false,
) {
    val g = LocalGlass.current
    var editing by remember { mutableStateOf(startEditing) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    val ownGate = remember { androidx.compose.ui.focus.FocusRequester() }
    val gate = gateFocus ?: ownGate
    val submit by androidx.compose.runtime.rememberUpdatedState(onSubmit)
    // build 18 (A0): its id on the page (Back comes back to it), and the page's entry when it doesn't start typing
    val entry = LocalEntryFocus.current
    androidx.compose.runtime.DisposableEffect(entry, focusId, gate) {
        if (entry != null && focusId != null) entry.ids[focusId] = gate
        onDispose { if (focusId != null && entry?.ids?.get(focusId) === gate) entry.ids.remove(focusId) }
    }
    if (!startEditing && (gateFocus != null || focusId != null)) OfferEntry(gate)
    val gateKeys = remember { ReleaseGate() }      // dev build 16 (WP7): A starts typing on a release seen here
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 1.4.1: the text with its cursor — typing starts at the END (Compose starts at 0: a hex code typed before the old one)
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    if (tfv.text != value) tfv = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    fun stop() {
        if (!editing) return
        editing = false; keyboard?.hide(); runCatching { gate.requestFocus() }
    }
    // device pass (P1-2): the field has held the focus since this typing started — only then does losing it end the typing.
    // Compose sends every newly attached focus node its state once: the field's first event is "not focused", and with
    // [startEditing] it ended the typing before the field could take the focus — Search opened with nothing focused and
    // no keyboard (the field refused the focus, the gate was never asked). A plain holder, read in the focus callback
    val fieldHeld = remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) {
            fieldHeld[0] = false
            // build 18 (A19): the whole text selected when asked (the first key replaces it), else the cursor at the end
            tfv = tfv.copy(selection = if (selectAllOnStart) androidx.compose.ui.text.TextRange(0, tfv.text.length)
                else androidx.compose.ui.text.TextRange(tfv.text.length))
            // the field can take the focus once [editing] is laid out (one frame). Device pass (P1-2): it didn't (never
            // attached, or the focus went elsewhere) → no typing, the gate has the controller — never nothing focused
            if (!field.requestAfterLayout()) { editing = false; gate.requestAfterLayout() }
        }
    }
    // the keyboard closed (its B / ↵ / close key): the typing ends here too — one B, not two (QA2). b18n (N3-ui 2, b18v #8):
    // Android's insets see only a keyboard window on this screen — Wayfinder Keyboard's keys on the other one with "Keep a
    // thin bar" off have none: its own word says it is up (else B closed the keys, the next B only left the field)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeShown = androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible ||
        app.wayfinder.keyboard.ThorKeyboardService.upForOwnField
    var imeSeen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(imeShown, editing) {
        if (!editing) imeSeen = false
        else if (imeShown) imeSeen = true
        else if (imeSeen) { kotlinx.coroutines.delay(150); if (editing) stop() }
    }
    // dev build 17 (WP8): the one focus look, inside its edge (hasFocus: the gate or the field while typing)
    Box(modifier.focusIndicator(shape, inset = true).then(surface)) {
        androidx.compose.foundation.text.BasicTextField(
            tfv, { v -> tfv = v; if (v.text != value) onValueChange(v.text) }, singleLine = singleLine, textStyle = textStyle,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(g.accent),
            keyboardOptions = keyboardOptions.copy(imeAction = imeAction),
            // build 18 (A0): whatever the action key is (Done, Search, Go…), it stops the typing, then [onSubmit]
            keyboardActions = run {
                val act: androidx.compose.foundation.text.KeyboardActionScope.() -> Unit = { stop(); submit?.invoke() }
                androidx.compose.foundation.text.KeyboardActions(onDone = act, onGo = act, onNext = act, onSearch = act, onSend = act)
            },
            modifier = (if (singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .padding(contentPadding)
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                // device pass (P1-2): leaving the field ends the typing — once it has held the focus (not its first event)
                .onFocusChanged {
                    FocusTruth.report(field, it.hasFocus)
                    if (it.isFocused) fieldHeld[0] = true
                    else if (editing && fieldHeld[0]) editing = false
                }
                .onKeyEvent { e ->
                    // the keyboard closed: B (or Escape) ends the typing here instead of leaving the page
                    if (editing && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape)) {
                        if (e.type == KeyEventType.KeyUp) stop(); true
                    } else false
                },
            decorationBox = { inner ->
                Box {
                    // dev build 16 (theme F): a placeholder may name a PlayStation button (drawn as its mark)
                    if (value.isEmpty() && placeholder != null) GlyphText(placeholder, color = g.textTertiary, style = textStyle)
                    inner()
                }
            },
        )
        // not typing: one focus target over the field (A or a tap starts). Always there — only off while typing — so
        // the focus has somewhere to return when the typing stops.
        Box(Modifier.matchParentSize()
            .focusRequester(gate)
            .focusProperties { canFocus = !editing }
            .onFocusChanged { FocusTruth.report(gate, it.hasFocus); if (!it.isFocused) gateKeys.armed = false }
            .onKeyEvent { e -> gateKeys.onKey(e) { editing = true } }
            .focusable()
            .pointerInput(editing) { if (!editing) detectTapGestures { editing = true } })
    }
}

/** Big primary action tile: icon + label + optional subtitle. */
@Composable
fun GlassActionButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleLines: Int = 1,
    tint: Color? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier, radius = Radii.Action, focusRequester = focusRequester) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = tint ?: g.accent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                GlyphText(label, color = g.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (subtitle != null)
                    GlyphText(subtitle, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 1.4: which row opened which Hub page — Back focuses that row again. dev build 16 (WP7): by the row's id (not its label),
 *  after the page's first layout ([rememberEntryFocus]). */
object ReturnFocus {
    /** The row just pressed (the page it opens records it). */
    @Volatile var clicked: String? = null
    /** The row to focus on the page being shown (set by Back). */
    @Volatile var pending: String? = null
    /** The page being shown takes the row to come back to (once: a later page never gets it). */
    fun take(): String? = pending.also { pending = null }
    val opener = HashMap<String, String>()
    /** A page opened by a link from another page than its parent → Back goes back there. */
    val from = HashMap<String, String>()
    @Volatile var shown: String? = null
    @Volatile var goingBack = false
}

/** What a [GlassListRow] shows after its value (build 18, A0): nothing, a chevron (it opens a page) or "open in new"
 *  (it leaves Wayfinder: a link, another app). */
enum class RowEnd { NONE, CHEVRON, EXTERNAL }

/** A tappable list row: label + value + optional end mark ([end], build 18 A0: a row that opens a page shows a chevron, one
 *  that leaves Wayfinder the "open in new" mark). [id] = its stable id on the page (dev build 16, WP7: a controller's row
 *  "pad:<key>" — its label changes with its name). b18n (N3-ui 3): the label and the value share the row by [RowRules.split]
 *  ([NameAndValue]); [labelLines] = the label's lines at most, cut with "…" (a controller's name: 1). */
@Composable
fun GlassListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    id: String = label,
    end: RowEnd = RowEnd.NONE,
    labelLines: Int = Int.MAX_VALUE,
    onClick: () -> Unit,
) {
    val g = LocalGlass.current
    // 1.4: back from the page this row opened → the controller is on this row again (dev build 16, WP7: the page's
    // EntryFocus finds it by [id] after its first layout — no timer)
    FocusableGlass(onClick = {
        ReturnFocus.clicked = id; onClick()
        // a row that doesn't open a page (a switch, "More options") is forgotten
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (ReturnFocus.clicked == id) ReturnFocus.clicked = null }, 700)
    }, modifier = modifier, radius = Radii.Row, focusId = id, role = if (end == RowEnd.CHEVRON) FocusRole.OPEN else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp))
            val name: @Composable () -> Unit = { GlyphText(label, color = g.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                maxLines = labelLines, overflow = TextOverflow.Ellipsis) }
            if (value == null) Box(Modifier.weight(1f)) { name() }
            else NameAndValue(Modifier.weight(1f), 12.dp, name) {
                GlyphText(value, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            }
            when (end) {
                RowEnd.NONE -> {}
                RowEnd.CHEVRON -> Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    tint = g.textTertiary, modifier = Modifier.size(20.dp))
                RowEnd.EXTERNAL -> Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.OpenInNew, null,
                    tint = g.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * b18n (N3-ui 3, b18m check obs. 1): a row's name and value side by side, by [RowRules.split] — on one line when both fit
 * (the name fills, the value at the end, as before); else the name keeps its one-line width (up to half the room) and the
 * value wraps in the rest. In a plain Row the value was measured first, at its one-line width: a long status left the name
 * a few letters wide ("WFT / DualSe / nse" over three lines).
 */
@Composable
private fun NameAndValue(modifier: Modifier, gap: androidx.compose.ui.unit.Dp, name: @Composable () -> Unit, value: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout({ Box { name() }; Box { value() } }, modifier) { ms, c ->
        val gapPx = gap.roundToPx()
        val nameOne = ms[0].maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val valueOne = ms[1].maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val room = if (c.hasBoundedWidth) c.maxWidth else nameOne + gapPx + valueOne
        val (nameW, valueW) = RowRules.split(room, gapPx, nameOne, valueOne)
        val n = ms[0].measure(androidx.compose.ui.unit.Constraints(minWidth = nameW, maxWidth = nameW))
        val v = ms[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = valueW))
        val h = maxOf(n.height, v.height).coerceIn(c.minHeight, c.maxHeight)
        layout(room, h) {
            n.placeRelative(0, (h - n.height) / 2)
            v.placeRelative(room - v.width, (h - v.height) / 2)
        }
    }
}

/** b18n (N3-ui 3): the list row's rules that need no Android (ControllerRowsTest). */
internal object RowRules {
    /** The name's and the value's widths in [room] px with [gap] between, from their one-line widths ([nameOne], [valueOne]):
     *  both fit — the value its line, the name the rest (it fills); else the name its line up to half the room (or all the
     *  value's line leaves it, when that is more), the value the rest — it wraps there. Never below 0. */
    fun split(room: Int, gap: Int, nameOne: Int, valueOne: Int): Pair<Int, Int> {
        val free = (room - gap).coerceAtLeast(0)
        if (nameOne + valueOne <= free) return (free - valueOne) to valueOne
        val name = minOf(nameOne, maxOf(free / 2, free - valueOne)).coerceAtLeast(0)
        return name to (free - name)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    // dev build 15 (WP2): a hint in it ("… ✕ on the wheel …") draws PlayStation's marks as shapes
    GlyphText(
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
    GlassPanel(modifier, radius = Radii.Control) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) g.positive else g.textTertiary))
            Text(text, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * dev build 17 (WP8): one option — the segmented control's cell, also used alone where options aren't one row: a multi-pick
 * grid (Game controls' "Several at once"), a list of actions with a line each. Selected = fill + check; focus = the halo
 * and the ring. Replaces RemapScreen's Pill(selected) / Choice, LightsPage's
 * ModeChip, ActionPicker's and PanelShortcutsPage's selected rows. Give it a width (weight / width / fillMaxWidth).
 * Build 18 (A10): the check is always a corner badge (14 dp; 12 when [tight]) — it takes no text width, so a word never
 * moves when it gets selected (the inline "✓ Usual" is gone); the same look in a folded choice's open list. [role]: the
 * page's button legend (A14) — a choice among options side by side by default.
 */
@Composable
fun GlassOption(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                caption: String? = null, tight: Boolean = false,
                align: Alignment.Horizontal = Alignment.CenterHorizontally,
                focusRequester: androidx.compose.ui.focus.FocusRequester? = null, focusable: Boolean = true, focusId: String? = null,
                role: FocusRole = FocusRole.OPTION) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Radii.Control)
    // build 18 (A3): unselected, an opaque well under its glass — the app or the aurora behind can't tint it "half selected"
    FocusableGlass(onClick = onClick, modifier = modifier.focusProperties { canFocus = focusable }, radius = Radii.Control,
        focusRequester = focusRequester, focusId = focusId, contentAlignment = Alignment.Center,
        well = if (selected) Color.Unspecified else g.optionWell, role = role) {
        val start = align == Alignment.Start
        Box(Modifier.fillMaxWidth().then(if (selected) Modifier.selectedFill(g, shape) else Modifier),
            contentAlignment = if (start) Alignment.CenterStart else Alignment.Center) {
            // dev build 17 (review WP8-2): a tight cell keeps its width for the text (2 dp a side — 8 cut "Usual" in the
            // compact header's 43 dp cells, "PlayStation" in the full one's); the corner check's ink sits above the glyphs.
            // Build 18 (A10): a row of a list (start-aligned) starts its words where a choice row's do (14 dp)
            Column(Modifier.padding(horizontal = if (tight) TIGHT_PAD_DP.dp else if (start) 14.dp else 8.dp,
                    vertical = if (caption != null) 6.dp else 10.dp),
                horizontalAlignment = align) {
                // dev build 15 (WP2): a name with PlayStation's marks draws them as shapes
                GlyphText(text, color = if (selected) g.onAccent else g.textSecondary,
                    style = if (tight) androidx.compose.material3.MaterialTheme.typography.labelMedium
                        else androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) GlyphText(caption, color = if (selected) g.onAccent.copy(alpha = 0.85f) else g.textSecondary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = g.onAccent,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = if (tight) 3.dp else 4.dp, end = if (tight) 4.dp else 6.dp).size(if (tight) 12.dp else 14.dp))
        }
    }
}

/** Build 18 (A21, ps-F18): the line of the card a choice sits in, while an UNSELECTED option has the focus —
 *  "<option>: <hint>" ([GlassSegmentedControl]'s hints, [segmentHint]); null = the card's own line. The card provides it and
 *  shows it in place of its explanation (SettingCard, B14); none provided = the hints go nowhere. */
val LocalSegmentHint = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** Build 18 (A21): what one choice wrote to the card's [LocalSegmentHint] — it clears only its own words (two choices in a
 *  card, the focus moving from one to the other). */
private class SegmentHintWriter(private val sink: androidx.compose.runtime.MutableState<String?>) {
    private var mine: String? = null
    fun show(text: String?) {
        if (text != null) { mine = text; if (sink.value != text) sink.value = text }
        else if (mine != null) { if (sink.value == mine) sink.value = null; mine = null }
    }
}

/** Device pass (final): a page's "nothing left to focus below (above): the page scrolls" (QA2) — [down] = the key's way. The
 *  page that scrolls so provides it (SubPage, a game's app settings) and its own key handler runs the same one; a popup
 *  resets it (GlassSheet: the page behind never scrolls). A control that moves the focus itself and uses the key (a choice
 *  at its top or bottom row, [segmentEdgeScrolls]) calls it when nothing is beyond: the key no longer reaches the page, and
 *  the text after a page's last choice stayed out of reach. */
val LocalPageEdgeScroll = androidx.compose.runtime.compositionLocalOf<((down: Boolean) -> Unit)?> { null }

/** The writer for a choice with [hints] in a card that shows them; null = nothing to write (no hints, or no card line). */
@Composable
private fun rememberSegmentHint(hints: List<String>?): SegmentHintWriter? {
    val sink = LocalSegmentHint.current
    val w = remember(sink) { sink?.let { SegmentHintWriter(it) } }
    androidx.compose.runtime.DisposableEffect(w) { onDispose { w?.show(null) } }
    return w.takeIf { hints != null }
}

/** Controller-navigable segmented control (e.g. theme: System / Light / Dark). */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /** 1.4 (§10.4): 4+ choices show as one value row that opens its list; false keeps the buttons. dev build 17 (WP8): null =
     *  automatic — never with [captions] or several rows (a Kotlin default can't read the later parameters, hence nullable).
     *  Build 18 (A21, MORE-D03): automatic = by width ([segmentsFold]: under 150 dp an option, from 4 options), not by count */
    collapse: Boolean? = null,
    /** The choice's name, when the card has other choices too ("Where", "Corner"): above the buttons, or in the value row. */
    label: String? = null,
    /** dev build 15 (WP2): false = out of the controller's reach (Game controls' header behind an open popup). */
    itemsFocusable: Boolean = true,
    /** Smaller text and the check as a corner badge: five or more options, or a narrow place (Game controls' header). */
    tight: Boolean = options.size >= 5,
    /** dev build 17 (WP8): a line under each option (Game controls' fire modes: "one press", "rapid presses"…) */
    captions: List<String?>? = null,
    /** dev build 17 (WP8): options in rows of [columns] (Lights' effects: 4 a row) */
    columns: Int = options.size,
    /** dev build 17 (WP8): a popup's first control — requestFocus() lands on the current choice (the group's enter) */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** Build 18 (A21, ps-F18): a line per option (20 words at most), shown by the card ([LocalSegmentHint]) as
     *  "<option>: <hint>" while that option has the focus and isn't the selected one — no focus stop is added */
    hints: List<String>? = null,
    onSelect: (Int) -> Unit,
) {
    // build 18 (A21): fold by width — the room decides, not the count (measured only where it could fold)
    if (collapse == null && segmentsMayFold(options.size, captions != null, columns)) {
        // build 18 review (A): the page's entry is offered HERE, in the page's own composition order. BoxWithConstraints
        // composes its content while measuring, after the page's SideEffects: the current choice offered from inside came
        // after every control below it (Appearance opened on "More options", not on Theme). This requester reaches the
        // control inside — the segments' group (its enter: the current choice) or the folded value row — and nothing in
        // it offers itself again
        val entryReq = remember { androidx.compose.ui.focus.FocusRequester() }
        if (itemsFocusable) OfferEntry(entryReq)
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.reportingFocusRequester(entryReq)) {
            // the widest option in the segments' own style (GlassOption: labelMedium when tight, else labelLarge)
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val style = if (tight) androidx.compose.material3.MaterialTheme.typography.labelMedium
                else androidx.compose.material3.MaterialTheme.typography.labelLarge
            val density = androidx.compose.ui.platform.LocalDensity.current
            val widestDp = remember(options, style, density) {
                with(density) { (options.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0).toDp().value }
            }
            val fold = segmentsFold(options.size, maxWidth.value, captions != null, columns, widestDp, tight)
            androidx.compose.runtime.CompositionLocalProvider(LocalEntryFocus provides null) {
                GlassSegmentedControl(options, selectedIndex, Modifier.fillMaxWidth(), collapse = fold, label = label,
                    itemsFocusable = itemsFocusable, tight = tight, captions = captions, columns = columns,
                    focusRequester = focusRequester, hints = hints, onSelect = onSelect)
            }
        }
        return
    }
    val folded = collapse ?: false
    if (folded) { GlassChoiceRow(options, selectedIndex, modifier, label, itemsFocusable, hints, onSelect); return }
    // dev build 14 (WP1): the buttons dropped their label ("Controls for" on Game controls) — it's always shown now
    if (label != null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(label, Modifier.padding(start = 6.dp))
            GlassSegmentedControl(options, selectedIndex, Modifier.fillMaxWidth(), collapse = false, itemsFocusable = itemsFocusable,
                tight = tight, captions = captions, columns = columns, focusRequester = focusRequester, hints = hints, onSelect = onSelect)
        }
        return
    }
    // the D-pad into the row lands on the current choice (QA2: it took the nearest one)
    val optFocus = remember(options.size) { List(options.size) { androidx.compose.ui.focus.FocusRequester() } }
    val cols = columns.coerceIn(1, maxOf(1, options.size))
    // dev build 14 (WP1): Left/Right step through the options by index, not Compose's geometric search — with the
    // controller it stayed on the selected option (cause unconfirmed). At either end the key goes on, so focus can
    // still leave sideways (an "Edit ›" beside the row). dev build 17 (WP8): within its row (segmentStep)
    // build 18 (A2): Up / Down too — a grid steps by row (segmentStepVertical), and at its top or bottom the key goes on, so
    // the focus leaves the control (Down on the quick panel's "Top screen only" went back to the selected option; Down on
    // Lights' "Breathing", nothing under it, jumped back to "Colour")
    var at by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    // build 18 (A2): the focus is inside the control — its enter (the current choice) is only for a focus coming from
    // outside; a search that ends on this group while the focus is already in it must never send it back to the choice.
    // A plain holder: read when the focus moves, never in composition
    val inside = remember { booleanArrayOf(false) }
    // build 18 (A21): the focused option's hint on the card's line (an unselected one), cleared when the focus leaves the
    // control, reaches the selected option, or A picks the focused one
    val hint = rememberSegmentHint(hints)
    androidx.compose.runtime.LaunchedEffect(selectedIndex) { if (inside[0]) hint?.show(segmentHint(options, hints, at, selectedIndex)) }
    // device pass (P2-6): Up / Down at the top or bottom row LEAVE the control (A2). The key went on to Compose's 2D search
    // from the focused option — drawn larger by its focus scale — and a taller option of the same row (the only one with a
    // caption: Shows › "Everything") passed for the one below. Now the control moves the focus itself, with its other options
    // out of that one search ([leavingFrom]: the option it leaves from, -1 = none; a plain holder, read by the options'
    // focusProperties while the search runs). Nothing beyond it: the page's edge scroll ([LocalPageEdgeScroll], device pass
    // final — handed on, the key reached the page's own search, unrestricted); the key is used either way, never sideways.
    val leavingFrom = remember { intArrayOf(-1) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val edgeScroll = LocalPageEdgeScroll.current
    val gap = if (tight) 6.dp else 8.dp
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    Column(modifier
        .then(if (focusRequester != null) Modifier.reportingFocusRequester(focusRequester) else Modifier)
        .onFocusChanged { inside[0] = it.hasFocus; if (!it.hasFocus) hint?.show(null) }
        .focusProperties {
            enter = {
                // A2's reproduction aid (debug builds): an enter while the focus is already inside is the suspected cause
                if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorSeg", "enter inside=${inside[0]} selected=$selectedIndex")
                if (inside[0]) androidx.compose.ui.focus.FocusRequester.Default
                else optFocus.getOrNull(selectedIndex)?.takeIf { itemsFocusable } ?: androidx.compose.ui.focus.FocusRequester.Default
            }
        }
        .focusGroup()
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            // a key here = the focus is in the row; not told which option yet (focused before this composition): the one
            // focus enters on, the current choice
            val cur = if (at in optFocus.indices) at else selectedIndex
            val target = when (e.key) {
                Key.DirectionLeft -> segmentStep(cur, -1, options.size, cols)
                Key.DirectionRight -> segmentStep(cur, 1, options.size, cols)
                Key.DirectionUp -> segmentStepVertical(cur, -1, options.size, cols)
                Key.DirectionDown -> segmentStepVertical(cur, 1, options.size, cols)
                else -> null
            }
            val next = target?.let { optFocus.getOrNull(it) }
            if (app.wayfinder.BuildConfig.DEBUG && e.key in DIRECTION_KEYS) android.util.Log.d("ThorSeg", "${e.key} at $cur -> ${target ?: "leaves"}")
            if (next != null) { runCatching { next.requestFocus() }; true }
            else if (segmentLeavesVertically(e.key == Key.DirectionUp, e.key == Key.DirectionDown, cur, options.size)) {
                val down = e.key == Key.DirectionDown
                leavingFrom[0] = cur
                val moved = try {
                    focusManager.moveFocus(if (down) androidx.compose.ui.focus.FocusDirection.Down
                        else androidx.compose.ui.focus.FocusDirection.Up)
                } finally { leavingFrom[0] = -1 }
                if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorSeg", "left ${if (moved) "the control" else "nothing beyond"}")
                if (segmentEdgeScrolls(moved, edgeScroll != null)) edgeScroll?.invoke(down)
                true
            }
            else false
        }, verticalArrangement = Arrangement.spacedBy(gap)) {
        val entry = LocalEntryFocus.current
        for (row in options.indices.chunked(cols)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (i in row) {
                    val selected = i == selectedIndex
                    // dev build 16 (WP7): a page opening on this row lands on the current choice, as the D-pad does (not the first)
                    androidx.compose.runtime.CompositionLocalProvider(LocalEntryFocus provides
                        entry.takeIf { selected || (selectedIndex !in options.indices && i == 0) }) {
                        // Selected = the fill + a check; focused (the controller is HERE) = the halo. dev build 17 (WP8): the
                        // text-coloured ring is gone — the check shows the choice in every size now (a corner badge when tight)
                        GlassOption(options[i], selected, { onSelect(i) }, Modifier.weight(1f).onFocusChanged {
                                if (it.isFocused) {
                                    at = i; hint?.show(segmentHint(options, hints, i, selectedIndex))
                                    if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorSeg", "focus $i")
                                } }
                                // device pass (P2-6): no candidate while the control's own search leaves it (outermost:
                                // it wins over GlassOption's own canFocus for that one search only)
                                .focusProperties { if (leavingFrom[0] >= 0 && leavingFrom[0] != i) canFocus = false },
                            caption = captions?.getOrNull(i), tight = tight, focusRequester = optFocus[i], focusable = itemsFocusable)
                    }
                }
                // a short last row keeps the cells one width
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The D-pad's four keys (A2's debug log). */
private val DIRECTION_KEYS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/** 1.4 (§10.4): the current choice as one row (▾); opened, the choices as a list; a pick closes it. Build 18 (A10): each
 *  choice of the open list is a [GlassOption] — the current one filled with its corner check, as everywhere (it was accent
 *  text and a ✓). (A21): [hints] as [GlassSegmentedControl]'s, while an option of the open list has the focus. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassChoiceRow(options: List<String>, selectedIndex: Int, modifier: Modifier = Modifier, label: String? = null,
                   itemsFocusable: Boolean = true, hints: List<String>? = null, onSelect: (Int) -> Unit) {
    val g = LocalGlass.current
    val hint = rememberSegmentHint(hints)
    var open by remember { mutableStateOf(false) }
    // dev build 15 (WP2): out of reach = closed too (nothing left open behind a popup)
    androidx.compose.runtime.LaunchedEffect(itemsFocusable) { if (!itemsFocusable) open = false }
    val rowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val pickFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) pickFocus.requestAfterLayout()
        else if (refocus) { rowFocus.requestAfterLayout(); refocus = false }
    }
    // ...and it closes once the controller has left it: it stayed open behind you (uinav 5). Read as settled state —
    // a move between two of its rows reports "no focus here" for an instant (Compose clears, then grants).
    // dev build 14: then the row comes back into view. Down past the last choice, the control below jumped up by the
    // whole list's height, off screen with its ring (a pad's Part list), and Compose doesn't follow a focused control
    // when content above it shrinks. Left upwards or sideways, the row is already beside the new focus: nothing moves.
    var focusIn by remember { mutableStateOf(false) }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(focusIn) {
        if (!focusIn && open) { open = false; kotlinx.coroutines.delay(60); runCatching { bring.bringIntoView() } }
    }
    Column(modifier.bringIntoViewRequester(bring).onFocusChanged { focusIn = it.hasFocus; if (!it.hasFocus) hint?.show(null) },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusableGlass(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().focusProperties { canFocus = itemsFocusable }
            .onFocusChanged { if (it.isFocused) hint?.show(null) },
            radius = Radii.Control, focusRequester = rowFocus, role = FocusRole.LIST) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) FieldLabel(label, Modifier.padding(end = 14.dp))
                // dev build 15 (WP2): a controller's part names can carry PlayStation's marks ("Bottom button (✕)")
                GlyphText(options.getOrElse(selectedIndex) { "—" }, color = g.textPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (open) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                    null, tint = g.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        // dev build 14: B closes the open list and puts the controller back on its row — it left the whole page (uinav 5).
        // dev build 16 (WP7): a layer of its own — inside a sheet or a page it is the innermost, whatever came first
        BackLayer(enabled = open, onBack = { open = false; refocus = true }) {
        if (open) options.forEachIndexed { i, opt ->
            val selected = i == selectedIndex
            // build 18 (A10): the one selected look (fill + corner check); the focus still opens on the current choice
            GlassOption(opt, selected, { open = false; refocus = true; if (!selected) onSelect(i) },
                Modifier.fillMaxWidth().padding(start = 18.dp)
                    .onFocusChanged { if (it.isFocused) hint?.show(segmentHint(options, hints, i, selectedIndex)) },
                align = Alignment.Start,
                focusRequester = if (selected || (selectedIndex !in options.indices && i == 0)) pickFocus else null,
                role = FocusRole.PICK)
        }
        }
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
