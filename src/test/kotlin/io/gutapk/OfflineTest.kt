package io.gutapk

// The offline cache server runs on the JVM here, against a stand-in origin. The
// protocols are existing ones: keep-alive and request parsing as NanoHTTPD's own
// tests check them
// https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/test/java/org/nanohttpd/junit/protocols/http/HttpKeepAliveTest.java
// https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/test/java/org/nanohttpd/junit/protocols/http/HttpServerTest.java
// and JSON Pointer as RFC 6901 https://www.rfc-editor.org/rfc/rfc6901

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.gutapk.offline.CacheInterceptor
import io.gutapk.offline.Offline
import io.gutapk.offline.Overrides
import io.gutapk.offline.Peer
import io.gutapk.offline.ProxyResponseRenderer
import io.gutapk.device.ServerAdmin
import io.gutapk.offline.Routes
import io.gutapk.offline.Seed
import io.gutapk.offline.Server
import io.gutapk.offline.Store
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTest {

    private class Reply(val code: Int, val body: String, val headers: Map<String, String>) {
        val mode: String? get() = headers["x-gutapk"]
        val json: JSONObject get() = JSONObject(body)
    }

    /** A stand-in for the game's real server, on loopback, that can be stopped to go offline. */
    private class Upstream {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        @Volatile var seenAcceptEncoding: String? = null
        @Volatile var seenHost: String? = null
        val port: Int get() = server.address.port

        init {
            server.createContext("/config") { ex ->
                note(ex)
                val query = ex.requestURI.rawQuery ?: ""
                val body = """{"level":3,"name":"x","flags":{"on":true},"items":[1,2],"q":"$query"}"""
                reply(ex, 200, "application/json", body.toByteArray())
            }
            server.createContext("/text") { ex ->
                note(ex)
                reply(ex, 200, "text/plain", "plain".toByteArray())
            }
            server.createContext("/echo") { ex ->
                note(ex)
                val sent = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                reply(ex, 200, "application/json", """{"echo":"$sent","n":${sent.length}}""".toByteArray())
            }
            server.createContext("/gz") { ex ->
                note(ex)
                val raw = ByteArrayOutputStream()
                GZIPOutputStream(raw).use { it.write("""{"level":5}""".toByteArray()) }
                ex.responseHeaders.add("Content-Encoding", "gzip")
                reply(ex, 200, "application/json", raw.toByteArray())
            }
            server.createContext("/moved") { ex ->
                note(ex)
                ex.responseHeaders.add("Location", "http://127.0.0.1:$port/text?x=1")
                reply(ex, 302, "text/plain", ByteArray(0))
            }
            server.createContext("/away") { ex ->
                note(ex)
                ex.responseHeaders.add("Location", "https://other.example/play")
                reply(ex, 301, "text/plain", ByteArray(0))
            }
            server.createContext("/out") { ex ->
                note(ex)
                ex.responseHeaders.add("Location", "https://other.example.net/")
                reply(ex, 302, "text/plain", ByteArray(0))
            }
            server.createContext("/gone") { ex ->
                note(ex)
                reply(ex, 404, "application/json", """{"error":"gone"}""".toByteArray())
            }
            server.start()
        }

        private fun note(ex: HttpExchange) {
            hits.incrementAndGet()
            seenAcceptEncoding = ex.requestHeaders.getFirst("Accept-Encoding")
            seenHost = ex.requestHeaders.getFirst("Host")
        }

        private fun reply(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        fun stop() = server.stop(0)
    }

    /** The cache server in front of an [Upstream], and its admin routes on a second loopback port. */
    private class Rig(val dir: File = Files.createTempDirectory("gutapk-offline").toFile()) {
        val upstream = Upstream()
        val origin = "http://127.0.0.1:${upstream.port}"
        val cache = Store(File(dir, "cache"))
        val overrides = Overrides(File(dir, "overrides.json"))
        val front = Server("127.0.0.1", 0, CacheInterceptor(origin, cache, overrides, ProxyResponseRenderer(), mapOf(origin to "http://0.0.0.0:0000020001", "https://other.example" to "http://127.0.0.1:20002")))
        val admin: Server

        init {
            front.start()
            admin = Server("127.0.0.1", 0, Routes(cache, overrides, TreeMap(mapOf(front.listeningPort to origin))))
            admin.start()
        }

        fun key(path: String, method: String = "GET") = "$method $origin$path"

        fun get(path: String, headers: Map<String, String> = emptyMap()) = call(front.listeningPort, "GET", path, null, headers)

        fun post(path: String, body: String) = call(front.listeningPort, "POST", path, body)

        fun admin(method: String, path: String, body: String? = null) = call(admin.listeningPort, method, path, body)

        fun close() {
            front.stop()
            admin.stop()
            upstream.stop()
            dir.deleteRecursively()
        }
    }

    private companion object {
        fun call(port: Int, method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Reply {
            val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 20000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val seen = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null) seen[k.lowercase()] = v.last() }
            return Reply(code, text, seen)
        }

        fun raw(port: Int, request: String): String {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 10000
                s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                s.getOutputStream().flush()
                return s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            }
        }

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }

    private inline fun rig(block: (Rig) -> Unit) {
        val r = Rig()
        try {
            block(r)
        } finally {
            r.close()
        }
    }

    @Test
    fun liveAnswerIsProxiedWithTheOriginsHostAndIdentityEncoding() = rig { r ->
        val a = r.get("/config?a=1", mapOf("Accept-Encoding" to "gzip"))
        assertEquals(200, a.code)
        assertEquals("live", a.mode)
        assertEquals("a=1", a.json.getString("q"))
        assertEquals(3, a.json.getInt("level"))
        assertTrue(a.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("identity", r.upstream.seenAcceptEncoding)
        assertEquals("127.0.0.1:${r.upstream.port}", r.upstream.seenHost)
    }

    @Test
    fun replayAnswersFromTheExactRequestWhenTheOriginIsGone() = rig { r ->
        val live = r.get("/config?a=1")
        r.upstream.stop()
        val replay = r.get("/config?a=1")
        assertEquals("replay", replay.mode)
        assertEquals(200, replay.code)
        assertEquals(live.body, replay.body)
        assertEquals(live.headers["content-type"], replay.headers["content-type"])
    }

    @Test
    fun exactBeatsLatestAndLatestBeatsMiss() = rig { r ->
        r.get("/config?a=1")
        r.get("/config?a=2")
        r.upstream.stop()
        assertEquals("a=1", r.get("/config?a=1").json.getString("q"))
        val latest = r.get("/config?a=3")
        assertEquals("replay", latest.mode)
        assertEquals("a=2", latest.json.getString("q"))
        val miss = r.get("/never")
        assertEquals(502, miss.code)
        assertEquals("miss", miss.mode)
        assertTrue(miss.json.has("error"))
    }

    @Test
    fun anErrorAnswerPassesLiveAndIsNotRecorded() = rig { r ->
        val gone = r.get("/gone")
        assertEquals(404, gone.code)
        assertEquals("live", gone.mode)
        r.upstream.stop()
        assertEquals("miss", r.get("/gone").mode)
    }

    @Test
    fun requestBodiesKeyTheRecordedAnswers() = rig { r ->
        r.post("/echo", "alpha")
        r.post("/echo", "beta")
        r.upstream.stop()
        assertEquals("alpha", r.post("/echo", "alpha").json.getString("echo"))
        assertEquals("beta", r.post("/echo", "beta").json.getString("echo"))
        val other = r.post("/echo", "gamma")
        assertEquals("replay", other.mode)
        assertEquals("beta", other.json.getString("echo"))
    }

    // Jungle Heat 2.2.7 posts every command to /?r=<random>, the command in
    // the body. The latest answer was a ping's, sent back to its login.
    // https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/verification/NearMissCalculator.java#findNearestTo
    @Test
    fun aRequestNeverSeenGetsTheAnswerToTheNearestOne() = rig { r ->
        r.post("/echo?r=103634281", "cmd=login&user=7&t=100")
        r.post("/echo?r=574844464", "cmd=ping&t=101")
        r.upstream.stop()
        val login = r.post("/echo?r=25577140", "cmd=login&user=7&t=205")
        assertEquals("replay", login.mode)
        assertEquals("cmd=login&user=7&t=100", login.json.getString("echo"))
        assertEquals("cmd=ping&t=101", r.post("/echo?r=1206760692", "cmd=ping&t=206").json.getString("echo"))
    }

    @Test
    fun overridesApplyToLiveAndReplayedJson() = rig { r ->
        r.get("/config")
        val key = r.key("/config")
        val set = r.admin(
            "POST",
            "/set",
            """[{"key":"$key","pointer":"/level","value":9},{"key":"$key","pointer":"/flags/on","value":false},""" +
                """{"key":"$key","pointer":"/items/1","value":7},{"key":"$key","pointer":"/name","value":"changed"}]""",
        )
        assertEquals(200, set.code)
        assertEquals(4, set.json.getInt("set"))
        val live = r.get("/config")
        assertEquals("live", live.mode)
        assertEquals(9, live.json.getInt("level"))
        assertFalse(live.json.getJSONObject("flags").getBoolean("on"))
        assertEquals(7, live.json.getJSONArray("items").getInt(1))
        assertEquals("changed", live.json.getString("name"))
        r.upstream.stop()
        val replay = r.get("/config")
        assertEquals("replay", replay.mode)
        assertEquals(9, replay.json.getInt("level"))
        assertEquals(1, replay.json.getJSONArray("items").getInt(0))
        assertEquals(7, replay.json.getJSONArray("items").getInt(1))
        r.admin("POST", "/reset-overrides")
        assertEquals(3, r.get("/config").json.getInt("level"))
    }

    @Test
    fun aBodyThatIsNotJsonPassesUnchanged() = rig { r ->
        r.get("/text")
        r.admin("POST", "/set", """{"key":"${r.key("/text")}","pointer":"/x","value":1}""")
        assertEquals("plain", r.get("/text").body)
        r.upstream.stop()
        val replay = r.get("/text")
        assertEquals("replay", replay.mode)
        assertEquals("plain", replay.body)
        assertTrue(replay.headers["content-type"]!!.startsWith("text/plain"))
    }

    @Test
    fun aGzipAnswerIsDecodedBeforeItIsRecordedAndOverridden() = rig { r ->
        r.admin("POST", "/set", """{"key":"${r.key("/gz")}","pointer":"/level","value":6}""")
        val live = r.get("/gz")
        assertEquals(6, live.json.getInt("level"))
        assertNull(live.headers["content-encoding"])
        r.upstream.stop()
        assertEquals(6, r.get("/gz").json.getInt("level"))
    }

    @Test
    fun webSocketUpgradeAndChunkedBodiesAreRefusedClearly() = rig { r ->
        val ws = raw(r.front.listeningPort, "GET /config HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: close\r\n\r\n")
        assertTrue(ws.startsWith("HTTP/1.1 501"), ws)
        assertTrue(ws.contains("\"error\":\"websocket\""), ws)
        val chunked = raw(r.front.listeningPort, "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n")
        assertTrue(chunked.startsWith("HTTP/1.1 501"), chunked)
        assertEquals(0, r.upstream.hits.get())
    }

    @Test
    fun oneConnectionServesSeveralRequestsAndAnOversizedHeaderIsRefused() = rig { r ->
        val two = raw(
            r.front.listeningPort,
            "GET /config?a=1 HTTP/1.1\r\nHost: x\r\n\r\nGET /config?a=2 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n",
        )
        assertEquals(2, Regex("HTTP/1.1 200 OK").findAll(two).count(), two)
        assertTrue(two.contains("a=1") && two.contains("a=2"))
        assertTrue(two.contains("Content-Length: "))
        val huge = raw(r.front.listeningPort, "GET /config HTTP/1.1\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n")
        assertTrue(huge.startsWith("HTTP/1.1 431"), huge.take(40))
    }

    @Test
    fun adminReportsStatusEntriesAndValues() = rig { r ->
        r.get("/config?a=1")
        r.get("/text")
        val status = r.admin("GET", "/status").json
        assertEquals(CacheInterceptor.lastSeen, status.getString("mode"))
        assertEquals(2, status.getInt("entries"))
        assertEquals(r.origin, status.getJSONObject("origins").getString(r.front.listeningPort.toString()))

        val entries = JSONArray(r.admin("GET", "/entries").body)
        assertEquals(2, entries.length())
        val first = (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("url").endsWith("/config?a=1") }
        assertEquals(r.key("/config"), first.getString("key"))
        assertEquals(200, first.getInt("status"))

        r.admin("POST", "/set", """{"key":"${r.key("/config")}","pointer":"/level","value":11}""")
        val values = JSONArray(r.admin("GET", "/values").body)
        val leaves = (0 until values.length()).map { values.getJSONObject(it) }
        val level = leaves.first { it.getString("pointer") == "/level" }
        assertEquals("number", level.getString("type"))
        assertEquals(3, level.getInt("value"))
        assertEquals(11, level.getInt("override"))
        assertEquals("bool", leaves.first { it.getString("pointer") == "/flags/on" }.getString("type"))
        assertEquals("string", leaves.first { it.getString("pointer") == "/name" }.getString("type"))
        assertEquals(2, leaves.first { it.getString("pointer") == "/items/1" }.getInt("value"))
        assertFalse(leaves.any { it.getString("key") == r.key("/text") }, "a text body has no JSON leaves")
        assertFalse(leaves.first { it.getString("pointer") == "/name" }.has("override"))
    }

    @Test
    fun adminSetClearAndUnknownRoutes() = rig { r ->
        r.get("/config")
        assertEquals(400, r.admin("POST", "/set", "not json").code)
        assertEquals(400, r.admin("POST", "/set", """{"key":"k","pointer":"no-slash","value":1}""").code)
        assertEquals(400, r.admin("POST", "/set", """{"pointer":"/a","value":1}""").code)
        assertEquals(405, r.admin("GET", "/set").code)
        assertEquals(404, r.admin("GET", "/nowhere").code)
        assertEquals(1, r.admin("GET", "/status").json.getInt("entries"))
        assertEquals(200, r.admin("POST", "/clear").code)
        assertEquals(0, r.admin("GET", "/status").json.getInt("entries"))
        r.upstream.stop()
        assertEquals("miss", r.get("/config").mode)
    }

    @Test
    fun onlyAdbAndTheAppItselfMayUseTheAdminSocket() {
        assertTrue(Peer(1, 2000, 2000).isAllowed(10234))
        assertTrue(Peer(1, 10234, 10234).isAllowed(10234))
        assertFalse(Peer(1, 10235, 10235).isAllowed(10234))
        assertFalse(Peer(1, 0, 0).isAllowed(10234))
    }

    @Test
    fun jsonPointersUnescapeAndWalkArrays() {
        assertEquals(listOf("a", "b~c", "d/e"), Overrides.compile("/a/b~0c/d~1e"))
        assertEquals(listOf(""), Overrides.compile("/"))
        assertTrue(Overrides.compile("").isEmpty())
        assertFailsWith<IllegalArgumentException> { Overrides.compile("a/b") }
        val doc = JSONObject("""{"a":[{"b":1},{"b":2}],"x/y":{"~":0}}""")
        assertTrue(Overrides.replace(doc, Overrides.compile("/a/1/b"), 5))
        assertTrue(Overrides.replace(doc, Overrides.compile("/x~1y/~0"), 8))
        assertFalse(Overrides.replace(doc, Overrides.compile("/a/2/b"), 5))
        assertFalse(Overrides.replace(doc, Overrides.compile("/a/01/b"), 5))
        assertFalse(Overrides.replace(doc, Overrides.compile("/missing/b"), 5))
        assertEquals(5, doc.getJSONArray("a").getJSONObject(1).getInt("b"))
        assertEquals(8, doc.getJSONObject("x/y").getInt("~"))
        val pointers = Overrides.leaves(doc).map { it.pointer }.toSet()
        assertEquals(setOf("/a/0/b", "/a/1/b", "/x~1y/~0"), pointers)
    }

    @Test
    fun recordedAnswersAndOverridesSurviveARestart() = rig { r ->
        r.get("/config?a=1")
        r.admin("POST", "/set", """{"key":"${r.key("/config")}","pointer":"/level","value":4}""")
        val again = Store(File(r.dir, "cache"))
        val entry = assertNotNull(again.latest("GET", r.origin, "/config"))
        assertEquals("a=1", entry.query)
        assertEquals(1, again.urls().size)
        val reread = Overrides(File(r.dir, "overrides.json"))
        assertEquals(4, reread.get(r.key("/config"), "/level"))
        assertEquals(1, reread.count())
    }

    // The PC reads /export through ServerAdmin, unzips into an emptied folder, and
    // a store over that folder answers what the phone had recorded, which is what
    // the offline build carries. A file being written aside is not exported.
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#addFileToZip
    // https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#extractFileFromZip
    @Test
    fun exportedRecordingsUnzipOnThePcAndReplay() = rig { r ->
        val live = r.get("/config?a=1")
        r.post("/echo", "hi")
        File(r.dir, "cache/stray.0.tmp").writeText("half")
        val into = File(r.dir, "pc/recordings")
        File(into, "stale.0").also { it.parentFile.mkdirs() }.writeText("old")

        assertEquals(2, ServerAdmin.export(r.admin.listeningPort, into.toPath()))

        assertFalse(File(into, "stale.0").exists(), "the folder is emptied first")
        assertFalse(File(into, "stray.0.tmp").exists())
        assertEquals(File(r.dir, "cache").list()!!.filter { !it.endsWith(".tmp") }.toSet(), into.list()!!.toSet())
        val pc = Store(into)
        val entry = assertNotNull(pc.latest("GET", r.origin, "/config"))
        assertEquals("a=1", entry.query)
        assertEquals(live.body, String(entry.body))
        assertEquals(2, pc.urls().size)
        val reply = r.admin("GET", "/export")
        assertEquals(200, reply.code)
    }

    @Test
    fun anExportEntryThatLeavesTheFolderIsRefused() {
        val d = Files.createTempDirectory("gutapk-export")
        try {
            val bytes = ByteArrayOutputStream().also { raw ->
                ZipOutputStream(raw).use { z ->
                    z.putNextEntry(ZipEntry("a.0"))
                    z.write(1)
                    z.putNextEntry(ZipEntry("../evil.0"))
                    z.write(2)
                }
            }.toByteArray()
            val into = Files.createDirectories(d.resolve("pc/recordings"))
            assertFailsWith<java.io.IOException> { ServerAdmin.extractFileFromZip(bytes, into) }
            assertFalse(Files.exists(d.resolve("pc/evil.0")))
            assertEquals(listOf("a.0"), into.toFile().list()!!.toList())
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    // The copy of the recordings in the assets into the cache of the phone: the
    // folder is made, every file comes over, and a file the cache holds already
    // (a recording made on the phone, newer) is kept.
    // https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFolder
    // https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFile
    @Test
    fun seedCopiesTheAssetsAndKeepsAFileTheCacheHolds() {
        val shipped = mapOf("a.0" to "shipped a", "a.1" to "shipped body", "b.0" to "shipped b")
        val assets = object : Seed.Assets {
            override fun list(path: String): Array<String> = if (path == "gutapk/recordings") shipped.keys.toTypedArray() else emptyArray()
            override fun open(fileName: String): java.io.InputStream = ByteArrayInputStream(shipped.getValue(fileName.substringAfterLast('/')).toByteArray())
        }
        val d = Files.createTempDirectory("gutapk-seed").toFile()
        try {
            val cache = File(d, "files/gutapk-offline/cache")
            cache.mkdirs()
            File(cache, "a.0").writeText("recorded on the phone")

            assertTrue(Seed.copyAssetFolder(assets, "gutapk/recordings", cache.path))

            assertEquals("recorded on the phone", File(cache, "a.0").readText())
            assertEquals("shipped body", File(cache, "a.1").readText())
            assertEquals("shipped b", File(cache, "b.0").readText())
            assertEquals(setOf("a.0", "a.1", "b.0"), cache.list()!!.toSet())

            // A first launch: the folder does not exist yet.
            val fresh = File(d, "fresh/cache")
            assertTrue(Seed.copyAssetFolder(assets, "gutapk/recordings", fresh.path))
            assertEquals("shipped a", File(fresh, "a.0").readText())
            assertEquals(3, fresh.list()!!.size)
            // A second launch changes nothing.
            assertTrue(Seed.copyAssetFolder(assets, "gutapk/recordings", fresh.path))
            assertEquals(3, fresh.list()!!.size)
        } finally {
            d.deleteRecursively()
        }
    }

    // A redirect to the origin stays on this server, live and replayed, one to
    // another moved origin goes to its address, and any other is left alone.
    // https://github.com/ktorio/ktor-samples/blob/main/reverse-proxy/src/main/kotlin/io/ktor/samples/reverseproxy/ReverseProxyApplication.kt#L41-L52
    @Test
    fun redirectsToAMovedOriginStayOnThePhone() {
        val r = Rig()
        try {
            assertEquals("/text?x=1", r.get("/moved").headers["location"])
            assertEquals("http://127.0.0.1:20002/play", r.get("/away").headers["location"])
            assertEquals("https://other.example.net/", r.get("/out").headers["location"])
            r.upstream.stop()
            val replayed = r.get("/moved")
            assertEquals("replay", replayed.mode)
            assertEquals("/text?x=1", replayed.headers["location"])
        } finally {
            r.close()
        }
    }

    @Test
    fun startSkipsAPortAlreadyTakenAndKeepsTheOthers() {
        val taken = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val free = freePort()
        val dir = Files.createTempDirectory("gutapk-offline-start").toFile()
        try {
            val config = "listen.${taken.localPort}=https://one.example\nlisten.$free=https://two.example\n"
            val offline = Offline.start(config.byteInputStream(), dir)
            try {
                assertEquals(1, offline.servers.size)
                assertEquals(free, offline.servers[0].listeningPort)
                assertEquals(2, offline.origins.size)
                val miss = call(free, "GET", "/x")
                assertEquals("miss", miss.mode)
            } finally {
                offline.stop()
            }
        } finally {
            taken.close()
            dir.deleteRecursively()
        }
    }

    // mitmproxy's block_list: a matching request is answered with an empty response of the chosen
    // status and never sent upstream. The stand-in server counts every call it gets.
    // https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#request
    @Test
    fun aBlockedOriginIsAnsweredWithNoContentAndNeverCalled() {
        val upstream = Upstream()
        val dir = Files.createTempDirectory("gutapk-offline-block").toFile()
        val origin = "http://127.0.0.1:${upstream.port}"
        val cache = Store(File(dir, "cache"))
        val front = Server("127.0.0.1", 0, CacheInterceptor(origin, cache, Overrides(File(dir, "overrides.json")), ProxyResponseRenderer(), emptyMap(), 204))
        try {
            front.start()
            val get = call(front.listeningPort, "GET", "/config?a=1")
            assertEquals(204, get.code)
            assertEquals("", get.body)
            assertEquals("blocked", get.mode)
            val post = call(front.listeningPort, "POST", "/event", """{"e":1}""")
            assertEquals(204, post.code)
            assertEquals("blocked", post.mode)
            assertEquals(0, upstream.hits.get())
            // Never recorded either.
            assertNull(cache.get("GET", origin, "/config", "a=1", Store.sha256(ByteArray(0))))
            assertEquals(0, dir.resolve("cache").listFiles().orEmpty().size)
        } finally {
            front.stop()
            upstream.stop()
            dir.deleteRecursively()
        }
    }

    // block.<port> lines: true is the default status, a number is the status itself,
    // a value that is neither leaves the origin served as any other.
    @Test
    fun startReadsTheBlockLinesOfTheConfig() {
        val one = freePort()
        val two = freePort()
        val three = freePort()
        val dir = Files.createTempDirectory("gutapk-offline-lines").toFile()
        try {
            val config = "listen.$one=https://one.example\nlisten.$two=https://two.example\nlisten.$three=https://three.example\n" +
                "block.$one=true\nblock.$two=403\nblock.$three=maybe\n"
            val offline = Offline.start(config.byteInputStream(), dir)
            try {
                assertEquals(mapOf(one to 204, two to 403), offline.items)
                val one204 = call(one, "GET", "/x")
                assertEquals(204, one204.code)
                assertEquals("blocked", one204.mode)
                assertEquals(403, call(two, "GET", "/x").code)
                assertEquals("miss", call(three, "GET", "/x").mode)
            } finally {
                offline.stop()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theBuiltServerDexIsAResourceWithTheStartupClass() {
        val bytes = OfflineTest::class.java.getResourceAsStream("/io/gutapk/offline/server.dex")!!.use { it.readBytes() }
        assertTrue(bytes.size > 1000)
        assertEquals("dex\n", bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("io/gutapk/offline/CacheProvider"))
    }

    @Test
    fun theDexCompilerStaysOutOfTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("com.android.tools.r8.D8") }
        assertFailsWith<ClassNotFoundException> { Class.forName("android.content.ContentProvider") }
    }
}
