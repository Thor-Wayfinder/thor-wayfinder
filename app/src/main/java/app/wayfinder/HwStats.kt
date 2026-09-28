package app.wayfinder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/** One reading of the Thor's hardware, for the quick panel. Null = unreadable. */
data class HwSnapshot(
    val cpuLoad: Int?, val cpuGhz: Float?, val cpuTemp: Float?,
    val gpuLoad: Int?, val gpuMhz: Int?, val gpuTemp: Float?,
    val ramUsedGb: Float?, val ramTotalGb: Float?,
    val battery: Int?, val batteryTemp: Float?, val watts: Float?, val charging: Boolean,
)

/**
 * Reads the hardware straight from the kernel — all of it readable by the app itself
 * (no root): /proc/stat (CPU load), cpufreq, kgsl (GPU), thermal zones cpu-* / gpuss-*,
 * /proc/meminfo, the battery broadcast + power_supply for watts.
 */
class HwStats(private val ctx: Context) {
    private var lastIdle = 0L
    private var lastTotal = 0L
    // 1.3 (GitHub #21): the cluster sensors (cpuss-*). The per-core hot spots (cpu-x-y) read several °C above
    // them at idle and far more under load — their max showed "> 90 °C" while other tools showed the CPU cooler
    private val cpuZones by lazy { zones { it.startsWith("cpuss-") }.ifEmpty { zones { it.startsWith("cpu-") } } }
    private val gpuZones by lazy { zones { it.startsWith("gpuss-") } }

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    private fun zones(match: (String) -> Boolean): List<String> =
        (File("/sys/class/thermal").listFiles() ?: emptyArray())
            .filter { it.name.startsWith("thermal_zone") && read("$it/type")?.let(match) == true }
            .map { "$it/temp" }

    private fun maxTemp(paths: List<String>): Float? =
        paths.mapNotNull { read(it)?.toLongOrNull() }.filter { it in 1..150_000 }.maxOrNull()?.let { it / 1000f }

    fun sample(): HwSnapshot {
        // CPU load since the previous sample (first call: since boot).
        val cpu = read("/proc/stat")?.lineSequence()?.firstOrNull()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
        var load: Int? = null
        if (cpu != null && cpu.size >= 5) {
            val idle = cpu[3] + cpu[4]
            val total = cpu.sum()
            val dt = total - lastTotal
            if (lastTotal > 0 && dt > 0) load = (100 * (dt - (idle - lastIdle)) / dt).toInt().coerceIn(0, 100)
            lastIdle = idle; lastTotal = total
        }
        val ghz = (File("/sys/devices/system/cpu/cpufreq").listFiles() ?: emptyArray())
            .mapNotNull { read("$it/scaling_cur_freq")?.toLongOrNull() }.maxOrNull()?.let { it / 1_000_000f }
        val mem = read("/proc/meminfo")?.lines()?.associate { l ->
            l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }
        val total = mem?.get("MemTotal")?.let { it / 1_048_576f }
        val avail = mem?.get("MemAvailable")?.let { it / 1_048_576f }
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val uA = read("/sys/class/power_supply/battery/current_now")?.toLongOrNull()
        val uV = read("/sys/class/power_supply/battery/voltage_now")?.toLongOrNull()
        val watts = if (uA != null && uV != null) kotlin.math.abs(uA * uV / 1e12f) else null
        return HwSnapshot(
            cpuLoad = load, cpuGhz = ghz, cpuTemp = maxTemp(cpuZones),
            gpuLoad = read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.substringBefore(' ')?.trim()?.toIntOrNull(),
            gpuMhz = read("/sys/class/kgsl/kgsl-3d0/gpuclk")?.toLongOrNull()?.let { (it / 1_000_000).toInt() },
            gpuTemp = maxTemp(gpuZones),
            ramUsedGb = if (total != null && avail != null) total - avail else null, ramTotalGb = total,
            battery = level, batteryTemp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10f },
            watts = watts, charging = charging,
        )
    }
}
