package io.gutapk.core.godot

import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile

// One value of a Godot game a player may balance: a number or a bool written
// in one of its compiled scripts. name is the const or var it sets when it is
// the whole value of one (SPEED in const SPEED := 100), text its line.
data class GodotValue(val script: String, val token: Int, val line: Int, val name: String?, val value: Any, val text: String)

// The values of an APK's compiled scripts, the way GDRE Tools lists a
// script's strings (get_script_strings_from_buf walks the constants of the
// script state), here walking the tokens so each literal keeps its line, and
// a line shown as its decompiler writes tokens (get_token_text). An edit
// points a literal's token at a constant the way _token_to_binary maps a
// literal to its constant_pos.
// https://github.com/GDRETools/gdsdecomp/blob/v2.7.4/bytecode/bytecode_base.cpp#get_script_strings_from_buf
// https://github.com/GDRETools/gdsdecomp/blob/v2.7.4/bytecode/bytecode_base.cpp#get_token_text
// https://github.com/GDRETools/gdsdecomp/blob/v2.7.4/bytecode/bytecode_base.cpp#_ensure_space
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#_token_to_binary
// not ported: get_global_token (Godot 3's token ids, the buffer here is version 101 only), get_engine_version and gdre::get_strings_from_variant (a string is not a value to balance)
object GodotValues {
    private val scripts = Regex("assets/.+\\.gdc")

    // https://github.com/GDRETools/gdsdecomp/blob/v2.7.4/bytecode/bytecode_base.cpp#get_constant_string
    fun get_constant_string(constant: Any?): String = when (constant) {
        null -> "null"
        is String -> "\"" + constant.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is GDScriptTokenizerBuffer.StringName -> "&\"" + constant.name + "\""
        is GDScriptTokenizerBuffer.NodePath -> "^\"…\""
        // A float keeps its point, as Godot writes 1.0.
        is Double -> if (constant % 1.0 == 0.0 && !constant.isInfinite()) "${constant.toLong()}.0" else constant.toString()
        else -> constant.toString()
    }

    fun get_token_text(p_script_state: GDScriptTokenizerBuffer, i: Int): String {
        if (i >= p_script_state.tokens.size) return "ERROR: Invalid token index"
        val token_id = p_script_state.tokens[i].type
        val token_val = p_script_state.tokens[i].literal
        return when (token_id) {
            0 -> ""
            // An annotation's name keeps its @.
            GDScriptTokenizerBuffer.ANNOTATION, GDScriptTokenizerBuffer.IDENTIFIER -> p_script_state.identifiers[token_val]
            GDScriptTokenizerBuffer.LITERAL -> get_constant_string(p_script_state.constants[token_val].value)
            else -> GDScriptTokenizerBuffer.token_names[token_id]
        }
    }

    private fun _ensure_space(p_code: StringBuilder) {
        if (p_code.isNotEmpty() && !p_code.endsWith(" ")) p_code.append(" ")
    }

    // The tokens of one line, a space between two but around punctuation.
    private fun line(p_script_state: GDScriptTokenizerBuffer, tokens: IntRange): String {
        val p_code = StringBuilder()
        for (i in tokens) {
            val text = get_token_text(p_script_state, i)
            val previous = if (i > tokens.first) get_token_text(p_script_state, i - 1) else ""
            // := is written as : then =.
            val joined = previous == ":" && text == "="
            if (!joined && text !in setOf(".", ",", ")", "]", ":", "(", "[") && previous !in setOf(".", "(", "[", "$")) _ensure_space(p_code)
            p_code.append(text)
        }
        return p_code.toString()
    }

    // The name a literal sets: the line is [@annotations] [static]
    // const|var NAME [: type] = literal.
    private fun name(script_state: GDScriptTokenizerBuffer, first: Int, i: Int, last: Int): String? {
        val tokens = script_state.tokens
        if (i != last || i - 1 <= first || tokens[i - 1].type != GDScriptTokenizerBuffer.EQUAL) return null
        val at = (first until i).firstOrNull { tokens[it].type == GDScriptTokenizerBuffer.TK_CONST || tokens[it].type == GDScriptTokenizerBuffer.VAR } ?: return null
        if ((first until at).any { tokens[it].type != GDScriptTokenizerBuffer.ANNOTATION && tokens[it].type != GDScriptTokenizerBuffer.STATIC }) return null
        val identifier = tokens.getOrNull(at + 1)?.takeIf { it.type == GDScriptTokenizerBuffer.IDENTIFIER } ?: return null
        return script_state.identifiers[identifier.literal]
    }

    fun get_script_values_from_buf(p_path: String, p_buffer: ByteArray, r_values: MutableList<GodotValue>, zstd: Decompressor) {
        val script_state = GDScriptTokenizerBuffer(zstd).set_code_buffer(p_buffer)
        val tokens = script_state.tokens
        var first = 0
        while (first < tokens.size) {
            var last = first
            while (last + 1 < tokens.size && tokens[last + 1].start_line == tokens[first].start_line) last++
            val text = line(script_state, first..last)
            for (i in first..last) {
                if (tokens[i].type != GDScriptTokenizerBuffer.LITERAL) continue
                val constant = script_state.constants[tokens[i].literal].value
                if (constant is Long || constant is Double || constant is Boolean) {
                    r_values.add(GodotValue(p_path, i, tokens[i].start_line, name(script_state, first, i, last), constant, text))
                }
            }
            first = last + 1
        }
    }

    // Every value of the APK's scripts, script by script in the zip's order.
    fun get_script_values(p_path: Path, zstd: Decompressor): List<GodotValue> {
        val r_values = mutableListOf<GodotValue>()
        ZipFile(p_path.toFile()).use { zip ->
            for (e in zip.entries()) {
                if (scripts.matches(e.name)) get_script_values_from_buf(e.name, zip.getInputStream(e).use { it.readAllBytes() }, r_values, zstd)
            }
        }
        return r_values
    }

    // The script with the literal of each token given a value of its type
    // (a Long for an int, a Double for a float, a Boolean).
    fun set_script_values(p_buffer: ByteArray, r_values: Map<Int, Any>, zstd: Decompressor): ByteArray {
        val script_state = GDScriptTokenizerBuffer(zstd).set_code_buffer(p_buffer)
        for ((i, constant) in r_values) {
            val p_token = script_state.tokens.getOrNull(i) ?: throw IOException("ERROR: Invalid token index $i")
            if (p_token.type != GDScriptTokenizerBuffer.LITERAL) throw IOException("token $i is not a literal")
            val old = script_state.constants[p_token.literal].value
            if (old == null || old::class != constant::class) throw IOException("token $i holds $old, it cannot become $constant")
            val constant_pos = script_state.constant(constant)
            p_token.literal = constant_pos
        }
        return script_state.serialize()
    }
}
