package com.thorwayfinder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

class ForegroundAppService : AccessibilityService() {

    companion object {
        private const val TAG = "ThorFgSvc"

        // Per-display app tracking: displayId → packageName
        // ConcurrentHashMap: accessed from main thread (events) and background (swap logic)
        val displayApps = ConcurrentHashMap<Int, String>()

        @Volatile var isRunning = false
            private set

        @Volatile var availableDisplayIds = emptyList<Int>()
            private set

        var triggerKeyCode = KeyEvent.KEYCODE_BACK
    }

    private var keyDownTime = 0L
    private val holdThresholdMs = 1000L
    private val doubleTapWindowMs = 300L
    private var swapFired = false
    private var pendingBackPress = false
    @Volatile private var swapInProgress = false
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        Log.d(TAG, "Long-press threshold reached → vibrate + swap")
        vibrateShort()
        swapFired = true
        if (swapInProgress) {
            Log.w(TAG, "Swap already in progress — ignoring")
            return@Runnable
        }
        // Run on background thread so Thread.sleep doesn't block the main thread.
        // Trampoline activities need the main thread free to execute their onCreate.
        Thread {
            try {
                swapInProgress = true
                performSwapOrSend()
            } finally {
                swapInProgress = false
            }
        }.start()
    }
    private val singleBackRunnable = Runnable {
        pendingBackPress = false
        Log.d(TAG, "Short press → back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    private val ignoredPackages = mutableSetOf(
        "com.android.systemui",
        "com.thorwayfinder.app",
        "com.odin.gameassistant",
        "com.odin.dualscreen.assistant"
    )
    private val launcherPackages = mutableSetOf<String>()

    // ── lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        // THIS is why back button wasn't intercepted before:
        // canRequestFilterKeyEvents in XML sets the CAPABILITY but the
        // FLAG must also be enabled for the system to deliver key events.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        refreshDisplays()
        detectLaunchers()
        postPersistentNotification()
        isRunning = true
        Log.d(TAG, "Connected — displays: $availableDisplayIds, flags: ${serviceInfo.flags}, ignored: $ignoredPackages")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(1)
    }

    private fun postPersistentNotification() {
        val channelId = "thor_wayfinder_svc"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId, "Wayfinder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the screen-swap service alive" }
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Thor Wayfinder")
            .setContentText("Long-press Back to swap screens")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(1, notification)
    }

    // ── foreground tracking (per-display) ────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Strategy 1: get displayId from event.source → window → displayId
        var displayId = getDisplayIdFromEvent(event)

        // Strategy 2: try windowId → find matching window in windows list
        if (displayId == null) {
            displayId = getDisplayIdFromWindowId(event.windowId)
        }

        // If an actual launcher came to the foreground, the user went Home
        // → clear that display's tracked app so we don't swap with a ghost.
        // Suppress during swaps: the launcher briefly flashes on the target
        // display between the two 'am start' commands, which would falsely
        // clear the app we just moved there.
        if (pkg in launcherPackages) {
            if (swapInProgress) {
                Log.d(TAG, "Launcher $pkg on display $displayId — ignored (swap in progress)")
                return
            }
            if (displayId != null) {
                if (displayApps.containsKey(displayId)) {
                    Log.d(TAG, "Launcher $pkg on display $displayId — clearing tracked app")
                    displayApps.remove(displayId)
                }
            } else {
                // displayId unknown — launcher is visible somewhere; clear any
                // display whose tracked app no longer has a visible window.
                // This prevents ghost entries when the event system can't resolve
                // which display the launcher appeared on.
                Log.d(TAG, "Launcher $pkg (display unknown) — will be reconciled on next scan")
            }
            return
        }

        if (pkg in ignoredPackages) return

        if (displayId != null) {
            displayApps[displayId] = pkg
            Log.d(TAG, "Display $displayId → $pkg")
        } else {
            Log.d(TAG, "FG → $pkg (display unknown)")
        }
    }

    private fun getDisplayIdFromEvent(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var source: AccessibilityNodeInfo? = null
        var window: AccessibilityWindowInfo? = null
        try {
            source = event.source ?: return null
            window = source.window ?: return null
            @Suppress("NewApi")
            return window.displayId
        } catch (e: Exception) {
            Log.w(TAG, "getDisplayIdFromEvent: ${e.message}")
            return null
        } finally {
            window?.recycle()
            source?.recycle()
        }
    }

    private fun getDisplayIdFromWindowId(windowId: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        try {
            for (w in windows) {
                if (w.id == windowId) {
                    @Suppress("NewApi")
                    return w.displayId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDisplayIdFromWindowId: ${e.message}")
        }
        return null
    }

    // Scan all displays for foreground apps before acting.
    // getWindowsOnAllDisplays() returns SparseArray<List<AccessibilityWindowInfo>>
    private fun scanAllDisplayApps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val fallback = displayApps.toMap() // save event-based data as fallback
        displayApps.clear()
        val hadUnreadableWindow = mutableSetOf<Int>()
        try {
            @Suppress("NewApi")
            val sparse = getWindowsOnAllDisplays()
            Log.d(TAG, "scanAll: ${sparse.size()} displays")

            for (i in 0 until sparse.size()) {
                val displayId = sparse.keyAt(i)
                val windowList = sparse.valueAt(i) ?: continue
                Log.d(TAG, "scanAll: display $displayId has ${windowList.size} windows")
                for (w in windowList) {
                    val typeName = when (w.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                        else -> "OTHER(${w.type})"
                    }
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        Log.d(TAG, "scanAll: display $displayId — skipping window type=$typeName")
                        continue
                    }
                    val root = w.root
                    if (root == null) {
                        hadUnreadableWindow.add(displayId)
                        Log.d(TAG, "scanAll: display $displayId — app window root null")
                        continue
                    }
                    val pkg = root.packageName?.toString()
                    root.recycle()
                    if (pkg != null && pkg !in ignoredPackages) {
                        displayApps[displayId] = pkg
                        Log.d(TAG, "Scan: display $displayId → $pkg")
                        break
                    } else if (pkg != null) {
                        Log.d(TAG, "scanAll: display $displayId — skipping ignored pkg $pkg")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanAll failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        // Don't restore event-based fallback data: if the scan can't confirm
        // an app via its window root, the app may have been closed. Restoring
        // stale entries leads to swaps trying to reopen dead apps.
        for ((id, pkg) in fallback) {
            if (!displayApps.containsKey(id)) {
                Log.d(TAG, "scanAll: display $id — not confirmed (dropped: $pkg)")
            }
        }
    }

    // ── key event: long-press gate ───────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != triggerKeyCode) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyDownTime == 0L) {
                    keyDownTime = event.eventTime
                    swapFired = false
                    // Cancel the delayed single-back timer (keep pendingBackPress
                    // so the UP handler can detect double-tap)
                    handler.removeCallbacks(singleBackRunnable)
                    handler.postDelayed(longPressRunnable, holdThresholdMs)
                    Log.d(TAG, "Key DOWN at ${event.eventTime}")
                }
                return true // CONSUME down so system doesn't start its own long-press (launcher)
            }
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - keyDownTime
                keyDownTime = 0L
                handler.removeCallbacks(longPressRunnable)
                Log.d(TAG, "Key UP — held ${held}ms")
                if (!swapFired) {
                    if (pendingBackPress) {
                        // Second tap within window → double-tap → open recents
                        pendingBackPress = false
                        handler.removeCallbacks(singleBackRunnable)
                        Log.d(TAG, "Double-tap → recents")
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                    } else {
                        // First tap → wait for possible second tap
                        pendingBackPress = true
                        handler.postDelayed(singleBackRunnable, doubleTapWindowMs)
                    }
                }
                swapFired = false
                return true // always consume UP
            }
        }
        return false
    }

    private fun vibrateShort() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed: ${e.message}")
        }
    }

    // ── swap / send logic ────────────────────────────────────────────────

    private data class Move(val pkg: String, val fromDisplay: Int, val toDisplay: Int)

    private fun performSwapOrSend() {
        refreshDisplays()
        scanAllDisplayApps() // fresh scan from all displays before acting
        val ids = availableDisplayIds
        if (ids.size < 2) {
            Log.w(TAG, "Only ${ids.size} display(s) — nothing to do")
            return
        }

        val d0 = ids[0]
        val d1 = ids[1]
        // Validate tracked apps are actually launchable (not uninstalled/closed ghosts)
        var app0 = displayApps[d0]
        var app1 = displayApps[d1]
        if (app0 != null && packageManager.getLaunchIntentForPackage(app0) == null) {
            Log.w(TAG, "Tracked app $app0 on display $d0 has no launch intent — dropping")
            displayApps.remove(d0)
            app0 = null
        }
        if (app1 != null && packageManager.getLaunchIntentForPackage(app1) == null) {
            Log.w(TAG, "Tracked app $app1 on display $d1 has no launch intent — dropping")
            displayApps.remove(d1)
            app1 = null
        }

        Log.d(TAG, "State: display $d0→$app0, display $d1→$app1")

        // Duplicate from MULTIPLE_TASK: same app on both displays — clean up
        if (app0 != null && app0 == app1) {
            Log.w(TAG, "Duplicate $app0 on both displays — force-stop + relaunch on d$d1")
            launchOnDisplay(app0, d1, aggressive = true)
            Thread.sleep(1500)
            scanAllDisplayApps()
            Log.d(TAG, "After cleanup: d$d0→${displayApps[d0]}, d$d1→${displayApps[d1]}")
            return
        }

        // Build list of intended moves
        val moves: List<Move>
        when {
            app0 != null && app1 != null -> {
                Log.d(TAG, "Swap: $app0 \u2194 $app1")
                moves = listOf(Move(app0, d0, d1), Move(app1, d1, d0))
            }
            app0 != null -> {
                Log.d(TAG, "Send: $app0 \u2192 display $d1")
                moves = listOf(Move(app0, d0, d1))
            }
            app1 != null -> {
                Log.d(TAG, "Send: $app1 \u2192 display $d0")
                moves = listOf(Move(app1, d1, d0))
            }
            else -> {
                Log.w(TAG, "No tracked apps on any display")
                return
            }
        }

        // ── Phase 1: gentle launches ──
        val shizukuReady = ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()
        for ((i, move) in moves.withIndex()) {
            if (i > 0) Thread.sleep(if (shizukuReady) 100 else 300)
            launchOnDisplay(move.pkg, move.toDisplay, aggressive = false)
        }

        // ── Phase 2: verify after delay ──
        // Shizuku moves are instant shell commands; trampoline needs more time
        Thread.sleep(if (shizukuReady) 800 else 1500)
        scanAllDisplayApps()

        val clean = mutableSetOf<Move>()
        val duplicates = mutableListOf<Move>()
        val failed = mutableListOf<Move>()
        val isSend = moves.size == 1
        for (move in moves) {
            val onTarget = displayApps[move.toDisplay] == move.pkg
            val onSource = displayApps[move.fromDisplay] == move.pkg
            when {
                onTarget && !onSource -> {
                    Log.d(TAG, "Verify OK: ${move.pkg} clean on display ${move.toDisplay}")
                    clean.add(move)
                }
                onTarget && onSource -> {
                    Log.w(TAG, "Verify DUPLICATE: ${move.pkg} on BOTH display " +
                        "${move.fromDisplay} and ${move.toDisplay}")
                    duplicates.add(move)
                }
                else -> {
                    Log.w(TAG, "Verify FAIL: ${move.pkg} NOT on display ${move.toDisplay} " +
                        "(found: ${displayApps[move.toDisplay]})")
                    failed.add(move)
                }
            }
        }

        // Handle duplicates: app IS on target, just also lingers on source.
        // For sends: accept — source will be covered by launcher, no action needed.
        // For swaps: companion app should cover source; if not, skip to aggressive.
        val needsAggressive = mutableListOf<Move>()
        for (move in duplicates) {
            if (isSend) {
                Log.d(TAG, "DUPLICATE (send): ${move.pkg} on target — accepting, " +
                    "source covered by launcher")
                clean.add(move)
            } else {
                val companionCoversSource = moves.any {
                    it != move && it.toDisplay == move.fromDisplay && it in clean
                }
                if (companionCoversSource) {
                    Log.d(TAG, "DUPLICATE (swap): ${move.pkg} — companion covers source, accepting")
                    clean.add(move)
                } else {
                    Log.w(TAG, "DUPLICATE (swap): ${move.pkg} — companion didn't cover source, " +
                        "needs aggressive cleanup")
                    needsAggressive.add(move)
                }
            }
        }

        // ── Phase 3: gentle retry for moves that haven't landed yet ──
        // Some apps (RetroArch) are slow to appear on the target display.
        // Re-attempt with gentle launch to preserve app state before force-stopping.
        // Only for genuine failures — duplicates already on target skip this.
        val needsForceRetry = mutableListOf<Move>()
        if (failed.isNotEmpty()) {
            for ((i, move) in failed.withIndex()) {
                if (i > 0) Thread.sleep(300)
                Log.d(TAG, "Gentle retry: ${move.pkg} → display ${move.toDisplay}")
                launchOnDisplay(move.pkg, move.toDisplay, aggressive = false)
            }
            Thread.sleep(if (shizukuReady) 1000 else 2000)
            scanAllDisplayApps()

            for (move in failed) {
                val onTarget = displayApps[move.toDisplay] == move.pkg
                if (onTarget) {
                    Log.d(TAG, "Gentle retry OK: ${move.pkg} now on display ${move.toDisplay}")
                    clean.add(move)
                } else {
                    Log.w(TAG, "Gentle retry FAIL: ${move.pkg} still not on display ${move.toDisplay}")
                    needsForceRetry.add(move)
                }
            }
        }
        // Merge: duplicates that need aggressive go straight to force retry
        needsForceRetry.addAll(needsAggressive)

        // ── Phase 4: aggressive retry (force-stop + relaunch) as last resort ──
        // Force-stop kills ALL instances (including duplicates), then CLEAR_TASK
        // relaunches a single fresh instance on the target display.
        for ((i, move) in needsForceRetry.withIndex()) {
            val stillOnSource = displayApps[move.fromDisplay] == move.pkg
            val companionCoversSource = moves.any { it != move && it.toDisplay == move.fromDisplay && it in clean }
            if (stillOnSource && !isSend && !companionCoversSource) {
                Log.w(TAG, "${move.pkg} still on display ${move.fromDisplay}, " +
                    "companion didn't cover it — skipping aggressive")
                continue
            }
            if (i > 0) Thread.sleep(300)
            Log.d(TAG, "Aggressive retry: ${move.pkg} → display ${move.toDisplay} " +
                "(send=$isSend, companionCovers=$companionCoversSource)")
            launchOnDisplay(move.pkg, move.toDisplay, aggressive = true)
            clean.add(move)
        }

        // Update tracking: build final state first to avoid swap conflicts
        // (sequential remove+put would cause second move to delete first move's result)
        val finalState = mutableMapOf<Int, String?>()
        for (move in clean) {
            finalState[move.fromDisplay] = null
            finalState[move.toDisplay] = move.pkg
        }
        for ((displayId, pkg) in finalState) {
            if (pkg != null) displayApps[displayId] = pkg
            else displayApps.remove(displayId)
        }
    }

    private fun launchOnDisplay(pkg: String, targetDisplayId: Int, aggressive: Boolean) {
        val shizukuReady = ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()

        if (aggressive) {
            // Shizuku force-stop (shell UID) actually kills the app;
            // without it, killBackgroundProcesses only kills background apps.
            if (shizukuReady) {
                Log.d(TAG, "Aggressive: Shizuku force-stop $pkg")
                ShizukuHelper.forceStop(pkg)
            } else {
                Log.d(TAG, "Aggressive: fallback kill $pkg")
                tryKillApp(pkg)
            }
            Thread.sleep(500)
        }

        // Prefer Shizuku 'am start --display' for gentle launches:
        // - reuses existing task (no duplicate instances like MULTIPLE_TASK)
        // - works for singleTask apps (SmartTube) that ignore trampoline hints
        if (!aggressive && shizukuReady) {
            val ok = ShizukuHelper.startOnDisplay(this, pkg, targetDisplayId)
            if (ok) {
                Log.i(TAG, "Shizuku am start → $pkg → display $targetDisplayId")
                return
            }
            Log.w(TAG, "Shizuku am start failed for $pkg, falling back to trampoline")
        }

        // Trampoline fallback — CLEAR_TASK for aggressive, MULTIPLE_TASK for gentle
        val trampolineIntent = Intent(this, TrampolineActivity::class.java).apply {
            putExtra(TrampolineActivity.EXTRA_TARGET_PKG, pkg)
            putExtra(TrampolineActivity.EXTRA_TARGET_DISPLAY, targetDisplayId)
            putExtra(TrampolineActivity.EXTRA_AGGRESSIVE, aggressive)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        try {
            val opts = ActivityOptions.makeBasic()
            opts.setLaunchDisplayId(targetDisplayId)
            startActivity(trampolineIntent, opts.toBundle())
            Log.i(TAG, "${if (aggressive) "AGGRESSIVE" else "Gentle"} trampoline → $pkg → display $targetDisplayId")
        } catch (e: Exception) {
            Log.e(TAG, "Trampoline launch failed for $pkg: ${e.message}", e)
        }
    }

    private fun tryKillApp(pkg: String) {
        // killBackgroundProcesses is the only kill available without Shizuku/root.
        // Only kills background apps — foreground apps survive, but it's our best effort.
        try {
            val am = getSystemService(ActivityManager::class.java)
            am.killBackgroundProcesses(pkg)
            Log.d(TAG, "killBackgroundProcesses $pkg called")
        } catch (e: Exception) {
            Log.d(TAG, "killBackgroundProcesses $pkg: ${e.message}")
        }
    }

    private fun refreshDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        availableDisplayIds = dm.displays.map { it.displayId }
    }

    private fun detectLaunchers() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        // FallbackHome in com.android.settings responds to CATEGORY_HOME but isn't a real launcher
        val falsePositives = setOf("com.android.settings", "com.android.permissioncontroller")
        val launchers = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        for (ri in launchers) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in falsePositives) continue
            launcherPackages.add(pkg)
            ignoredPackages.add(pkg)
        }
        Log.d(TAG, "Detected launchers: $launcherPackages")
    }
}
