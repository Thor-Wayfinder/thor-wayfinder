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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.ButtonKind
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.GlassButton
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.Radii
import app.wayfinder.ui.RowEnd
import app.wayfinder.ui.SectionHeader

/** Hub → Battery → Sleep & standby. */
@Composable
fun SleepPage(myDisplayId: Int, onBack: () -> Unit) =
    SubPage(myDisplayId, NavPages.title(HubPage.SLEEP), "Less drain while the screen is off, and a Thor that stays asleep in its case", onBack) {
        @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue
        if (SleepSettings.otherSleepApp != null) GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
            Text(
                "SleepManager (or another sleep app) is installed on this Thor. To avoid the two fighting over Wi-Fi, Bluetooth " +
                    "and the lid, these are off here unless you turn them on — use one app for sleep, not both.",
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
        SleepCreditCard(myDisplayId)
    }

@Composable
private fun SleepActionsCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    val ctx = LocalContext.current
    fun has(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
    // build 18 (B6, hF13): each switch says what it does now — off, "Off — …". (B9, D19): under their own heading
    SectionHeader("Turn off while asleep")
    SettingCard("Wi-Fi", if (SleepSettings.wifi) "Turned off while asleep (left alone in airplane mode)" else "Off — Wi-Fi stays on while asleep",
        checked = SleepSettings.wifi, onChecked = { SleepSettings.set("wifi", it) })
    SettingCard("Bluetooth",
        if (SleepSettings.bluetooth) "Turned off while asleep — leave this off if you wake it with a Bluetooth controller"
        else "Off — Bluetooth stays on while asleep",
        checked = SleepSettings.bluetooth, onChecked = { SleepSettings.set("bluetooth", it) })
    if (has("com.tailscale.ipn")) SettingCard("Tailscale",
        if (SleepSettings.tailscale) "Disconnect while asleep, reconnect once the network is back" else "Off — Tailscale stays connected",
        checked = SleepSettings.tailscale, onChecked = { SleepSettings.set("tailscale", it) })
    if (has("james.dsp") || has("me.timschneeberger.rootlessjamesdsp")) SettingCard("JamesDSP",
        if (SleepSettings.jamesDsp) "Turned off while asleep, on again when it wakes" else "Off — JamesDSP keeps running while asleep",
        checked = SleepSettings.jamesDsp, onChecked = { SleepSettings.set("jamesdsp", it) })
    if (has("com.nutomic.syncthingandroid") || has("com.github.catfriend1.syncthingandroid")) SettingCard("Syncthing",
        if (SleepSettings.syncthing) "Stop syncing while asleep, restart when the network is back. Ignored if Syncthing is set to always run."
        else "Off — Syncthing keeps syncing while asleep",
        checked = SleepSettings.syncthing, onChecked = { SleepSettings.set("syncthing", it) })
}

@Composable
private fun SleepWhenCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    val g = LocalGlass.current
    val grace = SleepSettings.GRACE_OPTIONS
    // build 18 (B9, D19): the dock line is part of this card's explanation (behind "?"; it was a loose line at the end)
    SettingCard("After the screen goes off", "How long to wait first — a quick look at the clock won't trigger it. " +
        "An external display (dock) always counts as awake.") {
        GlassSegmentedControl(grace.map { SleepSettings.graceLabel(it) },
            grace.indexOf(SleepSettings.graceSec).coerceAtLeast(0), Modifier.fillMaxWidth()) { SleepSettings.set("grace", grace[it]) }
    }
    // build 18 (B9, D19): the conditions under one heading that says they all count
    SectionHeader("Only when… (all must apply)")
    val bat = listOf(0, 20, 30, 50, 80)
    SettingCard("Only below a battery level", "At or below this level") {
        GlassSegmentedControl(bat.map { if (it == 0) "Any" else "$it %" }, bat.indexOf(SleepSettings.maxBattery).coerceAtLeast(0),
            Modifier.fillMaxWidth()) { SleepSettings.set("max_battery", bat[it]) }
    }
    // build 18 (B6): were blank — on and off said (B9's short lines)
    SettingCard("Only when not charging",
        if (SleepSettings.notCharging) "Sleep actions wait while it charges" else "Off — they run on the charger too",
        checked = SleepSettings.notCharging, onChecked = { SleepSettings.set("not_charging", it) })
    SettingCard("Only in Battery Saver",
        if (SleepSettings.batterySaverOnly) "Only while Android's Battery Saver is on" else "Off — with or without Battery Saver",
        checked = SleepSettings.batterySaverOnly, onChecked = { SleepSettings.set("saver_only", it) })
    val windows = listOf(-1 to 0, 22 to 7, 23 to 8, 0 to 9)
    // build 18 (B9, D19 / hF14): a line of its own (it was blank), "Any time" (was "Always")
    SettingCard("Only at these hours", "Only in this time window") {
        GlassSegmentedControl(windows.map { (f, t) -> if (f < 0) "Any time" else "${f}:00–${t}:00" },
            windows.indexOfFirst { it.first == SleepSettings.fromHour && (it.first < 0 || it.second == SleepSettings.toHour) }.coerceAtLeast(0),
            Modifier.fillMaxWidth()) { SleepSettings.set("from_hour", windows[it].first); SleepSettings.set("to_hour", windows[it].second) }
    }
}

@Composable
private fun SleepLidCard() {
    @Suppress("UNUSED_VARIABLE") val v = SleepSettings.version.intValue   // redraw on change (GitHub #5)
    // build 18 (B6, hF13): on and off said; (B3) the lid's pause is a registry card (Search opens it)
    SettingCard("Stay asleep, lid closed",
        if (SleepSettings.lidProtection) "If it wakes in its case, it goes straight back to sleep. A button, the power key or a notification can wake it; it's checked every few seconds, and if it runs hot in there, music stops and performance drops to Standard. Not while docked on an external display."
        else "Off — if it wakes in its case, it stays awake",
        checked = SleepSettings.lidProtection, onChecked = { SleepSettings.set("lid", it) })
    SettingCard(NavCards.LID_PAUSE,
        if (SleepSettings.pauseOnLid) "Closing the lid stops music and videos (it doesn't start again by itself when you open it)"
        else "Off — music and videos go on with the lid closed",
        checked = SleepSettings.pauseOnLid, onChecked = { SleepSettings.set("lid_pause", it) })
    SettingCard("Sleep when undocked",
        if (SleepSettings.sleepOnDisplayGone) "Unplugging from the dock or TV (an external display) puts the Thor to sleep"
        else "Off — unplugging from the dock or TV leaves the Thor awake",
        checked = SleepSettings.sleepOnDisplayGone, onChecked = { SleepSettings.set("display_gone", it) })
}

/** Credit (GitHub #70): the ideas of this page come from SleepManager, by Baggio94. Build 18 (B9, D20): one line and the link;
 *  the rest behind "?" (it opens while the controller is on the link); the link leaves Wayfinder (its mark), a code icon. */
@Composable
private fun SleepCreditCard(myDisplayId: Int) {
    val ctx = LocalContext.current
    SettingCard("Credit: SleepManager", short = "The ideas behind this page come from SleepManager, by Baggio94 — thank you!",
        more = "The community's sleep app for the Thor: closed-lid protection, false-wake handling, the dock, turning Wi-Fi, " +
            "Bluetooth, Tailscale, Syncthing and JamesDSP off while asleep, the conditions and delays, putting back only what was " +
            "on, and the standby statistics. Wayfinder's version is written from scratch: no SleepManager code was copied. If you " +
            "want a dedicated sleep app, SleepManager does all this and more.") {
        app.wayfinder.ui.GlassListRow("SleepManager on GitHub", value = "github.com/Baggio94/SleepManager",
            icon = androidx.compose.material.icons.Icons.Rounded.Code, end = RowEnd.EXTERNAL) {
            openLink(ctx, "https://github.com/Baggio94/SleepManager", myDisplayId)
        }
    }
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
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
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
                // build 18 (B9, D20): "1 sleep" / "N sleeps" of 3 h or more (was "sleep(s) of 3 h+")
                Text("Best ${"%.2f".format(best?.drainPerHour ?: 0f)} %/h · worst ${"%.2f".format(worst?.drainPerHour ?: 0f)} %/h · " +
                    (if (q.size == 1) "1 sleep" else "${q.size} sleeps") + " of 3 h or more",
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
    GlassPanel(Modifier.fillMaxWidth(), radius = Radii.Card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("What it did", color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // build 18 (B9, D20): the shared button (it was bare accent text)
                GlassButton(if (copied) "✓ Copied" else "Copy report", {
                    val report = "Wayfinder sleep report\n" + SleepEngine.sessions().take(10).joinToString("\n") {
                        "${java.util.Date(it.end)}: ${SleepEngine.fmtDur(it.durMs)} −${"%.2f".format(it.pctDrop)} % mAh=${it.mAh} deep=${it.deepPct} %"
                    } + "\n\n" + lines.joinToString("\n")
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Wayfinder sleep report", report))
                    copied = true
                }, kind = ButtonKind.PRIMARY, size = ButtonSize.COMPACT)
            }
            if (lines.isEmpty()) app.wayfinder.ui.EmptyState("Nothing yet")
            lines.take(14).forEach { Text(it, color = g.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
