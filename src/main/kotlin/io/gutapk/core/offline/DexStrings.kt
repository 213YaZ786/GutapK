package io.gutapk.core.offline

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.EncodedValueRewriter
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.nio.file.Path

// The servers of a dex pointed at the phone. A dex keeps its strings sorted
// and ART refuses one that is not ("Out-of-order string_ids"): an https
// address turned into http://127.0.0.1 in place moves ahead of its
// neighbours. So the dex is read and written again by dexlib2, each string
// holding an origin replaced, in a const-string or a constant value, and
// DexPool sorts the table anew. InstructionRewriter.rewrite leaves string
// references as they are (rewriteReference, case STRING): the two
// const-string formats are rebuilt here, the rest goes to it.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/RewriterModule.java#getInstructionRewriter
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/InstructionRewriter.java#rewrite
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/EncodedValueRewriter.java#rewrite
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
// DexStrings as the rest of GutapK sees it, dexlib2 not being loaded in
// GutapK: tools.Libraries loads DexStrings with the downloaded jars. Its
// writeTo is DexPool's, with the targets of the rewrite.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
// calls turns a virtual call into a static one: "Lclass;->name(params)return"
// to the class that answers it, the receiver passed first, in the same
// registers. RewrittenInstruction35c and 3rc keep an instruction's registers
// and rewrite its reference, here the opcode is made static too. The calls
// rewritten come back with the strings.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/InstructionRewriter.java#L191-L252
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/immutable/instruction/ImmutableInstruction35c.java#ImmutableInstruction35c
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/immutable/instruction/ImmutableInstruction3rc.java#ImmutableInstruction3rc
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/immutable/reference/ImmutableMethodReference.java#ImmutableMethodReference
// not ported: getRegisterCount, getRegisterC, getRegisterD, getRegisterE, getRegisterF, getRegisterG, getStartRegister (read from the instruction and passed to the immutable one as they are)
// not ported: getReference2, getReferenceType2 (an invoke-virtual has one reference)
interface DexStringRewrite {
    // Writes the dex to dataStore's file and returns the strings and calls it changed.
    fun writeTo(dataStore: Path, input: ByteArray, targets: Map<String, String>, calls: Map<String, String>): Set<String>

    // The classes whose code holds each origin, as a const-string or as the
    // constant value of a field: origin to class descriptors. An origin the
    // dex does not hold is not in the map.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/dexbacked/DexBackedDexFile.java#getStringSection
    fun classes(input: ByteArray, origins: Collection<String>): Map<String, Set<String>>

    // The keys of calls the dex references, in the form writeTo rewrites.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/dexbacked/DexBackedDexFile.java#getMethodSection
    fun found(input: ByteArray, calls: Map<String, String>): Set<String>

    // The classes of the dex that are a Google Play Games helper: the ones that
    // define every method of Inject.GameHelper, whatever the class is called.
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/ClassDefinition.java#writeVirtualMethods
    fun helpers(input: ByteArray): Set<String>
}

class DexStrings : DexStringRewrite {
    // The scan walks a class as baksmali writes it, the static fields with
    // their initial values, then the methods with their instructions, and
    // reads where baksmali writes: the string a field value or a reference
    // holds goes to the class being walked.
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/ClassDefinition.java#writeStaticFields
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/FieldDefinition.java#writeTo
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/MethodDefinition.java#writeTo
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/MethodImplementationRewriter.java#getInstructions
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/dexbacked/DexBackedDexFile.java#getStringSection
    // not ported: writeStaticFields (the loop over the fields of classDef below)
    // not ported: writeAccessFlagsAndRestrictions (no flag is written)
    // not ported: writeClass, writeAccessFlags, writeSuper, writeSourceFile, writeInterfaces, writeAnnotations, writeInstanceFields, writeDirectMethods, writeVirtualMethods (only the code of a class holds a string the app uses)
    override fun classes(input: ByteArray, origins: Collection<String>): Map<String, Set<String>> {
        val classes = HashMap<String, MutableSet<String>>()
        val dexFile = DexBackedDexFile(null, input)
        // Most dex files hold none of the origins: the string table says so before a class is opened.
        if (dexFile.stringSection.none { string -> origins.any { it in string } }) return classes
        fun holding(string: String, type: String) {
            for (origin in origins) {
                if (origin in string) classes.getOrPut(origin) { HashSet() }.add(type)
            }
        }
        for (classDef in dexFile.classes) {
            for (field in classDef.fields) {
                val initialValue = field.initialValue
                if (initialValue is StringEncodedValue) holding(initialValue.value, classDef.type)
            }
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    val reference = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
                    holding(reference.string, classDef.type)
                }
            }
        }
        return classes
    }

    // The method table of the dex lists every call it makes or declares:
    // run's loop over the references of one type, kept where run prints.
    // https://github.com/google/smali/blob/3.0.9/baksmali/src/main/java/com/android/tools/smali/baksmali/ListReferencesCommand.java#run
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/util/ReferenceUtil.java#getMethodDescriptor
    // not ported: usage, loadDexFile, getReference (the input is the bytes given, nothing is printed)
    override fun found(input: ByteArray, calls: Map<String, String>): Set<String> {
        val dexFile = DexBackedDexFile(null, input)
        val references = HashSet<String>()
        for (reference in dexFile.getReferences(ReferenceType.METHOD)) {
            val descriptor = ReferenceUtil.getMethodDescriptor(reference as MethodReference)
            if (descriptor in calls) references.add(descriptor)
        }
        return references
    }

    // A class is the helper when its virtual methods hold the GameHelper's
    // three, found as writeVirtualMethods lists them, one descriptor each.
    // https://github.com/google/smali/blob/3.0.9/third_party/baksmali/src/main/java/com/android/tools/smali/baksmali/Adaptors/ClassDefinition.java#writeVirtualMethods
    // https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#setConnectOnStart
    // not ported: writeVirtualMethods (it writes smali, only its loop over the virtual methods and their descriptors is kept)
    // not ported: connectOnStart (the helper's flag, SignIn sets it when the game runs)
    override fun helpers(input: ByteArray): Set<String> {
        val dexFile = DexBackedDexFile(null, input)
        // Most dex files hold no setConnectOnStart: the string table says so before a class is opened.
        if (dexFile.stringSection.none { it == "setConnectOnStart" }) return emptySet()
        val helpers = HashSet<String>()
        for (classDef in dexFile.classes) {
            val writtenMethods = HashSet<String>()
            for (method in classDef.virtualMethods) {
                val methodString = ReferenceUtil.getMethodDescriptor(method)
                writtenMethods.add(methodString)
            }
            if (Inject.GameHelper.all { "${classDef.type}->$it" in writtenMethods }) helpers.add(classDef.type)
        }
        return helpers
    }

    override fun writeTo(dataStore: Path, input: ByteArray, targets: Map<String, String>, calls: Map<String, String>): Set<String> {
        val changed = HashSet<String>()
        fun rewrite(string: String): String {
            val rewritten = targets.entries.fold(string) { s, (origin, target) -> s.replace(origin, target) }
            if (rewritten != string) changed.add(string)
            return rewritten
        }
        fun rewrite(instruction: Instruction, reference: MethodReference): Instruction? {
            val key = "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
            val definingClass = calls[key] ?: return null
            // The server's dex is built against the Android API only, so the receiver of an SDK's class goes as an Object, which any receiver fits.
            // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/immutable/reference/ImmutableMethodReference.java#ImmutableMethodReference
            val receiver = if (reference.definingClass.startsWith("Landroid/")) reference.definingClass else "Ljava/lang/Object;"
            val method = ImmutableMethodReference(definingClass, reference.name, listOf(receiver) + reference.parameterTypes.map { it.toString() }, reference.returnType)
            val static = when {
                instruction is Instruction35c && instruction.opcode == Opcode.INVOKE_VIRTUAL ->
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, instruction.registerCount, instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG, method)
                instruction is Instruction3rc && instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE ->
                    ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, instruction.startRegister, instruction.registerCount, method)
                else -> return null
            }
            changed.add(key)
            return static
        }
        val module = object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> = object : InstructionRewriter(rewriters) {
                override fun rewrite(instruction: Instruction): Instruction {
                    val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    if (method != null) return rewrite(instruction, method) ?: super.rewrite(instruction)
                    val reference = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: return super.rewrite(instruction)
                    return when (instruction) {
                        is Instruction21c -> ImmutableInstruction21c(instruction.opcode, instruction.registerA, ImmutableStringReference(rewrite(reference.string)))
                        is Instruction31c -> ImmutableInstruction31c(instruction.opcode, instruction.registerA, ImmutableStringReference(rewrite(reference.string)))
                        else -> super.rewrite(instruction)
                    }
                }
            }

            override fun getEncodedValueRewriter(rewriters: Rewriters): Rewriter<EncodedValue> = object : EncodedValueRewriter(rewriters) {
                override fun rewrite(encodedValue: EncodedValue): EncodedValue =
                    if (encodedValue is StringEncodedValue) ImmutableStringEncodedValue(rewrite(encodedValue.value)) else super.rewrite(encodedValue)
            }
        }
        val dexFile = DexRewriter(module).dexFileRewriter.rewrite(DexBackedDexFile(null, input))
        val dexPool = DexPool(dexFile.opcodes)
        for (classDef in dexFile.classes) {
            dexPool.internClass(classDef)
        }
        dexPool.writeTo(FileDataStore(dataStore.toFile()))
        return changed
    }
}
