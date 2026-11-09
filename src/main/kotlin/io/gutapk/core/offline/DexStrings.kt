package io.gutapk.core.offline

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.EncodedValueRewriter
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
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
fun interface DexStringRewrite {
    // Writes the dex to dataStore's file and returns the strings it changed.
    fun writeTo(dataStore: Path, input: ByteArray, targets: Map<String, String>): Set<String>
}

class DexStrings : DexStringRewrite {
    override fun writeTo(dataStore: Path, input: ByteArray, targets: Map<String, String>): Set<String> {
        val changed = HashSet<String>()
        fun rewrite(string: String): String {
            val rewritten = targets.entries.fold(string) { s, (origin, target) -> s.replace(origin, target) }
            if (rewritten != string) changed.add(string)
            return rewritten
        }
        val module = object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> = object : InstructionRewriter(rewriters) {
                override fun rewrite(instruction: Instruction): Instruction {
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
