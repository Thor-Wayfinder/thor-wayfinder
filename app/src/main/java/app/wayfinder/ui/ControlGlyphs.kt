package app.wayfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import app.wayfinder.ButtonNames
import app.wayfinder.FaceStyle
import app.wayfinder.PadLook
import app.wayfinder.Press
import app.wayfinder.ThorAction
import app.wayfinder.ThorButton
import app.wayfinder.Trigger
import app.wayfinder.Vocab

/**
 * Controller-style badges so a combo reads at a glance: coloured A/B/X/Y face
 * buttons, shoulder pills, a D-pad with the pressed arm lit, a stick for L3/R3,
 * Home/Back icons, and a press-type tag ("hold", "×2"…). Used by the Control Deck
 * cheat sheet and the Controls page.
 */

private val FACE = mapOf(
    ThorButton.A to Color(0xFF34C759), ThorButton.B to Color(0xFFFF453A),
    ThorButton.X to Color(0xFF0A84FF), ThorButton.Y to Color(0xFFFFD60A),
)

/** dev build 15 (WP2): the controller whose names a composable draws — a controller's page / tab, or a window that
 *  follows the controller in hand ([ButtonNames.handLook]), provides its own; none = the Thor's. */
val LocalPadLook = compositionLocalOf<PadLook?> { null }
/** The look to draw with here (it reads state: recomposes when a face style or a controller changes). */
@Composable fun currentLook(): PadLook = LocalPadLook.current ?: ButtonNames.thorLook

/** The whole trigger: [modifier] + button, or button + press tag. */
@Composable
fun TriggerGlyphs(t: Trigger, size: Dp = 30.dp, dim: Boolean = false, look: PadLook = currentLook()) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.modifier != null) {
            ButtonGlyph(t.modifier, size, dim, look)
            Text("+", color = g.textTertiary, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.SemiBold)
        }
        ButtonGlyph(t.button, size, dim, look)
        if (t.press != Press.TAP) PressTag(t.press)   // 1.4: a combo's second button can be doubled / held too
    }
}

@Composable
private fun PressTag(p: Press) {
    val g = LocalGlass.current
    val text = when (p) { Press.DOUBLE_TAP -> "×2"; Press.TRIPLE_TAP -> "×3"; Press.HOLD -> "hold"; Press.TAP -> "" }
    Box(
        // solid: readable on any row, selected or not (QA2 — accent on accent vanished)
        Modifier.background(g.accent, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge) }
}

/** 1.3.1 — the AYN button (not a [ThorButton]: it never goes through combos), tapped or held. Build 18 (A15): on a controller
 *  whose own button does AYN's job ([PadLook.ayn]: a DualSense's Mute) the chip says that button's name ([aynGlyphText]),
 *  as wide as its word needs ([aynGlyphWidth]); the Thor and a stand-in keep "AYN". */
@Composable
fun AynGlyph(hold: Boolean, size: Dp = 30.dp, look: PadLook = currentLook()) {
    val g = LocalGlass.current
    val chip = if (g.dark) Color(0x33FFFFFF) else Color(0x1A000000)
    val rim = if (g.dark) Color(0x66FFFFFF) else Color(0x40000000)
    val text = aynGlyphText(look)
    val need = size * aynGlyphWidth(text, size.value)
    val word: @Composable () -> Unit = {
        Box(
            Modifier.height(size).widthIn(min = need).background(chip, RoundedCornerShape(size / 2)).border(1.dp, rim, RoundedCornerShape(size / 2))
                .padding(horizontal = minOf(7.dp, size * 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = g.textPrimary, fontSize = aynTextSp(size.value).sp, fontWeight = FontWeight.Bold, maxLines = 1) }   // P2-8: the 11 sp floor
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // b18r3 (r2 BUG-1): in a slot its word doesn't fit at the floor, its button's mark (a DualSense's Mute) — never a cut word
        val short = aynShort(text)
        if (short == null) word() else FitOrShort(need, word) { SysIconBadge(short, size, text, g.textPrimary, chip, rim) }
        if (hold) PressTag(Press.HOLD)
    }
}

/**
 * b18r3 (r2 BUG-1, P2-8's side effect): [full] — a badge with a word, [need] wide at the 11 sp floor — where its slot holds
 * it, else [short] (its one-badge-wide mark: [sysGlyphShort], [aynShort]). The keyboard's globe key (≈ 36 dp) cut a
 * DualSense's "Create" (≈ 47 dp) to "Creat"; the floor stays, the word gives way. [need] is the width the inline slots
 * reserve ([rememberButtonInline]), so a badge in a line of text is always the full one, as before. A Layout, no
 * subcomposition: a parent may still ask its intrinsic size. The form not shown is never placed (no second description).
 */
@Composable
private fun FitOrShort(need: Dp, full: @Composable () -> Unit, short: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = {
        Box(propagateMinConstraints = true) { full() }
        Box(propagateMinConstraints = true) { short() }
    }) { parts, c ->
        val fits = !c.hasBoundedWidth || c.maxWidth + 1 >= need.roundToPx()
        val p = parts[if (fits) 0 else 1].measure(c)
        layout(p.width, p.height) { p.place(0, 0) }
    }
}

/** 1.4 (GitHub #50): PlayStation's symbol colours, keyed by the Xbox-position letter (A = ✕ …). */
private val PS_FACE = mapOf(
    ThorButton.A to Color(0xFF6F9FE6), ThorButton.B to Color(0xFFFF5A5F),
    ThorButton.X to Color(0xFFE38AC8), ThorButton.Y to Color(0xFF3DDC97),
)

/**
 * dev build 14: PlayStation's four marks, drawn as shapes. As text (✕ ○ □ △) they came from different
 * fallback fonts, each with its own weight, size and baseline: the square small and low ("wonky").
 * [xbox] = the button in that position named the Xbox way (A = bottom = ✕); [spoken] = its name read aloud.
 */
enum class FaceMark(val xbox: ThorButton, val spoken: String) {
    CROSS(ThorButton.A, "Cross"), CIRCLE(ThorButton.B, "Circle"), SQUARE(ThorButton.X, "Square"), TRIANGLE(ThorButton.Y, "Triangle");

    companion object {
        fun of(xboxLetter: ThorButton): FaceMark? = values().firstOrNull { it.xbox == xboxLetter }
        /** The mark a plain-text symbol stands for ([Vocab.psSymbol]): text drawn later (overlays). */
        fun ofSymbol(c: Char): FaceMark? = values().firstOrNull { Vocab.psSymbol(it.xbox)?.singleOrNull() == c }
    }
}

/** A mark's stroke, × the box side: round caps and joins. Build 18 (A18): per mark ([markStroke]). */
private const val MARK_STROKE = 0.11f

/** Build 18 (A18, V19): the square's stroke, × the box side — its four full-length sides read heavier than the triangle's
 *  three at the same stroke ("wonky" at 28 dp). */
private const val SQUARE_STROKE = 0.10f

/** Build 18 (A18): mark [m]'s stroke, × the box side — the pages ([drawFaceMark]) and the overlays ([ButtonGlyphDrawable])
 *  draw it the same, so they match. */
internal fun markStroke(m: FaceMark): Float = if (m == FaceMark.SQUARE) SQUARE_STROKE else MARK_STROKE

/** Build 18 (A18): the square's half side, × the box side (was 0.24: it read bigger and heavier than the triangle). */
internal const val SQUARE_HALF = 0.22f

/**
 * [m] centred in a box of side [s], as the centre line of a [markStroke] × [s] stroke. Sized so the four carry the
 * same weight; the square's sides are equal by construction.
 */
fun faceMarkPath(m: FaceMark, s: Float): Path = Path().apply {
    val c = s / 2f
    when (m) {
        FaceMark.CROSS -> { val d = 0.25f * s; moveTo(c - d, c - d); lineTo(c + d, c + d); moveTo(c + d, c - d); lineTo(c - d, c + d) }
        FaceMark.CIRCLE -> addOval(Rect(Offset(c, c), 0.27f * s))
        FaceMark.SQUARE -> { val h = SQUARE_HALF * s; addRoundRect(RoundRect(c - h, c - h, c + h, c + h, CornerRadius(0.04f * s))) }
        FaceMark.TRIANGLE -> {
            // equilateral, side 0.58 s, apex up — centred optically: halfway between its box's centre (h/2 below the
            // apex) and its centroid (2h/3), so the apex sits 7h/12 above the centre (tune once on the device, WP3)
            val half = 0.29f * s
            val h = half * kotlin.math.sqrt(3f)
            moveTo(c, c - h * 7f / 12f); lineTo(c + half, c + h * 5f / 12f); lineTo(c - half, c + h * 5f / 12f); close()
        }
    }
}

/** One mark of side [s] centred on [center] — badges and pad drawings share it, so they look the same. */
fun DrawScope.drawFaceMark(m: FaceMark, center: Offset, s: Float, color: Color) {
    translate(center.x - s / 2f, center.y - s / 2f) {
        drawPath(faceMarkPath(m, s), color, style = Stroke(markStroke(m) * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Build 18 (A8): a stick flick's arrow — a filled arrowhead (notched, the marks' style) just inside the rim of a stick badge
 * of side [s], pointing the way flick [b] goes (RS_UP / LS_UP up, …_RIGHT right…). Drawn pointing up, then turned.
 */
fun flickArrowPath(b: ThorButton, s: Float): Path {
    val c = s / 2f
    // (x, y) from the centre, × the side, y down: the tip on the rim, two wings, the notch between them
    val up = listOf(0f to -0.47f, 0.2f to -0.24f, 0f to -0.31f, -0.2f to -0.24f)
    val turned = up.map { (x, y) ->
        val (tx, ty) = when {
            b.name.endsWith("_RIGHT") -> -y to x
            b.name.endsWith("_DOWN") -> -x to -y
            b.name.endsWith("_LEFT") -> y to -x
            else -> x to y
        }
        Offset(c + tx * s, c + ty * s)
    }
    return Path().apply {
        moveTo(turned[0].x, turned[0].y)
        for (i in 1 until turned.size) lineTo(turned[i].x, turned[i].y)
        close()
    }
}

/** dev build 16 (WP5): [text] laid out for a canvas on one line (ellipsized past [maxWidth] px), PlayStation's marks
 *  (✕ ○ □ △, put in by [Vocab]) left as placeholders for [drawMarked] — typed, they came from mismatched fallback fonts. */
fun TextMeasurer.measureMarked(text: String, style: TextStyle, maxWidth: Int = Int.MAX_VALUE): TextLayoutResult {
    val ph = text.indices.filter { FaceMark.ofSymbol(text[it]) != null }
        .map { AnnotatedString.Range(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter), it, it + 1) }
    return measure(AnnotatedString(text), style, overflow = TextOverflow.Ellipsis, maxLines = 1, placeholders = ph,
        constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(0)))
}

/** [l] ([measureMarked]) drawn at [topLeft], its marks as shapes in [color]. */
fun DrawScope.drawMarked(l: TextLayoutResult, topLeft: Offset, color: Color) {
    drawText(l, topLeft = topLeft)
    l.placeholderRects.forEachIndexed { i, r ->
        val m = FaceMark.ofSymbol(l.layoutInput.text.text[l.layoutInput.placeholders[i].start]) ?: return@forEachIndexed
        if (r != null) drawFaceMark(m, topLeft + r.center, r.height * .9f, color)
    }
}

/** dev build 16 (WP5, critique G2): the colour of face letter [xboxLetter] (named the Xbox way) on a drawing — the badges'
 *  colours (an Xbox pad's A B X Y), darker on the light theme. (Build 18: a drawing keeps this; the badges read by
 *  contrast, [faceInk].) */
fun faceLetterColor(xboxLetter: ThorButton, dark: Boolean): Color? =
    FACE[xboxLetter]?.let { if (dark) it else androidx.compose.ui.graphics.lerp(it, Color.Black, 0.38f) }

/** Build 18 (A9): how far a badge's letter or mark may move from its colour — toward white over a dark card (the first
 *  is the colour itself, as before), toward black over a light one (the first is the light theme's 0.38 of before). */
private val INK_LIGHTER = floatArrayOf(0f, 0.35f, 0.6f, 0.8f)
private val INK_DARKER = floatArrayOf(0.38f, 0.55f, 0.7f, 0.85f)

/** Build 18 (A9): what a badge sits on — the card ([GlassColors.panel] over the backdrop's base), opaque. */
internal val GlassColors.glyphUnder: Color get() = panel.compositeOver(base)

/** The overlays' card when the caller gives none ([ButtonGlyphDrawable]): the built-in themes' panel over their base. */
private fun defaultUnder(dark: Boolean): Color =
    if (dark) Color(0x1FFFFFFF).compositeOver(Color(0xFF06070F)) else Color(0x73FFFFFF).compositeOver(Color(0xFFF3F4FA))

private fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * The colour of a mark or letter on its badge of colour [c] — build 18 (A9, MORE-16 / MORE-D33): chosen by contrast, not a
 * fixed mix. The badge's disc is [c] at 26 % over [under] (the card); the first of [c] moved toward white (a dark card) or
 * black (a light one) that reads on that disc at 4.5 : 1, else the one that reads best — the blue X on its blue disc in
 * Dark, any letter on Your own colours' cyan cards. (The light theme's yellow Y was the critique of 2026-10-03.)
 */
internal fun faceInk(c: Color, under: Color): Color {
    val disc = c.copy(alpha = 0.26f).compositeOver(under)
    val dark = TokenArgb.luminance(under.argbLong()) < 0.5
    val toward = if (dark) Color.White else Color.Black
    val cands = (if (dark) INK_LIGHTER else INK_DARKER).map { t -> if (t == 0f) c else androidx.compose.ui.graphics.lerp(c, toward, t) }
    return cands[TokenArgb.readableIndex(cands.map { it.argbLong() }, disc.argbLong(), 4.5)]
}

/** What a glyph of [b] is called aloud on [look]'s controller: PlayStation's marks by name ("Cross"), any other as
 *  [Vocab.spoken] (dev build 15, WP2: the look's names — the Thor's by default). */
fun glyphName(b: ThorButton, look: PadLook = ButtonNames.thorLook): String =
    (if (look.names == FaceStyle.PLAYSTATION && !look.sideways && b in FACE) FaceMark.of(Vocab.faceSwap(b))?.spoken else null) ?: Vocab.spoken(b, look)

/** PlayStation's badge for [mark]: its colour's disc and rim, the mark drawn on it ([ButtonGlyph], [GlyphText]; build 18,
 *  A18: [KeyHint] too — one ○ on a screen, not a white ring on blue beside a red one). */
@Composable
internal fun MarkBadge(mark: FaceMark, size: Dp, dim: Boolean = false) {
    val g = LocalGlass.current
    val c = PS_FACE.getValue(mark.xbox)
    val under = g.glyphUnder
    val ink = remember(c, under) { faceInk(c, under) }
    Box(
        Modifier.size(size).background(c.copy(alpha = if (dim) 0.18f else 0.26f), CircleShape).border(1.5.dp, c, CircleShape)
            .semantics { contentDescription = mark.spoken },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.matchParentSize()) { drawFaceMark(mark, center, this.size.minDimension, ink) } }
}

/** A glyph's letters: the line box trimmed to them and centred, bold. Inside a line of text (a legend's inline badge) a
 *  plain Text took that line's height and sat low in its circle (seen on the Thor, dev build 16: the legend's A / Y). */
private fun letterStyle(fontSize: TextUnit) = TextStyle(
    fontSize = fontSize, fontWeight = FontWeight.Bold, lineHeight = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** One physical button (a POSITION: [ThorButton] A right, B bottom…), drawn the way it looks on a controller — named in
 *  [look]'s vocabulary (dev build 15, WP2: the controller of the page or the one in hand; the Thor's by default). */
@Composable
fun ButtonGlyph(b: ThorButton, size: Dp = 30.dp, dim: Boolean = false, look: PadLook = currentLook()) {
    val g = LocalGlass.current
    val ink = if (dim) g.textSecondary else g.textPrimary
    val chip = if (g.dark) Color(0x33FFFFFF) else Color(0x1A000000)
    val rim = if (g.dark) Color(0x66FFFFFF) else Color(0x40000000)
    when {
        // a single Joy-Con held sideways: its four buttons have no letters that mean anything — the position, lit
        b in FACE && look.sideways -> FacePosGlyph(b, size, Vocab.name(b, look), ink, chip, rim, g.accent)
        b in FACE -> {
            val shown = Vocab.shown(b, look)     // 1.3 (GitHub #27): named the Xbox way if chosen
            // 1.4 (GitHub #50): PlayStation — its symbols in its colours; dev build 14: drawn, not typed
            val mark = if (look.names == FaceStyle.PLAYSTATION) FaceMark.of(shown) else null
            if (mark != null) MarkBadge(mark, size, dim)
            else {
                val c = FACE.getValue(shown)
                val under = g.glyphUnder
                val letterInk = remember(c, under) { faceInk(c, under) }
                Box(
                    Modifier.size(size).background(c.copy(alpha = if (dim) 0.18f else 0.26f), CircleShape).border(1.5.dp, c, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(shown.printed, color = letterInk, style = letterStyle((size.value * 0.55f).sp)) }
            }
        }
        b == ThorButton.L1 || b == ThorButton.R1 || b == ThorButton.L2 || b == ThorButton.R2 -> {
            val shape = RoundedCornerShape(topStart = size * 0.45f, topEnd = size * 0.45f, bottomStart = 6.dp, bottomEnd = 6.dp)
            // build 18 (A17): the word at 0.55 of the badge, never under 11 sp (0.42: Game controls' legend L1 / R1 at 7–9 px)
            Box(
                Modifier.height(size).widthIn(min = size * 1.5f).background(chip, shape).border(1.dp, rim, shape).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(Vocab.name(b, look), color = ink, maxLines = 1, style = letterStyle(shoulderTextSp(size.value).sp)) }   // LB, ZL, SL…
        }
        b == ThorButton.L3 || b == ThorButton.R3 -> {
            // A stick seen from above, PRESSED: outer ring + the cap filled (ink at 25 %) + its name on it — "L3" / "R3", an
            // Xbox pad's "LS" / "RS". Build 18 (A8): a click and a flick read apart (the flick has an arrow, not a cap)
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) {
                    val s = this.size.minDimension
                    drawCircle(chip, radius = s / 2f)
                    drawCircle(rim, radius = s / 2f - 1f, style = Stroke(1.5f))
                    drawCircle(ink.copy(alpha = 0.25f), radius = s * 0.30f)
                    drawCircle(rim, radius = s * 0.30f, style = Stroke(2f))
                }
                Text(Vocab.name(b, look), color = ink, maxLines = 1, style = letterStyle((size.value * 0.30f).sp))
            }
        }
        b == ThorButton.AYN -> AynGlyph(false, size, look)          // build 18 (A15): "Mute" on a DualSense
        b.isDpad -> DpadGlyph(b, size, ink, chip, rim, g.accent)
        b.isFlick -> {
            // The stick seen from above (like L3/R3, but no cap) — build 18 (A8): an accent arrow on its rim points the way
            // the flick goes, its stick's letter ("R") in the middle (the old "R ↑" read like the stick click's "R")
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) {
                    val s = this.size.minDimension
                    drawCircle(chip, radius = s / 2f)
                    drawCircle(rim, radius = s / 2f - 1f, style = Stroke(1.5f))
                    drawPath(flickArrowPath(b, s), g.accent)
                }
                Text(Vocab.name(b, look).take(1), color = ink, style = letterStyle((size.value * 0.36f).sp))
            }
        }
        else -> SystemGlyph(b, size, look, ink, chip, rim)
    }
}

/**
 * Round system buttons, printed the way the Thor prints them (Home = house, Back = counter-clockwise looping arrow, Start =
 * right-pointing triangle, Select = square) — build 18 (A5): and each controller's own ([systemGlyphKind]): a DualSense's
 * "PS", Create, Options' ≡ and touchpad; an Xbox pad's "Xbox", View, Menu; a Nintendo pad's − and +. Its description is
 * always its name on that controller ("PS", "Options", "Create"…).
 */
@Composable
private fun SystemGlyph(b: ThorButton, size: Dp, look: PadLook, ink: Color, chip: Color, rim: Color) {
    val name = Vocab.name(b, look)
    val s = systemGlyphKind(b, look)
    // b18r3 (r2 BUG-1): a word its slot can't hold at the 11 sp floor (the keyboard's globe key: "Creat") → its short form
    if (s is SysGlyph.Capsule) FitOrShort(size * sysGlyphWidth(s, size.value), { SysBadge(s, size, name, ink, chip, rim) }) {
        SysBadge(sysGlyphShort(b, look), size, name, ink, chip, rim)
    }
    else SysBadge(s, size, name, ink, chip, rim)
}

/** One system badge as [s] says ([systemGlyphKind], or its short form [sysGlyphShort]); [name] its description. */
@Composable
private fun SysBadge(s: SysGlyph, size: Dp, name: String, ink: Color, chip: Color, rim: Color) {
    when (s) {
        is SysGlyph.Capsule -> {
            // the shoulders' style: a word in a capsule (the round chip held only an icon). Device pass (P2-8): the word never
            // under the shoulders' 11 sp ("PS" drew ~6 px tall inline), the capsule as wide as that word needs
            val shape = RoundedCornerShape(size / 2)
            Box(
                Modifier.height(size).widthIn(min = size * sysGlyphWidth(s, size.value)).background(chip, shape).border(1.dp, rim, shape)
                    .padding(horizontal = size * 0.2f).semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) { Text(s.text, color = ink, maxLines = 1, softWrap = false, style = letterStyle(capsuleTextSp(s, size.value).sp)) }
        }
        is SysGlyph.Letter -> Box(
            Modifier.size(size).background(chip, CircleShape).border(1.dp, rim, CircleShape).semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) { Text(s.text, color = ink, style = letterStyle((size.value * 0.6f).sp)) }
        SysGlyph.Touchpad -> Box(
            Modifier.size(size).background(chip, CircleShape).border(1.dp, rim, CircleShape).semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            // a DualSense's touchpad from above: a wide rounded rectangle
            Canvas(Modifier.size(size * 0.62f)) {
                val w = this.size.width; val h = this.size.height * 0.62f
                val st = maxOf(1.2f, this.size.width * 0.1f)
                drawRoundRect(ink, Offset(st / 2f, (this.size.height - h) / 2f + st / 2f), Size(w - st, h - st), CornerRadius(h * 0.28f), style = Stroke(st))
            }
        }
        is SysGlyph.Icon -> SysIconBadge(s.icon, size, name, ink, chip, rim)
    }
}

/** A system button's round chip with [icon] ([name]: its description). b18r3: the short forms' marks too — a DualSense's
 *  printed Create mark (three short rays) and a left Joy-Con's printed Capture mark (a dot in a rounded square) are drawn;
 *  Share and Mute (its crossed microphone) are icons. */
@Composable
private fun SysIconBadge(icon: SysIcon, size: Dp, name: String, ink: Color, chip: Color, rim: Color) {
    Box(
        Modifier.size(size).background(chip, CircleShape).border(1.dp, rim, CircleShape).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            SysIcon.CREATE -> Canvas(Modifier.size(size * 0.62f)) {
                // three rays fanning up and left from one point, as printed beside a DualSense's touchpad
                val s = this.size.minDimension
                val st = maxOf(1.2f, s * 0.13f)
                val c = Offset(s * 0.8f, s * 0.8f)
                for (deg in listOf(180f, 225f, 270f)) {
                    val a = Math.toRadians(deg.toDouble())
                    val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                    drawLine(ink, Offset(c.x + dx * s * 0.2f, c.y + dy * s * 0.2f), Offset(c.x + dx * s * 0.62f, c.y + dy * s * 0.62f),
                        strokeWidth = st, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            SysIcon.CAPTURE -> Canvas(Modifier.size(size * 0.6f)) {
                val s = this.size.minDimension
                val st = maxOf(1.2f, s * 0.11f)
                drawRoundRect(ink, Offset(st / 2f, st / 2f), Size(s - st, s - st), CornerRadius(s * 0.22f), style = Stroke(st))
                drawCircle(ink, radius = s * 0.2f)
            }
            else -> Icon(when (icon) {
                SysIcon.HOUSE -> Icons.Rounded.Home
                SysIcon.REPLAY -> Icons.Rounded.Replay
                SysIcon.PLAY -> Icons.Rounded.PlayArrow
                SysIcon.SQUARE -> Icons.Rounded.CropSquare
                SysIcon.MENU -> Icons.Rounded.Menu
                SysIcon.VIEW -> Icons.Rounded.FilterNone
                SysIcon.SHARE -> Icons.Rounded.IosShare
                SysIcon.MIC_OFF -> Icons.Rounded.MicOff
                SysIcon.CREATE, SysIcon.CAPTURE -> Icons.Rounded.CropSquare            // drawn above
            }, null, tint = ink, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** dev build 15 (WP2): a face position as four dots, [b]'s lit — a single Joy-Con held sideways ([name] in words). */
@Composable
private fun FacePosGlyph(b: ThorButton, size: Dp, name: String, ink: Color, chip: Color, rim: Color, lit: Color) {
    Canvas(Modifier.size(size).semantics { contentDescription = name }) {
        val s = this.size.minDimension
        drawCircle(chip, radius = s / 2f)
        drawCircle(rim, radius = s / 2f - 1f, style = Stroke(1.2f))
        val d = s * 0.27f; val r = s * 0.11f
        val c = Offset(s / 2f, s / 2f)
        for ((p, o) in listOf(ThorButton.A to Offset(d, 0f), ThorButton.B to Offset(0f, d), ThorButton.X to Offset(0f, -d), ThorButton.Y to Offset(-d, 0f))) {
            if (p == b) drawCircle(lit, radius = r * 1.25f, center = c + o)
            else drawCircle(ink.copy(alpha = 0.45f), radius = r, center = c + o, style = Stroke(1.5f))
        }
    }
}

@Composable
private fun DpadGlyph(b: ThorButton, size: Dp, ink: Color, chip: Color, rim: Color, lit: Color) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val arm = s / 3f
        val r = CornerRadius(s * 0.07f)
        // Cross = horizontal + vertical bars.
        drawRoundRect(chip, Offset(0f, arm), Size(s, arm), r)
        drawRoundRect(chip, Offset(arm, 0f), Size(arm, s), r)
        drawRoundRect(rim, Offset(0f, arm), Size(s, arm), r, style = Stroke(1.2f))
        drawRoundRect(rim, Offset(arm, 0f), Size(arm, s), r, style = Stroke(1.2f))
        // Lit arm for the pressed direction.
        val litTopLeft = when (b) {
            ThorButton.UP -> Offset(arm, 0f); ThorButton.DOWN -> Offset(arm, 2 * arm)
            ThorButton.LEFT -> Offset(0f, arm); else -> Offset(2 * arm, arm)
        }
        drawRoundRect(lit, litTopLeft, Size(arm, arm), r)
        drawCircle(ink.copy(alpha = 0.25f), radius = arm * 0.18f, center = Offset(s / 2, s / 2))
    }
}

/** Build 18 (A9): an inline badge's slot — its side [box] (text units) × [wide], plus 0.12 em a side ([em] = the text's
 *  size), so a badge never touches the letter beside it (the tour's "(✕" touched). The badge is centred in it ([Centred]). */
private fun inlineSlot(box: TextUnit, wide: Float, em: TextUnit) =
    Placeholder((box.value * wide + 2 * INLINE_GAP_EM * em.value).sp, box, PlaceholderVerticalAlign.TextCenter)

/** Build 18 (A9): the room each side of an inline badge, × the text's size. */
private const val INLINE_GAP_EM = 0.12f

/** An inline badge centred in its slot (the slot measures its content at the slot's size: a round badge would stretch). */
@Composable
private fun Centred(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

/** The text size badges are measured against: [style]'s, else bodyMedium's (an inherited size). */
@Composable
private fun emOf(style: TextStyle): TextUnit = style.fontSize.takeIf { it.isSp } ?: MaterialTheme.typography.bodyMedium.fontSize

/**
 * dev build 14: buttons inside a line of text. A hint or legend puts them in with [appendButton] and passes
 * this map as Text's `inlineContent`. Each badge sits in a 1.3 em box centred on the line ([style] = that Text's
 * style, for the em); shoulders and AYN are as much wider as their badges are. Build 18: [badge] = the badges' side
 * instead of 1.3 em (A14's legend: 18 dp); 0.12 em of room each side (A9).
 */
@Composable
fun rememberButtonInline(style: TextStyle, dim: Boolean = false, look: PadLook = currentLook(),
                         badge: Dp = Dp.Unspecified): Map<String, InlineTextContent> {
    val em = emOf(style)
    val density = LocalDensity.current
    val size = if (badge.isSpecified) badge else with(density) { (em * 1.3f).toDp() }
    val box = with(density) { size.toSp() }
    return remember(size, box, em, dim, look) {
        ThorButton.values().associate { b ->
            // build 18 (A5): a system button drawn as a word in a capsule ("PS", "Create", "Xbox") is as wide as its badge;
            // (A17) a shoulder 1.5 (wider on a small badge: its 11 sp word); (A15) the AYN chip as wide as its word ("Mute")
            val wide = when (b) {
                ThorButton.L1, ThorButton.R1, ThorButton.L2, ThorButton.R2 -> shoulderGlyphWidth(size.value)
                ThorButton.AYN -> aynGlyphWidth(aynGlyphText(look), size.value)
                // device pass (P2-8): at this badge's size — a capsule's word keeps the 11 sp floor, its slot that width
                ThorButton.HOME, ThorButton.BACK, ThorButton.SELECT, ThorButton.START -> sysGlyphWidth(systemGlyphKind(b, look), size.value)
                else -> 1f
            }
            "btn:${b.name}" to InlineTextContent(inlineSlot(box, wide, em)) { Centred { ButtonGlyph(b, size, dim, look) } }
        }
    }
}

/**
 * dev build 14: [text] with PlayStation's ✕ ○ □ △ (put in by [app.wayfinder.ButtonNames.of]) drawn as
 * badges in the line — typed, they come from mismatched fallback fonts ("wonky"). Plain Text when it has none.
 * The shared titles and descriptions (SubPage, SettingCard, list rows) use it, so every page's words get the marks.
 * dev build 15 (WP2): no look needed — the marks are in the text whoever's names made it ([Vocab.name] per controller).
 * Build 18 (A7): and the buttons a text holds as tokens ([app.wayfinder.Say.key] / [app.wayfinder.Say.keyMenu]) drawn as
 * their badges ([ButtonGlyph]) in the names of the page or window showing it ([currentLook]) — "Hold (B) to leave"; an
 * unknown token is its name in words. [badge] = the badges' side (default 1.3 em).
 */
@Composable
fun GlyphText(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip, badge: Dp = Dp.Unspecified,
) {
    val keys = app.wayfinder.Say.KEY_OPEN in text
    if (!keys && text.none { FaceMark.ofSymbol(it) != null }) { Text(text, modifier, color, style = style, maxLines = maxLines, overflow = overflow); return }
    val look = currentLook()
    val em = emOf(style)
    val density = LocalDensity.current
    val size = if (badge.isSpecified) badge else with(density) { (em * 1.3f).toDp() }
    val box = with(density) { size.toSp() }
    val marks = remember(size, box, em) {
        FaceMark.values().associate { m ->
            "mark:${m.name}" to InlineTextContent(inlineSlot(box, 1f, em)) { Centred { MarkBadge(m, size, false) } }
        }
    }
    val buttons = if (keys) rememberButtonInline(style, look = look, badge = size) else emptyMap()
    val s = remember(text, look) {
        buildAnnotatedString {
            for ((part, b) in app.wayfinder.Say.keyParts(text)) {
                if (b != null) appendButton(b, look)
                else for (ch in part) FaceMark.ofSymbol(ch)?.let { appendInlineContent("mark:${it.name}", it.spoken) } ?: append(ch)
            }
        }
    }
    Text(s, modifier, color, style = style, maxLines = maxLines, overflow = overflow, inlineContent = if (keys) marks + buttons else marks)
}

/** [b] as a badge in an AnnotatedString, drawn by [rememberButtonInline]; [glyphName] is the text it stands for (pass
 *  the same [look] as the inline map's). */
fun AnnotatedString.Builder.appendButton(b: ThorButton, look: PadLook = ButtonNames.thorLook) = appendInlineContent("btn:${b.name}", glyphName(b, look))

/**
 * dev build 14: [ButtonGlyph]'s PlayStation badge for the View-based overlays (FocusCue's ImageSpans): the
 * same disc, rim and [faceMarkPath] in the same colours, so overlays and pages match. Square: size it with setBounds.
 * Build 18 (A9): its mark's colour chosen by contrast as the pages' ([faceInk]) — [under] = the overlay's card (ARGB;
 * null: the built-in theme's, from [dark]).
 */
class ButtonGlyphDrawable(private val mark: FaceMark, dark: Boolean, under: Int? = null) : android.graphics.drawable.Drawable() {
    private val c = PS_FACE.getValue(mark.xbox)
    private val ink = faceInk(c, under?.let { Color(it) } ?: defaultUnder(dark)).toArgb()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private var path = android.graphics.Path()
    private var a = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        path = faceMarkPath(mark, minOf(bounds.width(), bounds.height()).toFloat()).asAndroidPath()
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        val rim = 0.05f * s   // ButtonGlyph's 1.5 dp at its usual 30 dp
        canvas.save()
        canvas.translate(bounds.exactCenterX() - s / 2f, bounds.exactCenterY() - s / 2f)
        paint.style = android.graphics.Paint.Style.FILL; paint.color = c.copy(alpha = 0.26f * a / 255f).toArgb()
        canvas.drawCircle(s / 2f, s / 2f, s / 2f, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = rim; paint.color = c.copy(alpha = a / 255f).toArgb()
        canvas.drawCircle(s / 2f, s / 2f, s / 2f - rim / 2f, paint)
        paint.strokeWidth = markStroke(mark) * s; paint.color = ink; paint.alpha = a
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { a = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Build 18 (E6 addendum, MORE-D32): [ButtonGlyph]'s lettered face button for the View overlays (the held list's A / B / X / Y
 * were bare letters beside PlayStation's drawn marks): the same disc, rim and letter colour as the pages' ([faceInk]).
 * [letter] = the letter shown (its colour: an Xbox pad's, as the pages'); [under] as [ButtonGlyphDrawable]'s. Square: size it
 * with setBounds.
 */
class ButtonLetterDrawable(private val letter: ThorButton, dark: Boolean, under: Int? = null) : android.graphics.drawable.Drawable() {
    private val c = FACE.getValue(letter)
    private val ink = faceInk(c, under?.let { Color(it) } ?: defaultUnder(dark)).toArgb()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
    }
    private var a = 255

    override fun draw(canvas: android.graphics.Canvas) {
        val s = minOf(bounds.width(), bounds.height()).toFloat()
        val rim = 0.05f * s   // ButtonGlyph's 1.5 dp at its usual 30 dp
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        paint.style = android.graphics.Paint.Style.FILL; paint.color = c.copy(alpha = 0.26f * a / 255f).toArgb()
        canvas.drawCircle(cx, cy, s / 2f, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = rim; paint.color = c.copy(alpha = a / 255f).toArgb()
        canvas.drawCircle(cx, cy, s / 2f - rim / 2f, paint)
        text.color = ink; text.alpha = a; text.textSize = 0.55f * s   // ButtonGlyph's letter: 0.55 of the badge
        val fm = text.fontMetrics
        canvas.drawText(letter.printed, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    override fun setAlpha(alpha: Int) { a = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; text.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** The face button a lone letter in a text names ("A", "B", "X", "Y"), else null. */
        fun letterOf(s: CharSequence): ThorButton? = FACE.keys.firstOrNull { it.printed == s.toString() }
    }
}

/** An icon for each action (cheat sheet / Controls). */
fun actionIcon(a: ThorAction): ImageVector = when (a) {
    ThorAction.SWAP_OR_SEND -> Icons.Rounded.Bolt
    ThorAction.CLEAR_BACKGROUND -> Icons.Rounded.DeleteSweep
    // build 18 (A22): never a × — a blue × beside PlayStation's marks read as ✕, the confirm button
    ThorAction.CLOSE_APP -> Icons.Rounded.CancelPresentation
    ThorAction.RECENTS -> Icons.Rounded.GridView
    ThorAction.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    ThorAction.SCREENSHOT -> Icons.Rounded.PhotoCamera
    ThorAction.TOGGLE_SECOND_SCREEN -> Icons.Rounded.DarkMode
    ThorAction.TOGGLE_TOP_SCREEN -> Icons.Rounded.DarkMode
    ThorAction.WAYFINDER_OFF -> Icons.Rounded.PowerSettingsNew
    ThorAction.NOTHING -> Icons.Rounded.Block
    ThorAction.TOGGLE_KEEP_AWAKE -> Icons.Rounded.Coffee
    ThorAction.SLEEP -> Icons.Rounded.Bedtime
    ThorAction.AYN_MOUSE -> Icons.Rounded.Mouse
    ThorAction.GYRO_TOGGLE -> androidx.compose.material.icons.Icons.Rounded.ScreenRotation
    ThorAction.GUIDE -> Icons.Rounded.MenuBook
    ThorAction.FOCUS_SWITCH_UP -> Icons.Rounded.VerticalAlignTop
    ThorAction.FOCUS_SWITCH_DOWN -> Icons.Rounded.VerticalAlignBottom
    ThorAction.FOCUS_LOCK_TOGGLE -> Icons.Rounded.Lock
    ThorAction.KEYBOARD -> Icons.Rounded.Keyboard
    ThorAction.QUICK_MENU -> Icons.Rounded.Menu
    ThorAction.BRIGHTER -> Icons.Rounded.LightMode
    ThorAction.DIMMER -> Icons.Rounded.WbTwilight
    ThorAction.TOP_BRIGHTER, ThorAction.BOTTOM_BRIGHTER -> Icons.Rounded.BrightnessHigh
    ThorAction.TOP_DIMMER, ThorAction.BOTTOM_DIMMER -> Icons.Rounded.BrightnessLow
    ThorAction.LOUDER, ThorAction.TOP_LOUDER, ThorAction.BOTTOM_LOUDER -> Icons.Rounded.VolumeUp
    ThorAction.QUIETER, ThorAction.TOP_QUIETER, ThorAction.BOTTOM_QUIETER -> Icons.Rounded.VolumeDown
    ThorAction.FPS_COUNTER -> Icons.Rounded.Speed
    ThorAction.GAME_CONTROLS -> Icons.Rounded.Tune
    ThorAction.OPEN -> Icons.Rounded.Apps
    ThorAction.HOME_TOP -> Icons.Rounded.VerticalAlignTop
    ThorAction.HOME_BOTTOM -> Icons.Rounded.VerticalAlignBottom
    ThorAction.HOME_BOTH -> Icons.Rounded.Home
    ThorAction.RECORD_SCREEN -> Icons.Rounded.FiberManualRecord
    ThorAction.CLOSE_OTHER -> Icons.Rounded.ClearAll
    ThorAction.AYN_DRAWER -> Icons.Rounded.Dashboard
    ThorAction.HOME_HERE -> Icons.Rounded.Home
    ThorAction.SWIPE_UP -> Icons.Rounded.SwipeUp
    ThorAction.SWIPE_DOWN -> Icons.Rounded.SwipeDown
    ThorAction.SWIPE_LEFT -> Icons.Rounded.SwipeLeft
    ThorAction.SWIPE_RIGHT -> Icons.Rounded.SwipeRight
}

/**
 * Build 18 (A0): the Thor's two screens, stacked — [top] or the bottom one filled in the accent, the other outlined. Says
 * "which screen" where "▲ Top screen" read as PlayStation's △ (SourceRulesTest bans ▲ / ▼ as a screen mark). Put the words
 * beside it ("Top screen"): it has no description of its own, and it is never a button.
 */
@Composable
fun ScreenIcon(top: Boolean, size: Dp = 18.dp) {
    val g = LocalGlass.current
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = maxOf(1f, s * 0.08f)
        // the top screen wide, the bottom one narrower under it (the Thor's 6" and 3.9")
        val tw = s * 0.92f; val th = s * 0.44f
        val bw = s * 0.64f; val bh = s * 0.36f
        val gap = s * 0.08f
        val y0 = (s - th - gap - bh) / 2f
        val r = CornerRadius(s * 0.09f)
        fun screen(x: Float, y: Float, w: Float, h: Float, chosen: Boolean) {
            if (chosen) drawRoundRect(g.accent, Offset(x, y), Size(w, h), r)
            else drawRoundRect(g.textTertiary, Offset(x + stroke / 2f, y + stroke / 2f), Size(w - stroke, h - stroke), r, style = Stroke(stroke))
        }
        screen((s - tw) / 2f, y0, tw, th, top)
        screen((s - bw) / 2f, y0 + th + gap, bw, bh, !top)
    }
}

/** Three fingers — the blank gesture's badge. Build 18 (A22, ps-F4): three short rounded bars (the middle one longer, as
 *  fingers are), a small arc under them for the tap, a down chevron for the swipe — a row of three dots read as a "more"
 *  menu. */
@Composable
fun ThreeFingerGlyph(size: Dp = 30.dp, swipe: Boolean = false) {
    val g = LocalGlass.current
    val ink = g.textPrimary.copy(alpha = 0.85f)
    val mark = g.accent
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.12f
        // the fingers: x, top, bottom (× the side) — round caps
        for ((x, top) in listOf(0.30f to 0.20f, 0.50f to 0.12f, 0.70f to 0.20f))
            drawLine(ink, Offset(s * x, s * top), Offset(s * x, s * 0.50f), strokeWidth = w, cap = StrokeCap.Round)
        val st = Stroke(s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (swipe) drawPath(Path().apply {          // ⌄ under them: the swipe down
            moveTo(s * 0.36f, s * 0.68f); lineTo(s * 0.50f, s * 0.82f); lineTo(s * 0.64f, s * 0.68f)
        }, mark, style = st)
        else drawArc(mark, startAngle = 25f, sweepAngle = 130f, useCenter = false,       // ‿ under them: the tap
            topLeft = Offset(s * 0.24f, s * 0.50f), size = Size(s * 0.52f, s * 0.28f), style = st)
    }
}
