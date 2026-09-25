package io.gutapk.device

import io.gutapk.core.apk.SplitSet
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.io.IOException
import java.nio.file.Path

// Why Android refused an install, the code it names in "Failure [...]".
class InstallRefused(val code: String, val output: String) : IOException("install refused: $code")

object DeviceInstall {
    // One APK is install, several are install-multiple: the base and its
    // splits go in one session, as the Play Store does. -r keeps the app's
    // data when it is already there.
    fun args(serial: String, files: List<Path>, downgrade: Boolean): List<String> = buildList {
        add("-s")
        add(serial)
        add(if (files.size == 1) "install" else "install-multiple")
        add("-r")
        if (downgrade) add("-d")
        files.forEach { add(it.toString()) }
    }

    // A .apks, .xapk or .apkm is opened into its APKs first, the same way
    // the editor imports it, and refused when parts are missing.
    fun prepare(sources: List<Path>, work: Path, sink: JobSink, cancelled: () -> Boolean): List<Path> {
        if (sources.size == 1 && SplitSet.extension(sources[0]) == "apk") return sources
        val set = SplitSet.gather(sources, work, sink, cancelled)
        if (set.problem != null) throw CheckFailed("the split set is incomplete: ${set.problem}")
        return set.parts.map { it.file }
    }

    fun install(adb: Path, serial: String, files: List<Path>, downgrade: Boolean, sink: JobSink, cancelled: () -> Boolean): String {
        val args = args(serial, files, downgrade)
        sink.emit(JobEvent.Step("install", 1, 1))
        sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
        val r = Adb.run(adb, args, 1800, cancelled)
        r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
        val code = failure(r.out)
        if (code != null) throw InstallRefused(code, r.out.trim())
        if (r.code != 0 || !r.out.lines().any { it.trim() == "Success" }) throw CheckFailed("adb install did not report success: ${r.out.trim()}")
        return r.out.trim()
    }

    // "adb: failed to install x.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE: ...]"
    internal fun failure(out: String): String? =
        Regex("""Failure \[(INSTALL_[A-Z_]+)""").find(out)?.groupValues?.get(1)
}
