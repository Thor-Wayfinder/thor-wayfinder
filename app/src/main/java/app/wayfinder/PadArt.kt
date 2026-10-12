package app.wayfinder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * dev build 16 (WP5): ONE drawing system for every controller. A controller's
 * drawing is data — its body, its parts (where, how big, what they edit), where their tags go — and the canvas
 * (PadArtCanvas.kt) draws any of them the same way: selection, D-pad walk, tags and inspector are one code path.
 * Units: Game controls' design units (RemapScreen's map since 1.3): every drawing in the same box (x 10..1770,
 * y -185..858), so a label is the same size on every tab. Pure Kotlin: the JVM tests walk every drawing (PadArtTest).
 * Sources are POSITIONS (the Thor's print, as PadRemap): one source table serves every drawing (fx/wfhid_core.h HX_PRINTED).
 */

data class ArtPt(val x: Float, val y: Float)
data class ArtBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val r get() = x + w
    val b get() = y + h
    fun overlaps(o: ArtBox) = x < o.r && o.x < r && y < o.b && o.y < b
}

/** dev build 16 (WP5): the five drawings (Part 3 §2 "Per controller"). */
enum class ArtKind { THOR, SYMMETRIC, OFFSET, JOYCON_PAIR, JOYCON_SINGLE }

/** A part's identity, the same on every drawing (the selection survives a tab switch). Face buttons are POSITIONS
 *  ([ThorButton] A right, B bottom, X top, Y left), whatever is printed on them. */
enum class ArtId { A, B, X, Y, L1, R1, L2, R2, L3, R3, SELECT, START, DPAD, GYRO, HOME, SL, SR }

/** What a part edits. */
sealed class ArtSrc {
    /** A [PadRemap] source: a position (buttons, L1…R3, Select, Start). */
    data class Btn(val b: ThorButton) : ArtSrc()
    /** The D-pad's four directions ([PadRemap.DIRS]) — its own page (DpadPopup). */
    object Dpad : ArtSrc()
    /** The Thor's motion ([PadRemap.gyro]). */
    object Gyro : ArtSrc()
    /** A controller's motion aim (ExtPadsCtl.Gyro, per game: gyroFor) — its pill's words come from the page. */
    object PadGyro : ArtSrc()
    /** A part only a connected controller has, by its printed code (wfhid HX_PRINTED: 0x132 SL, 0x135 SR, 0x13c its
     *  Home going to the game) — [PadRemap.pads] / [PadRemap.padFire]. */
    data class Part(val code: Int) : ArtSrc()
}

/** The small round buttons' marks (drawn as shapes, never as typed symbols — but the Thor's ⌂ / ↺ as today). */
enum class ArtGlyph { NONE, SQUARE, TRIANGLE, LINES, VIEW, SHARE, MINUS, PLUS, HOUSE, BACK, PS, XBOX, CAPTURE }

sealed class ArtShape {
    /** A face button: [rd] its radius, [sel] its selection ring's. */
    data class Face(val rd: Float, val sel: Float) : ArtShape()
    /** Select / Start / a Home: [label] = where its name is written (the Thor's "Select" / "Start"), null = none; build 18
     *  (GC11, ps-F11) [labelSize] its size (a DualSense's "Create" / "Options" fit above them smaller). */
    data class Small(val rd: Float, val sel: Float, val glyph: ArtGlyph, val label: ArtPt? = null, val labelSize: Float = 42f) : ArtShape()
    /** A stick: its well, its cap, [ring] = the Thor's lights around it (0 none). */
    data class Stick(val well: Float, val cap: Float, val sel: Float, val ring: Float = 0f) : ArtShape()
    /** A D-pad: [arm] half its arm's width, [reach] its arm's length, [recess] the well around it. */
    data class Cross(val arm: Float, val reach: Float, val recess: Float, val sel: Float, val arrows: Boolean) : ArtShape()
    /** A Joy-Con's four arrow buttons: [d] from the centre, [rb] each button's radius. */
    data class Four(val d: Float, val rb: Float, val sel: Float) : ArtShape()
    /** L1 / R1: a pill on the top edge. */
    object Bumper : ArtShape()
    /** L2 / R2: the dome behind it. */
    object Trigger : ArtShape()
    /** SL / SR on a Joy-Con's rail. */
    data class Rail(val w: Float, val h: Float) : ArtShape()
    /** The motion pill (its state written inside). */
    object Gyro : ArtShape()
}

/** The selection ring's radius of a round part (0: drawn its own way — shoulders, rails, the motion pill). */
val ArtShape.selR: Float get() = when (this) {
    is ArtShape.Face -> sel; is ArtShape.Small -> sel; is ArtShape.Stick -> sel; is ArtShape.Cross -> sel; is ArtShape.Four -> sel
    else -> 0f
}

/** Where a part's tag goes, centred on y: [align] 1 = the tag starts at x, −1 = ends at x, 0 = centred on x (below
 *  the part: its leader ends at the tag's top); [via] = one corner for the dotted leader (around a cluster); [maxW] =
 *  its widest (longer text ellipsized: nothing leaves the drawing). */
data class TagSpot(val x: Float, val y: Float, val align: Int, val via: ArtPt? = null, val maxW: Float = 330f)

/** A selectable part: [r] = its reach (hit radius ×1.25, leader start — as 1.3's map), [hit] = half-size of a hit
 *  rectangle (the motion pill) or null. */
data class ArtCtl(val id: ArtId, val src: ArtSrc, val x: Float, val y: Float, val r: Float, val shape: ArtShape,
                  val tag: TagSpot, val hit: ArtPt? = null) {
    val btn: ThorButton? get() = (src as? ArtSrc.Btn)?.b
}

/** What is drawn but never selected: the Thor's hinge and screen, a cluster's recess, a touchpad, Mute, Capture, a
 *  rail that does nothing, KEY = a button not editable here (a Home going to the game, before its editor), and
 *  WAYFINDER = a key that stays with Wayfinder (dashed: the Thor's Home and Back, a pad's Home while it is the Thor's
 *  Home). HINGE is drawn before the body. Boxes are top-left + size (HINGE, SCREEN), others centred; [d] = a path. */
enum class PartKind { HINGE, SCREEN, RECESS, TOUCHPAD, MUTE, CAPTURE, RAIL, KEY, WAYFINDER }
/** Build 18 (GC11, gd-D19 / ps-F11): [label] its name, at [labelAt] in [labelSize] (null: a Wayfinder key's under it, as the
 *  Thor's Home and Back) — a touchpad or a Mute with a label is a key that stays with Wayfinder (drawn dashed). */
data class ArtPart(val kind: PartKind, val x: Float, val y: Float, val w: Float = 0f, val h: Float = 0f, val r: Float = 0f,
                   val glyph: ArtGlyph = ArtGlyph.NONE, val label: String? = null, val d: String? = null,
                   val labelAt: ArtPt? = null, val labelSize: Float = 38f)

data class ArtNote(val text: String, val x: Float, val y: Float, val size: Float = 34f)
/** The inspector's two lines: centred on [x], at [y1] (64, shrinks to 44 then ellipsis within [maxW]) and [y2] (36). */
data class InspectorBox(val x: Float, val y1: Float, val y2: Float, val maxW: Float)

class PadArt(
    val kind: ArtKind,
    val body: String,                  // SVG path data, design units (several sub-paths: the pair)
    val ctls: List<ArtCtl>,
    val parts: List<ArtPart>,
    val notes: List<ArtNote>,
    val inspector: InspectorBox,
    val frame: ArtBox,                 // the drawing alone (the setup wizard's crop)
    val first: ArtId,                  // selected when the asked one isn't on this drawing
    // (build 18, GC11 / gd-D20: the legend's "caption" went — the Thor's back-edge sentence is one of its notes)
    /** Build 18 (GC4, G17): the walk's set moves — (part, 'U' 'D' 'L' 'R') → part — where the nearest one ahead surprises
     *  ([neighbour] asks them first; a part not on this drawing: the geometry's answer). */
    val links: Map<Pair<ArtId, Char>, ArtId> = emptyMap(),
    /** Build 18 (GC9, gd-D07): the tab's icon (SVG path, any units: drawn fitted) — the body, the Thor's a clamshell. */
    val iconPath: String = body,
    /** Build 18 (GC11, gd-D02): where the face-positions note goes (wrapped to its width) — by the face buttons, under their
     *  tags: inside the drawing there is no room under the cluster that no leader or outline crosses. */
    val faceNote: ArtBox? = null,
) {
    fun ctl(id: ArtId) = ctls.firstOrNull { it.id == id }
    /** Never null: the asked part, else [first], else the first part. */
    fun resolve(id: ArtId) = ctl(id) ?: ctl(first) ?: ctls.first()
    /** The part a press of [b] (a position) selects: its button, or the D-pad for a direction. */
    fun ctlFor(b: ThorButton) = ctls.firstOrNull { it.btn == b } ?: if (b.isDpad) ctl(ArtId.DPAD) else null
    /** This drawing without [ids] (parts a controller hasn't got). Never empty: all of them = the drawing unchanged. */
    fun without(ids: Set<ArtId>): PadArt {
        val kept = ctls.filter { it.id !in ids }
        return if (kept.isEmpty() || kept.size == ctls.size) this
            else PadArt(kind, body, kept, parts, notes, inspector, frame, first, links, iconPath, faceNote)
    }
}

// ── view, hit test, walk ──────────────────────────────────────────────────

object ArtView {
    /** The design box every drawing lives in (RemapScreen's map since 1.3). */
    val VIEW = ArtBox(10f, -185f, 1760f, 1043f)
    class Scale(val k: Float, val ox: Float, val oy: Float) {
        fun x(u: Float) = ox + u * k
        fun y(u: Float) = oy + u * k
        fun ux(px: Float) = (px - ox) / k
        fun uy(py: Float) = (py - oy) / k
    }
    /** [box] fitted and centred in a w × h canvas (1.3's mapScale for [VIEW]). */
    fun scale(w: Float, h: Float, box: ArtBox = VIEW): Scale {
        val k = minOf(w / box.w, h / box.h)
        return Scale(k, (w - box.w * k) / 2 - box.x * k, (h - box.h * k) / 2 - box.y * k)
    }
    /** The walk's second cone when nothing lies within ~63°: a part only counts if along > FALLBACK × across. critique
     *  M7: 0 = any part ahead — 1.3's rule, the Thor's walk exactly (PadArtTest freezes its table). */
    const val FALLBACK = 0f
}

/** The part under a touch (canvas pixels): a hit rectangle first (the motion pill), else the nearest part within
 *  max(r × 1.25, 70) — 1.3's mapHit. */
fun PadArt.hit(px: Float, py: Float, w: Float, h: Float, box: ArtBox = ArtView.VIEW): ArtCtl? {
    val s = ArtView.scale(w, h, box); val ux = s.ux(px); val uy = s.uy(py)
    ctls.firstOrNull { c -> c.hit?.let { abs(ux - c.x) < it.x && abs(uy - c.y) < it.y } == true }?.let { return it }
    return ctls.filter { hypot(it.x - ux, it.y - uy) < maxOf(it.r * 1.25f, 70f) }.minByOrNull { hypot(it.x - ux, it.y - uy) }
}

/** The part in that direction: the nearest one, favouring those straight ahead. 1.3: it must lie IN that direction
 *  (within ~63°) — Left on Y went up to Start and never reached the left stick; only when nothing does, one further
 *  round ([ArtView.FALLBACK]). null = nothing that way: the key goes on. Every drawing's walk reaches every part. */
fun PadArt.neighbour(from: ArtCtl, dx: Float, dy: Float): ArtCtl? {
    // build 18 (GC4, G17): a set move first
    val dir = when { dy < 0f && dx == 0f -> 'U'; dy > 0f && dx == 0f -> 'D'; dx < 0f && dy == 0f -> 'L'; dx > 0f && dy == 0f -> 'R'; else -> null }
    dir?.let { links[from.id to it] }?.let { ctl(it) }?.takeIf { it !== from }?.let { return it }
    fun along(c: ArtCtl) = (c.x - from.x) * dx + (c.y - from.y) * dy
    fun across(c: ArtCtl) = abs((c.x - from.x) * dy - (c.y - from.y) * dx)
    val ahead = ctls.filter { it !== from && along(it) > 20f }
    return ahead.filter { along(it) > 0.5f * across(it) }.ifEmpty { ahead.filter { along(it) > ArtView.FALLBACK * across(it) } }
        .minByOrNull { along(it) + 2.2f * across(it) }
}

// ── tags (the canvas and the tests share them) ────────────────────────────

const val TAG_H = 54f
/** The tag's box for a text [w] units wide (its padding included), clamped to [TagSpot.maxW]. */
fun ArtCtl.tagBox(w: Float): ArtBox {
    val ww = minOf(w, tag.maxW)
    val left = when { tag.align > 0 -> tag.x; tag.align < 0 -> tag.x - ww; else -> tag.x - ww / 2 }
    return ArtBox(left, tag.y - TAG_H / 2, ww, TAG_H)
}
/** The dotted leader: from the part's edge (r + 8 toward the first point, as 1.3), through [TagSpot.via], to the tag's
 *  near end. */
fun ArtCtl.leader(t: ArtBox): List<ArtPt> {
    val end = when { tag.align > 0 -> ArtPt(t.x, tag.y); tag.align < 0 -> ArtPt(t.r, tag.y); else -> ArtPt(tag.x, t.y) }
    val aim = tag.via ?: end
    val ang = atan2(aim.y - y, aim.x - x)
    val start = ArtPt(x + cos(ang) * (r + 8f), y + sin(ang) * (r + 8f))
    return listOfNotNull(start, tag.via, end)
}

// ── the setup wizard's targets (ExtPadsPage STEPS, wfhid HX_TNAME) ─────────

val ArtCtl.targets: List<String> get() = when (id) {
    ArtId.B -> listOf("S"); ArtId.A -> listOf("E"); ArtId.Y -> listOf("W"); ArtId.X -> listOf("N")
    ArtId.L1 -> listOf("L1"); ArtId.R1 -> listOf("R1"); ArtId.L2 -> listOf("L2"); ArtId.R2 -> listOf("R2")
    ArtId.L3 -> listOf("L3", "LX", "LY"); ArtId.R3 -> listOf("R3", "RX", "RY")
    ArtId.SELECT -> listOf("SE"); ArtId.START -> listOf("ST"); ArtId.HOME -> listOf("HO")
    ArtId.SL -> listOf("SL"); ArtId.SR -> listOf("SR"); ArtId.DPAD -> listOf("DU", "DD", "DL", "DR"); ArtId.GYRO -> emptyList()
}
val PadArt.targets: Set<String> get() = ctls.flatMap { it.targets }.toSet()
/** A target's part, and which piece of it: the D-pad's arm ('U' 'D' 'L' 'R') or a stick's axis ('X' 'Y'). */
fun PadArt.targetOf(t: String): Pair<ArtCtl, Char?>? = ctls.firstOrNull { t in it.targets }?.let { c ->
    c to when (t) { "DU" -> 'U'; "DD" -> 'D'; "DL" -> 'L'; "DR" -> 'R'; "LX", "RX" -> 'X'; "LY", "RY" -> 'Y'; else -> null } }

// ── which drawing for which controller ─────────────────────────────────────

/** dev build 16 (WP5): the drawings. Geometry never depends on a controller's names or face style (tags stay on the
 *  same physical button); only its kind, how a Joy-Con is held, and what its Home does choose. */
object PadArts {
    val THOR: PadArt = thor()

    /** [look]'s controller. [notOn]: wizard targets its saved mapping marks "Not on it" ([notOn] of the mapping) — a part
     *  is left out only then or when its kind has none (an unmapped part is still drawn: a real Pro). [homeWayfinder]:
     *  its Home is the Thor's Home (ExtPadsCtl.homeIsThors) — a dashed Wayfinder key, else a part going to the game
     *  ([parts]: editable, else a plain key). [gyro]: its motion pill (the Nintendo kinds, D10). dev build 17 (theme G):
     *  [kept] = the names of its own Back / AYN buttons (ExtPadsCtl.spares: a button of its own, never a stand-in — those
     *  are no parts), said in the drawing's note as staying with Wayfinder. */
    fun of(look: PadLook, notOn: Set<String> = emptySet(), homeWayfinder: Boolean = true,
           gyro: Boolean = look.kind.gyro, parts: Boolean = true, kept: Pair<String?, String?> = null to null): PadArt {
        val k = look.kind
        val h = Home(Vocab.name(ThorButton.HOME, look), homeWayfinder, look.noHome, "HO" in notOn, parts, kept.first, kept.second)
        val g = gyro && k.gyro
        val art = when {
            k == ControllerKind.THOR -> return THOR
            k == ControllerKind.PLAYSTATION -> symmetric(dualSense = look.pid == 0x0ce6 || look.pid == 0x0df2, h)
            k == ControllerKind.XBOX -> offset(Center.XBOX, h, false, k.capture)
            k == ControllerKind.NINTENDO_PRO -> offset(Center.NINTENDO, h, g, k.capture)
            k == ControllerKind.JOYCON_PAIR -> joyconPair(null, h, g, parts, k)
            k.single && look.sideways -> joyconSingle(k == ControllerKind.JOYCON_L, h, g)
            k.single -> joyconPair(if (k == ControllerKind.JOYCON_L) 'L' else 'R', h, g, parts, k)
            else -> offset(Center.GENERIC, h, false, k.capture)
        }
        return art.without(art.ctls.filter { c -> c.targets.isNotEmpty() && c.targets.all { it in notOn } }.map { it.id }.toSet())
    }

    /** The setup wizard: everything its kind has, its Home as a part to press, no motion. */
    fun full(look: PadLook): PadArt = of(look.copy(noHome = false), homeWayfinder = false, gyro = false, parts = true)

    /** The targets a wfhid mapping names ("S=2 E=3i …", ExtPadsCtl.Pad.mapping), null when it names none (not said yet). */
    fun targetsOf(mapping: String?): Set<String>? = mapping.orEmpty().split(' ')
        .mapNotNull { t -> t.substringBefore('=', "").takeIf { it.isNotEmpty() && !t.endsWith("=-1") } }.toSet().takeIf { it.isNotEmpty() }
    /** The targets a saved mapping marks "Not on it" ("SL=-1", the setup wizard). */
    fun notOn(spec: String?): Set<String> =
        spec.orEmpty().split(' ').filter { it.endsWith("=-1") }.map { it.substringBefore('=') }.toSet()

    /** The parts its mapping in use ([targetsOf]) doesn't read: drawn faint, "not set up" (critique m5). Unknown = none. */
    fun unset(art: PadArt, mapping: Set<String>?): Set<ArtId> = if (mapping == null) emptySet() else art.ctls.filter { c ->
        c.targets.isNotEmpty() && c.targets.none { it in mapping } &&
            !(c.id == ArtId.L2 && "LT" in mapping) && !(c.id == ArtId.R2 && "RT" in mapping)
    }.map { it.id }.toSet()
    // (dev build 17, theme G: build 16's "analog" set went — wfhid makes L2 / R2 of an axis-only trigger, 30 % / 20 %)

    /** dev build 17 (theme G): what stays with Wayfinder on a controller, in one line — "PS, Back (touchpad click) and AYN
     *  (Mute) stay with Wayfinder"; [home] its Home's name when it is the Thor's Home, [back] / [ayn] the names of its own
     *  Back / AYN buttons. null = nothing stays. */
    fun keptNote(home: String?, back: String?, ayn: String?): String? {
        val l = listOfNotNull(home, back?.let { "Back ($it)" }, ayn?.let { "AYN ($it)" })
        return when (l.size) {
            0 -> null
            1 -> "${l[0]} stays with Wayfinder"
            else -> "${l.dropLast(1).joinToString(", ")} and ${l.last()} stay with Wayfinder"
        }
    }

    // ── the builders (coordinates checked: walk, hits, tags and leaders — PadArtTest) ──

    /** How a controller's Home is drawn: [name] its own ("PS", "Capture"), [wayfinder] the Thor's Home, [noHome] none
     *  (Select + Start held is its Home), [absent] marked "Not on it", [parts] editable when it goes to the game. dev build
     *  17 (theme G): [back] / [ayn] its own Back / AYN buttons' names (they stay with Wayfinder: the note says so). */
    private class Home(val name: String, val wayfinder: Boolean, val noHome: Boolean, val absent: Boolean, val parts: Boolean,
                       val back: String? = null, val ayn: String? = null)

    /** dev build 17 (theme G): how far under the Home note its Back / AYN's own note goes — 40 units: a motion pill pushes
     *  the Nintendo Pro's note down, just above the inspector. */
    private const val KEPT_UNDER = 40f

    private fun home(h: Home, x: Float, y: Float, rd: Float, glyph: ArtGlyph, tag: TagSpot, note: ArtPt,
                     ctls: MutableList<ArtCtl>, parts: MutableList<ArtPart>, notes: MutableList<ArtNote>, homeLabel: ArtPt? = null) {
        // dev build 17 (theme G): its own Back / AYN stay with Wayfinder whatever its Home does — in the Home's note when its
        // Home is the Thor's, else in a note of its own: under the Home note, or under the Home's tag when that sits where the
        // note goes (PlayStation's, under its Home), else in the note's place
        val kept = keptNote(null, h.back, h.ayn)
        fun keptAt(y0: Float) { if (kept != null) notes += ArtNote(kept, note.x, y0, 32f) }
        when {
            h.absent -> keptAt(note.y)
            h.noHome -> { notes += ArtNote("${h.name} = Home", note.x, note.y, 32f); keptAt(note.y + KEPT_UNDER) }
            // build 18 (GC11, ps-F11): where there is room, the key's job written by it ("Home"), as the Thor's
            h.wayfinder -> { parts += ArtPart(PartKind.WAYFINDER, x, y, r = rd, glyph = glyph, label = homeLabel?.let { "Home" }, labelAt = homeLabel,
                labelSize = 28f); notes += ArtNote(keptNote(h.name, h.back, h.ayn)!!, note.x, note.y, 32f) }
            h.parts -> {
                ctls += ArtCtl(ArtId.HOME, ArtSrc.Part(PadRemap.HOME_CODE), x, y, maxOf(30f, rd + 6f), ArtShape.Small(rd, rd + 12f, glyph), tag)
                keptAt(if (tag.align == 0 && abs(tag.y - note.y) < TAG_H) tag.y + TAG_H / 2 + 30f else note.y)
            }
            else -> { parts += ArtPart(PartKind.KEY, x, y, r = rd, glyph = glyph); keptAt(note.y) }
        }
    }

    private fun btn(id: ArtId, b: ThorButton, x: Float, y: Float, r: Float, shape: ArtShape, tag: TagSpot) =
        ArtCtl(id, ArtSrc.Btn(b), x, y, r, shape, tag)
    private fun gyroCtl(src: ArtSrc, x: Float, y: Float) =
        ArtCtl(ArtId.GYRO, src, x, y, 60f, ArtShape.Gyro, TagSpot(x, y, 1), hit = ArtPt(175f, 45f))
    /** Four face buttons around ([cx], [cy]), [d] apart: X top, Y left, A right, B bottom (positions). */
    private fun face(cx: Float, cy: Float, d: Float, rd: Float, sel: Float, r: Float, x: TagSpot, y: TagSpot, a: TagSpot, b: TagSpot) = listOf(
        btn(ArtId.X, ThorButton.X, cx, cy - d, r, ArtShape.Face(rd, sel), x),
        btn(ArtId.Y, ThorButton.Y, cx - d, cy, r, ArtShape.Face(rd, sel), y),
        btn(ArtId.A, ThorButton.A, cx + d, cy, r, ArtShape.Face(rd, sel), a),
        btn(ArtId.B, ThorButton.B, cx, cy + d, r, ArtShape.Face(rd, sel), b))
    private fun left(x: Float, y: Float, via: ArtPt? = null, maxW: Float = 330f) = TagSpot(x, y, -1, via, maxW)
    private fun right(x: Float, y: Float, via: ArtPt? = null, maxW: Float = 330f) = TagSpot(x, y, 1, via, maxW)

    /** The Thor — 1.3's map, unchanged (same parts in the same order: the walk's ties too). */
    private fun thor() = PadArt(
        ArtKind.THOR,
        "M30 36 Q30 22 44 22 L1436 22 Q1450 22 1450 36 L1450 784 Q1450 830 1404 830 L76 830 Q30 830 30 784 Z",
        listOf(
            btn(ArtId.L2, ThorButton.L2, 141f, -130f, 60f, ArtShape.Trigger, right(256f, -130f, maxW = 400f)),
            btn(ArtId.L1, ThorButton.L1, 141f, -69f, 60f, ArtShape.Bumper, right(256f, -69f, maxW = 400f)),
            btn(ArtId.R2, ThorButton.R2, 1339f, -130f, 60f, ArtShape.Trigger, left(1224f, -130f, maxW = 400f)),
            btn(ArtId.R1, ThorButton.R1, 1339f, -69f, 60f, ArtShape.Bumper, left(1224f, -69f, maxW = 400f)),
            btn(ArtId.SELECT, ThorButton.SELECT, 285f, 80f, 34f, ArtShape.Small(24f, 36f, ArtGlyph.SQUARE, ArtPt(196f, 80f)), right(322f, 80f, maxW = 400f)),
            btn(ArtId.START, ThorButton.START, 1195f, 80f, 34f, ArtShape.Small(24f, 36f, ArtGlyph.TRIANGLE, ArtPt(1278f, 80f)), left(1158f, 80f, maxW = 400f)),
            btn(ArtId.L3, ThorButton.L3, 178f, 240f, 118f, ArtShape.Stick(118f, 76f, 90f, 103f), right(312f, 240f, maxW = 400f)),
            btn(ArtId.X, ThorButton.X, 1302f, 156f, 44f, ArtShape.Face(40f, 52f), right(1466f, 150f, maxW = 304f)),
            btn(ArtId.Y, ThorButton.Y, 1218f, 240f, 44f, ArtShape.Face(40f, 52f), left(1160f, 240f, maxW = 400f)),
            btn(ArtId.A, ThorButton.A, 1386f, 240f, 44f, ArtShape.Face(40f, 52f), right(1466f, 240f, maxW = 304f)),
            btn(ArtId.B, ThorButton.B, 1302f, 324f, 44f, ArtShape.Face(40f, 52f), right(1466f, 330f, maxW = 304f)),
            ArtCtl(ArtId.DPAD, ArtSrc.Dpad, 178f, 522f, 112f, ArtShape.Cross(31f, 104f, 112f, 122f, true), right(312f, 522f, maxW = 400f)),
            btn(ArtId.R3, ThorButton.R3, 1302f, 522f, 118f, ArtShape.Stick(118f, 76f, 90f, 103f), right(1466f, 522f, maxW = 304f)),
            gyroCtl(ArtSrc.Gyro, 740f, 600f),
        ),
        listOf(
            ArtPart(PartKind.HINGE, 40f, -24f, 1400f, 24f, 12f),
            ArtPart(PartKind.SCREEN, 372.5f, 58f, 735f, 640f, 12f),
            ArtPart(PartKind.RECESS, 1302f, 240f, r = 132f),
            ArtPart(PartKind.WAYFINDER, 250f, 722f, r = 28f, glyph = ArtGlyph.HOUSE, label = "Home"),
            ArtPart(PartKind.WAYFINDER, 1230f, 722f, r = 28f, glyph = ArtGlyph.BACK, label = "Back"),
        ),
        // build 18 (GC11, gd-D20): the back-edge sentence is the drawing's (it sat in the legend among the button hints) — at
        // the top of the screen, nearest the shoulders: above them the view ends
        listOf(ArtNote("Home and Back stay with Wayfinder", 740f, 770f, 34f), ArtNote(THOR_BACK_EDGE, 740f, 165f, 32f)),
        InspectorBox(740f, 350f, 440f, 1300f), ArtBox(10f, -185f, 1460f, 1043f), ArtId.A,
        // build 18 (GC4, G17): Up from the motion pill (under the screen) went to Y across the drawing, Down from R1 to X past
        // Start — the pill goes up to Select, R1 down to Start (the top corners' own buttons)
        links = mapOf((ArtId.GYRO to 'U') to ArtId.SELECT, (ArtId.R1 to 'D') to ArtId.START),
        // build 18 (GC9, gd-D07): the tab's icon reads as a handheld — two screens and the hinge between them
        iconPath = THOR_ICON,
        faceNote = ArtBox(1470f, 570f, 296f, 150f),
    )

    /** Build 18 (GC11): the Thor's note about its shoulder buttons. */
    const val THOR_BACK_EDGE = "L1 L2 · R1 R2 are on the back edge"
    /** Build 18 (GC9, gd-D07): a clamshell — the lid (top screen), the hinge line, the base (bottom screen and controls). */
    private const val THOR_ICON = "M20 0 L140 0 Q150 0 150 10 L150 50 Q150 58 142 58 L18 58 Q10 58 10 50 L10 10 Q10 0 20 0 Z " +
        "M14 66 L146 66 M10 74 L150 74 Q160 74 160 84 L160 120 Q160 132 148 132 L12 132 Q0 132 0 120 L0 84 Q0 74 10 74 Z"

    /** PlayStation (DualSense, DS4): mirrored about x 890, the touchpad between the clusters; its Home in the notch. */
    private fun symmetric(dualSense: Boolean, h: Home): PadArt {
        val l = 367f; val r = 1413f
        val ctls = mutableListOf(
            btn(ArtId.L2, ThorButton.L2, 592.5f, -130f, 60f, ArtShape.Trigger, left(l, -130f)),
            btn(ArtId.L1, ThorButton.L1, 592.5f, -69f, 60f, ArtShape.Bumper, left(l, -69f)),
            btn(ArtId.R2, ThorButton.R2, 1187.5f, -130f, 60f, ArtShape.Trigger, right(r, -130f)),
            btn(ArtId.R1, ThorButton.R1, 1187.5f, -69f, 60f, ArtShape.Bumper, right(r, -69f)),
            // build 18 (GC11, ps-F11): their names ("Create", "Options") above them, under the body's edge
            btn(ArtId.SELECT, ThorButton.SELECT, 648f, 46f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.SHARE, ArtPt(642f, 4f), 26f), left(l, 46f)),
            btn(ArtId.START, ThorButton.START, 1132f, 46f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.LINES, ArtPt(1138f, 4f), 26f), right(r, 46f)),
            ArtCtl(ArtId.DPAD, ArtSrc.Dpad, 592.5f, 185f, 100f, ArtShape.Cross(28f, 92f, 100f, 110f, true), left(l, 185f)),
        )
        // □'s tag goes inward, between the touchpad and the sticks (as the Thor's Y)
        ctls += face(1187.5f, 185f, 76f, 36f, 48f, 40f, right(r, 109f), left(1059.5f, 185f, maxW = 320f), right(r, 185f), right(r, 261f))
        ctls += btn(ArtId.L3, ThorButton.L3, 745.5f, 345f, 100f, ArtShape.Stick(100f, 68f, 82f), left(l, 345f))
        ctls += btn(ArtId.R3, ThorButton.R3, 1034.5f, 345f, 100f, ArtShape.Stick(100f, 68f, 82f), right(r, 345f))
        // build 18 (GC11, gd-D19 / ps-F11): the touchpad and Mute named when they are its own Back / AYN (they stay with
        // Wayfinder: drawn dashed) — the names its spares give them ([Vocab.spareName]), never a stand-in's
        val pad = h.back?.takeIf { it == Vocab.spareName(0xFF01, 14) }
        val mute = h.ayn?.takeIf { it == Vocab.spareName(0xFF01, 15) }
        val parts = mutableListOf(
            // stylised lower than the real one, so □'s tag band stays free
            ArtPart(PartKind.TOUCHPAD, 890f, 66f, d = "M718 -8 L1062 -8 Q1080 -8 1078 10 L1068 122 Q1066 140 1048 140 L732 140 Q714 140 712 122 L702 10 Q700 -8 718 -8 Z",
                label = pad, labelAt = ArtPt(890f, 66f), labelSize = 30f),
            ArtPart(PartKind.RECESS, 1187.5f, 185f, r = 120f),
        )
        if (dualSense) parts += ArtPart(PartKind.MUTE, 890f, 418f, 44f, 14f, label = mute, labelAt = ArtPt(890f, 440f), labelSize = 22f)
        val notes = mutableListOf<ArtNote>()
        // build 18 (GC11, gd-D19): the note under the body (at 530 the grips' outline crossed it), the inspector below it
        home(h, 890f, 352f, 24f, ArtGlyph.PS, TagSpot(890f, 535f, 0), ArtPt(890f, UNDER_BODY), ctls, parts, notes, homeLabel = ArtPt(890f, 300f))
        return PadArt(ArtKind.SYMMETRIC,
            "M890 -12 L1255 -12 C1330 -12 1372 20 1385 90 C1398 160 1402 260 1392 360 C1380 470 1372 590 1330 640 " +
                "C1300 672 1240 668 1215 640 C1170 590 1130 500 1070 478 C1010 458 950 455 890 455 C830 455 770 458 710 478 " +
                "C650 500 610 590 565 640 C540 668 480 672 450 640 C408 590 400 470 388 360 C378 260 382 160 395 90 " +
                "C408 20 450 -12 525 -12 L890 -12 Z",
            ctls, parts, notes, UNDER_INSPECTOR, ArtBox(370f, -180f, 1040f, 860f), ArtId.A, faceNote = ArtBox(1418f, 385f, 340f, 150f))
    }

    /** Build 18 (GC11, gd-D19): a PlayStation / Xbox / Pro drawing's notes go under the body (its grips end at ~663), its
     *  inspector under them — a note across the grips was struck through by their outline. */
    private const val UNDER_BODY = 690f
    private val UNDER_INSPECTOR = InspectorBox(890f, 782f, 838f, 1480f)

    private enum class Center { XBOX, NINTENDO, GENERIC }

    /** Xbox, the Nintendo Pro, any other pad: the left stick high, the D-pad low; three centres. The elbows ([TagSpot.via])
     *  keep the centre buttons' leaders off the sticks and the face cluster. */
    private fun offset(center: Center, h: Home, gyro: Boolean, capture: Boolean): PadArt {
        val l = 369f; val r = 1411f
        val ctls = mutableListOf(
            btn(ArtId.L2, ThorButton.L2, 633f, -130f, 60f, ArtShape.Trigger, left(l, -130f)),
            btn(ArtId.L1, ThorButton.L1, 633f, -69f, 60f, ArtShape.Bumper, left(l, -69f)),
            btn(ArtId.R2, ThorButton.R2, 1158f, -130f, 60f, ArtShape.Trigger, right(r, -130f)),
            btn(ArtId.R1, ThorButton.R1, 1158f, -69f, 60f, ArtShape.Bumper, right(r, -69f)),
            btn(ArtId.L3, ThorButton.L3, 633f, 205f, 100f, ArtShape.Stick(100f, 68f, 82f), left(l, 205f)),
        )
        // the left face button's tag goes inward, stopping before the left stick
        ctls += face(1158f, 190f, 76f, 36f, 48f, 40f, right(r, 114f), left(1030f, 190f, maxW = 270f), right(r, 190f), right(r, 266f))
        ctls += ArtCtl(ArtId.DPAD, ArtSrc.Dpad, 770f, 385f, 100f, ArtShape.Cross(28f, 92f, 100f, 110f, true), left(l, 385f))
        ctls += btn(ArtId.R3, ThorButton.R3, 1018f, 390f, 100f, ArtShape.Stick(100f, 68f, 82f), right(r, 390f))
        val parts = mutableListOf(ArtPart(PartKind.RECESS, 1158f, 190f, r = 120f))
        val notes = mutableListOf<ArtNote>()
        // build 18 (GC11, gd-D19): under the body, motion pill or not (between the grips the outline crossed a long note)
        val note = ArtPt(890f, UNDER_BODY)
        if (center == Center.NINTENDO) {
            ctls += btn(ArtId.SELECT, ThorButton.SELECT, 790f, 90f, 30f, ArtShape.Small(22f, 34f, ArtGlyph.MINUS), left(l, 46f, ArtPt(790f, 46f)))
            ctls += btn(ArtId.START, ThorButton.START, 990f, 90f, 30f, ArtShape.Small(22f, 34f, ArtGlyph.PLUS), right(r, 51f, ArtPt(990f, 51f)))
            if (capture) parts += ArtPart(PartKind.CAPTURE, 845f, 135f, 30f, 30f)
            home(h, 935f, 135f, 24f, ArtGlyph.HOUSE, right(r, -9f, ArtPt(935f, -9f)), note, ctls, parts, notes)
        } else {
            val xbox = center == Center.XBOX
            ctls += btn(ArtId.SELECT, ThorButton.SELECT, 806f, 250f, 30f, ArtShape.Small(22f, 34f, if (xbox) ArtGlyph.VIEW else ArtGlyph.SQUARE), left(l, 82f, ArtPt(806f, 82f)))
            ctls += btn(ArtId.START, ThorButton.START, 974f, 250f, 30f, ArtShape.Small(22f, 34f, if (xbox) ArtGlyph.LINES else ArtGlyph.TRIANGLE), right(r, 20f, ArtPt(974f, 20f)))
            home(h, 890f, 100f, if (xbox) 34f else 28f, if (xbox) ArtGlyph.XBOX else ArtGlyph.HOUSE, left(l, 20f, ArtPt(890f, 20f)), note, ctls, parts, notes)
        }
        // the motion pill in the notch between the grips
        if (gyro) ctls += gyroCtl(ArtSrc.PadGyro, 890f, 580f)
        return PadArt(ArtKind.OFFSET,
            "M890 0 C1010 0 1110 -18 1210 -18 C1300 -18 1365 30 1385 110 C1405 200 1400 300 1385 380 C1365 480 1350 590 1300 635 " +
                "C1260 668 1195 665 1170 630 C1135 585 1110 535 1040 520 C980 510 940 515 890 515 C840 515 800 510 740 520 " +
                "C670 535 645 585 610 630 C585 665 520 668 480 635 C430 590 415 480 395 380 C380 300 375 200 395 110 " +
                "C415 30 480 -18 570 -18 C670 -18 770 0 890 0 Z",
            ctls, parts, notes, UNDER_INSPECTOR, ArtBox(370f, -180f, 1040f, 860f), ArtId.A, faceNote = ArtBox(1416f, 430f, 340f, 150f))
    }

    private const val JOYCON_L = "M530 -10 L400 -10 C345 -10 300 25 300 90 L300 590 C300 645 345 690 400 690 L530 690 Q540 690 540 680 L540 0 Q540 -10 530 -10 Z"
    private const val JOYCON_R = "M1250 -10 L1380 -10 C1435 -10 1480 25 1480 90 L1480 590 C1480 645 1435 690 1380 690 L1250 690 Q1240 690 1240 680 L1240 0 Q1240 -10 1250 -10 Z"

    /** Two Joy-Cons — the Thor's arrangement (stick high, D-pad low on the left; face high, stick low on the right), so
     *  the gap is used like the Thor's screen: the inspector and the motion pill. [half] 'L' / 'R': one Joy-Con held
     *  upright, its rail buttons live (WP4's pads; else plain). The pair's rails face each other: wfhid maps nothing. */
    private fun joyconPair(half: Char?, h: Home, gyro: Boolean, parts: Boolean, kind: ControllerKind): PadArt {
        val ctls = mutableListOf<ArtCtl>()
        val decor = mutableListOf<ArtPart>()
        val notes = mutableListOf<ArtNote>()
        fun rail(id: ArtId, code: Int, x: Float, y: Float, tag: TagSpot) {
            if (half != null && parts) ctls += ArtCtl(id, ArtSrc.Part(code), x, y, 40f, ArtShape.Rail(16f, 74f), tag)
            else if (kind.rails) decor += ArtPart(PartKind.RAIL, x, y, 16f, 74f)
        }
        if (half != 'R') {
            ctls += btn(ArtId.L2, ThorButton.L2, 420f, -130f, 60f, ArtShape.Trigger, right(535f, -130f))
            ctls += btn(ArtId.L1, ThorButton.L1, 420f, -69f, 60f, ArtShape.Bumper, right(535f, -69f))
            ctls += btn(ArtId.SELECT, ThorButton.SELECT, 500f, 40f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.MINUS), right(532f, 40f))
            ctls += btn(ArtId.L3, ThorButton.L3, 420f, 160f, 92f, ArtShape.Stick(92f, 64f, 78f), right(556f, 160f, maxW = 320f))
            ctls += ArtCtl(ArtId.DPAD, ArtSrc.Dpad, 420f, 390f, 100f, ArtShape.Four(64f, 28f, 104f), right(556f, 390f))
            // dev build 17 (D4): a lone left Joy-Con's Capture is its Home (wfhid); the pair's stays Capture (its Back)
            if (half == 'L') home(h, 470f, 540f, 24f, ArtGlyph.CAPTURE, right(572f, 540f), ArtPt(890f, JOYCON_NOTE), ctls, decor, notes)
            else if (kind.capture) decor += ArtPart(PartKind.CAPTURE, 470f, 540f, 30f, 30f)
            rail(ArtId.SL, 0x132, 540f, 110f, right(572f, 100f))
            rail(ArtId.SR, 0x135, 540f, 610f, right(572f, 610f))
        }
        if (half != 'L') {
            ctls += btn(ArtId.R2, ThorButton.R2, 1360f, -130f, 60f, ArtShape.Trigger, left(1245f, -130f))
            ctls += btn(ArtId.R1, ThorButton.R1, 1360f, -69f, 60f, ArtShape.Bumper, left(1245f, -69f))
            ctls += btn(ArtId.START, ThorButton.START, 1280f, 40f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.PLUS), left(1248f, 40f))
            // Y's tag into the gap, clear of the rail
            ctls += face(1360f, 140f, 64f, 28f, 40f, 34f, right(1504f, 76f, maxW = 260f), left(1224f, 160f, maxW = 320f),
                right(1504f, 140f, maxW = 260f), right(1504f, 204f, maxW = 260f))
            ctls += btn(ArtId.R3, ThorButton.R3, 1360f, 370f, 92f, ArtShape.Stick(92f, 64f, 78f), right(1504f, 370f, maxW = 260f))
            rail(ArtId.SL, 0x132, 1240f, 610f, left(1208f, 610f))
            rail(ArtId.SR, 0x135, 1240f, 110f, left(1208f, 100f))
            home(h, 1300f, 540f, 24f, ArtGlyph.HOUSE, left(1268f, 540f), ArtPt(890f, JOYCON_NOTE), ctls, decor, notes)
        }
        if (gyro) ctls += gyroCtl(ArtSrc.PadGyro, 890f, 460f)
        return PadArt(ArtKind.JOYCON_PAIR, when (half) { 'L' -> JOYCON_L; 'R' -> JOYCON_R; else -> "$JOYCON_L $JOYCON_R" },
            ctls, decor, notes, InspectorBox(890f, 270f, 340f, 660f),
            when (half) { 'L' -> ArtBox(280f, -180f, 290f, 890f); 'R' -> ArtBox(1210f, -180f, 290f, 890f); else -> ArtBox(280f, -180f, 1220f, 890f) },
            if (half == 'L') ArtId.DPAD else ArtId.A, faceNote = ArtBox(1505f, 420f, 260f, 150f))
    }

    /** Build 18 (GC11): a Joy-Con drawing's notes under its bodies (they end at 690: a long note sat on their bottom edge). */
    private const val JOYCON_NOTE = 724f

    /** One Joy-Con held sideways (the rail up): positions as wfhid turns it (hx_map_sideways — the right one clockwise,
     *  the left one anticlockwise); SL / SR are its L1 / R1. Its face buttons carry no letter (the canvas: look.sideways). */
    private fun joyconSingle(isLeft: Boolean, h: Home, gyro: Boolean): PadArt {
        val l = 366f; val r = 1414f
        val stx = if (isLeft) 635f else 880f
        val fx = if (isLeft) 929f else 1145f
        val ctls = mutableListOf(
            btn(ArtId.L1, ThorButton.L1, 606f, -69f, 60f, ArtShape.Bumper, left(l, -69f)),
            btn(ArtId.R1, ThorButton.R1, 1194f, -69f, 60f, ArtShape.Bumper, right(r, -69f)),
            btn(ArtId.L3, ThorButton.L3, stx, 176f, 96f, ArtShape.Stick(96f, 66f, 80f), left(l, 176f)),
        )
        ctls += if (isLeft) face(fx, 176f, 70f, 32f, 44f, 36f, right(r, 51f, ArtPt(929f, 51f)), right(r, -9f, ArtPt(859f, -9f)), right(r, 176f), right(r, 246f))
            else face(fx, 176f, 70f, 32f, 44f, 36f, right(r, 111f), right(r, -9f, ArtPt(1075f, -9f)), right(r, 176f), right(r, 246f))
        // its + (right) / − (left) is its Start
        ctls += if (isLeft) btn(ArtId.START, ThorButton.START, 488f, 78f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.MINUS), left(l, 78f))
            else btn(ArtId.START, ThorButton.START, 1292f, 78f, 30f, ArtShape.Small(20f, 32f, ArtGlyph.PLUS), right(r, 51f))
        val parts = mutableListOf(ArtPart(PartKind.RECESS, fx, 176f, r = 112f))
        val notes = mutableListOf<ArtNote>()
        // its Home (right) / Capture (left: wfhid's sideways Home)
        if (isLeft) home(h, 1125f, 98f, 24f, ArtGlyph.CAPTURE, right(r, 111f), ArtPt(890f, 400f), ctls, parts, notes)
        else home(h, 674f, 98f, 24f, ArtGlyph.HOUSE, left(l, 98f), ArtPt(890f, 400f), ctls, parts, notes)
        if (gyro) ctls += gyroCtl(ArtSrc.PadGyro, 890f, 480f)
        return PadArt(ArtKind.JOYCON_SINGLE,
            "M400 0 L1380 0 Q1390 0 1390 10 L1390 253 C1390 308 1345 353 1290 353 L490 353 C435 353 390 308 390 253 L390 10 Q390 0 400 0 Z",
            ctls, parts, notes, InspectorBox(890f, 610f, 680f, 1480f), ArtBox(370f, -110f, 1040f, 480f), ArtId.A,
            faceNote = ArtBox(1418f, 300f, 340f, 150f))
    }
}
