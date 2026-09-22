package io.gutapk.core.apk

// The part of a configuration that picking a value needs: language for
// strings, density for images. 0xfffe is anydpi, 0xffff nodpi.
data class ResConfig(val language: String, val density: Int)

data class ResValue(val config: ResConfig, val type: Int, val data: Int, val string: String?)

// resources.arsc, read only far enough to resolve an id to its values in
// every configuration. Complex entries, styles and arrays, are skipped.
class ResourceTable private constructor(
    private val global: StringPool,
    private val types: Map<Int, Map<Int, List<Pair<ResConfig, Map<Int, Pair<Int, Int>>>>>>,
) {
    fun values(id: Int): List<ResValue> {
        val pkg = (id ushr 24) and 0xff
        val type = (id ushr 16) and 0xff
        val entry = id and 0xffff
        return types[pkg]?.get(type).orEmpty().mapNotNull { (cfg, entries) ->
            entries[entry]?.let { (t, v) -> ResValue(cfg, t, v, if (t == ValueType.STRING) global.get(v) else null) }
        }
    }

    // Follows references, a few hops at most, until string values remain.
    fun strings(id: Int, hops: Int = 0): List<Pair<ResConfig, String>> {
        if (hops > 8) return emptyList()
        return values(id).flatMap { v ->
            when {
                v.type == ValueType.STRING && v.string != null -> listOf(v.config to v.string)
                v.type == ValueType.REFERENCE -> strings(v.data, hops + 1).map { v.config to it.second }
                else -> emptyList()
            }
        }
    }

    // The default-language value, else the first one found.
    fun label(id: Int): String? {
        val all = strings(id)
        return (all.firstOrNull { it.first.language.isEmpty() } ?: all.firstOrNull())?.second
    }

    companion object {
        fun parse(data: ByteArray): ResourceTable {
            val d = Bytes.of(data)
            if (d.u16(0) != Chunk.TABLE) throw ApkFormatError("not a resource table")
            if (d.u32(4) > d.size) throw ApkFormatError("truncated resource table")
            val end = d.u32(4).toInt()
            var pos = d.u16(2)
            var global: StringPool? = null
            val pkgs = mutableMapOf<Int, MutableMap<Int, MutableList<Pair<ResConfig, Map<Int, Pair<Int, Int>>>>>>()
            while (pos + 8 <= end) {
                val type = d.u16(pos)
                val size = d.u32(pos + 4).toInt()
                if (size < 8 || pos.toLong() + size > end) throw ApkFormatError("bad chunk size at $pos")
                when (type) {
                    Chunk.STRING_POOL -> {
                        if (global == null) global = StringPool(d, pos)
                    }
                    Chunk.TABLE_PACKAGE -> {
                        val id = d.u32(pos + 8).toInt()
                        pkgs[id] = readPackage(d, pos, size)
                    }
                }
                pos += size
            }
            return ResourceTable(global ?: throw ApkFormatError("no global string pool"), pkgs)
        }

        private fun readPackage(
            d: Bytes,
            at: Int,
            size: Int,
        ): MutableMap<Int, MutableList<Pair<ResConfig, Map<Int, Pair<Int, Int>>>>> {
            val out = mutableMapOf<Int, MutableList<Pair<ResConfig, Map<Int, Pair<Int, Int>>>>>()
            val end = at + size
            var p = at + d.u16(at + 2)
            while (p + 8 <= end) {
                val type = d.u16(p)
                val header = d.u16(p + 2)
                val chunk = d.u32(p + 4).toInt()
                if (chunk < 8 || p.toLong() + chunk > end) throw ApkFormatError("bad chunk size at $p")
                if (type == Chunk.TABLE_TYPE) {
                    val typeId = d.u8(p + 8)
                    val flags = d.u8(p + 9)
                    val count = d.u32(p + 12).toInt()
                    val entriesStart = d.u32(p + 16).toInt()
                    val cfg = p + 20
                    val lang = String(d.slice(cfg + 8, 2), Charsets.ISO_8859_1).trim('\u0000')
                    val config = ResConfig(lang, d.u16(cfg + 14))
                    val offsets = mutableListOf<Pair<Int, Int>>()
                    when {
                        // Sparse: pairs of entry index and offset divided by 4.
                        flags and 0x01 != 0 -> for (i in 0 until count) {
                            offsets.add(d.u16(p + header + 4 * i) to d.u16(p + header + 4 * i + 2) * 4)
                        }
                        // 16-bit offsets divided by 4, 0xffff for none.
                        flags and 0x02 != 0 -> for (i in 0 until count) {
                            val o = d.u16(p + header + 2 * i)
                            if (o != 0xffff) offsets.add(i to o * 4)
                        }
                        else -> for (i in 0 until count) {
                            val o = d.u32(p + header + 4 * i)
                            if (o != 0xffffffffL) offsets.add(i to o.toInt())
                        }
                    }
                    val entries = mutableMapOf<Int, Pair<Int, Int>>()
                    offsets.forEach { (index, off) ->
                        val e = p + entriesStart + off
                        val entrySize = d.u16(e)
                        val entryFlags = d.u16(e + 2)
                        when {
                            // Compact entry: type in the high byte of the flags,
                            // data right after.
                            entryFlags and 0x08 != 0 -> {
                                entries[index] = (entryFlags ushr 8) to d.i32(e + 4)
                            }
                            entryFlags and 0x01 != 0 -> {}
                            else -> {
                                entries[index] = d.u8(e + entrySize + 3) to d.i32(e + entrySize + 4)
                            }
                        }
                    }
                    out.getOrPut(typeId) { mutableListOf() }.add(config to entries)
                }
                p += chunk
            }
            return out
        }
    }
}
