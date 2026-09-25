package io.gutapk.device

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class DeviceState { READY, UNAUTHORIZED, OFFLINE, NO_PERMISSION, OTHER }

class AdbDevice(val serial: String, val state: DeviceState, val model: String?, val raw: String)

class AdbResult(val code: Int, val out: String)

// adb and nothing else: runs a command, parses what adb prints. Knows no
// feature. The standard server and key are used, like any adb client, so a
// phone already authorised for the system's adb stays authorised and the
// two never fight over the USB device.
object Adb {
    fun run(adb: Path, args: List<String>, timeoutS: Long = 30): AdbResult {
        val process = ProcessBuilder(listOf(adb.toString()) + args).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { out.append(it).append('\n') } }
        }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return AdbResult(-1, "adb ${args.joinToString(" ")} did not answer within $timeoutS s")
        }
        reader.join(2000)
        return AdbResult(process.exitValue(), out.toString())
    }

    fun devices(adb: Path): List<AdbDevice> = parseDevices(run(adb, listOf("devices", "-l")).out)

    // command is read by the device's shell. Anything that did not come
    // from GutapK itself goes through quote first.
    fun shell(adb: Path, serial: String, command: String, timeoutS: Long = 30): AdbResult =
        run(adb, listOf("-s", serial, "shell", command), timeoutS)

    // One shell word, single quoted: nothing inside is expanded, a quote
    // inside is closed, escaped and reopened.
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    // "SERIAL<tab or spaces>state key:value ...". The no permissions state
    // carries a sentence, so the state is read by its first words. Only the
    // lines after adb's header are devices: before it come the daemon's
    // start messages and "adb server version (x) doesn't match this client".
    internal fun parseDevices(text: String): List<AdbDevice> =
        text.lineSequence()
            .map { it.trim() }
            .dropWhile { !it.startsWith("List of devices") }
            .drop(1)
            .filter { it.isNotEmpty() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("""\s+"""), limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val rest = parts[1]
                val state = when {
                    rest.startsWith("device") -> DeviceState.READY
                    rest.startsWith("unauthorized") -> DeviceState.UNAUTHORIZED
                    rest.startsWith("offline") -> DeviceState.OFFLINE
                    rest.startsWith("no permissions") -> DeviceState.NO_PERMISSION
                    else -> DeviceState.OTHER
                }
                val model = Regex("""\bmodel:(\S+)""").find(rest)?.groupValues?.get(1)?.replace('_', ' ')
                AdbDevice(parts[0], state, model, rest)
            }
            .toList()

    // getprop prints "[key]: [value]", one per line.
    internal fun parseProps(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { Regex("""^\[([^\]]+)\]: \[(.*)\]$""").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }

    const val PORT = 5037

    // The protocol version a running server answers to host:version, read
    // over its socket without restarting it. Null when no server listens.
    // An adb client restarts any server whose number differs from its own.
    fun serverVersion(port: Int = PORT): Int? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            socket.soTimeout = 2000
            val request = "host:version"
            socket.getOutputStream().write(("%04x".format(request.length) + request).toByteArray(Charsets.US_ASCII))
            parseVersionReply(socket.getInputStream().readNBytes(12))
        }
    }.getOrNull()

    // OKAY, then a 4 hex digit length, then the version in 4 hex digits.
    internal fun parseVersionReply(reply: ByteArray): Int? {
        val text = String(reply, Charsets.US_ASCII)
        if (text.length < 12 || !text.startsWith("OKAY")) return null
        return text.substring(8, 12).toIntOrNull(16)
    }

    // "Android Debug Bridge version 1.0.41" gives 41, the number the server
    // is compared with. The release, "Version 37.0.1-...", comes next.
    fun clientVersion(adb: Path): Pair<Int, String?> {
        val out = run(adb, listOf("version"), 10).out
        return parseClientVersion(out) ?: throw IOException("adb version printed nothing readable")
    }

    internal fun parseClientVersion(text: String): Pair<Int, String?>? {
        val protocol = Regex("""Android Debug Bridge version \d+\.\d+\.(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val release = Regex("""^Version (\S+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        return protocol to release
    }

    // Stops whatever server listens on the port, whichever adb started it.
    fun killServer(adb: Path): AdbResult = run(adb, listOf("kill-server"), 15)
}
