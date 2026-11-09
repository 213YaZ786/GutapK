package io.gutapk.device

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.nio.file.Path

// The game with its own offline server, installed on one phone and opened,
// for the Offline button. The commands are the ones listed to the user before
// anything runs, in the same order. The phone's copy is replaced in place
// first. It is removed only when the phone refuses the install for another
// signature and the user allowed it, so the game's data is lost only when it
// has to be.
// not ported: readLog, logArgs (the game's log is not read back here, the server records online by itself)
object OfflineInstall {
    private const val ALREADY_THERE = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/TryOut.kt#plan
    fun plan(serial: String, apk: Path, pkg: String, uninstallFirst: Boolean): List<String> = buildList {
        add("adb " + DeviceInstall.args(serial, listOf(apk), false).joinToString(" "))
        // Only after the phone refused the install for another signature.
        if (uninstallFirst) {
            add("$ALREADY_THERE: adb " + TryOut.uninstallArgs(serial, pkg).joinToString(" "))
            add("$ALREADY_THERE: adb " + DeviceInstall.args(serial, listOf(apk), false).joinToString(" "))
        }
        add("adb -s $serial shell " + TryOut.launchCommand(pkg))
    }

    // first and steps place the two steps in the job that builds the file
    // before them.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/TryOut.kt#run
    fun run(adb: Path, serial: String, apk: Path, pkg: String, uninstallFirst: Boolean, sink: JobSink, cancelled: () -> Boolean, first: Int = 1, steps: Int = 2) {
        var step = first
        sink.emit(JobEvent.Step("install", step++, steps))
        try {
            DeviceInstall.install(adb, serial, listOf(apk), false, sink, cancelled)
        } catch (e: InstallRefused) {
            if (!uninstallFirst || e.code != ALREADY_THERE) throw e
            val args = TryOut.uninstallArgs(serial, pkg)
            sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
            val r = Adb.run(adb, args, 120, cancelled)
            r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
            DeviceInstall.install(adb, serial, listOf(apk), false, sink, cancelled)
        }

        sink.emit(JobEvent.Step("open", step, steps))
        val command = TryOut.launchCommand(pkg)
        sink.emit(JobEvent.Line("adb -s $serial shell $command"))
        val r = Adb.shell(adb, serial, command, 30)
        AppActions.failed(r.out)?.let { throw IOException("the app did not open: $it") }
    }
}
