package io.gutapk.core.apk

import java.util.zip.ZipFile

enum class UnityBackend { IL2CPP, MONO }

data class NativeLib(val abi: String, val size: Long)

// What the dump and the hex view will need to know about a Unity game,
// read from the APK alone.
data class UnityInfo(
    // Null when the game data is there but no engine library says which.
    val backend: UnityBackend?,
    val version: String?,
    val metadataPath: String?,
    val metadataSize: Long?,
    // Null when the file does not start with IL2CPP's magic, which is what
    // an encrypted or obfuscated metadata file looks like.
    val metadataVersion: Int?,
    val il2cpp: List<NativeLib>,
    val engineAbis: List<String>,
    val assemblies: Int,
)

object UnityReader {
    private const val DATA = "assets/bin/Data/"
    private const val MAGIC = 0xFAB11BAF.toInt()

    // Unity writes its version near the start of globalgamemanagers and of
    // data.unity3d, whatever the serialized format version. 2022.3.62f3 and
    // 5.6.7f1 are both matched, the bundle format's "5.x.x" is not.
    private val VERSION = Regex("""\b(\d{1,4}\.\d{1,2}\.\d{1,3}[abfpx]\d{1,3})\b""")

    // libunity.so holds older version strings too. The one followed by its
    // changeset hash is the build's own.
    private val LIB_VERSION = Regex("""(\d{1,4}\.\d{1,2}\.\d{1,3}[abfpx]\d{1,3}) \([0-9a-f]{12}\)""")

    private const val HEAD = 256
    private const val LIB_MAX = 256L shl 20

    fun read(zip: ZipFile): UnityInfo? {
        val entries = zip.entries().toList().filter { !it.isDirectory }
        val names = entries.map { it.name }
        val engine = entries.filter { lib(it.name, "libunity.so") }
        if (engine.isEmpty() && names.none { it.startsWith(DATA) }) return null

        val il2cpp = entries.filter { lib(it.name, "libil2cpp.so") }.map { NativeLib(it.name.split('/')[1], it.size) }
        val mono = names.any { lib(it, "libmonobdwgc-2.0.so") || lib(it, "libmono.so") || lib(it, "libmonosgen-2.0.so") }
        val metadata = entries.firstOrNull { it.name.endsWith("/global-metadata.dat") }
        val backend = when {
            il2cpp.isNotEmpty() || metadata != null -> UnityBackend.IL2CPP
            mono -> UnityBackend.MONO
            else -> null
        }

        val version = listOf("${DATA}globalgamemanagers", "${DATA}data.unity3d")
            .firstNotNullOfOrNull { name -> zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { versionIn(it.readNBytes(HEAD)) } } }
            ?: engine.firstOrNull { it.size in 1..LIB_MAX }?.let { e -> zip.getInputStream(e).use { libVersionIn(it.readBytes()) } }

        return UnityInfo(
            backend = backend,
            version = version,
            metadataPath = metadata?.name,
            metadataSize = metadata?.size?.takeIf { it >= 0 },
            metadataVersion = metadata?.let { e -> zip.getInputStream(e).use { metadataVersion(it.readNBytes(8)) } },
            il2cpp = il2cpp.sortedBy { it.abi },
            engineAbis = engine.map { it.name.split('/')[1] }.distinct().sorted(),
            assemblies = names.count { it.startsWith("${DATA}Managed/") && it.endsWith(".dll") },
        )
    }

    private fun lib(name: String, file: String): Boolean =
        name.startsWith("lib/") && name.count { it == '/' } == 2 && name.endsWith("/$file")

    internal fun metadataVersion(head: ByteArray): Int? {
        if (head.size < 8) return null
        val b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != MAGIC) return null
        return b.getInt(4)
    }

    internal fun versionIn(head: ByteArray): String? =
        VERSION.find(String(head, Charsets.ISO_8859_1))?.groupValues?.get(1)

    internal fun libVersionIn(lib: ByteArray): String? =
        LIB_VERSION.find(String(lib, Charsets.ISO_8859_1))?.groupValues?.get(1)
}
