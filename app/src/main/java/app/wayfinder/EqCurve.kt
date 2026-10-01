package app.wayfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.LocalGlass

/**
 * 1.4 (Reddit): the headphone EQ as a curve you shape — ten points an octave apart (31 Hz … 16 kHz, ±12 dB),
 * drawn as the smooth curve the sound actually gets ([HeadphoneEq.curve]). Touch: drag a point up or down (the
 * nearest one to the finger). Controller: A starts editing, left / right picks a point, up / down moves it 1 dB,
 * A or B stops. A preset shows its curve too; shaping it makes it your Custom one.
 */
@Composable
fun EqCurve(out: HeadphoneEq.Out, gains: List<Float>) {
    val g = LocalGlass.current
    val n = HeadphoneEq.FREQS.size
    var sel by remember { mutableIntStateOf(-1) }        // the point being moved (touch or controller)
    var editing by remember { mutableStateOf(false) }   // controller editing
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(gains)
    fun set(i: Int, db: Float) {
        val v = Math.round(db.coerceIn(-12f, 12f)).toFloat()
        if (latest[i] != v) HeadphoneEq.edit(out, i, v)
    }
    fun label(f: Int) = if (f >= 1000) "${f / 1000}k" else "$f"
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val scaleStyle = MaterialTheme.typography.labelSmall.copy(color = g.textTertiary)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            sel >= 0 -> HeadphoneEq.FREQS[sel].let { f -> if (f >= 1000) "${f / 1000} kHz" else "$f Hz" } + "  ·  " + (if (gains[sel] > 0) "+" else "") + "${gains[sel].toInt()} dB" +
                if (editing) "   —  ◀ ▶ another point · ▲ ▼ move it · ${ButtonNames.m("A")} done" else ""
            focused -> "${ButtonNames.m("A")} to shape the curve with the D-pad"
            else -> "Drag a point up for more, down for less — bass on the left, treble on the right"
        }, color = if (sel >= 0) g.accent else g.textTertiary, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(220.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) g.accent else g.textTertiary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) { editing = false; sel = -1 } }
            .onKeyEvent { e ->
                val select = e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter
                if (select || (editing && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape))) {
                    if (e.type == KeyEventType.KeyUp) {
                        editing = select && !editing
                        sel = if (editing) (if (sel >= 0) sel else 2) else -1
                    }
                    return@onKeyEvent true
                }
                if (!editing || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { sel = (sel - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { sel = (sel + 1).coerceAtMost(n - 1); true }
                    Key.DirectionUp -> { set(sel, latest[sel] + 1f); true }
                    Key.DirectionDown -> { set(sel, latest[sel] - 1f); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(out) {
                val padY = 18.dp.toPx()
                fun dbAt(y: Float) = 12f - (y - padY) / (size.height - 2 * padY) * 24f
                val grab = 34.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val i = (down.position.x / (size.width - 64.dp.toPx()) * n).toInt().coerceIn(0, n - 1)
                    // only a finger on (or near) a point takes it — anywhere else, the swipe scrolls the page
                    val py = padY + (12f - latest[i]) / 24f * (size.height - 2 * padY)
                    if (kotlin.math.abs(down.position.y - py) > grab) return@awaitEachGesture
                    sel = i; down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        set(i, dbAt(c.position.y)); c.consume()
                    }
                    if (!editing) sel = -1
                }
            }) {
            Canvas(Modifier.matchParentSize()) {
                val padY = 18.dp.toPx()
                val h = size.height - 2 * padY
                fun y(db: Float) = padY + (12f - db) / 24f * h
                val plotW = size.width - 64.dp.toPx()                // the dB scale has its own margin on the right
                fun x(t: Float) = (t + 0.5f) / n * plotW             // t in points (0 … n-1)
                // the grid: 0 dB solid, ±6 / ±12 dashed, a line per point
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (db in listOf(12f, 6f, -6f, -12f))
                    drawLine(g.textTertiary.copy(alpha = 0.25f), Offset(0f, y(db)), Offset(size.width, y(db)), 1.dp.toPx(), pathEffect = dash)
                drawLine(g.textTertiary.copy(alpha = 0.5f), Offset(0f, y(0f)), Offset(size.width, y(0f)), 1.dp.toPx())
                // the scale, at the right edge (the 16 kHz point sits left of it)
                for (db in listOf(12, 6, 0, -6, -12)) {
                    val t = measurer.measure(if (db > 0) "+$db dB" else if (db == 0) "0 dB" else "−${-db} dB", scaleStyle)
                    drawText(t, topLeft = Offset(size.width - t.size.width - 6.dp.toPx(), y(db.toFloat()) - t.size.height - 1.dp.toPx()))
                }
                for (i in 0 until n) drawLine(g.textTertiary.copy(alpha = 0.12f), Offset(x(i.toFloat()), padY), Offset(x(i.toFloat()), padY + h), 1.dp.toPx())
                // the curve (what the sound gets), filled toward 0 dB
                val steps = 120
                val line = Path(); val fill = Path()
                for (s in 0..steps) {
                    val t = -0.5f + s / steps.toFloat() * n
                    val p = Offset(x(t), y(HeadphoneEq.curve(gains, t)))
                    if (s == 0) { line.moveTo(p.x, p.y); fill.moveTo(p.x, y(0f)); fill.lineTo(p.x, p.y) } else { line.lineTo(p.x, p.y); fill.lineTo(p.x, p.y) }
                }
                fill.lineTo(plotW, y(0f)); fill.close()
                drawPath(fill, g.accent.copy(alpha = 0.16f))
                drawPath(line, g.accent, style = Stroke(3.dp.toPx()))
                // the points
                for (i in 0 until n) {
                    val c = Offset(x(i.toFloat()), y(gains[i]))
                    if (i == sel) drawCircle(g.accent.copy(alpha = 0.3f), 16.dp.toPx(), c)
                    drawCircle(Color.White, (if (i == sel) 9 else 7).dp.toPx(), c)
                    drawCircle(g.accent, (if (i == sel) 9 else 7).dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
                }
            }
        }
        // the frequencies, under their points (one cell each — the points sit in the cells' middles)
        Row(Modifier.fillMaxWidth()) {
            HeadphoneEq.FREQS.forEach { f ->
                Text(label(f), color = g.textTertiary, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(64.dp))
        }
    }
}
