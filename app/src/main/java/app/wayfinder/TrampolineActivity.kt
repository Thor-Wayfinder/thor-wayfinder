package app.wayfinder

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Invisible helper activity that launches a target app from the correct display context.
 *
 * Why this exists:
 * Context.startActivity() + setLaunchDisplayId() is unreliable for singleTask apps —
 * they ignore the display hint and reopen on their original display. By first placing
 * this lightweight trampoline ON the target display, the subsequent startActivity call
 * originates from that display's context, which Android respects as a stronger hint
 * for task placement.
 */
class TrampolineActivity : Activity() {

    /** b19 (R5): this one closes Android's Recents from its screen ([EXTRA_CLOSE_RECENTS]); it launches nothing. */
    private var closing = false
    private val closeHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // b19 (R5): B in Recents, its app on the other screen (ForegroundAppService.closeRecentsFrom; b19 round 2: also on
        // Recents' own screen when a move put B's app there, and on the controller's screen when Recents' Back would go there).
        // An empty, see-through task that appears on that screen and goes: Quickstep ends its animation when a task appears
        // and puts Recents' screen back as it was. It goes once its entry is over (the transition that showed it has run), at the
        // latest after CLOSE_MAX_MS; the app under it keeps its place. Touches go through to that app meanwhile.
        // b19 review: never focusable either. The window with a screen's focus decides that screen's system bars, and Android 13
        // lists a window that takes no touches only while it has the focus: a focusable closer showed the navigation bar over the
        // game for its life and could make the profile tick read Wayfinder as the controller's app (the game's controls off, a
        // held button let go). Not focusable: no window has that screen's focus while it is up, so the bars stay as the app set
        // them, the window list never shows it, and a press meanwhile waits for it to go (Android waits for its window), then
        // reaches the app. Its task still appears, which is what ends Recents
        if (intent.getBooleanExtra(EXTRA_CLOSE_RECENTS, false)) {
            closing = true
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            closeHandler.postDelayed({ if (!isFinishing) finish() }, CLOSE_MAX_MS)
            Log.d(TAG, "Recents closer on display ${display?.displayId}")
            return
        }

        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
        val targetDisplay = intent.getIntExtra(EXTRA_TARGET_DISPLAY, -1)
        val aggressive = intent.getBooleanExtra(EXTRA_AGGRESSIVE, false)

        Log.d(TAG, "Trampoline on display ${display?.displayId}, " +
            "${if (aggressive) "AGGRESSIVE" else "gentle"} → $targetPkg → display $targetDisplay")

        if (targetPkg != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                if (aggressive) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                } else {
                    // MULTIPLE_TASK creates a NEW task on the target display.
                    // The old task stays but gets covered by the other app / launcher.
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    )
                }
                if (targetDisplay >= 0) {
                    val opts = ActivityOptions.makeBasic()
                    opts.setLaunchDisplayId(targetDisplay)
                    startActivity(launchIntent, opts.toBundle())
                } else {
                    startActivity(launchIntent)
                }
                Log.d(TAG, "Target launched (caller-display=${display?.displayId}, aggressive=$aggressive)")
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "No launch intent for $targetPkg")
            }
        }

        finish()
    }

    /** b19 (R5): its entry is over, so Quickstep has seen it appear: the closer goes. */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (closing && !isFinishing) finish()
    }

    /** b19 (R5): a second B (Back to Android) while it is up must not end it before its entry is over (not focusable, it gets
     *  no key: kept in case a Back reaches it anyway). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!closing) super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!closing) return
        closeHandler.removeCallbacksAndMessages(null)
        ForegroundAppService.recentsCloserDone()   // the controller with the app under it
    }

    companion object {
        private const val TAG = "ThorTrampoline"
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_TARGET_DISPLAY = "target_display"
        const val EXTRA_AGGRESSIVE = "aggressive"
        /** b19 (R5): close Android's Recents from this screen; nothing is launched ([ForegroundAppService.closeRecentsFrom]). */
        const val EXTRA_CLOSE_RECENTS = "close_recents"
        /** b19 (R5): the closer goes by then even if its entry never reports done. */
        const val CLOSE_MAX_MS = 1500L
    }
}
