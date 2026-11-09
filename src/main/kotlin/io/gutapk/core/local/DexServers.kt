package io.gutapk.core.local

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.io.File

// A port a Java game's server binds to: whether it takes UDP too, and the
// calls the value goes through, the bind first.
data class JavaServer(val port: Long, val udp: Boolean, val chain: List<String>)

// DexServers as the rest of GutapK sees it, dexlib2 not being loaded in
// GutapK: tools.Libraries loads DexServers with the downloaded jars.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyze
fun interface JavaServerScan {
    fun scan(apk: File): List<JavaServer>
}

// The ports a Java game's server binds to, read in its dex: every server
// bind of the Java library given a port alone (every network), the port's
// register followed back the way MethodAnalyzer follows a register's type,
// a const giving it (analyzeConst), a move copying it (analyzeMove), a
// static field its value, a parameter its value at each call of the method
// (the type the call names, or a supertype of the method's class: extendsFrom). One
// path is followed back, not every branch: the user reviews what is found.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyze
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeMove
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeConst
// not ported: analyze, analyzeConst (as functions of their own: values walks back and reads a const where it finds one)
// not ported: classPath, RegisterType, setPostRegisterTypeAndPropagateChanges, setDestinationRegisterTypeAndPropagateChanges, propagateParameterTypes, getRegisterTypeForLiteral (a value is followed, not a type)
// not ported: analyzedState, instructionsToAnalyze, BitSet, startOfMethod, uninit, UNINIT_THIS, isConstructor, getPreInstructionRegisterType (one path back, no fixpoint over the whole method)
// not ported: buildInstructionList, getClassPath, AnalyzedInstruction (the instructions are dexlib2's own)
class DexServers : JavaServerScan {
    // A method and its code, its parameters in the last registers.
    private class Analyzed(val method: Method, methodImpl: MethodImplementation) {
        val analyzedInstructions: List<Instruction> = methodImpl.instructions.toList()
        val totalRegisters = methodImpl.registerCount
        val parameterRegisters = MethodUtil.getParameterRegisterCount(method)
        val nonParameterRegisters = totalRegisters - parameterRegisters
    }

    private class Value(val literal: Long, val chain: List<String>)

    private lateinit var callers: Map<String, List<Pair<Analyzed, Int>>>
    private lateinit var methods: Map<String, List<Analyzed>>
    private val staticValues = HashMap<String, Long>()
    private val superclasses = HashMap<String, String>()
    private val interfaces = HashMap<String, Set<String>>()

    // Every dex of the APK, as dexlib2's ZipDexContainer lists them.
    override fun scan(apk: File): List<JavaServer> {
        val analyzed = mutableListOf<Analyzed>()
        val container = DexFileFactory.loadDexContainer(apk, null)
        for (entryName in container.dexEntryNames) {
            val dexFile = container.getEntry(entryName)?.dexFile ?: continue
            for (classDef in dexFile.classes) {
                classDef.superclass?.let { superclasses[classDef.type] = it }
                interfaces[classDef.type] = classDef.interfaces.toSet()
                for (field in classDef.staticFields) (field.initialValue as? IntEncodedValue)?.let { staticValues[fieldKey(field)] = it.value.toLong() }
                for (method in classDef.methods) method.implementation?.let { analyzed.add(Analyzed(method, it)) }
            }
        }
        val calls = HashMap<String, MutableList<Pair<Analyzed, Int>>>()
        for (a in analyzed) a.analyzedInstructions.forEachIndexed { instructionIndex, instruction ->
            invoked(instruction)?.let { calls.getOrPut(key(it)) { mutableListOf() }.add(a to instructionIndex) }
        }
        callers = calls
        methods = analyzed.groupBy { key(it.method) }

        val found = mutableListOf<JavaServer>()
        for (a in analyzed) a.analyzedInstructions.forEachIndexed { instructionIndex, instruction ->
            val reference = invoked(instruction) ?: return@forEachIndexed
            val datagram = sinks[reference.definingClass + "->" + key(reference)] ?: return@forEachIndexed
            val register = registers(instruction).getOrNull(1) ?: return@forEachIndexed
            val udp = datagram || touches(a, 3, HashSet())
            for (value in values(a, instructionIndex, register, 0, listOf(name(a.method)))) {
                // An app binds no port under 1024, Android keeps them for the system.
                if (value.literal in 1024..65535) found.add(JavaServer(value.literal, udp, value.chain))
            }
        }
        return found.groupBy { it.port }.map { (port, all) -> JavaServer(port, all.any { it.udp }, all.first().chain) }
    }

    // The values a register holds before an instruction, followed back.
    private fun values(a: Analyzed, instructionIndex: Int, register: Int, depth: Int, chain: List<String>): List<Value> {
        for (j in instructionIndex - 1 downTo 0) {
            val instruction = a.analyzedInstructions[j]
            if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != register) continue
            return when {
                instruction is NarrowLiteralInstruction -> listOf(Value(instruction.narrowLiteral.toLong(), chain + "const ${instruction.narrowLiteral}"))
                instruction.opcode in moves -> analyzeMove(a, j, instruction as TwoRegisterInstruction, depth, chain)
                instruction.opcode == Opcode.SGET -> {
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    staticValues[fieldKey(field)]?.let { listOf(Value(it, chain + "static ${fieldKey(field)}")) } ?: emptyList()
                }
                else -> emptyList()
            }
        }
        // A parameter: its value at each call, the invoke's registers laid out as the parameters are.
        if (register < a.nonParameterRegisters || depth >= MAX_DEPTH) return emptyList()
        val slot = register - a.nonParameterRegisters
        val called = callers[key(a.method)].orEmpty().filter { (caller, at) -> extendsFrom(a.method.definingClass, invoked(caller.analyzedInstructions[at])!!.definingClass) }
        return called.take(MAX_CALLERS).flatMap { (caller, at) ->
            val argument = registers(caller.analyzedInstructions[at]).getOrNull(slot) ?: return@flatMap emptyList()
            values(caller, at, argument, depth + 1, chain + name(caller.method))
        }
    }

    private fun analyzeMove(a: Analyzed, instructionIndex: Int, instruction: TwoRegisterInstruction, depth: Int, chain: List<String>): List<Value> =
        values(a, instructionIndex, instruction.registerB, depth, chain)

    // Whether the method, or one it calls, uses a datagram socket or channel.
    private fun touches(a: Analyzed, depth: Int, seen: MutableSet<String>): Boolean {
        if (depth < 0 || !seen.add(key(a.method))) return false
        for (instruction in a.analyzedInstructions) {
            val reference = (instruction as? ReferenceInstruction)?.reference
            val type = when (reference) {
                is MethodReference -> reference.definingClass
                is FieldReference -> reference.type
                else -> null
            }
            if (type in datagrams) return true
            if (reference is MethodReference && methods[key(reference)].orEmpty().filter { extendsFrom(it.method.definingClass, reference.definingClass) }.take(MAX_CALLERS).any { touches(it, depth - 1, seen) }) return true
        }
        return false
    }

    private fun invoked(instruction: Instruction): MethodReference? =
        if (instruction.opcode.referenceType == ReferenceType.METHOD) (instruction as ReferenceInstruction).reference as MethodReference else null

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> emptyList()
    }

    // A method by name and prototype, any class.
    private fun key(method: MethodReference): String = method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    // Whether a class is the type a call names, or extends or implements it:
    // extendsFrom over the superclass chain, each class's interfaces beside it.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/util/TypeProtoUtils.java#extendsFrom
    private fun extendsFrom(candidate: String, possibleSuper: String): Boolean {
        if (candidate == possibleSuper) return true
        for (superProto in sequenceOf(candidate) + getSuperclassChain(candidate)) {
            if (superProto == possibleSuper || possibleSuper in interfaces[superProto].orEmpty()) return true
        }
        return false
    }

    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/util/TypeProtoUtils.java#getSuperclassChain
    // not ported: iterator, hasNext, next, remove (a Kotlin sequence)
    private fun getSuperclassChain(typeProto: String): Sequence<String> = sequence {
        var type = getSuperclassAsTypeProto(typeProto)
        while (type != null) {
            yield(type)
            type = getSuperclassAsTypeProto(type)
        }
    }

    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/util/TypeProtoUtils.java#getSuperclassAsTypeProto
    // not ported: getClassPath, getTypeProto, UnresolvedClassException (the dex's own classes, none resolved outside)
    private fun getSuperclassAsTypeProto(type: String): String? = superclasses[type]

    private fun fieldKey(field: FieldReference): String = field.definingClass + "->" + field.name

    private fun name(method: MethodReference): String =
        method.definingClass.removePrefix("L").removeSuffix(";").replace('/', '.') + "." + method.name + "(" + method.parameterTypes.joinToString(", ") + ")"

    companion object {
        private const val MAX_DEPTH = 6
        private const val MAX_CALLERS = 50

        // The Java library's binds given a port alone, whether each is UDP.
        private val sinks = mapOf(
            "Ljava/net/InetSocketAddress;-><init>(I)V" to false,
            "Ljava/net/ServerSocket;-><init>(I)V" to false,
            "Ljava/net/ServerSocket;-><init>(II)V" to false,
            "Ljava/net/DatagramSocket;-><init>(I)V" to true,
        )
        private val datagrams = setOf("Ljava/nio/channels/DatagramChannel;", "Ljava/net/DatagramSocket;")
        private val moves = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
    }
}
