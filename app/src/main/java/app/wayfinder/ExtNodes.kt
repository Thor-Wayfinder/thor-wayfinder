package app.wayfinder

import android.os.FileObserver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** dev build 15 (C2) — what Android lists besides our pads for a controller Wayfinder handles. AYN's copy of it and its
 *  own gamepad node (a second controller AYN didn't take) leave Android's list the way AYN removes the controller it
 *  holds: their /dev/input node is unlinked (EventHub drops a deleted node at once; hidden devices still took emulator
 *  ports). Its touchpad and motion sensors (PlayStation pads) are switched off in Android (the touchpad drove a mouse
 *  pointer). All of it comes back only on a real give-back. Runs in the root helper ([InputMonitorTool]), one thread.
 *  Also the C1 sign's sysfs side: AYN took a pad of ours (its node deleted while it lives, or AYN's copy of it). */
internal object ExtNodes {
    private const val TAG = "ThorExtNodes"
    private val EVENT = Regex("^event[0-9]{1,4}$")
    /** Android's fixed gid for "input" (android_filesystem_config.h AID_INPUT): /dev/input nodes are root:input. */
    private const val AID_INPUT = 1004

    /** A controller Wayfinder handles, as [ExtPads] sees it now: [hids] its HID devices (current, last, or arriving);
     *  [ours] its pad's eventN; [phys] / [shown] its pad's location and name (the C1 sign); [quiet]: Wayfinder makes its
     *  pad again right now (no sign then). [slot] "" = a controller whose reader is about to start (critique R10: its
     *  AYN copy goes at once, not after the Thor's side settled). */
    class Owner(val slot: String, val hids: Set<String>, val ours: String?, val phys: String, val shown: String, val quiet: Boolean)

    @Volatile var owners: () -> List<Owner> = { emptyList() }
    /** AYN took the pad of the controller in [slot] (C1). */
    @Volatile var onTook: ((slot: String, why: String) -> Unit)? = null
    /** Something appeared in /dev/input: a controller may have connected (the loop scans now, not in ≤ 2 s). */
    @Volatile var onAppeared: (() -> Unit)? = null

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThorExtNodes").apply { isDaemon = true } }
    private val recs = ConcurrentHashMap<String, ExtRules.Rec>()          // eventN → what was done to it
    private var observer: FileObserver? = null                             // kept: a collected observer stops
    private val walkQueued = AtomicBoolean(false)

    // ── start / stop ──

    /** "E on": watch /dev/input (a controller's devices appear, AYN deletes a node). The sweep backstop runs anyway. */
    fun start() = exec.execute {
        if (observer != null) return@execute
        observer = runCatching {
            object : FileObserver(File("/dev/input"), FileObserver.CREATE or FileObserver.DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !EVENT.matches(path)) return
                    if (event and FileObserver.DELETE != 0) exec.execute { runCatching { deleted(path) } }
                    if (event and FileObserver.CREATE != 0) runCatching { onAppeared?.invoke() }
                    claimAll()
                }
            }.apply { startWatching() }
        }.onFailure { Log.w(TAG, "no /dev/input watch (${it.javaClass.simpleName}) — the sweep's 2 s pass only") }.getOrNull()
    }

    fun stop() = exec.execute { runCatching { observer?.stopWatching() }; observer = null }

    /** Walk every input device once, against every controller Wayfinder handles (coalesced): after a reader's "K", an
     *  attach ("S 1"), every sweep pass (missed events, devices Android listed late) and any change in /dev/input. */
    fun claimAll() {
        if (!walkQueued.compareAndSet(false, true)) return
        exec.execute { walkQueued.set(false); runCatching { walk() }.onFailure { Log.w(TAG, "walk: $it") } }
    }

    /** Something is removed / switched off (a walk is needed even without a controller: to give it back). */
    fun hasRecords() = recs.isNotEmpty()

    /** A switched-off device ('t' / 'd') with that Android id: the input layer must not switch it back on. */
    fun isOurs(reader: Int): Boolean = reader >= 0 && recs.values.any { (it.kind == 't' || it.kind == 'd') && it.reader == reader }

    /** For the identity table: removed (c, r) and switched off (t, d — per Android device: critique R4). */
    fun counts(): Pair<Map<Char, Int>, Map<Char, Int>> {
        val v = recs.values.toList()
        val removed = listOf('c', 'r').associateWith { k -> v.count { it.kind == k } }
        val off = listOf('t', 'd').associateWith { k -> v.filter { it.kind == k }.map { it.reader }.toSet().size }
        return removed to off
    }

    // ── give back ──

    /** Everything removed / switched off for the controllers [hids] comes back (a real give-back: its reader ended and
     *  isn't made again, the 30-min drop, Forget). [wait]: ≤ [waitMs]. Returns the nodes that could not be put back.
     *  Review P4: one that finishes after the wait gives its failures to [late] (else they were lost: no pill). */
    fun giveBack(hids: Set<String>, wait: Boolean = true, waitMs: Long = 3000, late: ((List<String>) -> Unit)? = null): List<String> {
        if (hids.isEmpty() || recs.values.none { it.owner in hids }) return emptyList()
        val f = exec.submit(Callable { giveBackNow(recs.values.filter { it.owner in hids }, hids) })
        if (!wait) return emptyList()
        return try { f.get(waitMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.TimeoutException) {
            // one thread: queued after the give-back, it runs once that is done
            if (late != null) exec.execute { runCatching { f.get().takeIf { it.isNotEmpty() }?.let(late) } }
            emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /** "E off", the app gone (update, uninstall — it may never come back): everything back. */
    fun giveBackAll(wait: Boolean = true) {
        if (recs.isEmpty()) return
        val f = exec.submit(Callable { giveBackNow(recs.values.toList(), recs.values.map { it.owner }.toSet()) })
        if (wait) runCatching { f.get(3, TimeUnit.SECONDS) }
    }

    /** Helper start: what a helper that died left removed (this boot only: /dev is made anew at boot) comes back. */
    fun cleanupLeftovers() {
        val f = file() ?: return
        if (!f.isFile) return
        val lines = runCatching { f.readLines().take(65) }.getOrDefault(emptyList())
        val boot = bootId()
        val old = if (boot.isNotEmpty() && lines.firstOrNull() == "boot $boot") lines.drop(1).mapNotNull { ExtRules.parseRec(it) } else emptyList()
        if (old.isNotEmpty()) {
            Log.w(TAG, "a previous helper left ${old.size} device(s) removed — back")
            old.forEach { recs[it.event] = it }
            runCatching { exec.submit(Callable { giveBackNow(old, old.map { it.owner }.toSet()) }).get(5, TimeUnit.SECONDS) }
        }
        recs.clear()
        runCatching { f.delete() }
    }

    private fun giveBackNow(list: List<ExtRules.Rec>, hids: Set<String>): List<String> {
        val failed = ArrayList<String>()
        // critique R4: Android off first, then the nodes — a DualSense's gamepad, touchpad and motion devices may be ONE
        // Android device (one descriptor): a node made again while that device is still off would come back off
        for (r in list.filter { it.kind == 't' || it.kind == 'd' }) { runCatching { enableAgain(r) }; recs.remove(r.event) }
        for (r in list.filter { it.kind == 'c' || it.kind == 'r' }) {
            if (!runCatching { restoreNode(r) }.getOrDefault(false)) failed += r.event
            recs.remove(r.event)
        }
        // …and whatever of that controller Android still has off (merged with a switched-off device) on again
        runCatching { enableControllers(hids) }
        persist()
        if (failed.isNotEmpty()) noUnlink("a node could not be put back")
        return failed
    }

    private fun enableAgain(r: ExtRules.Rec) {
        if (!sameDevice(r)) return
        val d = PadLayer.snapshot().firstOrNull { it.path == "/dev/input/${r.event}" } ?: return
        if (d.reader >= 0 && !d.enabled) PadLayer.setEnabled(d.reader, true)
    }

    /** mknod with its mode AT CREATION (a node made 0600 then chmod-ed was opened by EventHub before the chmod and
     *  ignored), root:input, its SELinux label — then, once Android opened it, the original 0660
     *  (a world-readable controller node lets any app read the controller). A missed one heals itself: /dev is made
     *  anew at boot, AYN makes its copy again at every connect. */
    private fun restoreNode(r: ExtRules.Rec): Boolean {
        if (!sameDevice(r)) return true                                     // gone, or its number reused: nothing to do
        val path = "/dev/input/${r.event}"
        if (runCatching { Os.lstat(path) }.isSuccess) return true            // there already
        if (!run("mknod", "-m", "666", path, "c", r.maj.toString(), r.min.toString())) { Log.w(TAG, "restore ${r.event} failed: mknod"); return false }
        // made, then gone at once (ENOENT): AYN opened it and took it into its slot (it deletes the node it holds) — the
        // controller is back the way it was before Wayfinder, not a failure (seen on the Thor: handling off → "could not
        // be put back" and unlinking stopped for the boot)
        val gone = { e: Throwable -> (e as? ErrnoException)?.errno == OsConstants.ENOENT }
        val own = runCatching { Os.chown(path, 0, AID_INPUT) }
        own.exceptionOrNull()?.let { if (gone(it)) return true; Log.w(TAG, "restore ${r.event}: chown ${(it as? ErrnoException)?.errno}") }
        run("restorecon", path)
        val until = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < until && PadLayer.snapshot().none { it.path == path }) Thread.sleep(100)
        val mode = runCatching { Os.chmod(path, "660".toInt(8)) }
        return mode.isSuccess || mode.exceptionOrNull().let { e -> if (e != null && gone(e)) true else { Log.w(TAG, "restore ${r.event}: chmod ${(e as? ErrnoException)?.errno}"); false } }
    }

    /** Every gamepad of these controllers Android has off — not ours, not the Thor's while docked — on again. */
    private fun enableControllers(hids: Set<String>) {
        if (hids.isEmpty()) return
        for (d in PadLayer.snapshot()) {
            if (!d.gamepad || d.enabled || d.reader < 0 || d.location.startsWith("wayfinder-")) continue
            if (isOurs(d.reader) || d.reader in ExtPads.dockHidden) continue
            val ev = d.path.removePrefix("/dev/input/").takeIf { EVENT.matches(it) } ?: continue
            if (hidOf(ev) in hids) PadLayer.setEnabled(d.reader, true)
        }
    }

    // ── the walk ──

    /** One input device as read from sysfs (+ what a record needs). */
    private class Sys(val n: ExtRules.NodeDev, val dev: String, val input: String)

    private val orphanSince = HashMap<String, Long>()
    private val signSince = HashMap<String, Long>()
    private val firedAt = HashMap<String, Long>()
    private val copiesAt = HashMap<String, ArrayDeque<Long>>()

    private fun walk() {
        val os = runCatching { owners() }.getOrDefault(emptyList())
        if (os.isEmpty() && recs.isEmpty()) return                         // no controller of ours, nothing to give back
        val owned = os.flatMap { it.hids }.filter { it.isNotEmpty() }.toSet()
        val devs = readAll()
        val now = System.currentTimeMillis()
        // records of devices gone (the controller went, its number reused) are dropped — nothing to give back
        val byEvent = devs.associateBy { it.n.event }
        var changed = false
        for (r in recs.values.toList()) {
            val s = byEvent[r.event]
            if (s == null || s.dev != "${r.maj}:${r.min}" || s.input != r.input) { recs.remove(r.event); changed = true }
        }
        if (changed) persist()
        // a controller no reader owns any more (refused, a stop that wasn't followed by a new reader): back after 20 s
        val orphans = recs.values.map { it.owner }.filter { it !in owned }.toSet()
        orphanSince.keys.retainAll(orphans)
        for (h in orphans) {
            val since = orphanSince.getOrPut(h) { now }
            if (now - since > 20_000) { orphanSince.remove(h); giveBackNow(recs.values.filter { it.owner == h }, setOf(h)) }
        }
        // C1: AYN took a pad of ours — its node deleted while it lives (≥ 300 ms), or AYN's copy of it (newer than it).
        // Review H7: the copy sign by name only when one pad carries that name (quiet ones too: one being made again may be
        // the one AYN holds) — two identical controllers' pads share it, and AYN's copy of one fired for both (the other
        // made again mid-game for nothing); for a shared name the node sign names the pad AYN really took
        val byName = os.filter { it.shown.isNotEmpty() }.groupingBy { it.shown }.eachCount()
        for (o in os) {
            if (o.quiet || o.ours == null || o.slot.isEmpty()) { signSince.remove(o.slot); continue }
            if (nodeDeleted(o)) {
                val since = signSince.getOrPut(o.slot) { now }
                if (now - since >= 300) took(o, "node deleted")
            } else signSince.remove(o.slot)
            val mine = inputNumber(o.ours)
            if (o.shown.isNotEmpty() && byName[o.shown] == 1 && mine >= 0 && devs.any { it.n.vendor == "2020" && it.n.product == "0111" && it.n.phys.isEmpty() &&
                    it.n.name == o.shown && (it.input.removePrefix("input").toIntOrNull() ?: -1) > mine }) took(o, "its copy appeared")
        }
        // switched off by us, switched on by someone else: off again
        if (recs.values.any { it.kind == 't' || it.kind == 'd' }) {
            val snap = PadLayer.snapshot()
            for (r in recs.values.filter { it.kind == 't' || it.kind == 'd' }) {
                val d = snap.firstOrNull { it.path == "/dev/input/${r.event}" } ?: continue
                if (d.enabled && d.reader >= 0) { PadLayer.setEnabled(d.reader, false); if (d.reader != r.reader) recs[r.event] = copyWith(r, d.reader) }
            }
        }
        if (owned.isEmpty()) return
        val unlink = unlinkAllowed()
        val all = devs.map { it.n }
        var snap: List<PadLayer.Dev>? = null
        for (s in devs) {
            val kinds = recs.mapValues { it.value.kind }
            val c = ExtRules.classify(s.n, all, owned, kinds, unlink) ?: continue
            c.dropRaw?.let { recs.remove(it); Log.w(TAG, "$it: AYN took that controller after its node was removed") }
            when (c.kind) {
                'c', 'r' -> unlinkNode(s, c)
                else -> { val sn = snap ?: PadLayer.snapshot().also { snap = it }; switchOff(s, c.kind, c.owner, sn) }
            }
        }
    }

    private fun copyWith(r: ExtRules.Rec, reader: Int) = ExtRules.Rec(r.event, r.maj, r.min, r.input, r.kind, r.owner, reader)

    private fun unlinkNode(s: Sys, c: ExtRules.Claim) {
        val (maj, min) = majMin(s.dev) ?: return
        val path = "/dev/input/${s.n.event}"
        val st = try { Os.lstat(path) } catch (e: ErrnoException) { return }    // gone already (AYN deleted it first)
        if (!OsConstants.S_ISCHR(st.st_mode) || ExtRules.devMajor(st.st_rdev) != maj || ExtRules.devMinor(st.st_rdev) != min) return
        try { Os.remove(path) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return
            Log.w(TAG, "${s.n.event}: can't remove its node (${OsConstants.errnoName(e.errno)}) — off in Android instead")
            switchOff(s, 'd', c.owner, PadLayer.snapshot()); return
        }
        recs[s.n.event] = ExtRules.Rec(s.n.event, maj, min, s.input, c.kind, c.owner, -1); persist()
        Log.w(TAG, "${s.n.event} removed (${if (c.kind == 'c') "AYN's copy" else "its own node"}) — a pad of ours replaces it")
        if (c.kind == 'c') {
            // AYN making its copy again and again (C0b says it doesn't): stop removing for this boot (critique R11)
            val q = copiesAt.getOrPut(c.owner) { ArrayDeque() }
            val now = System.currentTimeMillis(); q.addLast(now); while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 3) noUnlink("AYN made its copy again ${q.size} times in a minute")
        }
    }

    private fun switchOff(s: Sys, kind: Char, owner: String, snap: List<PadLayer.Dev>) {
        val (maj, min) = majMin(s.dev) ?: return
        val d = snap.firstOrNull { it.path == "/dev/input/${s.n.event}" } ?: return    // not listed yet: the next pass
        if (d.reader < 0) return
        if (d.enabled) PadLayer.setEnabled(d.reader, false)
        recs[s.n.event] = ExtRules.Rec(s.n.event, maj, min, s.input, kind, owner, d.reader); persist()
        Log.w(TAG, "${s.n.event} off in Android (#${d.reader}, $kind)")
    }

    /** AYN deleted a node: if it is a pad of ours that still lives, look again in 300 ms (our own re-make deletes the
     *  sysfs device first, so it never looks like this; a node made again at once is there by then). */
    private fun deleted(ev: String) {
        val o = runCatching { owners() }.getOrDefault(emptyList()).firstOrNull { it.ours == ev && !it.quiet && it.slot.isNotEmpty() } ?: return
        if (!nodeDeleted(o)) return
        exec.schedule(Runnable {
            val again = runCatching { owners() }.getOrDefault(emptyList()).firstOrNull { it.slot == o.slot && it.ours == ev && !it.quiet }
            if (again != null && nodeDeleted(again)) took(again, "node deleted")
        }, 300, TimeUnit.MILLISECONDS)
    }

    /** S1 ∧ S2: our pad lives (sysfs keeps its location) and its node is gone. Wayfinder never unlinks a "wayfinder-*"
     *  node, so only AYN does this. */
    private fun nodeDeleted(o: Owner): Boolean {
        val ev = o.ours ?: return false
        if (rd(File("/sys/class/input/$ev/device/phys")) != o.phys) return false
        return try { Os.lstat("/dev/input/$ev"); false } catch (e: ErrnoException) { e.errno == OsConstants.ENOENT }
    }

    private fun took(o: Owner, why: String) {
        val now = System.currentTimeMillis()
        if (now - (firedAt[o.slot] ?: 0L) < 5000) return
        firedAt[o.slot] = now; signSince.remove(o.slot)
        Log.w(TAG, "${o.slot}: AYN took its pad ($why)")
        runCatching { onTook?.invoke(o.slot, why) }
    }

    // ── sysfs ──

    private fun rd(f: File) = runCatching { f.readText().trim() }.getOrNull()
    private fun majMin(s: String): Pair<Int, Int>? {
        val p = s.split(':'); val a = p.getOrNull(0)?.toIntOrNull() ?: return null; val b = p.getOrNull(1)?.toIntOrNull() ?: return null
        return if (p.size == 2) a to b else null
    }
    private fun hidOf(ev: String) = runCatching { ExtRules.hidOfInputPath(File("/sys/class/input/$ev/device").canonicalPath) }.getOrDefault("")
    private fun inputNumber(ev: String) = runCatching { File("/sys/class/input/$ev/device").canonicalFile.name.removePrefix("input").toInt() }.getOrDefault(-1)

    private fun readAll(): List<Sys> {
        val foreign by lazy { AynPad.foreignNames() }
        val out = ArrayList<Sys>()
        for (f in File("/sys/class/input").listFiles().orEmpty()) {
            if (!EVENT.matches(f.name)) continue
            val dir = File(f, "device")
            val name = rd(File(dir, "name")) ?: continue
            val canon = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            val vendor = rd(File(dir, "id/vendor"))?.lowercase() ?: ""
            val hasNode = runCatching { Os.lstat("/dev/input/${f.name}") }.isSuccess
            val n = ExtRules.NodeDev(f.name, name, rd(File(dir, "phys")) ?: "", vendor, rd(File(dir, "id/product"))?.lowercase() ?: "",
                ExtRules.hidOfInputPath(canon), ExtPads.isPad(dir), ExtRules.propBits(rd(File(dir, "properties"))), hasNode,
                vendor == "2020" && AynPad.isAyn(f, foreign))
            out += Sys(n, rd(File(f, "dev")) ?: "", File(canon).name)
        }
        return out
    }

    /** The record still names that very device: same sysfs input device, same MAJ:MIN. */
    private fun sameDevice(r: ExtRules.Rec): Boolean {
        val f = File("/sys/class/input/${r.event}")
        return rd(File(f, "dev")) == "${r.maj}:${r.min}" && runCatching { File(f, "device").canonicalFile.name }.getOrNull() == r.input
    }

    // ── unlinking allowed (critique R11) ──

    @Volatile private var offThisBoot: Boolean? = null
    /** `setprop debug.wayfinder.unlink 0` (if C0b fails on a Thor: today's Android-off everywhere), or stopped for this
     *  boot by a failed restore / AYN re-making its copy. */
    private fun unlinkAllowed(): Boolean {
        if (prop("debug.wayfinder.unlink") == "0") return false
        val off = offThisBoot ?: (runCatching { File(file()?.parentFile, "ext_nodes_off").readText().trim() }.getOrNull()
            .let { it != null && it == bootId() }).also { offThisBoot = it }
        return !off
    }
    private fun noUnlink(why: String) {
        if (offThisBoot == true) return
        offThisBoot = true
        Log.w(TAG, "$why — devices are switched off in Android instead of removed, until the next boot")
        runCatching { File(file()?.parentFile ?: return, "ext_nodes_off").writeText(bootId()) }
    }

    // ── the records file (critique R16: the app's own folder, by its id) ──

    private fun file(): File? {
        val uid = PadLayer.appUid
        if (uid < 0) return null
        val user = uid / 100_000
        val internal = File("/data/user/$user/${BuildConfig.APPLICATION_ID}/files")
        val dir = if (internal.isDirectory) internal else runCatching {
            File("/mnt/expand").listFiles().orEmpty().map { File(it, "user/$user/${BuildConfig.APPLICATION_ID}/files") }.firstOrNull { it.isDirectory }
        }.getOrNull() ?: internal
        return File(dir, "ext_nodes")
    }
    private fun bootId() = rd(File("/proc/sys/kernel/random/boot_id")) ?: ""
    private fun persist() {
        val f = file() ?: return
        runCatching {
            val v = recs.values.sortedBy { it.event }.take(64)
            if (v.isEmpty()) { if (f.exists()) f.delete(); return@runCatching }
            f.writeText("boot ${bootId()}\n" + v.joinToString("") { ExtRules.recLine(it) + "\n" })
        }.onFailure { Log.w(TAG, "records: ${it.javaClass.simpleName}") }
    }

    private fun run(vararg cmd: String): Boolean = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching false }
        p.exitValue() == 0
    }.getOrDefault(false)

    private fun prop(k: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("")
}
