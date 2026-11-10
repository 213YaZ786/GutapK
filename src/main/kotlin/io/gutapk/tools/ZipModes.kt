package io.gutapk.tools

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The unix mode of each entry of a zip, which Java's zip readers drop. A tool
// zipped on Linux keeps its programs executable this way, Ghidra's
// launch.sh and decompile among them.
// Ported from Apache Commons Compress,
// https://github.com/apache/commons-compress/blob/master/src/main/java/org/apache/commons/compress/archivers/zip/ZipFile.java#populateFromCentralDirectory
// and its positionAtCentralDirectory, positionAtEndOfCentralDirectoryRecord,
// tryToLocateSignature, positionAtCentralDirectory32 and 64,
// readCentralDirectoryEntry and toPlatform, with getUnixMode from
// https://github.com/apache/commons-compress/blob/master/src/main/java/org/apache/commons/compress/archivers/zip/ZipArchiveEntry.java#getUnixMode
// Not ported: split archives (isSplitZipArchive, a tool is one file), the
// name encodings other than UTF-8 and the extra fields (only the mode is
// read here), firstLocalFileHeaderOffset (data before the zip moves no
// central directory record).
object ZipModes {
    private const val WORD = 4
    private const val DWORD = 8
    private const val SHORT = 2
    private const val CFH_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_EOCD_LOC_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CFH_LEN = 42
    private const val MIN_EOCD_SIZE = 22
    private const val MAX_EOCD_SIZE = MIN_EOCD_SIZE + 0xFFFF
    private const val CFD_LENGTH_OFFSET = WORD + 4 * SHORT
    private const val ZIP64_EOCDL_LENGTH = WORD + WORD + DWORD + WORD
    private const val ZIP64_EOCDL_LOCATOR_OFFSET = WORD + WORD
    private const val ZIP64_EOCD_CFD_LOCATOR_OFFSET = WORD + DWORD + SHORT + SHORT + WORD + WORD + DWORD + DWORD + DWORD
    private const val PLATFORM_UNIX = 3
    private const val SHORT_SHIFT = 16
    private const val SHORT_MASK = 0xFFFF
    private const val BYTE_SHIFT = 8
    private const val NIBLET_MASK = 0x0F

    // Entry name to mode, only entries made on unix. Empty when the archive
    // carries none or cannot be read this way: the caller then falls back.
    fun of(path: Path): Map<String, Int> = runCatching {
        FileChannel.open(path, StandardOpenOption.READ).use { populateFromCentralDirectory(it) }
    }.getOrDefault(emptyMap())

    private fun populateFromCentralDirectory(archive: FileChannel): Map<String, Int> {
        positionAtCentralDirectory(archive)
        val modes = LinkedHashMap<String, Int>()
        var sig = readWord(archive)
        while (sig == CFH_SIG) {
            readCentralDirectoryEntry(archive, modes)
            sig = readWord(archive)
        }
        return modes
    }

    private fun positionAtCentralDirectory(archive: FileChannel) {
        val is64 = positionAtEndOfCentralDirectoryRecord(archive)
        if (!is64) positionAtCentralDirectory32(archive) else positionAtCentralDirectory64(archive)
    }

    private fun positionAtEndOfCentralDirectoryRecord(channel: FileChannel): Boolean {
        if (!tryToLocateSignature(channel, MIN_EOCD_SIZE.toLong(), MAX_EOCD_SIZE.toLong(), EOCD_SIG)) {
            throw java.util.zip.ZipException("Archive is not a ZIP archive")
        }
        var found64 = false
        val position = channel.position()
        if (position > ZIP64_EOCDL_LENGTH) {
            channel.position(position - ZIP64_EOCDL_LENGTH)
            found64 = readWord(channel) == ZIP64_EOCD_LOC_SIG
            if (!found64) channel.position(position) else channel.position(channel.position() - WORD)
        }
        return found64
    }

    private fun tryToLocateSignature(channel: FileChannel, minDistanceFromEnd: Long, maxDistanceFromEnd: Long, sig: Int): Boolean {
        var off = channel.size() - minDistanceFromEnd
        val stopSearching = maxOf(0L, channel.size() - maxDistanceFromEnd)
        var found = false
        if (off >= 0) {
            while (off >= stopSearching) {
                channel.position(off)
                val curr = try {
                    readWord(channel)
                } catch (e: EOFException) {
                    break
                }
                if (curr == sig) {
                    found = true
                    break
                }
                off--
            }
        }
        if (found) channel.position(off)
        return found
    }

    private fun positionAtCentralDirectory32(archive: FileChannel) {
        skipBytes(archive, CFD_LENGTH_OFFSET)
        readWord(archive)
        val centralDirectoryStartRelativeOffset = readWord(archive).toLong() and 0xffffffffL
        archive.position(centralDirectoryStartRelativeOffset)
    }

    private fun positionAtCentralDirectory64(archive: FileChannel) {
        skipBytes(archive, WORD)
        skipBytes(archive, ZIP64_EOCDL_LOCATOR_OFFSET - WORD)
        val relativeOffsetOfEOCD = readDword(archive)
        if (relativeOffsetOfEOCD < 0) throw java.util.zip.ZipException("ZIP64 end of central directory locator with negative offset")
        archive.position(relativeOffsetOfEOCD)
        if (readWord(archive) != ZIP64_EOCD_SIG) throw java.util.zip.ZipException("ZIP64 end of central directory locator is corrupt")
        skipBytes(archive, ZIP64_EOCD_CFD_LOCATOR_OFFSET - WORD)
        val centralDirectoryStartRelativeOffset = readDword(archive)
        if (centralDirectoryStartRelativeOffset < 0) throw java.util.zip.ZipException("ZIP64 central directory with negative offset")
        archive.position(centralDirectoryStartRelativeOffset)
    }

    private fun readCentralDirectoryEntry(archive: FileChannel, modes: MutableMap<String, Int>) {
        val cfhBuf = readFully(archive, CFH_LEN)
        val versionMadeBy = cfhBuf.getShort(0).toInt() and 0xffff
        val platform = toPlatform(versionMadeBy)
        val fileNameLen = cfhBuf.getShort(24).toInt() and 0xffff
        val extraLen = cfhBuf.getShort(26).toInt() and 0xffff
        val commentLen = cfhBuf.getShort(28).toInt() and 0xffff
        val externalAttributes = cfhBuf.getInt(34).toLong() and 0xffffffffL
        val fileName = readFully(archive, fileNameLen)
        val name = String(fileName.array(), Charsets.UTF_8)
        skipBytes(archive, extraLen + commentLen)
        getUnixMode(platform, externalAttributes).takeIf { it != 0 }?.let { modes[name] = it }
    }

    internal fun toPlatform(versionMadeBy: Int): Int = versionMadeBy shr BYTE_SHIFT and NIBLET_MASK

    internal fun getUnixMode(platform: Int, externalAttributes: Long): Int =
        if (platform != PLATFORM_UNIX) 0 else (externalAttributes shr SHORT_SHIFT and SHORT_MASK.toLong()).toInt()

    private fun skipBytes(channel: FileChannel, count: Int) {
        val newPosition = channel.position() + count
        if (newPosition > channel.size()) throw EOFException()
        channel.position(newPosition)
    }

    private fun readFully(channel: FileChannel, count: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) throw EOFException()
        }
        return buf.flip() as ByteBuffer
    }

    private fun readWord(channel: FileChannel): Int = readFully(channel, WORD).getInt(0)

    private fun readDword(channel: FileChannel): Long = readFully(channel, DWORD).getLong(0)
}
