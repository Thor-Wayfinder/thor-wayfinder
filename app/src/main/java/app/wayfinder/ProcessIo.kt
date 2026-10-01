package app.wayfinder

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Drain a process's stdout/stderr on daemon threads and bound waitFor.
 *
 * A command that never exits (or fills the stderr pipe buffer) must not hang
 * the swap thread forever — that would leave swapInProgress stuck until
 * service restart. Returns (exitCode, stdout); exitCode -1 on timeout.
 */
internal fun runProcessWithTimeout(
    process: Process,
    label: String,
    timeoutSeconds: Long
): Pair<Int, String> {
    val stdout = StringBuilder()
    val stdoutThread = Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }
    Thread {
        try {
            process.errorStream.bufferedReader().readText()
        } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        // only the command word: the rest can name apps, and any app can read the log on the Thor
        Log.w("ThorShell", "Command timed out after ${timeoutSeconds}s: ${label.split(' ').take(2).joinToString(" ")}")
        process.destroy()
        return -1 to stdout.toString().trim()
    }
    stdoutThread.join(1000)
    return process.exitValue() to stdout.toString().trim()
}
