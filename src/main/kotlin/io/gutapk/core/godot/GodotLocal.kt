package io.gutapk.core.godot

import java.nio.file.Path
import java.util.zip.ZipFile

// A call of a Godot game's scripts that opens its server: the method the
// engine binds, whether its packets are UDP, the port when the scripts set
// it, the bind address it is given (null: every network), the function
// making the call.
data class GodotServer(val script: String, val line: Int, val text: String, val function_name: String, val udp: Boolean, val port: Long?, val bind_address: String?, val current_function: String?)

// A flag of the command line a script reads, and the server it opens.
data class GodotFlag(val script: String, val line: Int, val name: String, val opens: GodotServer?)

// A string literal a server may bind to that means every network: Local
// points it at 127.0.0.1, so friends reach the game only through the relay.
data class GodotBind(val script: String, val token: Int, val line: Int, val ip: String, val text: String)

// What Local reads of a Godot game, nothing set per game: the calls the
// engine binds to open a server, the flags the scripts read from
// get_cmdline_args and the suite each flag opens. The tokens are read the
// way GDScriptParser reads them, a call by parse_call, a block by
// parse_suite (its indent compared as the tokenizer's scan compares it), an
// if by parse_if, a match branch by parse_match_branch, without building
// the tree: only what leads to a server is followed. The script states are
// walked as GDRE Tools walks them.
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_parser.cpp#parse_call
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_parser.cpp#parse_suite
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_parser.cpp#parse_if
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_parser.cpp#parse_match_branch
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#L453-L478
// https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/tcp_server.cpp#L34
// https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/udp_server.cpp#L34
// https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/packet_peer_udp.cpp#L362
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/enet/enet_multiplayer_peer.cpp#L471
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/enet/enet_multiplayer_peer.cpp#L475
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/websocket/websocket_multiplayer_peer.cpp#L76
// https://github.com/godotengine/godot/blob/4.6.3-stable/core/core_bind.cpp#L800-L801
// https://github.com/GDRETools/gdsdecomp/blob/v2.7.4/bytecode/bytecode_base.cpp#L1556-L1569
// not ported: alloc_node, reset_extents, complete_extents, push_error, consume, make_completion_context, override_completion_context, push_completion_call, pop_completion_call, set_last_completion_call_arg, push_multiline, pop_multiline, CompletionType, ct (nothing is built, reported or completed)
// not ported: is_super, SUPER, SNAME, IdentifierNode, parse_identifier, SubscriptNode, parse_expression (a super call opens no server, an argument is kept as its tokens)
// not ported: argument (as a parsed expression: the tokens between two commas are kept)
// not ported: parent_block, parent_function, is_in_loop, error_count, parse_statement, SEMICOLON, lambda_ended, is_at_end, p_context, p_suite, p_for_lambda, VariableNode, ConstantNode, Local, get_local, add_local, get_name, UNDEFINED, CONSTANT, VARIABLE, alloc_recovery_suite (a suite is a range of tokens)
// not ported: n_if, parent_if, has_continue, has_return, false_block, else_block, previous_suite, ELSE, p_token, vformat (an else does not test the flag)
// not ported: has_bind, binds, PatternNode, parse_match_pattern, pattern_type, PT_REST, PT_BIND, PT_WILDCARD, has_wildcard, has_guard, guard_body, guard, WHEN, KeyValue, StringName, E, local, PATTERN_BIND (a pattern is a literal)
// not ported: pending_indents, multiline_mode, newline, last_token_was_newline, TK_EOF, eof, indent_stack, current_line (an indent is compared, no token is made)
// not ported: ClassDB, bind_method, D_METHOD, DEFVAL, _bind_methods, recv_buf_size, max_clients, max_channels, in_bandwidth, out_bandwidth, tls_server_options (the bindings are read for where a server listens)
// not ported: get_script_state, get_engine_version, engine_version, get_strings_from_variant, r_strings, p_include_identifiers, ERR_FAIL_COND_V_MSG, err (the tokens are walked, the strings are not listed)
data class GodotLocal(
    val servers: List<GodotServer>,
    val flags: List<GodotFlag>,
    val binds: List<GodotBind>,
    // The game reads get_cmdline_user_args: its flags come after --.
    val user_args: Boolean,
    // The first file where 127.0.0.1 or localhost is written, the address a
    // friend's client must reach.
    val loopback: String?,
) {
    // The server a flag opens, else the only one.
    val server: GodotServer? get() = flags.firstNotNullOfOrNull { it.opens } ?: servers.singleOrNull()

    // The arguments that start the server engine as the server.
    val args: List<String> get() {
        val names = flags.filter { it.opens != null && it.opens == server }.map { it.name }.distinct()
        return if (user_args && names.isNotEmpty()) listOf("--") + names else names
    }

    companion object {
        private val scripts = Regex("assets/.+\\.gdc")
        private val scenes = Regex("assets/.+\\.(tscn|scn|tres|res)")
        private val flag = Regex("--?[A-Za-z][\\w-]*")
        private val token_names = GDScriptTokenizerBuffer.token_names
        private val IDENTIFIER = GDScriptTokenizerBuffer.IDENTIFIER
        private val LITERAL = GDScriptTokenizerBuffer.LITERAL
        private val EQUAL = GDScriptTokenizerBuffer.EQUAL
        private val COLON = GDScriptTokenizerBuffer.COLON
        private val VAR = GDScriptTokenizerBuffer.VAR
        private val PERIOD = token_names.indexOf(".")
        private val COMMA = token_names.indexOf(",")
        private val PARENTHESIS_OPEN = token_names.indexOf("(")
        private val PARENTHESIS_CLOSE = token_names.indexOf(")")
        private val BRACKET_OPEN = token_names.indexOf("[")
        private val BRACKET_CLOSE = token_names.indexOf("]")
        private val BRACE_OPEN = token_names.indexOf("{")
        private val BRACE_CLOSE = token_names.indexOf("}")
        private val FUNC = token_names.indexOf("func")
        private val CLASS_NAME = token_names.indexOf("class_name")
        private val IF = token_names.indexOf("if")
        private val ELIF = token_names.indexOf("elif")
        private val NOT = token_names.indexOf("not")

        // The classes whose method opens a server, as the engine binds it:
        // the method, UDP or not, the index of its bind address argument
        // (ENetMultiplayerPeer takes set_bind_ip instead).
        private class Bound(val function_name: String, val udp: Boolean, val argument_index: Int?)
        private val listen = mapOf(
            "TCPServer" to Bound("listen", false, 1),
            "UDPServer" to Bound("listen", true, 1),
            "PacketPeerUDP" to Bound("bind", true, 1),
            "ENetMultiplayerPeer" to Bound("create_server", true, null),
            "WebSocketMultiplayerPeer" to Bound("create_server", false, 1),
        )
        private const val set_bind_ip = "set_bind_ip"
        private val wildcard = setOf("*", "0.0.0.0", "::")
        private val loopbacks = listOf("127.0.0.1", "localhost")

        // One script state: its tokens, the column of each line's first one.
        private class Script(val p_path: String, val script_state: GDScriptTokenizerBuffer) {
            val tokens = script_state.tokens
            val token_columns = script_state.token_columns
            fun name(i: Int) = GodotValues.get_token_text(script_state, i)
            fun check(i: Int, type: Int) = tokens.getOrNull(i)?.type == type
            fun identifier(i: Int, name: String) = check(i, IDENTIFIER) && name(i) == name
            fun literal(i: Int) = script_state.constants[tokens[i].literal].value
            val class_name = tokens.indices.firstOrNull { check(it, CLASS_NAME) }?.let { name(it + 1) }

            // The first token of the line holding i, and of the next line.
            fun current(i: Int) = token_columns.headMap(i + 1).keys.lastOrNull() ?: 0
            fun next(i: Int) = token_columns.tailMap(i + 1).keys.firstOrNull() ?: tokens.size
            fun line(i: Int) = (current(i) until next(i)).joinToString(" ") { name(it) }
        }

        // A call as parse_call reads it: the callee's function_name, the
        // token its attribute is called on, the tokens of each argument.
        private class CallNode(val function_name: String, val base: Int?, val arguments: List<IntRange>)

        private fun parse_call(s: Script, open: Int): CallNode? {
            if (!s.check(open, PARENTHESIS_OPEN) || !s.check(open - 1, IDENTIFIER)) return null
            val is_attribute = s.check(open - 2, PERIOD)
            val arguments = mutableListOf<IntRange>()
            var argument = open + 1
            var depth = 0
            for (i in open until s.tokens.size) {
                when (s.tokens[i].type) {
                    PARENTHESIS_OPEN, BRACKET_OPEN, BRACE_OPEN -> depth++
                    PARENTHESIS_CLOSE, BRACKET_CLOSE, BRACE_CLOSE -> if (--depth == 0) {
                        if (i > argument) arguments.add(argument until i)
                        break
                    }
                    COMMA -> if (depth == 1) {
                        arguments.add(argument until i)
                        argument = i + 1
                    }
                }
            }
            return CallNode(s.name(open - 1), if (is_attribute) open - 3 else null, arguments)
        }

        // The tokens of the suite after a colon: the lines indented further,
        // as scan pushes an indent, or the rest of its line.
        private fun parse_suite(s: Script, colon: Int): IntRange {
            val multiline = colon + 1 in s.token_columns
            if (!multiline) return colon + 1 until s.next(colon)
            // The first token is never in the table: parse_code_string starts it at column 1.
            val previous_indent = s.token_columns[s.current(colon)] ?: 1
            val statements = s.token_columns.tailMap(colon + 1).entries.firstOrNull { (_, current_column) -> current_column <= previous_indent }?.key ?: s.tokens.size
            return colon + 1 until statements
        }

        // The suite of the line holding i when it ends in a block's colon
        // (an if, a match branch, a func), else null.
        private fun block(s: Script, i: Int): IntRange? {
            val colon = (i until s.next(i)).lastOrNull { s.check(it, COLON) } ?: return null
            if (s.check(colon + 1, EQUAL) && colon + 1 < s.next(i)) return null
            return parse_suite(s, colon)
        }

        // Every script of the APK, and where a loopback address is written.
        fun read(p_path: Path, zstd: Decompressor): GodotLocal {
            val script_states = mutableListOf<Script>()
            var loopback: String? = null
            ZipFile(p_path.toFile()).use { zip ->
                for (e in zip.entries()) {
                    val p_buffer = if (scripts.matches(e.name) || scenes.matches(e.name)) zip.getInputStream(e).use { it.readAllBytes() } else continue
                    if (scripts.matches(e.name)) {
                        val script_state = Script(e.name, GDScriptTokenizerBuffer(zstd).set_code_buffer(p_buffer))
                        script_states.add(script_state)
                        val constants = script_state.script_state.constants
                        if (loopback == null && constants.any { c -> loopbacks.any { (c.value as? String)?.contains(it) == true } }) loopback = e.name
                    } else if (loopback == null && loopbacks.any { String(p_buffer, Charsets.ISO_8859_1).contains(it) }) {
                        loopback = e.name
                    }
                }
            }
            return read(script_states, loopback)
        }

        private fun read(script_states: List<Script>, loopback: String?): GodotLocal {
            val servers = mutableListOf<GodotServer>()
            for (s in script_states) for (i in s.tokens.indices) {
                val call = parse_call(s, i) ?: continue
                val bound = call.base?.let { listen[callee(s, it)] }?.takeIf { it.function_name == call.function_name } ?: continue
                val port = call.arguments.getOrNull(0)?.let { value(script_states, s, it) } as? Long
                val bind_address = if (bound.argument_index == null) {
                    s.tokens.indices.firstOrNull { s.identifier(it, set_bind_ip) }?.let { s.line(it) }
                } else {
                    call.arguments.getOrNull(bound.argument_index)?.joinToString(" ") { s.name(it) }
                }
                servers.add(GodotServer(s.p_path, s.tokens[i].start_line, s.line(i), call.function_name, bound.udp, port, bind_address, current_function(s, i)))
            }

            val callers = servers.associateWith { callers(script_states, it.current_function) }
            val cmdline = script_states.filter { s -> s.tokens.indices.any { s.identifier(it, "get_cmdline_args") || s.identifier(it, "get_cmdline_user_args") } }
            val user_args = cmdline.isNotEmpty() && cmdline.none { s -> s.tokens.indices.any { s.identifier(it, "get_cmdline_args") } }
            val flags = mutableListOf<GodotFlag>()
            for (s in cmdline) for (i in s.tokens.indices) {
                val name = (if (s.check(i, LITERAL)) s.literal(i) as? String else null)?.takeIf { flag.matches(it) } ?: continue
                val opens = servers.firstOrNull { opens(s, i, callers.getValue(it)) }
                flags.add(GodotFlag(s.p_path, s.tokens[i].start_line, name, opens))
            }

            // The wildcards of the scripts that open a server or read the
            // command line, and the ip given to set_bind_ip.
            val binds = mutableListOf<GodotBind>()
            val hosting = servers.map { it.script }.toSet() + cmdline.map { it.p_path }
            for (s in script_states) for (i in s.tokens.indices) {
                val ip = (if (s.check(i, LITERAL)) s.literal(i) as? String else null) ?: continue
                val set = s.identifier(i - 2, set_bind_ip) && s.check(i - 1, PARENTHESIS_OPEN)
                if ((ip in wildcard && s.p_path in hosting) || set) binds.add(GodotBind(s.p_path, i, s.tokens[i].start_line, ip, s.line(i)))
            }
            return GodotLocal(servers, flags, binds, user_args, loopback)
        }

        // The class the callee's base is: Class.new(), or a var declared as
        // one (var x: Class, var x := Class.new()).
        private fun callee(s: Script, base: Int): String? {
            if (s.check(base, PARENTHESIS_CLOSE)) {
                if (!s.check(base - 1, PARENTHESIS_OPEN) || !s.identifier(base - 2, "new") || !s.check(base - 3, PERIOD)) return null
                return s.name(base - 4)
            }
            if (!s.check(base, IDENTIFIER)) return null
            val name = s.name(base)
            for (i in s.tokens.indices) {
                if (!s.identifier(i, name)) continue
                if (s.check(i + 1, COLON) && s.name(i + 2) in listen) return s.name(i + 2)
                val initializer = if (s.check(i + 1, COLON)) i + 2 else i + 1
                if (s.check(initializer, EQUAL) && s.name(initializer + 1) in listen && s.check(initializer + 2, PERIOD) && s.identifier(initializer + 3, "new")) return s.name(initializer + 1)
            }
            return null
        }

        // The value an argument holds: a literal, or a const, var or
        // parameter set to one, in this script, in Class.NAME, or in the only
        // script that sets NAME.
        private fun value(script_states: List<Script>, s: Script, argument: IntRange): Any? {
            if (argument.count() == 1 && s.check(argument.first, LITERAL)) return s.literal(argument.first)
            if (argument.count() == 1 && s.check(argument.first, IDENTIFIER)) return initializer(s, s.name(argument.first))
            if (argument.count() != 3 || !s.check(argument.first + 1, PERIOD)) return null
            val class_name = s.name(argument.first)
            val name = s.name(argument.last)
            script_states.firstOrNull { it.class_name == class_name }?.let { return initializer(it, name) }
            return script_states.mapNotNull { initializer(it, name) }.distinct().singleOrNull()
        }

        // The literal a name is set to where it is declared: name := literal,
        // name : Type = literal, name = literal, ending its line or argument.
        private fun initializer(s: Script, name: String): Any? {
            for (i in s.tokens.indices) {
                if (!s.identifier(i, name)) continue
                var assign = i + 1
                if (s.check(assign, COLON)) assign += if (s.check(assign + 1, EQUAL)) 1 else 2
                if (!s.check(assign, EQUAL) || !s.check(assign + 1, LITERAL)) continue
                val end = assign + 2
                if (end == s.tokens.size || end in s.token_columns || s.check(end, COMMA) || s.check(end, PARENTHESIS_CLOSE)) return s.literal(assign + 1)
            }
            return null
        }

        // The named function holding token i.
        private fun current_function(s: Script, i: Int): String? =
            (i downTo 0).firstOrNull { s.check(it, FUNC) && s.check(it + 1, IDENTIFIER) }?.let { s.name(it + 1) }

        // The function, the functions calling it, and those calling them.
        private fun callers(script_states: List<Script>, function_name: String?): Set<String> {
            val functions = function_name?.let { mutableSetOf(it) } ?: return emptySet()
            repeat(8) {
                val size = functions.size
                for (s in script_states) for (i in s.tokens.indices) {
                    if (!s.check(i, FUNC) || !s.check(i + 1, IDENTIFIER) || s.name(i + 1) in functions) continue
                    if (calls(s, block(s, i + 1), functions)) functions.add(s.name(i + 1))
                }
                if (functions.size == size) return functions
            }
            return functions
        }

        private fun calls(s: Script, statements: IntRange?, functions: Set<String>) =
            statements != null && statements.any { parse_call(s, it)?.function_name in functions }

        // Whether the flag at i leads to one of the functions: the suite of
        // its match branch or if calls one, or sets a var true that the
        // condition of an if calling one tests.
        private fun opens(s: Script, i: Int, functions: Set<String>): Boolean {
            val true_block = block(s, i)
            if (calls(s, true_block, functions)) return true
            val guards = mutableSetOf<String>()
            if (true_block != null) {
                for (j in true_block) {
                    if (s.check(j, IDENTIFIER) && s.check(j + 1, EQUAL) && s.check(j + 2, LITERAL) && s.literal(j + 2) == true) guards.add(s.name(j))
                }
            } else {
                // var guard := "--flag" in args
                val current = s.current(i)
                val variable = if (s.check(current, VAR)) current + 1 else current
                if (s.check(variable, IDENTIFIER)) guards.add(s.name(variable))
            }
            for (j in s.tokens.indices) {
                if (!s.check(j, IF) && !s.check(j, ELIF)) continue
                val condition = j + 1 until s.next(j)
                val tested = condition.any { s.check(it, IDENTIFIER) && s.name(it) in guards && !s.check(it - 1, NOT) }
                if (tested && calls(s, block(s, j), functions)) return true
            }
            return false
        }
    }
}
