package io.gutapk

// What a hostile app or web page on the phone's loopback, or in the PC's browser, may do to the offline server:
// nesting that overflows the stack, a flood of connections, a request-target that names another host, and an
// admin request that does not come from the PC client. Each check follows the tests of its source
// https://github.com/FasterXML/jackson-core/blob/main/src/test/java/tools/jackson/core/unittest/constraints/DeeplyNestedContentReadTest.java
// https://github.com/apache/tomcat/blob/main/test/org/apache/tomcat/util/threads/TestLimitLatch.java
// https://github.com/webpack/webpack-dev-server/blob/main/test/server/host-header-validation.test.js

import io.gutapk.offline.Http
import io.gutapk.offline.LimitLatch
import io.gutapk.offline.Overrides
import io.gutapk.offline.Routes
import io.gutapk.offline.Server
import io.gutapk.offline.Store
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {

    /** [levels] documents, each holding the next under the name "a", the innermost one empty. */
    private fun nested(levels: Int): ByteArray {
        var doc = byteArrayOf(5, 0, 0, 0, 0)
        repeat(levels - 1) {
            val out = ByteBuffer.allocate(4 + 1 + 2 + doc.size + 1).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(out.capacity())
            out.put(3)
            out.put('a'.code.toByte())
            out.put(0)
            out.put(doc)
            out.put(0)
            doc = out.array()
        }
        return doc
    }

    @Test
    fun javaBsonRefusesNestingPastTheLimitWithoutOverflowingTheStack() {
        assertNotNull(io.gutapk.offline.Bson.decode(nested(io.gutapk.offline.Bson.DEFAULT_MAX_DEPTH)))
        val over = assertFailsWith<IllegalArgumentException> { io.gutapk.offline.Bson.decode(nested(io.gutapk.offline.Bson.DEFAULT_MAX_DEPTH + 1)) }
        assertTrue(over.message!!.contains("exceeds the maximum allowed (1000"), over.message)
        // About 70 KB, the size that overflowed the stack before the limit
        assertFailsWith<IllegalArgumentException> { io.gutapk.offline.Bson.decode(nested(10000)) }
    }

    @Test
    fun kotlinBsonRefusesNestingPastTheLimitWithoutOverflowingTheStack() {
        val bson = io.gutapk.core.offline.Bson
        fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertNotNull(bson.decodeBson(buffer(nested(bson.DEFAULT_MAX_DEPTH))))
        assertFailsWith<IllegalArgumentException> { bson.decodeBson(buffer(nested(bson.DEFAULT_MAX_DEPTH + 1))) }
        assertFailsWith<IllegalArgumentException> { bson.decodeBson(buffer(nested(10000))) }
        // The callers read an IllegalArgumentException as "not BSON"
        assertNull(bson.document(nested(10000)))
        assertTrue(bson.documents(nested(10000)).isEmpty())
        assertNotNull(bson.document(nested(9)))
    }

    private fun waitForWaiter(latch: LimitLatch) {
        val until = System.currentTimeMillis() + 5000
        while (!latch.hasQueuedThreads() && System.currentTimeMillis() < until) Thread.sleep(5)
        assertTrue(latch.hasQueuedThreads(), "the share waits")
    }

    @Test
    fun limitLatchCountsUpBlocksAtTheLimitAndReleases() {
        val latch = LimitLatch(2)
        latch.countUpOrAwait()
        latch.countUpOrAwait()
        assertEquals(2L, latch.count)
        assertFalse(latch.hasQueuedThreads())

        val through = AtomicBoolean(false)
        val waiter = Thread {
            latch.countUpOrAwait()
            through.set(true)
        }
        waiter.isDaemon = true
        waiter.start()
        waitForWaiter(latch)
        assertFalse(through.get())
        assertEquals(2L, latch.count)

        assertEquals(1L, latch.countDown())
        waiter.join(5000)
        assertTrue(through.get(), "a returned share lets the waiter through")
        assertEquals(2L, latch.count)
        assertEquals(1L, latch.countDown())
        assertEquals(0L, latch.countDown())

        assertFailsWith<IllegalArgumentException> { LimitLatch(-1) }
        latch.limit = 5
        assertEquals(5L, latch.limit)
    }

    @Test
    fun limitLatchReleaseAllLetsWaitersThroughAndKeepsTheCount() {
        val latch = LimitLatch(1)
        latch.countUpOrAwait()
        val through = AtomicBoolean(false)
        val waiter = Thread {
            latch.countUpOrAwait()
            through.set(true)
        }
        waiter.isDaemon = true
        waiter.start()
        waitForWaiter(latch)
        assertEquals(1, latch.queuedThreads.size)
        assertTrue(latch.releaseAll())
        waiter.join(5000)
        assertTrue(through.get())
        // The share held plus the one the released thread took
        assertEquals(2L, latch.count)
        latch.reset()
        assertEquals(0L, latch.count)
        latch.countUpOrAwait()
        assertEquals(1L, latch.count)
    }

    private class Rig {
        val dir: File = Files.createTempDirectory("gutapk-hardening").toFile()
        val admin = Server("127.0.0.1", 0, Routes(Store(File(dir, "cache")), Overrides(File(dir, "overrides.json")), TreeMap<Int, String>()))

        init {
            admin.start()
        }

        fun close() {
            admin.stop()
            dir.deleteRecursively()
        }
    }

    private inline fun rig(block: (Rig) -> Unit) {
        val r = Rig()
        try {
            block(r)
        } finally {
            r.close()
        }
    }

    private fun raw(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    private fun status(answer: String): Int = answer.substringBefore("\r\n").split(" ")[1].toInt()

    private fun body(answer: String): String = answer.substringAfter("\r\n\r\n")

    /** A GET of [path] with the headers as given, the admin header unless [admin] is null. */
    private fun get(r: Rig, host: String?, origin: String? = null, admin: String? = "1", path: String = "/status"): String {
        val lines = StringBuilder("GET $path HTTP/1.1\r\n")
        if (host != null) lines.append("Host: $host\r\n")
        if (origin != null) lines.append("Origin: $origin\r\n")
        if (admin != null) lines.append("X-GutapK-Admin: $admin\r\n")
        lines.append("Connection: close\r\n\r\n")
        return raw(r.admin.listeningPort, lines.toString())
    }

    @Test
    fun adminRoutesAnswer403WithoutTheAdminHeader() = rig { r ->
        val port = r.admin.listeningPort
        val answer = get(r, "127.0.0.1:$port", admin = null)
        assertEquals(403, status(answer))
        assertEquals("forbidden", JSONObject(body(answer)).getString("error"))
        assertEquals(403, status(get(r, "127.0.0.1:$port", admin = "0")))
        assertEquals(403, status(get(r, "127.0.0.1:$port", admin = "")))
        // A POST that a page could send as a simple no-cors request does nothing
        val post = raw(port, "POST /clear HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        assertEquals(403, status(post))
    }

    @Test
    fun adminRoutesAnswer403ForAHostThatIsNotThisMachine() = rig { r ->
        val port = r.admin.listeningPort
        assertEquals(403, status(get(r, "evil.example")))
        assertEquals(403, status(get(r, "evil.example:$port")))
        assertEquals(403, status(get(r, "127.0.0.1.evil.example:$port")))
        assertEquals(403, status(get(r, "127.0.0.1@evil.example")))
        assertEquals(403, status(get(r, "evil.example/127.0.0.1")))
        assertEquals(403, status(get(r, "localhost.evil.example")))
        assertEquals(403, status(get(r, "999.0.0.1")))
        assertEquals(403, status(get(r, null)))
        assertEquals("Invalid Host header", JSONObject(body(get(r, "evil.example"))).getString("message"))
    }

    @Test
    fun adminRoutesAnswer403ForAnOriginThatIsNotThisMachine() = rig { r ->
        val port = r.admin.listeningPort
        val answer = get(r, "127.0.0.1:$port", origin = "http://evil.example")
        assertEquals(403, status(answer))
        assertEquals("Cross-Origin request blocked", JSONObject(body(answer)).getString("message"))
        assertEquals(403, status(get(r, "127.0.0.1:$port", origin = "null")))
        assertEquals(403, status(get(r, "127.0.0.1:$port", origin = "https://127.0.0.1.evil.example")))
        assertEquals(403, status(get(r, "127.0.0.1:$port", origin = "")))
    }

    @Test
    fun adminRoutesAnswer200ForThisMachineWithTheAdminHeader() = rig { r ->
        val port = r.admin.listeningPort
        val answer = get(r, "127.0.0.1:$port")
        assertEquals(200, status(answer))
        assertTrue(JSONObject(body(answer)).has("entries"))
        assertEquals(200, status(get(r, "127.0.0.1")))
        assertEquals(200, status(get(r, "localhost:$port")))
        assertEquals(200, status(get(r, "LOCALHOST:$port")))
        assertEquals(200, status(get(r, "dev.localhost:$port")))
        assertEquals(200, status(get(r, "[::1]:$port")))
        assertEquals(200, status(get(r, "127.0.0.1:$port", origin = "http://127.0.0.1:$port")))
        assertEquals(200, status(get(r, "127.0.0.1:$port", origin = "http://localhost:3000")))
        // The route table is still behind the check
        assertEquals(404, status(get(r, "127.0.0.1:$port", path = "/nothing")))
    }

    @Test
    fun httpAnswers400ToARequestTargetThatIsNotOriginForm() = rig { r ->
        val port = r.admin.listeningPort
        for (target in listOf("@evil.example/x", "http://evil.example/x", "evil.example/x", "*")) {
            val answer = raw(port, "GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-GutapK-Admin: 1\r\nConnection: close\r\n\r\n")
            assertEquals(400, status(answer), target)
            assertTrue(body(answer).contains("must start with /"), target)
        }
        assertEquals(200, status(raw(port, "GET /status HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-GutapK-Admin: 1\r\nConnection: close\r\n\r\n")))
    }

    // An Error in a client thread reaches Android's uncaught handler, which kills the whole app. The client
    // is dropped instead and the listener goes on.
    @Test
    fun aClientThatOverflowsTheStackIsDroppedAndTheServerGoesOn() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        val calls = AtomicInteger()
        val server = Server("127.0.0.1", 0, Http.Handler {
            if (calls.incrementAndGet() == 1) throw StackOverflowError() else throw OutOfMemoryError()
        })
        server.start()
        try {
            for (i in 1..2) {
                val answer = raw(server.listeningPort, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                assertEquals("", answer)
            }
            assertEquals(2, calls.get())
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        } finally {
            server.stop()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    // More clients one after the other than the limit: each gives its share back when it ends, else the
    // listener would wait for a free slot for ever.
    @Test
    fun theSharedLatchIsGivenBackWhenClientsEnd() {
        val server = Server("127.0.0.1", 0, Http.Handler { Http.Response.newFixedLengthResponse(200, "text/plain", "ok") })
        server.start()
        try {
            repeat(Server.MAX_CONNECTIONS + 20) {
                assertEquals(200, status(raw(server.listeningPort, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")))
            }
        } finally {
            server.stop()
        }
    }
}
