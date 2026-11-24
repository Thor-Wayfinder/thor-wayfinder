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
 * ([failedOutside]): a restart of the Thor clears it.
 * Build 19 (widener mount), so another tool's dead layer can neither empty our copy nor block our bind:
 *   - the stock libraries come from the vendor partition itself ([BASE_SCRIPT]: a non-recursive bind of /vendor inside a
 *     throwaway mount namespace, gone with the call), not from the live folder a dead empty tmpfs may hide; the effects
 *     other tools added are copied too, and stock wins;
 *   - the config's whole folder is copied and bound ([cfgDir]), not the file: a folder always takes a mount, and it
 *     hides a dead file-level layer under it (a bind over a deleted file is refused until a reboot);
 *   - every install makes a fresh copy (`/dev/wayfinder_fx/g<N>`), never written to again once a mount was made from it;
 *   - our own audio restarts first check that every stock library is still there (stock_ok), else a fresh copy first;
 *   - a layer already on a target (another tool's, or our earlier copy) becomes a slave before our bind, so ours doesn't
 *     spread into that layer's peers (checked on the Thor 2026-10-24). A clean Thor has none.
 */
object AudioFx {
    private const val TAG = "ThorAudioFx"
    @Volatile var installed = false
        private set
    /** Build 18 (widener review): the last attempt's mount was refused on a layer that isn't Wayfinder's (another tool's
     *  bind, or one of a deleted file) — nothing the app can undo; a restart of the Thor clears it. b19 fix: or this boot's
     *  copies are all used ([restartClears]): the same, only a restart clears them. */
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
        val base = File(dir, "fx_base.sh"); base.writeText(BASE_SCRIPT)
        val prep = File(dir, "fx_prep.sh")
        prep.writeText(prepScript(lib.absolutePath, cur.absolutePath, path.absolutePath, base.absolutePath))
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

        // 2. Our entries: the library and the effect (once: a config that has them already comes back from prep when a
        // stock library went missing).
        xml = addOurs(xml)
        // (No post-process <apply>: SpeakerTune attaches it to the output mix itself — as a
        // post-process, AYN's AudioFlinger moved it to session 0 and then refused it.)
        val mine = File(dir, "fx_wf.xml"); mine.writeText(xml); mine.setReadable(true, false)

        // 3. Overlay, mount, restart audio. (Long → a script file: pservice drops long commands.)
        val mount = File(dir, "fx_mount.sh")
        mount.writeText(mountScript(xmlPath, cfgDir(xmlPath), lib.absolutePath, mine.absolutePath, base.absolutePath))
        val r2 = PServiceBridge.exec("sh ${mount.absolutePath}")?.trim()
        return when (r2) {
            "OK" -> { Log.i(TAG, "mount: OK"); back("mount: OK") }
            "ALREADY" -> { Log.i(TAG, "mount: ALREADY"); inPlace(ctx) }
            else -> { failedOutside = restartClears(r2); Log.w(TAG, "mount: ${forLog(r2)}"); false }
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

    /** Build 19 (widener mount): the folder bound in place of the config file, when the config lies below
     *  /vendor/etc/<x>/ (the Thor: /vendor/etc/audio/sku_kalama). Never /vendor/etc itself (vintf, init and permissions
     *  files are read from there at runtime) and never a path with anything but plain names in it: null, and the file
     *  itself is bound (build 18's way). */
    internal fun cfgDir(xmlPath: String): String? = xmlPath.substringBeforeLast('/', "").takeIf { CFG_DIR.matches(it) }
    private val CFG_DIR = Regex("""/vendor/etc(/[A-Za-z0-9_][A-Za-z0-9._-]*)+""")

    /** Build 19 (widener mount): our library and effect entries, added once (prep hands back a config that has them
     *  already when a stock library went missing under it). */
    internal fun addOurs(xml: String): String =
        if ("libwfwide" in xml) xml
        else xml.replaceFirst("<libraries>", "<libraries>\n        <library name=\"wfwide\" path=\"libwfwide.so\"/>")
            .replaceFirst("<effects>", "<effects>\n        <effect name=\"wfwide\" library=\"wfwide\" uuid=\"$UUID\"/>")

    /** Build 19 (widener mount): at most this many fresh copies per boot (a few MB of RAM each: soundfx + the config's
     *  folder, about 2.7 MB on the Thor). Our own installs make one each; the 8th refuses. */
    internal const val MAX_COPIES = 8

    /** Build 19 (widener mount): the stock soundfx, read from the vendor partition itself. Run ONLY as
     *  `unshare -m sh <this> <mount dir> <copy-to | ->`: a throwaway mount namespace, so its bind of /vendor dies with the
     *  call and there is nothing to undo. Its first lines make sure nothing done here reaches the Thor's mounts: the
     *  namespace isn't init's, then every mount in it becomes a slave (toybox's `unshare -m` keeps them PEERS of the Thor's:
     *  a mount or an umount here would go out). `--bind` is the keyword that makes toybox's bind non-recursive (no overlay
     *  comes along), and a recursive one is refused anyway. With "-": 0 when every stock library is in the live soundfx, 1
     *  when one is missing. Else the stock libraries are copied into <copy-to>: 0. Any other answer (2-7): can't tell. */
    internal val BASE_SCRIPT = """
            |cd /
            |[ -n "${'$'}(readlink /proc/1/ns/mnt)" ] && [ "${'$'}(readlink /proc/self/ns/mnt)" != "${'$'}(readlink /proc/1/ns/mnt)" ] || exit 2
            |mount -o rslave none / || exit 3
            |mount --bind /vendor ${'$'}1 || exit 4
            |grep -q " ${'$'}1/" /proc/self/mountinfo && exit 5
            |S=${'$'}1/lib64/soundfx
            |[ -n "${'$'}(ls ${'$'}S)" ] || exit 6
            |if [ "${'$'}2" = - ]; then for f in ${'$'}S/*; do [ -e /vendor/lib64/soundfx/${'$'}{f##*/} ] || exit 1; done; exit 0; fi
            |cp -a ${'$'}S/. ${'$'}2/ || exit 7
            |""".trimMargin()

    /** Build 19 (widener mount): in prep and mount, after W and B. False only when [BASE_SCRIPT] answers 1 (a stock library
     *  is missing from the live soundfx: another tool's uninstall deleted through a mount); "can't tell" (no unshare, any
     *  other refusal) counts as fine. Never restart audio by hand with stock libraries missing. */
    private const val STOCK_OK = "stock_ok() { mkdir -p \$W/probe; unshare -m sh \$B \$W/probe - 2>/dev/null; [ \$? -ne 1 ]; }"

    /** Which config the HAL reads; ALREADY (ours, same library), UPDATED (ours, a newer library: replaced + an audio
     *  restart), COPIED (a copy of it at [cur], its path at [path]), NOXML. One line: pservice returns only the first.
     *  Build 19 (widener mount): "ours" also needs every stock library in place (stock_ok, [base] = [BASE_SCRIPT]'s file);
     *  one missing → COPIED, and the mount script builds a fresh copy before the restart. */
    internal fun prepScript(lib: String, cur: String, path: String, base: String): String = """
            |W=/dev/wayfinder_fx
            |B=$base
            |$STOCK_OK
            |X=/vendor/etc/audio/sku_$(getprop ro.board.platform)/audio_effects.xml
            |[ -f ${'$'}X ] || X=/vendor/etc/audio_effects.xml
            |[ -f ${'$'}X ] || { echo NOXML; exit 0; }
            |L=/vendor/lib64/soundfx/libwfwide.so
            |if grep -q libwfwide ${'$'}X && stock_ok; then
            |  cmp -s $lib ${'$'}L && { echo ALREADY; exit 0; }
            |  rm -f ${'$'}L; cp $lib ${'$'}L; chmod 644 ${'$'}L; chcon u:object_r:vendor_file:s0 ${'$'}L 2>/dev/null
            |  kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |  echo UPDATED; exit 0
            |fi
            |cp ${'$'}X $cur; chmod 644 $cur
            |echo ${'$'}X > $path; chmod 644 $path
            |echo COPIED
            |""".trimMargin()

    /** The fresh copy, the two binds and the audio restart: OK, ALREADY, or one line "MOUNTFAIL <what> <where> <the first
     *  error line>". Build 18 (widener review): lib / cfg say which bind, and what sits on its target (nothing of anyone's
     *  "-", another "layer", or a "deleted" file's: [outside]). Build 19 (widener mount): base / copy / stage, with "-"
     *  (the stock copy refused, a copy incomplete, [MAX_COPIES] this boot): base and copy a plain Try again, stage one that
     *  only a restart of the Thor clears ([restartClears]).
     *  - The copy `g<N>` is new each time: the stock libraries from [BASE_SCRIPT] ([base]), then the effects other tools
     *    added (stock wins), then ours ([lib]).
     *  - [dir] ([cfgDir]) is copied whole, as it reads now (a dead bind in it is readable), with [mine] for the config, and
     *    the folder is bound; null binds [mine] on the file itself.
     *  - `.bound` marks the copy before its first mount: from then on it is never deleted (the undo trap); a copy without
     *    it never had a mount made from it, so the next install clears it. b19 fix: a bind that fails takes the mark off
     *    again when no mount from the copy is left (the lib bind's own failure; the cfg one's once the lib bind is undone),
     *    so failed tries no longer fill the [MAX_COPIES] of the boot.
     *  - harden: a layer already on the target (another tool's, or our earlier copy) becomes a slave first, so our bind
     *    doesn't spread into its peers (the data folder of the tool whose tmpfs it is: its uninstall would delete through
     *    our copy). On a clean Thor there is no layer and it does nothing.
     *  - The audio restarts only after every copy is complete and both binds are in. */
    internal fun mountScript(xmlPath: String, dir: String?, lib: String, mine: String, base: String): String {
        val copyFail = """{ echo "MOUNTFAIL copy - ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }"""
        val cfg = if (dir != null) """
                |cp -a ${'$'}D/. ${'$'}G/etc/ 2>${'$'}W/err || $copyFail
                |cp $mine ${'$'}G/etc/${'$'}N 2>${'$'}W/err || $copyFail
                |[ "${'$'}(ls -A ${'$'}D | wc -l)" = "${'$'}(ls -A ${'$'}G/etc | wc -l)" ] && cmp -s $mine ${'$'}G/etc/${'$'}N || { echo "MOUNTFAIL copy - incomplete"; exit 0; }
                |chmod 644 ${'$'}G/etc/${'$'}N; chmod ${'$'}(stat -c %a ${'$'}D) ${'$'}G/etc; chown ${'$'}(stat -c %u:%g ${'$'}D) ${'$'}G/etc
                |chcon -R u:object_r:vendor_configs_file:s0 ${'$'}G/etc 2>/dev/null
                """.trimMargin() else """
                |cp $mine ${'$'}G/cfg.xml 2>${'$'}W/err || $copyFail
                |cmp -s $mine ${'$'}G/cfg.xml || { echo "MOUNTFAIL copy - incomplete"; exit 0; }
                |chmod 644 ${'$'}G/cfg.xml; chcon u:object_r:vendor_configs_file:s0 ${'$'}G/cfg.xml 2>/dev/null
                """.trimMargin()
        val on = if (dir != null) "\$D" else "\$X"
        val from = if (dir != null) "\$G/etc" else "\$G/cfg.xml"
        return """
            |cd /
            |W=/dev/wayfinder_fx
            |X=$xmlPath
            |${if (dir != null) "D=$dir; N=\${X##*/}" else "# the config's file alone (no folder to bind)"}
            |B=$base
            |$STOCK_OK
            |grep -q libwfwide ${'$'}X && stock_ok && { echo ALREADY; exit 0; }
            |where_is() { M=${'$'}(grep -h " ${'$'}1 " /proc/1/mountinfo /proc/self/mountinfo 2>/dev/null); case "${'$'}M" in "") echo -;; *deleted*) echo deleted;; *) echo layer;; esac; }
            |harden() { [ "${'$'}(where_is ${'$'}1)" = - ] || mount -o slave none ${'$'}1 2>/dev/null; }
            |for g in ${'$'}W/g*; do [ -d ${'$'}g ] && [ ! -e ${'$'}g/.bound ] && rm -rf ${'$'}g; done
            |n=1; while [ -e ${'$'}W/g${'$'}n ]; do n=${'$'}((n+1)); done
            |[ ${'$'}n -le $MAX_COPIES ] || { echo "MOUNTFAIL stage - $MAX_COPIES copies this boot"; exit 0; }
            |G=${'$'}W/g${'$'}n; mkdir -p ${'$'}G/base ${'$'}G/soundfx${if (dir != null) " \$G/etc" else ""}
            |unshare -m sh ${'$'}B ${'$'}G/base ${'$'}G/soundfx 2>${'$'}W/err; r=${'$'}?
            |[ ${'$'}r = 0 ] || { echo "MOUNTFAIL base - ${'$'}r ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }
            |for f in /vendor/lib64/soundfx/*; do [ -e "${'$'}f" ] && [ ! -e "${'$'}G/soundfx/${'$'}{f##*/}" ] && cp -a "${'$'}f" ${'$'}G/soundfx/ 2>/dev/null; done
            |cp $lib ${'$'}G/soundfx/libwfwide.so 2>${'$'}W/err && cmp -s $lib ${'$'}G/soundfx/libwfwide.so || $copyFail
            |chmod 755 ${'$'}G/soundfx; chmod 644 ${'$'}G/soundfx/*
            |chcon -R u:object_r:vendor_file:s0 ${'$'}G/soundfx 2>/dev/null
            |$cfg
            |touch ${'$'}G/.bound
            |harden /vendor/lib64/soundfx
            |mount --bind ${'$'}G/soundfx /vendor/lib64/soundfx 2>${'$'}W/err || { rm -f ${'$'}G/.bound; echo "MOUNTFAIL lib ${'$'}(where_is /vendor/lib64/soundfx) ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }
            |harden $on
            |mount --bind $from $on 2>${'$'}W/err || { umount /vendor/lib64/soundfx 2>/dev/null && rm -f ${'$'}G/.bound; echo "MOUNTFAIL cfg ${'$'}(where_is $on) ${'$'}(head -n 1 ${'$'}W/err)"; exit 0; }
            |kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |echo OK
            |""".trimMargin()
    }

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

    /** Pure (JVM test), b19 fix: only a restart of the Thor clears this failure, so the notice says so ([failedOutside]):
     *  a layer that isn't ours ([outside]), or every copy of this boot used ("MOUNTFAIL stage": /dev is cleared only by a
     *  restart, and a copy a mount was made from is never deleted). */
    internal fun restartClears(r: String?): Boolean = outside(r) || r?.startsWith("MOUNTFAIL stage ") == true

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
