package io.gutapk

import io.gutapk.core.apk.ApkReader
import io.gutapk.core.apk.BinaryXml
import io.gutapk.core.apk.DexClasses
import io.gutapk.core.apk.IconCache
import io.gutapk.core.apk.IconArt
import io.gutapk.core.apk.IconLayer
import io.gutapk.core.apk.RemoteIcon
import io.gutapk.core.apk.VectorArt
import io.gutapk.core.apk.IconArtReader
import io.gutapk.core.apk.IconColour
import io.gutapk.core.apk.IconGroup
import io.gutapk.core.apk.IconKind
import io.gutapk.core.apk.IconPath
import io.gutapk.core.apk.XmlAttr
import io.gutapk.core.apk.XmlElement
import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.PartialZip
import io.gutapk.core.apk.RangeReader
import io.gutapk.core.apk.RemoteApk
import io.gutapk.core.apk.RemoteIcons
import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.Signatures
import io.gutapk.core.apk.Tracker
import io.gutapk.core.apk.Trackers
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import com.android.apksig.SigningCertificateLineage
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.Der
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.SigningKey
import io.gutapk.core.sign.Subject
import io.gutapk.core.sign.TestKey
import io.gutapk.core.sign.fingerprint
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.Storage
import io.gutapk.ui.isOwnCertificate
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
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

    // A valid empty DEX 035: 0x70 header plus a map with HEADER_ITEM and
    // MAP_LIST, the two entries APKEditor's reader requires. Built here so
    // the fixture stays a few lines and the repository ships no real APK.
    private fun emptyDex(): ByteArray {
        val header = 0x70
        val entries = listOf(Triple(0x0000, 1, 0), Triple(0x1000, 1, header))
        val size = header + 4 + entries.size * 12
        val out = ByteArray(size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val magic = byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00)
        magic.copyInto(out)
        bb.putInt(0x20, size)          // file_size
        bb.putInt(0x24, header)        // header_size
        bb.putInt(0x28, 0x12345678)    // endian_tag
        bb.putInt(0x34, header)        // map_off
        bb.putInt(0x38, size - header) // data_size
        bb.putInt(0x3c, header)        // data_off
        bb.position(header)
        bb.putInt(entries.size)
        entries.forEach { (type, count, off) ->
            bb.putShort(type.toShort())
            bb.putShort(0)
            bb.putInt(count)
            bb.putInt(off)
        }
        val sig = java.security.MessageDigest.getInstance("SHA-1").digest(out.copyOfRange(0x20, size))
        System.arraycopy(sig, 0, out, 0x0c, 20)
        val adler = java.util.zip.Adler32().apply { update(out, 0x0c, size - 0x0c) }.value
        bb.putInt(0x08, adler.toInt())
        return out
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
            put("classes.dex", emptyDex())
            put("classes2.dex", emptyDex())
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
        assertEquals(IconKind.NONE, info.iconKind)

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
        // The signer view: the certificate itself, its digest, and the schemes
        // that carry it (a single key signed every verified scheme). ApkSignerTool's
        // verify prints this certificate once per signer.
        // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#verify
        val signer = check.signers.single()
        val certificate = TestKey.load().certificate
        assertEquals(certificate, signer.certificate)
        assertEquals(fingerprint(certificate), signer.sha256)
        assertEquals(check.schemes, signer.schemes)
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

    // A group, a path inside it, a path after it: the flat list the reader
    // gives is rebuilt by depth. An RGB colour is opaque, a system colour
    // keeps its palette and tone for the UI to resolve.
    @Test
    fun rebuildsAVectorIcon() {
        fun f(v: Float) = java.lang.Float.floatToRawIntBits(v)
        val xml = listOf(
            XmlElement(1, "vector", listOf(XmlAttr(0x01010402, "viewportWidth", 0x04, f(108f), null), XmlAttr(0x01010403, "viewportHeight", 0x04, f(108f), null))),
            XmlElement(2, "group", listOf(XmlAttr(0x0101045a, "translateX", 0x04, f(10f), null))),
            XmlElement(3, "path", listOf(XmlAttr(0x01010405, "pathData", 0x03, 0, "M0,0h10v10z"), XmlAttr(0x01010404, "fillColor", 0x1d, 0x00ff0000, null))),
            XmlElement(2, "path", listOf(XmlAttr(0x01010405, "pathData", 0x03, 0, "M1,1h2v2z"), XmlAttr(0x01010404, "fillColor", 0x01, 0x0106003a, null))),
        )
        val art = IconArtReader.vector(xml, null)
        assertEquals(108f, art.viewportWidth)
        assertEquals(2, art.root.children.size)
        val group = art.root.children[0] as IconGroup
        assertEquals(10f, group.translateX)
        val red = group.children.single() as IconPath
        assertEquals(IconColour.Argb(0xffff0000.toInt()), red.fill)
        val themed = art.root.children[1] as IconPath
        assertEquals(IconColour.System(2, 90), themed.fill)
    }

    // A dex with only what DexClasses reads: the string and type id tables.
    // Types that are not classes, a bad index and an offset past the end are
    // skipped, not fatal.
    private fun dex(strings: List<String>, types: List<Int>): ByteArray {
        val header = 0x70
        val stringIds = header
        val typeIds = stringIds + 4 * strings.size
        val dataAt = typeIds + 4 * types.size
        val data = ByteArrayOutputStream()
        val offsets = strings.map { str ->
            val at = dataAt + data.size()
            val bytes = str.toByteArray(Charsets.UTF_8)
            data.write(bytes.size)
            data.write(bytes)
            data.write(0)
            at
        }
        val out = ByteArray(dataAt + data.size())
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00).copyInto(out)
        bb.putInt(0x38, strings.size)
        bb.putInt(0x3c, stringIds)
        bb.putInt(0x40, types.size)
        bb.putInt(0x44, typeIds)
        offsets.forEachIndexed { i, o -> bb.putInt(stringIds + 4 * i, o) }
        types.forEachIndexed { i, t -> bb.putInt(typeIds + 4 * i, t) }
        data.toByteArray().copyInto(out, dataAt)
        return out
    }

    @Test
    fun readsClassNamesFromDex() {
        // A class descriptor ends in 0x3b, written as a char code to keep the
        // source free of that character.
        fun cls(name: String) = "L" + name + Char(0x3b)
        val strings = listOf("I", cls("com/example/Main"), "[" + cls("com/example/Main"), cls("com/flurry/android/FlurryAgent"), "V")
        val names = DexClasses.names(dex(strings, listOf(0, 1, 2, 3, 4, 99)))
        assertEquals(listOf("com.example.Main", "com.flurry.android.FlurryAgent"), names)
        assertEquals(emptyList(), DexClasses.names(ByteArray(0x70)))
        assertEquals(emptyList(), DexClasses.names("not a dex at all".toByteArray()))
    }

    // Prefixes are matched by binary search on sorted names: before, after
    // and between classes, and a prefix longer than any class.
    @Test
    fun detectsTrackersByPrefix() {
        val classes = listOf("com.example.Main", "com.flurry.android.FlurryAgent", "com.google.firebase.analytics.FirebaseAnalytics").sorted()
        val flurry = Tracker("Flurry", listOf("Analytics"), listOf("com.flurry."))
        val firebase = Tracker("Firebase Analytics", listOf("Analytics"), listOf("com.google.firebase.analytics.", "com.google.android.gms.measurement."))
        val absent = Tracker("Absent", emptyList(), listOf("com.absent.", "com.google.firebase.analytics.FirebaseAnalyticsX", "zzz."))
        assertEquals(listOf("Flurry", "Firebase Analytics"), Trackers.detect(classes, listOf(flurry, firebase, absent)).map { it.name })
        assertEquals(emptyList(), Trackers.detect(emptyList(), listOf(flurry)))
    }

    // A phone's APK read in pieces, here from a local file through the same
    // reader interface, with a counter to show the whole file is not read.
    @Test
    fun readsAnApkInPieces() = withDir { dir ->
        // Larger than the 64 KB read at the end, with an asset that must
        // never be fetched.
        val file = dir.resolve("app.apk")
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
            put("AndroidManifest.xml", manifest())
            put("assets/big.bin", ByteArray(400_000).also { java.util.Random(7).nextBytes(it) })
            put("resources.arsc", table("My App"))
            put("classes.dex", emptyDex())
        }
        val bytes = Files.readAllBytes(file)
        var read = 0L
        val reader = RangeReader { offset, length ->
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            read += end - offset
            bytes.copyOfRange(offset.toInt(), end)
        }
        val entries = PartialZip.directory(bytes.size.toLong(), reader)
        java.util.zip.ZipFile(file.toFile()).use { zip ->
            assertEquals(zip.entries().toList().map { it.name }, entries.keys.toList())
            listOf("AndroidManifest.xml", "classes.dex").forEach { name ->
                val whole = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                assertTrue(whole.contentEquals(PartialZip.entry(entries.getValue(name), reader)), name)
            }
        }

        read = 0
        val icon = RemoteIcons.read(RemoteApk(bytes.size.toLong(), reader), dir.resolve("work"))
        assertEquals("My App", icon.label)
        // The test manifest names no icon, as Google Services Framework.
        assertFalse(icon.declared)
        // Manifest and table only: no dex, no library, no asset.
        assertTrue(read < bytes.size, "read $read of ${bytes.size}")
        assertFailsWith<io.gutapk.core.apk.ApkFormatError> { PartialZip.directory(8, RangeReader { _, _ -> ByteArray(8) }) }
    }

    // A vector icon with a nested group and a Material You colour, and a
    // bitmap one, back from the cache as they went in.
    @Test
    fun cachesPhoneIcons() = withDir { dir ->
        val path = IconPath("M0,0L108,108", IconColour.System(2, 40), 1f, IconColour.Argb(0xff112233.toInt()), 2f, 0.5f, true)
        val group = IconGroup(15f, 54f, 54f, 1f, 1f, 0f, 0f, listOf(IconGroup(0f, 0f, 0f, 1f, 1f, 3f, 4f, listOf(path))))
        val art = IconArt(IconLayer.Colour(IconColour.Argb(-1)), IconLayer.Vector(VectorArt(108f, 108f, group)), adaptive = true)
        val file = assertNotNullOrFail(IconCache.file(dir, "com.example.app", 12345))
        IconCache.write(file, RemoteIcon("Mon appli", null, art, declared = true))
        val back = assertNotNullOrFail(IconCache.read(file))
        assertEquals("Mon appli", back.label)
        val fg = (back.art?.foreground as IconLayer.Vector).art
        val inner = (fg.root.children.single() as IconGroup).children.single() as IconPath
        assertEquals("M0,0L108,108", inner.data)
        assertEquals(IconColour.System(2, 40), inner.fill)
        assertEquals(IconColour.Argb(0xff112233.toInt()), inner.stroke)
        assertTrue(inner.evenOdd)
        assertEquals(15f, fg.root.rotation)
        assertEquals(IconLayer.Colour(IconColour.Argb(-1)), back.art?.background)

        val png = assertNotNullOrFail(IconCache.file(dir, "com.example.other", 1))
        IconCache.write(png, RemoteIcon(null, byteArrayOf(1, 2, 3), null, declared = false))
        val b = assertNotNullOrFail(IconCache.read(png))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(b.bitmap))
        assertFalse(b.declared)
        assertEquals(null, IconCache.file(dir, "../evil", 1))
        Files.writeString(png, "not a cache entry")
        assertEquals(null, IconCache.read(png))
    }

    private fun <T> assertNotNullOrFail(v: T?): T = kotlin.test.assertNotNull(v)

    // A restored key must be the one its certificate names. A certificate
    // from another key is refused, whatever the file claims.
    @Test
    fun ownKeyMatchesOnlyItsCertificate() {
        val random = java.security.SecureRandom()
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, random)
        val a = gen.generateKeyPair()
        val b = gen.generateKeyPair()
        val certA = io.gutapk.core.sign.Der.selfSigned(a, io.gutapk.core.sign.Subject("A"), 1, random)
        assertTrue(io.gutapk.core.sign.OwnKey.matches(a.private, certA))
        assertFalse(io.gutapk.core.sign.OwnKey.matches(b.private, certA))
    }

    private fun testKeys(): Pair<java.security.KeyPair, java.security.SecureRandom> {
        val random = java.security.SecureRandom()
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, random)
        return gen.generateKeyPair() to random
    }

    // The encoded name read back by hand: one (tag, text) per RDN, in order.
    // Each RDN is a SET of one SEQUENCE of an OID and a string.
    private fun rdnValues(name: ByteArray): List<Pair<Int, String>> {
        var at = 0
        fun length(): Int {
            val first = name[at++].toInt() and 0xff
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7f) { n = (n shl 8) or (name[at++].toInt() and 0xff) }
            return n
        }
        assertEquals(0x30, name[at++].toInt())
        val end = at + length()
        val out = ArrayList<Pair<Int, String>>()
        while (at < end) {
            assertEquals(0x31, name[at++].toInt())
            length()
            assertEquals(0x30, name[at++].toInt())
            length()
            assertEquals(0x06, name[at++].toInt())
            val oidSize = length()
            at += oidSize
            val tag = name[at++].toInt()
            val size = length()
            out.add(tag to String(name, at, size, Charsets.UTF_8))
            at += size
        }
        return out
    }

    // The JDK is the oracle: its RFC 2253 string, its own parse of that
    // string and its encoded name, C first and CN last.
    @Test
    fun selfSignedCarriesEveryField() {
        val (keys, random) = testKeys()
        val dn = "CN=Yazid X,OU=Dev,O=Example,L=Lyon,ST=Rhone,C=FR"
        val subject = io.gutapk.core.sign.Subject("Yazid X", "Dev", "Example", "Lyon", "Rhone", "FR")
        val cert = io.gutapk.core.sign.Der.selfSigned(keys, subject, 1, random)
        assertEquals(dn, cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253))
        assertEquals(javax.security.auth.x500.X500Principal(dn), cert.subjectX500Principal)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        val expected = listOf(0x13 to "FR", 0x0c to "Rhone", 0x0c to "Lyon", 0x0c to "Example", 0x0c to "Dev", 0x0c to "Yazid X")
        assertEquals(expected, rdnValues(cert.subjectX500Principal.encoded))
        assertEquals(listOf("FR", "Rhone", "Lyon", "Example", "Dev", "Yazid X"), rdnValues(javax.security.auth.x500.X500Principal(dn).encoded).map { it.second })
    }

    @Test
    fun selfSignedDropsEmptyFieldsAndTrims() {
        val (keys, random) = testKeys()
        val cert = io.gutapk.core.sign.Der.selfSigned(keys, io.gutapk.core.sign.Subject("  x ", o = "  ", c = "FR"), 1, random)
        assertEquals("CN=x,C=FR", cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253))
        assertEquals(listOf(0x13 to "FR", 0x0c to "x"), rdnValues(cert.subjectX500Principal.encoded))
    }

    @Test
    fun selfSignedKeepsUnicodeNames() {
        val (keys, random) = testKeys()
        for (name in listOf("Yazid Ɣ ⵣ", "Zoé Çelik", "213ⵣ786")) {
            val cert = io.gutapk.core.sign.Der.selfSigned(keys, io.gutapk.core.sign.Subject(name, o = "Équipe"), 1, random)
            assertEquals("CN=$name,O=Équipe", cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253))
            assertEquals(listOf(0x0c to "Équipe", 0x0c to name), rdnValues(cert.subjectX500Principal.encoded))
        }
    }

    @Test
    fun selfSignedRefusesWhatTheStandardRefuses() {
        val (keys, random) = testKeys()
        val s = io.gutapk.core.sign.Subject("x")
        for (country in listOf("FRA", "F", "Fé", "fr", "F1", "1A")) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(country) {
                io.gutapk.core.sign.Der.selfSigned(keys, s.copy(c = country), 1, random)
            }
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            io.gutapk.core.sign.Der.selfSigned(keys, io.gutapk.core.sign.Subject("x".repeat(65)), 1, random)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            io.gutapk.core.sign.Der.selfSigned(keys, io.gutapk.core.sign.Subject(""), 1, random)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            io.gutapk.core.sign.Der.selfSigned(keys, io.gutapk.core.sign.Subject("   "), 1, random)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { io.gutapk.core.sign.Der.selfSigned(keys, s.copy(o = "o".repeat(65)), 1, random) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { io.gutapk.core.sign.Der.selfSigned(keys, s.copy(ou = "o".repeat(65)), 1, random) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { io.gutapk.core.sign.Der.selfSigned(keys, s.copy(l = "l".repeat(129)), 1, random) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { io.gutapk.core.sign.Der.selfSigned(keys, s.copy(st = "s".repeat(129)), 1, random) }
        // The bounds themselves are accepted.
        val edge = io.gutapk.core.sign.Subject("x".repeat(64), "u".repeat(64), "o".repeat(64), "l".repeat(128), "s".repeat(128), "FR")
        assertEquals(6, rdnValues(io.gutapk.core.sign.Der.selfSigned(keys, edge, 1, random).subjectX500Principal.encoded).size)
    }

    // The table lists size and signer, then only what differs.
    @Test
    fun comparesBeforeAndAfter() {
        val a = io.gutapk.core.apk.ApkInfo(
            packageName = "com.a", versionName = "1.0", versionCode = 1, minSdk = 21, targetSdk = 30, label = "Game",
            iconPath = null, iconArt = null, iconKind = io.gutapk.core.apk.IconKind.ADAPTIVE,
            predictiveBack = false, hasLocaleConfig = false, nativeLibsFromApk = false, allowsBackup = true,
            debuggable = false, fragileUserData = false, memoryTagging = false, split = null,
            permissions = listOf("android.permission.INTERNET", "android.permission.READ_CONTACTS"),
            languages = listOf("de", "fr"), components = listOf("a.Main"), dexCount = 2,
            abis = listOf("arm64-v8a", "armeabi-v7a"), nativeLibs = 4, engines = emptyList(), entries = 100,
        )
        val b = a.copy(label = "Mine", targetSdk = 35, permissions = listOf("android.permission.INTERNET"), abis = listOf("arm64-v8a"), allowsBackup = false)
        val c = io.gutapk.core.apk.Compare.changes(a, b, 1000, 800, "AA", "BB")
        assertEquals(listOf("size", "signer", "label", "target_sdk", "permissions_removed", "abis", "backup"), c.map { it.key })
        assertEquals(listOf("android.permission.READ_CONTACTS"), c.first { it.key == "permissions_removed" }.before)
        assertEquals(listOf("size", "signer"), io.gutapk.core.apk.Compare.changes(a, a, 1, 1, "AA", "AA").map { it.key })
    }

    // getDName and getSubject are inverse: UTF8String fields, accents, the
    // PrintableString country, and an absent field read back as "".
    @Test
    fun subjectReadsBackWhatWasWritten() {
        val (keys, random) = testKeys()
        val subjects = listOf(
            Subject("Yazid Ɣ ⵣ", "Équipe", "Société Ünïcode", "Zürich", "Île-de-France", "FR"),
            Subject("213ⵣ786"),
            Subject("x", o = "Example, Inc.", c = "US"),
            Subject("x".repeat(64), "u".repeat(64), "o".repeat(64), "l".repeat(128), "s".repeat(128), "DE"),
        )
        for (subject in subjects) {
            val cert = Der.selfSigned(keys, subject, 1, random)
            assertEquals(subject, Der.getSubject(cert))
        }
        assertEquals(Subject("x", c = "FR"), Der.getSubject(Der.selfSigned(keys, Subject("  x ", o = "  ", c = "FR"), 1, random)))
        // A name written by another tool, with an e-mail field Subject does not carry.
        assertEquals(Subject("Android", "Android", "Android", "Mountain View", "California", "US"), Der.getSubject(TestKey.load().certificate))
    }

    // The test key's six names, read by Der.getSubject, equal the JDK's own
    // reading of the same certificate, so the identity dialog shows the real ones.
    // https://raw.githubusercontent.com/openjdk/jdk/jdk-21-ga/src/java.naming/share/classes/javax/naming/ldap/LdapName.java#getRdns
    @Test
    fun testKeySubjectMatchesTheJdk() {
        val cert = TestKey.load().certificate
        val jdk = javax.naming.ldap.LdapName(cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253))
            .rdns.associate { it.type.uppercase() to it.value.toString() }
        val subject = Der.getSubject(cert)
        val read = mapOf("C" to subject.c, "ST" to subject.st, "L" to subject.l, "O" to subject.o, "OU" to subject.ou, "CN" to subject.cn)
        for ((type, value) in read) {
            assertEquals(jdk[type], value, type)
            assertTrue(value.isNotEmpty(), type)
        }
    }

    private val silent = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private fun lineageOf(keys: java.security.KeyPair, first: X509Certificate, vararg next: X509Certificate): SigningCertificateLineage {
        fun config(cert: X509Certificate) = SigningCertificateLineage.SignerConfig.Builder(KeyConfig.Jca(keys.private), cert).build()
        var lineage = SigningCertificateLineage.Builder(config(first), config(next[0])).build()
        for (i in 1 until next.size) lineage = lineage.spawnDescendant(config(next[i - 1]), config(next[i]))
        return lineage
    }

    // apksig's SigningCertificateLineageTest protocol: write the lineage,
    // read it back, same certificates in the same order.
    @Test
    fun lineageRoundTripsThroughBytes() {
        val (keys, random) = testKeys()
        val a = Der.selfSigned(keys, Subject("A"), 1, random)
        val b = Der.selfSigned(keys, Subject("B"), 1, random)
        val c = Der.selfSigned(keys, Subject("C"), 1, random)
        assertEquals(a.publicKey, b.publicKey)
        val lineage = lineageOf(keys, a, b, c)
        assertEquals(listOf(a, b, c), lineage.certificatesInLineage)
        val back = SigningCertificateLineage.readFromBytes(lineage.bytes)
        assertEquals(listOf(a, b, c), back.certificatesInLineage)
        assertEquals(2, back.getSubLineage(b).size())
        assertEquals(1, back.getSubLineage(a).size())
    }

    // An APK signed with A, then signed again with [A, B] and the lineage:
    // apksig's verifier sees A in v1 and v2, A in v3 and B in v3.1, the
    // lineage A then B, and GutapK's own check agrees.
    @Test
    fun signsWithTwoCertificatesAndALineage() = withDir { dir ->
        val (keys, random) = testKeys()
        val a = Der.selfSigned(keys, Subject("Old name"), 1, random)
        val b = Der.selfSigned(keys, Subject("New name", c = "FR"), 2, random)
        val source = dir.resolve("app.apk")
        apk(source)
        val first = dir.resolve("a.apk")
        val second = dir.resolve("b.apk")

        ApkSigning.sign(source, first, SigningKey("CERT", keys.private, a), 21, "test", silent)
        val check = ApkSigning.sign(first, second, SigningKey("CERT", keys.private, b, lineageOf(keys, a, b)), 21, "test", silent)

        assertTrue(check.verified, check.problems.joinToString())
        assertTrue(Signatures.verify(second, 21).verified)
        val result = ApkVerifier.Builder(second.toFile()).setMinCheckedPlatformVersion(21).build().verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertTrue(result.isVerifiedUsingV1Scheme && result.isVerifiedUsingV2Scheme && result.isVerifiedUsingV3Scheme)
        assertTrue(result.isVerifiedUsingV31Scheme)
        assertEquals(a, result.v1SchemeSigners.single().certificate)
        assertEquals(a, result.v2SchemeSigners.single().certificate)
        assertEquals(a, result.v3SchemeSigners.single().certificate)
        assertEquals(b, result.v31SchemeSigners.single().certificate)
        assertEquals(listOf(a, b), result.v31SchemeSigners.single().signingCertificateLineage.certificatesInLineage)
        assertEquals(listOf(a, b), result.signingCertificateLineage.certificatesInLineage)
        // GutapK's own list: the rotated certificate first as apksig's
        // signerCertificates gives it (what Sign.kt reads), with v3.1 among its
        // schemes, then the old one, as apksigner prints both.
        // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#verify
        val signers = Signatures.verify(second, 21).signers
        assertEquals(listOf(b, a), signers.map { it.certificate })
        assertEquals(listOf("v3.1"), signers[0].schemes)
        assertEquals(listOf("v1", "v2", "v3"), signers[1].schemes)
        // A bare B, without the lineage, is a different signer: Android would refuse the update.
        ApkSigning.sign(source, dir.resolve("c.apk"), SigningKey("CERT", keys.private, b), 21, "test", silent)
        val bare = ApkVerifier.Builder(dir.resolve("c.apk").toFile()).setMinCheckedPlatformVersion(21).build().verify()
        assertEquals(listOf(b), bare.v3SchemeSigners.map { it.certificate })
    }

    // The folder, the keyring and the key size are the test's: nothing of
    // the user's config folder or Secret Service is touched. The keyring
    // answers like the Secret Service, an item matches when it holds every
    // attribute asked for.
    private class MemoryRing {
        val items = LinkedHashMap<Map<String, String>, ByteArray>()

        fun store(label: String, attributes: Map<String, String>, secret: ByteArray) {
            items[attributes] = secret.copyOf()
        }

        fun lookup(attributes: Map<String, String>): ByteArray? =
            items.entries.firstOrNull { e -> attributes.all { e.key[it.key] == it.value } }?.value?.copyOf()
    }

    private fun withOwnKey(block: (Path, MemoryRing, MutableList<String>) -> Unit) = withDir { dir ->
        val ring = MemoryRing()
        val warnings = ArrayList<String>()
        val folder = OwnKey.folder
        val store = OwnKey.keyringStore
        val lookup = OwnKey.keyringLookup
        val bits = OwnKey.bits
        val warn = OwnKey.warn
        OwnKey.folder = dir.resolve("config")
        OwnKey.keyringStore = ring::store
        OwnKey.keyringLookup = ring::lookup
        OwnKey.bits = 2048
        OwnKey.warn = { warnings.add(it) }
        try {
            block(dir, ring, warnings)
        } finally {
            OwnKey.folder = folder
            OwnKey.keyringStore = store
            OwnKey.keyringLookup = lookup
            OwnKey.bits = bits
            OwnKey.warn = warn
        }
    }

    private fun useFreshMachine(dir: Path, name: String): MemoryRing {
        val ring = MemoryRing()
        OwnKey.folder = dir.resolve(name)
        OwnKey.keyringStore = ring::store
        OwnKey.keyringLookup = ring::lookup
        return ring
    }

    // apksig's SigningCertificateLineageTest protocol: a lineage read from its
    // file holds its signers and not an unknown one, an invalid file fails.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/test/java/com/android/apksig/SigningCertificateLineageTest.java#testLineageFromFileContainsExpectedSigners
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/test/java/com/android/apksig/SigningCertificateLineageTest.java#testLineageFromFileDoesNotContainUnknownSigner
    // not ported: the rotation, capability and v3 attribute tests (apksig's own, not GutapK's)
    @Test
    fun ownCertificateIsFalseWithoutAKey() = withOwnKey { _, _, _ ->
        assertFalse(isOwnCertificate(TestKey.load().certificate))
    }

    @Test
    fun ownCertificateIsTheCreatedKeyOnly() = withOwnKey { _, _, _ ->
        val a = OwnKey.create(Subject("Mine"), 1, silent)
        assertTrue(isOwnCertificate(a))
        assertFalse(isOwnCertificate(TestKey.load().certificate))
    }

    @Test
    fun ownCertificateCountsThePastCertificatesOfTheLineage() = withOwnKey { _, _, _ ->
        val a = OwnKey.create(Subject("Old name"), 1, silent)
        val b = OwnKey.reissue(Subject("New name"), 2, silent)
        assertTrue(isOwnCertificate(a))
        assertTrue(isOwnCertificate(b))
        assertFalse(isOwnCertificate(TestKey.load().certificate))

        // An unreadable lineage is not own, only the current certificate stays.
        Files.write(OwnKey.lineage, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFalse(isOwnCertificate(a))
        assertTrue(isOwnCertificate(b))
    }

    @Test
    fun reissueKeepsTheKeyAndChainsTheCertificates() = withOwnKey { dir, ring, warnings ->
        val a = OwnKey.create(Subject("Old name", o = "Org"), 1, silent)
        val before = OwnKey.load()
        assertEquals(null, before.lineage)
        assertEquals(1, ring.items.size)

        val b = OwnKey.reissue(Subject("Nouveau nom", o = "Équipe", c = "FR"), 2, silent)

        val after = OwnKey.load()
        assertTrue(before.privateKey.encoded.contentEquals(after.privateKey.encoded))
        assertEquals(b, after.certificate)
        assertEquals(b, OwnKey.readCertificate())
        assertEquals(a.publicKey, b.publicKey)
        assertFalse(fingerprint(a) == fingerprint(b))
        assertEquals(Subject("Nouveau nom", o = "Équipe", c = "FR"), Der.getSubject(b))
        assertEquals(listOf(a, b), after.lineage?.certificatesInLineage)
        assertEquals(2, ring.items.size)
        assertTrue(OwnKey.lineage.let { Files.isRegularFile(it) })
        assertEquals(listOf("signing.crt", "signing.lineage", "signing.p12"), Files.list(OwnKey.keystore.parent).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        assertTrue(warnings.isEmpty(), warnings.joinToString())

        // A third certificate extends the chain.
        val c = OwnKey.reissue(Subject("Troisieme"), 3, silent)
        assertEquals(listOf(a, b, c), OwnKey.load().lineage?.certificatesInLineage)
        assertEquals(3, ring.items.size)
        assertTrue(before.privateKey.encoded.contentEquals(OwnKey.load().privateKey.encoded))

        // What it signs verifies, with the oldest in v1 and v2 and the newest in v3.1.
        val source = dir.resolve("app.apk")
        apk(source)
        val out = dir.resolve("signed.apk")
        ApkSigning.sign(source, out, OwnKey.load(), 21, "test", silent)
        val result = ApkVerifier.Builder(out.toFile()).setMinCheckedPlatformVersion(21).build().verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertEquals(a, result.v2SchemeSigners.single().certificate)
        assertEquals(c, result.v31SchemeSigners.single().certificate)
        assertEquals(listOf(a, b, c), result.signingCertificateLineage.certificatesInLineage)
    }

    @Test
    fun reissueRefusesWhatItCannotDo() = withOwnKey { _, _, _ ->
        assertFailsWith<java.io.IOException> { OwnKey.reissue(Subject("x"), 1, silent) }
        OwnKey.create(Subject("Old name"), 1, silent)
        val key = OwnKey.load().privateKey.encoded
        for (years in listOf(0L, 101L, -1L)) {
            assertFailsWith<IllegalArgumentException> { OwnKey.reissue(Subject("x"), years, silent) }
        }
        assertFailsWith<IllegalArgumentException> { OwnKey.reissue(Subject(""), 1, silent) }
        assertFailsWith<IllegalArgumentException> { OwnKey.reissue(Subject("x", c = "FRA"), 1, silent) }
        assertTrue(key.contentEquals(OwnKey.load().privateKey.encoded))
        assertEquals(null, OwnKey.load().lineage)
        assertFalse(Files.exists(OwnKey.lineage))
    }

    // A lineage that does not hold the key's certificate is ignored with its
    // reason and never deleted. Installing a new certificate moves it aside.
    @Test
    fun aForeignLineageIsIgnoredAndKept() = withOwnKey { dir, _, warnings ->
        val a = OwnKey.create(Subject("Old name"), 1, silent)
        val (other, random) = testKeys()
        val x = Der.selfSigned(other, Subject("X"), 1, random)
        val y = Der.selfSigned(other, Subject("Y"), 1, random)
        val foreign = lineageOf(other, x, y).bytes
        Files.write(OwnKey.lineage, foreign)

        assertEquals(null, OwnKey.load().lineage)
        assertEquals(1, warnings.size)
        assertTrue("does not hold the certificate" in warnings[0], warnings[0])
        assertTrue(foreign.contentEquals(Files.readAllBytes(OwnKey.lineage)))

        val b = OwnKey.reissue(Subject("New name"), 1, silent)
        assertEquals(listOf(a, b), OwnKey.load().lineage?.certificatesInLineage)
        val aside = Files.list(OwnKey.lineage.parent).use { s -> s.filter { it.fileName.toString().endsWith(".old") }.toList() }
        assertEquals(1, aside.size)
        assertTrue(foreign.contentEquals(Files.readAllBytes(aside[0])))

        // Garbage is ignored the same way.
        Files.write(OwnKey.lineage, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(null, OwnKey.load().lineage)
        assertTrue("cannot be read" in warnings.last(), warnings.last())
        assertEquals(6, Files.size(OwnKey.lineage))
    }

    // A plain restore of a key whose old lineage file is still in the folder:
    // the file is used when it holds the certificate, else moved aside.
    @Test
    fun aStaleLineageFollowsTheKeyBeingInstalled() = withOwnKey { dir, ring, _ ->
        val a = OwnKey.create(Subject("Old name"), 1, silent)
        val b = OwnKey.reissue(Subject("New name"), 1, silent)
        val lineageBytes = Files.readAllBytes(OwnKey.lineage)
        val target = dir.resolve("plain.p12")
        // A plain backup of B alone, as another tool writes it.
        val key = OwnKey.load()
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("k", key.privateKey, "pw".toCharArray(), arrayOf(b))
        Files.newOutputStream(target).use { ks.store(it, "pw".toCharArray()) }
        Files.delete(OwnKey.keystore)
        Files.delete(OwnKey.certificate)

        OwnKey.restore(target, "pw".toCharArray(), silent)

        assertTrue(lineageBytes.contentEquals(Files.readAllBytes(OwnKey.lineage)))
        assertEquals(listOf(a, b), OwnKey.load().lineage?.certificatesInLineage)
        assertTrue(ring.items.size >= 2)

        // A lineage of other certificates is moved aside, never deleted.
        val (other, random) = testKeys()
        val foreign = lineageOf(other, Der.selfSigned(other, Subject("X"), 1, random), Der.selfSigned(other, Subject("Y"), 1, random)).bytes
        Files.delete(OwnKey.keystore)
        Files.delete(OwnKey.certificate)
        Files.write(OwnKey.lineage, foreign)
        OwnKey.restore(target, "pw".toCharArray(), silent)
        assertFalse(Files.exists(OwnKey.lineage))
        assertEquals(null, OwnKey.load().lineage)
        val aside = Files.list(OwnKey.keystore.parent).use { s -> s.filter { it.fileName.toString().endsWith(".old") }.toList() }
        assertEquals(1, aside.size)
        assertTrue(foreign.contentEquals(Files.readAllBytes(aside[0])))
    }

    @Test
    fun backupAndRestoreKeepTheLineage() = withOwnKey { dir, _, _ ->
        val a = OwnKey.create(Subject("Old name"), 1, silent)
        val b = OwnKey.reissue(Subject("New name"), 1, silent)
        val c = OwnKey.reissue(Subject("Third name"), 1, silent)
        val encodedKey = OwnKey.load().privateKey.encoded
        val target = dir.resolve("backup.p12")
        val password = "backup password".toCharArray()

        OwnKey.backup(target, password, silent)

        val lineageFile = OwnKey.backupLineage(target)
        assertEquals(dir.resolve("backup.lineage"), lineageFile)
        assertTrue(Files.isRegularFile(lineageFile))
        assertEquals(listOf(a, b, c), SigningCertificateLineage.readFromFile(lineageFile.toFile()).certificatesInLineage)
        val ks = java.security.KeyStore.getInstance("PKCS12")
        Files.newInputStream(target).use { ks.load(it, password) }
        assertEquals(setOf("gutapk", "lineage-1", "lineage-2"), ks.aliases().toList().toSet())
        assertTrue(ks.isCertificateEntry("lineage-1") && ks.isCertificateEntry("lineage-2") && ks.isKeyEntry("gutapk"))
        assertEquals(a, ks.getCertificate("lineage-1"))
        assertEquals(b, ks.getCertificate("lineage-2"))
        assertEquals(c, ks.getCertificate("gutapk"))
        // Not replaced.
        assertFailsWith<java.io.IOException> { OwnKey.backup(target, password, silent) }
        Files.delete(target)
        assertFailsWith<java.io.IOException> { OwnKey.backup(target, password, silent) }
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(target.resolveSibling("backup.p12.part")))
        Files.delete(lineageFile)
        OwnKey.backup(target, password, silent)

        // With the lineage file.
        useFreshMachine(dir, "config-with-file")
        assertEquals(c, OwnKey.restore(target, password, silent))
        assertEquals(listOf(a, b, c), OwnKey.load().lineage?.certificatesInLineage)
        assertTrue(encodedKey.contentEquals(OwnKey.load().privateKey.encoded))

        // Without it: rebuilt from lineage-1 to lineage-n, signed by the key.
        Files.delete(lineageFile)
        useFreshMachine(dir, "config-without-file")
        assertEquals(c, OwnKey.restore(target, password, silent))
        val rebuilt = OwnKey.load()
        assertEquals(listOf(a, b, c), rebuilt.lineage?.certificatesInLineage)
        val source = dir.resolve("app.apk")
        apk(source)
        val out = dir.resolve("signed.apk")
        ApkSigning.sign(source, out, rebuilt, 21, "test", silent)
        val result = ApkVerifier.Builder(out.toFile()).setMinCheckedPlatformVersion(21).build().verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertEquals(listOf(a, b, c), result.signingCertificateLineage.certificatesInLineage)
    }

    @Test
    fun aKeyWithoutLineageBacksUpAndRestoresAsBefore() = withOwnKey { dir, _, _ ->
        val a = OwnKey.create(Subject("Only name"), 1, silent)
        val target = dir.resolve("plain.p12")
        val password = "pw".toCharArray()

        OwnKey.backup(target, password, silent)

        assertFalse(Files.exists(OwnKey.backupLineage(target)))
        val ks = java.security.KeyStore.getInstance("PKCS12")
        Files.newInputStream(target).use { ks.load(it, password) }
        assertEquals(listOf("gutapk"), ks.aliases().toList())
        useFreshMachine(dir, "other")
        assertEquals(a, OwnKey.restore(target, password, silent))
        assertEquals(null, OwnKey.load().lineage)
        assertFalse(Files.exists(OwnKey.lineage))
    }

    @Test
    fun restoreRefusesAForeignLineage() = withOwnKey { dir, _, _ ->
        val (mine, random) = testKeys()
        val (other, _) = testKeys()
        val certificate = Der.selfSigned(mine, Subject("Mine"), 1, random)
        val x = Der.selfSigned(other, Subject("X"), 1, random)
        val y = Der.selfSigned(other, Subject("Y"), 1, random)
        val password = "pw".toCharArray()

        // Older certificates of another key in the file, no lineage file.
        val target = dir.resolve("foreign.p12")
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("gutapk", mine.private, password, arrayOf(certificate))
        ks.setCertificateEntry("lineage-1", x)
        Files.newOutputStream(target).use { ks.store(it, password) }
        val failure = assertFailsWith<java.io.IOException> { OwnKey.restore(target, password, silent) }
        assertTrue("foreign.lineage" in failure.message.orEmpty(), failure.message)
        assertFalse(OwnKey.exists())

        // A lineage file that does not hold the key's certificate.
        Files.write(dir.resolve("foreign.lineage"), lineageOf(other, x, y).bytes)
        val failure2 = assertFailsWith<java.io.IOException> { OwnKey.restore(target, password, silent) }
        assertTrue("does not hold the certificate" in failure2.message.orEmpty(), failure2.message)
        assertFalse(OwnKey.exists())
    }
}
