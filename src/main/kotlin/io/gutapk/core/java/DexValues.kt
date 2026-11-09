package io.gutapk.core.java

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21lh
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction51l
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.nio.file.Path

// One number a Java game's code writes into a field, the values a player
// balances (a unit's health, a weapon's reload): the field put that stores it
// and the const that gives it, by dex, method and instruction; the object it
// belongs to as the game names it (UnitTypes.dagger › large-weapon › bullet);
// the field, the class declaring it, its type and the value (a Long, a Double
// or a Boolean). creation: set in a constructor or a static initializer,
// where a game gives its things their numbers.
data class JavaValue(
    val dex: String,
    val method: String,
    val index: Int,
    val constIndex: Int,
    val label: String,
    val field: String,
    val owner: String,
    val type: String,
    val value: Any,
    val creation: Boolean,
)

// DexValues as the rest of GutapK sees it, dexlib2 not being loaded in
// GutapK: tools.Libraries loads DexValues with the downloaded jars. writeTo
// writes a dex again with the new bits of the values given and returns how
// many it changed.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
interface JavaValueEdit {
    fun scan(apk: File): List<JavaValue>

    fun writeTo(dataStore: Path, input: ByteArray, changes: Map<JavaValue, Long>): Int
}

// The values read in every dex of the APK: each const whose register a field
// put reads, followed back along one path as MethodAnalyzer follows a
// register, refused when a branch, a switch case or a catch can enter between
// the two. An object is named where the game creates it (analyzeNewInstance):
// the field it is stored in, or the name its constructor is given, after the
// name of the object creating it. An edit adds the new const right before the
// put and the old one right after it, so another field the const fills keeps
// its value, in the method's MutableMethodImplementation, which moves the
// branches after them; DexPool writes the dex again.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeNewInstance
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/rewriter/MethodRewriter.java#rewrite
// not ported: analyzeConst, analyzeWideConst (a const read where the put's register is followed back, in analyze; its literal is the value, not a register type)
// not ported: addInstruction (MutableMethodImplementation's own, called in writeTo)
// not ported: setDestinationRegisterTypeAndPropagateChanges, RegisterType, getRegisterTypeForLiteral, LONG_LO_TYPE (a value is read, not a type)
// not ported: classPath, isUninitialized, UNINIT_REF, getUninitializedReference (the class is a name, its type never checked)
class DexValues : JavaValueEdit {
    // Where an object of a class is created: by the class of the method
    // creating it, under the name it gets; root when stored in a static field.
    private class Creation(val parent: String, val name: String, val root: Boolean)

    private val creations = HashMap<String, Creation>()
    private val labels = HashMap<String, String>()
    // A constructor's parameter names, by method key, where the dex keeps them.
    private val parameterNames = HashMap<String, List<String?>>()
    // A constructor's call to its super (or this) constructor: the callee's
    // key, and for each parameter its position among the callee's, when passed on.
    private val superCalls = HashMap<String, Pair<String, List<Int?>>>()

    override fun scan(apk: File): List<JavaValue> {
        val container = DexFileFactory.loadDexContainer(apk, null)
        val dexFiles = container.dexEntryNames.mapNotNull { entryName -> container.getEntry(entryName)?.dexFile?.let { entryName to it } }
        for ((_, dexFile) in dexFiles) for (classDef in dexFile.classes) for (method in classDef.methods) {
            method.implementation?.let { analyzeNewInstance(method.definingClass, it.instructions.toList()) }
            if (method.name == "<init>") {
                parameterNames[key(method)] = method.parameters.map { it.name }
                method.implementation?.let { propagateParameterTypes(method, it) }
            }
        }
        // A constructor without names (an anonymous class's) takes the names
        // of the constructor it passes its parameters on to, down the chain.
        repeat(MAX_DEPTH) {
            for ((methodKey, superCall) in superCalls) {
                val (calleeKey, positions) = superCall
                val names = parameterNames[methodKey] ?: continue
                val calleeNames = parameterNames[calleeKey] ?: continue
                parameterNames[methodKey] = names.mapIndexed { i, name -> name ?: positions.getOrNull(i)?.let { calleeNames.getOrNull(it) } }
            }
        }
        val found = mutableListOf<JavaValue>()
        for ((entryName, dexFile) in dexFiles) for (classDef in dexFile.classes) for (method in classDef.methods) {
            val implementation = method.implementation ?: continue
            found += analyze(entryName, method, implementation, label(method.definingClass, 0))
        }
        return found
    }

    // Each new-instance of a method's class, with the field it is put in
    // next, or else the first string its constructor is given; the first
    // creation of a class is kept.
    private fun analyzeNewInstance(parent: String, analyzedInstructions: List<Instruction>) {
        analyzedInstructions.forEachIndexed { index, instruction ->
            if (instruction.opcode != Opcode.NEW_INSTANCE) return@forEachIndexed
            val objectRegister = (instruction as OneRegisterInstruction).registerA
            val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
            if (type in creations) return@forEachIndexed
            var given: String? = null
            for (next in analyzedInstructions.subList(index + 1, minOf(analyzedInstructions.size, index + NEW_INSTANCE_REACH))) {
                if (next.opcode == Opcode.CONST_STRING || next.opcode == Opcode.CONST_STRING_JUMBO) {
                    if (given == null) given = ((next as ReferenceInstruction).reference as StringReference).string
                    continue
                }
                if (next.opcode == Opcode.SPUT_OBJECT || next.opcode == Opcode.IPUT_OBJECT) {
                    if ((next as OneRegisterInstruction).registerA != objectRegister) continue
                    val field = (next as ReferenceInstruction).reference as FieldReference
                    val root = next.opcode == Opcode.SPUT_OBJECT
                    // Put in a field of an object made by new-instance in this method, it is named
                    // under that object, as dagger.bullet is under dagger.
                    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeNewInstance
                    val holder = if (root) null else (next as TwoRegisterInstruction).registerB
                    val holderType = analyzedInstructions.subList(0, index).lastOrNull { it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == holder }
                        ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
                    creations[type] = Creation(if (root) field.definingClass else holderType ?: parent, if (root) simpleName(field.definingClass) + "." + field.name else field.name, root)
                    return@forEachIndexed
                }
                if (next.opcode.setsRegister() && (next as? OneRegisterInstruction)?.registerA == objectRegister) break
            }
            creations[type] = Creation(parent, given ?: simpleName(type), false)
        }
    }

    // Where a constructor's parameters go in its call to the super (or this)
    // constructor. The parameters sit in the last registers, this first, as
    // MethodAnalyzer lays them out from MethodUtil's register count; the call
    // is the first invoke-direct of an <init> on the this register.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#L165-L192
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/util/MethodUtil.java#L85-L100
    // not ported: analyze (its first lines only, the parameter registers; DexValues has an analyze of its own)
    // not ported: setPostRegisterTypeAndPropagateChanges, RegisterType, UNINIT_THIS, REFERENCE, classPath, startOfMethod (a register's place is read, not its type)
    // not ported: nonParameterRegisters, uninit, instructionsToAnalyze, undeodexedInstructions, analyzedState, successors, didSomething (the rest of analyze: no instruction is analyzed here)
    // not ported: isStatic (only constructors, never static), isConstructor (the caller keeps <init> alone)
    private fun propagateParameterTypes(method: Method, methodImpl: MethodImplementation) {
        val totalRegisters = methodImpl.registerCount
        val parameterRegisters = getParameterRegisterCount(method.parameterTypes, false)
        val thisRegister = totalRegisters - parameterRegisters
        // Each parameter's first register, and the registers a call passes each parameter of methodRef in.
        fun registersOf(parameterTypes: List<CharSequence>, firstRegister: (Int) -> Int): List<Int> {
            var regCount = 0
            return parameterTypes.map { paramType ->
                firstRegister(regCount).also { regCount += getParameterRegisterCount(listOf(paramType), true) }
            }
        }
        // Without debug names, a parameter stored in a field of this is named
        // by the field: this.speed = speed.
        val parameters = registersOf(method.parameterTypes) { thisRegister + 1 + it }
        val methodKey = key(method)
        val names = parameterNames[methodKey]?.toMutableList() ?: return
        // Until the register is written again: a constructor reuses its parameters' registers.
        val overwritten = HashSet<Int>()
        for (instruction in methodImpl.instructions) {
            if (instruction.opcode in iputs && (instruction as TwoRegisterInstruction).registerB == thisRegister && instruction.registerA !in overwritten) {
                val i = parameters.indexOf(instruction.registerA)
                if (i >= 0 && names[i] == null) names[i] = ((instruction as ReferenceInstruction).reference as FieldReference).name
            }
            if (instruction.opcode.setsRegister() && instruction is OneRegisterInstruction) {
                overwritten += instruction.registerA
                if (instruction.opcode.setsWideRegister()) overwritten += instruction.registerA + 1
            }
        }
        parameterNames[methodKey] = names
        val instruction = methodImpl.instructions.firstOrNull {
            (it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                ((it as ReferenceInstruction).reference as MethodReference).name == "<init>" &&
                analyzeInvokeDirect(it).firstOrNull() == thisRegister
        } ?: return
        val methodRef = (instruction as ReferenceInstruction).reference as MethodReference
        val registers = analyzeInvokeDirect(instruction)
        val passed = registersOf(methodRef.parameterTypes) { registers.getOrElse(1 + it) { -1 } }
        superCalls[methodKey] = key(methodRef) to parameters.map { register -> passed.indexOf(register).takeIf { it >= 0 } }
    }

    // The registers a method's parameters take, this one more when not static.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/util/MethodUtil.java#getParameterRegisterCount
    // not ported: getParameterRegisterCount (the overloads with a Method or a MethodReference: the types are given)
    private fun getParameterRegisterCount(parameterTypes: Collection<CharSequence>, isStatic: Boolean): Int {
        var regCount = 0
        for (paramType in parameterTypes) {
            val firstChar = paramType[0]
            regCount += if (firstChar == 'J' || firstChar == 'D') 2 else 1
        }
        if (!isStatic) regCount++
        return regCount
    }

    // The name of the objects of a class: its creator's name, then its own.
    private fun label(type: String, depth: Int): String = labels.getOrPut(type) {
        val creation = creations[type]
        when {
            creation == null -> simpleName(type)
            creation.root -> creation.name
            depth >= MAX_DEPTH || creation.parent == type -> creation.name
            else -> label(creation.parent, depth + 1) + " › " + creation.name
        }
    }

    // The consts of a method its field puts read, one value per put.
    private fun analyze(entryName: String, method: Method, methodImpl: MethodImplementation, label: String): List<JavaValue> {
        val analyzedInstructions = methodImpl.instructions.toList()
        val targets = branchTargets(methodImpl, analyzedInstructions)
        val methodKey = key(method)
        val creation = method.name == "<init>" || method.name == "<clinit>"
        val found = mutableListOf<JavaValue>()
        // The value a register holds before an instruction, when a const gave it.
        // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeMove
        // not ported: analyzeMove (a move is not followed: the const writes the register the put or the call reads)
        fun add(analyzedInstruction: Int, sourceRegister: Int, type: String, owner: String, name: String, of: String) {
            val (constIndex, bits) = getPreInstructionRegisterType(analyzedInstructions, targets, analyzedInstruction, sourceRegister, type == "J" || type == "D") ?: return
            val value: Any = when (type) {
                "F" -> java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                "D" -> java.lang.Double.longBitsToDouble(bits)
                "Z" -> bits != 0L
                else -> bits
            }
            found += JavaValue(entryName, methodKey, analyzedInstruction, constIndex, of, name, owner, type, value, creation)
        }
        analyzedInstructions.forEachIndexed { index, instruction ->
            if (instruction.opcode in puts) {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                // An instance put on an object new-instance made: that object's label, as a constructor call's.
                // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeInvokeDirectCommon
                // not ported: isInvokeInit, getSetRegisters, setPostRegisterTypeAndPropagateChanges, UNINIT_REF, UNINIT_THIS, initRef (the object is a name, nothing propagates)
                val objectRegister = (instruction as? TwoRegisterInstruction)?.registerB
                val uninitRef = objectRegister?.let { register ->
                    (index - 1 downTo 0).map { analyzedInstructions[it] }
                        .firstOrNull { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register }
                        ?.takeIf { it.opcode == Opcode.NEW_INSTANCE }
                }
                val of = uninitRef?.let { label(((it as ReferenceInstruction).reference as TypeReference).type, 0) } ?: label
                if (field.type in PRIMITIVES) add(index, (instruction as OneRegisterInstruction).registerA, field.type, simpleName(field.definingClass), field.name, of)
            }
            // A constructor's numbers given as arguments: new BasicBulletType(2.5f, 9).
            if (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) {
                val methodReference = (instruction as ReferenceInstruction).reference as MethodReference
                if (methodReference.name == "<init>") analyzeInvokeDirectCommon(analyzedInstructions, index, methodReference, label, ::add)
            }
        }
        return found
    }

    // A constructor call's literal arguments, each named by the constructor's
    // parameter, of the object new-instance gave the object register, or of
    // the method's own when it is this (a super call).
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeInvokeDirectCommon
    // not ported: isInvokeInit, getSetRegisters, setPostRegisterTypeAndPropagateChanges, UNINIT_REF, UNINIT_THIS (the object's type is a name, nothing propagates)
    private fun analyzeInvokeDirectCommon(
        analyzedInstructions: List<Instruction>,
        analyzedInstruction: Int,
        methodReference: MethodReference,
        label: String,
        add: (Int, Int, String, String, String, String) -> Unit,
    ) {
        val registers = analyzeInvokeDirect(analyzedInstructions[analyzedInstruction])
        val objectRegister = registers.firstOrNull() ?: return
        val uninitRef = (analyzedInstruction - 1 downTo 0).map { analyzedInstructions[it] }
            .firstOrNull { it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == objectRegister }
        val initRef = uninitRef?.let { label(((it as ReferenceInstruction).reference as TypeReference).type, 0) } ?: label
        val names = parameterNames[key(methodReference)]
        var register = 1
        methodReference.parameterTypes.forEachIndexed { i, parameterType ->
            val registerType = parameterType.toString()
            val sourceRegister = registers.getOrNull(register) ?: return
            register += if (registerType == "J" || registerType == "D") 2 else 1
            if (registerType in PRIMITIVES) add(analyzedInstruction, sourceRegister, registerType, simpleName(methodReference.definingClass), names?.getOrNull(i) ?: "#${i + 1}", initRef)
        }
    }

    // The registers of a call, the object first: from registerC, or from startRegister.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeInvokeDirect
    // not ported: analyzeInvokeDirectRange (the range form is read here too, analyzeInvokeDirect takes both)
    private fun analyzeInvokeDirect(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> emptyList()
    }

    // The const that last wrote register before an instruction, by index and
    // bits, when nothing can jump between them and its width is the one wanted.
    // https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/analysis/MethodAnalyzer.java#analyzeMove
    // not ported: analyzeMove (a move is not followed: the const writes the register the put or the call reads)
    private fun getPreInstructionRegisterType(analyzedInstructions: List<Instruction>, targets: Set<Int>, analyzedInstruction: Int, sourceRegister: Int, wide: Boolean): Pair<Int, Long>? {
        for (j in analyzedInstruction - 1 downTo 0) {
            if (j + 1 in targets) return null
            val instruction = analyzedInstructions[j]
            if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != sourceRegister) continue
            if (instruction !is NarrowLiteralInstruction && instruction !is WideLiteralInstruction) return null
            // A narrow literal is a wide one too in dexlib2: the opcode tells.
            if (instruction.opcode.setsWideRegister() != wide) return null
            return j to if (wide) (instruction as WideLiteralInstruction).wideLiteral else (instruction as NarrowLiteralInstruction).narrowLiteral.toLong()
        }
        return null
    }

    // The instructions a jump, a switch case or a catch lands on, by index.
    private fun branchTargets(methodImpl: MethodImplementation, analyzedInstructions: List<Instruction>): Set<Int> {
        val codeAddresses = IntArray(analyzedInstructions.size)
        var codeAddress = 0
        analyzedInstructions.forEachIndexed { index, instruction ->
            codeAddresses[index] = codeAddress
            codeAddress += instruction.codeUnits
        }
        val byAddress = codeAddresses.withIndex().associate { (index, address) -> address to index }
        val addresses = HashSet<Int>()
        analyzedInstructions.forEachIndexed { index, instruction ->
            if (instruction !is OffsetInstruction) return@forEachIndexed
            val target = codeAddresses[index] + instruction.codeOffset
            val payload = byAddress[target]?.let { analyzedInstructions[it] }
            if (payload is SwitchPayload) payload.switchElements.forEach { addresses.add(codeAddresses[index] + it.offset) } else addresses.add(target)
        }
        methodImpl.tryBlocks.forEach { tryBlock -> tryBlock.exceptionHandlers.forEach { addresses.add(it.handlerCodeAddress) } }
        return addresses.mapNotNull { byAddress[it] }.toSet()
    }

    override fun writeTo(dataStore: Path, input: ByteArray, changes: Map<JavaValue, Long>): Int {
        var changed = 0
        val byMethod = changes.entries.groupBy { it.key.method }
        val module = object : RewriterModule() {
            override fun getMethodRewriter(rewriters: Rewriters): Rewriter<Method> = object : MethodRewriter(rewriters) {
                override fun rewrite(value: Method): Method {
                    val replacements = byMethod[key(value)] ?: return super.rewrite(value)
                    val methodImplementation = MutableMethodImplementation(value.implementation!!)
                    // Last put first: the indices before it stay where they are.
                    // The arguments of one call share its index: all their new
                    // consts go before it, all the old ones after it. Two
                    // arguments one register gives cannot differ: neither is written.
                    for ((index, atPut) in replacements.groupBy { it.key.index }.toSortedMap(compareByDescending { it })) {
                        // register to (wide, old bits, new bits), for each value at this put.
                        val byRegister = atPut.map { (javaValue, bits) ->
                            val literal = methodImplementation.instructions[javaValue.constIndex]
                            val register = (literal as OneRegisterInstruction).registerA
                            val wide = literal.opcode.setsWideRegister()
                            val old = if (wide) (literal as WideLiteralInstruction).wideLiteral else (literal as NarrowLiteralInstruction).narrowLiteral.toLong()
                            register to Triple(wide, old, bits)
                        }.groupBy({ it.first }, { it.second })
                        val kept = byRegister.filterValues { same -> same.map { it.third }.distinct().size == 1 }
                        for ((register, same) in kept) methodImplementation.addInstruction(index + 1, literal(register, same[0].first, same[0].second))
                        for ((register, same) in kept) methodImplementation.addInstruction(index, literal(register, same[0].first, same[0].third))
                        changed += kept.values.sumOf { it.size }
                    }
                    return ImmutableMethod(value.definingClass, value.name, value.parameters, value.returnType, value.accessFlags, value.annotations, value.hiddenApiRestrictions, methodImplementation)
                }
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

    // The smallest const that gives register the bits, as dx and d8 pick one.
    private fun literal(register: Int, wide: Boolean, bits: Long): BuilderInstruction {
        if (wide) {
            return when {
                bits in Short.MIN_VALUE..Short.MAX_VALUE -> BuilderInstruction21s(Opcode.CONST_WIDE_16, register, bits.toInt())
                bits in Int.MIN_VALUE..Int.MAX_VALUE -> BuilderInstruction31i(Opcode.CONST_WIDE_32, register, bits.toInt())
                bits and 0xFFFFFFFFFFFFL == 0L -> BuilderInstruction21lh(Opcode.CONST_WIDE_HIGH16, register, bits)
                else -> BuilderInstruction51l(Opcode.CONST_WIDE, register, bits)
            }
        }
        val literal = bits.toInt()
        return when {
            literal in -8..7 && register < 16 -> BuilderInstruction11n(Opcode.CONST_4, register, literal)
            literal in Short.MIN_VALUE..Short.MAX_VALUE -> BuilderInstruction21s(Opcode.CONST_16, register, literal)
            literal and 0xFFFF == 0 -> BuilderInstruction21ih(Opcode.CONST_HIGH16, register, literal)
            else -> BuilderInstruction31i(Opcode.CONST, register, literal)
        }
    }

    // A method by class, name and prototype.
    private fun key(method: MethodReference): String =
        method.definingClass + "->" + method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    private fun simpleName(type: String): String = type.removePrefix("L").removeSuffix(";").substringAfterLast('/')

    companion object {
        // Far enough for a constructor's arguments between new-instance and the put.
        private const val NEW_INSTANCE_REACH = 24
        private const val MAX_DEPTH = 8
        private val PRIMITIVES = setOf("I", "F", "J", "D", "Z", "S", "B")
        private val iputs = setOf(
            Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
        )
        private val puts = setOf(
            Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_SHORT,
            Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_BOOLEAN, Opcode.SPUT_BYTE, Opcode.SPUT_SHORT,
        )
    }
}
