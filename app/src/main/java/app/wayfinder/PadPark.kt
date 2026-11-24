package app.wayfinder

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.5-beta2 (GitHub #102) — the rules, no Android types (JVM tests: ParkRulesTest). While the input layer's copy runs, wfpad
 * sets AYN's pad aside (fx/wfpark.h): its /dev/input node is hard-linked into [DIR] as "eventN.inputM", then removed from
 * /dev/input, so Android stops listing it (GameNative and apps like it took the silent pad as player 1 at a hot-plug) while
 * wfpad keeps reading it. Putting it back = one link() of the same inode. The same rules in C (wfpark.h) and here.
 */
internal object ParkRules {
    /** root's own folder on /dev's tmpfs (link() can't cross filesystems), outside /dev/input (EventHub, wfpad and ExtNodes
     *  watch that), not "input…" / "v4l-touch…" (EventHub watches /dev for those). Never ExtNodes' [ExtRules.STAGE_DIR]. */
    const val DIR = "/dev/wayfinder-park"
    private val NAME = Regex("^(event[0-9]{1,4})\\.input([0-9]{1,7})$")
    private val EVENT = Regex("^event[0-9]{1,4}$")

    class Parked(val event: String, val input: Int)
    /** A name in [DIR] → its eventN and sysfs input number; null = not one of ours (left alone). */
    fun parse(name: String): Parked? = NAME.find(name)?.let { Parked(it.groupValues[1], it.groupValues[2].toInt()) }
    /** The name wfpad gives a node set aside ("event11.input43"); null = not an eventN (never a path). */
    fun name(event: String, input: Int): String? = if (EVENT.matches(event) && input in 0..9_999_999) "$event.input$input" else null

    enum class Act { LINK, DROP }
    /** What a name in [DIR] becomes. LINK: its node goes back into /dev/input — the device still lives (sysfs [sysInput] is
     *  its input number, sysfs [sysDev] its MAJ:MIN = [nodeDev], the set-aside node's; null for a file that isn't a device
     *  node) and nothing has its name there. DROP: only the name goes (the device went, its eventN is another device's, or
     *  the node is back already). */
    fun act(p: Parked, nodeDev: String?, sysDev: String?, sysInput: String?, nodeThere: Boolean): Act = when {
        sysDev == null || sysInput != "input${p.input}" -> Act.DROP
        nodeDev != null && nodeDev != sysDev -> Act.DROP
        nodeThere -> Act.DROP
        else -> Act.LINK
    }

    /** wfpad may set AYN's pad aside: not switched off (`setprop debug.wayfinder.park 0` → [prop] "0"), removing nodes allowed
     *  ([unlink]: ExtNodes' `debug.wayfinder.unlink 0` / stopped for this boot), not given up ([off]: a refusal that stops it
     *  for this helper, a pause ([paused]), or a node that could not be put back). */
    fun allowed(prop: String, unlink: Boolean, off: Boolean): Boolean = prop != "0" && unlink && !off

    /** 1.5-beta2 (GitHub #102, repair): what one refusal means for the next wfpad (it was "no more parking in this helper"
     *  for every one: a single moment's race, or a few AYN style flips, turned the fix off for days). */
    enum class Refusal {
        /** A moment's race (AYN re-made its pad between the helper's look and wfpad's grab): set it aside again next start. */
        RETRY,
        /** AYN made its pad again and again ("I park off", wfpad's cap): not until the layer's next start, or [PAUSE_MS]. */
        PAUSE,
        /** The folder or the link refused (something on this Thor won't allow it): never again in this helper. */
        STOP,
    }
    private val TRANSIENT = setOf("node changed", "not in sysfs", "no input number", "aside changed", "node there")
    /** "I park no <why>" ([kind] "no") / "I park off <why>" ([kind] "off") → what it means. */
    fun refusal(kind: String, why: String): Refusal = when {
        kind == "off" -> Refusal.PAUSE
        why.trim() in TRANSIENT -> Refusal.RETRY
        else -> Refusal.STOP
    }
    /** A pause lasts this long at most (or until the layer's next start). */
    const val PAUSE_MS = 10 * 60_000L
    /** Two RETRY refusals in a row at a start: a pause (no start loop). */
    const val RETRIES = 2
    fun paused(since: Long, now: Long): Boolean = since > 0L && now >= since && now - since < PAUSE_MS

    /** 1.5-beta2 (GitHub #102, repair): AYN's pad [event] (sysfs input number [input]) left aside by a planned restart ("q k"):
     *  its name among the folder's [names], the next wfpad's source (wfpad takes it where it is: wfpark.h wfp_adopt), so AYN's
     *  pad is never listed between the two runs. null = not there (then the loop puts every node back first). */
    fun asideName(event: String, input: Int, names: Collection<String>): String? = name(event, input)?.takeIf { it in names }

    /** wfpad's command line. [park]: no takeover race ("0": AYN's pad goes out of the list, so #1 is free), "park" last.
     *  Else as before: the race (4000 ms) unless in compatibility mode. */
    fun wfpadArgs(exe: String, source: String, phys: String, compat: Boolean, named: Boolean, uhid: Boolean, park: Boolean): List<String> =
        listOf(exe, source, "grab", phys, if (park || compat) "0" else "4000", if (named) "named" else "-", if (uhid) "uhid" else "-") +
            (if (park) listOf("park") else emptyList())

    /** After wfpad stopped: put the nodes back now, unless it was told to leave them aside ("q k": a planned restart, the
     *  next wfpad starts in a moment — [keep]) and the layer goes on undocked ([wanted], ![docked]). 1.5-beta2 (GitHub
     *  #102, repair): that next wfpad is started on the node left aside ([asideName]) and takes it where it is. */
    fun restoreAfterExit(keep: Boolean, wanted: Boolean, docked: Boolean): Boolean = !(keep && wanted && !docked)

    /** aynFirst's re-make (Eden's port 0) only matters while AYN's pad is listed: never while it is set aside. */
    fun orderFixNeeded(parked: Boolean): Boolean = !parked
}

/** 1.5-beta2 (GitHub #102) — the root helper's side ([InputMonitorTool], uid 0): every node set aside comes back. At helper
 *  start (a wfpad killed with the helper), after every wfpad exit, before every wfpad start (repair: except one started on
 *  the node a planned restart left aside, [ParkRules.asideName]), when the layer stops, at uninstall. wfpad puts its own node back on every exit it lives through (fx/wfpad.c cleanup): this is the second line. */
internal object PadPark {
    private const val TAG = "ThorPadPark"
    private const val AID_INPUT = 1004
    /** A node could not be put back: no more parking in this helper (the takeover again, as before beta2). */
    @Volatile var failed = false
        private set

    /** Every node in [ParkRules.DIR] back if its device lives (the same inode: owner, mode, label as ueventd made them); the
     *  names go, the folder too when empty. Returns how many came back. A folder that isn't root's own is never touched. */
    @Synchronized fun restoreAll(): Int {
        val dir = ParkRules.DIR
        val st = try { Os.lstat(dir) } catch (e: ErrnoException) { return 0 }               // nothing set aside (the usual case)
        if (!ExtRules.stageDirOk(OsConstants.S_ISDIR(st.st_mode), st.st_uid, st.st_mode)) { Log.w(TAG, "$dir isn't root's own folder: left alone"); return 0 }
        var back = 0
        for (name in File(dir).list().orEmpty()) {
            val p = ParkRules.parse(name) ?: continue
            val aside = "$dir/$name"
            val e = runCatching { Os.lstat(aside) }.getOrNull() ?: continue
            val nodeDev = if (OsConstants.S_ISCHR(e.st_mode)) "${ExtRules.devMajor(e.st_rdev)}:${ExtRules.devMinor(e.st_rdev)}" else null
            val sys = File("/sys/class/input/${p.event}")
            val sysDev = runCatching { File(sys, "dev").readText().trim() }.getOrNull()
            val sysInput = runCatching { File(sys, "device").canonicalFile.name }.getOrNull()
            val path = "/dev/input/${p.event}"
            val there = runCatching { Os.lstat(path) }.isSuccess
            when (ParkRules.act(p, nodeDev, sysDev, sysInput, there)) {
                ParkRules.Act.DROP -> runCatching { Os.remove(aside) }
                ParkRules.Act.LINK -> if (putBack(aside, path, e)) { back++; runCatching { Os.remove(aside) }; Log.w(TAG, "${p.event}: AYN's pad put back") }
                    else { failed = true; Log.w(TAG, "${p.event}: AYN's pad could not be put back — no more setting it aside in this helper") }
            }
        }
        runCatching { Os.remove(dir) }                                                          // only if empty (rmdir)
        return back
    }

    /** link() (EEXIST: there already). Refused: the direct way, as ExtNodes gives a node back — mknod with the Thor's mode
     *  at creation, then root:input and its label. */
    private fun putBack(aside: String, path: String, st: android.system.StructStat): Boolean {
        try { Os.link(aside, path); return true } catch (e: ErrnoException) { if (e.errno == OsConstants.EEXIST) return true
            Log.w(TAG, "put back ${path.substringAfterLast('/')}: link ${OsConstants.errnoName(e.errno)}") }
        if (!OsConstants.S_ISCHR(st.st_mode)) return false
        if (!run("mknod", "-m", ExtRules.NODE_MODE, path, "c", ExtRules.devMajor(st.st_rdev).toString(), ExtRules.devMinor(st.st_rdev).toString()))
            return runCatching { Os.lstat(path) }.isSuccess
        runCatching { Os.chown(path, 0, AID_INPUT) }
        run("restorecon", path)
        return true
    }

    private fun run(vararg cmd: String): Boolean = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching false }
        p.exitValue() == 0
    }.getOrDefault(false)
}
