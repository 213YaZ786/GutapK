package io.gutapk.core.offline

import io.airlift.compress.lz4.Lz4Compressor
import io.airlift.compress.lz4.Lz4Decompressor
import io.airlift.compress.zstd.ZstdCompressor
import io.airlift.compress.zstd.ZstdDecompressor
import java.nio.ByteBuffer
import java.nio.ByteOrder

// How a .NET for Android build compresses an assembly of its store.
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/AssemblyCompression.cs#AssemblyCompressionFormat
enum class AssemblyCompressionFormat {
    Lz4,
    Zstandard,
}

// aircompressor's codecs as GutapK sees them, the library itself not being
// loaded in GutapK: tools.Libraries loads AircompressorCodecs with the jar,
// as it loads godot.Zstd.
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/Compressor.java
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/Decompressor.java
// not ported: compress(ByteBuffer, ByteBuffer), decompress(ByteBuffer, ByteBuffer) (an assembly is read whole into an array)
interface AssemblyCodecs {
    // The number of bytes written to the output.
    fun decompress(format: AssemblyCompressionFormat, input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int

    fun maxCompressedLength(format: AssemblyCompressionFormat, uncompressedSize: Int): Int

    // The number of bytes written to the output.
    fun compress(format: AssemblyCompressionFormat, input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int
}

// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/lz4/Lz4Compressor.java#compress
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/lz4/Lz4Decompressor.java#decompress
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/zstd/ZstdCompressor.java#compress
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/zstd/ZstdDecompressor.java#decompress
class AircompressorCodecs : AssemblyCodecs {
    override fun decompress(format: AssemblyCompressionFormat, input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int =
        when (format) {
            AssemblyCompressionFormat.Lz4 -> Lz4Decompressor().decompress(input, inputOffset, inputLength, output, outputOffset, maxOutputLength)
            AssemblyCompressionFormat.Zstandard -> ZstdDecompressor().decompress(input, inputOffset, inputLength, output, outputOffset, maxOutputLength)
        }

    override fun maxCompressedLength(format: AssemblyCompressionFormat, uncompressedSize: Int): Int =
        when (format) {
            AssemblyCompressionFormat.Lz4 -> Lz4Compressor().maxCompressedLength(uncompressedSize)
            AssemblyCompressionFormat.Zstandard -> ZstdCompressor().maxCompressedLength(uncompressedSize)
        }

    override fun compress(format: AssemblyCompressionFormat, input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int =
        when (format) {
            AssemblyCompressionFormat.Lz4 -> Lz4Compressor().compress(input, inputOffset, inputLength, output, outputOffset, maxOutputLength)
            AssemblyCompressionFormat.Zstandard -> ZstdCompressor().compress(input, inputOffset, inputLength, output, outputOffset, maxOutputLength)
        }
}

// The header a compressed assembly starts with, magic, descriptor index and
// uncompressed length, then the compressed data. TryDecompress reads it,
// compress writes an assembly back the same way with the header it had: the
// runtime finds the descriptor and the size of its buffer in it.
// not ported: ReadFully, the ArrayPool (an assembly is read whole into an array)
// not ported: IsCompressed (TryReadFormat answers it)
// https://github.com/dotnet/android/blob/main/tests/Utilities/AssemblyStore/AssemblyCompression.cs#TryDecompress
object AssemblyCompression {
    const val Lz4Magic = 0x5A4C4158 // 'XALZ', little-endian
    const val ZstandardMagic = 0x535A4158 // 'XAZS', little-endian
    const val HeaderSize = 3 * 4
    const val MaximumUncompressedAssemblySize = 512 * 1024 * 1024

    fun TryReadFormat(input: ByteArray, start: Int, length: Int): AssemblyCompressionFormat? {
        if (length < 4) return null
        return when (ByteBuffer.wrap(input, start, 4).order(ByteOrder.LITTLE_ENDIAN).int) {
            Lz4Magic -> AssemblyCompressionFormat.Lz4
            ZstandardMagic -> AssemblyCompressionFormat.Zstandard
            else -> null
        }
    }

    // The assembly whole, or null when the data is not compressed.
    fun TryDecompress(input: ByteArray, start: Int, length: Int, codecs: AssemblyCodecs): ByteArray? {
        val format = TryReadFormat(input, start, length) ?: return null
        if (length < HeaderSize) throw IllegalArgumentException("Truncated $format assembly header")
        val reader = ByteBuffer.wrap(input, start, HeaderSize).order(ByteOrder.LITTLE_ENDIAN)
        reader.int // magic
        reader.int // descriptor index
        val uncompressedLength = reader.int
        if (uncompressedLength < 0 || uncompressedLength > MaximumUncompressedAssemblySize) {
            throw IllegalArgumentException("$format assembly expands to an unsupported size of ${uncompressedLength.toUInt()} bytes (maximum $MaximumUncompressedAssemblySize bytes)")
        }
        val compressedLength = length - HeaderSize
        val assemblyBytes = ByteArray(uncompressedLength)
        val decoded = codecs.decompress(format, input, start + HeaderSize, compressedLength, assemblyBytes, 0, uncompressedLength)
        if (decoded != uncompressedLength) {
            throw IllegalArgumentException("Failed to decompress $format assembly data (decoded $decoded of $uncompressedLength bytes)")
        }
        return assemblyBytes
    }

    // An assembly compressed again in the format and under the header of the
    // data it replaces, of the same uncompressed length.
    fun compress(original: ByteArray, start: Int, length: Int, assemblyBytes: ByteArray, codecs: AssemblyCodecs): ByteArray {
        val format = TryReadFormat(original, start, length) ?: throw IllegalArgumentException("the assembly was not compressed")
        val uncompressedLength = ByteBuffer.wrap(original, start + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (uncompressedLength != assemblyBytes.size) {
            throw IllegalArgumentException("the assembly changed length, ${assemblyBytes.size} bytes for $uncompressedLength")
        }
        val compressedBytes = ByteArray(HeaderSize + codecs.maxCompressedLength(format, assemblyBytes.size))
        System.arraycopy(original, start, compressedBytes, 0, HeaderSize)
        val written = codecs.compress(format, assemblyBytes, 0, assemblyBytes.size, compressedBytes, HeaderSize, compressedBytes.size - HeaderSize)
        return compressedBytes.copyOf(HeaderSize + written)
    }
}
