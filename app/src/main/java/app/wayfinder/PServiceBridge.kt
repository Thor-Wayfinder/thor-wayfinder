package app.wayfinder

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Bridge to AYN's own root daemon "pservice" (uid 0, running from boot), which
 * registers the binder service "PServerBinder" and executes shell commands as
 * root via popen. This is the backend of the firmware's
 * Settings → Thor → "Run Script as Root" feature (client in com.odin.settings,
 * log tag "PServiceBridgeV2").
 *
 * On this firmware our app's domain (untrusted_app) can reach it — verified on
 * device: `id` returns uid=0(root). This gives Thor Wayfinder root-level moves
 * with ZERO setup — no Shizuku, no ADB pairing, no rooting — and it survives
 * reboots because pservice always runs.
 *
 * IMPORTANT: this works because the device's SELinux is Permissive (AYN's
 * "Force SELinux" toggle OFF, the shipped default — AYN's own root-script
 * feature needs it too). If SELinux is switched to Enforcing, the service
 * becomes unreachable and Thor Wayfinder falls back to Shizuku/trampoline.
 * [isAvailable] re-checks per boot so the app adapts automatically.
 *
 * Transaction contract (from com.odin.settings b1.C0182b):
 *   code 0, data = writeStringArray([command, "0"]), NO interface token,
 *   reply = createByteArray() → the command's stdout.
 */
object PServiceBridge {

    private const val TAG = "ThorPService"
    private const val SERVICE = "PServerBinder"
    private const val CODE_EXEC = 0

    @Volatile private var cached: IBinder? = null
    @Volatile private var checked = false
    @Volatile private var available = false

    fun probed(): Boolean = checked
    fun cachedAvailable(): Boolean = checked && available

    fun probeAsync() {
        if (checked) return
        Thread { isAvailable() }.apply { isDaemon = true }.start()
    }

    /** True if pservice is reachable AND runs our commands as root. Cached. */
    fun isAvailable(): Boolean {
        if (!checked) {
            synchronized(this) {
                // Only a SUCCESS is cached: one failed probe at boot (pservice not up yet)
                // used to disable root — moves, audio, input — until the app restarted.
                val now = android.os.SystemClock.uptimeMillis()
                if (!checked && now - lastFail > 10_000) {
                    available = try {
                        exec("id")?.contains("uid=0") == true
                    } catch (t: Throwable) {
                        false
                    }
                    if (available) checked = true else lastFail = now
                    Log.d(TAG, "pservice available=$available")
                }
            }
        }
        return available
    }
    @Volatile private var lastFail = -1_000_000L

    private fun service(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as? IBinder
            cached = binder
            binder
        } catch (t: Throwable) {
            Log.w(TAG, "getService($SERVICE): ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Run [cmd] as root via pservice; returns stdout, or null on failure. */
    /**
     * One command at a time: overlapping transactions (e.g. the input helper's launch
     * and a Clear right after a reinstall) came back empty — Clear then looked failed.
     */
    private val execLock = Any()

    fun exec(cmd: String): String? = synchronized(execLock) { execLocked(cmd) }

    private fun execLocked(cmd: String): String? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "0"))
            if (!binder.transact(CODE_EXEC, data, reply, 0)) return null
            reply.createByteArray()?.let { String(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "transact: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Run one of the app's app_process entry points ([RootMover], [RecentsTool])
     * as root through pservice. Returns stdout (with stderr merged), or null.
     */
    private val runSeq = java.util.concurrent.atomic.AtomicLong()

    fun runEntryPoint(context: Context, simpleClassName: String, vararg args: String): String? {
        val apk = context.applicationInfo.sourceDir
        val a = args.joinToString(" ") { Shell.q(it) }   // quoted for real: a ' can't break out
        // Two pservice quirks: it returns only the FIRST line of output, and it silently
        // drops long commands (~300+ chars: Clear's keep-list made it come back empty and
        // "triple-tap Back" did nothing). So the command goes into a small script run as
        // `sh <file>`, and we hand back the tool's own result line ("OK …" / "ERR …").
        val cmd = "CLASSPATH='$apk' app_process /system/bin " +
            "app.wayfinder.$simpleClassName $a 2>&1 | grep -m1 -E '^(OK|ERR)'\n"
        // One file per call: two threads running the same tool used to overwrite each
        // other's script (a brightness read could come back with the other screen's level).
        val script = java.io.File(context.cacheDir, "thor_run_${simpleClassName}_${runSeq.incrementAndGet()}.sh")
        return try {
            script.writeText(cmd)
            exec("sh ${script.absolutePath}")?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "runEntryPoint $simpleClassName: ${e.message}"); null
        } finally {
            script.delete()
        }
    }

    /**
     * Best move: reparent the live task via binder by running [RootMover] in an
     * app_process VM as root through pservice. No relaunch (state preserved),
     * no new instance (no duplicates), works on singleTask apps.
     */
    fun moveTaskViaBinder(context: Context, pkg: String, displayId: Int, fromDisplay: Int? = null): Boolean {
        val out = if (fromDisplay == null) runEntryPoint(context, "RootMover", pkg, displayId.toString())
            else runEntryPoint(context, "RootMover", pkg, displayId.toString(), fromDisplay.toString())
        Log.d(TAG, "RootMover $pkg → $displayId via pservice: $out")
        return out?.contains("OK") == true
    }

    /** 1.3: [pkg]'s task to the front of [displayId], as Recents does (no relaunch) — see [RootMover]. */
    fun frontTask(context: Context, pkg: String, displayId: Int): Boolean {
        val out = runEntryPoint(context, "RootMover", pkg, "front", displayId.toString())
        Log.d(TAG, "RootMover front $pkg on $displayId via pservice: $out")
        return out?.contains("OK") == true
    }

    /** `am start --display <id> -n <component>` as root via pservice. */
    fun startOnDisplay(context: Context, pkg: String, displayId: Int): Boolean {
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return false
        val c = Shell.component(component.flattenToString()) ?: return false
        val out = exec("am start --display $displayId -n $c")
        Log.d(TAG, "am start --display $displayId $pkg via pservice: ${out?.trim()}")
        return out != null && !out.contains("Error")
    }

    /** `am force-stop <pkg>` as root via pservice. */
    fun forceStop(pkg: String): Boolean {
        if (!Shell.isPkg(pkg)) return false
        val out = exec("am force-stop ${Shell.q(pkg)}")
        Log.d(TAG, "am force-stop $pkg via pservice: ${out?.trim()}")
        return out != null
    }

    /** Bring the launcher/home to the top of [displayId] as root via pservice. */
    fun goHomeOnDisplay(displayId: Int): Boolean {
        val out = exec(
            "am start --display $displayId " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME"
        )
        Log.d(TAG, "home on display $displayId via pservice: ${out?.trim()}")
        return out != null && !out.contains("Error")
    }
}
