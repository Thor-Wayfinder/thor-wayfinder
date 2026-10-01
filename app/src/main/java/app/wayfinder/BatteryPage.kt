package app.wayfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.GlassListRow
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader

/**
 * 1.4 — one page for what makes the battery last: what the Thor draws right now, the charging switches and sleep.
 * (An Eco mode was tried and dropped: measured, it saved ~0–10 %. ClusterTune does CPU limits.)
 */
@Composable
internal fun BatteryPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, "Battery", "What the Thor draws, charging and sleep", onBack) {
        val ctx = LocalContext.current
        NowCard()
        Tuners.installed(ctx)?.let { t ->
            GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
                Text(Tuners.note(t), color = LocalGlass.current.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp))
            }
        }
        SectionHeader("Charging")
        BatterySwitches()
        SectionHeader("Asleep")
        GlassListRow("Sleep & standby", value = sleepSummary(), icon = Icons.Rounded.Bedtime) { go(HubPage.SLEEP) }
    }

/** What the Thor does right now: its draw, the time left (or the charger), and the CPU's top speeds in force. */
@Composable
private fun NowCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var n by remember { mutableStateOf(Charging.now(ctx)) }
    var watts by remember { mutableStateOf<Float?>(null) }
    var hours by remember { mutableStateOf<Float?>(null) }
    var caps by remember { mutableStateOf<List<Int>?>(null) }
    var maxima by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(Unit) {
        val samples = ArrayDeque<Float>()
        while (true) {
            // battery files and the CPU's limits: read off the main thread
            val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Charging.now(ctx) }
            if (now.plugged != n.plugged) samples.clear()     // plugged in / out: a new average, not the old one
            n = now
            val w = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Charging.drawWatts(ctx) }
            if (w == null) samples.clear() else { samples.addLast(w); if (samples.size > 10) samples.removeFirst() }
            watts = samples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            val e = if (!n.plugged) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Charging.energyWh() } else null
            hours = watts?.let { ww -> e?.let { if (ww > 0.3f) it / ww else null } }
            caps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Tuners.cpuLimitsNow() }
            if (maxima == null) maxima = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Tuners.cpuMaxima() }
            kotlinx.coroutines.delay(2000)
        }
    }
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(watts?.let { "%.1f W".format(it) } ?: "—", color = g.textPrimary, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(when {
                    n.plugged -> "the Thor draws now, on the charger" + (n.phrase?.let { " · $it" } ?: "")
                    hours != null -> "now · about ${hours!!.toInt()} h ${((hours!! % 1) * 60).toInt()} min left at this rate"
                    else -> "the Thor draws now"
                }, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            val limited = caps != null && maxima != null && caps!!.indices.any { caps!![it] < maxima!![it] }
            Text("Battery ${n.level ?: "—"} % · CPU top speed: " + (caps?.let { c ->
                (if (maxima == null) "" else if (limited) (Tuners.installed(ctx)?.let { "limited by $it — " } ?: "limited — ") else "usual — ") +
                    c.joinToString(" · ") { "%.1f".format(it / 1_000_000f) } + " GHz (small · middle · big cores)"
            } ?: "—") + ". Most of the battery goes to the screens: brightness saves the most.",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sleepSummary(): String {
    val q = SleepEngine.qualifying()
    val avg = q.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.pctDrop.toDouble() } / (l.sumOf { it.durMs } / 3_600_000.0) }
    return listOfNotNull(when { SleepSettings.enabled -> "On"; SleepSettings.lidProtection -> "Lid guard only"; else -> "Off" },
        avg?.let { "%.2f %%/h asleep".format(it) }).joinToString(" · ")
}
