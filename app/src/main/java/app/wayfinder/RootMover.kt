package app.wayfinder

import android.app.ActivityManager
import android.os.IBinder

/**
 * Entry point run in a bare VM via `app_process` as root (uid 0).
 *
 * A plain root shell can run `am`, but `am` exposes no command for
 * IActivityTaskManager.moveRootTaskToDisplay — the call that reparents a live
 * task without relaunching it. This class makes that binder call directly, so
 * a rooted device gets the same move quality as Shizuku with no companion app.
 * It is the root-only equivalent of Shizuku's own app_process server, minus the
 * persistent daemon: spawned per swap, one call, then exits.
 *
 * Launched by [RootHelper.moveTaskViaBinder] as:
 *   su -c "CLASSPATH=<apk> app_process /system/bin \
 *       app.wayfinder.RootMover <pkg> <displayId> [fromDisplayId]"
 *
 * Or `<pkg> front <displayId>` (1.3): bring [pkg]'s task to the front of its screen the way
 * Recents does (startActivityFromRecents) — the task as it was, no new intent, no relaunch.
 *
 * Contract with the caller (parsed from stdout / exit code):
 *   stdout "OK <taskId>"  + exit 0  → moved
 *   stdout "ERR <reason>" + exit ≠0 → caller falls back to am / trampoline
 *
 * Runs as a non-app process, so ART hidden-API enforcement does not apply and
 * the reflected framework calls resolve freely (same as the `am` binary).
 */
object RootMover {

    private const val MAX_TASKS = 200

    // TaskInfo.displayId / isVisible are @hide fields; no hidden-API checks in app_process (root).
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun displayOf(t: ActivityManager.RunningTaskInfo): Int =
        runCatching { android.app.TaskInfo::class.java.getDeclaredField("displayId").apply { isAccessible = true }.getInt(t) }.getOrDefault(-1)

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun visible(t: ActivityManager.RunningTaskInfo): Boolean =
        runCatching { android.app.TaskInfo::class.java.getDeclaredField("isVisible").apply { isAccessible = true }.getBoolean(t) }.getOrDefault(true)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            fail(2, "usage: <pkg> <displayId>")
            return
        }
        val pkg = args[0]
        if (args[1] == "front") { front(pkg, args.getOrNull(2)?.toIntOrNull()); return }
        val displayId = args[1].toIntOrNull()
        if (displayId == null) {
            fail(2, "bad displayId '${args[1]}'")
            return
        }

        try {
            // ServiceManager + IActivityTaskManager$Stub are @hide; reflection
            // avoids a compile-time dependency on framework-internal classes.
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder
            if (binder == null) {
                fail(3, "activity_task service unavailable")
                return
            }
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!

            // Resolve by NAME, not exact signature: getTasks' parameter list
            // drifts between ROMs/versions (AOSP 13 is (int,boolean,boolean),
            // but this AYN build differs). Build args from the actual types:
            // first int = maxNum, any other int = -1 (e.g. displayId=all),
            // booleans = false (don't filter, no intent extras).
            val getTasks = atm.javaClass.methods.firstOrNull { it.name == "getTasks" }
                ?: run {
                    fail(5, "getTasks not found; candidates=" + signaturesOf(atm, "Task"))
                    return
                }
            val gArgs = getTasks.parameterTypes.mapIndexed { i, t ->
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> MAX_TASKS
                    t == Int::class.javaPrimitiveType -> -1
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            val tasks = getTasks.invoke(atm, *gArgs) as? List<*> ?: emptyList<Any?>()

            // An app can have several tasks (e.g. two Settings tasks), and getTasks lists
            // display by display, so "the first one" could be a hidden task on the other
            // screen (2026-09-23: the wrong Settings task moved). Prefer the VISIBLE task
            // on the screen the app was seen on, then any task not already on the target.
            val from = args.getOrNull(2)?.toIntOrNull()
            val mine = tasks.filterIsInstance<ActivityManager.RunningTaskInfo>().filter {
                it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg
            }
            val task = mine.firstOrNull { from != null && displayOf(it) == from && visible(it) }
                ?: mine.firstOrNull { from != null && displayOf(it) == from }
                ?: mine.firstOrNull { displayOf(it) != displayId && visible(it) }
                ?: mine.firstOrNull { displayOf(it) != displayId }
                ?: mine.firstOrNull()
            if (task == null) {
                fail(4, "no running task for $pkg")
                return
            }

            val move = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
                ?: run {
                    fail(6, "moveRootTaskToDisplay not found; candidates=" + signaturesOf(atm, "Display"))
                    return
                }
            move.invoke(atm, task.taskId, displayId)

            println("OK ${task.taskId}")
            halt(0)
        } catch (t: Throwable) {
            // Throwable, not Exception: reflection can raise linkage errors.
            // Print the chain to stdout — the pservice bridge only returns stdout.
            var c: Throwable? = t
            val sb = StringBuilder()
            while (c != null) { sb.append(c.javaClass.name).append(": ").append(c.message).append(" | "); c = c.cause }
            fail(1, sb.toString())
        }
    }

    /** 1.3: [pkg]'s task (the one on [display], if given) to the front, as Recents would. */
    private fun front(pkg: String, display: Int?) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "activity_task") as? IBinder
                ?: return fail(3, "activity_task service unavailable")
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val getTasks = atm.javaClass.methods.firstOrNull { it.name == "getTasks" } ?: return fail(5, "getTasks not found")
            val gArgs = getTasks.parameterTypes.mapIndexed { i, t ->
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> MAX_TASKS
                    t == Int::class.javaPrimitiveType -> -1
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            val mine = (getTasks.invoke(atm, *gArgs) as? List<*>).orEmpty().filterIsInstance<ActivityManager.RunningTaskInfo>()
                .filter { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }
            val task = mine.firstOrNull { display != null && displayOf(it) == display } ?: mine.firstOrNull()
                ?: return fail(4, "no running task for $pkg")
            val fromRecents = atm.javaClass.methods.firstOrNull { it.name == "startActivityFromRecents" && it.parameterTypes.size == 2 }
                ?: return fail(6, "startActivityFromRecents not found; candidates=" + signaturesOf(atm, "Recents"))
            val opts = display?.let { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            fromRecents.invoke(atm, task.taskId, opts)
            println("OK ${task.taskId}")
            halt(0)
        } catch (t: Throwable) {
            fail(1, "${t.javaClass.name}: ${t.message} | ${t.cause?.javaClass?.name}: ${t.cause?.message}")
        }
    }

    private fun fail(code: Int, reason: String) {
        // stdout, not stderr: the pservice bridge only returns stdout
        println("ERR $reason")
        halt(code)
    }

    // For diagnostics: list method signatures whose name contains [needle]
    private fun signaturesOf(obj: Any, needle: String): String =
        obj.javaClass.methods.filter { it.name.contains(needle) }.joinToString("; ") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }

    // A bare app_process VM has live binder threads; System.exit alone can hang
    // on shutdown hooks, so halt the runtime hard once the work is done.
    private fun halt(code: Int) {
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(code)
    }
}
