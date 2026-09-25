package io.gutapk.device

import java.nio.file.Path

// Android's settings tables and the phone's own reports. Reading is free,
// a change to a setting is one command the user sees before it is sent.
object SystemInfo {
    val NAMESPACES = listOf("global", "secure", "system")
    private val KEY = Regex("""[A-Za-z0-9_.:\-]+""")

    // Read-only reports, each one command.
    val DIAGNOSTICS = listOf(
        "sy_d_memory" to "cat /proc/meminfo",
        "sy_d_meminfo" to "dumpsys meminfo",
        "sy_d_cpu" to "dumpsys cpuinfo",
        "sy_d_top" to "top -b -n 1 -m 20",
        "sy_d_thermal" to "dumpsys thermalservice",
        "sy_d_uptime" to "uptime",
        "sy_d_storage" to "dumpsys diskstats",
        "sy_d_battery" to "dumpsys batterystats --charged",
        "sy_d_display" to "dumpsys display",
        "sy_d_network" to "dumpsys connectivity",
    )

    fun validKey(k: String): Boolean = KEY.matches(k)

    fun listCommand(ns: String): String {
        require(ns in NAMESPACES) { "not a settings table: $ns" }
        return "settings list $ns"
    }

    fun putCommand(ns: String, key: String, value: String): String {
        require(ns in NAMESPACES && validKey(key)) { "not a setting: $ns $key" }
        return "settings put $ns $key " + Adb.quote(value)
    }

    fun deleteCommand(ns: String, key: String): String {
        require(ns in NAMESPACES && validKey(key)) { "not a setting: $ns $key" }
        return "settings delete $ns $key"
    }

    // "key=value" per line, the value may hold = itself.
    internal fun parseSettings(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trimEnd() }
            .filter { '=' in it }
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .filter { validKey(it.first) }
            .sortedBy { it.first.lowercase() }
            .toList()

    fun settings(adb: Path, serial: String, ns: String): List<Pair<String, String>> =
        parseSettings(Adb.shell(adb, serial, listCommand(ns), 60).out)

    fun props(adb: Path, serial: String): List<Pair<String, String>> =
        Adb.parseProps(Adb.shell(adb, serial, "getprop", 60).out).toList().sortedBy { it.first }
}
