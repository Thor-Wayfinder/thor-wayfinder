package app.wayfinder

import android.os.IBinder

/**
 * Entry point run in an app_process VM as root (via pservice/su). Powers a
 * physical display panel off or on WITHOUT touching the app(s) on it — the
 * blank/wake primitive for the second screen.
 *
 *   ... app_process ... app.wayfinder.DisplayPowerTool <physicalId> <mode>
 * mode: 0 = off (blank), 2 = on. Prints "OK ..." / "ERR ...".
 */
object DisplayPowerTool {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) { fail("usage: <physicalId> <mode>"); return }
        val physId = args[0].toLongOrNull()
        val mode = args[1].toIntOrNull()
        if (physId == null || mode == null) { fail("bad args"); return }
        try {
            val sc = Class.forName("android.view.SurfaceControl")
            // getPhysicalDisplayToken(long) → IBinder (name-resolved for ROM variance)
            val tokenMethod = sc.methods.firstOrNull {
                it.name == "getPhysicalDisplayToken" && it.parameterTypes.size == 1
            } ?: run { fail("getPhysicalDisplayToken not found; " + sig(sc, "Token")); return }
            val token = tokenMethod.invoke(null, physId) as? IBinder
                ?: run { fail("no token for $physId"); return }
            val setMode = sc.methods.firstOrNull {
                it.name == "setDisplayPowerMode" && it.parameterTypes.size == 2
            } ?: run { fail("setDisplayPowerMode not found; " + sig(sc, "PowerMode")); return }
            setMode.invoke(null, token, mode)
            println("OK physId=$physId mode=$mode")
            halt(0)
        } catch (t: Throwable) {
            fail(chain(t))
        }
    }

    private fun sig(c: Class<*>, needle: String): String =
        c.methods.filter { it.name.contains(needle) }.joinToString("; ") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }

    private fun chain(t: Throwable): String {
        val sb = StringBuilder(); var c: Throwable? = t
        while (c != null) { sb.append(c.javaClass.name).append(": ").append(c.message).append(" | "); c = c.cause }
        return sb.toString()
    }

    private fun fail(reason: String) { println("ERR $reason"); halt(1) }
    private fun halt(code: Int) { System.out.flush(); Runtime.getRuntime().halt(code) }
}
