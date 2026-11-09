package io.gutapk.core.local

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

// A game whose server binds a port its menu passes down through an
// interface, beside a class with a method of the same name and prototype on
// an unrelated type: only the game's port is found, with UDP since its
// server opens a datagram channel too. Built with dexlib2's immutable
// classes the way its own writer tests build theirs.
// https://github.com/google/smali/blob/3.0.9/dexlib2/src/main/java/com/android/tools/smali/dexlib2/writer/pool/DexPool.java#writeTo
class DexServersTest {
    private val provider = "Lgame/Provider;"
    private val net = "Lgame/Net;"
    private val host = ImmutableMethodReference(provider, "host", listOf("I"), "V")

    private fun method(type: String, name: String, parameters: List<String>, static: Boolean, registers: Int, instructions: List<Instruction>) =
        ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null),
        )

    // A call to host(I) on owner, from new-instance of type, with port.
    private fun caller(type: String, owner: String, port: Int) = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            method(
                type, "start", emptyList(), true, 2,
                listOf(
                    ImmutableInstruction21s(Opcode.CONST_16, 0, port),
                    ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(net)),
                    ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 0, 0, 0, 0, ImmutableMethodReference(owner, "host", listOf("I"), "V")),
                ),
            ),
        ),
    )

    private fun apk(): java.io.File {
        // Net.host(int port): new ServerSocket(port), DatagramChannel.open(). v0, this in v1, port in v2.
        val server = method(
            net, "host", listOf("I"), false, 3,
            listOf(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Ljava/net/ServerSocket;")),
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 2, 0, 2, 0, 0, 0, ImmutableMethodReference("Ljava/net/ServerSocket;", "<init>", listOf("I"), "V")),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ImmutableMethodReference("Ljava/nio/channels/DatagramChannel;", "open", null, "Ljava/nio/channels/DatagramChannel;")),
            ),
        )
        val dexPool = DexPool(Opcodes.forApi(21))
        dexPool.internClass(ImmutableClassDef(provider, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod(provider, host.name, listOf(ImmutableMethodParameter("I", null, null)), "V", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null))))
        dexPool.internClass(ImmutableClassDef(net, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(provider), null, null, null, listOf(server)))
        dexPool.internClass(caller("Lgame/Menu;", provider, 7777))
        // The same name and prototype on a type Net is not: never a caller of Net.host.
        dexPool.internClass(caller("Lgame/Bloom;", "Lgame/Shader;", 9999))
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        val file = Files.createTempFile("gutapk", ".apk").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dataStore.data)
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun findsThePortTheMenuPassesDown() {
        val file = apk()
        try {
            val servers = DexServers().scan(file)
            assertEquals(listOf(7777L), servers.map { it.port })
            assertEquals(true, servers.single().udp)
            assertEquals(listOf("game.Net.host(I)", "game.Menu.start()", "const 7777"), servers.single().chain)
        } finally {
            file.delete()
        }
    }
}
