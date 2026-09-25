package io.gutapk

import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.DumpParser
import io.gutapk.core.il2cpp.DumpRecord
import io.gutapk.core.il2cpp.Il2CppDump
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.Hex
import io.gutapk.core.il2cpp.LibBytes
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.core.il2cpp.PatchProblem
import io.gutapk.core.il2cpp.Patches
import io.gutapk.features.overview.searchMethods
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The layout of Cpp2IL 2022.1.0 pre-release 21 diffable-cs output, as it
// wrote Daggerfall Unity 1.1.1.9: tabs for nesting, attributes above each
// member, accessors named on their own.
class Il2CppTest {

    private val file = listOf(
        "namespace DaggerfallWorkshop;",
        "",
        "[Token(Token = \"0x20001BD\")]",
        "public class DaggerfallUnity : MonoBehaviour",
        "{",
        "\t[Token(Token = \"0x20001C2\")]",
        "\tinternal sealed class OnReadyEventHandler : MulticastDelegate",
        "\t{",
        "",
        "\t\t[Address(RVA = \"0x21270A8\", Offset = \"0x21270A8\", Length = \"0x14\")]",
        "\t\t[Token(Token = \"0x6000919\")]",
        "\t\tpublic override void Invoke() { }",
        "\t}",
        "",
        "\t[Token(Token = \"0x17000177\")]",
        "\tpublic static ulong CurrentUID",
        "\t{",
        "\t\t[Address(RVA = \"0x2124C34\", Offset = \"0x2124C34\", Length = \"0x58\")]",
        "\t\t[Token(Token = \"0x60008E0\")]",
        "\t\t get { } //Length: 88",
        "\t\t[Address(RVA = \"0x2124C8C\", Offset = \"0x2124C8C\", Length = \"0x68\")]",
        "\t\t[Token(Token = \"0x60008E1\")]",
        "\t\tprivate set { } //Length: 104",
        "\t}",
        "",
        "\t[Address(RVA = \"0x2125328\", Offset = \"0x2125328\", Length = \"0x8C\")]",
        "\t[Token(Token = \"0x60008F8\")]",
        "\tpublic static bool get_HasInstance() { }",
        "}",
    )

    private val global = listOf(
        "//Type is in global namespace",
        "",
        "[Token(Token = \"0x2000064\")]",
        "internal static class \$BurstDirectCallInitializer",
        "{",
        "\t[Address(RVA = \"0x3C3CCA8\", Offset = \"0x3C3CCA8\", Length = \"0xA4\")]",
        "\t[RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType::AfterAssembliesLoaded (2))]",
        "\tprivate static void Initialize() { }",
        "}",
    )

    private fun parse(lines: List<String>): List<MethodEntry> {
        val out = mutableListOf<MethodEntry>()
        DumpParser.parseFile("Assembly-CSharp", lines.asSequence(), out)
        return out
    }

    @Test
    fun readsMethodsTypesAndAccessors() {
        val m = parse(file)
        assertEquals(
            listOf(
                "DaggerfallUnity.OnReadyEventHandler" to "public override void Invoke()",
                "DaggerfallUnity" to "CurrentUID.get",
                "DaggerfallUnity" to "CurrentUID.set",
                "DaggerfallUnity" to "public static bool get_HasInstance()",
            ),
            m.map { it.type to it.member },
        )
        assertEquals("DaggerfallWorkshop", m[0].namespace)
        assertEquals(0x21270A8L, m[0].offset)
        assertEquals(0x14L, m[0].length)
        assertEquals(0x2125328L, m[3].rva)
    }

    @Test
    fun readsATypeOutsideAnyNamespace() {
        val m = parse(global).single()
        assertEquals("", m.namespace)
        assertEquals("\$BurstDirectCallInitializer", m.type)
        assertEquals("private static void Initialize()", m.member)
    }

    @Test
    fun indexRoundTripsAndSearches() {
        val dir = Files.createTempDirectory("gutapk-il2cpp")
        try {
            val entries = parse(file)
            MethodIndex.write(dir, entries, DumpRecord("arm64-v8a", entries.size, "ab", "2022.1.0-pre-release.21", "2022.3.62f3"))
            assertEquals(entries, MethodIndex.read(dir))
            assertEquals("arm64-v8a", MethodIndex.record(dir)?.abi)
            assertEquals(4, MethodIndex.record(dir)?.count)

            assertEquals(2, searchMethods(entries, "uid daggerfall", 10).first)
            assertEquals(listOf("public override void Invoke()"), searchMethods(entries, "ONREADY invoke", 10).second.map { it.member })
            assertEquals(0, searchMethods(entries, "  ", 10).first)
            assertEquals(1, searchMethods(entries, "daggerfall", 1).second.size)
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun prefersTheAbiPhonesRun() {
        assertEquals("arm64-v8a", Il2CppDump.preferredAbi(listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals("armeabi-v7a", Il2CppDump.preferredAbi(listOf("armeabi-v7a")))
        assertEquals(null, Il2CppDump.preferredAbi(emptyList()))
    }

    @Test
    fun readsAndWritesHex() {
        assertContentEquals(byteArrayOf(0x20, 0, 0x80.toByte(), 0x52), Hex.parse("20 00 80 52"))
        assertContentEquals(byteArrayOf(0xc0.toByte(), 0x03), Hex.parse("c003"))
        assertNull(Hex.parse("2"))
        assertNull(Hex.parse("zz"))
        assertNull(Hex.parse(" "))
        assertEquals("C0 03 5F D6", Hex.format(byteArrayOf(0xc0.toByte(), 0x03, 0x5f, 0xd6.toByte())))
    }

    // A method of 8 bytes at 0x100. Patches must stay inside it, change
    // something, and not overlap each other.
    @Test
    fun makesOnlySoundPatches() {
        val original = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        fun make(offset: Long, text: String, existing: List<BytePatch> = emptyList()) =
            Patches.make("arm64-v8a", offset, text, "T m", 0x100, original, existing)

        val (made, problem) = make(0x102, "AA BB")
        assertNull(problem)
        val patch = assertNotNull(made)
        assertEquals(BytePatch("arm64-v8a", 0x102, "03 04", "AA BB", "T m"), patch)
        assertEquals(PatchProblem.BadHex, make(0x100, "A").second)
        assertEquals(PatchProblem.OutOfRange, make(0xff, "AA").second)
        assertEquals(PatchProblem.OutOfRange, make(0x107, "AA BB").second)
        assertEquals(PatchProblem.Unchanged, make(0x100, "01 02").second)
        assertEquals(PatchProblem.Overlaps(patch), make(0x103, "00", listOf(patch)).second)
        assertNull(make(0x104, "00", listOf(patch)).second)

        val (shown, changed) = Patches.overlay(original, 0x100, "arm64-v8a", listOf(patch))
        assertContentEquals(byteArrayOf(1, 2, 0xaa.toByte(), 0xbb.toByte(), 5, 6, 7, 8), shown)
        assertEquals(listOf(2, 3), changed.indices.filter { changed[it] })
        assertContentEquals(original, Patches.overlay(original, 0x100, "armeabi-v7a", listOf(patch)).first)
    }

    @Test
    fun patchesRoundTripAndLibraryIsReadAtAnOffset() {
        val dir = Files.createTempDirectory("gutapk-patch")
        try {
            val patches = listOf(
                BytePatch("arm64-v8a", 0x2125328, "FF 43 01 D1", "20 00 80 52", "DaggerfallUnity get_HasInstance"),
                BytePatch("arm64-v8a", 0x10, "00", "01", "A b"),
            )
            Patches.write(dir, patches)
            assertEquals(patches.sortedBy { it.offset }, Patches.read(dir))

            val lib = ByteArray(4096) { (it % 251).toByte() }
            val apk = dir.resolve("game.apk")
            ZipOutputStream(Files.newOutputStream(apk)).use { z ->
                // Stored the way Android wants native libraries, and a
                // compressed copy under another ABI.
                val stored = ZipEntry(LibBytes.entry("arm64-v8a"))
                stored.method = ZipEntry.STORED
                stored.size = lib.size.toLong()
                stored.crc = CRC32().apply { update(lib) }.value
                z.putNextEntry(stored)
                z.write(lib)
                z.closeEntry()
                z.putNextEntry(ZipEntry(LibBytes.entry("armeabi-v7a")))
                z.write(lib)
                z.closeEntry()
            }
            listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
                assertContentEquals(lib.copyOfRange(3000, 3016), LibBytes.read(apk, abi, 3000, 16))
                assertContentEquals(lib.copyOfRange(4090, 4096), LibBytes.read(apk, abi, 4090, 16))
            }
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    // Every patch is checked before any is written: a library from another
    // build is refused whole, not left half patched.
    @Test
    fun appliesPatchesOnlyWhenAllMatch() {
        val dir = Files.createTempDirectory("gutapk-apply")
        try {
            val lib = dir.resolve("libil2cpp.so")
            val bytes = ByteArray(64) { it.toByte() }
            Files.write(lib, bytes)
            val good = BytePatch("arm64-v8a", 0x10, "10 11 12 13", "20 00 80 52", "A b")
            val stale = BytePatch("arm64-v8a", 0x20, "FF FF", "00 00", "C d")
            val libs: (String) -> java.nio.file.Path? = { abi -> if (abi == "arm64-v8a") lib else null }
            val lines = mutableListOf<String>()

            assertFailsWith<java.io.IOException> { Patches.apply(libs, listOf(good, stale)) { lines.add(it) } }
            assertContentEquals(bytes, Files.readAllBytes(lib))
            assertFailsWith<java.io.IOException> { Patches.apply(libs, listOf(good.copy(abi = "x86"))) { lines.add(it) } }
            assertFailsWith<java.io.IOException> { Patches.apply(libs, listOf(good.copy(offset = 62))) { lines.add(it) } }
            assertEquals(emptyList(), lines)

            Patches.apply(libs, listOf(good)) { lines.add(it) }
            val after = Files.readAllBytes(lib)
            assertContentEquals(byteArrayOf(0x20, 0, 0x80.toByte(), 0x52), after.copyOfRange(0x10, 0x14))
            assertContentEquals(bytes.copyOfRange(0x14, 64), after.copyOfRange(0x14, 64))
            assertEquals(1, lines.size)
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }
}
