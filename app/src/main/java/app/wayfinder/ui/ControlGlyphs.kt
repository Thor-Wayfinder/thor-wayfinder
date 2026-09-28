package app.wayfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WbTwilight
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.Press
import app.wayfinder.ThorAction
import app.wayfinder.ThorButton
import app.wayfinder.Trigger

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

/** The whole trigger: [modifier] + button, or button + press tag. */
@Composable
fun TriggerGlyphs(t: Trigger, size: Dp = 30.dp, dim: Boolean = false) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.modifier != null) {
            ButtonGlyph(t.modifier, size, dim)
            Text("+", color = g.textTertiary, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.SemiBold)
        }
        ButtonGlyph(t.button, size, dim)
        if (t.modifier == null && t.press != Press.TAP) PressTag(t.press)
    }
}

@Composable
private fun PressTag(p: Press) {
    val g = LocalGlass.current
    val text = when (p) { Press.DOUBLE_TAP -> "×2"; Press.TRIPLE_TAP -> "×3"; Press.HOLD -> "hold"; Press.TAP -> "" }
    Box(
        Modifier.background(g.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, color = g.accent, style = MaterialTheme.typography.labelLarge) }
}

/** One physical button, drawn the way it looks on a controller. */
@Composable
fun ButtonGlyph(b: ThorButton, size: Dp = 30.dp, dim: Boolean = false) {
    val g = LocalGlass.current
    val ink = if (dim) g.textSecondary else g.textPrimary
    val chip = if (g.dark) Color(0x33FFFFFF) else Color(0x1A000000)
    val rim = if (g.dark) Color(0x66FFFFFF) else Color(0x40000000)
    when {
        b in FACE -> {
            val shown = app.wayfinder.ButtonNames.shown(b)     // 1.3 (GitHub #27): named the Xbox way if chosen
            val c = FACE.getValue(shown)
            Box(
                Modifier.size(size).background(c.copy(alpha = if (dim) 0.18f else 0.26f), CircleShape).border(1.5.dp, c, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(shown.printed, color = c, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold) }
        }
        b == ThorButton.L1 || b == ThorButton.R1 || b == ThorButton.L2 || b == ThorButton.R2 -> {
            val shape = RoundedCornerShape(topStart = size * 0.45f, topEnd = size * 0.45f, bottomStart = 6.dp, bottomEnd = 6.dp)
            Box(
                Modifier.height(size).widthIn(min = size * 1.35f).background(chip, shape).border(1.dp, rim, shape).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(b.label, color = ink, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold) }
        }
        b == ThorButton.L3 || b == ThorButton.R3 -> {
            // A stick seen from above: outer ring + inner cap + "L"/"R".
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) {
                    drawCircle(chip, radius = this.size.minDimension / 2f)
                    drawCircle(rim, radius = this.size.minDimension / 2f - 1f, style = Stroke(1.5f))
                    drawCircle(rim, radius = this.size.minDimension * 0.28f, style = Stroke(2f))
                }
                Text(b.label.take(1), color = ink, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
            }
        }
        b.isDpad -> DpadGlyph(b, size, ink, chip, rim, g.accent)
        b.isFlick -> {
            // The stick seen from above (like L3/R3) with the flick's direction on the rim.
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) {
                    drawCircle(chip, radius = this.size.minDimension / 2f)
                    drawCircle(rim, radius = this.size.minDimension / 2f - 1f, style = Stroke(1.5f))
                }
                Text(b.label.take(1) + " " + b.label.takeLast(1), color = ink,
                    fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold)
            }
        }
        else -> {
            // Round system buttons, printed the way the Thor prints them:
            // Home = house, Back = counter-clockwise looping arrow,
            // Start = right-pointing triangle, Select = square.
            val icon = when (b) {
                ThorButton.HOME -> Icons.Rounded.Home
                ThorButton.BACK -> Icons.Rounded.Replay
                ThorButton.START -> Icons.Rounded.PlayArrow
                else -> Icons.Rounded.CropSquare   // SELECT
            }
            Box(
                Modifier.size(size).background(chip, CircleShape).border(1.dp, rim, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, b.label, tint = ink, modifier = Modifier.size(size * 0.6f)) }
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

/** An icon for each action (cheat sheet / Controls). */
fun actionIcon(a: ThorAction): ImageVector = when (a) {
    ThorAction.SWAP_OR_SEND -> Icons.Rounded.Bolt
    ThorAction.CLEAR_BACKGROUND -> Icons.Rounded.DeleteSweep
    ThorAction.CLOSE_APP -> Icons.Rounded.Close
    ThorAction.RECENTS -> Icons.Rounded.GridView
    ThorAction.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    ThorAction.SCREENSHOT -> Icons.Rounded.PhotoCamera
    ThorAction.TOGGLE_SECOND_SCREEN -> Icons.Rounded.DarkMode
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
    ThorAction.FPS_COUNTER -> Icons.Rounded.Speed
    ThorAction.GAME_CONTROLS -> Icons.Rounded.Tune
    ThorAction.OPEN -> Icons.Rounded.Apps
}

/** Three fingertips — the blank gesture's badge. */
@Composable
fun ThreeFingerGlyph(size: Dp = 30.dp, swipe: Boolean = false) {
    val g = LocalGlass.current
    val ink = g.textPrimary
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            val r = s * 0.13f
            listOf(0.22f, 0.5f, 0.78f).forEachIndexed { i, fx ->
                drawCircle(ink.copy(alpha = 0.85f), radius = r, center = Offset(s * fx, s * (if (i == 1) 0.34f else 0.42f)))
            }
        }
        if (swipe) Icon(Icons.Rounded.South, null, tint = g.accent, modifier = Modifier.size(size * 0.45f).align(Alignment.BottomEnd))
    }
}
