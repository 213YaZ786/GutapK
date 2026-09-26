package io.gutapk.core.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

// The class names a dex file defines or uses, read from its type table.
// Enough to tell which libraries an app ships, without decompiling it.
object DexClasses {
    private const val HEADER = 0x70
    private const val STRING_IDS_SIZE = 0x38
    private const val STRING_IDS_OFF = 0x3c
    private const val TYPE_IDS_SIZE = 0x40
    private const val TYPE_IDS_OFF = 0x44
    private const val METHOD_IDS_SIZE = 0x58
    private const val METHOD_IDS_OFF = 0x5c

    // A class descriptor is L, the name with slashes, then this byte.
    private const val DESCRIPTOR_END: Byte = 0x3b

    // Every classes*.dex, names with dots, sorted and without repeats. A dex
    // that cannot be read adds nothing, it never fails the whole APK.
    fun read(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
            .flatMap { e -> runCatching { names(zip.getInputStream(e).use { it.readBytes() }) }.getOrDefault(emptyList()) }
            .distinct()
            .sorted()
            .toList()

    // "class.method" for the methods the code calls or defines, kept only
    // when keep says so: a big app names a few hundred thousand. A method
    // reached through a subclass carries the subclass as its class.
    fun methods(zip: ZipFile, keep: (String, String) -> Boolean): Set<String> =
        zip.entries().asSequence()
            .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
            .flatMap { e -> runCatching { methodNames(zip.getInputStream(e).use { it.readBytes() }, keep) }.getOrDefault(emptyList()) }
            .toSet()

    internal fun methodNames(d: ByteArray, keep: (String, String) -> Boolean): List<String> {
        if (d.size < HEADER || d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte() || d[2] != 'x'.code.toByte()) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = b.getInt(STRING_IDS_SIZE)
        val stringOff = b.getInt(STRING_IDS_OFF)
        val typeCount = b.getInt(TYPE_IDS_SIZE)
        val typeOff = b.getInt(TYPE_IDS_OFF)
        val methodCount = b.getInt(METHOD_IDS_SIZE)
        val methodOff = b.getInt(METHOD_IDS_OFF)
        if (stringCount < 0 || typeCount < 0 || methodCount < 0) return emptyList()
        if (stringOff < 0 || stringOff.toLong() + 4L * stringCount > d.size) return emptyList()
        if (typeOff < 0 || typeOff.toLong() + 4L * typeCount > d.size) return emptyList()
        if (methodOff < 0 || methodOff.toLong() + 8L * methodCount > d.size) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until methodCount) {
            val at = methodOff + 8 * i
            val classIdx = b.getShort(at).toInt() and 0xffff
            val nameIdx = b.getInt(at + 4)
            if (classIdx >= typeCount) continue
            val descriptor = string(d, b, stringOff, stringCount, b.getInt(typeOff + 4 * classIdx)) ?: continue
            if (descriptor.length < 3 || descriptor[0] != 'L' || descriptor.last().code.toByte() != DESCRIPTOR_END) continue
            val cls = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            val name = string(d, b, stringOff, stringCount, nameIdx) ?: continue
            if (keep(cls, name)) out.add("$cls.$name")
        }
        return out
    }

    // One entry of the string table: its length as uleb128, then the bytes
    // up to a zero.
    private fun string(d: ByteArray, b: ByteBuffer, stringOff: Int, stringCount: Int, idx: Int): String? {
        if (idx < 0 || idx >= stringCount) return null
        val data = b.getInt(stringOff + 4 * idx)
        if (data < 0 || data >= d.size) return null
        var p = data
        while (p < d.size && d[p].toInt() and 0x80 != 0) p++
        p++
        val start = p
        while (p < d.size && d[p] != 0.toByte()) p++
        if (p >= d.size) return null
        return String(d, start, p - start, Charsets.UTF_8)
    }

    // Bounds checked at every read: offsets come from the file itself.
    internal fun names(d: ByteArray): List<String> {
        if (d.size < HEADER || d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte() || d[2] != 'x'.code.toByte()) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = b.getInt(STRING_IDS_SIZE)
        val stringOff = b.getInt(STRING_IDS_OFF)
        val typeCount = b.getInt(TYPE_IDS_SIZE)
        val typeOff = b.getInt(TYPE_IDS_OFF)
        if (stringCount < 0 || typeCount < 0) return emptyList()
        if (stringOff < 0 || stringOff.toLong() + 4L * stringCount > d.size) return emptyList()
        if (typeOff < 0 || typeOff.toLong() + 4L * typeCount > d.size) return emptyList()
        val out = ArrayList<String>(typeCount)
        for (i in 0 until typeCount) {
            val idx = b.getInt(typeOff + 4 * i)
            if (idx < 0 || idx >= stringCount) continue
            val data = b.getInt(stringOff + 4 * idx)
            if (data < 0 || data >= d.size) continue
            // The length in UTF-16 units comes first, as uleb128.
            var p = data
            while (p < d.size && d[p].toInt() and 0x80 != 0) p++
            p++
            val start = p
            while (p < d.size && d[p] != 0.toByte()) p++
            if (p >= d.size || p - start < 3) continue
            if (d[start] != 'L'.code.toByte() || d[p - 1] != DESCRIPTOR_END) continue
            out.add(String(d, start + 1, p - start - 2, Charsets.UTF_8).replace('/', '.'))
        }
        return out
    }
}
