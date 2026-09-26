package io.gutapk.device

import io.gutapk.core.apk.Parts
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import java.io.IOException
import java.nio.file.Path

// A freshly signed APK, or every part of a signed set, installed on one
// phone, opened, and what it logged read back, for the edit, sign and try
// loop. The commands are the ones
// listed to the user before anything runs, in the same order.
object TryOut {
    private const val LOG_LINES = 300
    private const val WAIT_PID_MS = 4000L
    private const val POLL_MS = 500L

    class Outcome(val pid: Int?, val log: List<String>)

    fun uninstallArgs(serial: String, pkg: String): List<String> = listOf("-s", serial, "uninstall", pkg)

    fun launchCommand(pkg: String): String = AppActions.command(AppAction.LAUNCH, pkg, 0)

    // The app's own lines while it runs, the phone's crash buffer when it
    // is already gone.
    fun logArgs(serial: String, pid: Int?): List<String> =
        listOf("-s", serial, "logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString()) +
            if (pid != null) listOf("--pid=$pid") else listOf("-b", "crash")

    fun plan(serial: String, apk: Path, pkg: String, uninstallFirst: Boolean): List<String> = buildList {
        if (uninstallFirst) add("adb " + uninstallArgs(serial, pkg).joinToString(" "))
        add("adb " + DeviceInstall.args(serial, Parts.apks(apk), false).joinToString(" "))
        add("adb -s $serial shell " + launchCommand(pkg))
        add("adb " + logArgs(serial, null).dropLast(2).joinToString(" ") + " --pid=<pid>")
    }

    fun run(adb: Path, serial: String, apk: Path, pkg: String, uninstallFirst: Boolean, sink: JobSink, cancelled: () -> Boolean): Outcome {
        val steps = if (uninstallFirst) 4 else 3
        var step = 1
        if (uninstallFirst) {
            sink.emit(JobEvent.Step("uninstall", step++, steps))
            val args = uninstallArgs(serial, pkg)
            sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
            val r = Adb.run(adb, args, 120, cancelled)
            r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
        }
        sink.emit(JobEvent.Step("install", step++, steps))
        DeviceInstall.install(adb, serial, Parts.apks(apk), false, sink, cancelled)

        sink.emit(JobEvent.Step("open", step++, steps))
        val command = launchCommand(pkg)
        sink.emit(JobEvent.Line("adb -s $serial shell $command"))
        val r = Adb.shell(adb, serial, command, 30)
        AppActions.failed(r.out)?.let { throw IOException("the app did not open: $it") }

        sink.emit(JobEvent.Step("log", step, steps))
        var pid: Int? = null
        val until = System.currentTimeMillis() + WAIT_PID_MS
        while (pid == null && System.currentTimeMillis() < until) {
            if (cancelled()) throw CancelledByUser()
            Thread.sleep(POLL_MS)
            pid = Logcat.pidof(adb, serial, pkg)
        }
        return Outcome(pid, readLog(adb, serial, pid))
    }

    fun readLog(adb: Path, serial: String, pid: Int?): List<String> =
        Adb.run(adb, logArgs(serial, pid), 30).out.lines().filter { it.isNotBlank() && !it.startsWith("---------") }.takeLast(LOG_LINES)
}
