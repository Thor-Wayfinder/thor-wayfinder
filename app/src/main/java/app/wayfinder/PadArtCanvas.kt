package app.wayfinder

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wayfinder.ui.FaceMark
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.currentLook
import app.wayfinder.ui.drawFaceMark
import app.wayfinder.ui.drawMarked
import app.wayfinder.ui.faceLetterColor
import app.wayfinder.ui.measureMarked
import kotlin.math.hypot

/*
 * dev build 16 (WP5): any controller's drawing ([PadArt]) — Game controls' map since 1.3 (RemapScreen's ControllerMap,
 * the same layers in the same order), now for every controller, and the setup wizard's picture. Colours from the theme,
 * names and PlayStation's marks from the controller of the tab ([PadLook]). The canvas never decides what a controller
 * does: it reads a [PadRemap] and calls the page back.
 */

/** dev build 16 (WP5): a motion pill's state and words (a controller's motion aim: the page's words). */
internal class GyroPill(val on: Boolean, val text: String)

/** dev build 16 (WP5): a Game controls tab's drawing, whose names it speaks, and what its mapping in use leaves out —
 *  [faint]: parts it doesn't read (critique m5: "not set up"). (dev build 17, theme G: build 16's "analog" L2 / R2 went —
 *  wfhid presses them from the pull.) */
internal class TabArt(val art: PadArt, val look: PadLook, val faint: Set<ArtId> = emptySet())

/** A tab's drawing: the Thor's (docked too — critique M3: its own drawing, its gyro editable), or connected controller
 *  [padKey]'s: its kind, how it is held, what its Home does, what its saved mapping says it hasn't got. Recomposes when
 *  the controllers change (a Joy-Con turned sideways, a mapping saved). [padGyro] / [parts]: its motion pill and its
 *  own parts (SL / SR / Home), once the page has their editors (WP6). */
@Composable
internal fun rememberTabArt(padKey: String?, padGyro: Boolean = false, parts: Boolean = true): TabArt {
    @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue
    if (padKey == null) { val look = ButtonNames.thorLook; return remember(look) { TabArt(PadArts.THOR, look) } }
    val ctx = LocalContext.current
    val look = ExtPadsCtl.padLook(padKey)
    val pad = ExtPadsCtl.pads.values.firstOrNull { it.key == padKey }
    val mapping = PadArts.targetsOf(pad?.mapping)
    val homeWf = ExtPadsCtl.homeIsThors(ctx, padKey)
    val notOn = PadArts.notOn(ExtPadsCtl.savedMap(ctx, padKey, look.sideways))
    // dev build 17 (theme G): its own Back / AYN buttons stay with Wayfinder — the drawing's note names them
    val kept = pad?.let { p -> ExtPadsCtl.spares(ctx, p).let { (it.back as? Say.Spare.Own)?.name to (it.ayn as? Say.Spare.Own)?.name } }
        ?: (null to null)
    return remember(look, mapping, homeWf, notOn, padGyro, parts, kept) {
        val art = PadArts.of(look, notOn, homeWf, padGyro, parts, kept)
        TabArt(art, look, PadArts.unset(art, mapping))
    }
}

/** dev build 16 (WP5): the drawing's colours from the theme (dark, black, light, "Your own colours") — 1.3's constants
 *  INK / ACC are what these give on the default themes, within a few levels (accent exactly). Build 18 (GC11, gd-D08):
 *  [lightRing] = the Thor's stick-light rings, in the ink — never an accent (they read as selection rings). */
internal class PadArtColors(val ink: Color, val face: Color, val accent: Color, val accentText: Color, val lightRing: Color, val onAccent: Color)

private fun mix(a: Color, b: Color, t: Float) =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

@Composable
internal fun padArtColors(): PadArtColors {
    val g = LocalGlass.current
    return remember(g) {
        val ink = mix(mix(g.textPrimary, g.accent2, .22f), g.base, if (g.dark) .04f else .15f)       // 1.3: 363C58 / E2E5F5
        PadArtColors(
            ink = ink,
            face = if (g.dark) g.textPrimary.copy(alpha = .10f) else g.base.copy(alpha = .70f),       // 1.3: white .10 / .70
            accent = g.accent,
            accentText = mix(g.accent, g.textPrimary, .18f),                                          // 1.3: 0A6BDA / 6CB4FF
            lightRing = ink.copy(alpha = .22f),                                                       // build 18 (GC11, gd-D08)
            onAccent = g.onAccent,                                                                    // as the tags' and PressTag's
        )
    }
}

private val PATHS = HashMap<String, Path>()
/** SVG path data as a path in design units, parsed once (main thread). */
private fun artPath(d: String): Path = PATHS.getOrPut(d) { PathParser().parsePathString(d).toPath() }
/** [PadArt.body] as a path in design units (WP6's tab glyph: [PadArt.frame] is its box). */
internal fun PadArt.bodyPath(): Path = artPath(body)

/** dev build 16 (WP5): [art] drawn — with [remap] its tags, [sel] ringed (glowing while [focused]), the [inspector]'s two
 *  lines; without [remap] a picture cropped to the drawing (the setup wizard): [highlight] lit (a wizard target: S E W N,
 *  L1 … SL SR, DU … DR, LX LY RX RY), [done] faint. [gyro]: a controller's motion pill; [borrowed]: tags dimmed (a
 *  controller using the Thor's controls); [faint]: parts drawn faint (not set up). Build 18 (C2, VD-04): [lit] = targets
 *  lit as pressed now (the Test page's drawing: what is held), drawn like [highlight]. */
@Composable
internal fun PadArtCanvas(
    art: PadArt,
    modifier: Modifier = Modifier,
    remap: PadRemap? = null,
    sel: ArtId? = null,
    focused: Boolean = false,
    inspector: Pair<String, String>? = null,
    highlight: String? = null,
    done: Set<String> = emptySet(),
    gyro: GyroPill? = null,
    borrowed: Boolean = false,
    faint: Set<ArtId> = emptySet(),
    look: PadLook = currentLook(),
    lit: Set<String> = emptySet(),
    /** Build 18 (GC11, gd-D02): this game's face positions in words, by the face buttons ([PadArt.faceNote]); null = none. */
    faceNote: String? = null,
) {
    val co = padArtColors()
    val dark = LocalGlass.current.dark
    val tm = rememberTextMeasurer(cacheSize = 48)    // b18 round 2: a drawing lays out ~20-40 labels/tags per draw; 8 (default) never hit
    Canvas(modifier) {
        val picture = remap == null
        val s = ArtView.scale(size.width, size.height, if (picture) art.frame else ArtView.VIEW)
        val k = s.k
        fun X(u: Float) = s.x(u)
        fun Y(u: Float) = s.y(u)
        fun circle(cx: Float, cy: Float, r: Float, sw: Float, a: Float, fill: Color? = co.face, stroke: Color = co.ink, dashed: Boolean = false) {
            if (fill != null) drawCircle(fill, r * k, Offset(X(cx), Y(cy)), style = Fill)
            drawCircle(stroke.copy(alpha = stroke.alpha * a), r * k, Offset(X(cx), Y(cy)),
                style = Stroke(sw * k, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f * k, 7f * k)) else null))
        }
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, sw: Float, a: Float, fill: Color? = co.face, stroke: Color = co.ink) {
            if (fill != null) drawRoundRect(fill, Offset(X(x), Y(y)), Size(w * k, h * k), CornerRadius(r * k), style = Fill)
            drawRoundRect(stroke.copy(alpha = stroke.alpha * a), Offset(X(x), Y(y)), Size(w * k, h * k), CornerRadius(r * k), style = Stroke(sw * k))
        }
        fun style(sz: Float, color: Color, bold: Boolean) =
            TextStyle(fontSize = (sz * k / density).sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        fun label(str: String, cx: Float, cy: Float, sz: Float, color: Color = co.ink, bold: Boolean = true, maxW: Float = 0f) {
            val l = tm.measureMarked(str, style(sz, color, bold), if (maxW > 0f) (maxW * k).toInt() else Int.MAX_VALUE)
            drawMarked(l, Offset(X(cx) - l.size.width / 2f, Y(cy) - l.size.height / 2f), color)
        }
        fun path(p: Path, fill: Color?, stroke: Color, sw: Float) = withTransform({ translate(s.ox, s.oy); scale(k, k, Offset.Zero) }) {
            if (fill != null) drawPath(p, fill)
            drawPath(p, stroke, style = Stroke(sw))
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, c: Color) =
            drawLine(c, Offset(X(x0), Y(y0)), Offset(X(x1), Y(y1)), strokeWidth = 3.5f * k)
        // a small button's mark (stroke 3.5, as the Thor's Select ▢ / Start ▷); [rd] its button's radius
        fun glyph(gl: ArtGlyph, x: Float, y: Float, rd: Float, c: Color) {
            val q = rd / 24f
            val st = Stroke(3.5f * k)
            when (gl) {
                ArtGlyph.SQUARE -> drawRect(c, Offset(X(x - 8f * q), Y(y - 8f * q)), Size(16f * q * k, 16f * q * k), style = st)
                ArtGlyph.TRIANGLE -> drawPath(Path().apply {
                    moveTo(X(x - 6f * q), Y(y - 9f * q)); lineTo(X(x + 9f * q), Y(y)); lineTo(X(x - 6f * q), Y(y + 9f * q)); close() }, c, style = st)
                ArtGlyph.LINES -> for (dy in listOf(-6f, 0f, 6f)) line(x - 9f * q, y + dy * q, x + 9f * q, y + dy * q, c)
                ArtGlyph.VIEW -> for (o in listOf(-3f, 3f))
                    drawRoundRect(c, Offset(X(x + (o - 6f) * q), Y(y + (o - 6f) * q)), Size(12f * q * k, 12f * q * k), CornerRadius(3f * q * k), style = st)
                // build 18 (GC11, ps-F11): Create = three upright strokes, the middle one longer (a fan of lines read as nothing)
                ArtGlyph.SHARE -> for (dx in listOf(-6f, 0f, 6f)) { val hh = if (dx == 0f) 8f else 5f; line(x + dx * q, y - hh * q, x + dx * q, y + hh * q, c) }
                ArtGlyph.MINUS -> line(x - 9f * q, y, x + 9f * q, y, c)
                ArtGlyph.PLUS -> { line(x - 9f * q, y, x + 9f * q, y, c); line(x, y - 9f * q, x, y + 9f * q, c) }
                // the Thor's Home and Back as 1.3 drew them (36 on its r 28 keys)
                ArtGlyph.HOUSE -> label("⌂", x, y, 36f * rd / 28f, c)
                ArtGlyph.BACK -> label("↺", x, y, 36f * rd / 28f, c)
                ArtGlyph.PS -> label("PS", x, y, rd * .9f, c)
                ArtGlyph.XBOX -> {
                    drawCircle(c, .6f * rd * k, Offset(X(x), Y(y)), style = st)
                    line(x - .3f * rd, y - .3f * rd, x + .3f * rd, y + .3f * rd, c); line(x + .3f * rd, y - .3f * rd, x - .3f * rd, y + .3f * rd, c)
                }
                ArtGlyph.CAPTURE -> {
                    val h = .9f * rd
                    drawRoundRect(c, Offset(X(x - h / 2), Y(y - h / 2)), Size(h * k, h * k), CornerRadius(.2f * h * k), style = st)
                    drawCircle(c, .22f * rd * k, Offset(X(x), Y(y)), style = Stroke(3f * k))
                }
                ArtGlyph.NONE -> {}
            }
        }

        // the setup wizard: a target lit (its part filled), the ones set faint; build 18 (C2): the Test page's held parts too
        fun on(t: String?) = picture && t != null && (t == highlight || t in lit)
        fun litFill(t: String): Color? = if (!picture) null else if (on(t)) co.accent else if (t in done) co.accent.copy(alpha = .28f) else null
        fun hi(c: ArtCtl) = on(c.targets.firstOrNull())
        fun fillOf(c: ArtCtl) = c.targets.singleOrNull()?.let { litFill(it) } ?: co.face
        fun edgeOf(c: ArtCtl) = if (picture && ((highlight != null && highlight in c.targets) || c.targets.any { it in lit })) co.accent else co.ink
        // a changed part: its label in the accent colour (the tag says what it does)
        fun changed(c: ArtCtl): Boolean {
            val r = remap ?: return false
            return when (val src = c.src) {
                ArtSrc.Dpad -> r.dpadStick || PadRemap.DIRS.any { r.changedAt(it) }
                ArtSrc.Gyro -> r.gyro.isOn
                ArtSrc.PadGyro -> gyro?.on == true
                is ArtSrc.Part -> r.pads.containsKey(src.code) || r.padFire.containsKey(src.code)
                is ArtSrc.Btn -> when (src.b) {
                    ThorButton.L3 -> r.jobAt(ThorButton.L3) || r.invertLeftY || r.invertLeftX || r.swapSticks || !r.jobL.isDefault
                    ThorButton.R3 -> r.jobAt(ThorButton.R3) || r.invertRightY || r.invertRightX || r.swapSticks || !r.jobR.isDefault
                    ThorButton.L2, ThorButton.R2 -> r.changedAt(src.b) || r.digitalTriggers
                    else -> r.changedAt(src.b)
                }
            }
        }
        fun labelColor(c: ArtCtl) = if (hi(c)) co.onAccent else if (changed(c)) co.accentText else co.ink
        // a part its controller doesn't read now: faint (a layer at .4)
        fun part(c: ArtCtl, draw: () -> Unit) {
            val dim = !picture && c.id in faint
            if (dim) drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = .4f }) }
            draw()
            if (dim) drawIntoCanvas { it.restore() }
        }
        val faceDim = co.face.copy(alpha = co.face.alpha * .5f)
        fun name(c: ArtCtl) = c.btn?.let { Vocab.name(it, look) } ?: ""

        // bumpers L1 / R1: pills on the top edge
        for (c in art.ctls) if (c.shape == ArtShape.Bumper) part(c) {
            rrect(c.x - 95f, c.y - 23f, 190f, 46f, 23f, 6f, .9f, fillOf(c), edgeOf(c))
            label(name(c), c.x, c.y, 42f, labelColor(c))
        }
        // triggers L2 / R2: behind them, a little dome (their rounded top), flat underside
        for (c in art.ctls) if (c.shape == ArtShape.Trigger) part(c) {
            val x0 = c.x - 95f; val x1 = c.x + 95f; val top = c.y - 44f; val bot = c.y + 23f
            val dome = Path().apply {
                moveTo(X(x0), Y(bot - 8f)); lineTo(X(x0), Y(c.y + 6f))
                cubicTo(X(x0), Y(top + 10f), X(c.x - 70f), Y(top), X(c.x), Y(top))
                cubicTo(X(c.x + 70f), Y(top), X(x1), Y(top + 10f), X(x1), Y(c.y + 6f))
                lineTo(X(x1), Y(bot - 8f)); quadraticBezierTo(X(x1), Y(bot), X(x1 - 8f), Y(bot))
                lineTo(X(x0 + 8f), Y(bot)); quadraticBezierTo(X(x0), Y(bot), X(x0), Y(bot - 8f)); close()
            }
            drawPath(dome, fillOf(c)); drawPath(dome, edgeOf(c).copy(alpha = .9f), style = Stroke(6f * k))
            label(name(c), c.x, c.y + 4f, 42f, labelColor(c))
        }
        for (p in art.parts) if (p.kind == PartKind.HINGE) rrect(p.x, p.y, p.w, p.h, p.r, 3f, .35f, co.face.copy(alpha = co.face.alpha * .4f))
        path(art.bodyPath(), co.face.copy(alpha = co.face.alpha * .55f), co.ink.copy(alpha = .85f), 7f)
        for (p in art.parts) when (p.kind) {
            PartKind.SCREEN -> rrect(p.x, p.y, p.w, p.h, p.r, 3f, .3f, co.face.copy(alpha = co.face.alpha * .35f))
            // build 18 (GC11, gd-D19 / ps-F11): named (its own Back / AYN): a key that stays with Wayfinder — dashed
            PartKind.TOUCHPAD -> p.d?.let { d ->
                if (p.label == null) path(artPath(d), co.face.copy(alpha = co.face.alpha * .35f), co.ink.copy(alpha = .6f), 4f)
                else withTransform({ translate(s.ox, s.oy); scale(k, k, Offset.Zero) }) {
                    drawPath(artPath(d), co.face.copy(alpha = co.face.alpha * .35f))
                    drawPath(artPath(d), co.ink.copy(alpha = .5f), style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))))
                }
            }
            PartKind.MUTE -> if (p.label == null) rrect(p.x - p.w / 2, p.y - p.h / 2, p.w, p.h, p.h / 2, 3f, .5f, fill = null)
                else drawRoundRect(co.ink.copy(alpha = .5f), Offset(X(p.x - p.w / 2), Y(p.y - p.h / 2)), Size(p.w * k, p.h * k), CornerRadius(p.h / 2 * k),
                    style = Stroke(3f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * k, 5f * k))))
            PartKind.CAPTURE -> {
                rrect(p.x - p.w / 2, p.y - p.w / 2, p.w, p.w, 6f, 4f, .45f, faceDim)
                circle(p.x, p.y, p.w * .22f, 3f, .45f, fill = null)
            }
            // a rail that does nothing: not dashed (not Wayfinder's)
            PartKind.RAIL -> rrect(p.x - p.w / 2, p.y - p.h / 2, p.w, p.h, p.w / 2, 3f, .45f, fill = null)
            else -> {}
        }
        // the touchpad's and Mute's names (build 18, GC11): inside the touchpad, under Mute
        if (!picture) for (p in art.parts) if ((p.kind == PartKind.TOUCHPAD || p.kind == PartKind.MUTE) && p.label != null)
            label(p.label, p.labelAt?.x ?: p.x, p.labelAt?.y ?: p.y, p.labelSize, co.ink.copy(alpha = .6f), bold = false)
        // sticks
        for (c in art.ctls) { val sh = c.shape as? ArtShape.Stick ?: continue; part(c) {
            circle(c.x, c.y, sh.well, 4f, .5f, faceDim)
            if (sh.ring > 0f) drawCircle(co.lightRing, sh.ring * k, Offset(X(c.x), Y(c.y)), style = Stroke(8f * k))
            circle(c.x, c.y, sh.cap, 6f, .9f, litFill(c.targets[0]) ?: co.face, edgeOf(c))
            if (picture && c.targets.drop(1).any { it in done }) drawCircle(co.accent.copy(alpha = .28f), sh.cap * .45f * k, Offset(X(c.x), Y(c.y)))
            label(name(c), c.x, c.y, sh.cap * 48f / 76f, labelColor(c))
        } }
        // the D-pad, with its arrows (a Joy-Con's: four arrow buttons)
        val dirs = listOf(Triple("DU", 0, -1), Triple("DD", 0, 1), Triple("DL", -1, 0), Triple("DR", 1, 0))
        fun arrow(px: Float, py: Float, dx: Int, dy: Int, q: Float, c: Color) = drawPath(Path().apply {
            moveTo(X(px + dx * 13f * q), Y(py + dy * 13f * q))
            lineTo(X(px - dx * 6f * q + dy * 13f * q), Y(py - dy * 6f * q + dx * 13f * q))
            lineTo(X(px - dx * 6f * q - dy * 13f * q), Y(py - dy * 6f * q - dx * 13f * q)); close()
        }, c)
        for (c in art.ctls) when (val sh = c.shape) {
            is ArtShape.Cross -> part(c) {
                circle(c.x, c.y, sh.recess, 4f, .5f, faceDim)
                val aw = sh.arm; val rr = sh.reach; val cx = c.x; val cy = c.y
                val cross = Path().apply {
                    moveTo(X(cx - aw), Y(cy - rr)); lineTo(X(cx + aw), Y(cy - rr)); lineTo(X(cx + aw), Y(cy - aw)); lineTo(X(cx + rr), Y(cy - aw))
                    lineTo(X(cx + rr), Y(cy + aw)); lineTo(X(cx + aw), Y(cy + aw)); lineTo(X(cx + aw), Y(cy + rr)); lineTo(X(cx - aw), Y(cy + rr))
                    lineTo(X(cx - aw), Y(cy + aw)); lineTo(X(cx - rr), Y(cy + aw)); lineTo(X(cx - rr), Y(cy - aw)); lineTo(X(cx - aw), Y(cy - aw)); close()
                }
                drawPath(cross, co.face)
                for ((t, dx, dy) in dirs) litFill(t)?.let { f ->       // the wizard: that arm lit
                    val x0 = if (dx == 0) cx - aw else if (dx < 0) cx - rr else cx + aw
                    val y0 = if (dy == 0) cy - aw else if (dy < 0) cy - rr else cy + aw
                    drawRect(f, Offset(X(x0), Y(y0)), Size((if (dx == 0) 2 * aw else rr - aw) * k, (if (dy == 0) 2 * aw else rr - aw) * k))
                }
                drawPath(cross, (if (changed(c)) co.accentText else edgeOf(c)).copy(alpha = .9f), style = Stroke(6f * k))
                if (sh.arrows) {
                    val q = rr / 104f
                    for ((t, dx, dy) in dirs) arrow(cx + dx * 66f * q, cy + dy * 66f * q, dx, dy, q, if (on(t)) co.onAccent else co.ink.copy(alpha = .7f))
                }
            }
            is ArtShape.Four -> part(c) {
                circle(c.x, c.y, sh.d + sh.rb + 8f, 4f, .5f, faceDim)
                for ((t, dx, dy) in dirs) {
                    val bx = c.x + dx * sh.d; val by = c.y + dy * sh.d
                    circle(bx, by, sh.rb, 6f, .9f, litFill(t) ?: co.face, if (changed(c)) co.accentText else if (on(t)) co.accent else co.ink)
                    arrow(bx, by, dx, dy, .6f, if (on(t)) co.onAccent else co.ink.copy(alpha = .7f))
                }
            }
            else -> {}
        }
        // face buttons: their names (PlayStation's marks drawn, not typed; a sideways Joy-Con's mean nothing: none)
        for (p in art.parts) if (p.kind == PartKind.RECESS) circle(p.x, p.y, p.r, 4f, .5f, faceDim)
        for (c in art.ctls) { val sh = c.shape as? ArtShape.Face ?: continue; part(c) {
            circle(c.x, c.y, sh.rd, 6f, .9f, fillOf(c), edgeOf(c))
            val b = c.btn ?: return@part
            // critique G2: an Xbox pad's letters in its colours (a changed one stays the accent)
            val col = if (look.kind == ControllerKind.XBOX && !hi(c) && !changed(c)) faceLetterColor(Vocab.shown(b, look), dark) ?: co.ink else labelColor(c)
            when {
                look.sideways -> {}
                look.names == FaceStyle.PLAYSTATION -> FaceMark.of(Vocab.shown(b, look))?.let { drawFaceMark(it, Offset(X(c.x), Y(c.y)), 1.45f * sh.rd * k, col) }
                else -> label(Vocab.name(b, look), c.x, c.y, sh.rd * 1.25f, col)
            }
        } }
        // Select / Start / a Home going to the game: their marks (the Thor's names beside them)
        for (c in art.ctls) { val sh = c.shape as? ArtShape.Small ?: continue; part(c) {
            circle(c.x, c.y, sh.rd, 6f, .9f, fillOf(c), edgeOf(c))
            glyph(sh.glyph, c.x, c.y, sh.rd, labelColor(c))
            sh.label?.let { p -> label(name(c), p.x, p.y, sh.labelSize, bold = false) }
        } }
        for (p in art.parts) if (p.kind == PartKind.KEY) { circle(p.x, p.y, p.r, 6f, .9f); glyph(p.glyph, p.x, p.y, p.r, co.ink.copy(alpha = .8f)) }
        // SL / SR on a Joy-Con's rail
        for (c in art.ctls) { val sh = c.shape as? ArtShape.Rail ?: continue; part(c) {
            rrect(c.x - sh.w / 2, c.y - sh.h / 2, sh.w, sh.h, sh.w / 2, 5f, .9f, fillOf(c), if (changed(c)) co.accentText else edgeOf(c))
            label(if (c.id == ArtId.SL) "SL" else "SR", c.x + if (c.x < 890f) 34f else -34f, c.y, 30f, if (changed(c)) co.accentText else co.ink)
        } }
        // Wayfinder's own keys (the Thor's Home / Back, a controller's Home while it is the Thor's): dashed, never selectable
        for (p in art.parts) if (p.kind == PartKind.WAYFINDER) {
            circle(p.x, p.y, p.r, 4f, .5f, dashed = true)
            glyph(p.glyph, p.x, p.y, p.r, co.ink.copy(alpha = .6f))
            p.label?.let { label(it, p.labelAt?.x ?: p.x, p.labelAt?.y ?: (p.y + 62f), p.labelSize, co.ink.copy(alpha = .6f), bold = false) }
        }
        if (picture) {
            // a stick's axis: an arrow from its centre along + x / + y
            highlight?.let { art.targetOf(it) }?.let { (c, sub) ->
                val cap = (c.shape as? ArtShape.Stick)?.cap ?: return@let
                if (sub != 'X' && sub != 'Y') return@let
                val from = Offset(X(c.x), Y(c.y)); val dir = if (sub == 'X') Offset(1f, 0f) else Offset(0f, 1f)
                val len = 1.3f * cap * k; val end = from + dir * len; val n = Offset(-dir.y, dir.x); val head = len * .3f
                drawLine(co.accent, from, end, strokeWidth = 6f * k)
                drawLine(co.accent, end, end - dir * head + n * (head * .6f), strokeWidth = 6f * k)
                drawLine(co.accent, end, end - dir * head - n * (head * .6f), strokeWidth = 6f * k)
            }
            return@Canvas
        }
        for (n in art.notes) label(n.text, n.x, n.y, n.size, co.ink.copy(alpha = .6f), bold = false)
        // build 18 (GC11, gd-D02): this game's face positions, by the face buttons — wrapped to its spot's width
        if (faceNote != null) art.faceNote?.let { b ->
            val l = wrappedMarked(tm, faceNote, style(30f, co.accentText, false), (b.w * k).toInt(), 4)
            drawMarked(l, Offset(X(b.x + b.w / 2) - l.size.width / 2f, Y(b.y)), co.accentText)
        }
        // the motion pill, its state written inside
        for (c in art.ctls) if (c.shape == ArtShape.Gyro) part(c) {
            val thor = c.src == ArtSrc.Gyro
            val on = if (thor) remap!!.gyro.isOn else gyro?.on == true
            // build 18 (GC10, gd-D17): "Gyro · Off" — the state as the popup names it
            val text = if (thor) (if (on) "Gyro → " + remap!!.gyro.summary() else "Gyro · ${GyroMode.OFF.label}") else gyro?.text ?: "Motion · off"
            val tc = if (on) co.onAccent else co.ink
            val l = tm.measureMarked(text, style(36f, tc, true))
            val w = maxOf(l.size.width + 48f * k, 330f * k); val hh = 70f * k
            val left = X(c.x) - w / 2; val top = Y(c.y) - hh / 2
            if (on) drawRoundRect(co.accent, Offset(left, top), Size(w, hh), CornerRadius(hh / 2))
            else { drawRoundRect(co.face, Offset(left, top), Size(w, hh), CornerRadius(hh / 2)); drawRoundRect(co.ink.copy(alpha = .6f), Offset(left, top), Size(w, hh), CornerRadius(hh / 2), style = Stroke(4f * k)) }
            drawMarked(l, Offset(X(c.x) - l.size.width / 2f, Y(c.y) - l.size.height / 2f), tc)
            if (sel == c.id) {
                // build 18 (GC11, gd-D09): the full ring and its glow only while the drawing has the controller; a faint one else
                if (focused) drawRoundRect(co.accent.copy(alpha = .28f), Offset(left - 13f * k, top - 13f * k), Size(w + 26f * k, hh + 26f * k), CornerRadius(hh), style = Stroke(16f * k))
                drawRoundRect(if (focused) co.accent else co.accent.copy(alpha = PINNED_ALPHA), Offset(left - 7f * k, top - 7f * k),
                    Size(w + 14f * k, hh + 14f * k), CornerRadius(hh), style = Stroke((if (focused) 6f else 3f) * k))
            }
        }
        // the inspector, in the drawing's empty middle: line 1 shrinks, then ellipsizes, to fit its box
        inspector?.let { (a, b) ->
            val ib = art.inspector
            var l1 = tm.measureMarked(a, style(64f, co.accentText, true))
            if (l1.size.width > ib.maxW * k) l1 = tm.measureMarked(a, style(44f, co.accentText, true), (ib.maxW * k).toInt())
            drawMarked(l1, Offset(X(ib.x) - l1.size.width / 2f, Y(ib.y1) - l1.size.height / 2f), co.accentText)
            label(b, ib.x, ib.y2, 36f, co.ink.copy(alpha = .7f), bold = false, maxW = ib.maxW)
        }
        // tags: what a changed part now does, joined to it by a dotted line (ellipsized at their widest)
        val r = remap!!
        val ta = if (borrowed) .55f else 1f
        for (c in art.ctls) {
            if (c.shape == ArtShape.Gyro) continue
            val (text, filled) = tagText(c, r, look) ?: continue
            val tc = if (filled) co.onAccent else co.accentText
            val l = tm.measureMarked(text, style(36f, tc, true), ((c.tag.maxW - 32f) * k).toInt())
            val box = c.tagBox(l.size.width / k + 32f)
            val pts = c.leader(box)
            if (pts.zipWithNext { p, q -> hypot(q.x - p.x, q.y - p.y) }.sum() > 10f)
                drawPath(Path().apply { moveTo(X(pts[0].x), Y(pts[0].y)); for (p in pts.drop(1)) lineTo(X(p.x), Y(p.y)) }, co.accent.copy(alpha = ta),
                    style = Stroke(3f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * k, 6f * k))))
            val tl = Offset(X(box.x), Y(box.y)); val sz = Size(box.w * k, box.h * k); val cr = CornerRadius(sz.height / 2)
            if (filled) drawRoundRect(co.accent.copy(alpha = ta), tl, sz, cr)
            else { drawRoundRect(co.face, tl, sz, cr); drawRoundRect(co.accent.copy(alpha = ta), tl, sz, cr, style = Stroke(4f * k)) }
            drawMarked(l, Offset(tl.x + 16f * k, tl.y + (sz.height - l.size.height) / 2), tc)
        }
        // selection: glowing ring while the drawing has the controller, a plain "pinned" ring otherwise
        sel?.let { art.ctl(it) }?.let { c -> selection(c, focused, co.accent, ::X, ::Y, k) }
    }
}

/** Build 18 (GC11, gd-D09): the pinned ring's alpha — the selection kept while the controller is elsewhere (a header pill, a
 *  popup): faint, so one thing looks focused. */
private const val PINNED_ALPHA = .35f

/** [text] laid out on up to [maxLines] lines of [maxWidth] px, PlayStation's marks as placeholders ([measureMarked]'s, wrapped)
 *  — build 18 (GC11): the face-positions note. */
private fun wrappedMarked(tm: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, maxWidth: Int, maxLines: Int): androidx.compose.ui.text.TextLayoutResult {
    val ph = text.indices.filter { FaceMark.ofSymbol(text[it]) != null }.map {
        androidx.compose.ui.text.AnnotatedString.Range(androidx.compose.ui.text.Placeholder(1.15.em, 1.15.em,
            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter), it, it + 1)
    }
    return tm.measure(androidx.compose.ui.text.AnnotatedString(text), style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, maxLines = maxLines, placeholders = ph,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxWidth.coerceAtLeast(0)))
}

/** The selection ring of [c] (the motion pill's is drawn with it). Build 18 (GC11, gd-D09): [glow] (the drawing has the
 *  controller) = the full ring and its glow; else a faint, thin one. */
private fun DrawScope.selection(c: ArtCtl, glow: Boolean, accent: Color, X: (Float) -> Float, Y: (Float) -> Float, k: Float) {
    val acc = if (glow) accent else accent.copy(alpha = PINNED_ALPHA)
    val sw = (if (glow) 6f else 3f) * k
    when (val sh = c.shape) {
        ArtShape.Gyro -> {}
        ArtShape.Bumper, ArtShape.Trigger -> {
            val dome = sh == ArtShape.Trigger
            val t = if (dome) 52f else 29f; val h = if (dome) 81f else 58f
            if (glow) drawRoundRect(acc.copy(alpha = .28f), Offset(X(c.x - 108f), Y(c.y - t - 7f)), Size(216f * k, (h + 14f) * k), CornerRadius(36f * k), style = Stroke(16f * k))
            drawRoundRect(acc, Offset(X(c.x - 101f), Y(c.y - t)), Size(202f * k, h * k), CornerRadius(29f * k), style = Stroke(sw))
        }
        is ArtShape.Rail -> {
            val w = sh.w + 14f; val h = sh.h + 14f
            if (glow) drawRoundRect(acc.copy(alpha = .28f), Offset(X(c.x - w / 2 - 7f), Y(c.y - h / 2 - 7f)), Size((w + 14f) * k, (h + 14f) * k), CornerRadius((w + 14f) / 2 * k), style = Stroke(16f * k))
            drawRoundRect(acc, Offset(X(c.x - w / 2), Y(c.y - h / 2)), Size(w * k, h * k), CornerRadius(w / 2 * k), style = Stroke(sw))
        }
        else -> {
            val r = sh.selR
            if (glow) drawCircle(acc.copy(alpha = .28f), (r + 7f) * k, Offset(X(c.x), Y(c.y)), style = Stroke(16f * k))
            drawCircle(acc, r * k, Offset(X(c.x), Y(c.y)), style = Stroke(sw))
        }
    }
}

// ── words: tags and the inspector, in the names of the tab's controller ──────

/** What [b]'s source becomes in [this] (its [PadRemap.buttons] entry, else a controller part's job in [PadRemap.pads]),
 *  in [look]'s names — null = itself. */
private fun PadRemap.jobOf(b: ThorButton, look: PadLook): String? = buttons[b]?.short(look)
    ?: PadRemap.outCode(b)?.let { c -> part(c)?.let { d -> partTarget(c)?.short(look) ?: PadRemap.PAD_PARTS[d] ?: "?" } }
private fun PadRemap.jobAt(b: ThorButton) = buttons.containsKey(b) || PadRemap.outCode(b)?.let { pads.containsKey(it) } == true
private fun PadRemap.changedAt(b: ThorButton) =
    jobAt(b) || fire.containsKey(b) || PadRemap.outCode(b)?.let { padFire.containsKey(it) } == true
/** How [b] fires (turbo, toggle, long / double press), from [PadRemap.fire] or its reader's [PadRemap.padFire]. */
private fun PadRemap.fireOf(b: ThorButton): Fire? = fire[b] ?: PadRemap.outCode(b)?.let { partFire(it) }

/** What a part's tag says and whether it is filled (it becomes something else); null = no tag (unchanged). */
internal fun tagText(c: ArtCtl, r: PadRemap, look: PadLook): Pair<String, Boolean>? {
    return when (val src = c.src) {
        ArtSrc.Dpad -> {
            val arrow = mapOf(ThorButton.UP to "↑", ThorButton.DOWN to "↓", ThorButton.LEFT to "←", ThorButton.RIGHT to "→")
            val parts = PadRemap.DIRS.mapNotNull { d -> r.jobOf(d, look)?.let { "${arrow[d]} $it" } } +
                listOfNotNull("↔ Left stick".takeIf { r.dpadStick })
            if (parts.isEmpty()) return null
            (if (parts.size <= 2) parts.joinToString("  ·  ") else parts.take(2).joinToString("  ·  ") + "  +${parts.size - 2}") to true
        }
        is ArtSrc.Btn -> {
            val s = src.b
            val fire = r.fireOf(s)?.let { " · " + if (it.hasAlt) "${it.label.lowercase()} → ${r.alt[s]?.short(look) ?: "?"}" else it.label } ?: ""
            val becomes = r.jobOf(s, look)
            // 1.3: a stick with a job (mouse / scroll / keys) says so first
            val job = when (s) { ThorButton.L3 -> r.jobL; ThorButton.R3 -> r.jobR; else -> null }?.takeIf { !it.isDefault }
            val text = when {
                job != null -> "→ " + job.summary
                becomes != null -> "→ $becomes$fire"
                fire.isNotEmpty() -> fire.removePrefix(" · ").replaceFirstChar { it.uppercase() }
                s == ThorButton.L3 && (r.invertLeftY || r.invertLeftX || r.swapSticks) -> if (r.swapSticks) "↔ Right stick"
                    else listOfNotNull("↕".takeIf { r.invertLeftY }, "↔".takeIf { r.invertLeftX }).joinToString(" ", "Inverted ")
                s == ThorButton.R3 && (r.invertRightY || r.invertRightX || r.swapSticks) -> if (r.swapSticks) "↔ Left stick"
                    else listOfNotNull("↕".takeIf { r.invertRightY }, "↔".takeIf { r.invertRightX }).joinToString(" ", "Inverted ")
                (s == ThorButton.L2 || s == ThorButton.R2) && r.digitalTriggers -> "All-or-nothing"
                else -> null
            } ?: return null
            text to (becomes != null || job != null)
        }
        is ArtSrc.Part -> {
            val to = r.part(src.code)?.let { d -> r.partTarget(src.code)?.short(look) ?: PadRemap.PAD_PARTS[d] ?: "?" }
            val f = r.partFire(src.code)?.label
            val text = when { to != null -> "→ $to" + (f?.let { " · $it" } ?: ""); f != null -> f; else -> null } ?: return null
            text to (to != null)
        }
        ArtSrc.Gyro, ArtSrc.PadGyro -> null
    }
}

/** A part's name in a title (the bind popup's): "Left stick (L3)", "D-pad", "PS"… in [look]'s names. */
internal fun ArtCtl.title(look: PadLook): String = when (val s = src) {
    is ArtSrc.Btn -> when (s.b) {
        ThorButton.L3 -> "Left stick (${Vocab.name(ThorButton.L3, look)})"
        ThorButton.R3 -> "Right stick (${Vocab.name(ThorButton.R3, look)})"
        else -> Vocab.name(s.b, look)
    }
    ArtSrc.Dpad -> "D-pad"
    ArtSrc.Gyro -> "Gyro"
    ArtSrc.PadGyro -> "Motion"
    is ArtSrc.Part -> when (id) { ArtId.HOME -> Vocab.name(ThorButton.HOME, look); ArtId.SL -> "SL"; else -> "SR" }
}

/** What the drawing's empty middle says about part [c]: line 1 what it does, line 2 how it fires — plus, on a controller's
 *  tab ([tab]), why it may do nothing. critique M8: no button hints here (the page's legend has them). */
internal fun inspectorText(c: ArtCtl, r: PadRemap, look: PadLook, gyro: GyroPill? = null, tab: TabArt? = null): Pair<String, String> {
    val note = when {
        tab == null -> null
        c.id in tab.faint -> "Not set up — on its page: A button or stick is read wrong"
        else -> null
    }
    val (a, b) = when (val src = c.src) {
        is ArtSrc.Btn -> {
            val s = src.b
            val t = r.jobOf(s, look)
            val job = when (s) { ThorButton.L3 -> r.jobL; ThorButton.R3 -> r.jobR; else -> null }?.takeIf { !it.isDefault }
            (if (job != null) "${if (s == ThorButton.L3) "Left" else "Right"} stick  →  ${job.summary}"
                else if (t == null) "${Vocab.name(s, look)} — as usual" else "${Vocab.name(s, look)}  →  $t") to
                (r.fireOf(s)?.let { if (it.hasAlt) "${it.label} → ${r.alt[s]?.short(look) ?: "nothing yet"}" else it.label } ?: "")
        }
        ArtSrc.Gyro -> "Gyro — ${r.gyro.summary()}" to "Mouse or stick from moving the Thor"
        // dev build 16 review (WP5-1): the pill's own words ("Motion → …" / "Motion · off") — "Motion — Motion → …" before
        ArtSrc.PadGyro -> (gyro?.text ?: "Motion · off") to "Turn and tilt the controller to aim"
        ArtSrc.Dpad -> "D-pad" to PadRemap.DIRS.count { r.changedAt(it) }.let { n ->
            listOfNotNull("$n direction${if (n > 1) "s" else ""} changed".takeIf { n > 0 }, "swapped with the left stick".takeIf { r.dpadStick })
                .joinToString(" · ").ifEmpty { "Each direction can do something else" }.replaceFirstChar { it.uppercase() } }
        is ArtSrc.Part -> {
            val to = r.part(src.code)?.let { d -> r.partTarget(src.code)?.short(look) ?: PadRemap.PAD_PARTS[d] ?: "?" }
            (if (to == null) "${c.title(look)} — as usual" else "${c.title(look)}  →  $to") to (r.partFire(src.code)?.label ?: "")
        }
    }
    return a to listOfNotNull(b.takeIf { it.isNotEmpty() }, note).joinToString("   ·   ")
}

// ── input ──────────────────────────────────────────────────────────────────

/** dev build 16 (WP5): the drawing driven by any controller or a touch — A opens on its RELEASE (both halves kept, as
 *  1.3), the D-pad walks ([PadArt.neighbour]; nothing that way = the key goes on), a touch selects, opens and takes the
 *  focus. critique M6: the touched part is what opens ([onOpen] gets its id — the page's selection is a frame behind),
 *  and the callbacks are always the latest (rememberUpdatedState: the tap detector outlives a recomposition). */
@Composable
internal fun Modifier.padArtInput(art: PadArt, sel: ArtId, focus: FocusRequester, enabled: Boolean,
                                  onSelect: (ArtId) -> Unit, onOpen: (ArtId) -> Unit): Modifier {
    val artNow by rememberUpdatedState(art)
    val selNow by rememberUpdatedState(sel)
    val select by rememberUpdatedState(onSelect)
    val open by rememberUpdatedState(onOpen)
    return focusRequester(focus)
        .onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent
            val confirm = n.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == KeyEvent.KEYCODE_ENTER ||
                ButtonEngine.menuButton(n) == ThorButton.A
            // A opens the part on its RELEASE (both halves kept here, so the release can't "click" the popup's first choice)
            if (confirm) { if (e.type == KeyEventType.KeyUp) open(selNow); return@onPreviewKeyEvent true }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val dir = when (n.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f; KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
                KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f; KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
                else -> return@onPreviewKeyEvent false
            }
            val a = artNow
            a.neighbour(a.resolve(selNow), dir.first, dir.second)?.let { select(it.id); true } ?: false
        }
        .focusProperties { canFocus = enabled }
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures { p ->
                artNow.hit(p.x, p.y, size.width.toFloat(), size.height.toFloat())?.let {
                    select(it.id); runCatching { focus.requestFocus() }; open(it.id)
                }
            }
        }
}
