package app.wayfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * Linked volume. The Thor has two volumes: the normal media volume (what the volume
 * keys move) and AYN's "bottom screen" volume — `Settings.System
 * secondary_screen_volume_level` 0..15, which AYN's settings app turns into the gain
 * `persist.sys.audio.value` that AYN's AudioFlinger applies to apps on the bottom screen
 * (`updateSecondDisplayAppUids`). The keys only ever moved the first one.
 *
 * Linked: every media-volume change moves the bottom one by the same steps, keeping the
 * user's balance ([offset] = bottom − top, re-learned whenever they move AYN's bottom
 * slider, or ours). At 0 / 15 the bottom one stops, and the balance comes back after.
 */
object LinkedVolume {
    private const val TAG = "ThorVolume"
    private const val KEY = "secondary_screen_volume_level"
    /** AYN's own table: level 0..15 → gain (×1e-6). */
    private val GAINS = intArrayOf(0, 2786, 7647, 14595, 31623, 61684, 101197, 162208, 223701, 319522, 428549, 528750, 648635, 741433, 855067, 1000000)
    private const val MAX = 15

    var enabled by mutableStateOf(true)
        private set
    /** Bottom − top, in volume steps. */
    var offset by mutableStateOf(0)
        private set

    private lateinit var ctx: Context
    private val prefs by lazy { ctx.getSharedPreferences("thor_volume", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ThorVolume").apply { isDaemon = true } }
    @Volatile private var pending = -1          // latest level to write (coalesced)
    @Volatile private var lastWritten = -1      // our own write, so the observer can tell it apart
    private var started = false

    private val audio get() = ctx.getSystemService(AudioManager::class.java)
    private fun music() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun bottom(): Int = Settings.System.getInt(ctx.contentResolver, KEY, 7)

    /** Bumped on every volume change (buttons, sliders, AYN's drawer) — UI sliders follow it live. */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    /** Our own top-only change in flight: don't let the link move the bottom with it. */
    @Volatile private var suppressUntil = 0L

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            changes.intValue++
            if (!enabled || i == null) return
            if (android.os.SystemClock.uptimeMillis() < suppressUntil) return
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val now = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (now < 0 || now == prev) return
            write((now + offset).coerceIn(0, MAX))
        }
    }

    /** AYN's bottom slider moved (not by us) → that's the user's new balance. */
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            changes.intValue++
            val b = bottom()
            if (b == lastWritten || b == pending) return
            learn(b - music())
            Log.d(TAG, "bottom set to $b by the user → balance $offset")
        }
    }

    /** Settings only (the UI can read and change them before the service runs). */
    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        enabled = prefs.getBoolean("enabled", true)
        // 1.3 (GitHub #7): a fresh start = the same level on both screens. Taking AYN's difference over kept
        // its untouched default bottom level — the bottom screen's apps ~half as loud, for good.
        offset = if (prefs.contains("offset")) prefs.getInt("offset", 0) else 0
    }

    fun start(context: Context) {
        if (started) return
        init(context)
        ctx.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        ctx.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY), false, observer)
        started = true
        Log.d(TAG, "linked=$enabled balance=$offset (top ${music()}, bottom ${bottom()})")
        if (enabled && !prefs.contains("offset")) { learn(0); write(music()) }   // the fresh start, applied
    }

    fun stop() {
        if (!started) return
        runCatching { ctx.unregisterReceiver(volumeReceiver) }
        runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
        started = false
    }

    fun setLinked(on: Boolean) {
        enabled = on
        if (::ctx.isInitialized) prefs.edit().putBoolean("enabled", on).apply()   // saved even with the service off
        if (on && started) write((music() + offset).coerceIn(0, MAX))
    }

    /** Top screen's level 0..15 (media volume). */
    fun top(): Int = music()

    /** "Both" slider: the top goes to [level], the bottom follows keeping the offset. */
    fun setBoth(level: Int) {
        val t = level.coerceIn(0, MAX)
        suppressUntil = android.os.SystemClock.uptimeMillis() + 600
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, t, 0)
        write((t + offset).coerceIn(0, MAX))
    }

    /** "Top" slider: only the top moves; the new difference is remembered. */
    fun setTopOnly(level: Int) {
        val t = level.coerceIn(0, MAX)
        suppressUntil = android.os.SystemClock.uptimeMillis() + 600
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, t, 0)
        learn(bottom() - t)
    }

    /** "Bottom" slider: only the bottom moves; the new difference is remembered. */
    fun setBottomOnly(level: Int) {
        val b = level.coerceIn(0, MAX)
        learn(b - music())
        write(b)
    }

    /** Our own balance control: applies right away. */
    fun setBalance(steps: Int) {
        learn(steps.coerceIn(-MAX, MAX))
        if (started) write((music() + offset).coerceIn(0, MAX))
    }

    private fun learn(o: Int) {
        offset = o
        if (::ctx.isInitialized) prefs.edit().putInt("offset", o).apply()
    }

    /** Write AYN's setting + its gain through the root bridge, then nudge the media volume
     *  so the mixer re-reads it (exactly what AYN's settings app does). */
    private fun write(level: Int) {
        pending = level
        worker.execute {
            val l = pending
            if (l < 0 || l == lastWritten && bottom() == l) return@execute
            lastWritten = l
            PServiceBridge.exec("settings put system $KEY $l; setprop persist.sys.audio.value ${GAINS[l]}")
            if (pending == l) pending = -1
            main.post { runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, music(), 0) } }
            Log.d(TAG, "bottom → $l")
        }
    }
}
