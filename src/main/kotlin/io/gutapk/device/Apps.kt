package io.gutapk.device

import io.gutapk.core.apk.RangeReader
import io.gutapk.core.apk.RemoteIcon
import io.gutapk.core.apk.RemoteIcons
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class InstalledApp(val packageName: String, val path: String, val system: Boolean)

class AppDetails(
    val versionName: String?,
    val versionCode: String?,
    val installer: String?,
    val firstInstall: String?,
    val lastUpdate: String?,
    val apks: List<String>,
)

object DeviceApps {
    // Android package names, nothing else is ever put in a shell command.
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    fun list(adb: Path, serial: String, system: Boolean): List<InstalledApp> {
        val flag = if (system) "-s" else "-3"
        val out = Adb.shell(adb, serial, "pm list packages -f $flag", 60).out
        return parseList(out, system).sortedBy { it.packageName }
    }

    // "package:/data/app/~~a==/com.x-b==/base.apk=com.x". The path may hold
    // "=", so the name is what follows the last one.
    internal fun parseList(text: String, system: Boolean): List<InstalledApp> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") && '=' in it }
            .mapNotNull { line ->
                val body = line.removePrefix("package:")
                val name = body.substringAfterLast('=')
                if (!PACKAGE.matches(name)) return@mapNotNull null
                InstalledApp(name, body.substringBeforeLast('='), system)
            }
            .toList()

    fun details(adb: Path, serial: String, packageName: String): AppDetails {
        require(PACKAGE.matches(packageName)) { "not a package name: $packageName" }
        val quoted = Adb.quote(packageName)
        val dump = Adb.shell(adb, serial, "dumpsys package $quoted", 60).out
        val paths = parsePaths(Adb.shell(adb, serial, "pm path $quoted").out)
        return parseDetails(dump, paths)
    }

    // The first value of each key in the package's section of dumpsys.
    internal fun parseDetails(dump: String, apks: List<String>): AppDetails {
        fun first(key: String): String? =
            Regex("""(?m)^\s*$key=(.+?)\s*$""").find(dump)?.groupValues?.get(1)
        val code = Regex("""\bversionCode=(\d+)""").find(dump)?.groupValues?.get(1)
        return AppDetails(
            versionName = first("versionName"),
            versionCode = code,
            installer = Regex("""\binstallerPackageName=(\S+)""").find(dump)?.groupValues?.get(1)?.takeIf { it != "null" },
            firstInstall = first("firstInstallTime"),
            lastUpdate = first("lastUpdateTime"),
            apks = apks,
        )
    }

    // "package:/data/app/.../base.apk", one line per APK of the app.
    internal fun parsePaths(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("package:/") }.map { it.removePrefix("package:") }.toList()

    // The name and icon of an installed app, from a few pieces of its APK
    // read in place. The path came from the device, so it is quoted.
    fun icon(adb: Path, serial: String, apk: String, work: Path): RemoteIcon {
        val quoted = Adb.quote(apk)
        val size = Adb.shell(adb, serial, "stat -c %s $quoted").out.trim().toLongOrNull()
            ?: throw IOException("cannot read the size of $apk")
        return RemoteIcons.read(size, reader(adb, serial, quoted), work)
    }

    // dd in whole blocks, the one form every toybox dd has, then cut to the
    // bytes asked for.
    private const val BLOCK = 4096

    internal fun ddCommand(quotedPath: String, offset: Long, length: Int): Pair<String, Int> {
        val first = offset / BLOCK
        val last = (offset + length + BLOCK - 1) / BLOCK
        return "dd if=$quotedPath bs=$BLOCK skip=$first count=${last - first} 2>/dev/null" to (offset - first * BLOCK).toInt()
    }

    private fun reader(adb: Path, serial: String, quotedPath: String) = RangeReader { offset, length ->
        if (length <= 0) {
            ByteArray(0)
        } else {
            val (command, cut) = ddCommand(quotedPath, offset, length)
            val bytes = Adb.runBytes(adb, listOf("-s", serial, "exec-out", command))
            if (cut >= bytes.size) ByteArray(0) else bytes.copyOfRange(cut, minOf(bytes.size, cut + length))
        }
    }

    // Every APK of the app into dir, under its own file name. adb pull
    // takes the path as an argument, no shell reads it.
    fun pull(adb: Path, serial: String, apks: List<String>, dir: Path, sink: JobSink, cancelled: () -> Boolean): List<Path> {
        Files.createDirectories(dir)
        return apks.mapIndexed { i, remote ->
            if (cancelled()) throw CancelledByUser()
            sink.emit(JobEvent.Step("pull", i + 1, apks.size))
            val name = remote.substringAfterLast('/').ifEmpty { "part-$i.apk" }
            val local = dir.resolve(name)
            sink.emit(JobEvent.Line("adb -s $serial pull $remote $local"))
            val r = Adb.run(adb, listOf("-s", serial, "pull", remote, local.toString()), 1800)
            if (r.code != 0 || !Files.isRegularFile(local)) throw IOException("adb pull failed for $remote: ${r.out.trim()}")
            local
        }
    }
}
