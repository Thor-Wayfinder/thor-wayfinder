package app.wayfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wayfinder.lights.LightMode
import app.wayfinder.lights.LightProfile
import app.wayfinder.lights.LightSettings
import app.wayfinder.lights.StickLights
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader

// Pure, LED-friendly colours (screen-style tints wash out to pastel on the rings).
private val PALETTE = listOf(
    0xFFFF0000, 0xFFFF7000, 0xFFFFD000, 0xFF00FF00, 0xFF00FFA0,
    0xFF00E0FF, 0xFF0000FF, 0xFF8000FF, 0xFFFF0080, 0xFFFFFFFF,
).map { it.toInt() }

private val COLOURED = setOf(LightMode.STATIC, LightMode.BREATHING, LightMode.STROBE)
private val ANIMATED = setOf(LightMode.BREATHING, LightMode.STROBE, LightMode.SPECTRUM)

fun lightsSummary(): String = "Effect: " + LightSettings.global.mode.label

/**
 * Stick lights. [pkg] null = the global setting; otherwise that app's own
 * lights (or "use the global setting"). Every change shows on the sticks right away;
 * leaving the page hands the lights back to whatever app has the controller.
 */
@Composable
fun LightsPage(myDisplayId: Int, pkg: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    LightSettings.init(ctx)
    AppConfigStore.init(ctx)
    val appLabel = remember(pkg) {
        pkg?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: p }
    }
    val stored = if (pkg == null) LightSettings.global else AppConfigStore.get(pkg).lights
    var useGlobal by remember(pkg) { mutableStateOf(pkg != null && stored == null) }
    var p by remember(pkg) { mutableStateOf(stored ?: LightSettings.global) }
    var sameColour by remember(pkg) { mutableStateOf(p.left == p.right) }
    var editSide by remember(pkg) { mutableStateOf(0) }

    fun save(np: LightProfile) {
        p = np
        if (pkg == null) LightSettings.setGlobalProfile(np)
        else AppConfigStore.update(pkg) { it.copy(lights = if (useGlobal) null else np) }
        StickLights.apply(ctx, if (useGlobal) LightSettings.global else np)   // live preview
    }
    DisposableEffect(pkg) { onDispose { ForegroundAppService.reapplyLights() } }

    SubPage(myDisplayId, if (appLabel != null) "Stick lights · $appLabel" else "Stick lights",
        if (pkg == null) "The rings around the sticks — for every app"
        else "While $appLabel has the controller", onBack) {
        if (!StickLights.available) SettingCard("No stick lights found", "This device doesn't expose the Thor's stick LEDs.")
        if (pkg == null) Text("One app its own lights (Screen colour too): App profiles, then the app, then “Stick lights”.",
            color = g.textTertiary, style = MaterialTheme.typography.bodySmall)

        if (pkg != null) SettingCard(
            "Use my usual lights", if (useGlobal) "Same as everywhere else" else "$appLabel has its own lights",
            checked = useGlobal, onChecked = { on ->
                useGlobal = on
                AppConfigStore.update(pkg) { it.copy(lights = if (on) null else p) }
                StickLights.apply(ctx, if (on) LightSettings.global else p)
            },
        )

        if (!useGlobal) {
            SectionHeader("Effect")
            for (row in LightMode.values().toList().chunked(4)) ModeRow(row, p.mode) { save(p.copy(mode = it)) }

            if (p.mode in COLOURED) {
                SettingCard("Same colour on both sticks", if (sameColour) "One colour" else "Left and right separately",
                    checked = sameColour, onChecked = { on -> sameColour = on; if (on) save(p.copy(right = p.left)) })
                if (!sameColour) GlassSegmentedControl(listOf("Left stick", "Right stick"), editSide, Modifier.fillMaxWidth()) { editSide = it }
                val current = if (sameColour || editSide == 0) p.left else p.right
                val setColour: (Int) -> Unit = { c ->
                    save(if (sameColour) p.copy(left = c, right = c) else if (editSide == 0) p.copy(left = c) else p.copy(right = c))
                }
                SectionHeader("Colour — pick one, or A on the wheel to steer it with the stick")
                SwatchRow(current, setColour)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    app.wayfinder.ui.ColorWheel(current, 240.dp, setColour)
                }
            }
            if (p.mode in ANIMATED) {
                // Log scale: 0.25× … 4× with 1× in the middle, so both ends are easy to dial in.
                SectionHeader("Speed · " + String.format("%.2f", p.speed).trimEnd('0').trimEnd('.') + "×  (left slower, right faster)")
                val pos = (kotlin.math.ln(p.speed / 0.25f) / kotlin.math.ln(16f)).coerceIn(0f, 1f)
                app.wayfinder.ui.GlassSlider(pos, step = 0.04f) { v ->
                    save(p.copy(speed = (0.25f * Math.pow(16.0, v.toDouble()).toFloat())))
                }
            }
            if (p.mode != LightMode.AYN && p.mode != LightMode.OFF) {
                SectionHeader("Brightness · ${(p.brightness * 100).toInt()} %")
                app.wayfinder.ui.GlassSlider(p.brightness) { v -> save(p.copy(brightness = v.coerceAtLeast(0.01f))) }
            }
            if (p.mode == LightMode.SCREEN) {
                // 1.3 (GitHub #15): like BiFrost — each ring its side of the screen
                SettingCard("Each stick its own side", if (p.split) "Left ring = the left half of the screen, right ring = the right half"
                    else "Both rings show the whole screen's main colour", checked = p.split, onChecked = { save(p.copy(split = it)) })
                // 1.3 (GitHub #33): a DS game's bottom screen is menus — keep the colours on the top one
                SettingCard("Colour from", when (p.screenFrom) {
                    1 -> "Always the top screen"
                    2 -> "Always the bottom screen"
                    else -> "The screen with the controller (a touch on the other screen moves it)"
                }) {
                    app.wayfinder.ui.GlassSegmentedControl(listOf("Controller's screen", "Top screen", "Bottom screen"), p.screenFrom,
                        Modifier.fillMaxWidth()) { save(p.copy(screenFrom = it)) }
                }
                Text("The rings follow the colours of the game's screen, a few times a second.",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModeRow(modes: List<LightMode>, selected: LightMode, onPick: (LightMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in modes) ModeChip(m, m == selected, Modifier.weight(1f)) { onPick(m) }
        repeat(4 - modes.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ModeChip(m: LightMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, modifier = modifier, radius = 16.dp) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (selected) g.accent.copy(alpha = 0.85f) else Color.Transparent)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(m.label, color = if (selected) Color.White else g.textPrimary, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun SwatchRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (c in PALETTE) Swatch(c, c == selected) { onPick(c) }
    }
}

@Composable
private fun Swatch(c: Int, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    FocusableGlass(onClick = onClick, radius = 24.dp) {
        Box(Modifier.padding(6.dp).size(36.dp).background(Color(c), CircleShape)
            .then(if (selected) Modifier.border(3.dp, g.textPrimary, CircleShape) else Modifier))
    }
}
