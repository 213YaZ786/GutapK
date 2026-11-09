package io.gutapk.core.offline

import java.nio.ByteBuffer
import java.nio.ByteOrder

// The assembly store of a .NET for Android app: every assembly of the game in
// one shared library, each compressed alone. StoreReader_V2 reads it: the ELF
// payload, the header, the index, the descriptors and the names. An entry's
// data_offset counts from the start of the store.
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/StoreReader_V2.cs#Prepare
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/StoreReader_V2.Classes.cs
// not ported: AddArchPaths, GetArchPath, ApkPaths, AabPaths, AabBasePaths (isStore matches the entry names of an APK)
// not ported: TargetArch, Is64Bit (the entry's lib/<abi>/ directory names the ABI, a descriptor has the same size on both)
// not ported: IndexEntry, StoreItem_V2, TemporaryItem, IndexToHashes (the index stays as it is, it points at descriptors, which keep their order)
// not ported: Log.Debug (a store that does not read is left alone, as a file with no address)
class AssemblyStore private constructor(
    val bytes: ByteArray,
    private val elf: Elf?,
    val elfOffset: Int,
    val header: Header,
    val descriptors: List<EntryDescriptor>,
    val names: List<String>,
    private val descriptorsOffset: Int,
) {
    class Header(val magic: Int, val version: Int, val entry_count: Int, val index_entry_count: Int, val index_size: Int) {
        companion object {
            const val NativeSize = 5 * 4
        }
    }

    data class EntryDescriptor(
        val mapping_index: Int,
        val data_offset: Int,
        val data_size: Int,
        val debug_data_offset: Int,
        val debug_data_size: Int,
        val config_data_offset: Int,
        val config_data_size: Int,
    ) {
        companion object {
            const val NativeSize = 7 * 4
        }
    }

    // The assembly of descriptor i whole, decompressed when it is.
    fun assembly(i: Int, codecs: AssemblyCodecs): ByteArray {
        val desc = descriptors[i]
        val start = elfOffset + desc.data_offset
        return AssemblyCompression.TryDecompress(bytes, start, desc.data_size, codecs) ?: bytes.copyOfRange(start, start + desc.data_size)
    }

    // The library again with some assemblies changed, each of its old length,
    // compressed again as it was. A compressed one changes size: the data
    // after it moves, padded so it keeps its alignment up to 16, its
    // descriptor follows, and so do the ELF's payload size and the section
    // headers after the payload.
    fun with(changed: Map<Int, ByteArray>, codecs: AssemblyCodecs): ByteArray {
        val data = changed.mapValues { (i, assemblyBytes) ->
            val desc = descriptors[i]
            val start = elfOffset + desc.data_offset
            if (AssemblyCompression.TryReadFormat(bytes, start, desc.data_size) != null) {
                AssemblyCompression.compress(bytes, start, desc.data_size, assemblyBytes, codecs)
            } else {
                if (assemblyBytes.size != desc.data_size) throw IllegalArgumentException("${names[i]} changed length")
                assemblyBytes
            }
        }
        // Every stretch of the payload is moved by what the data before it grew.
        val grown = sortedMapOf<Int, Int>()
        for ((i, newData) in data) {
            val desc = descriptors[i]
            val growth = newData.size - desc.data_size
            if (growth > 0) grown[desc.data_offset + desc.data_size] = (growth + 15) / 16 * 16
        }
        fun moved(offset: Int): Int = offset + grown.headMap(offset + 1).values.sum()
        val payloadSize = elf?.payloadSize?.toInt() ?: (bytes.size - elfOffset)
        val newPayload = ByteArray(moved(payloadSize))
        // Header, index, descriptors and names keep their place: no data is before them.
        val front = descriptors.filter { it.data_size > 0 }.minOfOrNull { it.data_offset } ?: payloadSize
        var from = 0
        for (end in grown.keys) {
            System.arraycopy(bytes, elfOffset + from, newPayload, moved(from), end - from)
            from = end
        }
        System.arraycopy(bytes, elfOffset + from, newPayload, moved(from), payloadSize - from)
        val writer = ByteBuffer.wrap(newPayload).order(ByteOrder.LITTLE_ENDIAN)
        descriptors.forEachIndexed { i, desc ->
            val at = descriptorsOffset - elfOffset + i * EntryDescriptor.NativeSize
            if (at + EntryDescriptor.NativeSize > front) throw IllegalArgumentException("the descriptors overlap the data")
            val newData = data[i]
            if (desc.data_size > 0) writer.putInt(at + 4, moved(desc.data_offset))
            if (newData != null) {
                System.arraycopy(newData, 0, newPayload, moved(desc.data_offset), newData.size)
                writer.putInt(at + 8, newData.size)
            }
            if (desc.debug_data_size > 0) writer.putInt(at + 12, moved(desc.debug_data_offset))
            if (desc.config_data_size > 0) writer.putInt(at + 20, moved(desc.config_data_offset))
        }
        // A store that is not inside an ELF is the payload itself.
        return elf?.resized(bytes, newPayload) ?: (bytes.copyOfRange(0, elfOffset) + newPayload)
    }

    companion object {
        // Bit 31 is set for 64-bit platforms, cleared for the 32-bit ones
        const val ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V2 = 0x80000002.toInt()
        const val ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V2 = 0x00000002
        const val ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V3 = 0x80000003.toInt()
        const val ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V3 = 0x00000003

        const val ASSEMBLY_STORE_ABI_AARCH64 = 0x00010000
        const val ASSEMBLY_STORE_ABI_ARM = 0x00020000
        const val ASSEMBLY_STORE_ABI_X64 = 0x00030000
        const val ASSEMBLY_STORE_ABI_X86 = 0x00040000

        const val ASSEMBLY_STORE_MAGIC = 0x41424158
        const val ELF_MAGIC = 0x464c457f

        private val supportedVersions = setOf(
            ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V2 or ASSEMBLY_STORE_ABI_AARCH64,
            ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V2 or ASSEMBLY_STORE_ABI_X64,
            ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V2 or ASSEMBLY_STORE_ABI_ARM,
            ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V2 or ASSEMBLY_STORE_ABI_X86,
            ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V3 or ASSEMBLY_STORE_ABI_AARCH64,
            ASSEMBLY_STORE_FORMAT_VERSION_64BIT_V3 or ASSEMBLY_STORE_ABI_X64,
            ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V3 or ASSEMBLY_STORE_ABI_ARM,
            ASSEMBLY_STORE_FORMAT_VERSION_32BIT_V3 or ASSEMBLY_STORE_ABI_X86,
        )

        // The two names StoreReader_V2 looks for under each lib/<abi>/.
        private val storeNames = Regex("lib/([^/]+)/(libassembly-store\\.so|libassemblies\\.\\1\\.blob\\.so)")

        // Between a store's entry name and an assembly's in an Occurrence.
        const val SEPARATOR = "!/"

        fun isStore(name: String): Boolean = storeNames.matches(name)

        // IsSupported then Prepare: the store, or null when the bytes hold none.
        fun read(bytes: ByteArray): AssemblyStore? {
            val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < Header.NativeSize) return null
            var magic = reader.getInt(0)
            var elf: Elf? = null
            var elfOffset = 0
            if (magic == ELF_MAGIC) {
                elf = Elf.FindELFPayloadOffsetAndSize(bytes) ?: return null
                elfOffset = elf.payloadOffset.toInt()
                if (elfOffset + Header.NativeSize > bytes.size) return null
                magic = reader.getInt(elfOffset)
            }
            if (magic != ASSEMBLY_STORE_MAGIC) return null
            val version = reader.getInt(elfOffset + 4)
            if (version !in supportedVersions) return null
            val header = Header(magic, version, reader.getInt(elfOffset + 8), reader.getInt(elfOffset + 12), reader.getInt(elfOffset + 16))
            if (header.entry_count < 0 || header.index_size < 0) return null

            // The index is skipped: descriptors follow it, then the names.
            val descriptorsOffset = elfOffset + Header.NativeSize + header.index_size
            reader.position(descriptorsOffset)
            val descriptors = List(header.entry_count) {
                EntryDescriptor(reader.int, reader.int, reader.int, reader.int, reader.int, reader.int, reader.int)
            }
            val names = List(header.entry_count) {
                val name_length = reader.int
                val name_bytes = ByteArray(name_length)
                reader.get(name_bytes)
                String(name_bytes, Charsets.UTF_8)
            }
            for (desc in descriptors) {
                if (desc.data_size > 0 && (desc.data_offset < 0 || elfOffset.toLong() + desc.data_offset + desc.data_size > bytes.size)) return null
            }
            return AssemblyStore(bytes, elf, elfOffset, header, descriptors, names, descriptorsOffset)
        }
    }
}

// The few fields of an ELF image the store needs: where the payload is, by the
// _assembly_store symbol or else the payload section, and what to change when
// it grows. The fields are read where AssemblyStoreElfWriter writes them.
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/Utils.cs#FindELFPayloadOffsetAndSize
// https://github.com/dotnet/android/blob/main/src/Microsoft.Android.Build.Tasks/Utilities/AssemblyStoreElfWriter.cs#WriteSectionHeader
// not ported: ReturnError, ELFPayloadError (null says the payload was not found)
// not ported: Write, Layout, WriteHeader, WriteDynamicEntry (the image is kept, only its sizes and offsets move)
// not ported: WriteProgramHeader, WriteSectionHeader, WriteSymbol (read here at the places they write, resized writes back only the offsets and sizes)
class Elf private constructor(
    private val Is64Bit: Boolean,
    private val sectionHeadersOffset: Long,
    private val sections: List<Section>,
    private val programHeaders: List<ProgramHeader>,
    private val symbolAt: Long?,
    val payloadOffset: Long,
    val payloadSize: Long,
) {
    // Where each header sits in the file, so it can be written again.
    class Section(val at: Long, val name: Int, val type: Int, val flags: Long, val address: Long, val offset: Long, val size: Long, val link: Int)

    class ProgramHeader(val at: Long, val type: Int, val offset: Long, val fileSize: Long)

    private val WordSize get() = if (Is64Bit) 8 else 4

    // The image with the payload replaced by a bigger one: what follows the
    // payload in the file moves by the growth, rounded to keep its alignment,
    // the payload's section, its symbol and the segment holding it get the
    // new size.
    fun resized(image: ByteArray, payload: ByteArray): ByteArray {
        if (payload.size < payloadSize) throw IllegalArgumentException("the payload cannot shrink")
        val end = payloadOffset + payloadSize
        val growth = (payload.size - payloadSize + 15) / 16 * 16
        val out = ByteArray((image.size + growth).toInt())
        System.arraycopy(image, 0, out, 0, payloadOffset.toInt())
        System.arraycopy(payload, 0, out, payloadOffset.toInt(), payload.size)
        System.arraycopy(image, end.toInt(), out, (end + growth).toInt(), (image.size - end).toInt())
        val writer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val newSize = payload.size.toLong()
        fun WriteWord(at: Long, value: Long) {
            if (Is64Bit) writer.putLong(at.toInt(), value) else writer.putInt(at.toInt(), value.toInt())
        }
        fun moved(at: Long) = if (at >= end) at + growth else at
        // e_shoff
        if (sectionHeadersOffset >= end) WriteWord(if (Is64Bit) 0x28L else 0x20L, sectionHeadersOffset + growth)
        for (section in sections) {
            // SHT_NOBITS has no bytes in the file.
            if (section.type == 8) continue
            val offsetAt = moved(section.at) + 8 + 2L * WordSize
            if (section.offset >= end) WriteWord(offsetAt, section.offset + growth)
            if (section.offset <= payloadOffset && end <= section.offset + section.size) {
                WriteWord(offsetAt + WordSize, section.size + newSize - payloadSize)
            }
        }
        for (header in programHeaders) {
            // PT_LOAD
            if (header.type == 1 && header.offset <= payloadOffset && end <= header.offset + header.fileSize) {
                val fileSizeAt = moved(header.at) + (if (Is64Bit) 8 else 4) + 3L * WordSize
                WriteWord(fileSizeAt, header.fileSize + newSize - payloadSize)
                WriteWord(fileSizeAt + WordSize, header.fileSize + newSize - payloadSize)
            }
        }
        symbolAt?.let { at -> WriteWord(moved(at) + (if (Is64Bit) 16 else 8), newSize) }
        return out
    }

    companion object {
        const val AssemblyStorePayloadSymbol = "_assembly_store"

        fun FindELFPayloadOffsetAndSize(image: ByteArray): Elf? {
            val reader = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
            if (image.size < 52 || reader.getInt(0) != AssemblyStore.ELF_MAGIC) return null
            val Is64Bit = image[4].toInt() == 2
            // Not little-endian, or not a shared library.
            if (image[5].toInt() != 1 || reader.getShort(16).toInt() != 3) return null
            fun word(at: Long): Long = if (Is64Bit) reader.getLong(at.toInt()) else reader.getInt(at.toInt()).toLong() and 0xffffffffL
            val wordSize = if (Is64Bit) 8 else 4
            val programHeadersOffset = word(if (Is64Bit) 0x20L else 0x1cL)
            val sectionHeadersOffset = word(if (Is64Bit) 0x28L else 0x20L)
            val headerFieldsAt = if (Is64Bit) 0x36 else 0x2a
            val programHeaderSize = reader.getShort(headerFieldsAt).toInt() and 0xffff
            val programHeaderCount = reader.getShort(headerFieldsAt + 2).toInt() and 0xffff
            val sectionHeaderSize = reader.getShort(headerFieldsAt + 4).toInt() and 0xffff
            val sectionCount = reader.getShort(headerFieldsAt + 6).toInt() and 0xffff
            val sectionNamesIndex = reader.getShort(headerFieldsAt + 8).toInt() and 0xffff
            if (sectionHeadersOffset + sectionCount.toLong() * sectionHeaderSize > image.size) return null
            if (programHeadersOffset + programHeaderCount.toLong() * programHeaderSize > image.size) return null
            val sections = List(sectionCount) { i ->
                val at = sectionHeadersOffset + i.toLong() * sectionHeaderSize
                Section(
                    at = at,
                    name = reader.getInt(at.toInt()),
                    type = reader.getInt(at.toInt() + 4),
                    flags = word(at + 8),
                    address = word(at + 8 + wordSize),
                    offset = word(at + 8 + 2L * wordSize),
                    size = word(at + 8 + 3L * wordSize),
                    link = reader.getInt((at + 8 + 4L * wordSize).toInt()),
                )
            }
            val programHeaders = List(programHeaderCount) { i ->
                val at = programHeadersOffset + i.toLong() * programHeaderSize
                val offsetAt = at + if (Is64Bit) 8 else 4
                ProgramHeader(at, reader.getInt(at.toInt()), word(offsetAt), word(offsetAt + 3L * wordSize))
            }
            fun name(table: Section, index: Int): String {
                val start = (table.offset + index).toInt()
                var k = start
                while (k < image.size && image[k].toInt() != 0) k++
                return String(image, start, k - start, Charsets.ISO_8859_1)
            }
            val names = sections.getOrNull(sectionNamesIndex)

            // TryGetPayloadSymbolOffsetAndSize: the symbol in .dynsym, inside the section it points at.
            val dynsym = sections.firstOrNull { names != null && it.type == 11 && name(names, it.name) == ".dynsym" }
            val strings = dynsym?.let { sections.getOrNull(it.link) }
            if (dynsym != null && strings != null) {
                val symbolSize = if (Is64Bit) 24 else 16
                for (k in 0 until (dynsym.size / symbolSize).toInt()) {
                    val at = dynsym.offset + k.toLong() * symbolSize
                    if (name(strings, reader.getInt(at.toInt())) != AssemblyStorePayloadSymbol) continue
                    val value = if (Is64Bit) word(at + 8) else word(at + 4)
                    val size = if (Is64Bit) word(at + 16) else word(at + 8)
                    val sectionIndex = reader.getShort((at + if (Is64Bit) 6 else 14).toInt()).toInt() and 0xffff
                    val section = sections.getOrNull(sectionIndex) ?: return null
                    if (value < section.address) return null
                    val sectionOffset = value - section.address
                    if (sectionOffset >= section.size) return null
                    val availableSize = section.size - sectionOffset
                    if (size > availableSize) return null
                    return Elf(Is64Bit, sectionHeadersOffset, sections, programHeaders, at, section.offset + sectionOffset, if (size == 0L) availableSize else size)
                }
            }
            val payloadSection = sections.firstOrNull { names != null && name(names, it.name) == "payload" } ?: return null
            return Elf(Is64Bit, sectionHeadersOffset, sections, programHeaders, null, payloadSection.offset, payloadSection.size)
        }
    }
}
