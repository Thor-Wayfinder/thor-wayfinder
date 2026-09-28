package app.wayfinder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sleep & standby. While the screen is off, turn off what drains the battery for
 * nothing (Wi-Fi, Bluetooth, a VPN, the audio DSP, file sync) and put back ONLY what we
 * turned off when it wakes; measure every sleep; keep the Thor asleep with its lid
 * closed. Everything goes through public commands (root `cmd wifi …`,
 * `cmd bluetooth_manager …`, `input keyevent KEYCODE_SLEEP`) or each app's own
 * documented automation intent.
 */
object SleepSettings {
    private lateinit var prefs: android.content.SharedPreferences
    val version = mutableIntStateOf(0)

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("thor_sleep", Context.MODE_PRIVATE)
        otherSleepApp = findOtherSleepApp(ctx)
    }

    /** Another app that manages sleep / the lid is installed → ours stays hands-off by
     *  default (two apps toggling radios or forcing sleep would fight). Found by label or
     *  package name; never shown by name. */
    var otherSleepApp: String? = null
        private set

    private fun findOtherSleepApp(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .firstOrNull { ai ->
                ai.packageName != ctx.packageName && (ai.packageName.contains("sleepmanager", true) ||
                    pm.getApplicationLabel(ai).toString().replace(" ", "").contains("sleepmanager", true))
            }?.packageName
    }.getOrNull()

    private fun b(k: String, d: Boolean) = if (::prefs.isInitialized) prefs.getBoolean(k, d) else d
    private fun i(k: String, d: Int) = if (::prefs.isInitialized) prefs.getInt(k, d) else d
    fun set(k: String, v: Boolean) { prefs.edit().putBoolean(k, v).apply(); version.intValue++ }
    fun set(k: String, v: Int) { prefs.edit().putInt(k, v).apply(); version.intValue++ }

    val enabled get() = b("enabled", false)
    val wifi get() = b("wifi", true)
    val bluetooth get() = b("bluetooth", false)       // off by default: controllers / earbuds
    val tailscale get() = b("tailscale", true)
    val jamesDsp get() = b("jamesdsp", false)
    val syncthing get() = b("syncthing", true)
    /** Seconds after screen-off before acting. */
    val graceSec get() = i("grace", 10)
    /** Only below/above: 0 = no battery condition; else only when battery ≤ this %. */
    val maxBattery get() = i("max_battery", 0)
    val notCharging get() = b("not_charging", false)
    val batterySaverOnly get() = b("saver_only", false)
    /** Schedule: -1 = always; else hours [from, to) (wrapping past midnight). */
    val fromHour get() = i("from_hour", -1)
    val toHour get() = i("to_hour", 7)
    val lidProtection get() = b("lid", otherSleepApp == null)
    val pauseOnLid get() = b("lid_pause", true)
    val sleepOnDisplayGone get() = b("display_gone", false)

    val GRACE_OPTIONS = listOf(0, 5, 10, 60, 300, 600, 1800)
    fun graceLabel(s: Int) = when { s == 0 -> "Now"; s < 60 -> "$s s"; else -> "${s / 60} min" }
}

/** One measured sleep. */
data class SleepSession(val end: Long, val durMs: Long, val pctDrop: Float, val mAh: Int?, val deepPct: Int) {
    fun toJson() = JSONObject().put("end", end).put("dur", durMs).put("drop", pctDrop.toDouble()).put("mah", mAh ?: -1).put("deep", deepPct)
    val drainPerHour get() = if (durMs > 0) pctDrop / (durMs / 3_600_000f) else 0f
    companion object {
        fun fromJson(o: JSONObject) = SleepSession(o.getLong("end"), o.getLong("dur"), o.getDouble("drop").toFloat(),
            o.getInt("mah").takeIf { it >= 0 }, o.getInt("deep"))
    }
}

object SleepEngine {
    private const val TAG = "ThorSleep"
    private const val ACTION_ALARM = "app.wayfinder.SLEEP_ALARM"
    private const val MIN_STATS_MS = 3 * 3_600_000L   // only long sleeps count for drain figures
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { app.getSharedPreferences("thor_sleep_state", Context.MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile var lidClosed = false
        private set
    private var started = false

    // ── lifecycle ────────────────────────────────────────────────────────
    fun start(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        SleepSettings.init(app)
        app.registerReceiver(alarmReceiver, IntentFilter(ACTION_ALARM), Context.RECEIVER_NOT_EXPORTED)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        refreshExternal()
        // Exact alarms for the 1–30 min delays: special access, granted through the root
        // bridge (plug and play — no trip to "Alarms & reminders").
        if (!app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            Thread { PServiceBridge.exec("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow") }.start()
        // `dumpsys window` lists a lid state per display policy (LID_ABSENT, LID_OPEN…).
        Thread { lidClosed = PServiceBridge.exec("dumpsys window | grep -c mLidState=LID_CLOSED")?.trim()?.toIntOrNull()?.let { it > 0 } == true }.start()
        started = true
        // A sleep that was running when we died (the battery ran out overnight, a reboot): with
        // the screen already on no screen-on comes — put the radios back now (review 2026-09-25)
        if (prefs.getBoolean("asleep", false) && app.getSystemService(PowerManager::class.java).isInteractive)
            Thread { log("Started while marked asleep — waking up"); wakeUp() }.start()
    }

    // ── screen off / on (from the service's screen receiver) ─────────────
    fun onScreenOff() {
        if (!started) return
        beginSession()
        if (!SleepSettings.enabled) return
        val why = blockReason()
        if (why != null) { log("Screen off — no sleep actions: $why"); return }
        val g = SleepSettings.graceSec
        if (g <= 30) {
            // Short grace: hold a partial wake lock so it runs before the CPU suspends.
            wakeLock = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wayfinder:sleep").apply { acquire((g + 20) * 1000L) }
            main.postDelayed(goToSleep, g * 1000L)
        } else {
            // Exact: an inexact alarm may land up to ~75 % late ("1 min" became 1 min 42 s).
            val am = app.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + g * 1000L
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
        }
        log("Screen off — sleep actions in ${SleepSettings.graceLabel(g)}")
    }

    fun onScreenOn() {
        if (!started) return
        main.removeCallbacks(goToSleep)
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
        releaseWakeLock()
        endSession()
        if (prefs.getBoolean("asleep", false)) Thread { wakeUp() }.start()
        maybeLidSleep("screen turned on")
    }

    // ── lid (hall sensor, from the root input helper) ────────────────────
    fun onLid(closed: Boolean) {
        lidClosed = closed
        log(if (closed) "Lid closed" else "Lid opened")
        main.removeCallbacks(lidGuard); guardTries = 0; hotLogged = false
        if (!closed && hotDropped) {
            hotDropped = false
            val mode = Settings.System.getInt(app.contentResolver, "performance_mode", 0).coerceIn(0, 2)
            Thread { PServiceBridge.exec("setprop persist.vendor.debug.mode $mode") }.start()
            PerfProfiles.forget(); ForegroundAppService.reapplyPolicy()   // a game's own mode comes back too
            log("Lid opened — performance back to normal")
        }
        if (!closed) return
        if (SleepSettings.pauseOnLid && !externalDisplay()) pauseMedia()
        main.postDelayed({ maybeLidSleep("lid closed") }, 1500)
        main.postDelayed(lidGuard, GUARD_MS)
    }

    private fun pauseMedia() {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        if (!am.isMusicActive) return
        for (a in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(a, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        log("Lid closed — paused what was playing")
    }

    // ── lid guard: airtight in a case ─────────────────────────────────────
    // A single "go to sleep" isn't enough: an app can hold the screen on, a key press can
    // land again, and a Thor awake in a closed case burns its OLED and cooks. While the lid
    // is closed (and not docked) this checks every few seconds: screen on → sleep again
    // (after 3 misses, the power key); running hot → stop the music, performance Standard.
    private const val GUARD_MS = 5_000L
    private const val HOT_C = 45f
    private var guardTries = 0
    private var hotLogged = false
    /** The heat guard dropped performance: undone when the lid opens (it stayed at Standard —
     *  a persist prop, even across a reboot — review 2026-09-25). */
    @Volatile private var hotDropped = false
    private val lidGuard: Runnable = object : Runnable {
        override fun run() {
            if (!lidClosed || !SleepSettings.lidProtection) return
            if (externalDisplay()) { main.postDelayed(this, GUARD_MS * 4); return }
            val pm = app.getSystemService(PowerManager::class.java)
            if (pm.isInteractive) {
                guardTries++
                val key = if (guardTries > 3) "KEYCODE_POWER" else "KEYCODE_SLEEP"
                log("Lid guard: screen still on in the case (try $guardTries) — $key")
                Thread { PServiceBridge.exec("input keyevent $key") }.start()
            } else guardTries = 0
            val t = batteryTempC()
            if (t != null && t >= HOT_C) {
                if (!hotLogged) {
                    // Once per hot spell. Only the PROP (what acts): writing the performance
                    // SETTING to Standard makes AYN drop the fan to Quiet — less cooling (§2).
                    log("Lid guard: ${"%.1f".format(t)} °C in the case — stopping the music, performance to Standard")
                    hotLogged = true
                    pauseMedia()
                    hotDropped = true
                    Thread { PServiceBridge.exec("setprop persist.vendor.debug.mode 0") }.start()
                }
            } else if (t != null && t < HOT_C - 3) hotLogged = false
            // (Runs only while the CPU is awake — asleep, nothing heats up.)
            main.postDelayed(this, GUARD_MS)
        }
    }

    private fun batteryTempC(): Float? = runCatching {
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
    }.getOrNull()

    /** Screen on with the lid closed (a controller press, a notification, the power
     *  button in a bag…) → back to sleep — unless docked on an external display. */
    private fun maybeLidSleep(why: String) {
        if (!SleepSettings.lidProtection || !lidClosed) return
        if (externalDisplay()) { log("Lid closed but an external display is connected — staying awake (docked)"); return }
        main.postDelayed({
            val pm = app.getSystemService(PowerManager::class.java)
            if (lidClosed && pm.isInteractive) {
                log("Screen on with the lid closed ($why) — back to sleep")
                Thread { PServiceBridge.exec("input keyevent KEYCODE_SLEEP") }.start()
            }
        }, 1200)
    }

    // A removed display can't be inspected any more → remember the external ones.
    private val externalIds = java.util.Collections.synchronizedSet(HashSet<Int>())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { refreshExternal() }
        override fun onDisplayChanged(id: Int) {}
        override fun onDisplayRemoved(id: Int) {
            val wasExternal = externalIds.remove(id)
            if (!wasExternal) return
            log("External display disconnected")
            if (SleepSettings.sleepOnDisplayGone && externalIds.isEmpty()) {
                log("…going to sleep (setting on)")
                Thread { PServiceBridge.exec("input keyevent KEYCODE_SLEEP") }.start()
            }
        }
    }

    /** An external monitor / dock: a public display that isn't one of the Thor's own two
     *  panels — named "Built-in Screen" (0) and "Screen-2" by AYN's ROM. */
    private fun isExternal(d: android.view.Display): Boolean {
        val n = d.name.orEmpty()
        return d.displayId != 0 && d.isValid && (d.flags and android.view.Display.FLAG_PRIVATE) == 0 &&
            !n.startsWith("Built-in", true) && !n.equals("Screen-2", true) && !n.contains("Overlay", true)
    }

    private fun refreshExternal() {
        app.getSystemService(DisplayManager::class.java).displays.filter { isExternal(it) }.forEach { externalIds.add(it.displayId) }
    }

    private fun externalDisplay(): Boolean { refreshExternal(); return externalIds.isNotEmpty() }

    // ── conditions ───────────────────────────────────────────────────────
    private fun blockReason(): String? {
        val bm = app.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (SleepSettings.maxBattery in 1 until 100 && pct > SleepSettings.maxBattery) return "battery $pct % > ${SleepSettings.maxBattery} %"
        if (SleepSettings.notCharging && bm.isCharging) return "charging"
        if (SleepSettings.batterySaverOnly && !app.getSystemService(PowerManager::class.java).isPowerSaveMode) return "Battery Saver is off"
        val from = SleepSettings.fromHour
        if (from >= 0) {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val to = SleepSettings.toHour
            val inWindow = if (from <= to) h in from until to else h >= from || h < to
            if (!inWindow) return "outside the schedule (${from}:00–${to}:00)"
        }
        if (externalDisplay()) return "docked on an external display"
        return null
    }

    // ── the actions ──────────────────────────────────────────────────────
    private val goToSleep = Runnable { Thread { sleepActions(); releaseWakeLock() }.start() }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (app.getSystemService(PowerManager::class.java).isInteractive) return
            val wl = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wayfinder:sleepAlarm")
            wl.acquire(20_000)
            Thread { try { sleepActions() } finally { wl.release() } }.start()
        }
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(app, 7, Intent(ACTION_ALARM).setPackage(app.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun releaseWakeLock() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    @Synchronized private fun sleepActions() {
        if (app.getSystemService(PowerManager::class.java).isInteractive) return   // woke during the grace
        val did = mutableListOf<String>()
        val cr = app.contentResolver
        if (SleepSettings.wifi) {
            val on = app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled
            when {
                Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 -> log("Wi-Fi: airplane mode is on — left alone")
                !on -> log("Wi-Fi: already off — nothing to do")
                else -> {
                    PServiceBridge.exec("cmd wifi set-wifi-enabled disabled")
                    did += "wifi"   // recorded even if slow to confirm: it can go off late, and must come back
                    if (waitFor { !app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled }) log("Wi-Fi: turned off")
                    else log("Wi-Fi: turning off not confirmed yet")
                }
            }
        }
        if (SleepSettings.bluetooth) {
            if (Settings.Global.getInt(cr, "bluetooth_on", 0) == 0) log("Bluetooth: already off — nothing to do")
            else {
                PServiceBridge.exec("cmd bluetooth_manager disable")
                did += "bt"
                if (waitFor { Settings.Global.getInt(cr, "bluetooth_on", 1) == 0 }) log("Bluetooth: turned off")
                else log("Bluetooth: turning off not confirmed yet")
            }
        }
        if (SleepSettings.tailscale && installed(TAILSCALE)) {
            if (!vpnActive()) log("Tailscale: not connected — nothing to do")
            else {
                sendTailscale(false)
                if (waitFor(6000) { !vpnActive() }) { did += "tailscale"; log("Tailscale: disconnected") }
                else log("Tailscale: disconnect FAILED (its intent didn't work)")
            }
        }
        // (Not while Wayfinder's speaker fix is on: it has the DSP app paused already — turning
        // it "back on" at wake would stack two EQs.)
        // only what is RUNNING: waking used to start them even when the user had them off (review)
        if (SleepSettings.jamesDsp && installed(JAMESDSP) && !SpeakerTune.enabled && running(JAMESDSP)) {
            sendJamesDsp(false); did += "jamesdsp"; log("JamesDSP: turned off")
        }
        if (SleepSettings.syncthing) SYNCTHING.filter { installed(it) && running(it) }.forEach { p ->
            app.sendBroadcast(Intent("$p.action.STOP").setPackage(p)); did += "sync:$p"; log("Syncthing ($p): stop sent")
        }
        // MERGE with what an earlier, interrupted run recorded (else a radio it turned off is
        // "already off" now and would never come back), and commit before looking at the screen.
        val before = prefs.getString("did", "").orEmpty().split(',').filter { it.isNotBlank() }
        prefs.edit().putBoolean("asleep", true).putString("did", (before + did).distinct().joinToString(",")).commit()
        // Woken while we were at it (the actions take up to ~12 s): undo right away — the
        // screen-on handler already ran and found nothing to restore.
        if (app.getSystemService(PowerManager::class.java).isInteractive) { log("Woke up during the sleep actions — undoing"); wakeUp() }
    }

    private fun running(pkg: String): Boolean =
        Shell.isPkg(pkg) && !PServiceBridge.exec("pidof $pkg").isNullOrBlank()

    private fun wakeUp() {
        val did = prefs.getString("did", "").orEmpty().split(',').filter { it.isNotBlank() }
        prefs.edit().putBoolean("asleep", false).remove("did").apply()
        if (did.isEmpty()) return
        for (d in did) when {
            d == "wifi" -> { PServiceBridge.exec("cmd wifi set-wifi-enabled enabled"); log("Wi-Fi: back on") }
            d == "bt" -> { PServiceBridge.exec("cmd bluetooth_manager enable"); log("Bluetooth: back on") }
            d == "tailscale" -> {
                // Only once there's a network to reconnect over.
                waitFor(15_000) { networkUp() }
                sendTailscale(true)
                log(if (waitFor(8000) { vpnActive() }) "Tailscale: reconnected" else "Tailscale: reconnect sent (not confirmed)")
            }
            d == "jamesdsp" -> { sendJamesDsp(true); log("JamesDSP: back on") }
            d.startsWith("sync:") -> {
                val p = d.removePrefix("sync:")
                waitFor(15_000) { networkUp() }
                app.sendBroadcast(Intent("$p.action.START").setPackage(p)); log("Syncthing ($p): start sent")
            }
        }
    }

    // ── integrations (each app's own public automation intent) ───────────
    private const val TAILSCALE = "com.tailscale.ipn"
    private const val JAMESDSP = "james.dsp"
    private val SYNCTHING = listOf("com.nutomic.syncthingandroid", "com.github.catfriend1.syncthingandroid")

    private fun sendTailscale(connect: Boolean) = app.sendBroadcast(
        Intent(if (connect) "com.tailscale.ipn.CONNECT_VPN" else "com.tailscale.ipn.DISCONNECT_VPN")
            .setClassName(TAILSCALE, "com.tailscale.ipn.IPNReceiver"))

    private fun sendJamesDsp(on: Boolean) {
        val action = "me.timschneeberger.rootlessjamesdsp.SET_POWER_STATE"
        for (p in listOf(JAMESDSP, "me.timschneeberger.rootlessjamesdsp").filter { installed(it) })
            app.sendBroadcast(Intent(action).setPackage(p).putExtra("rootlessjamesdsp.enabled", on))
    }

    private fun installed(p: String) = runCatching { app.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)

    private fun vpnActive(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun networkUp(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun waitFor(ms: Long = 3000, ok: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (runCatching(ok).getOrDefault(false)) return true; Thread.sleep(200) }
        return runCatching(ok).getOrDefault(false)
    }

    // ── measuring sleeps ─────────────────────────────────────────────────
    private fun battery(): Pair<Float, Int?> {
        val bm = app.getSystemService(BatteryManager::class.java)
        val uAh = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        val full = fullCapacityUah()
        val pct = if (uAh != null && full != null) 100f * uAh / full else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        return pct to uAh
    }

    /** Learned full-charge capacity (µAh), else the design capacity. */
    fun fullCapacityUah(): Long? = listOf("charge_full", "charge_full_design").firstNotNullOfOrNull {
        runCatching { java.io.File("/sys/class/power_supply/battery/$it").readText().trim().toLong() }.getOrNull()?.takeIf { v -> v > 0 }
    }

    private fun beginSession() {
        val (pct, uAh) = battery()
        prefs.edit().putLong("s_real", SystemClock.elapsedRealtime()).putLong("s_up", SystemClock.uptimeMillis())
            .putFloat("s_pct", pct).putLong("s_uah", uAh?.toLong() ?: -1)
            .putBoolean("s_charging", app.getSystemService(BatteryManager::class.java).isCharging).apply()
    }

    private fun endSession() {
        val r0 = prefs.getLong("s_real", -1)
        if (r0 < 0) return
        val dur = SystemClock.elapsedRealtime() - r0
        val upDur = SystemClock.uptimeMillis() - prefs.getLong("s_up", 0)
        val charging = prefs.getBoolean("s_charging", false) || app.getSystemService(BatteryManager::class.java).isCharging
        prefs.edit().remove("s_real").apply()
        if (dur < 60_000 || charging || upDur < 0) return            // too short, or on the charger
        val (pct, uAh) = battery()
        val u0 = prefs.getLong("s_uah", -1)
        val s = SleepSession(
            end = System.currentTimeMillis(), durMs = dur,
            pctDrop = (prefs.getFloat("s_pct", pct) - pct).coerceAtLeast(0f),
            mAh = if (u0 > 0 && uAh != null) ((u0 - uAh) / 1000).toInt().coerceAtLeast(0) else null,
            deepPct = (100 * (dur - upDur) / dur).toInt().coerceIn(0, 100),
        )
        val list = sessions().toMutableList().apply { add(0, s) }.take(60)
        prefs.edit().putString("sessions", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        log("Slept ${fmtDur(dur)}: −${"%.1f".format(s.pctDrop)} %${s.mAh?.let { " ($it mAh)" } ?: ""}, deep sleep ${s.deepPct} %")
        SleepSettings.version.intValue++
    }

    fun sessions(): List<SleepSession> = runCatching {
        val a = JSONArray(prefs.getString("sessions", "[]"))
        (0 until a.length()).map { SleepSession.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Long sleeps of the last 7 days (the ones drain figures use). */
    fun qualifying(): List<SleepSession> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        return sessions().filter { it.durMs >= MIN_STATS_MS && it.end >= since }
    }

    fun fmtDur(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }

    // ── activity log ─────────────────────────────────────────────────────
    fun log(msg: String) {
        Log.d(TAG, msg)
        if (!::app.isInitialized) return
        val t = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val a = runCatching { JSONArray(prefs.getString("log", "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray().put("$t  $msg")
        for (i in 0 until minOf(a.length(), 79)) out.put(a.getString(i))
        prefs.edit().putString("log", out.toString()).apply()
        SleepSettings.version.intValue++
    }

    fun logLines(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("log", "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}
