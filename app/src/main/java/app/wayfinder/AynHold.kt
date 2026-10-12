package app.wayfinder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** AYN's controller-hold blacklist (com.odin.mapping).
 *  dev build 15 (C1, D6): the names of Wayfinder's pads go on it while "Wayfinder handles controllers" is on, so AYN's one
 *  external slot never takes a pad of ours (when its slot was free it held our pad: no controls). Our own binder client
 *  from the facts of AYN's service (no code of theirs). Only names ending " (Wayfinder)": entries outlive an uninstall.
 *  Every binder call runs on one thread of its own — never the main thread, never the helper's socket thread. */
internal object AynHold {
    private const val TAG = "ThorAynHold"
    private const val PKG = "com.odin.mapping"
    private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
    private const val TOKEN = "com.ro.mapping.sdk.IServerApi"
    private const val TX_HELD = 34          // () → String: the devices AYN holds now (JSON)
    private const val TX_LIST = 35          // () → List<String>: the list
    private const val TX_SET = 36           // (int add 1 / remove 0, String name)
    private const val PREFS = "thor_extpads"
    private const val NAMES = "hold_names"  // the names AYN confirmed (string set)
    private const val PROBE = "Wayfinder check (Wayfinder)"

    /** critique R2: not known yet (the self-test runs at app start; review P2: or AYN's service wasn't reached — tried
     *  again) · AYN's list works here · it can't be used (another AYN version, the service missing, the self-test failed,
     *  `setprop debug.wayfinder.aynhold 0`). */
    enum class State { UNKNOWN, OK, UNUSABLE }
    @Volatile var state = State.UNKNOWN
        private set
    /** Called (on this object's thread) once the state is known — the app then tells the helper ("E hold ok …" for each
     *  known name, or "E hold off"). Until then it tells nothing: the helper asks for each new name and waits ≤ 1.5 s. */
    @Volatile var onReady: ((State) -> Unit)? = null

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "ThorAynHold").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    @Volatile private var started = false

    /** Once, at app start: the self-test (≤ 3 s to bind), then [sync]. */
    @Synchronized fun init(ctx: Context) {
        if (started) return
        started = true
        appCtx = appCtx ?: ctx.applicationContext
        exec.execute { testNow() }
    }

    /** Review P2 / H11: when the self-test last ran, and how many times AYN's service couldn't be reached in a row. */
    @Volatile private var lastTry = 0L
    private var tries = 0
    private val RETRY_MS = longArrayOf(10_000, 30_000, 120_000, 600_000)
    private val retry = Runnable { exec.execute { if (state == State.UNKNOWN) testNow() } }

    /** On [exec]: the self-test (again). A definite answer sets the state, then [sync] and [onReady] (the helper hears it).
     *  Review P2 / H11: AYN's service out of reach (bound late at boot, restarting) is no answer — UNKNOWN (nothing sent:
     *  the helper keeps asking), tried again 10 s, 30 s, 2 min, then every 10 min (UNUSABLE for the whole app process,
     *  every AYN take went to the last resort for days). */
    private fun testNow() {
        lastTry = System.currentTimeMillis()
        val u = runCatching { usable() }.getOrNull()
        if (u == null) {
            state = State.UNKNOWN
            val d = RETRY_MS[minOf(tries, RETRY_MS.size - 1)]; tries++
            Log.w(TAG, "AYN's hold list: AYN's service not reached — tried again in ${d / 1000} s")
            main.removeCallbacks(retry); main.postDelayed(retry, d)
            return
        }
        main.removeCallbacks(retry); tries = 0
        state = if (u) State.OK else State.UNUSABLE
        Log.w(TAG, "AYN's hold list: ${state.name.lowercase()}")
        if (state == State.OK) runCatching { syncNow() }
        runCatching { onReady?.invoke(state) }
    }

    /** One of our pads' names (" (Wayfinder)" ending, ≤ 79 bytes, no controls) — nothing else ever goes on AYN's list. */
    fun valid(name: String) = ExtRules.validHoldName(name)

    /** [name] on AYN's list (listed first: AYN's add is a plain list add — no duplicates). [done] (this object's thread):
     *  it is listed now. */
    fun add(name: String, done: ((Boolean) -> Unit)? = null) = exec.execute {
        val ok = valid(name) && state == State.OK && runCatching { addNow(name, record = true) }.getOrDefault(false)
        runCatching { done?.invoke(ok) }
        // review P2: not known yet (AYN's service wasn't reached): a name asked tries the self-test again — after this
        // answer (the helper waits ≤ 2 s for it), at most every 30 s
        if (state == State.UNKNOWN && started && System.currentTimeMillis() - lastTry > 30_000) testNow()
    }

    /** [name] off AYN's list (repeated until it is gone). [done]: it is gone. */
    fun remove(name: String, done: ((Boolean) -> Unit)? = null) = exec.execute {
        val ok = valid(name) && state == State.OK && runCatching { removeNow(name) }.getOrNull() == true
        runCatching { done?.invoke(ok) }
    }

    /** App start: every name the app knows (confirmed ones, and every controller of the identity table — so a
     *  controller's first connection after an update rarely waits) put back when AYN's list lacks it (AYN's data cleared
     *  by a firmware update). critique R17: only while "Wayfinder handles controllers" is on. */
    fun sync(ctx: Context) {
        appCtx = appCtx ?: ctx.applicationContext
        exec.execute { if (state == State.OK) runCatching { syncNow() } }
    }

    /** "Wayfinder handles controllers" turned off (after "E off": our pads are gone): every name of ours off AYN's list.
     *  Never on Forget (the controller may come back; the entry is inert), never at uninstall (impossible, harmless). */
    fun removeAll(ctx: Context) {
        appCtx = appCtx ?: ctx.applicationContext
        exec.execute {
            if (state != State.OK) return@execute
            for (n in knownNames()) runCatching { removeNow(n) }
        }
    }

    /** The names AYN confirmed (prefs). */
    fun knownNames(): Set<String> = prefs()?.getStringSet(NAMES, null)?.toSet().orEmpty()

    // ── on [exec] only ──

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun record(name: String, on: Boolean) {
        val p = prefs() ?: return
        val s = p.getStringSet(NAMES, null)?.toMutableSet() ?: mutableSetOf()
        if ((name in s) == on) return
        if (on) s += name else s -= name
        p.edit().putStringSet(NAMES, s).apply()
    }

    private fun syncNow() {
        val ctx = appCtx ?: return
        if (!ExtPadsCtl.isOn(ctx)) return
        val listed = listNow() ?: return
        // the identity table (C4: prefs id_<ident> = "<pid> <key> <title>"): the names its controllers' pads carry
        val fromTable = prefs()?.all.orEmpty().filterKeys { it.startsWith("id_") }.values
            .mapNotNull { (it as? String)?.split(' ', limit = 3)?.getOrNull(2) }.map { ExtRules.padName(it) }
        for (n in (knownNames() + fromTable).filter { valid(it) }) {
            if (n in listed) { record(n, true); continue }
            addNow(n, record = true)
        }
    }

    private fun addNow(name: String, record: Boolean): Boolean {
        if (!valid(name)) return false
        val before = listNow() ?: return false
        if (name !in before) { setNow(true, name) ?: return false }
        val ok = listNow()?.contains(name) == true
        if (ok && record) record(name, true)
        if (BuildConfig.DEBUG) Log.d(TAG, "add $name -> $ok")
        return ok
    }

    /** null: AYN's service couldn't be reached (review H11: no "couldn't remove" then — the self-test cached that). */
    private fun removeNow(name: String): Boolean? {
        if (!valid(name)) return false
        for (i in 0 until 5) {
            val l = listNow() ?: return null
            if (name !in l) { record(name, false); return true }
            setNow(false, name) ?: return null
        }
        val gone = name !in (listNow() ?: return null)
        if (gone) record(name, false)
        return gone
    }

    /** AYN 3.0 (the version whose service was read) and our self-test passed — once per AYN version / update. null: AYN's
     *  service couldn't be reached now (not cached: [testNow] tries again). */
    private fun usable(): Boolean? {
        val ctx = appCtx ?: return false
        if (prop("debug.wayfinder.aynhold") == "0") return false
        val pi = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull() ?: return false
        // another version may number its transactions differently: 36 could be something else
        if (pi.versionName?.startsWith("3.0(") != true) return false
        val key = "aynhold_ok_${pi.longVersionCode}_${pi.lastUpdateTime}"
        val p = prefs() ?: return false
        if (p.getInt(key, -1) == 1) return true
        if (p.getInt(key, -1) == 0) return false
        val ok = selfTestNow() ?: return null                   // couldn't reach it now: tried again ([testNow])
        p.edit().apply { p.all.keys.filter { it.startsWith("aynhold_ok_") }.forEach { remove(it) } }.putInt(key, if (ok) 1 else 0).apply()
        return ok
    }

    /** list → add a probe name → listed → remove → not listed. null: AYN's service couldn't be reached. */
    private fun selfTestNow(): Boolean? {
        val l0 = listNow() ?: return null
        if (PROBE in l0) { removeNow(PROBE) }
        setNow(true, PROBE) ?: return null
        val added = listNow()?.contains(PROBE) ?: return null
        val gone = removeNow(PROBE) ?: return null              // review H11: out of reach mid-test is no "false"
        Log.w(TAG, "self-test: added $added, removed $gone")
        return added && gone
    }

    private fun listNow(): List<String>? = call(TX_LIST, {}) { r -> r.createStringArrayList()?.filterNotNull() }
    private fun heldNow(): String? = call(TX_HELD, {}) { r -> r.readString() ?: "" }
    private fun setNow(add: Boolean, name: String): Boolean? = call(TX_SET, { it.writeInt(if (add) 1 else 0); it.writeString(name) }) { true }

    // ── the binder ──

    @Volatile private var binder: IBinder? = null
    @Volatile private var latch: CountDownLatch? = null
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; latch?.countDown() }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onBindingDied(name: ComponentName?) { binder = null; exec.execute { unbindNow() } }
        override fun onNullBinding(name: ComponentName?) { latch?.countDown() }
    }
    private val unbinder = Runnable { exec.execute { unbindNow() } }

    private fun unbindNow() {
        if (!bound) return
        runCatching { appCtx?.unbindService(conn) }
        bound = false; binder = null
    }

    private fun binderNow(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val ctx = appCtx ?: return null
        val l = CountDownLatch(1); latch = l
        if (!bound) {
            bound = runCatching { ctx.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE) }
                .onFailure { Log.w(TAG, "bind: ${it.javaClass.simpleName}") }.getOrDefault(false)
            if (!bound) { runCatching { ctx.unbindService(conn) }; return null }
        }
        l.await(2500, TimeUnit.MILLISECONDS)
        return binder?.takeIf { it.isBinderAlive }
    }

    /** One AIDL call (the reply starts with the exception header). null: not bound, refused, or it threw. */
    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T? {
        main.removeCallbacks(unbinder)
        try {
            for (attempt in 0..1) {
                val b = binderNow() ?: return null
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(TOKEN); write(data)
                    if (!b.transact(code, data, reply, 0)) return null
                    reply.readException()
                    return read(reply)
                } catch (e: DeadObjectException) {
                    binder = null                                   // AYN's service restarted: once more
                } catch (e: Throwable) {
                    Log.w(TAG, "call $code: ${e.javaClass.simpleName}"); return null
                } finally { data.recycle(); reply.recycle() }
            }
            return null
        } finally { main.postDelayed(unbinder, 60_000) }   // unbound a minute after the last call
    }

    private fun prop(k: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("")

    /** Debug builds' adb test hook (the C0a device test): [op] add | remove | list | held | selftest,
     *  straight to AYN's service (no state gate, nothing recorded: test names stay out of the app's set). */
    fun test(ctx: Context, op: String, name: String, done: (String) -> Unit) {
        appCtx = appCtx ?: ctx.applicationContext
        exec.execute {
            val r = runCatching {
                when (op) {
                    "add" -> if (!valid(name)) "refused: not a (Wayfinder) name" else addNow(name, record = false).toString()
                    "remove" -> if (!valid(name)) "refused: not a (Wayfinder) name" else removeNow(name)?.toString() ?: "null (unreachable)"
                    "list" -> listNow()?.joinToString(" | ", "[", "]") ?: "null"
                    "held" -> heldNow() ?: "null"
                    "selftest" -> selfTestNow()?.toString() ?: "null (unreachable)"
                    else -> "unknown op"
                }
            }.getOrElse { "error ${it.javaClass.simpleName}" }
            done(r)
        }
    }
}
