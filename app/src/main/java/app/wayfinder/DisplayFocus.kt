package app.wayfinder

import android.app.ActivityManager
import android.os.IBinder

/**
 * Move Android's input focus (the "focused display" that receives controller
 * / key input) to a given display. ROOT-SIDE ONLY: runs inside an app_process VM
 * (the persistent [InputMonitorTool] helper, or [FocusTool] for one-shot tests),
 * where hidden framework calls resolve freely.
 *
 * Why not a synthetic tap: a touch only moves display focus if it lands on a
 * window that can take keys, so off-screen "tap -10 -10" tricks work by accident
 * on one screen and not the other (verified on the Thor).
 *
 * Primary: IActivityTaskManager.focusTopTask(displayId) (Android 12+).
 * Fallback: setFocusedTask(<top task on that display>).
 * Methods are resolved by NAME (signatures drift on this ROM).
 */
object DisplayFocus {

    private val atm: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder
        Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    /** Returns "OK <how>" or "ERR <reason>". */
    fun focus(displayId: Int): String {
        return try {
            val m = atm.javaClass.methods.firstOrNull { it.name == "focusTopTask" && it.parameterTypes.size == 1 }
            if (m != null) {
                m.invoke(atm, displayId)
                "OK focusTopTask($displayId)"
            } else {
                val taskId = topTaskOn(displayId) ?: return "ERR no task on display $displayId"
                val set = atm.javaClass.methods.firstOrNull { it.name == "setFocusedTask" }
                    ?: return "ERR neither focusTopTask nor setFocusedTask"
                set.invoke(atm, taskId)
                "OK setFocusedTask($taskId)"
            }
        } catch (t: Throwable) {
            var c: Throwable? = t
            val sb = StringBuilder("ERR ")
            while (c != null) { sb.append(c.javaClass.simpleName).append(": ").append(c.message).append(" | "); c = c.cause }
            sb.toString()
        }
    }

    private fun topTaskOn(displayId: Int): Int? {
        val getTasks = atm.javaClass.methods.firstOrNull { it.name == "getTasks" } ?: return null
        val args = getTasks.parameterTypes.mapIndexed { i, t ->
            when {
                t == Int::class.javaPrimitiveType && i == 0 -> 50
                t == Int::class.javaPrimitiveType -> -1
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        val tasks = getTasks.invoke(atm, *args) as? List<*> ?: return null
        return tasks.filterIsInstance<ActivityManager.RunningTaskInfo>().firstOrNull { ti ->
            runCatching { ti.javaClass.getField("displayId").getInt(ti) }.getOrNull() == displayId
        }?.taskId
    }
}

/** One-shot entrypoint for testing: `app_process … app.wayfinder.FocusTool <displayId>`. */
object FocusTool {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(0) == "list") {   // diagnostics: candidate methods per service
            for ((svc, stub) in listOf(
                "activity_task" to "android.app.IActivityTaskManager\$Stub",
                "window" to "android.view.IWindowManager\$Stub",
                "input" to "android.hardware.input.IInputManager\$Stub",
            )) runCatching {
                val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, svc) as IBinder
                val o = Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
                o.javaClass.methods.filter { m ->
                    listOf("ocus", "ToTop", "Top", "isplayToTop").any { m.name.contains(it) }
                }.map { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}): ${m.returnType.simpleName}" }
                    .distinct().sorted().forEach { println("$svc.$it") }
            }.onFailure { println("$svc: ${it.message}") }
        } else if (args.getOrNull(0) == "mode") {   // AYN's IWindowManager.setFocusedMode(int)
            val v = args.getOrNull(1)?.toIntOrNull() ?: 0
            println(runCatching {
                val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
                val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
                wm.javaClass.methods.first { it.name == "setFocusedMode" }.invoke(wm, v)
                "OK setFocusedMode($v)"
            }.getOrElse { "ERR ${it.cause ?: it}" })
        } else {
            val id = args.getOrNull(0)?.toIntOrNull()
            println(if (id == null) "ERR usage: <displayId>" else DisplayFocus.focus(id))
        }
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}
