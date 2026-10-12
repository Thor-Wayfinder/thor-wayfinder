package app.wayfinder

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Installs Wayfinder's native audio effect (fx/wfwide.c → assets/fx/libwfwide.so, the
 * stereo widener) into the audio HAL — the only way to mix L and R, which no effect an
 * app can attach does. Same mechanism the DSP apps use on this ROM (verified 2026-09-23):
 *   - a tmpfs-backed copy of /vendor/lib64/soundfx with our .so added, bind-mounted over it;
 *   - the LIVE audio_effects.xml (whatever is mounted now — other apps' entries kept) with
 *     our library and effect entries, bind-mounted over it (SpeakerTune attaches the effect);
 *   - the audio HAL + audioserver restarted once (init brings them back; ~1–2 s of silence).
 * Nothing is written to /vendor: a reboot undoes it all. Once per boot, and only when the
 * speaker fix is on. The effect itself is driven by `persist.wayfinder.wide` (0 = neutral).
 * Build 18 (widener review): "installed" means LOADED — the audio server lists our effect ([loaded]: public API, no root).
 * The scripts' word alone (OK / ALREADY) once reported a widener another tool's overlay had pushed out, for hours. Every
 * answer that isn't OK / ALREADY / UPDATED is logged as a warning (release builds keep those), and a mount refused on a
 * layer that isn't ours (another tool's bind — often of a file deleted when that tool was uninstalled) is kept apart
 * ([failedOutside]): a restart of the Thor clears it. The mount itself is unchanged (build 19: the config's directory, and
 * the stock libraries copied from /vendor itself).
 */
object AudioFx {
    private const val TAG = "ThorAudioFx"
    @Volatile var installed = false
        private set
    /** Build 18 (widener review): the last attempt's mount was refused on a layer that isn't Wayfinder's (another tool's
     *  bind, or one of a deleted file) — nothing the app can undo; a restart of the Thor clears it. */
    @Volatile var failedOutside = false
        private set

    /** Blocking (root + an audio restart): call off the main thread. Returns true when in place (the audio server lists it). */
    @Synchronized fun ensureInstalled(ctx: Context): Boolean {
        if (installed) return true
        failedOutside = false
        if (!PServiceBridge.isAvailable()) { Log.w(TAG, "no root bridge"); return false }
        val dir = ctx.filesDir
        val lib = File(dir, "libwfwide.so")
        runCatching { ctx.assets.open("fx/libwfwide.so").use { i -> lib.outputStream().use { i.copyTo(it) } } }
            .onFailure { Log.w(TAG, "no effect library in the APK: $it"); return false }
        lib.setReadable(true, false)
        dir.setExecutable(true, false)
        val cur = File(dir, "fx_cur.xml"); val path = File(dir, "fx_path.txt"); cur.delete(); path.delete()

        // 1. Which config the HAL reads, and a copy of it as it is NOW (already ours → done).
        val prep = File(dir, "fx_prep.sh")
        prep.writeText(prepScript(lib.absolutePath, cur.absolutePath, path.absolutePath))
        val r1 = PServiceBridge.exec("sh ${prep.absolutePath}")?.trim()
        // (UPDATED: a newer library replaced ours — rm + cp, a new file: the HAL keeps the old one
        // mapped until the restart the script just did. Never overwrite a mapped .so in place.)
        when (r1) {
            "ALREADY" -> { Log.i(TAG, "prep: ALREADY"); return inPlace(ctx) }
            "UPDATED" -> { Log.i(TAG, "prep: UPDATED"); return back("prep: UPDATED") }
            "COPIED" -> Log.i(TAG, "prep: COPIED")
            else -> { Log.w(TAG, "prep: ${forLog(r1)}"); return false }
        }
        val xmlPath = path.readText().trim()
        var xml = cur.readText()
        if (!xml.contains("<libraries>") || !xml.contains("<effects>")) { Log.w(TAG, "unexpected effects config"); return false }

        // 2. Our entries: the library and the effect.
        xml = xml.replaceFirst("<libraries>", "<libraries>\n        <library name=\"wfwide\" path=\"libwfwide.so\"/>")
            .replaceFirst("<effects>", "<effects>\n        <effect name=\"wfwide\" library=\"wfwide\" uuid=\"$UUID\"/>")
        // (No post-process <apply>: SpeakerTune attaches it to the output mix itself — as a
        // post-process, AYN's AudioFlinger moved it to session 0 and then refused it.)
        val mine = File(dir, "fx_wf.xml"); mine.writeText(xml); mine.setReadable(true, false)

        // 3. Overlay, mount, restart audio. (Long → a script file: pservice drops long commands.)
        val mount = File(dir, "fx_mount.sh")
        mount.writeText(mountScript(xmlPath, lib.absolutePath, mine.absolutePath))
        val r2 = PServiceBridge.exec("sh ${mount.absolutePath}")?.trim()
        return when (r2) {
            "OK" -> { Log.i(TAG, "mount: OK"); back("mount: OK") }
            "ALREADY" -> { Log.i(TAG, "mount: ALREADY"); inPlace(ctx) }
            else -> { failedOutside = outside(r2); Log.w(TAG, "mount: ${forLog(r2)}"); false }
        }
    }

    /** Build 18 (widener review): our entries are in the config. Listed → in place; not listed (the audio server started
     *  before them, or didn't load them) → ONE audio restart, then listed or not. */
    private fun inPlace(ctx: Context): Boolean {
        if (loaded()) { installed = true; return true }
        Log.w(TAG, "in the effects config but not loaded: one audio restart")
        val f = File(ctx.filesDir, "fx_restart.sh")
        val r = runCatching { f.writeText(RESTART_SCRIPT); PServiceBridge.exec("sh ${f.absolutePath}")?.trim() }.getOrNull()
        if (r != "OK") { Log.w(TAG, "restart: ${forLog(r)}"); return false }
        return back("restart: OK")
    }

    /** Build 18 (widener review): the audio server was just restarted — wait (up to [LOAD_WAIT_MS]) for it to come back
     *  listing the widener; in place only then. */
    private fun back(what: String): Boolean {
        installed = pollUntil(LOAD_WAIT_MS, 250, SystemClock::elapsedRealtime, Thread::sleep, firstMs = 400) { loaded() }
        if (!installed) Log.w(TAG, "$what, but the audio server didn't list the widener within ${LOAD_WAIT_MS / 1000} s")
        return installed
    }

    /** Build 18 (widener review): the audio server lists our effect now — what "installed" means. Public API, no root;
     *  blocks while the audio server is restarting (off the main thread). */
    fun loaded(): Boolean = listed() == true

    /** true / false, or null: the audio server didn't answer. */
    private fun listed(): Boolean? = runCatching {
        AudioEffect.queryEffects()?.any { it.uuid == java.util.UUID.fromString(UUID) }
    }.getOrNull()

    /** Build 18 (widener review): the widener was in place and the audio server doesn't list it any more — another tool's
     *  overlay won an audio restart. Blocking: two looks [LOST_RECHECK_MS] apart, so never in the middle of a restart; no
     *  answer isn't "lost". */
    fun lost(): Boolean {
        if (!installed || listed() != false) return false
        Thread.sleep(LOST_RECHECK_MS)
        return installed && listed() == false
    }

    /** [lost]: in place no more — the next [ensureInstalled] adds it again. */
    fun forget() { installed = false }

    /** The widener's width, live (0 = neutral). */
    fun setWidth(w: Float) {
        // One worker, latest value wins (a slider drag fires dozens; separate threads could
        // land out of order and leave an older width).
        wantWidth = w
        widthWorker.execute {
            val v = wantWidth
            if (v == appliedWidth) return@execute
            appliedWidth = v
            PServiceBridge.exec("setprop persist.wayfinder.wide ${"%.2f".format(java.util.Locale.US, v)}")
        }
    }
    @Volatile private var wantWidth = 0f
    @Volatile private var appliedWidth = Float.NaN
    private val widthWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    const val UUID = "7f3a1c2f-51b4-4e8f-9a21-576179666e64"
    const val TYPE_UUID = "7f3a1c2e-51b4-4e8f-9a21-576179666e64"

    /** Build 18 (widener review): how long an audio restart may take to list the widener again (on a Thor: 1–2 s). */
    internal const val LOAD_WAIT_MS = 5000L
    internal const val LOST_RECHECK_MS = 3000L

    // ── the scripts (pure: JVM tests read them) ──

    /** Which config the HAL reads; ALREADY (ours, same library), UPDATED (ours, a newer library: replaced + an audio
     *  restart), COPIED (a copy of it at [cur], its path at [path]), NOXML. One line: pservice returns only the first. */
    internal fun prepScript(lib: String, cur: String, path: String): String = """
            |X=/vendor/etc/audio/sku_$(getprop ro.board.platform)/audio_effects.xml
            |[ -f ${'$'}X ] || X=/vendor/etc/audio_effects.xml
            |[ -f ${'$'}X ] || { echo NOXML; exit 0; }
            |L=/vendor/lib64/soundfx/libwfwide.so
            |if grep -q libwfwide ${'$'}X; then
            |  cmp -s $lib ${'$'}L && { echo ALREADY; exit 0; }
            |  rm -f ${'$'}L; cp $lib ${'$'}L; chmod 644 ${'$'}L; chcon u:object_r:vendor_file:s0 ${'$'}L 2>/dev/null
            |  kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |  echo UPDATED; exit 0
            |fi
            |cp ${'$'}X $cur; chmod 644 $cur
            |echo ${'$'}X > $path; chmod 644 $path
            |echo COPIED
            |""".trimMargin()

    /** The overlay, the two binds and the audio restart: OK, ALREADY, or one line "MOUNTFAIL <lib|cfg> <-|layer|deleted>
     *  <the kernel's first error line>" — build 18 (widener review): which bind, and what sits on its target (nothing of
     *  anyone's, another layer, or a deleted file's: [outside]). The strategy is build 17's (build 19 changes it). */
    internal fun mountScript(xmlPath: String, lib: String, mine: String): String = """
            |W=/dev/wayfinder_fx
            |X=$xmlPath
            |grep -q libwfwide ${'$'}X && { echo ALREADY; exit 0; }
            |where_is() { M=${'$'}(grep -h " ${'$'}1 " /proc/1/mountinfo /proc/self/mountinfo 2>/dev/null); case "${'$'}M" in "") echo -;; *deleted*) echo deleted;; *) echo layer;; esac; }
            |mkdir -p ${'$'}W/soundfx
            |cp -a /vendor/lib64/soundfx/. ${'$'}W/soundfx/
            |cp $lib ${'$'}W/soundfx/libwfwide.so
            |cp $mine ${'$'}W/audio_effects.xml
            |chmod 755 ${'$'}W/soundfx; chmod 644 ${'$'}W/soundfx/* ${'$'}W/audio_effects.xml
            |chcon -R u:object_r:vendor_file:s0 ${'$'}W/soundfx 2>/dev/null
            |chcon u:object_r:vendor_configs_file:s0 ${'$'}W/audio_effects.xml 2>/dev/null
            |mount --bind ${'$'}W/soundfx /vendor/lib64/soundfx 2>${'$'}W/err || { echo "MOUNTFAIL lib ${'$'}(where_is /vendor/lib64/soundfx) ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }
            |mount --bind ${'$'}W/audio_effects.xml ${'$'}X 2>${'$'}W/err || { umount /vendor/lib64/soundfx 2>/dev/null; echo "MOUNTFAIL cfg ${'$'}(where_is ${'$'}X) ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }
            |kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |echo OK
            |""".trimMargin()

    /** Build 18 (widener review): the audio HAL + audioserver restarted (init brings them back) — our entries are in the
     *  config but weren't loaded. */
    internal val RESTART_SCRIPT = """
            |kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |echo OK
            |""".trimMargin()

    /** Pure (JVM test): the mount was refused on a layer that isn't ours — another bind, or a deleted file's, sits on the
     *  target ([mountScript]'s third word). Only a restart of the Thor clears that. */
    internal fun outside(r: String?): Boolean =
        r != null && r.startsWith("MOUNTFAIL ") && r.split(' ').getOrNull(2).let { it == "layer" || it == "deleted" }

    /** Pure (JVM test): a script's answer for the log — one line, never a package name (they live in paths under /data). */
    internal fun forLog(r: String?): String =
        r?.lineSequence()?.firstOrNull()?.replace(Regex("""/data\S*"""), "/data/…")?.take(240)?.ifBlank { "(empty)" } ?: "no answer"
}

/** Pure (JVM test): [check] until it's true, for up to [timeoutMs] from the call — [firstMs] before the first look (a server
 *  just told to stop may still answer for a moment), then every [stepMs]. Never past the deadline by more than one look. */
internal fun pollUntil(timeoutMs: Long, stepMs: Long, now: () -> Long, sleep: (Long) -> Unit, firstMs: Long = 0,
                       check: () -> Boolean): Boolean {
    val end = now() + timeoutMs
    if (firstMs > 0) sleep(firstMs)
    while (true) {
        if (check()) return true
        if (now() >= end) return false
        sleep(minOf(stepMs, (end - now()).coerceAtLeast(1)))
    }
}
