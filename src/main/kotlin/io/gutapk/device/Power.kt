package io.gutapk.device

import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Path
import kotlin.math.abs

// The battery as the phone reports it, in the units the phone uses. Sources
// for the whole file: AOSP healthd (BatteryMonitor.cpp) for the sysfs side,
// AOSP BatteryStats.java and Battery Historian's checkin_parse.go for the
// checkin side, AOSP UidBatteryConsumer.java for the usage side.
// https://android.googlesource.com/platform/system/core/+/refs/heads/main/healthd/BatteryMonitor.cpp
// https://github.com/google/battery-historian/blob/master/checkinparse/checkin_parse.go
class BatteryReading(
    val status: BatteryStatus,
    val level: Int?,
    val microAmps: Long?,
    val microVolts: Long?,
    val celsius: Double?,
    val chargeCounterUah: Long?,
) {
    // The sign of current_now differs by vendor, the direction is the status.
    val milliAmps: Double? get() = microAmps?.let { abs(it) / 1e3 }
    val volts: Double? get() = microVolts?.let { it / 1e6 }
    val watts: Double? get() = if (microAmps != null && microVolts != null) abs(microAmps) * microVolts / 1e12 else null
}

// dumpsys battery: the reading plus whether any cable feeds the phone.
class DumpsysBattery(val reading: BatteryReading, val powered: Boolean)

// One uid's counters from a checkin, all cumulative since the last charge.
class UidUsage(
    val uid: Int,
    val mah: Double = 0.0,
    val system: Boolean = false,
    val cpuMs: Long = 0,
    val wakeMs: Long = 0,
    val wakeCount: Long = 0,
    val jobMs: Long = 0,
    val jobCount: Long = 0,
    val alarms: Long = 0,
    val netBytes: Long = 0,
) {
    // Counters only grow, so a smaller value means the uid started over:
    // it is clamped at zero, never negative.
    operator fun minus(base: UidUsage?): UidUsage {
        if (base == null) return this
        return UidUsage(
            uid = uid,
            mah = (mah - base.mah).coerceAtLeast(0.0),
            system = system,
            cpuMs = (cpuMs - base.cpuMs).coerceAtLeast(0),
            wakeMs = (wakeMs - base.wakeMs).coerceAtLeast(0),
            wakeCount = (wakeCount - base.wakeCount).coerceAtLeast(0),
            jobMs = (jobMs - base.jobMs).coerceAtLeast(0),
            jobCount = (jobCount - base.jobCount).coerceAtLeast(0),
            alarms = (alarms - base.alarms).coerceAtLeast(0),
            netBytes = (netBytes - base.netBytes).coerceAtLeast(0),
        )
    }

    val active: Boolean get() = mah > 0.0 || cpuMs > 0 || wakeMs > 0 || wakeCount > 0 || jobMs > 0 || jobCount > 0 || alarms > 0 || netBytes > 0
}

// dumpsys batterystats --checkin, the sections GutapK reads. packages maps
// an appId (uid modulo 100000) to the packages sharing it.
class Checkin(val capacityMah: Double?, val uids: Map<Int, UidUsage>, val packages: Map<Int, List<String>>) {
    val totalMah: Double get() = uids.values.sumOf { it.mah }
    val totalCpuMs: Long get() = uids.values.sumOf { it.cpuMs }
    fun packagesOf(uid: Int): List<String> = packages[uid % Power.PER_USER_RANGE].orEmpty()
}

// What one uid used between two checkins. rebased means the second one is
// below the first: Android reset its counters, so the second is the new start.
class Since(val rows: List<UidUsage>, val rebased: Boolean)

// One UID line of dumpsys batterystats --usage: its total, the share of each
// process state (fg, bg, fgs, cached) and of each component (cpu, screen...).
class UsageUid(val label: String, val mah: Double, val states: Map<String, Double>, val components: Map<String, Double>) {
    operator fun minus(base: UsageUid?): UsageUid {
        if (base == null) return this
        fun diff(now: Map<String, Double>, was: Map<String, Double>) =
            now.mapValues { (k, v) -> (v - (was[k] ?: 0.0)).coerceAtLeast(0.0) }
        return UsageUid(label, (mah - base.mah).coerceAtLeast(0.0), diff(states, base.states), diff(components, base.components))
    }
}

// The power test of one app: what it used in the hands of the user, what it
// used once left alone, and what it left behind.
class AppTest(
    val use: UidUsage,
    val idle: UidUsage,
    val services: Int?,
    val alive: Boolean,
    val idleTicks: Long?,
)

object Power {
    const val PER_USER_RANGE = 100000

    // AID_SHELL, the uid of every adb shell command.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcutils/include/private/android_filesystem_config.h
    const val SHELL_APP_ID = 2000

    // Process.java, the ranges formatUid tells apart.
    private const val FIRST_APPLICATION_UID = 10000
    private const val FIRST_ZYGOTE_ISOLATED_UID = 90000
    private const val LAST_ZYGOTE_ISOLATED_UID = 98999
    private const val FIRST_ISOLATED_UID = 99000
    private const val LAST_ISOLATED_UID = 99999

    // A clock tick of /proc/<pid>/stat is 10 ms on every Android kernel.
    const val TICK_MS = 10L

    // healthd keeps the first power supply whose type reads Battery and
    // whose scope is not Device (a stylus, a watch), then reads these files
    // of it. Ported as one shell loop that prints name=value lines.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/main/healthd/BatteryMonitor.cpp
    const val SYSFS = "for d in /sys/class/power_supply/*\n" +
        "do\n" +
        "[ \"\$(cat \$d/type 2>/dev/null)\" = Battery ] || continue\n" +
        "[ \"\$(cat \$d/scope 2>/dev/null)\" = Device ] && continue\n" +
        "for k in status capacity current_now voltage_now temp charge_counter\n" +
        "do\n" +
        "echo \"\$k=\$(cat \$d/\$k 2>/dev/null)\"\n" +
        "done\n" +
        "break\n" +
        "done"

    const val DUMPSYS_BATTERY = "dumpsys battery"
    const val UNPLUG = "dumpsys battery unplug"
    const val CHECKIN = "dumpsys batterystats --checkin"
    const val USAGE = "dumpsys batterystats --usage"

    fun unplugArgs(serial: String): List<String> = listOf("-s", serial, "shell", UNPLUG)

    fun resetArgs(serial: String): List<String> = listOf("-s", serial, "shell", Controls.BATTERY_RESET)

    // name=value or name: value lines into a map, the last one winning.
    private fun pairs(text: String, sep: Char): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { sep in it }
            .associate { it.substringBefore(sep).trim() to it.substringAfter(sep).trim() }

    // healthd's getBatteryStatus strings.
    private fun statusOf(text: String?): BatteryStatus = when (text?.lowercase()) {
        "charging" -> BatteryStatus.CHARGING
        "discharging" -> BatteryStatus.DISCHARGING
        "not charging" -> BatteryStatus.NOT_CHARGING
        "full" -> BatteryStatus.FULL
        else -> BatteryStatus.UNKNOWN
    }

    // The loop's output. capacity is a percent, current_now microamps,
    // voltage_now microvolts, temp tenths of a degree, charge_counter microamp
    // hours. A file the shell may not read comes out empty and stays null.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/main/healthd/BatteryMonitor.cpp
    internal fun parseSysfs(text: String): BatteryReading? {
        val f = pairs(text, '=').filterValues { it.isNotEmpty() }
        if (f.isEmpty()) return null
        val known = listOf("status", "capacity", "current_now", "voltage_now", "temp", "charge_counter")
        if (known.none { it in f }) return null
        return BatteryReading(
            status = statusOf(f["status"]),
            level = f["capacity"]?.toIntOrNull(),
            microAmps = f["current_now"]?.toLongOrNull(),
            microVolts = f["voltage_now"]?.toLongOrNull(),
            celsius = f["temp"]?.toIntOrNull()?.let { it / 10.0 },
            chargeCounterUah = f["charge_counter"]?.toLongOrNull(),
        )
    }

    // dumpsys battery, the part of AOSP BatteryService.dumpInternal GutapK
    // needs. Level, status and temperature come from DeviceReader, the
    // voltage is in millivolts and the charge counter in microamp hours.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/BatteryService.java
    internal fun parseDumpsysBattery(text: String): DumpsysBattery? {
        val b = DeviceReader.parseBattery(text) ?: return null
        val f = pairs(text, ':')
        return DumpsysBattery(
            BatteryReading(
                status = b.status,
                level = b.level,
                microAmps = null,
                microVolts = f["voltage"]?.toLongOrNull()?.times(1000),
                celsius = b.celsius,
                chargeCounterUah = f["Charge counter"]?.toLongOrNull(),
            ),
            b.plugged != null,
        )
    }

    // The charge counter (BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, in
    // microamp hours) between two readings, as a mean current. The sign is
    // the direction, so only the size is kept.
    // https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CHARGE_COUNTER
    fun averageMilliAmps(fromMs: Long, fromUah: Long, toMs: Long, toUah: Long): Double? {
        if (toMs <= fromMs) return null
        val hours = (toMs - fromMs) / 3_600_000.0
        return abs(toUah - fromUah) / 1000.0 / hours
    }

    // Battery Historian reads the checkin with Go's csv reader: a field may
    // be quoted, and a quoted field may hold commas.
    // https://github.com/google/battery-historian/blob/master/checkinparse/checkin_parse.go
    internal fun splitCheckin(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in line) {
            if (c == '"') {
                quoted = !quoted
            } else if (c == ',' && !quoted) {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    private class Acc(val uid: Int) {
        var mah = 0.0
        var system = false
        var cpuMs = 0L
        var wakeMs = 0L
        var wakeCount = 0L
        var jobMs = 0L
        var jobCount = 0L
        var alarms = 0L
        var netBytes = 0L

        fun done() = UidUsage(uid, mah, system, cpuMs, wakeMs, wakeCount, jobMs, jobCount, alarms, netBytes)
    }

    // Lines are "9,<uid>,<category>,<type>,...". Category l carries the
    // stats, category i the uid to package map. Column handling follows
    // Battery Historian: parseAppSystemPowerUseItem (pwi), parseAppWakelock
    // (wl), parseAppScheduledJob (jb), parseAppNetwork (nt),
    // parseAppWakeupAlarm (wua), and AOSP BatteryStats.java for pws, cpu, uid.
    // https://github.com/google/battery-historian/blob/master/checkinparse/checkin_parse.go
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryStats.java
    internal fun parseCheckin(text: String): Checkin {
        val accs = LinkedHashMap<Int, Acc>()
        val packages = LinkedHashMap<Int, MutableList<String>>()
        var capacity: Double? = null
        fun acc(uid: Int) = accs.getOrPut(uid) { Acc(uid) }
        for (line in text.lineSequence()) {
            val f = splitCheckin(line.trim())
            if (f.size < 5) continue
            val uid = f[1].toIntOrNull() ?: continue
            if (f[2] == "i" && f[3] == "uid") {
                val appId = f[4].toIntOrNull() ?: continue
                val list = packages.getOrPut(appId) { ArrayList() }
                if (f.size > 5 && f[5].isNotEmpty() && f[5] !in list) list.add(f[5])
                continue
            }
            if (f[2] != "l") continue
            when (f[3]) {
                // capacity, computed, discharged low, discharged high
                "pws" -> {
                    capacity = f[4].toDoubleOrNull()
                }
                // device rows carry a label, per app rows the label uid:
                // uid, mAh, isSystem, screen mAh, proportional mAh
                "pwi" -> {
                    if (f[4] == "uid" && f.size > 6) {
                        val a = acc(uid)
                        a.mah += f[5].toDoubleOrNull() ?: 0.0
                        a.system = f[6] == "1"
                    }
                }
                // user ms, system ms, old power
                "cpu" -> {
                    val a = acc(uid)
                    a.cpuMs += (f[4].toLongOrNull() ?: 0L) + (f.getOrNull(5)?.toLongOrNull() ?: 0L)
                }
                // name then groups of time, type, count, current, max, total
                // for f, p, bp, w. The partial group is the one that costs
                // battery, its time sits before the p and its count after.
                "wl" -> {
                    val at = (6 until f.size).firstOrNull { f[it] == "p" }
                    if (at != null && at + 1 < f.size) {
                        val a = acc(uid)
                        a.wakeMs += (f[at - 1].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                        a.wakeCount += (f[at + 1].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    }
                }
                // "name", total ms, count, background ms, background count,
                // read from the right in case the name still holds a comma.
                "jb" -> {
                    if (f.size >= 9) {
                        val a = acc(uid)
                        a.jobMs += (f[f.size - 4].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                        a.jobCount += (f[f.size - 3].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    }
                }
                // mobile rx, mobile tx, wifi rx, wifi tx, then packets and more
                "nt" -> {
                    if (f.size >= 8) {
                        val a = acc(uid)
                        a.netBytes += (4..7).sumOf { (f[it].toLongOrNull() ?: 0L).coerceAtLeast(0L) }
                    }
                }
                // wakeup alarm name, count
                "wua" -> {
                    if (f.size >= 6) {
                        val a = acc(uid)
                        a.alarms += (f[5].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    }
                }
            }
        }
        return Checkin(capacity, accs.mapValues { it.value.done() }, packages)
    }

    // What every uid used between two checkins, most mAh first, then most CPU.
    // A second checkin below the first means the counters were reset by a
    // charge or an unplug, so nothing is subtracted and the caller starts over.
    // Battery Historian likewise treats a lower total as a new report.
    // https://github.com/google/battery-historian/blob/master/checkinparse/checkin_parse.go
    fun since(base: Checkin, now: Checkin): Since {
        if (now.totalMah + 1e-9 < base.totalMah || now.totalCpuMs < base.totalCpuMs) return Since(emptyList(), true)
        val rows = now.uids.values
            .map { it - base.uids[it.uid] }
            .filter { it.active }
            .sortedWith(compareByDescending<UidUsage> { it.mah }.thenByDescending { it.cpuMs })
        return Since(rows, false)
    }

    // System uids by name, as toybox ps prints them through bionic's
    // android_ids table: AID_<NAME> lowercased, every AID_ below 10000 except
    // range markers, the first name kept when a number repeats.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcutils/include/private/android_filesystem_config.h
    val SYSTEM_UIDS: Map<Int, String> = mapOf(
        0 to "root", 1 to "daemon", 2 to "bin", 3 to "sys", 1000 to "system", 1001 to "radio",
        1002 to "bluetooth", 1003 to "graphics", 1004 to "input", 1005 to "audio", 1006 to "camera",
        1007 to "log", 1008 to "compass", 1009 to "mount", 1010 to "wifi", 1011 to "adb",
        1012 to "install", 1013 to "media", 1014 to "dhcp", 1015 to "sdcard_rw", 1016 to "vpn",
        1017 to "keystore", 1018 to "usb", 1019 to "drm", 1020 to "mdnsr", 1021 to "gps",
        1022 to "unused1", 1023 to "media_rw", 1024 to "mtp", 1025 to "unused2", 1026 to "drmrpc",
        1027 to "nfc", 1028 to "sdcard_r", 1029 to "clat", 1030 to "loop_radio", 1031 to "media_drm",
        1032 to "package_info", 1033 to "sdcard_pics", 1034 to "sdcard_av", 1035 to "sdcard_all",
        1036 to "logd", 1037 to "shared_relro", 1038 to "dbus", 1039 to "tlsdate", 1040 to "media_ex",
        1041 to "audioserver", 1042 to "metrics_coll", 1043 to "metricsd", 1044 to "webserv",
        1045 to "debuggerd", 1046 to "media_codec", 1047 to "cameraserver", 1048 to "firewall",
        1049 to "trunks", 1050 to "nvram", 1051 to "dns", 1052 to "dns_tether", 1053 to "webview_zygote",
        1054 to "vehicle_network", 1055 to "media_audio", 1056 to "media_video", 1057 to "media_image",
        1058 to "tombstoned", 1059 to "media_obb", 1060 to "ese", 1061 to "ota_update",
        1062 to "automotive_evs", 1063 to "lowpan", 1064 to "hsm", 1065 to "reserved_disk",
        1066 to "statsd", 1067 to "incidentd", 1068 to "secure_element", 1069 to "lmkd", 1070 to "llkd",
        1071 to "iorapd", 1072 to "gpu_service", 1073 to "network_stack", 1074 to "gsid",
        1075 to "fsverity_cert", 1076 to "credstore", 1077 to "external_storage", 1078 to "ext_data_rw",
        1079 to "ext_obb_rw", 1080 to "context_hub", 1081 to "virtualizationservice", 1082 to "artd",
        1083 to "uwb", 1084 to "thread_network", 1085 to "diced", 1086 to "dmesgd", 1087 to "jc_weaver",
        1088 to "jc_strongbox", 1089 to "jc_identitycred", 1090 to "sdk_sandbox",
        1091 to "security_log_writer", 1092 to "prng_seeder", 1093 to "uprobestats", 1094 to "cros_ec",
        1095 to "mmd", 2000 to "shell", 2001 to "cache", 2002 to "diag", 3001 to "net_bt_admin",
        3002 to "net_bt", 3003 to "inet", 3004 to "net_raw", 3005 to "net_admin", 3006 to "net_bw_stats",
        3007 to "net_bw_acct", 3009 to "readproc", 3010 to "wakelock", 3011 to "uhid",
        3012 to "readtracefs", 3013 to "virtualmachine", 9997 to "everybody", 9998 to "misc",
        9999 to "nobody"
    )

    // The package of a row: the first one of its appId, "+N" for the others
    // sharing the uid, the system name of its appId when the phone named no
    // package, else the bare uid.
    fun title(uid: Int, packages: List<String>): String = when {
        packages.isEmpty() -> SYSTEM_UIDS[uid % PER_USER_RANGE] ?: "uid $uid"
        packages.size == 1 -> packages[0]
        else -> packages[0] + " +" + (packages.size - 1)
    }

    // UserHandle.formatUid: 10155 is u0a155, 1010155 is u10a155, a system
    // uid stays a plain number.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/UserHandle.java
    fun formatUid(uid: Int): String {
        if (uid < FIRST_APPLICATION_UID) return uid.toString()
        val appId = uid % PER_USER_RANGE
        val isolated = appId in FIRST_ISOLATED_UID..LAST_ISOLATED_UID || appId in FIRST_ZYGOTE_ISOLATED_UID..LAST_ZYGOTE_ISOLATED_UID
        val tail = when {
            isolated && appId > FIRST_ISOLATED_UID -> "i" + (appId - FIRST_ISOLATED_UID)
            isolated -> "ai" + (appId - FIRST_ZYGOTE_ISOLATED_UID)
            appId >= FIRST_APPLICATION_UID -> "a" + (appId - FIRST_APPLICATION_UID)
            else -> "s$appId"
        }
        return "u" + (uid / PER_USER_RANGE) + tail
    }

    private val UID_LINE = Regex("""^\s*UID (\S+): ([0-9]+[.,]?[0-9]*)(.*)$""")
    private val STATE = Regex("""(?<![\w:])(fg|bg|fgs|cached): ([0-9]+[.,]?[0-9]*)""")
    private val COMPONENT = Regex("""(?<![\w:])([A-Za-z_][\w]*)=([0-9]+[.,]?[0-9]*)""")

    // Some locales print a decimal comma.
    private fun number(text: String): Double = text.replace(',', '.').toDoubleOrNull() ?: 0.0

    // UidBatteryConsumer.dump: "UID <formatUid>: <total> fg: <mAh> (<time>)
    // bg: ... fgs: ... cached: ..." then one line of component=mAh, where a
    // key holding a colon is that component in one state and is left out.
    // Lines in parentheses are the same split by screen and power state.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/UidBatteryConsumer.java
    internal fun parseUsage(text: String): Map<String, UsageUid> {
        val out = LinkedHashMap<String, UsageUid>()
        val lines = text.lines()
        for ((i, line) in lines.withIndex()) {
            val m = UID_LINE.find(line) ?: continue
            val label = m.groupValues[1]
            val states = STATE.findAll(m.groupValues[3]).associate { it.groupValues[1] to number(it.groupValues[2]) }
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            val components = if (next.isNotEmpty() && !next.startsWith("(") && UID_LINE.find(next) == null) {
                COMPONENT.findAll(next).associate { it.groupValues[1] to number(it.groupValues[2]) }
            } else {
                emptyMap()
            }
            out[label] = UsageUid(label, number(m.groupValues[2]), states, components)
        }
        return out
    }

    fun usageTotal(usage: Map<String, UsageUid>): Double = usage.values.sumOf { it.mah }

    private val RECORD = Regex("""^\s*\* ServiceRecord\{""")

    // dumpsys activity services <name> matches by substring, so the answer
    // for com.android.bluetooth holds com.google.android.bluetooth too. A
    // block is one "* ServiceRecord{" and counts when its packageName line
    // is the package exactly.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java
    internal fun countServices(text: String, pkg: String): Int {
        var count = 0
        var inBlock = false
        var seen = false
        for (line in text.lineSequence()) {
            if (RECORD.containsMatchIn(line)) {
                inBlock = true
                seen = false
            } else if (inBlock && !seen && line.trim().startsWith("packageName=")) {
                seen = true
                if (line.trim().substringAfter('=') == pkg) count++
            }
        }
        return count
    }

    // /proc/<pid>/stat: utime and stime are fields 14 and 15, in clock ticks.
    // The command name is field 2 and may hold spaces and parentheses, so the
    // line is read after its last closing parenthesis, where field 3 comes
    // first.
    // https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/os/ProcessCpuTracker.java
    internal fun cpuTicks(stat: String): Pair<Long, Long>? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 1).trim().split(Regex("""\s+"""))
        val user = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val system = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return user to system
    }

    // mAh to 3 significant digits, as BatteryStats.formatCharge limits them.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryStats.java
    fun mahText(mah: Double): String = when {
        !mah.isFinite() || mah <= 0.0 -> "0"
        mah < 0.001 -> "< 0.001"
        else -> BigDecimal(mah).round(MathContext(3)).toPlainString()
    }

    // BatteryStats.formatTimeMs keeps hours, minutes and seconds apart, here
    // cut to two units.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryStats.java
    fun durationText(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(ms / 1000.0)
        ms < 3_600_000 -> "%d min %02d s".format(ms / 60_000, ms / 1000 % 60)
        else -> "%d h %02d min".format(ms / 3_600_000, ms / 60_000 % 60)
    }

    // The phone side. Each call is one adb round trip and blocks: callers
    // run it on an IO dispatcher.

    fun sysfs(adb: Path, serial: String): BatteryReading? = parseSysfs(Adb.shell(adb, serial, SYSFS, 10).out)

    fun battery(adb: Path, serial: String): DumpsysBattery? = parseDumpsysBattery(Adb.shell(adb, serial, DUMPSYS_BATTERY, 10).out)

    fun checkin(adb: Path, serial: String): Checkin? {
        val r = Adb.shell(adb, serial, CHECKIN, 60)
        if (r.code != 0) return null
        return parseCheckin(r.out).takeIf { it.uids.isNotEmpty() }
    }

    // The package filter of --usage is ignored by Android: the whole report
    // comes back and the caller picks its uid.
    fun usage(adb: Path, serial: String): Map<String, UsageUid>? {
        val r = Adb.shell(adb, serial, USAGE, 60)
        if (r.code != 0) return null
        return parseUsage(r.out).takeIf { it.isNotEmpty() }
    }

    fun services(adb: Path, serial: String, pkg: String): Int? {
        val r = Adb.shell(adb, serial, "dumpsys activity services " + Adb.quote(pkg), 30)
        return if (r.code == 0) countServices(r.out, pkg) else null
    }

    fun ticks(adb: Path, serial: String, pid: Int): Pair<Long, Long>? {
        val r = Adb.shell(adb, serial, "cat /proc/$pid/stat", 10)
        return if (r.code == 0) cpuTicks(r.out) else null
    }
}
