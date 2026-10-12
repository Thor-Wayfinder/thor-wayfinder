package app.wayfinder

import java.io.File

/**
 * 1.3 (GitHub #4) — which input nodes are the Thor's OWN controller. AYN's `rsinput` grabs every
 * external gamepad (Bluetooth, USB) and re-emits it under AYN's ids (2020:0111) keeping the external's
 * name, e.g. a Bluetooth "Xbox Wireless Controller" (045e) also appears as 2020:0111 "Xbox Wireless
 * Controller". That copy IS the external pad for games: never hide it, never take it for AYN's.
 * AYN's own pad = vendor 2020 and (product 0x0112 — AYN's Xbox mode — or 0x0111 under a name that no
 * non-AYN device has). Read from sysfs (the root helper and the app can both read it).
 */
object AynPad {
    private val CONTROLLER = Regex("(?i)controller|gamepad|xbox")
    private fun rd(f: File) = runCatching { f.readText().trim() }.getOrNull()

    /** Names of the input devices that aren't AYN's (their vendor isn't 2020). */
    fun foreignNames(): Set<String> =
        (File("/sys/class/input").listFiles { f -> f.name.startsWith("event") } ?: emptyArray()).mapNotNull { n ->
            val v = rd(File(n, "device/id/vendor")) ?: return@mapNotNull null
            if (v.equals("2020", true)) null else rd(File(n, "device/name"))
        }.toSet()

    /** sysfs's number for /sys/class/input/eventN ("input366" → 366): it only grows, so the highest is the newest. */
    fun inputNumber(node: File): Int = runCatching {
        File(node, "device").canonicalFile.name.removePrefix("input").toInt()
    }.getOrDefault(-1)

    /** [node] = /sys/class/input/eventN (or its /dev/input path). */
    fun isAyn(node: File, foreign: Set<String> = foreignNames()): Boolean {
        val n = if (node.path.startsWith("/dev/input/")) File("/sys/class/input", node.name) else node
        // a controller Wayfinder publishes for an external pad (ExtPads): never AYN's, whatever its ids
        if (rd(File(n, "device/phys"))?.startsWith(ExtPads.PHYS_PREFIX) == true) return false
        if (rd(File(n, "device/id/vendor"))?.equals("2020", true) != true) return false
        val name = rd(File(n, "device/name")) ?: return false
        if (!CONTROLLER.containsMatchIn(name) || name.contains("Mouse", true)) return false
        // dev build 15: AYN's copy of a pad of ours ("… (Wayfinder)", 2020:0111, no location) is never AYN's pad — taken
        // for it, the input layer could start its copy on it (the Thor's buttons dead). AYN's own pad never has that name
        if (name.endsWith(ExtRules.WF_SUFFIX)) return false
        val product = rd(File(n, "device/id/product"))?.lowercase()
        return product == "0112" || name !in foreign
    }

}
