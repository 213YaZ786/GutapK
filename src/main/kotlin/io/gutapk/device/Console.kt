package io.gutapk.device

import io.gutapk.tools.RunLog
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// One command typed by the user, sent to the phone's shell as typed: the
// console is the one place where the user's text is the command. Its
// output is kept as it comes, so a long command shows progress and can be
// stopped.
class ShellRun(adb: Path, serial: String, val command: String) {
    private val lines = ArrayList<String>()
    private val process: Process

    @Volatile
    var exitCode: Int? = null
        private set

    init {
        RunLog.line("[console] adb -s $serial shell $command")
        process = ProcessBuilder(listOf(adb.toString(), "-s", serial, "shell", command)).redirectErrorStream(true).start()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        synchronized(lines) {
                            lines.add(line)
                            if (lines.size > Console.KEPT) lines.removeAt(0)
                        }
                    }
                }
            }
            exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            RunLog.line("[console] exit $exitCode")
        }
        reader.isDaemon = true
        reader.start()
    }

    val running: Boolean get() = exitCode == null

    fun output(): List<String> = synchronized(lines) { lines.toList() }

    fun stop() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

object Console {
    const val KEPT = 5000

    // Commands worth one click, read only: nothing here changes the phone.
    val PRESETS = listOf(
        "getprop ro.build.fingerprint",
        "uname -a",
        "df -h",
        "ps -A",
        "top -b -n 1 -m 15",
        "ip addr",
        "dumpsys battery",
        "dumpsys meminfo",
        "pm list packages -3",
        "settings list global",
        "cmd package list libraries",
        "service list",
    )

    // History kept for the run, newest first, one of each.
    fun remember(history: List<String>, command: String): List<String> =
        (listOf(command) + history.filter { it != command }).take(50)
}
