package app.wayfinder

import android.content.Context

/**
 * Round 8 (2026-09-25): what an "Open…" combo opens — an app (on the controller's screen), an app
 * pair, or a Wayfinder page. Stored on the binding as `app:<package>`, `pair:<id>` or `page:<id>`;
 * anything else is refused (a binding's text comes from the prefs or a backup, never trusted).
 */
object OpenTargets {
    /** The pages a combo can open (the Hub's own ids), with their names. */
    val PAGES = listOf(
        HubPage.HOME to "Home", HubPage.SCREENS to "Screens & power", HubPage.SLEEP to "Sleep & standby",
        HubPage.CONTROLLER to "Controller", HubPage.CONTROLS to "Combos", HubPage.KEYBOARD to "Keyboard",
        HubPage.APPS to "App profiles", HubPage.PANEL to "Quick panel", HubPage.PAIRS to "App pairs",
        HubPage.LIGHTS to "Stick lights", HubPage.APPEARANCE to "Appearance", HubPage.HELP to "Help & status",
    )

    fun app(pkg: String) = "app:$pkg"
    /** 1.3.2 (GitHub #42): an app on a chosen screen — `apptop:` / `appbottom:` (plain `app:` = the controller's). */
    fun app(pkg: String, where: String?) = when (where) { "top" -> "apptop:$pkg"; "bottom" -> "appbottom:$pkg"; else -> "app:$pkg" }
    fun isApp(arg: String) = arg.startsWith("app:") || arg.startsWith("apptop:") || arg.startsWith("appbottom:")
    fun appPkg(arg: String) = arg.substringAfter(':')
    fun appWhere(arg: String): String? = when { arg.startsWith("apptop:") -> "top"; arg.startsWith("appbottom:") -> "bottom"; else -> null }
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
        arg.startsWith("page:") -> "Open Wayfinder: " + (PAGES.firstOrNull { it.first == arg.removePrefix("page:") }?.second ?: "?")
        else -> "Open…"
    }

    /** The title of a binding as the user reads it (hints, lists, warnings). */
    fun title(ctx: Context, b: Binding): String = if (b.action == ThorAction.OPEN) label(ctx, b.arg) else b.action.title
}
