package io.gutapk.core.il2cpp

// What the Methods search box can hold besides words: an offset in the
// library, or bytes with ?? for any byte, the way patch notes write them.
object Find {
    // The hex view shows this much from an offset that is not a method.
    const val RAW_WINDOW = 1024L

    // Past this many hits a pattern says nothing, "00 00" for instance.
    const val CAP = 10_000

    private val OFFSET = Regex("""^0[xX][0-9a-fA-F]{1,12}$""")
    private val TOKEN = Regex("""^([0-9a-fA-F]{2}|\?\?)$""")

    fun offset(query: String): Long? = query.trim().takeIf { OFFSET.matches(it) }?.drop(2)?.toLong(16)

    // Two tokens at least and one known byte, so a single word is never
    // read as bytes.
    fun pattern(query: String): List<Int?>? {
        val tokens = query.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size < 2 || !tokens.all { TOKEN.matches(it) }) return null
        val bytes = tokens.map { if (it == "??") null else it.toInt(16) }
        return bytes.takeIf { b -> b.any { it != null } }
    }

    // Every place the pattern fits, counted up to CAP, the first limit
    // kept. The first known byte anchors the scan, the rest is checked
    // only where it matches.
    fun search(data: ByteArray, pattern: List<Int?>, limit: Int): Pair<Int, List<Long>> {
        val anchor = pattern.indexOfFirst { it != null }
        if (anchor < 0 || pattern.size > data.size) return 0 to emptyList()
        val first = (pattern[anchor] ?: 0).toByte()
        val hits = mutableListOf<Long>()
        var count = 0
        val last = data.size - pattern.size + anchor
        var i = anchor
        while (i <= last && count < CAP) {
            if (data[i] == first && fits(data, i - anchor, pattern)) {
                count++
                if (hits.size < limit) hits.add((i - anchor).toLong())
            }
            i++
        }
        return count to hits
    }

    private fun fits(data: ByteArray, start: Int, pattern: List<Int?>): Boolean {
        for (k in pattern.indices) {
            val want = pattern[k] ?: continue
            if (data[start + k].toInt() and 0xff != want) return false
        }
        return true
    }

    fun methodAt(all: List<MethodEntry>, offset: Long): MethodEntry? =
        all.firstOrNull { offset >= it.offset && offset < it.offset + it.length }

    // A view that starts at any offset, named after the method it falls in
    // so a patch made there still says where it is.
    fun rawEntry(offset: Long, inside: MethodEntry?): MethodEntry {
        val at = "0x" + offset.toString(16).uppercase()
        return if (inside == null) {
            MethodEntry("", "", "libil2cpp.so", at, offset, offset, RAW_WINDOW)
        } else {
            val delta = offset - inside.offset
            MethodEntry(
                inside.assembly,
                inside.namespace,
                inside.type,
                inside.member + " +0x" + delta.toString(16).uppercase(),
                inside.rva + delta,
                offset,
                RAW_WINDOW,
            )
        }
    }
}
