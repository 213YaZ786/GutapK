package io.gutapk.core.il2cpp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

// Bytes as the user types and reads them: pairs of hex digits, spaces
// allowed anywhere.
object Hex {
    fun parse(text: String): ByteArray? {
        val digits = text.filterNot { it.isWhitespace() }
        if (digits.isEmpty() || digits.length % 2 != 0) return null
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(digits.length / 2) { i -> digits.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun format(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}

// One change to libil2cpp.so of one ABI. old is what the untouched APK has
// there, so a patch that no longer fits its library is refused at rebuild
// instead of corrupting it. Hex strings keep equality simple.
data class BytePatch(val abi: String, val offset: Long, val old: String, val new: String, val label: String) {
    val size: Int get() = new.split(' ').count { it.isNotEmpty() }
    val end: Long get() = offset + size
}

sealed interface PatchProblem {
    data object BadHex : PatchProblem
    data object OutOfRange : PatchProblem
    data object Unchanged : PatchProblem
    data class Overlaps(val other: BytePatch) : PatchProblem
}

object Patches {
    private const val FILE = "patches.tsv"

    private fun file(packageDir: Path): Path = MethodIndex.dir(packageDir).resolve(FILE)

    fun read(packageDir: Path): List<BytePatch> {
        val f = file(packageDir)
        if (!Files.isRegularFile(f)) return emptyList()
        return Files.readAllLines(f).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5) return@mapNotNull null
            val offset = p[1].toLongOrNull(16) ?: return@mapNotNull null
            if (Hex.parse(p[2]) == null || Hex.parse(p[3]) == null) return@mapNotNull null
            BytePatch(p[0], offset, p[2], p[3], p[4])
        }.sortedBy { it.offset }
    }

    // Written aside then moved, a crash never leaves half a list.
    fun write(packageDir: Path, patches: List<BytePatch>) {
        val dir = MethodIndex.dir(packageDir)
        Files.createDirectories(dir)
        val part = dir.resolve("$FILE.part")
        Files.writeString(
            part,
            patches.sortedBy { it.offset }.joinToString("") { p ->
                listOf(p.abi, p.offset.toString(16), p.old, p.new, p.label.replace('\t', ' ').replace('\n', ' ')).joinToString("\t") + "\n"
            },
        )
        Files.move(part, file(packageDir), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // original holds the untouched bytes from start. A patch stays inside
    // them, which keeps it inside the method the user opened.
    fun make(
        abi: String,
        offset: Long,
        newText: String,
        label: String,
        start: Long,
        original: ByteArray,
        existing: List<BytePatch>,
    ): Pair<BytePatch?, PatchProblem?> {
        val bytes = Hex.parse(newText) ?: return null to PatchProblem.BadHex
        if (offset < start || offset + bytes.size > start + original.size) return null to PatchProblem.OutOfRange
        val at = (offset - start).toInt()
        val old = original.copyOfRange(at, at + bytes.size)
        if (old.contentEquals(bytes)) return null to PatchProblem.Unchanged
        val patch = BytePatch(abi, offset, Hex.format(old), Hex.format(bytes), label)
        existing.firstOrNull { it.abi == abi && it.offset < patch.end && patch.offset < it.end }?.let {
            return null to PatchProblem.Overlaps(it)
        }
        return patch to null
    }

    // The untouched bytes with this ABI's patches laid over them, for the
    // view. Which positions changed is returned too.
    fun overlay(original: ByteArray, start: Long, abi: String, patches: List<BytePatch>): Pair<ByteArray, BooleanArray> {
        val shown = original.copyOf()
        val changed = BooleanArray(original.size)
        patches.filter { it.abi == abi }.forEach { p ->
            val bytes = Hex.parse(p.new) ?: return@forEach
            bytes.forEachIndexed { i, b ->
                val at = p.offset - start + i
                if (at >= 0 && at < shown.size) {
                    shown[at.toInt()] = b
                    changed[at.toInt()] = true
                }
            }
        }
        return shown to changed
    }
}

// Reads a window of libil2cpp.so straight from the APK. Stored libraries
// skip at once, compressed ones are inflated up to the window.
object LibBytes {
    fun entry(abi: String): String = "lib/$abi/libil2cpp.so"

    fun read(apk: Path, abi: String, offset: Long, length: Int): ByteArray = ZipFile(apk.toFile()).use { zip ->
        val e = zip.getEntry(entry(abi)) ?: throw IOException("${entry(abi)} missing from the APK")
        if (offset < 0 || (e.size >= 0 && offset >= e.size)) throw IOException("offset 0x${offset.toString(16)} is outside the library")
        zip.getInputStream(e).use { input ->
            var left = offset
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("the library ended before 0x${offset.toString(16)}")
                    left -= 1
                } else {
                    left -= skipped
                }
            }
            input.readNBytes(length)
        }
    }
}
