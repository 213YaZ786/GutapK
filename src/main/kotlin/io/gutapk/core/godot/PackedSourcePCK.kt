package io.gutapk.core.godot

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

// The directory of a Godot pack, read as try_open_pack reads a standalone
// one. On Android Godot 4.6 writes assets.sparsepck: the files sit in the
// APK's assets and the pack only lists them, each with its size and md5.
// FileAccessPack reads a file up to that size, so a file written again with
// another length must have its entry changed too: both are fixed-width, the
// entry is rewritten where it is.
// https://github.com/godotengine/godot/blob/4.6.3-stable/core/io/file_access_pack.cpp#try_open_pack
// not ported: the search in an executable's pck section and at its end (an Android pack is a file of its own), the encrypted directory (refused), PackedData::add_path and remove_path (the engine's file table)
object PackedSourcePCK {
    const val PACK_HEADER_MAGIC = 0x43504447
    const val PACK_FORMAT_VERSION_V2 = 2
    const val PACK_FORMAT_VERSION_V3 = 3
    const val PACK_DIR_ENCRYPTED = 1 shl 0
    const val PACK_REL_FILEBASE = 1 shl 1
    const val PACK_SPARSE_BUNDLE = 1 shl 2
    const val PACK_FILE_ENCRYPTED = 1 shl 0
    const val PACK_FILE_REMOVAL = 1 shl 1

    // One file of the directory: its path under res://, and where its size
    // field sits in the pack (the md5 follows it).
    data class PackedFile(val path: String, val ofs: Long, val size: Long, val md5: ByteArray, val flags: Int, val size_at: Int)

    data class Pack(val sparse_bundle: Boolean, val files: List<PackedFile>)

    fun try_open_pack(pack: ByteArray): Pack {
        val f = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN)
        val magic = f.getInt()
        if (magic != PACK_HEADER_MAGIC) throw IOException("not a Godot pack")
        val pck_start_pos = 0L

        // Read header.
        val version = f.getInt()
        f.getInt() // ver_major
        f.getInt() // ver_minor
        f.getInt() // ver_patch, not used for validation.
        if (version != PACK_FORMAT_VERSION_V3 && version != PACK_FORMAT_VERSION_V2) throw IOException("Pack version unsupported: $version.")

        val pack_flags = f.getInt()
        val enc_directory = pack_flags and PACK_DIR_ENCRYPTED != 0
        val sparse_bundle = pack_flags and PACK_SPARSE_BUNDLE != 0
        f.getLong() // file_base

        if (version == PACK_FORMAT_VERSION_V3) {
            // V3: Read directory offset and skip reserved part of the header.
            val dir_offset = f.getLong() + pck_start_pos
            f.position(dir_offset.toInt())
        } else {
            // V2: Directory directly after the header.
            repeat(16) { f.getInt() } // Reserved.
        }

        // Read directory.
        val file_count = f.getInt()
        if (enc_directory) throw IOException("the pack's directory is encrypted")
        val files = List(file_count) {
            val sl = f.getInt()
            val cs = ByteArray(sl).also { f.get(it) }
            val path = String(cs, Charsets.UTF_8).trimEnd('\u0000')
            val size_at = f.position() + 8
            val ofs = f.getLong()
            val size = f.getLong()
            val md5 = ByteArray(16).also { f.get(it) }
            val flags = f.getInt()
            PackedFile(path, ofs, size, md5, flags, size_at)
        }
        return Pack(sparse_bundle, files)
    }

    // The pack with the entry of path given the size and the md5 of data,
    // as _save_pack_file records them.
    // https://github.com/godotengine/godot/blob/4.6.3-stable/editor/export/editor_export_platform.cpp#_save_pack_file
    fun set_file(pack: ByteArray, path: String, data: ByteArray): ByteArray {
        val file = try_open_pack(pack).files.firstOrNull { it.path == path } ?: throw IOException("$path is not in the pack")
        if (file.flags and (PACK_FILE_ENCRYPTED or PACK_FILE_REMOVAL) != 0) throw IOException("$path is encrypted or removed in the pack")
        val out = pack.copyOf()
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putLong(file.size_at, data.size.toLong())
        MessageDigest.getInstance("MD5").digest(data).copyInto(out, file.size_at + 8)
        return out
    }
}
