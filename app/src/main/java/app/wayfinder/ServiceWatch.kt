package app.wayfinder

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * 1.4 — Wayfinder back after a restart, always. While the Thor starts, Android's memory killer can stop dozens of apps
 * in the same second — Wayfinder too (verified 2026-10-01: "LOW_MEMORY" 2.6 s after boot). Android then counts its
 * accessibility service as "crashed" and doesn't connect it again until it's turned off and on: Wayfinder stayed dead
 * until then. Now, a minute after boot (and again two minutes later), if the service is ON in Android's settings but
 * not running, Wayfinder turns it off and on itself (root, like the setup button). Nothing happens if the user had
 * turned it off.
 */
object ServiceWatch {
    private const val TAG = "ThorWatch"
    private const val JOB = 7341

    fun schedule(ctx: Context, delayMs: Long) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB, ComponentName(ctx, Job::class.java))
            .setMinimumLatency(delayMs).setOverrideDeadline(delayMs + 30_000).build()
        runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "schedule: $it") }
    }

    private fun me(ctx: Context) = "${ctx.packageName}/${ForegroundAppService::class.java.name}"

    /** On in Android's settings, but not running in this process (it's the app's only process). */
    fun stuck(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        return list.split(':').any { it.equals(me(ctx), ignoreCase = true) } && !ForegroundAppService.isRunning
    }

    /** Off and on again through root: Android forgets the "crashed" mark and connects it. */
    fun revive(ctx: Context): Boolean {
        if (!PServiceBridge.isAvailable()) return false
        val me = me(ctx)
        val f = File(ctx.filesDir, "revive_a11y.sh")
        // the list can be long (pservice drops long commands): a script. Only OUR entry is taken out and put back.
        f.writeText("""
            |cur=${'$'}(settings get secure enabled_accessibility_services)
            |case "${'$'}cur" in *$me*) ;; *) echo SKIP; exit 0 ;; esac
            |rest=${'$'}(echo "${'$'}cur" | tr ':' '\n' | grep -v -x '$me' | paste -sd: -)
            |if [ -z "${'$'}rest" ] && [ "${'$'}cur" != "$me" ]; then echo FAIL; exit 0; fi
            |if [ -z "${'$'}rest" ]; then settings put secure enabled_accessibility_services null; else settings put secure enabled_accessibility_services "${'$'}rest"; fi
            |sleep 1
            |if [ -z "${'$'}rest" ]; then settings put secure enabled_accessibility_services $me; else settings put secure enabled_accessibility_services "${'$'}rest:$me"; fi
            |settings put secure accessibility_enabled 1
            |echo OK
            |""".trimMargin())
        val r = PServiceBridge.exec("sh ${f.absolutePath}")?.trim()
        Log.w(TAG, "accessibility service was stopped by Android — turned on again: $r")
        if (r != "OK") return false
        // it worked only if the service really runs again
        repeat(12) { if (ForegroundAppService.isRunning) return true; Thread.sleep(500) }
        return ForegroundAppService.isRunning
    }

    class Boot : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_BOOT_COMPLETED) return
            schedule(c.applicationContext, 60_000)
        }
    }

    class Job : JobService() {
        override fun onStartJob(p: JobParameters?): Boolean {
            val ctx = applicationContext
            Thread {
                try {
                    if (stuck(ctx)) revive(ctx)
                } finally {
                    jobFinished(p, false)
                    // once more later: the start of the Thor can take a while
                    if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000) schedule(ctx, 120_000)
                }
            }.apply { isDaemon = true }.start()
            return true
        }
        override fun onStopJob(p: JobParameters?) = false
    }
}
