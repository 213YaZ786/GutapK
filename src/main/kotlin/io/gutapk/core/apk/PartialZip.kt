package io.gutapk.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Reads bytes of a file that is not here, a phone's APK for one. It may
// return fewer bytes at the end of the file.
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}

class CdEntry(val name: String, val method: Int, val compressed: Long, val size: Long, val localOffset: Long)

// An APK's zip read piece by piece: its central directory from the end of
// the file, then only the entries asked for. Zip64 is not read, no APK
// that matters here is over 4 GB.
object PartialZip {
    private const val EOCD = 0x06054b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50
    private const val TAIL = 65557
    // An entry inflated past this is not an icon or a manifest.
    private const val MAX_ENTRY = 64 shl 20

    fun directory(fileSize: Long, reader: RangeReader): Map<String, CdEntry> {
        val tailStart = maxOf(0L, fileSize - TAIL)
        val tail = reader.read(tailStart, (fileSize - tailStart).toInt())
        val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var at = tail.size - 22
        while (at >= 0 && t.getInt(at) != EOCD) at--
        if (at < 0) throw ApkFormatError("no end of central directory, not a zip")
        val cdSize = t.getInt(at + 12).toLong() and 0xffffffffL
        val cdOffset = t.getInt(at + 16).toLong() and 0xffffffffL
        if (cdOffset + cdSize > fileSize || cdSize > Int.MAX_VALUE) throw ApkFormatError("central directory out of the file")
        val cd = reader.read(cdOffset, cdSize.toInt())
        return parseDirectory(cd)
    }

    internal fun parseDirectory(cd: ByteArray): Map<String, CdEntry> {
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val out = LinkedHashMap<String, CdEntry>()
        var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == CENTRAL) {
            val method = b.getShort(p + 10).toInt() and 0xffff
            val compressed = b.getInt(p + 20).toLong() and 0xffffffffL
            val size = b.getInt(p + 24).toLong() and 0xffffffffL
            val nameLen = b.getShort(p + 28).toInt() and 0xffff
            val extraLen = b.getShort(p + 30).toInt() and 0xffff
            val commentLen = b.getShort(p + 32).toInt() and 0xffff
            val local = b.getInt(p + 42).toLong() and 0xffffffffL
            if (p + 46 + nameLen > cd.size) break
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            out[name] = CdEntry(name, method, compressed, size, local)
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    fun entry(e: CdEntry, reader: RangeReader): ByteArray {
        if (e.size > MAX_ENTRY || e.compressed > MAX_ENTRY) throw ApkFormatError("${e.name} is too large to read here")
        val head = reader.read(e.localOffset, 30)
        val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (head.size < 30 || h.getInt(0) != LOCAL) throw ApkFormatError("no local header for ${e.name}")
        val skip = 30 + (h.getShort(26).toInt() and 0xffff) + (h.getShort(28).toInt() and 0xffff)
        val raw = reader.read(e.localOffset + skip, e.compressed.toInt())
        if (raw.size.toLong() != e.compressed) throw ApkFormatError("${e.name} ended early")
        return when (e.method) {
            0 -> raw
            8 -> inflate(raw, e.size.toInt())
            else -> throw ApkFormatError("${e.name} uses compression method ${e.method}")
        }
    }

    private fun inflate(raw: ByteArray, size: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(size)
            val buf = ByteArray(1 shl 16)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ENTRY) throw ApkFormatError("an entry inflates past its limit")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

// What the list of a phone's apps shows: the name and the icon, read from
// a few entries of the APK instead of the whole file. declared is false
// when the manifest names no icon: Android then shows its default one.
class RemoteIcon(val label: String?, val bitmap: ByteArray?, val art: IconArt?, val declared: Boolean)

// One APK on the phone: its size and a way to read pieces of it.
class RemoteApk(val size: Long, val reader: RangeReader)

object RemoteIcons {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val TABLE = "resources.arsc"
    private const val ANYDPI = 0xfffe
    private const val MAX_FILES = 40

    // The base first. An app installed as a split set keeps its pictures
    // per density in its config splits, split_config.xxhdpi.apk for one,
    // with a resources.arsc of their own that points into them. Those are
    // tried in order until one gives a picture. splits is only called when
    // the base has none, since it costs a call to the phone.
    fun read(base: RemoteApk, work: Path, splits: () -> List<RemoteApk> = { emptyList() }): RemoteIcon {
        val dir = PartialZip.directory(base.size, base.reader)
        val manifest = dir[MANIFEST]?.let { PartialZip.entry(it, base.reader) } ?: throw ApkFormatError("no AndroidManifest.xml")
        val app = BinaryXml.parse(manifest).firstOrNull { it.depth == 2 && it.name == "application" }
        val icon = app?.attr(Attr.ICON, "icon")?.takeIf { it.type == ValueType.REFERENCE }?.data
        val first = attempt(manifest, base, dir, icon, work)
        if (icon == null || first.bitmap != null || first.art != null) return RemoteIcon(first.label, first.bitmap, first.art, icon != null)
        for (split in splits()) {
            val found = runCatching {
                attempt(manifest, split, PartialZip.directory(split.size, split.reader), icon, work)
            }.getOrNull() ?: continue
            if (found.bitmap != null || found.art != null) return RemoteIcon(first.label, found.bitmap, found.art, true)
        }
        return RemoteIcon(first.label, null, null, true)
    }

    private class Attempt(val label: String?, val bitmap: ByteArray?, val art: IconArt?)

    // The manifest, this APK's table and the icon's files from this APK,
    // put in a small zip that ApkReader reads like any APK, so the icon is
    // chosen by the same rules as in the editor.
    private fun attempt(manifest: ByteArray, apk: RemoteApk, dir: Map<String, CdEntry>, icon: Int?, work: Path): Attempt {
        val fetched = LinkedHashMap<String, ByteArray>()
        fetched[MANIFEST] = manifest
        fun fetch(name: String): ByteArray? {
            fetched[name]?.let { return it }
            val e = dir[name] ?: return null
            return PartialZip.entry(e, apk.reader).also { fetched[name] = it }
        }
        val table = fetch(TABLE)?.let { runCatching { ResourceTable.parse(it) }.getOrNull() }
        if (table != null && icon != null) closure(icon, table, dir.keys) { fetch(it) }
        Files.createDirectories(work)
        val mini = Files.createTempFile(work, "icon", ".apk")
        try {
            ZipOutputStream(Files.newOutputStream(mini)).use { z ->
                fetched.forEach { (name, bytes) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
            val info = ApkReader.read(mini)
            val bitmap = info.iconPath?.let { p -> ZipFile(mini.toFile()).use { zip -> zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } } }
            return Attempt(info.label, bitmap, info.iconArt)
        } finally {
            Files.deleteIfExists(mini)
        }
    }

    // Bitmaps: the densest only, the one ApkReader picks. XML files: every
    // variant, then every resource they reference.
    internal fun closure(start: Int, table: ResourceTable, names: Set<String>, fetch: (String) -> ByteArray?) {
        val queue = ArrayDeque(listOf(start to 0))
        val seen = HashSet<Int>()
        var files = 0
        while (queue.isNotEmpty() && files < MAX_FILES) {
            val (id, depth) = queue.removeFirst()
            if (!seen.add(id) || depth > 4) continue
            val paths = table.strings(id).filter { it.second in names }
            paths.filter { it.second.endsWith(".png") || it.second.endsWith(".webp") }
                .maxByOrNull { if (it.first.density < ANYDPI) it.first.density else -1 }
                ?.let {
                    fetch(it.second)
                    files++
                }
            paths.filter { it.second.endsWith(".xml") }.forEach { (_, path) ->
                val bytes = fetch(path) ?: return@forEach
                files++
                val xml = runCatching { BinaryXml.parse(bytes) }.getOrNull() ?: return@forEach
                xml.flatMap { it.attrs }
                    .filter { it.type == ValueType.REFERENCE && it.data != 0 }
                    .forEach { queue.addLast(it.data to depth + 1) }
            }
        }
    }
}
