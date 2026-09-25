package io.gutapk.device

import java.nio.file.Path

// Codes as dumpsys battery prints them, BatteryManager's constants.
enum class BatteryStatus { UNKNOWN, CHARGING, DISCHARGING, NOT_CHARGING, FULL }

class Battery(val level: Int?, val status: BatteryStatus, val celsius: Double?, val health: Int?, val plugged: String?)

class DiskSpace(val totalKb: Long, val usedKb: Long, val freeKb: Long)

class User(val id: Int, val name: String, val running: Boolean)

class DeviceInfo(val props: Map<String, String>, val battery: Battery?, val storage: DiskSpace?, val users: List<User>)

// What the device page shows, read with four fixed shell commands. None
// takes user input, so nothing needs quoting here.
object DeviceReader {
    fun read(adb: Path, serial: String): DeviceInfo {
        val props = Adb.parseProps(Adb.shell(adb, serial, "getprop").out)
        return DeviceInfo(
            props = props,
            battery = parseBattery(Adb.shell(adb, serial, "dumpsys battery").out),
            storage = parseDf(Adb.shell(adb, serial, "df -k /data").out),
            users = parseUsers(Adb.shell(adb, serial, "pm list users").out),
        )
    }

    // "  level: 85", "  status: 2", "  temperature: 285" in tenths of a
    // degree, "  AC powered: true".
    internal fun parseBattery(text: String): Battery? {
        val fields = text.lineSequence()
            .map { it.trim() }
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val level = fields["level"]?.toIntOrNull() ?: return null
        val status = when (fields["status"]?.toIntOrNull()) {
            2 -> BatteryStatus.CHARGING
            3 -> BatteryStatus.DISCHARGING
            4 -> BatteryStatus.NOT_CHARGING
            5 -> BatteryStatus.FULL
            else -> BatteryStatus.UNKNOWN
        }
        val plugged = listOf("AC", "USB", "Wireless", "Dock").firstOrNull { fields["$it powered"] == "true" }
        return Battery(
            level = level,
            status = status,
            celsius = fields["temperature"]?.toIntOrNull()?.let { it / 10.0 },
            health = fields["health"]?.toIntOrNull(),
            plugged = plugged,
        )
    }

    // toybox df -k: a header, then one line per filesystem. A long device
    // name can push the numbers onto the next line, so they are read as the
    // three values before the Use% column, wherever the line broke.
    internal fun parseDf(text: String): DiskSpace? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Filesystem") }
        val numbers = lines.joinToString(" ").split(Regex("""\s+"""))
        val at = numbers.indexOfFirst { it.endsWith("%") }
        if (at < 3) return null
        val total = numbers[at - 3].toLongOrNull() ?: return null
        val used = numbers[at - 2].toLongOrNull() ?: return null
        val free = numbers[at - 1].toLongOrNull() ?: return null
        return DiskSpace(total, used, free)
    }

    // "\tUserInfo{0:Owner:c13} running", one per user.
    internal fun parseUsers(text: String): List<User> =
        Regex("""UserInfo\{(\d+):([^:}]*):[0-9a-fA-F]+}(\s+running)?""").findAll(text).map {
            User(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3].isNotEmpty())
        }.toList()

    // reboot with no argument restarts into Android.
    fun rebootArgs(serial: String, target: String?): List<String> =
        listOf("-s", serial, "reboot") + listOfNotNull(target)
}
