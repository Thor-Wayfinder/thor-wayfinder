package app.wayfinder

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.wayfinder.lights.LightMode
import app.wayfinder.lights.LightProfile
import app.wayfinder.lights.LightSettings
import app.wayfinder.lights.StickLights

/**
 * Every shortcut the quick panel can show (Controller → Quick panel shortcuts): Wayfinder's
 * own, Android's quick settings, and the Thor switches AYN's drawer has. The user picks
 * which, and in what order. Switches that Android or AYN apply by themselves are written
 * through root (`settings put …`, `cmd …`) — the same keys their own tiles write.
 */
object PanelShortcuts {
    enum class Group(val title: String) { WAYFINDER("Wayfinder"), ANDROID("Android"), THOR("Thor"), OPEN("Your shortcut") }

    /** [pkg]: an app tile — its own icon is drawn instead of [icon]. */
    class Info(val icon: ImageVector, val name: String, val what: String, val group: Group, val pkg: String? = null)

    /** 1.4 (Reddit): a tile that opens an app, an app pair or a Wayfinder page — its id is the "Open…" target. */
    fun isOpenTile(id: String): Boolean = id !in CATALOG && OpenTargets.valid(id) &&
        (!id.startsWith("pair:") || Layouts.pairs.any { it.id == id.removePrefix("pair:").toLongOrNull() })

    private fun appName(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** A tile's info: the catalog's, or an "Open…" tile's (null: gone — an app uninstalled, a pair deleted). */
    fun info(ctx: Context, id: String): Info? = CATALOG[id] ?: if (!isOpenTile(id)) null else when {
        OpenTargets.isApp(id) -> appName(ctx, OpenTargets.appPkg(id))?.let {
            Info(Icons.Rounded.Apps, it, "Opens $it on the screen with the controller.", Group.OPEN, OpenTargets.appPkg(id)) }
        id.startsWith("pair:") -> Info(Icons.Rounded.ViewAgenda, OpenTargets.label(ctx, id).removePrefix("Open the pair "),
            "Opens this app pair — each app on its screen.", Group.OPEN)
        else -> OpenTargets.label(ctx, id).removePrefix("Open Wayfinder: ").let {
            Info(Icons.Rounded.Explore, it, "Opens Wayfinder's “$it” page.", Group.OPEN) }
    }

    /** id → what the Hub's picker shows. Ids are stored — never rename one. */
    val CATALOG: Map<String, Info> = linkedMapOf(
        // ── Wayfinder
        "swap" to Info(Icons.Rounded.SwapVert, "Move / swap apps", "Moves the app on the controller's screen to the other screen — or swaps the two apps when both screens have one.", Group.WAYFINDER),
        "deck" to Info(Icons.Rounded.Keyboard, "Keyboard & mouse", "Puts keys and a trackpad on the other screen, for the game — for PC games and apps that need a keyboard or mouse.", Group.WAYFINDER),
        "controls" to Info(Icons.Rounded.Tune, "Game controls", "Opens the buttons, gyro and macros of the game you're playing — change them without leaving the game. Press again (or B) to go back to it.", Group.WAYFINDER),
        "lock" to Info(Icons.Rounded.Lock, "Controller lock", "Keeps the controller on the top or the bottom screen, so touching the other screen doesn't move it. Each press switches (and, unless the controller stays where you send it, the third press unlocks it). More choices in Controller.", Group.WAYFINDER),
        "fps" to Info(Icons.Rounded.Timeline, "Frame rate", "A small counter over the game — each press: frames per second, then + battery, then + temperatures, then hidden.", Group.WAYFINDER),
        "shot" to Info(Icons.Rounded.PhotoCamera, "Screenshot", "Takes a screenshot — of the top, the bottom or both screens (you choose which in Wayfinder).", Group.WAYFINDER),
        "recents" to Info(Icons.Rounded.GridView, "Recent apps", "Opens Android's recent-apps view on the screen with the controller.", Group.WAYFINDER),
        "clear" to Info(Icons.Rounded.DeleteSweep, "Close background apps", "Closes the apps running in the background to free memory. The apps showing on the two screens stay open.", Group.WAYFINDER),
        "bottomoff" to Info(Icons.Rounded.DarkMode, "Bottom screen off", "Turns the bottom screen off to save battery while the top keeps running. Touch it to turn it back on.", Group.WAYFINDER),
        "guide" to Info(Icons.Rounded.MenuBook, "Guide & notes", "The game's guide page and your notes — on the other screen, or beside the game when there's only one.", Group.WAYFINDER),
        "sleep" to Info(Icons.Rounded.Bedtime, "Sleep", "Puts the Thor to sleep — both screens off, like a press on the power button.", Group.WAYFINDER),
        "keepon" to Info(Icons.Rounded.Coffee, "Stay awake", "The screens don't turn off on their own — for maps, videos, guides. The power button still works.", Group.WAYFINDER),
        "speaker" to Info(Icons.Rounded.GraphicEq, "Speaker sound fix", "Makes the built-in speakers clearer and louder (Wayfinder's equalizer). Headphones are left alone.", Group.WAYFINDER),
        "boost" to Info(Icons.Rounded.VolumeUp, "Volume boost", "Louder than the maximum: off, +6 dB, +12 dB (a limiter keeps it clean). Every output — start low with headphones.", Group.WAYFINDER),
        "speakereq" to Info(Icons.Rounded.Equalizer, "Speaker EQ", "The speaker fix's equalizer and loudness on or off — hear the difference (the stereo widener stays as set).", Group.WAYFINDER),
        "lights" to Info(Icons.Rounded.Lightbulb, "Stick light settings", "Opens the stick-lights page: colours, effects, lighting per game.", Group.WAYFINDER),
        "lightsonoff" to Info(Icons.Rounded.FlashlightOn, "Stick lights on/off", "Turns the lights around the sticks off — and back on to the lighting you had.", Group.WAYFINDER),
        "hub" to Info(Icons.Rounded.Home, "Wayfinder", "Opens Wayfinder's main screen.", Group.WAYFINDER),
        "pairs" to Info(Icons.Rounded.ViewAgenda, "App pairs", "Your app pairs: pick one to open both apps, each on its screen — or save the two apps on the screens right now as a new pair.", Group.WAYFINDER),
        // ── Android quick settings
        "wifi" to Info(Icons.Rounded.Wifi, "Wi-Fi", "Turns Wi-Fi on or off.", Group.ANDROID),
        "bt" to Info(Icons.Rounded.Bluetooth, "Bluetooth", "Turns Bluetooth on or off (wireless headphones, controllers…).", Group.ANDROID),
        "airplane" to Info(Icons.Rounded.AirplanemodeActive, "Airplane mode", "Turns Wi-Fi and Bluetooth off at once.", Group.ANDROID),
        "dnd" to Info(Icons.Rounded.DoNotDisturbOn, "Do not disturb", "Silences notification sounds and pop-ups. Games, music and alarms still play.", Group.ANDROID),
        "rotate" to Info(Icons.Rounded.ScreenRotation, "Auto-rotate", "Lets apps turn when you turn the Thor. Off: apps stay the way they are.", Group.ANDROID),
        "location" to Info(Icons.Rounded.LocationOn, "Location", "Lets apps know where you are (maps, weather…).", Group.ANDROID),
        "saver" to Info(Icons.Rounded.BatterySaver, "Battery saver", "Android's battery saver: slows background activity to last longer. Android doesn't allow it while charging.", Group.ANDROID),
        "dark" to Info(Icons.Rounded.Contrast, "Dark theme", "Switches Android — and the apps that follow it — to dark colours.", Group.ANDROID),
        "night" to Info(Icons.Rounded.Nightlight, "Night light", "Tints the screens warm (less blue light) — easier on the eyes at night.", Group.ANDROID),
        "dim" to Info(Icons.Rounded.BrightnessLow, "Extra dim", "Makes the screens dimmer than the brightness slider's lowest setting — for dark rooms.", Group.ANDROID),
        "invert" to Info(Icons.Rounded.InvertColors, "Colour inversion", "Inverts every colour on screen (an accessibility feature).", Group.ANDROID),
        "record" to Info(Icons.Rounded.FiberManualRecord, "Screen record", "Records a video of the top screen — up to 3 minutes, no sound. Press again to stop; saved in Movies/Wayfinder.", Group.ANDROID),
        "hotspot" to Info(Icons.Rounded.WifiTethering, "Hotspot", "Opens Android's hotspot settings, to share this device's internet.", Group.ANDROID),
        "cast" to Info(Icons.Rounded.Cast, "Cast", "Opens Android's cast settings, to show the screen on a TV.", Group.ANDROID),
        // ── Thor (AYN's drawer and quick settings)
        "perf" to Info(Icons.Rounded.Speed, "Performance", "How hard the chip may work: Standard, Medium or High (each press moves to the next). Higher = smoother games, more heat and battery.", Group.THOR),
        "fan" to Info(Icons.Rounded.Air, "Fan", "Fan behaviour: Quiet, Smart (follows the temperature), Sports (always strong, coolest) or Custom (your own curve) — each press moves to the next.", Group.THOR),
        "gyro" to Info(Icons.Rounded.ScreenRotation, "Gyro", "The game's gyro on or off (set it up in Game controls → Gyro).", Group.WAYFINDER),
        "aynmouse" to Info(Icons.Rounded.Mouse, "Mouse mode", "AYN's virtual mouse on or off: once on, click a stick (L3 / R3) and it moves a pointer — for GameNative, web pages, apps made for touch.", Group.THOR),
        "fancurve" to Info(Icons.Rounded.Thermostat, "Fan curve", "Opens AYN's fan-curve editor: how fast the fan spins at each temperature. The Custom fan mode follows it.", Group.THOR),
        "hz" to Info(Icons.Rounded.Refresh, "Refresh rate", "Screen refresh rate: 60 Hz saves battery, 120 Hz is smoother.", Group.THOR),
        "style" to Info(Icons.Rounded.SportsEsports, "Face buttons", "Nintendo (as printed, A on the right) or Xbox (A at the bottom) for every app — the same setting as the Controller page. One game differently: Game controls.", Group.THOR),
        "trigger" to Info(Icons.Rounded.Gamepad, "L2 / R2 mode", "How L2 / R2 talk to games: Analog (how far you press), Digital (on/off, like a button) or Both.", Group.THOR),
        "landscape" to Info(Icons.Rounded.ScreenLockLandscape, "Force landscape", "Forces every app sideways, even ones made for phones held upright.", Group.THOR),
        "vibration" to Info(Icons.Rounded.Vibration, "Vibration", "Turns rumble and system vibration on or off.", Group.THOR),
        "bypass" to Info(Icons.Rounded.ElectricalServices, "Direct power supply", "When plugged in, runs the Thor straight from the charger instead of charging the battery — cooler, and kinder to the battery in long sessions.", Group.THOR),
        "limit80" to Info(Icons.Rounded.Battery5Bar, "80 % charge limit", "Stops charging at 80 % — the battery wears out more slowly.", Group.THOR),
    )
    val DEFAULT = listOf("perf", "fan", "hz", "fps", "lock", "shot", "deck", "controls", "recents", "pairs")

    /** The "App pairs" tile's popup (open / save pairs without leaving the panel). */
    var pairsOpen by androidx.compose.runtime.mutableStateOf(false)
    /** What was changed in this panel ("perf" / "fan" / "hz"): "Keep for <game>" saves only those
     *  (keeping all three pinned an adaptive 60–120 Hz user to a fixed 120 Hz — review 2026-09-25). */
    val touched = androidx.compose.runtime.mutableStateListOf<String>()
    /** Bumps on every change: a second change after "Keep" can be kept too. */
    var touchCount by androidx.compose.runtime.mutableIntStateOf(0)
    fun touch(what: String) { if (what !in touched) touched.add(what); touchCount++ }
    /** 1.3 (GitHub #25): the bottom screen changed from the panel — which closes it (the panel lives on
     *  that screen), so the NEXT panel for the same app still offers "Keep for <game>" (10 min). */
    @Volatile private var bottomApp: String? = null
    @Volatile private var bottomAt = 0L
    fun touchBottom() {
        touch("bottomoff"); bottomApp = ForegroundAppService.panelApp(); bottomAt = android.os.SystemClock.elapsedRealtime()
    }
    /** A new panel: forget what the last one changed, except a recent bottom-screen change for [app]. */
    fun startPanel(app: String?) {
        touched.clear(); touchCount = 0
        if (app != null && app == bottomApp && android.os.SystemClock.elapsedRealtime() - bottomAt < 10 * 60_000L) {
            touched.add("bottomoff"); touchCount = 1
        }
    }
    /** Kept (or "Use my usual"): nothing left to offer. */
    fun bottomKept() { bottomApp = null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thor_panel", Context.MODE_PRIVATE)
    fun chosen(ctx: Context): List<String> {
        val p = prefs(ctx)
        val saved = p.getString("tiles", null)?.split(',')?.filter { it in CATALOG || isOpenTile(it) } ?: return DEFAULT
        // New Wayfinder tiles reach panels saved before them — once (a removed tile stays removed).
        if (!p.getBoolean("offered_controls", false)) {
            p.edit().putBoolean("offered_controls", true).apply()
            if ("controls" !in saved) {
                val at = saved.indexOf("deck").let { if (it < 0) saved.size else it + 1 }
                return saved.toMutableList().apply { add(at, "controls") }.also { save(ctx, it) }
            }
        }
        return saved
    }
    fun save(ctx: Context, ids: List<String>) = prefs(ctx).edit().putString("tiles", ids.joinToString(",")).apply()

    /** 1.4: a tile of a part turned off (Features) isn't shown. */
    fun allowed(id: String): Boolean = when (id) {
        "deck" -> Features.deck
        "controls", "gyro" -> Features.gameControls
        else -> true
    }

    /**
     * The live tiles. [close] closes the panel; [after] closes it and then runs something
     * (actions that need the screen); [refresh] re-reads the tile values after a delay
     * (root writes are applied a moment later).
     */
    fun tiles(ctx: Context, close: () -> Unit, after: (Long, () -> Unit) -> Unit, refresh: (Long) -> Unit): Map<String, PanelTile> {
        val cr = ctx.contentResolver
        // Hidden keys an app may not read (Android 12+ throws for them — e.g. extra dim)
        // are read through root instead, cached, and the tiles refresh when it answers.
        val reread = { refresh(0) }
        fun sys(k: String, d: Int) = runCatching { Settings.System.getInt(cr, k, d) }.getOrElse { QuickSettings.rootInt("system", k, d, reread) }
        fun glob(k: String, d: Int) = runCatching { Settings.Global.getInt(cr, k, d) }.getOrElse { QuickSettings.rootInt("global", k, d, reread) }
        fun sec(k: String, d: Int) = runCatching { Settings.Secure.getInt(cr, k, d) }.getOrElse { QuickSettings.rootInt("secure", k, d, reread) }
        fun ic(id: String) = CATALOG.getValue(id).icon
        fun open(page: String?) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).apply { page?.let { putExtra("page", it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close()
        }
        fun openSettings(action: String) { after(250) { QuickSettings.openSettings(ctx, action) } }
        fun onOff(on: Boolean) = if (on) "On" else "Off"
        /** A root switch tile: shows On/Off, writes [cmd] (on/off), re-reads. */
        fun switch(id: String, label: String, on: Boolean, cmd: (Boolean) -> String) =
            PanelTile(ic(id), label, onOff(on), on) { QuickSettings.exec(cmd(!on)); refresh(500); refresh(1500) }

        val perf = sys("performance_mode", 0)
        val fan = sys("fan_mode", 4)
        val hz = runCatching { Settings.System.getFloat(cr, "peak_refresh_rate", 60f) }.getOrDefault(60f)
        val bottomOff = ForegroundAppService.screenMode() == 1
        val keptOn = ForegroundAppService.isSecondKeptAwake()
        val wifiOn = runCatching { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }.getOrDefault(false)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val night = ctx.getSystemService(UiModeManager::class.java)?.nightMode == UiModeManager.MODE_NIGHT_YES
        val style = sys("temp_abxy_layout_mode", 1)
        val trig = sys("trigger_input_mode", 2)
        val lightsOff = LightSettings.global.mode == LightMode.OFF
        return mapOf(
            // ── Wayfinder
            // with ClusterTune / Pulse: the tile names it and changes nothing (AYN's Medium / High would pin the CPU at
            // the tuner's limits) — compatibility first
            "perf" to Tuners.installed(ctx).let { t -> PanelTile(ic("perf"), "Performance", t ?: PerfMode.ofAyn(perf)?.label ?: "—", perf > 0 && t == null) {
                if (t != null) ForegroundAppService.pill("$t manages the CPU — Wayfinder leaves performance to it")
                else {
                    // AYN may also move the fan (Standard → Quiet): refresh again once it has.
                    touch("perf"); QuickSettings.cyclePerformance(ctx); refresh(350); refresh(2000)
                } } },
            "fan" to PanelTile(ic("fan"), "Fan", FanMode.values().firstOrNull { it.value == fan }?.label ?: "Custom", fan != 4) {
                touch("fan"); QuickSettings.cycleFan(ctx); refresh(350) },
            "fancurve" to PanelTile(ic("fancurve"), "Fan curve", null, false) { after(300) { QuickSettings.openFanCurve(ctx) } },
            "gyro" to (GyroEngine.configuredForCurrent != null).let { set ->
                val on = set && !GyroEngine.pausedByUser
                PanelTile(ic("gyro"), "Gyro", if (!set) "Not set up" else onOff(on), on) { ActionRegistry.perform(ThorAction.GYRO_TOGGLE); refresh(300) } },
            "aynmouse" to sys("global_gamepad_to_mouse_mode", 0).let { m ->
                PanelTile(ic("aynmouse"), "Mouse mode", onOff(m == 1), m == 1) { after(300) { ActionRegistry.perform(ThorAction.AYN_MOUSE) } } },
            "hz" to PanelTile(ic("hz"), "Refresh rate", "${hz.toInt()} Hz", hz > 90f) { touch("hz"); QuickSettings.toggleRefresh(ctx); refresh(350) },
            // 1.3: Hidden → FPS → + battery → + temperatures (the extra stats were hard to find)
            "fps" to PanelTile(ic("fps"), "Frame rate", if (!AppSettings.fpsCounter) "Hidden" else listOf("FPS", "+ battery", "+ temps")[AppSettings.fpsLevel.coerceIn(0, 2)], AppSettings.fpsCounter) {
                when {
                    !AppSettings.fpsCounter -> { AppSettings.setFpsLevelTo(0); AppSettings.setFpsCounterOn(true) }
                    AppSettings.fpsLevel < 2 -> AppSettings.setFpsLevelTo(AppSettings.fpsLevel + 1)
                    else -> { AppSettings.setFpsCounterOn(false); AppSettings.setFpsLevelTo(0) }
                }
                ForegroundAppService.reapplyFps(); refresh(0) },
            "lock" to PanelTile(ic("lock"), "Controller lock",
                if (AppSettings.focusSticky && !AppSettings.focusLockEnabled) "Unlocked"
                else if (!AppSettings.focusLockEnabled) "Off"
                else (if (AppSettings.focusSticky) "Stays on " else "") + (if (AppSettings.focusLockTop) "top screen" else "bottom screen")
                    .let { if (AppSettings.focusSticky) it else it.replaceFirstChar { c -> c.uppercase() } },
                AppSettings.focusLockEnabled || AppSettings.focusSticky) {
                // Applied when the panel closes (the panel itself has the controller now).
                when {
                    // "Stays where sent": the tile only flips the screen — never "Off" while the
                    // mode keeps sticking (release test 2026-09-24)
                    AppSettings.focusSticky -> AppSettings.setFocusLock(true, top = !(AppSettings.focusLockEnabled && AppSettings.focusLockTop))
                    !AppSettings.focusLockEnabled -> AppSettings.setFocusLock(true, top = true)
                    AppSettings.focusLockTop -> AppSettings.setFocusLock(true, top = false)
                    else -> AppSettings.setFocusLock(false)
                }; refresh(0) },
            "shot" to PanelTile(ic("shot"), "Screenshot", null, false) { after(600) { ActionRegistry.perform(ThorAction.SCREENSHOT) } },
            "deck" to PanelTile(ic("deck"), "Keyboard & mouse", null, false) { after(400) { ActionRegistry.perform(ThorAction.KEYBOARD) } },
            "controls" to PanelTile(ic("controls"), "Game controls", null, false) { after(400) { ActionRegistry.perform(ThorAction.GAME_CONTROLS) } },
            "recents" to PanelTile(ic("recents"), "Recent apps", null, false) { after(400) { ActionRegistry.perform(ThorAction.RECENTS) } },
            "clear" to PanelTile(ic("clear"), "Close background", null, false) { after(400) { ActionRegistry.perform(ThorAction.CLEAR_BACKGROUND) } },
            "swap" to PanelTile(ic("swap"), "Move / swap apps", null, false) { after(400) { ActionRegistry.perform(ThorAction.SWAP_OR_SEND) } },
            "bottomoff" to PanelTile(ic("bottomoff"), "Bottom screen", if (bottomOff) "Off" else "On", bottomOff) {
                touchBottom()   // 1.3 (GitHub #25): "Keep for <game>" remembers it
                // Turning it off closes the panel first (it lives on that screen).
                if (!bottomOff) after(300) { ForegroundAppService.setScreenMode(1) } else { ForegroundAppService.setScreenMode(0); refresh(300) } },
            "sleep" to PanelTile(ic("sleep"), "Sleep", null, false) { after(300) { ActionRegistry.perform(ThorAction.SLEEP) } },
            "guide" to PanelTile(ic("guide"), "Guide & notes", null, false) { after(400) { ActionRegistry.perform(ThorAction.GUIDE) } },
            "keepon" to PanelTile(ic("keepon"), "Stay awake", onOff(keptOn), keptOn) {
                ActionRegistry.perform(ThorAction.TOGGLE_KEEP_AWAKE); refresh(300) },
            "boost" to PanelTile(ic("boost"), "Volume boost", if (SpeakerTune.boost == 0) "Off" else "+${SpeakerTune.boost} dB", SpeakerTune.boost > 0) {
                SpeakerTune.setVolumeBoost(when (SpeakerTune.boost) { 0 -> 6; 6 -> 12; else -> 0 }); refresh(0) },
            "speaker" to PanelTile(ic("speaker"), "Speaker fix", onOff(SpeakerTune.enabled), SpeakerTune.enabled) {
                SpeakerTune.setOn(!SpeakerTune.enabled); refresh(0) },
            "speakereq" to PanelTile(ic("speakereq"), "Speaker EQ",
                if (!SpeakerTune.enabled) "Fix is off" else if (SpeakerTune.eqOn) "On" else "Off", SpeakerTune.enabled && SpeakerTune.eqOn) {
                if (!SpeakerTune.enabled) SpeakerTune.setOn(true) else SpeakerTune.setEq(!SpeakerTune.eqOn); refresh(0) },
            "lights" to PanelTile(ic("lights"), "Light settings", null, false) { open(HubPage.LIGHTS) },
            "lightsonoff" to PanelTile(ic("lightsonoff"), "Stick lights", if (lightsOff) "Off" else "On", !lightsOff) {
                QuickSettings.toggleStickLights(ctx); refresh(0) },
            "hub" to PanelTile(ic("hub"), "Wayfinder", null, false) { open(null) },
            "pairs" to PanelTile(ic("pairs"), "App pairs", if (Layouts.pairs.isEmpty()) "None yet" else "${Layouts.pairs.size} saved", false) {
                pairsOpen = true },
            // ── Android
            "wifi" to switch("wifi", "Wi-Fi", wifiOn) { "cmd wifi set-wifi-enabled ${if (it) "enabled" else "disabled"}" },
            "bt" to switch("bt", "Bluetooth", glob("bluetooth_on", 0) == 1) { "cmd bluetooth_manager ${if (it) "enable" else "disable"}" },
            "airplane" to switch("airplane", "Airplane", glob("airplane_mode_on", 0) == 1) { "cmd connectivity airplane-mode ${if (it) "enable" else "disable"}" },
            "dnd" to switch("dnd", "Do not disturb", glob("zen_mode", 0) != 0) { "cmd notification set_dnd ${if (it) "priority" else "off"}" },
            "rotate" to switch("rotate", "Auto-rotate", sys("accelerometer_rotation", 0) == 1) { "settings put system accelerometer_rotation ${if (it) 1 else 0}" },
            "location" to switch("location", "Location", sec("location_mode", 0) != 0) { "cmd location set-location-enabled $it" },
            "saver" to (if (QuickSettings.plugged(ctx) && pm?.isPowerSaveMode != true)
                PanelTile(ic("saver"), "Battery saver", "Not while charging", false) { }
            else switch("saver", "Battery saver", pm?.isPowerSaveMode == true) { "cmd power set-mode ${if (it) 1 else 0}" }),
            "dark" to switch("dark", "Dark theme", night) { "cmd uimode night ${if (it) "yes" else "no"}" },
            "night" to switch("night", "Night light", sec("night_display_activated", 0) == 1) { "settings put secure night_display_activated ${if (it) 1 else 0}" },
            "dim" to switch("dim", "Extra dim", sec("reduce_bright_colors_activated", 0) == 1) { "settings put secure reduce_bright_colors_activated ${if (it) 1 else 0}" },
            "invert" to switch("invert", "Invert colours", sec("accessibility_display_inversion_enabled", 0) == 1) { "settings put secure accessibility_display_inversion_enabled ${if (it) 1 else 0}" },
            "record" to PanelTile(ic("record"), "Screen record", if (ScreenRecorder.recording) "Recording" else null, ScreenRecorder.recording) {
                if (ScreenRecorder.recording) { ScreenRecorder.stop(); refresh(0) } else after(400) { ScreenRecorder.start() } },
            "hotspot" to PanelTile(ic("hotspot"), "Hotspot", null, false) { openSettings("android.settings.TETHER_SETTINGS") },
            "cast" to PanelTile(ic("cast"), "Cast", null, false) { openSettings(Settings.ACTION_CAST_SETTINGS) },
            // ── Thor
            "style" to PanelTile(ic("style"), "Face buttons", when (style) { 0 -> "Xbox"; 1 -> "Nintendo"; else -> "AYN pad off" }, style == 0) {
                QuickSettings.toggleControllerStyle(style); refresh(500) },
            "trigger" to PanelTile(ic("trigger"), "L2 / R2", when (trig) { 0 -> "Analog"; 1 -> "Digital"; else -> "Both" }, trig != 2) {
                QuickSettings.exec("settings put system trigger_input_mode ${(trig + 1) % 3}"); refresh(500) },
            "landscape" to switch("landscape", "Force landscape", sys("force_landscape", 0) == 1) { "settings put system force_landscape ${if (it) 1 else 0}" },
            "vibration" to switch("vibration", "Vibration", sys("vibrate_on", 1) == 1) { "settings put system vibrate_on ${if (it) 1 else 0}" },
            // through Charging: AYN's node is checked (and written if its settings app didn't)
            "bypass" to sys("is_charging_separation", 0).let { v -> PanelTile(ic("bypass"), "Direct power", onOff(v == 1), v == 1) {
                Charging.set(ctx, direct = v != 1); refresh(500); refresh(2500) } },
            "limit80" to sys("percent_80_charge_limit", 0).let { v -> PanelTile(ic("limit80"), "80 % limit", onOff(v == 1), v == 1) {
                Charging.set(ctx, limit = v != 1); refresh(500); refresh(2500) } },
        ) + chosen(ctx).filter { it !in CATALOG }.mapNotNull { id ->
            // 1.4 (Reddit): the "Open…" tiles — the panel closes first so the app lands on the game's screen
            info(ctx, id)?.let { i -> id to PanelTile(i.icon, i.name, null, false, i.pkg) { after(250) { ForegroundAppService.open(id) } } }
        }
    }
}

class PanelTile(val icon: ImageVector, val label: String, val value: String?, val on: Boolean, val pkg: String? = null, val action: () -> Unit)

object QuickSettings {
    fun exec(cmd: String) = Thread { PServiceBridge.exec(cmd) }.apply { isDaemon = true }.start()

    private val rootCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val rootAsking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A settings value the app can't read itself: last known via root (asks again each time). */
    fun rootInt(ns: String, key: String, default: Int, changed: () -> Unit): Int {
        val id = "$ns/$key"
        if (rootAsking.add(id)) Thread {
            val v = PServiceBridge.exec("settings get $ns $key")?.trim()?.toIntOrNull() ?: default
            rootAsking.remove(id)
            if (rootCache.put(id, v) != v) changed()
        }.apply { isDaemon = true }.start()
        return rootCache[id] ?: default
    }

    fun cyclePerformance(ctx: Context) {
        val next = (Settings.System.getInt(ctx.contentResolver, "performance_mode", 0) + 1) % 3
        exec("settings put system performance_mode $next; setprop persist.vendor.debug.mode $next")
    }

    fun cycleFan(ctx: Context) {
        // Quiet → Smart → Sports → Custom → Quiet: one tap too many must never turn the fan OFF
        // mid-game (Off stays in AYN's own settings for whoever really wants it).
        val order = listOf(FanMode.QUIET, FanMode.SMART, FanMode.SPORTS, FanMode.CUSTOM).map { it.value }
        val cur = Settings.System.getInt(ctx.contentResolver, "fan_mode", 4)
        val next = order[(order.indexOf(cur).coerceAtLeast(-1) + 1) % order.size]
        PerfProfiles.markOwn(next)
        exec("settings put system fan_mode $next")
    }

    /** AYN's fan-curve editor (exported), on the top screen. */
    fun openFanCurve(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent("action_fan_temp_control_curve_config").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
        }.onFailure { ForegroundAppService.pill("AYN's fan curve isn't on this device", android.view.Display.DEFAULT_DISPLAY, 2500) }
    }

    fun toggleRefresh(ctx: Context) {
        val hz = if (Settings.System.getFloat(ctx.contentResolver, "peak_refresh_rate", 60f) > 90f) "60.0" else "120.0"
        exec("settings put system min_refresh_rate $hz; settings put system peak_refresh_rate $hz")
    }

    /**
     * AYN's three controller styles are written as three keys (as AYN's own tile does):
     * Xbox = mode 0 + flip_button_layout; Standard = mode 1. Mode 2 ("Ban On Use", AYN's
     * virtual pad off) is left out on purpose: from a panel driven by the controller it
     * could leave you without one. From it, a tap goes back to Standard.
     */
    fun toggleControllerStyle(current: Int) {
        val xbox = current == 1
        exec("settings put system temp_abxy_layout_mode ${if (xbox) 0 else 1}; " +
            "settings put system flip_button_layout ${if (xbox) 1 else 0}; " +
            "settings put system no_create_gamepad_button_layout 0")
    }

    /** Off ↔ the lighting you had before (AYN's default if none). */
    fun toggleStickLights(ctx: Context) {
        LightSettings.init(ctx)
        val prefs = ctx.getSharedPreferences("thor_panel", Context.MODE_PRIVATE)
        val cur = LightSettings.global
        val next = if (cur.mode == LightMode.OFF)
            runCatching { LightProfile.fromJson(org.json.JSONObject(prefs.getString("lights_before_off", "{}") ?: "{}")) }.getOrNull()
                ?.takeIf { it.mode != LightMode.OFF } ?: LightProfile()
        else { prefs.edit().putString("lights_before_off", cur.toJson().toString()).apply(); cur.copy(mode = LightMode.OFF) }
        LightSettings.setGlobalProfile(next)
        StickLights.apply(ctx, next)
    }

    fun plugged(ctx: Context): Boolean = runCatching {
        val b = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (b?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    /** A system settings page, on the top screen (where there's room for it). */
    fun openSettings(ctx: Context, action: String) {
        val opts = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = 0 }
        runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle()) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle()) } }
    }
}

/**
 * Screen record (the quick-settings one needs SystemUI's dialog): Android's own
 * `screenrecord`, run as root in the background — top screen, 3 minutes max, no sound.
 * Saved to Movies/Wayfinder and handed to the media scanner so galleries see it.
 */
object ScreenRecorder {
    @Volatile var recording = false
        private set
    private var file = ""
    private var startedAt = 0L

    fun start() {
        if (recording) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        file = "/sdcard/Movies/Wayfinder/Wayfinder_$stamp.mp4"
        recording = true; startedAt = android.os.SystemClock.uptimeMillis()
        QuickSettings.exec("mkdir -p /sdcard/Movies/Wayfinder; nohup screenrecord --time-limit 180 $file >/dev/null 2>&1 &")
        ForegroundAppService.pill("● Recording the top screen — stop it from the quick panel")
        val mine = startedAt
        ForegroundAppService.later(181_000) { if (recording && startedAt == mine) finished() }
    }

    fun stop() {
        if (!recording) return
        // Only OUR recording (its file name is unique) — never someone else's screenrecord.
        // "[s]creenrecord": the shell running pkill has the pattern in its own command line and
        // would match (and kill) itself; the bracket stops the regex matching its own text.
        QuickSettings.exec("pkill -INT -f '[s]creenrecord .*${file.substringAfterLast('/')}'")
        ForegroundAppService.later(1200) { finished() }
    }

    private fun finished() {
        recording = false
        QuickSettings.exec("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$file")
        ForegroundAppService.pill("Saved to Movies/Wayfinder")
    }
}
