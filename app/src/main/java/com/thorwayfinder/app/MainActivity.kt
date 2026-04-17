package com.thorwayfinder.app

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import rikka.shizuku.Shizuku
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.d("ThorMain", "Shizuku permission result: code=$requestCode granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // Request Shizuku permission if available but not yet granted
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            }
        } catch (e: Exception) {
            Log.d("ThorMain", "Shizuku not available: ${e.message}")
        }

        @Suppress("DEPRECATION")
        val myDisplayId = windowManager.defaultDisplay.displayId
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen(myDisplayId) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}

@Composable
fun MainScreen(myDisplayId: Int) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var displayAppsSnapshot by remember { mutableStateOf(mapOf<Int, String>()) }
    var displayIds by remember { mutableStateOf(listOf<Int>()) }
    var batteryOptimized by remember { mutableStateOf(true) }
    var shizukuStatus by remember { mutableStateOf("checking...") }

    LaunchedEffect(Unit) {
        while (true) {
            running = ForegroundAppService.isRunning
            displayAppsSnapshot = ForegroundAppService.displayApps.toMap()
            displayIds = ForegroundAppService.availableDisplayIds.ifEmpty {
                ctx.getSystemService(DisplayManager::class.java)
                    .displays.map { it.displayId }
            }
            val pm = ctx.getSystemService(PowerManager::class.java)
            batteryOptimized = !pm.isIgnoringBatteryOptimizations(ctx.packageName)
            shizukuStatus = try {
                when {
                    !Shizuku.pingBinder() -> "not running"
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "no permission"
                    else -> "ready"
                }
            } catch (_: Exception) { "unavailable" }
            delay(2000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var showTutorial by remember { mutableStateOf(false) }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Thor Wayfinder", style = MaterialTheme.typography.headlineSmall)
                Text("beta v0.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/thorwayfinder"))
                    ctx.startActivity(intent)
                }) { Text("\u2615 Tip") }
                OutlinedButton(onClick = { showTutorial = true }) { Text("How to Use") }
            }
        }

        if (showTutorial) {
            TutorialDialog(onDismiss = { showTutorial = false })
        }

        if (!running) {
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Enable accessibility service") }
        } else {
            Text("Service: active  \u2022  Long-press Back to swap")
        }

        if (batteryOptimized) {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                ctx.startActivity(intent)
            }) { Text("\u26A0\uFE0F Disable battery optimization (recommended)") }
        }

        Text("Shizuku: $shizukuStatus",
            style = MaterialTheme.typography.bodySmall,
            color = if (shizukuStatus == "ready") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
        )

        Text("This app on display $myDisplayId")
        displayIds.forEach { id ->
            val app = displayAppsSnapshot[id] ?: "\u2013"
            Text("Display $id \u2192 $app")
        }

        Button(
            onClick = {
                if (displayIds.size < 2) { status = "No other display"; return@Button }
                val d0 = displayIds[0]
                val d1 = displayIds[1]
                val app0 = displayAppsSnapshot[d0]
                val app1 = displayAppsSnapshot[d1]

                when {
                    app0 != null && app1 != null -> {
                        launchPkg(ctx, app0, d1)
                        launchPkg(ctx, app1, d0)
                        status = "Swapped $app0 \u2194 $app1"
                    }
                    app0 != null -> {
                        launchPkg(ctx, app0, d1)
                        status = "Sent $app0 \u2192 display $d1"
                    }
                    app1 != null -> {
                        launchPkg(ctx, app1, d0)
                        status = "Sent $app1 \u2192 display $d0"
                    }
                    else -> status = "No tracked apps to move"
                }
            },
            enabled = running && displayAppsSnapshot.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("\u26A1 Swap / Send to other screen") }

        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Quick Start Guide",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                HorizontalDivider()

                // Setup section
                Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                TutorialStep(
                    number = "1",
                    title = "Enable Accessibility Service",
                    desc = "Go to Settings \u2192 Accessibility \u2192 Thor Wayfinder and enable it. This lets the app detect which apps are on each screen."
                )
                TutorialStep(
                    number = "2",
                    title = "Disable Battery Optimization",
                    desc = "Tap the warning in the main screen to prevent Android from killing the service in the background."
                )
                TutorialStep(
                    number = "3",
                    title = "Shizuku (Recommended)",
                    desc = "Install and start Shizuku for reliable app switching. Without it, some apps cannot be moved between screens."
                )

                HorizontalDivider()

                // Shortcuts section
                Text("Back Button Shortcuts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                ShortcutRow(
                    gesture = "Single tap",
                    action = "Back",
                    detail = "Normal back navigation"
                )
                ShortcutRow(
                    gesture = "Double tap",
                    action = "Recent Apps",
                    detail = "Opens the task/window manager"
                )
                ShortcutRow(
                    gesture = "Long press (1s)",
                    action = "Swap / Send",
                    detail = "Moves the current app to the other screen, or swaps both apps if both screens have one"
                )

                HorizontalDivider()

                // Tips section
                Text("Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Text(
                    "\u2022 A short vibration confirms the long-press swap\n" +
                    "\u2022 If an app fails to move gently, Wayfinder retries automatically\n" +
                    "\u2022 With Shizuku, apps move without restarting (preserves state)\n" +
                    "\u2022 Without Shizuku, stubborn apps may be force-stopped as a last resort\n" +
                    "\u2022 The Thor Wayfinder shortcut in Accessibility settings lets you quickly enable or disable the service \u2014 leave it off unless you need to toggle it on the fly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Text("Disclaimer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Text(
                    "Thor Wayfinder is a workaround for dual-screen app management using the Shizuku shell bridge. " +
                    "The app will function without Shizuku but will be significantly less stable \u2014 " +
                    "some apps won't switch screens at all.\n\n" +
                    "Not all apps play nice with display switching. Heavy or single-task apps " +
                    "(games, streaming apps) may shut down, lose state, or refuse to move cleanly. " +
                    "This is a limitation of Android\u2019s multi-display handling, not a bug in Wayfinder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun TutorialStep(number: String, title: String, desc: String) {
    Row {
        Text(
            number,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShortcutRow(gesture: String, action: String, detail: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(gesture, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            action,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun launchPkg(ctx: android.content.Context, pkg: String, targetDisplayId: Int) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    try {
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(targetDisplayId)
        ctx.startActivity(intent, opts.toBundle())
        Log.i("ThorLaunch", "Launched $pkg \u2192 display $targetDisplayId")
    } catch (e: Exception) {
        Log.e("ThorLaunch", "Failed $pkg: ${e.message}", e)
    }
}
