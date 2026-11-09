package io.gutapk.core.offline

import io.airlift.compress.lz4.Lz4Compressor
import io.gutapk.core.il2cpp.Hex
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// A store as a .NET for Android build writes it, two assemblies, the game's
// compressed with LZ4 and a runtime one plain, inside an ELF image whose
// payload section is followed by the section headers.
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/StoreReader_V2.cs#Prepare
// https://github.com/dotnet/android/blob/main/src/Microsoft.Android.Build.Tasks/Utilities/AssemblyStoreElfWriter.cs#Write
class AssemblyStoreTest {
    private val codecs = AircompressorCodecs()
    private val origin = "https://api.example.com"

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    // A .NET string literal is UTF-16, at an odd offset here as in a string heap.
    private val game = "\u0001".toByteArray() + ByteArray(40) { 7 } + "$origin/v1/scores".toByteArray(Charsets.UTF_16LE) + ByteArray(300) { 9 }
    private val runtime = "the runtime of http://www.example.org/x fallback".toByteArray()

    private fun payload(): ByteArray {
        val names = listOf("Game.dll", "System.Net.Http.dll")
        val compressed = ByteArray(Lz4Compressor().maxCompressedLength(game.size))
        val n = Lz4Compressor().compress(game, 0, game.size, compressed, 0, compressed.size)
        val blob0 = le(12).putInt(AssemblyCompression.Lz4Magic).putInt(0).putInt(game.size).array() + compressed.copyOf(n)
        val indexSize = names.size * 13
        val namesBytes = names.fold(ByteArray(0)) { a, s -> a + le(4).putInt(s.length).array() + s.toByteArray() }
        val dataStart = AssemblyStore.Header.NativeSize + indexSize + names.size * AssemblyStore.EntryDescriptor.NativeSize + namesBytes.size
        val header = le(20).putInt(AssemblyStore.ASSEMBLY_STORE_MAGIC).putInt(AssemblyStore.ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V3 or AssemblyStore.ASSEMBLY_STORE_ABI_AARCH64)
            .putInt(names.size).putInt(names.size).putInt(indexSize).array()
        val index = le(indexSize).apply { names.indices.forEach { putLong(it.toLong()).putInt(it).put(0) } }.array()
        val descriptors = le(2 * 28)
            .putInt(0).putInt(dataStart).putInt(blob0.size).putInt(0).putInt(0).putInt(0).putInt(0)
            .putInt(1).putInt(dataStart + blob0.size).putInt(runtime.size).putInt(0).putInt(0).putInt(0).putInt(0).array()
        return header + index + descriptors + namesBytes + blob0 + runtime
    }

    // WriteHeader and WriteSectionHeader for three sections: none, .shstrtab, payload.
    private fun elf(payload: ByteArray): ByteArray {
        val strings = "\u0000.shstrtab\u0000payload\u0000".toByteArray()
        val payloadOffset = 0x100
        val sectionHeadersOffset = (payloadOffset + payload.size + 7) / 8 * 8
        val out = ByteArray(sectionHeadersOffset + 3 * 64)
        val writer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        writer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        writer.putShort(16, 3).putShort(18, 183).putInt(20, 1).putLong(0x28, sectionHeadersOffset.toLong())
        writer.putShort(0x34, 64).putShort(0x36, 56).putShort(0x38, 0).putShort(0x3a, 64).putShort(0x3c, 3).putShort(0x3e, 1)
        System.arraycopy(strings, 0, out, 64, strings.size)
        System.arraycopy(payload, 0, out, payloadOffset, payload.size)
        fun WriteSectionHeader(i: Int, name: Int, type: Int, offset: Int, size: Int) {
            val at = sectionHeadersOffset + i * 64
            writer.putInt(at, name).putInt(at + 4, type).putLong(at + 24, offset.toLong()).putLong(at + 32, size.toLong())
        }
        WriteSectionHeader(1, 1, 3, 64, strings.size)
        WriteSectionHeader(2, 11, 1, payloadOffset, payload.size)
        return out
    }

    private fun apk(store: ByteArray) = Files.createTempFile("store", ".apk").also { path ->
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("lib/arm64-v8a/libassembly-store.so"))
            z.write(store)
            z.closeEntry()
        }
        Files.write(path, bytes.toByteArray())
    }

    @Test
    fun theStoreIsReadInItsElfImage() {
        val store = assertNotNull(AssemblyStore.read(elf(payload())))
        assertEquals(listOf("Game.dll", "System.Net.Http.dll"), store.names)
        assertEquals(0x100, store.elfOffset)
        assertTrue(store.assembly(0, codecs).contentEquals(game))
        assertTrue(store.assembly(1, codecs).contentEquals(runtime))
        assertEquals(null, AssemblyStore.read("not a store at all, only text".toByteArray()))
    }

    @Test
    fun aUtf16AddressInTheGamesAssemblyIsItsCode() {
        val path = apk(elf(payload()))
        try {
            // Without aircompressor the store is left out, never read as bytes.
            assertTrue(Endpoints.scan(path).isEmpty())
            val found = Endpoints.scan(path, codecs).associateBy { it.origin }
            val api = assertNotNull(found[origin])
            assertEquals("lib/arm64-v8a/libassembly-store.so!/Game.dll", api.occurrences.single().file)
            assertEquals(41L, api.occurrences.single().start)
            assertEquals(1, Endpoints.inCode(api))
            assertEquals(0, Endpoints.inCode(assertNotNull(found["http://www.example.org"])))
            val patch = Redirect.patches(path, listOf(api), mapOf(origin to 20001), codecs).single()
            assertEquals(Hex.format(origin.toByteArray(Charsets.UTF_16LE)), patch.old)
            assertEquals(Hex.format(Redirect.target(origin, port = 20001)!!.toByteArray(Charsets.UTF_16LE)), patch.new)
        } finally {
            Files.delete(path)
        }
    }

    @Test
    fun aChangedAssemblyIsCompressedAgainAndTheElfFollows() {
        val image = elf(payload())
        val store = assertNotNull(AssemblyStore.read(image))
        val target = assertNotNull(Redirect.target(origin, port = 20001))
        // Ones everywhere compress worse than the original: the data grows.
        val changed = game.copyOf().also { a ->
            val t = target.toByteArray(Charsets.UTF_16LE)
            System.arraycopy(t, 0, a, 41, t.size)
            for (k in 41 + 2 * 40 until a.size) a[k] = (k * 31 % 251).toByte()
        }
        val out = store.with(mapOf(0 to changed), codecs)
        assertTrue(out.size > image.size)
        val again = assertNotNull(AssemblyStore.read(out))
        assertTrue(again.assembly(0, codecs).contentEquals(changed))
        assertTrue(again.assembly(1, codecs).contentEquals(runtime))
        val reader = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sectionHeadersOffset = reader.getLong(0x28).toInt()
        assertEquals(0, (sectionHeadersOffset - (ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getLong(0x28).toInt())) % 16)
        // The payload section has the new size and still starts the store.
        val payloadSize = reader.getLong(sectionHeadersOffset + 2 * 64 + 32)
        assertEquals(again.descriptors[1].data_offset + again.descriptors[1].data_size, payloadSize.toInt())
    }
}
