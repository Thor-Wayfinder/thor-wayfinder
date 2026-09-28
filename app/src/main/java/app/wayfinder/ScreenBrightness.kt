package app.wayfinder

import android.util.Log

/**
 * Per-screen brightness, done the way AYN's drawer does it: DisplayManager's
 * per-display `setBrightness(displayId, 0..1)` (the bottom screen has no settings key of
 * its own). That API needs a system permission, so it runs as root: in the input helper
 * ("L <display> <value>", fast, for sliders) or as a one-shot tool
 * (`BrightnessTool get|set …`, prints one "OK …" line for the pservice bridge).
 * Only ever called from root processes.
 */
object ScreenBrightness {
    private const val TAG = "ThorBrightness"

    private val global by lazy {
        Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
    }

    fun set(displayId: Int, value: Float) {
        runCatching {
            global.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(global, displayId, value.coerceIn(0f, 1f))
        }.onFailure { Log.w(TAG, "set $displayId: ${it.cause ?: it}") }
    }

    /** Current brightness 0..1, or null. */
    fun get(displayId: Int): Float? = runCatching {
        val info = global.javaClass.getMethod("getBrightnessInfo", Int::class.javaPrimitiveType).invoke(global, displayId)
            ?: return null
        info.javaClass.getField("brightness").getFloat(info)
    }.getOrNull()
}

/** app_process entry: `get <display>` → "OK <0..1>"; `set <display> <0..1>` → "OK". */
object BrightnessTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val d = args.getOrNull(1)?.toIntOrNull() ?: run { println("ERR usage"); return }
        when (args.getOrNull(0)) {
            "get" -> ScreenBrightness.get(d)?.let { println("OK $it") } ?: println("ERR no info")
            "set" -> { ScreenBrightness.set(d, args.getOrNull(2)?.toFloatOrNull() ?: 0.5f); println("OK") }
            else -> println("ERR usage")
        }
        System.exit(0)
    }
}
