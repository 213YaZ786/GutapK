package io.gutapk.core.godot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A game's scripts written as an export writes them, each token as
// _token_to_binary writes it, the line and column of each line's first token
// but the first, as parse_code_string keeps them, from GDScript text cut into
// identifiers, keywords, literals and symbols; then read by GodotLocal.
// https://github.com/godotengine/godot/blob/4.6.3-stable/modules/gdscript/gdscript_tokenizer_buffer.cpp#parse_code_string
// not ported: GDScriptTokenizerText, tokenizer, set_source_code, set_multiline_mode, scan, TK_EOF, Token (a regex cuts the text, a test script has no continuation line)
// not ported: get_continuation_lines, rev_token_lines, line (no line continues another)
// not ported: token_pos, token_len, end_line, last_token_line (one line per statement, the counter places the tokens)
// not ported: KeyValue, E, StringName, Variant, write, resize (the maps are Kotlin's)
// not ported: compressed_size, Compression, MODE_ZSTD, buf, p_compress_mode (written uncompressed)
// not ported: TOKENIZER_VERSION check, encode_uint32 of Godot's header beyond the size, cs, memcpy (written as GodotTest writes them)
class GodotLocalTest {
    private val zstd = Zstd()

    private fun encode_uint32(p_uint: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p_uint).array()

    private fun parse_code_string(p_code: String): ByteArray {
        val identifier_map = linkedMapOf<String, Int>()
        val constant_map = linkedMapOf<Any, Int>()
        val token_lines = linkedMapOf<Int, Int>()
        val token_columns = linkedMapOf<Int, Int>()
        val token_buffer = ByteArrayOutputStream()
        var token_counter = 0
        p_code.trimIndent().lines().forEachIndexed { l, text ->
            Regex("\"[^\"]*\"|\\d+|[A-Za-z_][A-Za-z_0-9]*|\\S").findAll(text).forEachIndexed { at, current ->
                val s = current.value
                val constant: Any? = when {
                    s.startsWith("\"") -> s.trim('"')
                    s[0].isDigit() -> s.toLong()
                    s == "true" || s == "false" -> s.toBoolean()
                    else -> null
                }
                val keyword = GDScriptTokenizerBuffer.token_names.indexOf(s)
                val token_type = when {
                    constant != null -> GDScriptTokenizerBuffer.LITERAL or (constant_map.getOrPut(constant) { constant_map.size } shl GDScriptTokenizerBuffer.TOKEN_BITS)
                    keyword > 0 && (!s[0].isLetter() || s[0].isLowerCase()) -> keyword
                    else -> GDScriptTokenizerBuffer.IDENTIFIER or (identifier_map.getOrPut(s) { identifier_map.size } shl GDScriptTokenizerBuffer.TOKEN_BITS)
                }
                token_buffer.write(encode_uint32(token_type or GDScriptTokenizerBuffer.TOKEN_BYTE_MASK))
                token_buffer.write(encode_uint32(l + 1))
                if (token_counter > 0 && at == 0) {
                    token_lines[token_counter] = l + 1
                    token_columns[token_counter] = current.range.first + 1
                }
                token_counter++
            }
        }

        val contents = ByteArrayOutputStream()
        contents.write(encode_uint32(identifier_map.size))
        contents.write(encode_uint32(constant_map.size))
        contents.write(encode_uint32(token_lines.size))
        contents.write(encode_uint32(token_counter))
        // Save identifiers.
        for (s in identifier_map.keys) {
            contents.write(encode_uint32(s.length))
            for (c in s) encode_uint32(c.code).forEach { contents.write(it.toInt() xor 0xb6) }
        }
        // Save constants.
        for (v in constant_map.keys) contents.write(GDScriptTokenizerBuffer.encode_variant(v))
        // Save lines and columns.
        for ((k, v) in token_lines) { contents.write(encode_uint32(k)); contents.write(encode_uint32(v)) }
        for ((k, v) in token_columns) { contents.write(encode_uint32(k)); contents.write(encode_uint32(v)) }
        // Store tokens.
        contents.write(token_buffer.toByteArray())

        // Save header.
        val header = ByteArrayOutputStream()
        header.write("GDSC".toByteArray(Charsets.ISO_8859_1))
        header.write(encode_uint32(GDScriptTokenizerBuffer.TOKENIZER_VERSION))
        header.write(encode_uint32(0))
        header.write(contents.toByteArray())
        return header.toByteArray()
    }

    // An APK holding the scripts and the scenes given, by entry name.
    private fun apk(entries: Map<String, ByteArray>): Path {
        val file = createTempDirectory("gutapk-local").resolve("game.apk")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    // Burgers' way: a const in another script, a TCPServer, a flag setting
    // a var an if tests, another flag giving the address.
    private val consts = """
        class_name Consts
        const PORT := 2755
    """
    private val server = """
        class_name Server extends Node
        var _server := TCPServer.new()
        func _open(address := "0.0.0.0"):
            var err := _server.listen(Consts.PORT, address)
            if err == OK:
                print("Server opened!")
    """
    private val cmd = """
        class_name CmdParser extends Node
        var args := {}
        func _check_on_start():
            var open_server := false
            var address := "0.0.0.0"
            for key in args.keys():
                match key:
                    "--server":
                        open_server = true
                    "--bind", "--bind-address":
                        address = args[key]
            if open_server:
                server._open(address)
        func _parse():
            var _args := OS.get_cmdline_args()
            _check_on_start()
    """

    @Test
    fun readsTheServerItsPortItsFlagAndItsBinds() {
        val local = GodotLocal.read(apk(mapOf(
            "assets/consts.gdc" to parse_code_string(consts),
            "assets/server.gdc" to parse_code_string(server),
            "assets/cmd.gdc" to parse_code_string(cmd),
            "assets/choose.scn" to "RSRC placeholder_text 127.0.0.1".toByteArray(),
        )), zstd)
        val found = local.server!!
        assertEquals("assets/server.gdc", found.script)
        assertEquals("listen", found.function_name)
        assertEquals(2755L, found.port)
        assertEquals(false, found.udp)
        assertEquals("address", found.bind_address)
        assertEquals("_open", found.current_function)
        assertEquals(listOf("--server"), local.args)
        assertEquals(setOf("--server", "--bind", "--bind-address"), local.flags.map { it.name }.toSet())
        assertEquals(listOf("assets/server.gdc" to 3, "assets/cmd.gdc" to 5), local.binds.map { it.script to it.line })
        assertTrue(local.binds.all { it.ip == "0.0.0.0" })
        assertEquals("assets/choose.scn", local.loopback)
    }

    @Test
    fun readsEnetAfterUserArgsWithItsBindIp() {
        val game = """
            const PORT = 7000
            func _ready():
                if "--host" in OS.get_cmdline_user_args():
                    host()
            func host():
                var peer = ENetMultiplayerPeer.new()
                peer.set_bind_ip("*")
                peer.create_server(PORT, 8)
                multiplayer.multiplayer_peer = peer
        """
        val local = GodotLocal.read(apk(mapOf("assets/game.gdc" to parse_code_string(game))), zstd)
        val found = local.server!!
        assertEquals("create_server", found.function_name)
        assertEquals(7000L, found.port)
        assertTrue(found.udp)
        assertTrue(local.user_args)
        assertEquals(listOf("--", "--host"), local.args)
        assertEquals(listOf("*"), local.binds.map { it.ip })
        assertNull(local.loopback)
    }

    @Test
    fun aServerNoFlagOpensHasNoArgs() {
        val game = """
            func _on_host_pressed():
                var peer := WebSocketMultiplayerPeer.new()
                peer.create_server(9080)
            func _ready():
                if "--fullscreen" in OS.get_cmdline_args():
                    print("full")
        """
        val local = GodotLocal.read(apk(mapOf("assets/menu.gdc" to parse_code_string(game))), zstd)
        assertEquals(9080L, local.server!!.port)
        assertNull(local.server!!.bind_address)
        assertEquals(listOf("--fullscreen"), local.flags.map { it.name })
        assertEquals(emptyList(), local.args)
        assertEquals(emptyList(), local.binds)
    }

    // A method named like a server's on another class opens nothing.
    @Test
    fun aListenOfAnotherClassIsNoServer() {
        val game = """
            var radio := Radio.new()
            func _ready():
                radio.listen(2755, "0.0.0.0")
        """
        val local = GodotLocal.read(apk(mapOf("assets/radio.gdc" to parse_code_string(game))), zstd)
        assertEquals(emptyList(), local.servers)
        assertNull(local.server)
    }
}
