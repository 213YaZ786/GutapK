package io.gutapk.core.offline

import com.android.apksig.ApkVerifier
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.axml.Axml
import io.gutapk.core.apk.axml.AxmlReader
import io.gutapk.core.apk.axml.AxmlWriter
import io.gutapk.core.apk.axml.NodeVisitor
import io.gutapk.core.sign.TestKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The manifest edits, the dex names, the ports and the whole offline build.
// The cases of the edits are bundletool's ManifestEditorTest: an element or an
// attribute created when missing, adjusted when there, never added twice.
// https://github.com/google/bundletool/blob/master/src/test/java/com/android/tools/build/bundletool/model/ManifestEditorTest.java
// The binary XML is made with the writer under test, then read back by the
// reader: the byte layout itself is checked against aapt2 and apksigner in a
// run on a real APK, not here.
class InjectTest {
    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private val android = "http://schemas.android.com/apk/res/android"
    private val internet = "android.permission.INTERNET"

    private fun withDir(block: (Path) -> Unit) {
        val d = Files.createTempDirectory("gutapk-inject")
        try {
            block(d)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    // An attribute of the android namespace for the builder below.
    private class A(val name: String, val id: Int, val type: Int, val value: Any)

    private fun manifest(
        pkg: String = "com.example.game",
        min: Int? = 16,
        target: Int? = 30,
        permissions: List<String> = listOf("android.permission.VIBRATE"),
        application: List<A> = emptyList(),
        root: List<A> = emptyList(),
        // The activity's name, given a MAIN LAUNCHER filter when it is set.
        launcher: String? = null,
        schemes: List<String> = emptyList(),
    ): ByteArray {
        val w = AxmlWriter()
        w.ns("android", android, 1)
        val m = w.child(null, "manifest")!!
        m.line(2)
        m.attr(null, "package", -1, NodeVisitor.TYPE_STRING, pkg)
        m.attr(android, "versionCode", 0x0101021b, NodeVisitor.TYPE_FIRST_INT, 7)
        root.forEach { m.attr(null, it.name, it.id, it.type, it.value) }
        if (min != null || target != null) {
            val u = m.child(null, "uses-sdk")!!
            if (target != null) u.attr(android, "targetSdkVersion", 0x01010270, NodeVisitor.TYPE_FIRST_INT, target)
            if (min != null) u.attr(android, "minSdkVersion", 0x0101020c, NodeVisitor.TYPE_FIRST_INT, min)
            u.end()
        }
        permissions.forEach {
            val p = m.child(null, "uses-permission")!!
            p.attr(android, "name", 0x01010003, NodeVisitor.TYPE_STRING, it)
            p.end()
        }
        val app = m.child(null, "application")!!
        app.attr(android, "label", 0x01010001, NodeVisitor.TYPE_STRING, "Game")
        application.forEach { app.attr(android, it.name, it.id, it.type, it.value) }
        val activity = app.child(null, "activity")!!
        activity.attr(android, "name", 0x01010003, NodeVisitor.TYPE_STRING, launcher ?: "com.example.game.Main")
        if (launcher != null) {
            val filter = activity.child(null, "intent-filter")!!
            for ((element, name) in listOf("action" to "android.intent.action.MAIN", "category" to "android.intent.category.LAUNCHER")) {
                val child = filter.child(null, element)!!
                child.attr(android, "name", 0x01010003, NodeVisitor.TYPE_STRING, name)
                child.end()
            }
            filter.end()
        }
        // VIEW filters, one a scheme each.
        for (scheme in schemes) {
            val filter = activity.child(null, "intent-filter")!!
            val action = filter.child(null, "action")!!
            action.attr(android, "name", 0x01010003, NodeVisitor.TYPE_STRING, "android.intent.action.VIEW")
            action.end()
            val data = filter.child(null, "data")!!
            data.attr(android, "scheme", 0x01010027, NodeVisitor.TYPE_STRING, scheme)
            data.end()
            filter.end()
        }
        activity.end()
        app.end()
        m.end()
        w.end()
        return w.toByteArray()
    }

    private fun read(bytes: ByteArray): Axml = Axml().also { AxmlReader(bytes).accept(it) }

    private fun Axml.Node.firstChild(name: String): Axml.Node? = children.firstOrNull { it.name == name }

    private fun Axml.Node.byId(id: Int): Axml.Node.Attr? = attrs.firstOrNull { it.resourceId == id }

    private fun root(bytes: ByteArray): Axml.Node = read(bytes).firsts.single()

    private fun providers(bytes: ByteArray): List<Axml.Node> = root(bytes).firstChild("application")!!.children.filter { it.name == "provider" }

    private fun permissions(bytes: ByteArray): List<String> =
        root(bytes).children.filter { it.name == "uses-permission" }.map { it.byId(0x01010003)!!.value as String }

    private val auth = Inject.authority("com.example.game")

    @Test
    fun providerIsAddedOnceWithItsAttributes() {
        val first = Inject.manifest(manifest(), auth).bytes
        val p = providers(first).single()
        assertEquals(Inject.PROVIDER, p.byId(0x01010003)!!.value)
        assertEquals("com.example.game.gutapk-offline", p.byId(0x01010018)!!.value)
        assertEquals(false, p.byId(0x01010010)!!.value)
        assertEquals(Int.MAX_VALUE, p.byId(0x0101001a)!!.value)
        assertEquals(NodeVisitor.TYPE_STRING, p.byId(0x01010018)!!.type)
        assertEquals(NodeVisitor.TYPE_INT_BOOLEAN, p.byId(0x01010010)!!.type)
        // The app's own activity is still there, and the package is the same.
        assertEquals("com.example.game.Main", root(first).firstChild("application")!!.firstChild("activity")!!.byId(0x01010003)!!.value)
        assertEquals("com.example.game", root(first).attrs.first { it.name == "package" }.value)
        assertEquals("Game", root(first).firstChild("application")!!.byId(0x01010001)!!.value)
    }

    @Test
    fun aSecondRunChangesNothing() {
        val first = Inject.manifest(manifest(), auth).bytes
        val second = Inject.manifest(first, auth).bytes
        assertEquals(1, providers(second).size)
        assertEquals(1, permissions(second).count { it == internet })
        assertContentEquals(first, second)
    }

    @Test
    fun attributesStayInResourceIdOrder() {
        val out = Inject.manifest(manifest(application = listOf(A("usesCleartextTraffic", 0x010104ec, NodeVisitor.TYPE_INT_BOOLEAN, false))), auth).bytes
        fun ids(n: Axml.Node): List<Int> = n.attrs.map { it.resourceId }
        fun check(n: Axml.Node) {
            assertEquals(ids(n).sorted(), ids(n), n.name)
            n.children.forEach { check(it) }
        }
        check(root(out))
        assertEquals(listOf(0x01010003, 0x01010010, 0x01010018, 0x0101001a), ids(providers(out).single()))
    }

    @Test
    fun internetIsAddedOnlyWhenMissing() {
        val without = Inject.manifest(manifest(), auth).bytes
        assertEquals(setOf("android.permission.VIBRATE", internet), permissions(without).toSet())
        assertEquals(2, permissions(without).size)
        // Before application, as the build tools write them.
        val order = root(without).children.map { it.name }
        assertTrue(order.lastIndexOf("uses-permission") < order.indexOf("application"))
        val with = Inject.manifest(manifest(permissions = listOf(internet, "android.permission.VIBRATE")), auth).bytes
        assertEquals(listOf(internet, "android.permission.VIBRATE"), permissions(with))
    }

    @Test
    fun cleartextIsSetWhenAbsentAndEditedWhenPresent() {
        val absent = root(Inject.manifest(manifest(), auth).bytes).firstChild("application")!!
        assertEquals(true, absent.byId(0x010104ec)!!.value)
        assertEquals(NodeVisitor.TYPE_INT_BOOLEAN, absent.byId(0x010104ec)!!.type)
        val strict = manifest(application = listOf(A("usesCleartextTraffic", 0x010104ec, NodeVisitor.TYPE_INT_BOOLEAN, false)))
        val edited = root(Inject.manifest(strict, auth).bytes).firstChild("application")!!
        assertEquals(true, edited.byId(0x010104ec)!!.value)
        assertEquals(1, edited.attrs.count { it.resourceId == 0x010104ec })
    }

    @Test
    fun hasCodeFalseIsRemovedAndTrueIsKept() {
        val off = manifest(application = listOf(A("hasCode", 0x0101000c, NodeVisitor.TYPE_INT_BOOLEAN, false)))
        assertNull(root(Inject.manifest(off, auth).bytes).firstChild("application")!!.byId(0x0101000c))
        val on = manifest(application = listOf(A("hasCode", 0x0101000c, NodeVisitor.TYPE_INT_BOOLEAN, true)))
        assertEquals(true, root(Inject.manifest(on, auth).bytes).firstChild("application")!!.byId(0x0101000c)!!.value)
    }

    @Test
    fun minSdkIsRaisedToTwentyOneAndTheTargetKept() {
        fun sdk(bytes: ByteArray): Axml.Node = root(bytes).firstChild("uses-sdk")!!
        val low = sdk(Inject.manifest(manifest(min = 16, target = 30), auth).bytes)
        assertEquals(21, low.byId(0x0101020c)!!.value)
        assertEquals(30, low.byId(0x01010270)!!.value)
        assertEquals(1, low.attrs.count { it.resourceId == 0x0101020c })
        val high = sdk(Inject.manifest(manifest(min = 23, target = 30), auth).bytes)
        assertEquals(23, high.byId(0x0101020c)!!.value)
        // No target: it defaulted to the old minimum, and still does.
        val noTarget = sdk(Inject.manifest(manifest(min = 14, target = null), auth).bytes)
        assertEquals(21, noTarget.byId(0x0101020c)!!.value)
        assertEquals(14, noTarget.byId(0x01010270)!!.value)
        // No uses-sdk at all means minSdk 1.
        val none = Inject.manifest(manifest(min = null, target = null), auth).bytes
        assertEquals("uses-sdk", root(none).children.first().name)
        assertEquals(21, sdk(none).byId(0x0101020c)!!.value)
        assertEquals(1, sdk(none).byId(0x01010270)!!.value)
    }

    @Test
    fun theNetworkSecurityConfigIdIsReturnedWhenThereIsOne() {
        assertNull(Inject.manifest(manifest(), auth).networkSecurityConfig)
        val withConfig = manifest(application = listOf(A("networkSecurityConfig", 0x01010527, NodeVisitor.TYPE_REFERENCE, 0x7f0b0001)))
        val out = Inject.manifest(withConfig, auth)
        assertEquals(0x7f0b0001, out.networkSecurityConfig)
        assertEquals(0x7f0b0001, root(out.bytes).firstChild("application")!!.byId(0x01010527)!!.value)
    }

    @Test
    fun anEmptyPackageGivesNoAuthority() {
        assertFailsWith<IllegalStateException> { Inject.manifest(manifest(), Inject.authority("")) }
        assertFailsWith<IllegalStateException> { Inject.manifest(manifest(), "") }
    }

    @Test
    fun aFileThatIsNotBinaryXmlIsRefused() {
        assertFailsWith<Exception> { Inject.manifest("<manifest/>".toByteArray(), auth) }
        assertFailsWith<Exception> { Inject.manifest(manifest().copyOf(60), auth) }
    }

    // The config of a strict app: https only, one domain with its own rule.
    private fun securityXml(extra: Boolean = true): ByteArray {
        val w = AxmlWriter()
        val r = w.child(null, "network-security-config")!!
        val base = r.child(null, "base-config")!!
        base.attr(null, "cleartextTrafficPermitted", -1, NodeVisitor.TYPE_INT_BOOLEAN, false)
        base.end()
        val d = r.child(null, "domain-config")!!
        d.attr(null, "cleartextTrafficPermitted", -1, NodeVisitor.TYPE_INT_BOOLEAN, false)
        val dom = d.child(null, "domain")!!
        dom.attr(null, "includeSubdomains", -1, NodeVisitor.TYPE_INT_BOOLEAN, true)
        dom.text(0, "game.example.org")
        dom.end()
        d.end()
        if (extra) {
            val o = r.child(null, "debug-overrides")!!
            o.end()
        }
        r.end()
        w.end()
        return w.toByteArray()
    }

    @Test
    fun theSecurityConfigAllowsTheLoopback() {
        val out = Inject.securityConfig(securityXml())
        val configs = root(out).children
        assertEquals(listOf("base-config", "domain-config", "domain-config", "debug-overrides"), configs.map { it.name })
        val own = configs[1]
        assertEquals(false, own.attrs.single { it.name == "cleartextTrafficPermitted" }.value)
        assertEquals("game.example.org", own.firstChild("domain")!!.text!!.text)
        val added = configs[2]
        assertEquals(true, added.attrs.single().value)
        assertEquals(NodeVisitor.TYPE_INT_BOOLEAN, added.attrs.single().type)
        assertEquals(listOf("127.0.0.1", "localhost"), added.children.map { it.text!!.text })
        assertTrue(added.children.all { it.attrs.single().name == "includeSubdomains" && it.attrs.single().value == false })
        assertNull(added.attrs.single().ns)
        // A second run finds it there, and a config without debug-overrides gets it last.
        assertContentEquals(out, Inject.securityConfig(out))
        assertEquals("domain-config", root(Inject.securityConfig(securityXml(extra = false))).children.last().name)
        assertFailsWith<Exception> { Inject.securityConfig(manifest()) }
    }

    @Test
    fun dexNamesFollowArtsRun() {
        assertEquals("classes.dex", Inject.nextDexName(emptyList()))
        assertEquals("classes2.dex", Inject.nextDexName(listOf("classes.dex")))
        assertEquals("classes3.dex", Inject.nextDexName(listOf("classes.dex", "classes2.dex", "AndroidManifest.xml")))
        // ART stops at the first missing one: a name after a gap would never load.
        assertEquals("classes2.dex", Inject.nextDexName(listOf("classes.dex", "classes3.dex")))
        assertEquals("classes.dex", Inject.nextDexName(listOf("classes2.dex")))
        // There is no classes1.dex.
        assertEquals("classes2.dex", Inject.nextDexName(listOf("classes.dex", "classes1.dex")))
        assertEquals("classes.dex", Inject.getMultiDexClassesDexName(0))
        assertEquals("classes2.dex", Inject.getMultiDexClassesDexName(1))
        assertEquals("classes10.dex", Inject.getMultiDexClassesDexName(9))
    }

    @Test
    fun portsAreStableDistinctAndConsecutive() {
        val origins = listOf("https://a.example.org", "https://b.example.org", "wss://c.example.org:8443")
        val ports = Inject.ports("com.example.game", origins)
        assertEquals(ports, Inject.ports("com.example.game", origins))
        assertEquals(origins, ports.keys.toList())
        assertEquals(3, ports.values.toSet().size)
        val first = ports.values.first()
        assertEquals(listOf(first, first + 1, first + 2), ports.values.toList())
        assertTrue(ports.values.all { it in 20000..29999 })
        // Another package lands elsewhere, and the same origin twice is one port.
        val firsts = (1..40).map { Inject.ports("com.example.game$it", origins).values.first() }.toSet()
        assertTrue(firsts.size > 20, "$firsts")
        assertEquals(1, Inject.ports("com.example.game", listOf(origins[0], origins[0])).size)
        // A full range still ends inside it.
        val all = (1..10000).map { "https://h$it.example.org" }
        assertEquals(20000..29999, Inject.ports("com.example.game", all).values.let { it.min()..it.max() })
        assertFailsWith<IllegalArgumentException> { Inject.ports("com.example.game", all + "https://more.example.org") }
        assertEquals(emptyMap(), Inject.ports("com.example.game", emptyList()))
    }

    @Test
    fun theConfigListsEveryPortAndTheAdminSocket() {
        val ports = mapOf("https://bim-prod.jorge.st" to 24210, "wss://play.example.net:8443" to 24211)
        val text = Inject.config("bim.app", ports)
        assertEquals(
            "listen.24210=https://bim-prod.jorge.st\nlisten.24211=wss://play.example.net:8443\n" +
                "target.24210=http://127.0.0.1:00024210\ntarget.24211=ws://127.0.0.1:000000024211\nadmin=gutapk.bim.app\n",
            text,
        )
        // Read as java does: the origin comes back whole, ':' and '/' included.
        val props = java.util.Properties().also { it.load(text.reader()) }
        assertEquals("https://bim-prod.jorge.st", props.getProperty("listen.24210"))
        assertEquals("http://127.0.0.1:00024210", props.getProperty("target.24210"))
        assertEquals("gutapk.bim.app", props.getProperty("admin"))
        assertFailsWith<IllegalArgumentException> { Inject.config("bim.app", mapOf("https://a\nb" to 24210)) }
    }

    // A block.<port>=true line per blocked origin, none for an origin the config does not move.
    // https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#configure
    @Test
    fun theConfigMarksTheBlockedPorts() {
        val ports = mapOf("https://bim-prod.jorge.st" to 24210, "https://events.tracker.example" to 24211, "wss://play.example.net:8443" to 24212)
        val text = Inject.config("bim.app", ports, setOf("https://events.tracker.example", "https://elsewhere.example"))
        val props = java.util.Properties().also { it.load(text.reader()) }
        assertEquals(setOf("block.24211"), props.stringPropertyNames().filter { it.startsWith("block.") }.toSet())
        assertEquals("true", props.getProperty("block.24211"))
        assertFalse("block." in Inject.config("bim.app", ports))
    }

    // The offline build.

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun lib(vararg strings: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 1, 2, 3))
        strings.forEach {
            out.write(ascii(it))
            out.write(0)
            out.write(byteArrayOf(1, 2))
        }
        return out.toByteArray()
    }

    // A header of 0x70 bytes with a wrong checksum and signature, then one
    // string as dex stores it. OfflineTest's helper.
    private fun dex(url: String): ByteArray {
        val head = ByteArray(0x70)
        ascii("dex\n035").copyInto(head)
        for (i in 8 until 32) head[i] = 0x55
        for (i in 32 until 0x70) head[i] = (i * 7).toByte()
        return head + byteArrayOf(url.length.toByte()) + ascii(url) + byteArrayOf(0)
    }

    // A real dex, the game's: one method returning the url, written by
    // dexlib2 the way DexStringsTest writes its own.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
    private fun appDex(url: String): ByteArray {
        val code = ImmutableMethodImplementation(1, listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(url)), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null)
        val method = ImmutableMethod("Lgame/Net;", "url", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef("Lgame/Net;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    // A resource table with one string, id 0x7f010000, here the path of a file.
    private fun table(value: String): ByteArray {
        fun u16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
        fun pool(strings: List<String>): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = strings.map { s ->
                val at = data.size()
                data.write(u16(s.length))
                data.write(s.toByteArray(Charsets.UTF_16LE))
                data.write(u16(0))
                at
            }
            while (data.size() % 4 != 0) data.write(0)
            val body = cat(*offsets.map { u32(it) }.toTypedArray(), data.toByteArray())
            return cat(u16(1), u16(28), u32(28 + body.size), u32(strings.size), u32(0), u32(0), u32(28 + 4 * strings.size), u32(0), body)
        }
        val entries = cat(u16(8), u16(0), u32(0), u16(8), byteArrayOf(0, 3), u32(0))
        val offsets = u32(0)
        val header = 20 + 64
        val type = cat(
            u16(0x0201), u16(header), u32(header + offsets.size + entries.size),
            byteArrayOf(1, 0), u16(0), u32(1), u32(header + offsets.size), u32(64), ByteArray(60), offsets, entries,
        )
        val typePool = pool(listOf("xml"))
        val keyPool = pool(listOf("network_security"))
        val pkgBody = cat(typePool, keyPool, type)
        val name = ByteArray(256).also { "p".toByteArray(Charsets.UTF_16LE).copyInto(it) }
        val pkg = cat(u16(0x0200), u16(288), u32(288 + pkgBody.size), u32(0x7f), name, u32(288), u32(0), u32(288 + typePool.size), u32(0), u32(0), pkgBody)
        val body = cat(pool(listOf(value)), pkg)
        return cat(u16(2), u16(12), u32(12 + body.size), u32(1), body)
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

    private val socket = "wss://play.example.net:8443"
    private val game = "https://game.example.org"
    private val serverDex = dex("")

    private fun fakeApk(d: Path, manifest: ByteArray = manifest(), extra: List<Pair<String, ByteArray>> = emptyList()): Path {
        val apk = d.resolve("game.apk")
        zip(
            apk,
            listOf(
                "AndroidManifest.xml" to manifest,
                "lib/arm64-v8a/libgame.so" to lib("$game/api", "libc.so"),
                "classes.dex" to appDex("$socket/socket"),
                "assets/config.json" to ascii("{\"base\":\"$game/v1\"}"),
                "res/drawable/icon.png" to ascii("PNG not scanned https://not.scanned.example.com/x"),
            ) + extra,
            stored = setOf("lib/arm64-v8a/libgame.so"),
        )
        return apk
    }

    private fun entry(apk: Path, name: String): ByteArray = ZipFile(apk.toFile()).use { z ->
        z.getInputStream(z.getEntry(name) ?: throw AssertionError("$name is not in $apk")).use { it.readAllBytes() }
    }

    private fun build(d: Path, apk: Path, out: Path = d.resolve("out/offline.apk"), servers: List<Endpoint>? = null, recordings: Path? = null, sink: JobSink = quiet, blocked: Set<String> = emptySet(), consent: Boolean = true, signIn: Boolean = true) = OfflineBuild.run(
        input = apk,
        endpoints = servers ?: Endpoints.scan(apk).filter { it.likelyServer },
        dex = serverDex,
        strings = DexStrings(),
        work = d.resolve("work"),
        out = out,
        key = TestKey.load(),
        minSdk = 16,
        appVersion = "test",
        sink = sink,
        recordings = recordings,
        blocked = blocked,
        consent = consent,
        signIn = signIn,
    )

    // A second dex with the game's call to a consent SDK: MRGSGDPR's
    // showAgreementAtActivity, receiver in v0, the activity in v1 and the two names after.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
    private fun consentDex(): ByteArray {
        val show = ImmutableMethodReference(Inject.MRGS_CONSENT, "showAgreementAtActivity", listOf("Landroid/app/Activity;", "Ljava/lang/String;", "Ljava/lang/String;"), "V")
        val code = ImmutableMethodImplementation(
            4,
            listOf(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 0, 1, 2, 3, 0, show), ImmutableInstruction10x(Opcode.RETURN_VOID)),
            null,
            null,
        )
        val params = listOf(Inject.MRGS_CONSENT, "Landroid/app/Activity;", "Ljava/lang/String;", "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) }
        val method = ImmutableMethod("Lgame/Main;", "ask", params, "V", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef("Lgame/Main;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    // The build marks the blocked origins in the config the server reads, and the dex
    // that opens a consent window is written again with the call answered by Consent.
    // A game that skips the switch keeps its dex byte for byte.
    // https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#configure
    @Test
    fun theBuildBlocksTheChosenOriginsAndSkipsTheConsentWindow() = withDir { d ->
        val apk = fakeApk(d, extra = listOf("classes2.dex" to consentDex()))
        val lines = mutableListOf<String>()
        val out = d.resolve("out/offline.apk")
        assertTrue(build(d, apk, out, blocked = setOf(game), sink = object : JobSink {
            override fun emit(event: JobEvent) {
                if (event is JobEvent.Line) lines.add(event.text)
            }
        }).verified)
        val props = java.util.Properties().also { it.load(entry(out, Inject.CONFIG).inputStream()) }
        val port = props.stringPropertyNames().single { it.startsWith("listen.") && props.getProperty(it) == game }.substringAfter("listen.")
        assertEquals(setOf("block.$port"), props.stringPropertyNames().filter { it.startsWith("block.") }.toSet())
        assertEquals("true", props.getProperty("block.$port"))
        assertTrue("1 servers answered on the phone, never called" in lines, lines.toString())
        val call = DexBackedDexFile(null, entry(out, "classes2.dex")).classes.single().methods.single().implementation!!.instructions.first() as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(4, call.registerCount)
        val reference = call.reference as com.android.tools.smali.dexlib2.iface.reference.MethodReference
        assertEquals("Lio/gutapk/offline/Consent;", reference.definingClass)
        assertEquals("showAgreementAtActivity", reference.name)
        assertEquals(listOf("Ljava/lang/Object;", "Landroid/app/Activity;", "Ljava/lang/String;", "Ljava/lang/String;"), reference.parameterTypes.map { it.toString() })
        assertTrue(lines.any { "1 consent windows answered" in it }, lines.toString())

        // Nothing blocked, the switch off: no block line, the dex as it was.
        val plain = d.resolve("out/plain.apk")
        assertTrue(build(d, apk, plain, consent = false).verified)
        assertFalse("block." in String(entry(plain, Inject.CONFIG)))
        assertContentEquals(consentDex(), entry(plain, "classes2.dex"))
    }

    // A second dex with a GameHelper under an obfuscated name (its three methods) and the
    // activity's call to its onStart: the dex is written again with the call answered by
    // SignIn, the line counts it, and the switch off leaves the dex byte for byte.
    // https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#onStart
    @Test
    fun theBuildTurnsTheAutomaticSignInOff() = withDir { d ->
        val activity = "Landroid/app/Activity;"
        val helper = "Lgame/a;"
        val own = listOf("setConnectOnStart" to listOf("Z"), "beginUserInitiatedSignIn" to emptyList(), "onStart" to listOf(activity)).map { (name, params) ->
            val code = ImmutableMethodImplementation(1 + params.size, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            ImmutableMethod(helper, name, params.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null, code)
        }
        val start = ImmutableMethodReference(helper, "onStart", listOf(activity), "V")
        val code = ImmutableMethodImplementation(3, listOf(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, start), ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
        val begin = ImmutableMethod("Lgame/Main;", "begin", listOf(helper, activity).map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef(helper, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, own))
        dexPool.internClass(ImmutableClassDef("Lgame/Main;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(begin)))
        val helperDex = MemoryDataStore().also { dexPool.writeTo(it) }.data
        val apk = fakeApk(d, extra = listOf("classes2.dex" to helperDex))
        val lines = mutableListOf<String>()
        val out = d.resolve("out/offline.apk")
        assertTrue(build(d, apk, out, sink = object : JobSink {
            override fun emit(event: JobEvent) {
                if (event is JobEvent.Line) lines.add(event.text)
            }
        }).verified)
        val call = DexBackedDexFile(null, entry(out, "classes2.dex")).classes.single { it.type == "Lgame/Main;" }.methods.single().implementation!!.instructions.first() as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        val reference = call.reference as com.android.tools.smali.dexlib2.iface.reference.MethodReference
        assertEquals("Lio/gutapk/offline/SignIn;", reference.definingClass)
        assertEquals(listOf("Ljava/lang/Object;", activity), reference.parameterTypes.map { it.toString() })
        assertTrue(lines.any { "1 sign-in calls answered" in it }, lines.toString())

        val plain = d.resolve("out/plain.apk")
        assertTrue(build(d, apk, plain, signIn = false).verified)
        assertContentEquals(helperDex, entry(plain, "classes2.dex"))
    }

    // The recordings kept on the PC go into the APK as assets, name for name, the
    // files being written aside left out, and the build says how many travel.
    // https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
    @Test
    fun theRecordingsKeptOnThePcTravelAsAssets() = withDir { d ->
        val apk = fakeApk(d)
        val folder = Files.createDirectories(OfflineBuild.recordings(d))
        mapOf("k1.0" to "meta1", "k1.1" to "body1", "k1.2" to "", "k2.0" to "meta2", "k2.1" to "body2", "h.latest" to "k2", "k3.0.tmp" to "half").forEach { (name, text) ->
            Files.write(folder.resolve(name), ascii(text))
        }
        val lines = mutableListOf<String>()
        val out = d.resolve("out/offline.apk")
        val check = build(d, apk, out, recordings = folder, sink = object : JobSink {
            override fun emit(event: JobEvent) {
                if (event is JobEvent.Line) lines.add(event.text)
            }
        })
        assertTrue(check.verified, check.problems.joinToString())
        assertEquals(2, OfflineBuild.count(folder))
        assertTrue("2 recordings travel with the game" in lines, lines.toString())
        val prefix = Inject.CONFIG.substringBeforeLast('/') + "/recordings/"
        assertEquals("assets/gutapk/recordings/", prefix)
        assertContentEquals(ascii("body2"), entry(out, prefix + "k2.1"))
        assertContentEquals(ascii("meta1"), entry(out, prefix + "k1.0"))
        assertContentEquals(ascii("k2"), entry(out, prefix + "h.latest"))
        val names = ZipFile(out.toFile()).use { z -> z.entries().asSequence().map { it.name }.filter { it.startsWith(prefix) }.toSet() }
        assertEquals(setOf("k1.0", "k1.1", "k1.2", "k2.0", "k2.1", "h.latest").map { prefix + it }.toSet(), names)
        // The server's own asset is still there, and the signature verifies with them.
        assertNotNull(entry(out, Inject.CONFIG))
        assertTrue(ApkVerifier.Builder(out.toFile()).build().verify().isVerified)
    }

    @Test
    fun noRecordingsMeansNoAssetAndNoLine() = withDir { d ->
        val apk = fakeApk(d)
        val lines = mutableListOf<String>()
        val sink = object : JobSink {
            override fun emit(event: JobEvent) {
                if (event is JobEvent.Line) lines.add(event.text)
            }
        }
        // A folder that does not exist, and one that is empty, are the same as none.
        build(d, apk, d.resolve("out/a.apk"), recordings = d.resolve("recordings"), sink = sink)
        build(d, apk, d.resolve("out/b.apk"), recordings = Files.createDirectories(d.resolve("empty")), sink = sink)
        assertTrue(lines.none { "travel" in it }, lines.toString())
        listOf("a", "b").forEach { n ->
            ZipFile(d.resolve("out/$n.apk").toFile()).use { z -> assertTrue(z.entries().asSequence().none { it.name.startsWith("assets/gutapk/recordings") }) }
        }
    }

    @Test
    fun theOfflineBuildCarriesItsServer()= withDir { d ->
        val apk = fakeApk(d)
        val out = d.resolve("out/offline.apk")
        val check = build(d, apk, out)
        assertTrue(check.verified, check.problems.joinToString())
        assertEquals(TestKey.CERT_SHA256, check.signers.single().sha256)
        val verdict = ApkVerifier.Builder(out.toFile()).build().verify()
        assertTrue(verdict.isVerified, verdict.errors.joinToString())
        assertTrue(verdict.isVerifiedUsingV2Scheme)

        // The manifest reads back with the provider, the permission and the rights.
        val manifest = entry(out, "AndroidManifest.xml")
        val p = providers(manifest).single()
        assertEquals(Inject.PROVIDER, p.byId(0x01010003)!!.value)
        assertEquals("com.example.game.gutapk-offline", p.byId(0x01010018)!!.value)
        assertTrue(permissions(manifest).contains(internet))
        assertEquals(21, root(manifest).firstChild("uses-sdk")!!.byId(0x0101020c)!!.value)
        assertEquals(true, root(manifest).firstChild("application")!!.byId(0x010104ec)!!.value)

        // The server's dex sits right after the app's, and the asset tells it what to serve.
        assertContentEquals(serverDex, entry(out, "classes2.dex"))
        val props = java.util.Properties().also { it.load(String(entry(out, Inject.CONFIG)).reader()) }
        assertEquals("gutapk.com.example.game", props.getProperty("admin"))
        val listen = props.stringPropertyNames().filter { it.startsWith("listen.") }
        assertEquals(setOf(game, socket), listen.map { props.getProperty(it) }.toSet())
        val ports = listen.associate { props.getProperty(it) to it.removePrefix("listen.").toInt() }
        assertEquals(Inject.ports("com.example.game", listOf(game, socket)), ports)
        assertTrue(ports.values.all { it in 20000..29999 })
        assertEquals(ports.values.min() + 1, ports.values.max())

        // The addresses moved to the phone with their length unchanged in the library and the asset.
        val target = Redirect.target(game, port = ports.getValue(game))!!
        assertContentEquals(ascii("$target/api"), entry(out, "lib/arm64-v8a/libgame.so").copyOfRange(8, 8 + target.length + 4))
        assertContentEquals(ascii("$target/v1"), entry(out, "assets/config.json").copyOfRange(9, 9 + target.length + 3))
        // The dex is written again, its strings still sorted as ART checks them.
        val patched = entry(out, "classes.dex")
        val strings = DexBackedDexFile(null, patched).stringSection.toList()
        assertTrue("${Redirect.target(socket, port = ports.getValue(socket))!!}/socket" in strings, strings.toString())
        assertTrue(strings.none { socket in it })
        assertEquals(strings.sorted(), strings)

        // The dex has a checksum that fits, the other entries are as they were.
        assertEquals(Adler32().also { it.update(patched, 12, patched.size - 12) }.value, ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).getInt(8).toLong() and 0xffffffffL)
        assertContentEquals(entry(apk, "res/drawable/icon.png"), entry(out, "res/drawable/icon.png"))

        // Every entry has the crc its header says, and a stored library stays stored.
        ZipFile(out.toFile()).use { z ->
            z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val crc = CRC32().also { it.update(z.getInputStream(e).use { s -> s.readAllBytes() }) }.value
                assertEquals(e.crc, crc, e.name)
            }
            assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libgame.so").method)
            assertEquals(ZipEntry.DEFLATED, z.getEntry("classes2.dex").method)
            assertEquals(ZipEntry.DEFLATED, z.getEntry(Inject.CONFIG).method)
            assertNotNull(z.getEntry("classes.dex"))
        }
    }

    // The game with Local, built as the offline one is: the Local screen from
    // the launcher and from gutapk://<package>/join, the engine's service in
    // its own process, the config the screen reads, the same dex.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/AndroidManifest.java#L981-L1022
    // not ported: hasMainTvActivity, isHeadless, hasMainActivity, getActivities, getActivityAliases (mainActivity is checked through the build)
    private fun local(d: Path, apk: Path, out: Path = d.resolve("out/local.apk"), args: List<String> = listOf("--", "--server"), server: String? = null) =
        io.gutapk.core.local.LocalBuild.run(apk, args, 2755, false, server, emptyMap(), io.gutapk.core.godot.Zstd(), serverDex, d.resolve("work"), out, TestKey.load(), 16, "test", quiet)

    @Test
    fun theLocalBuildCarriesLocal() = withDir { d ->
        val apk = fakeApk(d, manifest(launcher = ".Main"))
        val out = d.resolve("out/local.apk")
        assertTrue(local(d, apk, out).verified)
        assertTrue(ApkVerifier.Builder(out.toFile()).build().verify().isVerified)

        val manifest = entry(out, "AndroidManifest.xml")
        val application = root(manifest).firstChild("application")!!
        val screen = application.children.single { it.name == "activity" && it.byId(0x01010003)?.value == Inject.LOCAL_ACTIVITY }
        assertEquals(true, screen.byId(0x01010010)!!.value)
        val filters = screen.children.filter { it.name == "intent-filter" }
        assertEquals(listOf("android.intent.action.MAIN", "android.intent.action.VIEW"), filters.map { it.firstChild("action")!!.byId(0x01010003)!!.value })
        val data = filters[1].firstChild("data")!!
        assertEquals(listOf("gutapk", "com.example.game", "/join"), listOf(0x01010027, 0x01010028, 0x0101002a).map { data.byId(it)!!.value })
        val service = application.children.single { it.name == "service" }
        assertEquals(Inject.GODOT_SERVICE, service.byId(0x01010003)!!.value)
        assertEquals(Inject.SERVER_PROCESS, service.byId(0x01010011)!!.value)
        assertTrue(permissions(manifest).contains(internet))
        assertEquals(21, root(manifest).firstChild("uses-sdk")!!.byId(0x0101020c)!!.value)

        assertContentEquals(serverDex, entry(out, "classes2.dex"))
        val config = io.gutapk.local.Config.build(entry(out, Inject.LOCAL_CONFIG).inputStream())
        assertEquals(2755, config.localPort)
        assertEquals(false, config.isUdp)
        assertEquals("com.example.game.Main", config.activity)
        assertEquals(listOf("--", "--server"), config.args)

        assertNull(config.server)

        // Once is enough, an offline game is the original's to start from,
        // a game no launcher opens is refused.
        assertFailsWith<CheckFailed> { local(d, out, d.resolve("out/again.apk")) }
        assertFailsWith<CheckFailed> { local(d, build(d, fakeApk(d, manifest(launcher = ".Main"))).let { d.resolve("out/offline.apk") }, d.resolve("out/both.apk")) }
        assertFailsWith<CheckFailed> { local(d, fakeApk(d), d.resolve("out/none.apk")) }
    }

    // A game that hosts from its own menu: no second engine, its own link
    // to a server read from its manifest and carried for a friend's Play.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/AndroidManifest.java#L981-L1022
    // not ported: hasMainTvActivity, isHeadless, hasMainActivity, getActivities, getActivityAliases (viewSchemes is checked on its result)
    @Test
    fun aGameHostingFromItsMenuRunsNoEngine() = withDir { d ->
        val original = manifest(launcher = ".Main", schemes = listOf("https", "mygame", "content", "mygame"))
        assertEquals(listOf("mygame"), Inject.viewSchemes(original))
        assertEquals(emptyList(), Inject.viewSchemes(manifest(launcher = ".Main")))

        val out = d.resolve("out/own-menu.apk")
        assertTrue(local(d, fakeApk(d, original), out, emptyList(), "mygame://127.0.0.1:2755").verified)
        val application = root(entry(out, "AndroidManifest.xml")).firstChild("application")!!
        assertTrue(application.children.none { it.name == "service" })
        val config = io.gutapk.local.Config.build(entry(out, Inject.LOCAL_CONFIG).inputStream())
        assertEquals(emptyList(), config.args)
        assertEquals("mygame://127.0.0.1:2755", config.server)
    }

    @Test
    fun theSecurityConfigOfTheAppIsEditedToo() = withDir { d ->
        val path = "res/xml/network_security_config.xml"
        val m = manifest(application = listOf(A("networkSecurityConfig", 0x01010527, NodeVisitor.TYPE_REFERENCE, 0x7f010000)))
        val apk = fakeApk(d, m, listOf("resources.arsc" to table(path), path to securityXml()))
        assertEquals(listOf(path), ResourceTable.parse(table(path)).strings(0x7f010000).map { it.second })
        val out = d.resolve("out/offline.apk")
        assertTrue(build(d, apk, out).verified)
        assertEquals(listOf("base-config", "domain-config", "domain-config", "debug-overrides"), root(entry(out, path)).children.map { it.name })
        assertContentEquals(table(path), entry(out, "resources.arsc"))
        assertEquals(0x7f010000, root(entry(out, "AndroidManifest.xml")).firstChild("application")!!.byId(0x01010527)!!.value)
    }

    @Test
    fun anAppWithoutCodeGetsItsCodeBack() = withDir { d ->
        val m = manifest(application = listOf(A("hasCode", 0x0101000c, NodeVisitor.TYPE_INT_BOOLEAN, false)))
        val out = d.resolve("out/offline.apk")
        build(d, fakeApk(d, m), out)
        assertNull(root(entry(out, "AndroidManifest.xml")).firstChild("application")!!.byId(0x0101000c))
    }

    @Test
    fun aSplitOfASetIsRefused() = withDir { d ->
        val split = manifest(root = listOf(A("split", -1, NodeVisitor.TYPE_STRING, "config.arm64_v8a")))
        val e = assertFailsWith<CheckFailed> { build(d, fakeApk(d, split)) }
        assertTrue(e.message!!.contains("split set"), e.message)
        assertFalse(Files.exists(d.resolve("out/offline.apk")))
    }

    @Test
    fun anOfflineApkIsNotBuiltTwice() = withDir { d ->
        val apk = fakeApk(d)
        val out = d.resolve("out/offline.apk")
        build(d, apk, out)
        val e = assertFailsWith<CheckFailed> { build(d, out, d.resolve("out/again.apk"), Endpoints.scan(out).filter { it.likelyServer }) }
        assertTrue(e.message!!.contains("already"), e.message)
    }

    @Test
    fun withNothingToRedirectNothingIsBuilt() = withDir { d ->
        val apk = fakeApk(d)
        val short = Endpoint("http://a.bc", emptyList(), emptyList(), true)
        assertFailsWith<CheckFailed> { build(d, apk, servers = listOf(short)) }
        assertFailsWith<CheckFailed> { build(d, apk, servers = emptyList()) }
        assertFalse(Files.exists(d.resolve("out/offline.apk")))
        assertFalse(Files.exists(d.resolve("work")))
    }

    @Test
    fun aDexThatIsNotOneIsRefused() = withDir { d ->
        val apk = fakeApk(d)
        val e = assertFailsWith<CheckFailed> {
            OfflineBuild.run(apk, Endpoints.scan(apk).filter { it.likelyServer }, ByteArray(200), DexStrings(), d.resolve("work"), d.resolve("out/o.apk"), TestKey.load(), 16, "test", quiet)
        }
        assertTrue(e.message!!.contains("dex"), e.message)
    }

    @Test
    fun patchesTakeOnePortPerOrigin() = withDir { d ->
        val apk = fakeApk(d)
        val servers = Endpoints.scan(apk).filter { it.likelyServer }
        fun written(patches: List<io.gutapk.core.il2cpp.BytePatch>): Set<String> =
            patches.map { p -> String(p.new.split(' ').map { it.toInt(16).toByte() }.toByteArray(), Charsets.ISO_8859_1) }.toSet()
        val both = Redirect.patches(apk, servers, mapOf(game to 24210, socket to 24999))
        assertEquals(setOf(Redirect.target(game, port = 24210)!!, Redirect.target(socket, port = 24999)!!), written(both))
        // An origin with no port in the map is left alone, and the single port still serves them all.
        assertEquals(setOf(Redirect.target(game, port = 24210)!!), written(Redirect.patches(apk, servers, mapOf(game to 24210))))
        val single = servers.associate { it.origin to 24210 }
        assertEquals(both.size, Redirect.patches(apk, servers, single).size)
        assertEquals(setOf(Redirect.target(game, port = 24210)!!, Redirect.target(socket, port = 24210)!!), written(Redirect.patches(apk, servers, single)))
    }
}
