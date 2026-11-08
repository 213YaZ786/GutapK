package io.gutapk.core.offline

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.Adler32

// A dex file carries an Adler-32 checksum and a SHA-1 signature of its own
// bytes in its header. A patched dex must have both written again or ART
// refuses it, signature first because the checksum covers the signature.
// Ported from dexlib2's DexWriter, which does it on a DexDataStore.
object DexHeader {
    // The offsets are dexlib2's HeaderItem constants of the same names.
    const val CHECKSUM_OFFSET = 8
    const val CHECKSUM_DATA_START_OFFSET = 12
    const val SIGNATURE_OFFSET = 12
    const val SIGNATURE_SIZE = 20
    const val SIGNATURE_DATA_START_OFFSET = 32

    private val MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0A)
    private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")

    // A zip entry the platform loads as dex.
    fun isDex(entry: String): Boolean = DEX_ENTRY.matches(entry)

    // Both fields written again in place, in the order DexWriter does it.
    // https://github.com/JesusFreke/smali/blob/master/dexlib2/src/main/java/org/jf/dexlib2/writer/DexWriter.java#updateSignature
    // https://github.com/JesusFreke/smali/blob/master/dexlib2/src/main/java/org/jf/dexlib2/writer/DexWriter.java#updateChecksum
    fun update(dex: Path) {
        RandomAccessFile(dex.toFile(), "rw").use { dataStore ->
            val magic = ByteArray(MAGIC.size)
            if (dataStore.length() < SIGNATURE_DATA_START_OFFSET || dataStore.read(magic).let { it != MAGIC.size || !magic.contentEquals(MAGIC) }) {
                throw IOException("$dex is not a dex file")
            }
            updateSignature(dataStore)
            updateChecksum(dataStore)
        }
    }

    // SHA-1 over everything after the signature, written at the signature.
    // https://github.com/JesusFreke/smali/blob/master/dexlib2/src/main/java/org/jf/dexlib2/writer/DexWriter.java#updateSignature
    fun updateSignature(dataStore: RandomAccessFile) {
        val md = MessageDigest.getInstance("SHA-1")

        val buffer = ByteArray(4 * 1024)
        dataStore.seek(SIGNATURE_DATA_START_OFFSET.toLong())
        var bytesRead = dataStore.read(buffer)
        while (bytesRead >= 0) {
            md.update(buffer, 0, bytesRead)
            bytesRead = dataStore.read(buffer)
        }

        val signature = md.digest()
        if (signature.size != SIGNATURE_SIZE) {
            throw IOException("unexpected digest write: ${signature.size} bytes")
        }

        // write signature
        dataStore.seek(SIGNATURE_OFFSET.toLong())
        dataStore.write(signature)
    }

    // Adler-32 over everything after the checksum, signature included,
    // written little endian at the checksum.
    // https://github.com/JesusFreke/smali/blob/master/dexlib2/src/main/java/org/jf/dexlib2/writer/DexWriter.java#updateChecksum
    fun updateChecksum(dataStore: RandomAccessFile) {
        val a32 = Adler32()

        val buffer = ByteArray(4 * 1024)
        dataStore.seek(CHECKSUM_DATA_START_OFFSET.toLong())
        var bytesRead = dataStore.read(buffer)
        while (bytesRead >= 0) {
            a32.update(buffer, 0, bytesRead)
            bytesRead = dataStore.read(buffer)
        }

        // write checksum
        dataStore.seek(CHECKSUM_OFFSET.toLong())
        writeInt(dataStore, a32.value.toInt())
    }

    // DexDataWriter.writeInt, four bytes little endian.
    private fun writeInt(output: RandomAccessFile, value: Int) {
        output.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    }
}
