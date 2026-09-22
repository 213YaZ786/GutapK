package io.gutapk

import io.gutapk.core.apk.ApkReader
import io.gutapk.core.apk.BinaryXml
import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.Signatures
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.TestKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.Storage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Builds a tiny binary manifest and resource table byte by byte, in the
// layout aapt2 writes, so the readers are tested without shipping anyone's
// APK in the repository. The same layout was checked against a real APK.
class ApkTest {

    private class Out {
        private val bytes = ByteArrayOutputStream()
        val size: Int get() = bytes.size()

        fun u8(v: Int) = apply { bytes.write(v and 0xff) }
        fun u16(v: Int) = apply { bytes.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()) }
        fun u32(v: Long) = apply { bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()) }
        fun u32(v: Int) = u32(v.toLong())
        fun raw(b: ByteArray) = apply { bytes.write(b) }
        fun bytes(): ByteArray = bytes.toByteArray()
    }

    private val none = 0xffffffffL

    private fun pool(strings: List<String>): ByteArray {
        val data = Out()
        val offsets = strings.map { s ->
            val at = data.size
            data.u16(s.length).raw(s.toByteArray(Charsets.UTF_16LE)).u16(0)
            at
        }
        while (data.size % 4 != 0) data.u8(0)
        val header = 28
        val body = Out()
        offsets.forEach { body.u32(it) }
        body.raw(data.bytes())
        return Out().u16(0x0001).u16(header).u32(header + body.size)
            .u32(strings.size).u32(0).u32(0).u32(header + 4 * strings.size).u32(0)
            .raw(body.bytes()).bytes()
    }

    private class Attr(val name: Int, val type: Int, val data: Long, val raw: Long)

    private fun start(name: Int, attrs: List<Attr>): ByteArray {
        val body = Out().u32(1).u32(none)
            .u32(none).u32(name).u16(20).u16(20).u16(attrs.size).u16(0).u16(0).u16(0)
        attrs.forEach { a -> body.u32(none).u32(a.name).u32(a.raw).u16(8).u8(0).u8(a.type).u32(a.data) }
        return Out().u16(0x0102).u16(16).u32(8 + body.size).raw(body.bytes()).bytes()
    }

    private fun end(name: Int): ByteArray {
        val body = Out().u32(1).u32(none).u32(none).u32(name)
        return Out().u16(0x0103).u16(16).u32(8 + body.size).raw(body.bytes()).bytes()
    }

    private fun manifest(): ByteArray {
        val strings = listOf(
            "manifest", "package", "com.example.app", "versionName", "1.2", "application", "label",
            "uses-permission", "name", "android.permission.INTERNET", "versionCode",
        )
        val resMap = listOf(0, 0, 0, 0x0101021c, 0, 0, 0x01010001, 0, 0x01010003, 0, 0x0101021b)
        val body = Out().raw(pool(strings))
        val map = Out()
        resMap.forEach { map.u32(it) }
        body.u16(0x0180).u16(8).u32(8 + map.size).raw(map.bytes())
        body.raw(start(0, listOf(Attr(1, 0x03, 2, 2), Attr(3, 0x03, 4, 4), Attr(10, 0x10, 7, none))))
        body.raw(start(5, listOf(Attr(6, 0x01, 0x7f010000, none))))
        body.raw(end(5))
        body.raw(start(7, listOf(Attr(8, 0x03, 9, 9))))
        body.raw(end(7))
        body.raw(end(0))
        return Out().u16(0x0003).u16(8).u32(8 + body.size).raw(body.bytes()).bytes()
    }

    private fun table(label: String): ByteArray {
        val global = pool(listOf(label))
        val typePool = pool(listOf("string"))
        val keyPool = pool(listOf("app_name"))
        val entries = Out().u16(8).u16(0).u32(0).u16(8).u8(0).u8(0x03).u32(0)
        val header = 20 + 64
        val offsets = Out().u32(0)
        val type = Out().u16(0x0201).u16(header).u32(header + offsets.size + entries.size)
            .u8(1).u8(0).u16(0).u32(1).u32(header + offsets.size)
            .u32(64).raw(ByteArray(60))
            .raw(offsets.bytes()).raw(entries.bytes()).bytes()
        val pkgHeader = 288
        val pkgBody = Out().raw(typePool).raw(keyPool).raw(type)
        val name = ByteArray(256).also { "p".toByteArray(Charsets.UTF_16LE).copyInto(it) }
        val pkg = Out().u16(0x0200).u16(pkgHeader).u32(pkgHeader + pkgBody.size).u32(0x7f)
            .raw(name).u32(pkgHeader).u32(0).u32(pkgHeader + typePool.size).u32(0).u32(0)
            .raw(pkgBody.bytes()).bytes()
        val body = Out().raw(global).raw(pkg)
        return Out().u16(0x0002).u16(12).u32(12 + body.size).u32(1).raw(body.bytes()).bytes()
    }

    private fun apk(file: Path) {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
            put("AndroidManifest.xml", manifest())
            put("resources.arsc", table("My App"))
            put("classes.dex", ByteArray(4))
            put("classes2.dex", ByteArray(4))
            put("lib/arm64-v8a/libil2cpp.so", ByteArray(4))
            put("assets/bin/Data/Managed/Metadata/global-metadata.dat", ByteArray(4))
        }
    }

    @Test
    fun readsManifestAndTable() {
        val xml = BinaryXml.parse(manifest())
        assertEquals(listOf("manifest", "application", "uses-permission"), xml.map { it.name })
        assertEquals(listOf(1, 2, 2), xml.map { it.depth })
        assertEquals("My App", ResourceTable.parse(table("My App")).label(0x7f010000))
    }

    @Test
    fun refusesWhatIsNotBinaryXml() {
        assertFailsWith<Exception> { BinaryXml.parse("<manifest/>".toByteArray()) }
        assertFailsWith<Exception> { BinaryXml.parse(manifest().copyOf(40)) }
    }

    private fun withDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("gutapk-apk")
        try {
            block(dir)
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun readsAnApk() = withDir { dir ->
        val file = dir.resolve("app.apk")
        apk(file)
        val info = ApkReader.read(file)
        assertEquals("com.example.app", info.packageName)
        assertEquals("My App", info.label)
        assertEquals("1.2", info.versionName)
        assertEquals(7L, info.versionCode)
        assertEquals(listOf("android.permission.INTERNET"), info.permissions)
        assertEquals(2, info.dexCount)
        assertEquals(listOf("arm64-v8a"), info.abis)
        assertTrue("Unity IL2CPP" in info.engines)

        // Unsigned is a result to show, not a crash.
        assertFalse(Signatures.verify(file).verified)
    }

    // The whole signing path on a real zip: apksig signs with the bundled
    // test key, the result verifies, and the signer is AOSP's test key.
    @Test
    fun signsWithTheTestKey() = withDir { dir ->
        val source = dir.resolve("app.apk")
        apk(source)
        val out = ApkSigning.output(dir, "com.example.app", "1.2", KeyChoice.TEST)
        val quiet = object : JobSink {
            override fun emit(event: JobEvent) {}
        }

        val check = ApkSigning.sign(source, out, TestKey.load(), 21, "test", quiet)

        assertTrue(check.verified, check.problems.joinToString())
        assertEquals(listOf("v1", "v2", "v3"), check.schemes.filter { it != "v3.1" })
        assertEquals(TestKey.CERT_SHA256, check.signers.single().sha256)
        assertTrue(Files.isRegularFile(out))
        assertFalse(Files.exists(out.resolveSibling(out.fileName.toString() + ".part")))
        assertEquals(listOf("v2", "v3"), ApkSigning.schemesFor(24))
    }

    @Test
    fun importCopiesUnderTheRoot() = withDir { dir ->
        val source = dir.resolve("app.apk")
        apk(source)
        val root = dir.resolve("root")
        Storage.SUBDIRS.forEach { Files.createDirectories(root.resolve(it)) }
        val quiet = object : JobSink {
            override fun emit(event: JobEvent) {}
        }

        val first = Packages.importApk(root, source, quiet)
        val again = Packages.importApk(root, source, quiet)

        assertEquals(first, again)
        assertTrue(first.startsWith(root.resolve("packages")))
        assertTrue(Files.isRegularFile(first.resolve(Packages.ORIGINAL)))
        assertEquals(listOf("My App"), Packages.recent(root, 6).map { it.label })
    }
}
