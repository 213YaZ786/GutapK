package io.gutapk.core.offline

import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.Engine
import io.gutapk.core.edit.SetBuild
import io.gutapk.core.edit.Tweaks
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.Hex
import io.gutapk.core.il2cpp.Patches
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The servers a game names, found in any APK, and the patches that move them
// with the length unchanged. The dex header is checked against the format
// text, https://source.android.com/docs/core/runtime/dex-format#header-item
// (checksum: Adler-32 from byte 12, signature: SHA-1 from byte 32), computed
// here with java.util.zip.Adler32 and MessageDigest apart from DexHeader.
class OfflineTest {
    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private fun withDir(block: (Path) -> Unit) {
        val d = Files.createTempDirectory("gutapk-offline")
        try {
            block(d)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    // The single port patches took before there was a port per origin.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/offline/Redirect.kt#patches
    private fun at(servers: List<Endpoint>, port: Int): Map<String, Int> = servers.associate { it.origin to port }

    // Native code: a few bytes, then each C string followed by a NUL.
    private fun lib(vararg strings: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 1, 2, 3))
        strings.forEach {
            out.write(ascii(it))
            out.write(0)
            out.write(byteArrayOf(1, 2))
        }
        return out.toByteArray()
    }

    // A header of 0x70 bytes with a wrong checksum and signature, then one
    // string as dex stores it: its length byte, its text, a NUL.
    private fun dex(url: String): ByteArray {
        val head = ByteArray(0x70)
        ascii("dex\n035").copyInto(head)
        for (i in 8 until 32) head[i] = 0x55
        for (i in 32 until 0x70) head[i] = (i * 7).toByte()
        return head + byteArrayOf(0x1D) + ascii(url) + byteArrayOf(0)
    }

    private fun zip(file: Path, entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()) {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, bytes) ->
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    private val manifest = ascii("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"/>")
    private val arm = lib("https://game.example.org/api", "http://a.b", "libc.so")
    private val x86 = lib("padding here", "https://game.example.org/api/v2")
    private val layout = ascii("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>")
    private val config = ascii("{\"base\":\"https://game.example.org/v1\",\"ns\":\"http://www.w3.org/2001/XMLSchema\"}")

    private fun fakeApk(d: Path): Path {
        val apk = d.resolve("game.apk")
        zip(
            apk,
            listOf(
                "AndroidManifest.xml" to manifest,
                "lib/arm64-v8a/libgame.so" to arm,
                "lib/x86_64/libgame.so" to x86,
                "classes.dex" to dex("wss://play.example.net:8443/socket"),
                "assets/config.json" to config,
                "assets/layout.xml" to layout,
                "res/drawable/icon.png" to ascii("PNG https://not.scanned.example.com/x"),
            ),
            stored = setOf("lib/arm64-v8a/libgame.so", "lib/x86_64/libgame.so"),
        )
        return apk
    }

    private fun entryBytes(apk: Path, name: String): ByteArray = ZipFile(apk.toFile()).use { z ->
        z.getInputStream(z.getEntry(name)).use { it.readAllBytes() }
    }

    @Test
    fun scanFindsTheOriginsOfEveryEntryKind() = withDir { d ->
        val apk = fakeApk(d)
        val found = Endpoints.scan(apk).associateBy { it.origin }
        assertEquals(
            listOf("http://schemas.android.com", "http://www.w3.org", "https://game.example.org", "wss://play.example.net:8443"),
            found.keys.sorted(),
        )
        val game = found.getValue("https://game.example.org")
        assertEquals(3, game.occurrences.size)
        game.occurrences.forEach { o ->
            val at = o.start.toInt()
            assertContentEquals(ascii("https://game.example.org"), entryBytes(apk, o.file).copyOfRange(at, at + 24))
        }
        assertEquals(
            setOf("lib/arm64-v8a/libgame.so", "lib/x86_64/libgame.so", "assets/config.json"),
            game.occurrences.map { it.file }.toSet(),
        )
        assertEquals(8L, game.occurrences.first { it.file == "lib/arm64-v8a/libgame.so" }.start)
        assertTrue("https://game.example.org/api" in game.urls)
        assertTrue("https://game.example.org/api/v2" in game.urls)
        val socket = found.getValue("wss://play.example.net:8443")
        assertEquals(listOf(Occurrence("classes.dex", 0x71L)), socket.occurrences)
        assertEquals(listOf("wss://play.example.net:8443/socket"), socket.urls)
    }

    @Test
    fun namespacesAreNotServersAndMediaIsNotScanned() = withDir { d ->
        val found = Endpoints.scan(fakeApk(d)).associateBy { it.origin }
        assertFalse(found.getValue("http://www.w3.org").likelyServer)
        assertFalse(found.getValue("http://schemas.android.com").likelyServer)
        assertTrue(found.getValue("https://game.example.org").likelyServer)
        assertTrue(found.getValue("wss://play.example.net:8443").likelyServer)
        assertFalse("https://not.scanned.example.com" in found)
        assertFalse(Endpoints.likelyServer("xmlpull.org"))
        assertFalse(Endpoints.likelyServer("sub.purl.org"))
        assertFalse(Endpoints.likelyServer("schemas.xmlsoap.org"))
        assertTrue(Endpoints.likelyServer("purl.org.example.com"))
    }

    @Test
    fun parserFileKeepsOnlyNamedHostsAndCanDropDuplicates() {
        val more = Regex("^https://[a-z.]+\\.org")
        val regex = Regex("https://[^ ]+")
        val content = "https://a.example.org/x https://a.example.org/x https://1.2.3.4/y"
        assertEquals(2, Endpoints.parser_file(content, regex, more).size)
        assertEquals(1, Endpoints.parser_file(content, regex, more, no_dup = true).size)
        assertEquals(3, Endpoints.parser_file(content, regex).size)
    }

    @Test
    fun aDexHeaderGetsItsSignatureAndChecksumBack() = withDir { d ->
        val file = d.resolve("classes.dex")
        val bytes = dex("https://game.example.org/api")
        Files.write(file, bytes)
        DexHeader.update(file)
        val fixed = Files.readAllBytes(file)
        assertContentEquals(MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)), fixed.copyOfRange(12, 32))
        val adler = Adler32().also { it.update(fixed, 12, fixed.size - 12) }.value
        assertEquals(adler, (0 until 4).sumOf { (fixed[8 + it].toLong() and 0xFF) shl (8 * it) })
        assertContentEquals(bytes.copyOfRange(32, bytes.size), fixed.copyOfRange(32, fixed.size))
        assertContentEquals(bytes.copyOfRange(0, 8), fixed.copyOfRange(0, 8))

        Files.write(file, lib("not a dex file at all"))
        assertFailsWith<IOException> { DexHeader.update(file) }
        assertTrue(DexHeader.isDex("classes.dex"))
        assertTrue(DexHeader.isDex("classes12.dex"))
        assertFalse(DexHeader.isDex("assets/classes.dex"))
        assertFalse(DexHeader.isDex("lib/arm64-v8a/libclasses.dex.so"))
    }

    // The loopback address of the same length, written by hand here.
    private fun loopback(origin: String): String {
        val prefix = if (origin.startsWith("ws")) "ws://127.0.0.1:" else "http://127.0.0.1:"
        return prefix + "4210".padStart(origin.length - prefix.length, '0')
    }

    private fun patchesFor(apk: Path, origin: String): List<BytePatch> =
        Endpoints.scan(apk).first { it.origin == origin }.occurrences.map { o ->
            val abi = if (o.file.startsWith("lib/")) o.file.split('/')[1] else "none"
            BytePatch(abi, o.start, Hex.format(ascii(origin)), Hex.format(ascii(loopback(origin))), "server $origin", o.file)
        }

    @Test
    fun patchesLandInLibrariesAssetsAndDexWithValidCrcAndHeader() = withDir { d ->
        val apk = fakeApk(d)
        val before = Files.readAllBytes(apk)
        val patches = patchesFor(apk, "https://game.example.org") + patchesFor(apk, "wss://play.example.net:8443")
        SetBuild.writeLibs(listOf(apk), patches, false, d.resolve("work"), quiet)
        assertFalse(before.contentEquals(Files.readAllBytes(apk)))

        // ZipInputStream checks every entry's data against its header.
        ZipInputStream(Files.newInputStream(apk)).use { z ->
            generateSequence { z.nextEntry }.forEach { _ -> z.readAllBytes() }
        }
        ZipFile(apk.toFile()).use { z ->
            z.entries().asSequence().forEach { e ->
                val crc = CRC32().also { c -> z.getInputStream(e).use { c.update(it.readAllBytes()) } }.value
                assertEquals(crc, e.crc, e.name)
            }
            assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libgame.so").method)
            assertEquals(ZipEntry.STORED, z.getEntry("lib/x86_64/libgame.so").method)
            assertEquals(ZipEntry.DEFLATED, z.getEntry("classes.dex").method)
        }
        val game = loopback("https://game.example.org")
        assertEquals("http://127.0.0.1:0004210", game)
        val latin = Charsets.ISO_8859_1
        assertEquals(String(arm, latin).replace("https://game.example.org", game), String(entryBytes(apk, "lib/arm64-v8a/libgame.so"), latin))
        assertEquals(String(x86, latin).replace("https://game.example.org", game), String(entryBytes(apk, "lib/x86_64/libgame.so"), latin))
        assertEquals(String(config, latin).replace("https://game.example.org", game), String(entryBytes(apk, "assets/config.json"), latin))
        assertContentEquals(manifest, entryBytes(apk, "AndroidManifest.xml"))
        assertContentEquals(layout, entryBytes(apk, "assets/layout.xml"))

        val dex = entryBytes(apk, "classes.dex")
        assertEquals(dex("wss://play.example.net:8443/socket").size, dex.size)
        assertTrue(String(dex, latin).contains(loopback("wss://play.example.net:8443") + "/socket"))
        assertContentEquals(MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size)), dex.copyOfRange(12, 32))
        val adler = Adler32().also { it.update(dex, 12, dex.size - 12) }.value
        assertEquals(adler, (0 until 4).sumOf { (dex[8 + it].toLong() and 0xFF) shl (8 * it) })

        // Scanned again, the servers are gone and the loopback is not a name.
        assertEquals(listOf("http://schemas.android.com", "http://www.w3.org"), Endpoints.scan(apk).map { it.origin }.sorted())
    }

    @Test
    fun aPatchForAFileTheApkLacksStopsTheBuild() = withDir { d ->
        val apk = fakeApk(d)
        val before = Files.readAllBytes(apk)
        val stray = BytePatch("arm64-v8a", 0, "00", "01", "x", "lib/arm64-v8a/libother.so")
        assertFailsWith<CheckFailed> { SetBuild.writeLibs(listOf(apk), listOf(stray), false, d.resolve("work"), quiet) }
        assertContentEquals(before, Files.readAllBytes(apk))
    }

    @Test
    fun applyKeysByAbiForIl2cppAndByEntryForTheRest() = withDir { d ->
        val il2cpp = d.resolve("a.bin")
        val other = d.resolve("b.bin")
        Files.write(il2cpp, ByteArray(16) { it.toByte() })
        Files.write(other, ByteArray(16) { (it + 100).toByte() })
        val libs: (String) -> Path? = {
            when (it) {
                "arm64-v8a" -> il2cpp
                "lib/arm64-v8a/libgame.so" -> other
                else -> null
            }
        }
        val a = BytePatch("arm64-v8a", 2, "02", "FF", "a")
        val b = BytePatch("arm64-v8a", 3, "67", "EE", "b", "lib/arm64-v8a/libgame.so")
        assertFailsWith<IOException> { Patches.apply(libs, listOf(a, b.copy(old = "00"))) { } }
        assertEquals(2.toByte(), Files.readAllBytes(il2cpp)[2])
        val lines = mutableListOf<String>()
        Patches.apply(libs, listOf(a, b)) { lines.add(it) }
        assertEquals(0xFF.toByte(), Files.readAllBytes(il2cpp)[2])
        assertEquals(0xEE.toByte(), Files.readAllBytes(other)[3])
        assertEquals("lib/arm64-v8a/libil2cpp.so", a.name)
        assertEquals("lib/arm64-v8a/libgame.so", b.name)
        assertEquals(2, lines.size)
    }

    // The ports parsePort of OkHttp accepts and refuses, in an address of fixed length:
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/HttpUrl.kt#parsePort
    @Test
    fun targetKeepsTheLengthOfTheOrigin() {
        assertEquals("http://127.0.0.1:0004210", Redirect.target("https://game.example.org", port = 4210))
        assertEquals("http://127.0.0.1:004210", Redirect.target("http://game.example.org", port = 4210))
        assertEquals("http://127.0.0.1:000004210", Redirect.target("https://game.example.orgxx", port = 4210))
        // Exactly the room the digits need, no zero.
        assertEquals("http://127.0.0.1:4210", Redirect.target("https://example.co.uk", port = 4210))
        // The default port is written too, or the length would change.
        assertEquals("http://10.0.2.2:00000080", Redirect.target("https://game.example.org", host = "10.0.2.2", port = 80))
        listOf("https://game.example.org", "wss://play.example.net:8443", "http://a.example.fr:81").forEach {
            assertEquals(it.length, Redirect.target(it, port = 4210)?.length, it)
        }
    }

    @Test
    fun targetUsesWsForWebSockets() {
        assertEquals("ws://127.0.0.1:000000004210", Redirect.target("wss://play.example.net:8443", port = 4210))
        assertEquals("ws://127.0.0.1:004210", Redirect.target("ws://play.example.net", port = 4210))
    }

    @Test
    fun targetRefusesWhatDoesNotFitOrIsNotAnAddress() {
        // Too short for 127.0.0.1 and the port: 0.0.0.0, two characters shorter.
        assertEquals("http://0.0.0.0:065535", Redirect.target("https://example.co.uk", port = 65535))
        assertEquals("http://0.0.0.0:6123", Redirect.target("https://lichess.org", port = 6123))
        assertNull(Redirect.target("https://lichess.org", port = 26123))
        // Too short for any of them.
        assertNull(Redirect.target("http://a.bc", port = 4210))
        assertNull(Redirect.target("https://abc.io", port = 6123))
        // A port that is not one.
        listOf(0, -1, 65536, 100000).forEach { assertNull(Redirect.target("https://game.example.org", port = it), "$it") }
        // A scheme that is not one of Endpoints'.
        assertNull(Redirect.target("ftp://game.example.org", port = 4210))
        assertNull(Redirect.target("game.example.org/api", port = 4210))
        assertNull(Redirect.target("", port = 4210))
        // A host that is not a name.
        assertNull(Redirect.target("https://game.example.org", host = "a b", port = 4210))
        assertNull(Redirect.target("https://game.example.org", host = "", port = 4210))
    }

    @Test
    fun theBuilderReadsBackTheZeroPaddedPort() {
        assertEquals("http://127.0.0.1:00004210", Redirect.Builder().scheme("HTTPS").host("127.0.0.1").port(4210).length(25).build())
        assertEquals("ws://localhost:4210", Redirect.Builder().scheme("wss").host("localhost").port(4210).build())
        assertEquals("http://localhost:80", Redirect.Builder().scheme("http").host("localhost").build())
        assertEquals(80, Redirect.defaultPort("ws"))
        assertEquals(443, Redirect.defaultPort("wss"))
        assertEquals(-1, Redirect.defaultPort("ftp"))
        assertEquals(4210, "00004210".toInt())
        assertFailsWith<IllegalArgumentException> { Redirect.Builder().scheme("ftp") }
        assertFailsWith<IllegalStateException> { Redirect.Builder().scheme("http").build() }
        assertFailsWith<IllegalArgumentException> { Redirect.Builder().scheme("http").host("a.bc").port(4210).length(10).build() }
    }

    @Test
    fun patchesCoverEveryOccurrenceAndApplyThroughTheRebuildPath() = withDir { d ->
        val apk = fakeApk(d)
        val servers = Endpoints.scan(apk).filter { it.likelyServer }
        val patches = Redirect.patches(apk, servers, at(servers, 4210))
        assertEquals(4, patches.size)
        patches.forEach { p ->
            assertEquals(p.old.length, p.new.length)
            assertTrue(p.label.startsWith("server "), p.label)
            assertEquals(p.name, p.entry)
        }
        val libs = patches.filter { it.name.startsWith("lib/") }
        assertEquals(setOf("arm64-v8a", "x86_64"), libs.map { it.abi }.toSet())
        assertEquals(Redirect.NO_ABI, patches.first { it.name == "classes.dex" }.abi)
        assertEquals(Redirect.NO_ABI, patches.first { it.name == "assets/config.json" }.abi)
        assertEquals(
            "server https://game.example.org -> http://127.0.0.1:0004210",
            patches.first { it.name == "assets/config.json" }.label,
        )
        assertEquals(
            Hex.format(ascii("ws://127.0.0.1:000000004210")),
            patches.first { it.name == "classes.dex" }.new,
        )

        SetBuild.writeLibs(listOf(apk), patches, false, d.resolve("work"), quiet)
        assertEquals(listOf("http://schemas.android.com", "http://www.w3.org"), Endpoints.scan(apk).map { it.origin }.sorted())
        val dex = entryBytes(apk, "classes.dex")
        assertContentEquals(MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size)), dex.copyOfRange(12, 32))
        // Nothing is left to redirect, and the same list no longer fits the APK.
        assertFailsWith<IOException> { Redirect.patches(apk, servers, at(servers, 4210)) }
    }

    @Test
    fun patchesRefuseAListThatIsNotThisApksAndSkipWhatCannotMove() = withDir { d ->
        val apk = fakeApk(d)
        val game = Endpoints.scan(apk).first { it.origin == "https://game.example.org" }
        val shifted = game.copy(occurrences = game.occurrences.map { it.copy(start = it.start + 1) })
        assertFailsWith<IOException> { Redirect.patches(apk, listOf(shifted), at(listOf(shifted), 4210)) }
        val elsewhere = game.copy(occurrences = listOf(Occurrence("lib/arm64-v8a/libother.so", 0)))
        assertFailsWith<IOException> { Redirect.patches(apk, listOf(elsewhere), at(listOf(elsewhere), 4210)) }
        val tooShort = Endpoint("http://a.bc", listOf(Occurrence("assets/config.json", 0)), emptyList(), true)
        assertEquals(emptyList(), Redirect.patches(apk, listOf(tooShort), at(listOf(tooShort), 4210)))
        assertEquals(emptyList(), Redirect.patches(apk, listOf(game), at(listOf(game), 70000)))
    }

    @Test
    fun aRebuildRefusesADexPatchBeforeRunningAnyTool() = withDir { d ->
        val dexPatch = BytePatch("none", 0x71, "00", "01", "x", "classes.dex")
        val tweaks = Tweaks(cleartextTraffic = true, bytePatches = listOf(dexPatch))
        val e = assertFailsWith<CheckFailed> {
            Edit.rebuild(d.resolve("none.jar"), Engine.APKTOOL, d.resolve("in.apk"), tweaks, d.resolve("work"), d.resolve("out.apk"), 4, quiet) { false }
        }
        assertTrue("classes.dex" in (e.message ?: ""), e.message)
    }

    @Test
    fun theExportedTextKeepsReadingOldFilesAndTheEntry() = withDir { d ->
        val old = listOf("# note", "arm64-v8a\t102\t03 04\tAA BB\tT m")
        assertEquals(listOf(BytePatch("arm64-v8a", 0x102, "03 04", "AA BB", "T m")), Patches.parse(old))
        val plain = BytePatch("arm64-v8a", 0x10, "00", "01", "A b")
        val named = BytePatch("none", 0x20, "41", "42", "C d", "classes2.dex")
        val file = d.resolve("p.tsv")
        Patches.export(file, "x.y", null, listOf(plain, named))
        val lines = Files.readAllLines(file)
        assertTrue(lines.any { it == "arm64-v8a\t10\t00\t01\tA b" })
        assertEquals(listOf(plain, named), Patches.parse(lines))
    }
}
