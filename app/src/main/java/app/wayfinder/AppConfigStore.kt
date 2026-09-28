package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** What the SECOND screen should do while an app is on the top screen. */
enum class SecondScreenPolicy { DEFAULT, KEEP_ON, BLANK }

/** Which screen this app opens on (ANY = wherever Android puts it). */
enum class Route(val label: String) { ANY("Any screen"), TOP("Top"), BOTTOM("Bottom") }

/** Input layer — face buttons for this app: as PRINTED on the Thor (Nintendo: A right, B
 *  bottom) or Xbox (A at the bottom). null in [AppConfig.face] = AYN's own setting. */
enum class FaceLayout(val label: String) { NINTENDO("Nintendo"), XBOX("Xbox") }

/** How Wayfinder's button bindings behave while this app has the controller. */
enum class ButtonsMode(val label: String) { NORMAL("Normal"), CUSTOM("Custom"), OFF("Off") }

/**
 * Per-app settings. One record per package so later per-app features (screen
 * routing, performance) extend this instead of adding menus.
 *
 * Buttons: [buttons] are this app's overrides (same trigger or same action as
 * a global binding → replaces it); [freed] are global triggers handed back to the
 * app untouched. Both only apply in [ButtonsMode.CUSTOM]; OFF gives every button
 * to the app.
 */
data class AppConfig(
    val second: SecondScreenPolicy = SecondScreenPolicy.DEFAULT,
    val buttonsMode: ButtonsMode = ButtonsMode.NORMAL,
    val buttons: List<Binding> = emptyList(),
    val freed: List<Trigger> = emptyList(),
    /** This app's stick lights (null = the global setting). */
    val lights: app.wayfinder.lights.LightProfile? = null,
    val route: Route = Route.ANY,
    /** Open the Game companion on the bottom screen while this app is on top. */
    val companion: Boolean = false,
    /** Performance / fan while this app is on screen (null = the user's AYN setting). */
    val perf: PerfMode? = null,
    val fan: FanMode? = null,
    /** Refresh rate while this app is on screen: 60 / 120 (null = the user's setting). */
    val hz: Int? = null,
    /** Input layer: face buttons forced for this app (null = like all apps / AYN's setting). */
    val face: FaceLayout? = null,
    /** Input layer: what this app's controls become (null = as the Thor sends them). */
    val remap: PadRemap? = null,
    /** 1.3 — the controller's own Back goes straight to this app, held too (RetroArch's Back
     *  hotkeys and menu): no Wayfinder combo uses Back here. null = automatic (on for RetroArch). */
    val backToGame: Boolean? = null,
    /** 1.3 (GitHub #22): the frame-rate counter for this app (null = the usual setting). */
    val fps: Boolean? = null,
    /** 1.3.2 — moved to the other screen by reopening it (not live). null = automatic ([reopensOnMove]). */
    val reopenOnMove: Boolean? = null,
) {
    val isDefault get() = this == AppConfig()

    fun toJson(): String = JSONObject()
        .put("second", second.name)
        .put("buttonsMode", buttonsMode.name)
        .put("buttons", org.json.JSONArray().apply {
            buttons.forEach { put(JSONObject().put("trigger", it.trigger.toJson()).put("action", it.action.name)) }
        })
        .put("freed", org.json.JSONArray().apply { freed.forEach { put(it.toJson()) } })
        .apply { lights?.let { put("lights", it.toJson()) } }
        .put("route", route.name)
        .put("companion", companion)
        .apply { perf?.let { put("perf", it.name) }; fan?.let { put("fan", it.name) }; hz?.let { put("hz", it) } }
        .apply { face?.let { put("face", it.name) } }
        .apply { remap?.takeIf { !it.isEmpty }?.let { put("remap", it.toJson()) } }
        .apply { backToGame?.let { put("backGame", it) } }
        .apply { fps?.let { put("fps", it) } }
        .apply { reopenOnMove?.let { put("reopenMove", it) } }
        .toString()

    companion object {
        fun fromJson(s: String): AppConfig = runCatching {
            val o = JSONObject(s)
            val b = o.optJSONArray("buttons")
            val f = o.optJSONArray("freed")
            AppConfig(
                second = runCatching { SecondScreenPolicy.valueOf(o.optString("second")) }
                    .getOrDefault(SecondScreenPolicy.DEFAULT),
                buttonsMode = runCatching { ButtonsMode.valueOf(o.optString("buttonsMode")) }
                    .getOrDefault(ButtonsMode.NORMAL),
                buttons = if (b == null) emptyList() else (0 until b.length()).mapNotNull { i ->
                    val e = b.getJSONObject(i)
                    val t = Trigger.fromJson(e.getJSONObject("trigger")) ?: return@mapNotNull null
                    val a = runCatching { ThorAction.valueOf(e.getString("action")) }.getOrNull() ?: return@mapNotNull null
                    Binding(t, a)
                },
                freed = if (f == null) emptyList() else (0 until f.length()).mapNotNull { i -> Trigger.fromJson(f.getJSONObject(i)) },
                lights = app.wayfinder.lights.LightProfile.fromJson(o.optJSONObject("lights")),
                route = runCatching { Route.valueOf(o.optString("route")) }.getOrDefault(Route.ANY),
                companion = o.optBoolean("companion", false),
                perf = runCatching { PerfMode.valueOf(o.optString("perf")) }.getOrNull(),
                fan = runCatching { FanMode.valueOf(o.optString("fan")) }.getOrNull(),
                hz = o.optInt("hz", 0).takeIf { it == 60 || it == 120 },
                face = runCatching { FaceLayout.valueOf(o.optString("face")) }.getOrNull(),
                remap = PadRemap.fromJson(o.optJSONObject("remap")),
                backToGame = if (o.has("backGame")) o.optBoolean("backGame") else null,
                fps = if (o.has("fps")) o.optBoolean("fps") else null,
                reopenOnMove = if (o.has("reopenMove")) o.optBoolean("reopenMove") else null,
            )
        }.getOrDefault(AppConfig())
    }
}

/**
 * Persistent per-app config backbone (SharedPreferences "thor_apps", one JSON
 * value per package). Readable from any thread; [version] bumps on every change
 * so Compose screens can observe it.
 */
object AppConfigStore {
    private lateinit var prefs: SharedPreferences
    private val cache = ConcurrentHashMap<String, AppConfig>()
    @Volatile private var initialized = false

    val version = mutableIntStateOf(0)

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = ctx.applicationContext.getSharedPreferences("thor_apps", Context.MODE_PRIVATE)
            prefs.all.forEach { (pkg, v) -> (v as? String)?.let { cache[pkg] = AppConfig.fromJson(it) } }
            initialized = true
        }
    }

    fun get(pkg: String): AppConfig = cache[pkg] ?: AppConfig()

    fun set(pkg: String, config: AppConfig) {
        if (config.isDefault) cache.remove(pkg) else cache[pkg] = config
        if (initialized) {
            prefs.edit().apply {
                if (config.isDefault) remove(pkg) else putString(pkg, config.toJson())
            }.apply()
        }
        version.intValue++
    }

    fun update(pkg: String, change: (AppConfig) -> AppConfig) = set(pkg, change(get(pkg)))

    /** Packages with any non-default setting. */
    fun configured(): Set<String> = cache.keys.toSet()
}

/** 1.3.2 — Firefox-based browsers (GeckoView) keep their popups on the screen they started on after a live
 *  move, and crash when one opens (selecting a word, a menu): they move by reopening, unless set otherwise. */
private val REOPEN_BY_DEFAULT = listOf("org.mozilla.", "org.torproject.torbrowser", "io.github.forkmaintainers.iceraven", "org.ironfoxoss.")

fun reopensOnMove(pkg: String): Boolean =
    AppConfigStore.get(pkg).reopenOnMove ?: REOPEN_BY_DEFAULT.any { pkg.startsWith(it) }

fun reopensOnMoveByDefault(pkg: String): Boolean = REOPEN_BY_DEFAULT.any { pkg.startsWith(it) }
