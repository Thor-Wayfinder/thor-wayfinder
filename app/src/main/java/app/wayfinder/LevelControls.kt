package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.GlassSlider
import app.wayfinder.ui.LocalGlass
import kotlinx.coroutines.delay

/**
 * Brightness and volume, the same way everywhere (Screens, quick panel):
 * three sliders each — Both screens, Top, Bottom. "Both" keeps the difference between the
 * screens; Top / Bottom set it. The volume buttons and Home + R2 / L2 act like "Both".
 */
@Composable
fun BrightnessSliders(compact: Boolean = false, collapsible: Boolean = compact) {
    val ctx = LocalContext.current
    // The Thor's two built-in screens, from Android itself (the service's list is empty until
    // it runs — a fresh install showed no "Bottom" row).
    val ids = remember {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        val second = (ForegroundAppService.availableDisplayIds.filter { it != 0 } +
            dm.displays.filter { it.displayId != 0 && it.name.orEmpty().equals("Screen-2", true) }.map { it.displayId } +
            dm.displays.filter { it.displayId != 0 && (it.flags and android.view.Display.FLAG_PRIVATE) == 0 }.map { it.displayId })
            .firstOrNull()
        listOfNotNull(0, second)
    }
    var levels by remember { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    LaunchedEffect(Unit) { ScreenLevels.readAsync(ctx, ids) { levels = it } }
    // Follow changes made elsewhere (Home + R2/L2, the other slider set) the moment they land.
    val changed = ScreenLevels.changes.intValue
    LaunchedEffect(changed) { if (changed > 0) levels = levels + ids.mapNotNull { d -> ScreenLevels.known(d)?.let { d to it } } }
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 2.dp)) {
        val ready = levels.size == ids.size
        LevelRow(if (compact) null else Icons.Rounded.LightMode, if (compact) "Both" else "Both screens", compact = compact, value = if (ready) "${Math.round(ScreenLevels.snap(ScreenLevels.bothPos(levels)) * 100)} %" else null,
            pos = if (ready) ScreenLevels.bothPos(levels) else null,
            trailing = if (collapsible) ({ ExpandButton(open) { open = !open } }) else null) { p ->
            levels = ScreenLevels.shiftBoth(ctx, levels, p - ScreenLevels.bothPos(levels))
        }
        if (open) for (d in ids) LevelRow(null, name(d == 0, compact), compact = compact, value =
            levels[d]?.let { "${ScreenLevels.percent(it)} %" }, pos = levels[d]?.let { ScreenLevels.toSlider(it) },
            trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val v = ScreenLevels.fromSlider(p); levels = levels + (d to v); ScreenLevels.setAsync(ctx, d, v)
        }
    }
}

@Composable
fun VolumeSliders(compact: Boolean = false, collapsible: Boolean = compact) {
    var top by remember { mutableIntStateOf(LinkedVolume.top()) }
    var bottom by remember { mutableIntStateOf(LinkedVolume.bottom()) }
    // Follow the volume buttons (and anything else) the moment they change — plus a slow
    // re-read in case a change came without a broadcast.
    val changed = LinkedVolume.changes.intValue
    LaunchedEffect(changed) { top = LinkedVolume.top(); bottom = LinkedVolume.bottom() }
    LaunchedEffect(Unit) { while (true) { delay(2000); top = LinkedVolume.top(); bottom = LinkedVolume.bottom() } }
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 2.dp)) {
        LevelRow(if (compact) null else Icons.Rounded.VolumeUp, if (compact) "Both" else "Both screens", compact = compact, value = if (compact) "$top" else "$top / 15", pos = top / 15f, step = 1f / 15f,
            trailing = if (collapsible) ({ ExpandButton(open) { open = !open } }) else null) { p ->
            val t = Math.round(p * 15); LinkedVolume.setBoth(t); top = t; bottom = (t + LinkedVolume.offset).coerceIn(0, 15)
        }
        if (open) LevelRow(null, name(true, compact), compact = compact, value = if (compact) "$top" else "$top / 15", pos = top / 15f, step = 1f / 15f,
            trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val t = Math.round(p * 15); LinkedVolume.setTopOnly(t); top = t
        }
        if (open) LevelRow(null, name(false, compact), compact = compact, value = if (compact) "$bottom" else "$bottom / 15", pos = bottom / 15f, step = 1f / 15f,
            trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val b = Math.round(p * 15); LinkedVolume.setBottomOnly(b); bottom = b
        }
        // 1.3 (GitHub #7): say when the bottom screen's apps play quieter / louder than the top's
        if (!compact && bottom != top) {
            val g = LocalGlass.current
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Apps on the bottom screen play ${if (bottom < top) "quieter" else "louder"} (${bottom} / 15 against ${top} / 15)",
                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                app.wayfinder.ui.FocusableGlass(onClick = { LinkedVolume.setBalance(0); LinkedVolume.setBoth(top); bottom = top }, radius = 12.dp) {
                    Text("Same on both", color = g.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

private fun name(top: Boolean, compact: Boolean) = if (top) (if (compact) "Top" else "Top screen") else (if (compact) "Bottom" else "Bottom screen")

@Composable
private fun LevelRow(icon: ImageVector?, label: String, compact: Boolean, value: String?, pos: Float?, step: Float = 0.05f,
                     trailing: (@Composable () -> Unit)? = null, onChange: (Float) -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        if (!compact) Box(Modifier.size(22.dp)) { if (icon != null) Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp)) }
        Text(label, color = if (label.startsWith("Both")) g.textPrimary else g.textSecondary,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(if (compact) 44.dp else 118.dp))
        val h = if (compact) 30.dp else 36.dp
        if (pos != null) GlassSlider(pos, Modifier.weight(1f), step = step, height = h, onChange = onChange)
        else Box(Modifier.weight(1f).height(h))
        Text(value ?: "…", color = g.textSecondary, style = MaterialTheme.typography.bodySmall.takeIf { compact } ?: MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(if (compact) 34.dp else 64.dp), textAlign = TextAlign.End)
        trailing?.invoke()
    }
}

/** Same width as [ExpandButton], so the per-screen sliders line up with "Both". */
@Composable
private fun ExpandSpace() = Box(Modifier.size(30.dp))

/** Shows / hides the per-screen sliders under the "Both" one (the drawer keeps just "Both"). */
@Composable
private fun ExpandButton(open: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    app.wayfinder.ui.FocusableGlass(onClick = onClick, radius = 12.dp) {
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide top and bottom" else "Top and bottom",
            tint = g.accent, modifier = Modifier.padding(4.dp).size(22.dp))
    }
}

/** Appearance → See-through glass: frost amount and blur strength of the live-blur glass. */
@Composable
fun GlassLookSliders(onBlur: () -> Unit) {
    LevelRow(null, "Transparency", compact = false, value = "${(AppSettings.glassClarity * 100).toInt()} %",
        pos = AppSettings.glassClarity) { AppSettings.setGlassLook(clarity = Math.round(it * 20) / 20f) }
    LevelRow(null, "Blur", compact = false, value = "${(AppSettings.glassBlur * 100).toInt()} %",
        pos = AppSettings.glassBlur) { AppSettings.setGlassLook(blur = Math.round(it * 20) / 20f); onBlur() }
}

/**
 * Colour saturation of both screens (the panels are vivid out of the box; 80–90 % looks
 * natural). SurfaceFlinger's saturation: live via its debug transaction 1022, kept across
 * reboots by `persist.sys.sf.color_saturation` (read at boot). Root.
 */
@Composable
fun SaturationSlider() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sat by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        sat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PServiceBridge.exec("getprop persist.sys.sf.color_saturation")?.trim()?.toFloatOrNull() ?: 1f
        }
    }
    val s = sat
    // Slider 0..1 ↔ 50 %..130 %.
    LevelRow(null, "Saturation", compact = false, value = s?.let { "${(it * 100).toInt()} %" },
        pos = s?.let { ((it - 0.5f) / 0.8f).coerceIn(0f, 1f) }, step = 0.05f / 0.8f) { p ->
        val v = (Math.round((0.5f + p * 0.8f) * 20) / 20f)   // 5 % steps
        sat = v; Saturation.apply(v); Saturation.keep(ctx, v)
    }
}

/** Applies the latest saturation only (a drag fires dozens of changes). */
object Saturation {
    @Volatile private var want = 1f
    @Volatile private var applied = -1f
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun apply(v: Float) {
        want = v
        worker.execute {
            val w = want
            if (w == applied) return@execute
            applied = w
            PServiceBridge.exec("service call SurfaceFlinger 1022 f $w; setprop persist.sys.sf.color_saturation $w")
        }
    }

    // 1.4.1 (GitHub #79): on some Thors something (AYN's firmware) puts its own saturation back at boot — Wayfinder
    // keeps the user's value and applies it again once it has started
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("thor_settings", android.content.Context.MODE_PRIVATE)
    fun keep(ctx: android.content.Context, v: Float) = prefs(ctx).edit().putFloat("saturation", v).apply()
    /** Service start: the user's own saturation again, if they ever set one. */
    fun restore(ctx: android.content.Context) {
        val v = prefs(ctx).getFloat("saturation", -1f)
        if (v < 0.5f || v > 1.3f) return
        applied = -1f; apply(v)
    }
}

/** The speaker fix's stereo width: 1 = as recorded, 2 = the community preset, up to 3. */
@Composable
fun StereoWidthSlider() {
    val w = SpeakerTune.width
    LevelRow(null, "Stereo width", compact = false, value = if (w <= 1.01f) "Off" else "%.1f×".format(w),
        pos = (w - 1f) / 2f, step = 0.1f / 2f) { p ->
        SpeakerTune.setStereoWidth(Math.round((1f + p * 2f) * 10) / 10f)
    }
}
