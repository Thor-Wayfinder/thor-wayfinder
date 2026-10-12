package app.wayfinder

import android.content.Context

/**
 * Round 8 (2026-09-25): what an "Open…" combo opens — an app (on its screen: [opensOnTop]), an app
 * pair, or a Wayfinder page. Stored on the binding as `app:<package>`, `pair:<id>` or `page:<id>`;
 * anything else is refused (a binding's text comes from the prefs or a backup, never trusted).
 */
object OpenTargets {
    /** The pages a combo can open (the Hub's own ids), with their names. Build 18 (C10, VD-19): the names are the pages'
     *  titles (NavPages: the home page is "Wayfinder" — "Home" read as the Home button; "The Thor", as its page says). The
     *  ids are what a binding stores (`page:<id>`): unchanged. */
    val PAGES: List<Pair<String, String>> = listOf(
        HubPage.HOME, HubPage.SCREENS, HubPage.BATTERY, HubPage.SOUND, HubPage.SLEEP,
        HubPage.CONTROLLER, HubPage.THOR, HubPage.CONTROLS, HubPage.KEYBOARD,
        HubPage.APPS, HubPage.PANEL, HubPage.PAIRS,
        HubPage.LIGHTS, HubPage.APPEARANCE, HubPage.HELP,
    ).map { it to NavPages.title(it) }

    fun app(pkg: String) = "app:$pkg"
    /** 1.3.2 (GitHub #42): an app on a chosen screen — `apptop:` / `appbottom:` (plain `app:` = the controller's). */
    fun app(pkg: String, where: String?) = when (where) { "top" -> "apptop:$pkg"; "bottom" -> "appbottom:$pkg"; else -> "app:$pkg" }
    fun isApp(arg: String) = arg.startsWith("app:") || arg.startsWith("apptop:") || arg.startsWith("appbottom:")
    fun appPkg(arg: String) = arg.substringAfter(':')
    fun appWhere(arg: String): String? = when { arg.startsWith("apptop:") -> "top"; arg.startsWith("appbottom:") -> "bottom"; else -> null }

    /** b18p (GitHub #107): the screen an "Open…" app lands on — true: the top one. Every Wayfinder launch of an app decides
     *  here, through [ForegroundAppService.open]: the quick panel's app tiles, "Open…" combos, the media card's Open (app
     *  pairs and the restore after a restart keep the screens they name). In order:
     *  - one screen: the top;
     *  - the combo's own screen (`apptop:` / `appbottom:`, 1.3.2 #42);
     *  - the app's own "Opens on" ([route], its Games page) — it ignored it, so a Kiwi set to Bottom opened on the top
     *    (Wayfinder's launch holds the move gate, the window-event routing can't correct it). As Android's launcher plus that
     *    routing do: an app already showing on a screen stays there ([shownOnTop], null: on none); a screen another app's
     *    second screen covers ([covered], a dual-screen game's Presentation) gives way to the other one;
     *  - else ("Any screen", every app by default) the controller's screen, as before. */
    fun opensOnTop(arg: String, route: Route, hasSecond: Boolean, shownOnTop: Boolean?, controllerOnTop: () -> Boolean,
                   covered: (top: Boolean) -> Boolean): Boolean {
        if (!hasSecond) return true
        appWhere(arg)?.let { return it == "top" }
        if (route == Route.ANY) return controllerOnTop()
        shownOnTop?.let { return it }
        val top = route == Route.TOP
        return if (covered(top)) !top else top
    }
    fun pair(id: Long) = "pair:$id"
    fun page(id: String) = "page:$id"

    fun valid(arg: String?): Boolean = when {
        arg == null -> false
        isApp(arg) -> Shell.isPkg(appPkg(arg))
        arg.startsWith("pair:") -> arg.removePrefix("pair:").toLongOrNull() != null
        arg.startsWith("page:") -> PAGES.any { it.first == arg.removePrefix("page:") }
        else -> false
    }

    private fun appLabel(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** "Open Chrome", "Open the pair Chrome + Discord", "Open Wayfinder: Stick lights". */
    fun label(ctx: Context, arg: String?): String = when {
        arg == null -> "Open…"
        isApp(arg) -> "Open ${appLabel(ctx, appPkg(arg))}" + when (appWhere(arg)) {
            "top" -> " on the top screen"; "bottom" -> " on the bottom screen"; else -> "" }
        arg.startsWith("pair:") -> Layouts.pairs.firstOrNull { it.id == arg.removePrefix("pair:").toLongOrNull() }
            ?.let { p -> "Open the pair " + listOfNotNull(p.top, p.bottom).joinToString(" + ") { appLabel(ctx, it) } }
            ?: "Open a deleted app pair"
        // build 18 (C10, VD-19): its home page is "Open Wayfinder" (not "Open Wayfinder: Home")
        arg.startsWith("page:") -> arg.removePrefix("page:").let { id ->
            if (id == HubPage.HOME) "Open Wayfinder" else "Open Wayfinder: " + (PAGES.firstOrNull { it.first == id }?.second ?: "?")
        }
        else -> "Open…"
    }

    /** The title of a binding as the user reads it (hints, lists, warnings). */
    fun title(ctx: Context, b: Binding): String = if (b.action == ThorAction.OPEN) label(ctx, b.arg) else b.action.title
}
