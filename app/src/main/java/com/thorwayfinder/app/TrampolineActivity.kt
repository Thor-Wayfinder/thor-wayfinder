package com.thorwayfinder.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                Log.w(TAG, "No launch intent for $targetPkg")
            }
        }

        finish()
    }

    companion object {
        private const val TAG = "ThorTrampoline"
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_TARGET_DISPLAY = "target_display"
        const val EXTRA_AGGRESSIVE = "aggressive"
    }
}
