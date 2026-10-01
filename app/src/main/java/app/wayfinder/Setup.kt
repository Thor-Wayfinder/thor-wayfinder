package app.wayfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * One-press setup (plug and play): what a new user would otherwise do in three Android
 * settings screens — turn on Wayfinder's accessibility service, let it run in the
 * background, pick the Thor keyboard — done through the root bridge, which works before
 * the service is on. Without root, the matching settings screen opens instead.
 */
object Setup {
    private const val TAG = "ThorSetup"
    private val main = Handler(Looper.getMainLooper())

    private fun service(ctx: Context) = "${ctx.packageName}/${ForegroundAppService::class.java.name}"
    private fun keyboard(ctx: Context) = "${ctx.packageName}/.keyboard.ThorKeyboardService"

    /** Accessibility service on (keeps any other services the user has on). */
    fun enableService(ctx: Context) = viaRoot(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
        // 1.4: already on but Android stopped it ("crashed") — off and on again
        if (ServiceWatch.stuck(ctx)) return@viaRoot ServiceWatch.revive(ctx)
        val me = service(ctx)
        // The list can be long (pservice drops long commands): a script that appends us.
        val f = File(ctx.filesDir, "setup_a11y.sh")
        f.writeText("""
            |cur=${'$'}(settings get secure enabled_accessibility_services)
            |case "${'$'}cur" in *$me*) ;; null|"") settings put secure enabled_accessibility_services $me ;; *) settings put secure enabled_accessibility_services "${'$'}cur:$me" ;; esac
            |settings put secure accessibility_enabled 1
            |echo OK
            |""".trimMargin())
        PServiceBridge.exec("sh ${f.absolutePath}")?.trim() == "OK"
    }

    /** Off Android's battery optimisation (so it isn't stopped in the background). */
    fun allowBackground(ctx: Context) = viaRoot(ctx,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) {
        PServiceBridge.exec("cmd deviceidle whitelist +${ctx.packageName}") != null
    }

    /** The Thor keyboard enabled and selected. */
    fun useKeyboard(ctx: Context) = viaRoot(ctx, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) {
        val k = keyboard(ctx)
        PServiceBridge.exec("ime enable $k; ime set $k") != null
    }

    fun notificationsAllowed(ctx: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The status notification (shows Wayfinder is running). Without root: Android's prompt. */
    fun allowNotifications(activity: android.app.Activity) {
        Thread {
            val ok = PServiceBridge.isAvailable() &&
                PServiceBridge.exec("pm grant ${activity.packageName} android.permission.POST_NOTIFICATIONS; echo OK")?.trim() == "OK" &&
                notificationsAllowed(activity)
            if (!ok) main.post { runCatching { activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) } }
        }.start()
    }

    /** Root in the background; if that's unavailable or fails, open [fallback]. */
    private fun viaRoot(ctx: Context, fallback: Intent, work: () -> Boolean) {
        val app = ctx.applicationContext
        Thread {
            val ok = PServiceBridge.isAvailable() && runCatching(work).onFailure { Log.w(TAG, "setup: $it") }.getOrDefault(false)
            Log.i(TAG, "${fallback.action}: ${if (ok) "done" else "opening settings"}")
            if (!ok) main.post { runCatching { app.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }.start()
    }
}
