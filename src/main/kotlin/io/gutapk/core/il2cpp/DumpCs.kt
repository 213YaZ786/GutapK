package io.gutapk.core.il2cpp

// Il2CppDumper's dump.cs, read into the same index as Cpp2IL's output. Its
// shape, checked on version 6.7.46 with a Unity 2022 game:
//
//   // Image 1: Assembly-CSharp.dll - 1710
//   // Namespace: Mono.Math
//   internal class BigInteger // TypeDefIndex: 81
//   	// RVA: 0x18D5258 Offset: 0x18D5258 VA: 0x18D5258
//   	public void .ctor(BigInteger.Sign sign, uint len) { }
//
// An image owns the type indexes from its start to the next image's. The
// dump gives no method length: each runs to the next method's offset.
object DumpCs {
    // Past the last method the length is unknown, one instruction is shown.
    private const val LAST_LENGTH = 4L

    private val IMAGE = Regex("""^// Image \d+: (.+?) - (\d+)$""")
    private val NAMESPACE = Regex("""^// Namespace: ?(.*)$""")
    private val TYPE = Regex("""^[^/\t][^/]*?\b(?:class|struct|interface|enum)\s+(.+?)(?:\s+:\s+.*?)?\s*// TypeDefIndex: (\d+)\s*$""")
    private val RVA = Regex("""^\t// RVA: 0x([0-9A-Fa-f]+) Offset: 0x([0-9A-Fa-f]+)\b""")

    fun parse(lines: Sequence<String>): List<MethodEntry> {
        val images = mutableListOf<Pair<Int, String>>()
        val found = mutableListOf<MethodEntry>()
        var namespace = ""
        var type: String? = null
        var assembly = ""
        var pending: Pair<Long, Long>? = null
        for (line in lines) {
            IMAGE.matchEntire(line)?.let { m ->
                images.add(m.groupValues[2].toInt() to m.groupValues[1].removeSuffix(".dll"))
            }
            NAMESPACE.matchEntire(line)?.let { m ->
                namespace = m.groupValues[1].trim()
                type = null
            }
            TYPE.matchEntire(line)?.let { m ->
                type = m.groupValues[1].trim()
                val index = m.groupValues[2].toInt()
                assembly = images.lastOrNull { it.first <= index }?.second ?: ""
                pending = null
                continue
            }
            val at = RVA.find(line)
            if (at != null) {
                pending = at.groupValues[1].toLong(16) to at.groupValues[2].toLong(16)
                continue
            }
            val p = pending
            val t = type
            if (p != null && t != null && line.startsWith("\t") && line.trimEnd().endsWith("{ }")) {
                found.add(MethodEntry(assembly, namespace, t, line.trim().removeSuffix("{ }").trim(), p.first, p.second, 0))
            }
            pending = null
        }
        val offsets = found.map { it.offset }.distinct().sorted()
        return found.map { e ->
            val i = offsets.binarySearch(e.offset)
            val next = offsets.getOrNull(i + 1)
            e.copy(length = if (next != null) next - e.offset else LAST_LENGTH)
        }
    }
}
