package io.gutapk.device

import io.gutapk.tools.RunLog
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// What the user switched on before starting. Each maps to one scrcpy
// option, shown in the command before it runs.
data class MirrorOptions(
    val screenOff: Boolean = false,
    val stayAwake: Boolean = true,
    val showTouches: Boolean = false,
    val audio: Boolean = true,
    val readOnly: Boolean = false,
    val maxSize: Int? = null,
    val record: Path? = null,
)

// scrcpy in its own window, one per phone. It is given GutapK's adb
// through ADB, so no second adb of another version restarts the server.
object Mirror {
    private val running = ConcurrentHashMap<String, Process>()

    fun args(serial: String, o: MirrorOptions): List<String> = buildList {
        add("--serial=$serial")
        add("--window-title=GutapK  $serial")
        if (o.screenOff) add("--turn-screen-off")
        if (o.stayAwake) add("--stay-awake")
        if (o.showTouches) add("--show-touches")
        if (!o.audio) add("--no-audio")
        if (o.readOnly) add("--no-control")
        o.maxSize?.let { add("--max-size=$it") }
        o.record?.let { add("--record=$it") }
    }

    fun start(scrcpy: Path, adb: Path, serial: String, o: MirrorOptions): Process {
        stop(serial)
        val cmd = listOf(scrcpy.toString()) + args(serial, o)
        RunLog.line("[mirror] ADB=$adb " + cmd.joinToString(" "))
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["ADB"] = adb.toString()
        val process = pb.start()
        running[serial] = process
        // scrcpy says what it does and why it stopped. The run log keeps it.
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { RunLog.line("[mirror] $it") } }
        }
        reader.isDaemon = true
        reader.start()
        return process
    }

    fun isRunning(serial: String): Boolean = running[serial]?.isAlive == true

    fun stop(serial: String) {
        running.remove(serial)?.let { p ->
            p.destroy()
            RunLog.line("[mirror] stopped $serial")
        }
    }
}
