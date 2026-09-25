package io.gutapk.tools

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

// A .tar.gz unpacked with the JDK alone, no library for one format. Plain
// files and folders only: links and devices are skipped. The archive's
// top folder is dropped, it carries the version (scrcpy-linux-x86_64-v4.1)
// while the tool table needs a stable path.
object Untar {
    private const val BLOCK = 512
    // Far above any tool, low enough that a lying header cannot fill the disk.
    private const val MAX_FILE = 2L shl 30

    fun extract(archive: Path, target: Path, cancelled: () -> Boolean) {
        Files.createDirectories(target)
        val base = target.toAbsolutePath().normalize()
        GZIPInputStream(Files.newInputStream(archive).buffered()).use { input ->
            var longName: String? = null
            while (true) {
                if (cancelled()) throw CancelledByUser()
                val header = input.readNBytes(BLOCK)
                if (header.size < BLOCK || header.all { it == 0.toByte() }) break
                val size = octal(header, 124, 12)
                if (size < 0 || size > MAX_FILE) throw CheckFailed("tar entry size out of bounds")
                val type = header[156].toInt().toChar()
                when (type) {
                    // GNU long name, then the entry it names.
                    'L' -> {
                        longName = String(readData(input, size), Charsets.UTF_8).trimEnd('\u0000')
                        continue
                    }
                    // pax header: a path record replaces the name.
                    'x' -> {
                        longName = paxPath(String(readData(input, size), Charsets.UTF_8)) ?: longName
                        continue
                    }
                }
                val name = longName ?: headerName(header)
                longName = null
                val relative = name.trimStart('/').substringAfter('/', "")
                if (relative.isEmpty()) {
                    skip(input, size)
                    continue
                }
                val out = base.resolve(relative).normalize()
                if (!out.startsWith(base) || out == base) throw CheckFailed("unsafe path in archive: $name")
                when (type) {
                    '5' -> Files.createDirectories(out)
                    '0', '\u0000' -> {
                        Files.createDirectories(out.parent)
                        Files.copy(Bounded(input, size), out, StandardCopyOption.REPLACE_EXISTING)
                        pad(input, size)
                        // Any execute bit in the mode, 0o111.
                        if ((octal(header, 100, 8) and 0b001_001_001L) != 0L) executable(out)
                    }
                    else -> skip(input, size)
                }
            }
        }
    }

    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(b: ByteArray, at: Int, len: Int): String {
        val end = (at until at + len).firstOrNull { b[it] == 0.toByte() } ?: (at + len)
        return String(b, at, end - at, Charsets.UTF_8)
    }

    internal fun octal(b: ByteArray, at: Int, len: Int): Long {
        val text = cString(b, at, len).trim()
        return if (text.isEmpty()) 0 else text.toLongOrNull(8) ?: -1
    }

    // "27 path=some/long/name\n": the length counts the whole record.
    internal fun paxPath(records: String): String? =
        records.lineSequence().mapNotNull { r -> r.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=") }.lastOrNull()

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size > 1 shl 20) throw CheckFailed("tar header record too large")
        val data = input.readNBytes(size.toInt())
        pad(input, size)
        return data
    }

    private fun skip(input: InputStream, size: Long) {
        input.skipNBytes(size)
        pad(input, size)
    }

    private fun pad(input: InputStream, size: Long) {
        val rest = (BLOCK - size % BLOCK) % BLOCK
        if (rest > 0) input.skipNBytes(rest)
    }

    private fun executable(p: Path) {
        val perms = Files.getPosixFilePermissions(p).toMutableSet()
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(p, perms)
    }

    // Exactly size bytes of the entry, the stream left at its end.
    private class Bounded(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b >= 0) left--
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(buf, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() {}
    }
}
