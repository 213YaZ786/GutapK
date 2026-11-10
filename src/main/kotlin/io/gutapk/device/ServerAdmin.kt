package io.gutapk.device

import io.gutapk.core.offline.Inject
import io.gutapk.core.offline.OfflineBuild
import io.gutapk.tools.Json
import io.gutapk.tools.Storage
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

// What GET /status says: the mode of the last request (live, replay, miss or
// none), the recordings held, the overrides set and the origin each port
// serves. Param's job, for the state of the server.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#Param
data class Status(val mode: String, val entries: Int, val overrides: Int, val origins: Map<String, String>, val unknown: Map<String, Int> = emptyMap())

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

    // A header no browser sends to another origin without a CORS preflight,
    // which the phone's admin never grants: a page in a browser of this
    // computer cannot reach the forwarded admin port with it (Routes refuses
    // a request without it).
    // https://github.com/webpack/webpack-dev-server/blob/main/lib/Server.js#isValidHost
    const val ADMIN_HEADER = "X-GutapK-Admin"

    // What the zip of /export may hold, counted while it is read and never
    // trusted from its headers: the files, each file, the sum of them. The
    // download itself is bound too. The store of a phone holds recordings of
    // some megabytes, these limits only stop a zip that is a bomb.
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipSecureFile.java#MAX_FILE_COUNT
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipSecureFile.java#MAX_ENTRY_SIZE
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipSecureFile.java#MAX_FILE_COUNT_MSG
    // not ported: MIN_INFLATE_RATIO, GRACE_ENTRY_SIZE, graceEntrySize (ZipInputStream gives no compressed count while reading, so no ratio can be checked)
    // not ported: MAX_TEXT_SIZE, setMaxTextSize, getMaxTextSize (no text is extracted)
    // not ported: setMaxFileCount, getMaxFileCount, setMaxEntrySize, getMaxEntrySize, setMinInflateRatio (Limits is a parameter, not a global)
    // not ported: the constructors of ZipSecureFile, getInputStream, getName, InputStream (a zip is in memory, ThresholdInputStream below wraps its stream)
    // not ported: validateEntryNames (a second entry of one name overwrites the first, it makes no second file, and the count counts both)
    const val MAX_FILE_COUNT = 100_000L
    const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
    const val MAX_TOTAL_SIZE = 1024L * 1024 * 1024
    const val MAX_DOWNLOAD = 512L * 1024 * 1024
    const val MAX_FILE_COUNT_MSG =
        "The export holds more files than expected, it may be a zip bomb.\n" +
            "Limits: MAX_FILE_COUNT: %d"

    // The limits of one pass over a zip, the defaults above. A smaller set lets
    // a test check a limit without a huge zip.
    data class Limits(val maxFileCount: Long = MAX_FILE_COUNT, val maxEntrySize: Long = MAX_ENTRY_SIZE, val maxTotalSize: Long = MAX_TOTAL_SIZE)

    // The local port adb forwards to the phone's, found free by asking the
    // system for one.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#freePort
    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // The abstract socket the server listens on, read from the properties
    // file GutapK writes into the game, so the two sides cannot disagree.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forwardArgs
    fun socket(packageName: String): String =
        Inject.config(packageName, emptyMap()).lines().first { it.startsWith("admin=") }.removePrefix("admin=")

    // The line the provider prints, looked for as the VM service line is in the
    // app's output: one regex per line, the last match wins. The name is taken
    // only when it is the prefix, a dot and 32 lowercase hex, anything else is
    // not the game's.
    // https://github.com/flutter/flutter/blob/master/packages/flutter_tools/lib/src/protocol_discovery.dart#_getPatternMatch
    // https://github.com/flutter/flutter/blob/master/packages/flutter_tools/lib/src/protocol_discovery.dart#_getVmServiceUri
    // https://github.com/flutter/flutter/blob/master/packages/flutter_tools/lib/src/protocol_discovery.dart#_handleLine
    // not ported: _forwardPort, _stopScrapingLogs (the forward is made by forward below, and a dump is read once, not followed)
    // not ported: the device port check of _handleLine (the name is the only thing printed)
    private val kVMServiceMessageRegExp = Regex("""^\s*admin (\S+)\s*$""")

    private fun _getPatternMatch(line: String): MatchResult? = kVMServiceMessageRegExp.find(line)

    private fun _getVmServiceUri(line: String): String? {
        val match = _getPatternMatch(line)
        if (match != null) {
            return match.groupValues[1]
        }
        return null
    }

    private fun _handleLine(line: String, uri: String?): String? {
        val next = _getVmServiceUri(line)
        if (next == null) {
            return uri
        }
        return next
    }

    internal fun parseAdmin(text: String, prefix: String): String? {
        var uri: String? = null
        for (line in text.lines()) {
            uri = _handleLine(line, uri)
        }
        if (uri == null || !Regex(Regex.escape(prefix) + """\.[0-9a-f]{32}""").matches(uri)) return null
        return uri
    }

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forwardArgs
    fun forwardArgs(serial: String, local: Int, socket: String): List<String> = listOf("-s", serial, "forward", "tcp:$local", "localabstract:$socket")

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#removeArgs
    fun removeArgs(serial: String, local: Int): List<String> = listOf("-s", serial, "forward", "--remove", "tcp:$local")

    // What the PC asks the game's own process for its bound name: dumpsys reaches
    // the dump of the provider, inside the process that holds the socket.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L2753-L2755
    // not ported: dump (it is CacheProvider.dump in the game, this is the command that calls it)
    fun dumpArgs(serial: String, packageName: String): List<String> =
        listOf("-s", serial, "shell", "dumpsys", "activity", "provider", Adb.quote(packageName + "/" + Inject.PROVIDER))

    // The name the running game bound, read from the game's own process.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forward
    fun name(adb: Path, serial: String, packageName: String): String {
        val r = Adb.run(adb, dumpArgs(serial, packageName), 15)
        return parseAdmin(r.out, socket(packageName)) ?: throw IOException("the game is not running, or its offline server has no admin socket")
    }

    // A forward made by forward, with what it takes to make it again once the
    // game is launched anew and binds another name.
    private class Link(val route: String, val lookup: () -> String, val rebind: (Int, String) -> Boolean)

    private val links = ConcurrentHashMap<Int, Link>()

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#forward
    fun forward(adb: Path, serial: String, packageName: String): Int {
        val local = freePort()
        val name = name(adb, serial, packageName)
        val r = Adb.run(adb, forwardArgs(serial, local, name), 15)
        if (r.code != 0) throw IOException("adb forward failed: ${r.out.trim()}")
        links[local] = Link(
            name,
            { name(adb, serial, packageName) },
            { port, now -> Adb.run(adb, forwardArgs(serial, port, now), 15).code == 0 },
        )
        return local
    }

    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/device/ServerAdmin.kt#unforward
    fun unforward(adb: Path, serial: String, local: Int): AdbResult {
        links.remove(local)
        return Adb.run(adb, removeArgs(serial, local), 15)
    }

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
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_S)).header(ADMIN_HEADER, "1")
        val request = if (!post) {
            builder.GET().build()
        } else {
            builder.header("Content-Type", "application/json").POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        }
        return transmit(local, client, request, handler)
    }

    // The request, sent once more when the first failed because the game was
    // launched anew: its name changes at each launch, so the forward of the
    // dialog goes stale, and a request to the dead name never reached the phone.
    // Any other failure is the first one.
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#intercept
    // not ported: followUpRequest, buildRedirectRequest, retryAfter (a redirect or a challenge is not a failure to communicate here)
    // not ported: the loop of intercept and MAX_FOLLOW_UPS (one more try, never a chain of them)
    private fun <T> transmit(call: Int, client: HttpClient, request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        try {
            try {
                return client.send(request, handler)
            } catch (e: IOException) {
                if (!recover(e, call)) throw e
            }
            return client.send(request, handler)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("the request was interrupted")
        }
    }

    // Report and attempt to recover from a failure to communicate with the
    // phone: true when the forward now names the name the game binds, false when
    // the failure is permanent.
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#recover
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#requestIsOneShot
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#isRecoverable
    private fun recover(e: IOException, call: Int): Boolean {
        val requestSendStarted = e !is HttpConnectTimeoutException

        // We can't send the request body again.
        if (requestSendStarted && requestIsOneShot(e)) return false

        // This exception is fatal.
        if (!isRecoverable(e, requestSendStarted)) return false

        // No more routes to attempt.
        if (!retryAfterFailure(call)) return false

        // For failure recovery, use the same route selector with a new connection.
        return true
    }

    // The route left to attempt: the name the game binds now, when it is not the
    // one the forward holds. Then the forward is made again over it, adb forward
    // rebinds the same local port. A port forward did not make has no game to ask.
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#recover
    private fun retryAfterFailure(call: Int): Boolean {
        val link = links[call] ?: return false
        val route = try {
            link.lookup()
        } catch (e: IOException) {
            return false
        }
        if (route == link.route || !link.rebind(call, route)) return false
        links[call] = Link(route, link.lookup, link.rebind)
        return true
    }

    // A body here is bytes or a string held whole, it can always be sent again.
    private fun requestIsOneShot(e: IOException): Boolean {
        return e is FileNotFoundException
    }

    private fun isRecoverable(e: IOException, requestSendStarted: Boolean): Boolean {
        // If there was a protocol problem, don't recover.
        if (e is ProtocolException) {
            return false
        }

        // If there was an interruption don't recover, but if there was a timeout connecting to a route
        // we should try the next route (if there is one).
        if (e is InterruptedIOException) {
            return e is SocketTimeoutException && !requestSendStarted
        }

        // The client's request timeout fires after the request left: the phone may have it.
        if (e is HttpTimeoutException) {
            return e is HttpConnectTimeoutException
        }

        // Not ported: the SSL cases (the forward is plain http on the loopback).
        // Unless it is one we know we should not retry, we return true and try a new route.
        return true
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
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_S)).header(ADMIN_HEADER, "1")
            .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val response = transmit(local, client, request, HttpResponse.BodyHandlers.ofString())
        return parseAnswer(response.statusCode(), response.body())
    }

    // The game reads its progress at its login only: it is stopped and launched
    // again, as the app actions do, once its progress was replaced. The new
    // process binds another name: the next request finds it and makes the forward again.
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

    // A zip stream that counts what it hands out and refuses a zip over a limit:
    // more files, a file larger, or all of them larger. The count is made while
    // reading, a header can claim any size.
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#checkThreshold
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#getNextEntry
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#read
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#skip
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#getEntryName
    // not ported: setGuardState, setEntry (the guard is always on, and the entry is set by getNextEntry)
    // not ported: the MIN_INFLATE_RATIO check of checkThreshold and the GRACE_ENTRY_SIZE test (no compressed count while reading)
    // not ported: the NotOfficeXmlFileException of getNextEntry (the file is not an office file, a bad zip is the ZipException as it is)
    private class ThresholdInputStream(private val zis: ZipInputStream, private val limits: Limits) : FilterInputStream(zis) {
        private var entry: ZipEntry? = null
        private var entryCount = 0L
        private var payloadSize = 0L
        private var totalSize = 0L
        private var uncheckedBytes = 0

        override fun read(): Int {
            val b = super.read()
            if (b > -1) {
                payloadSize++
                totalSize++
                if (++uncheckedBytes >= SINGLE_BYTE_CHECK_INTERVAL) {
                    checkThreshold()
                }
            } else if (uncheckedBytes > 0) {
                // check the bytes read since the last check before reporting the end of the entry
                checkThreshold()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val cnt = super.read(b, off, len)
            if (cnt > -1) {
                payloadSize += cnt
                totalSize += cnt
                checkThreshold()
            }
            return cnt
        }

        override fun skip(n: Long): Long {
            val cnt = maxOf(0L, super.skip(n))
            if (cnt > 0) {
                payloadSize += cnt
                totalSize += cnt
                checkThreshold()
            }
            return cnt
        }

        private fun checkThreshold() {
            uncheckedBytes = 0

            // check the file size first, in case we are working on uncompressed streams
            if (payloadSize > limits.maxEntrySize) {
                throw IOException("Zip bomb detected! The export would exceed the max size of a file in the zip.\nUncompressed size: $payloadSize\nLimits: MAX_ENTRY_SIZE: ${limits.maxEntrySize}, Entry: ${getEntryName()}")
            }
            if (totalSize > limits.maxTotalSize) {
                throw IOException("Zip bomb detected! The export would exceed the max size of the expanded data in the zip.\nUncompressed size: $totalSize\nLimits: MAX_TOTAL_SIZE: ${limits.maxTotalSize}, Entry: ${getEntryName()}")
            }
        }

        private fun getEntryName(): String = entry?.name ?: "not set"

        fun getNextEntry(): ZipEntry? {
            try {
                uncheckedBytes = 0
                payloadSize = 0
                entry = zis.nextEntry
                if (entry != null) {
                    if (++entryCount > limits.maxFileCount) {
                        throw IOException(String.format(MAX_FILE_COUNT_MSG, limits.maxFileCount))
                    }
                }
                return entry
            } catch (e: EOFException) {
                return null
            }
        }
    }

    private const val SINGLE_BYTE_CHECK_INTERVAL = 1024

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
        val response = send(local, "/export", null, false, HttpResponse.BodyHandlers.ofInputStream())
        // The download is read through a bound: a phone that never stops sending fills no memory.
        val zipFile = response.body().use { it.readNBytes(MAX_DOWNLOAD.toInt() + 1) }
        if (response.statusCode() != 200) throw IOException("the server answered ${response.statusCode()} to the export: ${String(zipFile, Charsets.UTF_8).take(200)}")
        if (zipFile.size > MAX_DOWNLOAD) throw IOException("the export is larger than $MAX_DOWNLOAD bytes")
        // Every limit is checked on the whole zip before the folder is emptied.
        checkThreshold(zipFile)
        if (!Storage.deleteTree(into, into.toAbsolutePath().parent)) throw IOException("could not empty $into")
        Files.createDirectories(into)
        return extractFileFromZip(zipFile, into)
    }

    // The loop of extractFileFromZip over every entry. A name with a folder in
    // it, or a dot name, is refused before that entry is written: the files of
    // the store are plain names, anything else would leave the folder (zip slip).
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#extractFileFromZip
    // The limits hold here too, counted as the entries are written.
    internal fun extractFileFromZip(zipFile: ByteArray, path: Path, limits: Limits = Limits()): Int {
        var recordings = 0
        ThresholdInputStream(ZipInputStream(BufferedInputStream(ByteArrayInputStream(zipFile))), limits).use { inZip ->
            var ze: ZipEntry?
            while (inZip.getNextEntry().also { ze = it } != null) {
                val nameInZip = ze!!.name
                checkName(nameInZip)
                Files.copy(inZip, path.resolve(nameInZip), StandardCopyOption.REPLACE_EXISTING)
                if (nameInZip.endsWith(OfflineBuild.ENTRY)) recordings++
            }
        }
        return recordings
    }

    // The files of the store are plain names, anything else would leave the folder (zip slip).
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#extractFileFromZip
    // not ported: extractFileFromZip, acceptStream, addFileToZip, zipContainsFile, isValidZipFile (extractFileFromZip is the function above, calling this, the others are not needed)
    private fun checkName(nameInZip: String) {
        if (nameInZip.isEmpty() || nameInZip == "." || nameInZip == ".." || nameInZip.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IOException("the export holds an entry that is not a plain file name: " + nameInZip.take(80))
        }
    }

    // The whole zip read once before anything is emptied or written: every name,
    // the count of the files, each file and their sum, all counted as the bytes
    // come out. A zip over a limit leaves the folder as it was.
    // https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipArchiveThresholdInputStream.java#checkThreshold
    internal fun checkThreshold(zipFile: ByteArray, limits: Limits = Limits()) {
        ThresholdInputStream(ZipInputStream(BufferedInputStream(ByteArrayInputStream(zipFile))), limits).use { inZip ->
            var ze: ZipEntry?
            while (inZip.getNextEntry().also { ze = it } != null) {
                checkName(ze!!.name)
                inZip.copyTo(OutputStream.nullOutputStream())
            }
        }
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
            // The commands the game sent that nothing recorded and nothing saved, with their counts.
            unknown = (root["unknown"] as? Map<*, *>).orEmpty().entries.mapNotNull { (name, count) -> (name as? String)?.let { n -> (count as? Number)?.let { n to it.toInt() } } }.toMap(),
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
