package app.wayfinder

import android.content.Context
import android.provider.Settings

/**
 * Android's "this device finished its first setup" flags (`global device_provisioned`, `secure
 * user_setup_complete`). With either at 0, SystemUI silently refuses Recents — double Back (and
 * Android's own Recents key) do nothing, no error. Their built-in default is 0, so anything that
 * resets settings to defaults (Android's automatic recovery after repeated system crashes) leaves a
 * set-up Thor "unprovisioned" (seen on a user's Thor, 2026-09-26). We detect it and offer one press to put them back.
 */
object SetupFlagGuard {
    fun broken(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1) == 0 ||
            Settings.Secure.getInt(ctx.contentResolver, "user_setup_complete", 1) == 0

    /** Through the root bridge (global / secure settings). Off the main thread. */
    fun fix(done: () -> Unit) = Thread {
        PServiceBridge.exec("settings put global device_provisioned 1; settings put secure user_setup_complete 1")
        done()
    }.apply { isDaemon = true }.start()
}
