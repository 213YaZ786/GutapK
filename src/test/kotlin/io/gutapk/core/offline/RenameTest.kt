package io.gutapk.core.offline

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.gutapk.core.apk.axml.Axml
import io.gutapk.core.apk.axml.AxmlWriter
import io.gutapk.core.apk.axml.NodeVisitor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The manifest of a game renamed as aapt2 renames one, with the authorities and
// the permissions it owns, and the dex strings that are the old name as a whole.
// The manifest is built as the offline tests build one, the dex as dexlib2 writes one.
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#RenameManifestPackage
class RenameTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val old = "com.example.game"
    private val new = "com.example.game.offline"

    private fun NodeVisitor.named(name: String, value: String) = attr(android, name, 0x01010003, NodeVisitor.TYPE_STRING, value)

    private fun manifest(split: String? = null): ByteArray {
        val w = AxmlWriter()
        w.ns("android", android, 1)
        val m = w.child(null, "manifest")!!
        m.line(2)
        m.attr(null, "package", -1, NodeVisitor.TYPE_STRING, old)
        if (split != null) m.attr(null, "split", -1, NodeVisitor.TYPE_STRING, split)
        fun leaf(parent: NodeVisitor, tag: String, build: NodeVisitor.() -> Unit) {
            val e = parent.child(null, tag)!!
            e.build()
            e.end()
        }
        leaf(m, "permission") { named("name", "$old.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        leaf(m, "permission") { named("name", "com.other.app.PERMISSION") }
        leaf(m, "uses-permission") { named("name", "$old.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        leaf(m, "uses-permission") { named("name", "com.other.app.PERMISSION") }
        leaf(m, "uses-permission") { named("name", "android.permission.INTERNET") }
        val app = m.child(null, "application")!!
        app.named("name", ".App")
        app.named("backupAgent", "Agent")
        leaf(app, "activity") { named("name", ".Main") }
        leaf(app, "activity") { named("name", "com.lib.Other") }
        leaf(app, "activity-alias") {
            named("name", "Alias")
            attr(android, "targetActivity", 0x01010202, NodeVisitor.TYPE_STRING, ".Main")
        }
        leaf(app, "service") {
            named("name", "com.lib.Svc")
            attr(android, "permission", 0x01010006, NodeVisitor.TYPE_STRING, "$old.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        leaf(app, "provider") {
            named("name", "androidx.startup.InitializationProvider")
            attr(android, "authorities", 0x01010018, NodeVisitor.TYPE_STRING, "$old.androidx-startup;com.foreign.auth;$old")
        }
        app.end()
        m.end()
        w.end()
        return w.toByteArray()
    }

    private fun tree(bytes: ByteArray): Axml.Node = Inject.read(bytes).firsts.single { it.name == "manifest" }

    private fun Axml.Node.value(name: String): String? = attrs.firstOrNull { it.name == name }?.value as? String

    private fun Axml.Node.all(tag: String): List<Axml.Node> = (if (name == tag) listOf(this) else emptyList()) + children.flatMap { it.all(tag) }

    @Test
    fun theManifestTakesTheNewPackageAndItsRelativeNamesStayAbsolute() {
        val (bytes, map) = Rename.renameManifest(manifest(), new)
        val m = tree(bytes)
        assertEquals(new, m.value("package"))
        val app = m.all("application").single()
        assertEquals("$old.App", app.value("name"))
        assertEquals("$old.Agent", app.value("backupAgent"))
        assertEquals(listOf("$old.Main", "com.lib.Other"), m.all("activity").map { it.value("name") })
        val alias = m.all("activity-alias").single()
        assertEquals("$old.Alias", alias.value("name"))
        assertEquals("$old.Main", alias.value("targetActivity"))
        assertEquals("com.lib.Svc", m.all("service").single().value("name"))
        assertEquals(new, map[old])
    }

    @Test
    fun theAuthoritiesOfTheAppFollowAndAForeignOneStays() {
        val (bytes, map) = Rename.renameManifest(manifest(), new)
        assertEquals("$new.androidx-startup;com.foreign.auth;$new", tree(bytes).all("provider").single().value("authorities"))
        assertEquals("$new.androidx-startup", map["$old.androidx-startup"])
        assertNull(map["com.foreign.auth"])
    }

    @Test
    fun theAuthorityKeptIsNotRenamedAgain() {
        // The server's provider for the new name starts with the old name.
        val kept = Inject.authority(new)
        val result = Rename.renameManifest(Inject.manifest(manifest(), kept).bytes, new, setOf(kept))
        val authorities = tree(result.bytes).all("provider").mapNotNull { it.value("authorities") }
        assertTrue(kept in authorities, authorities.toString())
    }

    @Test
    fun theDeclaredPermissionAndEveryUseOfItFollowAndAForeignOneStays() {
        val result = Rename.renameManifest(manifest(), new, emptySet())
        val m = tree(result.bytes)
        val own = "$new.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(listOf(own, "com.other.app.PERMISSION"), m.all("permission").map { it.value("name") })
        assertEquals(listOf(own, "com.other.app.PERMISSION", "android.permission.INTERNET"), m.all("uses-permission").map { it.value("name") })
        assertEquals(own, m.all("service").single().value("permission"))
        assertEquals(1, result.permissions)
        assertEquals(2, result.authorities)
        assertEquals(5, result.classNames)
    }

    @Test
    fun aSplitManifestTakesTheNewPackageToo() {
        val bytes = Rename.renameManifest(manifest("config.fr"), new).first
        val m = tree(bytes)
        assertEquals(new, m.value("package"))
        assertEquals("config.fr", m.value("split"))
    }

    private fun dex(vararg strings: String): ByteArray {
        val code = ImmutableMethodImplementation(
            1,
            strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            null,
            null,
        )
        val method = ImmutableMethod("Lgame/Id;", "id", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef("Lgame/Id;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    @Test
    fun aDexStringIsRenamedOnlyAsAWholeAndAClassNameStays() {
        val out = Files.createTempFile("gutapk", ".dex")
        try {
            val whole = mapOf(old to new, "$old.androidx-startup" to "$new.androidx-startup")
            val changed = DexStrings().writeTo(out, dex(old, "$old.Foo", "content://$old/x", "$old.androidx-startup", "${old}x"), emptyMap(), emptyMap(), whole)
            assertEquals(setOf(old, "content://$old/x", "$old.androidx-startup"), changed)
            val strings = DexBackedDexFile(null, Files.readAllBytes(out)).stringSection.toList()
            assertTrue(new in strings, strings.toString())
            assertTrue("$old.Foo" in strings, strings.toString())
            assertTrue("${old}x" in strings, strings.toString())
            assertTrue("content://$new/x" in strings, strings.toString())
            assertTrue("$new.androidx-startup" in strings, strings.toString())
            assertTrue(old !in strings, strings.toString())
            // ART's order stays: string_ids sorted.
            assertEquals(strings.sorted(), strings)
            val instructions = DexBackedDexFile(null, Files.readAllBytes(out)).classes.single().methods.single().implementation!!.instructions
            assertTrue(instructions.any { (it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == new } })
        } finally {
            Files.deleteIfExists(out)
        }
    }
}
