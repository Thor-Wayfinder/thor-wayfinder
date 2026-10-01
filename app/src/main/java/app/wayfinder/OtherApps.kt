package app.wayfinder

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.wayfinder.lights.LightMode
import app.wayfinder.lights.LightSettings

/**
 * 1.4 — other Thor apps that do what a part of Wayfinder does. Two kinds:
 *  - handled: Wayfinder steps aside by itself (ClusterTune / Pulse: the CPU; SleepManager: sleep and the lid);
 *  - not handled: both would act — Wayfinder warns on the Hub (once per app, "Got it") and lists it in Help & status,
 *    with what to do. Only when the other app is ACTIVE (its accessibility service on, or Mjolnir as the Home screen),
 *    not merely installed.
 */
object OtherApps {
    /** [page]: the Hub page where it's set (opened from Help & status). */
    data class App(val pkg: String, val name: String, val handled: Boolean, val text: String, val page: String)

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

    private fun a11yOn(ctx: Context, pkg: String) =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.startsWith("$pkg/") } == true

    private fun isHome(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == pkg
    }.getOrDefault(false)

    /** The ones on this Thor right now. */
    fun detected(ctx: Context): List<App> = buildList {
        Tuners.installed(ctx)?.let { t ->
            add(App(if (t == "Pulse") "com.kei.pulse" else "com.aure.clustertune", t, true,
                if (t == "Pulse") "Wayfinder leaves the CPU, GPU, fan and refresh rate to it: no per-game performance, fan or refresh rate, " +
                    "and the quick panel's Performance tile changes nothing."
                else "Wayfinder leaves the CPU to it: no per-game performance, and the quick panel's Performance tile changes nothing.",
                HubPage.APPS))
        }
        SleepSettings.otherSleepApp?.let { p ->
            add(App(p, runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }
                .getOrDefault("SleepManager"), true,
                "Wayfinder's sleep actions and lid guard stay off unless you turn them on (Battery → Sleep & standby).", HubPage.SLEEP))
        }
        val mj = "xyz.blacksheep.mjolnir"
        if (installed(ctx, mj) && (a11yOn(ctx, mj) || isHome(ctx, mj))) add(App(mj, "Mjolnir", false,
            "Both act on the Home button, so one press can do two things. Use one for Home: turn Mjolnir's Home routing " +
                "off, or move Wayfinder's Home combos to another button (Controller → Combos).", HubPage.CONTROLS))
        val bf = "com.moonbench.bifrost"
        if (installed(ctx, bf) && a11yOn(ctx, bf) && LightSettings.global.mode != LightMode.AYN) add(App(bf, "BiFrost", false,
            "Both drive the stick lights, so they flicker between the two. Use one: Wayfinder's Stick lights → " +
                "\"AYN default\", or turn BiFrost off.", HubPage.LIGHTS))
        val ot = "de.langerhans.odintools"
        if (installed(ctx, ot) && a11yOn(ctx, ot)) add(App(ot, "OdinTools", false,
            "Both set performance and the fan for each app, so they undo each other. Use one: leave Wayfinder's games on " +
                "\"Usual\" performance and fan, or turn OdinTools' app overrides off.", HubPage.APPS))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
    /** Not handled, active, and not yet acknowledged with "Got it". */
    fun toWarn(ctx: Context): List<App> {
        val seen = prefs(ctx).getStringSet("conflicts_seen", emptySet()).orEmpty()
        return detected(ctx).filter { !it.handled && it.pkg !in seen }
    }
    fun acknowledge(ctx: Context, pkg: String) {
        val seen = prefs(ctx).getStringSet("conflicts_seen", emptySet()).orEmpty()
        prefs(ctx).edit().putStringSet("conflicts_seen", seen + pkg).apply()
    }
}
