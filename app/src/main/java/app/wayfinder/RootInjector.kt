package app.wayfinder

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.OutputStream

/**
 * Input deck — runs INSIDE the root input helper ([InputMonitorTool], uid 0), fed
 * one command per line by the app over the helper's socket:
 *
 *   K <keyCode> <1=down|0=up> <metaState> <displayId>   inject a key (no fake hardware:
 *        a virtual-keyboard key event, like `input keyevent`, but without a JVM per key)
 *   M <dx> <dy>          move the mouse (relative)
 *   B <0|1|2> <1|0>      mouse button left/right/middle down/up
 *   W <vertical> <horizontal>   scroll wheel
 *   R                    release the mouse (the virtual mouse device goes away)
 *
 * Keys are injected, not typed through a virtual keyboard DEVICE on purpose: an
 * alphabetic keyboard device makes Android believe a physical keyboard is attached —
 * on-screen keyboards hide and some games restart on that configuration change.
 * The mouse IS a real virtual device (Android's own `uinput` tool): only a real
 * pointer device gets the system cursor and true relative motion (DOSBox, PC games).
 * It exists only while the trackpad is used, and disappears with [release].
 */
object RootInjector {
    private const val TAG = "ThorInject"

    private val injectMethod by lazy {
        val imClass = Class.forName("android.hardware.input.InputManager")
        val im = imClass.getMethod("getInstance").invoke(null)
        val m = imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        im to m
    }
    private val setDisplayId by lazy {
        runCatching { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }.getOrNull()
    }

    fun handle(line: String) {
        val p = line.trim().split(' ')
        when (p.firstOrNull()) {
            "K" -> key(p[1].toInt(), p[2] == "1", p.getOrNull(3)?.toInt() ?: 0, p.getOrNull(4)?.toInt() ?: -1)
            "M" -> mouse(intArrayOf(2, 0, p[1].toInt(), 2, 1, p[2].toInt()))
            "B" -> mouse(intArrayOf(1, 272 + p[1].toInt().coerceIn(0, 2), if (p[2] == "1") 1 else 0))
            "W" -> mouse(intArrayOf(2, 8, p[1].toInt(), 2, 6, p.getOrNull(2)?.toInt() ?: 0))
            "R" -> release()      // the mouse only (the FPS counter / screen colour keep running)
            "L" -> ScreenBrightness.set(p[1].toInt(), p[2].toFloat())   // L <displayId> <0..1>
            // Q <displayId…> → "B <id> <0..1>" each: the brightness sliders' first read, in ms
            // instead of a root process per screen (the drawer's slider appeared ~0.5 s late).
            "Q" -> p.drop(1).mapNotNull { it.toIntOrNull() }.forEach { d ->
                ScreenBrightness.get(d)?.let { FpsSampler.send?.invoke("B $d $it\n") }
            }
            "F" -> FpsSampler.watch(p.getOrNull(1), p.getOrNull(2))      // F <top pkg|-> <bottom pkg|->  (FPS counter)
            "A" -> AmbientSampler.watch(p.getOrNull(1))                  // A <physical display id> | A -  (screen colour)
            "G" -> PadLayer.command(p)                                   // G on <wfpad path> | G off  (input layer)
            "S" -> PadLayer.setScreen(p.getOrNull(1) == "1")             // S 1 | S 0  (screens on / off: GitHub #40)
            // D <pkg> → "D <pkg> [<game-id> <title>]": which game it runs (per-game profiles);
            // off the command thread (it may copy a database), one at a time
            "D" -> p.getOrNull(1)?.let { pkg -> detector.execute { FpsSampler.send?.invoke(GameDetector.answer(pkg) + "\n") } }
        }
    }

    private val detector = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ── keys ────────────────────────────────────────────────────────────
    private val downTimes = HashMap<Int, Long>()

    private fun key(code: Int, down: Boolean, meta: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val downTime = if (down) now.also { downTimes[code] = it } else downTimes.remove(code) ?: now
        val ev = KeyEvent(
            downTime, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        if (displayId >= 0) runCatching { setDisplayId?.invoke(ev, displayId) }
        val (im, m) = injectMethod
        runCatching { m.invoke(im, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) }
            .onSuccess { if (down && BuildConfig.DEBUG) Log.d(TAG, "key $code → display $displayId") }
            .onFailure { Log.w(TAG, if (BuildConfig.DEBUG) "inject key $code: ${it.cause ?: it}" else "inject key failed: ${(it.cause ?: it).javaClass.simpleName}") }
    }

    // ── mouse (virtual uinput device) ───────────────────────────────────
    private var uinput: Process? = null
    private var uinputIn: OutputStream? = null

    private fun ensureMouse(): OutputStream? {
        uinputIn?.let { if (uinput?.isAlive == true) return it }
        return try {
            val proc = ProcessBuilder("/system/bin/uinput", "-").redirectErrorStream(true).start()
            val out = proc.outputStream
            // EV_KEY(1) + EV_REL(2); BTN_LEFT/RIGHT/MIDDLE; REL_X, REL_Y, REL_HWHEEL, REL_WHEEL.
            out.write((
                """{"id":1,"command":"register","name":"Wayfinder Trackpad","vid":6969,"pid":2,"bus":"usb",""" +
                    """"configuration":[{"type":100,"data":[1,2]},{"type":101,"data":[272,273,274]},""" +
                    """{"type":102,"data":[0,1,6,8]}]}""" + "\n"
                ).toByteArray())
            out.flush()
            // Drain the tool's output so it never blocks on a full pipe.
            Thread { runCatching { proc.inputStream.copyTo(OutputStream.nullOutputStream()) } }.apply { isDaemon = true; start() }
            uinput = proc; uinputIn = out
            Thread.sleep(150)   // let the device register before the first event
            Log.i(TAG, "virtual mouse up")
            out
        } catch (e: Exception) {
            Log.w(TAG, "uinput mouse: ${e.message}"); null
        }
    }

    private fun mouse(events: IntArray) {
        val out = ensureMouse() ?: return
        val list = ArrayList<Int>(events.size + 3)
        events.forEach { list.add(it) }
        list.add(0); list.add(0); list.add(0)   // SYN_REPORT
        try {
            out.write(("""{"id":1,"command":"inject","events":[${list.joinToString(",")}]}""" + "\n").toByteArray())
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "mouse write: ${e.message}"); release()
        }
    }

    fun release() {
        runCatching { uinputIn?.close() }
        runCatching { uinput?.destroy() }
        uinput = null; uinputIn = null
        Log.i(TAG, "virtual mouse released")
    }
}
