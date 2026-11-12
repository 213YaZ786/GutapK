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
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
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
            val changed = DexStrings().writeTo(out, dex(), mapOf(api to target), emptyMap(), emptyMap())
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
            assertEquals(emptySet(), DexStrings().writeTo(out, dex(), mapOf("https://api.example-game.com" to "http://127.0.0.1:00020000"), emptyMap(), emptyMap()))
        } finally {
            Files.deleteIfExists(out)
        }
    }

    // The classes that hold an origin: the one with the constant value of a field and the
    // const-string of a method, an origin the dex does not hold is not in the answer.
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/ClassDefinition.java#writeStaticFields
    @Test
    fun theClassesHoldingAnOriginAreFound() {
        val held = DexStrings().classes(dex(), listOf(api, "https://developer.apple.com", "https://absent.example.org"))
        assertEquals(mapOf(api to setOf(type), "https://developer.apple.com" to setOf(type)), held)
        assertEquals(emptyMap(), DexStrings().classes(dex(), listOf("https://absent.example.org")))
    }

    // MRGSGDPR.showAgreementAtActivity as Jungle Heat 2.2.7 calls it, the SDK's receiver in v0 and
    // the activity and the two names after it: the call goes to Consent, the receiver as an Object
    // since the server's dex knows no SDK, the registers as they were.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/InstructionRewriter.java#L191-L252
    @Test
    fun aConsentCallIsAnsweredByTheServer() {
        val sdk = Inject.MRGS_CONSENT
        val activity = "Landroid/app/Activity;"
        val string = "Ljava/lang/String;"
        val show = ImmutableMethodReference(sdk, "showAgreementAtActivity", listOf(activity, string, string), "V")
        val other = ImmutableMethodReference(sdk, "setDelegate", listOf("Lgames/my/mrgs/gdpr/MRGSGDPR\$MRGSGDPRDelegate;"), "V")
        val code = ImmutableMethodImplementation(
            4,
            listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, other),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 0, 1, 2, 3, 0, show),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            null,
            null,
        )
        val params = listOf(sdk, activity, string, string).map { ImmutableMethodParameter(it, null, null) }
        val method = ImmutableMethod(type, "ask", params, "V", AccessFlags.STATIC.value, null, null, code)
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        // The call is in the dex, found without being rewritten.
        assertEquals(setOf("$sdk->showAgreementAtActivity($activity$string$string)V"), DexStrings().found(dataStore.data, Inject.CONSENT_CALLS))
        assertEquals(emptySet(), DexStrings().found(dex(), Inject.CONSENT_CALLS))
        val out = Files.createTempFile("gutapk", ".dex")
        try {
            val changed = DexStrings().writeTo(out, dataStore.data, emptyMap(), Inject.CONSENT_CALLS, emptyMap())
            assertEquals(setOf("$sdk->showAgreementAtActivity($activity$string$string)V"), changed)
            val instructions = DexBackedDexFile(null, Files.readAllBytes(out)).classes.single().methods.single().implementation!!.instructions.toList()
            // Another call of the SDK is left alone.
            assertEquals(Opcode.INVOKE_VIRTUAL, instructions[0].opcode)
            val call = instructions[1] as Instruction35c
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(listOf(4, 0, 1, 2, 3), listOf(call.registerCount, call.registerC, call.registerD, call.registerE, call.registerF))
            val reference = call.reference as MethodReference
            assertEquals("Lio/gutapk/offline/Consent;", reference.definingClass)
            assertEquals("showAgreementAtActivity", reference.name)
            assertEquals(listOf("Ljava/lang/Object;", activity, string, string), reference.parameterTypes.map { it.toString() })
            assertEquals("V", reference.returnType)
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
            val changed = DexStrings().writeTo(out, dataStore.data, emptyMap(), Inject.NETWORK_CALLS, emptyMap())
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

    // A GameHelper under an obfuscated name, as BaseGameUtils' copy in Jungle Heat 2.2.7 defines
    // them: a virtual method each, the class found by the three and by nothing else.
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/ClassDefinition.java#writeVirtualMethods
    private fun helperDex(name: String, methods: List<Pair<String, List<String>>>, caller: Boolean = false): ByteArray {
        val helper = "Lgame/$name;"
        val own = methods.map { (method, params) ->
            val code = ImmutableMethodImplementation(1 + params.size, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            ImmutableMethod(helper, method, params.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null, code)
        }
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef(helper, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, own))
        if (caller) {
            // The activity's onStart: the helper and the activity are the two parameters, v1 and v2.
            val start = ImmutableMethodReference(helper, "onStart", listOf("Landroid/app/Activity;"), "V")
            val code = ImmutableMethodImplementation(
                3,
                listOf(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, start), ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            )
            val params = listOf(helper, "Landroid/app/Activity;").map { ImmutableMethodParameter(it, null, null) }
            val method = ImmutableMethod("Lgame/Main;", "begin", params, "V", AccessFlags.STATIC.value, null, null, code)
            dexPool.internClass(ImmutableClassDef("Lgame/Main;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        }
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    private val activity = "Landroid/app/Activity;"
    private val all = listOf("setConnectOnStart" to listOf("Z"), "beginUserInitiatedSignIn" to emptyList(), "onStart" to listOf(activity))

    @Test
    fun aClassWithTheThreeMethodsIsAHelperWhateverItsName() {
        assertEquals(setOf("Lgame/a/b;"), DexStrings().helpers(helperDex("a/b", all)))
        assertEquals(setOf("Lgame/GameHelper;"), DexStrings().helpers(helperDex("GameHelper", all + ("signOut" to emptyList()))))
    }

    @Test
    fun aClassWithTwoOfTheThreeMethodsIsNotAHelper() {
        for (left in all.indices) {
            assertEquals(emptySet(), DexStrings().helpers(helperDex("GameHelper", all.filterIndexed { i, _ -> i != left })), "without ${all[left].first}")
        }
        // The names are not enough: onStart with another signature is another method.
        assertEquals(emptySet(), DexStrings().helpers(helperDex("GameHelper", all.dropLast(1) + ("onStart" to emptyList()))))
        assertEquals(emptySet(), DexStrings().helpers(dex()))
    }

    // The activity's call to the helper's onStart goes to SignIn, the helper as an Object and the
    // registers as they were, found in the dex and left alone when it is not the helper's.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/InstructionRewriter.java#L191-L252
    @Test
    fun theHelpersStartIsAnsweredBySignIn() {
        val input = helperDex("GameHelper", all, caller = true)
        val calls = Inject.onStart(DexStrings().helpers(input))
        val start = "Lgame/GameHelper;->onStart(Landroid/app/Activity;)V"
        assertEquals(mapOf(start to "Lio/gutapk/offline/SignIn;", "Lgame/GameHelper;->beginUserInitiatedSignIn()V" to "Lio/gutapk/offline/SignIn;"), calls)
        assertEquals(calls.keys, DexStrings().found(input, calls))
        val out = Files.createTempFile("gutapk", ".dex")
        try {
            // Only the start is called in this dex, the sign-in method is only defined.
            assertEquals(setOf(start), DexStrings().writeTo(out, input, emptyMap(), calls, emptyMap()))
            val back = DexBackedDexFile(null, Files.readAllBytes(out))
            val call = back.classes.single { it.type == "Lgame/Main;" }.methods.single().implementation!!.instructions.first() as Instruction35c
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            assertEquals(listOf(2, 1, 2), listOf(call.registerCount, call.registerC, call.registerD))
            val reference = call.reference as MethodReference
            assertEquals("Lio/gutapk/offline/SignIn;", reference.definingClass)
            assertEquals("onStart", reference.name)
            assertEquals(listOf("Ljava/lang/Object;", activity), reference.parameterTypes.map { it.toString() })
            assertEquals("V", reference.returnType)
            // The helper's own onStart is still there for SignIn to call.
            assertTrue(back.classes.single { it.type == "Lgame/GameHelper;" }.virtualMethods.any { it.name == "onStart" })
        } finally {
            Files.deleteIfExists(out)
        }
    }
}
