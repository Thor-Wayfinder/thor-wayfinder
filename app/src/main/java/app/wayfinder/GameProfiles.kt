package app.wayfinder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-game profiles: Mario Kart and Zelda in the same Dolphin,
 * each with its own controls. A game profile holds what the controls page edits — face buttons
 * and the remap (buttons, gyro, macros, chords); everything else stays per app. It's stored under
 * `<pkg>#<game-id>` (the id from [GameDetector]); a game without one uses its app's profile.
 */
data class GameProfile(
    val title: String, val face: FaceLayout? = null, val remap: PadRemap? = null,
    // round 8 (2026-09-25): a game can also have its own performance, fan, refresh rate and stick
    // lights (null = its app's, else the user's) — edited in Game controls, or kept from the panel
    val perf: PerfMode? = null, val fan: FanMode? = null, val hz: Int? = null,
    val lights: app.wayfinder.lights.LightProfile? = null,
    /** 1.3 (GitHub #25): the bottom screen while this game is on top, and its frame-rate counter
     *  (null = its app's, else the usual). */
    val second: SecondScreenPolicy? = null,
    val fps: Boolean? = null,
) {
    fun toJson(): String = JSONObject().put("title", title)
        .apply { face?.let { put("face", it.name) } }
        .apply { remap?.takeIf { !it.isEmpty }?.let { put("remap", it.toJson()) } }
        .apply { perf?.let { put("perf", it.name) }; fan?.let { put("fan", it.name) }; hz?.let { put("hz", it) } }
        .apply { lights?.let { put("lights", it.toJson()) } }
        .apply { second?.let { put("second", it.name) }; fps?.let { put("fps", it) } }.toString()
    companion object {
        fun fromJson(s: String): GameProfile? = runCatching {
            val o = JSONObject(s)
            GameProfile(o.getString("title"), o.optString("face").takeIf { it.isNotEmpty() }?.let { f -> FaceLayout.values().firstOrNull { it.name == f } },
                PadRemap.fromJson(o.optJSONObject("remap")),
                runCatching { PerfMode.valueOf(o.optString("perf")) }.getOrNull(),
                runCatching { FanMode.valueOf(o.optString("fan")) }.getOrNull(),
                o.optInt("hz", 0).takeIf { it == 60 || it == 120 },
                app.wayfinder.lights.LightProfile.fromJson(o.optJSONObject("lights")),
                runCatching { SecondScreenPolicy.valueOf(o.optString("second")) }.getOrNull()?.takeIf { it != SecondScreenPolicy.DEFAULT },
                if (o.has("fps")) o.optBoolean("fps") else null)
        }.getOrNull()
    }
}

object GameProfiles {
    private const val TAG = "ThorGames"
    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, GameProfile>()

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("thor_games", Context.MODE_PRIVATE).also { p ->
            p.all.forEach { (k, v) -> (v as? String)?.let { GameProfile.fromJson(it) }?.let { cache[k] = it } }
        }
    }

    fun key(pkg: String, game: String) = "$pkg#$game"
    fun isGame(key: String) = '#' in key
    fun pkgOf(key: String) = key.substringBefore('#')
    fun get(key: String): GameProfile? = cache[key]
    /** This app's game profiles: key → profile. */
    fun forApp(pkg: String): List<Pair<String, GameProfile>> = cache.entries.filter { pkgOf(it.key) == pkg }
        .map { it.key to it.value }.sortedBy { it.second.title.lowercase() }

    fun set(key: String, p: GameProfile) {
        cache[key] = p
        prefs?.edit()?.putString(key, p.toJson())?.apply()
        AppConfigStore.version.intValue++
    }
    fun remove(key: String) {
        cache.remove(key); prefs?.edit()?.remove(key)?.apply()
        AppConfigStore.version.intValue++
    }
    /** 1.3: [key]'s profile has nothing of its own left (its app's controls, no settings): removed. */
    fun removeIfPlain(key: String) {
        val p = cache[key] ?: return
        val a = AppConfigStore.get(pkgOf(key))
        fun r(x: PadRemap?) = x?.takeIf { !it.isEmpty }
        if (p.perf == null && p.fan == null && p.hz == null && p.lights == null && p.second == null && p.fps == null &&
            p.face == a.face && r(p.remap) == r(a.remap)) remove(key)
    }
    /** 1.4: game profiles with nothing of their own (an untouched copy left when Game controls closed by a crash or
     *  an update) — removed at service start. */
    fun dropPlain() { cache.keys.toList().forEach { removeIfPlain(it) } }

    /** A new game profile: starts as a copy of its app's controls. */
    fun create(pkg: String, game: String, title: String): String {
        val k = key(pkg, game)
        if (!cache.containsKey(k)) AppConfigStore.get(pkg).let { set(k, GameProfile(title, it.face, it.remap)) }
        return k
    }

    // ── what's running (the root helper's answers to "D <pkg>") ──
    data class Running(val pkg: String, val game: String, val title: String)
    /** What each app runs, one entry per app: asking about another app (the quick panel's, a second
     *  app with game profiles) used to clear the one slot — a game on the other screen lost its own
     *  performance / Hz / lights while still playing (review 2026-09-25). Cleared by that app's own
     *  "no game" answer, or when it leaves the screens ([forgetExcept]). */
    private val running = ConcurrentHashMap<String, Running>()

    fun onDetected(pkg: String, game: String?, title: String?) {
        val r = if (game != null && title != null) Running(pkg, game, title) else null
        val before = running[pkg]
        if (r == before) return
        if (r == null) running.remove(pkg) else running[pkg] = r
        Log.i(TAG, "running: ${r ?: "no game in $pkg"}")
        AppConfigStore.version.intValue++      // the controls page shows / offers the game
        ForegroundAppService.gameChanged()     // its own performance / Hz / lights, if any
    }
    /** Ask the helper which game [pkg] runs (the service's tick: on a switch, then now and then). */
    fun ask(pkg: String?) {
        if (pkg == null) return
        InputMonitor.send("D $pkg")
    }
    /** Apps no longer on a screen: whatever they ran is over. */
    fun forgetExcept(onScreens: Set<String>) {
        val gone = running.keys.filter { it !in onScreens }
        if (gone.isNotEmpty()) { gone.forEach { running.remove(it) }; AppConfigStore.version.intValue++ }
    }
    /** The detected game of [pkg], if it's running now. */
    fun runningIn(pkg: String): Running? = running[pkg]
    /** The profile the controls use for [pkg]: its game's, if the game has one, else the app's. */
    fun activeKey(pkg: String): String = runningIn(pkg)?.let { key(pkg, it.game) }?.takeIf { cache.containsKey(it) } ?: pkg

    /** What applies to [pkg] now: each of its running game's own settings, else the app's. */
    fun effective(pkg: String): AppConfig {
        val app = AppConfigStore.get(pkg)
        val g = runningIn(pkg)?.let { cache[key(pkg, it.game)] } ?: return app
        return app.copy(perf = g.perf ?: app.perf, fan = g.fan ?: app.fan, hz = g.hz ?: app.hz, lights = g.lights ?: app.lights,
            second = g.second ?: app.second, fps = g.fps ?: app.fps)
    }
}

/** The controls page edits an app's profile or a game's (same fields: face + remap). */
object Profiles {
    fun get(key: String): AppConfig =
        if (GameProfiles.isGame(key)) GameProfiles.get(key)?.let {
            AppConfig(face = it.face, remap = it.remap, perf = it.perf, fan = it.fan, hz = it.hz, lights = it.lights,
                second = it.second ?: SecondScreenPolicy.DEFAULT, fps = it.fps)
        } ?: AppConfig()
        else AppConfigStore.get(key)
    fun update(key: String, change: (AppConfig) -> AppConfig) {
        if (!GameProfiles.isGame(key)) { AppConfigStore.update(key, change); return }
        val p = GameProfiles.get(key) ?: return
        val c = change(get(key))
        GameProfiles.set(key, p.copy(face = c.face, remap = c.remap, perf = c.perf, fan = c.fan, hz = c.hz, lights = c.lights,
            second = c.second.takeIf { it != SecondScreenPolicy.DEFAULT }, fps = c.fps))
    }
}
