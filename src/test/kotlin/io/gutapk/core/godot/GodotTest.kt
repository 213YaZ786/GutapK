package io.gutapk.core.godot

import io.airlift.compress.zstd.ZstdCompressor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A compiled script written the way parse_code_string writes the .gdc an
// export carries, and a sparse pack the way _store_header and
// _encrypt_and_store_directory write its directory, then read, listed and
// edited by GutapK.
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#parse_code_string
// https://github.com/godotengine/godot/blob/4.6.3-stable/editor/export/editor_export_platform.cpp#_store_header
// https://github.com/godotengine/godot/blob/4.6.3-stable/editor/export/editor_export_platform.cpp#_encrypt_and_store_directory
// not ported: GDScriptTokenizerText's scan (the tokens are given), the reverse maps (given in order), the continuation lines (none), the encrypted directory (refused)
class GodotTest {
    private val zstd = Zstd()

    private fun encode_uint32(p_uint: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p_uint).array()

    private fun encode_uint64(p_uint: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(p_uint).array()

    // One token as _token_to_binary writes it: type, then the identifier or
    // constant index above TOKEN_BITS, then its line.
    private fun _token_to_binary(type: Int, literal: Int, start_line: Int, token_buffer: ByteArrayOutputStream) {
        var token_type = type
        if (literal >= 0) token_type = token_type or (literal shl GDScriptTokenizerBuffer.TOKEN_BITS)
        token_buffer.write(encode_uint32(token_type or GDScriptTokenizerBuffer.TOKEN_BYTE_MASK))
        token_buffer.write(encode_uint32(start_line))
    }

    // const SPEED := 100
    // const JUMP_FORCE := 100
    // var ratio := 0.5
    // The two 100 share one constant, as constant_map keeps a value once.
    private fun parse_code_string(compress: Boolean): ByteArray {
        val rev_identifier_map = listOf("SPEED", "JUMP_FORCE", "ratio")
        val rev_constant_map = listOf<Any?>(100L, 0.5)
        val token_buffer = ByteArrayOutputStream()
        var token_counter = 0
        val token_lines = mutableMapOf<Int, Int>()
        fun line(keyword: Int, identifier: Int, constant: Int, start_line: Int) {
            if (token_counter > 0) token_lines[token_counter] = start_line
            _token_to_binary(keyword, -1, start_line, token_buffer)
            _token_to_binary(GDScriptTokenizerBuffer.IDENTIFIER, identifier, start_line, token_buffer)
            _token_to_binary(GDScriptTokenizerBuffer.COLON, -1, start_line, token_buffer)
            _token_to_binary(GDScriptTokenizerBuffer.EQUAL, -1, start_line, token_buffer)
            _token_to_binary(GDScriptTokenizerBuffer.LITERAL, constant, start_line, token_buffer)
            // No Newline: set_multiline_mode(true) ignores whitespace tokens.
            token_counter += 5
        }
        line(GDScriptTokenizerBuffer.TK_CONST, 0, 0, 1)
        line(GDScriptTokenizerBuffer.TK_CONST, 1, 0, 2)
        line(GDScriptTokenizerBuffer.VAR, 2, 1, 3)

        val contents = ByteArrayOutputStream()
        contents.write(encode_uint32(rev_identifier_map.size))
        contents.write(encode_uint32(rev_constant_map.size))
        contents.write(encode_uint32(token_lines.size))
        contents.write(encode_uint32(token_counter))
        // Save identifiers.
        for (s in rev_identifier_map) {
            contents.write(encode_uint32(s.length))
            for (c in s) encode_uint32(c.code).forEach { contents.write(it.toInt() xor 0xb6) }
        }
        // Save constants.
        for (v in rev_constant_map) contents.write(GDScriptTokenizerBuffer.encode_variant(v))
        // Save lines and columns.
        for ((k, v) in token_lines) { contents.write(encode_uint32(k)); contents.write(encode_uint32(v)) }
        for (k in token_lines.keys) { contents.write(encode_uint32(k)); contents.write(encode_uint32(1)) }
        // Store tokens.
        contents.write(token_buffer.toByteArray())

        // Save header.
        val buf = ByteArrayOutputStream()
        buf.write("GDSC".toByteArray(Charsets.ISO_8859_1))
        buf.write(encode_uint32(GDScriptTokenizerBuffer.TOKENIZER_VERSION))
        val raw = contents.toByteArray()
        if (compress) {
            val compressor = ZstdCompressor()
            val compressed = ByteArray(compressor.maxCompressedLength(raw.size))
            val len = compressor.compress(raw, 0, raw.size, compressed, 0, compressed.size)
            buf.write(encode_uint32(raw.size))
            buf.write(compressed, 0, len)
        } else {
            buf.write(encode_uint32(0))
            buf.write(raw)
        }
        return buf.toByteArray()
    }

    private fun values(p_buffer: ByteArray) = mutableListOf<GodotValue>().also { GodotValues.get_script_values_from_buf("assets/s.gdc", p_buffer, it, zstd) }

    @Test
    fun listsTheValuesOfAScriptCompressedOrNot() {
        val r_values = values(parse_code_string(compress = true))
        assertEquals(listOf("SPEED", "JUMP_FORCE", "ratio"), r_values.map { it.name })
        assertEquals(listOf<Any>(100L, 100L, 0.5), r_values.map { it.value })
        assertEquals(listOf(4, 9, 14), r_values.map { it.token })
        assertEquals("const SPEED:= 100", r_values[0].text)
        assertEquals(r_values, values(parse_code_string(compress = false)))
    }

    @Test
    fun anEditGivesOneLiteralItsOwnConstant() {
        val p_buffer = GodotValues.set_script_values(parse_code_string(compress = true), mapOf(4 to 300L, 14 to 1.25), zstd)
        // Written again uncompressed, which a decompressed size of 0 marks.
        assertEquals("GDSC", String(p_buffer, 0, 4, Charsets.ISO_8859_1))
        assertEquals(0, ByteBuffer.wrap(p_buffer).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        // JUMP_FORCE shared SPEED's 100 and keeps it.
        assertEquals(listOf<Any>(300L, 100L, 1.25), values(p_buffer).map { it.value })
        // A value the table already holds is pointed at, not added again.
        val back = GodotValues.set_script_values(p_buffer, mapOf(9 to 300L), zstd)
        assertEquals(listOf<Any>(300L, 300L, 1.25), values(back).map { it.value })
        assertEquals(4, GDScriptTokenizerBuffer(zstd).set_code_buffer(back).constants.size)
    }

    @Test
    fun anEditKeepsTheTypeAndTheTokens() {
        val p_buffer = parse_code_string(compress = false)
        assertFailsWith<IOException> { GodotValues.set_script_values(p_buffer, mapOf(4 to 1.5), zstd) }
        assertFailsWith<IOException> { GodotValues.set_script_values(p_buffer, mapOf(3 to 1L), zstd) }
        assertFailsWith<IOException> { GodotValues.set_script_values(p_buffer, mapOf(99 to 1L), zstd) }
        // Nothing changed is the same script, byte for byte.
        assertContentEquals(p_buffer, GodotValues.set_script_values(p_buffer, emptyMap(), zstd))
    }

    // A sparse pack: the header, then the directory where the header points.
    private fun save_pack(files: List<Triple<String, ByteArray, Int>>): ByteArray {
        val f = ByteArrayOutputStream()
        f.write(encode_uint32(PackedSourcePCK.PACK_HEADER_MAGIC))
        f.write(encode_uint32(PackedSourcePCK.PACK_FORMAT_VERSION_V3))
        listOf(4, 6, 3).forEach { f.write(encode_uint32(it)) }
        f.write(encode_uint32(PackedSourcePCK.PACK_REL_FILEBASE or PackedSourcePCK.PACK_SPARSE_BUNDLE))
        f.write(encode_uint64(0)) // Files base offset.
        f.write(encode_uint64(24L + 16 + 64)) // Directory offset.
        repeat(16) { f.write(encode_uint32(0)) } // reserved
        f.write(encode_uint32(files.size)) // amount of files
        for ((path, data, flags) in files) {
            val path_utf8 = path.toByteArray(Charsets.UTF_8)
            val pad = (4 - path_utf8.size % 4) % 4
            f.write(encode_uint32(path_utf8.size + pad))
            f.write(path_utf8)
            repeat(pad) { f.write(0) }
            f.write(encode_uint64(0))
            f.write(encode_uint64(data.size.toLong()))
            f.write(MessageDigest.getInstance("MD5").digest(data))
            f.write(encode_uint32(flags))
        }
        return f.toByteArray()
    }

    @Test
    fun aPackEntryTakesTheSizeAndMd5OfTheScript() {
        val old = parse_code_string(compress = true)
        val other = byteArrayOf(1, 2, 3)
        val pack = save_pack(listOf(Triple("Scripts/s.gdc", old, 0), Triple("icon.png", other, 0), Triple("Scripts/x.gdc", other, PackedSourcePCK.PACK_FILE_ENCRYPTED)))
        val data = GodotValues.set_script_values(old, mapOf(4 to 300L), zstd)
        val back = PackedSourcePCK.try_open_pack(PackedSourcePCK.set_file(pack, "Scripts/s.gdc", data))
        assertEquals(true, back.sparse_bundle)
        assertEquals(listOf("Scripts/s.gdc", "icon.png", "Scripts/x.gdc"), back.files.map { it.path })
        assertEquals(data.size.toLong(), back.files[0].size)
        assertContentEquals(MessageDigest.getInstance("MD5").digest(data), back.files[0].md5)
        // The other entries are left as they were.
        assertEquals(PackedSourcePCK.try_open_pack(pack).files.drop(1).map { it.size to it.md5.toList() }, back.files.drop(1).map { it.size to it.md5.toList() })
        assertFailsWith<IOException> { PackedSourcePCK.set_file(pack, "Scripts/x.gdc", data) }
        assertFailsWith<IOException> { PackedSourcePCK.set_file(pack, "Scripts/none.gdc", data) }
    }
}
