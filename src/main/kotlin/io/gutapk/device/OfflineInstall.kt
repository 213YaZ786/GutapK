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
// not ported: runInstall (it runs on the phone, only its reason is kept: pm installs for every user when --user is absent, so the caller always gives one)
// not ported: runStartActivity (it runs on the phone, only its options are used: --user, -a, -c, -p)
object OfflineInstall {
    private const val ALREADY_THERE = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

    // pm uninstall --user, run in the phone's shell as AppActions does for UNINSTALL.
    fun uninstallCommand(pkg: String, user: String): String {
        require(DeviceInstall.validUser(user)) { "not a user: $user" }
        return "pm uninstall --user $user " + Adb.quote(pkg)
    }

    // monkey has no --user. am start takes one and finds the launcher activity of
    // the package from the MAIN and LAUNCHER intent with -p. It refuses "all" for
    // an activity, so the current user stands for it.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java#runStartActivity
    fun launchCommand(pkg: String, user: String?): String {
        if (user == null) return TryOut.launchCommand(pkg)
        require(DeviceInstall.validUser(user)) { "not a user: $user" }
        val target = if (user == "all") "current" else user
        return "am start --user $target -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + Adb.quote(pkg)
    }

    // The users a package is installed for: pm prints "package:<name> uid:<uid>",
    // several uids comma separated, and filters by substring, so the name is
    // matched exactly. The user of a uid is uid / PER_USER_RANGE.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/UserHandle.java#getUserId
    fun usersWith(out: String, pkg: String): List<Int> = out.lines().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.firstOrNull() != "package:$pkg") return@mapNotNull null
        fields.firstOrNull { it.startsWith("uid:") }?.removePrefix("uid:")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
    }.flatten().map { it / Power.PER_USER_RANGE }.distinct().sorted()

    fun installedUsers(adb: Path, serial: String, pkg: String): List<Int> =
        usersWith(Adb.shell(adb, serial, "pm list packages --user all -U " + Adb.quote(pkg)).out, pkg)

    private fun uninstallLine(serial: String, pkg: String, user: String?): String =
        if (user == null) TryOut.uninstallArgs(serial, pkg).joinToString(" ") else "-s $serial shell " + uninstallCommand(pkg, user)

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/TryOut.kt#plan
    fun plan(serial: String, apk: Path, pkg: String, uninstallFirst: Boolean, user: String? = null): List<String> = plan(serial, listOf(apk), pkg, uninstallFirst, user)

    // A split set: every part in one install-multiple, as the platform keeps a set.
    fun plan(serial: String, apks: List<Path>, pkg: String, uninstallFirst: Boolean, user: String? = null): List<String> = buildList {
        add("adb " + DeviceInstall.args(serial, apks, false, user).joinToString(" "))
        // Only after the phone refused the install for another signature.
        if (uninstallFirst) {
            add("$ALREADY_THERE: adb " + uninstallLine(serial, pkg, user))
            add("$ALREADY_THERE: adb " + DeviceInstall.args(serial, apks, false, user).joinToString(" "))
        }
        add("adb -s $serial shell " + launchCommand(pkg, user))
    }

    // first and steps place the two steps in the job that builds the file
    // before them.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/TryOut.kt#run
    fun run(adb: Path, serial: String, apk: Path, pkg: String, uninstallFirst: Boolean, sink: JobSink, cancelled: () -> Boolean, first: Int = 1, steps: Int = 2, user: String? = null) =
        run(adb, serial, listOf(apk), pkg, uninstallFirst, sink, cancelled, first, steps, user)

    fun run(adb: Path, serial: String, apks: List<Path>, pkg: String, uninstallFirst: Boolean, sink: JobSink, cancelled: () -> Boolean, first: Int = 1, steps: Int = 2, user: String? = null) {
        var step = first
        sink.emit(JobEvent.Step("install", step++, steps))
        try {
            DeviceInstall.install(adb, serial, apks, false, sink, cancelled, user)
        } catch (e: InstallRefused) {
            if (!uninstallFirst || e.code != ALREADY_THERE) throw e
            sink.emit(JobEvent.Line("adb " + uninstallLine(serial, pkg, user)))
            val r = if (user == null) Adb.run(adb, TryOut.uninstallArgs(serial, pkg), 120, cancelled) else Adb.shell(adb, serial, uninstallCommand(pkg, user), 120)
            r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
            DeviceInstall.install(adb, serial, apks, false, sink, cancelled, user)
        }

        sink.emit(JobEvent.Step("open", step, steps))
        val command = launchCommand(pkg, user)
        sink.emit(JobEvent.Line("adb -s $serial shell $command"))
        val r = Adb.shell(adb, serial, command, 30)
        AppActions.failed(r.out)?.let { throw IOException("the app did not open: $it") }
    }
}
