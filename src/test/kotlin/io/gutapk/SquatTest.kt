package io.gutapk

// A port of the offline server that is taken by another app: the game would send its
// account tokens there, so the start fails closed unless the holder proves it is this
// app. The proof is an HMAC of the port and a nonce made with a key kept in a file, as a
// kernel's ports are guarded by the key of its connection file, and the checks follow
// the tests of its session
// https://github.com/jupyter/jupyter_client/blob/main/tests/test_session.py#test_default_secure

import io.gutapk.offline.CacheInterceptor
import io.gutapk.offline.Offline
import io.gutapk.offline.Overrides
import io.gutapk.offline.ProxyResponseRenderer
import io.gutapk.offline.Server
import io.gutapk.offline.Store
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SquatTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun config(vararg ports: Int): String = ports.joinToString("") { "listen.$it=https://example$it.test\n" }

    private class Reply(val code: Int, val body: String)

    private fun get(port: Int, path: String): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Reply(code, text)
    }

    // Another app on the port: it takes connections and does what its handler says.
    private class Foreign(port: Int, private val handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        private val worker = thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = server.accept()
                    sockets.add(socket)
                    thread(isDaemon = true) {
                        try {
                            handler(socket)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            worker.join(2000)
        }
    }

    private fun readHead(socket: Socket): String {
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        return head.toString()
    }

    // The real server of the app on a port, with a key, built the way Offline.start builds one.
    private fun realServer(dir: File, port: Int, key: ByteArray?): Server {
        val cache = Store(File(dir, "cache$port"))
        val interceptor = if (key == null) {
            CacheInterceptor("http://127.0.0.1:1", cache, Overrides(File(dir, "overrides$port.json")), ProxyResponseRenderer(), emptyMap())
        } else {
            CacheInterceptor("http://127.0.0.1:1", cache, Overrides(File(dir, "overrides$port.json")), ProxyResponseRenderer(), emptyMap(), 0, null, key, port)
        }
        return Server("127.0.0.1", port, interceptor).also { it.start() }
    }

    private fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Test
    fun aHolderThatAnswersGarbageIsRefused() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-garbage").toFile()
        val squatter = Foreign(port) { socket ->
            readHead(socket)
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ngarb".toByteArray())
            socket.getOutputStream().flush()
            socket.close()
        }
        try {
            val e = assertFailsWith<Offline.Squatted> { Offline.start(config(port).byteInputStream(), dir) }
            assertTrue(e.message!!.contains(port.toString()), e.message)
            assertTrue(e.message!!.contains("held by another app"), e.message)
            assertTrue(e is SecurityException)
        } finally {
            squatter.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aHolderThatNeverAnswersIsRefusedInTime() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-silent").toFile()
        val squatter = Foreign(port) { socket ->
            readHead(socket)
            try {
                socket.soTimeout = 30000
                socket.getInputStream().read()
            } catch (_: SocketTimeoutException) {
            }
        }
        try {
            val begun = System.nanoTime()
            assertFailsWith<Offline.Squatted> { Offline.start(config(port).byteInputStream(), dir) }
            val seconds = (System.nanoTime() - begun) / 1_000_000_000.0
            assertTrue(seconds < 8.0, "took $seconds s")
        } finally {
            squatter.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun anotherProcessOfTheSameAppIsSkippedNotRefused() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-second").toFile()
        var first: Offline? = null
        var second: Offline? = null
        try {
            first = Offline.start(config(port).byteInputStream(), dir)
            assertEquals(1, first.servers.size)
            second = Offline.start(config(port).byteInputStream(), dir)
            assertEquals(0, second.servers.size)
            // The first one still serves the port.
            assertEquals(502, get(port, "/x").code)
        } finally {
            second?.stop()
            first?.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theKeyFileIsMadeOnceAndReusedByTheNextStart() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-key").toFile()
        try {
            Offline.start(config(port).byteInputStream(), dir).stop()
            val secret = File(dir, "secret")
            val key = secret.readBytes()
            assertEquals(32, key.size)
            assertNotEquals(0, key.count { it != 0.toByte() })
            Offline.start(config(port).byteInputStream(), dir).stop()
            assertContentEquals(key, secret.readBytes())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(secret.toPath()))
            // No temp file is left beside it.
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theSignatureOfOnePortIsNotTheSignatureOfAnother() {
        val key = ByteArray(32) { it.toByte() }
        val n = nonce()
        val p = freePort()
        val q = freePort()
        assertNotEquals(Offline.sign(key, p, n), Offline.sign(key, q, n))
        assertEquals(Offline.sign(key, p, n), Offline.sign(key, p, n))
        assertNotEquals(Offline.sign(key, p, n), Offline.sign(key, p, nonce()))
        assertNotEquals(Offline.sign(key, p, n), Offline.sign(ByteArray(32) { 7 }, p, n))
        assertTrue(Regex("[0-9a-f]{64}").matches(Offline.sign(key, p, n)))
    }

    // A squatter on one port that hands the challenge to the real server on another still
    // gets the signature of the other port.
    @Test
    fun aRelayToOurRealServerOnAnotherPortIsRefused() {
        val real = freePort()
        val taken = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-relay").toFile()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        File(dir, "secret").writeBytes(key)
        val server = realServer(dir, real, key)
        val relay = Foreign(taken) { socket ->
            val head = readHead(socket)
            Socket("127.0.0.1", real).use { upstream ->
                upstream.soTimeout = 3000
                upstream.getOutputStream().write(head.toByteArray())
                upstream.getOutputStream().flush()
                try {
                    upstream.getInputStream().copyTo(socket.getOutputStream())
                } catch (_: SocketTimeoutException) {
                }
            }
            socket.close()
        }
        try {
            // The relay is a faithful copy: the real server does sign what it is asked.
            val n = nonce()
            assertEquals(Offline.sign(key, real, n), get(taken, "${Offline.PROOF}?nonce=$n").body)
            assertFailsWith<Offline.Squatted> { Offline.start(config(taken).byteInputStream(), dir) }
        } finally {
            relay.close()
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theProofRouteAnswersTheSignatureAndRefusesABadNonce() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-route").toFile()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val server = realServer(dir, port, key)
        try {
            val n = nonce()
            val ok = get(port, "${Offline.PROOF}?nonce=$n")
            assertEquals(200, ok.code)
            assertEquals(Offline.sign(key, port, n), ok.body)
            assertEquals(400, get(port, "${Offline.PROOF}?nonce=${n.uppercase()}").code)
            assertEquals(400, get(port, "${Offline.PROOF}?nonce=${n.drop(1)}").code)
            assertEquals(400, get(port, "${Offline.PROOF}?nonce=${n}00").code)
            assertEquals(400, get(port, "${Offline.PROOF}?nonce=").code)
            assertEquals(400, get(port, Offline.PROOF).code)
            // Never recorded.
            assertEquals(0, File(dir, "cache$port").listFiles().orEmpty().size)
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun withoutAKeyTheProofRouteIsAbsent() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-nokey").toFile()
        val server = realServer(dir, port, null)
        try {
            // An ordinary request, to an origin that is out of reach and has no recording.
            assertEquals(502, get(port, "${Offline.PROOF}?nonce=${nonce()}").code)
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun noAdminSocketIsBoundWithoutAnAdminLine() {
        val port = freePort()
        val dir = Files.createTempDirectory("gutapk-squat-noadmin").toFile()
        val offline = Offline.start(config(port).byteInputStream(), dir)
        try {
            assertEquals(null, offline.adminName)
        } finally {
            offline.stop()
            dir.deleteRecursively()
        }
    }
}
