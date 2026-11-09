package io.gutapk.core.java

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21ih
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

// A game that creates a unit in a static field, gives it its health, and
// gives its bullet a speed and a damage through an anonymous subclass's
// constructor, without debug names, the way Mindustry's UnitTypes does: the
// values are found under the unit's name, the arguments named by the fields
// the base constructor stores them in, and two arguments of one call are
// written together. Built with dexlib2's immutable classes the way its own
// writer tests build theirs.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
class DexValuesTest {
    private val units = "Lgame/Units;"
    private val unit = "Lgame/Unit;"
    private val bullet = "Lgame/Bullet;"
    private val anonymous = "Lgame/Units\$1;"
    private val kind = "Lgame/Kind;"

    private fun method(type: String, name: String, parameters: List<String>, flags: Int, registers: Int, instructions: List<Instruction>) =
        ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
            ImmutableMethodImplementation(registers, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
        )

    private fun classDef(type: String, superclass: String, vararg methods: ImmutableMethod) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList())

    private val constructor = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value

    private fun float(register: Int, value: Float) =
        ImmutableInstruction21ih(Opcode.CONST_HIGH16, register, java.lang.Float.floatToRawIntBits(value))

    private fun dex(): ByteArray {
        val init = ImmutableMethodReference(bullet, "<init>", listOf("F", "F"), "V")
        val dexPool = DexPool(Opcodes.forApi(21))
        // Bullet(float speed, float damage) { this.speed = speed; this.damage = damage; }: v0 this, v1 v2 the parameters.
        dexPool.internClass(
            classDef(
                bullet, "Ljava/lang/Object;",
                method(
                    bullet, "<init>", listOf("F", "F"), constructor, 3,
                    listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference("Ljava/lang/Object;", "<init>", null, "V")),
                        ImmutableInstruction22c(Opcode.IPUT, 1, 0, ImmutableFieldReference(bullet, "speed", "F")),
                        ImmutableInstruction22c(Opcode.IPUT, 2, 0, ImmutableFieldReference(bullet, "damage", "F")),
                    ),
                ),
            ),
        )
        // new Bullet(speed, damage) {{ }}: passes its parameters on.
        dexPool.internClass(
            classDef(
                anonymous, bullet,
                method(anonymous, "<init>", listOf("F", "F"), constructor, 3, listOf(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 1, 2, 0, 0, init))),
            ),
        )
        dexPool.internClass(
            classDef(
                unit, "Ljava/lang/Object;",
                method(unit, "<init>", emptyList(), constructor, 1, listOf(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference("Ljava/lang/Object;", "<init>", null, "V")))),
            ),
        )
        // dagger = new Unit(); dagger.health = 150; dagger.bullet = new Bullet(2.5f, 9f) {{ }};
        dexPool.internClass(
            classDef(
                units, "Ljava/lang/Object;",
                method(
                    units, "<clinit>", emptyList(), AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 4,
                    listOf(
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(unit)),
                        ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(unit, "<init>", null, "V")),
                        float(1, 150f),
                        ImmutableInstruction22c(Opcode.IPUT, 1, 0, ImmutableFieldReference(unit, "health", "F")),
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(anonymous)),
                        float(2, 2.5f),
                        float(3, 9f),
                        ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 1, 2, 3, 0, 0, ImmutableMethodReference(anonymous, "<init>", listOf("F", "F"), "V")),
                        ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, ImmutableFieldReference(unit, "bullet", bullet)),
                        ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, ImmutableFieldReference(units, "dagger", unit)),
                    ),
                ),
            ),
        )
        // enum Kind { FAST }: the constant's ordinal 0 goes to Enum's constructor, it is no value.
        // https://github.com/JesusFreke/smali/blob/master/examples/Enums/Enum.smali#L51-L55
        val enumInit = ImmutableMethodReference(kind, "<init>", listOf("Ljava/lang/String;", "I"), "V")
        dexPool.internClass(
            classDef(
                kind, "Ljava/lang/Enum;",
                method(
                    kind, "<init>", listOf("Ljava/lang/String;", "I"), constructor, 3,
                    listOf(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 1, 2, 0, 0, ImmutableMethodReference("Ljava/lang/Enum;", "<init>", listOf("Ljava/lang/String;", "I"), "V"))),
                ),
                method(
                    kind, "<clinit>", emptyList(), AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 3,
                    listOf(
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(kind)),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("FAST")),
                        ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                        ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 1, 2, 0, 0, enumInit),
                        ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, ImmutableFieldReference(kind, "FAST", kind)),
                    ),
                ),
            ),
        )
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        return dataStore.data
    }

    private fun apk(dex: ByteArray): File {
        val file = Files.createTempFile("gutapk", ".apk").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex)
            zip.closeEntry()
        }
        return file
    }

    private fun shown(values: List<JavaValue>) = values.filter { it.creation }.map { Triple(it.label, it.field, it.value) }.toSet()

    @Test
    fun findsAndWritesTheValuesAGameCreatesItsThingsWith() {
        val bytes = dex()
        val file = apk(bytes)
        val written = Files.createTempFile("gutapk", ".dex")
        try {
            val values = DexValues().scan(file)
            assertEquals(
                setOf(Triple("Units.dagger", "health", 150.0), Triple("Units.dagger › bullet", "speed", 2.5), Triple("Units.dagger › bullet", "damage", 9.0)),
                shown(values),
            )

            fun bits(field: String, value: Float) = values.single { it.creation && it.field == field } to java.lang.Float.floatToRawIntBits(value).toLong()
            val changed = DexValues().writeTo(written, bytes, mapOf(bits("health", 9999f), bits("speed", 3f), bits("damage", 12f)))
            assertEquals(3, changed)
            val again = apk(Files.readAllBytes(written))
            try {
                assertEquals(
                    setOf(Triple("Units.dagger", "health", 9999.0), Triple("Units.dagger › bullet", "speed", 3.0), Triple("Units.dagger › bullet", "damage", 12.0)),
                    shown(DexValues().scan(again)),
                )
            } finally {
                again.delete()
            }
        } finally {
            file.delete()
            Files.deleteIfExists(written)
        }
    }
}
