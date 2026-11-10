package io.gutapk

// The admin socket's name is new at each launch: the PC reads it from the game's own
// process through dumpsys, takes it only when it has the shape the game gives it, makes
// its forward again when a relaunch changed it, and checks a whole export zip against
// its limits before it empties the folder. The checks follow the tests of their sources
// https://github.com/flutter/flutter/blob/master/packages/flutter_tools/test/general.shard/protocol_discovery_test.dart
// https://github.com/apache/poi/blob/trunk/poi-ooxml/src/test/java/org/apache/poi/openxml4j/util/TestZipSecureFile.java

import com.sun.net.httpserver.HttpServer
import io.gutapk.device.ServerAdmin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminDiscoveryTest {
    private val pkg = "com.example.game"
    private val prefix = ServerAdmin.socket(pkg)
    private val hex = "0123456789abcdef0123456789abcdef"

    // The shape dumpsys gives on API 36: the record, then what the provider printed under Client.
    private fun dump(name: String): String =
        "PROVIDER ContentProviderRecord{4f2a1b u0 $pkg/io.gutapk.offline.CacheProvider} pid=1108\n" +
            "    Client:\n" +
            "      admin $name\n"

    @Test
    fun theNameIsReadFromARealShapedDump() {
        assertEquals("$prefix.$hex", ServerAdmin.parseAdmin(dump("$prefix.$hex"), prefix))
        // dumpsys over adb ends its lines with a carriage return.
        assertEquals("$prefix.$hex", ServerAdmin.parseAdmin(dump("$prefix.$hex").replace("\n", "\r\n"), prefix))
    }

    @Test
    fun aGameThatIsNotRunningHasNoName() {
        assertNull(ServerAdmin.parseAdmin("PROVIDER ContentProviderRecord{4f2a1b u0 $pkg/io.gutapk.offline.CacheProvider} pid=(not running)\n", prefix))
        assertNull(ServerAdmin.parseAdmin("", prefix))
        assertNull(ServerAdmin.parseAdmin(dump("none"), prefix))
    }

    @Test
    fun aNameOfAnotherShapeIsRefused() {
        assertNull(ServerAdmin.parseAdmin(dump("other.pkg.$hex"), prefix))
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.${hex.drop(1)}"), prefix))
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.${hex}0"), prefix))
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.${hex.uppercase()}"), prefix))
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.${hex.dropLast(1)}g"), prefix))
        assertNull(ServerAdmin.parseAdmin(dump(prefix), prefix))
        // The dots of the prefix are dots, not any character.
        assertNull(ServerAdmin.parseAdmin(dump("${prefix.replaceFirst('.', 'x')}.$hex"), prefix))
        // Text after the name is not the name.
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.$hex extra"), prefix))
    }

    // The first uri in the stream is the last one from the log.
    @Test
    fun theLastLineWins() {
        val older = "$prefix.${hex.replace('0', 'a')}"
        assertEquals("$prefix.$hex", ServerAdmin.parseAdmin(dump(older) + "      admin $prefix.$hex\n", prefix))
        // A last line that is not a name leaves none: it is not read as an older one.
        assertNull(ServerAdmin.parseAdmin(dump("$prefix.$hex") + "      admin nonsense\n", prefix))
    }

    @Test
    fun theDumpIsAskedOfTheProviderOfThePackage() {
        assertEquals(
            listOf("-s", "SER1", "shell", "dumpsys", "activity", "provider", "'$pkg/io.gutapk.offline.CacheProvider'"),
            ServerAdmin.dumpArgs("SER1", pkg),
        )
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val raw = ByteArrayOutputStream()
        ZipOutputStream(raw).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return raw.toByteArray()
    }

    @Test
    fun moreEntriesThanTheLimitAreRefusedWhileTheFolderIsUntouched() {
        val d = Files.createTempDirectory("gutapk-limit-count")
        try {
            val into = Files.createDirectories(d.resolve("recordings"))
            Files.writeString(into.resolve("keep.0"), "earlier")
            val bytes = zip((0 until 4).map { "a$it.0" to ByteArray(1) })
            val limits = ServerAdmin.Limits(maxFileCount = 3)
            assertFailsWith<IOException> { ServerAdmin.checkThreshold(bytes, limits) }
            assertEquals(listOf("keep.0"), into.toFile().list()!!.toList())
            // Extraction enforces the same limit as it goes.
            assertFailsWith<IOException> { ServerAdmin.extractFileFromZip(bytes, into, limits) }
            // At the limit the zip passes.
            ServerAdmin.checkThreshold(bytes, ServerAdmin.Limits(maxFileCount = 4))
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    @Test
    fun anEntryOverTheSizeLimitIsRefused() {
        val d = Files.createTempDirectory("gutapk-limit-entry")
        try {
            val into = Files.createDirectories(d.resolve("recordings"))
            val bytes = zip(listOf("small.0" to ByteArray(10), "big.0" to ByteArray(101)))
            val limits = ServerAdmin.Limits(maxEntrySize = 100)
            assertFailsWith<IOException> { ServerAdmin.checkThreshold(bytes, limits) }
            assertFailsWith<IOException> { ServerAdmin.extractFileFromZip(bytes, into, limits) }
            ServerAdmin.checkThreshold(bytes, ServerAdmin.Limits(maxEntrySize = 101))
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    @Test
    fun theSumOfTheEntriesIsBoundToo() {
        val bytes = zip(listOf("a.0" to ByteArray(100), "b.0" to ByteArray(100)))
        assertFailsWith<IOException> { ServerAdmin.checkThreshold(bytes, ServerAdmin.Limits(maxTotalSize = 150)) }
        ServerAdmin.checkThreshold(bytes, ServerAdmin.Limits(maxTotalSize = 200))
    }

    @Test
    fun theDefaultLimitsLetARealExportThrough() {
        val d = Files.createTempDirectory("gutapk-limit-default")
        try {
            val bytes = zip(listOf("a.0" to ByteArray(1000), "a.1" to ByteArray(2000)))
            ServerAdmin.checkThreshold(bytes)
            assertEquals(1, ServerAdmin.extractFileFromZip(bytes, d))
            assertEquals(2000, Files.size(d.resolve("a.1")))
            assertEquals(100_000L, ServerAdmin.MAX_FILE_COUNT)
            assertEquals(512L * 1024 * 1024, ServerAdmin.MAX_ENTRY_SIZE)
            assertEquals(1024L * 1024 * 1024, ServerAdmin.MAX_TOTAL_SIZE)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    // The phone, as far as export sees it: an HTTP server on the local port.
    private fun phone(port: Int = 0, handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        return server
    }

    @Test
    fun exportChecksTheWholeZipBeforeItEmptiesTheFolder() {
        val d = Files.createTempDirectory("gutapk-export-order")
        val bad = zip(listOf("a.0" to ByteArray(1), "../evil.0" to ByteArray(1)))
        val good = zip(listOf("b.0" to ByteArray(3)))
        val server = phone { exchange ->
            val body = if (exchange.requestURI.path == "/export" && exchange.requestHeaders.getFirst("X-GutapK-Admin") == "1") (if (exchange.requestURI.query == "bad") bad else good) else ByteArray(0)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }
        try {
            val into = Files.createDirectories(d.resolve("recordings"))
            Files.writeString(into.resolve("keep.0"), "earlier")
            val port = server.address.port
            // The good zip replaces the folder's content.
            assertEquals(1, ServerAdmin.export(port, into))
            assertEquals(listOf("b.0"), into.toFile().list()!!.toList())
            // A zip with a name that leaves the folder is refused before anything is emptied or written.
            Files.writeString(into.resolve("keep.1"), "earlier")
            val badServer = phone { exchange ->
                exchange.sendResponseHeaders(200, bad.size.toLong())
                exchange.responseBody.write(bad)
            }
            try {
                assertFailsWith<IOException> { ServerAdmin.export(badServer.address.port, into) }
            } finally {
                badServer.stop(0)
            }
            assertEquals(setOf("b.0", "keep.1"), into.toFile().list()!!.toSet())
            assertFalse(Files.exists(d.resolve("evil.0")))
        } finally {
            server.stop(0)
            d.toFile().deleteRecursively()
        }
    }

    // A fake adb: it answers dumpsys from a file and writes every call it gets in a log.
    private class FakeAdb(val dir: Path) {
        val adb: Path = dir.resolve("adb")
        val dump: Path = dir.resolve("dump.txt")
        val log: Path = dir.resolve("calls.log")

        init {
            Files.writeString(adb, "#!/bin/sh\necho \"\$@\" >> '$log'\nif echo \"\$*\" | grep -q dumpsys\nthen\ncat '$dump'\nfi\n")
            adb.toFile().setExecutable(true)
            Files.writeString(log, "")
        }

        fun running(name: String?, pkg: String) {
            Files.writeString(dump, if (name == null) "PROVIDER ContentProviderRecord{4f2a1b u0 $pkg/io.gutapk.offline.CacheProvider} pid=(not running)\n" else "PROVIDER ContentProviderRecord{4f2a1b u0 $pkg/io.gutapk.offline.CacheProvider} pid=1108\n    Client:\n      admin $name\n")
        }

        fun calls(): List<String> = Files.readAllLines(log)
    }

    @Test
    fun forwardNamesTheSocketTheGameBoundAndFailsWhenItIsNotRunning() {
        val d = Files.createTempDirectory("gutapk-forward")
        try {
            val fake = FakeAdb(d)
            fake.running(null, pkg)
            val e = assertFailsWith<IOException> { ServerAdmin.forward(fake.adb, "SER1", pkg) }
            assertTrue(e.message!!.contains("not running"), e.message)
            fake.running("$prefix.$hex", pkg)
            val local = ServerAdmin.forward(fake.adb, "SER1", pkg)
            assertTrue(fake.calls().contains("-s SER1 forward tcp:$local localabstract:$prefix.$hex"), fake.calls().toString())
            assertEquals("$prefix.$hex", ServerAdmin.name(fake.adb, "SER1", pkg))
            ServerAdmin.unforward(fake.adb, "SER1", local)
            assertTrue(fake.calls().contains("-s SER1 forward --remove tcp:$local"))
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    // The game was launched anew: the name it binds changed, the forward of the dialog is dead.
    // The server below plays that phone: it drops every request until the forward names the new name.
    // forward frees the port it picked when it returns: the phone's stand-in server takes it from there.
    private fun relaunch(post: Boolean): Triple<Int, List<String>, AtomicInteger> {
        val d = Files.createTempDirectory("gutapk-relaunch")
        val fake = FakeAdb(d)
        val fresh = "$prefix.${hex.reversed()}"
        val reached = AtomicInteger()
        fake.running("$prefix.$hex", pkg)
        val local = ServerAdmin.forward(fake.adb, "SER1", pkg)
        val server = phone(local) { exchange ->
            if (!fake.calls().any { it.endsWith("localabstract:$fresh") }) {
                // A dead name: nothing reaches the phone, the connection ends without an answer.
                exchange.close()
            } else {
                reached.incrementAndGet()
                val body = (if (post) "{\"ok\":true}" else "{\"mode\":\"none\",\"entries\":0,\"overrides\":0,\"origins\":{}}").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
        try {
            // The game is launched anew: it binds another name.
            fake.running(fresh, pkg)
            val code = if (post) ServerAdmin.putState(local, ByteArray(4)).status else ServerAdmin.send(local, "/status").statusCode()
            return Triple(code, fake.calls(), reached)
        } finally {
            server.stop(0)
            d.toFile().deleteRecursively()
        }
    }

    @Test
    fun aRequestAfterARelaunchFindsTheNewNameAndIsSentAgain() {
        val (code, calls, reached) = relaunch(post = false)
        assertEquals(200, code)
        assertEquals(1, reached.get())
        assertTrue(calls.any { it.endsWith("localabstract:$prefix.${hex.reversed()}") }, calls.toString())
    }

    @Test
    fun aStateSentAfterARelaunchIsSentAgainOnce() {
        val (code, calls, reached) = relaunch(post = true)
        assertEquals(200, code)
        assertEquals(1, reached.get())
        assertEquals(1, calls.count { it.endsWith("localabstract:$prefix.${hex.reversed()}") })
    }

    @Test
    fun aFailureWithTheSameNameIsTheFirstFailure() {
        val d = Files.createTempDirectory("gutapk-samename")
        val fake = FakeAdb(d)
        fake.running("$prefix.$hex", pkg)
        val local = ServerAdmin.forward(fake.adb, "SER1", pkg)
        val server = phone(local) { exchange -> exchange.close() }
        try {
            val before = fake.calls().count { it.contains(" forward tcp:") }
            assertFailsWith<IOException> { ServerAdmin.send(local, "/status") }
            // The name was read again, it had not changed: no forward was made again.
            assertEquals(before, fake.calls().count { it.contains(" forward tcp:") })
            assertEquals(2, fake.calls().count { it.contains("dumpsys") })
        } finally {
            server.stop(0)
            d.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPortThatForwardDidNotMakeIsNeverRetried() {
        val d = Files.createTempDirectory("gutapk-noretry")
        val server = phone { exchange -> exchange.close() }
        try {
            val fake = FakeAdb(d)
            fake.running("$prefix.$hex", pkg)
            assertFailsWith<IOException> { ServerAdmin.send(server.address.port, "/status") }
            assertTrue(fake.calls().isEmpty())
        } finally {
            server.stop(0)
            d.toFile().deleteRecursively()
        }
    }
}
