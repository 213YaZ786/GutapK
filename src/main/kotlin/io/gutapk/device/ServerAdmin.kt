package io.gutapk.device

import io.gutapk.core.offline.ServerPack
import io.gutapk.tools.Json
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

// One value the server pack lets the admin change, as its list describes it.
// type is int, bool or string. min and max bound an int, restart says the
// pack restarts something when it changes, note is the pack's remark.
data class Param(
    val id: String,
    val group: String,
    val label: String,
    val type: String,
    val value: Any?,
    val default: Any?,
    val min: Long?,
    val max: Long?,
    val restart: Boolean,
    val note: String?,
)

// What the pack answered to a set: 200 with ok, or 400 with an error text.
data class Answer(val status: Int, val ok: Boolean, val error: String?, val restarted: Boolean, val applied: List<String>)

// The admin side of a server pack: its token from the control, a forward from
// a local port to the pack's port, and two requests over it, GET the list
// and POST the changed values. The pack answers only on the phone's loopback
// and checks the token, so nothing here is reachable from the network.
object ServerAdmin {
    private const val TIMEOUT_S = 10L

    // The local port adb forwards to the phone's, found free by asking the
    // system for one.
    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    fun forwardArgs(serial: String, local: Int, remote: Int): List<String> = listOf("-s", serial, "forward", "tcp:$local", "tcp:$remote")

    fun removeArgs(serial: String, local: Int): List<String> = listOf("-s", serial, "forward", "--remove", "tcp:$local")

    fun forward(adb: Path, serial: String, pack: ServerPack): Int {
        val local = freePort()
        val r = Adb.run(adb, forwardArgs(serial, local, pack.port), 15)
        if (r.code != 0) throw IOException("adb forward failed: ${r.out.trim()}")
        return local
    }

    fun unforward(adb: Path, serial: String, local: Int): AdbResult = Adb.run(adb, removeArgs(serial, local), 15)

    // Sends the request and reads the answer whole. A client of HTTP 1.1,
    // since the pack's server speaks no upgrade, and no proxy: the address
    // is the loopback of this computer.
    // https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html#send
    fun send(local: Int, path: String, token: String, body: String? = null): HttpResponse<String> {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()
        val uri = URI("http", null, "127.0.0.1", local, path, null, null)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_S)).header("Authorization", token)
        val request = if (body == null) {
            builder.GET().build()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        }
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("the request was interrupted")
        }
    }

    // GET <list>: {"params":[{id, group, label, type, value, default, min,
    // max, restart, note}]}. A parameter of a type GutapK cannot edit is
    // left out.
    fun list(local: Int, pack: ServerPack, token: String): List<Param> {
        val path = pack.list ?: throw IOException("${pack.name} has no admin side")
        val response = send(local, path, token)
        if (response.statusCode() != 200) throw IOException("${pack.name} answered ${response.statusCode()} to the list: ${response.body().take(200)}")
        return parseParams(response.body())
    }

    // POST <set> with the changed values, in one list: the pack checks them
    // together, since a limit can depend on another value of the request.
    fun set(local: Int, pack: ServerPack, token: String, changes: List<Pair<String, Any>>): Answer {
        val path = pack.set ?: throw IOException("${pack.name} has no admin side")
        val response = send(local, path, token, body(changes))
        return parseAnswer(response.statusCode(), response.body())
    }

    internal fun parseParams(text: String): List<Param> {
        val root = Json.parse(text) as? Map<*, *> ?: throw IOException("the list is not an object")
        val items = root["params"] as? List<*> ?: throw IOException("the list has no params")
        return items.mapNotNull { item ->
            val p = item as? Map<*, *> ?: return@mapNotNull null
            val id = p["id"] as? String ?: return@mapNotNull null
            val type = p["type"] as? String ?: return@mapNotNull null
            if (type != "int" && type != "bool" && type != "string") return@mapNotNull null
            Param(
                id = id,
                group = p["group"] as? String ?: "",
                label = p["label"] as? String ?: id,
                type = type,
                value = p["value"],
                default = p["default"],
                min = (p["min"] as? Number)?.toLong(),
                max = (p["max"] as? Number)?.toLong(),
                restart = p["restart"] == true,
                note = (p["note"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }

    // The body of a set, [{"id":...,"value":...},...]: an int and a bool bare,
    // a string quoted.
    internal fun body(changes: List<Pair<String, Any>>): String =
        changes.joinToString(",", "[", "]") { (id, value) ->
            val text = when (value) {
                is Boolean, is Long, is Int -> value.toString()
                else -> quote(value.toString())
            }
            "{\"id\":" + quote(id) + ",\"value\":" + text + "}"
        }

    // 200 {"ok":true,"restarted":false,"applied":[{id, value}]} or
    // 400 {"error":"..."}. An answer that is neither is the pack's text as is.
    internal fun parseAnswer(status: Int, text: String): Answer {
        val root = runCatching { Json.parse(text) as? Map<*, *> }.getOrNull()
        val applied = (root?.get("applied") as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        val error = root?.get("error") as? String ?: if (status == 200) null else text.take(200).ifBlank { "HTTP $status" }
        return Answer(status, status == 200 && root?.get("ok") == true, error, root?.get("restarted") == true, applied)
    }

    // Escapes for a JSON string: the quote, the backslash and every control
    // character, as the JSON specification asks.
    // https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/commonMain/src/kotlinx/serialization/json/internal/StringOps.kt#printQuoted
    private fun toHexChar(i: Int): Char {
        val d = i and 0xf
        return if (d < 10) (d + '0'.code).toChar() else (d - 10 + 'a'.code).toChar()
    }

    private val ESCAPE_STRINGS: Array<String?> = arrayOfNulls<String>(93).apply {
        for (c in 0..0x1f) {
            this[c] = "\\u" + toHexChar(c shr 12) + toHexChar(c shr 8) + toHexChar(c shr 4) + toHexChar(c)
        }
        this['"'.code] = "\\\""
        this['\\'.code] = "\\\\"
        this['\t'.code] = "\\t"
        this['\b'.code] = "\\b"
        this['\n'.code] = "\\n"
        this['\r'.code] = "\\r"
        this[0x0c] = "\\f"
    }

    internal fun quote(value: String): String {
        val out = StringBuilder("\"")
        var lastPos = 0
        for (i in value.indices) {
            val c = value[i].code
            if (c < ESCAPE_STRINGS.size && ESCAPE_STRINGS[c] != null) {
                out.append(value, lastPos, i)
                out.append(ESCAPE_STRINGS[c])
                lastPos = i + 1
            }
        }
        out.append(value, lastPos, value.length)
        return out.append('"').toString()
    }
}
