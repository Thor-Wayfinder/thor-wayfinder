package app.wayfinder

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass

/** Hub → Screens & power → Sleep & standby. */
@Composable
fun SleepPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, "Sleep & standby", "Less drain while the screen is off, and a Thor that stays asleep in its case", onBack) {
        @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue
        if (SleepSettings.otherSleepApp != null) GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
            Text(
                "Another sleep manager is installed on this Thor. To avoid the two fighting over Wi-Fi, Bluetooth and the lid, " +
                    "these are off here unless you turn them on — use one app for sleep, not both.",
                color = LocalGlass.current.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
            )
        }
        SettingCard(
            "Sleep actions",
            if (SleepSettings.enabled) "When the screen goes off, turn off what's picked below; put back only what was on when it wakes"
            else "Off. Turn on to have Wayfinder switch off what drains the battery while the Thor sleeps — Wi-Fi, Bluetooth, sync… — and switch it back on when it wakes.",
            checked = SleepSettings.enabled, onChecked = { SleepSettings.set("enabled", it) },
        )
        if (SleepSettings.enabled) {
            SleepActionsCard()
            SleepWhenCard()
        }
        SleepLidCard()
        SleepStatsCard()
        SleepLogCard()
    }

@Composable
private fun SleepActionsCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    val ctx = LocalContext.current
    fun has(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
    SettingCard("Wi-Fi", "Off while asleep (left alone in airplane mode)", checked = SleepSettings.wifi, onChecked = { SleepSettings.set("wifi", it) })
    SettingCard("Bluetooth", "Off while asleep — leave this off if you wake it with a Bluetooth controller",
        checked = SleepSettings.bluetooth, onChecked = { SleepSettings.set("bluetooth", it) })
    if (has("com.tailscale.ipn")) SettingCard("Tailscale", "Disconnect while asleep, reconnect once the network is back",
        checked = SleepSettings.tailscale, onChecked = { SleepSettings.set("tailscale", it) })
    if (has("james.dsp") || has("me.timschneeberger.rootlessjamesdsp")) SettingCard("JamesDSP",
        "Off while asleep, on again when it wakes", checked = SleepSettings.jamesDsp, onChecked = { SleepSettings.set("jamesdsp", it) })
    if (has("com.nutomic.syncthingandroid") || has("com.github.catfriend1.syncthingandroid")) SettingCard("Syncthing",
        "Stop syncing while asleep, restart when the network is back (ignored if Syncthing is set to always run)",
        checked = SleepSettings.syncthing, onChecked = { SleepSettings.set("syncthing", it) })
}

@Composable
private fun SleepWhenCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    val g = LocalGlass.current
    val grace = SleepSettings.GRACE_OPTIONS
    SettingCard("After the screen goes off", "How long to wait first — a quick look at the clock won't trigger it") {
        GlassSegmentedControl(grace.map { SleepSettings.graceLabel(it) },
            grace.indexOf(SleepSettings.graceSec).coerceAtLeast(0), Modifier.fillMaxWidth()) { SleepSettings.set("grace", grace[it]) }
    }
    val bat = listOf(0, 20, 30, 50, 80)
    SettingCard("Only when the battery is at or below", "All the conditions you set must be true") {
        GlassSegmentedControl(bat.map { if (it == 0) "Any" else "$it %" }, bat.indexOf(SleepSettings.maxBattery).coerceAtLeast(0),
            Modifier.fillMaxWidth()) { SleepSettings.set("max_battery", bat[it]) }
    }
    SettingCard("Only when not charging", "", checked = SleepSettings.notCharging, onChecked = { SleepSettings.set("not_charging", it) })
    SettingCard("Only in Battery Saver", "", checked = SleepSettings.batterySaverOnly, onChecked = { SleepSettings.set("saver_only", it) })
    val windows = listOf(-1 to 0, 22 to 7, 23 to 8, 0 to 9)
    SettingCard("Only at these hours", "") {
        GlassSegmentedControl(windows.map { (f, t) -> if (f < 0) "Always" else "${f}:00–${t}:00" },
            windows.indexOfFirst { it.first == SleepSettings.fromHour && (it.first < 0 || it.second == SleepSettings.toHour) }.coerceAtLeast(0),
            Modifier.fillMaxWidth()) { SleepSettings.set("from_hour", windows[it].first); SleepSettings.set("to_hour", windows[it].second) }
    }
    Text("An external display (dock) always counts as awake.", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SleepLidCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    SettingCard("Keep it asleep with the lid closed",
        "If it wakes in its case (a button, the power key, a notification) it goes straight back to sleep — checked again every few seconds, and if it runs hot in there, music stops and performance drops to Standard. Not while docked on an external display.",
        checked = SleepSettings.lidProtection, onChecked = { SleepSettings.set("lid", it) })
    SettingCard("Pause music and videos when the lid closes",
        "Closing the lid stops what's playing (it doesn't start again by itself when you open it)",
        checked = SleepSettings.pauseOnLid, onChecked = { SleepSettings.set("lid_pause", it) })
    SettingCard("Sleep when the external display disconnects", "Unplugging from the dock or TV puts the Thor to sleep",
        checked = SleepSettings.sleepOnDisplayGone, onChecked = { SleepSettings.set("display_gone", it) })
}

@Composable
private fun SleepStatsCard() {
    val g = LocalGlass.current
    val all = remember(SleepSettings.version.intValue) { SleepEngine.sessions() }
    val q = remember(all) { SleepEngine.qualifying() }
    val avg = q.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.pctDrop.toDouble() } / (l.sumOf { it.durMs } / 3_600_000.0) }
    val full = remember { SleepEngine.fullCapacityUah() }
    val design = remember {
        runCatching { java.io.File("/sys/class/power_supply/battery/charge_full_design").readText().trim().toLong() }.getOrNull()
    }
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Standby", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
            val last = all.firstOrNull()
            Text(last?.let { "Last sleep: ${SleepEngine.fmtDur(it.durMs)} · −${"%.1f".format(it.pctDrop)} %" +
                (it.mAh?.let { m -> " ($m mAh)" } ?: "") + " · deep sleep ${it.deepPct} %" } ?: "How fast the battery drains while the Thor sleeps. Nothing measured yet: it counts each sleep off the charger (sleeps of 3 h or more give the averages).",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (avg != null) {
                Text("7 days: ${"%.2f".format(avg)} %/h · a full battery would last about ${if (avg > 0) "%.0f".format(100 / avg) + " h" else "—"} asleep",
                    color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                val best = q.minByOrNull { it.drainPerHour }; val worst = q.maxByOrNull { it.drainPerHour }
                Text("Best ${"%.2f".format(best?.drainPerHour ?: 0f)} %/h · worst ${"%.2f".format(worst?.drainPerHour ?: 0f)} %/h · ${q.size} sleep(s) of 3 h+",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            } else Text("Drain figures appear after a sleep of 3 h or more (shorter ones are logged, not averaged)",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            if (full != null && design != null) Text("Battery: ${full / 1000} mAh now vs ${design / 1000} mAh design (${100 * full / design} %)",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SleepLogCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val lines = remember(SleepSettings.version.intValue) { SleepEngine.logLines() }
    var copied by remember { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What it did", color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FocusableGlass(onClick = {
                    val report = "Wayfinder sleep report\n" + SleepEngine.sessions().take(10).joinToString("\n") {
                        "${java.util.Date(it.end)}: ${SleepEngine.fmtDur(it.durMs)} −${"%.2f".format(it.pctDrop)} % mAh=${it.mAh} deep=${it.deepPct} %"
                    } + "\n\n" + lines.joinToString("\n")
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Wayfinder sleep report", report))
                    copied = true
                }, radius = 12.dp) {
                    Text(if (copied) "✓ Copied" else "Copy report", color = g.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            if (lines.isEmpty()) Text("Nothing yet", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            lines.take(14).forEach { Text(it, color = g.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
