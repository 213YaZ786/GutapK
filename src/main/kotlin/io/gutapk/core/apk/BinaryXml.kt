package io.gutapk.core.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkFormatError(message: String) : Exception(message)

// Little-endian reads with bounds checked, so a truncated or hostile file
// fails with a message instead of an index error deep in a parser.
internal class Bytes(private val b: ByteBuffer) {
    val size: Int get() = b.limit()

    private fun need(o: Int, n: Int) {
        if (o < 0 || n < 0 || o.toLong() + n > b.limit()) throw ApkFormatError("read past the end at $o")
    }

    fun u8(o: Int): Int {
        need(o, 1)
        return b.get(o).toInt() and 0xff
    }

    fun u16(o: Int): Int {
        need(o, 2)
        return b.getShort(o).toInt() and 0xffff
    }

    fun u32(o: Int): Long {
        need(o, 4)
        return b.getInt(o).toLong() and 0xffffffffL
    }

    fun i32(o: Int): Int {
        need(o, 4)
        return b.getInt(o)
    }

    fun slice(o: Int, n: Int): ByteArray {
        need(o, n)
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = b.get(o + i)
        return out
    }

    companion object {
        fun of(data: ByteArray) = Bytes(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))
    }
}

internal object Chunk {
    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003
    const val XML_START_ELEMENT = 0x0102
    const val XML_END_ELEMENT = 0x0103
    const val XML_RESOURCE_MAP = 0x0180
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
}

// A string pool, shared by binary XML and resources.arsc. Strings are
// decoded on demand, a table can hold tens of thousands.
internal class StringPool(private val d: Bytes, private val at: Int) {
    private val header = d.u16(at + 2)
    val count: Int = d.u32(at + 8).toInt()
    private val utf8 = (d.u32(at + 16) and 0x100L) != 0L
    private val start = d.u32(at + 20).toInt()

    fun get(i: Int): String? {
        if (i < 0 || i >= count) return null
        var p = at + start + d.u32(at + header + 4 * i).toInt()
        return if (utf8) {
            // Two lengths precede the bytes, UTF-16 units then UTF-8 bytes,
            // each on one byte or two when the high bit is set.
            p += if (d.u8(p) and 0x80 != 0) 2 else 1
            var n = d.u8(p)
            p += 1
            if (n and 0x80 != 0) {
                n = ((n and 0x7f) shl 8) or d.u8(p)
                p += 1
            }
            String(d.slice(p, n), Charsets.UTF_8)
        } else {
            var n = d.u16(p)
            p += 2
            if (n and 0x8000 != 0) {
                n = ((n and 0x7fff) shl 16) or d.u16(p)
                p += 2
            }
            String(d.slice(p, 2 * n), Charsets.UTF_16LE)
        }
    }
}

object ValueType {
    const val REFERENCE = 0x01
    const val STRING = 0x03
    const val INT_DEC = 0x10
    const val INT_HEX = 0x11
    const val BOOLEAN = 0x12
}

data class XmlAttr(val resId: Int, val name: String, val type: Int, val data: Int, val raw: String?)

data class XmlElement(val depth: Int, val name: String, val attrs: List<XmlAttr>) {
    fun attr(resId: Int): XmlAttr? = attrs.firstOrNull { it.resId == resId }

    // Obfuscated APKs strip attribute names but keep resource ids, so the
    // id is tried first and the name only as a fallback.
    fun attr(resId: Int, name: String): XmlAttr? = attr(resId) ?: attrs.firstOrNull { it.resId == 0 && it.name == name }
}

// Android's compiled XML, read into a flat list of start elements with their
// depth. Enough for manifests and drawables, nothing is ever written back.
object BinaryXml {
    fun parse(data: ByteArray): List<XmlElement> {
        val d = Bytes.of(data)
        if (d.u16(0) != Chunk.XML) throw ApkFormatError("not a binary XML file")
        if (d.u32(4) > d.size) throw ApkFormatError("truncated binary XML")
        val end = d.u32(4).toInt()
        var pos = d.u16(2)
        var pool: StringPool? = null
        var resMap = IntArray(0)
        var depth = 0
        val out = mutableListOf<XmlElement>()
        while (pos + 8 <= end) {
            val type = d.u16(pos)
            val header = d.u16(pos + 2)
            val size = d.u32(pos + 4).toInt()
            if (size < 8 || pos.toLong() + size > end) throw ApkFormatError("bad chunk size at $pos")
            if (header < 8 || header > size) throw ApkFormatError("bad chunk header at $pos")
            when (type) {
                Chunk.STRING_POOL -> {
                    pool = StringPool(d, pos)
                }
                Chunk.XML_RESOURCE_MAP -> {
                    resMap = IntArray((size - header) / 4) { d.i32(pos + header + 4 * it) }
                }
                Chunk.XML_START_ELEMENT -> {
                    val p = pool ?: throw ApkFormatError("element before string pool")
                    val ext = pos + header
                    val name = p.get(d.i32(ext + 4)) ?: ""
                    val attrStart = d.u16(ext + 8)
                    val attrSize = d.u16(ext + 10)
                    val count = d.u16(ext + 12)
                    val attrs = (0 until count).map { i ->
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = d.i32(a + 4)
                        val rawIdx = d.i32(a + 8)
                        XmlAttr(
                            resId = if (nameIdx in resMap.indices) resMap[nameIdx] else 0,
                            name = p.get(nameIdx) ?: "",
                            type = d.u8(a + 15),
                            data = d.i32(a + 16),
                            raw = if (rawIdx == -1) null else p.get(rawIdx),
                        )
                    }
                    depth++
                    out.add(XmlElement(depth, name, attrs))
                }
                Chunk.XML_END_ELEMENT -> {
                    depth--
                }
            }
            pos += size
        }
        return out
    }
}
