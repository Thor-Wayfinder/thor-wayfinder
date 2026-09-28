package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** What a screenshot captures. */
enum class ShotTarget(val label: String) { TOP("Top"), BOTTOM("Bottom"), BOTH("Both") }

/**
 * Screenshots of either screen or both (stacked like the device: top above
 * bottom), saved to Pictures/Screenshots. Uses the accessibility screenshot API
 * (no root; `canTakeScreenshot`), falling back to root `screencap` if it fails.
 * The a11y API allows one capture per ~333 ms, so "both" captures sequentially.
 */
class Screenshotter(private val service: AccessibilityService) {

    companion object { private const val TAG = "ThorShot"; private const val A11Y_INTERVAL_MS = 380L }

    private val main = Handler(Looper.getMainLooper())
    private val mainExec = Executor { main.post(it) }
    @Volatile private var busy = false

    /** [onDone] gets the saved file name, or null on failure. Main thread. */
    fun capture(topId: Int, bottomId: Int?, target: ShotTarget, onDone: (String?) -> Unit) {
        if (busy) return
        busy = true
        val ids = when (target) {
            ShotTarget.TOP -> listOf(topId)
            ShotTarget.BOTTOM -> listOfNotNull(bottomId ?: topId)
            ShotTarget.BOTH -> listOfNotNull(topId, bottomId)
        }
        grabAll(ids, mutableMapOf()) { shots ->
            Thread {
                val result = try {
                    if (shots.size < ids.size) null
                    else {
                        val bmp = if (ids.size == 1) shots.getValue(ids[0]) else stack(shots.getValue(ids[0]), shots.getValue(ids[1]))
                        save(bmp, when (target) { ShotTarget.TOP -> "top"; ShotTarget.BOTTOM -> "bottom"; ShotTarget.BOTH -> "both" })
                    }
                } catch (e: Exception) { Log.w(TAG, "save failed: ${e.message}"); null }
                main.post {
                    busy = false
                    if (result != null) ids.forEach { flash(it) }
                    onDone(result)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun grabAll(ids: List<Int>, acc: MutableMap<Int, Bitmap>, done: (Map<Int, Bitmap>) -> Unit) {
        val next = ids.firstOrNull { it !in acc } ?: return done(acc)
        grab(next) { bmp ->
            if (bmp != null) acc[next] = bmp
            else return@grab done(acc)   // give up; caller sees a short map
            if (acc.size < ids.size) main.postDelayed({ grabAll(ids, acc, done) }, A11Y_INTERVAL_MS) else done(acc)
        }
    }

    private fun grab(displayId: Int, cb: (Bitmap?) -> Unit) {
        try {
            service.takeScreenshot(displayId, mainExec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    cb(soft)
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "a11y screenshot of $displayId failed ($errorCode) — trying root")
                    rootGrab(displayId, cb)
                }
            })
        } catch (e: Exception) { Log.w(TAG, "takeScreenshot: ${e.message}"); rootGrab(displayId, cb) }
    }

    private fun rootGrab(displayId: Int, cb: (Bitmap?) -> Unit) = Thread {
        val bmp = runCatching { ScreenCapture.captureDisplays(service, listOf(displayId))[displayId] }.getOrNull()
        main.post { cb(bmp) }
    }.apply { isDaemon = true }.start()

    /** Top screen above bottom screen, bottom centred — the way the Thor looks. */
    private fun stack(top: Bitmap, bottom: Bitmap): Bitmap {
        val w = maxOf(top.width, bottom.width)
        val out = Bitmap.createBitmap(w, top.height + bottom.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(top, ((w - top.width) / 2).toFloat(), 0f, null)
            drawBitmap(bottom, ((w - bottom.width) / 2).toFloat(), top.height.toFloat(), null)
        }
        return out
    }

    private fun save(bmp: Bitmap, suffix: String): String {
        val name = "Thor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_$suffix.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = service.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.d(TAG, "saved $name (${bmp.width}x${bmp.height})")
        return name
    }

    /** Quick white flash on a captured screen (shutter feedback). */
    private fun flash(displayId: Int) {
        val display: Display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = View(ctx).apply { setBackgroundColor(Color.WHITE); alpha = 0.55f }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(v, lp)
            val gone = Runnable { try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
            v.animate().alpha(0f).setDuration(260).withEndAction(gone).start()
            v.postDelayed(gone, 600)   // a cancelled fade has no end action
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(gone, 700)
        } catch (_: Exception) {}
    }
}
