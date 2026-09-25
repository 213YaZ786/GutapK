package io.gutapk.device

import io.gutapk.tools.RunLog
import java.nio.file.Path

// One line of logcat -v threadtime:
// "09-25 18:49:13.123  1234  5678 I ActivityManager: Start proc".
class LogLine(val time: String, val pid: Int, val level: Char, val tag: String, val message: String) {
    val search: String = "$tag $message".lowercase()
}

// logcat running on the phone, its lines kept in a ring the page reads.
// The page may be slower than the phone, so lines are collected here and
// read in batches, never one recomposition per line.
class LogStream(private val adb: Path, private val serial: String, private val pid: Int?) {
    private val lines = ArrayDeque<LogLine>()
    private var process: Process? = null

    @Volatile
    var paused: Boolean = false

    fun start() {
        val cmd = listOf(adb.toString()) + Logcat.args(serial, pid)
        RunLog.line("[logcat] " + cmd.drop(1).joinToString(" "))
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process = p
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { raw ->
                        val line = Logcat.parse(raw) ?: return@forEach
                        if (!paused) {
                            synchronized(lines) {
                                lines.addLast(line)
                                while (lines.size > Logcat.KEPT) lines.removeFirst()
                            }
                        }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
    }

    fun snapshot(): List<LogLine> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}

object Logcat {
    // Enough to scroll back through a burst, small enough for memory.
    const val KEPT = 5000

    // Android's levels, most verbose first.
    val LEVELS = listOf('V', 'D', 'I', 'W', 'E', 'F')

    private val LINE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*:\s(.*)$""")

    fun args(serial: String, pid: Int?): List<String> = buildList {
        add("-s")
        add(serial)
        add("logcat")
        add("-v")
        add("threadtime")
        // The last lines already there, then the new ones as they come.
        add("-T")
        add("500")
        if (pid != null) add("--pid=$pid")
    }

    internal fun parse(raw: String): LogLine? {
        val m = LINE.find(raw) ?: return null
        val level = m.groupValues[4][0].let { if (it == 'A') 'F' else it }
        return LogLine(m.groupValues[1], m.groupValues[2].toInt(), level, m.groupValues[5].trim(), m.groupValues[6])
    }

    fun atLeast(line: LogLine, level: Char): Boolean = LEVELS.indexOf(line.level) >= LEVELS.indexOf(level)

    // Every word must appear in the tag or the message.
    fun matches(line: LogLine, words: List<String>): Boolean = words.all { it in line.search }

    fun pidof(adb: Path, serial: String, pkg: String): Int? =
        Adb.shell(adb, serial, "pidof " + Adb.quote(pkg)).out.trim().split(Regex("""\s+""")).firstOrNull()?.toIntOrNull()

    // The phone's log buffers emptied, main system and crash.
    const val CLEAR = "logcat -c"

    fun bugreportArgs(serial: String, dir: Path): List<String> = listOf("-s", serial, "bugreport", dir.toString())

    fun format(line: LogLine): String = "${line.time} ${line.pid} ${line.level} ${line.tag}: ${line.message}"
}
