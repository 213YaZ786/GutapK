package io.gutapk.core.godot

import io.airlift.compress.zstd.ZstdDecompressor

// aircompressor's Decompressor as GutapK sees it, the library itself not
// being loaded in GutapK: tools.Libraries loads Zstd with the jar.
// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/Decompressor.java
// not ported: decompress(ByteBuffer, ByteBuffer) (a script is read whole into an array)
fun interface Decompressor {
    // The number of bytes written to the output.
    fun decompress(input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int
}

// https://github.com/airlift/aircompressor/blob/2.0.3/src/main/java/io/airlift/compress/zstd/ZstdDecompressor.java#decompress
class Zstd : Decompressor {
    override fun decompress(input: ByteArray, inputOffset: Int, inputLength: Int, output: ByteArray, outputOffset: Int, maxOutputLength: Int): Int =
        ZstdDecompressor().decompress(input, inputOffset, inputLength, output, outputOffset, maxOutputLength)
}
