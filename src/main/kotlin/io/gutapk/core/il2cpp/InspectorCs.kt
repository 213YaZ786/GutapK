package io.gutapk.core.il2cpp

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

// Where each loaded segment of an ELF library sits in the file. A
// virtual address inside one maps to the file offset the hex view and the
// patches use. fx's libil2cpp.so maps its code 0x4000 above its offset.
class ElfSegments(private val segments: List<Triple<Long, Long, Long>>) {

    fun fileOffset(va: Long): Long? =
        segments.firstOrNull { (vaddr, size, _) -> va >= vaddr && va < vaddr + size }?.let { (vaddr, _, offset) -> va - vaddr + offset }

    companion object {
        private const val PT_LOAD = 1

        // Only the header and the program headers are read, little endian,
        // 32 or 64 bit, as Android libraries are.
        fun read(lib: Path): ElfSegments = RandomAccessFile(lib.toFile(), "r").use { f ->
            val head = ByteArray(64)
            f.readFully(head)
            if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
                throw IOException("libil2cpp.so is not an ELF file")
            }
            if (head[5].toInt() != 1) throw IOException("libil2cpp.so is not little endian")
            val wide = head[4].toInt() == 2
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = if (wide) h.getLong(0x20) else h.getInt(0x1C).toLong() and 0xffffffffL
            val phentsize = (if (wide) h.getShort(0x36) else h.getShort(0x2A)).toInt() and 0xffff
            val phnum = (if (wide) h.getShort(0x38) else h.getShort(0x2C)).toInt() and 0xffff
            if (phnum == 0 || phentsize < (if (wide) 56 else 32) || phnum > 256) throw IOException("libil2cpp.so has no usable program headers")
            val table = ByteArray(phentsize * phnum)
            f.seek(phoff)
            f.readFully(table)
            val b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            val list = (0 until phnum).mapNotNull { i ->
                val at = i * phentsize
                if (b.getInt(at) != PT_LOAD) return@mapNotNull null
                if (wide) {
                    Triple(b.getLong(at + 0x10), b.getLong(at + 0x20), b.getLong(at + 0x08))
                } else {
                    Triple(b.getInt(at + 0x08).toLong() and 0xffffffffL, b.getInt(at + 0x10).toLong() and 0xffffffffL, b.getInt(at + 0x04).toLong() and 0xffffffffL)
                }
            }
            ElfSegments(list)
        }
    }
}

// Il2CppInspectorRedux's C# stub, read into the same index. Its shape,
// checked on 2026.2 with fx (metadata 39):
//
//   // Image 0: Assembly-CSharp.dll - Assembly: Assembly-CSharp, ... - Types 0-4708
//   namespace UnityEngine.Foo
//   {
//   	public class Bar : IBaz // TypeDefIndex: 1911
//   	{
//   		public int Count { get, set } // 0x0144...-0x0144... 0x0144...-0x0144...
//   		public override eAdStatus GetAdStatus(string placementID) // 0x0169E92C-0x0169E934
//
// written here without the statement ends the real file has after each
// declaration and accessor. Addresses are virtual, start and end. Nested types sit inside their
// outer type's braces and take its name as a prefix, as Cpp2IL writes them.
object InspectorCs {
    private val IMAGE = Regex("""^// Image \d+: (.+?)\.dll - .* - Types (\d+)-(\d+)\s*$""")
    private val NAMESPACE = Regex("""^\s*namespace\s+([^\s{]+)\s*$""")
    private val TYPE = Regex("""^\s*[^/\s][^/]*?\b(?:class|struct|interface|enum)\s+(.+?)(?:\s+:\s+.*?)?\s*// TypeDefIndex: (\d+)\s*$""")
    private val RANGE = Regex("""0x([0-9A-Fa-f]+)-0x([0-9A-Fa-f]+)""")
    // The C# statement end, which this source spells by its code.
    private val END = Char(0x3B)
    private val ACCESSORS = Regex("""\{\s*((?:[a-z]+\s*\x3B\s*)+)\}""")

    private class Scope(val depth: Int, val name: String, val isType: Boolean)

    fun parse(lines: Sequence<String>, toOffset: (Long) -> Long?): List<MethodEntry> {
        val images = mutableListOf<Triple<Int, Int, String>>()
        val out = mutableListOf<MethodEntry>()
        val scopes = ArrayDeque<Scope>()
        var depth = 0
        var opening: Scope? = null
        var assembly = ""
        for (line in lines) {
            val t = line.trim()
            IMAGE.matchEntire(line)?.let { m ->
                images.add(Triple(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1]))
                continue
            }
            if (t == "{") {
                depth++
                opening?.let { scopes.addLast(Scope(depth, it.name, it.isType)) }
                opening = null
                continue
            }
            if (t == "}") {
                if (scopes.lastOrNull()?.depth == depth) scopes.removeLast()
                depth--
                continue
            }
            NAMESPACE.matchEntire(line)?.let { m ->
                opening = Scope(0, m.groupValues[1], false)
                continue
            }
            TYPE.matchEntire(line)?.let { m ->
                opening = Scope(0, m.groupValues[1].substringBefore(" where ").trim(), true)
                val index = m.groupValues[2].toInt()
                if (scopes.none { it.isType }) assembly = images.firstOrNull { index >= it.first && index <= it.second }?.third ?: ""
                continue
            }
            val comment = t.indexOf("$END // 0x").takeIf { it >= 0 } ?: t.indexOf("} // 0x").takeIf { it >= 0 }?.let { it + 1 }
            if (comment == null || scopes.none { it.isType }) continue
            val ranges = RANGE.findAll(t.substring(comment)).toList()
            if (ranges.isEmpty()) continue
            val namespace = scopes.filter { !it.isType }.joinToString(".") { it.name }
            val type = scopes.filter { it.isType }.joinToString(".") { it.name }
            val declaration = t.substring(0, comment).trimEnd(END).trim()
            val accessors = ACCESSORS.find(declaration)
            val members = if (accessors != null) {
                val head = declaration.substring(0, accessors.range.first).trim()
                // An indexer is named this[...], its parameters hold spaces.
                val name = if (head.endsWith("]") && "this[" in head) head.substring(head.indexOf("this[")) else head.substringAfterLast(' ')
                accessors.groupValues[1].split(END).map { it.trim() }.filter { it.isNotEmpty() }.map { "$name.$it" }
            } else {
                listOf(declaration)
            }
            members.zip(ranges).forEach { (member, r) ->
                val start = r.groupValues[1].toLong(16)
                val end = r.groupValues[2].toLong(16)
                // Accessors of open generic types have no code, 0x0-0x0.
                if (start == 0L) return@forEach
                val offset = toOffset(start) ?: return@forEach
                out.add(MethodEntry(assembly, namespace, type, member, start, offset, (end - start).coerceAtLeast(0)))
            }
        }
        return out
    }
}
