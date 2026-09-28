package app.wayfinder

import androidx.compose.foundation.verticalScroll

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.LocalGlass
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Round 8 (2026-09-25) — "Test the controller": what the pad really sends
 * (Wayfinder's own screens get the raw copy, no profile), each stick's drift at rest in %, a
 * suggested deadzone applied to every game in one press, the triggers' travel and every button.
 * While it's open the pad feeds this page ([PadTest.active]): A / B / the D-pad don't navigate.
 * The Back button (Wayfinder's) leaves.
 */
object PadTest {
    @Volatile var active = false
    var lx by mutableFloatStateOf(0f); var ly by mutableFloatStateOf(0f)
    var rx by mutableFloatStateOf(0f); var ry by mutableFloatStateOf(0f)
    var l2 by mutableFloatStateOf(0f); var r2 by mutableFloatStateOf(0f)
    var hx by mutableFloatStateOf(0f); var hy by mutableFloatStateOf(0f)
    val down = mutableStateListOf<ThorButton>()

    /** From MainActivity: true = consumed. */
    /** Start / Select on this page: "Use the suggestion" / "Remove" (its buttons can't take focus:
     *  the pad feeds the page — review 2026-09-25). */
    var onStart: (() -> Unit)? = null
    var onSelect: (() -> Unit)? = null

    fun onMotion(e: MotionEvent): Boolean {
        if (!active || e.source and android.view.InputDevice.SOURCE_JOYSTICK != android.view.InputDevice.SOURCE_JOYSTICK) return false
        lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y)
        rx = e.getAxisValue(MotionEvent.AXIS_Z); ry = e.getAxisValue(MotionEvent.AXIS_RZ)
        l2 = maxOf(e.getAxisValue(MotionEvent.AXIS_BRAKE), e.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        r2 = maxOf(e.getAxisValue(MotionEvent.AXIS_GAS), e.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        return true
    }
    fun onKey(e: KeyEvent): Boolean {
        if (!active || e.keyCode == KeyEvent.KEYCODE_BACK) return false          // Back leaves the page
        val b = ButtonEngine.printedButton(e) ?: return KeyEvent.isGamepadButton(e.keyCode)
        if (b.isSystem) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (b !in down) down.add(b)
            if (e.repeatCount == 0) when (b) { ThorButton.START -> onStart?.invoke(); ThorButton.SELECT -> onSelect?.invoke(); else -> {} }
        } else down.remove(b)
        return true
    }
}

@Composable
fun ControllerTestPage(myDisplayId: Int, onBack: () -> Unit) {
    val g = LocalGlass.current
    // start from centre: a stick at rest sends nothing, so values from the last visit would read as drift
    DisposableEffect(Unit) {
        PadTest.lx = 0f; PadTest.ly = 0f; PadTest.rx = 0f; PadTest.ry = 0f; PadTest.l2 = 0f; PadTest.r2 = 0f; PadTest.hx = 0f; PadTest.hy = 0f
        PadTest.active = true; PadTest.down.clear()
        onDispose { PadTest.active = false; PadTest.onStart = null; PadTest.onSelect = null }
    }
    // drift: the largest distance from centre over the last 3 s while the stick is "at rest" (< 30 %)
    val histL = remember { ArrayDeque<Pair<Long, Float>>() }
    val histR = remember { ArrayDeque<Pair<Long, Float>>() }
    var driftL by remember { mutableStateOf<Float?>(null) }
    var driftR by remember { mutableStateOf<Float?>(null) }
    var l2Max by remember { mutableFloatStateOf(0f) }
    var r2Max by remember { mutableFloatStateOf(0f) }
    // a reading only once the stick was pushed (> 30 %) and let go — at rest a stick sends nothing, so
    // before that "0 %" meant "not measured" and the page offered to REMOVE a deadzone (review 2026-09-25)
    val movedL = remember { booleanArrayOf(false) }
    val movedR = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            fun sample(h: ArrayDeque<Pair<Long, Float>>, moved: BooleanArray, x: Float, y: Float): Float? {
                val r = sqrt(x * x + y * y)
                if (r >= 0.3f) { moved[0] = true; h.clear(); return null }      // moved on purpose: start again
                if (!moved[0]) return null
                h.addLast(now to r)
                while (h.isNotEmpty() && now - h.first().first > 3000) h.removeFirst()
                return if (h.size >= 55 && now - h.first().first >= 2700) h.maxOf { it.second } * 100f else null
            }
            driftL = sample(histL, movedL, PadTest.lx, PadTest.ly)
            driftR = sample(histR, movedR, PadTest.rx, PadTest.ry)
            l2Max = maxOf(l2Max, PadTest.l2); r2Max = maxOf(r2Max, PadTest.r2)
            delay(50)
        }
    }
    fun suggest(d: Float?) = d?.let { if (it < 1.5f) 0 else listOf(4, 6, 8, 10, 12, 15, 20, 25).firstOrNull { s -> s >= ceil(it * 1.5f) + 2 } ?: 25 }
    val sugL = suggest(driftL); val sugR = suggest(driftR)
    val defaults = AppSettings.stickDefaults
    val canUse = sugL != null && sugR != null && (sugL != defaults.stickL.dead || sugR != defaults.stickR.dead)
    val canRemove = !defaults.stickL.isDefault || !defaults.stickR.isDefault
    fun use() { if (sugL != null && sugR != null) AppSettings.setStickDefaultsTo(defaults.copy(stickL = defaults.stickL.copy(dead = sugL), stickR = defaults.stickR.copy(dead = sugR))) }
    fun remove() = AppSettings.setStickDefaultsTo(defaults.copy(stickL = StickShape(), stickR = StickShape()))
    PadTest.onStart = if (canUse) ::use else null
    PadTest.onSelect = if (canRemove) ::remove else null

    app.wayfinder.ui.GlassScreen(span = if (myDisplayId == 0) app.wayfinder.ui.AuroraSpan.TOP else app.wayfinder.ui.AuroraSpan.BOTTOM) {
        // scrolls: its last line was cut off at the bottom of the Thor's top screen (1.3 pass)
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FocusableGlass(onClick = onBack, radius = 16.dp) {
                    Text("‹ Back", color = g.textPrimary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Test the controller", color = g.textPrimary, style = MaterialTheme.typography.headlineSmall)
                    Text("Move both sticks a little, let go, and leave them alone for 3 seconds: that measures drift. " +
                        "The buttons light up below; the Back button (or touch) leaves.", color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                StickCard("Left stick", PadTest.lx, PadTest.ly, driftL, defaults.stickL, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TriggerBar("L2", PadTest.l2, l2Max)
                    TriggerBar("R2", PadTest.r2, r2Max)
                    ButtonsGrid()
                }
                StickCard("Right stick", PadTest.rx, PadTest.ry, driftR, defaults.stickR, Modifier.weight(1f))
            }
            // the fix, for every game (a game's own Game controls values still win)
            GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Deadzone for every game: left ${defaults.stickL.dead} %, right ${defaults.stickR.dead} %",
                            color = g.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            sugL == null || sugR == null -> "Measuring — move both sticks a little, let go, and leave them alone…"
                            sugL == 0 && sugR == 0 -> "No drift worth fixing: both sticks rest at the centre."
                            else -> "Suggested: left $sugL %, right $sugR % — just enough to hide the drift."
                        }, color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canUse) FocusableGlass(onClick = ::use, radius = 14.dp) {
                        Text("Use the suggestion · Start", color = g.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                    if (canRemove) FocusableGlass(onClick = ::remove, radius = 14.dp) {
                        Text("Remove · Select", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
            Text("This page shows the raw controller. What a game gets (deadzone, remaps…) is set per game in Game controls (Home + ${ThorButton.X.label}).",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StickCard(name: String, x: Float, y: Float, drift: Float?, shape: StickShape, modifier: Modifier) {
    val g = LocalGlass.current
    val dead = shape.dead
    // what a game gets: the same maths as the engine's wf_shape (fx/wfmap.h), with the all-games shape
    val (gx, gy) = shaped(x, y, shape)
    val gameColour = g.textPrimary
    val accent = g.accent; val ring = g.textTertiary; val warn = androidx.compose.ui.graphics.Color(0xFFE0A63A)
    GlassPanel(modifier, radius = 18.dp) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = g.textPrimary, style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.size(170.dp)) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 - 6f
                drawCircle(ring, r, c, style = Stroke(3f))
                if (dead > 0) drawCircle(warn.copy(alpha = 0.35f), r * dead / 100f, c)          // the deadzone now
                drawLine(ring.copy(alpha = 0.4f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
                drawLine(ring.copy(alpha = 0.4f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
                drawCircle(ring, 11f, Offset(c.x + x.coerceIn(-1f, 1f) * r, c.y + y.coerceIn(-1f, 1f) * r))       // raw
                drawCircle(accent, 9f, Offset(c.x + gx.coerceIn(-1f, 1f) * r, c.y + gy.coerceIn(-1f, 1f) * r))   // the game
            }
            Text("Raw ${"%.0f".format(sqrt(x * x + y * y) * 100)} %  →  game gets ${"%.0f".format(sqrt(gx * gx + gy * gy) * 100)} %" +
                (if (dead > 0) "  (deadzone $dead %)" else ""), color = gameColour, style = MaterialTheme.typography.bodySmall)
            Text("Grey: the stick · blue: what a game gets", color = g.textTertiary, style = MaterialTheme.typography.labelSmall)
            Text(drift?.let { "At rest: ${"%.1f".format(it)} % from centre" } ?: "At rest: move it, let go…",
                color = if ((drift ?: 0f) > 3f) warn else g.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TriggerBar(name: String, v: Float, max: Float) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
        Box(Modifier.weight(1f).height(16.dp).background(g.textTertiary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))) {
            Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(16.dp).background(g.accent, RoundedCornerShape(8.dp)))
        }
        Text("${(v * 100).toInt()} % · max ${(max * 100).toInt()} %", color = g.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp))
    }
}

@Composable
private fun ButtonsGrid() {
    val g = LocalGlass.current
    val rows = listOf(
        listOf(ThorButton.L1, ThorButton.L2, ThorButton.R2, ThorButton.R1),
        listOf(ThorButton.X, ThorButton.Y, ThorButton.A, ThorButton.B),
        listOf(ThorButton.SELECT, ThorButton.START, ThorButton.L3, ThorButton.R3),
    )
    val hat = listOfNotNull(
        "up".takeIf { PadTest.hy < -0.5f }, "down".takeIf { PadTest.hy > 0.5f },
        "left".takeIf { PadTest.hx < -0.5f }, "right".takeIf { PadTest.hx > 0.5f },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in row) {
                val on = b in PadTest.down
                Box(Modifier.weight(1f).height(34.dp).background(if (on) g.accent else g.textTertiary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(b.spoken, color = if (on) androidx.compose.ui.graphics.Color.White else g.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("D-pad: " + (hat.joinToString(" + ").ifEmpty { "—" }) + "   ·   Home and Back are Wayfinder's (hold Home to see its combos)",
            color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

/** A stick position through a shape — a copy of wf_shape (fx/wfmap.h) for the preview. */
internal fun shaped(x: Float, y: Float, s: StickShape): Pair<Float, Float> {
    val r = sqrt(x * x + y * y)
    if (r < 1e-6f || s.isDefault) return x to y
    val lo = s.dead / 100f; val hi = s.full / 100f
    var t = if (r <= lo) 0f else if (r >= hi) 1f else (r - lo) / (hi - lo)
    if (s.curve == 1) t *= t else if (s.curve == 2) t = sqrt(t)
    val k = t / r
    return x * k to y * k
}
