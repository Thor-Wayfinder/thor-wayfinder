package app.wayfinder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * 1.4 — AYN's two charging switches, and what the charger is doing now.
 *
 * Verified on a Thor (2026-10-01): "Stop at 80 %" = setting `percent_80_charge_limit`, which
 * AYN writes to `/sys/class/qcom-battery/limit_capacity_charge`; "Direct power" = `is_charging_separation`, written
 * to `usb_charge_now` (0 = the charger runs the Thor and the battery rests). The limit STOPS charging at 80 %; it
 * doesn't bring a fuller battery down — plugged in at 100 % it stays at 100 % (Android still says "charging").
 */
object Charging {
    private const val TAG = "ThorCharge"
    private const val LIMIT_KEY = "percent_80_charge_limit"
    private const val DIRECT_KEY = "is_charging_separation"
    private const val LIMIT_NODE = "/sys/class/qcom-battery/limit_capacity_charge"
    private const val USB_NODE = "/sys/class/qcom-battery/usb_charge_now"

    data class Now(val plugged: Boolean, val level: Int?, val limit: Boolean, val direct: Boolean) {
        /** A few words for "100 % · …" (null when unplugged). */
        val phrase: String? get() = when {
            !plugged -> null
            direct -> "on the charger"
            limit && (level ?: 0) >= 80 -> "80 % limit"     // = not charging (short: the Hub's strip cut it)
            (level ?: 0) >= 100 -> "full"
            else -> "charging"
        }
    }

    private fun sys(ctx: Context, k: String) =
        runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, k, 0) == 1 }.getOrDefault(false)
    fun limitOn(ctx: Context) = sys(ctx, LIMIT_KEY)
    fun directOn(ctx: Context) = sys(ctx, DIRECT_KEY)

    fun now(ctx: Context): Now {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); if (l >= 0) l * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) else null }
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Now(plugged, level, limitOn(ctx), directOn(ctx))
    }

    /** The 80 % card's line, for what's happening now. */
    fun limitLine(n: Now): String = when {
        !n.limit -> "Off — charges to 100 %"
        !n.plugged -> "On — charging stops at 80 %: the battery wears out more slowly"
        n.direct -> "On — the charger runs the Thor now (Direct power), the battery rests"
        (n.level ?: 0) > 80 -> "On — ${n.level} % now: it won't charge more, and stays there until you use it"
        (n.level ?: 0) >= 80 -> "On — not charging: held at 80 %"
        else -> "On — charging, it stops at 80 % (${n.level ?: "—"} % now)"
    }

    fun directLine(n: Now): String = when {
        !n.direct -> "Off — plugged in, the battery charges"
        n.plugged -> "On — the charger runs the Thor now; the battery rests at ${n.level ?: "—"} %"
        else -> "On — plugged in, the Thor runs from the charger and the battery rests: cooler, kinder to it in long sessions"
    }

    /** Turn one on / off: AYN's setting (its settings app applies it), then its node checked — and written here if
     *  AYN's app didn't (not running, or a firmware without that trigger). Background thread; [done] on any thread. */
    fun set(ctx: Context, limit: Boolean? = null, direct: Boolean? = null, done: () -> Unit = {}) = Thread {
        limit?.let { apply(LIMIT_KEY, it, LIMIT_NODE, if (it) "1" else "0") }
        direct?.let { apply(DIRECT_KEY, it, USB_NODE, if (it) "0" else "1") }
        done()
    }.apply { isDaemon = true }.start()

    private fun apply(key: String, on: Boolean, node: String, want: String) {
        PServiceBridge.exec("settings put system $key ${if (on) 1 else 0}")
        for (i in 0 until 8) {
            Thread.sleep(250)
            if (read(node) == want) return
        }
        Log.w(TAG, "$key: AYN didn't apply it ($node = ${read(node)}) — writing it")
        PServiceBridge.exec("echo $want > $node")
    }

    private fun read(p: String) = runCatching { java.io.File(p).readText().trim() }.getOrNull()
    private fun num(p: String) = read(p)?.toLongOrNull()

    /** What the Thor itself draws now, in watts: from the battery when unplugged; on the charger, what comes in minus
     *  what goes into the battery (or plus what the battery adds when the charger can't keep up). Sign-free: the
     *  battery's status says which way its current goes. */
    fun drawWatts(ctx: Context): Float? {
        val bat = run { val a = num("/sys/class/power_supply/battery/current_now"); val v = num("/sys/class/power_supply/battery/voltage_now")
            if (a == null || v == null) null else kotlin.math.abs(a * v / 1e12f) }
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) return bat
        val usb = run { val a = num("/sys/class/power_supply/usb/current_now"); val v = num("/sys/class/power_supply/usb/voltage_now")
            if (a == null || v == null || a <= 0) null else a * v / 1e12f } ?: return null
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return when {
            bat == null || bat < 0.05f -> usb
            status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> usb + bat
            else -> (usb - bat).coerceAtLeast(0f)
        }
    }

    /** The energy left in the battery, in watt-hours (its charge counter × its voltage). */
    fun energyWh(): Float? {
        val uah = num("/sys/class/power_supply/battery/charge_counter") ?: return null
        val uv = num("/sys/class/power_supply/battery/voltage_now") ?: return null
        return uah / 1e6f * uv / 1e6f
    }
}
