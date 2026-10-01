package app.wayfinder

import android.util.Log
import java.io.BufferedInputStream

/**
 * Stick lights "Screen colour" — runs INSIDE the root input helper (app_process, uid 0).
 *
 * The average colour of a screen, ~12 times a second: raw `screencap -d <physical id>`
 * (no PNG: ~70 ms for 1920×1080), a sparse grid of pixels averaged while streaming
 * through. The accessibility screenshot the lights used before is limited to one per
 * ~333 ms and came back a frame late — the rings lagged the game by up to ~0.8 s.
 * Sends `C <r> <g> <b>` lines back over the helper's channel.
 */
object AmbientSampler {
    private const val TAG = "ThorAmbient"
    private const val STEP = 12            // every 12th pixel of every 12th row
    @Volatile private var phys: String? = null
    private var thread: Thread? = null
    var send: ((String) -> Unit)? = null

    /** `A <physical display id>` starts, `A -` stops. */
    fun watch(p: String?) {
        phys = p?.takeIf { it.isNotBlank() && it != "-" }
        if (phys != null && thread?.isAlive != true) thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    private fun loop() {
        // 1.4 (measured: 12 screenshots a second = +0.38 W at rest): fast only while the picture changes — slower
        // while the colour holds (2 a second), 12 a second for 1.5 s after a visible change. Most of the cost is per wake-up
        // (4 a second still measured +0.28 W) — a downscaled capture would be the real fix (1.5).
        var last: IntArray? = null
        var fastUntil = 0L
        while (true) {
            val id = phys ?: run { thread = null; return }
            val t0 = System.nanoTime()
            // C <avg r g b> <left half r g b> <right half r g b> (1.3: each stick its side — GitHub #15)
            runCatching { sample(id) }.onSuccess { col ->
                if (col != null) {
                    val prev = last
                    if (prev == null || (0 until 9).sumOf { kotlin.math.abs(col[it] - prev[it]) } > 30)
                        fastUntil = System.nanoTime() + 1_500_000_000L
                    last = col
                    send?.invoke("C " + col.joinToString(" ") + "\n")
                }
            }.onFailure { Log.w(TAG, "sample: $it") }
            val spent = (System.nanoTime() - t0) / 1_000_000
            val period = if (System.nanoTime() < fastUntil) 80L else 500L
            Thread.sleep((period - spent).coerceIn(5, period))
        }
    }

    private fun sample(id: String): IntArray? {
        val p = ProcessBuilder("screencap", "-d", id).start()
        try {
            val input = BufferedInputStream(p.inputStream, 1 shl 16)
            val head = ByteArray(16)
            if (!readFully(input, head)) return null
            fun le(o: Int) = (head[o].toInt() and 0xFF) or ((head[o + 1].toInt() and 0xFF) shl 8) or
                ((head[o + 2].toInt() and 0xFF) shl 16) or ((head[o + 3].toInt() and 0xFF) shl 24)
            val w = le(0); val h = le(4)
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
            val row = ByteArray(w * 4)
            val sum = LongArray(6); val cnt = LongArray(2)      // [left r g b, right r g b]
            val half = w / 2
            for (y in 0 until h) {
                if (!readFully(input, row)) break
                if (y % STEP != 0) continue
                var x = 0
                while (x < w) {
                    val o = x * 4; val s = if (x < half) 0 else 1
                    sum[s * 3] += row[o].toLong() and 0xFF; sum[s * 3 + 1] += row[o + 1].toLong() and 0xFF; sum[s * 3 + 2] += row[o + 2].toLong() and 0xFF
                    cnt[s]++; x += STEP
                }
            }
            val n = cnt[0] + cnt[1]
            if (n == 0L || cnt[0] == 0L || cnt[1] == 0L) return null
            return intArrayOf(((sum[0] + sum[3]) / n).toInt(), ((sum[1] + sum[4]) / n).toInt(), ((sum[2] + sum[5]) / n).toInt(),
                (sum[0] / cnt[0]).toInt(), (sum[1] / cnt[0]).toInt(), (sum[2] / cnt[0]).toInt(),
                (sum[3] / cnt[1]).toInt(), (sum[4] / cnt[1]).toInt(), (sum[5] / cnt[1]).toInt())
        } finally {
            p.destroy()
        }
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val k = input.read(buf, off, buf.size - off)
            if (k < 0) return false
            off += k
        }
        return true
    }
}
