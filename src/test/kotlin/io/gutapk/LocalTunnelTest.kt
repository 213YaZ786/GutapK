package io.gutapk

// Local's relay runs on the JVM here, a friend's relay and the host's on one
// machine in front of an echo server standing in for the game's. The key
// derivation is checked against the HKDF-SHA1 test vector of RFC 5869
// https://www.rfc-editor.org/rfc/rfc5869#appendix-A.4

import io.gutapk.local.Aead
import io.gutapk.local.Hex
import io.gutapk.local.Invite
import io.gutapk.local.PingPongBloom
import io.gutapk.local.Tunnel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalTunnelTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // The invitation: the page's link keeps the key in its fragment, which a
    // browser never sends, the game's link in its query, read back as Uri
    // reads a parameter; the Local screen's QR code is GutapK's own.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/Uri.java#L1721-L1764
    // not ported: getQueryParameters, getQueryParameterNames, getBooleanQueryParameter (one value per key)
    @Test
    fun theInvitationCarriesTheKeyAndItsQrIsGutapks() {
        val key = Aead.newKey()
        val address = InetAddress.getByName("192.168.43.1")
        val link = Invite.link(address, 8080, 45000, key)
        assertEquals("http://192.168.43.1:8080/#p=45000&k=" + Hex.encodeHexString(key), link)
        val query = Invite.join("com.example.game", address, 45000, key).substringAfter("gutapk://com.example.game/join?")
        assertEquals("192.168.43.1", Invite.getQueryParameter(query, "h"))
        assertEquals("45000", Invite.getQueryParameter(query, "p"))
        assertContentEquals(key, Hex.decodeHex(Invite.getQueryParameter(query, "k")))
        assertNull(Invite.getQueryParameter(query, "x"))
        assertFailsWith<IllegalArgumentException> { Hex.decodeHex("0g") }

        val java = io.gutapk.local.QrCode.encode(link)
        val kotlin = io.gutapk.core.QrCode.encode(link)
        assertEquals(kotlin.size, java.size)
        for (y in 0 until kotlin.size) for (x in 0 until kotlin.size) assertEquals(kotlin.isDark(x, y), java.isDark(x, y), "$x,$y")
    }

    @Test
    fun hkdfSha1MatchesRfc5869() {
        val okm = Aead.hkdf(ByteArray(11) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertContentEquals(hex("085a01ea1b10f36933068b56efa5ad81a4f14b822f5b091568a9cdd4f155fda2c22e422478d305f3f896"), okm)
    }

    @Test
    fun streamOpensWithTheKeyAndSplitsChunks() {
        val key = Aead.newKey()
        val sealed = ByteArrayOutputStream()
        val big = ByteArray(Aead.CHUNK_SIZE_MASK * 2 + 5) { it.toByte() }
        Aead.encrypt(key, sealed).write(sealed, big, 0, big.size)
        val input = DataInputStream(ByteArrayInputStream(sealed.toByteArray()))
        val opening = Aead.decrypt(key, input, null)
        val back = ByteArrayOutputStream()
        while (true) back.write(opening.read(input) ?: break)
        assertContentEquals(big, back.toByteArray())

        // Another key, a flipped bit, a replayed salt: all refused.
        assertFailsWith<GeneralSecurityException> {
            val other = DataInputStream(ByteArrayInputStream(sealed.toByteArray()))
            Aead.decrypt(Aead.newKey(), other, null).read(other)
        }
        val tampered = sealed.toByteArray().also { it[Aead.KEY_SIZE + 40] = (it[Aead.KEY_SIZE + 40].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> {
            val other = DataInputStream(ByteArrayInputStream(tampered))
            val aead = Aead.decrypt(key, other, null)
            while (aead.read(other) != null) Unit
        }
        val seen = PingPongBloom()
        Aead.decrypt(key, DataInputStream(ByteArrayInputStream(sealed.toByteArray())), seen)
        assertFailsWith<GeneralSecurityException> { Aead.decrypt(key, DataInputStream(ByteArrayInputStream(sealed.toByteArray())), seen) }
    }

    @Test
    fun datagramOpensOnceWithTheKey() {
        val key = Aead.newKey()
        val text = "ping".toByteArray()
        val sealed = Aead.encryptAll(key, text, 0, text.size)
        val seen = PingPongBloom()
        assertContentEquals(text, Aead.decryptAll(key, sealed, 0, sealed.size, seen))
        assertFailsWith<GeneralSecurityException> { Aead.decryptAll(key, sealed, 0, sealed.size, seen) }
        assertFailsWith<GeneralSecurityException> { Aead.decryptAll(Aead.newKey(), sealed, 0, sealed.size, null) }
    }

    @Test
    fun pingPongForgetsTheOldestHalf() {
        val bloom = PingPongBloom()
        val salts = (0 until 30000).map { ByteArray(4) { b -> (it shr (8 * b)).toByte() } }
        salts.forEach { assertFalse(bloom.check_and_set(it)) }
        assertTrue(bloom.check_and_set(salts.last()))
        // 10000 per set: the first ten thousand were cleared at the second turn.
        assertFalse(bloom.check_and_set(salts.first()))
    }

    @Test
    fun streamGoesThroughBothRelays() {
        ServerSocket(0, 50, loopback).use { game ->
            thread(isDaemon = true) {
                while (!game.isClosed) {
                    val client = runCatching { game.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { client.use { it.getInputStream().copyTo(it.getOutputStream()) } }
                }
            }
            val key = Aead.newKey()
            Tunnel.host(key, 0, game.localPort, false).use { host ->
                val friendPort = ServerSocket(0, 1, loopback).use { it.localPort }
                Tunnel.join(key, listOf(InetSocketAddress(loopback, 1), InetSocketAddress(loopback, host.listeningPort)), friendPort, false).use { friend ->
                    assertEquals(host.listeningPort, friend.target.port)
                    Socket(loopback, friendPort).use { socket ->
                        val text = ByteArray(100000) { (it * 7).toByte() }
                        thread(isDaemon = true) { socket.getOutputStream().write(text); socket.shutdownOutput() }
                        assertContentEquals(text, socket.getInputStream().readBytes())
                    }
                }
                // A friend with another key reaches nothing.
                val otherPort = ServerSocket(0, 1, loopback).use { it.localPort }
                Tunnel.join(Aead.newKey(), listOf(InetSocketAddress(loopback, host.listeningPort)), otherPort, false).use {
                    Socket(loopback, otherPort).use { socket ->
                        socket.soTimeout = 10000
                        socket.getOutputStream().write("hello".toByteArray())
                        // Closed, or reset: never an answer.
                        val read = runCatching { socket.getInputStream().read() }
                        assertTrue(read.isFailure || read.getOrNull() == -1)
                    }
                }
            }
        }
    }

    @Test
    fun datagramsGoThroughBothRelays() {
        DatagramSocket(0, loopback).use { game ->
            thread(isDaemon = true) {
                val buffer = ByteArray(2048)
                while (!game.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { game.receive(packet) }.getOrNull() ?: break
                    game.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                }
            }
            val key = Aead.newKey()
            Tunnel.host(key, 0, game.localPort, true).use { host ->
                val friendPort = DatagramSocket(0, loopback).use { it.localPort }
                Tunnel.join(key, listOf(InetSocketAddress(loopback, host.listeningPort)), friendPort, true).use {
                    DatagramSocket().use { client ->
                        client.soTimeout = 10000
                        for (word in listOf("one", "two")) {
                            client.send(DatagramPacket(word.toByteArray(), word.length, InetSocketAddress(loopback, friendPort)))
                            val answer = DatagramPacket(ByteArray(2048), 2048)
                            client.receive(answer)
                            assertEquals(word, String(answer.data, 0, answer.length))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun streamEndsBetweenChunks() {
        val key = Aead.newKey()
        val sealed = ByteArrayOutputStream()
        Aead.encrypt(key, sealed)
        val input = DataInputStream(ByteArrayInputStream(sealed.toByteArray()))
        assertNull(Aead.decrypt(key, input, null).read(input))
    }
}
