package io.gutapk.core.offline

import com.android.apksig.ApkVerifier
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.gutapk.core.apk.Part
import io.gutapk.core.apk.Parts
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.apk.axml.AxmlWriter
import io.gutapk.core.apk.axml.NodeVisitor
import io.gutapk.core.sign.TestKey
import io.gutapk.device.OfflineInstall
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The offline mode on a split set: a base with an address in its dex, a
// split with another in a library, a third split with none. Part by part,
// never merged, one key for all. The set is made as GutapK's InjectTest makes
// its APK, the dex as dexlib2 writes one.
// https://github.com/213YaZ786/GutapK/blob/531ee678f6805e43115bb9ddf6ca80f40df43c41/src/test/kotlin/io/gutapk/core/offline/InjectTest.kt#fakeApk
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
class OfflineSetTest {
    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private val android = "http://schemas.android.com/apk/res/android"
    private val socket = "wss://play.example.net:8443"
    private val game = "https://game.example.org"

    private fun withDir(block: (Path) -> Unit) {
        val d = Files.createTempDirectory("gutapk-offline-set")
        try {
            block(d)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    private fun manifest(split: String? = null): ByteArray {
        val w = AxmlWriter()
        w.ns("android", android, 1)
        val m = w.child(null, "manifest")!!
        m.line(2)
        m.attr(null, "package", -1, NodeVisitor.TYPE_STRING, "com.example.game")
        if (split != null) m.attr(null, "split", -1, NodeVisitor.TYPE_STRING, split)
        m.attr(android, "versionCode", 0x0101021b, NodeVisitor.TYPE_FIRST_INT, 7)
        val sdk = m.child(null, "uses-sdk")!!
        sdk.attr(android, "minSdkVersion", 0x0101020c, NodeVisitor.TYPE_FIRST_INT, 21)
        sdk.end()
        val app = m.child(null, "application")!!
        app.attr(android, "label", 0x01010001, NodeVisitor.TYPE_STRING, "Game")
        app.end()
        m.end()
        w.end()
        return w.toByteArray()
    }

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

    private fun appDex(url: String): ByteArray {
        val code = ImmutableMethodImplementation(1, listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(url)), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null)
        val method = ImmutableMethod("Lgame/Net;", "url", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef("Lgame/Net;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    private fun zip(file: Path, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    private fun entry(apk: Path, name: String): ByteArray = ZipFile(apk.toFile()).use { z ->
        z.getInputStream(z.getEntry(name) ?: throw AssertionError("$name is not in $apk")).use { it.readAllBytes() }
    }

    private fun has(apk: Path, name: String): Boolean = ZipFile(apk.toFile()).use { it.getEntry(name) != null }

    private fun parts(d: Path): List<Part> {
        val dir = Files.createDirectories(d.resolve("parts"))
        zip(d.resolve("original.apk"), listOf("AndroidManifest.xml" to manifest(), "classes.dex" to appDex("$socket/socket"), "res/drawable/icon.png" to ascii("PNG")))
        zip(dir.resolve("split_config.arm64_v8a.apk"), listOf("AndroidManifest.xml" to manifest("config.arm64_v8a"), "lib/arm64-v8a/libgame.so" to lib("$game/api", "libc.so")))
        zip(dir.resolve("split_config.xxhdpi.apk"), listOf("AndroidManifest.xml" to manifest("config.xxhdpi"), "res/drawable-xxhdpi/icon.png" to ascii("PNG")))
        return Parts.of(d)
    }

    private val serverDex: ByteArray = ByteArray(0x70).also {
        ascii("dex\n035").copyInto(it)
        for (i in 8 until 32) it[i] = 0x55
        for (i in 32 until 0x70) it[i] = (i * 7).toByte()
    } + byteArrayOf(0, 0)

    private fun contains(bytes: ByteArray, text: String): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains(text) || String(bytes, Charsets.ISO_8859_1).contains(String(text.toByteArray(Charsets.UTF_16LE), Charsets.ISO_8859_1))

    @Test
    fun theMergedScanFindsTheAddressesOfEveryPart() = withDir { d ->
        val set = parts(d)
        val found = Endpoints.scan(set.map { it.file }).filter { it.likelyServer }.map { it.origin }.toSet()
        assertEquals(setOf(game, socket), found)
        assertTrue(game !in Endpoints.scan(set.first().file).map { it.origin })
    }

    @Test
    fun theSetIsBuiltPartByPartAndSignedWithOneKey() = withDir { d ->
        val set = parts(d)
        val out = d.resolve("out/game-offline-test")
        val servers = Endpoints.scan(set.map { it.file }).filter { it.likelyServer }
        OfflineBuild.runSet(set, servers, serverDex, DexStrings(), d.resolve("work"), out, TestKey.load(), 16, "test", quiet)

        val built = Parts.apks(out)
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk"), built.map { it.fileName.toString() })
        built.forEach { f ->
            val verdict = ApkVerifier.Builder(f.toFile()).build().verify()
            assertTrue(verdict.isVerified, "$f " + verdict.errors.joinToString() + verdict.v2SchemeSigners.flatMap { it.errors }.joinToString() + verdict.v3SchemeSigners.flatMap { it.errors }.joinToString() + verdict.v1SchemeSigners.flatMap { it.errors }.joinToString())
            assertEquals(TestKey.CERT_SHA256, MessageDigest.getInstance("SHA-256").digest(verdict.signerCertificates.single().encoded).joinToString(":") { "%02X".format(it) })
        }

        // The base: the provider, the config and the server, the socket moved to the phone.
        val base = built[0]
        assertNotNull(entry(base, Inject.CONFIG))
        assertTrue(contains(entry(base, "AndroidManifest.xml"), Inject.PROVIDER))
        assertContentEquals(serverDex, entry(base, "classes2.dex"))
        assertTrue(socket !in String(entry(base, "classes.dex"), Charsets.ISO_8859_1))

        // The split holding the library has its address moved, and nothing else added.
        val arm = built[1]
        assertTrue(game !in String(entry(arm, "lib/arm64-v8a/libgame.so"), Charsets.ISO_8859_1))
        assertTrue(!has(arm, Inject.CONFIG) && !has(arm, "classes2.dex"))

        // The split with no address keeps every entry byte for byte.
        val density = built[2]
        ZipFile(set[2].file.toFile()).use { z ->
            z.entries().asSequence().forEach { e -> assertContentEquals(z.getInputStream(e).use { it.readAllBytes() }, entry(density, e.name), e.name) }
        }
        ZipFile(density.toFile()).use { z -> assertEquals(setOf("AndroidManifest.xml", "res/drawable-xxhdpi/icon.png"), z.entries().asSequence().map { it.name }.filter { !it.startsWith("META-INF/") }.toSet()) }
    }

    // The set built under a new package name: every part says it, the provider
    // carries the authority of the new name though that name starts with the old one.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#RenameManifestPackage
    @Test
    fun theSetRenamedToANewPackageNamesItInEveryPart() = withDir { d ->
        val set = parts(d)
        val out = d.resolve("out/game-offline-beside")
        val servers = Endpoints.scan(set.map { it.file }).filter { it.likelyServer }
        val lines = mutableListOf<String>()
        val noting = object : JobSink {
            override fun emit(event: JobEvent) {
                if (event is JobEvent.Line) lines.add(event.text)
            }
        }
        val to = "com.example.game.offline"
        OfflineBuild.runSet(set, servers, serverDex, DexStrings(), d.resolve("work"), out, TestKey.load(), 16, "test", noting, renameTo = to)

        val built = Parts.apks(out)
        assertEquals(3, built.size)
        built.forEach { assertEquals(to, SplitSet.part(it).packageName, it.toString()) }
        val base = Inject.read(entry(built[0], "AndroidManifest.xml"))
        val authorities = mutableListOf<String>()
        fun walk(n: io.gutapk.core.apk.axml.Axml.Node) {
            n.attrs.filter { it.name == "authorities" }.forEach { authorities.add(it.value as String) }
            n.children.forEach { walk(it) }
        }
        base.firsts.forEach { walk(it) }
        assertEquals(listOf(Inject.authority(to)), authorities)
        assertTrue(lines.any { it.startsWith("package renamed to $to: ") }, lines.toString())
        built.forEach { assertTrue(ApkVerifier.Builder(it.toFile()).build().verify().isVerified, it.toString()) }
    }

    @Test
    fun aPartThatCarriesTheServerAlreadyIsRefused() = withDir { d ->
        val set = parts(d)
        val servers = Endpoints.scan(set.map { it.file }).filter { it.likelyServer }
        val extra = d.resolve("arm.apk")
        zip(extra, listOf("lib/arm64-v8a/libgame.so" to lib("$game/api"), Inject.CONFIG to ascii("x")))
        Files.move(extra, set[1].file, StandardCopyOption.REPLACE_EXISTING)
        assertFailsWith<CheckFailed> {
            OfflineBuild.runSet(set, servers, serverDex, DexStrings(), d.resolve("work"), d.resolve("out/o"), TestKey.load(), 16, "test", quiet)
        }
        assertNull(null)
    }

    @Test
    fun theInstallPlanListsEveryFileOfASet() {
        val files = listOf(Path.of("/o/base.apk"), Path.of("/o/split_config.arm64_v8a.apk"), Path.of("/o/split_config.xxhdpi.apk"))
        val plan = OfflineInstall.plan("emulator-5554", files, "com.example.game", true)
        files.forEach { f -> assertTrue(plan[0].contains(f.toString()), plan[0]) }
        assertTrue(plan[0].contains("install-multiple"), plan[0])
        assertEquals(plan[0], plan[2].substringAfter(": "))
        assertEquals(OfflineInstall.plan("s", listOf(files[0]), "p", false), OfflineInstall.plan("s", files[0], "p", false))
    }
}
