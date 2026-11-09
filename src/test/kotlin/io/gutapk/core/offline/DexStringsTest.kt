package io.gutapk.core.offline

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The dex Bim carried: an https address among strings that sort around it,
// which a patch in place left out of order and ART refused. Built with
// dexlib2's immutable classes the way its own writer tests build theirs.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
class DexStringsTest {
    private val type = "Lgame/Net;"
    private val api = "https://eu-assets.i.posthog.com"

    private fun dex(): ByteArray {
        val field = ImmutableField(type, "HOST", "Ljava/lang/String;", AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue("$api/static"), null, null)
        val code = ImmutableMethodImplementation(
            1,
            listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("$api/decide")), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            null,
            null,
        )
        val method = ImmutableMethod(type, "url", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null, code)
        // Strings that sort between http: and https:, as in Bim's dex.
        val other = ImmutableMethod(type, "docs", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(1, listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("https://developer.apple.com/streaming/emsg-id3")), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null))
        val classDef = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, listOf(field), listOf(method, other))
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(classDef)
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        return dataStore.data
    }

    @Test
    fun pointsTheStringsAtThePhoneAndKeepsThemSorted() {
        val target = Redirect.target(api, port = 29477)!!
        val out = Files.createTempFile("gutapk", ".dex")
        try {
            val changed = DexStrings().writeTo(out, dex(), mapOf(api to target), emptyMap())
            assertEquals(setOf("$api/static", "$api/decide"), changed)
            val back = DexBackedDexFile(null, Files.readAllBytes(out))
            val strings = back.stringSection.toList()
            assertTrue(strings.none { api in it }, strings.toString())
            // ART's order: string_ids sorted by their UTF-16 code units, which String.compareTo is for these.
            assertEquals(strings.sorted(), strings)
            val classDef = back.classes.single()
            assertEquals("$target/static", (classDef.staticFields.single().initialValue as StringEncodedValue).value)
            val url = classDef.methods.single { it.name == "url" }.implementation!!.instructions.first() as ReferenceInstruction
            assertEquals("$target/decide", (url.reference as StringReference).string)
            // A dex with none of the origins is written again unchanged in its strings.
            assertEquals(emptySet(), DexStrings().writeTo(out, dex(), mapOf("https://api.example-game.com" to "http://127.0.0.1:00020000"), emptyMap()))
        } finally {
            Files.deleteIfExists(out)
        }
    }

    // Jungle Heat 2.2.7's Device.isNetworkAvailable: the manager's
    // getActiveNetworkInfo, then isConnected. With the phone in flight mode
    // the game stopped there. The call goes to the server's Net, the receiver
    // first, in the same register; isConnected is left as it is.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/InstructionRewriter.java#L191-L252
    @Test
    fun aNetworkCheckIsAnsweredByTheServer() {
        val manager = Inject.CONNECTIVITY
        val info = "Landroid/net/NetworkInfo;"
        val active = ImmutableMethodReference(manager, "getActiveNetworkInfo", null, info)
        val connected = ImmutableMethodReference(info, "isConnected", null, "Z")
        val code = ImmutableMethodImplementation(
            2,
            listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, active),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, connected),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            null,
            null,
        )
        val method = ImmutableMethod(type, "isNetworkAvailable", listOf(ImmutableMethodParameter(manager, null, null)), "Z", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        val out = Files.createTempFile("gutapk", ".dex")
        try {
            val changed = DexStrings().writeTo(out, dataStore.data, emptyMap(), Inject.NETWORK_CALLS)
            assertEquals(setOf("$manager->getActiveNetworkInfo()$info"), changed)
            val instructions = DexBackedDexFile(null, Files.readAllBytes(out)).classes.single().methods.single().implementation!!.instructions.toList()
            val call = instructions[0] as Instruction35c
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(1, call.registerCount)
            assertEquals(1, call.registerC)
            val reference = call.reference as MethodReference
            assertEquals("Lio/gutapk/offline/Net;", reference.definingClass)
            assertEquals("getActiveNetworkInfo", reference.name)
            assertEquals(listOf(manager), reference.parameterTypes.map { it.toString() })
            assertEquals(info, reference.returnType)
            assertEquals(Opcode.INVOKE_VIRTUAL, instructions[2].opcode)
        } finally {
            Files.deleteIfExists(out)
        }
    }
}
