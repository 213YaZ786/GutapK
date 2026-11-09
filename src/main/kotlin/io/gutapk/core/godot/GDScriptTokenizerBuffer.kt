package io.gutapk.core.godot

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Godot 4's compiled GDScript, the .gdc an export writes for each script:
// the identifiers, a table of the constants the literals point at, the lines
// and columns, then the tokens. Read as set_code_buffer reads it and written
// again as parse_code_string writes it, uncompressed (a decompressed size of
// 0 is how Godot marks that), so GutapK needs zstd only to read, through
// the Decompressor tools.Libraries loads with aircompressor. The token
// count never changes: a literal is edited by pointing its token at another
// constant, since two literals of the same value share one.
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp
// not ported: get_cursor_line, get_cursor_column, set_cursor_position, set_multiline_mode, is_past_cursor, push_expression_indented_block, pop_expression_indented_block, scan (the parser reads tokens through them, nothing is parsed here)
// parse_code_string: its writing half only, from the tokens read, GDScriptTokenizerText is the source text's tokenizer
class GDScriptTokenizerBuffer(private val zstd: Decompressor) {
    // A token: its type, its line, and for an identifier or a literal the
    // index of its name or its constant (-1 for the others).
    class Token(val type: Int, val start_line: Int, var literal: Int)

    // A constant of the table: its value and its bytes as they were read.
    class Constant(val value: Any?, val bytes: ByteArray)

    // The two literal types that are not a Kotlin type of their own.
    data class StringName(val name: String)
    data class NodePath(val bytes: ByteArray)

    val identifiers = mutableListOf<String>()
    val constants = mutableListOf<Constant>()
    val tokens = mutableListOf<Token>()
    // By the index of a line's first token, its column.
    val token_columns = sortedMapOf<Int, Int>()

    // Kept as read: the tokens keep their count and their order.
    private var identifier_bytes = ByteArray(0)
    private var token_line_count = 0
    private var token_line_bytes = ByteArray(0)

    // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#_binary_to_token
    private fun _binary_to_token(b: ByteBuffer): Token {
        val start = b.position()
        val token_type = b.getInt(start)
        val type = token_type and TOKEN_MASK
        b.position(start + if (token_type and TOKEN_BYTE_MASK != 0) 4 else 1)
        val start_line = b.getInt()
        var literal = -1
        when (type) {
            ANNOTATION, IDENTIFIER -> {
                // Get name from map.
                literal = token_type ushr TOKEN_BITS
                if (literal >= identifiers.size) throw IOException("Identifier index out of bounds.")
            }
            ERROR, LITERAL -> {
                // Get literal from map.
                literal = token_type ushr TOKEN_BITS
                if (literal >= constants.size) throw IOException("Constant index out of bounds.")
            }
        }
        return Token(type, start_line, literal)
    }

    // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#set_code_buffer
    fun set_code_buffer(p_buffer: ByteArray): GDScriptTokenizerBuffer {
        if (p_buffer.size < 12 || String(p_buffer, 0, 4, Charsets.ISO_8859_1) != "GDSC") throw IOException("not a compiled GDScript")
        val head = ByteBuffer.wrap(p_buffer).order(ByteOrder.LITTLE_ENDIAN)
        val version = head.getInt(4)
        if (version != TOKENIZER_VERSION) throw IOException("Binary GDScript is not compatible with this engine version ($version, GutapK reads $TOKENIZER_VERSION).")
        val decompressed_size = head.getInt(8)

        val contents = if (decompressed_size == 0) {
            p_buffer.copyOfRange(12, p_buffer.size)
        } else {
            val out = ByteArray(decompressed_size)
            val result = zstd.decompress(p_buffer, 12, p_buffer.size - 12, out, 0, out.size)
            if (result != decompressed_size) throw IOException("Error decompressing GDScript tokenizer buffer.")
            out
        }

        val b = ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN)
        val identifier_count = b.getInt()
        val constant_count = b.getInt()
        token_line_count = b.getInt()
        val token_count = b.getInt()

        val identifiers_start = b.position()
        for (i in 0 until identifier_count) {
            val len = b.getInt()
            if (len < 0 || len * 4L > b.remaining()) throw IOException("identifier $i runs past the buffer")
            val cs = IntArray(len)
            for (j in 0 until len) {
                val tmp = ByteArray(4) { k -> (b.get().toInt() xor 0xb6).toByte() }
                cs[j] = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN).getInt()
            }
            identifiers.add(String(cs, 0, len))
        }
        identifier_bytes = contents.copyOfRange(identifiers_start, b.position())

        for (i in 0 until constant_count) {
            val at = b.position()
            val (v, len) = decode_variant(b)
            constants.add(Constant(v, contents.copyOfRange(at, at + len)))
        }

        // Lines then columns, a token index and a value each.
        if (token_line_count * 16L > b.remaining()) throw IOException("the line table runs past the buffer")
        token_line_bytes = ByteArray(token_line_count * 16).also { b.get(it) }
        // The column of each line's first token, its indent as scan reads it.
        // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#L211-L219
        val columns = ByteBuffer.wrap(token_line_bytes).order(ByteOrder.LITTLE_ENDIAN)
        columns.position(token_line_count * 8)
        for (i in 0 until token_line_count) {
            val token_index = columns.getInt()
            token_columns[token_index] = columns.getInt()
        }

        for (i in 0 until token_count) {
            val token_len = if (b.get(b.position()).toInt() and TOKEN_BYTE_MASK != 0) 8 else 5
            if (b.remaining() < token_len) throw IOException("token $i runs past the buffer")
            val token = _binary_to_token(b)
            if (token.type >= token_names.size) throw IOException("token $i has no type ${token.type}")
            tokens.add(token)
        }

        if (b.remaining() > 0) throw IOException("${b.remaining()} bytes after the last token")
        return this
    }

    // The index of a constant equal to value, added at the end when none is:
    // a literal edited points there and the others keep theirs.
    fun constant(value: Any?): Int {
        val bytes = encode_variant(value)
        val found = constants.indexOfFirst { it.bytes.contentEquals(bytes) }
        if (found >= 0) return found
        constants.add(Constant(value, bytes))
        return constants.size - 1
    }

    // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#_token_to_binary
    private fun _token_to_binary(p_token: Token, r_buffer: ByteArrayOutputStream) {
        var token_type = p_token.type and TOKEN_MASK
        when (p_token.type) {
            ANNOTATION, IDENTIFIER, ERROR, LITERAL -> token_type = token_type or (p_token.literal shl TOKEN_BITS)
        }
        // Encode token.
        if (token_type and TOKEN_MASK != 0) {
            r_buffer.write(u32(token_type or TOKEN_BYTE_MASK))
        } else {
            r_buffer.write(token_type)
        }
        r_buffer.write(u32(p_token.start_line))
    }

    // The end of parse_code_string with COMPRESS_NONE: the header, the four
    // counts, the identifiers, the constants, the lines and the tokens.
    // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#parse_code_string
    fun serialize(): ByteArray {
        val contents = ByteArrayOutputStream()
        contents.write(u32(identifiers.size))
        contents.write(u32(constants.size))
        contents.write(u32(token_line_count))
        contents.write(u32(tokens.size))
        contents.write(identifier_bytes)
        constants.forEach { contents.write(it.bytes) }
        contents.write(token_line_bytes)
        tokens.forEach { _token_to_binary(it, contents) }

        val buf = ByteArrayOutputStream()
        buf.write("GDSC".toByteArray(Charsets.ISO_8859_1))
        buf.write(u32(TOKENIZER_VERSION))
        buf.write(u32(0))
        buf.write(contents.toByteArray())
        return buf.toByteArray()
    }

    companion object {
        const val TOKENIZER_VERSION = 101
        const val TOKEN_BYTE_MASK = 0x80
        const val TOKEN_BITS = 8
        const val TOKEN_MASK = (1 shl (TOKEN_BITS - 1)) - 1

        // Token::Type in order, each by the name get_name gives it.
        // https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer.cpp#token_names
        val token_names = listOf(
            "Empty", "Annotation", "Identifier", "Literal",
            "<", "<=", ">", ">=", "==", "!=",
            "and", "or", "not", "&&", "||", "!",
            "&", "|", "~", "^", "<<", ">>",
            "+", "-", "*", "**", "/", "%",
            "=", "+=", "-=", "*=", "**=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=",
            "if", "elif", "else", "for", "while", "break", "continue", "pass", "return", "match", "when",
            "as", "assert", "await", "breakpoint", "class", "class_name", "const", "enum", "extends", "func", "in", "is",
            "namespace", "preload", "self", "signal", "static", "super", "trait", "var", "void", "yield",
            "[", "]", "{", "}", "(", ")", ",", ";", ".", "..", "...", ":", "$", "->", "_",
            "Newline", "Indent", "Dedent",
            "PI", "TAU", "INF", "NaN",
            "VCS conflict marker", "`", "?", "Error", "End of file",
        )

        val ANNOTATION = token_names.indexOf("Annotation")
        val IDENTIFIER = token_names.indexOf("Identifier")
        val LITERAL = token_names.indexOf("Literal")
        val ERROR = token_names.indexOf("Error")
        val MINUS = token_names.indexOf("-")
        val EQUAL = token_names.indexOf("=")
        val COLON = token_names.indexOf(":")
        val TK_CONST = token_names.indexOf("const")
        val VAR = token_names.indexOf("var")
        val STATIC = token_names.indexOf("static")

        // Variant::Type of the values a literal holds.
        const val NIL = 0
        const val BOOL = 1
        const val INT = 2
        const val FLOAT = 3
        const val STRING = 4
        const val STRING_NAME = 21
        const val NODE_PATH = 22

        private const val HEADER_TYPE_MASK = 0xFF
        private const val HEADER_DATA_FLAG_64 = 1 shl 16

        private fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        // https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/marshalls.cpp#_decode_string
        private fun _decode_string(buf: ByteBuffer): String {
            val strlen = buf.getInt()
            // Handle padding.
            val pad = if (strlen % 4 != 0) 4 - strlen % 4 else 0
            if (strlen < 0 || strlen + pad > buf.remaining()) throw IOException("a string runs past the buffer")
            val str = ByteArray(strlen).also { buf.get(it) }
            buf.position(buf.position() + pad)
            return String(str, Charsets.UTF_8)
        }

        // decode_variant for the types a literal can be, which is all the
        // constant table holds. Returns the value and its length.
        // not ported: the math, container, object and packed array cases (a GDScript literal is never one)
        // https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/marshalls.cpp#decode_variant
        fun decode_variant(buf: ByteBuffer): Pair<Any?, Int> {
            val start = buf.position()
            val header = buf.getInt()
            val value: Any? = when (header and HEADER_TYPE_MASK) {
                NIL -> null
                BOOL -> buf.getInt() != 0
                INT -> if (header and HEADER_DATA_FLAG_64 != 0) buf.getLong() else buf.getInt().toLong()
                FLOAT -> if (header and HEADER_DATA_FLAG_64 != 0) buf.getDouble() else buf.getFloat().toDouble()
                STRING -> _decode_string(buf)
                STRING_NAME -> StringName(_decode_string(buf))
                NODE_PATH -> {
                    val strlen = buf.getInt()
                    // Old format, just a string.
                    if (strlen and 0x80000000.toInt() == 0) throw IOException("a NodePath in the old format")
                    val namecount = strlen and 0x7FFFFFFF
                    var subnamecount = buf.getInt()
                    val np_flags = buf.getInt()
                    // Obsolete format with property separate from subpath.
                    if (np_flags and 2 != 0) subnamecount++
                    repeat(namecount + subnamecount) { _decode_string(buf) }
                    NodePath(buf.array().copyOfRange(start, buf.position()))
                }
                else -> throw IOException("a constant of Variant type ${header and HEADER_TYPE_MASK}, which no literal is")
            }
            return value to buf.position() - start
        }

        // encode_variant for the values an edit writes: nil, a bool, an int
        // or a float, a string. An int needs 8 bytes out of the int32 range,
        // a float when a float32 does not hold it exactly.
        // not ported: the other cases (an edit writes none of them)
        // https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/marshalls.cpp#encode_variant
        fun encode_variant(p_variant: Any?): ByteArray {
            val buf = ByteArrayOutputStream()
            fun string(s: String) {
                val utf8 = s.toByteArray(Charsets.UTF_8)
                buf.write(u32(utf8.size))
                buf.write(utf8)
                repeat((4 - utf8.size % 4) % 4) { buf.write(0) }
            }
            when (p_variant) {
                null -> buf.write(u32(NIL))
                is Boolean -> { buf.write(u32(BOOL)); buf.write(u32(if (p_variant) 1 else 0)) }
                is Long -> if (p_variant > Int.MAX_VALUE || p_variant < Int.MIN_VALUE) {
                    buf.write(u32(INT or HEADER_DATA_FLAG_64))
                    buf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(p_variant).array())
                } else {
                    buf.write(u32(INT))
                    buf.write(u32(p_variant.toInt()))
                }
                is Double -> if (p_variant.toFloat().toDouble() != p_variant) {
                    buf.write(u32(FLOAT or HEADER_DATA_FLAG_64))
                    buf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(p_variant).array())
                } else {
                    buf.write(u32(FLOAT))
                    buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(p_variant.toFloat()).array())
                }
                is String -> { buf.write(u32(STRING)); string(p_variant) }
                is StringName -> { buf.write(u32(STRING_NAME)); string(p_variant.name) }
                else -> throw IllegalArgumentException("a literal cannot be $p_variant")
            }
            return buf.toByteArray()
        }
    }
}
