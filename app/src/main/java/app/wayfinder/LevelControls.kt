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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.wayfinder.ui.BackLayer
import app.wayfinder.ui.GlassSlider
import app.wayfinder.ui.requestAfterLayout
import kotlinx.coroutines.launch
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.Radii
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Brightness and volume, the same way everywhere (Screens, quick panel):
 * three sliders each — Both screens, Top, Bottom. "Both" keeps the difference between the
 * screens; Top / Bottom set it. The volume buttons and Home + R2 / L2 act like "Both".
 * Build 18 (A6): the sliders are there from the first frame (a page opens on "Both", even while the levels load); opened
 * ([collapsible]), B folds them back and puts the controller on the expand button; [sideExit] lets Left / Right leave a
 * row at its ends (the quick panel's cards side by side).
 */
@Composable
fun BrightnessSliders(compact: Boolean = false, collapsible: Boolean = compact, sideExit: Boolean = false) {
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
    // build 18 (A6): from the cache at once (the last read or change) — the async read then corrects it
    var levels by remember { mutableStateOf(ids.mapNotNull { d -> ScreenLevels.known(d)?.let { d to it } }.toMap()) }
    LaunchedEffect(Unit) { ScreenLevels.readAsync(ctx, ids) { levels = it } }
    // Follow changes made elsewhere (Home + R2/L2, the other slider set) the moment they land.
    val changed = ScreenLevels.changes.intValue
    LaunchedEffect(changed) { if (changed > 0) levels = levels + ids.mapNotNull { d -> ScreenLevels.known(d)?.let { d to it } } }
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    val fold = rememberFold(collapsible, open) { open = false }
    Column(fold.modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 2.dp)) {
        val ready = levels.size == ids.size
        LevelRow(if (compact) null else Icons.Rounded.LightMode, if (compact) "Both" else "Both screens", compact = compact, value = if (ready) "${Math.round(ScreenLevels.snap(ScreenLevels.bothPos(levels)) * 100)} %" else null,
            pos = if (ready) ScreenLevels.bothPos(levels) else null, sideExit = sideExit,
            trailing = if (collapsible) ({ ExpandButton(open, fold.expandFocus) { open = !open } }) else null) { p ->
            levels = ScreenLevels.shiftBoth(ctx, levels, p - ScreenLevels.bothPos(levels))
        }
        if (open) for (d in ids) LevelRow(null, name(d == 0, compact), compact = compact, value =
            levels[d]?.let { "${ScreenLevels.percent(it)} %" }, pos = levels[d]?.let { ScreenLevels.toSlider(it) }, sideExit = sideExit,
            trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val v = ScreenLevels.fromSlider(p); levels = levels + (d to v); ScreenLevels.setAsync(ctx, d, v)
        }
    }
}

/** Build 18 (A6): an opened level card's Back — while its sliders are open and the controller is in them, B folds them
 *  (B closed the whole quick panel) and puts the controller on the expand button. */
private class Fold(val modifier: Modifier, val expandFocus: FocusRequester)

@Composable
private fun rememberFold(collapsible: Boolean, open: Boolean, close: () -> Unit): Fold {
    val expandFocus = remember { FocusRequester() }
    var focusInside by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shut by rememberUpdatedState(close)
    // a layer one level deeper than what holds the card (the panel, the page): it closes first
    BackLayer(enabled = collapsible && open && focusInside, onBack = { shut(); scope.launch { expandFocus.requestAfterLayout() } })
    return Fold(Modifier.onFocusChanged { focusInside = it.hasFocus }, expandFocus)
}

@Composable
fun VolumeSliders(compact: Boolean = false, collapsible: Boolean = compact, sideExit: Boolean = false) {
    var top by remember { mutableIntStateOf(LinkedVolume.top()) }
    var bottom by remember { mutableIntStateOf(LinkedVolume.bottom()) }
    // Follow the volume buttons (and anything else) the moment they change — plus a slow
    // re-read in case a change came without a broadcast.
    val changed = LinkedVolume.changes.intValue
    LaunchedEffect(changed) { top = LinkedVolume.top(); bottom = LinkedVolume.bottom() }
    LaunchedEffect(Unit) { while (true) { delay(2000); top = LinkedVolume.top(); bottom = LinkedVolume.bottom() } }
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    val fold = rememberFold(collapsible, open) { open = false }
    Column(fold.modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 2.dp)) {
        // build 18 (A20, V21): volume in % like brightness, "Muted" at 0 (a bare "0" read as nothing)
        LevelRow(if (compact) null else Icons.Rounded.VolumeUp, if (compact) "Both" else "Both screens", compact = compact, value = volumeText(top), pos = top / 15f, step = 1f / 15f,
            sideExit = sideExit, trailing = if (collapsible) ({ ExpandButton(open, fold.expandFocus) { open = !open } }) else null) { p ->
            val t = Math.round(p * 15); LinkedVolume.setBoth(t); top = t; bottom = (t + LinkedVolume.offset).coerceIn(0, 15)
        }
        if (open) LevelRow(null, name(true, compact), compact = compact, value = volumeText(top), pos = top / 15f, step = 1f / 15f,
            sideExit = sideExit, trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val t = Math.round(p * 15); LinkedVolume.setTopOnly(t); top = t
        }
        if (open) LevelRow(null, name(false, compact), compact = compact, value = volumeText(bottom), pos = bottom / 15f, step = 1f / 15f,
            sideExit = sideExit, trailing = if (collapsible) ({ ExpandSpace() }) else null) { p ->
            val b = Math.round(p * 15); LinkedVolume.setBottomOnly(b); bottom = b
        }
        // 1.3 (GitHub #7): say when the bottom screen's apps play quieter / louder than the top's
        if (!compact && bottom != top) {
            val g = LocalGlass.current
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Apps on the bottom screen play ${if (bottom < top) "quieter" else "louder"} (${volumeText(bottom)} against ${volumeText(top)})",
                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                app.wayfinder.ui.FocusableGlass(onClick = { LinkedVolume.setBalance(0); LinkedVolume.setBoth(top); bottom = top }, radius = Radii.Chip) {
                    Text("Same on both", color = g.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

private fun name(top: Boolean, compact: Boolean) = if (top) (if (compact) "Top" else "Top screen") else (if (compact) "Bottom" else "Bottom screen")

/** Build 18 (A20, V21): a volume step (0–[max]) in words — "40 %" like brightness, "Muted" at 0. */
internal fun volumeText(v: Int, max: Int = 15): String =
    if (v <= 0) "Muted" else "${(v * 100f / max).roundToInt()} %"

/**
 * One level: [icon] (in a 22 dp slot), its name, a slider and its value. Build 18 (A20): internal (Stick lights uses it,
 * B19); [iconSlot] false = no slot at all — the name starts at the card's text edge (Stereo width, Transparency, Blur: an
 * empty slot indented them under the row above).
 */
@Composable
internal fun LevelRow(icon: ImageVector?, label: String, compact: Boolean, value: String?, pos: Float?, step: Float = 0.05f,
                      sideExit: Boolean = false, trailing: (@Composable () -> Unit)? = null, iconSlot: Boolean = true,
                      onChange: (Float) -> Unit) {
    val g = LocalGlass.current
    // dev build 16 (WP7): Left / Right stay in the row — the slider at a limit lets them reach its expand button
    // (build 18, A6: [sideExit] — and beyond the row's ends)
    app.wayfinder.ui.SliderRow(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        sideExit = sideExit) {
        if (!compact && iconSlot) Box(Modifier.size(22.dp)) { if (icon != null) Icon(icon, null, tint = g.accent, modifier = Modifier.size(22.dp)) }
        Text(label, color = if (label.startsWith("Both")) g.textPrimary else g.textSecondary,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(if (compact) 44.dp else 118.dp))
        val h = if (compact) 30.dp else 36.dp
        // build 18 (A6): always a slider — not known yet = empty, no knob, inert, but it takes the page's entry (the page
        // opened on the card after it: Screens on "3-finger tap")
        GlassSlider(pos ?: 0f, Modifier.weight(1f), step = step, height = h, enabled = pos != null, onChange = onChange)
        // build 18 (A20): 40 dp in a compact row — "Muted" and "100 %" fit on one line (34 wrapped them)
        Text(value ?: "…", color = g.textSecondary, style = MaterialTheme.typography.bodySmall.takeIf { compact } ?: MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(if (compact) 40.dp else 64.dp), textAlign = TextAlign.End, maxLines = 1)
        trailing?.invoke()
    }
}

/** Same width as [ExpandButton], so the per-screen sliders line up with "Both". */
@Composable
private fun ExpandSpace() = Box(Modifier.size(30.dp))

/** Shows / hides the per-screen sliders under the "Both" one (the drawer keeps just "Both"). */
@Composable
private fun ExpandButton(open: Boolean, focus: FocusRequester? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    app.wayfinder.ui.FocusableGlass(onClick = onClick, radius = Radii.Chip, focusRequester = focus) {
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide top and bottom" else "Top and bottom",
            tint = g.accent, modifier = Modifier.padding(4.dp).size(22.dp))
    }
}

/** Appearance → See-through glass: frost amount and blur strength of the live-blur glass. */
@Composable
fun GlassLookSliders(onBlur: () -> Unit) {
    // build 18 (A20): no icon slot — the names start at the card's text edge
    LevelRow(null, "Transparency", compact = false, value = "${(AppSettings.glassClarity * 100).toInt()} %",
        pos = AppSettings.glassClarity, iconSlot = false) { AppSettings.setGlassLook(clarity = Math.round(it * 20) / 20f) }
    LevelRow(null, "Blur", compact = false, value = "${(AppSettings.glassBlur * 100).toInt()} %",
        pos = AppSettings.glassBlur, iconSlot = false) { AppSettings.setGlassLook(blur = Math.round(it * 20) / 20f); onBlur() }
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
        pos = (w - 1f) / 2f, step = 0.1f / 2f, iconSlot = false) { p ->     // build 18 (A20): lines up with "Equalizer" above
        SpeakerTune.setStereoWidth(Math.round((1f + p * 2f) * 10) / 10f)
    }
}
