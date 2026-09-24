package io.gutapk

import io.gutapk.core.il2cpp.DumpParser
import io.gutapk.core.il2cpp.DumpRecord
import io.gutapk.core.il2cpp.Il2CppDump
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.features.overview.searchMethods
import io.gutapk.tools.Storage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
