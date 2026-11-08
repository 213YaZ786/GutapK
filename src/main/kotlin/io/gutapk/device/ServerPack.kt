package io.gutapk.device

import io.gutapk.core.offline.ServerPack
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import java.io.IOException
import java.nio.file.Path

// A server pack and a patched game put on one phone, the pack set to a mode
// and opened, the game launched, for the Offline and Local buttons. The pack
// is driven through its control provider, which answers only the adb shell:
// content call --uri <control> --method <mode|token|status|reset>. The
// commands are listed to the user before anything runs, in the same order.
object ServerControl {
    private const val POLL_MS = 500L
    private const val WAIT_ADDRESS_MS = 6000L
    private const val ALREADY_THERE = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    private val WORD = Regex("""[a-z_]{1,32}""")

    class Outcome(val mode: String, val status: Map<String, String>, val address: String?)

    // The method and the argument are words GutapK itself chooses, checked
    // all the same, and the address of the provider is quoted.
    fun command(control: String, method: String, arg: String? = null): String {
        require(WORD.matches(method)) { "not a method: $method" }
        require(arg == null || WORD.matches(arg)) { "not an argument: $arg" }
        return "content call --uri " + Adb.quote(control) + " --method " + method + (if (arg != null) " --arg $arg" else "")
    }

    // command is read by the device's shell, like Adb.shell, and what the
    // provider answers is read back: a refusal comes on stdout with exit 0.
    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/device/Adb.kt#shell
    fun shell(adb: Path, serial: String, control: String, method: String, arg: String? = null, timeoutS: Long = 30): Map<String, String> {
        val r = Adb.run(adb, listOf("-s", serial, "shell", command(control, method, arg)), timeoutS)
        return parseBundle(r.out)
    }

    // AOSP's content tool prints "Result: " and the bundle's toString,
    // Bundle[{token=abc, mode=host}], or an exception's text when the
    // provider refuses the caller.
    // not ported: GetTypeCommand, ReadCommand (the control has no type and no file to read)
    // not ported: onExecute (the tool runs in the phone, here only what it printed is read back)
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java#CallCommand
    internal fun parseBundle(out: String): Map<String, String> {
        val result = out.lines().map { it.trim() }.firstOrNull { it.startsWith("Result: ") }
            ?: throw IOException(refused(out) ?: "the server app answered nothing readable: ${out.trim()}")
        val text = result.removePrefix("Result: ")
        if (text == "Bundle[EMPTY_PARCEL]") return emptyMap()
        if (!text.startsWith("Bundle[{") || !text.endsWith("}]")) throw IOException("the server app answered $text")
        val inner = text.removePrefix("Bundle[{").removeSuffix("}]")
        // A value ends where the next ", key=" begins.
        val marks = Regex("""(?:^|, )([A-Za-z0-9_.]+)=""").findAll(inner).toList()
        return marks.withIndex().associate { (i, m) ->
            m.groupValues[1] to inner.substring(m.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: inner.length)
        }
    }

    private fun refused(out: String): String? =
        out.lines().map { it.trim() }.let { lines -> lines.firstOrNull { it.contains("Exception") } ?: lines.firstOrNull { it.startsWith("Error") } }

    // The address a guest or a browser reaches the host at, from status. A
    // service not started yet answers lan_address=null.
    fun address(status: Map<String, String>): String? {
        val host = status["lan_address"]?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val port = status["port"]?.takeIf { it.isNotBlank() && it != "null" }
        return if (port != null) "$host:$port" else host
    }

    fun token(adb: Path, serial: String, pack: ServerPack): String =
        shell(adb, serial, pack.control, "token")["token"]?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("the server app gave no token")

    fun plan(serial: String, pack: ServerPack, packFiles: List<Path>, pkg: String, files: List<Path>, mode: String, uninstallFirst: Boolean): List<String> = buildList {
        add("adb " + DeviceInstall.args(serial, packFiles, false).joinToString(" "))
        add("adb -s $serial shell " + command(pack.control, "mode", mode))
        add("adb -s $serial shell " + TryOut.launchCommand(pack.packageName))
        add("adb " + DeviceInstall.args(serial, files, false).joinToString(" "))
        // Only after the phone refused the install for another signature.
        if (uninstallFirst) {
            add("$ALREADY_THERE: adb " + TryOut.uninstallArgs(serial, pkg).joinToString(" "))
            add("$ALREADY_THERE: adb " + DeviceInstall.args(serial, files, false).joinToString(" "))
        }
        add("adb -s $serial shell " + TryOut.launchCommand(pkg))
        if (mode == "host") add("adb -s $serial shell " + command(pack.control, "status"))
    }

    // The pack in, set to the mode and opened, then the game in and launched.
    // uninstallFirst allows the one uninstall a signature of another key
    // needs: the game is installed over its copy first, and removed only if
    // the phone refuses, so its data is lost only when it has to be.
    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/device/TryOut.kt#run
    fun run(
        adb: Path,
        serial: String,
        pack: ServerPack,
        packFiles: List<Path>,
        pkg: String,
        files: List<Path>,
        mode: String,
        uninstallFirst: Boolean,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): Outcome {
        if (mode !in pack.modes) throw IOException("${pack.name} has no $mode mode")
        val steps = if (mode == "host") 6 else 5
        var step = 1
        sink.emit(JobEvent.Step("install ${pack.name}", step++, steps))
        DeviceInstall.install(adb, serial, packFiles, false, sink, cancelled)

        sink.emit(JobEvent.Step("mode $mode", step++, steps))
        val command = command(pack.control, "mode", mode)
        sink.emit(JobEvent.Line("adb -s $serial shell $command"))
        val set = shell(adb, serial, pack.control, "mode", mode)
        sink.emit(JobEvent.Line(set.entries.joinToString(", ") { "${it.key}=${it.value}" }))

        sink.emit(JobEvent.Step("open ${pack.name}", step++, steps))
        val open = TryOut.launchCommand(pack.packageName)
        sink.emit(JobEvent.Line("adb -s $serial shell $open"))
        val r = Adb.shell(adb, serial, open, 30)
        AppActions.failed(r.out)?.let { throw IOException("the server app did not open: $it") }

        sink.emit(JobEvent.Step("install", step++, steps))
        try {
            DeviceInstall.install(adb, serial, files, false, sink, cancelled)
        } catch (e: InstallRefused) {
            if (!uninstallFirst || e.code != ALREADY_THERE) throw e
            val args = TryOut.uninstallArgs(serial, pkg)
            sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
            Adb.run(adb, args, 120, cancelled).out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
            DeviceInstall.install(adb, serial, files, false, sink, cancelled)
        }

        sink.emit(JobEvent.Step("open", step++, steps))
        val launch = TryOut.launchCommand(pkg)
        sink.emit(JobEvent.Line("adb -s $serial shell $launch"))
        val g = Adb.shell(adb, serial, launch, 30)
        AppActions.failed(g.out)?.let { throw IOException("the app did not open: $it") }

        if (mode != "host") return Outcome(mode, set, null)
        sink.emit(JobEvent.Step("status", step, steps))
        var status = shell(adb, serial, pack.control, "status")
        // The service starts from the opened activity, a moment after mode.
        val until = System.currentTimeMillis() + WAIT_ADDRESS_MS
        while (address(status) == null && System.currentTimeMillis() < until) {
            if (cancelled()) throw CancelledByUser()
            Thread.sleep(POLL_MS)
            status = shell(adb, serial, pack.control, "status")
        }
        return Outcome(mode, status, address(status))
    }
}
