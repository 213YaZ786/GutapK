package io.gutapk.core.il2cpp

import java.io.IOException
import java.io.RandomAccessFile
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

// Ready-made arm64 bodies, each read back with binutils 2.47 objdump. A
// return sets w0 or s0 then returns, so the method stops there. arm64 only
// by the user's choice, armeabi-v7a would need to know ARM from Thumb.
data class Preset(val id: String, val bytes: String)

object Arm64Presets {
    const val ABI = "arm64-v8a"
    private const val RET = "C0 03 5F D6"

    val all = listOf(
        Preset("false", "00 00 80 52 $RET"),
        Preset("true", "20 00 80 52 $RET"),
        Preset("minus_one", "00 00 80 12 $RET"),
        Preset("int_max", "00 00 B0 12 $RET"),
        Preset("int_min", "00 00 B0 52 $RET"),
        Preset("float_zero", "E0 03 27 1E $RET"),
        Preset("float_one", "00 10 2E 1E $RET"),
        Preset("ret", RET),
        Preset("nop", "1F 20 03 D5"),
    )
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

    // Every patch checks the bytes it replaces first, all before any is
    // written, so a library from another build is refused whole instead of
    // left half patched. libs gives the decoded library of each ABI.
    fun apply(libs: (String) -> Path?, patches: List<BytePatch>, log: (String) -> Unit) {
        val checked = patches.map { p ->
            val lib = libs(p.abi) ?: throw IOException("patch at 0x${p.offset.toString(16)} is for ${p.abi}, which is not in the APK being rebuilt")
            val old = Hex.parse(p.old) ?: throw IOException("patch at 0x${p.offset.toString(16)} is unreadable")
            val new = Hex.parse(p.new) ?: throw IOException("patch at 0x${p.offset.toString(16)} is unreadable")
            if (old.size != new.size) throw IOException("patch at 0x${p.offset.toString(16)} changes the length")
            RandomAccessFile(lib.toFile(), "r").use { f ->
                if (p.offset + old.size > f.length()) throw IOException("patch at 0x${p.offset.toString(16)} is past the end of ${p.abi}/libil2cpp.so")
                val found = ByteArray(old.size)
                f.seek(p.offset)
                f.readFully(found)
                if (!found.contentEquals(old)) {
                    throw IOException("patch at 0x${p.offset.toString(16)} expects ${p.old} but ${p.abi}/libil2cpp.so has ${Hex.format(found)}. The library is not the one the patch was made on.")
                }
            }
            Triple(lib, p, new)
        }
        checked.forEach { (lib, p, new) ->
            RandomAccessFile(lib.toFile(), "rw").use { f ->
                f.seek(p.offset)
                f.write(new)
            }
            log("patched ${p.abi} at 0x${p.offset.toString(16)}: ${p.old} to ${p.new} (${p.label})")
        }
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

    // The whole library, for a byte search. Daggerfall's is 73 MB.
    fun readAll(apk: Path, abi: String): ByteArray = ZipFile(apk.toFile()).use { zip ->
        val e = zip.getEntry(entry(abi)) ?: throw IOException("${entry(abi)} missing from the APK")
        if (e.size > Int.MAX_VALUE - 16) throw IOException("the library is too large to search")
        zip.getInputStream(e).use { it.readAllBytes() }
    }

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
