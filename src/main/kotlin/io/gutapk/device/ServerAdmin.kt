package io.gutapk.device

import io.gutapk.core.offline.Inject
import io.gutapk.core.offline.OfflineBuild
import io.gutapk.tools.Json
import io.gutapk.tools.Storage
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

// What GET /status says: the mode of the last request (live, replay, miss or
// none), the recordings held, the overrides set and the origin each port
// serves. Param's job, for the state of the server.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#Param
data class Status(val mode: String, val entries: Int, val overrides: Int, val origins: Map<String, String>)

// One JSON leaf of the latest answer of a method, origin and path: key is the
// method, a space, the origin and the path, pointer an RFC 6901 pointer, type
// number, bool or string, override the value the user set, when there is one.
// Param, for a leaf.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#Param
data class Value(val key: String, val pointer: String, val type: String, val value: Any?, val override: Any?)

// One value to set: the key and pointer of a Value and the typed value. The
// (id, value) pair of the old set, with the pointer that finds the leaf.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#set
data class Change(val key: String, val pointer: String, val value: Any)

// What the server answered to a set: 200 with the count it took, or 400 with
// an error text.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#Answer
data class Answer(val status: Int, val ok: Boolean, val error: String?, val count: Int)

// The admin side of the offline server inside a game: a forward from a local
// port to its abstract socket, and the requests over it. The server answers
// only the adb daemon and the app itself, so nothing here is reachable from
// the network.
// not ported: Param (the leaves replace the list of parameters of a server app)
// not ported: list (status and values take its place, the server has no params route)
// not ported: printQuoted (it is quote below, with the same table and loop)
object ServerAdmin {
    private const val TIMEOUT_S = 10L

    // The local port adb forwards to the phone's, found free by asking the
    // system for one.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#freePort
    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // The abstract socket the server listens on, read from the properties
    // file GutapK writes into the game, so the two sides cannot disagree.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forwardArgs
    fun socket(packageName: String): String =
        Inject.config(packageName, emptyMap()).lines().first { it.startsWith("admin=") }.removePrefix("admin=")

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forwardArgs
    fun forwardArgs(serial: String, local: Int, socket: String): List<String> = listOf("-s", serial, "forward", "tcp:$local", "localabstract:$socket")

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#removeArgs
    fun removeArgs(serial: String, local: Int): List<String> = listOf("-s", serial, "forward", "--remove", "tcp:$local")

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forward
    fun forward(adb: Path, serial: String, packageName: String): Int {
        val local = freePort()
        val r = Adb.run(adb, forwardArgs(serial, local, socket(packageName)), 15)
        if (r.code != 0) throw IOException("adb forward failed: ${r.out.trim()}")
        return local
    }

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#unforward
    fun unforward(adb: Path, serial: String, local: Int): AdbResult = Adb.run(adb, removeArgs(serial, local), 15)

    // Sends the request and reads the answer whole. A client of HTTP 1.1,
    // since the server speaks no upgrade, and no proxy: the address is the
    // loopback of this computer. A body, or post, makes a POST.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#send
    fun send(local: Int, path: String, body: String? = null, post: Boolean = body != null): HttpResponse<String> =
        send(local, path, body, post, HttpResponse.BodyHandlers.ofString())

    // The same request with the answer read by the given handler: the zip of
    // /export is bytes, not text.
    fun <T> send(local: Int, path: String, body: String?, post: Boolean, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()
        val uri = URI("http", null, "127.0.0.1", local, path, null, null)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_S))
        val request = if (!post) {
            builder.GET().build()
        } else {
            builder.header("Content-Type", "application/json").POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        }
        try {
            return client.send(request, handler)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("the request was interrupted")
        }
    }

    // GET /status: {"mode","entries","overrides","origins":{"<port>":"<origin>"}}
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#list
    fun status(local: Int): Status {
        val response = send(local, "/status")
        if (response.statusCode() != 200) throw IOException("the server answered ${response.statusCode()} to the status: ${response.body().take(200)}")
        return parseStatus(response.body())
    }

    // GET /values: every JSON leaf of the latest answers, see Value.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#list
    fun values(local: Int): List<Value> {
        val response = send(local, "/values")
        if (response.statusCode() != 200) throw IOException("the server answered ${response.statusCode()} to the values: ${response.body().take(200)}")
        return parseValues(response.body())
    }

    // POST /set with the changed values, in one list.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#set
    fun set(local: Int, changes: List<Change>): Answer {
        val response = send(local, "/set", body(changes))
        return parseAnswer(response.statusCode(), response.body())
    }

    // POST /reset-overrides and POST /clear: {"ok":true}.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#set
    fun reset(local: Int): Answer = send(local, "/reset-overrides", post = true).let { parseAnswer(it.statusCode(), it.body()) }

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#set
    fun clear(local: Int): Answer = send(local, "/clear", post = true).let { parseAnswer(it.statusCode(), it.body()) }

    // GET /state: the progress the local game server keeps, its BSON bytes. Null
    // when the phone has no local game server (404).
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#send
    fun state(local: Int): ByteArray? {
        val response = send(local, "/state", null, false, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw IOException("the server answered ${response.statusCode()} to the state: ${String(response.body(), Charsets.UTF_8).take(200)}")
        return response.body()
    }

    // PUT /state with the BSON bytes of the new progress. The server keeps it at
    // once, the game reads it at its next login.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#send
    fun putState(local: Int, bytes: ByteArray): Answer {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()
        val uri = URI("http", null, "127.0.0.1", local, "/state", null, null)
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_S))
            .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("the request was interrupted")
        }
        return parseAnswer(response.statusCode(), response.body())
    }

    // The game reads its progress at its login only: it is stopped and launched
    // again, as the app actions do, once its progress was replaced. The forward
    // names the socket, it reaches the new process when its server is up.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/device/AppActions.kt#command
    // not ported: permission, parseUserState (no permission is changed, the app is only restarted)
    fun restart(adb: Path, serial: String, packageName: String): String? {
        for (action in listOf(AppAction.FORCE_STOP, AppAction.LAUNCH)) {
            val r = AppActions.run(adb, serial, AppActions.command(action, packageName, 0))
            val failed = AppActions.failed(r.out) ?: if (r.code != 0) r.out.trim().take(200) else null
            if (failed != null) return failed
        }
        return null
    }

    // GET /export: the zip of every file of the store, unzipped into the given
    // folder, which is emptied first. Returns the recordings, the files of
    // metadata. The folder is emptied only once the zip is in hand, so a phone
    // that does not answer leaves the earlier recordings.
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#extractFileFromZip
    // not ported: the StoredFileHelper and nameInZip overloads of extractFileFromZip (the zip is in memory and every entry is taken, not one by name)
    // not ported: InputStreamConsumer, acceptStream (Files.copy is called in the loop)
    // not ported: addFileToZip, OutputStreamConsumer (the zip is written on the phone, in Routes.java)
    // not ported: zipContainsFile, isValidZipFile (a zip that is not one fails the loop)
    fun export(local: Int, into: Path): Int {
        val response = send(local, "/export", null, false, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw IOException("the server answered ${response.statusCode()} to the export: ${String(response.body(), Charsets.UTF_8).take(200)}")
        val zipFile = response.body()
        if (!Storage.deleteTree(into, into.toAbsolutePath().parent)) throw IOException("could not empty $into")
        Files.createDirectories(into)
        return extractFileFromZip(zipFile, into)
    }

    // The loop of extractFileFromZip over every entry. A name with a folder in
    // it, or a dot name, is refused before that entry is written: the files of
    // the store are plain names, anything else would leave the folder (zip slip).
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#extractFileFromZip
    internal fun extractFileFromZip(zipFile: ByteArray, path: Path): Int {
        var recordings = 0
        ZipInputStream(BufferedInputStream(ByteArrayInputStream(zipFile))).use { inZip ->
            var ze: ZipEntry?
            while (inZip.nextEntry.also { ze = it } != null) {
                val nameInZip = ze!!.name
                if (nameInZip.isEmpty() || nameInZip == "." || nameInZip == ".." || nameInZip.any { it == '/' || it == '\\' || it == '\u0000' }) {
                    throw IOException("the export holds an entry that is not a plain file name: " + nameInZip.take(80))
                }
                Files.copy(inZip, path.resolve(nameInZip), StandardCopyOption.REPLACE_EXISTING)
                if (nameInZip.endsWith(OfflineBuild.ENTRY)) recordings++
            }
        }
        return recordings
    }

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#parseParams
    internal fun parseStatus(text: String): Status {
        val root = Json.parse(text) as? Map<*, *> ?: throw IOException("the status is not an object")
        val origins = (root["origins"] as? Map<*, *>).orEmpty().entries.mapNotNull { (port, origin) -> (port as? String)?.let { p -> (origin as? String)?.let { p to it } } }
        return Status(
            mode = root["mode"] as? String ?: "none",
            entries = (root["entries"] as? Number)?.toInt() ?: 0,
            overrides = (root["overrides"] as? Number)?.toInt() ?: 0,
            origins = origins.toMap(),
        )
    }

    // A leaf of a type GutapK cannot edit is left out.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#parseParams
    internal fun parseValues(text: String): List<Value> {
        val items = Json.parse(text) as? List<*> ?: throw IOException("the values are not a list")
        return items.mapNotNull { item ->
            val v = item as? Map<*, *> ?: return@mapNotNull null
            val key = v["key"] as? String ?: return@mapNotNull null
            val pointer = v["pointer"] as? String ?: return@mapNotNull null
            val type = v["type"] as? String ?: return@mapNotNull null
            if (type != "number" && type != "bool" && type != "string") return@mapNotNull null
            Value(key, pointer, type, v["value"], v["override"])
        }
    }

    // The body of a set, [{"key":...,"pointer":...,"value":...},...]: a number
    // and a bool bare, a string quoted.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#body
    internal fun body(changes: List<Change>): String =
        changes.joinToString(",", "[", "]") { (key, pointer, value) ->
            val text = when (value) {
                is Boolean, is Long, is Int -> value.toString()
                is Double -> if (value.isFinite()) value.toString() else throw IllegalArgumentException("not a number: $value")
                else -> quote(value.toString())
            }
            "{\"key\":" + quote(key) + ",\"pointer\":" + quote(pointer) + ",\"value\":" + text + "}"
        }

    // 200 {"set":2} or {"ok":true}, 400 {"error":"bad request","message":"..."}.
    // An answer that is neither is the server's text as is.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#parseAnswer
    internal fun parseAnswer(status: Int, text: String): Answer {
        val root = runCatching { Json.parse(text) as? Map<*, *> }.getOrNull()
        val error = (root?.get("message") ?: root?.get("error")) as? String ?: if (status == 200) null else text.take(200).ifBlank { "HTTP $status" }
        val count = (root?.get("set") as? Number)?.toInt() ?: 0
        return Answer(status, status == 200 && (root?.get("ok") == true || root?.containsKey("set") == true), error, count)
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
